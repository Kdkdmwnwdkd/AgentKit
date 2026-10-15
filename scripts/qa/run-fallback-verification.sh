#!/usr/bin/env bash
# Phase 2 DoD⑤「能力/权限申请一次走完降级链」QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）。
# 形态学 scripts/qa/run-appcontrol-verification.sh：kotlin-compiler-embeddable 编译 harness + java 运行。
set -euo pipefail

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SERIALIZATION=$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3/2241746853abf04073e3ab0dcd9e6729d363b313/kotlinx-serialization-json-jvm-1.6.3.jar
SER_CORE=$(find $CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm -name "kotlinx-serialization-core-jvm-*.jar" | grep -v sources | head -1)
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
# JB_ANNOTATIONS 用 QA 本地稳定副本（scripts/qa/lib/）：避免并发 Gradle 进程瞬时扰动缓存副本
JB_ANNOTATIONS=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/8cc20c07506ec18e0834947b84a864bfc094484e/annotations-23.0.0.jar

CLASSES=/workspace/AgentKit/app/build/tmp/kotlin-classes/debug
OUT=/tmp/qa-verify-fallback
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$JB_ANNOTATIONS

rm -rf "$OUT" && mkdir -p "$OUT"

# -Xfriend-paths 让 harness 可访问 main 模块内部符号。
# 编译带分类重试：后端注解解析竞态可重试；前端 error: 是真实代码错误，立即失败不重试。
compile_fallback() {
  local attempt=$1 out
  out=$(java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -Xfriend-paths="$CLASSES" \
    -classpath "$DEPS" \
    -d "$OUT" /workspace/AgentKit/scripts/qa/FallbackVerification.kt 2>&1) || true
  if echo "$out" | grep -q "error:"; then
    echo "$out"; return 1
  fi
  if echo "$out" | grep -q "BackendException\|NoClassDefFoundError"; then
    echo "  (compile attempt $attempt hit backend annotation race, retrying ...)" >&2
    return 2
  fi
  echo "$out" | grep -v "^warning:" || true
  return 0
}

echo "[1/2] compiling fallback harness with kotlin-compiler-embeddable ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e
  compile_fallback "$attempt"
  crc=$?
  set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: fallback harness compile keeps hitting backend annotation race after 5 attempts" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  FallbackVerificationKt
