package com.example.agentkit.core.agent

import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * 子代理执行器（W-31）：把若干 [SubTask] 各交给一个**独立上下文**的 [RealAgentLoop] 跑，
 * 收集结论返回给主代理。
 *
 * ## 为什么值得存在
 * 主代理的上下文是稀缺资源：让它「读 8 个文件，只告诉我哪个定义了 X」会把这些文件的
 * 全文都灌进主对话，之后每一轮都要重新计费、还挤掉真正的任务信息。
 * 子代理把这段「脏活」关在**自己的消息栈**里，只把一段结论交回来。
 *
 * ## 三条边界（都写在代码里，不只是注释）
 *  1. **只读**：工具表按 [SubAgentLogic.READ_ONLY_TOOLS] 过滤，模型看不到写工具；
 *  2. **不递归**：白名单里没有 `spawn_agents`，子代理无法再派子代理；
 *  3. **有预算**：单任务超时 [timeoutMs]、最多 [maxSteps] 步、上下文 [budgetTokens]——
 *     子代理跑飞不会拖垮整轮任务。
 *
 * 并发用「分批 + 批内并行」而不是一次性全并发：手机端同时打 3 个请求最容易撞 429，
 * 分批能让前一批的 Key 冷却在下一批生效。
 */
class SubAgentRunner(
    private val llmProvider: () -> LLMClient,
    private val baseRegistry: () -> ToolRegistry,
    /** 单个子代理的总超时（毫秒）。 */
    private val timeoutMs: Long = 240_000L,
    /** 单个子代理最多几步工具往返。 */
    private val maxSteps: Int = 8,
    /** 单个子代理的上下文预算（token）。 */
    private val budgetTokens: Int = 12_000
) {

    /**
     * 执行全部子任务；顺序与入参一致（汇总时能对上号）。
     * 外层协程取消（用户点停止）会传播进来，正在跑的子代理一起取消。
     */
    suspend fun run(
        tasks: List<SubTask>,
        concurrency: Int = SubAgentLogic.MAX_TASKS
    ): List<SubAgentOutcome> = coroutineScope {
        val limit = SubAgentLogic.clampConcurrency(concurrency, tasks.size)
        if (limit == 0) return@coroutineScope emptyList()
        tasks.chunked(limit).flatMap { batch ->
            batch.map { task ->
                async(Dispatchers.IO) { runOne(task) }
            }.awaitAll()
        }
    }

    /**
     * 跑一个子代理。
     *
     * 异常分三类，处理方式不同：
     *  - [TimeoutCancellationException]：子代理自己超时 → 记为失败，**不影响**其他子代理；
     *  - 其他 [CancellationException]：外层取消（停止按钮）→ 必须重抛，否则停止无效；
     *  - 其余异常：模型/网络问题 → 记为失败并带上原因。
     */
    private suspend fun runOne(task: SubTask): SubAgentOutcome {
        val started = System.currentTimeMillis()
        return try {
            val history = withTimeout(timeoutMs) {
                RealAgentLoop(
                    llmProvider = llmProvider,
                    registry = buildRegistry(),
                    systemPrompt = SubAgentLogic.systemPrompt(task.title),
                    maxSteps = maxSteps,
                    maxParallelTools = 2,
                    contextBudgetTokens = budgetTokens,
                    // 白名单只有十几个工具，不能被「小模型核心集」再裁掉——
                    // 子代理拿不到 read_file 就等于瞎了
                    forceFullTools = true
                ).send(SubAgentLogic.userPrompt(task.instruction), emptyList(), emptyList())
            }
            val texts = history.mapNotNull { (it as? AssistantMessage)?.content }
            SubAgentOutcome(
                title = task.title,
                ok = true,
                text = SubAgentLogic.extractConclusion(texts),
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (e: TimeoutCancellationException) {
            SubAgentOutcome(
                title = task.title,
                ok = false,
                text = "超时（${timeoutMs / 1000} 秒）",
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SubAgentOutcome(
                title = task.title,
                ok = false,
                text = SubAgentLogic.describeFailure(e),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    /** 每次执行现建一个子工具表：主表可能被 MCP 运行时增删，用快照更安全。 */
    private fun buildRegistry(): ToolRegistry {
        val sub = ToolRegistry()
        baseRegistry().list().forEach { tool ->
            if (tool.name in SubAgentLogic.READ_ONLY_TOOLS) sub.register(tool)
        }
        return sub
    }
}
