package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.agent.PlanBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * plan_propose（W-25，缺口 #58 / #62）：提交多步计划，等用户批准后再执行。
 *
 * ## 什么时候用
 * - **PLAN 模式**（设置页可开）：每个需要 3 步以上的任务，先提交计划，不得直接动手；
 * - **AUTO 模式**：遇到破坏性/不可逆操作（批量删文件、装/卸应用、连续点按）时建议先计划。
 *
 * ## 为什么必须真的挂起
 * 若只返回「计划已提交」，模型会自己继续干，用户根本来不及看。这里挂起等待
 * （[PlanBroker]），批准才返回 `plan_approved`，拒绝返回 `plan_rejected` 并带原因，
 * 模型据此重新规划。
 *
 * ## 超时的安全侧默认
 * 300 秒无响应 → `plan_timeout`，明确告诉模型**未获批准，不得执行破坏性步骤**；
 * 只允许做只读探查（读文件、看界面）后再次提交。
 */
class PlanTool(
    private val broker: PlanBroker = PlanBroker,
    /** PLAN 模式是否开启：影响系统提示的强度（工具本身始终可调）。 */
    private val planModeEnabled: () -> Boolean = { false },
    private val timeoutSec: Int = 300
) : AgentTool {

    override val name = "plan_propose"

    override val description =
        "提交一份多步计划并等待用户批准。多数情况下先计划再动手能避免跑偏；" +
            "批准后才开始执行，被拒绝时按用户意见重新规划。"

    /**
     * 系统提示随模式变化：
     *  - PLAN 模式：强指令——3 步以上的任务必须先提计划，不得直接动手；
     *  - AUTO 模式：温和建议——破坏性操作前建议先计划。
     */
    override val systemPrompt: String
        get() = if (planModeEnabled()) {
            "当前是「先计划后执行」模式：任何需要 3 步以上、或会改变设备/文件状态的任务，" +
                "**必须先调用 plan_propose 提交计划并等用户批准**，批准前只能做只读探查" +
                "（read_file / ui_tree / web_search 等）。被拒绝就按用户意见修改后重新提交；" +
                "超时视为未批准，不得执行破坏性步骤。"
        } else {
            "遇到破坏性/不可逆操作（批量删除、安装卸载应用、连续点按），建议先 plan_propose " +
                "让用户确认；普通任务直接做，不用每步都计划。"
        }

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("summary", "string", "一句话说明要做什么（≤200 字）"),
        JsonSchema.prop("steps", "string", "执行步骤，每行一条（可带 1. 2. 前缀，会自动清洗）"),
        required = listOf("summary", "steps")
    )

    /** 只读：提交计划本身不改设备状态（批准后的执行才由其他工具完成）。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val summary = args["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val steps = PlanLogic.parseSteps(args["steps"]?.jsonPrimitive?.contentOrNull.orEmpty())

        PlanLogic.validate(summary, steps)?.let {
            return ToolResult.fail(
                code = "bad_plan", message = it,
                hint = "summary 一句话说清目标；steps 每行一条、动词开头（如「读取 config.json」），" +
                    "不超过 ${PlanLogic.MAX_STEPS} 步。"
            )
        }

        val decision = broker.propose(summary, steps, timeoutSec)
        return when (decision) {
            is PlanBroker.Decision.Approved -> ToolResult.ok(
                code = "plan_approved",
                message = "用户已批准计划（${steps.size} 步），开始执行",
                data = steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            )

            is PlanBroker.Decision.Rejected -> ToolResult.fail(
                code = "plan_rejected",
                message = if (decision.reason.isBlank()) "用户拒绝了该计划" else "用户拒绝：${decision.reason}",
                hint = "按用户意见修改计划后重新调用 plan_propose；不要忽略拒绝直接执行。"
            )

            is PlanBroker.Decision.Timeout -> ToolResult.fail(
                code = "plan_timeout",
                message = "等待批准超时（${timeoutSec} 秒），计划未获批准",
                hint = "未获批准时**不得执行破坏性步骤**；可先做只读探查，再重新提交计划，" +
                    "或直接告知用户需要确认。"
            )

            is PlanBroker.Decision.Busy -> ToolResult.fail(
                code = "plan_busy",
                message = "已有待批计划，请等待用户处理后再提交",
                hint = "不要重复提交；等用户批准/拒绝当前计划后继续。"
            )
        }
    }
}
