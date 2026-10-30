#!/usr/bin/env bash
# 内核 harness：编译 + 运行（纯 JVM，不加载 Android 类、不联网）。
#
# 编译单元刻意只有 6 个文件（core/kernel/*.kt）+ 1 个 harness：
# 如果哪天内核偷偷 import 了 Android 或某个工具实现，这里会第一个编译失败——
# 这就是「内核不依赖上层」这条约束的守卫。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-kernel-core
KERNEL_DIR="$ROOT/app/src/main/java/com/example/agentkit/core/kernel"

: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"

find_jar() { find "$CACHE/$1" -name "$2" 2>/dev/null | grep -v sources | head -1 || true; }

KOTLINC_JAR=$(find_jar "org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(find_jar "org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(find_jar "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "*.jar")
TROVE=$(find_jar "org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(find_jar "org.jetbrains/annotations" "annotations-*.jar")

# 沙箱回落：完整 kotlinc 发行版
if [ -z "${KOTLINC_JAR:-}" ] || [ -z "${STDLIB:-}" ]; then
  KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
  if [ -x "$KC_HOME/bin/kotlinc" ]; then
    KOTLINC_BIN="$KC_HOME/bin/kotlinc"
    STDLIB=${STDLIB:-$KC_HOME/lib/kotlin-stdlib.jar}
  fi
fi

# 环境不具备 → exit 3（SKIP），与仓库其它 harness 一致。
# 不能用 exit 1：run-all.sh 把非 0 非 3 视为 FAIL，会让"没有编译器"这种环境问题
# 伪装成"断言失败"，把 CI 门禁堵死。
if [ -z "${KOTLINC_BIN:-}" ] && [ -z "${KOTLINC_JAR:-}" ]; then
  echo "SKIP: 找不到 kotlinc（Gradle 缓存与本机发行版均缺失）" >&2
  exit 3
fi
if [ -z "${COROUTINES:-}" ] || [ ! -f "$COROUTINES" ]; then
  echo "SKIP: 缺少 kotlinx-coroutines-core-jvm（内核 harness 用 runBlocking）" >&2
  exit 3
fi

DEPS=$STDLIB:$COROUTINES
[ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && DEPS=$DEPS:$JB_ANNOTATIONS

SOURCES=$(ls "$KERNEL_DIR"/*.kt | sort)
if [ -z "$SOURCES" ]; then echo "FATAL: $KERNEL_DIR 下没有源码" >&2; exit 1; fi

rm -rf "$OUT" && mkdir -p "$OUT"

compile_once() {
  local out
  if [ -n "${KOTLINC_BIN:-}" ]; then
    out=$("$KOTLINC_BIN" -classpath "$DEPS" -d "$OUT" $SOURCES "$ROOT/scripts/qa/KernelCoreVerification.kt" 2>&1) || true
  else
    # 编译器自身（K2 前端）依赖 coroutines，必须也放进它的 classpath，
    # 否则会以 ClassNotFoundException: kotlinx.coroutines.CoroutineScope 崩掉。
    local kcp="$KOTLINC_JAR:$STDLIB:$COROUTINES"
    [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ] && kcp=$kcp:$TROVE
    [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && kcp=$kcp:$JB_ANNOTATIONS
    out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" \
      $SOURCES "$ROOT/scripts/qa/KernelCoreVerification.kt" 2>&1) || true
  fi
  echo "$out" | grep -v "^warning:" || true
  if echo "$out" | grep -q "error:"; then return 1; fi
  if echo "$out" | grep -q "BackendException\|NoClassDefFoundError"; then return 2; fi
  return 0
}

echo "[1/2] compiling kernel harness ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e; compile_once; crc=$?; set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then echo "FATAL: 编译失败" >&2; exit 1; fi
  echo "  (attempt $attempt 命中编译器注解竞态，重试 ...)" >&2
  rm -rf "$OUT" && mkdir -p "$OUT"
done
[ $rc -ne 0 ] && { echo "FATAL: 编译重试 5 次仍失败" >&2; exit 1; }

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" KernelCoreVerificationKt
