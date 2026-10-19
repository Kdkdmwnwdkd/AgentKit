#!/usr/bin/env bash
# 全量 harness 运行器（W-39）。
#
# 用法：
#   bash scripts/qa/run-all.sh [apk路径]
#
# 三类结果，语义严格区分：
#   PASS  跑完且断言全过
#   SKIP  环境不具备（例如缺 AGP 编译产物），**什么都没验证**，不算通过
#   FAIL  真跑了但断言失败，或跑完没有通过标记
#
# 退出码：只有 FAIL 才非 0（SKIP 不阻塞 CI/本地检查）。
set -uo pipefail
cd "$(dirname "$0")"

APK="${1:-}"
pass=0; skip=0; fail=0; failed=""

for s in run-*.sh; do
  [ "$s" = "run-all.sh" ] && continue
  case "$s" in
    *apk-verification*)
      if [ -z "$APK" ]; then
        echo "SKIP  $s（未传 APK 路径，静态反验未执行）"
        skip=$((skip + 1)); continue
      fi
      out=$(bash "$s" "$APK" 2>&1); rc=$?
      ;;
    *)
      out=$(bash "$s" 2>&1); rc=$?
      ;;
  esac

  if [ $rc -eq 3 ]; then
    skip=$((skip + 1))
    echo "SKIP  $s"
  elif [ $rc -ne 0 ]; then
    fail=$((fail + 1)); failed="$failed $s"
    echo "FAIL  $s（exit=$rc）"
    echo "$out" | tail -6
  elif echo "$out" | grep -qE "0 failed|FAIL=0|0 失败|_OK [0-9]|全部通过"; then
    pass=$((pass + 1))
    echo "PASS  $s"
  else
    fail=$((fail + 1)); failed="$failed $s"
    echo "FAIL  $s（无通过标记，可能是断言没跑到）"
    echo "$out" | tail -6
  fi
done

echo
echo "==== 汇总：PASS=$pass SKIP=$skip FAIL=$fail"
if [ $fail -ne 0 ]; then
  echo "失败清单:$failed"
  exit 1
fi
