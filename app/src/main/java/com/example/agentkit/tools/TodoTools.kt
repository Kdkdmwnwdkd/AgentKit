package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.agent.TodoStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * todo_write / todo_read（W-14）：让模型自己维护任务清单。
 *
 * 对齐 AiCode 的 task/todo 思路：10 步以上的长任务里，模型最容易犯的错不是「不会做」，
 * 而是「做到一半忘了还剩什么」，然后重复劳动或提前收工。
 * 一份显式清单把「计划」外化到上下文里，每步都能对齐。
 *
 * 清单用 Markdown 存（`- [x] 已完成` / `- [ ] 待办`），模型读得懂、用户看得清，
 * 不强制结构化 JSON（结构化反而增加模型的调用失败率）。
 */
class TodoWriteTool(
    private val store: TodoStore,
    private val conversationId: () -> String?
) : AgentTool {

    override val name = "todo_write"

    override val description =
        "写入/覆盖当前会话的任务清单（Markdown 勾选列表）。多步任务开始时先列清单，" +
            "每完成一步就更新一次状态；传空字符串可清空清单。"

    override val systemPrompt =
        "任务超过 3 步时，先用 todo_write 列出步骤（- [ ] 形式），做完一步改成 - [x]，" +
            "让用户随时能看到进度；不要每步都重写整个清单，只在状态变化时更新。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop(
            "items", "string",
            "完整清单（Markdown），例如「- [x] 打开设置\\n- [ ] 找到字体大小\\n- [ ] 调到最大」；传空字符串清空"
        ),
        required = listOf("items")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val items = args["items"]?.jsonPrimitive?.contentOrNull ?: ""
        val convId = conversationId()
            ?: return ToolResult.fail(
                code = "no_conversation",
                message = "当前没有活动会话，无法保存清单。",
                hint = "先让用户发起对话；或直接把步骤写在回复里。"
            )
        if (!store.write(convId, items.trim())) {
            return ToolResult.fail(
                code = "todo_write_failed",
                message = "清单写入失败（存储不可写）。",
                hint = "直接把步骤写在回复文本里，不要反复重试。"
            )
        }
        val done = items.count { it == 'x' || it == 'X' }
        val total = items.lines().count { it.trimStart().startsWith("- [") }
        return ToolResult.ok(
            code = "todo_saved",
            message = if (items.isBlank()) "清单已清空。" else "清单已保存（$done/$total 已完成）。",
            data = items.take(2_000)
        )
    }
}

class TodoReadTool(
    private val store: TodoStore,
    private val conversationId: () -> String?
) : AgentTool {

    override val name = "todo_read"

    override val description = "读取当前会话的任务清单（没有则明确告知）。"

    override val systemPrompt = "继续长任务前先 todo_read，确认还剩哪几步没做，避免重复或遗漏。"

    override val parameters = JsonSchema.objectSchema(
        required = emptyList()
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val convId = conversationId()
            ?: return ToolResult.fail(
                code = "no_conversation",
                message = "当前没有活动会话。",
                hint = "直接根据对话上下文继续。"
            )
        val list = store.read(convId)
            ?: return ToolResult.ok(code = "todo_empty", message = TodoStore.EMPTY_HINT)
        return ToolResult.ok(code = "todo_ok", message = "当前清单：", data = list.take(4_000))
    }
}
