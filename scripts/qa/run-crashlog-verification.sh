#!/usr/bin/env bash
# 真机第四轮（A 崩溃捕获落盘 CrashLogger + B 屏幕捕捉再加固）QA 行为验证：
# 编译 + 运行（离线，仅用 Gradle 缓存内构件）。
# 形态学 scripts/qa/run-hotfix3-verification.sh：kotlin-compiler-embeddable + -Xfriend-paths + main 手写断言。
set -euo pipefail

# W-39：仓库根目录由脚本位置推导，不再硬编码 /workspace/AgentKit
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
COROUTINES=$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SERIALIZATION=$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3/2241746853abf04073e3ab0dcd9e6729d363b313/kotlinx-serialization-json-jvm-1.6.3.jar
SER_CORE=$(find $CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm -name "kotlinx-serialization-core-jvm-*.jar" | grep -v sources | head -1)
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
# JB_ANNOTATIONS 用 QA 本地稳定副本（scripts/qa/lib/）：避免并发 Gradle 进程瞬时扰动缓存副本
JB_ANNOTATIONS=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/8cc20c07506ec18e0834947b84a864bfc094484e/annotations-23.0.0.jar

CLASSES=${AGENTKIT_CLASSES:-$ROOT/app/build/tmp/kotlin-classes/debug}
# W-39：集成型 harness 需要 AGP 编译产物。缺失时明确跳过（exit 3），
# 绝不把「环境不具备」伪装成「验证失败」，也不伪装成通过。
if [ ! -d "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin，或用 AGENTKIT_CLASSES 指向已有目录" >&2
  exit 3
fi

OUT=/tmp/qa-verify-crashlog
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="$CLASSES" \
  -classpath "$DEPS" \
  -d "$OUT" $ROOT/scripts/qa/CrashLogVerification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  CrashLogVerificationKt
