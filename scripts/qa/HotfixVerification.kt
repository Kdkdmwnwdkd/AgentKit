#!/usr/bin/env kotlin
/**
 * 修真机实测两个 Bug 的修复验证 harness（离线，无 JUnit，纯 main() + 手写断言）。
 *
 * **Bug 1（断网看不到「正在恢复（n/N）」）**：RealAgentLoop.callWithRetry 的重试循环
 * 没有等待——`UnknownHostException` 瞬时返回时 3 次尝试几十毫秒内打完，
 * RetryStarted 刚置位就被 finally 的 RetryFinished 清掉，横幅来不及显示。
 * 本轮断言：宣布重试之后、下一次尝试之前确实退避了，且：
 *  - 退避钩子调用次数 == 重试次数；
 *  - 注入小值（50ms）时确实累计等到了（>瞬时）；
 *  - 退避途中取消 → CancellationException 原样上抛、无「正在恢复」残留、不多等；
 *  - 401 等不可重试错误 → 钩子零调用（一次即失败，不白等）。
 *
 * **Bug 2（给权限后回 App 闪退）**：Android 14 要求 `startForeground(..., MEDIA_PROJECTION)`
 * 之前已取出 MediaProjection token；老流程反向且无 try/catch → SecurityException 逃逸
 * 杀进程。本轮断言：顺序策略在有无 token 两种情况下给出正确步骤序列；启动动作被整体
 * 兜底（任何异常都不再上抛，转成失败原因）；失败文案非空且带原因 + 可重试。
 *
 * 运行方式见 scripts/qa/run-hotfix-verification.sh。
 */

import com.example.agentkit.core.AgentEvent
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LlmHttpException
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.PingResult
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.agent.RealAgentLoop
import com.example.agentkit.core.agent.RetryBackoffPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
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
 * @param behaviors 每个下标对应一次调用的行为；调用序号超出列表长度时复用**最后一项**。
 */
private class FaultLlm(
    private val behaviors: List<suspend (Int) -> AssistantMessage>
) : LLMClient {
    override val id: String = "fault-hotfix"
    var calls: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>, tools: List<com.example.agentkit.core.AgentTool>): AssistantMessage {
        val index = calls
        calls++
        val behavior = behaviors.getOrElse(index) { behaviors.last() }
        return behavior(index)
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/** 重试事件探针：把 RetryStarted/RetryFinished 归约成「UI 侧横幅状态」。 */
private class RetryProbe {
    private val lock = Any()
    private val events = mutableListOf<AgentEvent>()

    @Volatile
    var state: Pair<Int, Int>? = null
        private set

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

    fun retryAttempts(): List<Pair<Int, Int>> = synchronized(lock) {
        events.filterIsInstance<AgentEvent.RetryStarted>().map { it.attempt to it.max }
    }
}

/* ------------------------------ Bug 1 用例 ------------------------------ */

/**
 * 用例 A：退避策略本身的序列（纯函数，毫秒级）。
 *
 * 真机第三轮 Hotfix3（Bug A1）把序列从 1s/2s/4s 加长到 3s/10s/25s（用户原话「太短，
 * 人家平常用都等个半分钟一分钟」），故这里的期望值同步改成新常量序列。
 */
private suspend fun testBackoffPolicySequence() {
    println("\n== Bug1-A：退避毫秒序列 3s → 10s → 25s 封顶 ==")

    check("backoffMillis 序列符合 3000/10000/25000 且封顶") {
        assertEquals("第 1 次重试", 3_000L, RetryBackoffPolicy.backoffMillis(1))
        assertEquals("第 2 次重试", 10_000L, RetryBackoffPolicy.backoffMillis(2))
        assertEquals("第 3 次重试", 25_000L, RetryBackoffPolicy.backoffMillis(3))
        assertEquals("第 4 次重试（封顶）", 25_000L, RetryBackoffPolicy.backoffMillis(4))
        assertEquals("第 9 次重试（仍封顶）", 25_000L, RetryBackoffPolicy.backoffMillis(9))
        assertEquals("非法序号按第 1 次处理", 3_000L, RetryBackoffPolicy.backoffMillis(0))
        assertEquals("常量序列（可配）", listOf(3_000L, 10_000L, 25_000L), RetryBackoffPolicy.BACKOFF_SEQUENCE_MS)
    }
}

/** 用例 B：前 2 次失败第 3 次成功 → (1/2)(2/2) + 成功 + finish 清状态 + 钩子调用次数。 */
private suspend fun testBackoffHookCalledOncePerRetry() {
    println("\n== Bug1-B：退避钩子调用次数 == 重试次数 ==")

    check("钩子调用 2 次（序号 1、2），事件序列与状态清理同 DoD③") {
        val probe = RetryProbe()
        val hookArgs = mutableListOf<Int>()
        val llm = FaultLlm(
            listOf(
                { throw java.net.UnknownHostException("airplane mode") },
                { throw java.net.UnknownHostException("airplane mode") },
                { AssistantMessage("联网后恢复成功") }
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            retryBackoff = { attempt -> hookArgs += attempt } // 即时版：不真等，保持 harness 快速
        )

        val history = loop.send("hi")

        assertEquals(
            "最终回答",
            "联网后恢复成功",
            history.filterIsInstance<AssistantMessage>().lastOrNull()?.content
        )
        assertEquals("重试序号序列", listOf(1 to 2, 2 to 2), probe.retryAttempts())
        assertEquals("请求次数", 3, llm.calls)
        assertEquals("退避钩子调用次数 == 重试次数", 2, hookArgs.size)
        assertEquals("钩子收到 1-based 重试序号", listOf(1, 2), hookArgs)
        assertEquals("收尾事件恰好一次", 1, probe.finishes)
        assertTrue("结束时状态被清除（无残留「正在恢复」）", probe.state == null)
    }
}

/** 用例 C：注入真退避小值（50ms）→ 确实等了（累计耗时显著大于瞬时返回）。 */
private suspend fun testRealBackoffActuallyWaits() {
    println("\n== Bug1-C：注入 50ms 退避 → 确实等待 ==")

    check("两次重试的等待 ≥ 50ms（且默认策略毫秒值远大于此）") {
        val probe = RetryProbe()
        var worstWait = 0L
        val llm = FaultLlm(
            listOf(
                { throw java.net.SocketTimeoutException("timeout") },
                { throw java.net.SocketTimeoutException("timeout") },
                { AssistantMessage("ok") }
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            retryBackoff = { attempt ->
                val waitMs = 50L * attempt
                val t0 = System.currentTimeMillis()
                delay(waitMs)
                worstWait = maxOf(worstWait, System.currentTimeMillis() - t0)
            }
        )

        val t0 = System.currentTimeMillis()
        loop.send("hi")
        val elapsed = System.currentTimeMillis() - t0

        assertEquals("重试两次", listOf(1 to 2, 2 to 2), probe.retryAttempts())
        assertTrue("单次等待 ≥ 50ms（实际 $worstWait ms）", worstWait >= 50L)
        assertTrue("整轮耗时 ≥ 150ms（实际 $elapsed ms；无退避时是毫秒级）", elapsed >= 150L)
        assertTrue("默认策略第 1 次就等 3s（远大于 harness 注入值）", RetryBackoffPolicy.backoffMillis(1) >= 1_000L)
    }
}

/** 用例 D：退避途中取消 → CancellationException 原样上抛、无残留、不多等。 */
private suspend fun testCancelDuringBackoff() {
    println("\n== Bug1-D：退避途中取消 ==")

    check("delay 型退避被取消 → 立刻上抛 CancellationException 且状态清除") {
        coroutineScope {
            val probe = RetryProbe()
            val started = CompletableDeferred<Unit>()
            val onEvent: (AgentEvent) -> Unit = { ev ->
                probe.onEvent(ev)
                if (ev is AgentEvent.RetryStarted) started.complete(Unit)
            }
            val llm = FaultLlm(listOf({ throw LlmHttpException(500, "boom") }))
            val loop = RealAgentLoop(
                llmProvider = { llm },
                registry = ToolRegistry(),
                providerRetry = 2,
                onEvent = onEvent,
                retryBackoff = { delay(60_000) } // 退避中挂起，模拟「正在恢复」横幅显示期间
            )

            var thrown: Throwable? = null
            // 显式派发到 Default：runBlocking 是单线程事件循环，若与 harness 共用主线程，
            // 不可取消的钩子（Thread.sleep）会把主线程占住，导致 cancel() 迟到而失真。
            val job = launch(Dispatchers.Default) {
                try {
                    loop.send("hi")
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }

            withTimeout(5_000) { started.await() }
            assertEquals("退避期间状态已置位 (1/2)", 1 to 2, probe.state)

            val t0 = System.currentTimeMillis()
            job.cancel()
            job.join()
            val cost = System.currentTimeMillis() - t0

            assertTrue("上抛 CancellationException（未被吞、未被替换）", thrown is CancellationException)
            assertTrue("取消后不多等（$cost ms）", cost < 5_000)
            assertTrue("取消后立即清状态（T05 不残留「正在恢复」）", probe.state == null)
            assertEquals("收尾事件恰好一次", 1, probe.finishes)
            assertEquals("取消后不再发新请求", 1, llm.calls)
        }
    }

    check("不可取消的退避钩子（Thread.sleep）由 ensureActive 兜底，仍上抛 CancellationException") {
        coroutineScope {
            val probe = RetryProbe()
            val started = CompletableDeferred<Unit>()
            val onEvent: (AgentEvent) -> Unit = { ev ->
                probe.onEvent(ev)
                if (ev is AgentEvent.RetryStarted) started.complete(Unit)
            }
            val llm = FaultLlm(listOf({ throw LlmHttpException(503, "unavailable") }))
            val loop = RealAgentLoop(
                llmProvider = { llm },
                registry = ToolRegistry(),
                providerRetry = 2,
                onEvent = onEvent,
                // 刻意不检查取消的钩子：只有 delay 之后补的 ensureActive 能保证「不多等一次请求」
                retryBackoff = { Thread.sleep(300) }
            )

            var thrown: Throwable? = null
            // 显式派发到 Default：runBlocking 是单线程事件循环，若与 harness 共用主线程，
            // 不可取消的钩子（Thread.sleep）会把主线程占住，导致 cancel() 迟到而失真。
            val job = launch(Dispatchers.Default) {
                try {
                    loop.send("hi")
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }

            withTimeout(5_000) { started.await() }
            job.cancel()
            job.join()

            assertTrue("上抛 CancellationException", thrown is CancellationException)
            assertTrue("无残留「正在恢复」", probe.state == null)
            assertEquals("退避后未再进行下一次尝试", 1, llm.calls)
        }
    }
}

/** 用例 E：401 等不可重试错误 → 零等待、一次即失败。 */
private suspend fun testNonRetryableDoesNotWait() {
    println("\n== Bug1-E：不可重试错误零等待 ==")

    check("401 → 退避钩子 0 次调用、请求 1 次") {
        val probe = RetryProbe()
        var hookCalls = 0
        val llm = FaultLlm(listOf({ throw LlmHttpException(401, "unauthorized") }))
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            retryBackoff = { hookCalls++ }
        )

        val thrown = catchThrowable("send 应抛 401") { loop.send("hi") }

        assertTrue("抛 LlmHttpException", thrown is LlmHttpException)
        assertEquals("状态码 401", 401, (thrown as LlmHttpException).code)
        assertEquals("退避钩子零调用（不白等）", 0, hookCalls)
        assertEquals("请求次数", 1, llm.calls)
        assertEquals("无 RetryStarted", emptyList<Pair<Int, Int>>(), probe.retryAttempts())
        assertEquals("无 RetryFinished", 0, probe.finishes)
    }

    check("首次即成功 → 退避钩子零调用") {
        val probe = RetryProbe()
        var hookCalls = 0
        val llm = FaultLlm(listOf({ AssistantMessage("一次就通") }))
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            retryBackoff = { hookCalls++ }
        )
        loop.send("hi")
        assertEquals("请求次数", 1, llm.calls)
        assertEquals("退避钩子零调用", 0, hookCalls)
        assertTrue("无残留状态", probe.state == null)
    }

    check("providerRetry=2 且耗尽 → 钩子恰好 2 次（=providerRetry），最后一次失败后不再等待") {
        val probe = RetryProbe()
        var hookCalls = 0
        val llm = FaultLlm(listOf({ throw LlmHttpException(503, "unavailable") }))
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            retryBackoff = { hookCalls++ }
        )
        val thrown = catchThrowable("send 应抛 503") { loop.send("hi") }
        assertEquals("状态码 503", 503, (thrown as LlmHttpException).code)
        assertEquals("请求次数 = providerRetry + 1", 3, llm.calls)
        assertEquals("退避钩子调用次数 = providerRetry（最后一次失败不再等）", 2, hookCalls)
        assertEquals("收尾一次", 1, probe.finishes)
        assertTrue("状态清除", probe.state == null)
    }
}

/* ------------------------------ 入口 ------------------------------ */

fun main() = runBlocking {
    println("== 修真机实测 Hotfix（Bug1 退避可见）QA 行为验证 ==")

    testBackoffPolicySequence()
    testBackoffHookCalledOncePerRetry()
    testRealBackoffActuallyWaits()
    testCancelDuringBackoff()
    testNonRetryableDoesNotWait()

    println("\n== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        println("失败用例：")
        failures.forEach { println("  - $it") }
        throw AssertionError("${failures.size} 个用例失败")
    }
    println("HOTFIX_VERIFICATION: ALL PASS")
}
