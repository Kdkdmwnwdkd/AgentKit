# W-79：让 3B 真的能调工具（提示瘦身 / 宽容解析 / 静默重试 / 命令审批）

## 起因

用户贴来 IRIDIS 2.1.8-full 的录屏，问「它好像能干活，为什么」。

我上一轮给过错误结论：拉的是 `nerve-sparks/iris_android`（llama.cpp 官方 example 换名，
零工具），据此断言「它不能干活」。真实仓库是
[`WWRJ3321/IRIDIS`](https://github.com/WWRJ3321/IRIDIS)（Apache-2.0，323 个 Kotlin 文件、
42268 行、47 次提交、三引擎 llama.cpp / MNN / LiteRT-LM）。**只读源码学原理，没有复制任何代码**
（它 Apache-2.0，抄了虽合法但会毁掉本项目的独立性；工具语义、沙箱路径、参数也不一样）。

## 从源码读到的四个事实

| 事实 | 出处 | 对我们的意义 |
|---|---|---|
| 系统提示 **≤600 token 覆盖 22 个工具**，英文写、中文答 | `agent/core/AgentLoop.kt:49,76` | 我们 7 个工具 1086 token |
| 工具只有**一行**说明：`read_file(path, offset?, limit?): ≤200 lines` | `agent/tools/Tools.kt:170` 等 21 处 | 我们每个工具 48 字中文 + 参数类型表 |
| 解析器 **637 行**只干一件事：读懂写坏的调用 | `agent/core/ToolCallParser.kt` | 我们 12 行，且只认裸 JSON |
| 命令审批按**内容**判，不是按工具判 | `agent/core/CommandRules.kt` | 我们 `needsApproval = true`，`ls` 也弹窗 |

决定性的数字：他们把 21 条工具行压到 1095 个字符（≈270 token），加上固定骨架共约 570 token。
而 3B 在真机上 prefill 是 16 tok/s——**1086 token 要 68 秒才轮到模型开口**，这就是
「发出去没反应」的全部原因，跟模型能力无关。

## 这一版改了什么

### 1. 本地 Agent 模式改用精简系统提示（`LocalAgentPrompt.kt`）

- 整条**替换**上游拼好的系统提示（`DEFAULT_SYSTEM_PROMPT` + 技能 XML + 记忆摘要），
  不是追加——追加等于让 3B 同时读两套口径。
- 英文写、中文答：中文约 1.5 字/token，英文约 4 字符/token，同样的意思差 2.5 倍。
- 每个工具一行，参数名原样取自 schema（模型填参数要逐字对齐），枚举摊开成 `get|set`。
- 技能只列名字，描述等它真 `use_skill` 时再给。
- **工具顺序固定**（`ORDER` 表 + 未登记的按名字排）：系统提示逐字节不变，KV 前缀才命中。
- Gemma-2 这类没有 system 通道的模型，提示并进首条用户消息（否则它一条规则都看不到）。

实测渲染（7 工具 + 3 技能）：**1223 字符 ≈ 300 token**，原来 1650 字符 ≈ 1086 token。

> 注意：字符数只降了 26%，但 token 数降了 72%——中文和英文的字符/token 比不同，
> 拿字符数对比会误判。真正的指标是日志里的 `prompt N 字符` 与 native 的 prefill 耗时。

### 2. 宽容解析（`LocalToolCallParser.kt`）

四道关，全过才执行，任一关过不了才当文本：

1. **取候选**：`<tool_call>` 标签 / ``` 围栏 / 裸 JSON，按「越像调用越靠前」排序；
2. **修 JSON**：尾逗号、字符串内裸换行、Python 的 `True/False/None`、单引号、被截断的括号；
3. **认名字**：精确 → 忽略大小写 → 去分隔符 → 别名表（`cat`→`read_file`）→ 编辑距离 ≤2
   （`readflle` → `read_file`）；
4. **补参数**：`arguments` 写成 JSON 字符串再解一层；干脆平铺的，除工具名那个键以外全当参数
   （`use_skill` 自己就有 `name` 参数，不能一刀切掉）。

**只返回可用列表里的名字**——模型瞎编 `format_disk` 不会被照单全收。

### 3. 静默重试一次

流式判定为「像工具调用」→ 解析失败 → 把原输出 + 一句纠正提示接在历史后重新生成一次。
成本很低：系统提示与历史一个字节没变（KV 前缀命中），只补算几十个 token。
硬超时 45 秒，到点 `engine.stop()`，绝不回到「等几分钟」。

顺带把「憋着不上屏」的判定从「首字符是 `{`」扩展到围栏/标签——否则用户会先看到一屏
` ```tool ` 记号，解析出调用后又被工具卡片顶掉。

### 4. `shell_run` 审批按命令内容（`ShellCommandRules.kt`）

`needsApproval = true` 的问题是反向的：`ls`、`cat`、`pwd` 每条都弹窗，用户三天就形成
「见窗就点允许」的肌肉记忆，真正的 `rm -rf` 也就没人看了。

现在：47 个只读命令放行；`find -delete`、`sort -o`、`uniq a b`、`tree -o` 识别为写操作仍问；
`>`、`` ` ``、`$(...)`、`<(..)` 一律问；`a && b | c` 每一段都只读才放行。

接口上加了 `AgentTool.needsApprovalFor(args)`，**默认实现返回 `needsApproval`**，
其余 57 个工具行为逐字节不变。

## 验证

- 新增静态契约 `scripts/qa/run-local-agent-prompt-verification.sh`：**41 条**（提示瘦身 11、
  整条替换 6、宽容解析 9、静默重试 4、命令审批 11）。
- 沙箱里用 kotlinc 真编译了 `LocalToolCallParser` + `ShellCommandRules` 并跑了 **30 条**
  行为断言（含 `cat` 别名、`readflle` 纠错、截断修复、`$(rm -rf /)` 必须审批）。
  编译过程还揪出一个真 bug：`listOf("\$(", "`", "<(", ">(", "${")` 里的 `"${"` 是字符串模板
  起始符，直接把文件语法搞崩——已修为 `"\${"`。
- `run-local-inference-perf-verification.sh` 61 条回归全过。
- 全量 `run-all.sh`：本地缺 kotlinc 的行为 harness 记 FAIL（环境不具备），静态契约 PASS。

## 下一步（未做）

- 用户自定义命令规则（`git status` → 允许 / `rm` → 拒绝）接进设置页与规则表；
- 本地模式默认 `maxSteps` 压到 8；
- NPU / Vulkan（IRIDIS 的 NPU 是 SM8550 专用 71MB 库，另一个量级的工程）。
