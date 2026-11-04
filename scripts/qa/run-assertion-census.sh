#!/usr/bin/env bash
# P5-2 harness 分层：文本断言只减不增。
#
# 规矩（架构方案 P0 立的）：「新断言必须调用被测函数，禁止新增 `src.contains`」。
# 立完没人执行——于是每重构一次，就有十几条「某文件里有没有这句话」变红，
# 修法都是把断言搬到新位置。它们守的从来不是行为。
#
# 这个脚本把那条规矩变成可执行的：按文件统计文本断言数，与基线比对，多一条就红。
# 判定逻辑全在 assertion-census.py（带完整说明），这里只是让它进 run-all。
set -uo pipefail
cd "$(dirname "$0")/../.."

out=$(python3 scripts/qa/assertion-census.py 2>&1)
rc=$?
echo "$out"

count=$(echo "$out" | grep -oE '文本断言：[0-9]+ 条' | head -1 | grep -oE '[0-9]+')
if [ "$rc" -eq 0 ]; then
  echo "文本断言体检：${count:-?} 条，0 失败（只减不增）"
  exit 0
fi
echo "文本断言体检：${count:-?} 条，有超标（见上）"
exit 1
