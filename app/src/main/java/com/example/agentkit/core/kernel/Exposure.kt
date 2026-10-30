package com.example.agentkit.core.kernel

/**
 * 工具暴露：决定「这一轮把多少工具表发给模型」。
 *
 * ## 这是整套内核里最省钱的一块
 * 现状是 `ToolExposurePolicy`：本地小模型走一张写死的 7 项白名单，云端不裁、52 个工具全发。
 * 按实测，52 个工具的 name + description + parameters 序列化后约 2.5–3k token；一个 6 步任务
 * 要发 6 次，光工具表就 15k+ token，且每一步都在等它上传完才开始推理。
 *
 * 三种模式按模型档位自动选，不再维护白名单：
 *
 * | 模式 | 发给模型的工具表 | 代价 |
 * |---|---|---|
 * | [ExposureMode.FULL] | 全量，含完整说明 | token 最贵，但一轮就能选对 |
 * | [ExposureMode.GROUPED] | 已激活组的完整说明 + 其余组只列组名 | 多一次激活，省 60%–80% |
 * | [ExposureMode.META] | 只有 3 个元工具（发现/激活/调用） | 工具表≈100 字符，多 1–2 轮 |
 *
 * ## 关键取舍：执行侧永远看得见全部
 * 裁剪只作用于「模型的视野」，不影响 [Surface.find]。模型瞎编一个没暴露的工具名时，
 * 内核仍然能找到它并如实报告「该工具需要先激活/未授权」，而不是假装它不存在。
 * 这避免了「模型看不见 → 以为不存在 → 反复编造」的坏循环。
 */
enum class ExposureMode {
    FULL, GROUPED, META
}

/**
 * 暴露策略。
 *
 * @param mode 模式。
 * @param pinned 永远可见的工具名（建议 0–2 个，用于「没有它就无法推进」的入口）。
 * @param maxVisible 可见工具上限，防止某个组塞了 30 个工具把上下文吃光。
 */
data class ExposurePolicy(
    val mode: ExposureMode,
    val pinned: Set<String> = emptySet(),
    val maxVisible: Int = 64
) {
    companion object {
        /**
         * 按模型档位给出默认策略。**唯一的分派点**——新增 provider 只需声明 [Strength]，
         * 不必回来改这里。
         */
        fun forStrength(strength: Strength): ExposurePolicy = when (strength) {
            // 端侧小模型：只给元工具。多一轮发现，换来工具表从 3k token 降到 ~80。
            Strength.TINY -> ExposurePolicy(ExposureMode.META, maxVisible = 3)
            // 端侧较强 / 小参数云端：分组懒加载，一次能看到一组。
            Strength.SMALL -> ExposurePolicy(ExposureMode.GROUPED, maxVisible = 16)
            // 云端大模型：全量，不折腾。
            Strength.LARGE -> ExposurePolicy(ExposureMode.FULL)
        }
    }
}

/** 内核内置的元工具名。双下划线前缀：与真实工具不可能撞名，日志里也一眼可辨。 */
object MetaTool {
    const val FIND = "__find_tools"
    const val ACTIVATE = "__activate_group"
    const val CALL = "__call_tool"

    /** 元工具都不产生副作用，可无条件放行（它们只是把调用转给真实工具）。 */
    val names: Set<String> = setOf(FIND, ACTIVATE, CALL)
}

/**
 * 一轮的工具视野（不可变，`activate` 返回新实例）。
 *
 * @param all 注册的全部工具（注册序）。执行侧用。
 * @param activeGroups 已激活的组。
 */
class Surface private constructor(
    val all: List<ToolSpec>,
    val policy: ExposurePolicy,
    val activeGroups: Set<String>,
    private val groupNotes: Map<String, String>
) {
    /** 组名 → 该组工具数，按组名排序保证渲染稳定（渲染不稳定会击穿 KV 前缀缓存）。 */
    fun groups(): List<Pair<String, Int>> =
        all.groupBy { it.group }.mapValues { it.value.size }.toList().sortedBy { it.first }

    /** 模型本轮能看见的工具（按注册序，稳定的）。 */
    fun visible(): List<ToolSpec> = when (policy.mode) {
        ExposureMode.FULL -> all.take(policy.maxVisible)
        ExposureMode.META -> emptyList()
        ExposureMode.GROUPED -> {
            val inActive = all.filter { it.group in activeGroups }
            val pinnedTools = all.filter { it.name in policy.pinned && it.group !in activeGroups }
            (pinnedTools + inActive).take(policy.maxVisible)
        }
    }

    /** 激活一个组。未知组返回自身（幂等，不抛异常——模型可能猜错组名）。 */
    fun activate(group: String): Surface {
        if (all.none { it.group == group } || group in activeGroups) return this
        return Surface(all, policy, activeGroups + group, groupNotes)
    }

    /** 在全部工具里找（执行侧，不受暴露限制）。 */
    fun find(name: String): ToolSpec? = all.firstOrNull { it.name == name }

    fun groupOf(name: String): String? = find(name)?.group

    /**
     * 渲染工具表文本。**这是进请求的那段字符串**，它的长度就是每轮白花的 token。
     *
     * @param model 面向的模型（工具可以据此给详略不同的说明）。
     */
    fun render(model: ModelRef): String = when (policy.mode) {
        ExposureMode.FULL -> visible().joinToString("\n") { line(it, model) }

        ExposureMode.GROUPED -> buildString {
            val vis = visible()
            if (vis.isNotEmpty()) {
                appendLine("已激活（可直接调用）：")
                vis.forEach { appendLine("- " + line(it, model)) }
            }
            val rest = groups().filter { it.first !in activeGroups }
            if (rest.isNotEmpty()) {
                appendLine("未激活的组（先调 ${MetaTool.ACTIVATE} 才能用）：")
                rest.forEach { (g, n) -> appendLine("- $g（$n 个工具）${groupNotes[g]?.let { "：$it" } ?: ""}") }
            }
        }.trimEnd()

        ExposureMode.META -> buildString {
            appendLine("可用工具组（先 ${MetaTool.FIND} 检索，再 ${MetaTool.CALL} 执行）：")
            groups().forEach { (g, n) -> appendLine("- $g（$n 个）${groupNotes[g]?.let { "：$it" } ?: ""}") }
        }.trimEnd()
    }

    private fun line(t: ToolSpec, model: ModelRef): String {
        val ps = t.params(model)
        val args = if (ps.isEmpty()) "" else ps.joinToString(", ", "(", ")") {
            it.name + ": " + it.type + if (it.required) "" else "?"
        }
        return "${t.name}$args — ${t.brief(model)}"
    }

    /** 工具表文本长度（测试与预算估算用，避免真的拼字符串）。 */
    fun chars(model: ModelRef): Int = render(model).length

    companion object {
        /**
         * @param groupNotes 组名 → 一句话说明。可选；缺省只显示「N 个工具」。
         *        说明写在这里而不是每个工具上，是为了让组索引本身可读——小模型选组时
         *        靠的就是这一句话。
         */
        fun of(
            all: List<ToolSpec>,
            policy: ExposurePolicy,
            groupNotes: Map<String, String> = emptyMap()
        ): Surface = Surface(all, policy, emptySet(), groupNotes)
    }
}
