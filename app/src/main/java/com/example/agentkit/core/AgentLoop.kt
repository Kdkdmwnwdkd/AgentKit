package com.example.agentkit.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.example.agentkit.core.ToolPart

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

    /** 工具调用结束（成功 / 失败 / 审批被拒，携带回填的 ToolMessage）。 */
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
    private val onEvent: ((AgentEvent) -> Unit)? = null
) {
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

        // 并行执行本轮工具调用（受 maxParallelTools 限流）
        val stepImages = mutableListOf<String>()
        val results = coroutineScope {
            resp.toolCalls
                .take(maxParallelTools)
                .map { call -> async { executeCall(call, stepImages) } }
                .awaitAll()
        }
            messages.addAll(results)
            // 若本轮有工具返回图片（如 ui_screenshot），附到下一轮对话让视觉模型“看见”
            if (stepImages.isNotEmpty()) {
                messages.add(UserMessage("请看下面由工具返回的画面：", stepImages.toList()))
            }
            checkpoint?.invoke(messages)
            maybeCompact(messages)
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
     * 单步执行 + 过程事件上报（Hotfix2）。
     *
     * ToolStarted 在真正执行前抛出（此时参数已定、结果未出，UI 显示「执行中」卡）；
     * ToolFinished 在 [executeCallInner] 返回后抛出（UI 把同一张卡翻成终态）。
     * 并行执行时多个调用的事件会交错到达，UI 侧按 call.id 归并，不依赖顺序。
     */
    private suspend fun executeCall(call: ToolCall, images: MutableList<String>): ToolMessage {
        onEvent?.invoke(AgentEvent.ToolStarted(call))
        val msg = executeCallInner(call, images)
        onEvent?.invoke(AgentEvent.ToolFinished(msg))
        return msg
    }

    /** 单步执行：未知工具、审批拒绝、异常都包装成 ToolMessage 进入历史。
     * 若工具返回图片（ToolPart.Image），其 url 收集到 [images] 供回灌视觉模型。
     *
     * Hotfix2：本方法只负责「执行」——ToolStarted / ToolFinished 两个过程
     * 事件由外层 [executeCall] 统一抛出，内层保持纯执行语义。 */
    private suspend fun executeCallInner(call: ToolCall, images: MutableList<String>): ToolMessage {
        val tool = registry.get(call.name)
            ?: run {
                AgentLogger.w("Tool", "未知工具：${call.name}")
                return ToolMessage(call.id, call.name, call.arguments, "未知工具：${call.name}", isError = true)
            }

        if (tool.needsApproval && approval != null) {
            val approver = approval
            val allowed = runCatching { approver(tool, call.arguments) }.getOrDefault(false)
            if (!allowed) {
                AgentLogger.w("Tool", "审批未通过，拒绝执行 ${call.name}")
                return ToolMessage(call.id, call.name, call.arguments, "用户拒绝了该危险操作", isError = true)
            }
        }
        AgentLogger.toolCall(call.name, call.arguments.toString().take(300))
        return try {
            val start = System.currentTimeMillis()
            val result = tool.execute(call.arguments)
            val cost = System.currentTimeMillis() - start
            val imgUrls = result.parts.filterIsInstance<ToolPart.Image>().map { it.url }
            images.addAll(imgUrls)
            AgentLogger.toolResult(call.name, !result.isError, cost, result.content.take(300))
            ToolMessage(call.id, tool.name, call.arguments, result.content, result.isError, imgUrls)
        } catch (e: Throwable) {
            AgentLogger.e("Tool", "${call.name} 执行异常", e)
            ToolMessage(call.id, call.name, call.arguments, "工具执行异常：${e.message}", isError = true)
        }
    }

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
