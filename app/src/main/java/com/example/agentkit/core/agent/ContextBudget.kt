package com.example.agentkit.core.agent

import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.MAX_TOOL_RESULT_CHARS
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.UserMessage

/**
 * 回合内上下文预算治理（W-10）——按「估算 token」而不是「消息条数」决定留什么。
 *
 * ## 为什么不用条数
 *
 * W-9 之前的实现是 `maxHistoryMessages=40` 的条数滑动窗口。条数对体积没有解释力：
 *  - 一条工具结果上限 24k 字符（[com.example.agentkit.core.MAX_TOOL_RESULT_CHARS]），
 *    40 条最坏可到 96 万字符 ≈ 30 万 token——32k 上下文的小模型直接爆；
 *  - 反过来 40 条短消息可能不到 2k token，又白白丢掉了有用的历史。
 *
 * 真机体感「越跑越慢、越跑越贵」的上下文侧根因就在这里：每多一步工具调用，
 * 请求体就多一条最长 24k 字符的结果，且**每轮都全量重发**。
 *
 * ## 三个纯函数（零 Android 依赖，QA harness 可直测）
 *
 * 1. [estimateTokens]：CJK 感知的**保守**估算（CJK 1 字符≈1 token，ASCII≈4 字符/token）。
 *    宁可高估：低估的代价是真的撑爆上下文，高估只是多丢一点旧历史。
 * 2. [pruneImages]：只保留最近 N 条工具截图的 base64，旧图剥成一行省略标记。
 *    截图是单条最大的上下文炸弹——768px JPEG 的 data URL 约 6~12 万字符，而真正
 *    计费的是图像 token（约 900），若按字符估算会高估 100 倍，故图片单独计价。
 * 3. [fitToBudget]：超预算时从**最老的完整步**开始整组丢弃，保留：
 *    - 首条用户消息（任务锚点，丢了模型就不知道要干什么）；
 *    - 最后一段（当前步，丢了这一步等于没做）；
 *    - `assistant(tool_calls)` ↔ `tool` 结果的配对（拆开会被严格供应商直接 400 拒绝）。
 *
 * ## 为什么是「构建请求视图」而不是原地压缩
 *
 * 本模块只返回新列表、不改入参。调用方（[RealAgentLoop]）每轮用完整历史构建一份
 * **只发给 LLM 的视图**，UI/落盘历史保持完整——用户仍能看到每一步工具卡片，
 * 且不会出现「上一轮还在、这一轮没了」的抖动（无状态 = 天然无抖动，无需滞回计数）。
 */
object ContextBudget {

    /** 默认预算：24k token（覆盖 32k 上下文的常见小模型，留出输出与工具定义余量）。 */
    const val DEFAULT_BUDGET_TOKENS: Int = 24_000

    /**
     * 本地 llama.cpp 通道的预算上限（2k token）。
     *
     * 依据：`LocalLlamaSetting.DEFAULT_N_CTX = 4096`——手机端默认只开 4k 上下文，
     * 其中系统提示（含工具片段/记忆摘要）与工具定义已占约 2k，输出再留几百；
     * 若沿用 24k 预算，压缩形同虚设，请求会直接被 llama.cpp 截断或报错。
     */
    const val LOCAL_LLAMA_BUDGET_TOKENS: Int = 2_000

    /** 本地通道的 provider id（与 `ToolExposurePolicy` 同一标识）。 */
    const val LOCAL_LLAMA_PROVIDER_ID: String = "local-llama"

    /**
     * 按通道取实际预算：本地小模型取 [LOCAL_LLAMA_BUDGET_TOKENS] 与配置值的较小者
     * （配置更小则尊重配置，绝不上调）；云端模型直接用配置值。
     */
    fun budgetForProvider(providerId: String, configured: Int = DEFAULT_BUDGET_TOKENS): Int =
        if (providerId == LOCAL_LLAMA_PROVIDER_ID) minOf(configured, LOCAL_LLAMA_BUDGET_TOKENS) else configured

    /**
     * 单条工具结果的字符上限（W-11）：与上下文预算对齐。
     *
     * 背景：全局上限 [MAX_TOOL_RESULT_CHARS]=24k 是按「大上下文云端模型」定的。
     * 在本地 2k 预算下，一条 24k 字符的结果 ≈ 8k~24k token，**单条就超预算**；
     * 而当前步不可丢（丢了等于没做），压缩也救不回来——请求仍会撑爆 n_ctx。
     *
     * 口径：结果字符数 ≤ 预算的 1.2 倍（CJK 约 1 字 1 token；ASCII 约 4 字 1 token，
     * 给纯英文结果留出余量）；再与全局上限取小，云端行为不变（24k 封顶）。
     * 下限 1000 字符，避免预算配置极小时把结果截成残句。
     */
    fun toolResultLimitChars(
        providerId: String,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS
    ): Int {
        val budget = budgetForProvider(providerId, budgetTokens)
        val byBudget = (budget * 6 / 5).coerceAtLeast(1_000)
        return minOf(MAX_TOOL_RESULT_CHARS, byBudget)
    }

    /** 默认保留最近几条带图消息的图片（更早的截图剥成文字标记）。 */
    const val DEFAULT_KEEP_IMAGES: Int = 2

    /**
     * 单张图片的估算 token。
     *
     * 768px 长边的 JPEG 缩略图，视觉模型按像素计费约 `768*768/750 ≈ 786`；
     * 取 900 留一点余量。**绝不能按 base64 字符数估算**（会高估约 100 倍）。
     */
    const val IMAGE_TOKENS: Int = 900

    /** 旧图被剥离后追加的标记（模型据此知道「这里曾有一张图，但被省略了」）。 */
    const val PRUNED_IMAGE_NOTE: String = "\n（早前截图已从上下文省略以节省 token）"

    /**
     * 估算一段文本的 token 数（保守上界）。
     *
     * 口径：CJK 1 字符 ≈ 1 token；ASCII ≈ 4 字符/token；其余（emoji/西里尔等）≈ 2 字符/token。
     * 这是**估算**，不是分词器——目的是给出稳定的预算判据，不追求与某家 tokenizer 逐字对齐。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        var other = 0
        for (ch in text) {
            val c = ch.code
            when {
                c < 0x80 -> ascii++
                c in 0x3000..0x30FF -> cjk++ // CJK 标点 + 假名
                c in 0x3400..0x4DBF -> cjk++ // 扩展 A
                c in 0x4E00..0x9FFF -> cjk++ // 基本区
                c in 0xAC00..0xD7AF -> cjk++ // 谚文
                c in 0xFF00..0xFFEF -> cjk++ // 全角
                else -> other++
            }
        }
        return cjk + (ascii + 3) / 4 + (other + 1) / 2
    }

    /** 单条消息的估算 token（图片按 [IMAGE_TOKENS] 计价，不按 base64 长度）。 */
    fun messageTokens(message: ChatMessage): Int = when (message) {
        is SystemMessage -> estimateTokens(message.content)
        is UserMessage -> estimateTokens(message.content) + message.images.size * IMAGE_TOKENS
        is AssistantMessage -> {
            val calls = message.toolCalls.sumOf { call ->
                // 工具名 + 参数 JSON + 结构开销（id/type 字段）
                estimateTokens(call.name) + estimateTokens(call.arguments.toString()) + 8
            }
            estimateTokens(message.content) + calls
        }
        is ToolMessage -> {
            estimateTokens(message.content) +
                estimateTokens(message.name) + 8 +
                message.images.size * IMAGE_TOKENS
        }
    }

    /** 整段历史的估算 token。 */
    fun totalTokens(messages: List<ChatMessage>): Int = messages.sumOf { messageTokens(it) }

    /**
     * 只保留最近 [keepLast] 条**工具截图**消息的图片，更早的剥掉 base64 并追加省略标记。
     *
     * 首条用户消息里的图片**不裁**——那是用户本次任务的输入（例如「按这张图改」），
     * 裁掉等于把任务本身删了。只裁工具回灌的截图。
     *
     * 入参不被修改；返回新列表（未命中的元素原样复用，零拷贝）。
     */
    fun pruneImages(messages: List<ChatMessage>, keepLast: Int = DEFAULT_KEEP_IMAGES): List<ChatMessage> {
        if (keepLast < 0) return messages
        val anchorIdx = messages.indexOfFirst { it is UserMessage }
        val imageIdx = messages.indices.filter { i ->
            i != anchorIdx && (messages[i] as? UserMessage)?.images?.isNotEmpty() == true
        }
        if (imageIdx.size <= keepLast) return messages
        val drop = imageIdx.dropLast(keepLast).toHashSet()
        return messages.mapIndexed { i, m ->
            if (i !in drop) {
                m
            } else {
                val u = m as UserMessage
                u.copy(
                    images = emptyList(),
                    content = if (u.content.endsWith(PRUNED_IMAGE_NOTE)) u.content else u.content + PRUNED_IMAGE_NOTE
                )
            }
        }
    }

    /**
     * 把历史压进预算：超限时从最老的**完整步**开始丢，直到 ≤ [targetTokens] 且 ≤ [maxMessages]。
     *
     * 「完整步」= `assistant(tool_calls)` + 其后所有 `tool` 结果 + 紧随的截图回灌消息。
     * 整组丢/整组留，保证任何 `tool` 结果都有配对的 `tool_call`（严格供应商的硬约束）。
     *
     * **只丢到「刚好不超」为止**（[targetTokens] 默认 = [budgetTokens]），不多丢一步：
     * 曾有「压到 60% 留滞回空间」的设计，那是**有状态就地压缩**的产物（怕每轮都触发）；
     * 本实现是无状态请求视图，每轮从完整历史重算、天然无抖动，多丢的旧历史纯属浪费。
     * 需要预留余量的调用方可显式传更小的 targetTokens。
     *
     * 永不丢弃：首条 SystemMessage、首条 UserMessage（任务锚点）、最后一段、最后一个
     * 含工具结果的步（防止「最终文本尾随」时把当前步整步丢掉）。
     * 若丢无可丢仍超预算，返回尽力而为的结果（调用方只能接受——比整轮失败好）。
     *
     * 入参不被修改；未超限时原样返回（同一个 List 实例，零分配）。
     */
    fun fitToBudget(
        messages: List<ChatMessage>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        targetTokens: Int = budgetTokens,
        maxMessages: Int = Int.MAX_VALUE
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        var total = totalTokens(messages)
        if (total <= budgetTokens && messages.size <= maxMessages) return messages

        val segs = segmentsOf(messages)
        if (segs.size <= 2) return messages // 只剩锚点段与末段，没有可丢的中间步

        val anchorIdx = segs.indexOfFirst { s ->
            (s.start until s.end).any { messages[it] is UserMessage }
        }
        val lastIdx = segs.lastIndex
        // 最后一个「含工具结果的步」也要钉住：若末尾跟着一条最终文本，lastIdx 指向文本段，
        // 当前步就成了可丢的中间段——预算紧时会把「刚做完这一步」的证据整段删掉。
        val lastStepIdx = segs.indexOfLast { s ->
            (s.start until s.end).any { messages[it] is ToolMessage }
        }
        val dropped = BooleanArray(messages.size)
        var kept = messages.size

        for (idx in segs.indices) {
            if (total <= targetTokens && kept <= maxMessages) break
            if (idx == 0 || idx == anchorIdx || idx == lastIdx || idx == lastStepIdx) continue
            val s = segs[idx]
            for (j in s.start until s.end) dropped[j] = true
            kept -= s.size
            total -= (s.start until s.end).sumOf { messageTokens(messages[it]) }
        }
        if (kept == messages.size) return messages
        return messages.filterIndexed { i, _ -> !dropped[i] }
    }

    // ---- 内部分段 ----------------------------------------------------------

    private data class Segment(val start: Int, val end: Int) {
        val size: Int get() = end - start
    }

    /**
     * 把消息切成「完整步」：assistant(tool_calls) 与其后的 tool 结果、截图回灌消息同组。
     * 其余消息各自成段。返回的段连续覆盖整个列表。
     */
    private fun segmentsOf(messages: List<ChatMessage>): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            var end = i + 1
            if (msg is AssistantMessage && msg.toolCalls.isNotEmpty()) {
                while (end < messages.size && messages[end] is ToolMessage) end++
                // 截图回灌消息紧跟在工具结果之后，属于同一「步」
                if (end < messages.size) {
                    val next = messages[end]
                    if (next is UserMessage && next.images.isNotEmpty()) end++
                }
            }
            out.add(Segment(i, end))
            i = end
        }
        return out
    }
}
