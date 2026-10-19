#!/usr/bin/env bash
# W-37 harness：Shizuku UserService 退出码语义（纯 JVM，不加载 Android）。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-shizuku-result
KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
KOTLINC_BIN="$KC_HOME/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || { echo "FATAL: 找不到 kotlinc（$KOTLINC_BIN）" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC_BIN" -d "$OUT" \
  "$ROOT/app/src/main/java/com/example/agentkit/automation/shizuku/ShizukuResultLogic.kt" \
  "$ROOT/scripts/qa/ShizukuResultVerification.kt"
java -cp "$OUT:$KC_HOME/lib/kotlin-stdlib.jar" ShizukuResultVerificationKt
