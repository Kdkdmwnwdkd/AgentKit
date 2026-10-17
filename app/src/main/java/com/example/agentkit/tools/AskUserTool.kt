package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.agent.AskUserBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ask_user（W-14）：向用户提问并**等待回答**（对齐 RikkaHub 的 `ask_user`）。
 *
 * 价值：缺参数时不用瞎猜。以前模型只能 `ToolResult.fail` → 重试 → 再失败，
 * 用户还得等半天；现在直接弹一张选择卡片，用户点一下就能继续。
 *
 * 参数：
 *  - question（必填）：一句话问题，具体到「要选什么」；
 *  - options（可选）：候选答案，逗号或换行分隔；为空则界面给自由输入框。
 */
class AskUserTool(
    private val broker: AskUserBroker = AskUserBroker,
    private val timeoutSec: Int = 180
) : AgentTool {

    override val name = "ask_user"

    override val description =
        "向用户提一个明确的问题并等待回答（缺关键参数、需要在多个选项里选一个、需要确认破坏性操作时用）。" +
            "不要用它问「你还有什么需要」这类空话；问题要具体，能一次问清。"

    override val systemPrompt =
        "缺少必要信息（选哪个文件/哪个应用/哪个网络/是否继续）时用 ask_user，而不是猜测后硬做。" +
            "问题一次只问一件事，选项尽量给 2~4 个。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("question", "string", "要问用户的问题，一句话，具体明确"),
        JsonSchema.prop(
            "options", "string",
            "候选答案，用逗号或换行分隔（如「是,否」或「微信,支付宝」）；留空则由用户自由输入"
        ),
        required = listOf("question")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (question.isBlank()) {
            return ToolResult.fail(
                code = "missing_param",
                message = "缺少 question",
                hint = "传入要问用户的问题，例如 {\"question\":\"要清理哪个目录？\",\"options\":\"Download,Temp\"}。"
            )
        }
        val options = args["options"]?.jsonPrimitive?.contentOrNull
            ?.split(',', '\n', '；', ';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return when (val outcome = broker.ask(question, options, timeoutSec)) {
            is AskUserBroker.Outcome.Answered -> ToolResult.ok(
                code = "user_answered",
                message = "用户回答：${outcome.choice}"
            )
            AskUserBroker.Outcome.Timeout -> ToolResult.fail(
                code = "ask_user_timeout",
                message = "用户 ${timeoutSec} 秒内没有回答。",
                hint = "不要重复提问；按最合理的默认假设继续执行，并在最终答复里说明你假设了什么。"
            )
            AskUserBroker.Outcome.Busy -> ToolResult.fail(
                code = "ask_user_busy",
                message = "已有一个问题在等待用户回答。",
                hint = "等当前问题回答后再提问；或直接按默认假设继续。"
            )
        }
    }
}
