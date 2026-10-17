package com.example.agentkit.core.agent

import com.example.agentkit.core.AgentEvent
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.DEFAULT_SYSTEM_PROMPT
import com.example.agentkit.core.FailureGuard
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.LlmHttpException
import com.example.agentkit.core.MEMORY_USAGE_PROMPT
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolApproval
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolExposure
import com.example.agentkit.core.ToolExposurePolicy
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.UserMessage
import com.example.agentkit.core.llm.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AgentLoop] 的默认实现（T01）：回合主循环——规划 -> 调工具 -> 回填 -> 再规划，
 * 直到 LLM 不再请求工具调用。旧 `com.example.agentkit.core.AgentLoop` 具体类的
 * 逻辑整体迁入本类，外部行为逐条保持（见下方「保留行为」清单）；
 * 旧类名保留为薄适配层（委托本类），ChatViewModel 零改动。
 *
 * **三段式工具执行**（T01 契约化后的回合内工具阶段）：
 *  1. **解析**：registry.descriptorOf(call.name) 拿 [ToolDescriptor]（未知工具 →
 *     合成占位 descriptor，executor 内走 T02 模糊匹配文案）；
 *  2. **执行**：[ToolExecutor]（默认 [RegistryToolExecutor]）——T03 并发判据
 *     （含写串行 / 全只读并行 + maxParallelTools 分块保序）、T04 熔断、审批、
 *     统一契约格式化、T06 截断、图片收集全部内聚在 executor 内；
 *  3. **commit + 回喂**：结果按调用序整批进历史（保序），图片汇合后附
 *     「请看下面由工具返回的画面：」UserMessage 回灌视觉模型（E1：局部 images
 *     列表由父协程按序合并，杜绝并发写丢更新）。
 *
 * **流式（T01）**：LLM 通道从裸 `chat()` 演进为 [LLMClient.streamChat]——
 * TextDelta 只进 [onTextDelta] 回调（UI 打字机），进历史的一定是 Completed
 * 整条 AssistantMessage（含 ToolCall）。默认 streamChat 实现 = chat() + 单条
 * Completed，故未覆盖流式的四个既有客户端行为零变化。
 *
 * **保留行为（回归面，逐条不许丢）**：
 *  - T02 统一契约（formatToolResult 单一出口 + 未知工具模糊匹配文案）；
 *  - T03 并发判据（batchHasMutation 含写串行/全只读并行 + maxParallelTools 分块保序
 *    + E1 局部 images 列表父协程合并）；
 *  - T04 熔断（指纹/check/onExecuted/第 3 次拦截/同批 duplicate 预计算——预计算
 *    仍在 loop 侧，经 [BatchToolExecutor] 重传 executor）；
 *  - T05（每轮 ensureActive / NonCancellable closePendingToolResults /
 *    shouldContinue 优雅收尾 / 取消绝不吞成拒绝 / 取消绝不吞成 Failed）；
 *  - T06 截断；callWithRetry/isRetryable（429/5xx 重试、4xx 快败、取消不重试）；
 *  - buildSystemPrompt（工具 systemPrompt 拼接 + 记忆摘要 + MEMORY_USAGE_PROMPT）；
 *  - maybeCompact 滑动窗口；审批流（needsApproval + ToolApproval；审批回调抛
 *    CancellationException 必须重抛不吞）；checkpoint 回调；onEvent 两个事件
 *    （ToolStarted 执行前 / ToolFinished 终态，并行时按 call.id 归并由 UI 负责）；
 *  - maxSteps=40 / maxParallelTools=5 / providerRetry=3 / maxHistoryMessages=40
 *    默认值；AgentLogger 各日志点保留。（消耗警示：步数上调直接放大 token 消耗与
 *    单回合时长——最坏 N 次 LLM 往返；上下文侧由 maxHistoryMessages/maybeCompact
 *    兜底，调整前需评估。）
 *
 * **取消 vs 失败的两条红线**：
 *  - CancellationException 永远不是失败：绝不被 executor 吞成 outcome、绝不被
 *    回合级兜底吞成 [ToolExecutionFailedException]——原样上抛，由 T05 树尾闭合收尾。
 *  - executor 自身违约（实现代码抛异常，如 NPE/Error）→ 回合级兜底转
 *    [ToolExecutionFailedException] 上抛：整个回合失败、该调用不回喂模型
 *    （业务 bug 显形）。此路径**不做**树尾闭合——回合作废，历史不回喂；
 *    与取消路径（历史要继续用，必须闭合）语义相反，刻意区分。
 *
 * @param llmProvider lambda 而非实例：模型配置可在运行时变化（每轮调用时取最新）。
 * @param registry 当前可用工具集合（受 Project 工具白名单约束）。
 * @param systemPrompt 基础系统提示（工具自带提示与记忆摘要在其上拼接）。
 * @param maxSteps 单轮最大步数（默认 40；与 core/AgentLoop.kt 适配层默认值必须一致，
 *   hotfix5 起两处均为 40，改一处务必同步另一处）。
 * @param maxParallelTools 全只读批次并行上限（分块限流，默认 5）。
 * @param providerRetry 供应商瞬时失败重试次数（默认 3，共 4 次尝试；hotfix10 上调：
 *   429 限流序列 10s/30s/60s 需 3 次重试才走得完，2 次会在第 3 次 429 后直接失败）。
 * @param maxHistoryMessages 上下文滑动窗口上限（默认 40）。
 * @param approval 危险工具审批回调（null = 关闭）。
 * @param checkpoint 检查点回调（每批消息变更后调用，断线/被杀后可恢复）。
 * @param memoryProvider 记忆摘要提供者（null = 关闭）。
 * @param onEvent 过程事件回调（null = 关闭，保持旧行为）。
 * @param shouldContinue 每轮结束复查的续跑钩子（null = 关闭；stop() 与之并列）。
 * @param onTextDelta 流式文本增量回调（null = 关闭）。
 * @param onReasoningDelta 功能五（P1-9）：推理过程增量回调（null = 关闭）。
 * @param hooks 执行过程钩子（默认 [NoopHooks]）。
 * @param executorFactory executor 工厂：每批工具执行前取一次（null = 按当前字段
 *   构造 [RegistryToolExecutor]）。测试可注入违约 executor 验证回合级兜底；
 *   update(registry) 也因此对默认 executor 即时生效。
 * @param forceFullTools Phase 2 DoD④「本地模型工具暴露降级」：强制全量工具开关
 *   （默认 false = 按 provider 自动）。provider = local-llama 时默认把暴露给 LLM 的
 *   工具面裁剪到 [ToolExposurePolicy.LOCAL_CORE_TOOLS]（≤7 个）；置 true 可强制放行
 *   全量（开发者/设置项逃生门）。云端 provider 不受此开关影响（本就全量）。
 * @param backoffPlan 第 n 次重试（1-based）应等待的计划毫秒数（默认
 *   [RetryBackoffPolicy.backoffMillis]：3s → 10s → 25s 封顶的**可配常量序列**）。
 *   测试可注入小值序列以保持 harness 毫秒级。
 * @param networkRecovery 退避期间的网络恢复等待器（默认
 *   [NetworkRecoveryWaiter.TIMER_ONLY] 纯定时降级；Android 侧由
 *   [ConnectivityNetworkRecoveryWaiter] 注入）。等待期间网络恢复 → 立即结束等待、
 *   马上重试（真机第三轮 Bug A2：关掉飞行模式后应「秒续」而不是干等）。
 * @param retryBackoff 重试退避钩子（「准备重试」事件抛出**之后**、下一次尝试**之前**
 *   调用，参数为 1-based 重试序号）。默认由 [backoffPlan] + [networkRecovery] 组合而成
 *   （[RetryBackoffPolicy.networkAware]）；测试可注入即时版以保持 harness 毫秒级。
 *   断网恢复了这种「等一会儿再试」才有意义的场景，全靠它区分「重试」与「原地冲锋」。
 */
class RealAgentLoop(
    private val llmProvider: () -> LLMClient,
    registry: ToolRegistry,
    systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    maxSteps: Int = 40,
    private val maxParallelTools: Int = 5,
    private val providerRetry: Int = 3,
    private val maxHistoryMessages: Int = 40,
    /**
     * W-10 上下文预算（估算 token）。每轮请求前按此预算构建**请求视图**：
     * 超预算时从最老的完整步开始丢（保留任务锚点与当前步），UI/落盘历史不动。
     *
     * 默认 24k token：覆盖 32k 上下文的常见小模型；[maxHistoryMessages] 作为条数硬上限
     * 继续生效（双闸）。纯函数实现见 [ContextBudget]。
     */
    private val contextBudgetTokens: Int = ContextBudget.DEFAULT_BUDGET_TOKENS,
    /** W-10：请求视图里最多保留最近几条工具截图的 base64（更早的剥成文字标记）。 */
    private val keepRecentImages: Int = ContextBudget.DEFAULT_KEEP_IMAGES,
    private val approval: ToolApproval? = null,
    private val checkpoint: ((List<ChatMessage>) -> Unit)? = null,
    memoryProvider: (() -> String)? = null,
    private val onEvent: ((AgentEvent) -> Unit)? = null,
    private val shouldContinue: (() -> Boolean)? = null,
    private val onTextDelta: ((String) -> Unit)? = null,
    /** 功能五（P1-9）：推理过程增量回调（null = 关闭）。与 [onTextDelta] 分流。 */
    private val onReasoningDelta: ((String) -> Unit)? = null,
    private val hooks: Hooks = NoopHooks,
    private val executorFactory: (() -> ToolExecutor)? = null,
    private val forceFullTools: Boolean = false,
    private val networkRecovery: NetworkRecoveryWaiter = NetworkRecoveryWaiter.TIMER_ONLY,
    private val backoffPlan: (attempt: Int) -> Long = RetryBackoffPolicy::backoffMillis,
    /**
     * 429 限流专用退避计划（hotfix10，默认 [RetryBackoffPolicy.rateLimitBackoffMillis]：
     * 10s → 30s → 60s 封顶 + 尊重服务端 Retry-After）。
     *
     * 与 [backoffPlan] 分开而非合并：harness 大量注入 `backoffPlan = { 小值 }` 保持毫秒级，
     * 合并会破坏所有既有注入点；分开则「普通故障走通用序列、限流走限流序列」各行其道。
     */
    private val rateLimitPlan: (attempt: Int, retryAfterMs: Long) -> Long =
        RetryBackoffPolicy::rateLimitBackoffMillis,
    /**
     * 429 限流专用退避钩子（hotfix10，默认 [RetryBackoffPolicy.networkAwareRateLimit]）。
     * 与 [retryBackoff] 分开：harness 大量注入 `retryBackoff`/`backoffPlan` 保持毫秒级，
     * 且 429 与服务端 Retry-After 需要额外的毫秒参数——合并签名会破坏所有既有注入点。
     */
    private val rateLimitBackoff: suspend (attempt: Int, retryAfterMs: Long) -> Unit =
        RetryBackoffPolicy.networkAwareRateLimit(rateLimitPlan, networkRecovery),
    private val retryBackoff: suspend (attempt: Int) -> Unit =
        RetryBackoffPolicy.networkAware(backoffPlan, networkRecovery)
) : AgentLoop {

    /**
     * T04：失败熔断 + 调用指纹去重。
     *
     * **回合作用域（W-8）**：随 loop 活在 ViewModel 上（不随每批重建的 executor 丢失），
     * 但 [send] 入口 [FailureGuard.resetForTurn] 清空——回合内跨批次保留（防模型同参
     * 打转的本意不变），跨回合/跨会话不继承。修前全仓无人 clear：真机日志里上个回合
     * 两次 service_not_ready（瞬时故障）把恒定指纹的失败计数永久留下，下个回合服务
     * 就绪后 ui_tree 仍被 requires_changed_call 挡死，模型「有眼无手」任务必败。
     */
    private val failureGuard = FailureGuard()

    /**
     * T05：stop() 优雅停止通道（[AgentLoop.stop] 的实现）——与 shouldContinue
     * 并列的编程式触发，不杀协程；取消（Job.cancel）仍是另一条独立通道。
     *
     * **回合作用域（Round1 QA Minor 修复）**：标志只在 [send] 执行期间有效——
     * [send] 入口无条件重置，上一回合的停止请求绝不被下一回合继承；[stop] 在
     * 无在飞回合时的置位是无害空转（下一轮 [send] 入口即被清掉）。
     */
    private val stopRequested = AtomicBoolean(false)

    /**
     * 是否有回合在飞（[send] 入口置 true、退出必置 false，含异常路径）。
     * 仅供 [stop] 区分日志语义（在飞收尾 vs 空转 no-op），不参与停止判据——
     * 判据永远是 [stopRequested] 本身，避免竞态吞掉真实的停止请求。
     */
    private val turnActive = AtomicBoolean(false)

    /** 可运行时更新字段（[update] 只改这四项；@Volatile：update 可能来自 UI 线程）。 */
    @Volatile
    private var registry: ToolRegistry = registry

    @Volatile
    private var systemPrompt: String = systemPrompt

    @Volatile
    private var memoryProvider: (() -> String)? = memoryProvider

    @Volatile
    private var maxSteps: Int = maxSteps

    /**
     * 执行一轮对话，返回完整消息历史（语义 = 旧 AgentLoop.run()）。
     *
     * 结束路径：① LLM 不再请求工具（正常结束）② shouldContinue/stop() 优雅收尾
     * ③ 达到 maxSteps；取消路径抛 CancellationException（树尾闭合后重抛）；
     * executor 违约路径抛 [ToolExecutionFailedException]（回合作废，不回喂）。
     *
     * **stop 标志回合作用域（Round1 QA Minor 修复）**：入口无条件重置
     * [stopRequested]——第 1 回合 stop 后，第 2 回合不被残留标志优雅停止。
     *
     * **熔断状态回合作用域（W-8）**：入口同样无条件 [FailureGuard.resetForTurn]——
     * 上一回合的失败指纹（尤其 service_not_ready 类瞬时故障的累加）不污染本轮；
     * 回合内跨批次保留，同参打转防护不受影响。
     */
    override suspend fun send(
        userInput: String,
        images: List<String>
    ): List<ChatMessage> {
        AgentLogger.i("AgentLoop", "开始运行，输入：${userInput.take(120)}")
        // Round1 QA Minor 修复：stop 标志回合作用域化——入口先重置（上一回合的
        // 停止请求绝不被本轮继承），再标记在飞；finally 保证异常路径也清零。
        stopRequested.set(false)
        // W-8：熔断状态同样回合作用域化——上一回合的失败指纹（含 service_not_ready
        // 瞬时故障累加）绝不被本轮继承；回合内跨批次保留，防同参打转的本意不变。
        failureGuard.resetForTurn()
        turnActive.set(true)
        try {
            return runTurn(userInput, images)
        } finally {
            turnActive.set(false)
        }
    }

    /** [send] 的回合体（抽出的目的是让 stop 标志的重置/在飞标记包住整个回合，含异常路径）。 */
    private suspend fun runTurn(
        userInput: String,
        images: List<String> = emptyList()
    ): List<ChatMessage> {
        val loopStart = System.currentTimeMillis()
        // Phase 2 DoD④：按当前 provider 计算本回合的「工具暴露面」——provider = local-llama
        // 时裁剪到核心集（≤7 个）。每回合算一次：系统提示拼接与发给 LLM 的工具列表共用同一
        // [exposure]，保证「给了哪些工具」与「提示里教了哪些工具」始终一致。
        val exposure = currentExposure()
        logExposure(exposure)
        // 回合级工具视图：未裁剪时为原 registry（零开销、行为不变）；裁剪时仅含暴露工具——
        // 被裁工具即便被本地模型瞎编调用，也在此视图中「查无此工具」，走 T02 未知工具路径。
        val turnRegistry = scopedRegistry(exposure)
        val messages = mutableListOf<ChatMessage>(
            SystemMessage(buildSystemPrompt(exposure.tools)),
            // 功能四（P0-2）：用户手动附带的图片随首条用户消息一起发
            UserMessage(userInput, images)
        )
        checkpoint?.invoke(messages)

        repeat(maxSteps) {
            // T05：每轮开始复查取消——UI 点「停止」后 ≤1 轮内退出
            currentCoroutineContext().ensureActive()
            // T01 钩子：序列化前（每次 LLM 请求前一次；快照传参，钩子看不到后续变更）
            hooks.beforeSerialization(messages.toList())
            val stepStart = System.currentTimeMillis()
            // W-10：只把「裁剪后的请求视图」发给 LLM——旧截图剥图、超预算丢最老步；
            // messages 本身保持完整（UI 工具卡片与落盘历史不受影响）。
            // 视图在重试循环外算一次：同一请求的多次重试必须发完全相同的载荷。
            val requestMessages = buildRequestMessages(messages)
            val resp = try {
                callWithRetry { chatOnce(requestMessages, exposure) }
            } catch (e: Throwable) {
                AgentLogger.e("LLM", "调用失败", e)
                throw e
            }
            AgentLogger.i(
                "AgentLoop",
                "LLM 返回：${System.currentTimeMillis() - stepStart}ms，toolCalls=${resp.toolCalls.size}"
            )
            messages.add(resp)
            checkpoint?.invoke(messages)

            // LLM 不再请求工具调用 -> 给出最终回答，结束循环
            if (resp.toolCalls.isEmpty()) {
                AgentLogger.i(
                    "AgentLoop",
                    "运行结束：${System.currentTimeMillis() - loopStart}ms，消息数=${messages.size}"
                )
                hooks.beforeStop()
                return messages
            }

            // T01 三段式之执行 + commit：本批工具执行（T03 并发判据在 executor/loop 协作下生效）
            val stepImages = mutableListOf<String>()
            val results = try {
                executeBatch(resp.toolCalls, stepImages, turnRegistry)
            } catch (cancel: CancellationException) {
                // T05 树尾闭合：不信任取消时 execute() 的返回值——工具被 kill 后可能返回假成功。
                // 在 NonCancellable 内给本 Assistant 里所有**未配对**的 ToolCall 补错误 ToolResult，
                // 保证「已 commit 的 ToolCall 必有配对 ToolResult」，再重抛取消。
                withContext(NonCancellable) { closePendingToolResults(resp.toolCalls, messages, turnRegistry) }
                throw cancel
            }
            // 注：ToolExecutionFailedException 不在此捕获——回合级失败语义就是
            // 「整轮作废、不回喂」，原样上抛给调用方（业务 bug 显形）。
            messages.addAll(results)
            // 若本轮有工具返回图片（如 ui_screenshot），附到下一轮对话让视觉模型“看见”
            if (stepImages.isNotEmpty()) {
                messages.add(UserMessage("请看下面由工具返回的画面：", stepImages.toList()))
            }
            checkpoint?.invoke(messages)
            // W-10：压缩不再就地改 messages（旧实现 maybeCompact 会删掉 UI 已展示的工具卡片，
            // 且在「刚丢完又新增」之间来回抖动）——改为下一次请求时按预算构建只读视图，
            // 见 buildRequestMessages。此处保留 checkpoint，UI/落盘仍拿到完整历史。

            // T05：每轮结束再复查一次（工具批可能执行很久，结束时 UI 可能已点停止）
            currentCoroutineContext().ensureActive()
            // shouldContinue 显式返回 false 或收到 stop() 请求才停止；null（未配置）不影响流程
            if (!shouldContinueNow()) {
                AgentLogger.i("AgentLoop", "shouldContinue 返回 false 或收到 stop() 请求，中途停止")
                hooks.beforeStop()
                messages.add(AssistantMessage("⏹ 已根据请求停止。"))
                return messages
            }
        }

        AgentLogger.w("AgentLoop", "达到最大步数 $maxSteps，任务可能未完成")
        hooks.beforeStop()
        messages.add(AssistantMessage("⚠️ 已达到最大步数（$maxSteps），任务可能未完全完成。"))
        AgentLogger.i(
            "AgentLoop",
            "运行结束：${System.currentTimeMillis() - loopStart}ms，消息数=${messages.size}"
        )
        return messages
    }

    /**
     * [AgentLoop.stop]：置优雅停止标志（shouldContinue=false 语义），不杀协程。重复调用幂等。
     *
     * **回合作用域（Round1 QA Minor 修复，最小惊讶裁决）**：只对当前在飞的回合生效——
     *  - 有回合在飞：本回合剩余步骤结束后优雅收尾（[shouldContinueNow] 判 false）；
     *  - 无回合在飞（两回合之间 / 首轮之前）：**明确 no-op**——标志虽置位，但下一轮
     *    [send] 入口即重置，绝不影响新一轮。理由：用户停止的是旧回合，随后的新输入
     *    理应完整运行；若记「待停止」意图，新一轮会被莫名秒杀，反直觉。
     * 取消（Job.cancel）通道独立，互不依赖。
     */
    override fun stop() {
        if (stopRequested.compareAndSet(false, true)) {
            if (turnActive.get()) {
                AgentLogger.i("AgentLoop", "收到 stop() 请求：当前回合结束后优雅收尾（协程取消通道独立，互不依赖）")
            } else {
                AgentLogger.i("AgentLoop", "收到 stop() 请求但无在飞回合：按 no-op 处理（下一轮 send() 入口重置，不影响新一轮）")
            }
        }
    }

    /** [AgentLoop.update]：按非 null 项更新配置，null = 保持现值。 */
    override fun update(
        systemPrompt: String?,
        memoryProvider: (() -> String)?,
        registry: ToolRegistry?,
        maxSteps: Int?
    ) {
        systemPrompt?.let { this.systemPrompt = it }
        memoryProvider?.let { this.memoryProvider = it }
        registry?.let { this.registry = it }
        maxSteps?.let { this.maxSteps = it }
        AgentLogger.i(
            "AgentLoop",
            "运行时配置已更新（systemPrompt/memoryProvider/registry/maxSteps 按非 null 项生效）"
        )
    }

    /**
     * 执行一批工具调用（T03 并发判据 + T04 同批重复判定 + T01 三段式执行/commit）。
     *
     * - 同批重复：按指纹预计算 duplicate 标志（只放行每指纹首个），后续同名同参调用
     *   直接熔断回错误，不真执行（判定在 [FailureGuard.check]，标志经
     *   [BatchToolExecutor] 重传 executor）。
     * - 含写操作（任一工具 !readOnly）→ 整批**串行**（for 顺序执行，日志可见顺序）；
     *    全批只读 → async + awaitAll **并行**（按 maxParallelTools 分块限流）。
     * - 保序 commit：两种路径的结果列表都与调用序一一对应（块序 + 块内 awaitAll 保序）。
     *
     * Phase 2 DoD④：[turnRegistry] 为本回合的工具视图（未裁剪 = 原 registry；裁剪时仅含
     * 暴露工具）。descriptor 解析、并发判据、executor 一律以它为界——被裁工具据此「查无此工具」。
     */
    private suspend fun executeBatch(
        calls: List<ToolCall>,
        images: MutableList<String>,
        turnRegistry: ToolRegistry
    ): List<ToolMessage> {
        val seenFingerprints = HashSet<String>()
        val duplicateFlags = calls.map { call ->
            val fp = failureGuard.fingerprint(call.name, call.arguments)
            !seenFingerprints.add(fp) // 已出现过 -> 同批重复
        }
        // T01 三段式之解析：descriptor 快照（未知工具 -> 合成占位，executor 内走模糊匹配文案）。
        // Phase 2 DoD④：以「回合级工具视图」解析——被裁工具在此为 null，走未知工具路径。
        val descriptors = calls.map { turnRegistry.descriptorOf(it.name) }
        val unknownCount = descriptors.count { it == null }
        if (unknownCount > 0) {
            AgentLogger.w("AgentLoop", "本批含 $unknownCount 个未知工具调用（走 T02 模糊匹配文案）")
        }
        val hasMutation = turnRegistry.batchHasMutation(calls)
        AgentLogger.i(
            "AgentLoop",
            "本批 ${calls.size} 个调用：hasMutation=$hasMutation（${if (hasMutation) "串行" else "并行"}）"
        )
        val executor = toolExecutor(turnRegistry)
        return if (hasMutation) {
            // 含写整批串行：单协程顺序执行，直接写 images 无并发问题；顺序确定，
            // 便于日志审计与用户预期
            calls.mapIndexed { i, call ->
                executeOne(executor, call, descriptors[i], duplicateFlags[i], images).message
            }
        } else {
            // 全批只读：并行（分块限流，块间仍保序；块内 awaitAll 保序）
            // E1 修复：images（send() 的 stepImages）是普通 ArrayList，不能被多个
            // async 协程并发 addAll——并发写会丢更新（同索引互相覆盖）、 resize 时
            // 可能 ArrayIndexOutOfBoundsException、且无 happens-before 可见性无保证。
            // 改为每个 async 只写自己的局部列表，awaitAll 汇合后由父协程按调用序
            // 合并：数据竞争从构造上消除，收集顺序仍确定（块序 + 块内调用序）。
            calls.withIndex()
                .chunked(maxParallelTools)
                .flatMap { chunk ->
                    coroutineScope {
                        chunk.map { (i, call) ->
                            async {
                                val localImages = mutableListOf<String>()
                                executeOne(executor, call, descriptors[i], duplicateFlags[i], localImages) to localImages
                            }
                        }.awaitAll()
                            .onEach { (_, localImages) -> images.addAll(localImages) }
                            .map { (completed, _) -> completed.message }
                    }
                }
        }
    }

    /**
     * 单步执行 + 过程事件上报（Hotfix2/T01）。
     *
     * ToolStarted 在真正执行前抛出（此时参数已定、结果未出，UI 显示「执行中」卡）；
     * ToolFinished 在产出终态 message 后抛出（UI 把同一张卡翻成终态）。
     * 并行执行时多个调用的事件会交错到达，UI 侧按 call.id 归并，不依赖顺序。
     *
     * 异常语义（T01 验收③）：
     *  - CancellationException 原样上抛（取消绝不是失败，由树尾闭合收尾）；
     *  - 其余异常 = executor 自身违约 → 包装 [ToolExecutionFailedException] 上抛，
     *    整个回合失败、该调用不回喂模型。
     */
    private suspend fun executeOne(
        executor: ToolExecutor,
        call: ToolCall,
        descriptor: ToolDescriptor?,
        duplicateInBatch: Boolean,
        images: MutableList<String>
    ): ToolCallOutcome.Completed {
        onEvent?.invoke(AgentEvent.ToolStarted(call))
        val outcome = try {
            val d = descriptor ?: ToolDescriptor(call.name, call.name, "{}")
            if (executor is BatchToolExecutor) {
                executor.execute(call, d, duplicateInBatch)
            } else {
                executor.execute(call, d)
            }
        } catch (cancel: CancellationException) {
            // 取消绝不是失败：绝不上包装修、绝不变 Failed，原样上抛
            throw cancel
        } catch (e: Throwable) {
            // executor 实现代码违约（NPE/Error/替身故意抛）：回合级失败，不回喂模型
            AgentLogger.e("AgentLoop", "executor 违约（${call.name}），回合失败", e)
            throw ToolExecutionFailedException(call.name, e)
        }
        if (outcome is ToolCallOutcome.Completed) {
            images.addAll(outcome.images)
            onEvent?.invoke(AgentEvent.ToolFinished(outcome.message))
            return outcome
        }
        // 防御：execute() 契约规定只返回 Completed（业务失败也在内）。若实现违规
        // 返回中断态，按取消闭合补齐一条错误 ToolResult 后继续回合——不变量
        // 「已 commit 的 ToolCall 必有配对 ToolResult」不依赖 executor 自律。
        AgentLogger.w("AgentLoop", "executor 对 ${call.name} 返回了中断态 outcome，按闭合语义补齐")
        val filled = interruptToolMessage(call, outcome)
        onEvent?.invoke(AgentEvent.ToolFinished(filled))
        return ToolCallOutcome.Completed(filled, filled.images)
    }

    /**
     * T05 树尾闭合：给 [calls] 中所有**未配对**（历史里还没有同 id ToolMessage）的
     * ToolCall 补一条错误 ToolResult，并抛 ToolFinished 事件把 UI「执行中」卡翻终态
     * （不存在永远转圈的工具卡）。
     *
     * T01：每个未配对调用先经 [ToolExecutor.onInterrupt] 自判 Interrupted/Unknown
     * （只读→确定未完成；写→可能已有副作用），文案据此区分 code=cancelled 与
     * code=cancelled_unknown——后者明确要求模型先向用户确认再决定是否重试。
     *
     * 不变量保证点：本方法是 send() 取消路径的唯一收尾——调用后 messages 中每个已
     * commit 的 Assistant.toolCalls 元素都必有配对 ToolMessage，历史再发请求不会
     * 因「未闭合 tool_call」被 API 拒绝。
     */
    private suspend fun closePendingToolResults(
        calls: List<ToolCall>,
        messages: MutableList<ChatMessage>,
        turnRegistry: ToolRegistry
    ) {
        val paired = messages.filterIsInstance<ToolMessage>().mapTo(HashSet()) { it.id }
        val executor = toolExecutor(turnRegistry)
        var filled = 0
        for (call in calls) {
            if (call.id in paired) continue
            val descriptor = turnRegistry.descriptorOf(call.name) ?: ToolDescriptor(call.name, call.name, "{}")
            val verdict = executor.onInterrupt(call, descriptor)
            val msg = interruptToolMessage(call, verdict)
            messages.add(msg)
            onEvent?.invoke(AgentEvent.ToolFinished(msg))
            filled++
        }
        if (filled > 0) {
            checkpoint?.invoke(messages)
            AgentLogger.w("AgentLoop", "取消闭合：补填 $filled 个待决 ToolResult")
        }
    }

    /**
     * 中断态（Interrupted/Unknown）→ 回喂的错误 ToolMessage（树尾闭合 / 防御填充共用）。
     * Interrupted（只读，确定未完成、无副作用）→ code=cancelled；
     * Unknown（写操作被 kill，可能已有副作用）→ code=cancelled_unknown，文案要求
     * 模型先向用户确认再决定是否重试，不要原样重复调用。
     */
    private fun interruptToolMessage(call: ToolCall, verdict: ToolCallOutcome): ToolMessage =
        when (verdict) {
            is ToolCallOutcome.Interrupted -> toolMessageOf(
                call,
                ToolResult.fail(
                    code = "cancelled",
                    message = "该调用已被用户停止，未取得真实结果。",
                    hint = "如仍需此操作，请在下一轮重新调用 ${call.name}（可调整参数）。"
                )
            )

            is ToolCallOutcome.Unknown -> toolMessageOf(
                call,
                ToolResult.fail(
                    code = "cancelled_unknown",
                    message = "该调用已被用户停止，执行状态未知（写操作可能已产生部分副作用）。",
                    hint = "请先向用户确认 ${call.name} 是否已实际生效，再决定是否重试；不要原样重复调用。"
                )
            )

            is ToolCallOutcome.Completed -> verdict.message
        }

    /**
     * 单次 LLM 调用（T01 流式通道）：收集 [LLMClient.streamChat]——
     * TextDelta 转 [onTextDelta] 回调（UI 打字机，不进历史），Completed 整条
     * commit（进历史的唯一形态）。未覆盖流式的客户端走默认实现（chat() +
     * 单条 Completed），行为与旧裸 chat() 调用等价。
     *
     * 已知取舍：重试（[callWithRetry]）以「整次收集」为粒度——流式中途遇可重试
     * 错误时重新收集，已发出的 TextDelta 会重复下发（UI 侧按追加渲染可接受）；
     * 换来的是供应商瞬时故障不丢轮次。默认（非流式）实现无此问题。
     */
    private suspend fun chatOnce(messages: List<ChatMessage>, exposure: ToolExposure): AssistantMessage {
        var completed: AssistantMessage? = null
        // Phase 2 DoD④：发给 LLM 的工具定义用本回合的暴露面（本地小模型 ≤7 个），
        // 与系统提示拼接的 exposure.tools 同源，保证「给的工具」与「教的工具」一致。
        llmProvider().streamChat(messages, exposure.tools).collect { event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> onTextDelta?.invoke(event.text)
                // 功能五（P1-9）：推理过程增量与正文分流上抛（UI 折叠块实时渲染），
                // 同样不进历史——进历史的只有 Completed 整条（message.reasoning）
                is ChatStreamEvent.ReasoningDelta -> onReasoningDelta?.invoke(event.text)
                is ChatStreamEvent.Completed -> completed = event.message
            }
        }
        return completed
            ?: throw IllegalStateException("streamChat 未产生 Completed 事件（供应商违反流式契约）")
    }

    /**
     * 供应商瞬时失败重试（429/5xx 与网络异常才重试）。
     *
     * 401/403/400 这类鉴权与参数错误重试多少次都不会变，必须一次性快速失败，
     * 否则用户填错 Key 时会白等 providerRetry 次请求才看到报错。
     *
     * **Phase 2 DoD③：重试过程对 UI 可见**。每次「准备重试」抛
     * [AgentEvent.RetryStarted]（attempt/max/reason）；本轮最终成功 / 最终失败 /
     * 用户取消时**恰好一次**抛 [AgentEvent.RetryFinished] 收尾——由 `finally`
     * 统一保证（含取消路径），UI 据此清除「正在恢复」横幅。
     *
     * 语义边界：
     *  - 「准备重试」只在**确实还有下一次尝试**时抛出（`attempt < providerRetry`），
     *    故 attempt 序号 1..providerRetry，绝不出现 attempt > max；总请求数 ≤ max + 1。
     *  - 首次即成功、或非可重试错误快败时**从不**抛 RetryStarted（无状态可清），
     *    收尾的 `retrying` 标志同时守护 RetryFinished 不空抛。
     *  - 取消（CancellationException）原样上抛、绝不重试；`finally` 立即清状态——
     *    T05 不变量：用户点停止后不得残留「正在恢复」。
     *
     * **退避（机实测 Bug 1 修复 / 真机第三轮 Bug A1 加长）**：每次宣布「准备重试」后、
     * 真正重发之前，先走 [retryBackoff] 退避（默认序列 3s → 10s → 25s 封顶）。
     * 理由有二：
     *  1. **UI 可见性**：`UnknownHostException`（飞行模式）是**瞬时返回**的，无退避时
     *     3 次尝试几十毫秒内打完，`RetryStarted` 刚置位就被 `finally` 的 `RetryFinished`
     *     清掉——用户永远看不到「正在恢复（n/N）」横幅，只看到最后那个错误；
     *  2. **恢复窗口**：退避给了网络实际的恢复时间，这才是「重试」的意义。上一轮
     *     （1s/2s/4s，含请求耗时约 12s）被用户判为「太短」，故本轮加长到「半分钟」量级。
     * 不可重试错误（401/403/400）**不等待**、一次即失败（高频快败路径零新增延迟）。
     *
     * **网络恢复即刻重试（真机第三轮 Bug A2）**：默认退避钩子是「网络感知」的——
     * 等待期间由 [NetworkRecoveryWaiter] 监听默认网络，一恢复就**提前结束等待**并立刻
     * 发起下一次尝试（用户关掉飞行模式后「秒续」）。监听在等待结束（含取消路径）时
     * 注销；拿不到网络服务 / 注册失败一律降级为纯定时等待，绝不崩。
     *
     * **取消优先**：退避期间若协程被取消，必须立刻退出（[delay] 自带取消语义）；
     * 退避钩子本身是否可取消是注入方的自由，故钩子返回后再补一次
     * `ensureActive()`——即使是 `{ Thread.sleep(n) }` 这种不可取消钩子，取消也不会
     * 让它多跑一次无用请求（T05：点停止不得残留「正在恢复」，也不得多等）。
     */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        var last: Throwable? = null
        // 本轮是否对外抛出过重试状态（决定收尾是否要抛 RetryFinished）
        var retrying = false
        try {
            repeat(providerRetry + 1) { attempt ->
                try {
                    return block()
                } catch (cancel: CancellationException) {
                    // 取消立即上抛，绝不重试；finally 会立刻清掉「正在恢复」
                    throw cancel
                } catch (e: Throwable) {
                    last = e
                    if (!isRetryable(e)) {
                        AgentLogger.w(
                            "LLM",
                            "不可重试错误，快速失败（第 ${attempt + 1} 次尝试）：${e.message?.take(160)}"
                        )
                        throw e
                    }
                    AgentLogger.w(
                        "LLM",
                        "第 ${attempt + 1} 次调用失败，准备重试：${e.message?.take(160)}"
                    )
                    // 仅当确实还有下一次尝试时才通知 UI「准备重试」（attempt 为 1-based 重试序号）
                    if (attempt < providerRetry) {
                        onEvent?.invoke(
                            AgentEvent.RetryStarted(attempt + 1, providerRetry, retryReason(e))
                        )
                        retrying = true
                        // 先公告再退避：让「正在恢复（n/N）」有稳定可见时长，并留出恢复窗口
                        // hotfix10：429 限流走专用序列（10s/30s/60s + 尊重 Retry-After），
                        // 不再用 3s/10s 通用序列硬刚限流（真机 19:05 连续 3 次 429 挂掉的根因）。
                        val rateLimited = (e as? LlmHttpException)?.takeIf { it.code == 429 }
                        if (rateLimited != null) {
                            rateLimitBackoff(attempt + 1, rateLimited.retryAfterMs)
                        } else {
                            retryBackoff(attempt + 1)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            throw last ?: IllegalStateException("未知错误")
        } finally {
            // 成功 / 最终失败 / 取消——只要抛出过重试状态就必须收尾清除（T05：取消不残留「正在恢复」）
            if (retrying) onEvent?.invoke(AgentEvent.RetryFinished)
        }
    }

    /**
     * 可重试错误 → 面向用户的一句话原因（横幅「正在恢复（n/N）· 原因」用）。
     * 纯函数：仅依赖异常类型与 HTTP 状态码，**绝不回显**可能夹带 Key 的原始 message。
     */
    private fun retryReason(e: Throwable): String = when {
        e is LlmHttpException && e.code == 429 -> "请求过于频繁（429）"
        e is LlmHttpException && e.code >= 500 -> "服务端错误（${e.code}）"
        e is LlmHttpException -> "HTTP ${e.code}"
        e is java.net.UnknownHostException -> "网络不可达"
        e is java.net.SocketTimeoutException -> "网络超时"
        e is java.net.ConnectException -> "连接失败"
        e is java.io.IOException -> "网络异常"
        else -> "暂时故障"
    }

    /**
     * 是否值得重试：[LlmHttpException] 自身决定（429/5xx 可重试，401/403/400 不可），
     * 其余（超时、DNS、连接中断等网络 IO 异常）一律视为可重试。
     */
    private fun isRetryable(e: Throwable): Boolean =
        (e as? LlmHttpException)?.retryable ?: true

    /**
     * 每批工具执行前取一次 executor（默认按**当前**字段构造，update(registry) 即时生效）。
     *
     * Phase 2 DoD④：默认 executor 绑定的 registry 为「回合级工具视图」[reg]——未裁剪时
     * 即原 `registry`（行为不变）；裁剪时仅含暴露工具，被裁工具走未知工具路径。
     * executorFactory 注入（测试替身）优先级不变，忽略 [reg]。
     */
    private fun toolExecutor(reg: ToolRegistry): ToolExecutor =
        executorFactory?.invoke() ?: RegistryToolExecutor(reg, failureGuard, approval, hooks)

    /**
     * 计算本回合的工具暴露面（Phase 2 DoD④，纯委托 [ToolExposurePolicy.exposureFor]）。
     * provider 取 `llmProvider().id`；[forceFullTools] = true 时强制全量。
     */
    private fun currentExposure(): ToolExposure =
        ToolExposurePolicy.exposureFor(registry.list(), llmProvider().id, forceFullTools)

    /**
     * 由暴露面构造「回合级工具视图 registry」：
     *  - 未裁剪（[ToolExposure.trimmed] = false）→ 原样返回 [registry]，**零开销、行为完全不变**；
     *  - 发生裁剪 → 新建仅含 [ToolExposure.tools] 的 registry，使被裁工具在该视图内「查无此工具」，
     *    从而 descriptor 解析为 null、executor 走 T02 未知工具路径（模糊匹配 + 自纠 hint），不抛异常。
     *
     * 注：仅影响本回合的「工具视图」，全局 [registry]（Skill 桥等复用）不变。
     */
    private fun scopedRegistry(exposure: ToolExposure): ToolRegistry =
        if (!exposure.trimmed) {
            registry
        } else {
            ToolRegistry().apply { exposure.tools.forEach { register(it) } }
        }

    /**
     * 裁剪发生时打一条 INFO（Phase 2 DoD④ 诊断要求）：写明 provider + 暴露数量 + 裁剪掉的数量。
     * 未裁剪（暴露数 = 全量数）时不打——避免每回合刷屏。日志文本经 [AgentLogger.sanitize] 无害化。
     */
    private fun logExposure(exposure: ToolExposure) {
        if (!exposure.trimmed) return
        AgentLogger.i(
            "ToolExposure",
            "按 provider 裁剪工具：provider=${exposure.providerId} 暴露 ${exposure.exposedCount} 个，" +
                "裁剪掉 ${exposure.trimmedCount} 个（暴露：${exposure.exposedNames.joinToString(",")}）"
        )
    }

    /**
     * 续跑判定（T05）：显式 shouldContinue 优先；stop() 的优雅停止通道与之并列
     * （任一要求停即停）。null（未配置）且未收到 stop() → 继续。
     */
    private fun shouldContinueNow(): Boolean {
        if (stopRequested.get()) return false
        return shouldContinue?.invoke() ?: true
    }

    /**
     * 把「本回合暴露的」工具自带 systemPrompt 片段拼进系统提示；启用记忆时再追加当前记忆摘要与使用引导。
     *
     * Phase 2 DoD④：以 [tools]（= [ToolExposure.tools]，本地小模型已裁剪）而非全量
     * `registry.list()` 拼接——工具被裁掉后，其 systemPrompt 片段也一并消失，
     * 避免出现「你可以调 ui_swipe」这类已经不给的提示（否则模型会调不存在的工具）。
     */
    private fun buildSystemPrompt(tools: List<AgentTool>): String {
        val hints = tools.mapNotNull { it.systemPrompt.takeIf { s -> s.isNotBlank() } }
        var prompt = if (hints.isEmpty()) systemPrompt else "$systemPrompt\n\n" + hints.joinToString("\n\n")
        val provider = memoryProvider ?: return prompt
        val summary = runCatching { provider() }.getOrDefault("")
        if (summary.isNotBlank()) prompt = "$prompt\n\n$summary"
        return "$prompt\n\n$MEMORY_USAGE_PROMPT"
    }

    /**
     * W-10：为本轮 LLM 请求构建**只读视图**——先剥旧截图，再按 token 预算压历史。
     *
     * 顺序有意为之：先剥图再算预算。一张 768px JPEG 的 base64 约 6~12 万字符，若先按
     * 字符预算压历史，会把「只是图片大」误判成「文字太多」，白白丢掉有用的工具结果。
     *
     * 产物只用于 `chatOnce`，不写回 [runTurn] 的 messages——取消/落盘/UI 仍是完整历史。
     * 无超限时 [ContextBudget] 原样返回入参（零分配）。
     */
    private fun buildRequestMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val pruned = ContextBudget.pruneImages(messages, keepLast = keepRecentImages)
        val fitted = ContextBudget.fitToBudget(
            pruned,
            budgetTokens = contextBudgetTokens,
            maxMessages = maxHistoryMessages
        )
        if (fitted.size != pruned.size || pruned.size != messages.size) {
            AgentLogger.i(
                "ContextBudget",
                "请求视图：${messages.size} 条 / 估算 ${ContextBudget.totalTokens(messages)} tok" +
                    " → ${fitted.size} 条 / 估算 ${ContextBudget.totalTokens(fitted)} tok" +
                    "（预算 $contextBudgetTokens tok）"
            )
        }
        return fitted
    }
}

/**
 * 供应商重试退避策略（纯函数，零 Android 依赖——QA harness 可直接对校验发货单）。
 *
 * **可配常量序列 [BACKOFF_SEQUENCE_MS] = 3s → 10s → 25s**（真机第三轮 Bug A1）：
 *  - 第 1 次重试等 3s：足够 Compose 渲染「正在恢复（1/N）」横幅，也覆盖一次短暂抖动；
 *  - 第 2 次等 10s：给 Wi-Fi / 移动网络一个真实的重连窗口（用户原话「等个半分钟一分钟」）；
 *  - 第 3 次起 25s 封顶：配合「网络恢复即刻重试」，真正断网时不会干等满额。
 * 注：默认 providerRetry=2 → 实际等待 3s + 10s（第 3 次失败后不再等待）；
 * 序列三项之和 38s 需 providerRetry=3 才会走满（见 scripts/qa/HOTFIX3-NOTES.md）。
 *
 * 公开（而非 internal）：[RealAgentLoop] 的构造默认值需引用它，且 QA harness
 * 要脱离 Android 环境直接断言退避毫秒序列（故本对象零 Android / 零 ViewModel 依赖）。
 */
object RetryBackoffPolicy {

    /** 退避基数（第 1 次重试的等待毫秒）。 */
    const val BASE_MS: Long = 3_000L

    /** 单次退避上限（序列最后一项）。 */
    const val MAX_MS: Long = 25_000L

    /**
     * 退避序列（可配常量）：第 n 次重试取第 n 项，超出长度一律取末项封顶。
     * 改这一处即可整体调节「等多久」，无需动重试循环。
     */
    val BACKOFF_SEQUENCE_MS: List<Long> = listOf(3_000L, 10_000L, 25_000L)

    /**
     * **429 限流专用**退避序列（hotfix10）：第 n 次重试取第 n 项，越界取末项封顶。
     * 限流要「等得久、试得少」——通用序列 3s/10s 对限流等于没等（真机 19:05 实证：
     * 连续 3 次 429 后整个回合直接失败）。
     */
    val RATE_LIMIT_SEQUENCE_MS: List<Long> = listOf(10_000L, 30_000L, 60_000L)

    /** 429 单次退避封顶（含服务端 Retry-After 也不得超过它，防坏头值卡死回合）。 */
    const val MAX_RATE_LIMIT_MS: Long = 60_000L

    /**
     * 第 [attempt] 次重试（1-based）前应等待的毫秒数：取 [BACKOFF_SEQUENCE_MS] 的第
     * [attempt] 项（越界取末项封顶）；非法序号（≤0）按第 1 次处理，避免调用方传错导致
     * 永久忙等。
     */
    fun backoffMillis(attempt: Int): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        return BACKOFF_SEQUENCE_MS.getOrElse(index) { MAX_MS }.coerceAtLeast(0L)
    }

    /**
     * **429 限流专用**退避（hotfix10）：[RATE_LIMIT_SEQUENCE_MS] = 10s → 30s → 60s 封顶。
     *
     * 为什么单列：限流不是网络抖动——用 3s/10s 的通用序列硬刚只会连续再挨两次 429
     * （真机 19:05 实证：3s/10s 后第 3 次 429 直接整个回合失败）。限流的正确姿势是
     * 「等得久、试得少」，故序列显著拉长；同时尊重服务端 `Retry-After`：
     * 若服务端要求等更久，取两者较大值（再封顶 [MAX_RATE_LIMIT_MS]，防坏头值卡死）。
     *
     * @param attempt 1-based 重试序号。
     * @param retryAfterMs 服务端 `Retry-After` 毫秒（0 = 未提供）。
     */
    fun rateLimitBackoffMillis(attempt: Int, retryAfterMs: Long = 0L): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        val base = RATE_LIMIT_SEQUENCE_MS.getOrElse(index) { MAX_RATE_LIMIT_MS }
        val serverAsk = retryAfterMs.coerceAtLeast(0L)
        return maxOf(base, serverAsk).coerceAtMost(MAX_RATE_LIMIT_MS)
    }

    /**
     * 默认退避钩子（纯定时，无网络监听）：按 [backoffMillis] 挂起等待
     * （[kotlinx.coroutines.delay] 自带取消语义——退避途中取消会立刻以
     * CancellationException 返回）。
     */
    val DEFAULT: suspend (attempt: Int) -> Unit = networkAware(::backoffMillis, NetworkRecoveryWaiter.TIMER_ONLY)

    /**
     * 网络感知的退避钩子：[RealAgentLoop] 的默认退避由本函数组装。
     *
     * 计划等待 [plan] 毫秒，但期间交给 [waiter] 监听——网络恢复则**提前返回**并立刻
     * 发起下一次尝试；返回前等待器负责注销监听。等待结束时记一条 INFO（计划时长 +
     * 是被恢复打断还是等满），方便真机排查「等了多久、为什么提前重试」。
     */
    fun networkAware(
        plan: (attempt: Int) -> Long,
        waiter: NetworkRecoveryWaiter
    ): suspend (attempt: Int) -> Unit = { attempt ->
        val plannedMs = plan(attempt).coerceAtLeast(0L)
        val interrupted = waiter.awaitRecovery(plannedMs)
        AgentLogger.i(
            "LLM",
            "第 $attempt 次重试退避结束：计划 ${plannedMs}ms，" +
                (if (interrupted) "网络已恢复 → 立即重试" else "等待满额 → 按序列重试")
        )
    }

    /**
     * 429 限流专用版 [networkAware]（hotfix10）：计划毫秒由
     * [rateLimitPlan]（attempt + 服务端 Retry-After）给出，其余语义一致
     * （网络恢复可提前结束等待；等待结束记 INFO）。
     */
    fun networkAwareRateLimit(
        plan: (attempt: Int, retryAfterMs: Long) -> Long,
        waiter: NetworkRecoveryWaiter
    ): suspend (attempt: Int, retryAfterMs: Long) -> Unit = { attempt, retryAfterMs ->
        val plannedMs = plan(attempt, retryAfterMs).coerceAtLeast(0L)
        val interrupted = waiter.awaitRecovery(plannedMs)
        AgentLogger.i(
            "LLM",
            "第 $attempt 次重试（429 限流）退避结束：计划 ${plannedMs}ms" +
                (if (retryAfterMs > 0) "（服务端 Retry-After=${retryAfterMs}ms）" else "") +
                (if (interrupted) "，网络已恢复 → 立即重试" else "，等待满额 → 按序列重试")
        )
    }
}
