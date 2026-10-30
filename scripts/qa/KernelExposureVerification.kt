#!/usr/bin/env kotlin
/**
 * 暴露实现层 harness：验证 [com.example.agentkit.core.KernelExposure]。
 *
 * 与 [KernelCoreVerification] 分工：那边验内核的**规则**（三模式、分组、激活语义），
 * 这边验**接上真实工具之后的效果**——用仓库里实际存在的 56 个工具名，量「发给 LLM 的
 * 工具表到底省了多少」，并确认元工具真的能把裁掉的工具捞回来。
 *
 * 运行：bash scripts/qa/run-kernel-exposure-verification.sh
 *
 * 为什么工具用假实现、名字用真名：名字决定分组与描述体量，是成本的主要来源；
 * execute 的行为与本 harness 无关（这里不测工具本身）。描述按现有工具的实际长度写，
 * 避免「拿一句话的工具去比，把差距算小」。
 */

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.KernelExposure
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private var pass = 0
private val fails = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  ✅ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        fails += name
        println("  ❌ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "实际=$actual 期望=$expected")

/** 仓库里实际注册的 56 个工具名（2026-10-30 从 app/src/main/java 全量提取）。 */
private val NAMES = listOf(
    "app_install", "app_launch", "app_list", "app_stop", "app_uninstall",
    "ask_user", "calculator", "clipboard", "container_toolchain", "conversation_search",
    "device_foreground_app", "device_notifications", "edit_file", "github_clone", "github_commit",
    "github_diff", "github_log", "github_status", "http_request", "load_skill",
    "memory_forget", "memory_list", "memory_search", "memory_write", "ocr_image",
    "plan_propose", "read_file", "send_notification", "shell_background", "shell_close",
    "shell_jobs", "shell_run", "spawn_agents", "speak", "todo_read",
    "todo_write", "ui_click", "ui_input", "ui_key", "ui_long_press",
    "ui_screenshot", "ui_swipe", "ui_tap", "ui_tree", "use_skill",
    "web_click", "web_eval", "web_open", "web_search", "web_snapshot",
    "web_type", "workflow_create", "workflow_delete", "workflow_list", "workflow_toggle",
    "write_file",
    // 以下两个刻意用「推不出分组的前缀」，模拟插件 / 用户新增 / 第三方注册的工具。
    // 起因是 T01 harness 用 weather/wordcount 测主循环，被分组裁剪后模型看到「未知工具」。
    // 「我们不认识它」不等于「它不该可见」，所以 misc 必须常驻——这两条就是那条规则的哨兵。
    "weather", "wordcount"
)

private fun fakeTools(): List<AgentTool> = NAMES.map { n ->
    object : AgentTool {
        override val name: String = n
        override val description: String = when (n) {
            // 这三条按仓库里的真实 description 写，用于验证「按中文关键词能搜到英文工具名」
            "ui_screenshot" -> "截取当前屏幕画面，返回图片供视觉模型查看。需要先确认无障碍权限。"
            "read_file" -> "读取指定路径的文本文件内容。路径必须是绝对路径或工作区相对路径。"
            "memory_write" -> "把一条事实写入长期记忆，跨会话可用。重复写入会覆盖同键旧值。"
            else -> "$n：执行 $n 相关操作。适用于需要 $n 的场景；调用前请确认参数有效，" +
                "返回结果可能是文本或结构化数据，失败时会给出原因与重试建议。避免在高频循环中重复调用。"
        }
        override val parameters: JsonSchema = JsonSchema(
            type = "object",
            properties = mapOf(
                "target" to JsonSchema.Property("string", "目标对象"),
                "mode" to JsonSchema.Property("string", "执行模式"),
                "timeout" to JsonSchema.Property("int", "超时毫秒")
            ),
            required = listOf("target")
        )
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "ok")
    }
}

/**
 * 估算「这批工具发给 LLM 要多少字符」：按 OpenAI function-calling 的报文结构拼一遍。
 *
 * 不直接用 `ToolRegistry.descriptors()` 是因为那样只算 schema、漏掉 name/description，
 * 而 description 恰恰是工具表里最长的部分（每个约 100 字）。
 */
/**
 * 手写 JsonSchema → JSON 串。
 *
 * 不用 `JsonSchema.serializer()`：那需要 kotlinx-serialization 编译器插件，而 harness 的
 * 编译单元刻意只走标准 kotlinc（离线可跑、不依赖 Gradle 插件链）。字段顺序与官方序列化一致，
 * 长度差异 < 2%，用来量 token 足够。
 */
private fun schemaJson(s: JsonSchema): String = buildString {
    append("""{"type":""" + "\"" + s.type + "\",\"properties\":{")
    append(s.properties.entries.joinToString(",") { (k, p) ->
        "\"" + k + "\":{\"type\":\"" + p.type + "\",\"description\":\"" + p.description + "\"}"
    })
    append("},\"required\":[")
    append(s.required.joinToString(",") { "\"" + it + "\"" })
    append("]}")
}

private fun payloadChars(tools: List<AgentTool>): Int = buildString {
    for (t in tools) {
        append("{\"type\":\"function\",\"function\":{\"name\":\"")
        append(t.name)
        append("\",\"description\":\"")
        append(t.description)
        append("\",\"parameters\":")
        append(schemaJson(t.parameters))
        append("}}")
    }
}.length

fun main() = runBlocking {
    println("=== 暴露实现层自检（56 个真实工具）===")
    val all = fakeTools()

    // ---------- 1. 分组 ----------
    println("\n[1] 分组：每个工具都必须有组，不能有工具消失")
    val groups = KernelExposure.groupsOf(all)
    println("    " + groups.joinToString("  ") { "${it.first}:${it.second}" })
    eq("分组覆盖全部工具", groups.sumOf { it.second }, all.size)
    val misc = groups.firstOrNull { it.first == "misc" }?.second ?: 0
    eq("无前缀工具归入 misc", misc, 2)
    eq("read_file 归 file", KernelExposure.groupOf("read_file"), "file")
    eq("ui_tap 归 ui", KernelExposure.groupOf("ui_tap"), "ui")
    eq("github_clone 归 github", KernelExposure.groupOf("github_clone"), "github")
    eq("没见过的名字也能归组", KernelExposure.groupOf("never_seen_tool"), "misc")

    // ---------- 2. 三档工具表体量 ----------
    println("\n[2] 三档的工具表体量（真实报文结构）")
    val fullPlan = KernelExposure.plan(all, "openai", forceFull = true, active = mutableSetOf())
    val cloudPlan = KernelExposure.plan(all, "openai", forceFull = false, active = mutableSetOf())
    val localPlan = KernelExposure.plan(all, "local-llama", forceFull = false, active = mutableSetOf())

    val cFull = payloadChars(fullPlan.visible)
    val cCloud = payloadChars(cloudPlan.visible)
    val cLocal = payloadChars(localPlan.visible)
    println("    全量         ${fullPlan.visible.size.toString().padStart(2)} 个 → $cFull 字符 ≈ ${cFull / 2} token")
    println("    云端·分组    ${cloudPlan.visible.size.toString().padStart(2)} 个 → $cCloud 字符 ≈ ${cCloud / 2} token（省 ${100 - cCloud * 100 / cFull}%）")
    println("    本地·分组    ${localPlan.visible.size.toString().padStart(2)} 个 → $cLocal 字符 ≈ ${cLocal / 2} token（省 ${100 - cLocal * 100 / cFull}%）")

    eq("forceFull 就是全量", fullPlan.visible.size, all.size)
    check("云端默认不是全量", cloudPlan.visible.size < all.size)
    check("云端至少省 1/3", cCloud * 3 < cFull * 2, "$cCloud vs $cFull")
    check("本地比全量省 2/3 以上", cLocal * 3 < cFull, "$cLocal vs $cFull")
    // W-102：云端默认只剩 file（+ 兜底 misc）。原先给 file/ui/skill 三组，
    // 实测 ui 9 个工具占工具表近一半，而绝大多数对话一条都用不到。
    check("云端默认只激活 file（+misc）", cloudPlan.activated - "misc" == setOf("file"), "${cloudPlan.activated}")
    check("元工具总在末尾", cloudPlan.visible.takeLast(2).map { it.name }.toSet() == setOf(KernelExposure.USE_TOOLS, KernelExposure.FIND_TOOLS))

    // ---------- 3. 元工具：找回被裁掉的工具 ----------
    println("\n[3] 元工具：裁掉不等于禁用")
    val active = mutableSetOf<String>()
    val before = KernelExposure.plan(all, "openai", false, active)
    check("memory 组初始不可见", before.visible.none { it.name == "memory_write" })

    val useTool = before.visible.first { it.name == KernelExposure.USE_TOOLS }
    val useOut = useTool.execute(buildJsonObject { put("groups", "memory,workflow") })
    check("__use_tools 成功", useOut.ok, useOut.message.take(60))
    val after = KernelExposure.plan(all, "openai", false, active)
    check("启用后 memory_write 可见", after.visible.any { it.name == "memory_write" })
    check("启用后 workflow_list 可见", after.visible.any { it.name == "workflow_list" })
    check("启用后工具表变大但没到全量", after.visible.size > before.visible.size && after.visible.size < all.size)
    println("    memory+workflow 后：${after.visible.size} 个 → ${payloadChars(after.visible)} 字符")

    val findTool = after.visible.first { it.name == KernelExposure.FIND_TOOLS }
    val findOut = findTool.execute(buildJsonObject { put("query", "屏幕") })
    check("__find_tools 按中文描述命中", findOut.ok && findOut.message.contains("ui_screenshot"), findOut.message.take(60))

    // 口语查询（「截图」）与描述（「截取当前屏幕画面」）不是子串关系：不报错，退化为全量清单
    val loose = findTool.execute(buildJsonObject { put("query", "截图") })
    check("找不到时不报错而是给全量清单", loose.ok && loose.code == "find_fallback", loose.message.take(50))
    check("全量清单里含被裁掉的工具名", loose.message.contains("memory_write"))

    val badUse = useTool.execute(buildJsonObject { put("groups", "不存在组") })
    check("未知组失败", !badUse.ok)
    check("未知组给可用清单", badUse.hint?.contains("可用分组") == true)

    val noQuery = findTool.execute(buildJsonObject { })
    check("缺参数失败并给 hint", !noQuery.ok && !noQuery.hint.isNullOrBlank())

    // ---------- 4. 安全侧 ----------
    println("\n[4] 边界")
    eq("元工具是只读的", cloudPlan.visible.filter { it.name.startsWith("__") }.all { it.readOnly }, true)
    check("元工具不需要审批", cloudPlan.visible.filter { it.name.startsWith("__") }.none { it.needsApproval })
    eq("本地通道走 META", localPlan.mode.name, "META")
    check(
        "本地预激活 = file 组 + 常驻的 misc",
        localPlan.activated == setOf("file", "misc"), "${localPlan.activated}"
    )
    // W-102：云端默认面收到只剩 file 之后，与端侧的初始工具面已经一致；
    // 两者的差别只剩 mode（本地 META 更强调「先查再开」）。
    eq("W-102：本地与云端默认工具面一致", cLocal, cCloud)

    // 哨兵：不认识的工具必须默认可见（无论是云端还是端侧）。
    // 这两条对应一个真实踩过的坑：T01 用 weather/wordcount 自定义工具测主循环，
    // 分组裁剪把它们藏起来后，主循环收到的是「未知工具 weather」，端到端断言全红。
    for (case in listOf("云端" to cloudPlan, "本地" to localPlan)) {
        val label = case.first
        val plan = case.second
        val visibleNames = plan.visible.map { it.name }
        check("$label：无前缀工具 weather 默认可见", "weather" in visibleNames)
        check("$label：无前缀工具 wordcount 默认可见", "wordcount" in visibleNames)
    }
    eq("云端走 GROUPED", cloudPlan.mode.name, "GROUPED")
    eq("强制全量走 FULL", fullPlan.mode.name, "FULL")

    // ---------- 5. 粘性激活与滞回淘汰（W-102）----------
    println("\n[5] 粘性激活：开过就留着，撞上限才淘汰")
    val sticky = linkedSetOf<String>()
    KernelExposure.plan(all, "openai", false, sticky)
    check("种子只有核心组", sticky == setOf("file", "misc"), "$sticky")

    fun useGroup(g: String) {
        runBlocking {
            KernelExposure.plan(all, "openai", false, sticky)
                .visible.first { it.name == KernelExposure.USE_TOOLS }
                .execute(buildJsonObject { put("groups", g) })
        }
    }

    // 一次开两个组：参数顺序必须等于 LRU 顺序（先写的更旧、后写的更新）。
    // 这里锁的是一个真 bug：原写法先 addAll 再逐个 remove+add，对「本来不在集合里」
    // 的组是空操作，新组停在 addAll 给的位置，得到 {misc,ui,memory}——与语义相反，
    // 第一次淘汰会把刚开的 memory 砍掉、留下更早的 ui。
    val pair = linkedSetOf<String>()
    runBlocking {
        KernelExposure.plan(all, "openai", false, pair)
            .visible.first { it.name == KernelExposure.USE_TOOLS }
            .execute(buildJsonObject { put("groups", "memory,ui") })
    }
    eq(
        "一次开两组：迭代序 = 参数顺序（memory 旧、ui 新）",
        pair.toList(), listOf("file", "misc", "memory", "ui")
    )

    useGroup("memory")
    check("开 1 组后共 3 个（file+misc+memory）", sticky.size == 3, "$sticky")
    useGroup("ui")
    check("开 2 个可淘汰组仍在限内（不动手）", sticky.size == 4, "$sticky")
    useGroup("shell")
    check(
        "第 3 个可淘汰组触发滞回：降到 1 个",
        sticky.count { it != "file" && it != "misc" } == 1, "$sticky"
    )
    check("最后开的 shell 被保留（先开的 memory/ui 被挤掉）", "shell" in sticky, "$sticky")

    // LRU：重新启用旧组 = 移回尾部，撞上限时淘汰的是别人
    val lru = linkedSetOf("file", "misc", "memory", "ui", "workflow")
    lru.remove("memory"); lru.add("memory")
    val evicted = KernelExposure.enforceCap(lru)
    eq("淘汰最久未用的两个", evicted, listOf("ui", "workflow"))
    check("刚重开（移回尾部）的 memory 留下", lru == setOf("file", "misc", "memory"), "$lru")
    eq(
        "未超限时不动手",
        KernelExposure.enforceCap(linkedSetOf("file", "misc", "ui")),
        emptyList<String>()
    )

    // 持久化遗留：落盘读回的组名可能在代码改版后已不存在（组被删/合并）。
    // 不清理的话它们会一直占着上限名额，让 enforceCap 每轮误判超限、反复淘汰在用的组。
    val stale = linkedSetOf("file", "misc", "gone_group", "ui")
    KernelExposure.plan(all, "openai", false, stale)
    check("失效组名被清理掉", "gone_group" !in stale, "$stale")
    check("清理不误伤在用组", "ui" in stale && "file" in stale, "$stale")

    // ---------- 6. 结果 ----------

    // 结果行必须含 "0 失败"：run-all.sh 靠这个字样判 PASS，
    // 写成「0 项失败」不匹配它的正则，会被当成「跑完没有通过标记」判 FAIL。
    println("\n=== 结果：$pass 项通过 / " + if (fails.isEmpty()) "0 失败" else "${fails.size} 条断言失败" + " ===")
    if (fails.isNotEmpty()) {
        fails.forEach { println("  失败：$it") }
        kotlin.system.exitProcess(1)
    }
}
