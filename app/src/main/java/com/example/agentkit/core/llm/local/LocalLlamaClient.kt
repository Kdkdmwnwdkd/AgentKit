package com.example.agentkit.core.llm.local

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.ModelInfo
import com.example.agentkit.core.PingResult
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import com.example.agentkit.core.llm.ChatStreamEvent
import com.example.agentkit.core.llm.arrOrNull
import com.example.agentkit.core.llm.objOrNull
import com.example.agentkit.core.llm.primOrNull
import java.util.Locale
import java.util.UUID

/**
 * 本地 llama.cpp 推理客户端（LLMClient 实现）。
 *
 * 与云端 Provider 的差异全部收敛在本类：
 * 1. **不走网络**：模型文件在本地，prompt 由 llama.cpp 的 Jinja 对话模板渲染
 *    （Qwen/Llama/Gemma 各自的格式由模型自带，不在这里手拼——这是体验不碎片化的关键）。
 * 2. **工具调用是提示词协议**，不是协议原生：本地小模型对各家模板的 tool_use 支持
 *    参差不齐，统一用「输出 JSON 即调用」的约定（见 [toolProtocolPrompt]），
 *    解析失败一律按普通文本处理，绝不会把模型的闲聊误判成工具调用。
 * 3. **图片忽略**：本地小模型多为纯文本，多模态消息只取文字部分（截图类工具结果
 *    在 [ToolMessage] 里附了图片，这里也只取文本，UI 层已单独渲染）。
 *
 * 实例生命周期在 [LocalLlamaEngine]（进程级复用，避免每轮重新加载模型）。
 *
 * 流式（T01/T02）：[streamText] 与 [streamChat] 都走 native 的 token 级回调，
 * 边生成边上屏；工具调用因为走「整条 JSON」协议，首字符是 `{` 时改为一憋到底。
 */
class LocalLlamaClient(
    private val engine: LocalLlamaEngine,
    private val setting: LocalLlamaSetting,
    private val modelStore: GgufModelStore,
    private val config: LLMConfig
) : LLMClient {

    override val id: String = "local-llama"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // —— 对话主路径 ————————————————————————————————————————————————

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        val prompt = buildPrompt(messages, tools)
        AgentLogger.llmRequest(currentModelLabel(), messages.size, tools.size)
        val startedAt = System.currentTimeMillis()
        val text = engine.generate(prompt, setting.maxTokens, null)
        val elapsed = System.currentTimeMillis() - startedAt
        if (text.isEmpty()) {
            val diag = nativeDiagnostics()
            AgentLogger.e("LocalLlama", "本地生成返回空文本（prompt ${prompt.length} 字符）。native 诊断：\n$diag")
            throw LocalInferenceException(localFailureReason(diag))
        }
        AgentLogger.llmResponse(currentModelLabel(), text.isNotEmpty(), elapsed, "生成长度=${text.length}")
        parseAssistant(text, tools)
    }

    /**
     * 确保模型已加载（必要时加载，秒级）。
     *
     * 三个失败分支都必须抛异常而不是返回空文本——空文本会让 AgentLoop 以为
     * 「模型说完了」，用户侧表现为毫无反应的静默失败，是最难排查的一类 bug。
     *
     * 用 [LocalInferenceException] 而非 `IOException`：上层把 IOException 一律当成
     * 网络问题，而这里每一条都是「本地配置/文件」问题，提示不能往网络上引。
     */
    private fun ensureModelLoaded() {
        val name = setting.activeModel
        if (name.isBlank()) {
            throw LocalInferenceException("尚未选择本地模型：请到「模型配置 → 本地模型」下载并选用")
        }
        val file = modelStore.modelFile(name)
        if (!file.isFile) {
            throw LocalInferenceException("本地模型文件缺失：$name（可能已被删除，请重新下载）")
        }
        val handle = engine.acquire(file.absolutePath, setting.nCtx, setting.nThreads, 0)
        if (handle == 0L) {
            throw LocalInferenceException("本地模型加载失败：${file.name}（详见「运行日志」里的 llama-jni 记录）")
        }
    }

    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = channelFlow {
        ensureModelLoaded()
        val prompt = buildPrompt(messages, tools)
        AgentLogger.llmRequest(currentModelLabel(), messages.size, tools.size)
        val startedAt = System.currentTimeMillis()
        val meter = GenMeter()
        val acc = StringBuilder()
        // 原生回调在生成线程逐 token 触发，经 channel 中转给 Flow 收集方；
        // 协程取消（离开对话页等）时 invokeOnClose 通知 native 停止，线程自然收尾。
        val thread = Thread({
            try {
                engine.generate(prompt, setting.maxTokens) { piece ->
                    meter.onPiece(piece)
                    acc.append(piece)
                    trySend(piece)   // 通道满/已关时静默丢弃：宁可丢 token 不可卡死 native
                }
            } catch (t: Throwable) {
                AgentLogger.e("LocalLlama", "流式生成异常", t)
            } finally {
                close()
            }
        }, "local-llama-generate")
        thread.start()
        invokeOnClose { engine.stop() }
        thread.join()
        if (acc.isEmpty()) {
            val diag = nativeDiagnostics()
            AgentLogger.e("LocalLlama", "本地生成返回空文本（prompt ${prompt.length} 字符）。native 诊断：\n$diag")
            throw LocalInferenceException(localFailureReason(diag))
        }
        AgentLogger.llmResponse(
            currentModelLabel(),
            true,
            System.currentTimeMillis() - startedAt,
            "流式生成结束 ${meter.summary()}"
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 流式对话（T02）：把 native 的 token 级回调做成逐字上屏。
     *
     * 与云端 Provider 的最大差异在工具调用：本地用的是「整条回复就是一个 JSON」的
     * 提示词协议，所以首字符判定为 `{` 时**先憋着不吐**，等生成结束、解析出
     * [ToolCall] 再整条 commit。否则用户会先看一屏 `{"tool":"shell_run","arg…`
     * 乱码，再被工具卡替代 —— 比不流式更难看。
     *
     * 取消语义同 [streamText]：协程取消 → invokeOnClose → native 停止。
     */
    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<ChatStreamEvent> = channelFlow {
        ensureModelLoaded()
        val prompt = buildPrompt(messages, tools)
        AgentLogger.llmRequest(currentModelLabel(), messages.size, tools.size)
        val startedAt = System.currentTimeMillis()

        val acc = StringBuilder()
        var decided = false
        var looksLikeToolCall = false
        val meter = GenMeter()

        val thread = Thread({
            try {
                engine.generate(prompt, setting.maxTokens) { piece ->
                    meter.onPiece(piece)
                    acc.append(piece)
                    if (!decided && acc.isNotBlank()) {
                        decided = true
                        looksLikeToolCall = acc.trimStart().startsWith("{")
                    }
                    if (decided && !looksLikeToolCall) {
                        trySend(ChatStreamEvent.TextDelta(piece))
                    }
                }
            } catch (t: Throwable) {
                AgentLogger.e("LocalLlama", "流式生成异常", t)
            }
        }, "local-llama-stream")
        thread.start()
        invokeOnClose { engine.stop() }
        thread.join()

        val text = acc.toString()
        // 空文本 = 用户侧「发了消息毫无反应」。这不可能是「模型说完了」：
        // 结束符不该在一句话都没吐的时候命中。把 native 现场搬进日志并明确失败，
        // 否则这个回合会静默落一条空助手消息，看起来就像卡住/重复上一句。
        if (text.isEmpty()) {
            val diag = nativeDiagnostics()
            AgentLogger.e(
                "LocalLlama",
                "本地生成返回空文本（prompt ${prompt.length} 字符）。native 诊断：\n$diag"
            )
            throw LocalInferenceException(localFailureReason(diag))
        }
        AgentLogger.i("LocalLlama", "native 摘要｜${digest(nativeDiagnostics())}")

        val message = parseAssistant(text, tools)
        // 判定像工具调用、最终却没解析出 ToolCall（JSON 不合法 / 工具名不在白名单）：
        // 整段按普通文本补发一次，不让这轮回答凭空消失。
        if (looksLikeToolCall && message.toolCalls.isEmpty() && message.content.isNotEmpty()) {
            send(ChatStreamEvent.TextDelta(message.content))
        }
        AgentLogger.llmResponse(
            currentModelLabel(),
            text.isNotEmpty(),
            System.currentTimeMillis() - startedAt,
            "流式生成结束（${if (message.toolCalls.isEmpty()) "文本" else "工具 ${message.toolCalls.size} 个"}）${meter.summary()}"
        )
        send(ChatStreamEvent.Completed(message))
    }.flowOn(Dispatchers.IO)

    /** 读 native 诊断（库未加载时返回空串，不能让诊断本身把流程搞崩）。 */
    private fun nativeDiagnostics(): String = runCatching {
        if (LlamaJni.available) LlamaJni.nativeDiagnostics() else ""
    }.getOrDefault("")

    /** 只挑能判断「卡在哪一步」的行，避免把 80 行全塞进运行日志。 */
    private fun digest(diag: String): String = diag.lineSequence()
        .filter { line ->
            listOf("nativeInit 成功", "prompt 长度", "prefill 完成", "生成完成", "命中结束符", "生成为空")
                .any { keyword -> line.contains(keyword) }
        }
        .joinToString(" ｜ ")

    /**
     * 把 native 诊断翻译成一句用户能看懂、且能直接转述给开发者的话。
     *
     * 分支顺序按「从日志里能确定的最具体原因」排，取不到就退回让用户导出日志。
     */
    private fun localFailureReason(diag: String): String = when {
        diag.contains("tokenize 失败") ->
            "本地模型读不懂这段输入（分词失败），已记录到运行日志"
        diag.contains("llama_decode 失败") ->
            "本地推理的计算核拒绝执行（decode 失败），多半是该模型与当前 native 库不兼容，已记录到运行日志"
        diag.contains("prompt 异常地短") ->
            "对话模板没有渲染出内容（prompt 过短），模型无话可说，已记录到运行日志"
        diag.contains("生成为空") ->
            "模型一个字都没生成（首个 token 就是结束符），已记录到运行日志"
        else ->
            "本地模型没有返回内容，详情见「设置 → 数据 → 运行日志」的 LocalLlama 条目"
    }

    // —— 模型元信息 ————————————————————————————————————————————————
    override suspend fun listModels(): List<ModelInfo> =
        modelStore.catalog().map { ModelInfo(it.fileName, it.displayName, it.approxBytes.toInt()) }

    override suspend fun testPing(): PingResult {
        if (!LlamaJni.ensureLoaded()) {
            return PingResult(0, LlamaJni.unavailableReason ?: "native 库不可用（仅 arm64-v8a）")
        }
        val name = setting.activeModel
        if (name.isBlank()) {
            return PingResult(0, "尚未选择本地模型：请先在「本地模型」页签下载并选用")
        }
        val file = modelStore.modelFile(name)
        if (!file.isFile) {
            return PingResult(0, "模型文件缺失：${name}（可能已被删除）")
        }
        if (!modelStore.isValidGguf(file)) {
            return PingResult(0, "文件不是有效的 GGUF：${name}（下载可能不完整）")
        }
        if (engine.isLoaded) {
            return PingResult(200, "模型已加载：${name}，可直接对话")
        }
        return PingResult(200, "文件就绪：${name}（${file.length() / 1024 / 1024} MB），首次对话时加载")
    }

    // —— prompt 构建 ————————————————————————————————————————————————

    /**
     * 把 ChatMessage 列表渲染成模型 prompt：
     * 1. 有工具时按提示词协议注入工具说明（不支持系统提示的模型附在首条 user 消息上）；
     * 2. 交给 llama.cpp 的模型自带 Jinja 模板做角色/特殊 token 渲染。
     */
    private fun buildPrompt(messages: List<ChatMessage>, tools: List<AgentTool>): String {
        val effective = messages.toMutableList()
        if (tools.isNotEmpty()) {
            val protocol = toolProtocolPrompt(tools)
            val first = effective.firstOrNull()
            val catalogSupportsSystem = modelStore.catalog()
                .firstOrNull { it.fileName == setting.activeModel }
                ?.supportsSystemPrompt ?: true
            effective[0] = when {
                first is SystemMessage && catalogSupportsSystem ->
                    SystemMessage("${first.content}\n\n$protocol")
                first is UserMessage ->
                    UserMessage("$protocol\n\n${first.content}", first.images)
                else -> {
                    // 没有 system/user 打底的对话极少见；直接前置一条 user
                    effective.add(0, UserMessage(protocol))
                    effective[0]
                }
            }
        }

        val roles = ArrayList<String>(effective.size)
        val contents = ArrayList<String>(effective.size)
        effective.forEach { msg ->
            when (msg) {
                is SystemMessage -> { roles.add("system"); contents.add(msg.content) }
                is UserMessage -> { roles.add("user"); contents.add(msg.content) }
                is AssistantMessage -> {
                    roles.add("assistant")
                    // 带工具调用的历史回合：content 里存的就是当时输出的 JSON 原文，直接回放
                    contents.add(msg.content)
                }
                is ToolMessage -> {
                    // 模板角色名用 "tool"：Qwen/Llama 3.1+ 原生支持；其他模板会原样渲染角色名，
                    // 顶多多一行字，不会破坏对话结构。
                    roles.add("tool")
                    contents.add("工具 ${msg.name} 的执行结果：\n${msg.content}")
                }
            }
        }

        var prompt = engine.chatTemplate(roles.toTypedArray(), contents.toTypedArray())
        if (prompt.isBlank()) {
            // 引擎未加载/模板失败：chatTemplate 依赖已加载实例，理论上 chat() 里已 acquire 过。
            // 兜底拼装保证不把空 prompt 发给 native。
            AgentLogger.w("LocalLlama", "对话模板为空，使用 ChatML 兜底拼装")
            prompt = buildString {
                effective.forEachIndexed { i, msg ->
                    val role = roles.getOrElse(i) { "user" }
                    append("$role\n${contents.getOrElse(i) { "" }}\n")
                }
            }
        }
        return prompt
    }

    /**
     * 工具调用协议提示词。
     *
     * 设计取舍：不依赖任何模型特有的 tool_use 模板语法（Qwen/Llama/Gemma 各不相同），
     * 用「输出一个 JSON 对象」这一最小公约数。约束写死三条：
     * 1. 必须是合法 JSON、含 tool 与 arguments 两个键；
     * 2. 不要包 ```json 代码块（小模型极爱包，明确禁止能显著提高命中率）；
     * 3. 不需要工具时正常说话——防止模型把所有对话都硬拗成 JSON。
     */
    private fun toolProtocolPrompt(tools: List<AgentTool>): String = buildString {
        appendLine("【工具调用模式】")
        appendLine("你可以使用以下工具。需要调用时，你的整个回复必须且只能是这一个 JSON 对象（不要使用 ```json 代码块，不要输出任何其他文字）：")
        appendLine("{\"tool\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}")
        appendLine("不需要工具时，直接用自然语言回答。可用工具：")
        tools.forEach { tool ->
            val params = tool.parameters.properties.entries.joinToString(", ") { (k, p) ->
                "$k(${p.type}${if (p.description.isNotBlank()) ": ${p.description}" else ""})"
            }
            appendLine("- ${tool.name}: ${tool.description}。参数：$params")
        }
    }

    // —— 输出解析 ————————————————————————————————————————————————

    /**
     * 解析模型输出为 [AssistantMessage]：
     * 先尝试按工具协议提取 JSON（工具名必须存在于本次可用列表，防误判）；
     * 失败则整体视为普通文本。
     */
    private fun parseAssistant(text: String, tools: List<AgentTool>): AssistantMessage {
        if (text.isBlank()) return AssistantMessage("")
        if (tools.isEmpty()) return AssistantMessage(text.trim())
        val call = extractToolCall(text, tools.map { it.name }.toSet())
            ?: return AssistantMessage(text.trim())
        return AssistantMessage(text.trim(), listOf(call))
    }

    /** 从输出中提取工具调用 JSON；工具名不在白名单内一律返回 null（宁可漏判，不可误判）。 */
    private fun extractToolCall(text: String, validTools: Set<String>): ToolCall? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            json.parseToJsonElement(text.substring(start, end + 1)).objOrNull()
        }.getOrNull() ?: return null
        val name = obj["tool"]?.primOrNull()?.contentOrNull ?: return null
        if (name !in validTools) return null
        val args: JsonObject = runCatching { obj["arguments"]?.objOrNull() }.getOrNull() ?: JsonObject(emptyMap())
        return ToolCall("local_${UUID.randomUUID().toString().take(8)}", name, args)
    }

    private fun currentModelLabel(): String =
        setting.activeModel.ifBlank { config.model.ifBlank { "local-llama" } }

    /**
     * 一次本地生成的计时账：首字延迟（TTFT）+ 字符吞吐。
     *
     * 存在的理由：真机自测时用户手边未必有电脑，`adb logcat -s llama-jni` 里的
     * prefill 耗时、tok/s 全在 native 侧，进不了 App 内「运行日志」页。而「回得快
     * 不快」在体感上只由两个数决定 —— 第一字多久出来、之后每秒出多少字。这里在
     * Kotlin 侧独立计一遍，写进 LLM 响应日志，手机上翻日志就能对比改前改后。
     */
    private class GenMeter {
        private val startAt = System.currentTimeMillis()
        private var firstAt = 0L
        private var chars = 0

        /** 每收到一个 token 片段调用一次（native 线程，只做整数累加）。 */
        fun onPiece(piece: String) {
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
            chars += piece.length
        }

        fun summary(): String {
            val total = System.currentTimeMillis() - startAt
            val ttft = if (firstAt == 0L) -1L else firstAt - startAt
            val cps = if (total > 0) chars * 1000.0 / total else 0.0
            val head = if (ttft < 0) "首字 —" else "首字 ${ttft}ms"
            return String.format(
                Locale.US, "%s / %d 字 / 共 %dms（%.1f 字每秒）", head, chars, total, cps
            )
        }
    }
}
