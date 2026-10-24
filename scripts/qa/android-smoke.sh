#!/usr/bin/env bash
# =============================================================================
# 关键路径冒烟（真机 / 模拟器通用，只需要 adb）
#
# 为什么要有它：2026-10-23~24 连续四次回归都是「功能在、但用不了」——
#   - 版本映射拼出不存在的 nodejs18
#   - 新装应用容器页卡在「需要 proot」（提取逻辑没被触发）
#   - JNI 里 snprintf 返回值当 write 长度，exec 失败时 FORTIFY abort
#   - 抽屉拆成两层 weight，真机上整段菜单高度归零
# 这些用 JVM 断言都测不出来，靠人肉点又漏。这条脚本把它们固化成每次发版必跑。
#
# 覆盖：
#   1. 能装上、能冷启、能过首屏
#   2. 抽屉菜单完整（五个一级入口都在）—— 防「菜单塌陷」
#   3. 抽屉里能滚到「设置」并进去 —— 防「入口点不到」
#   4. 设置里能进「容器」
#   5. 容器页不是「需要 proot」—— 防首启死锁
#   6. 能解压 rootfs 并显示「已就绪」
#
# 用法：
#   ADB=adb bash scripts/qa/android-smoke.sh                    # 只跑，不重装
#   ADB=adb bash scripts/qa/android-smoke.sh app-debug.apk      # 先重装再跑
#   SMOKE_OUT=/tmp/smoke bash scripts/qa/android-smoke.sh ...   # 截图保存目录
# =============================================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../.." 2>/dev/null || true

ADB=${ADB:-adb}
export ADB
PKG=com.example.agentkit
OUT=${SMOKE_OUT:-/tmp/agentkit-smoke}
UI="python3 $HERE/ui.py"
APK=${1:-}

mkdir -p "$OUT"
pass=0
fail=0
ok()  { pass=$((pass + 1)); echo "  PASS  $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL  $1${2:+  → $2}"; }

shot() {
  "$ADB" shell screencap -p "/sdcard/smoke-$1.png" >/dev/null 2>&1
  "$ADB" pull "/sdcard/smoke-$1.png" "$OUT/$1.png" >/dev/null 2>&1
}

echo "== 设备 =="
"$ADB" devices | sed -n '2p'
"$ADB" shell getprop ro.product.cpu.abi | sed 's/^/  abi: /'

if [ -n "$APK" ]; then
  echo "== 安装 =="
  "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true   # 全新安装：旧数据会掩盖首启问题
  if "$ADB" install -r "$APK" 2>&1 | grep -q Success; then
    ok "安装成功"
  else
    bad "安装失败"
    exit 1
  fi
fi

echo "== 冷启动 =="
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1

started=1
for _ in $(seq 1 20); do
  if $UI has "新对话" || $UI has "三步开始" || $UI has "去配置模型"; then
    started=0
    break
  fi
  sleep 2
done
if [ "$started" -eq 0 ]; then ok "首屏已渲染"; else bad "首屏 40s 内没渲染出来"; shot "01-blank"; fi

# 引导页（仅首次安装出现）
$UI tap "先自己看看" >/dev/null 2>&1 && { ok "跳过引导"; sleep 3; }

echo "== 准备清单 =="
# 全新安装时四项权限都没给，卡片必然出现。若四项都就绪则卡片不显示，也算正常。
if $UI has "就能全自动"; then
  ok "对话页顶部有准备清单"
  # 「展开」两个字的节点在某些版本上 bounds 为 0，点标题更稳（整行都可点）
  if $UI tap "就能全自动" >/dev/null 2>&1 || $UI tap "展开" >/dev/null 2>&1; then
    sleep 2
    if $UI has "无障碍服务" || $UI has "通知权限" || $UI has "使用情况访问"; then
      ok "清单可展开且有条目"
    else
      bad "清单展开后没有条目"
      shot "02b-readiness"
    fi
  else
    bad "清单点不开"
    shot "02b-readiness"
  fi
else
  ok "准备清单未显示（说明四项都已就绪）"
fi

echo "== 抽屉 =="
# 汉堡按钮没有 content-desc，只能按比例点。比例取自 720x1280 的实测中心 (48,169)，
# 点完必须确认抽屉真的开了——不同状态栏高度会让它偏，所以带一次重试。
open_drawer() {
  for ratio in "0.067 0.132" "0.09 0.16"; do
    # shellcheck disable=SC2086
    $UI tap-ratio $ratio >/dev/null 2>&1
    sleep 2
    $UI has "控制台" && return 0
  done
  return 1
}
open_drawer || { bad "抽屉打不开"; shot "02-no-drawer"; }
missing=""
for item in 对话 模型 控制台 终端 定时任务; do
  $UI has "$item" || missing="$missing $item"
done
if [ -z "$missing" ]; then
  ok "五个一级菜单都在"
else
  bad "抽屉缺菜单：$missing" "抽屉被拆成两层时整段会塌成 0 高"
  shot "02-drawer"
fi

# 抽屉底部的「设置」在 Compose 语义树里 bounds 是 [0,0][0,0]（画面上可见），
# uiautomator 拿不到坐标，所以这里必须有坐标兜底，否则脚本会误报「入口不存在」。
if $UI scroll-to "设置" >/dev/null 2>&1; then
  ok "抽屉里能找到设置"
  sleep 3
else
  $UI tap-ratio 0.5 0.85 >/dev/null 2>&1   # 抽屉底部设置项的经验位置
  sleep 3
  ok "抽屉里能找到设置（坐标兜底）"
fi

# 用设置页特征文案确认真的进去了，不能靠「点了没报错」
if $UI has "外观" || $UI has "提醒" || $UI has "背景图"; then
  ok "已进入设置页"
else
  bad "没进入设置页"
  shot "03-no-settings-page"
fi

echo "== 容器 =="
if $UI scroll-to "容器" >/dev/null 2>&1; then
  sleep 4
else
  bad "设置里找不到容器入口"
  shot "04-no-container"
fi

# 先确认「确实到了容器页」，再做 proot 断言——否则没进页面时 has() 恒为 false，
# 会把「根本没进去」误判成「proot 正常」。
if $UI has "选择发行版启动容器" || $UI has "语言运行时" || $UI has "点击解压 rootfs"; then
  ok "已进入容器页"
  if $UI has "需要 proot 运行时" || $UI has "proot 运行时不可用"; then
    bad "容器页卡在 proot 不可用" "进页面应自动提取；提取失败会显示当前 ABI"
    shot "05-proot-missing"
  else
    ok "容器页未卡在 proot 缺失"
  fi
else
  bad "没进入容器页"
  shot "05-no-container-page"
fi

if $UI has "点击解压 rootfs"; then
  echo "  … 解压 rootfs（最多 180s；TCG 模拟器解压 8MB tar 很慢）"
  $UI tap "点击解压 rootfs" >/dev/null 2>&1
  if $UI wait "已就绪" 180; then
    ok "rootfs 解压完成（已就绪）"
  else
    bad "rootfs 180s 内未就绪"
    shot "06-extract"
  fi
else
  ok "rootfs 已是就绪状态"
fi

shot "99-final"

echo
echo "==== 冒烟结果：PASS=$pass FAIL=$fail ===="
[ "$fail" -eq 0 ] || exit 1
