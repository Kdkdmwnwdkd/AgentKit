package com.example.agentkit.core.agent

import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.ToolRegistry

/**
 * Agent 运行时契约（T01）：回合主循环的抽象接口。
 *
 * 背景：旧实现把回合主循环整个内联在具体类 `com.example.agentkit.core.AgentLoop`
 * 里，UI、Skill 桥、未来的 MCP 客户端都只能依赖那个具体类——换实现、插桩、
 * 跑 harness 都得拖上整套真 registry / 真 LLM。本接口把「跑一轮对话」的
 * 最小契约抽出来：
 *
 *  - [send]：跑一轮，返回完整历史（语义 = 旧 `AgentLoop.run()`）。
 *  - [stop]：优雅收尾通道（置 shouldContinue=false 语义），**不杀协程**——
 *    协程取消仍由调用方的 Job.cancel 负责，两条通道互不依赖（T05 现状保留）。
 *  - [update]：运行时更新配置（null = 保持现值），供 UI 在不重建 loop 的前提下
 *    换系统提示 / 记忆提供者 / 工具表 / 步数上限。
 *
 * UI 只认事件流（AgentEvent）与状态流，不认本接口的具体实现类；
 * 默认实现见 [RealAgentLoop]，兼容适配层见 `com.example.agentkit.core.AgentLoop`。
 */
interface AgentLoop {

    /**
     * 执行一轮对话，返回完整消息历史（含系统提示、工具调用与结果）。
     *
     * @param userInput 用户输入（已由调用方拼接设备上下文等前缀）。
     * @param images 用户手动附带的图片（data URL 列表），作为本轮首条
     *   [UserMessage] 的多模态内容一起发——让「截图问模型」成为一条自然路径
     *   （视觉能力此前只在工具返回里用，输入端没有入口）。
     * @param priorMessages 本回合之前的历史对话（W-46）。按时间正序，不含系统提示与当前输入。
     *   由调用方从会话存储摊平而来；实现须把它插在 SystemMessage 之后、当前 UserMessage 之前，
     *   且**返回值里不得包含**这些历史（返回值语义仍是「本回合新增的历史」，调用方据此落盘）。
     *   空列表 = 无历史（旧行为）。上下文预算由 [ContextBudget] 统一裁剪，调用方不必预压。
     * @return 完整历史；正常结束 / 优雅收尾 / 达到 maxSteps 时返回，
     *   取消时抛 CancellationException（T05 树尾闭合后重抛）。
     */
    suspend fun send(
        userInput: String,
        images: List<String> = emptyList(),
        priorMessages: List<ChatMessage> = emptyList()
    ): List<ChatMessage>

    /**
     * 请求停止（优雅收尾通道）：本轮工具批次结束、下一轮 LLM 调用前生效，
     * 补一条停止说明后返回历史。不取消协程、不抛异常。
     * 未在运行时调用为 no-op。
     */
    fun stop()

    /**
     * 运行时更新配置：命名可选参数，null = 保持现值（缺省即不变）。
     *
     * @param systemPrompt 系统提示（工具自带 systemPrompt 拼接与记忆摘要逻辑不变）。
     * @param memoryProvider 记忆摘要提供者；传 null 保持现值（注意：无法用本方法关闭记忆）。
     * @param registry 工具注册表；下一批工具执行与下一轮 LLM 请求即生效。
     * @param maxSteps 单轮最大步数；下一轮 send() 生效。
     */
    fun update(
        systemPrompt: String? = null,
        memoryProvider: (() -> String)? = null,
        registry: ToolRegistry? = null,
        maxSteps: Int? = null
    )
}
