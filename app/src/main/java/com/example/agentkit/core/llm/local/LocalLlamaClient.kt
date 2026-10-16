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
import com.example.agentkit.core.llm.arrOrNull
import com.example.agentkit.core.llm.objOrNull
import com.example.agentkit.core.llm.primOrNull
import java.io.IOException
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
 * 流式（T01）：[LLMClient.streamChat] 暂用默认实现（chat() 整条 + 单条 Completed）；
 * 后续可接 SSE（llama.cpp 的 token 级增量回调转 TextDelta，工具调用仍走提示词协议解析）。
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
        AgentLogger.llmResponse(currentModelLabel(), text.isNotEmpty(), elapsed, "生成长度=${text.length}")
        parseAssistant(text, tools)
    }

    /**
     * 确保模型已加载（必要时加载，秒级）。
     *
     * 三个失败分支都必须抛 [IOException] 而不是返回空文本——空文本会让 AgentLoop
     * 以为「模型说完了」，用户侧表现为毫无反应的静默失败，是最难排查的一类 bug。
     */
    private fun ensureModelLoaded() {
        val name = setting.activeModel
        if (name.isBlank()) {
            throw IOException("尚未选择本地模型：请到「模型配置 → 本地模型」下载并选用")
        }
        val file = modelStore.modelFile(name)
        if (!file.isFile) {
            throw IOException("本地模型文件缺失：$name（可能已被删除，请重新下载）")
        }
        val handle = engine.acquire(file.absolutePath, setting.nCtx, setting.nThreads, 0)
        if (handle == 0L) {
            throw IOException("本地模型加载失败：${file.name}（详见「日志」页 llama-jni 标签）")
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
        // 原生回调在生成线程逐 token 触发，经 channel 中转给 Flow 收集方；
        // 协程取消（离开对话页等）时 invokeOnClose 通知 native 停止，线程自然收尾。
        val thread = Thread({
            try {
                engine.generate(prompt, setting.maxTokens) { piece ->
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
        AgentLogger.llmResponse(
            currentModelLabel(),
            true,
            System.currentTimeMillis() - startedAt,
            "流式生成结束"
        )
    }.flowOn(Dispatchers.IO)

    // —— 模型元信息 ————————————————————————————————————————————————

    override suspend fun listModels(): List<ModelInfo> =
        modelStore.catalog().map { ModelInfo(it.fileName, it.displayName, it.approxBytes.toInt()) }

    override suspend fun testPing(): PingResult {
        if (!LlamaJni.ensureLoaded()) {
            return PingResult(0, "native 库不可用：当前设备 ABI 未编入（仅 arm64-v8a）")
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
}
