#!/usr/bin/env kotlin
/**
 * 遥测流水 harness（P5-1）：验证 [TurnEvent] → NDJSON 的字段契约与 [TraceSink] 的落盘行为。
 *
 * ## 为什么这个 harness 一条源码文本断言都没有
 *
 * 它验的全部是**输出**：八类事件的 JSON 逐字符比对、转义、超长截断、落盘行数。
 * 这正是 P5-2 立的那条规矩——新断言只准行为/契约。字段名是契约的一部分，
 * 所以比对的是「序列化出来的那一行」，而不是「源码里写了哪些键」。
 * 好处很直接：改实现（比如换成 kotlinx.serialization）只要输出不变，这里不会假红。
 *
 * ## 反向覆盖
 *
 * 只验「正常事件能序列化」是不够的——一个永远返回 `{}` 的实现也能通过。
 * 所以关键几条是**配对**与**边界**：
 *  - 同一 step 两个同名工具：靠 `call` 字段才分得开（这正是加 callId 的理由）；
 *  - 转义：参数摘要里出现引号/换行时，行协议不能被撑断；
 *  - 超长：一条 100KB 的 argsSummary 必须被截断，否则一个回合就能写爆流水文件。
 *
 * 运行：bash scripts/qa/run-trace-telemetry-verification.sh
 */

import com.beichen.polaris.core.agent.CompositeTurnObserver
import com.beichen.polaris.core.agent.NdjsonTurnObserver
import com.beichen.polaris.core.agent.TurnEvent
import com.beichen.polaris.core.agent.TurnObserver
import com.beichen.polaris.core.agent.nameOf
import com.beichen.polaris.core.agent.toNdjson
import com.beichen.polaris.core.telemetry.TraceSink
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

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    println("=== P5-1 遥测流水 ===")

    // ---------- 1. 三个信封字段 + 八类事件的精确形状 ----------
    println("\n[1] 八类事件的字段契约（ts 固定为 0，逐字符比对）")
    val T = "turn-x"
    fun ev(e: TurnEvent) = e.toNdjson(ts = 0L)

    eq(
        "turn.start",
        ev(TurnEvent.TurnStarted(T, 0, 0, inputChars = 24, visibleToolCount = 12)),
        """{"ev":"turn.start","ts":0,"turn":"turn-x","scope":"main","step":0,"ms":0,"chars":24,"tools":12}"""
    )
    eq(
        "prompt.plan（组按序成数组）",
        ev(TurnEvent.PromptPlanned(T, 0, 5, visibleToolCount = 7, estimatedTokens = 3200, groups = listOf("file", "ui"))),
        """{"ev":"prompt.plan","ts":0,"turn":"turn-x","scope":"main","step":0,"ms":5,"tools":7,"tokens":3200,"groups":["file","ui"]}"""
    )
    eq(
        "llm.call",
        ev(TurnEvent.LlmCallStarted(T, 2, 40, attempt = 1)),
        """{"ev":"llm.call","ts":0,"turn":"turn-x","scope":"main","step":2,"ms":40,"attempt":1}"""
    )
    eq(
        "llm.end（失败带归一化码）",
        ev(TurnEvent.LlmCallEnded(T, 2, 38400, attempt = 3, durationMs = 38360, success = false, code = "auth", toolCallCount = 0)),
        """{"ev":"llm.end","ts":0,"turn":"turn-x","scope":"main","step":2,"ms":38400,"attempt":3,"dur":38360,"ok":false,"code":"auth","calls":0}"""
    )
    eq(
        "tool.start（带 callId）",
        ev(TurnEvent.ToolCallStarted(T, 1, 18, tool = "write_file", argsSummary = """{"path":"a.txt"}""", callId = "call_a1")),
        """{"ev":"tool.start","ts":0,"turn":"turn-x","scope":"main","step":1,"ms":18,"call":"call_a1","tool":"write_file","args":"{\"path\":\"a.txt\"}"}"""
    )
    eq(
        "tool.end",
        ev(TurnEvent.ToolCallEnded(T, 1, 6021, tool = "write_file", code = "ok", isError = false, durationMs = 6003, callId = "call_a1")),
        """{"ev":"tool.end","ts":0,"turn":"turn-x","scope":"main","step":1,"ms":6021,"call":"call_a1","tool":"write_file","code":"ok","err":false,"dur":6003}"""
    )
    eq(
        "step.close",
        ev(TurnEvent.StepClosed(T, 1, 6100, toolCount = 2)),
        """{"ev":"step.close","ts":0,"turn":"turn-x","scope":"main","step":1,"ms":6100,"tools":2}"""
    )
    eq(
        "turn.end",
        ev(TurnEvent.TurnEnded(T, 3, 9000, status = "completed", steps = 4)),
        """{"ev":"turn.end","ts":0,"turn":"turn-x","scope":"main","step":3,"ms":9000,"status":"completed","steps":4}"""
    )

    // ---------- 2. 八类都有名字，不多不少 ----------
    println("\n[2] 事件名覆盖")
    val names = listOf(
        TurnEvent.TurnStarted(T, 0, 0, 0, 0),
        TurnEvent.PromptPlanned(T, 0, 0, 0, 0, emptyList()),
        TurnEvent.LlmCallStarted(T, 0, 0, 1),
        TurnEvent.LlmCallEnded(T, 0, 0, 1, 0, true),
        TurnEvent.ToolCallStarted(T, 0, 0, "t", "a"),
        TurnEvent.ToolCallEnded(T, 0, 0, "t", "ok", false, 0),
        TurnEvent.StepClosed(T, 0, 0, 0),
        TurnEvent.TurnEnded(T, 0, 0, "completed", 1),
    ).map { it.nameOf() }
    eq("八类事件名", names, listOf(
        "turn.start", "prompt.plan", "llm.call", "llm.end",
        "tool.start", "tool.end", "step.close", "turn.end"
    ))
    val allEvents = listOf(
        TurnEvent.TurnStarted(T, 0, 0, 0, 0),
        TurnEvent.PromptPlanned(T, 0, 0, 0, 0, emptyList()),
        TurnEvent.LlmCallStarted(T, 0, 0, 1),
        TurnEvent.LlmCallEnded(T, 0, 0, 1, 0, true),
        TurnEvent.ToolCallStarted(T, 0, 0, "t", "a"),
        TurnEvent.ToolCallEnded(T, 0, 0, "t", "ok", false, 0),
        TurnEvent.StepClosed(T, 0, 0, 0),
        TurnEvent.TurnEnded(T, 0, 0, "completed", 1),
    )
    check("八类事件全部以 {\"ev\": 打头（tail 时先看到是什么事件）",
        allEvents.all { it.toNdjson(0).startsWith("{\"ev\":") })

    // ---------- 3. 转义：行协议不能被参数撑断 ----------
    println("\n[3] 转义")
    eq("引号", TraceSink.quote("""a"b"""), "\"a\\\"b\"")
    eq("反斜杠", TraceSink.quote("""a\b"""), "\"a\\\\b\"")
    eq("换行", TraceSink.quote("a\nb"), "\"a\\nb\"")
    eq("制表", TraceSink.quote("a\tb"), "\"a\\tb\"")
    eq("控制字符走 \\u00XX", TraceSink.quote("a\u0001b"), "\"a\\u0001b\"")
    eq("中文原样保留（不转义，便于人读）", TraceSink.quote("写文件"), "\"写文件\"")
    eq("null → 字面 null（不是字符串 \"null\"）", TraceSink.quote(null), "null")

    val tricky = TurnEvent.ToolCallStarted(T, 0, 0, tool = "write_file", argsSummary = "a\"b\nc\\d")
    val line = tricky.toNdjson(ts = 0)
    check("含引号换行的参数不会产出额外换行", line.indexOf('\n') < 0, line)
    check("转义后仍以 } 收尾（没被截断成半条）", line.endsWith("}"))

    // ---------- 4. 同一步并行同名工具靠 call 配对 ----------
    println("\n[4] 并行同名工具的配对")
    val a = TurnEvent.ToolCallStarted(T, 1, 10, tool = "read_file", argsSummary = "a", callId = "call_a")
    val b = TurnEvent.ToolCallStarted(T, 1, 11, tool = "read_file", argsSummary = "b", callId = "call_b")
    check("两个 start 的行不同（否则配不上对）", a.toNdjson(0) != b.toNdjson(0))
    val endB = TurnEvent.ToolCallEnded(T, 1, 20, tool = "read_file", code = "ok", isError = false, durationMs = 9, callId = "call_b")
    eq(
        "end 带着与之配对的 call id",
        endB.toNdjson(0),
        """{"ev":"tool.end","ts":0,"turn":"turn-x","scope":"main","step":1,"ms":20,"call":"call_b","tool":"read_file","code":"ok","err":false,"dur":9}"""
    )

    // ---------- 5. TraceSink：未 init 时是 no-op ----------
    println("\n[5] TraceSink 未初始化")
    var threw = false
    try {
        TraceSink.emit("""{"ev":"x"}""")
        TraceSink.flush(0)
    } catch (e: Throwable) {
        threw = true
    }
    check("未 init 时 emit/flush 不抛（harness 与单测不产生文件）", !threw)

    // ---------- 6. TraceSink：真落盘 ----------
    println("\n[6] 真落盘")
    val dir = File.createTempFile("trace-verify", "").let {
        it.delete(); it.mkdirs(); it
    }
    TraceSink.init(dir)
    val lines = listOf(
        TurnEvent.TurnStarted(T, 0, 0, 5, 3).toNdjson(0),
        TurnEvent.LlmCallStarted(T, 0, 1, 1).toNdjson(0),
        TurnEvent.TurnEnded(T, 0, 120, "completed", 1).toNdjson(0),
    )
    lines.forEach { TraceSink.emit(it) }
    TraceSink.flush()
    val written = dir.listFiles { f -> f.name.startsWith("turns-") && f.name.endsWith(".ndjson") }
        ?.flatMap { it.readLines() }?.filter { it.isNotBlank() } ?: emptyList()
    eq("落盘行数 = 写入行数", written.size, 3)
    eq("第一行原样落盘", written.firstOrNull(), lines.first())
    check("每行都是完整对象（首尾大括号）",
        written.all { it.startsWith("{") && it.endsWith("}") })

    // 超长行截断：一条 100KB 的参数摘要不该整条进文件
    TraceSink.emit("{\"ev\":\"x\",\"pad\":\"" + "a".repeat(100_000) + "\"}")
    TraceSink.flush()
    val after = (dir.listFiles { f -> f.name.startsWith("turns-") } ?: emptyArray())
        .flatMap { it.readLines() }.filter { it.isNotBlank() }
    val longest = after.maxOf { it.length }
    check("超长行被截断（上限 4096 量级）", longest < 5000, "最长 $longest 字符")

    TraceSink.shutdownForTest()
    dir.deleteRecursively()

    // ---------- 7. 复合观察者：一个坏了不拖累另一个 ----------
    println("\n[7] 复合观察者")
    var first = 0
    var second = 0
    val boom = TurnObserver { first++; throw IllegalStateException("观察者炸了") }
    val counter = TurnObserver { second++ }
    val composite = CompositeTurnObserver(boom, counter)
    var compositeThrew = false
    try {
        composite.onEvent(TurnEvent.StepClosed(T, 0, 0, 1))
    } catch (e: Throwable) {
        compositeThrew = true
    }
    check("前一个抛异常不影响后一个收到", second == 1, "second=$second")
    check("异常不外泄（观测不该有能力弄挂回合）", !compositeThrew, "first=$first")

    // ---------- 8. NdjsonTurnObserver 走注入的 sink ----------
    println("\n[8] 观察者 → sink")
    val sink = mutableListOf<String>()
    NdjsonTurnObserver { sink.add(it) }.onEvent(TurnEvent.TurnEnded(T, 0, 7, "cancelled", 1))
    eq("一条事件 → 一行", sink.size, 1)
    // ts 取墙钟，无法固定——把它归零后比对整行，其余字段必须逐字相同
    eq(
        "内容即 toNdjson（观察者不做二次加工）",
        sink.firstOrNull()?.replace(Regex("\"ts\":\\d+"), "\"ts\":0"),
        """{"ev":"turn.end","ts":0,"turn":"turn-x","scope":"main","step":0,"ms":7,"status":"cancelled","steps":1}"""
    )

    // ---------- 结果 ----------
    println("\n结果：PASS=$pass FAIL=${fails.size}")
    if (fails.isNotEmpty()) {
        println("失败项：")
        fails.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
