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

/*
 * T02：全部工具改返回统一契约 ToolResult{ok, code, message, hint, data}——
 * 成功用 ToolResult.ok(...)，失败用 ToolResult.fail(code, message, hint)（hint 必填，
 * 教模型下一步该调什么）。回喂文本统一由 core/formatToolResult 生成，UI 与模型历史同源。
 *
 * T03：readOnly 逐项标注——本批任一工具非只读即整批串行，全批只读才并行
 * （coomi A2 has_mutation 思路）。5 个内置工具标注清单：
 *   http_request=true / read_file=true / write_file=false / clipboard=true / send_notification=false
 */

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

    /** 只读：GET 类查询无副作用；POST 等由调用方按语义自负（批量判据按 roadmap 标注为只读）。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val method = args["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
            val url = args["url"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 url",
                    hint = "http_request 必须提供 url 参数（如 https://example.com/api），请补充后重试。"
                )
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
                ToolResult.ok(code = "http_${resp.code}", message = "HTTP ${resp.code}", data = out)
            }
        }.getOrElse {
            ToolResult.fail(
                code = "http_error",
                message = "HTTP 请求异常：${it.message}",
                hint = "检查 URL 是否可达、网络是否正常；必要时更换方法或稍后重试。"
            )
        }
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
 * 避免一次性把大文件灌爆上下文。
 *
 * T06：回喂文本最终还会经 AgentLoop 的 truncateToolResult 独立截断到 24K 并带分页提示，
 * 双保险。
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

    /** 只读：仅读取沙箱内文件，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 path",
                    hint = "read_file 必须提供 path（相对于工作区的文件路径），请补充后重试。"
                )
            val offset = args["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 65536)
                .coerceAtMost(65536).coerceAtLeast(1)

            val file = resolve(path)
            if (!file.exists()) {
                return@withContext ToolResult.fail(
                    code = "not_found",
                    message = "文件不存在：$path",
                    hint = "确认路径拼写（相对于工作区根目录）；可用 memory_search 或向用户确认文件名。"
                )
            }
            if (!file.isFile) {
                return@withContext ToolResult.fail(
                    code = "not_a_file",
                    message = "不是普通文件：$path",
                    hint = "该路径是目录或特殊文件，请换用具体文件路径。"
                )
            }

            val full = file.readText()
            val end = (offset + limit).coerceAtMost(full.length)
            val snippet = if (offset < full.length) full.substring(offset, end) else ""
            val remaining = full.length - end
            val tail = if (remaining > 0) {
                "\n…（已截断，文件共 ${full.length} 字符，剩余 $remaining，可用 offset=$end 续读）"
            } else ""
            ToolResult.ok(
                code = "read_ok",
                message = "已读取 $path（本次 ${snippet.length} 字符，文件共 ${full.length} 字符）",
                data = snippet + tail
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "读取失败：${it.message}",
                hint = "确认文件在工作区内且可读；大文件请用 offset/limit 分批读取。"
            )
        }
    }
}

class WriteFileTool(workspace: File) : FileTool(workspace) {
    override val name = "write_file"
    override val description = "将文本写入工作区内的文件（覆盖写入）。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "相对于工作区的文件路径"),
        JsonSchema.prop("content", "string", "要写入的内容")
    )

    /** 写操作：覆盖文件内容，必须串行执行。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 path",
                    hint = "write_file 必须提供 path（相对于工作区的文件路径），请补充后重试。"
                )
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            resolve(path).writeText(content)
            ToolResult.ok(
                code = "write_ok",
                message = "已写入 ${content.length} 字符到 $path"
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "写入失败：${it.message}",
                hint = "确认路径在工作区内、目录可写；内容过大请分段写入。"
            )
        }
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

    /**
     * 只读（按 roadmap 首批标注）：action=set 的剪贴板写入是本地、可逆、无破坏性的
     * 轻副作用，与文件写入不同级；如需更严格可改 false（整批含 set 时走串行）。
     */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "get"
        if (action == "set") {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "set 缺少 text",
                    hint = "clipboard action=set 必须提供 text 参数（要写入的文本）。"
                )
            cm.setPrimaryClip(ClipData.newPlainText("agent", text))
            ToolResult.ok(
                code = "clipboard_set",
                message = "已写入剪贴板：${text.take(50)}"
            )
        } else {
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
            if (text.isBlank()) {
                ToolResult.ok(code = "clipboard_empty", message = "剪贴板为空。", data = "")
            } else {
                ToolResult.ok(
                    code = "clipboard_get",
                    message = "已读取剪贴板（${text.length} 字符）",
                    data = text
                )
            }
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

    /** 写操作：向系统发送用户可见通知，必须串行执行。 */
    override val readOnly = false

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
            ToolResult.ok(
                code = "notification_sent",
                message = "已发送通知：$title"
            )
        }.getOrElse {
            ToolResult.fail(
                code = "notification_error",
                message = "通知失败：${it.message}",
                hint = "确认通知权限已授予（系统设置 → 通知）；必要时重试或改用消息告知用户。"
            )
        }
    }
}
