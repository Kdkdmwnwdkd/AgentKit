#!/usr/bin/env kotlin
/**
 * 启动分段计时器 harness：验证 [com.example.agentkit.core.StartupTrace]。
 *
 * 验的是**计时器本身**能不能可信地报数——因为整个「启动慢在哪」的排查都建立在
 * 「这个数字是真的」之上。计时器自己写错了，那所有启动埋点都是自欺欺人。
 *
 * 运行：bash scripts/qa/run-startup-trace-verification.sh
 *
 * 用**注入的假时钟**而不是真 sleep：真 sleep 的断言要么慢，要么 flaky（CI 机器一卡
 * 就红）。假时钟让每条断言都是确定性的，同时还能构造出「某段 900ms」这种真机上
 * 未必复现的场景。
 */

import com.example.agentkit.core.StartupTrace

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

/** 可控假时钟：每次读取前进指定毫秒数。 */
private class FakeClock(private var now: Long = 0L, private val step: Long = 0L) : () -> Long {
    override fun invoke(): Long {
        now += step
        return now
    }
}

/**
 * 把 [com.example.agentkit.core.StartupTrace.summary] 的输出解析成「段名 → 毫秒」映射。
 *
 * ## 为什么解析而不是子串匹配
 *
 * 摘要文本的**形状**（`名字=毫秒ms`）是契约的一部分，但断言真正要验的是
 * 「各段各是多少毫秒」这个**数值**。解析成 map 之后可以整份相等比较，
 * 比逐个子串 find 更严：少一段、多一段、段名写错、毫秒数算错，都会红。
 */
private fun parseSummary(summary: String): Map<String, Long> =
    summary.split(", ").mapNotNull { part ->
        val name = part.substringBefore('=')
        val value = part.substringAfter('=').removeSuffix("ms").toLongOrNull()
        if (value == null) null else name to value
    }.toMap()

fun main() {
    println("\n== StartupTrace：分段计时 ==")

    // ---------- 1. 基本分段：每段记的是「上一段结束到本段结束」的间隔 ----------
    println("\n[1] 分段语义")
    run {
        val clock = FakeClock(now = 1000L, step = 10L)
        val trace = StartupTrace(clock)
        // 构造时读一次时钟（起点），之后每次 mark 读一次。
        trace.mark("a")
        trace.mark("b")
        trace.mark("c")
        val segs = trace.snapshot()
        eq("段数", segs.size, 3)
        eq("段名顺序", segs.map { it.name }, listOf("a", "b", "c"))
        // 假时钟每次 +10ms，所以每段都是 10ms
        check("每段耗时=10ms", segs.all { it.millis == 10L }, segs.joinToString(",") { "${it.name}=${it.millis}" })
        eq("总耗时=30ms", trace.totalMillis(), 30L)
    }

    // ---------- 2. 不同时长：证明它记的是间隔而不是绝对值 ----------
    println("\n[2] 段与段的间隔真的被分别记录")
    run {
        var now = 0L
        val trace = StartupTrace { now }
        now = 100
        trace.mark("fast")
        now = 1100
        trace.mark("slow")
        now = 1150
        trace.mark("mid")
        val segs = trace.snapshot()
        eq("第一段=100ms", segs[0].millis, 100L)
        eq("第二段=1000ms", segs[1].millis, 1000L)
        eq("第三段=50ms", segs[2].millis, 50L)
        eq("总耗时=1150ms", trace.totalMillis(), 1150L)
    }

    // ---------- 3. 空状态：不能返回空串 ----------
    println("\n[3] 空状态")
    run {
        val trace = StartupTrace(FakeClock())
        eq("无段时总数=0", trace.totalMillis(), 0L)
        eq("无段时 summary 不是空串", trace.summary(), "<no segments>")
        check("无段时 summary 非空", trace.summary().isNotBlank(), trace.summary())
        check("无段时无慢段", trace.slowSegments(100L).isEmpty(), "")
    }

    // ---------- 4. summary 格式：能直接落日志 ----------
    println("\n[4] summary 可直接落日志")
    run {
        var now = 0L
        val trace = StartupTrace { now }
        now = 12
        trace.mark("registry")
        now = 352
        trace.mark("skills")
        val s = trace.summary()
        // 解析成 map 后整份相等比较：少一段/多一段/段名错/毫秒错 都会红。
        val m = parseSummary(s)
        eq("段名与毫秒", m, mapOf("registry" to 12L, "skills" to 340L, "total" to 352L))
        eq("total 字段与 totalMillis 自洽", m["total"], trace.totalMillis())
        eq("段顺序", m.keys.toList(), listOf("registry", "skills", "total"))
    }

    // ---------- 5. 慢段筛选：阈值与倒序 ----------
    println("\n[5] 慢段筛选")
    run {
        var now = 0L
        val trace = StartupTrace { now }
        // 每次 mark 读一次时钟。构造时也读了一次（起点），所以第 1 段的间隔
        // = now 从 0 跳到 5 的差；后续每段同理。
        now = 5; trace.mark("tiny")      // 5ms
        now = 205; trace.mark("mid")     // 200ms
        now = 905; trace.mark("huge")    // 700ms
        now = 1105; trace.mark("alsoHuge") // 200ms
        val all = trace.snapshot()
        eq("各段耗时", all.map { it.millis }, listOf(5L, 200L, 700L, 200L))
        val slow = trace.slowSegments(100L)
        eq("慢段数（tiny 被滤掉）", slow.size, 3)
        eq("按耗时倒序", slow.map { it.name }, listOf("huge", "mid", "alsoHuge"))
        check("阈值边界：等于阈值的算慢段", slow.any { it.name == "mid" }, slow.toString())
        val stricter = trace.slowSegments(300L)
        eq("更严阈值后只留 huge", stricter.map { it.name }, listOf("huge"))
    }

    // ---------- 6. 快照是拷贝：外部改不了内部状态 ----------
    println("\n[6] 快照隔离")
    run {
        val trace = StartupTrace(FakeClock(now = 0L, step = 10L))
        trace.mark("a")
        val snap = trace.snapshot()
        check("快照非空", snap.isNotEmpty(), "")
        // 拿到的 list 改不动原表（Kotlin 的 List 是只读视图，但这里验证的是快照本身独立）
        trace.mark("b")
        eq("原表仍只记录了 1 段", trace.snapshot().size, 2)
        eq("先前快照仍是 1 段", snap.size, 1)
    }

    // ---------- 7. 并发打点不丢段：启动期确实有多线程打点 ----------
    println("\n[7] 并发打点")
    run {
        val trace = StartupTrace(FakeClock(now = 0L, step = 1L))
        val threads = (1..8).map { i ->
            Thread {
                repeat(50) { trace.mark("t$i") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        eq("8×50 段全部记上", trace.snapshot().size, 400)
        eq("段名无重复错乱", trace.snapshot().map { it.name }.distinct().size, 8)
    }

    // ---------- 8. 收尾（seal）之后不再接受打点 ----------
    //
    // 打点写在会被**重复调用**的方法里：`buildInitialSkills()` 在导入/卸载技能时会经
    // `reloadSkills()` 再跑一遍，也就再打一次 `fields` / `skills`。启动观测的逻辑终点
    // 是 `finish()`，之后那次重载属于「另一个事件」。不封口的话，`snapshot()` 会把
    // 启动之后的重载也算进启动分段——数字当场失真，而且是那种「看着像真的」的失真。
    println("\n[8] 收尾后打点失效")
    run {
        val trace = StartupTrace(FakeClock(now = 0L, step = 10L))
        trace.mark("skills")
        eq("收尾前正常记录", trace.snapshot().size, 1)
        check("收尾前 isSealed=false", !trace.isSealed())

        trace.seal()
        check("收尾后 isSealed=true", trace.isSealed())

        val ret = trace.mark("reload")
        eq("收尾后 mark 返回 0", ret, 0L)
        eq("收尾后不再新增段", trace.snapshot().size, 1)
        eq("已记录的段不受影响", trace.snapshot().map { it.name }, listOf("skills"))

        trace.seal()
        eq("seal 幂等：重复调用不改变段数", trace.snapshot().size, 1)
    }

    println()
    if (fails.isEmpty()) {
        println("✅ StartupTrace 全部通过（$pass 条）")
    } else {
        println("❌ StartupTrace 失败 ${fails.size} 条：")
        fails.forEach { println("   - $it") }
        kotlin.system.exitProcess(1)
    }
}
