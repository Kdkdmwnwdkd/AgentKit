# W-14 批次记录：MCP 接入 + ask_user + todo

> 依据：`docs/reference/参考仓库-索引.md` 与《四家对照-我们差什么》。
> 本批只补「投入产出比最高」的三项，不做浏览器/工作流（W-15/W-17）。

## 交付内容

| 文件 | 作用 |
|---|---|
| `core/mcp/McpServerStore.kt` | 服务器清单持久化（SharedPreferences JSON：id/name/url/enabled） |
| `core/mcp/McpToolAdapter.kt` | tools/list → AgentTool；inputSchema → JsonSchema；tools/call 结果解析（纯函数可断言） |
| `core/ToolRegistry.kt` | 加锁（运行时异步注册 vs 主线程读取） |
| `core/agent/AskUserBroker.kt` | 挂起式单问题模型（180s 超时 / Busy / 取消唤醒） |
| `tools/AskUserTool.kt` | ask_user（question + options） |
| `core/agent/TodoStore.kt` | 按会话隔离的清单存储（filesDir/todos.json） |
| `tools/TodoTools.kt` | todo_write / todo_read |
| `ui/ChatViewModel.kt` | 启动时异步接入 MCP（每台 ≤30、合计 ≤60）；askUserPending/answerAskUser；stop() 取消提问 |
| `ui/ChatScreen.kt` | 输入框上方提问卡片（选项按钮 / 自由输入） |
| `ui/McpScreen.kt` | 保存为常用服务器 + 我的服务器列表（启用/删除/点击重连） |

## 设计取舍

- **MCP 工具按写操作处理**（`readOnly=false`）：外部副作用未知，宁可串行。
- **上限 30/台、60/总**：工具描述要进系统提示，无上限会吃光上下文预算。
- **提问单问题模型**：并发会让人分不清在答哪个；超时后提示模型「按最合理假设继续并说明」。
- **清单存 Markdown 而非 JSON**：模型调用失败率更低，用户也看得懂。

## 验证

- CI run 182（`c100d99`）：Build debug APK + Publish **success**。
- APK dex 反验 **全绿**：`McpToolAdapter` / `McpServerStore` / `mcpWireName` /
  `AskUserBroker` / `TodoStore` / `ask_user` / `todo_write` / `todo_read` 入池；
  文案「需要你确认」「保存为常用服务器」「我的服务器」在包内；W-9~W-13 判据同时回归通过。
- 产物：30,110,809 B，sha256 `61acf7c7…e8828`。

## 未做（后续批次）

- WebView 浏览器五件套（W-15）
- 系统 TTS + 记忆自动写入 + BM25（W-16）
- 工作流/定时（W-17）
