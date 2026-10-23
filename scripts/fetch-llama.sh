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

# b4600 对应的**确切 commit**。
#
# 为什么要钉死 commit 而不只钉 tag：git tag 是可以被上游移动的（force-push / 重打 tag），
# 一旦移动，同一份 `--branch b4600` 在不同时间 clone 出来的源码就不是同一份了——
# 构建产物不可复现，而且这种漂移**完全静默**（不报错，只是行为变了）。
# 钉死 commit 后，任何漂移都会当场被下面的校验抓住。
EXPECTED_COMMIT="553f1e46e9e864514bbd6bf4009146db66be0541"

mkdir -p "$DEST"

# 校验目录里的源码是否就是那个 commit。
# 返回 0 = 一致；1 = 不一致或不是 git 仓库（缓存被污染 / tag 漂移过）。
verify_commit() {
  local dest="$1"
  [ -d "$dest/.git" ] || return 1
  local actual
  actual=$(git -C "$dest" rev-parse HEAD 2>/dev/null || echo "")
  [ "$actual" = "$EXPECTED_COMMIT" ]
}

case "$MODE" in
  git)
    if [ -f "$DEST/CMakeLists.txt" ]; then
      if [ ! -d "$DEST/.git" ]; then
        # 没有 git 元数据：多半是 cdn 模式拉出来的（逐文件下载，无 .git）。
        # 这种情况**不能删**——本地开发者可能正靠它构建，删了就得重新逐文件拉一遍。
        # 只警告无法校验，按"用户自己管"处理。
        echo "[fetch-llama] WARN: 已有源码但不是 git 仓库（可能是 cdn 模式拉的），无法做 commit 校验，直接使用"
        echo "[fetch-llama] 源码就绪: $DEST"
        exit 0
      fi
      if verify_commit "$DEST"; then
        echo "[fetch-llama] 源码已存在且 commit 校验通过，跳过（删掉 $DEST 可强制重新拉取）"
      else
        echo "[fetch-llama] WARN: 已有源码但 commit 不是 $EXPECTED_COMMIT（tag 被移动或缓存污染），重新拉取"
        rm -rf "$DEST"
        git clone --depth 1 --branch "$TAG" "$REPO" "$DEST"
        if ! verify_commit "$DEST"; then
          actual=$(git -C "$DEST" rev-parse HEAD 2>/dev/null || echo "?")
          echo "[fetch-llama] FATAL: 拉到的 commit 是 $actual，期望 $EXPECTED_COMMIT" >&2
          echo "[fetch-llama] 上游 tag $TAG 可能被移动过。若确认要升级，请同步更新本脚本的 EXPECTED_COMMIT。" >&2
          exit 1
        fi
        echo "[fetch-llama] commit 校验通过：$EXPECTED_COMMIT"
      fi
    else
      echo "[fetch-llama] git clone $REPO @ $TAG -> $DEST"
      rm -rf "$DEST"
      git clone --depth 1 --branch "$TAG" "$REPO" "$DEST"
      if ! verify_commit "$DEST"; then
        actual=$(git -C "$DEST" rev-parse HEAD 2>/dev/null || echo "?")
        echo "[fetch-llama] FATAL: 拉到的 commit 是 $actual，期望 $EXPECTED_COMMIT" >&2
        echo "[fetch-llama] 上游 tag $TAG 可能被移动过。若确认要升级，请同步更新本脚本的 EXPECTED_COMMIT。" >&2
        exit 1
      fi
      echo "[fetch-llama] commit 校验通过：$EXPECTED_COMMIT"
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
