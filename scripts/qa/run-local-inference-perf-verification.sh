#!/usr/bin/env bash
# W-69 harness：本地推理提速的三条硬契约（纯静态，只读源码，不需要编译器/APK）。
#
# 为什么用静态断言而不是行为 harness：这三条都落在 native（C++/CMake）与
# 「有没有覆盖 flow 接口」上，跑起来需要真机 + 模型文件。CI 里能守住的只有
# 「实现还在」——防止后续重构把增量 prefill 悄悄改回全量、把编译档位改回基线。
#
# 断言只锁契约，不锁写法：
#   1. 增量 prefill：KV 有账本（kvTokens）+ 能按公共前缀回退（akKvSeqRm 兼容层）
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
SETTING="$ROOT/app/src/main/java/com/example/agentkit/core/llm/local/LocalLlamaSetting.kt"
ABOUT="$ROOT/app/src/main/java/com/example/agentkit/ui/AboutScreen.kt"

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

echo "[1/10] 增量 prefill（native）"
expect "KV 账本存在" "$JNI" 'std::vector<llama_token>[[:space:]]+kvTokens'
expect "按公共前缀回退 KV" "$JNI" 'akKvSeqRm\('
# v0.7.0 起 KV 接口并进 memory 抽象；两个名字都得在（新版走前者，回退分支走后者）。
expect "KV 回退走 memory 新接口" "$JNI" 'llama_memory_seq_rm\('
expect "KV 回退保留旧接口分支" "$JNI" 'llama_kv_cache_seq_rm\('
# 起点从公共前缀之后开始（common==0 时预热探针会先吃掉第 0 个 token，
# 所以用 prefillFrom 承载「这一轮从哪继续」，语义仍是「只补算差异段」）。
expect "只补算差异段" "$JNI" 'decodeFrom\(inst, tokens, prefillFrom,'
expect "失败路径重置会话" "$JNI" 'resetKv\(inst,'
# 断言的是「显式设定」而非「开启」：FA 在 CPU 后端按 head_dim/量化类型分派，
# 个别组合会让 decode 直接失败（真机表现为一个字不出）。开启与否是调优决定，
# 契约是「不能依赖默认值、必须写明」。
expect "FlashAttention 显式设定" "$JNI" 'cparams\.flash_attn[[:space:]]*=[[:space:]]*(true|false)'
expect "显式 pos 构造 batch（不依赖 get_one 隐式续号）" "$JNI" 'batch\.pos\[i\][[:space:]]*=[[:space:]]*pos0'

echo "[2/10] 编译档位（CMake）"
expect "ARM 架构档位已显式指定" "$CMAKE" 'GGML_CPU_ARM_ARCH'
expect "启用 dotprod" "$CMAKE" 'armv8\.2-a\+dotprod'
# aarch64 repack 把权重懒重排塞进第一次 decode：真机表现是「加载 5 秒正常，
# 之后 158 token 的 prefill 卡满 180 秒没有任何进度」。宁可少 10% 峰值也不能不出字。
# repack 在 v0.7.0 改了名（GGML_CPU_AARCH64 → GGML_CPU_REPACK），默认值都是 ON，
# 必须显式关；两个名字都留着，回退 tag 时不用再改 CMake。
expect "关掉权重重排（新版开关名）" "$CMAKE" 'GGML_CPU_REPACK[[:space:]]+OFF'
expect "关掉权重重排（旧版开关名）" "$CMAKE" 'GGML_CPU_AARCH64[[:space:]]+OFF'
# 真机 1 token 要 24.5 秒的根因：AGP 给 debug 构建传 CMAKE_BUILD_TYPE=Debug，
# 而 CMake 的 Debug flags 只有 -g（= -O0）。ggml 在 -O0 下点积退化成标量循环。
expect "Debug 配置也强制 -O3" "$CMAKE" 'CMAKE_\$\{_lang\}_FLAGS_DEBUG "-O3'
expect "给 ggml 每个 target 追加 -O3" "$CMAKE" 'target_compile_options\(\$\{_t\} PRIVATE -O3\)'
expect "build type 强制 Release" "$CMAKE" 'set\(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE\)'
reject "不再关掉 CPU 变体" "$CMAKE" 'GGML_CPU_ALL_VARIANTS ON'

echo "[3/10] 加载前 CPU 自检（Kotlin）"
expect "检测 asimddp" "$LOADER" 'asimddp'
expect "检测 asimdhp" "$LOADER" 'asimdhp'
expect "缺指令集时不 dlopen" "$LOADER" 'missingCpuFeatures\(\)\?\.let'

echo "[4/10] 流式与窗口预算"
expect "本地客户端覆盖 streamChat" "$CLIENT" 'override fun streamChat\('
expect "工具调用 JSON 不逐字外泄" "$CLIENT" 'looksLikeToolCall'
expect "生成预算受窗口约束" "$JNI" 'genBudget'

echo "[5/10] 失败不许静默（真机「发了消息没反应」）"
expect "空输出抛本地推理异常" "$CLIENT" 'LocalInferenceException\('
expect "空输出前先落 native 诊断" "$CLIENT" 'nativeDiagnostics\(\)'
expect "本地异常不参与退避重试" "$LOOP" 'LocalInferenceException -> false'
expect "本地异常不被译成网络提示" "$VM" 'as\? com\.example\.agentkit\.core\.llm\.local\.LocalInferenceException'

echo "[6/10] 分词预算查询（llama_tokenize 返回负数 = 「需要多少」，不是失败）"
# 这条是真机上「模型配好了但发不出去」的真凶：第一趟传 nullptr/0 问需求，
# llama.cpp 装不下时返回 **-所需数**；写成 `n <= 0 → 失败` 会让每一个非空
# prompt 在分词阶段就判死（加载成功、80ms 报错、一个字不出）。
expect "负数按「需要多少」转换" "$JNI" 'need = n < 0 \? -n : n'
expect "第二趟仍不够时按新需求重试" "$JNI" 'need = -got'
reject "不再把负返回值当分词失败" "$JNI" 'if \(n <= 0\) return false;'

echo "[7/10] 慢要看得见（「等了几分钟还是一片空白」）"
# 这一组守的是「过程可见 + 等待有上限」。真机上 3B 模型的 prefill 本来就要几十秒到
# 几分钟，用户能忍「慢」，不能忍「不知道在不在动」。
expect "prefill 分块进度可观测" "$JNI" 'prefill 进度：'
expect "生成过程有进度打点" "$JNI" '生成进度：'
expect "prefill 可被停止打断" "$JNI" 'prefill 被用户停止'
expect "取消/卡死有等待上限" "$CLIENT" 'firstTokenBudgetMs'
expect "native 进度实时转入运行日志" "$CLIENT" 'NativeProgressTap'
expect "工具 JSON 不会无限憋着不出字" "$CLIENT" 'TOOL_JSON_HOLD_MAX_CHARS'
expect "重复惩罚（防原地循环）" "$JNI" 'llama_sampler_init_penalties'

echo "[8/10] 本地轻聊档（prompt 长度决定首字延迟）"
# 真机对照：同一台手机同一模型，7 token 的 prompt 首字 4.8 秒，
# 1086 token 的 prompt 180 秒算不完 prefill。所以「默认发多少」必须锁死。
expect "Agent 模式默认关" "$SETTING" 'KEY_AGENT_MODE, false'
expect "轻聊档不注入工具协议" "$CLIENT" 'EffectivePrompt\(lightMessages\(messages\), emptyList\(\)\)'
expect "轻聊档剥掉设备上下文" "$CLIENT" '用户当前请求：'
# W-93：轻聊档不再截云端提示的前几行——那份提示写的是「你可以调用工具、可操作界面」，
# 而轻聊档没有工具，模型据此答出「可以控制智能家居」（真机 2026-10-29 15:33）。
expect "轻聊档改用本地能力声明" "$CLIENT" 'LocalChatPrompt\.TEXT'
expect "能力声明写清「没有联网/不能操作」" \
  app/src/main/java/com/example/agentkit/core/llm/local/LocalChatPrompt.kt '没有联网，也不能操作手机或家电'
expect "能力声明给了「不能做」清单" \
  app/src/main/java/com/example/agentkit/core/llm/local/LocalChatPrompt.kt '不能做'
if grep -q 'LIGHT_SYSTEM_MAX_CHARS' "$CLIENT"; then
  bad "不再按字数截断云端系统提示（已改成本地能力声明）"
else
  ok "不再按字数截断云端系统提示（已改成本地能力声明）"
fi
# W-93：标题生成必须排在本轮结束之后。原来在主回答之前发起，本地模型下两个 prefill
# 交错抢 CPU（15:33 那轮首字 9709ms）。用行号比较，比匹配文案可靠。
_t_call=$(grep -n 'maybeGenerateTitle(_activeConversationId.value, text)' "$VM" | head -1 | cut -d: -f1)
_t_run=$(grep -n 'val outcome = runCatching { loop.run' "$VM" | head -1 | cut -d: -f1)
if [ -n "$_t_call" ] && [ -n "$_t_run" ] && [ "$_t_call" -gt "$_t_run" ]; then
  ok "标题生成排在本轮结束之后（不抢 prefill）"
else
  bad "标题生成排在本轮结束之后（调用行=${_t_call:-?} 运行行=${_t_run:-?}）"
fi

echo "[9/10] 停止与状态一致性（「点了停止没反应」「答案原样重复一遍」）"
# 中断回调：只靠「分块之间查 stop」，慢设备上要等一整块（几百 token）才生效。
expect "decode 内部可中断" "$JNI" 'cparams.abort_callback = &abortCb'
expect "重建上下文后仍可中断" "$JNI" 'cp.abort_callback = &abortCb'
# 中止 ≠ 失败：中止时 KV 只对齐、不清空，否则前面几千 token 白算。
expect "中止不清空 KV" "$JNI" 'if \(!inst->stop.load\(\)\) resetKv'
# 新 prompt 是 KV 严格前缀时，必须回退一个 token，否则采样读到上一轮的旧 logits。
expect "整段命中时回退一个 token" "$JNI" 'common == tokens.size\(\) && common > 0'
# 采样链顺序：惩罚先于截断、温度最后。
expect "top-k 截长尾" "$JNI" 'llama_sampler_init_top_k\(40\)'
expect "min-p 截长尾" "$JNI" 'llama_sampler_init_min_p'
# 线程默认值：8 核机不能只给 4 线程（真机 2~3 tok/s 的直接原因）。
expect "线程默认随核数上浮" "$SETTING" 'coerceIn\(4, 6\)'
# 后端能力必须自报，否则「慢」永远只能靠猜。
expect "后端能力写进诊断" "$JNI" 'llama_print_system_info'

echo "[10/10] 引擎自检（把「算得慢」和「卡死」分开）"
# 连续三版都是「模型加载成功、然后一句也不出」，而日志里只有结束行，分不清
# 是没进 decode、卡在 decode 内部、还是真的算得慢。这一组锁住三件让下次
# 一份日志就能定案的事。
expect "有单 token 预热探针" "$JNI" 'warmupDecode'
expect "预热超阈值自动降线程重试" "$JNI" 'ms > WARMUP_SLOW_MS && inst->n_threads > 4'
# 慢一次先复测：真机 13436 ms → 170 ms 的差距就是「一次性成本被当成线程问题」。
expect "预热慢一次先复测再判定" "$JNI" 'again < ms'
# 降线程不能重建上下文：重建会清 KV，且要付一次分配。
expect "降线程用 llama_set_n_threads 就地生效" "$JNI" 'llama_set_n_threads'
expect "prefill 前留下「开始」痕迹" "$JNI" 'prefill 开始：'
expect "prefill 分块小于逻辑批（进度不被大块吞掉）" "$JNI" 'std::min\(inst->n_batch, PREFILL_CHUNK\)'
# 冷启动成本（2 GB 权重首次缺页 + 重排）必须在加载阶段付掉，不能落在用户发消息之后。
expect "冷启动预热放在 nativeInit 内" "$JNI" '冷启动预热完成'
# mmap 只建映射不读数据，先做一次异步预读能把首次 decode 的缺页摊薄。
expect "加载前对模型文件做 fadvise 预读" "$JNI" 'POSIX_FADV_WILLNEED'
# prefill 与生成本来是两种开销结构，不能钉成同一个线程数。
# W-90：prefill 不再拿「硬件核数 +2」去凑线程。手机是 4 大 + 4 小，多出来的 2 个线程
# 落在小核上，ggml 的 spin-wait barrier 每次都要等最慢那个，prefill 于是比逐 token 生成还慢。
# 断言只锁语义：两个入口（首次 init / 自愈重建）都必须让 batch 线程等于生成线程。
expect "prefill 线程与生成同口径（不摊小核）" "$JNI" 'cparams\.n_threads_batch = nThreads;'
if grep -Eq 'n_threads_batch *= *std::min\(hwCores' "$JNI"; then
  bad "没有残留的「硬件核数 +2」写法（自愈重建那条路径也会摊上小核）"
else
  ok "没有残留的「硬件核数 +2」写法（自愈重建那条路径也会摊上小核）"
fi
# 进程级诊断缓冲跨请求累积，不加序号就会「复读上一轮」，看门狗还会误判。
expect "native 日志带全局序号" "$JNI" 'g_diagSeq'
expect "Kotlin 侧按序号只消费本轮日志" "$CLIENT" 'nativeLogSeq'
expect "首字预算由预热耗时决定" "$CLIENT" 'warmupMs in 1\.\.WARMUP_SLOW_HINT_MS'
expect "生成中途卡住也有上限" "$CLIENT" 'GEN_STALL_TIMEOUT_MS'
# 编译优化级别必须在运行时自报，否则「慢 50 倍」永远只能靠猜
expect "运行时自报是否带 -O 优化" "$JNI" '__OPTIMIZE__'
# 「这一版什么时候出的」要按北京时间显示，不能拿 CI 的 UTC 糊弄
expect "关于页构建时间固定北京时间" "$ABOUT" 'Asia/Shanghai'

echo
if [ "$fail" -eq 0 ]; then
  # 汇总行要带 FAIL=0：run-all.sh 用它识别「跑完且全过」（RESULT PASS 不认）
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
