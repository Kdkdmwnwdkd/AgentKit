#!/usr/bin/env bash
# Phase 2 · T11「聊天消息 Markdown 渲染」QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）
set -euo pipefail

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
# JB_ANNOTATIONS 用 QA 本地稳定副本（scripts/qa/lib/）：避免并发 Gradle 进程瞬时扰动缓存副本
JB_ANNOTATIONS=/workspace/scripts/qa/lib/annotations-23.0.0.jar

CLASSES=/workspace/app/build/tmp/kotlin-classes/debug
OUT=/tmp/qa-verify-markdown
DEPS=$CLASSES:$STDLIB:$COROUTINES:$JB_ANNOTATIONS

rm -rf "$OUT" && mkdir -p "$OUT"

# -Xfriend-paths 让 harness 可访问 main 模块的 internal 符号；解析器本身为 public 纯函数。
echo "[1/2] compiling markdown harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="$CLASSES" \
  -classpath "$DEPS" \
  -d "$OUT" /workspace/scripts/qa/MarkdownVerification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  MarkdownVerificationKt
