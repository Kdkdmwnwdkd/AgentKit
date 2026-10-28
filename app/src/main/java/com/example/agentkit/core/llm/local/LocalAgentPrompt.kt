package com.example.agentkit.core.llm.local

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema

/**
 * 本地小模型（llama.cpp）的 Agent 系统提示——**精简版**。
 *
 * ## 为什么要有这个文件
 *
 * 同一台手机上实测过两组数字：
 *  - 只发「你好」（7 token）：首字 4.8 秒；
 *  - 发「系统提示 + 7 个工具 + 设备上下文」（1086 token）：180 秒连第一块 prefill 都没算完。
 *
 * 1086 token 里，真正干活的内容不到三分之一。剩下的是三样东西：
 *  1. **中文写成的长系统提示**（600 字 ≈ 380 token）——中文约 1.5 字/token，同样的意思
 *     用英文写只要 1/3 的 token；
 *  2. **每个工具的 48 字中文描述 + 参数类型表**（7 个工具约 300 token）；
 *  3. **技能的完整 XML 清单**（三项技能带描述约 200 token）。
 *
 * 参考同类产品（IRIDIS，Apache-2.0）的做法后确认：它 22 个工具的整套系统提示只有
 * 约 570 token，靠的就是「英文写、每个工具一行、只列名字不列 schema」。这里按同样
 * 原理重写，不复制它的文案——我们的工具、参数、沙箱语义都和它不同。
 *
 * ## 三条硬约束
 *
 * 1. **只描述「够模型判断该不该用」的信息**。参数名照抄 schema（保证模型填得对），
 *    但不写类型、不写 JSON Schema、不写"这是一个用于…的工具"这种废话。
 * 2. **工具顺序固定**（按 [order]，未知工具排最后）。系统提示逐字节不变，KV 前缀才能
 *    命中——本地每轮省下的就是这几百 token 的重新 prefill。
 * 3. **不出现中文**。模型要用中文回答，但提示词本身用英文，纯粹为了 token 经济。
 *
 * 目标：7 个工具约 250 token，全量 20 个工具也不超过 450 token。
 */
object LocalAgentPrompt {

    /**
     * 工具的一句话职责（英文）。**只写「做什么」，不写怎么做**——怎么做在工具实现里，
     * 模型不需要知道。
     *
     * 没列在这里的工具（未来新增的）会退化成「`name(参数)` 一行、没有解释」，
     * 仍然可用，只是模型得靠名字猜。新增工具时补一行即可。
     */
    private val HINTS: Map<String, String> = mapOf(
        "read_file" to "read a workspace file as text",
        "write_file" to "write text into a workspace file (overwrite)",
        "edit_file" to "replace exact text inside a file",
        "list_dir" to "list files in a workspace folder",
        "search_files" to "find files by name or by content",
        "http_request" to "fetch a URL and return the body",
        "ui_tree" to "read the current screen: text and bounds",
        "ui_tap" to "tap a point on screen (x,y from ui_tree)",
        "clipboard" to "read or write the phone clipboard",
        "use_skill" to "load an installed skill's instructions",
        "shell_run" to "run a shell command and get its output",
        "memory_write" to "remember a fact about the user",
        "memory_search" to "recall remembered facts",
        "app_launch" to "open an installed app by name",
        "app_list" to "list installed apps",
        "send_notification" to "post a notification",
        "ui_screenshot" to "take a screenshot (needs a vision model)",
        "ui_input" to "type text into the focused field",
        "ui_swipe" to "swipe between two points",
        "ui_key" to "press a system key (back/home)",
        "ui_click" to "click a widget by its text or id",
        "spawn_agents" to "run sub-tasks in parallel",
    )

    /**
     * 固定顺序（= [com.example.agentkit.core.ToolExposurePolicy.LOCAL_CORE_TOOLS] 的语义顺序，
     * 外加常用云端工具）。顺序即「重要度」，也保证同一组工具每轮渲染结果逐字节一致。
     */
    private val ORDER: List<String> = listOf(
        "read_file", "write_file", "edit_file", "list_dir", "search_files",
        "http_request", "ui_tree", "ui_tap", "clipboard", "use_skill",
        "shell_run", "memory_write", "memory_search",
        "app_launch", "app_list", "send_notification",
        "ui_screenshot", "ui_input", "ui_swipe", "ui_key", "ui_click",
    )

    /** 单条工具行的长度上限（防止某个工具的描述把预算吃光，也防止截断切在半句话上）。 */
    private const val LINE_MAX_CHARS = 120

    /**
     * 构造系统提示。
     *
     * @param tools 本轮真正暴露给模型的工具（已按 provider 裁剪过）
     * @param skills 已安装技能的名字（只列名字，不列描述——描述要模型开口问了才给）
     */
    fun build(tools: List<AgentTool>, skills: List<String> = emptyList()): String = buildString {
        appendLine("You are 北辰, an AI agent running on the user's Android phone.")
        appendLine()
        appendLine("# Rules")
        appendLine("- Answer in Chinese. Be short. Never claim an action you did not take.")
        appendLine("- Plain chat, or a question you can answer yourself: reply in text, no tool call.")
        appendLine("- Need a file, the screen, the network or a skill: call ONE tool, then stop and wait.")
        appendLine("- When the tool results are enough, answer the user; when a call fails, fix it once or say so.")
        appendLine()
        appendLine("# Tool call")
        appendLine("Your whole reply must be this JSON and nothing else (no code fence, no extra words):")
        appendLine("""{"tool": "read_file", "arguments": {"path": "notes.md"}}""")
        appendLine("The result comes back as a separate message. Never write that message yourself.")
        appendLine()
        if (tools.isEmpty()) {
            appendLine("# Tools")
            appendLine("None are enabled. Answer in plain text.")
        } else {
            appendLine("# Tools (? = optional)")
            sorted(tools).forEach { appendLine(line(it)) }
        }
        if (skills.isNotEmpty() && tools.any { it.name == "use_skill" }) {
            appendLine()
            appendLine("# Skills (call use_skill with one of these names)")
            appendLine(skills.take(12).joinToString(", ") { it.take(24) })
        }
    }.trimEnd()

    /**
     * 固定顺序排序：先按 [ORDER] 的位置，未登记的按名字排（保证同样的工具集每轮输出一致，
     * 不会因为注册顺序变化而打断 KV 前缀）。
     */
    private fun sorted(tools: List<AgentTool>): List<AgentTool> = tools.sortedWith(
        compareBy({ ORDER.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name })
    )

    /**
     * 一个工具渲染成一行：`name(必填, 可选?): 一句话`。
     *
     * 参数名**原样取自 schema**——模型填参数时逐字对齐，比任何自然语言描述都可靠。
     * 枚举参数把取值摊开（`action:get|set`），因为小模型猜不到取值集合。
     */
    private fun line(tool: AgentTool): String {
        val params = params(tool.parameters)
        val hint = HINTS[tool.name].orEmpty()
        val head = if (params.isEmpty()) "${tool.name}()" else "${tool.name}($params)"
        val text = if (hint.isEmpty()) "- $head" else "- $head: $hint"
        return if (text.length <= LINE_MAX_CHARS) text else text.take(LINE_MAX_CHARS - 1) + "…"
    }

    private fun params(schema: JsonSchema): String =
        schema.properties.entries.joinToString(", ") { (name, prop) ->
            val enums = prop.enum
            val rendered = if (!enums.isNullOrEmpty()) {
                "$name:${enums.joinToString("|") { it.take(12) }}"
            } else {
                name
            }
            if (name in schema.required) rendered else "$rendered?"
        }
}
