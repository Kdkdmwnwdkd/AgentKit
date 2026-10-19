#!/usr/bin/env bash
# 全量 harness 运行器（W-39）。
#
# 用法：
#   bash scripts/qa/run-all.sh [apk路径]
#
# 三类结果，语义严格区分：
#   PASS  跑完且断言全过
#   SKIP  环境不具备（例如缺 AGP 编译产物），**什么都没验证**，不算通过
#   XFAIL 已知失效（测试源码与主代码脱节，见 known-broken.txt），照跑但不阻塞
#   FAIL  真跑了但断言失败，或跑完没有通过标记
#
# 退出码：只有 FAIL 才非 0（SKIP / XFAIL 不阻塞 CI/本地检查）。
set -uo pipefail
# 断言脚本按「仓库根」为 CWD 写（要读 app/src/main/... 与 scripts/qa/...），
# 所以这里必须停在仓库根，不能停在本目录——否则 read() 全找不到文件。
cd "$(dirname "$0")/../.."

APK="${1:-}"
pass=0; skip=0; fail=0; xfail=0; failed=""

# 已知失效清单：这些断言源码已与主代码脱节，编译就过不了。
# 它们照跑（避免假装不存在），但归为 XFAIL，不阻塞；修好后从清单删掉即可。
BROKEN_FILE="scripts/qa/known-broken.txt"
is_known_broken() {
    [ -f "$BROKEN_FILE" ] || return 1
    grep -qvE '^\s*(#|$)' "$BROKEN_FILE" || return 1
    awk '!/^[[:space:]]*(#|$)/ {print $1}' "$BROKEN_FILE" | grep -qx "$(basename "$1")"
}

for s in scripts/qa/run-*.sh; do
  [ "$(basename "$s")" = "run-all.sh" ] && continue
  case "$s" in
    *apk-verification*)
      if [ -z "$APK" ]; then
        echo "SKIP  $(basename "$s")（未传 APK 路径，静态反验未执行）"
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
    echo "SKIP  $(basename "$s")"
  elif [ $rc -ne 0 ]; then
    if is_known_broken "$s"; then
      xfail=$((xfail + 1))
      echo "XFAIL $s（已知失效，见 known-broken.txt）"
    else
      fail=$((fail + 1)); failed="$failed $s"
      echo "FAIL  $s（exit=$rc）"
      echo "$out" | tail -6
    fi
  elif echo "$out" | grep -qE "0 failed|FAIL=0|0 失败|_OK [0-9]|全部通过"; then
    if is_known_broken "$s"; then
      echo "XPASS $s（已修复！请从 known-broken.txt 移除）"
    fi
    pass=$((pass + 1))
    echo "PASS  $s"
  else
    if is_known_broken "$s"; then
      xfail=$((xfail + 1))
      echo "XFAIL $s（已知失效，见 known-broken.txt）"
    else
      fail=$((fail + 1)); failed="$failed $s"
      echo "FAIL  $s（无通过标记，可能是断言没跑到）"
      echo "$out" | tail -6
    fi
  fi
done

echo
echo "==== 汇总：PASS=$pass SKIP=$skip XFAIL=$xfail FAIL=$fail"
if [ $fail -ne 0 ]; then
  echo "失败清单:$failed"
  exit 1
fi
