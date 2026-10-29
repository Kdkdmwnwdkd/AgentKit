#!/usr/bin/env bash
# W-96 harness：聊天列表的「回合边界」契约（纯静态，只读源码）。
#
# 真机 2026-10-29 18:41 的视频里，用户的原话是「回答那个泡和我那个对话完全
# 挨在一起……视觉很差」。根因有两层：
#   1. 换手间距只有 16dp，且与同侧 8dp 只差一倍，一屏圆角块上下叠着读成一整段；
#   2. **流式草稿气泡是零间距**：它不走 itemsIndexed，自然没拿到 gap，
#      于是「正在生成的回答」直接压在提问的下边沿上，落库后又跳回 16dp。
#
# 静态能锁住的只有「间距来源」这一层（真机观感要人看），但足够防止回归：
#   - TURN_GAP 必须是 space6（24dp），且换手分支必须走它，不许再写死 space4；
#   - 草稿气泡必须与落库用同一个常量算 gap；
#   - 列表底部要留出与输入栏的呼吸（12dp），不能只有 8dp。
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)

CHAT="$ROOT/app/src/main/java/com/example/agentkit/ui/ChatScreen.kt"

pass=0
fail=0
failed=""

ok() {
  pass=$((pass + 1))
  echo "  PASS  $1"
}

bad() {
  fail=$((fail + 1))
  failed="$failed
  - $1"
  echo "  FAIL  $1"
}

# expect <描述> <文件> <正则>
expect() {
  local desc="$1" file="$2" pattern="$3"
  if [ ! -f "$file" ]; then
    bad "$desc（文件缺失：${file#$ROOT/}）"
    return
  fi
  if grep -Eq "$pattern" "$file"; then
    ok "$desc"
  else
    bad "$desc（未匹配：$pattern）"
  fi
}

# reject <描述> <文件> <正则>：这个写法必须已经不存在
reject() {
  local desc="$1" file="$2" pattern="$3"
  if [ ! -f "$file" ]; then
    bad "$desc（文件缺失：${file#$ROOT/}）"
    return
  fi
  if grep -Eq "$pattern" "$file"; then
    bad "$desc（仍然存在：$pattern）"
  else
    ok "$desc"
  fi
}

echo "W-96 聊天回合边界"

# 1. 换手间距的常量本身
expect "换手间距是 24dp（space6）" "$CHAT" 'private val TURN_GAP = AgentSpacing\.space6'

# 2. 换手分支必须引用它，不许写死
expect "换手分支走 TURN_GAP" "$CHAT" 'else -> TURN_GAP'
reject "换手分支不再写死 space4" "$CHAT" 'else -> AgentSpacing\.space4'

# 3. 同侧间距仍是 8dp（3:1 的差是分组线索，别一起放大）
expect "同发言者间距仍是 8dp" "$CHAT" 'sameSender\(prev, item\) -> AgentSpacing\.space2'

# 4. 流式草稿气泡必须有 gap，且与落库同口径
if grep -A10 'item(key = "stream-draft")' "$CHAT" | grep -q 'draftGap'; then
  ok "流式草稿气泡补上了 gap（不再零间距压在提问上）"
else
  bad "流式草稿气泡补上了 gap（item 内没有 draftGap）"
fi
if grep -A14 'item(key = "stream-draft")' "$CHAT" | grep -q 'else -> TURN_GAP'; then
  ok "草稿 gap 与落库同口径（生成→落库不跳格）"
else
  bad "草稿 gap 与落库同口径（草稿分支没用 TURN_GAP）"
fi
if grep -A14 'item(key = "stream-draft")' "$CHAT" | grep -q 'padding(top = draftGap)'; then
  ok "草稿 gap 真的应用到了布局上"
else
  bad "草稿 gap 真的应用到了布局上（缺 padding(top = draftGap)）"
fi

# 5. 列表内外留白
expect "列表左右边距 20dp（气泡不贴屏幕边）" "$CHAT" 'start = AgentSpacing\.space5'
expect "列表底部留白 12dp（不贴着输入栏）" "$CHAT" 'bottom = AgentSpacing\.space3'

# 6. 操作栏不再啃气泡圆角
expect "助手操作栏从气泡下沿退 4dp" "$CHAT" 'padding\(start = 36\.dp, top = 4\.dp\)'

echo
if [ "$fail" -eq 0 ]; then
  # 汇总行必须带 FAIL=0：run-all.sh 靠这个串识别「跑完且全过」（RESULT PASS 不认）
  echo "RESULT: PASS=$pass FAIL=0"
  exit 0
fi
echo "RESULT: PASS=$pass FAIL=$fail$failed"
exit 1
