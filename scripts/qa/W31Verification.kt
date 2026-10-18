import com.example.agentkit.core.agent.SubAgentLogic
import com.example.agentkit.core.agent.SubAgentOutcome

/**
 * W-31 harness：并行子代理的纯逻辑。
 *
 * 覆盖：
 *  - 参数校验（空/超量/空指令/超长截断/自动标题）；
 *  - 结论提取（末尾空消息回退、超长截断、全空兜底）；
 *  - 结果汇总（成功失败计数、耗时、超预算省略）；
 *  - 并发归一 + 只读白名单边界（不能有写工具、不能有 spawn_agents 自身）。
 *
 * 纯 JVM：不加载 Android，不需要网络与真实模型。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

fun main() {
    println("== W-31 harness：并行子代理 ==")

    // ——————————————— 参数校验 ———————————————
    check("两个正常任务 → Ok，标题与指令都保留") {
        val parsed = SubAgentLogic.parseTasks(
            listOf("查定义" to "在 src 下找 Kotlin 版本号定义在哪", "搜资料" to "搜 Kotlin 2.0 新特性")
        )
        assertTrue("应 Ok，实际 $parsed", parsed is SubAgentLogic.Parse.Ok)
        val tasks = (parsed as SubAgentLogic.Parse.Ok).tasks
        assertTrue("应有 2 个", tasks.size == 2)
        assertTrue("标题保留", tasks[0].title == "查定义")
    }
    check("空列表 → empty_tasks") {
        val parsed = SubAgentLogic.parseTasks(emptyList())
        assertTrue("应 Err", parsed is SubAgentLogic.Parse.Err)
        assertTrue("错误码", (parsed as SubAgentLogic.Parse.Err).code == "empty_tasks")
    }
    check("超过 3 个 → too_many_tasks") {
        val parsed = SubAgentLogic.parseTasks(
            (1..4).map { "任务$it" to "做第 $it 件事" }
        )
        assertTrue("应 Err", parsed is SubAgentLogic.Parse.Err)
        assertTrue("错误码", (parsed as SubAgentLogic.Parse.Err).code == "too_many_tasks")
        assertTrue("提示里要说明上限", parsed.message.contains("3"))
    }
    check("指令全为空白 → empty_tasks（不是「跑了 3 个空任务」）") {
        val parsed = SubAgentLogic.parseTasks(listOf("a" to "   ", "b" to ""))
        assertTrue("应 Err", parsed is SubAgentLogic.Parse.Err)
        assertTrue("错误码", (parsed as SubAgentLogic.Parse.Err).code == "empty_tasks")
    }
    check("标题为空 → 用指令前 20 字自动生成") {
        val parsed = SubAgentLogic.parseTasks(
            listOf("" to "把 src/main 下面所有 Kotlin 文件里的 TODO 列出来给我")
        )
        val task = (parsed as SubAgentLogic.Parse.Ok).tasks.first()
        assertTrue("标题非空", task.title.isNotEmpty())
        assertTrue("标题应带省略号（原文超过 20 字）", task.title.endsWith("…"))
        assertTrue("标题不超过 21 字", task.title.length <= 21)
    }
    check("自动标题里的换行被压成一行") {
        val parsed = SubAgentLogic.parseTasks(listOf("" to "第一行要做的事\n第二行补充说明\n第三行"))
        val task = (parsed as SubAgentLogic.Parse.Ok).tasks.first()
        assertTrue("不应含换行", !task.title.contains('\n'))
    }
    check("超长指令截断到 2000 字") {
        val parsed = SubAgentLogic.parseTasks(listOf("长" to "x".repeat(5_000)))
        val task = (parsed as SubAgentLogic.Parse.Ok).tasks.first()
        assertTrue("应截断到 2000，实际 ${task.instruction.length}", task.instruction.length == 2_000)
    }

    // ——————————————— 结论提取 ———————————————
    check("结论取最后一条非空 assistant 文本") {
        val got = SubAgentLogic.extractConclusion(listOf("中间过程", "最终结论在这里", "   "))
        assertTrue("应回退到最后非空条，实际 $got", got == "最终结论在这里")
    }
    check("全空 → 明确的占位文本（不是空串）") {
        val got = SubAgentLogic.extractConclusion(listOf("", "  "))
        assertTrue("应有占位", got.contains("没有给出文字结论"))
    }
    check("超长结论截断并标注") {
        val got = SubAgentLogic.extractConclusion(listOf("y".repeat(3_000)))
        assertTrue("应截断", got.length < 3_000)
        assertTrue("应有截断提示", got.contains("已截断"))
    }
    check("空列表不崩") {
        assertTrue("应有占位", SubAgentLogic.extractConclusion(emptyList()).isNotEmpty())
    }

    // ——————————————— 结果汇总 ———————————————
    check("汇总带成功/失败计数、标题与耗时") {
        val text = SubAgentLogic.summarize(
            listOf(
                SubAgentOutcome("查定义", true, "定义在 Config.kt:42", 3_000),
                SubAgentOutcome("搜资料", false, "超时（240 秒）", 240_000)
            )
        )
        assertTrue("应有计数", text.contains("成功 1") && text.contains("失败 1"))
        assertTrue("应有标题", text.contains("查定义") && text.contains("搜资料"))
        assertTrue("成功标记", text.contains("✓"))
        assertTrue("失败标记", text.contains("✗"))
        assertTrue("应有耗时秒数", text.contains("（3s）"))
        assertTrue("失败要标「失败：」", text.contains("失败：超时"))
    }
    check("空结果 → 占位，不返回空串") {
        assertTrue("应有占位", SubAgentLogic.summarize(emptyList()).contains("没有子代理结果"))
    }
    check("超预算：后面的任务只留标题与说明") {
        val big = "z".repeat(2_000)
        val text = SubAgentLogic.summarize(
            listOf(
                SubAgentOutcome("一", true, big, 1_000),
                SubAgentOutcome("二", true, big, 1_000),
                SubAgentOutcome("三", true, big, 1_000)
            ),
            maxChars = 2_500
        )
        assertTrue("应触发省略提示", text.contains("汇总已达长度上限"))
        assertTrue("总长应受控，实际 ${text.length}", text.length <= 4_000)
        assertTrue("至少标题都还在", text.contains("一") && text.contains("三"))
    }

    // ——————————————— 并发与白名单 ———————————————
    check("并发归一：不超过任务数、不超过上限、至少 1") {
        assertTrue("0 任务 → 0", SubAgentLogic.clampConcurrency(3, 0) == 0)
        assertTrue("要求 5 → 3", SubAgentLogic.clampConcurrency(5, 5) == 3)
        assertTrue("要求 0 → 1", SubAgentLogic.clampConcurrency(0, 2) == 1)
        assertTrue("任务 1 → 1", SubAgentLogic.clampConcurrency(3, 1) == 1)
    }
    check("白名单只含只读工具") {
        val mustHave = listOf("read_file", "web_search", "ui_tree", "ocr_image")
        mustHave.forEach { assertTrue("缺 $it", it in SubAgentLogic.READ_ONLY_TOOLS) }
        val mustNot = listOf(
            "write_file", "edit_file", "shell_run", "ui_tap", "app_install",
            "app_uninstall", "spawn_agents", "plan_propose"
        )
        mustNot.forEach { assertTrue("不应包含 $it", it !in SubAgentLogic.READ_ONLY_TOOLS) }
    }
    check("系统提示强调只读与结论格式") {
        val prompt = SubAgentLogic.systemPrompt("查定义")
        assertTrue("应含子代理标题", prompt.contains("查定义"))
        assertTrue("应强调只读", prompt.contains("只做只读操作"))
        assertTrue("应要求先给结论", prompt.contains("先说结论"))
        assertTrue("应允许「没找到」", prompt.contains("没找到"))
    }
    check("用户输入有明确边界（<<< >>>）") {
        val text = SubAgentLogic.userPrompt("看看这个文件")
        assertTrue("应有上边界", text.startsWith("<<<"))
        assertTrue("应有下边界", text.endsWith(">>>"))
    }

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
