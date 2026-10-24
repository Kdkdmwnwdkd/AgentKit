#!/usr/bin/env bash
# =============================================================================
# 长任务存活自检（真机专用）
#
# 为什么只能真机：模拟器没有厂商省电策略、没有真实 Doze、锁屏行为和真机不同。
# 「锁屏后 Agent 还跑不跑」这件事，模拟器给的答案没有参考价值。
#
# 检查三件事：
#   1. 进程在锁屏 + 后台后是否还活着（pid 不变、进程存在）
#   2. 前台服务是否还在（AgentRunService）
#   3. 电池优化是否已豁免（没豁免的话，多数国产 ROM 会在几分钟内杀掉后台）
#
# 用法：
#   ADB=adb bash scripts/qa/device-survival.sh          # 默认锁屏观察 120 秒
#   ADB=adb bash scripts/qa/device-survival.sh 300      # 观察 5 分钟
#
# 前置：先在 App 里发起一个长任务（比如「帮我把这个文件每 10 秒改一次，改 20 次」），
# 看到通知栏出现 AgentKit 常驻通知后再跑本脚本。
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/../.." 2>/dev/null || true

ADB=${ADB:-adb}
PKG=com.example.agentkit
SVC="$PKG/.core.agent.AgentRunService"
WAIT=${1:-120}
pass=0
fail=0
ok()  { pass=$((pass + 1)); echo "  PASS  $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL  $1${2:+  → $2}"; }

pid_of() { "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

svc_running() {
  "$ADB" shell dumpsys activity services "$PKG" 2>/dev/null \
    | grep -q "AgentRunService"
}

echo "== 设备 =="
"$ADB" devices | sed -n '2p'
echo "  Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"

PID_BEFORE=$(pid_of)
if [ -z "$PID_BEFORE" ]; then
  echo "App 没在跑。先在手机上发起一个长任务，再回来跑这个脚本。" >&2
  exit 1
fi
echo "  pid: $PID_BEFORE"

echo "== 前置状态 =="
if svc_running; then ok "前台服务在运行"; else bad "前台服务不在运行" "任务可能还没开始，或服务没起来"; fi

# 电池优化白名单：不在白名单里，国产 ROM 大概率几分钟内杀后台
if "$ADB" shell dumpsys deviceidle whitelist 2>/dev/null | grep -q "$PKG"; then
  ok "已在电池优化白名单"
else
  bad "不在电池优化白名单" "设置 → 电池 → 不受限制；否则锁屏后会被杀"
fi

echo "== 锁屏观察 ${WAIT}s =="
echo "  （屏幕会黑掉，属正常；期间不要碰手机）"
"$ADB" shell input keyevent 26 >/dev/null 2>&1   # 电源键锁屏
sleep "$WAIT"

PID_AFTER=$(pid_of)
SVC_AFTER=0
svc_running && SVC_AFTER=1

if [ -n "$PID_AFTER" ] && [ "$PID_AFTER" = "$PID_BEFORE" ]; then
  ok "进程存活且未重启（pid 未变）"
elif [ -n "$PID_AFTER" ]; then
  bad "进程被重启（$PID_BEFORE → $PID_AFTER）" "任务状态会丢；看是否被省电策略杀掉"
else
  bad "进程已被杀" "锁屏 ${WAIT}s 后 pid 不存在"
fi

if [ "$SVC_AFTER" -eq 1 ]; then ok "前台服务仍在"; else bad "前台服务已消失"; fi

# 唤醒屏幕，方便继续操作
"$ADB" shell input keyevent 26 >/dev/null 2>&1
"$ADB" shell input keyevent 82 >/dev/null 2>&1

echo
echo "==== 存活自检：PASS=$pass FAIL=$fail ===="
echo "补充判断（脚本测不了，需要你看一眼）："
echo "  - 通知栏的 AgentKit 通知是否还在、步骤号有没有继续涨"
echo "  - 回 App 看任务是否还在跑，还是变成了「已中断」"
[ "$fail" -eq 0 ] || exit 1
