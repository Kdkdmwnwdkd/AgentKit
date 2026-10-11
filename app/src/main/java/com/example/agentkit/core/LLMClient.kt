package com.example.agentkit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 模型供应商配置：无状态，每次调用都携带。
 * 支持自定义请求头/体与推理强度，以接入任意 OpenAI 兼容端点
 * （含 Ollama / vLLM / OneAPI / 本地 llama.cpp）。
 */
data class LLMConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val customBody: Map<String, JsonElement> = emptyMap(),
    val reasoningLevel: String? = null
)

/**
 * LLM 请求失败（携带 HTTP 状态码）。
 *
 * [retryable] 表示是否值得重试：429/5xx 属瞬时问题可重试，
 * 401/403/400 等鉴权/参数错误重试也无用，必须快速失败（避免用户白等 3 次重试）。
 */
class LlmHttpException(val code: Int, message: String) : java.io.IOException(message) {
    val retryable: Boolean get() = code == 429 || code >= 500
}

/**
 * LLM 抽象：Agent 核心只依赖它。换供应商（OpenAI / 本地 llama.cpp / Claude）
 * 只需新增一个实现，AgentLoop 一行都不用改。
 */
interface LLMClient {
    suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage

    /** 流式输出（文本增量）。默认不支持；实现方按需覆盖。 */
    fun streamText(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<String> =
        throw UnsupportedOperationException("当前供应商不支持流式输出")
}

/**
 * OpenAI 兼容实现（也适用于 vLLM、Ollama、OneAPI 等兼容端点）。
 * 传输用 OkHttp（挂起到 Dispatchers.IO），消息/工具按 OpenAI 协议手动拼 JsonObject。
 */
class OpenAIClient(
    private val config: LLMConfig,
    private val http: OkHttpClient = OkHttpClient()
) : LLMClient {

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        val requestJson = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") { messages.forEach { add(toApiMessage(it)) } }
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
            }
            config.reasoningLevel?.let { put("reasoning_effort", it) }
            config.customBody.forEach { (k, v) -> put(k, v) }
            put("temperature", 0.7)
        }

        val request = buildRequest(requestJson)

        // L8 日志：请求前记录（只记模型/消息数/工具数，绝不记 apiKey）
        AgentLogger.llmRequest(config.model, messages.size, tools.size)
        val startedAt = System.currentTimeMillis()

        // 超时/DNS/连接失败都走 IOException，记下堆栈后原样抛出
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "网络请求异常", e)
            throw e
        }

        resp.use { r ->
            if (!r.isSuccessful) {
                // ⚠️ resp.body?.string() 只能读一次，必须先存变量再使用
                val errBody = runCatching { r.body?.string() }.getOrDefault("") ?: ""
                // 401/403 的响应体常回显 sk-xxx 或 token，落日志与抛出的异常文案都必须先脱敏
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(r.code, safeBody)
                // 带上状态码抛出：上层据此判断是否值得重试、以及给用户什么文案
                throw LlmHttpException(r.code, "LLM 请求失败 ${r.code}：${safeBody.take(300)}")
            }
            val text = r.body!!.string()
            AgentLogger.llmResponse(
                config.model,
                true,
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
        val requestJson = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            putJsonArray("messages") { messages.forEach { add(toApiMessage(it)) } }
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
            }
            config.customBody.forEach { (k, v) -> put(k, v) }
        }
        val request = buildRequest(requestJson)
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("LLM 流式请求失败 ${resp.code}")
            }
            val source = resp.body!!.source()
            while (!source.exhausted()) {
                source.readUtf8Line()?.let { line ->
                    if (!line.startsWith("data:")) return@let
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") return@flow
                    runCatching {
                        val obj = json.parseToJsonElement(data).jsonObject
                        val delta = obj["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")?.jsonObject ?: return@let
                        delta["content"]?.jsonPrimitive?.contentOrNull?.let { emit(it) }
                    }
                }
            }
        }
    }

    private fun buildRequest(bodyJson: JsonObject): Request {
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
        config.customHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.post(body).build()
    }

    private fun parseResponse(text: String): AssistantMessage {
        val root = json.parseToJsonElement(text).jsonObject
        val msg = root["choices"]!!
            .jsonArray.first().jsonObject["message"]!!.jsonObject

        val content = msg["content"]?.jsonPrimitive?.contentOrNull ?: ""

        val toolCalls = msg["tool_calls"]?.jsonArray?.mapNotNull { tc ->
            val obj = tc.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val rawArgs = fn["arguments"]?.jsonPrimitive?.content ?: "{}"
            val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            ToolCall(id, name, args)
        } ?: emptyList()

        return AssistantMessage(content, toolCalls)
    }

    private fun toApiMessage(msg: ChatMessage): JsonObject = buildJsonObject {
        when (msg) {
            is SystemMessage -> { put("role", "system"); put("content", msg.content) }
            is UserMessage -> {
                put("role", "user")
                if (msg.images.isEmpty()) {
                    put("content", msg.content)
                } else {
                    // 多模态：content 改为 part 数组，文本 + image_url 交替
                    putJsonArray("content") {
                        if (msg.content.isNotBlank()) {
                            addJsonObject { put("type", "text"); put("text", msg.content) }
                        }
                        msg.images.forEach { url ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", url) }
                            }
                        }
                    }
                }
            }
            is AssistantMessage -> {
                put("role", "assistant")
                if (msg.content.isNotBlank()) put("content", msg.content)
                if (msg.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        msg.toolCalls.forEach { call ->
                            addJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments.toString())
                                }
                            }
                        }
                    }
                }
            }
            is ToolMessage -> {
                put("role", "tool")
                put("tool_call_id", msg.id)
                put("content", msg.content)
            }
        }
    }

    private fun toApiTool(tool: AgentTool): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            putJsonObject("parameters") {
                val s = tool.parameters
                put("type", "object")
                putJsonObject("properties") {
                    s.properties.forEach { (k, p) ->
                        putJsonObject(k) {
                            put("type", p.type)
                            if (p.description.isNotBlank()) put("description", p.description)
                            p.enum?.let { e -> putJsonArray("enum") { e.forEach { add(it) } } }
                        }
                    }
                }
                putJsonArray("required") { s.required.forEach { add(it) } }
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * 供应商注册表（RikkaHub ProviderManager 思路，净室重写）。
 * 通过 [create] 按名称构造 LLMClient；新增供应商只需 registerProvider。
 */
class ProviderManager {
    private val providers = mutableMapOf<String, (LLMConfig) -> LLMClient>()

    init {
        registerProvider("openai") { OpenAIClient(it) }
    }

    fun registerProvider(name: String, factory: (LLMConfig) -> LLMClient) {
        providers[name] = factory
    }

    fun create(name: String, config: LLMConfig): LLMClient =
        providers[name]?.invoke(config)
            ?: throw IllegalArgumentException("未知供应商：$name（已注册：${providers.keys}）")

    val names: Set<String> get() = providers.keys
}
