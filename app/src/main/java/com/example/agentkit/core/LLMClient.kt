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
class LlmHttpException(val code: Int, message: String, val serverBody: String = "") : java.io.IOException(message) {
    val retryable: Boolean get() = code == 429 || code >= 500
}

/**
 * 「测试连接」最小探测的返回：状态码 + 响应体预览（已脱敏）。
 * 仅用于诊断连通性，不进入对话。
 */
data class PingResult(val code: Int, val bodyPreview: String)

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
            put("model", effectiveModel())
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
        AgentLogger.llmRequest(effectiveModel(), messages.size, tools.size)
        // 记录实际端点/模型/Key 指纹，让 401 能在日志里自证（含不可见字符告警）
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
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
                throw LlmHttpException(r.code, "LLM 请求失败 ${r.code}：${safeBody.take(300)}", safeBody)
            }
            val text = r.body!!.string()
            AgentLogger.llmResponse(
                effectiveModel(),
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
            put("model", effectiveModel())
            put("stream", true)
            putJsonArray("messages") { messages.forEach { add(toApiMessage(it)) } }
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
            }
            config.customBody.forEach { (k, v) -> put(k, v) }
        }
        val request = buildRequest(requestJson)
        // 流式请求同样记录端点/指纹，保持诊断能力一致
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
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

    // —— 防御性清洗：统一在此处把配置三元组洗一遍，供 buildRequest / apiTrace / testPing 复用 ——

    /** 规范化后的 Base URL（去空白、去尾斜杠、剥掉误填的 /chat/completions 后缀）。 */
    private fun effectiveBaseUrl(): String = ConfigSanitizer.normalizeBaseUrl(config.baseUrl)

    /** 清洗后的 API Key（剔除一切非可打印 ASCII，含 NBSP/零宽/BOM）。 */
    private fun effectiveApiKey(): String = ConfigSanitizer.cleanApiKey(config.apiKey)

    /** 清洗后的模型名。 */
    private fun effectiveModel(): String = ConfigSanitizer.cleanModel(config.model)

    /** 实际拼出的请求端点（Base URL + /chat/completions）。 */
    private fun endpointUrl(): String = "${effectiveBaseUrl().trimEnd('/')}/chat/completions"

    private fun buildRequest(bodyJson: JsonObject): Request {
        // 防御性双保险：即便调用方忘了清洗，也不会带着 \u00A0 等不可见字符发请求
        val baseUrl = ConfigSanitizer.normalizeBaseUrl(config.baseUrl)
        val apiKey = ConfigSanitizer.cleanApiKey(config.apiKey)
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
        config.customHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.post(body).build()
    }

    /**
     * 最小连通性探测：发一条 [{role:user,content:"ping"}]、max_tokens=1、不带工具。
     * 用于「测试连接」按钮，不污染对话历史，也几乎不产生 token 消耗。
     *
     * @return [PingResult] 状态码与响应体预览（均已脱敏处理）。
     */
    suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "ping")
                }
            }
            put("max_tokens", 1)
            put("temperature", 0.0)
        }
        val request = buildRequest(requestJson)
        // 记录实际端点/模型/Key 指纹，让测试也能在日志里自证
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "测试连接网络异常", e)
            throw e
        }
        resp.use { r ->
            // 响应体可能回显 sk-xxx，必须先脱敏再截断
            val body = runCatching { r.body?.string() }.getOrDefault("") ?: ""
            val safe = AgentLogger.sanitize(body)
            PingResult(code = r.code, bodyPreview = safe.take(200))
        }
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
