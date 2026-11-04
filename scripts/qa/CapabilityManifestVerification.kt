#!/usr/bin/env kotlin
/**
 * 能力清单 harness：验证 [com.example.agentkit.core.capability.CapabilityManifest] 的四条双向断言。
 *
 * ## 为什么这个 harness 值得存在
 *
 * 分组表是「人记得更新」的那类东西：新增工具忘了登记 → 静默落进 misc；工具改名 →
 * 补丁表留死条目；工具删光 → 组索引里挂一个空组。三种都不会报错，只会让模型
 * 多绕一圈（或永远找不到某组工具）。所以这里既验**正向**（真实工具集必须零问题），
 * 也验**反向**（每种不同步都必须被抓到，且指名道姓）——只验正向的话，一个永远返回
 * 空问题的 audit 也能「通过」。
 *
 * ## 工具名从哪来
 *
 * 不写死清单（写死就会随代码漂移，变成第二个需要人同步的表）：直接扫
 * `app/src/main/java` 下所有 `override val name = "..."`，得到当前真实工具名集合。
 * 于是「新增工具忘了登记组」这件事会在下一次跑 harness 时自己冒出来。
 *
 * 运行：bash scripts/qa/run-capability-manifest-verification.sh
 */

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.KernelExposure
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.capability.CapabilityManifest
import kotlinx.serialization.json.JsonObject
import java.io.File

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

/** 只需名字的工具：审计只看 name，execute/参数与本题无关。 */
private fun stub(name: String): AgentTool = object : AgentTool {
    override val name: String = name
    override val description: String = "$name 的占位描述"
    override val parameters: JsonSchema = JsonSchema()
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "")
}

/** 扫源码拿真实工具名（`override val name = "xxx"`；动态名如 mcpWireName(...) 自然不匹配）。 */
private fun realToolNames(srcRoot: File): List<String> {
    val re = Regex("""override val name(?::\s*String)?\s*=\s*"([A-Za-z0-9_]+)"""")
    return srcRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { re.findAll(it.readText(Charsets.UTF_8)).map { m -> m.groupValues[1] } }
        .toList()
        .distinct()
        .sorted()
}

fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "." })
    val src = File(root, "app/src/main/java")
    println("== 能力清单（P3-1）==")

    // —— 1. 清单本身的常量自检 ——
    println("\n[1] 清单常量")
    val groups = CapabilityManifest.groups
    check("组名唯一", groups.size == groups.toSet().size, "共 ${groups.size} 组")
    check("工具不重复", CapabilityManifest.toolNames.size == CapabilityManifest.toolNames.toSet().size,
        "共 ${CapabilityManifest.toolNames.size} 个")
    check("每组都有说明", CapabilityManifest.capabilities.all { it.note.isNotBlank() })
    eq("常驻组只有 file/misc", groups.filter { CapabilityManifest.isPinned(it) }.toSet(), setOf("file", "misc"))
    eq("未归类兜底组常驻", CapabilityManifest.isPinned(CapabilityManifest.MISC), true)
    eq("file 组常驻", CapabilityManifest.isPinned("file"), true)
    eq("ui 组不常驻", CapabilityManifest.isPinned("ui"), false)

    // —— 2. 归组规则 ——
    println("\n[2] 归组规则（补丁 → 前缀 → misc）")
    eq("read_file → file", CapabilityManifest.groupOf("read_file"), "file")
    eq("ask_user → ui", CapabilityManifest.groupOf("ask_user"), "ui")
    eq("http_request → net（前缀推不出，靠清单）", CapabilityManifest.groupOf("http_request"), "net")
    eq("speak → media（前缀推不出，靠清单）", CapabilityManifest.groupOf("speak"), "media")
    eq("shell_run → shell", CapabilityManifest.groupOf("shell_run"), "shell")
    eq("github_commit → github", CapabilityManifest.groupOf("github_commit"), "github")
    eq("清单收录全部静态工具", CapabilityManifest.toolNames.size, 56)
    eq("weather 推不出来 → misc", CapabilityManifest.groupOf("weather"), "misc")
    check("说明文案不等于组名", CapabilityManifest.noteOf("ui") != "ui", CapabilityManifest.noteOf("ui"))

    // —— 3. KernelExposure 与清单同源（委托而不是各写一份） ——
    println("\n[3] KernelExposure 委托一致")
    val names = realToolNames(src)
    check("扫到真实工具名", names.size >= 40, "共 ${names.size} 个")
    val mismatch = names.filter { KernelExposure.groupOf(it) != CapabilityManifest.groupOf(it) }
    check("groupOf 与清单逐名一致", mismatch.isEmpty(), mismatch.take(3).joinToString())
    check(
        "isPinned 与清单一致",
        listOf("file", "misc", "ui", "shell").all { KernelExposure.isPinned(it) == CapabilityManifest.isPinned(it) }
    )
    check("noteOf 与清单一致", KernelExposure.noteOf("ui") == CapabilityManifest.noteOf("ui"))

    // —— 4. 正向：真实工具集审计必须零问题 ——
    println("\n[4] 真实工具集审计（${names.size} 个）")
    val live = CapabilityManifest.audit(names.map { stub(it) })
    check("零问题", live.ok, if (live.ok) "" else live.problems.joinToString("；"))
    check("空着的 misc 不算空组（兜底桶）", live.problems.none { it.contains("`misc`") })

    // —— 5. 反向：四种不同步都要被抓到，且指出是谁 ——
    println("\n[5] 反向（每条断言都能单独失败）")
    val unknown = CapabilityManifest.audit(names.map { stub(it) } + stub("demo_widget"))
    check(
        "① 新工具未登记 → 被抓",
        unknown.problems.any { it.contains("demo_widget") },
        unknown.problems.firstOrNull().orEmpty()
    )

    val noWeb = CapabilityManifest.audit(names.filterNot { it.startsWith("web_") }.map { stub(it) })
    val webProblems = noWeb.problems.filter { it.contains("`web_") }
    check(
        "② 整组工具删光 → 逐个点名（不是只报一句空组）",
        webProblems.size == 6 && webProblems.all { it.contains("没有对应实现") },
        "命中 ${webProblems.size} 条"
    )

    // 验收口径原文：「删一个工具不改清单 → harness 红」。这里就是那个场景。
    val noSpeak = CapabilityManifest.audit(names.filterNot { it == "speak" }.map { stub(it) })
    check(
        "③ 删了工具没改清单 → 被抓",
        noSpeak.problems.any { it.contains("`speak`") },
        noSpeak.problems.firstOrNull().orEmpty()
    )

    val noFile = CapabilityManifest.audit(
        names.filterNot { it in setOf("read_file", "write_file", "edit_file") }.map { stub(it) }
    )
    check(
        "④ 常驻组工具全删 → 三个都点名",
        listOf("read_file", "write_file", "edit_file").all { n ->
            noFile.problems.any { it.contains("`$n`") }
        },
        noFile.problems.size.toString()
    )

    // —— 6. 单点纪律（静态） ——
    println("\n[6] 单点（静态）")
    val exposure = File(src, "com/example/agentkit/core/KernelExposure.kt").readText(Charsets.UTF_8)
    check("KernelExposure 不再自持分组表", !exposure.contains("GROUP_NOTES =") && !exposure.contains("GROUP_PATCH ="))
    check("KernelExposure 的 groupOf 委托给清单", exposure.contains("CapabilityManifest.groupOf("))

    val vm = File(src, "com/example/agentkit/ui/ChatViewModel.kt").readText(Charsets.UTF_8)
    val auditAt = vm.indexOf("CapabilityManifest.audit(")
    val mcpAt = vm.indexOf("registerMcpTools()")
    check("启动时跑一次审计", auditAt > 0)
    check("审计在 MCP 注册之前（动态工具不参与）", auditAt in 1 until mcpAt, "audit=$auditAt mcp=$mcpAt")

    println("\n==== 结果：PASS=$pass FAIL=${fails.size} ====")
    if (fails.isNotEmpty()) {
        println("失败项：")
        fails.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
