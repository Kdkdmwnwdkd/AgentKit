package com.example.agentkit.core

/**
 * 工具结果体积上限 + 单结果截断（T06，来源：operit #9 / aicode C3）。
 *
 * 手机上下文宝贵：一个 100KB 文件原样回喂会撑爆上下文、推高 token 成本。
 * 每个工具结果**独立**截断——不可整批截，因为每个调用必须保留对应结果槽位
 * （operit `ConversationMarkupManager.kt:111` 注释同结论）。
 */
const val MAX_TOOL_RESULT_CHARS: Int = 24_000

/**
 * 单个工具结果截断到 [maxChars] 以内（含后缀本身——保证回喂内容恒 ≤ 上限）。
 *
 * 纯函数：只依赖入参，同样输入两次调用结果完全相等（与 [formatToolResult] 同性质）。
 *
 * @param text 已格式化的工具结果文本（通常来自 [formatToolResult]）。
 * @param maxChars 上限，默认 [MAX_TOOL_RESULT_CHARS]。
 * @return 未超长原样返回；超长则保留前 (maxChars - 后缀长) 字符并追加截断提示。
 */
fun truncateToolResult(text: String, maxChars: Int = MAX_TOOL_RESULT_CHARS): String {
    if (text.length <= maxChars) return text
    // 手工拼接后缀（不用 String.format——默认 Locale 下数字字形可能变形，破坏确定性）
    val suffix = buildString(text.length / 10 + 64) {
        append("\n…[结果过长已截断，共 ")
        append(text.length)
        append(" 字符，建议改用分页参数（如 read_file 的 offset/limit）分批获取]")
    }
    val keep = (maxChars - suffix.length).coerceAtLeast(0)
    return text.substring(0, keep) + suffix
}
