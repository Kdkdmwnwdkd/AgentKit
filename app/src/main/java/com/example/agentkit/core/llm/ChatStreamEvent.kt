package com.example.agentkit.core.llm

import com.example.agentkit.core.AssistantMessage

/**
 * 流式对话事件（T01）：[com.example.agentkit.core.LLMClient.streamChat] 的产出。
 *
 * 两种事件覆盖「文本增量」与「整条 commit」两个消费需求：
 *  - [TextDelta]：文本增量，UI 打字机效果直接渲染；
 *  - [Completed]：整条 AssistantMessage（含 ToolCall）——RealAgentLoop 的
 *    「整条 Assistant commit（含 ToolCall）」三段式在流式下同样成立：
 *    增量只进 onTextDelta 回调，进历史的一定是 Completed 整条。
 *
 * 放 core.llm 包：与 LLMClient 同包，四个供应商实现与 harness 共同引用。
 */
sealed interface ChatStreamEvent {

    /** 一段文本增量（content 非空才发射；空 content 的 chunk 不产生事件）。 */
    data class TextDelta(val text: String) : ChatStreamEvent

    /** 流结束：整条助手消息（content 为累积全文，toolCalls 为累积结果）。 */
    data class Completed(val message: AssistantMessage) : ChatStreamEvent
}
