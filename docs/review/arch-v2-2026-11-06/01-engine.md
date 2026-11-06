# AgentKit 引擎与回合编排 · 只读评审（HEAD 7dd0f88，2026-11-06）

范围：`core/agent/`（33 文件 6,222 行）与 `core/llm/`（31 文件 6,801 行），含 `RealAgentLoop.kt` 1,504 行、`LocalLlamaClient.kt` 958 行。只读，未改仓库，未跑 gradle。

## 结论摘要

1. `providerId` 链路已经打通（R-01/R-11），但**窗口收敛在生产中一次也不会触发**：生产 configured 预算只有 24k/12k 两种（本地另被压到 2k），而 35 家里仅 4 家声明窗口且最小 128k，`min(configured, window/2)` 恒等于 configured；代价是“已修好”的假象，换 8k 模型仍按 24k 发。
2. `RealAgentLoop` 仍是 1,504 行、**33 个构造参数**、至少 9 类职责；`TurnRequestAssembler` 没有抽出，三个装配函数仍在本类里，架构基线恰好锁在 1504 行、余量为 0；代价是下一步任何能力都必须先拆文件。
3. R-09 的 `AttemptOutputTracker` 只统计**正文**增量（阈值 160 字符），短回答并不会“永不重试”；真正的副作用是 reasoning 增量不计入，重试会清空已读的思考并整段重跑；代价是长思考场景仍会“闪没”。
4. 取消闭合只保证“有配对”，不保证“事实正确”：整批里已完成的调用，其结果在取消时被丢弃并统一改写成 `cancelled_unknown`；代价是已成功的写被报成“可能已生效”，UI 卡片与落盘记录被覆盖，模型下一轮白做核对。
5. 七道闸的骨架完整，但回执码仍是散字符串；`journal_unavailable` 分支漏传 `code`（默认 `"ok"`），时间线把失败记成成功；同时写批没有批级时限，`600s × N` 可以越过 30 分钟回合预算；代价是度量失真与极端长尾“停不下来”。
6. 本地与云端不是一套参数：请求预算 2k 常量、`n_ctx` 设置、`effectiveCtx`、`maxTokens` 四处真源，`LLMConfig.maxOutputTokens` 本地/Claude/Gemini 都不读；没有 `LocalEngine` 接口；代价是换引擎、接自建端点都要改客户端本身。

## 关键实证

> 复核状态：✔ = 逐行核对源码；○ = 由控制流/数据结构推断，未编译、未真机复跑。

| 结论 | 证据 file:line | 复核状态 |
|---|---|---|
| `LLMConfig` 已有 `providerId`，由 `ProviderManager.create` 盖章 | `core/LLMClient.kt:31-53`、`core/LLMClient.kt:900-916` | ✔ |
| `id` 已声明为协议名，`providerId` 为厂商 id；OpenAI 兼容客户端覆盖 | `core/LLMClient.kt:98-121`、`core/LLMClient.kt:229`、`core/LLMClient.kt:238` | ✔ |
| 完整链路：档案 providerId → create 盖章 → client.providerId → 查窗口 → 预算；以 deepseek 为例 `128000/2=64000`，`min(24000,64000)=24000`，与修前同值 | `ui/ChatViewModel.kt:1761-1782`、`core/llm/ProviderCatalog.kt:381`、`core/agent/RealAgentLoop.kt:1365-1377`、`core/agent/ContextBudget.kt:80-88` | ✔ |
| 生产 configured 预算只有 24k / 12k（本地再压到 2k）；目录 35 家仅 4 家声明窗口（128k/128k/128k/200k），其余 31 家为 0 | `ui/ChatViewModel.kt:1937-1961`（未传 contextBudgetTokens）、`core/agent/SubAgentRunner.kt:103`、`core/llm/ProviderCatalog.kt:118/130/138/153` | ✔ |
| 因此窗口收敛在生产中恒不改变预算；harness 自己承认默认配置下“收敛与否结果完全一样” | `scripts/qa/ProviderIdWiringVerification.kt:23-25`、`:109-117`（用 200k 才显形） | ✔ |
| `RealAgentLoop` 33 个构造参数、1,504 行；架构基线同为 1504（零余量） | `core/agent/RealAgentLoop.kt:136-263`、`scripts/qa/arch-baseline.txt:28` | ✔ |
| 装配三件套仍在类内，`TurnRequestAssembler` 全仓不存在 | `RealAgentLoop.kt:1302`、`:1328`、`:1359`；`find`/`grep` 均无 `TurnRequestAssembler` | ✔ |
| 文件里还寄生独立策略对象 `RetryBackoffPolicy`（约 100 行） | `RealAgentLoop.kt:1394-1502` | ✔ |
| R-09 判据 = 可重试 且 已上屏字符 < 160；计数在每次 attempt 开头清零 | `core/agent/RetryVisibility.kt:23`、`:32-46`、`RealAgentLoop.kt:1006`、`:1107` | ✔ |
| 只有 `TextDelta` 计入可见字符；`ReasoningDelta` 不计入，而重试会 `clearDrafts()` 连思考一起清 | `RealAgentLoop.kt:1015`、`:1018`；`ui/ChatViewModel.kt:2047-2054`；`ui/GenerationRunController.kt:112-135` | ✔ |
| 取消闭合只在 `executeBatch` 的 catch 里调用 | `RealAgentLoop.kt:670-680`、`:917-953` | ✔ |
| 批内结果要等整批结束才进 `messages`；串行 `mapIndexed` 中途抛异常则已完成结果全部丢弃 | `RealAgentLoop.kt:790-797`、`:683`、`:805-821` | ✔ |
| `closePending` 只看 `messages` 里有没有 ToolMessage，无法知道某调用其实已成功；写工具一律判 Unknown | `RealAgentLoop.kt:925-946`、`core/agent/ToolExecutor.kt:505-512` | ✔ |
| 取消时 `send()` 抛异常，完整 history 不返回；只抢救流式正文，工具结果不会作为模型历史回喂 | `ui/ChatViewModel.kt:2901-2916` | ✔ |
| 成功的 `ToolFinished` 已先落 store，随后被 `closePending` 用同一 call.id 的改判事件 upsert 覆盖；未开始的调用也会补出幻影卡 | `ui/ChatViewModel.kt:2028-2039`、`RealAgentLoop.kt:934` | ✔ |
| 全只读 `chunked(5)`：块间顺序 + `awaitAll` 保序 + 每协程局部 images 再按块合并 | `RealAgentLoop.kt:805-821` | ✔ |
| 含写整批串行；`__use_tools` 被特判为有副作用 | `RealAgentLoop.kt:750-751`、`:777-783`、`:790-797` | ✔ |
| 写预算 600s/次，只读 90s/次，工具自声明上限 30 分钟；批级无时限 | `ToolExecutor.kt:36-37`、`core/agent/ToolBudget.kt:49`、`ToolExecutor.kt:397-407` | ✔ |
| 七道闸完整：启用→熔断→审批→副作用登记→执行→截断→回灌 | `ToolExecutor.kt:276-321`、`:324-338`、`:342-368`、`:376-389`、`:397-430`、`:423`、`:425-430` | ✔ |
| `journal_unavailable` 分支构造 `Completed` 时漏传 `code`，默认成 `"ok"` | `ToolExecutor.kt:376-388`（对比 `:293-305` 均显式传 code） | ✔ |
| 回执码仍是 `String`：`ToolResult.code`、`Completed.code`、`GuardVerdict.Blocked.code`，无枚举/无全量断言 | `core/ToolResult.kt:30-40`、`ToolExecutor.kt:66-72`、`core/FailureGuard.kt:107-124` | ✔ |
| 本地客户端 958 行同时承担：加载/GPU 降级、流式生成、提示压缩、prompt 渲染、解析、诊断/看门狗 | `LocalLlamaClient.kt:48-51`、`:72-101`、`:289-490`、`:494-640`、`:680-841`、`:842-850` | ✔ |
| 本地上下文四处真源：预算 2k、`setting.nCtx`、`effectiveCtx`（安全模式 2048）、`setting.maxTokens` | `ContextBudget.kt:53/85`、`LocalLlamaSetting.kt:26/152`、`LocalLlamaClient.kt:134`、`:81/242` | ✔ |
| `LLMConfig.maxOutputTokens` 只被 OpenAI 兼容路径消费；本地用 setting，Claude/Gemini 各自硬编码 8192 | `LLMClient.kt:350/555`、`ClaudeClient.kt:62`、`GeminiClient.kt:57`、`LocalLlamaClient.kt:81` | ✔ |
| 没有 `LocalEngine` 接口：客户端参数类型是 `object LocalLlamaEngine` 本身 | `LocalLlamaClient.kt:49`、`LocalLlamaEngine.kt:22`、`ui/ChatViewModel.kt:1698` | ✔ |
| 本地引擎是进程单例，`generate` 只做引用计数不做互斥；`stop()` 停的是全局 current handle | `LocalLlamaEngine.kt:249-267`、`:273-277`、`:284-286` | ✔ |
| 子代理并行；`spawn_agents` 在目录里属 `agent` 组（非默认面，模型可 `__use_tools` 开启）→ 本地模式可并发打同一 handle | `SubAgentRunner.kt:67-72`、`ChatViewModel.kt:924/1694-1699`、`core/tool/ToolCatalog.kt:159`、`KernelExposure.kt:190` | ○ 路径可证，运行未复现 |
| 回调是**事件流**，不持有 ViewModel：`RealAgentLoop` 零 `ui/` import，经 8 个回调/观察点（onEvent/onTextDelta/onReasoningDelta/checkpoint/observer/hooks/approval/shouldContinue）驱动；其中 3 个（checkpoint/hooks/shouldContinue）生产零注入 | `RealAgentLoop.kt:1-40`、`:192-221`、`ChatViewModel.kt:1937-1961` | ✔ |
| `AgentEvent` 共 5 种，不带 turn/session/capability；三分未开始 | `core/AgentLoop.kt:57-92`；`ui/ChatViewModel.kt:2013` | ✔ |
| 另有 `TurnEvent` 8 种，带 turnId/scope（main/skill/subagent），但只在排障通道 | `core/agent/TurnObserver.kt:44-190`、`:29` | ✔ |
| `checkpoint`/`hooks`/`executorFactory`/`attemptOut`/`shouldContinue` 生产零注入（仅 harness） | `ChatViewModel.kt:1937-1961`、`SubAgentRunner.kt:97-112`；全仓 grep 无绑定 | ✔ |

### 取消 / 超时 / 异常 / 进程回收：逐路径覆盖

| 路径 | 机制 | 结论 | 证据 |
|---|---|---|---|
| 工具批内用户取消 | `executeBatch` 抛 `CancellationException` → `NonCancellable` 内 `closePending` | 只补“配对”，已完成结果丢失并改判（A1） | `RealAgentLoop.kt:672-679`、`:917-953` |
| LLM 调用中取消 | `chatOnce` 未拿到 Completed，messages 无新 tool_call | 无需闭合，安全 | `RealAgentLoop.kt:1009-1031` |
| 单次 LLM 超时 | `LlmCallTimeoutException`，回合失败，无在飞工具 | 无配对缺口；且明确不重试 | `RealAgentLoop.kt:1026-1029`、`:1185` |
| 工具级超时 | `timeoutOutcome` 返回 `Completed`（`tool_timeout` / `tool_timeout_unknown`） | 已配对，语义正确 | `ToolExecutor.kt:407-411`、`:462-496` |
| 回合预算超时 | 只在步边界软收尾，不在工具中途打断 | 无在飞调用，安全（但挡不住批内长尾，A2） | `RealAgentLoop.kt:562-578` |
| executor 违约异常 | 回合级 `ToolExecutionFailedException`，刻意不闭合 | 回合作废、history 丢弃；与取消语义相反是有意设计 | `RealAgentLoop.kt:866-870`、`:681-683` |
| 取消发生在审批前 | `onInterrupt` 只看 readOnly，`NotDispatched` 在闭合路径不可达 | 未派发的写也被报“可能已生效” | `ToolExecutor.kt:505-512`、`RealAgentLoop.kt:983` |
| 进程回收 | `RunJournalRepair` 补 `ToolSettled` 事件 + UI 提示 | 只补日志，不重建 ToolMessage（B6） | `RunJournal.kt:85-107`、`ChatViewModel.kt:1031-1069` |

### 本地 JNI / Vulkan 对架构的硬约束

- **单 ABI**：只编 `arm64-v8a`，x86_64 模拟器直接装不上（`app/build.gradle:50-53`）；这决定了本地推理无法在常规模拟器做回归，只能真机验。
- **单 context + 阻塞生成**：`nativeGenerate` 是阻塞调用，取消靠 `nativeStop(handle)` 置标志（`LlamaJni.kt:127-131`、`LocalLlamaEngine.kt:273-286`）。协程取消不能穿透 JNI，所以 `streamChat` 只能用裸 `Thread` + `invokeOnClose { engine.stop() }`（`LocalLlamaClient.kt:289-330`）；并发调用同一 handle 没有互斥（A3）。
- **Vulkan 是编译期开关、运行期一次性熔断**：`gpuBackendAvailable` 由 native 回答（`LlamaJni.kt:151-160`）；一旦 GPU 在 decode 里炸过，`gpuDisabledReason` 进程级钉死（`LocalLlamaEngine.kt:85-91`），本进程后续全部 CPU，UI 无法按会话切回。`effectiveCtx` 还会按 GPU/安全模式二次压 `n_ctx`（`LocalDeviceProfile.kt:426-447`），这是 B3 预算不一致的物理来源。

## 结构性问题

### A · 会导致错误

**A1｜取消一批，把已完成的调用改写成“未知”，并覆盖已落盘的成功卡**（✔）
- 事实：结果在批次结束后才 `messages.addAll(results)`；批次中途取消，已完成调用的 ToolMessage 只存在于被丢弃的局部返回值里。`closePending` 拿不到这些结果，只能按 `readOnly` 猜测；取消时 `send()` 也不返回 history。
- 根因：**提交粒度是“批”，不是“调用”**。`ToolCallOutcome.Completed` 在 `executeOne` 里已经产生，却没有立即进入任何可被取消路径读取的容器。
- 代价：已写成功的文件先以成功卡落盘，又被改判成 `cancelled_unknown` 覆盖；从未 `ToolStarted` 的后续调用也会被补一条幻影卡；模型永远看不到真实结果（history 被丢弃）。
- 最小修法：给 `executeBatch` 加一个 `MutableMap<String, ToolMessage>` 结果槽，`executeOne` 在 `:871-886` 产出 Completed 时立即写入；`closePending` 先取槽，再对剩余调用用 `onInterrupt` 补齐；`NotDispatched` 由槽/派发集合产生，而不是只靠 readOnly。约 40 行，不动接口。

**A2｜回合 30 分钟预算挡不住一个写批**（✔）
- 事实：`turnBudgetMs` 只在**步边界**检查（`:562-563`），批内每个写调用各自 600s（`ToolExecutor.kt:36-37`），单次调用数量无上限。一个含 5 个写调用的批次可以从第 29 分钟跑到 79 分钟。
- 根因：时间预算的作用域是“步”，执行的作用域是“调用”，中间缺一层“批”的剩余预算传递。
- 代价：用户看到的是“停止按钮还在转圈”，而设计文档承诺的 30 分钟软截止不成立；只有 `Job.cancel()` 能救。
- 最小修法：`executeBatch` 计算 `remaining = turnBudgetMs - elapsed`；每次调用前若 `remaining <= 0`，直接为剩余调用生成配对结果 `code=turn_budget`；否则把 `min(工具预算, remaining)` 作为本次上限下沉给 executor。约 50 行，纯参数传递。

**A3｜本地单例引擎没有推理互斥，取消会误杀另一路生成**（○ 路径明确，未真机复现）
- 事实：`LocalLlamaEngine.generate` 通过 `withHandle` 只做 `activeUsers++`，不持锁（`:249-267`、`:284-286`）；`stop()` 停的是全局 `current` handle（`:273-277`）。`spawn_agents` 属 `agent` 组，模型开组后 `SubAgentRunner` 会 `async` 并行跑 2–3 个本地子代理（`SubAgentRunner.kt:67-72`），它们共享同一个单例引擎。
- 根因：把“进程级单例 native context”当成了可重入资源；租约只保证不 free，不保证不并发 decode。
- 代价：同一 `llama_context` 并发 decode 属未定义行为，轻则输出串台，重则 SIGSEGV；任一路取消会把另一路也 `nativeStop`。
- 最小修法：在 `generate` 外包一把公平 `ReentrantLock`（或 `LocalLlmRuntime` 层加 `Mutex`），并把 `stop()` 改为按 generation token 只停自己那一路；或本地模式下强制 `SubAgentRunner` 串行。约 25 行。

### B · 阻碍演进

**B1｜1,504 行 / 33 参数 / 9 职责，且基线余量为 0**（✔）
- 职责清点：回合状态机与事件时钟（`:311-498`）、系统提示装配（`:1302-1345`）、请求视图预算（`:1359-1391`）、流式收集与超时（`:1004-1032`）、重试/退避/限流（`:1084-1210`、`:1394-1502`）、批调度（`:757-823`）、单调用事件/日志（`:837-953`）、工具暴露面（`:1220-1273`）、取消闭合（`:917-953`）。
- 根因：测试缝（checkpoint/hooks/executorFactory/attemptOut/shouldContinue）与生产依赖塞进同一个构造器，导致“抽一个职责”要动 33 个字段。
- 代价：`arch-baseline.txt` 锁死 1504，任何新能力都要先还债；P1-1 预计减 150 行，目前完成 0。
- 最小修法：先抽纯对象 `TurnRequestAssembler`（`buildSystemPrompt` + `logPromptPrefix` + `buildRequestMessages`，无 Android 依赖）；再把构造器拆成 `LoopDeps`（生产 12 项）+ 可选 `LoopTestSeams`。基线允许只减，降到 ~1340。

**B2｜“本地”仍由协议名/字面量判定，目录里的 `group=LOCAL` 没人用**（✔）
- 事实：超时用 `id.startsWith("local-")`（`RealAgentLoop.kt:1008`）；预算与工具档位用 `providerId == "local-llama"`（`ContextBudget.kt:85`、`KernelExposure.kt:133`）；重试档位按 style 推（`RetryProfile.kt:83-84`）。而 `ProviderCatalog.isLocal` 按 group 判定（`:409-410`），只有 UI 在用（`ModelConfigScreen.kt:323`）。
- 根因：“是否本地”被当成协议属性写在协议字符串上，而目录已经用 `group` 表达了同一事实，两个真源没有合流。
- 后果：目录里的 Ollama / LM Studio / vLLM（`ProviderCatalog.kt:339-361`，group=LOCAL、style=openai）会拿到云端策略：300s 超时、24k 预算、OFFICIAL 重试。
- 最小修法：新增单一入口 `ProviderCapabilities.of(providerId)`（isLocal / window / timeoutFactor / budgetCap / retryProfile），替换上述 4 处；验收 harness 断言 `of("ollama").isLocal == true && timeoutFactor == 3 && retry.maxRetries == 0`。

**B3｜本地上下文有四个真源，且不预留输出**（✔ 静态）
- 事实：`ContextBudget` 固定 2k（`:53/85`）；`LocalLlamaSetting.nCtx` 默认 4096（`:26/152`）；`effectiveCtx` 在安全模式压到 2048（`LocalDeviceProfile.kt:437-447`）；生成长度用 `setting.maxTokens`（默认 512，`LocalLlamaClient.kt:81/242`），`LLMConfig.maxOutputTokens` 对本地无效。2k 是**输入预算**，不含输出；按上限口径，安全模式 2048 时只剩约 48 token，默认 512 已超额（本地压缩后 prompt 通常更小，但预算仍按 2k 裁）。
- 根因：预算在 loop 侧算，`n_ctx` 在 client 侧算，两边没有交换事实。
- 代价：改大 `n_ctx` 不涨可用上下文；开安全模式/GPU 压上下文时可能触发上下文移位或截断，而日志只显示“预算 2000”。
- 最小修法：让 client 暴露实际 `contextLimit`/`maxOutputTokens`（默认 0 = 未知），`buildRequestMessages` 用 `min(configured, contextLimit - 输出预留)`，本地不再写死 2k。约 50 行；验收：nCtx=8192 → 预算随之上升，safeMode → 预算收缩且保留 512 输出。

**B4｜三套事件词汇并存，UI 事件没有归属字段**（✔）
- 事实：`RunEvent`（日志，6 种）、`TurnEvent`（时间线，8 种）、`AgentEvent`（UI，5 种）对同一动作各有说法（Dispatched/Settled vs ToolCallStarted/Ended vs ToolStarted/Finished）。`AgentEvent` 不带 turnId/sessionId/scope；会话归属仍由 `GenerationRunController.owner` 在 UI 侧补（`ChatViewModel.kt:2013-2039`）。
- 根因：三套事件各自服务一个消费方，却没有一个“事实源—投影”关系，新增语义只能三处各写一遍；`AgentEvent` 又刻意不带回合/会话信封。
- 代价：新增一种事件要改三处；§4.4 那类“切会话后回调落错对象”的 bug 只要事件不带归属就会复发。
- 最小修法：保留 `TurnEvent` 为唯一事实，`AgentEvent` 改成它的投影（adapter 仍在 core）；至少给 `AgentEvent` 加 `turnId` 与 `scope` 两个字段。零依赖，约 60 行。

**B5｜回执码未枚举，且 `journal_unavailable` 的码已经错了一次**（✔）
- 事实：`ToolResult.code`、`Completed.code`、`GuardVerdict.Blocked.code` 都是 `String`；`journal_unavailable` 结果构造时未传 `code`，时间线记成 `"ok"`（`ToolExecutor.kt:376-388`）。
- 根因：用字符串承载枚举语义，且没有任何东西逼迫新分支登记；`Completed.code` 又是可选参数，漏传不会编译失败。
- 代价：R5 时间线的 `tool.end` 与度量脚本按码聚合，会把“日志写不进、写操作被拒”统计成成功；枚举化缺失让同类错误无法被穷尽断言发现。
- 最小修法：`object ToolCodes` 收口本层出现的码（ok / tool_not_enabled / unknown_tool / duplicate_in_batch / requires_changed_call / approval_denied / journal_unavailable / exception / tool_timeout / tool_timeout_unknown，以及中断路径的 cancelled / cancelled_unknown / not_dispatched），先修 `:387` 的漏传，再加 harness 扫描 `ToolExecutor`/`RealAgentLoop` 中所有 `code = "..."` 必须属于集合。

**B6｜崩溃恢复只补日志，不补历史；“orphan → ToolMessage”的注释与实现不符**（✔/○）
- 事实：`RunJournalRepair` 只生成 `ToolSettled` 事件并写回文件（`RunJournal.kt:85-107`）；`OrphanCall` 注释写“用于生成回喂模型的 ToolMessage”（`RunJournalRepair.kt:178`），实际消费方只拼一条提示（`ChatViewModel.kt:1035-1069`）。当前之所以不 400，是因为 `buildPriorMessages` 把历史压平成“用户问题+最终回答”，工具调用根本没进下一轮（`ChatViewModel.kt:2682-2701`）。
- 根因：恢复器只对“日志文件”负责，而“下一轮请求的历史”由 `ConversationStore` 另建一条链；两条链没有共享“未配对调用”的表示。
- 代价：进程回收后的“配对”是**被历史扁平化绕过的**，不是解决的；一旦将来持久化 tool_calls，就会立刻暴露未配对请求。
- 最小修法：二选一并写进契约——(a) 明确“跨回合历史不含工具事实”，删掉误导注释并加 harness 断言 priorMessages 永不含 tool_calls；(b) 恢复时用 `AssistantCommitted` + orphan 重建配对 ToolMessage。当前阶段 (a) 成本更低（约 10 行 + 一条断言）。

### C · 不好看

**C1｜注释与代码漂移**（✔）
- 事实：`TurnObserver.kt:29` 仍写“AgentEvent（4 个）”，实际 5 个；`RealAgentLoop.kt:83/99`、`ContextBudget.kt:65`、`ProviderCatalog.kt:47` 仍把已删除的 `maybeCompact` 当兜底描述。
- 根因：注释没有守卫，重构后无人回改。最小修法：以代码为准批量改这 5 处；排序/装配类注释顺手改成引用函数名而不是历史实现名。

**C2｜文件按“历史”而不是职责组织**（✔）
- 事实：`RetryBackoffPolicy`（`:1394-1502`）与 loop 无关却同文件；构造器 KDoc 从 `:44` 写到 `:263`，真正的类体只剩 1,240 行。
- 根因：历次改动都往同一文件追加，守卫只看行数不看职责。最小修法：拆 B1 时顺手把策略对象移到 `core/agent/RetryBackoffPolicy.kt`，构造器 KDoc 压到参数表。

**C3｜字符串字面量与常量混用**（✔）
- 事实：`ChatViewModel.kt:2170/2213/2231/2332/3794` 用字面量 `"local-llama"`，其余处用 `ProviderCatalog.STYLE_LOCAL_LLAMA`；`ProviderCatalog.isLocal` 已存在却只在 UI 一处使用。
- 根因：没有“本地能力”的单一判据，只能到处写字符串。最小修法：B2 的能力函数落地后删掉这 5 处字面量，统一走 `ProviderCapabilities`。

## 与 v1.0 文档的差异

| 文档结论（2026-11-05，基线 19ee5d8） | 今天（7dd0f88）的事实 | 判定 |
|---|---|---|
| §5：`LLMConfig` 缺 `providerId`，是 R-01 根因 | `LLMConfig.kt:46` 已有，`create` 统一盖章 `:900-916` | 已过期（已修） |
| §5：`LLMClient.id` 语义过载（协议 vs 供应商） | `:98-121` 已拆成 `id`/`providerId`，OpenAI 覆盖 `:238` | 已过期；但“本地能力=协议名”的新过载仍在（B2） |
| §9-P0：补 `providerId` 传递链（窗口填 32k 不得压低预算） | 链路与 harness 都在（`ProviderIdWiringVerification.kt`），但生产预算只有 24k/12k，收敛不可观测 | 已落地但**未达成意图**；文档把“接线通”写成了“问题解决” |
| §4.1：取消时 `closePendingToolResults` 补配对，历史再发不会被拒 | 补的是“配对”，不是“真实结果”；已完成调用被改判 Unknown，history 在取消时根本不返回 | **被证伪一半**，需改写为“只保证 UI 卡片收口，不保证结果保真” |
| §4.1：系统提示每回合只拼一次、按完整步裁剪、只读并行保序 | `:529`、`ContextBudget.kt:257-315`、`:805-821` 均成立 | 仍成立（设计质量确实高于其他部分） |
| §4.2：七道闸原样保留，只差“回执码枚举化” | 七道闸都在；回执码仍未枚举，且 `journal_unavailable` 已漏码 | 前半成立，后半仍是未完成项（且新增一处 bug） |
| §4.2 / §9-P1：`TurnRequestAssembler` 应抽出，预计减 ~150 行 | 三个函数仍在 `RealAgentLoop.kt:1302/1328/1359`，全仓无该类；1553→1504 的 49 行不是这次抽取（该类不存在） | **未开始**（进度 0） |
| §9-P1-5 / §11：事件三分（session/agent/capability）未开始 | `AgentEvent` 仍 5 种、无归属；`TurnEvent` 只有 scope（agent 轴） | 仍成立；且现在多了一套 `RunEvent`，三分之前先要“三合一” |
| §11：P3 预留 `LocalEngine` 接口 | 只有 `LocalModelFormat` 枚举（GGUF/MNN/LITERT/QAIRT，`LocalBackendCatalog.kt:40-44`），无接口；客户端直连 `object` | 未开始；目录枚举比实现超前，容易被误读为“已支持多引擎” |
| §0/§2：五个文件顶格，`RealAgentLoop` 1553/1553 | 现 1504/1504（`arch-baseline.txt:28`），仍零余量 | 仍成立（数字变了，性质没变） |
| §4.4：切会话的 owner 机制正确，最后一米漏两处 | 本次未审 UI；但事件不带 session 的结构因仍在，属同一根因 | 不评价修复状态；结构风险仍成立 |

## 建议

1. **先抽 `TurnRequestAssembler`，不加任何能力。** 落点：新建 `core/agent/TurnRequestAssembler.kt`，迁 `RealAgentLoop.kt:1302-1391`；`RealAgentLoop` 只保留调用。验收：该文件可被单独 harness 编译（无 Android/loop 依赖）；`arch-baseline.txt` 的 1504 下调到 ≤1350；现有 T01/ContextBudget harness 全绿。量级：移动 ~150 行 + 改 ~40 行。
2. **让取消保留“已完成”的事实。** 落点：`RealAgentLoop.executeBatch/executeOne/closePendingToolResults`。验收：新 harness 用 3 个串行写替身，第 2 个执行中取消 → 历史中 #1 是真实成功结果、#2 是 `cancelled_unknown`、#3 是 `not_dispatched`；journal 每个 callId 只有一条 `ToolSettled`；store 中不出现“成功卡被改写成未知”。量级：~40 行。
3. **把回合剩余时间下沉到批。** 落点：`RealAgentLoop.runTurnBody/executeBatch` + `ToolExecutor.execute` 的可选预算参数。验收：注入 5 个 600s 写替身，回合总耗时 ≤ `turnBudgetMs + 单次调用`；剩余调用返回 `code=turn_budget` 且配对完整；`maxSteps` 路径不受影响。量级：~50 行。
4. **供应商能力收口为一个纯函数。** 落点：新建 `core/llm/ProviderCapabilities.kt`，替换 `RealAgentLoop.kt:1008/1227/1245/1365`、`ContextBudget.kt:85`、`KernelExposure.kt:133`。验收：`of("ollama")` 返回 local=true、timeout×3、重试 0；`of("deepseek")` 返回 local=false、窗口 128k；`of("my-endpoint")` 退回默认。量级：~60 行 + 现有 harness 扩 6 条断言。
5. **本地上下文单源化。** 落点：`LocalLlamaClient` 暴露 `contextLimit` 与 `maxOutputTokens`（默认 0），`ContextBudget.budgetForProvider` 改为接收这两个值；删除 `LOCAL_LLAMA_BUDGET_TOKENS` 的硬编码分支。验收：`nCtx=8192` 时输入预算 > 2k；`safeMode` 时预算 ≤ `2048 - 512`；`nCtx=4096` 时与今天行为一致。量级：~50 行。
6. **给本地推理加互斥；回执码枚举放到同一批收尾。** 落点：`LocalLlamaEngine.generate/stop`（公平锁 + generation 作用域），`ToolCodes` + `:387` 补码。验收：本地模式下两个并发生成，第二个等待且取消互不影响；harness 扫描所有 `code = "..."` 均属于 `ToolCodes`。量级：~40 行。

## 证据边界

- **未编译、未跑 harness**：沙箱无 Android SDK，也未执行 gradle；所有“验收”都是建议判据，不是已通过的结果。
- **取消语义是控制流推断**：A1/A2 由 `messages` 的提交时机与 `closePending` 的输入集合推出，未在真机上用慢工具复现；若某个工具实现自行把结果写进外部 store，可能掩盖部分症状，但不改变“回喂历史被丢弃”的结论。
- **本地并发（A3）未运行验证**：只能证明代码没有互斥与 `stop()` 是全局的；没有在真机上让 3 个本地子代理并发跑，故标 ○。
- **供应商窗口未联网核对**：窗口值取自 `ProviderCatalog` 源码，未验证各家 2026-11 的真实模型窗口；这不影响“生产预算恒 24k/12k、收敛不触发”的静态结论。
- **只审了引擎层**：`ChatViewModel` 只读到与 loop 接线、取消落库、历史扁平化、事件处理相关的段落；`SkillExecutor.buildToolExecutor`（`SkillExecutor.kt:496-509`）另有第二条 ToolExecutor 装配路径（无审批、无本地结果上限），说明“七道闸”并非类型保证而是调用方约定——这一条只做了点状核对，未展开。
- **行号基准**：全部指向 `7dd0f88`；`RealAgentLoop.kt` 当前 1504 行、`LocalLlamaClient.kt` 958 行、`arch-baseline.txt:28` 为 1504，若后续提交变动需重锚。
