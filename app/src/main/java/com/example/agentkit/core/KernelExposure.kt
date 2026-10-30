package com.example.agentkit.core

import com.example.agentkit.core.kernel.ExposureMode
import com.example.agentkit.core.kernel.Strength
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具暴露的**实现层**：把 [AgentTool]（现有 52 个工具的实现契约）接到内核的暴露模型上。
 *
 * ## 为什么需要这一层
 * `core/kernel/Exposure.kt` 用的是内核自己的 `ToolSpec`，而现有 52 个工具实现的是
 * `AgentTool`。让工具改实现是几十个文件的改动，且会让「核心化」这件事从第一天就背上回归面。
 * 所以中间加一层：内核只描述「按什么规则暴露」，这里负责「拿现有工具套规则」。
 *
 * ## 和 [ToolExposurePolicy] 的差别（三处，都是针对现状的毛病）
 * 1. **不再有白名单**。旧策略是「本地小模型只给这 7 个，名单写死在代码里」，新增工具忘了
 *    登记就等于永久对本地模型不可见。这里改成**分组**：任何工具都能按名字推到一个组，
 *    推不出来也归 `misc`，绝不会「消失」。
 * 2. **按能力档位分派，不按 provider 名**。旧策略用 `providerId == "local-llama"` 判断，
 *    每接一个 provider 就要回来改一次；这里用 [Strength]。
 * 3. **裁剪后仍然能被发现**。旧策略裁掉就是真没了（`scopedRegistry` 让它「查无此工具」）。
 *    这里给模型留了两个元工具：`__find_tools` 查、`__use_tools` 开。工具被裁不等于能力被禁。
 *
 * ## 安全边界（重要）
 * 暴露 ≠ 授权。裁掉只是「不摆在模型眼前」，不是「禁止执行」；真正的拦截在审批层
 * （`ToolExecutor` 的 approval 链路），本文件不碰、也不该碰。
 */
object KernelExposure {

    /** 元工具名。双下划线前缀：与真实工具不可能撞名。 */
    const val FIND_TOOLS = "__find_tools"
    const val USE_TOOLS = "__use_tools"

    /** 云端默认预激活的组：读写文件、屏幕操作、技能。其余靠 `__use_tools` 开。 */
    private val DEFAULT_GROUPS = setOf("file", "ui", "skill")

    /**
     * 端侧小模型的初始组：只给文件读写。
     *
     * 为什么连 ui 都不给：1.5B~4B 的模型在 16 个工具里选错的概率，比「先用 `__use_tools`
     * 开一组再用」带来的额外一轮更贵——选错一次至少浪费一轮 + 一次失败回填，而开组只需一次
     * 明确的调用。屏幕操作类工具的 description 又特别长（ui_* 9 个占工具表近一半），
     * 对小模型是纯噪声。
     */
    private val TINY_GROUPS = setOf("file")

    /**
     * 少数不能靠前缀推断的工具名 → 组。
     *
     * 这张表是**补丁不是白名单**：不在表里、前缀也推不出来的，归 `misc` 仍然可见可发现，
     * 只是组名不好看。这样新增工具永远不会因为「忘了登记」而从模型视野里消失。
     */
    private val GROUP_PATCH: Map<String, String> = mapOf(
        "read_file" to "file", "write_file" to "file", "edit_file" to "file",
        "ask_user" to "ui",
        "http_request" to "net",
        "conversation_search" to "memory",
        "load_skill" to "skill", "use_skill" to "skill",
        "plan_propose" to "agent", "spawn_agents" to "agent",
        "todo_read" to "agent", "todo_write" to "agent",
        "ocr_image" to "media", "speak" to "media",
        "clipboard" to "sys", "container_toolchain" to "sys",
        "send_notification" to "sys", "calculator" to "sys"
    )

    /** 组名 → 一句话说明。给模型看组索引时用，写完就不用改。 */
    private val GROUP_NOTES: Map<String, String> = mapOf(
        "file" to "读写改本地文件",
        "ui" to "看屏幕、点按、输入、问用户",
        "net" to "HTTP 请求与网页抓取",
        "web" to "浏览器前进后退、点击、填表",
        "app" to "安装卸载启停应用",
        "shell" to "命令行执行与会话管理",
        "memory" to "跨会话记忆的读写与检索",
        "skill" to "加载与使用技能",
        "workflow" to "工作流的增删改查与开关",
        "device" to "设备状态与通知",
        "media" to "图片识别与语音播报",
        "agent" to "计划、待办、子代理",
        "github" to "GitHub 仓库操作（克隆、提交、日志）",
        "sys" to "剪贴板、容器、通知等系统能力",
        "misc" to "未归类的其他工具"
    )

    /** 按名字推组。未知前缀归 `misc`（不是丢弃）。 */
    fun groupOf(name: String): String {
        GROUP_PATCH[name]?.let { return it }
        val prefix = name.substringBefore('_', missingDelimiterValue = name)
        return if (GROUP_NOTES.containsKey(prefix)) prefix else "misc"
    }

    /**
     * provider + 用户设置 → 能力档位。
     *
     * 云端一律 [Strength.SMALL]（走分组）：它的工具表值 4k token/轮，是纯浪费；
     * 而云端大模型完全能读懂组索引并主动 `__use_tools`。
     */
    fun strengthOf(providerId: String, forceFull: Boolean): Strength = when {
        forceFull -> Strength.LARGE
        providerId == "local-llama" -> Strength.TINY
        else -> Strength.SMALL
    }

    /**
     * 计算本回合的工具暴露面。
     *
     * @param all 全量工具（注册序）。
     * @param active 已激活的组（**会被元工具就地修改**，由调用方按回合持有）。
     * @param forceFull 强制全量（开发者开关）：返回全量，且不生成元工具。
     */
    fun plan(
        all: List<AgentTool>,
        providerId: String,
        forceFull: Boolean,
        active: MutableSet<String>
    ): Plan {
        val strength = strengthOf(providerId, forceFull)
        if (strength == Strength.LARGE) {
            return Plan(ExposureMode.FULL, all, 0, groupsOf(all), emptySet())
        }
        // 云端与端侧都走「分组 + 元工具」：差别只在初始激活哪些组。
        if (active.isEmpty()) {
            val seed = if (strength == Strength.TINY) TINY_GROUPS else DEFAULT_GROUPS
            active.addAll(seed.filter { g -> all.any { groupOf(it.name) == g } })
        }

        val visible = all.filter { groupOf(it.name) in active } + metaTools(all, active)
        val mode = if (strength == Strength.TINY) ExposureMode.META else ExposureMode.GROUPED
        val realVisible = visible.count { it.name != FIND_TOOLS && it.name != USE_TOOLS }
        return Plan(mode, visible, all.size - realVisible, groupsOf(all), active.toSet())
    }

    /** 组名 → 工具数，按组名排序（顺序稳定，避免提示逐轮抖动）。 */
    fun groupsOf(all: List<AgentTool>): List<Pair<String, Int>> =
        all.groupBy { groupOf(it.name) }.mapValues { it.value.size }.toList().sortedBy { it.first }

    /**
     * 两个元工具。它们**只改变「下一轮能不能看见」**，不执行任何真实工具，
     * 因此不需要审批、也没有副作用（`readOnly = true`）。
     */
    private fun metaTools(all: List<AgentTool>, active: MutableSet<String>): List<AgentTool> {
        val index = all.groupBy { groupOf(it.name) }.toSortedMap()

        val find = MetaTool(
            name = FIND_TOOLS,
            description = "按关键词检索全部工具（含当前未显示的）。只返回名字与用途，不执行。" +
                "查到后调 $USE_TOOLS 启用它所在的分组。",
            parameters = JsonSchema(
                type = "object",
                properties = mapOf("query" to JsonSchema.Property("string", "关键词，如 文件/截图/记忆")),
                required = listOf("query")
            )
        ) { args ->
            val q = args["query"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
            if (q.isEmpty()) {
                ToolResult.fail("missing_param", "缺少 query", "传一个关键词，例如 query=\"文件\"")
            } else {
                val hits = all.filter {
                    it.name.contains(q, true) || it.description.contains(q, true) || groupOf(it.name).contains(q, true)
                }.take(10)
                when {
                    hits.isNotEmpty() -> ToolResult.ok(
                        "find_ok",
                        hits.joinToString("\n") { "- ${it.name}（组 ${groupOf(it.name)}）：${it.description.take(40)}" }
                    )
                    // 没精确命中时不报失败，退化为「列出全部」。
                    // 理由：模型用中文口语查（「截图」），工具名和描述可能是「截取当前屏幕画面」，
                    // 纯子串匹配必然有漏。这里让模型一次拿到全量清单（只列名字，不列描述），
                    // 比让它换个词再查一轮便宜得多——失败重试也要花 token。
                    else -> ToolResult.ok(
                        "find_fallback",
                        "没有精确匹配「$q」，以下是全部工具（按组）：\n" + index.entries.joinToString("\n") { (g, ts) ->
                            "- $g：" + ts.joinToString(", ") { t -> t.name }
                        }
                    )
                }
            }
        }

        val use = MetaTool(
            name = USE_TOOLS,
            description = buildString {
                append("启用工具分组，启用后下一轮即可直接调用其中的工具。可分组的全部工具：\n")
                index.forEach { (g, tools) ->
                    append("- $g（${tools.size} 个，${GROUP_NOTES[g] ?: g}）")
                    if (g in active) append(" [已启用]")
                    append('\n')
                }
                append("参数 groups 用逗号分隔，例如 groups=\"ui,memory\"")
            },
            parameters = JsonSchema(
                type = "object",
                properties = mapOf("groups" to JsonSchema.Property("string", "组名，逗号分隔")),
                required = listOf("groups")
            )
        ) { args ->
            val raw = args["groups"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val want = raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            if (want.isEmpty()) {
                ToolResult.fail("missing_param", "缺少 groups", "传组名，例如 groups=\"ui,memory\"")
            } else {
                val unknown = want.filter { it !in index }
                val known = want.filter { it in index }
                active.addAll(known)
                when {
                    known.isEmpty() -> ToolResult.fail(
                        "unknown_group",
                        "没有这些分组：${unknown.joinToString(",")}",
                        "可用分组：" + index.keys.joinToString("、")
                    )
                    unknown.isEmpty() -> ToolResult.ok(
                        "use_ok",
                        "已启用 " + known.joinToString("、") { g ->
                            "$g(${index[g]?.size ?: 0} 个：" + (index[g]?.joinToString(",") { t -> t.name } ?: "") + ")"
                        }
                    )
                    else -> ToolResult.ok(
                        "use_partial",
                        "已启用 ${known.joinToString(",")}；未找到 ${unknown.joinToString(",")}",
                        data = "可用分组：" + index.keys.joinToString("、")
                    )
                }
            }
        }

        return listOf(use, find)
    }

    /**
     * 一次暴露的完整快照。
     *
     * @param hiddenCount 被裁掉的真实工具数（不含元工具）。
     */
    data class Plan(
        val mode: ExposureMode,
        val visible: List<AgentTool>,
        val hiddenCount: Int,
        val groups: List<Pair<String, Int>>,
        val activated: Set<String>
    ) {
        val names: List<String> get() = visible.map { it.name }
        val trimmed: Boolean get() = hiddenCount > 0
    }

    /** 元工具的最小实现：只读、无审批、行为由闭包给定。 */
    private class MetaTool(
        override val name: String,
        override val description: String,
        override val parameters: JsonSchema,
        private val block: suspend (JsonObject) -> ToolResult
    ) : AgentTool {
        override val readOnly: Boolean get() = true
        override suspend fun execute(args: JsonObject): ToolResult = block(args)
    }
}
