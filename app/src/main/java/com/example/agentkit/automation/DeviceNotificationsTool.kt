package com.example.agentkit.automation

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolPart
import com.example.agentkit.core.ToolResult
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * device_notifications：读取最近捕获的系统通知（L3 感知层）。
 *
 * Agent 借此“感知”手机上正在发生的事（新消息、日程提醒、App 推送等），
 * 用于主动告知用户或在执行任务前了解上下文。需先在「自动化」页开启通知访问权限。
 */
class DeviceNotificationsTool : AgentTool {
    override val name = "device_notifications"
    override val description =
        "读取设备最近收到的系统通知列表（含来源 App 包名、标题、正文、时间）。用于感知手机正在发生的事（新消息、提醒、推送等），或在执行任务前了解上下文。需在「自动化」页开启通知访问权限。"
    override val systemPrompt =
        "你可以用 device_notifications 读取设备最近的通知，感知手机上正在发生的事（如新消息、提醒）。当用户问“有没有新消息/刚才来了什么通知”或需要上下文时调用它。"
    override val parameters = JsonSchema.objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult {
        val list = NotificationStore.all
        if (list.isEmpty()) {
            return ToolResult(
                "当前没有捕获到通知（请确认已在「自动化」页开启通知访问权限，并等待新通知到达）。"
            )
        }
        val arr = JSONArray()
        for (r in list.reversed()) {
            arr.put(
                JSONObject().apply {
                    put("package", r.packageName)
                    put("title", r.title)
                    put("text", r.text)
                    put("time", r.postTime)
                }
            )
        }
        return ToolResult(
            listOf(
                ToolPart.Text("共捕获 ${list.size} 条通知（按时间倒序）：\n" + arr.toString(2))
            )
        )
    }
}
