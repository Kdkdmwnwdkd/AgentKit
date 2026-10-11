package com.example.agentkit.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/* ------------------------------------------------------------------ */
/* 1. http_request                                                     */
/* ------------------------------------------------------------------ */
class HttpRequestTool(
    private val client: OkHttpClient = OkHttpClient()
) : AgentTool {
    override val name = "http_request"
    override val description =
        "向指定 URL 发起 HTTP 请求，返回状态码与响应体（自动截断到前 8000 字符）。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("method", "string", "HTTP 方法", enum = listOf("GET", "POST", "PUT", "DELETE", "PATCH")),
        JsonSchema.prop("url", "string", "请求目标地址"),
        JsonSchema.prop("headers", "object", "请求头，键值均为字符串（可选）"),
        JsonSchema.prop("body", "string", "请求体，通常与 POST/PUT 配合使用（可选）")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val method = args["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
            val url = args["url"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult("缺少 url", isError = true)
            val bodyText = args["body"]?.jsonPrimitive?.contentOrNull

            val builder = Request.Builder().url(url)
            (args["headers"] as? JsonObject)?.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { builder.addHeader(k, it) }
            }
            val request = if (bodyText != null) {
                builder.method(method, bodyText.toRequestBody("application/json".toMediaType())).build()
            } else {
                builder.method(method, null).build()
            }

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val out = if (text.length > 8000) text.take(8000) + "\n...（已截断）" else text
                ToolResult("HTTP ${resp.code}\n$out")
            }
        }.getOrElse { ToolResult("HTTP 请求异常：${it.message}", isError = true) }
    }
}

/* ------------------------------------------------------------------ */
/* 2 & 3. read_file / write_file（限制在 workspace 沙箱内，防穿越）      */
/* ------------------------------------------------------------------ */
abstract class FileTool(protected val workspace: File) : AgentTool {
    init { if (!workspace.exists()) workspace.mkdirs() }

    protected fun resolve(rel: String): File {
        val f = File(workspace, rel)
        require(f.canonicalPath.startsWith(workspace.canonicalPath)) { "非法路径：$rel" }
        return f
    }
}

/**
 * 分段读取（借鉴 Coomi 的 read_file 设计：默认限制约 64KB，支持按 offset 分批续读，
 * 避免一次性把大文件灌爆上下文）。
 */
class ReadFileTool(workspace: File) : FileTool(workspace) {
    override val name = "read_file"
    override val description =
        "读取工作区内（沙箱）的文本文件。支持分段读取：offset 跳过字符数，limit 限制读取长度（默认 65536，约 64KB），超长文件可分批续读。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "相对于工作区的文件路径"),
        JsonSchema.prop("offset", "integer", "跳过的字符数，默认 0，用于分批读取大文件"),
        JsonSchema.prop("limit", "integer", "本次读取的最大字符数（默认 65536），超过则截断")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult("缺少 path", isError = true)
            val offset = args["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 65536)
                .coerceAtMost(65536).coerceAtLeast(1)

            val file = resolve(path)
            if (!file.exists()) return@withContext ToolResult("文件不存在：$path", isError = true)
            if (!file.isFile) return@withContext ToolResult("不是普通文件：$path", isError = true)

            val full = file.readText()
            val end = (offset + limit).coerceAtMost(full.length)
            val snippet = if (offset < full.length) full.substring(offset, end) else ""
            val remaining = full.length - end
            val tail = if (remaining > 0) {
                "\n…（已截断，文件共 ${full.length} 字符，剩余 $remaining，可用 offset=$end 续读）"
            } else ""
            ToolResult(snippet + tail)
        }.getOrElse { ToolResult("读取失败：${it.message}", isError = true) }
    }
}

class WriteFileTool(workspace: File) : FileTool(workspace) {
    override val name = "write_file"
    override val description = "将文本写入工作区内的文件（覆盖写入）。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "相对于工作区的文件路径"),
        JsonSchema.prop("content", "string", "要写入的内容")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult("缺少 path", isError = true)
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            resolve(path).writeText(content)
            ToolResult("已写入 ${content.length} 字符到 $path")
        }.getOrElse { ToolResult("写入失败：${it.message}", isError = true) }
    }
}

/* ------------------------------------------------------------------ */
/* 4. clipboard                                                        */
/* ------------------------------------------------------------------ */
class ClipboardTool(private val context: Context) : AgentTool {
    override val name = "clipboard"
    override val description = "读取或写入系统剪贴板。action=get 返回当前内容，action=set 写入 text。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("action", "string", "操作类型", enum = listOf("get", "set")),
        JsonSchema.prop("text", "string", "action=set 时要写入的文本")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "get"
        if (action == "set") {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult("set 缺少 text", isError = true)
            cm.setPrimaryClip(ClipData.newPlainText("agent", text))
            ToolResult("已写入剪贴板：${text.take(50)}")
        } else {
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
            ToolResult(text.ifBlank { "（剪贴板为空）" })
        }
    }
}

/* ------------------------------------------------------------------ */
/* 5. send_notification                                                */
/* ------------------------------------------------------------------ */
class NotificationTool(private val context: Context) : AgentTool {
    override val name = "send_notification"
    override val description = "发送一条系统通知，用于提醒用户。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("title", "string", "通知标题"),
        JsonSchema.prop("body", "string", "通知内容")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "Agent 提醒"
            val body = args["body"]?.jsonPrimitive?.contentOrNull ?: ""
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "agent_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Agent 通知", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notification)
            ToolResult("已发送通知：$title")
        }.getOrElse { ToolResult("通知失败：${it.message}", isError = true) }
    }
}
