package com.example.agentkit.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val DEFAULT_SYSTEM_PROMPT = """你是一个运行在 Android 设备上的本地 AI 助手。
你可以使用一组工具来完成任务。当完成任务需要信息或操作时，先调用相应工具，再基于工具结果给出最终回答。
如果一步不够，可以连续调用多个工具；工具调用之间要基于上一步的结果做下一步决策。"""

/** 记忆使用引导：仅在启用记忆库时拼进系统提示，告诉模型“可以写、可以搜”。 */
private const val MEMORY_USAGE_PROMPT =
    "<memory_usage>你可调用 memory_write 记录用户偏好/关键事实，用 memory_search 回忆过往信息。以下是当前记忆摘要：</memory_usage>"

/**
 * AgentLoop 执行过程事件（Hotfix2：过程透明化）。
 *
 * 旧模式：run() 整轮跑完才返回完整历史，UI 只能「事后」渲染——执行期间用户在
 * 黑盒里干等，AI 调过什么工具、被什么拦截，一概不可见。现在关键节点实时抛事件，
 * UI 边跑边渲染（工具卡「执行中 → 已完成/已失败/已拒绝」）。
 */
sealed interface AgentEvent {
    /** 工具调用开始执行（参数已定，结果未出）。 */
    data class ToolStarted(val call: ToolCall) : AgentEvent

    /** 工具调用结束（成功 / 失败 / 审批被拒 / 被熔断 / 取消闭合，携带回填的 ToolMessage）。 */
    data class ToolFinished(val message: ToolMessage) : AgentEvent
}

/**
 * Agent 核心循环：规划 -> 调工具 -> 回填 -> 再规划，直到 LLM 不再请求工具调用。
 *
 * 借鉴（净室重写，非抄源码）：
 *  - Coomi：工具并行执行（maxParallelTools）、供应商失败重试（providerRetry）、
 *    上下文滑动窗口压缩（maxHistoryMessages，Phase 3 升级为 LLM 摘要）、
 *    检查点回调（checkpoint，断线/被杀后可由持久层恢复）。
 *  - RikkaHub：危险工具调用前审批（approval）、工具自带 systemPrompt 注入系统提示。
 *
 * Phase 1 第二批加固（T02/T03/T04/T05/T06）：
 *  - T02 工具结果统一契约：所有 ToolMessage.content 一律经 [formatToolResult] 单一函数
 *    生成，UI 与模型历史共用同一份文本；未知工具回模糊匹配建议让模型自纠。
 *  - T03 并发判据：先算本批是否含写操作（[ToolRegistry.batchHasMutation]），
 *    含写整批串行（日志可见顺序执行），全批只读才 async + awaitAll 并行。
 *  - T04 失败熔断：同指纹调用打转时第 3 次起不再真执行，错误结果直接回喂模型。
 *  - T05 可取消 + 树尾闭合：每轮边界复查取消；取消时在 NonCancellable 内为本 Assistant
 *    所有未配对 ToolCall 补错误 ToolResult 再重抛——不变量「已 commit 的 ToolCall
 *    必有配对 ToolResult」，保证历史再发请求不因未闭合 tool_call 被 API 拒绝。
 *  - T06 体积上限：每个结果独立截断到 [MAX_TOOL_RESULT_CHARS]（各保留各自结果槽位）。
 *
 * - llmProvider 用 lambda 而非直接持有实例：模型配置可在运行时变化。
 * - registry 提供当前可用工具集合（受 Project 工具白名单约束）。
 * - memoryProvider 可选：返回注入系统提示的记忆摘要（L9 记忆库）；传 null 即关闭。
 */
class AgentLoop(
    private val llmProvider: () -> LLMClient,
    private val registry: ToolRegistry,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxSteps: Int = 16,
    private val maxParallelTools: Int = 5,
    private val providerRetry: Int = 2,
    private val maxHistoryMessages: Int = 40,
    private val approval: ToolApproval? = null,
    private val checkpoint: ((List<ChatMessage>) -> Unit)? = null,
    private val memoryProvider: (() -> String)? = null,
    /** 过程事件回调（Hotfix2）：工具开始/结束实时抛出；null = 关闭（默认，保持旧行为）。 */
    private val onEvent: ((AgentEvent) -> Unit)? = null,
    /**
     * T05：每轮结束复查的续跑钩子（operit #17 思路）。返回 false 即中途停止
     * （优雅收尾：补一条停止说明后返回历史）。null = 关闭。
     * 协程取消（Job.cancel）是另一条独立通道，两者互不依赖。
     */
    private val shouldContinue: (() -> Boolean)? = null
) {

    /** T04：失败熔断 + 调用指纹去重（跨轮次持有，切会话不重置——直到成功清指纹）。 */
    private val failureGuard = FailureGuard()

    /** 执行一轮对话，返回完整消息历史（含系统提示、工具调用与结果）。 */
    suspend fun run(userInput: String): List<ChatMessage> {
        AgentLogger.i("AgentLoop", "开始运行，输入：${userInput.take(120)}")
        val loopStart = System.currentTimeMillis()
        val messages = mutableListOf<ChatMessage>(
            SystemMessage(buildSystemPrompt()),
            UserMessage(userInput)
        )
        checkpoint?.invoke(messages)

        repeat(maxSteps) {
            // T05：每轮开始复查取消——UI 点「停止」后 ≤1 轮内退出
            currentCoroutineContext().ensureActive()
            val stepStart = System.currentTimeMillis()
            val resp = try {
                callWithRetry { llmProvider().chat(messages, registry.list()) }
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
                return messages
            }

            // T03/T04/T06：本批工具执行（含写串行/只读并行 + 熔断 + 单结果截断）
            val stepImages = mutableListOf<String>()
            val results = try {
                executeBatch(resp.toolCalls, stepImages)
            } catch (cancel: CancellationException) {
                // T05 树尾闭合：不信任取消时 execute() 的返回值——工具被 kill 后可能返回假成功。
                // 在 NonCancellable 内给本 Assistant 里所有**未配对**的 ToolCall 补错误 ToolResult，
                // 保证「已 commit 的 ToolCall 必有配对 ToolResult」，再重抛取消。
                withContext(NonCancellable) { closePendingToolResults(resp.toolCalls, messages) }
                throw cancel
            }
            messages.addAll(results)
            // 若本轮有工具返回图片（如 ui_screenshot），附到下一轮对话让视觉模型“看见”
            if (stepImages.isNotEmpty()) {
                messages.add(UserMessage("请看下面由工具返回的画面：", stepImages.toList()))
            }
            checkpoint?.invoke(messages)
            maybeCompact(messages)

            // T05：每轮结束再复查一次（工具批可能执行很久，结束时 UI 可能已点停止）
            currentCoroutineContext().ensureActive()
            // shouldContinue 显式返回 false 才停止；null（未配置）不影响流程
            if (shouldContinue?.invoke() == false) {
                AgentLogger.i("AgentLoop", "shouldContinue 返回 false，中途停止")
                messages.add(AssistantMessage("⏹ 已根据请求停止。"))
                return messages
            }
        }

        AgentLogger.w("AgentLoop", "达到最大步数 $maxSteps，任务可能未完成")
        messages.add(AssistantMessage("⚠️ 已达到最大步数（$maxSteps），任务可能未完全完成。"))
        AgentLogger.i(
            "AgentLoop",
            "运行结束：${System.currentTimeMillis() - loopStart}ms，消息数=${messages.size}"
        )
        return messages
    }

    /**
     * 执行一批工具调用（T03 并发判据 + T04 同批重复判定）。
     *
     * - 同批重复：按指纹预计算 duplicate 标志（只放行每指纹首个），后续同名同参调用
     *   直接熔断回错误，不真执行（判定在 [FailureGuard.check]）。
     * - 含写操作（任一工具 !readOnly）→ 整批**串行**（for 顺序执行，日志可见顺序）；
     *    全批只读 → async + awaitAll **并行**（按 maxParallelTools 分块限流）。
     */
    private suspend fun executeBatch(
        calls: List<ToolCall>,
        images: MutableList<String>
    ): List<ToolMessage> {
        val seenFingerprints = HashSet<String>()
        val duplicateFlags = calls.map { call ->
            val fp = failureGuard.fingerprint(call.name, call.arguments)
            !seenFingerprints.add(fp) // 已出现过 -> 同批重复
        }
        val hasMutation = registry.batchHasMutation(calls)
        AgentLogger.i(
            "AgentLoop",
            "本批 ${calls.size} 个调用：hasMutation=$hasMutation（${if (hasMutation) "串行" else "并行"}）"
        )
        return if (hasMutation) {
            // 含写整批串行：单协程顺序执行，直接写 images 无并发问题；顺序确定，
            // 便于日志审计与用户预期
            calls.mapIndexed { i, call -> executeCall(call, images, duplicateFlags[i]) }
        } else {
            // 全批只读：并行（分块限流，块间仍保序；块内 awaitAll 保序）
            // E1 修复：images（run() 的 stepImages）是普通 ArrayList，不能被多个
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
                                executeCall(call, localImages, duplicateFlags[i]) to localImages
                            }
                        }.awaitAll()
                            .onEach { (_, localImages) -> images.addAll(localImages) }
                            .map { (msg, _) -> msg }
                    }
                }
        }
    }

    /**
     * 单步执行 + 过程事件上报（Hotfix2）。
     *
     * ToolStarted 在真正执行前抛出（此时参数已定、结果未出，UI 显示「执行中」卡）；
     * ToolFinished 在 [executeCallInner] 返回后抛出（UI 把同一张卡翻成终态）。
     * 并行执行时多个调用的事件会交错到达，UI 侧按 call.id 归并，不依赖顺序。
     */
    private suspend fun executeCall(
        call: ToolCall,
        images: MutableList<String>,
        duplicateInBatch: Boolean = false
    ): ToolMessage {
        onEvent?.invoke(AgentEvent.ToolStarted(call))
        val msg = executeCallInner(call, images, duplicateInBatch)
        onEvent?.invoke(AgentEvent.ToolFinished(msg))
        return msg
    }

    /**
     * 单步执行：未知工具、审批拒绝、熔断拦截、异常都包装成 ToolMessage 进入历史。
     * 若工具返回图片（ToolPart.Image），其 url 收集到 [images] 供回灌视觉模型。
     *
     * Hotfix2：本方法只负责「执行」——ToolStarted / ToolFinished 两个过程
     * 事件由外层 [executeCall] 统一抛出，内层保持纯执行语义。
     */
    private suspend fun executeCallInner(
        call: ToolCall,
        images: MutableList<String>,
        duplicateInBatch: Boolean = false
    ): ToolMessage {
        val tool = registry.get(call.name)
            ?: run {
                // T02：未知工具 + 编辑距离 ≤2 模糊匹配建议，让模型从错误里自纠
                AgentLogger.w("Tool", "未知工具：${call.name}")
                return toToolMessage(
                    call,
                    ToolResult.fail(
                        code = "unknown_tool",
                        message = registry.unknownToolMessage(call.name),
                        hint = "从当前可用工具列表中选择正确名称重新调用；若任务无法完成请直接告知用户。"
                    ),
                    emptyList()
                )
            }

        // T04：失败熔断 + 指纹去重——命中则直接回错误结果，不走真实执行
        val fingerprint = failureGuard.fingerprint(call.name, call.arguments)
        when (val verdict = failureGuard.check(fingerprint, duplicateInBatch)) {
            is GuardVerdict.Blocked -> {
                AgentLogger.w("Tool", "熔断拦截 ${call.name}：${verdict.message}")
                return toToolMessage(
                    call,
                    ToolResult.fail(verdict.code, verdict.message, verdict.hint),
                    emptyList()
                )
            }
            GuardVerdict.Allow -> Unit
        }

        if (tool.needsApproval && approval != null) {
            val approver = approval
            val allowed = try {
                approver(tool, call.arguments)
            } catch (cancel: CancellationException) {
                // 取消不是拒绝：上抛由树尾闭合处理，绝不吞成「用户拒绝」
                throw cancel
            } catch (e: Throwable) {
                AgentLogger.e("Tool", "审批回调异常：${e.message}")
                false
            }
            if (!allowed) {
                AgentLogger.w("Tool", "审批未通过，拒绝执行 ${call.name}")
                return toToolMessage(
                    call,
                    ToolResult.fail(
                        code = "approval_denied",
                        message = "用户拒绝了该危险操作（${call.name}）。",
                        hint = "不要重复发起相同调用；可改换非危险工具完成，或向用户说明需要该操作的原因。"
                    ),
                    emptyList()
                )
            }
        }
        AgentLogger.toolCall(call.name, call.arguments.toString().take(300))
        return try {
            val start = System.currentTimeMillis()
            val result = tool.execute(call.arguments)
            val cost = System.currentTimeMillis() - start
            val imgUrls = result.parts.filterIsInstance<ToolPart.Image>().map { it.url }
            images.addAll(imgUrls)
            // T04：成功清指纹，失败累加计数
            failureGuard.onExecuted(fingerprint, failed = result.isError)
            // T02 + T06：统一契约格式化 + 单结果截断（UI 与模型历史共用同一份文本）
            val text = truncateToolResult(formatToolResult(result))
            AgentLogger.toolResult(call.name, !result.isError, cost, text.take(300))
            ToolMessage(call.id, tool.name, call.arguments, text, result.isError, imgUrls)
        } catch (cancel: CancellationException) {
            // 取消不是失败：不上计数、不造假结果，直接上抛由树尾闭合补全
            throw cancel
        } catch (e: Throwable) {
            failureGuard.onExecuted(fingerprint, failed = true)
            AgentLogger.e("Tool", "${call.name} 执行异常", e)
            toToolMessage(
                call,
                ToolResult.fail(
                    code = "exception",
                    message = "工具执行异常：${e.message}",
                    hint = "检查参数格式与取值；如持续失败请换用其他工具或告知用户。"
                ),
                emptyList()
            )
        }
    }

    /**
     * T05 树尾闭合：给 [calls] 中所有**未配对**（历史里还没有同 id ToolMessage）的
     * ToolCall 补一条错误 ToolResult，并抛 ToolFinished 事件把 UI「执行中」卡翻终态
     * （不存在永远转圈的工具卡）。
     *
     * 不变量保证点：本方法是 run() 取消路径的唯一收尾——调用后 messages 中每个已
     * commit 的 Assistant.toolCalls 元素都必有配对 ToolMessage，历史再发请求不会
     * 因「未闭合 tool_call」被 API 拒绝。
     */
    private suspend fun closePendingToolResults(
        calls: List<ToolCall>,
        messages: MutableList<ChatMessage>
    ) {
        val paired = messages.filterIsInstance<ToolMessage>().mapTo(HashSet()) { it.id }
        var filled = 0
        for (call in calls) {
            if (call.id in paired) continue
            val msg = toToolMessage(
                call,
                ToolResult.fail(
                    code = "cancelled",
                    message = "该调用已被用户停止，未取得真实结果。",
                    hint = "如仍需此操作，请在下一轮重新调用 ${call.name}（可调整参数）。"
                ),
                emptyList()
            )
            messages.add(msg)
            onEvent?.invoke(AgentEvent.ToolFinished(msg))
            filled++
        }
        if (filled > 0) {
            checkpoint?.invoke(messages)
            AgentLogger.w("AgentLoop", "取消闭合：补填 $filled 个待决 ToolResult")
        }
    }

    /** ToolResult → ToolMessage：统一契约文本 + T06 单结果截断（UI 与历史同源）。 */
    private fun toToolMessage(
        call: ToolCall,
        result: ToolResult,
        images: List<String>
    ): ToolMessage = ToolMessage(
        id = call.id,
        name = call.name,
        arguments = call.arguments,
        content = truncateToolResult(formatToolResult(result)),
        isError = result.isError,
        images = images
    )

    /**
     * 供应商瞬时失败重试（429/5xx 与网络异常才重试）。
     *
     * 401/403/400 这类鉴权与参数错误重试多少次都不会变，必须一次性快速失败，
     * 否则用户填错 Key 时会白等 providerRetry 次请求才看到报错。
     */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(providerRetry + 1) { attempt ->
            try {
                return block()
            } catch (cancel: CancellationException) {
                // 取消立即上抛，绝不重试
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
            }
        }
        throw last ?: IllegalStateException("未知错误")
    }

    /**
     * 是否值得重试：[LlmHttpException] 自身决定（429/5xx 可重试，401/403/400 不可），
     * 其余（超时、DNS、连接中断等网络 IO 异常）一律视为可重试。
     */
    private fun isRetryable(e: Throwable): Boolean =
        (e as? LlmHttpException)?.retryable ?: true

    /** 把工具自带的 systemPrompt 片段拼进系统提示；启用记忆时再追加当前记忆摘要与使用引导。 */
    private fun buildSystemPrompt(): String {
        val hints = registry.list().mapNotNull { it.systemPrompt.takeIf { s -> s.isNotBlank() } }
        var prompt = if (hints.isEmpty()) systemPrompt else "$systemPrompt\n\n" + hints.joinToString("\n\n")
        val provider = memoryProvider ?: return prompt
        val summary = runCatching { provider() }.getOrDefault("")
        if (summary.isNotBlank()) prompt = "$prompt\n\n$summary"
        return "$prompt\n\n$MEMORY_USAGE_PROMPT"
    }

    /**
     * 上下文滑动窗口压缩（Coomi auto_compact 思想的简化版）：
     * 历史超过上限时，从中间丢弃最早的 User/Tool 对，保留 System 与最近对话。
     * Phase 3 可升级为调用 LLM 对旧消息做摘要后回填。
     */
    private fun maybeCompact(messages: MutableList<ChatMessage>) {
        while (messages.size > maxHistoryMessages) {
            val idx = messages.indexOfFirst { it is UserMessage }
            if (idx <= 0) break
            messages.removeAt(idx)
            if (idx < messages.size && messages[idx] is ToolMessage) messages.removeAt(idx)
        }
    }

    /** 便捷封装：只返回最终文本回答。 */
    suspend fun runAgent(userInput: String): String =
        run(userInput)
            .filterIsInstance<AssistantMessage>()
            .lastOrNull()
            ?.content
            .orEmpty()
}
