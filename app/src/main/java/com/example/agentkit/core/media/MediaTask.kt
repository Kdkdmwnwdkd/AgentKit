package com.example.agentkit.core.media

import android.content.Context
import com.example.agentkit.core.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 媒体任务模型 + 持久化 + 下载落盘（调研 06 号文档「任务状态机 + 持久化」直译）。
 *
 * 存储：`filesDir/media/tasks.json`（与 MemoryStore 同款朴素方案，不自建数据库）。
 * 资产真理：完成的 URL 下载到 `filesDir/media/outputs/<taskId>/`——
 * **本地路径才是 truth**（各家 URL 都会过期），UI 与 Agent 工具只认本地路径。
 */

/** 任务状态（UI 三态与持久化四态对齐：SUBMITTED 是「已提交未开跑」的瞬时态）。 */
@Serializable
enum class TaskStatus { SUBMITTED, RUNNING, SUCCEEDED, FAILED }

/** 持久化任务记录（直接 @Serializable 落 JSON，字段带默认值兼容旧文件）。 */
@Serializable
data class MediaTask(
    val id: String,
    val providerId: String,
    val providerTaskId: String? = null,
    val request: MediaRequest,
    val status: TaskStatus,
    val progress: Float? = null,
    /** 本地落盘路径（资产真理，UI 只认这个）。 */
    val resultPaths: List<String> = emptyList(),
    /** 云端 URL（备份/分享用，会过期）。 */
    val resultUrls: List<String> = emptyList(),
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isActive: Boolean get() = status == TaskStatus.SUBMITTED || status == TaskStatus.RUNNING
}

/**
 * 任务持久化（JSON 文件）。杀进程重进 App 按 tasks.json 续查——
 * 这是任务模型的灵魂：长任务不随进程消亡。
 */
object MediaTaskStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private fun file(context: Context): File =
        File(File(context.filesDir, "media"), "tasks.json")

    /** 全部任务（新的在前）。 */
    fun list(context: Context): List<MediaTask> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        runCatching {
            json.decodeFromString<List<MediaTask>>(f.readText())
        }.getOrElse { e ->
            AgentLogger.e("MediaTaskStore", "tasks.json 解析失败，按空处理：${e.message}")
            emptyList()
        }.sortedByDescending { it.createdAt }
    }

    /** 插入或更新（按 id）。 */
    fun upsert(context: Context, task: MediaTask) = synchronized(lock) {
        val all = list(context).filter { it.id != task.id } + task.copy(updatedAt = System.currentTimeMillis())
        writeAll(context, all.sortedByDescending { it.createdAt })
    }

    /** 删除任务及其输出目录。 */
    fun delete(context: Context, id: String) = synchronized(lock) {
        writeAll(context, list(context).filter { it.id != id })
        outputsDir(context, id).deleteRecursively()
    }

    /** 新建任务的 id（调用方填好其余字段后 upsert）。 */
    fun newId(): String = UUID.randomUUID().toString()

    private fun writeAll(context: Context, tasks: List<MediaTask>) {
        val f = file(context)
        f.parentFile?.mkdirs()
        runCatching { f.writeText(json.encodeToString(ListSerializer(MediaTask.serializer()), tasks)) }
            .onFailure { AgentLogger.e("MediaTaskStore", "写 tasks.json 失败：${it.message}") }
    }

    /** 任务输出目录：filesDir/media/outputs/<taskId>/。 */
    fun outputsDir(context: Context, taskId: String): File =
        File(File(context.filesDir, "media/outputs"), taskId)
}

/**
 * 结果下载器：把云端 URL（或 data URI）落到任务输出目录。
 *
 * data URI（OpenAI gpt-image-1 的 b64_json 封装）直接解码落盘，不走网络。
 * 扩展名优先取 URL 路径，其次 Content-Type，最后按 kind 兜底。
 */
object MediaDownloader {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 下载全部 URL，返回成功落盘的本地路径列表（部分失败不阻断，
     * 失败的 URL 记入日志——作品库少一张图好过整个任务判失败）。
     */
    suspend fun download(
        context: Context,
        taskId: String,
        urls: List<String>,
        kind: MediaKind
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = MediaTaskStore.outputsDir(context, taskId).apply { mkdirs() }
        val paths = mutableListOf<String>()
        urls.forEachIndexed { i, url ->
            runCatching { fetch(dir, i, url, kind) }
                .onSuccess { paths += it }
                .onFailure { e ->
                    AgentLogger.e("MediaDownloader", "下载失败 $url：${e.message}")
                }
        }
        paths
    }

    private fun fetch(dir: File, index: Int, url: String, kind: MediaKind): String {
        val fallbackExt = if (kind == MediaKind.TEXT_TO_VIDEO) "mp4" else "png"
        val out = File(dir, "%03d.%s".format(index, extOf(url, fallbackExt)))
        if (url.startsWith("data:")) {
            // data:[<mime>][;base64],<payload>
            val comma = url.indexOf(',')
            require(comma > 0) { "非法 data URI" }
            val meta = url.substring(0, comma)
            val payload = url.substring(comma + 1)
            val bytes = if (meta.contains(";base64")) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
            }
            if (!meta.contains(";base64")) {
                // 非 base64 的 data URI 按文本内容处理（罕见），仍可落盘
            }
            out.writeBytes(bytes)
            return out.absolutePath
        }
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应体")
            out.outputStream().use { body.byteStream().copyTo(it) }
            val ct = body.contentType()?.subtype
            if (ct != null && out.extension != ct) {
                // Content-Type 与 URL 推测不一致时以 Content-Type 为准（如 ?x=1 丢弃后缀）
                val renamed = File(dir, "%03d.%s".format(index, ct.substringBefore('+')))
                if (out.renameTo(renamed)) return renamed.absolutePath
            }
        }
        return out.absolutePath
    }

    private fun extOf(url: String, fallback: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/', "")
        return if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) ext
        else fallback
    }
}
