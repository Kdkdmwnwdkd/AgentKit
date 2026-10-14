package com.example.agentkit.core.terminal

import com.example.agentkit.core.MAX_TOOL_RESULT_CHARS

/**
 * 截断结果：截断后文本 + 是否发生截断 + 原始长度。
 *
 * @param text 最终文本（[truncated] 为 true 时长度恒 ≤ [ToolOutputTruncator.maxChars]）
 * @param truncated 是否发生截断
 * @param originalLength 截断前原文长度（字符）
 */
data class TruncationResult(
    val text: String,
    val truncated: Boolean,
    val originalLength: Int
)

/**
 * 工具输出截断器（T08，来源：zafiro B2 · `ToolOutputTruncator`，净室重写）。
 *
 * ## 口径对齐（与 `core/ToolLimits.kt` 的关系，明确说明差异）
 * - **上限口径一致**：[DEFAULT_MAX_CHARS] 直接复用 [MAX_TOOL_RESULT_CHARS]（24000），
 *   保证「终端输出」与「工具结果」两条链路的封顶值同源，不会出现「池里放过 30K、
 *   AgentLoop 又砍回 24K」的重复口径漂移。
 * - **截断策略差异（有意）**：`truncateToolResult` 是**只保留头部**（模型看到结果开头即可）；
 *   本截断器是**保头 + 保尾**——终端命令的报错/退出信息通常在**尾部**，只砍尾会把最有用的
 *   一行丢掉。故此处保留 `head(60%) + 省略提示 + tail(20%)`，总长恒 ≤ 上限。
 *
 * 纯函数：同输入两次调用结果完全相等（与 [com.example.agentkit.core.truncateToolResult] 同性质）。
 */
class ToolOutputTruncator(
    val maxChars: Int = DEFAULT_MAX_CHARS
) {

    init {
        require(maxChars > 0) { "maxChars 必须 > 0，当前=$maxChars" }
    }

    /**
     * 截断 [text]。
     *
     * @param text 原始输出文本
     * @return [TruncationResult]；未超长时 [TruncationResult.truncated] 为 false 且原文返回
     */
    fun truncate(text: String): TruncationResult {
        if (text.length <= maxChars) return TruncationResult(text, false, text.length)

        // 头 60% / 尾 20% 起步；后缀长度随「省略字符数」的位数微调，迭代收敛后恒 ≤ 上限
        var headLen = maxChars * 60 / 100
        var tailLen = maxChars * 20 / 100
        var suffix = ""
        repeat(ITERATIONS) {
            val omitted = (text.length - headLen - tailLen).coerceAtLeast(0)
            suffix = "\n…[输出过长已截断，共 ${text.length} 字符，中间省略 ${omitted} 字符]\n"
            val overflow = headLen + tailLen + suffix.length - maxChars
            if (overflow <= 0) return@repeat
            val cutTail = overflow.coerceAtMost(tailLen)
            tailLen -= cutTail
            headLen = (headLen - (overflow - cutTail)).coerceAtLeast(0)
        }

        var out = text.substring(0, safeHeadEnd(text, headLen.coerceAtMost(text.length))) +
            suffix +
            text.substring(safeTailStart(text, (text.length - tailLen).coerceAtLeast(headLen.coerceAtMost(text.length))))
        // 兜底：极端参数下再夹一次，保证不变量（夹点同样对齐码点，不切出孤立代理）
        if (out.length > maxChars) out = out.substring(0, safeHeadEnd(out, maxChars))
        return TruncationResult(out, true, text.length)
    }

    companion object {
        /** 默认上限：与工具结果上限同源（`core/ToolLimits.kt` 的 24000）。 */
        const val DEFAULT_MAX_CHARS: Int = MAX_TOOL_RESULT_CHARS

        /** 收敛迭代次数（后缀长度只随省略位数变化，3 次足够，留 5 次保险）。 */
        private const val ITERATIONS = 5

        /**
         * 头部切点对齐：若 [end] 恰落在**代理对中间**（前一码元是高位代理、当前是低位代理），
         * 则回退 1，避免切出孤立代理（半个 emoji / 非法 UTF-8 码点）。BMP 字符（中/é 等，
         * 单个 UTF-16 码元）本就天然对齐，不受影响。
         */
        private fun safeHeadEnd(s: String, end: Int): Int {
            var e = end.coerceIn(0, s.length)
            if (e in 1 until s.length && s[e - 1].isHighSurrogate() && s[e].isLowSurrogate()) e -= 1
            return e
        }

        /** 尾部起点对齐：若起点落在代理对中间（当前是低位代理、前一位是高位代理），后移 1。 */
        private fun safeTailStart(s: String, start: Int): Int {
            var st = start.coerceIn(0, s.length)
            if (st in 1 until s.length && s[st].isLowSurrogate() && s[st - 1].isHighSurrogate()) st += 1
            return st
        }
    }
}
