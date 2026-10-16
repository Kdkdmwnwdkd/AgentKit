package com.example.agentkit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 工具统一接口：Agent 核心只依赖这个抽象，完全不接触任何 Android API。
 * 所有能力（文件、网络、剪贴板…）都通过 execute() 暴露给 LLM。
 *
 * 设计借鉴（净室重写，非抄源码）：
 *  - [systemPrompt]：工具可自带一段提示词，注入到系统提示里（RikkaHub 思路）。
 *  - [needsApproval]：危险工具可声明调用前需用户审批（RikkaHub / Coomi approval 思路）。
 *  - [readOnly]：是否只读（无副作用）。并发判据用——本批任一工具非只读即整批串行，
 *    全批只读才并行（T03，coomi A2 has_mutation 思路）。默认 false（未知工具按写处理，
 *    走向串行这一安全侧）。
 *  - execute 返回统一契约 [ToolResult]（T02：ok/code/message/hint/data，失败 hint 必填）。
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonSchema

    /** 工具自带提示词片段，拼进系统提示。默认空。 */
    val systemPrompt: String get() = ""

    /** 是否危险操作，调用前需要用户审批。默认 false。 */
    val needsApproval: Boolean get() = false

    /**
     * 是否只读（T03）：true = 无副作用可并行；false = 写操作须串行。
     * 不标注 = false（保守走串行）。
     */
    val readOnly: Boolean get() = false

    /** 执行工具，返回统一契约结果（T02）。 */
    suspend fun execute(args: JsonObject): ToolResult
}

/** 工具返回的片段：文本或图片（图片以 url 形式传给 UI）。 */
sealed interface ToolPart {
    val text: String get() = ""
    val isError: Boolean get() = false
    data class Text(val content: String, override val isError: Boolean = false) : ToolPart {
        override val text get() = content
    }
    data class Image(val url: String, val alt: String = "") : ToolPart {
        override val text get() = "[图片: $alt]"
    }
}

/** 工具审批回调：返回 true 表示允许执行。 */
typealias ToolApproval = suspend (tool: AgentTool, args: JsonObject) -> Boolean

/** Agent 调用某工具时的一次请求。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

/**
 * 对话消息模型——同时作为 LLM 的上下文。
 * Agent 核心只认 ChatMessage，不关心这些消息最终怎么渲染。
 */
sealed interface ChatMessage
data class SystemMessage(val content: String) : ChatMessage
data class UserMessage(
    val content: String,
    /** 视觉模型图片（data URL 列表），作为多模态内容附在消息里发给 LLM。 */
    val images: List<String> = emptyList()
) : ChatMessage
data class AssistantMessage(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * 功能五（P1-5）：本次 LLM 往返的 token 用量（服务端 usage 字段解析而来）。
     * null = 供应商没给 usage（本地 llama / 兼容端点缺字段）——UI 不显示而非猜一个数。
     * 多步回合（调工具的轮次）由调用方按轮汇总（见 ChatViewModel.send）。
     */
    val usage: TokenUsage? = null
) : ChatMessage

/**
 * 功能五（P1-5）：token 用量（一次 LLM 往返或一段时间窗口的合计）。
 *
 * prompt = 输入侧（含系统提示/工具定义/历史，通常远大于输出侧）；
 * completion = 输出侧。三家协议字段名各不相同，解析统一成本结构后再上传
 * （OpenAI usage.prompt_tokens/completion_tokens；Claude usage.input_tokens/
 * output_tokens；Gemini usageMetadata.promptTokenCount/candidatesTokenCount）。
 */
data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    companion object {
        /** 空用量（无数据时的占位，避免 UI 判空两套逻辑）。 */
        val EMPTY: TokenUsage = TokenUsage()

        /** 汇总多条（一个回合多次 LLM 往返 → 整轮成本）。null 元素跳过。 */
        fun sum(usages: List<TokenUsage?>): TokenUsage =
            TokenUsage(
                promptTokens = usages.sumOf { it?.promptTokens ?: 0L },
                completionTokens = usages.sumOf { it?.completionTokens ?: 0L }
            )
    }
}

/** 工具执行结果进入历史：带调用 id / 工具名 / 入参，方便 UI 折叠展示。 */
data class ToolMessage(
    val id: String,
    val name: String = "",
    val arguments: JsonObject = JsonObject(emptyMap()),
    val content: String,
    val isError: Boolean = false,
    /** 工具返回的图片（data URL 列表），供 UI 直接渲染（如 ui_screenshot 截图）。 */
    val images: List<String> = emptyList()
) : ChatMessage

/**
 * 工具参数的 JSON Schema 描述，用于暴露给 LLM 做 function calling。
 * 第一阶段只描述“顶层扁平字段”，嵌套 object 仅作 type=object 占位即可。
 * 结构与 MCP 工具的 inputSchema 兼容，便于后续直接接入 MCP 服务器。
 */
@Serializable
data class JsonSchema(
    val type: String = "object",
    val properties: Map<String, Property> = emptyMap(),
    val required: List<String> = emptyList()
) {
    @Serializable
    data class Property(
        val type: String,
        val description: String = "",
        val enum: List<String>? = null
    )

    companion object {
    fun prop(
        name: String,
        type: String,
        description: String,
        enum: List<String>? = null
    ): Pair<String, Property> = name to Property(type, description, enum)

        fun objectSchema(
            vararg props: Pair<String, Property>,
            required: List<String> = props.map { it.first }
        ): JsonSchema = JsonSchema(properties = mapOf(*props), required = required)
    }
}
