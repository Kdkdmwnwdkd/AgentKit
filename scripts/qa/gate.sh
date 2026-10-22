#!/usr/bin/env bash
# scripts/qa/gate.sh —— QA 硬门禁
#
# 为什么需要它：`run-all.sh` 把「已知失效」的 harness 归为 XFAIL，**不阻塞构建**。
# 这个设计本身没错（断言陈旧时不该把所有人堵在门口），但副作用是：
# 一个新引入的失效 harness 只要往 known-broken.txt 里加一行，就能长期悄悄挂在 CI 上。
# 本脚本把这类「沉淀」变成**当场报红**。
#
# 门禁三条（任一不满足即失败）：
#   1. known-broken.txt 里不能有非注释条目 —— 清单必须归零
#   2. QA 汇总里 FAIL 必须为 0
#   3. 日志里不能出现 XPASS —— 出现说明清单该更新了（脚本已修好却仍被当失效项）
#
# 用法：
#   bash scripts/qa/gate.sh [qa日志路径]
#   不传日志路径时，只做第 1 条（清单非空检查），适合本地快速自查。
#
# 逃生阀：环境变量 QA_GATE=warn 时只告警不失败（仅供紧急止血，默认是硬失败）。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)

BROKEN_FILE="scripts/qa/known-broken.txt"
LOG="${1:-}"

rc=0
say() { echo "$*"; }
fail() { echo "GATE FAIL: $*" >&2; rc=1; }

# ---------------------------------------------------------------- 门禁 1
if [ -f "$BROKEN_FILE" ]; then
  # 只取非注释、非空行；字段 1 是脚本名
  entries=$(awk '!/^[[:space:]]*(#|$)/ {print $1}' "$BROKEN_FILE")
  n=$(printf '%s' "$entries" | grep -c . || true)
  if [ "${n:-0}" -gt 0 ]; then
    fail "known-broken.txt 有 ${n} 条已知失效项（应为 0）："
    printf '%s\n' "$entries" | sed 's/^/    - /' >&2
    say "  修好一条就删一条；确实修不好就先修，别让失效项沉淀成常态。"
  else
    say "GATE OK 1/3：known-broken.txt 已归零（0 条）"
  fi
else
  fail "known-broken.txt 不存在（$BROKEN_FILE）——门禁无法判定，请检查文件是否被误删"
fi

# ---------------------------------------------------------------- 门禁 2 & 3
if [ -z "$LOG" ]; then
  say "GATE SKIP 2-3：未提供 QA 日志路径，只做了清单检查"
  say "  CI 里本脚本会自动取 /tmp/qa.log；本地可手动传：bash scripts/qa/gate.sh qa-logs/xxx.log"
elif [ ! -f "$LOG" ]; then
  fail "QA 日志不存在：$LOG（QA harness 可能被 QA_HARNESS=off 跳过了）"
else
  summary=$(grep -E '^\s*====\s*汇总：' "$LOG" | tail -1)
  if [ -z "$summary" ]; then
    fail "QA 日志里找不到汇总行（格式应为「==== 汇总：PASS=n SKIP=n XFAIL=n FAIL=n」）"
  else
    f=$(printf '%s' "$summary" | sed -n 's/.*FAIL=\([0-9]\+\).*/\1/p')
    x=$(printf '%s' "$summary" | sed -n 's/.*XFAIL=\([0-9]\+\).*/\1/p')
    p=$(printf '%s' "$summary" | sed -n 's/.*PASS=\([0-9]\+\).*/\1/p')
    say "  汇总：$summary"
    if [ -z "$f" ]; then
      fail "无法从汇总行解析 FAIL 数：$summary"
    elif [ "$f" -ne 0 ]; then
      fail "QA 汇总 FAIL=$f（必须为 0）"
    else
      say "GATE OK 2/3：FAIL=0（PASS=${p:-?} XFAIL=${x:-?}）"
    fi
  fi

  if grep -qE '^XPASS ' "$LOG"; then
    fail "QA 日志出现 XPASS（脚本已修好但仍列在 known-broken.txt），请从清单移除："
    grep -E '^XPASS ' "$LOG" | sed 's/^/    - /' >&2
  else
    say "GATE OK 3/3：无 XPASS"
  fi
fi

# ---------------------------------------------------------------- 逃生阀
if [ "${QA_GATE:-}" = "warn" ] && [ "$rc" -ne 0 ]; then
  echo "QA_GATE=warn：以上问题仅告警，不阻塞（紧急止血用）" >&2
  exit 0
fi

if [ "$rc" -eq 0 ]; then
  echo "GATE PASS：全部检查通过"
else
  echo "GATE BLOCKED：门禁未通过" >&2
fi
exit "$rc"
