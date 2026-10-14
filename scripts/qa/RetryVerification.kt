#!/usr/bin/env kotlin
/**
 * Phase 2 · DoD③「断网/失败自动恢复且 UI 可见」QA 行为验证 harness。
 *
 * 目标：验证 RealAgentLoop 的重试过程对 UI **可观测**——每次「准备重试」抛
 * [AgentEvent.RetryStarted(attempt, max, reason)]，本轮成功 / 最终失败 / 用户取消
 * 时**恰好一次**抛 [AgentEvent.RetryFinished] 收尾（含取消路径），且语义边界正确：
 *  - 429/5xx/网络异常才重试（可重试），401/403/400 一次性快速失败；
 *  - 取消（CancellationException）绝不重试、绝不被吞，且立即清掉「正在恢复」状态；
 *  - 重试序号 1..providerRetry，总请求数 ≤ providerRetry + 1。
 *
 * 仓库无测试源集（离线缓存无 JUnit），沿用纯 main() + 手写断言（形态学
 * scripts/qa/T02T06Verification.kt）。运行方式见 scripts/qa/run-retry-verification.sh。
 */

import com.example.agentkit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private suspend fun check(name: String, block: suspend () -> Unit) {
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

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("$name: expected <$expected> but was <$actual>")
}

private suspend fun catchThrowable(name: String, block: suspend () -> Unit): Throwable {
    try {
        block()
    } catch (t: Throwable) {
        return t
    }
    throw AssertionError("$name: expected an exception but nothing was thrown")
}

/* ------------------------------ 测试替身 ------------------------------ */

/**
 * 脚本化「故障 LLM」：按调用序号（0-based）执行预设行为。
 *
 * @param behaviors 每个下标对应一次调用的行为；调用序号超出列表长度时复用**最后一项**
 *   （便于「永远失败」这类脚本只写一条）。
 */
private class FaultLlm(
    private val behaviors: List<suspend (Int) -> AssistantMessage>
) : LLMClient {
    override val id: String = "fault"
    var calls: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage {
        val index = calls
        calls++
        // 默认 streamChat = chat() + 单条 Completed，故重试以「整次收集」为粒度；这里只覆盖 chat
        val behavior = behaviors.getOrElse(index) { behaviors.last() }
        return behavior(index)
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/**
 * 重试事件探针：收集全量事件，并把 RetryStarted/RetryFinished 归约成「当前重试状态」
 * ——模拟 UI 侧 `RetryState` 的置位/清除，用于断言 T05 不变量「结束时无残留」。
 */
private class RetryProbe {
    private val lock = Any()
    private val events = mutableListOf<AgentEvent>()

    /** 当前重试状态（attempt to max）；null = 已清除。 */
    @Volatile
    var state: Pair<Int, Int>? = null
        private set

    /** RetryFinished 事件计数（正常应 ≤ 1，且与是否发生重试一致）。 */
    @Volatile
    var finishes: Int = 0
        private set

    fun onEvent(ev: AgentEvent) = synchronized(lock) {
        events += ev
        when (ev) {
            is AgentEvent.RetryStarted -> state = ev.attempt to ev.max
            is AgentEvent.RetryFinished -> {
                state = null
                finishes++
            }
            else -> Unit
        }
    }

    /** 重试序号序列（保持到达顺序）。 */
    fun retryAttempts(): List<Pair<Int, Int>> = synchronized(lock) {
        events.filterIsInstance<AgentEvent.RetryStarted>().map { it.attempt to it.max }
    }

    /** 每个重试事件的原因文本。 */
    fun reasons(): List<String> = synchronized(lock) {
        events.filterIsInstance<AgentEvent.RetryStarted>().map { it.reason }
    }

    /** 只保留重试相关事件的有序种类（start/finish），用于校验收尾时机。 */
    fun retryEventKinds(): List<String> = synchronized(lock) {
        events.mapNotNull {
            when (it) {
                is AgentEvent.RetryStarted -> "start"
                is AgentEvent.RetryFinished -> "finish"
                else -> null
            }
        }
    }
}

/* ------------------------------ 用例 ------------------------------ */

/** 用例 1：连续两次 500 后第 3 次成功——序列 (1/2)、(2/2)，最终结果正确，结束时状态清除。 */
private suspend fun testRetryThenSuccess() {
    println("\n== 用例 1：两次 500 后第 3 次成功 ==")

    check("回调序列 (1/2),(2/2) + 最终成功 + 结束时状态清除") {
        val probe = RetryProbe()
        val llm = FaultLlm(
            listOf(
                { throw LlmHttpException(500, "server boom") }, // 第 1 次
                { throw LlmHttpException(500, "server boom") }, // 第 2 次
                { AssistantMessage("恢复成功") }                  // 第 3 次
            )
        )
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent
        )

        val history = loop.send("hi")

        assertEquals(
            "最终回答",
            "恢复成功",
            history.filterIsInstance<AssistantMessage>().lastOrNull()?.content
        )
        assertEquals("重试序号序列", listOf(1 to 2, 2 to 2), probe.retryAttempts())
        assertEquals("请求次数 = 2 次失败 + 1 次成功", 3, llm.calls)
        assertTrue("每次重试原因非空", probe.reasons().all { it.isNotBlank() })
        assertEquals("收尾事件恰好一次", 1, probe.finishes)
        assertEquals("重试+收尾顺序", listOf("start", "start", "finish"), probe.retryEventKinds())
        assertTrue("结束时状态被清除（无残留「正在恢复」）", probe.state == null)
    }
}

/** 用例 2：401（不可重试）——一次即失败，无任何重试事件。 */
private suspend fun testNonRetryableFastFail() {
    println("\n== 用例 2：401 快速失败（不重试）==")

    check("401 → 请求 1 次、无重试事件、原异常上抛") {
        val probe = RetryProbe()
        val llm = FaultLlm(listOf({ throw LlmHttpException(401, "unauthorized") }))
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent
        )

        val thrown = catchThrowable("send 应抛 401") { loop.send("hi") }

        assertTrue("抛 LlmHttpException", thrown is LlmHttpException)
        assertEquals("状态码 401", 401, (thrown as LlmHttpException).code)
        assertEquals("请求次数（一次即失败，绝不白等 3 次）", 1, llm.calls)
        assertEquals("无 RetryStarted", emptyList<Pair<Int, Int>>(), probe.retryAttempts())
        assertEquals("无 RetryFinished 收尾", 0, probe.finishes)
        assertTrue("无残留状态", probe.state == null)
    }
}

/** 用例 3：重试期间取消——状态立即清除、CancellationException 上抛不被吞。 */
private suspend fun testCancelDuringRetry() {
    println("\n== 用例 3：重试期间取消 ==")

    check("取消 → 立即清状态、CancellationException 上抛不被吞") {
        coroutineScope {
            val probe = RetryProbe()
            val started = CompletableDeferred<Unit>()
            val onEvent: (AgentEvent) -> Unit = { ev ->
                probe.onEvent(ev)
                if (ev is AgentEvent.RetryStarted) started.complete(Unit)
            }
            val llm = FaultLlm(
                listOf(
                    { throw LlmHttpException(500, "boom") },       // 第 1 次：可重试失败
                    { delay(60_000); AssistantMessage("never") }    // 第 2 次：挂起直到被取消
                )
            )
            val loop = AgentLoop(
                llmProvider = { llm },
                registry = ToolRegistry(),
                providerRetry = 2,
                onEvent = onEvent
            )

            var thrown: Throwable? = null
            val job = launch {
                try {
                    loop.send("hi")
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                } catch (e: Throwable) {
                    thrown = e
                }
            }

            // 等第一次「准备重试」到达（第 2 次调用已挂起在 delay 中）
            withTimeout(5_000) { started.await() }
            assertEquals("重试期间状态已置位 (1/2)", 1 to 2, probe.state)

            job.cancel()
            job.join()

            assertTrue("上抛 CancellationException（未被吞）", thrown is CancellationException)
            assertTrue("取消后立即清状态（T05 不残留「正在恢复」）", probe.state == null)
            assertEquals("收尾事件恰好一次", 1, probe.finishes)
            assertEquals("请求次数（第 1 次快败 + 第 2 次被取消）", 2, llm.calls)
        }
    }
}

/** 用例 4：连续失败到上限——最终失败一次、状态清除、异常上抛。 */
private suspend fun testExhaustRetries() {
    println("\n== 用例 4：连续失败到上限 ==")

    check("全部 503 → 请求 3 次、(1/2)(2/2)、最终失败一次、状态清除、异常上抛") {
        val probe = RetryProbe()
        val llm = FaultLlm(listOf({ throw LlmHttpException(503, "unavailable") }))
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent
        )

        val thrown = catchThrowable("send 应抛 503") { loop.send("hi") }

        assertTrue("抛 LlmHttpException", thrown is LlmHttpException)
        assertEquals("状态码 503", 503, (thrown as LlmHttpException).code)
        assertEquals("请求次数 = providerRetry + 1", 3, llm.calls)
        assertEquals("重试序号序列", listOf(1 to 2, 2 to 2), probe.retryAttempts())
        assertEquals("收尾事件恰好一次（最终失败也算收尾）", 1, probe.finishes)
        assertTrue("最终失败后状态清除", probe.state == null)
    }
}

/** 用例 5：重试计数与 max 的边界。 */
private suspend fun testBoundaries() {
    println("\n== 用例 5：重试计数 / max 边界 ==")

    check("providerRetry=0 + 可重试失败 → 1 次请求、无重试事件") {
        val probe = RetryProbe()
        val llm = FaultLlm(listOf({ throw LlmHttpException(500, "boom") }))
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 0,
            onEvent = probe::onEvent
        )
        catchThrowable("send 应抛 500") { loop.send("hi") }
        assertEquals("请求次数", 1, llm.calls)
        assertEquals("无 RetryStarted", emptyList<Pair<Int, Int>>(), probe.retryAttempts())
        assertEquals("无 RetryFinished", 0, probe.finishes)
    }

    check("providerRetry=1 + 首次成功 → 1 次请求、无重试事件") {
        val probe = RetryProbe()
        val llm = FaultLlm(listOf({ AssistantMessage("ok") }))
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 1,
            onEvent = probe::onEvent
        )
        loop.send("hi")
        assertEquals("请求次数", 1, llm.calls)
        assertEquals("无 RetryStarted", emptyList<Pair<Int, Int>>(), probe.retryAttempts())
        assertTrue("无残留状态", probe.state == null)
    }

    check("providerRetry=1 + 首败次成 → 2 次请求、序列 (1/1)") {
        val probe = RetryProbe()
        val llm = FaultLlm(
            listOf(
                { throw LlmHttpException(500, "boom") },
                { AssistantMessage("ok") }
            )
        )
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 1,
            onEvent = probe::onEvent
        )
        loop.send("hi")
        assertEquals("请求次数", 2, llm.calls)
        assertEquals("重试序号序列", listOf(1 to 1), probe.retryAttempts())
        assertEquals("收尾一次", 1, probe.finishes)
    }

    check("网络异常（IOException）也视为可重试") {
        val probe = RetryProbe()
        val llm = FaultLlm(
            listOf(
                { throw java.net.SocketTimeoutException("timed out") },
                { AssistantMessage("网络恢复") }
            )
        )
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent
        )
        val history = loop.send("hi")
        assertEquals(
            "最终回答",
            "网络恢复",
            history.filterIsInstance<AssistantMessage>().lastOrNull()?.content
        )
        assertEquals("重试序号序列", listOf(1 to 2), probe.retryAttempts())
        assertTrue("原因指向网络", probe.reasons().first().contains("超时"))
    }

    check("429（可重试）→ 触发重试，原因指向限流") {
        val probe = RetryProbe()
        val llm = FaultLlm(
            listOf(
                { throw LlmHttpException(429, "rate limited") },
                { AssistantMessage("ok") }
            )
        )
        val loop = AgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent
        )
        loop.send("hi")
        assertEquals("重试序号序列", listOf(1 to 2), probe.retryAttempts())
        assertTrue("原因指向 429", probe.reasons().first().contains("429"))
    }
}

/* ------------------------------ 入口 ------------------------------ */

fun main() = runBlocking {
    println("== Phase 2 DoD③ 断网/失败自动恢复且 UI 可见 · QA 行为验证 ==")

    testRetryThenSuccess()
    testNonRetryableFastFail()
    testCancelDuringRetry()
    testExhaustRetries()
    testBoundaries()

    println("\n== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        println("失败用例：")
        failures.forEach { println("  - $it") }
        throw AssertionError("${failures.size} 个用例失败")
    }
    println("RETRY_VERIFICATION: ALL PASS")
}
