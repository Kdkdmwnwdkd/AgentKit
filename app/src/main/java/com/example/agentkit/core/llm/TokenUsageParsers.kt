package com.example.agentkit.core.llm

import com.example.agentkit.core.TokenUsage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 功能五（P1-5）：三家协议的 token usage 解析（纯函数，零 Android / 零网络依赖——
 * QA harness 直接喂 JSON 断言，不发起任何请求）。
 *
 * 为什么在客户端外单独一层：三家字段名各异但语义一致，收成纯函数后
 *  - 各客户端的 parseResponse / SSE 末片处理只调一行，不重复正则式挖 JSON；
 *  - harness 可对「响应体样本 → TokenUsage」逐字段校验（含字段缺失/异构名/垃圾值容错）。
 *
 * 共同容错契约：usage 对象缺失或字段不可解析时返回 **null**（而非 0）——
 * 「服务端没给」与「服务端说 0」是两件事，UI 对 null 不显示、对 0 显示。
 */

/** OpenAI 兼容协议：`usage.{prompt_tokens, completion_tokens}`（total 由本端求和，不采信服务端）。 */
fun parseOpenAiUsage(root: JsonObject): TokenUsage? {
    val usage = root["usage"]?.jsonObject ?: return null
    val prompt = usage.longField("prompt_tokens") ?: usage.longField("input_tokens") ?: return null
    val completion = usage.longField("completion_tokens") ?: usage.longField("output_tokens") ?: return null
    return TokenUsage(prompt, completion)
}

/** Claude Messages API：`usage.{input_tokens, output_tokens}`（cache 字段不计入，避免虚高）。 */
fun parseClaudeUsage(root: JsonObject): TokenUsage? {
    val usage = root["usage"]?.jsonObject ?: return null
    val prompt = usage.longField("input_tokens") ?: return null
    val completion = usage.longField("output_tokens") ?: return null
    return TokenUsage(prompt, completion)
}

/**
 * Gemini generateContent：`usageMetadata.{promptTokenCount, candidatesTokenCount}`。
 * thoughtsTokenCount（思考 token）单列不计入输出——它含在 candidates 里会重复计数，
 * 服务端契约：candidatesTokenCount 已含 thoughts，故只取两项。
 */
fun parseGeminiUsage(root: JsonObject): TokenUsage? {
    val meta = root["usageMetadata"]?.jsonObject ?: return null
    val prompt = meta.longField("promptTokenCount") ?: return null
    val completion = meta.longField("candidatesTokenCount") ?: return null
    return TokenUsage(prompt, completion)
}

/**
 * 取 JSON 对象的数字字段为 Long（纯函数）：缺失 / 非数字 / 布尔一律 null。
 * 容忍字符串数字（少数兼容端点把 token 数序列化成 "123"）——trim 后 toLongOrNull。
 */
private fun JsonObject.longField(key: String): Long? =
    this[key]?.jsonPrimitive?.content?.trim()?.toLongOrNull()
