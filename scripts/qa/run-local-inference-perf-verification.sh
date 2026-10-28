#!/usr/bin/env bash
# W-69 harness：本地推理提速的三条硬契约（纯静态，只读源码，不需要编译器/APK）。
#
# 为什么用静态断言而不是行为 harness：这三条都落在 native（C++/CMake）与
# 「有没有覆盖 flow 接口」上，跑起来需要真机 + 模型文件。CI 里能守住的只有
# 「实现还在」——防止后续重构把增量 prefill 悄悄改回全量、把编译档位改回基线。
#
# 断言只锁契约，不锁写法：
#   1. 增量 prefill：KV 有账本（kvTokens）+ 能按公共前缀回退（llama_kv_cache_seq_rm）
#   2. 编译档位：GGML_CPU_ARM_ARCH 含 dotprod（基线 armv8-a 会让 Q4 点积退化）
#   3. 加载前自检：缺 asimddp/asimdhp 时必须拦在 dlopen 之前（否则 SIGILL 崩溃）
#   4. 本地推理必须走流式：LocalLlamaClient 覆盖 streamChat，否则整段憋完才上屏
#   5. 生成预算受窗口约束：不能拿 maxTokens 裸跑，否则长对话必然 decode 失败
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)

JNI="$ROOT/app/src/main/cpp/llama-jni.cpp"
CMAKE="$ROOT/app/src/main/cpp/CMakeLists.txt"
LOADER="$ROOT/app/src/main/java/com/example/agentkit/core/llm/local/LlamaJni.kt"
CLIENT="$ROOT/app/src/main/java/com/example/agentkit/core/llm/local/LocalLlamaClient.kt"
LOOP="$ROOT/app/src/main/java/com/example/agentkit/core/agent/RealAgentLoop.kt"
VM="$ROOT/app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt"

pass=0
fail=0
failed=""

ok() {
  pass=$((pass + 1))
  echo "  PASS  $1"
}

bad() {
  fail=$((fail + 1))
  failed="$failed
  - $1"
  echo "  FAIL  $1"
}

# expect <描述> <文件> <正则>
expect() {
  local desc="$1" file="$2" pattern="$3"
  if [ ! -f "$file" ]; then
    bad "$desc（文件缺失：${file#$ROOT/}）"
    return
  fi
  if grep -Eq "$pattern" "$file"; then
    ok "$desc"
  else
    bad "$desc（在 ${file#$ROOT/} 里找不到 /$pattern/）"
  fi
}

# reject <描述> <文件> <正则>
reject() {
  local desc="$1" file="$2" pattern="$3"
  if [ -f "$file" ] && grep -Eq "$pattern" "$file"; then
    bad "$desc（${file#$ROOT/} 里仍有 /$pattern/）"
  else
    ok "$desc"
  fi
}

echo "[1/4] 增量 prefill（native）"
expect "KV 账本存在" "$JNI" 'std::vector<llama_token>[[:space:]]+kvTokens'
expect "按公共前缀回退 KV" "$JNI" 'llama_kv_cache_seq_rm\('
expect "只补算差异段" "$JNI" 'decodeFrom\(inst, tokens, common\)'
expect "失败路径重置会话" "$JNI" 'resetKv\(inst,'
# 断言的是「显式设定」而非「开启」：FA 在 CPU 后端按 head_dim/量化类型分派，
# 个别组合会让 decode 直接失败（真机表现为一个字不出）。开启与否是调优决定，
# 契约是「不能依赖默认值、必须写明」。
expect "FlashAttention 显式设定" "$JNI" 'cparams\.flash_attn[[:space:]]*=[[:space:]]*(true|false)'
expect "显式 pos 构造 batch（不依赖 get_one 隐式续号）" "$JNI" 'batch\.pos\[i\][[:space:]]*=[[:space:]]*pos0'

echo "[2/4] 编译档位（CMake）"
expect "ARM 架构档位已显式指定" "$CMAKE" 'GGML_CPU_ARM_ARCH'
expect "启用 dotprod" "$CMAKE" 'armv8\.2-a\+dotprod'
reject "不再关掉 CPU 变体" "$CMAKE" 'GGML_CPU_ALL_VARIANTS ON'

echo "[3/4] 加载前 CPU 自检（Kotlin）"
expect "检测 asimddp" "$LOADER" 'asimddp'
expect "检测 asimdhp" "$LOADER" 'asimdhp'
expect "缺指令集时不 dlopen" "$LOADER" 'missingCpuFeatures\(\)\?\.let'

echo "[4/4] 流式与窗口预算"
expect "本地客户端覆盖 streamChat" "$CLIENT" 'override fun streamChat\('
expect "工具调用 JSON 不逐字外泄" "$CLIENT" 'looksLikeToolCall'
expect "生成预算受窗口约束" "$JNI" 'genBudget'

echo "[5/5] 失败不许静默（真机「发了消息没反应」）"
expect "空输出抛本地推理异常" "$CLIENT" 'LocalInferenceException\('
expect "空输出前先落 native 诊断" "$CLIENT" 'nativeDiagnostics\(\)'
expect "本地异常不参与退避重试" "$LOOP" 'LocalInferenceException -> false'
expect "本地异常不被译成网络提示" "$VM" 'as\? com\.example\.agentkit\.core\.llm\.local\.LocalInferenceException'

echo "[6/6] 分词预算查询（llama_tokenize 返回负数 = 「需要多少」，不是失败）"
# 这条是真机上「模型配好了但发不出去」的真凶：第一趟传 nullptr/0 问需求，
# llama.cpp 装不下时返回 **-所需数**；写成 `n <= 0 → 失败` 会让每一个非空
# prompt 在分词阶段就判死（加载成功、80ms 报错、一个字不出）。
expect "负数按「需要多少」转换" "$JNI" 'need = n < 0 \? -n : n'
expect "第二趟仍不够时按新需求重试" "$JNI" 'need = -got'
reject "不再把负返回值当分词失败" "$JNI" 'if \(n <= 0\) return false;'

echo
if [ "$fail" -eq 0 ]; then
  # 汇总行要带 FAIL=0：run-all.sh 用它识别「跑完且全过」（RESULT PASS 不认）
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
