package com.example.agentkit.memory

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * L9 记忆库的四个 AgentTool：写 / 检索 / 列表 / 遗忘。
 * 风格对齐 tools/BuiltInTools.kt：AgentTool + JsonSchema.objectSchema + runCatching 兜底，
 * 且**可选参数必须显式传 required**，否则 objectSchema 默认把所有参数都置为必填。
 */

/* ------------------------------------------------------------------ */
/* memory_write                                                        */
/* ------------------------------------------------------------------ */
class MemoryWriteTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_write"
    override val description =
        "把用户偏好、关键事实或重要约定写入长期记忆库，跨会话长期保留。kind 可选 fact/preference/event/note，salience 越大越不容易被遗忘（1-10）。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("content", "string", "要记住的内容（一句话说清事实或偏好）"),
        JsonSchema.prop("kind", "string", "记忆类型", enum = listOf("fact", "preference", "event", "note")),
        JsonSchema.prop("tags", "string", "标签，中英文逗号或空格分隔，便于后续检索（可选）"),
        JsonSchema.prop("salience", "string", "重要度 1-10，默认 5（可选）"),
        required = listOf("content")
    )

    override val systemPrompt =
        "当你从对话中得知用户的长期偏好、稳定事实或重要约定时，用 memory_write 记下来，" +
            "这样即便开启新会话也能保持一致；不要记录一次性临时信息。"

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@withContext ToolResult("缺少 content", isError = true)
            if (content.isBlank()) return@withContext ToolResult("content 不能为空", isError = true)
            val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: "note"
            val tags = args["tags"]?.jsonPrimitive?.contentOrNull
                ?.split(",", "，", "、", " ")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val salience = args["salience"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.toIntOrNull()?.coerceIn(1, 10) ?: 5

            val item = store.add(content, kind, tags, salience)
            val tagText = if (item.tags.isEmpty()) "无" else item.tags.joinToString(",")
            ToolResult("已记住 [${item.kind}] id=${item.id}｜重要度=${item.salience}｜标签=$tagText\n内容：${item.content}")
        }.getOrElse { ToolResult("写入记忆失败：${it.message}", isError = true) }
    }
}

/* ------------------------------------------------------------------ */
/* memory_search                                                       */
/* ------------------------------------------------------------------ */
class MemorySearchTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_search"
    override val description =
        "在长期记忆库中按关键词检索过往记忆（偏好/事实/事件），返回最相关的若干条。需要回忆用户信息时优先用它。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("query", "string", "检索关键词，可中英文混写"),
        JsonSchema.prop("topK", "string", "返回条数上限，默认 10（可选）"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@withContext ToolResult("缺少 query", isError = true)
            val topK = args["topK"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.toIntOrNull()?.coerceIn(1, 50) ?: 10

            val hits = store.search(query, topK)
            if (hits.isEmpty()) return@withContext ToolResult("未找到相关记忆（query=$query）")
            val body = hits.joinToString("\n") { item ->
                val tagText = if (item.tags.isEmpty()) "" else " #${item.tags.joinToString(" #")}"
                "[${item.kind}] ${item.id} ${item.content}$tagText"
            }
            ToolResult("命中 ${hits.size} 条（共 ${store.size()} 条记忆）：\n$body")
        }.getOrElse { ToolResult("检索记忆失败：${it.message}", isError = true) }
    }
}

/* ------------------------------------------------------------------ */
/* memory_list                                                         */
/* ------------------------------------------------------------------ */
class MemoryListTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_list"
    override val description = "列出记忆库中最新的若干条记忆（按更新时间倒序），用于概览已记住了什么。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("limit", "string", "返回条数，默认 20（可选）"),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.toIntOrNull()?.coerceIn(1, 100) ?: 20
            val items = store.list(limit)
            if (items.isEmpty()) return@withContext ToolResult("记忆库为空，还没有任何记忆。")
            val body = items.joinToString("\n") { item -> "[${item.kind}] ${item.id} ${item.content}" }
            ToolResult("共 ${store.size()} 条记忆，以下为最近 ${items.size} 条：\n$body")
        }.getOrElse { ToolResult("读取记忆失败：${it.message}", isError = true) }
    }
}

/* ------------------------------------------------------------------ */
/* memory_forget                                                       */
/* ------------------------------------------------------------------ */
class MemoryForgetTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_forget"
    override val description = "按 id 永久删除一条记忆。用户明确要求忘记某件事时使用；误删无法恢复，请谨慎。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("id", "string", "要删除的记忆 id，可从 memory_search / memory_list 获得"),
        required = listOf("id")
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@withContext ToolResult("缺少 id", isError = true)
            val ok = store.forget(id)
            if (ok) ToolResult("已忘记该记忆：$id（剩余 ${store.size()} 条）")
            else ToolResult("未找到 id 对应的记忆：$id", isError = true)
        }.getOrElse { ToolResult("删除记忆失败：${it.message}", isError = true) }
    }
}
