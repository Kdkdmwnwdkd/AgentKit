#!/usr/bin/env bash
# Phase1 T01（Agent 运行时内核抽象）QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）
set -euo pipefail

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SERIALIZATION=$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3/2241746853abf04073e3ab0dcd9e6729d363b313/kotlinx-serialization-json-jvm-1.6.3.jar
SER_CORE=$(find $CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm -name "kotlinx-serialization-core-jvm-*.jar" | grep -v sources | head -1)
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
JB_ANNOTATIONS=$CACHE/org.jetbrains/annotations/23.0.0/8cc20c07506ec18e0834947b84a864bfc094484e/annotations-23.0.0.jar
# Round1 IO 调度结构断言用：本地 OkHttp 客户端 + JDK HttpServer 端点（均来自 Gradle 离线缓存）
OKHTTP=$CACHE/com.squareup.okhttp3/okhttp/4.12.0/2f4525d4a200e97e1b87449c2cd9bd2e25b7e8cd/okhttp-4.12.0.jar
OKIO=$CACHE/com.squareup.okio/okio-jvm/3.6.0/5600569133b7bdefe1daf9ec7f4abeb6d13e1786/okio-jvm-3.6.0.jar

CLASSES=/workspace/AgentKit/app/build/tmp/kotlin-classes/debug
OUT=/tmp/qa-verify-t01
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$OKHTTP:$OKIO

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="$CLASSES" \
  -classpath "$DEPS" \
  -d "$OUT" /workspace/AgentKit/scripts/qa/T01Verification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  T01VerificationKt
