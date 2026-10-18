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

/**
 * role 常量：字符串散落各处必然拼写漂移，收成文件级常量。
 * `internal`（而非 `private`）：[ConversationBranch]（同包另一文件）迁移/展开时也复用同一常量，
 * 避免两处各写一份 "user"/"assistant" 字符串漂移。
 */
internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"
internal const val ROLE_TOOL = "tool"

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
 * @param promptTokens / completionTokens 功能五（P1-5）：本会话累计 token 用量
 *   （对所有轮次的助手变体求和——重新生成产生的废弃变体也真实花过钱，一并计入）。
 */
@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    /** 功能五（P1-3）：置顶标记——置顶会话恒排在列表最前（对标 RikkaHub pinned_chats）。 */
    val pinned: Boolean = false,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** W-28：绑定的项目目录（空 = 工作区根）。 */
    val workspaceDir: String = ""
)

/**
 * 一条落盘消息。
 *
 * @param role user / assistant / tool 三选一（见 [ROLE_USER] 等常量）。
 * @param id user/assistant 用 UUID；tool 用 ToolCall.id（与内存 _messages 的 Hotfix2 去重键一致）。
 * @param toolName / toolArgs 仅 tool 消息有值；arguments 本就是 JSON 字符串，直接存。
 * @param isError 终态是否失败（执行中态落盘时是空结果占位，终态到达后按 id 覆盖）。
 * @param promptTokens / completionTokens 功能五（P1-5）：assistant 消息的 token 用量
 *   （0 = 无数据——服务端没给 usage 或该消息非助手消息；旧文件反序列化自动补 0）。
 */
@Serializable
data class MsgRecord(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /**
     * 功能五（P1-9）：assistant 消息的推理过程（思维链全文）。
     * 空 = 无思考（旧文件/非推理模型）；与 content 分字段存储，
     * 读回历史时不把自言自语当对话内容发给下一轮。
     */
    val reasoning: String = ""
)

/**
 * 一个完整会话（含全部消息）。
 *
 * ## 双字段 = 向后兼容 + 分支能力（Phase 2 DoD②）
 * @param messages 平坦消息历史（**旧格式字段，保持不变**）：线性渲染与「未分叉会话」的权威来源。
 *   新版仍写它（值 = [turns] 的 [ConversationBranch.flatten]），故**旧版本 App 读新文件**时不认识
 *   `turns` 也能照常显示（ignoreUnknownKeys），新旧双向兼容。
 * @param turns 轮次变体结构（**新增字段，默认空**）：重新生成/左右切换的真源。旧文件缺此字段时
 *   `load()` 用 [ConversationBranch.fromMessages] 从 [messages] 迁移补齐（变体数=1、游标=0，历史不丢）。
 */
@Serializable
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MsgRecord> = emptyList(),
    val turns: List<TurnRecord> = emptyList(),
    /**
     * 功能五（P1-3）：置顶标记（默认 false，旧文件反序列化自动补齐）。
     * 置顶只影响排序，不改变任何消息语义——取消置顶即回到按时间排序。
     */
    val pinned: Boolean = false,
    /**
     * W-28：本会话绑定的项目目录（工作区内的相对路径；空 = 工作区根）。
     * 绑定时会把该目录下的 AGENTS.md / README.md 作为项目规则注入系统提示。
     */
    val workspaceDir: String = ""
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
        content = text.take(MAX_FIELD_CHARS),
        // 功能五（P1-5）：token 用量随气泡落盘（无 usage 存 0，读时按 0 还原为 null）
        promptTokens = usage?.promptTokens ?: 0L,
        completionTokens = usage?.completionTokens ?: 0L,
        // 功能五（P1-9）：推理过程随气泡落盘（截断纪律同正文）
        reasoning = reasoning.orEmpty().take(MAX_FIELD_CHARS)
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

    // 功能五（P1-5）：还原 token 用量；两个计数都 0 = 无数据（旧文件/供应商没给）→ null
    ROLE_ASSISTANT -> UiItem.Assistant(
        content,
        usage = if (promptTokens > 0 || completionTokens > 0) {
            TokenUsage(promptTokens, completionTokens)
        } else {
            null
        },
        // 功能五（P1-9）：还原推理过程（空白 = 无思考，UI 不渲染折叠块）
        reasoning = reasoning.takeIf { it.isNotBlank() }
    )
    // 未知 role 兜底按 assistant 渲染：宁可显示不错丢（历史数据兼容）
    else -> UiItem.Assistant(content)
}

/** List<MsgRecord> → List<UiItem>（VM 载入会话用）。 */
internal fun List<MsgRecord>.toUiItems(): List<UiItem> = map { it.toUiItem() }

/**
 * ConversationRecord → List<UiItem>（**基于 [ConversationRecord.turns] 渲染**，Phase 2 DoD②）。
 *
 * 为什么不再用 `messages.toUiItems()`：变体切换后 UI 必须体现「当前选中变体」，而
 * `messages` 只是 flatten 缓存；逐轮渲染 [TurnRecord] 才能给助手气泡带上
 * `variantIndex / variantCount / turnIndex` 元数据，供切换器 `← 2/3 →` 消费。
 *
 * 单轮顺序：`user → trace（工具卡等）→ 选中变体`，与 [ConversationBranch.flatten] 一致。
 * trace 里的消息按 [MsgRecord.toUiItem] 还原（工具卡元数据默认，不参与切换）。
 */
internal fun ConversationRecord.toUiItems(): List<UiItem> = buildList {
    turns.forEachIndexed { turnIndex, turn ->
        add(UiItem.User(turn.user.content, turnIndex))
        turn.trace.forEach { add(it.toUiItem()) }
        val sel = turn.variants.getOrNull(turn.selectedIndex)
        if (sel != null) {
            add(
                UiItem.Assistant(
                    sel.content,
                    variantIndex = turn.selectedIndex,
                    variantCount = turn.variants.size,
                    turnIndex = turnIndex,
                    // 功能五（P1-5）：选中变体的 token 用量一起还原（无数据 → null）
                    usage = if (sel.promptTokens > 0 || sel.completionTokens > 0) {
                        TokenUsage(sel.promptTokens, sel.completionTokens)
                    } else {
                        null
                    },
                    // 功能五（P1-9）：选中变体的推理过程一起还原（空白 → null）
                    reasoning = sel.reasoning.takeIf { it.isNotBlank() }
                )
            )
        }
    }
}

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

    /**
     * 会话摘要列表（功能五 P1-3：**置顶优先**，组内按 updatedAt 倒序）。
     *
     * 排序契约：`pinned` 降序 → `updatedAt` 降序。用两级比较而非单键，是因为
     * 置顶语义必须是「永远在最前」，不能被新消息顶下去（RikkaHub pinned_chats 同语义）。
     */
    fun list(): List<ConversationSummary> = synchronized(lock) {
        items.map { rec ->
            // 功能五（P1-5）：对**所有轮次的全部变体**求和——重新生成产生的废弃变体
            // 也真实发生过计费，计入比只算选中变体更贴近用户的钱包
            val usage = TokenUsage.sum(
                rec.turns.flatMap { it.variants }
                    .filter { it.role == ROLE_ASSISTANT }
                    .map { TokenUsage(it.promptTokens, it.completionTokens) }
            )
            ConversationSummary(
                id = rec.id,
                title = rec.title,
                updatedAt = rec.updatedAt,
                messageCount = rec.messages.size,
                pinned = rec.pinned,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                workspaceDir = rec.workspaceDir
            )
        }
            .sortedWith(compareByDescending<ConversationSummary> { it.pinned }
                .thenByDescending { it.updatedAt })
    }

    /**
     * 功能五（P1-3）：置顶 / 取消置顶。
     *
     * 只置排序键，不动 updatedAt——否则「置顶」会把会话伪装成「刚聊过」，
     * 取消置顶后它还会赖在列表顶部，用户会以为没生效。
     */
    fun setPinned(convId: String, pinned: Boolean) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            items[idx] = items[idx].copy(pinned = pinned)
        }
        schedulePersist()
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
            // Phase 2 DoD②：先并入轮次变体结构，再据其展开为平坦缓存 messages
            val turns = appendToTurns(conv.turns, rec)
            val messages = ConversationBranch.flatten(turns)
            var title = conv.title
            if (title == DEFAULT_TITLE && rec.role == ROLE_USER && rec.content.isNotBlank()) {
                title = rec.content.trim().take(TITLE_CHARS)
            }
            items[idx] = conv.copy(
                title = title,
                updatedAt = System.currentTimeMillis(),
                messages = messages,
                turns = turns
            )
        }
        schedulePersist()
    }

    /**
     * 把一条新消息并入轮次变体结构（纯函数，Phase 2 DoD②）：
     *  - user → 新开一轮（[TurnRecord.user]）；
     *  - assistant → 作为**当前轮**的助手变体追加并把游标指向它（重新生成也走这条：追加=新变体，
     *    原变体保留可切回）；
     *  - tool → 按 id upsert 进**当前轮的 trace**（ToolStarted 空结果占位 → ToolFinished 终态覆盖，
     *    语义与旧 `messages` 的 replace 一致）。
     * 轮数超 [MAX_MESSAGES] 丢最旧轮；无当前轮（异常数据）时安静忽略，绝不崩溃。
     */
    private fun appendToTurns(turns: List<TurnRecord>, rec: MsgRecord): List<TurnRecord> {
        val out = turns.toMutableList()
        when (rec.role) {
            ROLE_USER -> out.add(TurnRecord(user = rec))
            ROLE_ASSISTANT -> {
                val i = out.lastIndex
                if (i >= 0) {
                    val t = out[i]
                    val variants = t.variants + rec
                    out[i] = t.copy(variants = variants, selectedIndex = variants.lastIndex)
                }
            }
            ROLE_TOOL -> {
                val i = out.lastIndex
                if (i >= 0) {
                    val t = out[i]
                    val trace = t.trace.toMutableList()
                    val e = trace.indexOfFirst { it.role == ROLE_TOOL && it.id == rec.id }
                    if (e >= 0) trace[e] = rec else trace.add(rec)
                    out[i] = t.copy(trace = trace)
                }
            }
            else -> Unit
        }
        while (out.size > MAX_MESSAGES) out.removeAt(0)
        return out
    }

    /**
     * 左右切换某轮的选中变体（Phase 2 DoD②），[delta] = -1 左 / +1 右。
     * 委托 [ConversationBranch.select]（边界 clamp：首个左移、末个右移原地不动；单变体轮不响应）。
     * 切换后同步写回 `messages = flatten(turns)`，保证「下一次请求的历史」用的是选中变体。
     * 会话不存在或轮次越界时静默 no-op（不崩）。
     *
     * @return 切换后该轮选中的变体下标（无法定位时为 0）。
     */
    fun selectVariant(convId: String, turnIndex: Int, delta: Int): Int {
        var selected = 0
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return 0
            val conv = items[idx]
            val turns = ConversationBranch.select(conv.turns, turnIndex, delta)
            selected = turns.getOrNull(turnIndex)?.selectedIndex ?: 0
            items[idx] = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = turns,
                messages = ConversationBranch.flatten(turns)
            )
        }
        schedulePersist()
        return selected
    }

    /**
     * 为第 [turnIndex] 轮追加一个助手变体并选中（重新生成的持久化写入口）。
     * 轮次越界时 no-op（[ConversationBranch.appendVariant] 原样返回），不崩。
     */
    fun appendVariant(convId: String, turnIndex: Int, variant: MsgRecord) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            val conv = items[idx]
            val turns = ConversationBranch.appendVariant(conv.turns, turnIndex, variant)
            items[idx] = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = turns,
                messages = ConversationBranch.flatten(turns)
            )
        }
        schedulePersist()
    }

    /**
     * 「从中间轮重新发问」的截断半程：保留 `turns[0..keepThroughIndex]`（含该轮），丢弃其后所有轮。
     * 新问题由调用方（VM）随后以 user 消息开一轮（与 send 路径一致）。
     * KDoc 裁决见 [ConversationBranch.reaskAfter]。
     *
     * @return 截断后剩余的轮次数（会话不存在时为 0）。
     */
    fun truncateAfter(convId: String, keepThroughIndex: Int): Int {
        var remaining = 0
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return 0
            val conv = items[idx]
            val end = (keepThroughIndex + 1).coerceIn(0, conv.turns.size)
            val turns = conv.turns.take(end)
            remaining = turns.size
            items[idx] = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = turns,
                messages = ConversationBranch.flatten(turns)
            )
        }
        schedulePersist()
        return remaining
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

    /**
     * W-28：绑定/解绑项目目录（空串 = 解绑，回到工作区根）。
     * 只改记录字段，不影响消息内容。
     */
    fun setWorkspaceDir(convId: String, dir: String) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.id == convId }
            if (idx < 0) return
            items[idx] = items[idx].copy(
                workspaceDir = dir.trim().trim('/'),
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

    /**
     * 功能五（P1-4b）：全量快照（备份用）。返回不可变拷贝——调用方拿到的是
     * records 的浅拷贝列表，record 内部 list 均为不可变 List，外部改不动。
     */
    fun snapshot(): List<ConversationRecord> = synchronized(lock) { items.toList() }

    /**
     * 功能五（P1-4b）：整库重灌（恢复用，**破坏性**——调用方 UI 必须先拿到用户确认）。
     *
     * 三条保证：
     *  ① 每条都过 [migrate]：恢复老备份件（无 turns 字段）时按新格式迁移，不丢历史；
     *  ② 同步替换内存 + 预约落盘：恢复完成后即使进程被杀，落盘内容也是恢复后的数据；
     *  ③ 与 [load] 同语义的 items 替换，无并发窗口（synchronized）。
     * 调用方负责随后重载 UI 状态（会话列表/当前会话/消息），见 ChatViewModel.restoreBackup。
     */
    fun replaceAll(list: List<ConversationRecord>) {
        synchronized(lock) {
            items.clear()
            items.addAll(list.map { migrate(it) })
        }
        schedulePersist()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 读盘：优先主文件，缺失或损坏时回退 .bak；两者都不可用退化为空列表
     * （宁可没有聊天记录，也不能因为文件坏掉崩掉主流程）。
     * 读入的每条会话都过 [migrate]（旧格式 → 轮次变体，向后兼容）。
     */
    private fun load() {
        val parsed = parseOrNull(readTextOrEmpty(file)) ?: parseOrNull(readTextOrEmpty(bakFile))
        if (parsed != null) {
            synchronized(lock) {
                items.clear()
                items.addAll(parsed.map { migrate(it) })
            }
        }
    }

    /**
     * 单条会话的旧格式迁移（纯函数，Phase 2 DoD②）：
     *  - [ConversationRecord.turns] 非空（新格式）→ 只做 [ConversationBranch.normalize]（夹游标）；
     *  - turns 为空、messages 非空（**旧格式，无 variants 字段**）→ [ConversationBranch.fromMessages]
     *    迁移：每个旧会话变体数=1、selectedIndex=0，历史内容进 trace/variants，**不丢**；
     *  - messages 也为空 → 空会话，turns 仍为空。
     *
     * messages 权威保留（不重算）——良构旧数据 `flatten(fromMessages(messages)) == messages`，
     * 故迁移后再落盘内容零变化；仅当新格式文件 messages 意外为空时用 flatten 回填做兜底。
     */
    private fun migrate(rec: ConversationRecord): ConversationRecord {
        val turns = if (rec.turns.isNotEmpty()) {
            ConversationBranch.normalize(rec.turns)
        } else if (rec.messages.isNotEmpty()) {
            ConversationBranch.normalize(ConversationBranch.fromMessages(rec.messages))
        } else {
            emptyList()
        }
        val messages = if (rec.messages.isEmpty() && turns.isNotEmpty()) {
            ConversationBranch.flatten(turns)
        } else {
            rec.messages
        }
        return rec.copy(messages = messages, turns = turns)
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
