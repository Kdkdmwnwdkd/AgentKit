#!/usr/bin/env bash
# hotfix11「对话页模型快速切换」QA 源码级验证：
# 当前模型指示条 + 一键切换弹窗 + VM 即时生效根基。
# 形态学 scripts/qa/run-capture-path-verification.sh（纯源码扫描，不加载 Android 类）。
set -euo pipefail

# W-39：仓库根目录由脚本位置推导，不再硬编码 /workspace/AgentKit
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE=/root/.gradle/caches/modules-2/files-2.1
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains/annotations" "annotations-*.jar")

# W-40：Gradle 缓存缺失时回落本机 kotlinc 发行版（沙箱/净机器都能跑）
[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"

CLASSES=${AGENTKIT_CLASSES:-$ROOT/app/build/tmp/kotlin-classes/debug}
# W-39：集成型 harness 需要 AGP 编译产物。缺失时明确跳过（exit 3），
# 绝不把「环境不具备」伪装成「验证失败」，也不伪装成通过。
if [ ! -d "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin，或用 AGENTKIT_CLASSES 指向已有目录" >&2
  exit 3
fi

OUT=/tmp/qa-verify-hotfix11
DEPS=$CLASSES:$STDLIB

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$DEPS" \
  -d "$OUT" $ROOT/scripts/qa/Hotfix11Verification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  Hotfix11VerificationKt
