package com.example.agentkit.core

import com.example.agentkit.core.agent.AgentLoop as AgentLoopContract
import com.example.agentkit.core.agent.RealAgentLoop

const val DEFAULT_SYSTEM_PROMPT = """你是一个运行在 Android 设备上的本地 AI 助手。
你可以使用一组工具来完成任务。当完成任务需要信息或操作时，先调用相应工具，再基于工具结果给出最终回答。
如果一步不够，可以连续调用多个工具；工具调用之间要基于上一步的结果做下一步决策。"""

/** 记忆使用引导：仅在启用记忆库时拼进系统提示，告诉模型“可以写、可以搜”。 */
internal const val MEMORY_USAGE_PROMPT =
    "<memory_usage>你可调用 memory_write 记录用户偏好/关键事实，用 memory_search 回忆过往信息。以下是当前记忆摘要：</memory_usage>"

/**
 * AgentLoop 执行过程事件（Hotfix2：过程透明化）。
 *
 * 旧模式：run() 整轮跑完才返回完整历史，UI 只能「事后」渲染——执行期间用户在
 * 黑盒里干等，AI 调过什么工具、被什么拦截，一概不可见。现在关键节点实时抛事件，
 * UI 边跑边渲染（工具卡「执行中 → 已完成/已失败/已拒绝」）。
 *
 * T01：本类型**留在 core 包原位置**（不许挪）。新 loop（RealAgentLoop）与适配层
 * 共用本定义。
 *
 * Phase 2 DoD③（断网/失败自动恢复且 UI 可见）：新增 [RetryStarted] / [RetryFinished]
 * 两个子类型，把供应商瞬时失败的重试过程从「黑盒干等」变为可见——UI 据此显示
 * 「正在恢复（n/N）· 原因」横幅，恢复成功 / 最终失败 / 用户取消时清除。
 *
 * ⚠️ Kotlin 1.9 对 sealed 的 `when` 有**穷尽性硬约束**：新增子类型后，所有对
 * [AgentEvent] 做穷尽 `when` 的地方（当前唯一一处为 `ui/ChatViewModel.kt` 的
 * `handleAgentEvent`）必须**同步补分支**，否则编译失败。改动本类型时请全仓搜索
 * `is AgentEvent` / `when (ev)` 逐个核对。
 */
sealed interface AgentEvent {
    /** 工具调用开始执行（参数已定，结果未出）。 */
    data class ToolStarted(val call: ToolCall) : AgentEvent

    /** 工具调用结束（成功 / 失败 / 审批被拒 / 被熔断 / 取消闭合，携带回填的 ToolMessage）。 */
    data class ToolFinished(val message: ToolMessage) : AgentEvent

    /**
     * 一次 LLM 调用遭遇**可重试**错误、即将发起第 [attempt] 次重试（共 [max] 次）。
     *
     * @param attempt 1-based 重试序号（第 1 次重试 = 上一次调用失败后的下一次尝试）。
     * @param max 配置的最大重试次数（= providerRetry）；总请求数上限 = max + 1。
     * @param reason 面向用户的可读原因（如「服务端错误（500）」「网络超时」）。
     */
    data class RetryStarted(val attempt: Int, val max: Int, val reason: String) : AgentEvent

    /**
     * 重试过程收尾：LLM 调用最终成功、最终失败、或用户取消。
     *
     * 语义 = 「重试状态已结束，UI 可清除『正在恢复』横幅」。**取消也算收尾**——
     * T05 不变量：用户点停止后不得残留「正在恢复」（取消不是失败，但重试状态必须清）。
     */
    data object RetryFinished : AgentEvent
}

/**
 * Agent 核心循环（T01 起为**薄适配层**）。
 *
 * 背景：回合主循环的完整实现已迁到 `com.example.agentkit.core.agent.RealAgentLoop`
 * （契约接口 `com.example.agentkit.core.agent.AgentLoop`）。本类**原样保留**
 * 类名、构造参数与 `run()` / `runAgent()` 公开 API，内部委托 RealAgentLoop——
 * 目标：ChatViewModel 等既有调用方零改动编译通过、行为不变。
 *
 * 两个 `AgentLoop` 不同全限定名（`core.AgentLoop` 类 vs `core.agent.AgentLoop`
 * 接口），合法共存；本文件内引用接口用 import 别名 [AgentLoopContract]。
 *
 * 新代码请直接用契约接口（send/stop/update 三方法）或 RealAgentLoop
 * （流式 onTextDelta / hooks / executor 注入等扩展面）。
 *
 * 历史行为清单（T02-T06，由 RealAgentLoop 逐条保留）：
 *  - T02 工具结果统一契约：所有 ToolMessage.content 一律经 [formatToolResult] 单一函数
 *    生成，UI 与模型历史共用同一份文本；未知工具回模糊匹配建议让模型自纠。
 *  - T03 并发判据：先算本批是否含写操作（[ToolRegistry.batchHasMutation]），
 *    含写整批串行（日志可见顺序执行），全批只读才 async + awaitAll 并行。
 *  - T04 失败熔断：同指纹调用打转时第 3 次起不再真执行，错误结果直接回喂模型。
 *  - T05 可取消 + 树尾闭合：每轮边界复查取消；取消时在 NonCancellable 内为本 Assistant
 *    所有未配对 ToolCall 补错误 ToolResult 再重抛——不变量「已 commit 的 ToolCall
 *    必有配对 ToolResult」，保证历史再发请求不因未闭合 tool_call 被 API 拒绝。
 *  - T06 体积上限：每个结果独立截断到 [MAX_TOOL_RESULT_CHARS]（各保留各自结果槽位）。
 */
class AgentLoop(
    llmProvider: () -> LLMClient,
    registry: ToolRegistry,
    systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    maxSteps: Int = 16,
    maxParallelTools: Int = 5,
    providerRetry: Int = 2,
    maxHistoryMessages: Int = 40,
    approval: ToolApproval? = null,
    checkpoint: ((List<ChatMessage>) -> Unit)? = null,
    memoryProvider: (() -> String)? = null,
    /** 过程事件回调（Hotfix2）：工具开始/结束实时抛出；null = 关闭（默认，保持旧行为）。 */
    onEvent: ((AgentEvent) -> Unit)? = null,
    /**
     * T05：每轮结束复查的续跑钩子（operit #17 思路）。返回 false 即中途停止
     * （优雅收尾：补一条停止说明后返回历史）。null = 关闭。
     * 协程取消（Job.cancel）是另一条独立通道，两者互不依赖。
     */
    shouldContinue: (() -> Boolean)? = null,
    /**
     * T01 流式文本增量回调（Phase 2 DoD② 接入）：[LLMClient.streamChat] 的 TextDelta
     * 在此上抛，供 UI 做「打字机」式流式填入（TextDelta 不进历史，进历史的只有 Completed
     * 整条）。null = 关闭（默认，保持旧行为）。
     */
    onTextDelta: ((String) -> Unit)? = null
) : AgentLoopContract {

    /** 真实实现（T01 抽出的运行时内核）；本类所有公开 API 逐条转调至此。 */
    private val impl = RealAgentLoop(
        llmProvider = llmProvider,
        registry = registry,
        systemPrompt = systemPrompt,
        maxSteps = maxSteps,
        maxParallelTools = maxParallelTools,
        providerRetry = providerRetry,
        maxHistoryMessages = maxHistoryMessages,
        approval = approval,
        checkpoint = checkpoint,
        memoryProvider = memoryProvider,
        onEvent = onEvent,
        shouldContinue = shouldContinue,
        onTextDelta = onTextDelta
    )

    /** 执行一轮对话，返回完整消息历史（语义 = [AgentLoopContract.send]，保留原名）。 */
    suspend fun run(userInput: String): List<ChatMessage> = impl.send(userInput)

    /** 契约入口：与 [run] 等价（T01 后新代码优先用本名）。 */
    override suspend fun send(userInput: String): List<ChatMessage> = impl.send(userInput)

    /** 契约入口：优雅停止通道（转调 impl，不杀协程）。 */
    override fun stop() = impl.stop()

    /** 契约入口：运行时更新配置（null = 保持现值）。 */
    override fun update(
        systemPrompt: String?,
        memoryProvider: (() -> String)?,
        registry: ToolRegistry?,
        maxSteps: Int?
    ) = impl.update(systemPrompt, memoryProvider, registry, maxSteps)

    /** 便捷封装：只返回最终文本回答（保留原 API）。 */
    suspend fun runAgent(userInput: String): String =
        impl.send(userInput)
            .filterIsInstance<AssistantMessage>()
            .lastOrNull()
            ?.content
            .orEmpty()
}
