package com.example.agentkit.core

import com.example.agentkit.ui.UiItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 功能三：聊天记录（用户真机录屏反馈：「人家都有聊天记录对话记录，你这都没有，做好一点行吗」）。
 *
 * 持久化形态学 MemoryStore（app/src/main/java/com/example/agentkit/memory/MemoryStore.kt，
 * 先读它学原子写模式）：
 *  - 文件 conversations.json 放 App filesDir，tmp + rename 原子写 + .bak 备份；
 *  - `Json { ignoreUnknownKeys = true; prettyPrint = true }`，`synchronized(lock)` 护 items；
 *  - 与 MemoryStore 的唯一差异：落盘走单线程执行器**异步串行**——工具事件（开始/结束）
 *    每次都触发一次追加，主线程同步重写整个文件会 jank；串行执行器 FIFO 保序、不交叉。
 *
 * 跨包引用说明（UiItem 定义在 ui 包）：UiItem ↔ MsgRecord 映射与存储同文件，
 * 避免 VM 里散落「内存格式 / 存储格式 / 历史格式」三家转换；core 不反向依赖 ui 的其他东西。
 */

/** role 常量：字符串散落各处必然拼写漂移，收成文件级常量。 */
private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val ROLE_TOOL = "tool"

/** 新对话默认标题：首条 user 消息到达时按内容前 [TITLE_CHARS] 字自动命名。 */
private const val DEFAULT_TITLE = "新对话"

/**
 * 单条消息内容/入参的落盘上限。
 * 为什么截断：ui_tree 转储、read_file 大文件等工具结果可达数十 KB，200 条不截断的
 * 会话能把 conversations.json 撑到 MB 级，每次追加都全量重写会明显卡顿。
 * UI 气泡仍显示完整结果——截断只影响落盘的那一份。
 */
private const val MAX_FIELD_CHARS = 4000

/**
 * 会话摘要（抽屉「最近对话」区直接消费，不加载消息正文——列表要轻）。
 *
 * @param updatedAt 最近一次消息/重命名的时间（列表倒序键）。
 * @param messageCount 消息条数（摘要里展示，用户判断「这个会话聊到哪了」）。
 */
@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

/**
 * 一条落盘消息。
 *
 * @param role user / assistant / tool 三选一（见 [ROLE_USER] 等常量）。
 * @param id user/assistant 用 UUID；tool 用 ToolCall.id（与内存 _messages 的 Hotfix2 去重键一致）。
 * @param toolName / toolArgs 仅 tool 消息有值；arguments 本就是 JSON 字符串，直接存。
 * @param isError 终态是否失败（执行中态落盘时是空结果占位，终态到达后按 id 覆盖）。
 */
@Serializable
data class MsgRecord(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false
)

/** 一个完整会话（含全部消息）。 */
@Serializable
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MsgRecord>
)

/**
 * UiItem → MsgRecord（写入侧映射）。
 *
 * tool 消息的 images（data URL）不落盘：截图动辄数百 KB，存进去文件会失控；
 * 重进会话时工具卡无图但结果文字还在——可接受的取舍（录屏反馈要的是「有记录」）。
 */
internal fun UiItem.toMsgRecord(): MsgRecord = when (this) {
    is UiItem.User -> MsgRecord(
        id = UUID.randomUUID().toString(),
        role = ROLE_USER,
        content = text.take(MAX_FIELD_CHARS)
    )

    is UiItem.Assistant -> MsgRecord(
        id = UUID.randomUUID().toString(),
        role = ROLE_ASSISTANT,
        content = text.take(MAX_FIELD_CHARS)
    )

    is UiItem.Tool -> MsgRecord(
        // tool 的 id = call.id：appendMessage 据此覆盖「执行中 → 终态」两次写入
        id = call.id,
        role = ROLE_TOOL,
        content = result.take(MAX_FIELD_CHARS),
        toolName = call.name,
        // arguments 是 JsonObject，toString() 即标准 JSON 文本，直接存
        toolArgs = call.arguments.toString().take(MAX_FIELD_CHARS),
        isError = isError
    )
}

/** MsgRecord → UiItem（读取侧映射）；running 一律 false——落盘的 tool 只有终态与空结果占位。 */
internal fun MsgRecord.toUiItem(): UiItem = when (role) {
    ROLE_TOOL -> UiItem.Tool(
        call = ToolCall(
            id = id,
            name = toolName.orEmpty(),
            arguments = parseToolArgs(toolArgs)
        ),
        result = content,
        isError = isError
    )

    ROLE_USER -> UiItem.User(content)
    // 未知 role 兜底按 assistant 渲染：宁可显示不错丢（历史数据兼容）
    else -> UiItem.Assistant(content)
}

/** List<MsgRecord> → List<UiItem>（VM 载入会话用）。 */
internal fun List<MsgRecord>.toUiItems(): List<UiItem> = map { it.toUiItem() }

/** 解析落盘的 toolArgs；损坏/为空时退回空 object（工具卡入参块显示空白而非崩溃）。 */
private fun parseToolArgs(raw: String?): JsonObject {
    if (raw.isNullOrBlank()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(raw).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }
}

/**
 * 聊天记录存储：多会话 + 消息持久化（功能三）。
 *
 * 容量策略：单会话消息数封顶 [MAX_MESSAGES] 条，超出丢最旧——聊天记录要「有」，
 * 但不能让一个超长会话把文件撑到影响读写性能。
 */
class ConversationStore(file: File) {

    private val file: File = file

    /** 上一版良好快照：主文件损坏时由 [load] 回退读取（同 MemoryStore）。 */
    private val bakFile = File(file.parentFile, file.name + ".bak")

    /** 原子写中转文件：写完 rename 替换主文件，半截 JSON 不会落地。 */
    private val tmpFile = File(file.parentFile, file.name + ".tmp")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val items = mutableListOf<ConversationRecord>()

    /**
     * 落盘执行器：单线程 + daemon，FIFO 串行。
     * 为什么异步：工具事件（开始/结束）每次都会追加消息，主线程同步重写整个
     * conversations.json 会 jank；串行执行器保证写序不乱、不会两个 persist 交叉。
     * daemon 线程不拖住进程退出；进程被杀时最多丢失「最后一次预约还没执行」的少量消息。
     */
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conversation-store-writer").apply { isDaemon = true }
    }

    init {
        runCatching { file.parentFile?.mkdirs() }
        load()
    }

    /** 会话摘要列表（updatedAt 倒序；抽屉「最近对话」与对话页顶栏标题消费）。 */
    fun list(): List<ConversationSummary> = synchronized(lock) {
        items.map { ConversationSummary(it.id, it.title, it.updatedAt, it.messages.size) }
            .sortedByDescending { it.updatedAt }
    }

    /** 加载完整会话；不存在返回 null。返回拷贝，防调用方改内部状态。 */
    fun load(id: String): ConversationRecord? = synchronized(lock) {
        items.firstOrNull { it.id == id }?.let { it.copy(messages = it.messages.toList()) }
    }

    /** 新建会话（标题「新对话」，首条 user 消息到达时自动命名），返回 id。 */
    fun create(): String {
        val now = System.currentTimeMillis()
        val rec = ConversationRecord(
            id = UUID.randomUUID().toString(),
            title = DEFAULT_TITLE,
            createdAt = now,
            updatedAt = now,
            messages = emptyList()
        )
        synchronized(lock) { items.add(rec) }
        schedulePersist()
        return rec.id
    }

    /**
     * 追加一条消息（同步更新内存 + 预约落盘）。
     *
     * 两条规则：
     * ① tool 消息按 id 覆盖——ToolStarted（空结果占位）与 ToolFinished（终态）是同一
     *    call.id 的两次写入，后者必须替换前者，与内存 _messages 的 replace 语义一致；
     * ② 首条 user 消息且标题仍是「新对话」时，title = 内容前 [TITLE_CHARS] 字符
     *    （和别家聊天记录一样自动命名，省用户手打）。
     */
    fun appendMessage(convId: String, rec: MsgRecord) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            // 会话已被删：静默丢弃，不崩溃（VM 有运行中守卫，双保险）
            if (idx < 0) return
            val conv = items[idx]
            val messages = conv.messages.toMutableList()
            if (rec.role == ROLE_TOOL) {
                val existing = messages.indexOfFirst { it.role == ROLE_TOOL && it.id == rec.id }
                if (existing >= 0) messages[existing] = rec else messages.add(rec)
            } else {
                messages.add(rec)
            }
            // 单会话消息数封顶：超出丢最旧，防文件无限膨胀
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
            var title = conv.title
            if (title == DEFAULT_TITLE && rec.role == ROLE_USER && rec.content.isNotBlank()) {
                title = rec.content.trim().take(TITLE_CHARS)
            }
            items[idx] = conv.copy(
                title = title,
                updatedAt = System.currentTimeMillis(),
                messages = messages
            )
        }
        schedulePersist()
    }

    /** 重命名（空名回落默认标题，防用户清空文本框后按确定）。 */
    fun rename(convId: String, title: String) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            items[idx] = items[idx].copy(
                title = title.trim().ifBlank { DEFAULT_TITLE },
                updatedAt = System.currentTimeMillis()
            )
        }
        schedulePersist()
    }

    /** 删除会话（含其全部消息，不可恢复——UI 层已有二次确认）。 */
    fun delete(convId: String) {
        synchronized(lock) { items.removeAll { it.id == convId } }
        schedulePersist()
    }

    /**
     * 保证至少有一个会话：列表空则 create 一个；否则返回最近更新的会话 id。
     * VM 初始化用（首启即有一个可写会话，聊天记录不会「无处可写」）。
     */
    fun ensureNotEmpty(): String {
        synchronized(lock) {
            items.maxByOrNull { it.updatedAt }?.let { return it.id }
        }
        return create()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 读盘：优先主文件，缺失或损坏时回退 .bak；两者都不可用退化为空列表
     * （宁可没有聊天记录，也不能因为文件坏掉崩掉主流程）。
     */
    private fun load() {
        val parsed = parseOrNull(readTextOrEmpty(file)) ?: parseOrNull(readTextOrEmpty(bakFile))
        if (parsed != null) {
            synchronized(lock) {
                items.clear()
                items.addAll(parsed)
            }
        }
    }

    /** 预约一次异步落盘（执行器 FIFO，多次预约变成有序的多次全量写，最终态一致）。 */
    private fun schedulePersist() {
        runCatching {
            persistExecutor.execute {
                runCatching { persist() }
            }
        }
    }

    /**
     * 原子写：完整快照编码后写入 .tmp；上一版主文件备份为 .bak；最后 rename 替换主文件。
     * rename（同一目录）是原子的，写一半被杀进程也不会把文件打成半截 JSON。
     */
    private fun persist() {
        val snapshot = synchronized(lock) { items.toList() }
        runCatching {
            val payload = json.encodeToString(snapshot)
            tmpFile.writeText(payload)
            if (file.exists()) {
                runCatching {
                    if (bakFile.exists()) bakFile.delete()
                    file.copyTo(bakFile, overwrite = true)
                }
            }
            if (!tmpFile.renameTo(file)) {
                // 极少数文件系统 rename 失败：退化为直接写，保证至少有一份可读数据
                file.writeText(payload)
                runCatching { tmpFile.delete() }
            }
        }
    }

    private fun readTextOrEmpty(f: File): String =
        runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")

    private fun parseOrNull(text: String): List<ConversationRecord>? =
        if (text.isBlank()) null
        else runCatching { json.decodeFromString<List<ConversationRecord>>(text) }.getOrNull()

    private companion object {
        /** 单会话消息数封顶（超出丢最旧）。 */
        const val MAX_MESSAGES = 200

        /** 自动命名时取用户首条消息的前 N 个字符。 */
        const val TITLE_CHARS = 12
    }
}
