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
 * Gemini generateContent API 适配（Provider id = "gemini"）。
 *
 * 与 OpenAIClient 的关键差异（全部内聚在本类内）：
 *  - 端点是 /v1beta/models/{model}:generateContent，流式加 ?alt=sse；
 *  - 鉴权头 x-goog-api-key；
 *  - role 名不同：assistant → "model"，工具结果 → user 消息里的 functionResponse part；
 *  - system 是顶层 systemInstruction；
 *  - functionCall **没有调用 id**（Claude/OpenAI 都有）——ToolCall.id 由本地合成，
 *    回传时靠 name 匹配，AgentLoop 不依赖 id 的唯一跨轮语义；
 *  - 图片只认 inlineData（base64），http URL 由 [ImageUris] 预下载转换。
 *
 * 流式（T01）：[LLMClient.streamChat] 暂用默认实现（chat() 整条 + 单条 Completed）；
 * 后续可接 SSE（?alt=sse 的 candidates[0].content.parts 增量 + functionCall 分片累积）。
 */
class GeminiClient(
    private val config: LLMConfig,
    private val http: OkHttpClient = OkHttpClient()
) : LLMClient {

    override val id: String = "gemini"

    private val maxOutputTokens = 8192

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        val resolved = ImageUris.resolveImages(http, messages)

        val requestJson = buildJsonObject {
            val systemText = resolved.filterIsInstance<SystemMessage>()
                .joinToString("\n\n") { it.content }
            if (systemText.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemText) }
                    }
                }
            }
            put("contents", contentsJson(resolved))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { add(toApiTool(it)) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.7)
                put("maxOutputTokens", maxOutputTokens)
            }
        }

        val request = buildRequest(requestJson, stream = false)
        AgentLogger.llmRequest(effectiveModel(), messages.size, tools.size)
        AgentLogger.apiTrace(endpointUrl(false), effectiveModel(), effectiveApiKey())
        val startedAt = System.currentTimeMillis()

        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Gemini 网络请求异常", e)
            throw e
        }

        resp.use { r ->
            if (!r.isSuccessful) {
                val errBody = runCatching { r.body?.string() }.getOrDefault("") ?: ""
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(r.code, safeBody)
                throw LlmHttpException(r.code, "Gemini 请求失败 ${r.code}：${safeBody.take(300)}", safeBody)
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
        val resolved = ImageUris.resolveImages(http, messages)
        val systemText = resolved.filterIsInstance<SystemMessage>()
            .joinToString("\n\n") { it.content }
        val requestJson = buildJsonObject {
            if (systemText.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemText) } }
                }
            }
            put("contents", contentsJson(resolved))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") { tools.forEach { add(toApiTool(it)) } }
                    }
                }
            }
        }
        val request = buildRequest(requestJson, stream = true)
        AgentLogger.apiTrace(endpointUrl(true), effectiveModel(), effectiveApiKey())
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(resp.code, safeBody)
                throw IOException("Gemini 流式请求失败 ${resp.code}: ${safeBody.take(200)}")
            }
            val source = resp.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                runCatching {
                    val obj = json.parseToJsonElement(data).jsonObject
                    val parts = obj["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    parts?.forEach { el ->
                        el.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { emit(it) }
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
     * 实际端点。路径形如 /v1beta/models/{model}:generateContent；
     * base 已带 /v1beta 时不重复拼（防御手滑）。
     */
    private fun endpointUrl(stream: Boolean): String {
        val base = effectiveBaseUrl().removeSuffix("/v1beta")
        val suffix = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
        return "$base/v1beta/models/${effectiveModel()}$suffix"
    }

    private fun buildRequest(bodyJson: JsonObject, stream: Boolean): Request {
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(endpointUrl(stream))
            .addHeader("x-goog-api-key", effectiveApiKey())
            .addHeader("Content-Type", "application/json")
            .apply { config.customHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .post(body)
            .build()
    }

    /**
     * 最小连通性探测：一条 "ping"、maxOutputTokens=1。
     * 用于「测试连接」按钮，不污染对话历史，也几乎不产生 token 消耗。
     */
    override suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
        val requestJson = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", "ping") } }
                }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", 1) }
        }
        val request = buildRequest(requestJson, stream = false)
        AgentLogger.apiTrace(endpointUrl(false), effectiveModel(), effectiveApiKey())
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Gemini 测试连接网络异常", e)
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
        val parts = root["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: return AssistantMessage("", emptyList())

        val content = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        parts.forEach { el ->
            val obj = el.jsonObject
            obj["text"]?.jsonPrimitive?.contentOrNull?.let { content.append(it) }
            obj["functionCall"]?.jsonObject?.let { fc ->
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                // Gemini 的 functionCall 没有 id：本地合成，回传靠 name 匹配
                toolCalls += ToolCall("gemini_${UUID.randomUUID().toString().take(8)}", name, args)
            }
        }
        return AssistantMessage(content.toString(), toolCalls)
    }

    /**
     * ChatMessage 列表 → Gemini contents 数组。
     * role 映射：user→user、assistant→model、tool→user（functionResponse part）。
     * 连续多条 [ToolMessage] 合并成一条 user 消息、并列多个 functionResponse part。
     *
     * 注意：builder lambda 内不声明局部函数（拿不到隐式接收器），合并逻辑写在 forEach 里。
     */
    private fun contentsJson(messages: List<ChatMessage>) = buildJsonArray {
        val pendingResponses = mutableListOf<JsonObject>()
        messages.forEach { msg ->
            if (msg is ToolMessage) {
                pendingResponses += functionResponsePart(msg)
            } else {
                if (pendingResponses.isNotEmpty()) {
                    add(userFunctionResponseMessage(pendingResponses.toList()))
                    pendingResponses.clear()
                }
                add(toApiContent(msg))
            }
        }
        if (pendingResponses.isNotEmpty()) {
            add(userFunctionResponseMessage(pendingResponses.toList()))
        }
    }

    /** 单条 functionResponse part。 */
    private fun functionResponsePart(msg: ToolMessage): JsonObject = buildJsonObject {
        putJsonObject("functionResponse") {
            put("name", msg.name)
            putJsonObject("response") { put("result", msg.content) }
        }
    }

    /** 把多个 functionResponse part 包成一条 user 消息。 */
    private fun userFunctionResponseMessage(parts: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") { parts.forEach { add(it) } }
    }

    private fun toApiContent(msg: ChatMessage): JsonObject = when (msg) {
        is SystemMessage -> buildJsonObject {
            // 正常不会走到这（system 已提为 systemInstruction）；防御性兜底
            put("role", "user")
            putJsonArray("parts") { addJsonObject { put("text", msg.content) } }
        }

        is UserMessage -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                if (msg.content.isNotBlank()) addJsonObject { put("text", msg.content) }
                msg.images.forEach { url ->
                    addJsonObject {
                        putJsonObject("inlineData") {
                            val (mime, data) = ImageUris.splitDataUri(url) ?: ("image/png" to url)
                            put("mimeType", mime)
                            put("data", data)
                        }
                    }
                }
            }
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "model")
            putJsonArray("parts") {
                if (msg.content.isNotBlank()) addJsonObject { put("text", msg.content) }
                msg.toolCalls.forEach { call ->
                    addJsonObject {
                        putJsonObject("functionCall") {
                            put("name", call.name)
                            put("args", call.arguments)
                        }
                    }
                }
            }
        }

        is ToolMessage -> buildJsonObject {
            // 单条场景由 contentsJson 合并；此处防御性兜底
            put("role", "user")
            putJsonArray("parts") {
                addJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", msg.name)
                        putJsonObject("response") { put("result", msg.content) }
                    }
                }
            }
        }
    }

    private fun toApiTool(tool: AgentTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        putJsonObject("parameters") {
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
    }
}
