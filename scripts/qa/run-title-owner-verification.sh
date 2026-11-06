#!/usr/bin/env bash
# R-02（标题写到发起会话）验证。
#
# bug 形态：`send()` 开头已经捕获了 `val owner = _activeConversationId.value`
# （hotfix30：在途写必须按发起会话落盘），但标题生成在回合末尾又取了一次
# `_activeConversationId.value`。用户在第一轮生成期间切到另一个会话，
# 标题就 rename 到**新会话**上——旧会话永远叫「新对话」，新会话被改成了
# 上一轮的问题摘要。两个会话同时错，且都不报错。
#
# 断言取「参数贯通」这一层：函数体内不出现全局会话变量，就不可能写错会话。
set -uo pipefail

cd "$(dirname "$0")/../.."
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt

fail=0
npass=0
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
good() { echo "  PASS  $*"; npass=$((npass + 1)); }

echo "=== R-02 标题写到发起会话 ==="
echo ""

[ -f "$VM" ] || { echo "FAIL 缺少 $VM"; exit 1; }

echo "[A] 回合快照存在，且标题用它"

# 只看代码，不看注释：注释里写正确写法会让断言假绿（R15 已经踩过一次）。
code_only() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$VM"; }

# 用 grep -c 而不是 grep -q：`set -o pipefail` 下，grep -q 命中即退出会让上游
# 收到 SIGPIPE（141），整条管道被判失败——断言红得莫名其妙，实际是管道语义。
# 本轮第一版就是这么红的，A1/A2 明明在源码里。
CODE=$(code_only)

n=$(echo "$CODE" | grep -cE '^[[:space:]]*val owner = _activeConversationId\.value' || true)
if [ "$n" -gt 0 ]; then
  good "A1 send() 捕获了回合归属会话 owner"
else
  bad "A1 缺少 owner 快照 —— 在途写会按「当前会话」落盘"
fi

n=$(echo "$CODE" | grep -c 'maybeGenerateTitle(owner, text)' || true)
if [ "$n" -gt 0 ]; then
  good "A2 标题生成传入 owner（发起会话）"
else
  bad "A2 标题生成没传 owner —— 用户切走后会写到新会话"
fi

n=$(echo "$CODE" | grep -c 'maybeGenerateTitle(_activeConversationId.value' || true)
if [ "$n" -gt 0 ]; then
  bad "A3 又出现「传当前会话」的写法（本次修复的就是它）"
else
  good "A3 无「传当前会话」写法"
fi

echo ""
echo "[B] 函数内部不再回读全局会话"

# 提取 maybeGenerateTitle 函数体：从签名到下一个同级 private fun / 文件尾。
body=$(awk '/private fun maybeGenerateTitle\(/{f=1} f{print} f&&/^    }$/{exit}' "$VM")
if [ -z "$body" ]; then
  bad "B1 找不到 maybeGenerateTitle 函数体"
else
  good "B1 提取到 maybeGenerateTitle（$(echo "$body" | wc -l | tr -d ' ') 行）"

  n=$(echo "$body" | grep -vE '^[[:space:]]*(//|\*)' | grep -c '_activeConversationId' || true)
  if [ "$n" -gt 0 ]; then
    bad "B2 函数体内仍读全局会话 —— 参数会被绕开"
  else
    good "B2 函数体内只用参数 convId（不读全局）"
  fi

  n=$(echo "$body" | grep -c 'conversationStore\.rename(convId'); if [ "$n" -ge 2 ]; then
    good "B3 $n 处 rename 都用 convId（本地截取 + AI 生成两条路）"
  else
    bad "B3 只有 $n 处 rename 用 convId（应为 2：本地档与模型档）"
  fi

  n=$(echo "$body" | grep -c 'conversationStore.load(convId)' || true)
  if [ "$n" -gt 0 ]; then
    good "B4 判重读的也是 convId（否则会拿新会话的标题做比较）"
  else
    bad "B4 判重没读 convId"
  fi
fi

echo ""
echo "=== $npass 通过 / $fail 失败 ==="
[ "$fail" -eq 0 ]
