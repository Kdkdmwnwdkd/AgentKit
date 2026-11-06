#!/usr/bin/env kotlin
/**
 * R-05「消息列表无 key」验证 harness。
 *
 * ## 为什么这条要真跑
 * key 的对错只有一种判法：**插入一项之后，其它项的身份变没变**。grep 只能证明
 * `key =` 这个词出现过，证明不了「工具卡插进来时，正在展开的那张卡不会被当成新项」。
 * [uiKey] 是纯函数，所以这里直接编译真的 `UiItem.kt` 跑一遍。
 *
 * ## 覆盖
 *  A. 三类前缀互不干扰；工具卡的身份跟着 `call.id` 走，与位置无关
 *  B. 插入/删除一项，其余每一项的 key **一字不变**（本轮的正式诉求）
 *  C. 规模唯一：整表 key 不得重复（重复就直接抛异常、整页白屏）
 *
 * 运行：scripts/qa/run-r05-verification.sh
 */

import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.UiItem
import com.beichen.polaris.core.uiKey
import kotlinx.serialization.json.JsonObject

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"
        println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

/** 延迟求值的失败信息：热路径上不白拼字符串。 */
fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/* -------------------------------- 造数据 -------------------------------- */

private fun tool(id: String, name: String = "ui_tap") =
    UiItem.Tool(ToolCall(id, name, JsonObject(emptyMap())), "ok", false)

private fun keys(items: List<UiItem>): List<String> = items.mapIndexed { i, it -> it.uiKey(i) }

private fun describe(item: UiItem): String = when (item) {
    is UiItem.User -> "用户「${item.text}」"
    is UiItem.Assistant -> "回答「${item.text}」"
    is UiItem.Tool -> "工具 ${item.call.id}"
}

/* --------------------------------- 用例 --------------------------------- */

fun main() {
    println("R-05 消息列表 key：身份只由数据决定")

    val u0 = UiItem.User("第一条", turnIndex = 0)
    val a0 = UiItem.Assistant("答一", turnIndex = 0)
    val u1 = UiItem.User("第二条", turnIndex = 1)
    val a1 = UiItem.Assistant("答二", turnIndex = 1)

    // ── A. 身份来源 ────────────────────────────────────────────────────
    println("\n[A] 身份来源")
    check("A1 三类前缀互不干扰") {
        val k = listOf(u0.uiKey(0), a0.uiKey(1), tool("c1").uiKey(2))
        expect(k[0].startsWith("u:")) { "用户气泡不是 u: 前缀：${k[0]}" }
        expect(k[1].startsWith("a:")) { "助手气泡不是 a: 前缀：${k[1]}" }
        expect(k[2].startsWith("t:")) { "工具卡不是 t: 前缀：${k[2]}" }
        eq(k.toSet().size, 3, "三个不同 item 的 key 必须互不相同")
    }
    check("A2 工具卡身份跟 call.id 走，与位置无关") {
        val t = tool("call-abc")
        eq(t.uiKey(3), "t:call-abc", "下标 3 处的 key")
        eq(t.uiKey(99), "t:call-abc", "换到下标 99 —— 换位置不该换身份")
    }
    check("A3 同一轮的用户气泡与助手气泡不撞") {
        expect(u0.uiKey(0) != a0.uiKey(1)) { "第 0 轮的 user 与 assistant 撞了：${u0.uiKey(0)}" }
    }
    check("A4 未归属的草稿退回下标（它确实没有稳定身份）") {
        val draft = UiItem.User("刚敲的一句话")  // turnIndex 默认 -1
        eq(draft.uiKey(7), "u@7", "草稿 key")
        eq(UiItem.Assistant("流式中的回答").uiKey(8), "m@8", "中间消息 key")
    }
    check("A5 trace 里的中间消息与正式回答不撞") {
        // 投影里每轮的选中变体带 turnIndex，trace 里的中间 assistant 是 -1。
        val middle = UiItem.Assistant("想了想")
        expect(middle.uiKey(5) != a0.uiKey(0)) { "中间消息与第 0 轮回答撞了" }
    }

    // ── B. 插入/删除不惊动旁人 ──────────────────────────────────────────
    println("\n[B] 插入/删除不惊动旁人（本轮正式诉求）")
    check("B1 中途插入一张工具卡，原有每一项 key 不变") {
        val before = listOf(u0, tool("t-a"), tool("t-b"), a0, u1, a1)
        val after = listOf(u0, tool("t-a"), tool("t-b"), tool("t-c"), a0, u1, a1)
        val bk = keys(before)
        val ak = keys(after)
        before.forEachIndexed { i, item ->
            val j = after.indexOf(item)
            expect(j >= 0) { "插入后找不到原项 ${describe(item)}" }
            eq(ak[j], bk[i], "「${describe(item)}」的身份被插入搅动了")
        }
    }
    check("B2 删掉中间一项，其余每一项 key 不变") {
        val before = listOf(u0, tool("t-a"), tool("t-b"), a0, u1, a1)
        val after = listOf(u0, tool("t-a"), a0, u1, a1)
        val bk = keys(before)
        val ak = keys(after)
        before.filter { it != tool("t-b") }.forEach { item ->
            val i = before.indexOf(item)
            val j = after.indexOf(item)
            expect(j >= 0) { "删除后找不到 ${describe(item)}" }
            eq(ak[j], bk[i], "「${describe(item)}」的身份被删除搅动了")
        }
    }
    check("B3 running → done 原位替换，身份不变") {
        // R-05 与 R-06 的交汇点：工具卡从「执行中」变终态时只改 result/running，
        // key 必须还是同一个，否则那张卡会被当成「旧的消失、新的出现」。
        val running = UiItem.Tool(ToolCall("call-x", "ui_tap", JsonObject(emptyMap())), "", false, running = true)
        val done = running.copy(result = "已点击", running = false)
        eq(done.uiKey(2), running.uiKey(2), "终态替换后的 key")
    }

    // ── C. 唯一性 ──────────────────────────────────────────────────────
    println("\n[C] 整表唯一")
    check("C1 20 项混合列表 key 全不重复") {
        val items = buildList {
            for (turn in 0 until 4) {
                add(UiItem.User("问题 $turn", turnIndex = turn))
                add(tool("call-$turn-1"))
                add(UiItem.Assistant("中间想法 $turn"))
                add(tool("call-$turn-2"))
                add(UiItem.Assistant("回答 $turn", turnIndex = turn))
            }
        }
        eq(items.size, 20, "用例规模")
        val ks = keys(items)
        eq(ks.toSet().size, ks.size, "有重复 key —— Compose 会直接抛异常，整页白屏：$ks")
    }
    check("C2 空 call.id 的老数据不撞 key") {
        // 手工编辑过的文件、早期版本落盘可能没有 id。多个空 id 如果都映射成 "t:"，
        // Compose 的重复 key 检查会当场抛 IllegalArgumentException。
        val items = listOf(tool("", "a"), tool("", "b"), tool("", "c"))
        val ks = keys(items)
        eq(ks.toSet().size, 3, "空 id 工具卡撞了 key：$ks")
    }

    // ── 汇总 ───────────────────────────────────────────────────────────
    println()
    if (failures.isNotEmpty()) {
        println("失败清单：")
        failures.forEach { println("  - $it") }
    }
    println("=== 合计 $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
