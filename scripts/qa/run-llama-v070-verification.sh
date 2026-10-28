#!/usr/bin/env bash
# llama.cpp 版本升级 harness（b4600 → v0.7.0）。
#
# 为什么要有这个脚本：升级底座这件事，一旦「升级了但某处没跟上」是**静默失败**的——
# 比如 fetch 脚本的 tag 改了、CMake 的 repack 开关还用老名字（新名字默认 ON，于是
# 权重重排被悄悄打开，首次 decode 卡死重现），或者某处还直接调着已经删掉的
# `llama_kv_cache_clear`（编译期才发现，CI 一轮 10 分钟）。
#
# 这些都能用静态断言在推送前守住，所以这里锁四类契约：
#   1. 拉的是哪个版本（tag + 钉死 commit，缺一个都可复现性失效）
#   2. API 兼容层在不在（新旧两套接口都得有分支，否则回退 tag 时要重写 C++）
#   3. 调用点是否全部走兼容层（残留一处直接调用 = 编不过）
#   4. 编译开关是否按新名字设了（老名字在 v0.7.0 是无效变量）
#
# 只读源码，不需要编译器、不需要 APK、不需要网络。
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
JNI="$ROOT/app/src/main/cpp/llama-jni.cpp"
CMAKE="$ROOT/app/src/main/cpp/CMakeLists.txt"
FETCH="$ROOT/scripts/fetch-llama.sh"
CI="$ROOT/.github/workflows/build.yml"
DEPS="$ROOT/docs/DEPENDENCIES.md"

pass=0
fail=0
failed=""

ok()  { pass=$((pass + 1)); echo "  PASS  $1"; }
bad() { fail=$((fail + 1)); failed="$failed
  - $1"; echo "  FAIL  $1"; }

expect() {
  local desc="$1" file="$2" pattern="$3"
  if [ ! -f "$file" ]; then bad "$desc（文件缺失：${file#$ROOT/}）"; return; fi
  if grep -Eq "$pattern" "$file"; then ok "$desc"; else bad "$desc（在 ${file#$ROOT/} 里找不到 /$pattern/）"; fi
}

reject() {
  local desc="$1" file="$2" pattern="$3"
  if [ -f "$file" ] && grep -Eq "$pattern" "$file"; then
    bad "$desc（${file#$ROOT/} 里仍有 /$pattern/）"
  else ok "$desc"; fi
}

echo "[1/4] 版本钉定"
expect "fetch 脚本拉 v0.7.0"        "$FETCH" 'TAG="v0\.7\.0"'
expect "钉死 v0.7.0 的 commit"      "$FETCH" 'EXPECTED_COMMIT="620d2225cc436ab0b001e3200e590e3b286ef9ab"'
reject "没有残留 b4600 tag"          "$FETCH" 'TAG="b4600"'
# 缓存 key 带版本：key 不变的话 CI 会拿到旧源码，编译出来还是老版本（且不报错）
expect "CI 缓存 key 带新版本"        "$CI" 'llama-src-v070-'
reject "CI 缓存 key 没有旧版本"      "$CI" 'llama-src-b4600-'

echo "[2/4] API 兼容层（新旧双分支）"
expect "兼容开关按 SESSION_VERSION 判定" "$JNI" 'LLAMA_SESSION_VERSION >= 10'
expect "KV 清空走 memory 新接口"     "$JNI" 'llama_memory_clear\('
expect "KV 清空保留旧接口分支"       "$JNI" 'llama_kv_cache_clear\('
expect "KV 回退走 memory 新接口"     "$JNI" 'llama_memory_seq_rm\('
expect "KV 回退保留旧接口分支"       "$JNI" 'llama_kv_cache_seq_rm\('
expect "FlashAttention 走三态枚举"   "$JNI" 'LLAMA_FLASH_ATTN_TYPE_DISABLED'
expect "FlashAttention 保留布尔分支" "$JNI" 'flash_attn = false'
expect "映射方式走 load_mode 枚举"   "$JNI" 'LLAMA_LOAD_MODE_MMAP'
expect "映射方式保留 use_mmap 分支"  "$JNI" 'use_mmap = true'
# lazy_mode 是 v0.7.0 才有的，必须显式钉 OFF：它的默认值随版本变，不钉就是隐患
expect "lazy_mode 显式钉 OFF"        "$JNI" 'LLAMA_LAZY_MODE_OFF'
expect "惩罚采样器带 n_vocab 参数"   "$JNI" 'llama_vocab_n_tokens\(vocab\), last_n'

echo "[3/4] 调用点全部改走兼容层"
expect "KV 清空调用走 akKvClear"     "$JNI" 'akKvClear\(inst->ctx\)'
reject "没有直接调 llama_kv_cache_clear(ctx)" "$JNI" 'llama_kv_cache_clear\(inst->ctx\)'
reject "没有直接调 llama_kv_cache_seq_rm(ctx" "$JNI" 'llama_kv_cache_seq_rm\(inst->ctx'
expect "惩罚采样器调用走封装"        "$JNI" 'akSamplerPenalties\(inst->vocab'
# 兼容层内部当然要调上游函数，这里只拒绝「绕过封装直接串进采样链」的写法——
# 那种写法的参数个数会随版本变，正是这次踩到的坑。
reject "采样链没有绕过封装直接构造" "$JNI" 'llama_sampler_chain_add\(smpl, llama_sampler_init_penalties'

echo "[4/4] 编译开关按新名字设"
# v0.7.0 把 repack 改名为 GGML_CPU_REPACK 且**默认 ON**。只设老名字 = 等于没关，
# 首次 decode 卡死的老问题会原样复现，而且编译日志里一个字都不会提。
expect "新名 GGML_CPU_REPACK=OFF"    "$CMAKE" 'GGML_CPU_REPACK[[:space:]]+OFF'
expect "老名 GGML_CPU_AARCH64=OFF"   "$CMAKE" 'GGML_CPU_AARCH64[[:space:]]+OFF'
expect "KleidiAI 显式关（新增依赖）" "$CMAKE" 'GGML_CPU_KLEIDIAI[[:space:]]+OFF'
expect "ARM 架构标识未被改动"        "$CMAKE" 'armv8\.2-a\+dotprod\+fp16'
expect "依赖台账已记录新版本"        "$DEPS" 'v0\.7\.0.*620d2225cc436ab0b001e3200e590e3b286ef9ab'

echo
if [ "$fail" -eq 0 ]; then
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
