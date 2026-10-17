#!/usr/bin/env kotlin
/**
 * W-10 QA 行为验证 harness：上下文预算治理（[com.example.agentkit.core.agent.ContextBudget]）。
 *
 * 背景：W-9 之前上下文只按「消息条数」（40 条）滑动，而单条工具结果上限 24k 字符——
 * 条数对体积没有解释力；且截图 base64 每轮全量重发（768px JPEG 约 6~12 万字符）。
 * 真机体感「越跑越慢、越跑越贵」的上下文侧根因即此。
 *
 * 本 harness 只测**纯函数**（零 Android 依赖，JVM 直跑）：
 *  A. 估算口径：CJK/ASCII 配比、图片按张计价（不按 base64 字符数）；
 *  B. 图片裁剪：只留最近 N 张工具截图、首条用户消息的图不裁、入参不被修改；
 *  C. 预算压缩：超限丢最老完整步、保留任务锚点与当前步、tool_call↔tool 结果配对不破、
 *     条数硬上限继续生效、未超限时零改动。
 *
 * 运行方式见 scripts/qa/run-context-budget-verification.sh（离线，仅用 Gradle 缓存内构件）。
 */

import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.UserMessage
import com.example.agentkit.core.agent.ContextBudget
import kotlinx.serialization.json.JsonObject

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertNear(name: String, expected: Int, actual: Int, tolerance: Int) {
    if (kotlin.math.abs(expected - actual) > tolerance) {
        throw AssertionError("[$name] expected $expected ±$tolerance but was $actual")
    }
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

/* ------------------------------ 测试数据 ------------------------------ */

private fun big(n: Int): String = "测".repeat(n)

/** 10 万字符的假 data URL：若按字符计 token 会得 2.5 万，正确口径只算 900。 */
private fun fakeImage(): String = "data:image/jpeg;base64," + "A".repeat(100_000)

private fun call(id: String, name: String) = ToolCall(id, name, JsonObject(emptyMap()))

/** 一「步」= assistant(tool_calls) + 工具结果 + 截图回灌，返回 4 条消息。 */
private fun step(id: String, chars: Int, withImage: Boolean = true): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>(
        AssistantMessage("", listOf(call(id, "ui_tree"))),
        ToolMessage(id, "ui_tree", JsonObject(emptyMap()), big(chars))
    )
    if (withImage) out += UserMessage("请看下面由工具返回的画面：", listOf(fakeImage()))
    return out
}

private fun pairingIntact(messages: List<ChatMessage>): Boolean {
    for (i in messages.indices) {
        val t = messages[i] as? ToolMessage ?: continue
        var j = i - 1
        var matched = false
        while (j >= 0) {
            val a = messages[j] as? AssistantMessage
            if (a != null) {
                matched = a.toolCalls.any { it.id == t.id }
                break
            }
            j--
        }
        if (!matched) return false
    }
    return true
}

fun main() {
    println("== W10-A 估算口径（CJK 感知 / 图片按张计价）==")
    check("空串 = 0 token") {
        assertEquals("empty", 0, ContextBudget.estimateTokens(""))
    }
    check("1000 个汉字 ≈ 1000 token") {
        assertNear("cjk", 1000, ContextBudget.estimateTokens(big(1000)), 100)
    }
    check("4000 个 ASCII ≈ 1000 token") {
        assertNear("ascii", 1000, ContextBudget.estimateTokens("a".repeat(4000)), 100)
    }
    check("图片按张计价：10 万字符 base64 只算 900，不按字符数") {
        val msg = UserMessage("看图", listOf(fakeImage()))
        val tokens = ContextBudget.messageTokens(msg)
        assertNear("image-token", 900 + 2, tokens, 50)
    }
    check("整段历史 = 各条之和") {
        val msgs = listOf<ChatMessage>(SystemMessage("提示"), UserMessage("你好"))
        assertEquals("sum", ContextBudget.messageTokens(msgs[0]) + ContextBudget.messageTokens(msgs[1]), ContextBudget.totalTokens(msgs))
    }
    check("本地 llama 通道：预算压到 2k（n_ctx 默认 4096，不能按云端 24k 发）") {
        assertEquals("local", ContextBudget.LOCAL_LLAMA_BUDGET_TOKENS, ContextBudget.budgetForProvider("local-llama", 24_000))
    }
    check("云端通道：预算原样（不动用户配置）") {
        assertEquals("cloud", 24_000, ContextBudget.budgetForProvider("openai", 24_000))
    }
    check("本地通道配置更小：尊重配置，绝不上调") {
        assertEquals("min", 1_000, ContextBudget.budgetForProvider("local-llama", 1_000))
    }

    println("\n== W10-B 图片裁剪（只留最近 N 张工具截图）==")
    check("4 张工具截图 keepLast=2：前 2 张剥图 + 追加省略标记，后 2 张原样") {
        val anchor = UserMessage("任务：按这张图改", listOf(fakeImage())) // 锚点图不裁
        val imgs = (1..4).map { UserMessage("请看下面由工具返回的画面：", listOf(fakeImage())) }
        val input = listOf<ChatMessage>(SystemMessage("s"), anchor) + imgs
        val out = ContextBudget.pruneImages(input, keepLast = 2)
        assertEquals("size", input.size, out.size)
        assertTrue("anchor intact", (out[1] as UserMessage).images.isNotEmpty())
        assertTrue("1st pruned", (out[2] as UserMessage).images.isEmpty())
        assertTrue("2nd pruned", (out[3] as UserMessage).images.isEmpty())
        assertTrue("note added", (out[2] as UserMessage).content.endsWith(ContextBudget.PRUNED_IMAGE_NOTE))
        assertTrue("3rd intact", (out[4] as UserMessage).images.isNotEmpty())
        assertTrue("4th intact", (out[5] as UserMessage).images.isNotEmpty())
    }
    check("图片数 ≤ keepLast：原样返回同一实例（零分配）") {
        val input = listOf<ChatMessage>(SystemMessage("s"), UserMessage("u"), UserMessage("请看下面由工具返回的画面：", listOf(fakeImage())))
        assertTrue("same instance", ContextBudget.pruneImages(input, keepLast = 2) === input)
    }
    check("入参不被修改（首张工具截图的 images 仍在原列表里）") {
        val img = UserMessage("请看下面由工具返回的画面：", listOf(fakeImage()))
        val input = listOf<ChatMessage>(SystemMessage("s"), UserMessage("u"), img, img.copy(), img.copy())
        ContextBudget.pruneImages(input, keepLast = 1)
        assertTrue("input untouched", (input[2] as UserMessage).images.isNotEmpty())
    }

    println("\n== W10-C 预算压缩（丢最老完整步 / 保锚点与当前步 / 配对不破）==")
    val sys = SystemMessage("系统提示")
    val anchor = UserMessage("任务：打开设置并截图")
    val s1 = step("c1", 4000)
    val s2 = step("c2", 4000)
    val s3 = step("c3", 4000)
    val history = listOf<ChatMessage>(sys, anchor) + s1 + s2 + s3 + AssistantMessage("完成")

    check("未超预算：原样返回同一实例（零分配、零抖动）") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 1_000_000)
        assertTrue("same instance", out === history)
    }
    check("超预算：丢掉最老一步（c1 的 tool 结果与其截图回灌一起消失）") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 10_000)
        assertTrue("c1 dropped", out.none { it is ToolMessage && it.id == "c1" })
        assertTrue("c2 kept", out.any { it is ToolMessage && it.id == "c2" })
        assertTrue("c3 kept", out.any { it is ToolMessage && it.id == "c3" })
    }
    check("保留任务锚点（首条用户消息永不被丢）") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 3_000)
        assertTrue("anchor kept", out.any { it is UserMessage && it.content.startsWith("任务：") })
    }
    check("保留当前步（最后一步的工具结果不丢）") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 3_000)
        assertTrue("last step kept", out.any { it is ToolMessage && it.id == "c3" })
    }
    check("配对不变量：每个 tool 结果都有同 id 的 assistant tool_call") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 8_000)
        assertTrue("pairing", pairingIntact(out))
    }
    check("丢整步：截图回灌只紧跟保留的工具结果（不留孤立「请看画面」）") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 7_000)
        out.forEachIndexed { i, m ->
            if (m is UserMessage && m.content.startsWith("请看下面由工具返回的画面")) {
                assertTrue("image at $i must follow a tool result", i > 0 && out[i - 1] is ToolMessage)
            }
        }
    }
    check("压缩结果 ≤ 预算（能压到的情况下）") {
        val out = ContextBudget.fitToBudget(history, budgetTokens = 8_000)
        assertTrue("within budget: ${ContextBudget.totalTokens(out)}", ContextBudget.totalTokens(out) <= 8_000)
    }
    check("条数硬上限：预算充足时仍按 maxMessages 裁到上限内") {
        val tiny = (1..6).flatMap { step("t$it", 10, withImage = false) }
        val input = listOf<ChatMessage>(sys, anchor) + tiny + AssistantMessage("完成")
        val out = ContextBudget.fitToBudget(input, budgetTokens = 1_000_000, maxMessages = 6)
        assertTrue("size ${out.size} <= 6", out.size <= 6)
        assertTrue("pairing", pairingIntact(out))
    }
    check("只有锚点 + 当前步时无可丢：原样返回") {
        val input = listOf<ChatMessage>(sys, anchor, AssistantMessage("完成"))
        assertTrue("same instance", ContextBudget.fitToBudget(input, budgetTokens = 1) === input)
    }
    check("空历史：原样返回（不崩）") {
        assertTrue("empty", ContextBudget.fitToBudget(emptyList(), budgetTokens = 1).isEmpty())
    }
    check("确定性：同一输入两次压缩结果一致（无状态 = 无抖动）") {
        val a = ContextBudget.fitToBudget(history, budgetTokens = 9_000)
        val b = ContextBudget.fitToBudget(history, budgetTokens = 9_000)
        assertEquals("deterministic", a, b)
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
