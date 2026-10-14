#!/usr/bin/env bash
# Phase 2 · DoD②「消息分支与重新生成」QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）
# 形态学 scripts/qa/run-verification.sh：kotlin-compiler-embeddable 编译 harness + java 运行。
set -euo pipefail

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SERIALIZATION=$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3/2241746853abf04073e3ab0dcd9e6729d363b313/kotlinx-serialization-json-jvm-1.6.3.jar
SER_CORE=$(find $CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm -name "kotlinx-serialization-core-jvm-*.jar" | grep -v sources | head -1)
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
# JB_ANNOTATIONS 用 QA 本地稳定副本（scripts/qa/lib/）：避免并发 Gradle 进程瞬时扰动缓存副本
JB_ANNOTATIONS=/workspace/scripts/qa/lib/annotations-23.0.0.jar

CLASSES=/workspace/app/build/tmp/kotlin-classes/debug
OUT=/tmp/qa-verify-branch
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE

rm -rf "$OUT" && mkdir -p "$OUT"

# -Xfriend-paths 让 harness 可访问 main 模块的 internal 符号（ConversationBranch / ROLE_* / toUiItems）
echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="$CLASSES" \
  -classpath "$DEPS" \
  -d "$OUT" /workspace/scripts/qa/BranchVerification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  BranchVerificationKt
