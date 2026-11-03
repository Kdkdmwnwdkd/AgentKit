package com.example.agentkit.core

/**
 * 验证用最小替身：journal 包只需要 id 与 name。
 *
 * 与生产定义同名同包，字段裁剪到最小——**不要**在这里加 arguments/schema，
 * 加了就说明 journal 包开始依赖工具参数，那是不该发生的耦合。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String = "{}"
)
