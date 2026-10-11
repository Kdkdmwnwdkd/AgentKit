package com.example.agentkit.core

/**
 * 工具注册表：维护 name -> AgentTool 的映射，并统一负责「查找 + 调用 + 异常兜底」。
 * 之后的 Skill 插件系统只需把自己的工具 register 进来，Agent 循环无需改动。
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, AgentTool>()

    fun register(tool: AgentTool): ToolRegistry = apply { tools[tool.name] = tool }
    fun unregister(name: String): ToolRegistry = apply { tools.remove(name) }
    fun get(name: String): AgentTool? = tools[name]
    fun list(): List<AgentTool> = tools.values.toList()
    fun names(): List<String> = tools.keys.toList()

    /** 执行一次工具调用，无论成功失败都返回一个可进入历史的 ToolMessage。 */
    suspend fun execute(call: ToolCall): ToolMessage {
        val tool = tools[call.name]
            ?: return ToolMessage(call.id, call.name, call.arguments, "未知工具：${call.name}", isError = true)

        return runCatching { tool.execute(call.arguments) }
            .fold(
                onSuccess = { ToolMessage(call.id, tool.name, call.arguments, it.content, it.isError) },
                onFailure = { ToolMessage(call.id, tool.name, call.arguments, "工具执行异常：${it.message}", isError = true) }
            )
    }
}
