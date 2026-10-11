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
 *  - execute 返回多片段结果 [ToolResult]，支持文本与图片，方便 UI 直接渲染（RikkaHub 思路）。
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonSchema

    /** 工具自带提示词片段，拼进系统提示。默认空。 */
    val systemPrompt: String get() = ""

    /** 是否危险操作，调用前需要用户审批。默认 false。 */
    val needsApproval: Boolean get() = false

    /** 执行工具，返回多片段结果。 */
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

/** 单个工具执行后的返回结果（不含调用 id）。向后兼容旧用法 [ToolResult]("文本", isError)。 */
data class ToolResult private constructor(
    val parts: List<ToolPart>,
    val isError: Boolean
) {
    /** 便捷文本视图：拼接所有文本片段，供 UI 折叠卡片展示。 */
    val content: String = parts.filterIsInstance<ToolPart.Text>().joinToString("\n") { it.content }

    constructor(parts: List<ToolPart>) : this(parts, parts.any { it.isError })
    constructor(text: String, isError: Boolean = false) : this(listOf(ToolPart.Text(text, isError)))
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
    val toolCalls: List<ToolCall> = emptyList()
) : ChatMessage

/** 工具执行结果进入历史：带调用 id / 工具名 / 入参，方便 UI 折叠展示。 */
data class ToolMessage(
    val id: String,
    val name: String = "",
    val arguments: JsonObject = JsonObject(emptyMap()),
    val content: String,
    val isError: Boolean = false
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
