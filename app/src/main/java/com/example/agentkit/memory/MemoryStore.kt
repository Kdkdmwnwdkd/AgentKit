package com.example.agentkit.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 一条长期记忆（L9「记忆库」，净室原创）。
 *
 * @param kind 语义分类：fact（事实）/ preference（偏好）/ event（事件）/ note（随手记）。
 * @param tags 检索用标签，辅助关键词命中。
 * @param salience 重要度 1..10，越高越不容易被淘汰，也越容易被摘进提示词。
 */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val salience: Int = 5
)

/**
 * 记忆库：让 Agent 拥有跨会话的长期记忆。
 *
 * 持久化到 `dir/memory.json`（prettyPrint 的 JSON 数组），读写全部 synchronized + runCatching：
 * 记忆损坏/空间不足都不能让主流程崩溃（最多是「这次没记住」）。
 *
 * 检索采用轻量打分（关键词命中 + salience 权重 + 时间衰减），不引入 embedding 依赖，
 * 保证在真机上零额外开销；容量上限 500 条，超出时淘汰「salience 最低且最旧」的条目。
 */
class MemoryStore(private val dir: File) {

    private val file = File(dir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    /** 允许的记忆类型白名单，未知的 kind 一律归一为 note，避免 LLM 传任意字符串污染检索。 */
    private val knownKinds = listOf("fact", "preference", "event", "note")

    private val items: MutableList<MemoryItem> = ArrayList()

    init {
        runCatching { if (!dir.exists()) dir.mkdirs() }
        load()
    }

    /** 写入一条记忆（id/时间戳自动生成；持久化失败也不影响主流程）。 */
    fun add(
        content: String,
        kind: String = "note",
        tags: List<String> = emptyList(),
        salience: Int = 5
    ): MemoryItem {
        val now = System.currentTimeMillis()
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            kind = normalizeKind(kind),
            content = content.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            createdAt = now,
            updatedAt = now,
            salience = salience.coerceIn(MIN_SALIENCE, MAX_SALIENCE)
        )
        synchronized(lock) {
            items.add(item)
            trimToLimit()
            persist()
        }
        return item
    }

    /** 关键词检索 + salience/时间衰减打分，返回最相关的 top-K。query 为空时退化为最近列表。 */
    fun search(query: String, topK: Int = 10): List<MemoryItem> {
        val keywords = tokenize(query)
        val snapshot = synchronized(lock) { items.toList() }
        if (keywords.isEmpty()) return snapshot.sortedByDescending { it.updatedAt }.take(topK.coerceAtLeast(1))
        val now = System.currentTimeMillis()
        return snapshot
            .map { item -> Scored(item, scoreOf(item, keywords, now)) }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.item.updatedAt })
            .take(topK.coerceAtLeast(1))
            .map { it.item }
    }

    /** 最近记忆列表（按 updatedAt 降序）。 */
    fun list(limit: Int = 50): List<MemoryItem> {
        val snapshot = synchronized(lock) { items.toList() }
        return snapshot.sortedByDescending { it.updatedAt }.take(limit.coerceAtLeast(0))
    }

    /** 按 id 遗忘；返回是否真的删掉了。 */
    fun forget(id: String): Boolean {
        val removed = synchronized(lock) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx < 0) {
                false
            } else {
                items.removeAt(idx)
                persist()
                true
            }
        }
        return removed
    }

    /** 清空全部记忆（含落盘文件）。 */
    fun clear() {
        synchronized(lock) {
            items.clear()
            persist()
        }
    }

    fun size(): Int = synchronized(lock) { items.size }

    /**
     * 生成注入系统提示的记忆摘要，形如：
     * `<memory><item kind="fact">…</item>…</memory>`
     *
     * 必须做 XML 转义（用户内容里可能有 `<` `&`），且总长不超过 maxChars（宁少勿超）。
     */
    fun summaryForPrompt(maxItems: Int = 12, maxChars: Int = 1200): String {
        val snapshot = synchronized(lock) { items.toList() }
        if (snapshot.isEmpty()) return ""
        val picked = snapshot
            .sortedWith(compareByDescending<MemoryItem> { it.salience }.thenByDescending { it.updatedAt })
            .take(maxItems.coerceAtLeast(1))
        val tail = "</memory>"
        val sb = StringBuilder("<memory>")
        picked.forEach { item ->
            val piece = "<item kind=\"${escapeXml(item.kind)}\">${escapeXml(item.content.take(CONTENT_SNIPPET))}</item>"
            if (sb.length + piece.length + tail.length > maxChars) return@forEach
            sb.append(piece)
        }
        if (sb.length == "<memory>".length) return ""
        sb.append(tail)
        return sb.toString()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /** 打分数据对（轻量载体，避免 Pair 的语义模糊）。 */
    private data class Scored(val item: MemoryItem, val score: Double)

    private fun load() {
        val text = synchronized(lock) {
            runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        }
        if (text.isBlank()) return
        val parsed = runCatching { json.decodeFromString<List<MemoryItem>>(text) }.getOrNull() ?: return
        synchronized(lock) {
            items.clear()
            items.addAll(parsed)
        }
    }

    private fun persist() {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            file.writeText(json.encodeToString(items.toList()))
        }
    }

    /** 容量上限保护：优先淘汰「salience 最低且最旧」的条目。 */
    private fun trimToLimit() {
        var overflow = items.size - MAX_ITEMS
        while (overflow > 0 && items.isNotEmpty()) {
            val victim = items
                .mapIndexed { index, item -> index to item }
                .minWith(compareBy<Pair<Int, MemoryItem>> { it.second.salience }.thenBy { it.second.updatedAt })
                .first
            items.removeAt(victim)
            overflow--
        }
    }

    private fun normalizeKind(kind: String): String {
        val k = kind.trim().lowercase(Locale.getDefault())
        return if (k in knownKinds) k else "note"
    }

    /**
     * 朴素分词：按空白与常见中英文标点切分；对长中文片段再补 2-gram，
     * 让「喜欢喝美式咖啡」这类无空格输入也能命中（token 总数上限 32，防爆炸）。
     */
    private fun tokenize(query: String): List<String> {
        val raw = query.lowercase(Locale.getDefault())
            .split(Regex("[\\s,，.。、;；:：/\\\\|()（）\\[\\]【】\"'’“”!！?？]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        raw.forEach { token ->
            out.add(token)
            if (token.any { it in '\u4e00'..'\u9fff' } && token.length > 2) {
                for (i in 0..token.length - 2) out.add(token.substring(i, i + 2))
            }
        }
        return out.take(MAX_TOKENS)
    }

    /** 关键词命中 × 时间衰减 + salience 权重。 */
    private fun scoreOf(item: MemoryItem, keywords: List<String>, now: Long): Double {
        val content = item.content.lowercase(Locale.getDefault())
        val tags = item.tags.map { it.lowercase(Locale.getDefault()) }
        var hits = 0.0
        keywords.forEach { k ->
            if (content.contains(k)) hits += 2.0
            if (tags.any { it.contains(k) }) hits += 3.0
        }
        if (hits <= 0.0) return 0.0
        val ageDays = (now - item.updatedAt).coerceAtLeast(0).toDouble() / DAY_MS
        val decay = 1.0 / (1.0 + ageDays * DECAY_PER_DAY)
        return hits * (0.5 + 0.5 * decay) + item.salience * SALIENCE_WEIGHT
    }

    /** 摘要必须 XML 转义，否则用户内容里的 `<`/`&` 会破坏注入结构。 */
    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        s.forEach { c ->
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_ITEMS = 500
        const val MIN_SALIENCE = 1
        const val MAX_SALIENCE = 10
        const val MAX_TOKENS = 32
        const val CONTENT_SNIPPET = 200
        const val DAY_MS = 86_400_000.0
        const val DECAY_PER_DAY = 0.2
        const val SALIENCE_WEIGHT = 0.2
    }
}
