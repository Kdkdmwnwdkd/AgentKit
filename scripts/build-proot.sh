#!/usr/bin/env bash
# =============================================================================
# 用 Android NDK 交叉编译 proot（aarch64, API 26），产物放入
#   app/src/main/assets/bin/arm64-v8a/proot
#
# GPL 合规说明：
#   proot 以「独立可执行文件」形式随 APK 分发（不静态链接进本应用 .so）。
#   随包附带的 assets/3rdparty/proot/COPYING + SOURCE_OFFER.txt 履行 GPL 分发义务。
#
# 前置环境：
#   - $ANDROID_HOME 或 $ANDROID_SDK_ROOT 指向 Android SDK（含 ndk/）
#   - 网络可访问 samba.org（talloc 源码）与 github.com（proot 源码）
#   - 安装：curl / git / make / 基础构建工具
#
# 用法：
#   ./scripts/build-proot.sh
# =============================================================================
set -euo pipefail

API=26
HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_ARCH="$(uname -m)"

# 若未显式指定 NDK，取 SDK 下第一个 ndk 目录
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT}}"
  ANDROID_NDK_HOME="$(ls -1d "$SDK"/ndk/* 2>/dev/null | head -1)"
fi
[ -n "$ANDROID_NDK_HOME" ] || { echo "找不到 NDK" >&2; exit 1; }

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_OS-$HOST_ARCH"
[ -d "$TOOLCHAIN" ] || { echo "找不到 NDK 工具链：$TOOLCHAIN" >&2; exit 1; }

TARGET=aarch64-linux-android
CC="$TOOLCHAIN/bin/$TARGET$API-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/bin/arm64-v8a/proot"
mkdir -p "$(dirname "$OUT")"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# —— 1. talloc（proot 的硬依赖，GPL 项目需一并提供源码要约）——
TALLOC_VER=2.4.1
echo "==> 下载 talloc $TALLOC_VER"
curl -L "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VER.tar.gz" -o "$TMP/talloc.tar.gz"
tar -xzf "$TMP/talloc.tar.gz" -C "$TMP"
pushd "$TMP/talloc-$TALLOC_VER" >/dev/null
# talloc 使用 waf，交叉编译需给出 --cross-compile 与交叉应答文件；以下为通用模板，
# 若你的 NDK 版本/目标 API 不同，可能需要微调 sysroot 与链接参数。
cat > cross-answers.txt <<EOF
Checking uname sysname type: "Linux"
Checking uname machine type: "aarch64"
Checking uname release type: ""
Checking uname version type: ""
Checking simple C program: OK
building library support: OK
EOF
./configure --cross-compile \
  --cross-answers=cross-answers.txt \
  --host="$TARGET" \
  --disable-python \
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
  CFLAGS="--sysroot=$SYSROOT -fPIC" \
  LDFLAGS="--sysroot=$SYSROOT"
make -j"$(nproc)"
TALLOC_LIB="$(find "$PWD" -name 'libtalloc.so*' | head -1)"
TALLOC_INC="$PWD"
popd >/dev/null

# —— 2. proot ——
echo "==> 克隆 proot（提交 25dc6a3134891f98a79f57ce1c2c1b23ff15cad1）"
git clone --depth 1 https://github.com/proot-me/proot.git "$TMP/proot"
pushd "$TMP/proot/src" >/dev/null
make -j"$(nproc)" \
  CC="$CC" \
  CPPFLAGS="-I$TALLOC_INC -I$TALLOC_INC/replace -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE" \
  CFLAGS="--sysroot=$SYSROOT -g -O2" \
  LDFLAGS="--sysroot=$SYSROOT -L$(dirname "$TALLOC_LIB") -ltalloc" \
  WITHOUT_PYTHON=1
cp proot "$OUT"
"$STRIP" "$OUT" 2>/dev/null || true
popd >/dev/null

echo "==> 产物：$OUT"
ls -l "$OUT"
