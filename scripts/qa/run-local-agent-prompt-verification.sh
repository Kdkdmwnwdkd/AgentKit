#!/usr/bin/env bash
# W-79 harness：本地小模型「能干点活」的三条硬契约（纯静态，只读源码）。
#
# 背景：3B 在真机上 Agent 模式的 prompt 是 1086 token，光 prefill 就要 68 秒，
# 用户看到的是「发出去没反应」。参考同类产品的做法（IRIDIS，Apache-2.0，只学原理）
# 后确认问题不在模型，在三件事上：提示词太长、解析太严、审批太吵。
#
# 这套断言只锁「契约还在」，不锁具体写法：
#   1. Agent 模式的系统提示必须整条换成英文精简版（不是追加），且每个工具一行；
#   2. 工具调用必须宽容解析（别名 / 编辑距离 / 围栏 / 标签 / 尾逗号 / 截断）；
#   3. 解析失败必须静默重试一次，且重试有硬超时（不能回到「等几分钟」）；
#   4. shell 审批必须按命令内容判定（只读放行、写操作询问）。
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)

PROMPT="$ROOT/app/src/main/java/com/example/agentkit/core/llm/local/LocalAgentPrompt.kt"
PARSER="$ROOT/app/src/main/java/com/example/agentkit/core/llm/local/LocalToolCallParser.kt"
CLIENT="$ROOT/app/src/main/java/com/example/agentkit/core/llm/local/LocalLlamaClient.kt"
RULES="$ROOT/app/src/main/java/com/example/agentkit/core/agent/ShellCommandRules.kt"
SHELL="$ROOT/app/src/main/java/com/example/agentkit/tools/ShellTool.kt"
CONTRACT="$ROOT/app/src/main/java/com/example/agentkit/core/AgentContract.kt"
EXECUTOR="$ROOT/app/src/main/java/com/example/agentkit/core/agent/ToolExecutor.kt"

pass=0
fail=0
failed=""

check() {
  local name="$1" cond="$2"
  if [ "$cond" = "1" ]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    failed="$failed\n  - $name"
    echo "FAIL: $name"
  fi
}

expect() { # 文件里必须出现某段
  local name="$1" file="$2" pattern="$3"
  if grep -qE -- "$pattern" "$file" 2>/dev/null; then check "$name" 1; else check "$name" 0; fi
}

fixed() { # 纯文本匹配（正则里有反斜杠时用它，免得转义打架）
  local name="$1" file="$2" needle="$3"
  if grep -qF -- "$needle" "$file" 2>/dev/null; then check "$name" 1; else check "$name" 0; fi
}

refute() { # 文件里必须不出现某段
  local name="$1" file="$2" pattern="$3"
  if grep -qE -- "$pattern" "$file" 2>/dev/null; then check "$name" 0; else check "$name" 1; fi
}

# —— 1. 精简系统提示 ——————————————————————————————————————————————
check "精简提示文件存在" "$([ -f "$PROMPT" ] && echo 1 || echo 0)"
expect "身份行（英文）" "$PROMPT" 'You are 北辰, an AI agent running on the user.s Android phone'
expect "规则：中文回答但提示词英文" "$PROMPT" 'Answer in Chinese'
expect "规则：一次只调一个工具" "$PROMPT" 'call ONE tool, then stop and wait'
expect "给出一个具体调用样例" "$PROMPT" '\{"tool": "read_file", "arguments"'
expect "工具按固定顺序渲染（KV 前缀稳定）" "$PROMPT" 'ORDER.indexOf'
expect "工具一行：name(参数) 形式" "$PROMPT" '\$\{tool.name\}\('
expect "枚举参数摊开取值" "$PROMPT" 'joinToString\("\|"\)'
# 提示词里不该再出现中文长句（除了名字「北辰」）
if grep -qE '【|】|请|不要|如果|可以' "$PROMPT"; then
  check "提示词不含中文长句（token 经济）" 0
else
  check "提示词不含中文长句（token 经济）" 1
fi

# —— 2. 整条替换而不是追加 ————————————————————————————————————————
expect "Agent 模式走精简消息" "$CLIENT" 'compactAgentMessages'
expect "过滤掉云端长系统提示" "$CLIENT" 'filterNot \{ it is SystemMessage \}'
expect "精简提示替换整个 SystemMessage" "$CLIENT" 'listOf\(SystemMessage\(compact\)\) \+ rest' 
expect "没有 system 通道的模型并进首条用户消息" "$CLIENT" 'supportsSystemPrompt\(\)'
refute "旧的中文工具协议已删除（只留一套口径）" "$CLIENT" 'toolProtocolPrompt'
expect "buildPrompt 不再拼工具说明" "$CLIENT" 'private fun buildPrompt\(messages: List<ChatMessage>\): String'
expect "buildPrompt 不再拼工具说明" "$CLIENT" 'private fun buildPrompt\(messages: List<ChatMessage>\): String'
expect "技能只列名字（不传整段 XML）" "$CLIENT" 'Regex\("<name>\(\.\*\?\)</name>"\)'
expect "提示长度写进日志（可核对）" "$CLIENT" 'Agent 精简提示：\$\{compact.length\} 字符'

# —— 3. 宽容解析 ————————————————————————————————————————————————
check "解析器文件存在" "$([ -f "$PARSER" ] && echo 1 || echo 0)"
expect "认 <tool_call> 原生标签" "$PARSER" 'tool_call'
expect '认代码围栏' "$PARSER" 'FENCE'
expect "括号配平取 JSON（不用 lastIndexOf）" "$PARSER" 'depth\+\+'
fixed "修尾逗号" "$PARSER" ',\\s*([}\\]])'
expect "修字符串内裸换行" "$PARSER" 'escapeRawWhitespace'
expect "修被截断的 JSON" "$PARSER" 'closeUnbalanced'
expect "工具别名表" "$PARSER" '"read_file" to listOf\("read", "cat"'
expect "编辑距离纠错" "$PARSER" 'editDistanceLe2'
expect "只返回可用列表内的名字" "$PARSER" 'canonicalize\(cleaned, available\)'

# —— 4. 静默重试（有硬超时）——————————————————————————————————————
expect "解析失败后重试一次" "$CLIENT" 'TOOL_RETRY_HINT'
expect "重试有硬超时（不会回到干等）" "$CLIENT" 'TOOL_RETRY_BUDGET_MS'
expect "重试超时会 engine.stop()" "$CLIENT" 'engine.stop\(\)'
refute "重试不无限循环" "$CLIENT" 'while \(retry\)'

# —— 5. shell 审批按命令内容 ——————————————————————————————————————
check "命令规则文件存在" "$([ -f "$RULES" ] && echo 1 || echo 0)"
expect "只读白名单存在" "$RULES" 'READ_ONLY: Set<String>'
expect "重定向一律询问" "$RULES" "command.contains\('>'\)"
expect "命令替换一律询问" "$RULES" 'SUBSTITUTION'
expect "find -delete/-exec 不算只读" "$RULES" 'FIND_WRITES'
fixed "sort -o 不算只读" "$RULES" 'startsWith("-")'
expect "shell_run 覆写 needsApprovalFor" "$SHELL" 'override fun needsApprovalFor'
expect "执行器按参数判定审批" "$EXECUTOR" 'tool.needsApprovalFor\(call.arguments\)'
expect "接口默认行为不变" "$CONTRACT" 'fun needsApprovalFor\(args: JsonObject\): Boolean = needsApproval'

echo
echo "本地 Agent 契约：$pass 通过 / $fail 失败"
if [ "$fail" -ne 0 ]; then
  echo -e "失败项：$failed"
  exit 1
fi
echo "PASS 本地 Agent 契约（提示瘦身 / 宽容解析 / 静默重试 / 命令审批）"
