# W-11 批：单条工具结果体积与上下文预算对齐

W-10 落地后发现的**真实缺口**：预算压缩救不了「单条结果就超预算」的情况。

## 问题

`MAX_TOOL_RESULT_CHARS = 24_000` 是按「大上下文云端模型」定的全局上限。
本地 llama.cpp `n_ctx` 默认 4096（预算 2k token）：

- 一条 24k 字符的工具结果 ≈ 8k~24k token，**单条就远超整个预算**；
- 而当前步（含这条结果）在 W-10 里是**钉住不可丢**的——丢了等于这一步没做；
- 结果：压缩把其它步骤全丢光，请求仍然撑爆 n_ctx，llama.cpp 截断或报错。

## 修法

| # | 文件 | 改动 |
|---|------|------|
| A1 | `core/agent/ContextBudget.kt` | 新增 `toolResultLimitChars(providerId, budget)`：上限 = 预算 × 1.2 字符（CJK≈1 字 1 token，ASCII 4 字 1 token，给英文留余量），再与全局 24k 取小；下限 1000 字符防残句 |
| A2 | `core/agent/ToolExecutor.kt` | `RegistryToolExecutor` 新增 `maxResultChars`（默认全局 24k，向后兼容），执行结果截断改用它 |
| A3 | `core/agent/RealAgentLoop.kt` | 构建默认执行器时按 `llmProvider().id` + 预算传入实际上限 |
| H1 | `scripts/qa/ContextBudgetVerification.kt` | 新增 4 条断言（本地 2400 / 云端 24000 / 云端小预算 6000 / 下限 1000），26/26 PASS |

## 行为对照

| 场景 | 修前 | 修后 |
|---|---|---|
| 云端 32k+ 模型 | 单条 ≤ 24k 字符 | 不变（24k 封顶） |
| 云端小预算（5k） | 单条 ≤ 24k（≈ 6k token，仍超） | 单条 ≤ 6k 字符 |
| 本地 llama（2k） | 单条 ≤ 24k（必爆 n_ctx） | 单条 ≤ 2.4k 字符 |

截断仍走 `truncateToolResult` 的既有后缀（声明原始体量 + 建议分页参数），模型可自纠。

## 验证

- 第一道门：kotlinc 编译（ContextBudget + ToolLimits + 契约 + harness）0 error。
- 第二道门：harness **26/26 PASS**。
- 第三道门：CI run 152（`33af60d`）Build debug APK **success**。
- 第四道门：APK dex 反验 **全绿**（run 152 产物，28,255,873 B，
  sha256 `57d3c555…268c8`；`toolResultLimitChars` 已入池，W-9/W-10 判据同时回归通过）。
