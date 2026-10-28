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
expect "FlashAttention 已开启" "$JNI" 'cparams\.flash_attn[[:space:]]*=[[:space:]]*true'

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

echo
if [ "$fail" -eq 0 ]; then
  # 汇总行要带 FAIL=0：run-all.sh 用它识别「跑完且全过」（RESULT PASS 不认）
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
