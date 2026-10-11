package com.example.agentkit.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.example.agentkit.core.ToolPart

const val DEFAULT_SYSTEM_PROMPT = """你是一个运行在 Android 设备上的本地 AI 助手。
你可以使用一组工具来完成任务。当完成任务需要信息或操作时，先调用相应工具，再基于工具结果给出最终回答。
如果一步不够，可以连续调用多个工具；工具调用之间要基于上一步的结果做下一步决策。"""

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
    private val checkpoint: ((List<ChatMessage>) -> Unit)? = null
) {
    /** 执行一轮对话，返回完整消息历史（含系统提示、工具调用与结果）。 */
    suspend fun run(userInput: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>(
            SystemMessage(buildSystemPrompt()),
            UserMessage(userInput)
        )
        checkpoint?.invoke(messages)

        repeat(maxSteps) {
            val resp = callWithRetry { llmProvider().chat(messages, registry.list()) }
            messages.add(resp)
            checkpoint?.invoke(messages)

            // LLM 不再请求工具调用 -> 给出最终回答，结束循环
            if (resp.toolCalls.isEmpty()) return messages

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

        messages.add(AssistantMessage("⚠️ 已达到最大步数（$maxSteps），任务可能未完全完成。"))
        return messages
    }

    /** 单步执行：未知工具、审批拒绝、异常都包装成 ToolMessage 进入历史。
     * 若工具返回图片（ToolPart.Image），其 url 收集到 [images] 供回灌视觉模型。 */
    private suspend fun executeCall(call: ToolCall, images: MutableList<String>): ToolMessage {
        val tool = registry.get(call.name)
            ?: return ToolMessage(call.id, call.name, call.arguments, "未知工具：${call.name}", isError = true)

        if (tool.needsApproval && approval != null) {
            val approver = approval
            val allowed = runCatching { approver(tool, call.arguments) }.getOrDefault(false)
            if (!allowed) {
                return ToolMessage(call.id, call.name, call.arguments, "用户拒绝了该危险操作", isError = true)
            }
        }
        return try {
            val result = tool.execute(call.arguments)
            val imgUrls = result.parts.filterIsInstance<ToolPart.Image>().map { it.url }
            images.addAll(imgUrls)
            ToolMessage(call.id, tool.name, call.arguments, result.content, result.isError, imgUrls)
        } catch (e: Throwable) {
            ToolMessage(call.id, call.name, call.arguments, "工具执行异常：${e.message}", isError = true)
        }
    }

    /** 供应商瞬时失败重试（协议/鉴权错误等不可重试错误仍会快速失败）。 */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(providerRetry + 1) {
            try { return block() } catch (e: Throwable) { last = e }
        }
        throw last ?: IllegalStateException("未知错误")
    }

    /** 把工具自带的 systemPrompt 片段拼进系统提示。 */
    private fun buildSystemPrompt(): String {
        val hints = registry.list().mapNotNull { it.systemPrompt.takeIf { s -> s.isNotBlank() } }
        return if (hints.isEmpty()) systemPrompt else "$systemPrompt\n\n" + hints.joinToString("\n\n")
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
