package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.workflow.Workflow
import com.example.agentkit.workflow.WorkflowStore
import com.example.agentkit.workflow.WorkflowTrigger
import com.example.agentkit.workflow.describeTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * W-17 定时任务工具：让模型自己建/查/删/开关工作流。
 *
 * 与界面分工：界面只负责「看列表、开关、删、立即运行」，
 * 创建走对话（用户说「每天早上八点提醒我整理通知」，模型直接调 workflow_create）——
 * 比让用户在表单里填五个字段更符合「对话式」的产品定位。
 */

class WorkflowCreateTool(
    private val store: WorkflowStore,
    private val onChanged: () -> Unit
) : AgentTool {

    override val name = "workflow_create"

    override val description =
        "创建一个定时任务（每天/每周/每隔 N 分钟自动提醒执行一句话任务）。" +
            "例：用户说「每天早上八点帮我整理通知」，就传 name=整理通知、prompt=整理今天的通知、trigger_type=daily、hour=8、minute=0。"

    override val systemPrompt =
        "创建定时任务前先确认时间规则（几点/周几/间隔）。一次只创建一个；创建后把「任务名 + 触发规则」告诉用户。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("name", "string", "任务名（短，如「整理通知」）"),
        JsonSchema.prop("prompt", "string", "到点后要执行的任务，写成一句完整的话（会作为一次对话输入）"),
        JsonSchema.prop(
            "trigger_type", "string", "触发方式：daily（每天）/ weekly（每周某几天）/ interval（每隔 N 分钟）",
            enum = listOf("daily", "weekly", "interval")
        ),
        JsonSchema.prop("hour", "integer", "几点（0–23，daily/weekly 用）"),
        JsonSchema.prop("minute", "integer", "几分（0–59，daily/weekly 用）"),
        JsonSchema.prop("days", "string", "weekly 用：周几，逗号分隔，1=周一…7=周日（如 \"1,3,5\"）"),
        JsonSchema.prop("interval_minutes", "integer", "interval 用：间隔分钟数（最小 15）")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank() || prompt.isBlank()) {
            return ToolResult.fail(
                "missing_param",
                "缺少 name 或 prompt。",
                "至少给出任务名和到点要做什么，例如 name=整理通知、prompt=整理今天的通知。"
            )
        }
        val type = args["trigger_type"]?.jsonPrimitive?.contentOrNull ?: WorkflowTrigger.TYPE_DAILY
        val hour = args["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8
        val minute = args["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val days = args["days"]?.jsonPrimitive?.contentOrNull
            ?.split(',', '，')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        val interval = args["interval_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60

        val trigger = WorkflowTrigger.normalized(type, hour, minute, days, interval)
        val wf = store.create(name = name, prompt = prompt, trigger = trigger)
        onChanged()
        return ToolResult.ok(
            code = "workflow_created",
            message = "已创建定时任务「${wf.name}」（${describeTrigger(wf.trigger)}）",
            data = "id=${wf.id}\n到点会发通知，点开即执行；也可在「定时任务」页手动运行。"
        )
    }
}

class WorkflowListTool(private val store: WorkflowStore) : AgentTool {

    override val name = "workflow_list"

    override val description = "列出所有定时任务（名称、触发规则、是否启用、上次执行时间）。"

    override val parameters = JsonSchema.objectSchema(required = emptyList())

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val list = store.list()
        if (list.isEmpty()) {
            return ToolResult.ok(
                "workflow_empty",
                "还没有定时任务。",
                data = "用户想定时做事时，可用 workflow_create 创建。"
            )
        }
        val text = list.joinToString("\n") { wf -> formatWorkflow(wf) }
        return ToolResult.ok("workflow_list", "共 ${list.size} 个定时任务：", data = text)
    }
}

class WorkflowDeleteTool(
    private val store: WorkflowStore,
    private val onChanged: () -> Unit
) : AgentTool {

    override val name = "workflow_delete"

    override val description = "删除一个定时任务（按 id）。id 可从 workflow_list 获取。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("id", "string", "任务 id（workflow_list 返回）")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (id.isBlank()) return ToolResult.fail("missing_param", "缺少 id", "先用 workflow_list 查看 id。")
        val ok = store.delete(id)
        if (ok) onChanged()
        return if (ok) {
            ToolResult.ok("workflow_deleted", "已删除定时任务 $id。")
        } else {
            ToolResult.fail("not_found", "没有 id 为「$id」的任务。", "用 workflow_list 确认 id。")
        }
    }
}

class WorkflowToggleTool(
    private val store: WorkflowStore,
    private val onChanged: () -> Unit
) : AgentTool {

    override val name = "workflow_toggle"

    override val description = "启用或停用一个定时任务。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("id", "string", "任务 id"),
        JsonSchema.prop("enabled", "boolean", "true=启用，false=停用")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val enabled = args["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        if (id.isBlank()) return ToolResult.fail("missing_param", "缺少 id", "先用 workflow_list 查看 id。")
        val ok = store.setEnabled(id, enabled)
        if (ok) onChanged()
        return if (ok) {
            ToolResult.ok("workflow_toggled", if (enabled) "已启用 $id。" else "已停用 $id。")
        } else {
            ToolResult.fail("not_found", "没有 id 为「$id」的任务。", "用 workflow_list 确认 id。")
        }
    }
}

/** 单条工作流的文本表示（列表工具与日志共用，纯函数）。 */
private fun formatWorkflow(wf: Workflow): String = buildString {
    append("- ").append(wf.name)
    append("（id=").append(wf.id).append("）")
    append(" · ").append(describeTrigger(wf.trigger))
    append(" · ").append(if (wf.enabled) "启用" else "已停用")
    wf.lastRunAt?.let { append(" · 上次：").append(formatTime(it)) }
    append("\n  任务：").append(wf.prompt.take(120))
}

private fun formatTime(ts: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(ts))
}
