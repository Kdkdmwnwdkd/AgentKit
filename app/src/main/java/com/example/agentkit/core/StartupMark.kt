package com.example.agentkit.core

/**
 * 启动分段计时的**门面**：把「打点」这件事从调用方文件里彻底拿走。
 *
 * ## 为什么要多这一层
 *
 * [StartupTrace] 负责「记时间」，但它不负责「在哪儿打点」。打点必须落在具体的
 * 初始化步骤旁边（否则测的就不是那段代码），于是每个打点都要在被观测的文件里
 * 占一行——而 `ChatViewModel`（3900 行）与 `RealAgentLoop`（1560 行）都已经被
 * arch-guard 冻结了行数上限（只减不增）。
 *
 * 结果就是：加埋点会让这两个大文件超限。抬基线是错的做法（那等于放弃约束）。
 * 正确的做法是**把埋点代码抽出去**——本类就是抽出去的那部分：
 *
 *  - 调用方只写 `startup.mark("skills")` 一行；
 *  - 打点的记录与汇总全在本类里；
 *  - 大文件的行数增长被压到最低（埋点本身占的行，靠压缩同文件里的冗长注释抵消）。
 *
 * ## 用法
 *
 * ```kotlin
 * private val startup = StartupMark()
 * // …初始化…
 * val skills = buildSkills().also { startup.mark("skills") }
 * // …更多初始化…
 * startup.logSummary("ViewModel 初始化分段")   // 落一行日志
 * ```
 */
class StartupMark(
    /** 慢段阈值（毫秒）：低于它的段不进「慢段」清单。默认 100ms。 */
    private val slowThresholdMillis: Long = 100L
) {

    private val trace = StartupTrace()

    /**
     * 结束上一段并开始下一段。
     *
     * @param name 段名（如 "skills" / "registry"）。
     * @return 该段耗时（毫秒）。
     */
    fun mark(name: String): Long = trace.mark(name)

    /** 已记录的段（快照，按记录顺序）。 */
    fun snapshot(): List<StartupTrace.Segment> = trace.snapshot()

    /**
     * 落一行汇总日志：每段毫秒数 + 超阈值的慢段。
     *
     * @param label 前缀（如 "ViewModel 初始化分段"）。
     */
    fun logSummary(label: String) {
        AgentLogger.i("Startup", trace.logLine(label, slowThresholdMillis))
    }
}
