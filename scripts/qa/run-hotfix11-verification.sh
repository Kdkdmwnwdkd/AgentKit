#!/usr/bin/env bash
# hotfix11「对话页模型快速切换」QA 源码级验证：
# 当前模型指示条 + 一键切换弹窗 + VM 即时生效根基。
# 形态学 scripts/qa/run-capture-path-verification.sh（纯源码扫描，不加载 Android 类）。
set -euo pipefail

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.25/6e698f23ca1f74d6672827f61e92c2fe015d1b88/kotlin-compiler-embeddable-1.9.25.jar
STDLIB=$CACHE/org.jetbrains.kotlin/kotlin-stdlib/1.9.25/f700a2f2b8f0d6d0fde48f56d894dc722fb029d7/kotlin-stdlib-1.9.25.jar
TROVE=$CACHE/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
JB_ANNOTATIONS=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/8cc20c07506ec18e0834947b84a864bfc094484e/annotations-23.0.0.jar

CLASSES=/workspace/AgentKit/app/build/tmp/kotlin-classes/debug
OUT=/tmp/qa-verify-hotfix11
DEPS=$CLASSES:$STDLIB

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$DEPS" \
  -d "$OUT" /workspace/AgentKit/scripts/qa/Hotfix11Verification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  Hotfix11VerificationKt
