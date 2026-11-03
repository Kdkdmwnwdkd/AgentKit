#!/bin/bash
# R1 真机验收：工具执行中被杀，重进不重放。
#
# 用法：
#   ADB=adb PKG=com.example.agentkit bash r1-interrupt.sh prepare   # 造 FIFO、清日志
#   ... 在设备上触发一次 write_file，等日志出现 DISP ...
#   ADB=adb PKG=com.example.agentkit bash r1-interrupt.sh kill
#   ADB=adb PKG=com.example.agentkit bash r1-interrupt.sh verify
#
# 为什么用 FIFO：write_file 往 FIFO 写会阻塞，于是「已越过执行边界、尚未产出结果」
# 这个窗口可以被稳定制造——否则该窗口只有几毫秒，抓不到。
set -u
ADB=${ADB:-adb}
PKG=${PKG:-com.example.agentkit}
JRN=/data/data/$PKG/files/run-journal
WS=/data/data/$PKG/files/agent_workspace

journal() {
  for f in $($ADB shell run-as "$PKG" ls "$JRN" 2>/dev/null | tr -d '\r'); do
    echo "== $f"
    $ADB shell run-as "$PKG" cat "$JRN/$f"
  done
}

case "${1:-}" in
prepare)
  $ADB shell run-as "$PKG" rm -rf "$JRN"
  $ADB shell run-as "$PKG" rm -f "$WS/t.txt"
  $ADB shell run-as "$PKG" mkfifo "$WS/t.txt"
  $ADB shell run-as "$PKG" ls -l "$WS/t.txt"
  echo "FIFO ready: $WS/t.txt"
  ;;
kill)
  echo "=== 杀之前 ==="
  journal
  if ! journal | grep -q DISP; then
    echo "!! 还没有 DISP：先在设备上触发一次 write_file，再执行 kill"
    exit 1
  fi
  if journal | grep -q SETTLE; then
    echo "!! 已经 SETTLE，窗口已过：重新 prepare 再来一次"
    exit 1
  fi
  $ADB shell am force-stop "$PKG"
  echo "=== killed ==="
  ;;
verify)
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  echo "等待启动与自动修复…（慢设备需要 60–90 秒）"
  for i in $(seq 1 30); do
    sleep 3
    if journal | grep -q "OUTCOME_UNKNOWN"; then echo "修复完成（约 $((i*3))s）"; break; fi
  done
  echo "=== 修复后 ==="
  journal
  echo "=== 应用日志里的 Recovery 行 ==="
  LOG=$($ADB shell run-as "$PKG" ls -t "/data/data/$PKG/files/logs" 2>/dev/null | tr -d '\r' | head -1)
  $ADB shell run-as "$PKG" tail -400 "/data/data/$PKG/files/logs/$LOG" 2>/dev/null | grep -i Recovery
  echo
  echo "判据：应看到 SETTLE … OUTCOME_UNKNOWN + STEP + CLOSE interrupted；"
  echo "      Recovery 行应报「N 个写操作结局未知」；此后不应再出现同一个写操作的执行记录。"
  ;;
*)
  sed -n '2,12p' "$0"
  ;;
esac
