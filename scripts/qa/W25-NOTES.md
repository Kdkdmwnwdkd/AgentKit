# W-25 批次记录：Plan 模式 + 审批（#58 / #62）

> 依据：缺口清单 #58（Plan 模式+审批）与 #62（BUILD/PLAN/AUTO 三模式），AiCode 招牌。
> 我们已有 `ask_user`（问一个问题）和 `todo`（存清单），但缺「先出计划、用户点头再动手」。

## 交付

| 文件 | 作用 |
|---|---|
| `core/agent/PlanBroker.kt` | 审批中转站：提交计划 → 挂起等待 → 批准/拒绝/超时/忙 |
| `tools/PlanLogic.kt` | **纯函数**：步骤清洗（去序号/项目符号/去重）、校验（空/超长/超 20 步） |
| `tools/PlanTool.kt` | `plan_propose(summary, steps)`；systemPrompt 随模式变化 |
| `ui/ChatScreen.kt` | `PlanCard`：计划卡片（步骤列表滚动 + 批准/不要执行 + 拒绝原因输入） |
| `ui/SettingsScreen.kt` | 「任务模式」开关：先计划后执行 / 自动执行 |
| `ui/ChatViewModel.kt` | planMode 状态 + planPending + approve/reject + 停止时取消挂起 |
| `core/ConfigStore.kt` | `plan_mode` 落盘（跨启动保持） |

## 两种模式

- **AUTO（默认）**：直接执行。只有破坏性操作（批量删除、装卸应用、连续点按）才建议先计划。
  系统提示是**温和建议**，不强制。
- **PLAN**：3 步以上、或会改变设备/文件状态的任务**必须先 plan_propose**，
  批准前只能只读探查。系统提示是**强指令**。

工具本身始终注册——AUTO 模式下模型也能主动提计划，PLAN 模式只是把它变成硬要求。

## 关键决策

- **真的挂起**（同 ask_user）：若只返回「计划已提交」，模型会自己继续干，用户来不及看。
- **超时的安全侧默认**：300 秒无响应 → `plan_timeout`，明确告诉模型「未获批准，
  不得执行破坏性步骤」——只允许只读探查后重新提交。宁可不做，不可乱做。
- **单计划模型**：同一时刻只允许一个待批计划（并发返回 `plan_busy`），避免用户分不清在批哪个。
- **旧卡片防误触**：approve/reject 校验 plan id，不匹配直接拒绝。
- **停止/离开时唤醒**：`stop()` 与 `onCleared()` 都调 `PlanBroker.cancel()`，
  否则协程取消后 deferred 悬着、卡片一直挂在输入栏上方。
- **步骤清洗**：模型写的 `1.` `2)` `3、` `-` `•` 前缀全部剥掉；单行用 `；` 分隔也能拆；
  去重保序。宁可多洗一步，也不要让用户看到 "1. 1. 读取文件"。

## 验证

- plan harness **12/12**（清洗 7 + 校验 5）。
- 既有 harness 无回归（27 / 25 / 14 / 9）。
- CI：见当批 run。
- APK 反验：W25 组 9 条池条目 + 5 条文案。

## 已知边界

- PLAN 模式不限制"步数"以外的执行面：批准后模型仍可能跑偏，要靠 todo + 日志兜。
- 没有实现 AiCode 的 BUILD/PLAN/AUTO **三**模式（我们是二模式：AUTO / PLAN）——
  BUILD 与 AUTO 在实际行为上区分度低，先不做。
