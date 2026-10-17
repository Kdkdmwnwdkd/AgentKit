#!/usr/bin/env bash
# W-9 第四道门：APK dex 反验运行器。
# 用法: bash scripts/qa/run-w9-apk-verification.sh [apk路径]
# 缺省 apk 路径为 /tmp/w9-apk/app-debug.apk（CI run 148 产物，head_sha d17dd1a）。
set -euo pipefail
cd "$(dirname "$0")/../.."
APK="${1:-/tmp/w9-apk/app-debug.apk}"
python3 scripts/qa/verify-w9-apk.py "$APK"
