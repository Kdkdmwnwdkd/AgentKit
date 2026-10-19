#!/usr/bin/env bash
# APK dex 反验运行器（W-9 起：批次无关，判据在 verify-apk-features.py）。
# 用法: bash scripts/qa/run-apk-verification.sh [apk路径]
# 缺省 apk 路径为 /tmp/w9-apk/app-debug.apk；W-10 产物放 /tmp/w10-apk/app-debug.apk 时显式传参。
set -euo pipefail
cd "$(dirname "$0")/../.."
APK="${1:-/tmp/w9-apk/app-debug.apk}"
python3 scripts/qa/verify-apk-features.py "$APK"
