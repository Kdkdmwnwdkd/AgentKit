# W-10 批：上下文预算治理（token 口径 + 图片裁剪 + 配对安全的压缩）

批次目标：把「回合内上下文」从**按条数**（`maxHistoryMessages=40`）升级为**按估算 token 预算**
治理，并解决截图 base64 每轮全量重发的问题。

## 动机（实证）

1. **条数对体积没有解释力**：单条工具结果上限 24k 字符（`MAX_TOOL_RESULT_CHARS`），
   40 条最坏 ≈ 96 万字符 ≈ 30 万 token——32k 上下文的小模型直接爆；反过来 40 条短消息
   可能不到 2k token，又白白丢历史。
2. **截图是单条最大的上下文炸弹**：`ui_screenshot` 的 768px JPEG data URL 约 6~12 万字符，
   作为 `UserMessage.images` 进入历史后，**之后每一次 LLM 请求都全量重发**。真实计费按
   图像 token（约 900/张），但上下文占用按 base64 字符算——两个口径必须分开。
3. **旧压缩会破坏工具配对**：`maybeCompact` 从中间删 `UserMessage` + 紧随 `ToolMessage`，
   不保证 `assistant(tool_calls)` 与 `tool` 结果的配对；严格供应商（OpenAI 等）会直接 400。
   且它**就地修改** messages，UI 上已展示的工具卡片会突然消失。

## 实现

| # | 文件 | 改动 |
|---|------|------|
| A1 | `core/agent/ContextBudget.kt`（新，纯 JVM） | `estimateTokens`（CJK 1 字≈1 tok / ASCII 4 字≈1 tok）；`messageTokens`（图片按张 900 tok，不按 base64 长度）；`pruneImages`（只留最近 N 张工具截图，首条用户消息的图不裁）；`fitToBudget`（按「完整步」整组丢，保留 System / 任务锚点 / 末段 / 最后一个工具步；`tool_call↔tool` 配对天然不破）；`budgetForProvider`（本地 llama 通道压到 2k——`n_ctx` 默认 4096，不能按云端 24k 发） |
| A2 | `core/agent/RealAgentLoop.kt` | 新增 `contextBudgetTokens`（默认 24k）/`keepRecentImages`（默认 2）；每轮请求前 `buildRequestMessages()`：先剥旧图再按预算压；**不再就地 `maybeCompact`**（UI/落盘历史保持完整）；`maxHistoryMessages` 转为条数硬上限继续生效 |
| A3 | `core/AgentLoop.kt`（适配层） | 透传两个新参数，默认值与实现同源（`ContextBudget` 常量） |
| H1 | `scripts/qa/ContextBudgetVerification.kt`（新） | 22 条断言：估算口径 5 / 通道预算 3 / 图片裁剪 3 / 预算压缩 11 |
| H2 | `scripts/qa/run-context-budget-verification.sh`（新） | 离线运行器（Gradle 缓存优先，沙箱回落本机 kotlinc + `/tmp/w7-jars`） |

## 关键设计决策

- **无状态请求视图，不做就地压缩**：每轮从完整历史重算「只发给 LLM 的视图」，
  天然没有「刚丢完又新增」的抖动，也因此**不需要 60% 滞回目标**（首版按滞回设计，
  harness 抓出「预算 10k 却丢了两个步」的过度丢弃，已改为「丢到刚好不超」）。
- **先剥图再算预算**：否则一张大图会被误判成「文字太多」，连累有用的工具结果被丢。
- **钉住最后一个工具步**：若末尾跟着最终文本，`lastIndex` 指向文本段，当前步会变成
  可丢的中间段——harness 抓出后补钉（预算极紧时也不能把「刚做完这一步」的证据删掉）。
- **图片单独计价**：10 万字符 base64 只算 900 tok；按字符算会高估约 100 倍。
- **本地通道单独降额**：`LocalLlamaSetting.DEFAULT_N_CTX = 4096`，系统提示 + 工具定义已占约 2k；
  `budgetForProvider("local-llama")` 压到 2k（配置更小则尊重配置），否则压缩形同虚设。

## 验证

- 第一道门（沙箱二进制编译）：`kotlinc` 编译 `AgentContract.kt` + `ToolResult.kt` +
  `ContextBudget.kt` + harness，0 error（`run-context-budget-verification.sh` 的 [1/2]）。
- 第二道门（harness 断言）：**22/22 PASS**（估算口径 5 / 通道预算 3 / 图片裁剪 3 / 预算压缩 11）。
- 第三道门：push + CI `assembleDebug`（编译门，验证 `RealAgentLoop` / 适配层接线）。
- 第四道门：APK dex 反验（特征：`ContextBudget` 类描述符、`buildRequestMessages`
  方法名、`早前截图已从上下文省略` 文案、默认预算 24000 常量）。

## 已知边界

- 估算是启发式（非分词器），对代码/JSON 类文本偏保守（高估）；宁可多丢旧历史，
  也不冒险撑爆上下文。
- 预算默认 24k 是**全局值**，暂未按模型档案配置；后续可在模型页暴露
  「上下文预算」滑杆（Phase 3 提示词/配置资产化时一并做）。
- 只治理回合内历史；跨会话的长期记忆压缩不在本批（记忆库已有独立摘要链路）。
