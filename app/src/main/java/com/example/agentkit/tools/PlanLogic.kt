package com.example.agentkit.tools

/**
 * plan_propose 的解析与校验纯函数（W-25）：无 Android/协程依赖，harness 可测。
 *
 * 为什么单独拆：模型给的步骤列表格式很脏——可能带 `1.` `2)` `-` 前缀、空行、
 * 一整段用逗号分隔。这里统一清洗，避免把 "1." 也当成步骤内容显示给用户。
 */
object PlanLogic {

    /** 单次计划最多步骤数：再多用户也不会逐条看，说明该拆任务了。 */
    const val MAX_STEPS = 20

    /**
     * 清洗步骤列表：
     *  - 按换行切；没有换行时按 `；`/`;` 切（模型偶尔写成一行）；
     *  - 去掉 `1.` `1)` `-` `•` 等前缀；
     *  - 去首尾空白、丢弃空行；
     *  - 去重（保留首次出现顺序）。
     */
    fun parseSteps(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val lines = if (text.contains('\n')) text.lines() else text.split('；', ';')
        return lines.asSequence()
            .map { stripBullet(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /** 去掉行首的项目符号与序号。 */
    fun stripBullet(line: String): String {
        var s = line.trim()
        // 1. / 1) / 1、 / - / * / • 
        s = s.replaceFirst(Regex("^\\d{1,2}\\s*[.、)．]\\s*"), "")
        s = s.replaceFirst(Regex("^[-*•·]\\s*"), "")
        return s.trim()
    }

    /** 校验；返回错误文案，null 表示通过。 */
    fun validate(summary: String, steps: List<String>): String? {
        if (summary.isBlank()) return "计划缺少一句话摘要（summary）"
        if (summary.length > 200) return "摘要过长（>200 字），请压缩成一句话"
        if (steps.isEmpty()) return "计划没有任何步骤"
        if (steps.size > MAX_STEPS) return "步骤过多（${steps.size} 步），请合并到 $MAX_STEPS 步以内"
        if (steps.any { it.length > 200 }) return "有步骤描述过长（>200 字），请简化"
        return null
    }
}
