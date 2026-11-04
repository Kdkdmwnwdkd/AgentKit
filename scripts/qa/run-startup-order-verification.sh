#!/usr/bin/env bash
# 启动顺序 harness（纯 JVM，只读源码文本）。
#
# 盯的是「首帧之前别干重活」：后台 WebView 曾被排在 setContent 之前创建，
# 冷启动 20~30 秒把主线程占满 → 系统判 ANR → App 闪退。详见 StartupOrderVerification.kt。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-startup-order
KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
KOTLINC_BIN="$KC_HOME/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || { echo "FATAL: 找不到 kotlinc（$KOTLINC_BIN）" >&2; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC_BIN" -d "$OUT" \
  "$ROOT/scripts/qa/StartupOrderVerification.kt"
java -cp "$OUT:$KC_HOME/lib/kotlin-stdlib.jar" StartupOrderVerificationKt
