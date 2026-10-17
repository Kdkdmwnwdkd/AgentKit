package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.ConversationStore
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * conversation_search（W-18）：在自己过去的对话里检索（对齐 RikkaHub `conversation_search`）。
 *
 * 为什么有用：用户常说「上次你帮我弄的那个设置叫什么来着」——模型看不到其他会话，
 * 只能让用户自己翻。这个工具让 Agent 主动去历史会话里找。
 *
 * 性能边界：只扫最近 [MAX_SCAN] 个会话、每个会话只看前 [MAX_CHARS_PER_CONV] 字，
 * 避免几十个长会话把内存/时间吃光；要更早的内容请用户提供关键词缩小范围。
 */
class ConversationSearchTool(
    private val store: ConversationStore
) : AgentTool {

    override val name = "conversation_search"

    override val description =
        "在本机历史对话里按关键词检索，返回命中的会话标题、时间和上下文片段。" +
            "用户提到「上次/之前我们说过」时用它；不要用它查当前对话（当前内容你已经有）。"

    override val systemPrompt =
        "检索历史对话用 conversation_search，关键词要具体（人名、应用名、设置项）；" +
            "拿到片段后用自己的话总结，不要把整段原文复述给用户。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("query", "string", "检索关键词（多个词用空格分隔，任意命中即算）"),
        JsonSchema.prop("limit", "integer", "最多返回几条会话，默认 3，上限 5")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) {
            return ToolResult.fail("missing_param", "缺少 query", "传入关键词，例如「深色模式」或「下载目录」。")
        }
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val keywords = raw.split(' ', '，', ',', '、').map { it.trim() }.filter { it.isNotBlank() }
        if (keywords.isEmpty()) {
            return ToolResult.fail("missing_param", "query 里没有有效关键词。", "换一个具体的词再试。")
        }

        val summaries = store.list().take(MAX_SCAN)
        val hits = ArrayList<Triple<String, String, String>>() // 标题 / 时间 / 片段
        for (s in summaries) {
            if (hits.size >= limit) break
            val titleHit = keywords.any { s.title.contains(it, ignoreCase = true) }
            val record = runCatching { store.load(s.id) }.getOrNull()
            val body = record?.messages?.joinToString("\n") { it.content }?.take(MAX_CHARS_PER_CONV).orEmpty()
            if (!titleHit && keywords.none { body.contains(it, ignoreCase = true) }) continue

            val snippet = keywords
                .mapNotNull { k -> snippetAround(body, k) }
                .firstOrNull()
                ?: "（命中标题：${s.title}）"
            hits.add(Triple(s.title, formatDate(s.updatedAt), snippet))
        }

        if (hits.isEmpty()) {
            return ToolResult.ok(
                code = "conversation_no_hit",
                message = "最近 ${summaries.size} 个对话里没有找到「$raw」。",
                data = "可以换同义词或更短的关键词再试；也可以问用户具体是哪次对话。"
            )
        }
        val text = hits.joinToString("\n\n") { (title, time, snippet) ->
            "【$title】$time\n$snippet"
        }
        return ToolResult.ok(
            code = "conversation_hits",
            message = "找到 ${hits.size} 个相关会话（扫描最近 ${summaries.size} 个）：",
            data = text.take(4_000)
        )
    }

    private companion object {
        const val MAX_SCAN = 30
        const val MAX_CHARS_PER_CONV = 6_000
    }
}

/**
 * 命中片段：以第一个关键词为中心，左右各取 [radius] 字（纯函数，harness 可断言）。
 * 未命中返回 null。
 */
internal fun snippetAround(text: String, keyword: String, radius: Int = 60): String? {
    if (keyword.isBlank()) return null
    val idx = text.indexOf(keyword, ignoreCase = true)
    if (idx < 0) return null
    val start = (idx - radius).coerceAtLeast(0)
    val end = (idx + keyword.length + radius).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + text.substring(start, end).replace(Regex("\\s+"), " ").trim() + suffix
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
