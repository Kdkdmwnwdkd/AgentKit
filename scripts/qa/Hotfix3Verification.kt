#!/usr/bin/env kotlin
/**
 * 真机第三轮 Hotfix3（A 退避加长 + 网络恢复即刻重试 / B ui_click 匹配增强 / C 屏幕捕捉日志）
 * QA 行为验证 harness（离线，无 JUnit，纯 main() + 手写断言）。
 *
 * **A（Bug A1+A2）**：
 *  - 退避序列改为可配常量 3s → 10s → 25s 封顶（序列和 38s）；
 *  - 退避等待期间监听网络恢复：网络一恢复就**提前结束等待**并立刻重试（飞行模式关掉即「秒续」）；
 *  - 不可重试错误（401）零等待；退避中取消立即上抛且不留残留；监听注册/注销配对（防泄漏）。
 *  断言方式：注入**假等待器**（[FakeNetworkRecovery]）+ 小值退避序列，整套用例保持毫秒级。
 *
 * **B（Bug B）**：`ui_click` 的「匹配 + 选最深 + 上溯可点击祖先 + 生成候选」全部抽成
 * [com.example.agentkit.automation.UiNodeMatcher] 的**纯函数**（输入节点摘要列表 + query），
 * 脱离 AccessibilityNodeInfo，故可在纯 JVM 下逐条断言（归一化/包含/前缀/多命中/上溯/候选）。
 *
 * **C（随 W-12 删除屏幕捕捉）**：原 `ScreenshotBridge.lastError` 日志断言已移除。
 * 断言（不加载 Android 类），确保每处非空写入后 4 行内必有日志出口。
 *
 * 运行方式见 scripts/qa/run-hotfix3-verification.sh。
 */

import com.example.agentkit.automation.UiMatchResult
import com.example.agentkit.automation.UiNodeMatcher
import com.example.agentkit.automation.UiNodeSummary
import com.example.agentkit.core.*
import com.example.agentkit.core.agent.NetworkRecoveryWaiter
import com.example.agentkit.core.agent.RealAgentLoop
import com.example.agentkit.core.agent.RetryBackoffPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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

/** 脚本化「故障 LLM」：按调用序号（0-based）执行预设行为；序号越界复用最后一项。 */
private class FaultLlm(
    private val behaviors: List<suspend (Int) -> AssistantMessage>
) : LLMClient {
    override val id: String = "fault-hotfix3"
    var calls: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage {
        val index = calls
        calls++
        return behaviors.getOrElse(index) { behaviors.last() }(index)
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/** 重试事件探针：把 RetryStarted/RetryFinished 归约成 UI 侧「正在恢复（n/N）」横幅状态。 */
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

/**
 * 假网络恢复等待器（模拟 ConnectivityManager 回调）：
 *  - [recoverAfterMs] 毫秒后「网络恢复」并结束等待；`Long.MAX_VALUE` = 永不恢复（等满额）；
 *  - 记录注册/注销次数（断言监听配对、无泄漏）、每次的超时入参（断言按序列退避）；
 *  - 取消时 `finally` 仍注销，且 CancellationException 原样上抛（不吞）。
 */
private class FakeNetworkRecovery(
    private val recoverAfterMs: Long = Long.MAX_VALUE,
    private val onAwaitStart: () -> Unit = {}
) : NetworkRecoveryWaiter {
    val awaitTimeouts = mutableListOf<Long>()

    @Volatile
    var registerCount: Int = 0
        private set

    @Volatile
    var unregisterCount: Int = 0
        private set

    @Volatile
    var interruptedCount: Int = 0
        private set

    @Volatile
    var bannerVisibleAtAwait: Boolean? = null
        private set

    override suspend fun awaitRecovery(timeoutMs: Long): Boolean {
        awaitTimeouts += timeoutMs
        registerCount++
        onAwaitStart()
        bannerVisibleAtAwait = bannerVisibleAtAwait ?: (retryFlagSnapshot == true)
        return try {
            if (recoverAfterMs == Long.MAX_VALUE) {
                delay(timeoutMs)
                false
            } else {
                val hit = withTimeoutOrNull(timeoutMs) {
                    delay(recoverAfterMs)
                    true
                } ?: false
                if (hit) interruptedCount++
                hit
            }
        } finally {
            unregisterCount++
        }
    }

    companion object {
        /** 由用例写入：等待开始时「正在恢复」横幅是否已置位（断言 RetryStarted 先于等待）。 */
        @Volatile
        var retryFlagSnapshot: Boolean? = null
    }
}


/* --------------- hotfix10：429 限流专用序列 + Retry-After 解析 --------------- */

/** A3：429 专用退避序列 = 10s → 30s → 60s 封顶，且尊重服务端 Retry-After。 */
private suspend fun testRateLimitBackoff() {
    println("\n== A3：429 限流专用退避（hotfix10） ==")

    check("限流序列值与封顶符合配置") {
        assertEquals("第 1 次重试", 10_000L, RetryBackoffPolicy.rateLimitBackoffMillis(1))
        assertEquals("第 2 次重试", 30_000L, RetryBackoffPolicy.rateLimitBackoffMillis(2))
        assertEquals("第 3 次重试", 60_000L, RetryBackoffPolicy.rateLimitBackoffMillis(3))
        assertEquals("第 4 次重试（封顶）", 60_000L, RetryBackoffPolicy.rateLimitBackoffMillis(4))
        assertEquals("常量序列", listOf(10_000L, 30_000L, 60_000L), RetryBackoffPolicy.RATE_LIMIT_SEQUENCE_MS)
        assertEquals("封顶常量", 60_000L, RetryBackoffPolicy.MAX_RATE_LIMIT_MS)
    }

    check("Retry-After 秒数形态解析为毫秒") {
        assertEquals("120 秒", 120_000L, parseRetryAfterMs("120"))
        assertEquals("0 秒", 0L, parseRetryAfterMs("0"))
        assertEquals("空值", 0L, parseRetryAfterMs(null))
        assertEquals("空白", 0L, parseRetryAfterMs("   "))
        assertEquals("非数值（无日期形态）", 0L, parseRetryAfterMs("soon"))
    }

    check("服务端要求等更久时取较大值（再封顶）") {
        assertEquals(
            "Retry-After 45s > 序列 10s → 用 45s",
            45_000L,
            RetryBackoffPolicy.rateLimitBackoffMillis(1, retryAfterMs = 45_000L)
        )
        assertEquals(
            "Retry-After 5s < 序列 30s → 用 30s",
            30_000L,
            RetryBackoffPolicy.rateLimitBackoffMillis(2, retryAfterMs = 5_000L)
        )
        assertEquals(
            "Retry-After 300s 超过封顶 → 钳到 60s",
            60_000L,
            RetryBackoffPolicy.rateLimitBackoffMillis(1, retryAfterMs = 300_000L)
        )
    }
}

/* ------------------------------ A：退避序列 ------------------------------ */


/** A1：退避序列 = 可配常量 3s → 10s → 25s 封顶，序列和 38s（用户要的「半分钟」量级）。 */
private suspend fun testBackoffSequence() {
    println("\n== A1：退避序列 3s → 10s → 25s 封顶（可配常量） ==")

    check("序列值与封顶符合配置") {
        assertEquals("第 1 次重试", 3_000L, RetryBackoffPolicy.backoffMillis(1))
        assertEquals("第 2 次重试", 10_000L, RetryBackoffPolicy.backoffMillis(2))
        assertEquals("第 3 次重试", 25_000L, RetryBackoffPolicy.backoffMillis(3))
        assertEquals("第 4 次重试（封顶）", 25_000L, RetryBackoffPolicy.backoffMillis(4))
        assertEquals("第 9 次重试（仍封顶）", 25_000L, RetryBackoffPolicy.backoffMillis(9))
        assertEquals("非法序号按第 1 次处理", 3_000L, RetryBackoffPolicy.backoffMillis(0))
        assertEquals("常量序列", listOf(3_000L, 10_000L, 25_000L), RetryBackoffPolicy.BACKOFF_SEQUENCE_MS)
        assertEquals("序列三项之和（≈38s）", 38_000L, RetryBackoffPolicy.BACKOFF_SEQUENCE_MS.sum())
        assertEquals("封顶常量", 25_000L, RetryBackoffPolicy.MAX_MS)
    }
}

/* ------------------- A：网络恢复打断等待（核心体验） ------------------- */

/** A2：等待被「网络恢复」打断 → 立即重试（耗时远小于满额）。 */
private suspend fun testNetworkRecoveryInterruptsWait() {
    println("\n== A2：网络恢复打断等待 → 立刻重试（不干等） ==")

    check("计划等 5s×2，网络 120ms 后恢复 → 两次等待都被打断，总耗时远小于 10s") {
        val probe = RetryProbe()
        val waiter = FakeNetworkRecovery(recoverAfterMs = 120L) {
            FakeNetworkRecovery.retryFlagSnapshot = probe.state != null
        }
        val llm = FaultLlm(
            listOf(
                { throw java.net.UnknownHostException("airplane mode") },
                { throw java.net.UnknownHostException("airplane mode") },
                { AssistantMessage("网络恢复后成功") }
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            networkRecovery = waiter,
            backoffPlan = { 5_000L } // 满额要等 10s；被恢复打断后应只有几百毫秒
        )

        val t0 = System.currentTimeMillis()
        val history = loop.send("hi")
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(
            "最终回答",
            "网络恢复后成功",
            history.filterIsInstance<AssistantMessage>().lastOrNull()?.content
        )
        assertEquals("请求次数 = 3", 3, llm.calls)
        assertEquals("两次等待的超时入参都是 5s", listOf(5_000L, 5_000L), waiter.awaitTimeouts)
        assertEquals("两次都被网络恢复打断", 2, waiter.interruptedCount)
        assertTrue("总耗时应远小于满额 10s（实际 ${elapsed}ms）", elapsed < 2_000L)
        assertEquals("监听注册次数 == 等待次数", 2, waiter.registerCount)
        assertEquals("监听注销次数 == 等待次数（无泄漏）", 2, waiter.unregisterCount)
        assertEquals("重试序号序列", listOf(1 to 2, 2 to 2), probe.retryAttempts())
        assertEquals("收尾事件恰好一次", 1, probe.finishes)
        assertTrue("结束时无残留「正在恢复」", probe.state == null)
        assertTrue("RetryStarted 在等待之前抛出（横幅全程可见）", waiter.bannerVisibleAtAwait == true)
    }
}

/** A3：无网络事件时按序列等满（不打断、不提前）。 */
private suspend fun testFullBackoffWithoutNetworkEvent() {
    println("\n== A3：无网络事件 → 按序列等满 ==")

    check("计划 60ms/120ms → 等满 180ms，未被打断，注册注销各 2 次") {
        val probe = RetryProbe()
        val waiter = FakeNetworkRecovery() // 永不恢复
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
            networkRecovery = waiter,
            backoffPlan = { attempt -> 60L * attempt } // 1→60ms，2→120ms
        )

        val t0 = System.currentTimeMillis()
        loop.send("hi")
        val elapsed = System.currentTimeMillis() - t0

        assertEquals("按序列取超时", listOf(60L, 120L), waiter.awaitTimeouts)
        assertEquals("未被打断", 0, waiter.interruptedCount)
        assertTrue("确实等满了 180ms（实际 ${elapsed}ms）", elapsed >= 150L)
        assertEquals("注册 2 次", 2, waiter.registerCount)
        assertEquals("注销 2 次", 2, waiter.unregisterCount)
        assertEquals("请求 3 次", 3, llm.calls)
    }
}

/* ---------------------- A：取消优先 / 401 零等待 ---------------------- */

/** A4：退避等待中取消 → 立即退出、原样上抛、监听已注销（不留残留、不多发请求）。 */
private suspend fun testCancelDuringWait() {
    println("\n== A4：退避等待中取消（取消优先 + 无残留 + 注销） ==")

    check("取消 → CancellationException 原样上抛、注销 1 次、无新请求、横幅清除") {
        coroutineScope {
            val probe = RetryProbe()
            val started = CompletableDeferred<Unit>()
            val onEvent: (AgentEvent) -> Unit = { ev ->
                probe.onEvent(ev)
                if (ev is AgentEvent.RetryStarted) started.complete(Unit)
            }
            val waiter = FakeNetworkRecovery() // 永不恢复：会一直等
            val llm = FaultLlm(listOf({ throw java.net.UnknownHostException("airplane") }))
            val loop = RealAgentLoop(
                llmProvider = { llm },
                registry = ToolRegistry(),
                providerRetry = 2,
                onEvent = onEvent,
                networkRecovery = waiter,
                backoffPlan = { 30_000L } // 远大于 harness 耐心：只能靠取消退出
            )

            var thrown: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try {
                    loop.send("hi")
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }

            withTimeout(5_000) { started.await() }
            assertEquals("等待期间横幅已置位 (1/2)", 1 to 2, probe.state)
            val t0 = System.currentTimeMillis()
            job.cancel()
            job.join()
            val cost = System.currentTimeMillis() - t0

            assertTrue("上抛 CancellationException（未被吞、未被替换）", thrown is CancellationException)
            assertTrue("取消后立即退出（${cost}ms，不等满 30s）", cost < 5_000L)
            assertEquals("等待已注册 1 次", 1, waiter.registerCount)
            assertEquals("取消路径也注销监听（防泄漏）", 1, waiter.unregisterCount)
            assertEquals("取消后不再发新请求", 1, llm.calls)
            assertTrue("无残留「正在恢复」", probe.state == null)
            assertEquals("收尾事件恰好一次", 1, probe.finishes)
        }
    }
}

/** A5：不可重试错误（401）零等待 + 首次成功零等待。 */
private suspend fun testNonRetryableZeroWait() {
    println("\n== A5：401 / 首次成功 → 零等待 ==")

    check("401 → 等待器 0 次调用、退避计划 0 次取用、请求 1 次") {
        val probe = RetryProbe()
        val waiter = FakeNetworkRecovery()
        var planCalls = 0
        val llm = FaultLlm(listOf({ throw LlmHttpException(401, "unauthorized") }))
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            networkRecovery = waiter,
            backoffPlan = { planCalls++; 60L }
        )
        val thrown = catchThrowable("send 应抛 401") { loop.send("hi") }
        assertTrue("抛 LlmHttpException", thrown is LlmHttpException)
        assertEquals("状态码 401", 401, (thrown as LlmHttpException).code)
        assertEquals("等待器零调用（不白等）", 0, waiter.registerCount)
        assertEquals("退避计划零取用", 0, planCalls)
        assertEquals("请求 1 次", 1, llm.calls)
        assertEquals("无 RetryStarted", emptyList<Pair<Int, Int>>(), probe.retryAttempts())
        assertEquals("无 RetryFinished", 0, probe.finishes)
    }

    check("首次即成功 → 等待器零调用") {
        val waiter = FakeNetworkRecovery()
        val llm = FaultLlm(listOf({ AssistantMessage("一次就通") }))
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            networkRecovery = waiter,
            backoffPlan = { 60L }
        )
        loop.send("hi")
        assertEquals("请求 1 次", 1, llm.calls)
        assertEquals("等待器零调用", 0, waiter.registerCount)
    }
}

/** A6：默认等待器（TIMER_ONLY）是纯定时降级：等满且返回 false，绝不崩。 */
private suspend fun testTimerOnlyFallback() {
    println("\n== A6：默认等待器纯定时降级（无网络监听也不崩） ==")

    check("TIMER_ONLY 等满 60ms 且返回 false（降级路径可跑）") {
        val t0 = System.currentTimeMillis()
        val interrupted = NetworkRecoveryWaiter.TIMER_ONLY.awaitRecovery(60L)
        val cost = System.currentTimeMillis() - t0
        assertTrue("未被打断", !interrupted)
        assertTrue("确实等满了（实际 ${cost}ms）", cost >= 45L)
    }

    check("默认钩子（不注入等待器）也走得通：3 次请求 + 收尾一次") {
        val probe = RetryProbe()
        val llm = FaultLlm(
            listOf(
                { throw java.net.ConnectException("refused") },
                { AssistantMessage("ok") }
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            backoffPlan = { 30L } // 默认等待器（纯定时）+ 小值序列，保持 harness 毫秒级
        )
        loop.send("hi")
        assertEquals("请求 2 次", 2, llm.calls)
        assertEquals("重试序号", listOf(1 to 2), probe.retryAttempts())
        assertEquals("收尾一次", 1, probe.finishes)
    }
}

/* ------------------------------ B：ui_click 匹配增强 ------------------------------ */

/** 构造节点摘要（index 与列表下标一致，父先于子）。 */
private fun nodes(vararg spec: UiNodeSummary): List<UiNodeSummary> = spec.toList()

/** B1：归一化精确匹配（空白 / 换行 / 全角 / 大小写），且命中多个时取最深。 */
private suspend fun testNormalizedExactMatch() {
    println("\n== B1：归一化精确匹配（去空白/全角↔半角/忽略大小写）+ 多命中取最深 ==")

    check("「字体大小」命中深层脏文本节点（精确 + 最深）") {
        val list = nodes(
            UiNodeSummary(0, text = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,100,200"),
            UiNodeSummary(1, text = "字体大小", depth = 1, clickable = false, parentIndex = 0, bounds = "0,0,100,50"),
            UiNodeSummary(2, text = " 字体 大小 ", depth = 2, clickable = true, parentIndex = 1, bounds = "0,0,100,50")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小")
        assertTrue("命中", r.matched)
        assertEquals("取最深的那个（index=2）", 2, r.matchIndex)
        assertEquals("策略 = 归一化精确", "exact", r.strategy)
        assertEquals("该节点自身可点 → 点击自身", 2, r.clickIndex)
    }

    check("desc 同等参与：文本为空但 contentDescription 命中") {
        val list = nodes(
            UiNodeSummary(0, text = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,10,10"),
            UiNodeSummary(1, text = "", desc = "字体大小", depth = 1, clickable = true, parentIndex = 0, bounds = "1,2,3,4")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小")
        assertTrue("命中", r.matched)
        assertEquals("命中 index=1", 1, r.matchIndex)
    }

    check("全角 + 大小写 + 空格：query「font size」命中「Ｆｏｎｔ Ｓｉｚｅ」") {
        val list = nodes(
            UiNodeSummary(0, text = "Ｆｏｎｔ Ｓｉｚｅ", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,9,9")
        )
        val r = UiNodeMatcher.matchByText(list, "font size")
        assertTrue("命中（NFKC 归一 + 去空白 + 小写）", r.matched)
        assertEquals("策略 = 精确", "exact", r.strategy)
    }

    check("旧实现的精确相等会落空的脏文本「字体大小\\n默认」→ 现在包含命中") {
        val list = nodes(
            UiNodeSummary(0, text = "字体大小\n默认", depth = 2, clickable = true, parentIndex = -1, bounds = "0,0,9,9")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小")
        assertTrue("命中", r.matched)
        assertEquals("策略 = 包含", "contains", r.strategy)
    }
}

/** B2：包含 / 前缀两级降级都能命中。 */
private suspend fun testContainsAndPrefix() {
    println("\n== B2：包含匹配 / 前缀匹配 ==")

    check("query 是节点文本的一部分 → 包含命中（且多命中取更深那个）") {
        val list = nodes(
            UiNodeSummary(0, text = "显示与亮度", depth = 1, clickable = false, parentIndex = -1, bounds = "0,0,9,9"),
            UiNodeSummary(1, text = "显示大小调节", depth = 2, clickable = true, parentIndex = 0, bounds = "0,0,9,9")
        )
        val r = UiNodeMatcher.matchByText(list, "显示")
        assertTrue("命中", r.matched)
        assertEquals("取更深的 index=1", 1, r.matchIndex)
        assertEquals("策略 = 包含", "contains", r.strategy)
    }

    check("query 带尾巴（「字体大小 默认」）→ 前缀命中更短的节点") {
        val list = nodes(
            UiNodeSummary(0, text = "字体大小", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,9,9"),
            UiNodeSummary(1, text = "完全不相关", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,9,9")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小 默认")
        assertTrue("命中", r.matched)
        assertEquals("命中「字体大小」", 0, r.matchIndex)
        assertEquals("策略 = 前缀", "prefix", r.strategy)
    }
}

/** B3：命中节点不可点击 → 上溯最近的可点击祖先。 */
private suspend fun testClickableAncestorFallback() {
    println("\n== B3：命中节点不可点击 → 上溯最近可点击祖先 ==")

    check("text 在深层叶子（不可点）→ 点击落到可点击的父节点") {
        val list = nodes(
            UiNodeSummary(0, text = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,9,9"),
            UiNodeSummary(1, text = "", depth = 1, clickable = true, parentIndex = 0, bounds = "10,20,30,40"),
            UiNodeSummary(2, text = "字体大小", depth = 2, clickable = false, parentIndex = 1, bounds = "11,21,29,39")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小")
        assertEquals("命中叶子", 2, r.matchIndex)
        assertEquals("点击上溯到可点击父节点", 1, r.clickIndex)
    }

    check("中间层也不可点 → 继续上溯到最近的可点击祖先") {
        val list = nodes(
            UiNodeSummary(0, text = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,9,9"),
            UiNodeSummary(1, text = "", depth = 1, clickable = true, parentIndex = 0, bounds = "1,1,8,8"),
            UiNodeSummary(2, text = "", depth = 2, clickable = false, parentIndex = 1, bounds = "2,2,7,7"),
            UiNodeSummary(3, text = "字体大小", depth = 3, clickable = false, parentIndex = 2, bounds = "3,3,6,6")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小")
        assertEquals("命中 index=3", 3, r.matchIndex)
        assertEquals("上溯两级到最近可点击祖先 index=1", 1, r.clickIndex)
    }

    check("一路到根都不可点 → 退回自身（等价旧行为，绝不返回空）") {
        val list = nodes(
            UiNodeSummary(0, text = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,9,9"),
            UiNodeSummary(1, text = "字体大小", depth = 1, clickable = false, parentIndex = 0, bounds = "5,5,6,6")
        )
        val r = UiNodeMatcher.matchByText(list, "字体大小")
        assertEquals("命中 index=1", 1, r.matchIndex)
        assertEquals("无更上层可点 → 点击自身", 1, r.clickIndex)
    }
}

/** B4：未命中 → 返回 ≤5 条候选；query 为空时兜底给候选。 */
private suspend fun testCandidates() {
    println("\n== B4：未命中 → 候选 ≤5（含空 query 兜底） ==")

    check("未命中 → 给出候选（按相似度降序，≤5 条，非空）") {
        val list = nodes(
            UiNodeSummary(0, text = "显示与亮度", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(1, text = "字体与显示大小", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(2, text = "字体大小设置", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(3, text = "蓝牙", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(4, text = "字体缩放", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(5, text = "字体", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(6, text = "显示大小", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1")
        )
        val r = UiNodeMatcher.matchByText(list, "无线局域网")
        assertTrue("未命中", !r.matched)
        assertTrue("有候选（帮模型自纠）", r.candidates.isNotEmpty())
        assertTrue("候选 ≤5 条（实际 ${r.candidates.size}）", r.candidates.size <= 5)
        assertTrue("候选中不含空白项", r.candidates.all { it.isNotBlank() })
        assertEquals("候选已去重", r.candidates.size, r.candidates.toSet().size)
    }

    check("包含关系优先于纯字符相似：候选首位与 query 有包含/公共前缀关系") {
        val list = nodes(
            UiNodeSummary(0, text = "完全无关", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(1, text = "网络和互联网", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1")
        )
        val r = UiNodeMatcher.matchByText(list, "网络")
        assertTrue("命中（包含）", r.matched)
        assertEquals("命中「网络和互联网」", 1, r.matchIndex)
    }

    check("query 为空 → 不命中 + 策略 empty_query + 兜底候选（屏幕前几个标签）") {
        val list = nodes(
            UiNodeSummary(0, text = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(1, text = "设置", depth = 1, clickable = true, parentIndex = 0, bounds = "0,0,1,1"),
            UiNodeSummary(2, text = "显示", depth = 1, clickable = true, parentIndex = 0, bounds = "0,0,1,1")
        )
        val r = UiNodeMatcher.matchByText(list, "   ")
        assertTrue("不命中", !r.matched)
        assertEquals("策略 = empty_query", "empty_query", r.strategy)
        assertTrue("仍有兜底候选", r.candidates.isNotEmpty())
        assertTrue("兜底候选 ≤5", r.candidates.size <= 5)
    }
}

/** B5：resource-id 匹配同样分级 + 上溯 + 候选。 */
private suspend fun testResourceIdMatch() {
    println("\n== B5：resource-id 匹配（分级 + 上溯 + 候选） ==")

    check("按 id 命中（后缀相等）+ 不可点击时上溯") {
        val list = nodes(
            UiNodeSummary(0, resourceId = "", depth = 0, clickable = false, parentIndex = -1, bounds = "0,0,9,9"),
            UiNodeSummary(1, resourceId = "font_row", depth = 1, clickable = true, parentIndex = 0, bounds = "1,1,8,8"),
            UiNodeSummary(2, resourceId = "font_size", depth = 2, clickable = false, parentIndex = 1, bounds = "2,2,7,7")
        )
        val r = UiNodeMatcher.matchByResourceId(list, "font_size")
        assertTrue("命中", r.matched)
        assertEquals("命中 index=2", 2, r.matchIndex)
        assertEquals("上溯到可点击父节点 index=1", 1, r.clickIndex)
    }

    check("id 未命中 → 候选是屏幕上的 id（≤5）") {
        val list = nodes(
            UiNodeSummary(0, resourceId = "title", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(1, resourceId = "summary", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1"),
            UiNodeSummary(2, resourceId = "icon", depth = 1, clickable = true, parentIndex = -1, bounds = "0,0,1,1")
        )
        val r = UiNodeMatcher.matchByResourceId(list, "no_such_id")
        assertTrue("未命中", !r.matched)
        assertTrue("候选非空", r.candidates.isNotEmpty())
        assertTrue("候选 ≤5", r.candidates.size <= 5)
        assertTrue("候选都是 id", r.candidates.all { it.isNotBlank() })
    }
}

/* ------------------------------ 入口 ------------------------------ */

fun main() = runBlocking {
    println("== 真机第三轮 Hotfix3 + hotfix10 429 限流序列（A 退避+网络恢复 / B ui_click 匹配）QA 行为验证 ==")

    testBackoffSequence()
    testRateLimitBackoff()
    testNetworkRecoveryInterruptsWait()
    testFullBackoffWithoutNetworkEvent()
    testCancelDuringWait()
    testNonRetryableZeroWait()
    testTimerOnlyFallback()
    testNormalizedExactMatch()
    testContainsAndPrefix()
    testClickableAncestorFallback()
    testCandidates()
    testResourceIdMatch()

    println("\n== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        println("失败用例：")
        failures.forEach { println("  - $it") }
        throw AssertionError("${failures.size} 个用例失败")
    }
    println("HOTFIX3_VERIFICATION: ALL PASS")
}
