package com.example.agentkit.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 日志级别：从细到粗。真机排查时主要看 WARN / ERROR。
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条日志记录。存内存环形缓冲 + 异步落盘，供 UI「运行日志（日记）」页直接渲染。
 *
 * @param stack 异常堆栈（已按需截断），仅在 e/w 传 Throwable 时存在。
 */
data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val stack: String? = null
) {
    /** 单行格式：`HH:mm:ss.SSS LEVEL [tag] message`，有堆栈时换行追加。 */
    fun format(): String {
        val head = "${formatClock(timeMs)} ${level.name} [$tag] $message"
        val st = stack
        return if (st.isNullOrBlank()) head else "$head\n${st.take(PREVIEW_MAX)}"
    }
}

/**
 * 运行日志核心（L8「运行日志（日记）」，净室原创）。
 *
 * 解决的问题：真机运行出错时看不到任何线索，只能靠猜。这里把 LLM 请求/响应、
 * 工具调用/结果、HTTP 错误、异常堆栈全部落到 `dir/agent-<yyyyMMdd>.log`，同时保留
 * 一份内存环形缓冲给 UI 实时展示。
 *
 * 设计要点：
 *  - **纯 Kotlin，不 import android.***：core 层可在 JVM 单测里直接跑。
 *  - **绝不记录 apiKey / 完整请求体**：只存模型名、消息条数、工具个数、响应预览。
 *  - **写盘失败不能崩溃**：所有 IO 都包了 runCatching，日志只是旁路，不能拖垮主线程业务。
 *  - **异步落盘**：IO 协程里追加写，单文件超 2MB 轮转为 `.1`。
 */
object AgentLogger {

    /** 内存缓冲上限：超过后丢弃最旧记录，避免长时间运行 OOM。 */
    private const val MAX_MEMORY = 500

    /** 单文件轮转阈值：2MB。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val buffer: MutableList<LogEntry> =
        Collections.synchronizedList(ArrayList<LogEntry>())

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var logDir: File? = null

    /** 初始化落盘目录并启动异步写盘（幂等，可重复调用切换到新目录）。 */
    fun init(dir: File) {
        runCatching { if (!dir.exists()) dir.mkdirs() }
        logDir = dir
        i("AgentLogger", "日志目录已就绪：${dir.absolutePath}")
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.DEBUG, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.INFO, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.ERROR, tag, msg, t)

    /** 工具调用入参（预览，默认截断到 300 字符）。 */
    fun toolCall(name: String, argsPreview: String) {
        d("Tool", "→ $name $argsPreview")
    }

    /** 工具返回结果（是否出错 / 耗时 / 结果预览）。 */
    fun toolResult(name: String, ok: Boolean, ms: Long, preview: String) {
        val level = if (ok) LogLevel.DEBUG else LogLevel.WARN
        record(level, "Tool", "← $name ${if (ok) "ok" else "ERROR"} ${ms}ms $preview")
    }

    /** LLM 请求发出前的记录：只记模型名/消息数/工具数，绝不记 apiKey。 */
    fun llmRequest(model: String, msgCount: Int, toolCount: Int) {
        i("LLM", "→ 请求 model=$model messages=$msgCount tools=$toolCount")
    }

    /** LLM 响应（含耗时与预览）。 */
    fun llmResponse(model: String, ok: Boolean, ms: Long, preview: String) {
        val level = if (ok) LogLevel.INFO else LogLevel.ERROR
        record(level, "LLM", "← 响应 model=$model ${if (ok) "ok" else "FAIL"} ${ms}ms $preview")
    }

    /** HTTP 非 2xx：记状态码 + 响应体预览（截断 800），这是定位鉴权/参数错误的关键。 */
    fun httpError(code: Int, bodyPreview: String) {
        record(LogLevel.ERROR, "HTTP", "请求失败 code=$code body=${bodyPreview.take(PREVIEW_MAX)}")
    }

    /** 最近的日志（最新的在前），默认 200 条。 */
    fun recent(limit: Int = 200): List<LogEntry> = synchronized(buffer) {
        val size = buffer.size
        val from = (size - limit).coerceAtLeast(0)
        val tail = ArrayList<LogEntry>(size - from)
        for (i in size - 1 downTo from) tail.add(buffer[i])
        tail
    }

    /** 导出全文（按时间正序），供用户复制到剪贴板排查。 */
    fun snapshotText(limit: Int = 500): String = synchronized(buffer) {
        val from = (buffer.size - limit).coerceAtLeast(0)
        (from until buffer.size).joinToString("\n") { buffer[it].format() }
    }

    /** 清空内存缓冲与当日日志文件。 */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        currentLogFile()?.let { runCatching { if (it.exists()) it.delete() } }
    }

    /** 当日日志文件：`dir/agent-<yyyyMMdd>.log`；未初始化时返回 null。 */
    fun currentLogFile(): File? {
        val dir = logDir ?: return null
        return runCatching { File(dir, "agent-${formatDay(System.currentTimeMillis())}.log") }.getOrNull()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    private fun record(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, msg, stackOf(t))
        runCatching {
            synchronized(buffer) {
                buffer.add(entry)
                // 环形缓冲：超过上限移除最旧
                var overflow = buffer.size - MAX_MEMORY
                while (overflow > 0) { buffer.removeAt(0); overflow-- }
            }
        }
        val dir = logDir ?: return
        ioScope.launch { appendToDisk(dir, entry) }
    }

    private fun appendToDisk(dir: File, entry: LogEntry) {
        runCatching {
            if (!dir.exists() && dir.mkdirs().not()) return
            val file = currentLogFile() ?: File(dir, "agent-${formatDay(entry.timeMs)}.log")
            if (file.exists() && file.length() > MAX_FILE_BYTES) rotate(file)
            file.appendText(entry.format() + "\n")
        }
    }

    /** 轮转：当前文件重命名为 `<name>.1`（旧的 .1 直接覆盖删除）。 */
    private fun rotate(file: File) {
        val backup = File(file.parentFile, "${file.name}.1")
        runCatching { if (backup.exists()) backup.delete() }
        runCatching { file.renameTo(backup) }
    }

    private fun stackOf(t: Throwable?): String? =
        t?.let { runCatching { it.stackTraceToString() }.getOrNull()?.takeIf { it.isNotBlank() } }
}

/* ------------------------- 时间格式（SimpleDateFormat 非线程安全，加锁访问） ------------------------- */

/** 预览文本（堆栈、响应体等）统一截断长度。 */
private const val PREVIEW_MAX = 800

private val clockLock = Any()
private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

private fun formatClock(ms: Long): String = synchronized(clockLock) { clockFormat.format(Date(ms)) }
private fun formatDay(ms: Long): String = synchronized(clockLock) { dayFormat.format(Date(ms)) }
