package com.example.agentkit.core.agent

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.FailureGuard
import com.example.agentkit.core.GuardVerdict
import com.example.agentkit.core.ToolApproval
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolPart
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.formatToolResult
import com.example.agentkit.core.truncateToolResult
import kotlinx.coroutines.CancellationException

/**
 * 单次工具调用的产出（T01，本任务最微妙处，吃透再写）。
 *
 * 三分支语义边界（直接决定「回不回喂模型」）：
 *  - [Completed]：调用有确定终态——**含全部业务失败**（工具自己返回
 *    ToolResult.fail、未知工具、熔断拦截、审批拒绝、工具 execute 抛异常）。
 *    这些一律由 executor 内部捕获并包装成错误 ToolMessage 回喂模型
 *    （T02 自纠语义，必须保持：错误信息即修复指引）。
 *  - [Interrupted]：确定未完成、无副作用（只读工具被 kill）。仅 [ToolExecutor.onInterrupt] 产出。
 *  - [Unknown]：可能已有副作用（写工具被 kill），状态未知。仅 [ToolExecutor.onInterrupt] 产出。
 *
 * 注意 [Completed] 与中断态的**生产者分离**：execute() 正常路径只产出
 * Completed（业务失败也在内）；Interrupted/Unknown 只在取消路径由
 * onInterrupt() 产出。execute() 若违规返回中断态，RealAgentLoop 有防御填充
 * （见 RealAgentLoop.executeOne），不变量不依赖 executor 自律。
 */
sealed interface ToolCallOutcome {

    /**
     * 调用有确定终态，携带回喂模型的 ToolMessage 与工具返回的图片 url 列表。
     *
     * @property message 回喂历史 / UI 的 ToolMessage（统一契约文本 + T06 截断）。
     * @property images 工具返回的图片（如 ui_screenshot），由 loop 汇合回灌视觉模型。
     */
    data class Completed(
        val message: ToolMessage,
        val images: List<String>
    ) : ToolCallOutcome

    /** 确定未完成、无副作用（只读工具被 kill）。 */
    data object Interrupted : ToolCallOutcome

    /** 可能已有副作用（写工具被 kill），状态未知。 */
    data object Unknown : ToolCallOutcome
}

/**
 * executor 自身违约异常（T01 验收③）：executor **实现代码**抛异常
 * （NPE / Error / 测试替身故意抛的 RuntimeException 等——契约漏洞，
 * 区别于工具 execute 抛的业务异常）。
 *
 * 语义：RealAgentLoop 在回合级兜底捕获后**整个回合失败**（本异常上抛给
 * 调用方），该调用**不回喂模型**——业务 bug 显形，而不是被悄悄包装成
 * 一条错误 ToolMessage 让模型「自纠」一个根本不存在的工具问题。
 * CancellationException 永远不是失败：绝不被包装成本异常（见 execute 约定）。
 *
 * @property toolName 出错的工具名（消息内含，便于日志定位）。
 * @property reason 原始违约异常（作为 cause 保留）。
 */
class ToolExecutionFailedException(
    val toolName: String,
    val reason: Throwable
) : RuntimeException(
    "工具执行器违约（executor 实现代码抛异常，非工具业务失败）：tool=$toolName, " +
        "cause=${reason.javaClass.simpleName}: ${reason.message}",
    reason
)

/**
 * 工具执行器契约（T01）：loop 只认本接口，执行策略（真 registry / 沙箱 /
 * 远端 MCP / 测试替身）全部内聚在实现类内。
 *
 * 调用约定：
 *  - execute() **永不抛异常**（契约目标）：业务失败一律转 [ToolCallOutcome.Completed]
 *    错误 ToolMessage；实现若违约抛异常，由 loop 回合级兜底转
 *    [ToolExecutionFailedException]（验收③）。
 *  - CancellationException 永远不是失败：execute() 不得将其吞成任何 outcome，
 *    必须原样上抛（T05：由树尾闭合收尾）。
 *  - onInterrupt() 在取消路径被 loop 询问「在飞的这一调用算 Interrupted 还是
 *    Unknown」，由实现按工具 readOnly 自判；非挂起函数，可在 NonCancellable 内安全调用。
 */
interface ToolExecutor {

    /**
     * 执行一次工具调用。
     *
     * @param call 调用请求（id / name / arguments）。
     * @param descriptor loop 解析出的工具描述符；未知工具时为 loop 合成的占位
     *   descriptor（name=call.name、wireName=call.name、inputSchemaJson="{}"），
     *   实现应走未知工具文案路径（T02 模糊匹配）。
     * @return [ToolCallOutcome.Completed]（正常与业务失败）——中断态只由
     *   [onInterrupt] 产出。
     */
    suspend fun execute(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome

    /**
     * 取消路径询问：在飞的这一调用算 [ToolCallOutcome.Interrupted]（只读，确定
     * 无副作用）还是 [ToolCallOutcome.Unknown]（写操作，可能已有副作用）。
     * loop 据此生成树尾闭合的 ToolResult 文案（code=cancelled / cancelled_unknown）。
     */
    fun onInterrupt(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome
}

/**
 * 批次感知的 executor 扩展（T04 同批重复熔断需要，可选实现）。
 *
 * 「同批重复」是**批次级**判定（同一 Assistant 一批调用里出现完全相同的
 * 同名同参调用，第二个起拦截），只有 loop 知道批次边界，故由 loop 预计算
 * 位置标志（与旧 AgentLoop.executeBatch 的 duplicate 预计算逐行等价），
 * 经本接口的 execute 重载传给 executor。
 *
 * 未实现本接口的 executor（如 harness 测试替身）自行忽略同批重复语义，
 * 只认基础 [ToolExecutor.execute] 契约。
 */
interface BatchToolExecutor : ToolExecutor {

    /**
     * 同 [ToolExecutor.execute]，额外携带批次预计算标志。
     *
     * @param duplicateInBatch 本调用在同一批次内是否已有同指纹（同名同参）前驱。
     */
    suspend fun execute(
        call: ToolCall,
        descriptor: ToolDescriptor,
        duplicateInBatch: Boolean
    ): ToolCallOutcome
}

/**
 * 基于 ToolRegistry 的默认 [ToolExecutor]（T01）：旧 AgentLoop.executeCallInner
 * 的全部逻辑原样搬入——未知工具 / 审批 / 熔断 / 执行 / onExecuted / 截断格式化 /
 * 图片收集，产出 [ToolCallOutcome.Completed]。
 *
 * 依赖注入而非自持：registry / failureGuard / approval / hooks 全部由构造参数
 * 传入——failureGuard 跨轮次持有在 loop 上（T04 熔断状态不随 executor 重建丢失），
 * registry 由 loop 每批按当前字段构造本类（update(registry) 即时生效）。
 */
class RegistryToolExecutor(
    private val registry: ToolRegistry,
    private val failureGuard: FailureGuard,
    private val approval: ToolApproval? = null,
    private val hooks: Hooks = NoopHooks
) : BatchToolExecutor {

    /** 基础契约入口：duplicateInBatch 缺省 false（单发调用无同批前驱）。 */
    override suspend fun execute(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome =
        execute(call, descriptor, duplicateInBatch = false)

    /**
     * 执行一次调用：beforeToolCall 钩子 → 全部业务分支（未知/审批/熔断/执行/异常）
     * → afterToolCall 钩子（仅产出 Completed 时命中，与 beforeToolCall 严格配对）。
     */
    override suspend fun execute(
        call: ToolCall,
        descriptor: ToolDescriptor,
        duplicateInBatch: Boolean
    ): ToolCallOutcome {
        hooks.beforeToolCall(call)
        val outcome = executeInner(call, descriptor, duplicateInBatch)
        if (outcome is ToolCallOutcome.Completed) {
            hooks.afterToolCall(call, outcome.message)
        }
        return outcome
    }

    /**
     * 单步执行：未知工具、审批拒绝、熔断拦截、异常都包装成 ToolMessage 进入历史
     * （T02 自纠语义）。若工具返回图片（ToolPart.Image），其 url 进 Completed.images
     * 供 loop 回灌视觉模型。
     */
    private suspend fun executeInner(
        call: ToolCall,
        descriptor: ToolDescriptor,
        duplicateInBatch: Boolean
    ): ToolCallOutcome {
        val tool = registry.get(call.name)
            ?: run {
                // T02：未知工具 + 编辑距离 ≤2 模糊匹配建议，让模型从错误里自纠
                AgentLogger.w("Tool", "未知工具：${call.name}（wireName=${descriptor.wireName}）")
                return ToolCallOutcome.Completed(
                    toolMessageOf(
                        call,
                        ToolResult.fail(
                            code = "unknown_tool",
                            message = registry.unknownToolMessage(call.name),
                            hint = "从当前可用工具列表中选择正确名称重新调用；若任务无法完成请直接告知用户。"
                        )
                    ),
                    emptyList()
                )
            }

        // T04：失败熔断 + 指纹去重——命中则直接回错误结果，不走真实执行
        val fingerprint = failureGuard.fingerprint(call.name, call.arguments)
        when (val verdict = failureGuard.check(fingerprint, duplicateInBatch)) {
            is GuardVerdict.Blocked -> {
                AgentLogger.w("Tool", "熔断拦截 ${call.name}：${verdict.message}")
                return ToolCallOutcome.Completed(
                    toolMessageOf(
                        call,
                        ToolResult.fail(verdict.code, verdict.message, verdict.hint)
                    ),
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
                return ToolCallOutcome.Completed(
                    toolMessageOf(
                        call,
                        ToolResult.fail(
                            code = "approval_denied",
                            message = "用户拒绝了该危险操作（${call.name}）。",
                            hint = "不要重复发起相同调用；可改换非危险工具完成，或向用户说明需要该操作的原因。"
                        )
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
            // T04：成功清指纹，失败累加计数
            failureGuard.onExecuted(fingerprint, failed = result.isError)
            // T02 + T06：统一契约格式化 + 单结果截断（UI 与模型历史共用同一份文本）
            val text = truncateToolResult(formatToolResult(result))
            AgentLogger.toolResult(call.name, !result.isError, cost, text.take(300))
            ToolCallOutcome.Completed(
                ToolMessage(call.id, tool.name, call.arguments, text, result.isError, imgUrls),
                imgUrls
            )
        } catch (cancel: CancellationException) {
            // 取消不是失败：不上计数、不造假结果，直接上抛由树尾闭合补全
            throw cancel
        } catch (e: Throwable) {
            failureGuard.onExecuted(fingerprint, failed = true)
            AgentLogger.e("Tool", "${call.name} 执行异常", e)
            ToolCallOutcome.Completed(
                toolMessageOf(
                    call,
                    ToolResult.fail(
                        code = "exception",
                        message = "工具执行异常：${e.message}",
                        hint = "检查参数格式与取值；如持续失败请换用其他工具或告知用户。"
                    )
                ),
                emptyList()
            )
        }
    }

    /**
     * 取消路径自判（T01）：只读工具 → [ToolCallOutcome.Interrupted]（确定未完成、
     * 无副作用）；写工具 → [ToolCallOutcome.Unknown]（可能已有副作用）。
     * 未知工具按只读处理（它只会得到一条错误结果，无副作用——与
     * [ToolRegistry.batchHasMutation] 的未知工具判据一致）。
     */
    override fun onInterrupt(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome {
        val readOnly = registry.get(call.name)?.readOnly ?: true
        AgentLogger.w(
            "Tool",
            "onInterrupt：${call.name} 判为 ${if (readOnly) "Interrupted（只读，确定未完成）" else "Unknown（写操作，状态未知）"}"
        )
        return if (readOnly) ToolCallOutcome.Interrupted else ToolCallOutcome.Unknown
    }
}

/**
 * ToolResult → ToolMessage 的单一构造点（T01 抽出，executor 与 loop 共用）：
 * 统一契约文本（[formatToolResult]）+ T06 单结果截断（[truncateToolResult]），
 * UI 与模型历史同源。纯函数。
 */
internal fun toolMessageOf(
    call: ToolCall,
    result: ToolResult,
    images: List<String> = emptyList()
): ToolMessage = ToolMessage(
    id = call.id,
    name = call.name,
    arguments = call.arguments,
    content = truncateToolResult(formatToolResult(result)),
    isError = result.isError,
    images = images
)
