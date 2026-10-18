package com.example.agentkit.tools

/**
 * edit_file 的决策纯函数（W-20）：与 IO / Android 彻底解耦，供 [EditFileTool] 与
 * QA harness（scripts/qa/EditWebSearchVerification.kt）共用同一份真源。
 *
 * 判定顺序（与 AiCode edit 工具的净室对齐：先校验、再动手，绝不写坏文件）：
 *  1. old_string 为空 → 拒绝（空串会在每处边界匹配，结果不可预期）；
 *  2. old == new → 拒绝（no-op，别浪费一次写）；
 *  3. 文件中找不到 old_string → stale_not_found（提示重新读取，可能已被其他修改改变）；
 *  4. 出现多处且未声明 replace_all → not_unique（给出行号，让模型补上下文或改全量）；
 *  5. 通过 → 返回替换结果与替换次数。
 */
sealed interface EditPlan {
    /** 校验通过：替换后的完整文本、替换处数、首个替换点在新文本中的字符偏移。 */
    data class Replace(
        val replaced: String,
        val count: Int,
        val firstOffset: Int
    ) : EditPlan

    /** 校验拒绝：稳定失败码 + 面向模型的一句话说明 + 下一步修复指引。 */
    data class Reject(
        val code: String,
        val message: String,
        val hint: String
    ) : EditPlan
}

object EditFileLogic {

    fun plan(
        original: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): EditPlan {
        if (oldString.isEmpty()) {
            return EditPlan.Reject(
                code = "empty_old_string",
                message = "old_string 不能为空",
                hint = "空 old_string 会在每处边界匹配导致结果不可预期。请给出要替换的具体文本片段。"
            )
        }
        if (oldString == newString) {
            return EditPlan.Reject(
                code = "no_op",
                message = "old_string 与 new_string 完全相同，无需修改",
                hint = "如果确实要改内容，请调整 new_string；否则不必调用 edit_file。"
            )
        }

        val occurrences = ArrayList<Int>()
        var idx = original.indexOf(oldString)
        while (idx >= 0) {
            occurrences.add(idx)
            idx = original.indexOf(oldString, idx + 1)
        }
        if (occurrences.isEmpty()) {
            return EditPlan.Reject(
                code = "stale_not_found",
                message = "文件中找不到 old_string（可能已被其他修改改变，或缩进/换行不一致）",
                hint = "请重新 read_file 读取当前内容，核对要替换的片段（注意空格、制表符、" +
                    "换行符必须与文件完全一致），然后再 edit_file。"
            )
        }
        if (occurrences.size > 1 && !replaceAll) {
            val lines = occurrences.map { off -> lineOf(original, off) }
            return EditPlan.Reject(
                code = "not_unique",
                message = "old_string 在文件中出现 ${occurrences.size} 次（第 ${lines.joinToString("、")} 行），无法确定替换哪一处",
                hint = "两种改法：①把 old_string 扩充到包含上下文，使其唯一；" +
                    "②确认要全部替换时加 replace_all=true 重试。"
            )
        }

        val replaced = if (replaceAll) {
            original.replace(oldString, newString)
        } else {
            original.replaceFirst(oldString, newString)
        }
        if (replaced == original) {
            return EditPlan.Reject(
                code = "no_change",
                message = "替换前后内容相同，未做修改",
                hint = "检查 new_string 是否与 old_string 相等。"
            )
        }
        // 回显位置：必须用「第一处替换点」的原始偏移，不能用 replaced.indexOf(newString)——
        // 若新文本在文件别处早已存在（如把 "a" 改成 "b"，开头就有 "b"），前者会指向错误行。
        // 第一处替换点之前的文本未变，所以 occurrences[0] 就是替换后新文本的起始偏移。
        val firstOffset = occurrences.first()
        return EditPlan.Replace(
            replaced = replaced,
            count = if (replaceAll) occurrences.size else 1,
            firstOffset = firstOffset
        )
    }

    /** 字符偏移 → 行号（1 起）。 */
    fun lineOf(text: String, offset: Int): Int =
        text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /**
     * 替换点回显上下文：首替换行前后各 [contextLines] 行，首替换行加 "→" 前缀。
     * 空文件/极端偏移时返回占位文本，不抛异常（回显是加分项，不是失败点）。
     */
    fun contextAround(text: String, offset: Int, contextLines: Int = 3): String {
        if (text.isEmpty()) return "(上下文为空)"
        val lines = text.lines()
        if (lines.isEmpty()) return "(上下文为空)"
        val center = lineOf(text, offset) - 1
        val from = (center - contextLines).coerceAtLeast(0)
        val to = (center + contextLines).coerceAtMost(lines.lastIndex)
        return (from..to).joinToString("\n") { i ->
            "${if (i == center) "→" else " "} ${lines[i]}"
        }.ifEmpty { "(上下文为空)" }
    }
}
