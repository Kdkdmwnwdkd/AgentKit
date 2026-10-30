#!/usr/bin/env kotlin
/**
 * 内核 harness：验证 [com.example.agentkit.core.kernel] 六件套的行为。
 *
 * 与项目其它 QA 一致：纯 JVM、零 Android 依赖、不联网，直接跑断言。
 * 运行：bash scripts/qa/run-kernel-core-verification.sh
 *
 * 覆盖（46 项断言）：
 *  1. 暴露三模式的工具表体量（FULL / GROUPED / META），以及 52 工具真实规模的差距
 *  2. 提示分段的稳定前缀：TURN 变不影响指纹、FROZEN 变必然改指纹
 *  3. 预算折叠：早期对话折成一条摘要、保留系统提示与最新轮、超长单条标记截断
 *  4. 主循环：META 模式下 find → call 两步走通、步数正确、工具真被调用
 *  5. 失败路径：编造工具名 / 未激活组 / 审批拒绝 / 参数不可读 / 工具抛异常
 *  6. 预算换算：上下文窗口 → 字符上限
 *
 * 注意：这里是「行为断言」，不是「覆盖率」。每条都对应一个真会出问题的场景：
 * 加断言前先问「不写这条，出问题时会以什么形式表现出来」。
 */

import com.example.agentkit.core.kernel.*
import kotlinx.coroutines.runBlocking

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

// ---------- 假工具 ----------

private fun tool(
    name: String,
    group: String,
    danger: Danger = Danger.WRITE,
    approval: (Args) -> Approval = { Approval.INHERIT },
    hits: MutableList<String>? = null
) = ToolSpec(
    name = name,
    group = group,
    danger = danger,
    brief = { "说明:$name" },
    params = { listOf(Param("path")) },
    approval = approval,
    run = { args ->
        hits?.add(name)
        Outcome.ok("$name ok ${args.raw}")
    }
)

private fun sampleTools(hits: MutableList<String>? = null): List<ToolSpec> = listOf(
    tool("read_file", "file", Danger.READ, hits = hits),
    tool("write_file", "file", hits = hits),
    tool("list_dir", "file", Danger.READ, hits = hits),
    tool("http_get", "net", Danger.READ, hits = hits),
    tool("http_post", "net", hits = hits),
    tool("ui_tap", "ui", hits = hits),
    tool("ui_swipe", "ui", hits = hits),
    tool("ui_tree", "ui", Danger.READ, hits = hits),
    tool("shell_run", "shell", Danger.DESTRUCTIVE, approval = { a ->
        if (a.opt("cmd")?.startsWith("rm") == true) Approval.ASK else Approval.ALLOW
    }, hits = hits),
    tool("memory_write", "memory", hits = hits)
)

private val notes = mapOf(
    "file" to "读写文件", "net" to "联网", "ui" to "屏幕操作",
    "shell" to "命令行", "memory" to "记忆"
)

/**
 * 接近真实规模的工具集：52 个工具 / 11 个组，描述与参数按 AgentKit 现有工具的体量写
 * （description 约 100 字，3 个参数）。用来量「三种暴露模式差多少」，而不是拿 10 个
 * 一句话工具去比，那会把差距算小。
 */
private fun realisticToolset(): List<ToolSpec> {
    val groups = listOf(
        "file", "net", "ui", "shell", "memory", "skill", "browser",
        "automation", "terminal", "workflow", "media"
    )
    val names = listOf(
        "read_file", "write_file", "list_dir", "move_file", "delete_file",
        "http_get", "http_post", "http_download", "web_search", "fetch_page",
        "ui_tap", "ui_swipe", "ui_long_press", "ui_input", "ui_key", "ui_tree", "ui_click", "ui_screenshot",
        "shell_run", "shell_close", "shell_list", "shell_kill",
        "memory_write", "memory_search", "memory_list", "memory_forget",
        "use_skill", "list_skills", "install_skill", "remove_skill",
        "browser_open", "browser_click", "browser_type", "browser_back", "browser_read",
        "auto_start", "auto_stop", "auto_record", "auto_replay",
        "term_open", "term_write", "term_read", "term_close",
        "workflow_run", "workflow_list", "workflow_stop",
        "image_crop", "image_ocr", "audio_record", "audio_play", "clipboard", "notify"
    )
    return names.mapIndexed { i, n ->
        ToolSpec(
            name = n,
            group = groups[i % groups.size],
            danger = if (i % 5 == 0) Danger.READ else Danger.WRITE,
            brief = {
                "执行 $n 操作。适用于需要$n 的场景；调用前请确认参数有效，返回结果可能是文本或结构化数据，" +
                    "失败时会给出原因与重试建议。注意不要在高频循环中重复调用。"
            },
            params = {
                listOf(
                    Param("target", "string", true, "目标对象"),
                    Param("mode", "string", false, "执行模式"),
                    Param("timeout", "int", false, "超时毫秒")
                )
            },
            run = { Outcome.ok("ok") }
        )
    }
}

// ---------- 各项自检 ----------

private fun checkExposure() = runBlocking {
    println("\n[1] 工具暴露：三档模型的工具表成本")
    val tools = sampleTools()
    val full = Surface.of(tools, ExposurePolicy.forStrength(Strength.LARGE), notes)
    val grouped = Surface.of(tools, ExposurePolicy.forStrength(Strength.SMALL), notes)
    val meta = Surface.of(tools, ExposurePolicy.forStrength(Strength.TINY), notes)

    val large = ModelRef("openai", "gpt", 128_000, Strength.LARGE)
    val small = ModelRef("local", "qwen-4b", 8_192, Strength.SMALL)
    val tiny = ModelRef("local", "qwen-1.5b", 4_096, Strength.TINY)

    val cFull = full.chars(large)
    val cGrouped0 = grouped.chars(small)
    val cMeta = meta.chars(tiny)

    println("    全量 ${full.visible().size} 个工具 → $cFull 字符")
    println("    分组未激活 ${grouped.visible().size} 个 → $cGrouped0 字符")
    println("    元工具 ${meta.visible().size} 个 → $cMeta 字符")

    eq("FULL 模式暴露全部", full.visible().size, tools.size)
    check("META 模式不直接暴露业务工具", meta.visible().isEmpty())
    check("小工具集下 META 也便宜", cMeta < cFull, "$cMeta vs $cFull")

    println("\n     —— 真实规模（52 个工具 / 11 组）——")
    val real = realisticToolset()
    val rFull = Surface.of(real, ExposurePolicy.forStrength(Strength.LARGE), notes)
    val rGrouped = Surface.of(real, ExposurePolicy.forStrength(Strength.SMALL), notes)
    val rMeta = Surface.of(real, ExposurePolicy.forStrength(Strength.TINY), notes)
    val f1 = rFull.chars(large)
    val g1 = rGrouped.chars(small)
    val m1 = rMeta.chars(tiny)
    val gAct = rGrouped.activate("file").chars(small)
    println("    全量          $f1 字符  ≈ ${f1 / 2} token（现状：云端一路全发）")
    println("    分组·未激活   $g1 字符  ≈ ${g1 / 2} token")
    println("    分组·激活一组 $gAct 字符  ≈ ${gAct / 2} token  (比全量省 ${100 - gAct * 100 / f1}%)")
    println("    元工具        $m1 字符  ≈ ${m1 / 2} token  (比全量省 ${100 - m1 * 100 / f1}%)")

    check("52 个工具时 META 不到全量的 1/10", m1 * 10 < f1, "$m1 vs $f1")
    check("52 个工具时分组未激活不到全量的 1/4", g1 * 4 < f1, "$g1 vs $f1")
    check("分组激活一组后仍至少省一半", gAct * 2 < f1, "$gAct vs $f1")
    check("分组一次只多一组的量", gAct - g1 < f1 / 3, "增加 ${gAct - g1} 字符")

    val activated = grouped.activate("file")
    eq("激活 file 组后可见 3 个", activated.visible().size, 3)
    eq("激活后仍在 GROUPED", activated.policy.mode, ExposureMode.GROUPED)
    check("激活后工具表变长", activated.chars(small) > cGrouped0)
    check("未激活组不进可见列表", activated.visible().none { it.group == "net" })
    check("重复激活幂等", activated.activate("file") === activated)
    check("激活不存在的组不变", activated.activate("nope") === activated)
    check("执行侧仍能找到未暴露工具", activated.find("http_get") != null)
}

private fun checkPrompt() {
    println("\n[2] 提示分段：稳定前缀是否真的稳定")
    val base = listOf(
        Section("identity", "你是北辰", Stability.FROZEN),
        Section("rules", "规则一……", Stability.FROZEN),
        Section("persona", "人格设定", Stability.SESSION)
    )
    val p1 = PromptAssembler.assemble(base + Section("turn", "时间 10:00 计划 A", Stability.TURN))
    val p2 = PromptAssembler.assemble(base + Section("turn", "时间 10:05 计划 B", Stability.TURN))
    val p3 = PromptAssembler.assemble(
        listOf(Section("identity", "你是北辰（改）", Stability.FROZEN)) + base.drop(1) +
            Section("turn", "时间 10:00 计划 A", Stability.TURN)
    )

    eq("TURN 变化不影响指纹", p1.signature, p2.signature)
    eq("TURN 变化不影响稳定前缀长度", p1.frozenChars, p2.frozenChars)
    check("FROZEN 变化会改变指纹", p3.signature != p1.signature)
    check("TURN 段在最后", p2.text.endsWith("计划 B"))
    check("空段被跳过", !p1.text.contains("\n\n\n"))
    println("    稳定前缀 ${p1.frozenChars} 字符，指纹 ${p1.signature}")

    val withBlank = PromptAssembler.assemble(
        listOf(Section("a", "A", Stability.FROZEN), Section("b", "   ", Stability.FROZEN))
    )
    eq("空白段不产生额外换行", withBlank.text, "A")
}

private suspend fun checkBudget() {
    println("\n[3] 预算：早期对话折叠而不是硬砍")
    val msgs = mutableListOf<Msg>()
    msgs += Msg.system("系统提示")
    repeat(20) { i ->
        msgs += Msg.user("第 $i 轮：帮我处理第 $i 件事" + "x".repeat(200))
        msgs += Msg.assistant("好的，第 $i 轮完成")
        msgs += Msg.tool("c$i", "read_file", "第 $i 轮结果" + "y".repeat(200))
    }
    val fitted = ContextFitter.fit(msgs, Budget(maxPromptChars = 3_000, keepRecent = 4, minKeep = 1))
    println("    原 ${msgs.size} 条 → ${fitted.messages.size} 条，折叠 ${fitted.foldedCount} 条，${fitted.chars} 字符")

    check("发生了折叠", fitted.foldedCount > 0)
    check("保留了系统提示", fitted.messages.first().role == Role.SYSTEM && fitted.messages.first().text == "系统提示")
    check("有折叠说明", fitted.foldedNote?.contains("已折叠") == true)
    check("最新一条还在", fitted.messages.last().text.contains("第 19 轮"))
    check("折叠说明里保留了最初任务", fitted.foldedNote!!.contains("第 0 轮"))

    val tiny = ContextFitter.fit(listOf(Msg.system("s"), Msg.user("u".repeat(9999))), Budget(100, 2, 1))
    check("单条超预算时标记截断", tiny.truncated)
}

private class ScriptedCaller(private val script: MutableList<Reply>) : ModelCaller {
    val seen = mutableListOf<Request>()
    override suspend fun call(req: Request): Reply {
        seen += req
        return script.removeFirstOrNull() ?: Reply(text = "(脚本用尽)")
    }
}

private fun checkKernel() = runBlocking {
    println("\n[4] 主循环：META 模式下的一次完整两步工具调用")
    val hits = mutableListOf<String>()
    val caller = ScriptedCaller(
        mutableListOf(
            Reply(calls = listOf(Call("1", MetaTool.FIND, MapArgs.of("query" to "read_file")))),
            Reply(calls = listOf(Call("2", MetaTool.CALL, MapArgs.of("name" to "read_file", "args" to "{}")))),
            Reply(text = "读完了，文件里是配置项")
        )
    )
    val events = mutableListOf<Event>()
    val result = Kernel(caller).run(
        Turn(
            model = ModelRef("local", "qwen", 4_096, Strength.TINY),
            prompt = PromptAssembler.assemble(listOf(Section("id", "你是助手", Stability.FROZEN))),
            history = listOf(Msg.user("读一下配置")),
            tools = sampleTools(hits),
            policy = ExposurePolicy.forStrength(Strength.TINY),
            groupNotes = notes
        )
    ) { events += it }

    check("正常结束", result is Result.Done, result.toString())
    eq("最终文本", (result as Result.Done).text, "读完了，文件里是配置项")
    eq("步数", result.steps, 3)
    eq("工具真被执行一次", hits, listOf("read_file"))
    eq("每步都带工具表", events.filterIsInstance<Event.Step>().size, 3)
    val firstStep = events.filterIsInstance<Event.Step>().first()
    println("    第 1 步：可见 ${firstStep.visibleTools} 个工具，工具表 ${firstStep.tableChars} 字符")
    check("META 下工具表很短", firstStep.tableChars < 200, "${firstStep.tableChars} 字符")
    check(
        "第二步看到了 find 的结果",
        caller.seen[1].messages.any { it.role == Role.TOOL && it.text.contains("read_file") }
    )
}

private fun checkFailClosed() = runBlocking {
    println("\n[5] 失败路径：未知工具 / 未激活组 / 审批拒绝")

    // (a) 编造工具名
    val hitsA = mutableListOf<String>()
    val callerA = ScriptedCaller(
        mutableListOf(
            Reply(calls = listOf(Call("1", "delete_everything", Args.EMPTY))),
            Reply(text = "换个办法")
        )
    )
    Kernel(callerA).run(
        Turn(
            model = ModelRef("local", "q", 4_096, Strength.TINY),
            prompt = PromptAssembler.assemble(emptyList()),
            history = listOf(Msg.user("x")),
            tools = sampleTools(hitsA)
        )
    )
    val backA = callerA.seen[1].messages.last()
    check("未知工具没有执行", hitsA.isEmpty())
    check("回填了失败说明", backA.text.contains("没有名为"))
    check("回填了自纠提示", backA.text.contains("建议"))

    // (b) 分组模式直接调未激活组的工具
    val hitsB = mutableListOf<String>()
    val callerB = ScriptedCaller(
        mutableListOf(
            Reply(calls = listOf(Call("1", "http_get", Args.EMPTY))),
            Reply(text = "ok")
        )
    )
    Kernel(callerB).run(
        Turn(
            model = ModelRef("local", "q", 8_192, Strength.SMALL),
            prompt = PromptAssembler.assemble(emptyList()),
            history = listOf(Msg.user("x")),
            tools = sampleTools(hitsB),
            groupNotes = notes
        )
    )
    val backB = callerB.seen[1].messages.last()
    check("未激活组不执行", hitsB.isEmpty())
    check("提示先激活", backB.text.contains("未激活"))

    // (c) 审批拒绝
    val hitsC = mutableListOf<String>()
    val denyHooks = object : Hooks {
        override suspend fun approve(tool: ToolSpec, args: Args, level: Approval) = false
    }
    val callerC = ScriptedCaller(
        mutableListOf(
            Reply(calls = listOf(Call("1", "write_file", Args.EMPTY))),
            Reply(text = "那我先问你")
        )
    )
    Kernel(callerC, denyHooks).run(
        Turn(
            model = ModelRef("openai", "gpt", 128_000, Strength.LARGE),
            prompt = PromptAssembler.assemble(emptyList()),
            history = listOf(Msg.user("x")),
            tools = sampleTools(hitsC)
        )
    )
    check("拒绝后不执行", hitsC.isEmpty())
    check("拒绝的原因回填", callerC.seen[1].messages.last().text.contains("拒绝"))

    // (d) 参数级审批：只读子命令放行，rm 要问
    val shell = sampleTools().first { it.name == "shell_run" }
    eq("ls 放行", shell.approvalFor(MapArgs.of("cmd" to "ls -l"), ApprovalMode.AUTO), Approval.ALLOW)
    eq("rm 要问", shell.approvalFor(MapArgs.of("cmd" to "rm -rf /"), ApprovalMode.AUTO), Approval.ASK)
    eq("ASK_ALL 下 ls 也要问", shell.approvalFor(MapArgs.of("cmd" to "ls"), ApprovalMode.ASK_ALL), Approval.ASK)
    eq("RawArgs 读不到子参数 → 保守要问", shell.approvalFor(RawArgs("{\"cmd\":\"ls\"}"), ApprovalMode.AUTO), Approval.ASK)

    // (e) 工具抛异常不炸整轮
    val boom = ToolSpec(
        name = "boom", group = "x", danger = Danger.READ,
        brief = { "炸" }, run = { error("模拟崩溃") }
    )
    val o = boom.invoke(Args.EMPTY)
    check("异常被收敛为失败结果", !o.ok && o.hint != null)
}

private fun checkBudgetMath() {
    println("\n[6] 预算换算")
    val b = Budget.fromContext(8_192)
    println("    8k 窗口 → ${b.maxPromptChars} 字符上限（预留 1/4 给输出）")
    check("上限合理", b.maxPromptChars in 10_000..13_000, "${b.maxPromptChars}")
    val b2 = Budget.fromContext(128_000)
    check("大窗口上限更大", b2.maxPromptChars > b.maxPromptChars * 10)
}

fun main() = runBlocking {
    println("=== 内核自检 ===")
    checkExposure()
    checkPrompt()
    checkBudget()
    checkKernel()
    checkFailClosed()
    checkBudgetMath()

    // 结果行必须含 "0 失败"：run-all.sh 靠这个字样判 PASS，
    // 写成「0 项失败」不匹配它的正则，会被当成「跑完没有通过标记」判 FAIL。
    println("\n=== 结果：$pass 项通过 / " + if (fails.isEmpty()) "0 失败" else "${fails.size} 条断言失败" + " ===")
    if (fails.isNotEmpty()) {
        fails.forEach { println("  失败：$it") }
        kotlin.system.exitProcess(1)
    }
}
