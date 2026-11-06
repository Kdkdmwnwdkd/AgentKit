package com.beichen.polaris.core.agent

/**
 * 验证用替身：与生产定义同名同包，字段简化为 String。
 * journal 包只做 when 分支判断，不触碰 Completed 的负载。
 */
sealed interface ToolCallOutcome {
    data class Completed(val message: String, val images: List<String>) : ToolCallOutcome
    data object Interrupted : ToolCallOutcome
    data object Unknown : ToolCallOutcome
    data object NotDispatched : ToolCallOutcome
}
