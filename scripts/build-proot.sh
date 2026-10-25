#!/usr/bin/env bash
# =============================================================================
# 用 Android NDK 交叉编译 proot（从 termux/proot 源码），产物覆盖
#   app/src/main/assets/bin/<abi>/proot
#   app/src/main/jniLibs/<abi>/libproot.so
#
# GPL 合规：proot 以「独立可执行文件」形式随 APK 分发（不静态链接进本应用 .so）。
#          随包附带的 assets/3rdparty/proot/COPYING + SOURCE_OFFER.txt 履行义务。
#
# 用法：
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-proot.sh           # aarch64
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-proot.sh x86_64
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-proot.sh all
# =============================================================================
set -euo pipefail

# -----------------------------------------------------------------------------
# 为什么必须自己编译（不要直接拿 Termux 仓库里的 .deb）
#
# Termux 发布的 proot 二进制，其 loader 注入机制只在 Termux 自己的运行环境里生效。
# 放到别的应用里，表现是：
#
#   proot 能启动、能翻译路径、能解析出 PT_INTERP，但 execve 时内核拿到的仍是
#   **旧路径**（注入没真正落到子进程内存），返回 ENOENT：
#     proot error: execve("/bin/sh"): No such file or directory
#
# 已排除：权限域（shell / root 身份同样失败）、参数（proot-distro 全套开关同样失败）、
#         seccomp（PROOT_NO_SECCOMP=1 同样失败）、文件（同一个 ld-musl 直接执行正常）、
#         targetSdk（降到 28 后 error=13 消失，但 execve 仍失败）。
# 换用同一份源码自己编译的二进制后，**guest 能正常启动**。
# -----------------------------------------------------------------------------

WORK="${WORK:-/tmp/build-proot}"
ABI="${1:-aarch64}"

# 若未显式指定 NDK，取 SDK 下第一个 ndk 目录
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?请设置 ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_NDK_HOME}}"
  ANDROID_NDK_HOME="$(ls -1d "$SDK"/ndk/* 2>/dev/null | head -1)"
fi
[ -n "$ANDROID_NDK_HOME" ] || { echo "找不到 NDK" >&2; exit 1; }
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$BIN" ] || { echo "NDK 工具链不存在：$BIN" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROOT_URL="https://codeload.github.com/termux/proot/tar.gz/refs/heads/master"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz"
TERMUX_APT="https://packages.termux.dev/apt/termux-main"

mkdir -p "$WORK"
cd "$WORK"

fetch() { [ -s "$2" ] || curl -sL --max-time 180 "$1" -o "$2"; }

echo "== 取源码 =="
fetch "$PROOT_URL" proot.tgz
fetch "$TALLOC_URL" talloc.tgz
rm -rf proot talloc
mkdir -p proot && tar -xzf proot.tgz -C proot --strip-components=1
mkdir -p talloc && tar -xzf talloc.tgz -C talloc --strip-components=1
cp talloc/talloc.h "$WORK/talloc.h"

build_abi() {
  local abi="$1" api=26 deb fmt arch
  case "$abi" in
    aarch64) deb="libtalloc_2.5.0_aarch64.deb"; fmt="elf64-littleaarch64"; arch="aarch64" ;;
    x86_64)  deb="libtalloc_2.5.0_x86_64.deb";  fmt="elf64-little";        arch="i386:x86-64" ;;
    *) echo "不支持的 ABI：$abi" >&2; return 1 ;;
  esac

  echo "== 编译 $abi =="
  local libdir="$WORK/lib-$abi"
  mkdir -p "$libdir"
  fetch "$TERMUX_APT/pool/main/libt/libtalloc/$deb" "$libdir/t.deb"
  ( cd "$libdir" && ar x t.deb && tar -xf data.tar.xz 2>/dev/null ) || true
  local so
  so="$(find "$libdir" -name 'libtalloc.so.2.*' | head -1)"
  [ -n "$so" ] || { echo "取 libtalloc 失败：$abi" >&2; return 1; }
  cp "$so" "$libdir/libtalloc.so.2"
  ln -sf libtalloc.so.2 "$libdir/libtalloc.so"

  # termux/proot 的源码缺一处 string.h（ashmem_memfd 扩展），补上否则 C99 报隐式声明
  local ashmem="$WORK/proot/src/extension/ashmem_memfd/ashmem_memfd.c"
  if [ -f "$ashmem" ] && ! grep -q '#include <string.h>' "$ashmem"; then
    python3 - "$ashmem" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
i = s.index('#include')
open(p, 'w').write(s[:i] + '#include <string.h>\n' + s[i:])
PY
  fi

  local src="$WORK/proot/src"
  cd "$src"
  make clean >/dev/null 2>&1 || true

  # 第一步：先把 loader 编出来（下面要用它生成 loader-info.c）
  make -j4 \
    CC="$BIN/${abi}-linux-android${api}-clang" \
    OBJCOPY="$BIN/llvm-objcopy" STRIP="$BIN/llvm-strip" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK" \
    LDFLAGS="-L$libdir -ltalloc" \
    loader/loader

  # 原 makefile 用 awk 的 strtonum（gawk 专有），这里用 python 等价实现
  python3 - "$src/loader/loader" <<'PY'
import subprocess, sys
out = subprocess.run(['readelf', '-s', sys.argv[1]], capture_output=True, text=True).stdout
def find(name):
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 8 and p[7] == name:
            return int(p[1], 16)
    return None
pw, st = find('pokedata_workaround'), find('_start')
if pw is None or st is None:
    raise SystemExit('loader 符号缺失：pokedata_workaround=%s _start=%s' % (pw, st))
open('loader/loader-info.c', 'w').write(
    '#include <unistd.h>\n'
    'const ssize_t offset_to_pokedata_workaround=%d;\n' % (pw - st)
)
PY

  # objcopy 的目标格式：llvm-objdump 与 GNU objdump 输出串不一致，直接写死
  make -j4 \
    CC="$BIN/${abi}-linux-android${api}-clang" \
    OBJCOPY="$BIN/llvm-objcopy" STRIP="$BIN/llvm-strip" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK" \
    LDFLAGS="-L$libdir -ltalloc" \
    OBJIFY="$BIN/llvm-objcopy --input-target=binary --output-target=$fmt --binary-architecture $arch \$< \$@"

  [ -f proot ] || { echo "编译失败：$abi" >&2; return 1; }
  mkdir -p "$ROOT/app/src/main/assets/bin/$abi" "$ROOT/app/src/main/jniLibs/$abi"
  cp proot "$ROOT/app/src/main/assets/bin/$abi/proot"
  chmod 755 "$ROOT/app/src/main/assets/bin/$abi/proot"
  cp proot "$ROOT/app/src/main/jniLibs/$abi/libproot.so"
  echo "  写入 $abi（$(wc -c < proot) 字节）"
}

case "$ABI" in
  all) build_abi aarch64; build_abi x86_64 ;;
  *)   build_abi "$ABI" ;;
esac

echo "== 完成 =="
