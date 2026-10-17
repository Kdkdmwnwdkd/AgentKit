# W-18 批次记录：UI 兜底 + 会话检索 + 计算器

> 依据：《四家对照-我们差什么》剩余低风险项（权限后端矩阵的一格、`conversation_search`、小工具）。

## 交付

| 文件 | 作用 |
|---|---|
| `automation/UiAutomationTools.kt` | `runOnService` 增加 `shizukuCommand` 兜底；`escapeInputText` 纯函数 |
| `tools/ConversationSearchTool.kt` | 历史会话关键词检索 + `snippetAround` 纯函数 |
| `tools/CalculatorTool.kt` | 递归下降求值（无代码执行能力） |
| `ui/ChatViewModel.kt` | 注册两个工具；`conversationStore` 声明提前到 registry 之前 |

## 关键决策

- **只给 tap / long_press / swipe / input 兜底**：这些动作 `input` 能等价完成。
  `ui_tree` / `ui_click` 需要读界面结构，`input` 做不到——**做不到就如实失败**，不假装。
- **兜底是三级触发**：无障碍未连接 / 动作返回 false / 抛异常，三者都试一次 Shizuku；
  仍失败才回原错误。成功时结果码带 `_via_shizuku` 后缀，日志可追溯。
- **会话检索有硬边界**：只扫最近 30 个会话、每个前 6000 字；命中不到明确说「没找到」。
- **计算器不执行代码**：60 行递归下降，避免 eval/JS 引擎的安全面；除零、括号不配对、
  非法字符一律报错，不给模型 NaN/Infinity。

## 验证

- harness **12/12**（运算 7 + 错误 4 + 格式化 1）。
- CI run 194（`41ddc0f`）Build + Publish **success**。
- APK 反验全绿：`ConversationSearchTool` / `CalculatorTool` / `conversation_search` /
  `calculator` / `snippetAround` / `escapeInputText` + 文案「已用调试通道 input 完成」入池。
- 产物：30,588,944 B，sha256 `a4c18b41…ee682`。

## 四家对照补齐进度

| 批次 | 内容 | 版本 |
|---|---|---|
| W-14 | MCP 接入对话 + ask_user + todo | 1.182 |
| W-15 | 浏览器五件套 | 1.184 |
| W-16 | TTS + 记忆自写 + BM25 | 1.186 |
| W-17 | 定时任务 | 1.190 |
| W-18 | UI 兜底 + 会话检索 + 计算器 | 1.194 |

仍未做：子代理/任务拆分（AiCode 的 task/todo 已有 todo 部分）、`intent`/`send_broadcast`/
`chart_display` 等边缘工具、Operit 式的可视化工作流编辑器。
