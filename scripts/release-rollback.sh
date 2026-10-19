#!/usr/bin/env bash
# release-rollback.sh —— 回滚到某一次能用的构建（W-41）
#
# 为什么不是「下载旧 APK 再装」：Android 只允许**版本号不低于当前**的包覆盖安装。
# 直接把旧包丢给系统安装器会报「应用未安装」，只能先卸载——聊天记录、配置、工作区全丢。
#
# 正确做法：**拿旧代码、用更大的版本号重新构建一次**。本项目 versionCode 取自 CI 的
# run number，所以任何新构建都天然高于旧包，能直接覆盖安装、数据不动。
#
# 用法：
#   ./scripts/release-rollback.sh --list                列出最近可回滚的构建
#   ./scripts/release-rollback.sh --to <run-id|commit>  用那次构建的代码重新出包并发布 latest
#   ./scripts/release-rollback.sh --to <ref> --dry-run  只打印将要执行的命令，不触发
#
# 依赖：gh 已登录且对仓库有 Actions 写权限。
set -euo pipefail

REPO="${AGENTKIT_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
WORKFLOW="${AGENTKIT_WORKFLOW:-build.yml}"

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

list_runs() {
    echo "仓库：$REPO    工作流：$WORKFLOW"
    printf "%-12s %-9s %-18s %s\n" "RUN_ID" "COMMIT" "时间(UTC)" "标题"
    gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 15 \
        --json databaseId,headSha,createdAt,conclusion,displayTitle \
        -q '.[] | select(.conclusion=="success") | [.databaseId, .headSha[0:7], .createdAt[0:16], .displayTitle] | @tsv' |
    while IFS=$'\t' read -r id sha at title; do
        printf "%-12s %-9s %-18s %s\n" "$id" "$sha" "$at" "$title"
    done
    echo
    echo "回滚 = 用那次构建的 commit 重新跑一遍构建（版本号更大，可覆盖安装、不丢数据）。"
}

resolve_commit() {
    local arg="$1"
    # 纯数字 → 当 run id 查 commit；否则当 ref（sha / 分支 / tag）
    if [[ "$arg" =~ ^[0-9]+$ ]]; then
        gh run view "$arg" --repo "$REPO" --json headSha,conclusion \
            -q 'if .conclusion=="success" then .headSha else "ERR:" + .conclusion end'
    else
        echo "$arg"
    fi
}

do_rollback() {
    local arg="$1" dry="$2"
    [ -n "$arg" ] || { echo "缺少目标（run id 或 commit）" >&2; exit 2; }

    local sha
    sha=$(resolve_commit "$arg")
    case "$sha" in
        ERR:*)
            echo "拒绝回滚：该 run 的结论是「${sha#ERR:}」，不是 success。" >&2
            exit 2 ;;
        "") echo "无法解析目标：$arg" >&2; exit 2 ;;
    esac
    echo "回滚目标 commit：$sha"

    if [ "$dry" = "1" ]; then
        echo "将执行：gh workflow run $WORKFLOW --repo $REPO -f ref=$sha"
        echo "（dry-run：未触发）"
        exit 0
    fi

    gh workflow run "$WORKFLOW" --repo "$REPO" -f ref="$sha"
    echo "已触发构建，等待排队 ..."
    sleep 6
    local new_id
    new_id=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId -q '.[0].databaseId')
    echo "新构建：run $new_id"
    echo "进度：gh run watch $new_id --repo $REPO"
    echo "构建成功后 latest 会自动指向这一版（旧代码 + 新版本号，可直接覆盖安装）。"
}

case "${1:-}" in
    --list|list) list_runs ;;
    --to|to)
        shift
        arg="${1:-}"; shift || true
        dry="0"; [ "${1:-}" = "--dry-run" ] && dry="1"
        do_rollback "$arg" "$dry" ;;
    -h|--help|"") usage 0 ;;
    *) echo "未知参数：$1" >&2; usage 2 ;;
esac
