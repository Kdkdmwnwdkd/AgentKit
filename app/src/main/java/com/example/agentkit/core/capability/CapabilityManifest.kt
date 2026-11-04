package com.example.agentkit.core.capability

import com.example.agentkit.core.AgentTool

/**
 * 一个能力组：模型能「一次开一组」的最小单位。
 *
 * 注意与 [com.example.agentkit.core.capability.Capability]（能力就绪度裁决用的枚举）不是一回事：
 * 那个是「这台机器能不能跑 shell / 控屏」，这个是「工具按调用场景怎么分组」。
 *
 * @param group 组名（工具名前缀，少数例外在 [tools] 里显式列出）
 * @param note 给模型看的组索引说明（`__use_tools` 的组清单与此同源）
 * @param tools 该组包含的工具名。**清单是真源**：注册表里有、这里没有的，[CapabilityManifest.audit] 会红
 * @param pinned 常驻组：不参与淘汰，也不该让用户关掉（关了下一轮也会被 pin 回来）
 */
data class CapabilityGroup(
    val group: String,
    val note: String,
    val tools: List<String>,
    val pinned: Boolean = false,
)

/**
 * 能力清单审计结果。`problems` 为空即通过；每条都是「谁、为什么」的一句人话。
 */
data class CapabilityAudit(val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * 工具能力清单：**组从哪来、谁属于谁、哪些常驻**的单一真源，外加四条双向断言。
 *
 * ## 为什么它不能只是又一张表
 *
 * 分组原本散在 `KernelExposure` 里（一张 `GROUP_NOTES`、一张 `GROUP_PATCH`、两个常量集合），
 * 只被「计算本回合暴露面」用到。问题在于这些表**只能靠人记得更新**：
 *
 * - 新增工具 `foo_bar`，前缀 `foo` 不是已声明的组 → 它静默落进 `misc`。工具仍可见
 *   （misc 常驻），但 `__use_tools` 里永远找不到「foo」这一组，组索引也不会解释它是什么。
 * - 工具删了 / 改名了，组说明还挂在索引里，模型 `__use_tools("video")` 换回一个空组。
 * - 工具被挪了组，但名字前缀还指向老组，暴露面与索引从此对不上——这种最难查。
 *
 * 三种都是「表与代码不同步」，人眼审不出来。所以这张表带 [audit]：把同步关系变成
 * **可执行的双向断言**，启动时跑一次（留痕），harness 也跑（拦回归）。
 *
 * ## 归组规则
 *
 * 1. 先查清单（[groupOf] 命中 [toolNames] 即返回它所在组）；
 * 2. 未登记的名字（MCP 动态工具、插件）按「第一个下划线之前的前缀」推，前缀是已声明组就用它；
 * 3. 都不匹配 → `misc`（**兜底可见，不是丢弃**：不认识的东西不能藏）。
 */
object CapabilityManifest {

    /** 未归类兜底组。常驻：模型不认识的东西必须保持可见。 */
    const val MISC: String = "misc"

    /**
     * 全部能力组与它们的工具。**加工具必须改这里**——这正是本清单存在的意义：
     * 组索引、激活粒度、工具表顺序都从这一处派生，不再有第二份需要同步的名单。
     *
     * note 写「能干什么」而不是「内部叫什么」：模型看到的就是这句话。
     */
    val capabilities: List<CapabilityGroup> = listOf(
        CapabilityGroup(
            "file", "读写改本地文件",
            tools = listOf("read_file", "write_file", "edit_file"),
            pinned = true,
        ),
        CapabilityGroup(
            "ui", "看屏幕、点按、输入、问用户",
            tools = listOf(
                "ui_tree", "ui_tap", "ui_long_press", "ui_swipe", "ui_input",
                "ui_key", "ui_click", "ui_screenshot", "ask_user",
            ),
        ),
        CapabilityGroup("net", "HTTP 请求与网页抓取", tools = listOf("http_request")),
        CapabilityGroup(
            "web", "浏览器前进后退、点击、填表",
            tools = listOf("web_open", "web_snapshot", "web_click", "web_type", "web_eval", "web_search"),
        ),
        CapabilityGroup(
            "app", "安装卸载启停应用",
            tools = listOf("app_install", "app_uninstall", "app_launch", "app_list", "app_stop"),
        ),
        CapabilityGroup(
            "shell", "命令行执行与会话管理",
            tools = listOf("shell_run", "shell_close", "shell_background", "shell_jobs"),
        ),
        CapabilityGroup(
            "memory", "跨会话记忆的读写与检索",
            tools = listOf(
                "memory_write", "memory_search", "memory_list", "memory_forget", "conversation_search",
            ),
        ),
        CapabilityGroup("skill", "加载与使用技能", tools = listOf("load_skill", "use_skill")),
        CapabilityGroup(
            "workflow", "工作流的增删改查与开关",
            tools = listOf("workflow_create", "workflow_list", "workflow_delete", "workflow_toggle"),
        ),
        CapabilityGroup(
            "device", "设备状态与通知",
            tools = listOf("device_notifications", "device_foreground_app"),
        ),
        CapabilityGroup("media", "图片识别与语音播报", tools = listOf("ocr_image", "speak")),
        CapabilityGroup(
            "agent", "计划、待办、子代理",
            tools = listOf("plan_propose", "spawn_agents", "todo_write", "todo_read"),
        ),
        CapabilityGroup(
            "github", "GitHub 仓库操作（克隆、提交、日志）",
            tools = listOf("github_status", "github_commit", "github_log", "github_diff", "github_clone"),
        ),
        CapabilityGroup(
            "sys", "剪贴板、容器、通知等系统能力",
            tools = listOf("clipboard", "send_notification", "calculator", "container_toolchain"),
        ),
        // 兜底桶：成员恒为空（有成员说明有工具没登记），见 audit 的 ②③
        CapabilityGroup(MISC, "未归类的其他工具", tools = emptyList(), pinned = true),
    )

    /** 工具名 → 组（精确成员表；`misc` 不在表内，它是兜底而非成员）。 */
    private val groupOfTool: Map<String, String> = buildMap {
        capabilities.forEach { cap -> cap.tools.forEach { put(it, cap.group) } }
    }

    private val byGroup: Map<String, CapabilityGroup> = capabilities.associateBy { it.group }

    /** 按名字推组：先清单，再前缀，最后 [MISC]。 */
    fun groupOf(name: String): String {
        groupOfTool[name]?.let { return it }
        val prefix = name.substringBefore('_', missingDelimiterValue = name)
        return if (byGroup.containsKey(prefix)) prefix else MISC
    }

    /** 组是否常驻（`file` / `misc`）。 */
    fun isPinned(group: String): Boolean = byGroup[group]?.pinned == true

    /** 组的一句话说明；未知组回退成组名。 */
    fun noteOf(group: String): String = byGroup[group]?.note ?: group

    /** 声明过的组名（顺序同 [capabilities]）。 */
    val groups: List<String> get() = capabilities.map { it.group }

    /** 声明过的全部工具名（顺序即工具表顺序）。 */
    val toolNames: List<String> get() = capabilities.flatMap { it.tools }

    /**
     * 双向断言。每一条都对应一种真实发生过的「表与代码不同步」。
     *
     * 1. **工具 → 清单**：每个注册进 ToolRegistry 的工具都必须在清单里有一个组。
     *    漏登不算致命（兜底 misc 让它仍可见），但组索引不会解释它，`__use_tools` 也找不到它。
     * 2. **清单 → 工具**：清单里的每个工具名都必须真有实现——否则模型照清单调用会拿到
     *    `unknown_tool`，而且那条错误还会被模糊匹配指到一个不相干的工具上。
     * 3. **组 → 工具**：每个非兜底组至少要有一个工具，否则模型按索引开组会开到空组。
     * 4. **归组一致**：按 [groupOf] 反推出来的组，必须与它被写进的组一致——这条防的是
     *    「清单写了一套、名字前缀指向另一套」，那种情况下暴露面与组索引会对不上。
     *
     * `misc` 是兜底桶：空着才是理想状态，所以 2/3 都跳过它。
     *
     * @param tools 当前真正注册进 ToolRegistry 的工具（顺序无所谓）。
     *   MCP 动态工具请在调用前过滤掉（它们不在清单里，属于正常情况）。
     */
    fun audit(tools: List<AgentTool>): CapabilityAudit {
        val names = tools.map { it.name }
        val nameSet = names.toHashSet()
        val problems = mutableListOf<String>()

        // ① 工具 → 清单
        names.forEach { name ->
            if (name !in groupOfTool) {
                problems += "工具 `$name` 没有在清单里登记 → 会落进 ${groupOf(name)}，" +
                    "组索引里看不到它、`__use_tools` 也开不到它所在的组。" +
                    "请在 CapabilityManifest.capabilities 的某个组 tools 里加上它。"
            }
        }

        // ② 清单 → 工具
        groupOfTool.keys.forEach { declared ->
            if (declared !in nameSet) {
                problems += "清单声明的 `$declared` 没有对应实现（删了或改名了？）→ " +
                    "模型照清单调用会拿到 unknown_tool。请同步更新清单。"
            }
        }

        // ③ 组 → 工具（misc 是兜底桶，空着才是理想状态）
        capabilities.filter { it.group != MISC }.forEach { cap ->
            if (cap.tools.isEmpty()) {
                problems += "能力组 `${cap.group}`（${cap.note}）没有任何工具 → " +
                    "模型按索引开这个组会开到空组。补上工具，或删掉这个组。"
            }
        }

        // ④ 归组一致：清单位置必须与「按名字推出来的组」一致
        groupOfTool.forEach { (name, group) ->
            val derived = groupOf(name)
            if (derived != group) {
                problems += "`$name` 写在 `${group}` 组，但按名字推出来是 `$derived` → " +
                    "暴露面与组索引会对不上。要么改名字，要么把它挪到 `$derived`。"
            }
        }

        // ⑤ 常驻组不能空（misc 除外）：pin 一个没有内容的组只是白占淘汰名额
        capabilities.filter { it.pinned && it.group != MISC }.forEach { cap ->
            if (cap.tools.isEmpty()) {
                problems += "常驻组 `${cap.group}` 没有任何工具 → 每轮都要把它 pin 回来，纯浪费一个淘汰名额。"
            }
        }

        // 常量自检（表本身写坏了也算问题）
        val dupGroups = capabilities.groupingBy { it.group }.eachCount().filterValues { it > 1 }.keys
        if (dupGroups.isNotEmpty()) problems += "能力组重复声明：${dupGroups.joinToString()}。"
        val dupTools = toolNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dupTools.isNotEmpty()) problems += "工具被重复登记到多个组：${dupTools.joinToString()}。"
        capabilities.filter { it.note.isBlank() }.forEach {
            problems += "能力组 `${it.group}` 没有说明文字 → 组索引里会显示成组名，模型读不懂。"
        }

        return CapabilityAudit(problems)
    }
}
