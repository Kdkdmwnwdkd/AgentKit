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
 *  - **绝不记录明文敏感数据**：[record] 里统一对消息做 [sanitize]，API Key / Bearer /
 *    api_key / token / password 一律替换为 `***`；[SENSITIVE_TOOLS] 中的工具只记长度不记原文。
 *  - **写盘失败不能崩溃**：所有 IO 都包了 runCatching，日志只是旁路，不能拖垮主线程业务。
 *  - **写盘串行化 + 定期清理**：落盘走单线程 IO 协程避免行交错；超过 [LOG_RETENTION_DAYS]
 *    天的历史日志在 [init] 时清理，避免无限堆积。
 */
object AgentLogger {

    /** 内存缓冲上限：超过后丢弃最旧记录，避免长时间运行 OOM。 */
    private const val MAX_MEMORY = 500

    /** 单文件轮转阈值：2MB。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 磁盘保留天数：超过该天数的 `agent-<yyyyMMdd>.log` 在初始化时被删除。 */
    private const val LOG_RETENTION_DAYS = 7L

    /** 一天毫秒数，仅用于保留天数换算。 */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 脱敏替换串。 */
    private const val MASK = "***"

    private val buffer: MutableList<LogEntry> =
        Collections.synchronizedList(ArrayList<LogEntry>())

    /**
     * 写盘串行化：`limitedParallelism(1)` 让落盘只用一个线程，
     * 既不阻塞 IO 线程池其他任务，也不会因每条日志一个协程导致行交错/堆积。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * 敏感信息脱敏规则（大小写不敏感）。
     * 顺序很重要：先掩码长串凭据（sk-/Bearer），再掩码 key=value 形态，避免残留片段。
     */
    private val sanitizeRules: List<Regex> = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}""", RegexOption.IGNORE_CASE),
        Regex("""Bearer\s+[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""api[_-]?key"?\s*[:=]\s*"?[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""token"?\s*[:=]\s*"?[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""password"?\s*[:=]\s*"?\S+""", RegexOption.IGNORE_CASE)
    )

    /**
     * 内容天然携带隐私/凭据的工具：其入参与返回值**只记长度，不记原文**，
     * 否则 http_request 的 headers/body（含 API Key）、ui_input 的密码、剪贴板、
     * 通知正文（验证码/私信）、read_file 的文件内容都会明文落到磁盘上的日志里。
     */
    private val sensitiveTools: Set<String> = setOf(
        "device_notifications",
        "device_foreground_app",
        "read_file",
        "clipboard",
        "http_request",
        "ui_input",
        "ui_screenshot"
    )

    @Volatile
    private var logDir: File? = null

    /**
     * 日志脱敏：把 ApiKey / Bearer / api_key / token / password 等敏感片段替换为 `***`。
     *
     * 公开给调用方（如 LLMClient 抛出的 IOException 文案、ChatViewModel 写进聊天气泡的错误文本）
     * 在离开日志系统之前先自行脱敏。
     *
     * @param s 原始文本；为空或正则异常时原样返回，保证「旁路不影响主流程」。
     */
    fun sanitize(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        for (rule in sanitizeRules) {
            out = runCatching { rule.replace(out, MASK) }.getOrDefault(out)
        }
        return out
    }

    /** 初始化落盘目录、清理历史日志并启动异步写盘（幂等，可重复调用切换到新目录）。 */
    fun init(dir: File) {
        runCatching { if (!dir.exists()) dir.mkdirs() }
        logDir = dir
        i("AgentLogger", "日志目录已就绪：${dir.absolutePath}")
        // 清理放到串行 IO 协程：不阻塞主线程，也不与正在进行的写盘竞争
        ioScope.launch { cleanupOldLogs(dir, LOG_RETENTION_DAYS) }
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.DEBUG, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.INFO, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.ERROR, tag, msg, t)

    /**
     * 工具调用入参。
     * 敏感工具只记 `参数已脱敏(长度=N)`，绝不记原文。
     */
    fun toolCall(name: String, argsPreview: String) {
        val shown = if (name in sensitiveTools) {
            "参数已脱敏(长度=${argsPreview.length})"
        } else {
            argsPreview
        }
        d("Tool", "→ $name $shown")
    }

    /**
     * 工具返回结果（是否出错 / 耗时 / 结果预览）。
     * 敏感工具只记 `结果已脱敏(长度=N)`，绝不记内容。
     */
    fun toolResult(name: String, ok: Boolean, ms: Long, preview: String) {
        val level = if (ok) LogLevel.DEBUG else LogLevel.WARN
        val shown = if (name in sensitiveTools) {
            "结果已脱敏(长度=${preview.length})"
        } else {
            preview
        }
        record(level, "Tool", "← $name ${if (ok) "ok" else "ERROR"} ${ms}ms $shown")
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

    /**
     * HTTP 非 2xx：记状态码 + 响应体预览（截断 800），这是定位鉴权/参数错误的关键。
     * 调用方（LLMClient）已先行脱敏；[record] 会再脱一次，双保险防止 401 响应体回显密钥。
     */
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

    /** 清空内存缓冲与当日日志文件（含轮转出来的 `.log.1`）。 */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        val dir = logDir ?: return
        val day = formatDay(System.currentTimeMillis())
        runCatching { File(dir, "agent-$day.log").takeIf { it.exists() }?.delete() }
        runCatching { File(dir, "agent-$day.log.1").takeIf { it.exists() }?.delete() }
    }

    /** 当日日志文件：`dir/agent-<yyyyMMdd>.log`；未初始化时返回 null。 */
    fun currentLogFile(): File? {
        val dir = logDir ?: return null
        return runCatching { File(dir, "agent-${formatDay(System.currentTimeMillis())}.log") }.getOrNull()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 唯一出口：所有日志消息在入缓冲/落盘前统一过一遍 [sanitize]，一处覆盖全部日志形态。
     */
    private fun record(level: LogLevel, tag: String, msg: String, t: Throwable? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, sanitize(msg), stackOf(t))
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

    /**
     * 清理过期日志：`agent-<yyyyMMdd>.log` / `.log.1`，保留最近 [keepDays] 天。
     * 文件名中的日期解析失败（非 `yyyyMMdd` 或非法日期）时跳过该文件，不做猜测删除。
     */
    private fun cleanupOldLogs(dir: File, keepDays: Long) {
        runCatching {
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - keepDays * DAY_MS
            // 局部 SimpleDateFormat（非线程安全，故不用共享实例）；lenient=false 拒绝 20241340 这类非法日期
            val dayParser = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).apply { isLenient = false }
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (!name.startsWith("agent-")) continue
                if (!name.endsWith(".log") && !name.endsWith(".log.1")) continue
                val dayPart = name.removePrefix("agent-").substringBefore(".log")
                val dayMs = runCatching { dayParser.parse(dayPart) }.getOrNull()?.time ?: continue
                if (dayMs < cutoff) runCatching { file.delete() }
            }
        }
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
