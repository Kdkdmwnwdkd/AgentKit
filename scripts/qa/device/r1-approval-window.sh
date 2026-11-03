#!/bin/bash
# R1 真机验收：审批弹窗期间被杀，不该判成「可能已生效」。
#
# 用法：
#   ADB=adb PKG=com.example.agentkit bash r1-approval-window.sh watch
#       → 每 0.5 秒打印一次日志行数；在设备上触发一个需要审批的写工具，
#         **先别点允许**，观察是否出现 DISP。
#   ADB=adb PKG=com.example.agentkit bash r1-approval-window.sh kill
#       → 弹窗还在时直接 force-stop（不点任何按钮）。
#   ADB=adb PKG=com.example.agentkit bash r1-approval-window.sh verify
#       → 重启后检查：应判 NOT_DISPATCHED（「还没开始执行，可以安全重试」）。
#
# 判据：审批通过之前不该有 DISP。有 DISP = 记账点放早了，用户犹豫时被杀会被告知
# 一个从未发生的写操作「可能已经生效」。
set -u
ADB=${ADB:-adb}
PKG=${PKG:-com.example.agentkit}
JRN=/data/data/$PKG/files/run-journal
journal() {
  for f in $($ADB shell run-as "$PKG" ls "$JRN" 2>/dev/null | tr -d '\r'); do
    $ADB shell run-as "$PKG" cat "$JRN/$f"
  done
}

case "${1:-}" in
watch)
  echo "采样中（Ctrl-C 结束）。审批弹窗出现后先别点，看这里有没有 DISP："
  last=""
  while true; do
    cur=$(journal)
    if [ "$cur" != "$last" ]; then
      echo "--- $(date +%H:%M:%S) ---"
      echo "$cur"
      last="$cur"
    fi
    sleep 0.5
  done
  ;;
kill)
  echo "=== 杀之前（审批应当还没点）==="
  journal
  $ADB shell am force-stop "$PKG"
  echo "=== killed ==="
  ;;
verify)
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  for i in $(seq 1 30); do
    sleep 3
    journal | grep -q "NOT_STARTED" && { echo "修复完成（约 $((i*3))s）"; break; }
  done
  echo "=== 修复后 ==="
  journal
  echo
  echo "判据：SETTLE … NOT_STARTED + CLOSE interrupted；"
  echo "      提示语应是「N 个调用还没开始执行，可以安全重试」。"
  echo "      若出现 OUTCOME_UNKNOWN，说明记账点仍在审批之前，是缺陷。"
  ;;
*)
  sed -n '2,15p' "$0"
  ;;
esac
