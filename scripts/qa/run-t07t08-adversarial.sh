#!/usr/bin/env bash
# Phase1 · T07/T08 + 应用操控补口 · QA 对抗性补充探针（Edward）：编译 + 运行（离线，仅用 Gradle 缓存内构件）
set -euo pipefail

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SERIALIZATION=$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3/2241746853abf04073e3ab0dcd9e6729d363b313/kotlinx-serialization-json-jvm-1.6.3.jar
SER_CORE=$(find $CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm -name "kotlinx-serialization-core-jvm-*.jar" | grep -v sources | head -1)
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
JB_ANNOTATIONS=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/8cc20c07506ec18e0834947b84a864bfc094484e/annotations-23.0.0.jar

CLASSES=/workspace/AgentKit/app/build/tmp/kotlin-classes/debug
OUT=/tmp/qa-verify-t07t08-probe
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$JB_ANNOTATIONS

rm -rf "$OUT" && mkdir -p "$OUT"

compile_probe() {
  local attempt=$1 out
  out=$(java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -Xfriend-paths="$CLASSES" \
    -classpath "$DEPS" \
    -d "$OUT" /workspace/AgentKit/scripts/qa/T07T08AdversarialProbe.kt 2>&1) || true
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

echo "[1/2] compiling adversarial probe with kotlin-compiler-embeddable ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e; compile_probe "$attempt"; crc=$?; set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: probe compile keeps hitting backend annotation race after 5 attempts" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" T07T08AdversarialProbeKt
