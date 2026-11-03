package com.example.agentkit.core

/**
 * 一条消息在界面上的表示（气泡 / 工具卡）。
 *
 * ## 为什么住在 core 而不是 ui
 * 这是 P4-1 的下沉：它原先是 `ui/ChatViewModel.kt` 里的一个 sealed interface，
 * 而 `core/ConversationStore.kt` 需要用它做「落盘记录 ↔ 界面项」的映射，
 * 于是 `core` 反过来 `import com.example.agentkit.ui.UiItem`——整个工程唯一一处
 * 分层倒置（架构守卫的基线里那张豁免条目就是它）。
 *
 * 倒置的代价不是「不好看」：`core` 本应可以在没有 UI 的 JVM 里单独编译与测试
 * （记忆、会话、备份全都只有 core 依赖），一个 `ui` 引用会把整条 Compose 依赖链
 * 拖进每一次独立编译。R11~R13 的 harness 已经三次因为「顺手引了一个上层类型」
 * 而编不过。
 *
 * 判据：**纯数据、无 Compose 类型、被 core 与 ui 同时需要** → 住在 core。
 * [TokenUsage] 与 [ToolCall] 本来就在 core，所以这里没有任何新依赖。
 *
 * ## 字段语义
 * 三个子类型都只描述「显示什么」，不含任何行为：事件如何驱动、生命周期如何，
 * 都在 ViewModel 与 Compose 侧。
 */
sealed interface UiItem {

    /**
     * 用户气泡。
     *
     * @param turnIndex 该提问所属轮次下标；重新发问（截断其后轮次）按它定位；
     *   `< 0` 表示未归属（实时上屏的草稿）。
     * @param timeMs 消息时间（epoch 毫秒）；`0` = 未记录，UI 不显示时间行。
     */
    data class User(
        val text: String,
        val turnIndex: Int = -1,
        val timeMs: Long = 0
    ) : UiItem

    /**
     * 助手气泡。
     *
     * Phase 2 DoD②：额外携带「变体游标」元数据，供气泡旁切换器 `← 2/3 →` 消费。
     * 默认值保证旧构造点（只有 text）零改动即兼容——单变体、无切换器。
     *
     * @param variantIndex 当前选中变体下标（0-based）；`< 0` 表示非分叉消息（旧数据/中间助手）。
     * @param variantCount 该轮变体总数；`<= 1` 时不显示切换器。
     * @param turnIndex 该轮在会话 [TurnRecord] 列表中的下标；重新生成 / 切换 /
     *   重新发问都按它定位轮次；`< 0` 表示未归属任何轮。
     * @param usage 本条回答的 token 用量（一个回合多次 LLM 往返的合计；
     *   null = 服务端没给 usage，UI 不显示而非猜一个数）。
     * @param reasoning 本条回答的推理过程（思维链全文）；
     *   null = 模型没给思考，UI 不渲染折叠块而非摆个空框。
     */
    data class Assistant(
        val text: String,
        val variantIndex: Int = -1,
        val variantCount: Int = 1,
        val turnIndex: Int = -1,
        val usage: TokenUsage? = null,
        val reasoning: String? = null,
        val timeMs: Long = 0
    ) : UiItem

    /**
     * 工具调用卡。
     *
     * @param result 工具输出（已截断的展示文本，不是回喂给模型的原始载荷）。
     * @param images 工具返回的图片（data URL 列表），如 ui_screenshot 截图，直接渲染在对话中。
     * @param running 执行中态（`AgentEvent.ToolStarted` 已上报、`ToolFinished` 未到）。
     */
    data class Tool(
        val call: ToolCall,
        val result: String,
        val isError: Boolean,
        val images: List<String> = emptyList(),
        val running: Boolean = false
    ) : UiItem
}
