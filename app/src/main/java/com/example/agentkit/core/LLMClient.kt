package com.example.agentkit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.agentkit.core.llm.ChatStreamEvent
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
class LlmHttpException(
    val code: Int,
    message: String,
    val serverBody: String = "",
    /**
     * 服务端 `Retry-After` 头解析出的毫秒数（0 = 没给/解析不了）。
     * hotfix10：429 限流时按它退避，别用固定短序列硬刚。
     */
    val retryAfterMs: Long = 0L
) : java.io.IOException(message) {
    val retryable: Boolean get() = code == 429 || code >= 500
}

/**
 * 「测试连接」最小探测的返回：状态码 + 响应体预览（已脱敏）。
 * 仅用于诊断连通性，不进入对话。
 */
data class PingResult(val code: Int, val bodyPreview: String)

/**
 * 模型信息：Provider 动态列出的可用模型（listModels）。
 * 本地 llama 走内置清单，OpenAI 兼容端点可走 /models，Claude/Gemini 可内置常用档位。
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextLength: Int? = null
)

/**
 * LLM 抽象：Agent 核心只依赖它。换供应商（OpenAI / Claude / Gemini / 本地 llama.cpp）
 * 只需新增一个实现，AgentLoop 一行都不用改。
 *
 * 协议差异（鉴权头、消息结构、工具回传形态）全部内聚在各实现类内，
 * 对外统一成同一套 ChatMessage / AssistantMessage / ToolCall。
 */
interface LLMClient {
    /** 供应商标识（"openai" / "claude" / "gemini" / "local-llama"），ProviderManager 的键。 */
    val id: String

    suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage

    /** 流式输出（文本增量）。默认不支持；实现方按需覆盖。 */
    fun streamText(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<String> =
        throw UnsupportedOperationException("当前供应商不支持流式输出")

    /**
     * T01 流式对话通道：Agent 主循环（RealAgentLoop）的唯一 LLM 入口。
     *
     * **默认实现 = 调 [chat] 得整条 AssistantMessage**：content 非空时先 emit 一条
     * [ChatStreamEvent.TextDelta]，再 emit [ChatStreamEvent.Completed]——四个现有
     * 客户端零改动即合规（行为与旧裸 chat() 调用等价：一次请求、整条 commit）。
     * 真 SSE 实现方按需覆盖（OpenAIClient 已覆盖；Claude/Gemini/LocalLlama 后续可接）。
     *
     * 消费约定（RealAgentLoop）：TextDelta 只进 onTextDelta 回调（UI 打字机），
     * 进历史的一定是 Completed 整条（含 ToolCall）。
     */
    fun streamChat(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<ChatStreamEvent> = flow {
        val message = chat(messages, tools)
        if (message.content.isNotEmpty()) {
            emit(ChatStreamEvent.TextDelta(message.content))
        }
        emit(ChatStreamEvent.Completed(message))
    }

    /** 动态列出可用模型；默认空（实现方按需覆盖，如本地 llama 内置清单）。 */
    suspend fun listModels(): List<ModelInfo> = emptyList()

    /** 「测试连接」最小探测（各协议自行实现最小请求）。 */
    suspend fun testPing(): PingResult
}

/**
 * OpenAI 兼容实现（也适用于 vLLM、Ollama、OneAPI 等兼容端点）。
 * 传输用 OkHttp（挂起到 Dispatchers.IO），消息/工具按 OpenAI 协议手动拼 JsonObject。
 *
 * 流式（T01）：streamText（文本增量，公开 API 保留不许删）与 streamChat
 * （文本增量 + tool_calls 分片累积 → Completed 整条 commit，Agent 主循环消费）
 * 共享同一 SSE 读取逻辑（readSseDataObjects）。
 */

/**
 * 解析 `Retry-After` 响应头为毫秒（hotfix10）。
 *
 * 支持两种合法形态：延迟秒数（`Retry-After: 120`，最常见）与 HTTP 日期
 * （`Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`，少见）。解析不出一律返回 0
 * （调用方按自己的 429 序列退避，不会被坏头值卡死）。
 * 纯函数、零 Android 依赖——QA harness 可直接断言。
 */
fun parseRetryAfterMs(raw: String?): Long {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return 0L
    v.toLongOrNull()?.let { if (it >= 0) return it * 1000L }
    val fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
    return runCatching {
        val t = java.time.ZonedDateTime.parse(v, fmt).toInstant().toEpochMilli()
        (t - System.currentTimeMillis()).coerceAtLeast(0L)
    }.getOrDefault(0L)
}

class OpenAIClient(
    private val config: LLMConfig,
    private val http: OkHttpClient = OkHttpClient()
) : LLMClient {

    override val id: String = "openai"

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

    /**
     * 流式文本增量（公开 API）。Round1 Blocker 修复后用 [channelFlow]（先例：
     * LocalLlamaClient.streamText）——`flow {}` 构建器有上下文保持不变量，从
     * [readSseDataObjects] 的 withContext(IO) 嵌套协程里 emit 会抛 "Flow invariant
     * is violated"（JVM harness 实测记录）；channelFlow 的通道语义允许生产侧在 IO
     * 协程 send、收集方仍在自己的上下文（含 Main）收增量。
     */
    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = channelFlow {
        readSseDataObjects(messages, tools) { obj ->
            val content = sseDeltaContent(obj)
            if (content != null) send(content)
        }
    }

    /**
     * T01：流式对话（真 SSE 覆盖，替代默认的 chat()+单条 Completed）。
     *
     * 发射文本增量（打字机），并按 index 累积 tool_calls 分片——OpenAI 流式协议里
     * tool_calls 跨 chunk 拆分下发（首片带 id + function.name，后续片只带
     * function.arguments 片段，多个调用按 index 归组，见
     * [OpenAiToolCallAccumulator]）。流结束后 emit 一条 [ChatStreamEvent.Completed]
     * 整条 commit（含 ToolCall）——RealAgentLoop「整条 Assistant commit」三段式
     * 在流式下同样成立。
     *
     * 调度（Round1 Blocker 修复）：[channelFlow] + [readSseDataObjects] 内
     * withContext(IO)——网络段绝不跑在收集方调度器（含 Android Main）上，增量经
     * 通道序列化回传，收集方仍在自己的上下文收 delta。
     */
    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<ChatStreamEvent> = channelFlow {
        val accumulator = OpenAiToolCallAccumulator()
        val text = StringBuilder()
        readSseDataObjects(messages, tools) { obj ->
            val delta = sseDeltaObject(obj)
            if (delta != null) {
                delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                    text.append(it)
                    send(ChatStreamEvent.TextDelta(it))
                }
                delta["tool_calls"]?.jsonArray?.let { accumulator.absorb(it) }
            }
        }
        send(ChatStreamEvent.Completed(AssistantMessage(text.toString(), accumulator.build())))
    }

    /**
     * 共享 SSE 读取（[streamText] / [streamChat] 复用，T01 重构抽出）：
     * 建流式请求 -> 执行 -> 逐行解析 `data:` 载荷 -> 每个 JSON 对象回调 [onData]；
     * `[DONE]` 哨兵结束读取。请求构造 / 端点指纹日志 / 失败抛错只此一份，两条流式
     * 通道行为一致（回归面：streamText 公开 API 与语义不变）。
     *
     * **调度契约（Round1 Blocker 修复）**：网络段（`execute()` + 逐行 `readUtf8Line()`）
     * 强制 `withContext(Dispatchers.IO)`——阻塞网络 IO 绝不跑在调用方调度器上，任何
     * 上下文（含 Android Main）都不会触发 `NetworkOnMainThreadException`。[onData]
     * 内的 `send` 在 IO 协程执行是安全的：两个流式通道用 [channelFlow]（先例：
     * LocalLlamaClient.streamText），通道线程安全、序列化回传给收集方，收集方仍在
     * 自己的上下文（如 Main）收 delta。
     *
     * 实现注记（为什么必须 channelFlow 而不是 flow{}）：`flow {}` 构建器有上下文
     * 保持不变量（SafeCollector 校验），从本方法的 withContext(IO) 嵌套协程里
     * `emit` 会抛 "Flow invariant is violated"——JVM harness Round1 区块有实测
     * 失败记录；channelFlow 的通道语义才允许「生产侧 IO 协程、收集方原地上下文」。
     *
     * 非网络段（构造请求体 / 指纹日志）不切换调度。
     */
    private suspend fun readSseDataObjects(
        messages: List<ChatMessage>,
        tools: List<AgentTool>,
        onData: suspend (JsonObject) -> Unit
    ) {
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
        // Round1 Blocker 修复：网络段强制 IO 调度（streamText/streamChat 共用本方法，
        // 一处修两通道）。onData 里的 send 经 channelFlow 通道序列化回收集方，上下文安全。
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // hotfix10：流式通道原来抛裸 IOException("LLM 流式请求失败 429")，
                    // 丢了 HTTP 结构与 Retry-After——上层认不出 429、退避只能用通用
                    // 3s/10s 短序列硬刚限流（真机 19:05 连续 3 次 429 后运行直接失败）。
                    // 改抛结构化 LlmHttpException：isRetryable/retryReason/429 专用
                    // 退避序列全部能认出来。
                    val errBody = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                    AgentLogger.httpError(resp.code, AgentLogger.sanitize(errBody))
                    throw LlmHttpException(
                        code = resp.code,
                        message = "LLM 流式请求失败 ${resp.code}",
                        serverBody = AgentLogger.sanitize(errBody).take(200),
                        retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"))
                    )
                }
                val source = resp.body!!.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") return@withContext
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    onData(obj)
                }
            }
        }
    }

    /** 从一个 SSE data 对象里取 choices[0].delta（纯函数；无 choices/delta 时 null）。 */
    private fun sseDeltaObject(obj: JsonObject): JsonObject? =
        obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject

    /** 从 SSE data 对象里取 choices[0].delta.content 文本（纯函数；缺失时 null）。 */
    private fun sseDeltaContent(obj: JsonObject): String? =
        sseDeltaObject(obj)?.get("content")?.jsonPrimitive?.contentOrNull

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
    override suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
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
        /** 宽松 JSON 解析（容忍未知键与非常严格式）；internal：同文件的 SSE 累积器复用。 */
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * OpenAI SSE tool_calls 增量累积器（T01，纯数据 + 纯函数，harness 可直接断言）。
 *
 * OpenAI 流式协议里 tool_calls 按 index 归组、跨 chunk 分片下发：
 * 首片带 id + function.name，后续片只带 function.arguments 的片段（本身也可能
 * 再分多片）；id/name 只在首片出现，后续片不重复。本类把分片拼回整条
 * [ToolCall]：absorb 吸收分片（只追加、不重排），build 产出整表——
 * 顺序 = 首次出现顺序（LinkedHashMap 保序，即模型意图顺序）。
 *
 * 容错（与 [OpenAIClient.parseResponse] 的非流式路径一致）：
 *  - arguments 末尾整体解析为 JsonObject，解析失败（流被截断/分片损坏）退化为空对象；
 *  - 无名调用（从未收到 function.name）无法执行，build 时丢弃；
 *  - id 全程缺失时按 index 生成确定性回退 id（`call_<index>`），不产出随机量。
 */
internal class OpenAiToolCallAccumulator {

    /** 单个 index 上的增量状态（id/name 取首片值，arguments 追加拼接）。 */
    private class Delta(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    /** index -> 增量；LinkedHashMap 保序（build() 结果顺序 = 首次出现顺序）。 */
    private val byIndex = LinkedHashMap<Int, Delta>()

    /**
     * 吸收一个 chunk 的 tool_calls 数组（纯函数式更新：只追加，不重排）。
     *
     * @param toolCalls SSE `choices[0].delta.tool_calls` 数组；元素缺 index 时
     *   按数组位置兜底（兼容不守规矩的兼容端点）。
     */
    fun absorb(toolCalls: JsonArray) {
        toolCalls.forEachIndexed { position, element ->
            val obj = element.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: position
            val delta = byIndex.getOrPut(index) { Delta() }
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) delta.id = it }
            val fn = obj["function"]?.jsonObject
            fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) delta.name = it }
            fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { delta.arguments.append(it) }
        }
    }

    /**
     * 产出整条工具调用列表（纯函数）：过滤无名调用，arguments 整体解析
     * （失败退化空对象），id 缺失按 index 回退 `call_<index>`。
     */
    fun build(): List<ToolCall> = byIndex.entries.mapNotNull { (index, delta) ->
        if (delta.name.isBlank()) return@mapNotNull null
        val args = runCatching {
            OpenAIClient.json.parseToJsonElement(delta.arguments.toString()).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        ToolCall(
            id = delta.id.ifBlank { "call_$index" },
            name = delta.name,
            arguments = args
        )
    }
}

/**
 * 供应商注册表（RikkaHub ProviderManager 思路，净室重写）。
 * 通过 [create] 按名称构造 LLMClient；新增供应商只需 registerProvider。
 *
 * "local-llama" 留给第 3 步本地 llama.cpp 集成时注册（见 docs/research/02）。
 */
class ProviderManager {
    private val providers = mutableMapOf<String, (LLMConfig) -> LLMClient>()

    init {
        registerProvider("openai") { OpenAIClient(it) }
        registerProvider("claude") { com.example.agentkit.core.llm.ClaudeClient(it) }
        registerProvider("gemini") { com.example.agentkit.core.llm.GeminiClient(it) }
    }

    fun registerProvider(name: String, factory: (LLMConfig) -> LLMClient) {
        providers[name] = factory
    }

    fun create(name: String, config: LLMConfig): LLMClient =
        providers[name]?.invoke(config)
            ?: throw IllegalArgumentException("未知供应商：$name（已注册：${providers.keys}）")

    val names: Set<String> get() = providers.keys
}
