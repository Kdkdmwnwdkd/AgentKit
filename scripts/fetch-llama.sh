#!/bin/bash
# 拉取 llama.cpp 固定 tag b4600 源码到 app/src/main/cpp/llama.cpp/。
#
# 两种模式：
#   git 模式（默认，CI 用）：shallow clone。GitHub Actions 网络可达 github.com，最稳。
#   cdn 模式（本地/离线受限环境用）：经 jsDelivr CDN 逐文件拉取。
#
# 用法: scripts/fetch-llama.sh [git|cdn] [目标目录]
set -euo pipefail

MODE="${1:-git}"
DEST="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/cpp/llama.cpp}"
TAG="b4600"
REPO="https://github.com/ggml-org/llama.cpp.git"

mkdir -p "$DEST"

case "$MODE" in
  git)
    if [ ! -f "$DEST/CMakeLists.txt" ]; then
      echo "[fetch-llama] git clone $REPO @ $TAG -> $DEST"
      rm -rf "$DEST"
      git clone --depth 1 --branch "$TAG" "$REPO" "$DEST"
    else
      echo "[fetch-llama] 源码已存在，跳过（删掉 $DEST 可强制重新拉取）"
    fi
    ;;
  cdn)
    # 用 api.github.com 拿文件树，再逐文件从 jsDelivr 拉。
    # 本地沙箱连不上 codeload.github.com（连接短命、不支持 Range），CDN 是唯一稳定通道。
    echo "[fetch-llama] cdn 模式：api.github.com 取树 + jsDelivr 逐文件拉取"
    TREE_JSON=$(mktemp)
    curl -fsSL --connect-timeout 10 --max-time 60 \
      "https://api.github.com/repos/ggml-org/llama.cpp/git/trees/$TAG?recursive=1" -o "$TREE_JSON"
    python3 - "$TREE_JSON" > /tmp/llama-cdn-files.txt <<'PY'
import json, sys
tree = json.load(open(sys.argv[1]))
for t in tree['tree']:
    p = t['path']
    if t['type'] == 'blob' and (p.startswith(('src/', 'ggml/', 'cmake/', 'include/')) or p in ('CMakeLists.txt', 'LICENSE')):
        print(p)
PY
    rm -f "$TREE_JSON"

    TOTAL=$(wc -l < /tmp/llama-cdn-files.txt)
    echo "[fetch-llama] 待拉取 $TOTAL 个文件"
    BASE="https://cdn.jsdelivr.net/gh/ggml-org/llama.cpp@$TAG"
    ok=0; fail=0
    while IFS= read -r rel; do
      out="$DEST/$rel"
      mkdir -p "$(dirname "$out")"
      # 串行 + 小间隔，避开 CDN 限流（并行burst会被 403）
      if curl -fsSL --connect-timeout 10 --max-time 90 "$BASE/$rel" -o "$out" 2>/dev/null && [ -s "$out" ]; then
        ok=$((ok+1))
      else
        echo "[fetch-llama] FAIL $rel"
        fail=$((fail+1))
      fi
      sleep 0.3
    done < /tmp/llama-cdn-files.txt
    echo "[fetch-llama] 完成: ok=$ok fail=$fail"
    if [ "$fail" -gt 0 ]; then
      # Kompute/SYCL/Vulkan 等后端源码缺失不影响构建（CMake 侧已全部关闭），
      # 但核心 src/ 文件缺失必须失败。
      if [ ! -f "$DEST/src/llama.cpp" ] || [ ! -f "$DEST/ggml/src/ggml.c" ] || [ ! -f "$DEST/include/llama.h" ]; then
        echo "[fetch-llama] 核心文件缺失，构建必然失败" >&2
        exit 1
      fi
      echo "[fetch-llama] 警告: $fail 个非核心文件缺失（已关闭的后端），继续"
    fi
    ;;
  *)
    echo "未知模式: $MODE（可选 git|cdn）" >&2
    exit 1
    ;;
esac

echo "[fetch-llama] 源码就绪: $DEST"
