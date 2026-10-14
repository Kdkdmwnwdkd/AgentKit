package com.example.agentkit.core.llm

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.ConfigSanitizer
import com.example.agentkit.core.LlmHttpException
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.PingResult
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Claude Messages API 适配（Provider id = "claude"）。
 *
 * 与 OpenAIClient 的关键差异（全部内聚在本类内，对外统一成同一套
 * ChatMessage / AssistantMessage / ToolCall）：
 *  - system 是顶层字段，不是 messages[0]；
 *  - max_tokens 必填；
 *  - 工具调用是 content block：assistant 消息里 tool_use block，
 *    回传时是**一条 user 消息**里并列多个 tool_result block
 *    （并行工具调用必须合并成一条，拆多条会 400）；
 *  - 鉴权头是 x-api-key + anthropic-version，不是 Bearer；
 *  - 图片只认 base64（http URL 由 [ImageUris] 预下载转换）。
 *
 * 日志与脱敏复用 OpenAIClient 的同一套 AgentLogger / ConfigSanitizer 机制。
 *
 * 流式（T01）：[LLMClient.streamChat] 暂用默认实现（chat() 整条 + 单条 Completed）；
 * 后续可接 SSE（content_block_delta 增量 + input_json_delta 工具参数分片累积，
 * 参照 OpenAIClient 的 readSseDataObjects / OpenAiToolCallAccumulator 模式）。
 */
class ClaudeClient(
    private val config: LLMConfig,
    private val http: OkHttpClient = OkHttpClient()
) : LLMClient {

    override val id: String = "claude"

    /** Claude 的 max_tokens 必填；给足一档，避免长回复被截断。 */
    private val maxTokens = 8192

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        // 图片统一成 data URI（URL 需要先下载，Claude 不认外链）
        val resolved = ImageUris.resolveImages(http, messages)

        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            put("max_tokens", maxTokens)
            val systemText = resolved.filterIsInstance<SystemMessage>()
                .joinToString("\n\n") { it.content }
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", messagesJson(resolved))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
                put("tool_choice", buildJsonObject { put("type", "auto") })
            }
            config.reasoningLevel?.let { put("temperature", 0.7) }
        }

        val request = buildRequest(requestJson)
        AgentLogger.llmRequest(effectiveModel(), messages.size, tools.size)
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        val startedAt = System.currentTimeMillis()

        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Claude 网络请求异常", e)
            throw e
        }

        resp.use { r ->
            if (!r.isSuccessful) {
                // body.string() 只能读一次：先存变量；401 响应体可能回显 key，必须先脱敏
                val errBody = runCatching { r.body?.string() }.getOrDefault("") ?: ""
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(r.code, safeBody)
                throw LlmHttpException(r.code, "Claude 请求失败 ${r.code}：${safeBody.take(300)}", safeBody)
            }
            val text = r.body!!.string()
            AgentLogger.llmResponse(
                effectiveModel(), true,
                System.currentTimeMillis() - startedAt,
                "响应长度=${text.length}"
            )
            parseResponse(text)
        }
    }

    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = flow {
        // 流式分支的图片解析需要挂起，放到 flow 的 collect 协程里做
        val resolved = ImageUris.resolveImages(http, messages)
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            put("max_tokens", maxTokens)
            put("stream", true)
            val systemText = resolved.filterIsInstance<SystemMessage>()
                .joinToString("\n\n") { it.content }
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", messagesJson(resolved))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
            }
        }
        val request = buildRequest(requestJson)
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(resp.code, safeBody)
                throw IOException("Claude 流式请求失败 ${resp.code}: ${safeBody.take(200)}")
            }
            val source = resp.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "content_block_delta" -> {
                            val delta = obj["delta"]?.jsonObject
                            if (delta?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") {
                                delta["text"]?.jsonPrimitive?.contentOrNull?.let { emit(it) }
                            }
                        }
                        // 流中途报错（超限等）：抛出供上层按普通错误处理
                        "error" -> {
                            val msg = obj["error"]?.jsonObject?.get("message")
                                ?.jsonPrimitive?.contentOrNull ?: "Claude 流式错误"
                            throw IOException(msg)
                        }
                    }
                }
            }
        }
    }

    // —— 防御性清洗：与 OpenAIClient 同一套 ConfigSanitizer ——

    private fun effectiveBaseUrl(): String =
        ConfigSanitizer.normalizeBaseUrl(config.baseUrl).trimEnd('/')

    private fun effectiveApiKey(): String = ConfigSanitizer.cleanApiKey(config.apiKey)

    private fun effectiveModel(): String = ConfigSanitizer.cleanModel(config.model)

    /**
     * 实际端点。Claude 路径是 /v1/messages：
     * 用户填 https://api.anthropic.com → /v1/messages；
     * 已带 /v1 的不重复拼（防御手滑）。
     */
    private fun endpointUrl(): String {
        val base = effectiveBaseUrl()
        return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    }

    private fun buildRequest(bodyJson: JsonObject): Request {
        val apiKey = effectiveApiKey()
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(endpointUrl())
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")
            .apply { config.customHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .post(body)
            .build()
    }

    /**
     * 最小连通性探测：一条 "ping"、max_tokens=1、不带工具。
     * 用于「测试连接」按钮，不污染对话历史，也几乎不产生 token 消耗。
     */
    override suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            put("max_tokens", 1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "ping")
                }
            }
        }
        val request = buildRequest(requestJson)
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Claude 测试连接网络异常", e)
            throw e
        }
        resp.use { r ->
            val body = runCatching { r.body?.string() }.getOrDefault("") ?: ""
            val safe = AgentLogger.sanitize(body)
            PingResult(code = r.code, bodyPreview = safe.take(200))
        }
    }

    private fun parseResponse(text: String): AssistantMessage {
        val root = json.parseToJsonElement(text).jsonObject
        val blocks = root["content"]?.jsonArray ?: return AssistantMessage("", emptyList())

        val content = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        blocks.forEach { el ->
            val obj = el.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { content.append(it) }
                "tool_use" -> {
                    val callId = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    toolCalls += ToolCall(callId, name, input)
                }
            }
        }
        return AssistantMessage(content.toString(), toolCalls)
    }

    /**
     * ChatMessage 列表 → Claude messages 数组。
     *
     * 连续多条 [ToolMessage]（AgentLoop 并行执行工具会产生）合并成**一条** user 消息，
     * 里面并列多个 tool_result block——Claude 协议要求，拆多条会 400。
     *
     * 注意：builder lambda 内不声明局部函数——局部函数拿不到外层 lambda 的隐式接收器
     * （`add` 会解析失败），所以合并逻辑直接写在 forEach 里。
     */
    private fun messagesJson(messages: List<ChatMessage>) = buildJsonArray {
        val pendingToolResults = mutableListOf<JsonObject>()
        messages.forEach { msg ->
            if (msg is ToolMessage) {
                pendingToolResults += toolResultBlock(msg)
            } else {
                if (pendingToolResults.isNotEmpty()) {
                    add(userToolResultsMessage(pendingToolResults.toList()))
                    pendingToolResults.clear()
                }
                add(toApiMessage(msg))
            }
        }
        if (pendingToolResults.isNotEmpty()) {
            add(userToolResultsMessage(pendingToolResults.toList()))
        }
    }

    /** 单条 tool_result block。 */
    private fun toolResultBlock(msg: ToolMessage): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", msg.id)
        put("content", msg.content)
        if (msg.isError) put("is_error", true)
    }

    /** 把多个 tool_result block 包成一条 user 消息（并行工具回传的合并形态）。 */
    private fun userToolResultsMessage(blocks: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") { blocks.forEach { add(it) } }
    }

    private fun toApiMessage(msg: ChatMessage): JsonObject = when (msg) {
        is SystemMessage -> buildJsonObject {
            // 正常不会走到这（system 已提为顶层字段）；防御性兜底
            put("role", "user")
            put("content", msg.content)
        }

        is UserMessage -> buildJsonObject {
            put("role", "user")
            if (msg.images.isEmpty()) {
                put("content", msg.content)
            } else {
                putJsonArray("content") {
                    if (msg.content.isNotBlank()) {
                        addJsonObject { put("type", "text"); put("text", msg.content) }
                    }
                    msg.images.forEach { url ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                val (mime, data) = ImageUris.splitDataUri(url) ?: ("image/png" to url)
                                put("media_type", mime)
                                put("data", data)
                            }
                        }
                    }
                }
            }
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "assistant")
            putJsonArray("content") {
                if (msg.content.isNotBlank()) {
                    addJsonObject { put("type", "text"); put("text", msg.content) }
                }
                msg.toolCalls.forEach { call ->
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", call.arguments)
                    }
                }
            }
        }

        is ToolMessage -> buildJsonObject {
            // 单条场景由 messagesJson 合并；此处防御性兜底
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.id)
                    put("content", msg.content)
                    if (msg.isError) put("is_error", true)
                }
            }
        }
    }

    private fun toApiTool(tool: AgentTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                val s = tool.parameters
                s.properties.forEach { (k, p) ->
                    putJsonObject(k) {
                        put("type", p.type)
                        if (p.description.isNotBlank()) put("description", p.description)
                        p.enum?.let { e -> putJsonArray("enum") { e.forEach { add(it) } } }
                    }
                }
            }
            putJsonArray("required") { tool.parameters.required.forEach { add(it) } }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Anthropic API 版本头（官方要求，缺了会 400）。 */
        private const val API_VERSION = "2023-06-01"
    }
}
