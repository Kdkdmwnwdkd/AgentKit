#!/usr/bin/env kotlin
/**
 * Phase1 T01 Round2 QA 对抗性探针（Edward 自有资产，不改工程师源码）。
 *
 * 背景：Round1 判 Engineer 后，工程师回传三项修复（SSE 网络段 withContext(IO) +
 * streamText/streamChat 改 channelFlow；stopRequested 回合作用域；descriptors KDoc
 * 占位声明）。本探针专攻「修复本身引入的新风险」——工程师 harness（35 断言）未覆盖的缝隙：
 *
 *  R7  SSE 流式中途取消（streamChat/streamText 真代码路径）。取消语义分两个场景：
 *      ① idle 阻塞读（真实「流 idle 无新数据」最坏窗口，对应 OkHttp 读超时内的
 *         socket 阻塞读）：协程取消是协作式的，阻塞读不响应取消——收集方 job 立即
 *         进入 cancelled 态、不再消费，但 collect 调用要等 producer 子协程退出才
 *         返回（kotlinx 结构化并发语义，QA 实测确认）。验证：读返回（本探针用 gate
 *         精确控制）后全链有界展开、连接关闭、CancellationException 上抛（不吞）、
 *         无 Completed 残留（不永久悬挂、不泄漏）；
 *      ② 无尽流（生产侧永不停在可取消点 send）：取消在无外部解锁时自行传播到底
 *         （channelFlow→生产协程→withContext(IO) 段→use{} 关闭），秒退不悬挂。
 *      两者合起来覆盖 channelFlow 取消传播链的完整语义（场景① = 阻塞读边界，
 *      场景② = 可取消点传播）。
 *  R8  端到端：RealAgentLoop + 真 OpenAIClient SSE，round1 完整出 ToolCall 并执行，
 *      round2 流式中途取消 → send() 抛 CancellationException、树尾闭合不变量成立
 *      （新传输层取消语义与 T05 集成验证）。
 *  R9  stop()/send() 入口重置竞态：随机化并发 30 轮 + 确定性「在飞中停止」守卫
 *      + 竞态后干净回合完整性（确认在飞停止永不被吞、标志不残留）。
 *  R10 flow{} + withContext(IO) + emit 违例实证：验证 channelFlow 出处的设计
 *       rationale 为真（LLMClient.kt KDoc 声称的失败模式可复现）。
 *
 * 运行方式见 scripts/qa/run-t01-probe2.sh（kotlin-compiler-embeddable 离线编译 + java 运行）。
 */

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.OpenAIClient
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.UserMessage
import com.example.agentkit.core.agent.RealAgentLoop
import com.example.agentkit.core.llm.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

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
    if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("expected to contain <$needle> in <${haystack?.take(120)}>")
    }
}

private suspend fun awaitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return true
        delay(2)
    }
    return cond()
}

/** 诊断：失败时打印关键线程栈（定位协程卡点）。 */
private fun dumpThreads(vararg names: String) {
    Thread.getAllStackTraces().forEach { (t, frames) ->
        if (names.any { t.name.contains(it) }) {
            println("    THREAD ${t.name} state=${t.state}")
            frames.take(25).forEach { println("        $it") }
        }
    }
}

/* ------------------------------ 内存脚本化 SSE（不发 socket） ------------------------------ */

/** SSE data 行构造（原始串，反斜杠原样上链路——JSON 转义由字面量保证）。 */
private fun sseContent(text: String): String = """data: {"choices":[{"delta":{"content":"$text"}}]}"""

private fun sseToolCallFirstShard(): String =
    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}"""

private fun sseToolCallSecondShard(): String =
    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"北京\"}"}}]}}]}"""

private const val SSE_DONE = "data: [DONE]"

/**
 * 「无 gate」哨兵：已计数完成的闩锁——await() 立即返回（等价于旧的可空 null 语义），
 * countDown() 为 no-op。用它替代可空平台类型参数：QA 实测 kotlinc 1.9.25 离线后端在
 * 为可空**平台类型**参数生成空值注解时偶发 NoClassDefFoundError（非确定性，见
 * run-t01-probe2.sh 注释），故全面避开平台可空类型。
 */
private val NO_GATE: CountDownLatch = CountDownLatch(0)

/**
 * 脚本化 SSE 源：按 [lines] 次序吐行；行吐完后若 [idleGate] 未计数完成则**阻塞**在
 * 闩锁上（模拟「流 idle、无新数据、连接不关」的真实网络读窗口——阻塞读不响应协程
 * 取消，这正是 Round2 要验证的悬挂风险点），gate 释放后返回 EOF。
 * [onClosed] 在 close() 时触发（OkHttp Response.use{} 关闭链：Response→body→source）。
 */
private class ScriptedSseSource(
    private val lines: List<String>,
    private val idleGate: CountDownLatch,
    private val onClosed: CountDownLatch
) : Source {
    private val pending = Buffer()
    private var idx = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (pending.size == 0L && idx < lines.size) {
            pending.writeUtf8(lines[idx]).writeUtf8("\n")
            idx++
        }
        if (pending.size > 0L) {
            val n = minOf(byteCount, pending.size)
            sink.write(pending, n)
            return n
        }
        // 行耗尽：idle 窗口（阻塞，不看协程取消）→ EOF
        idleGate.await()
        return -1L
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        onClosed.countDown()
    }
}

private class ScriptedBody(private val src: Source) : ResponseBody() {
    private val buffered: BufferedSource = src.buffer()

    override fun contentType() = "text/event-stream".toMediaType()
    override fun contentLength() = -1L
    override fun source(): BufferedSource = buffered
}

/**
 * 无尽 SSE 源：read 恒有数据、永不 EOF（模拟「服务器持续推送」的活跃流）。生产侧
 * 永远停在**可取消点**（通道 send）上——用于验证取消无需外部解锁即自行传播到底。
 * [stopGate] 计数完成 = 强制 EOF（仅测试兜底：断言失败时防 producer 永转悬挂）。
 */
private class EndlessSseSource(
    private val lines: List<String>,
    private val onClosed: CountDownLatch,
    private val stopGate: CountDownLatch
) : Source {
    private val pending = Buffer()
    private var i = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (stopGate.count == 0L) return -1L
        if (pending.size == 0L) {
            pending.writeUtf8(lines[i % lines.size]).writeUtf8("\n")
            i++
        }
        val n = minOf(byteCount, pending.size)
        sink.write(pending, n)
        return n
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        onClosed.countDown()
    }
}

/**
 * 短路拦截器：不碰网络，按 [scripts] 次序返回内存 SSE 响应；每次响应的 close 触发
 * 对应的 [closeLatches] 项（第 i 次请求 → 第 i 个闩锁）。[endless] 为 true 时用
 * 无尽源（可取消点场景），否则用 [idleGateForRequest] 控制的 idle 阻塞读场景。
 */
private class ScriptedSseInterceptor(
    private val scripts: List<List<String>>,
    private val closeLatches: MutableList<CountDownLatch>,
    private val idleGateForRequest: (Int) -> CountDownLatch = { NO_GATE },
    private val endless: Boolean = false,
    private val endlessStopGate: CountDownLatch = CountDownLatch(1) // 默认永不停（生产侧持续推送）
) : Interceptor {
    private val idx = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val i = idx.getAndIncrement()
        val lines = scripts[minOf(i, scripts.size - 1)]
        val close = CountDownLatch(1)
        synchronized(closeLatches) { closeLatches += close }
        val source: Source = if (endless) EndlessSseSource(lines, close, endlessStopGate) else ScriptedSseSource(lines, idleGateForRequest(i), close)
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ScriptedBody(source))
            .build()
    }
}

private fun fakeHttp(interceptor: Interceptor): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(interceptor).build()

private fun testConfig(): LLMConfig =
    LLMConfig(baseUrl = "http://127.0.0.1:9/v1", apiKey = "test-key", model = "test-model")

private fun fakeMainDispatcher(): Pair<CoroutineDispatcher, () -> Unit> {
    val exec = Executors.newSingleThreadExecutor { Thread(it, "FakeMain") }
    return exec.asCoroutineDispatcher() to { exec.shutdownNow() }
}

/* ------------------------------ 测试替身 ------------------------------ */

private fun argsOf(vararg kv: Pair<String, String>): JsonObject = buildJsonObject {
    kv.forEach { (k, v) -> put(k, v) }
}

/** 剧本 Fake LLM：按调用次序返回；耗尽后钳制最后一条。 */
private class ScriptLlm(private val script: List<AssistantMessage>) : LLMClient {
    override val id: String = "script"
    private var i = 0

    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage {
        val r = script[minOf(i, script.size - 1)]
        i++
        return r
    }

    override suspend fun testPing() = com.example.agentkit.core.PingResult(200, "")
}

private class FakeTool(
    override val name: String,
    override val readOnly: Boolean,
    private val delayMs: Long = 0,
    private val onExecute: (suspend () -> Unit)? = null
) : AgentTool {
    override val description: String = "fake $name"
    override val parameters = com.example.agentkit.core.JsonSchema.objectSchema()
    var executions = 0

    override suspend fun execute(args: JsonObject): ToolResult {
        executions++
        onExecute?.invoke()
        if (delayMs > 0) delay(delayMs)
        return ToolResult.ok(code = "fake_ok", message = "$name done")
    }
}

/** T05 树尾闭合不变量：已 commit 的 ToolCall 必有配对 ToolMessage。 */
private fun invariantHolds(history: List<ChatMessage>): Boolean {
    val toolIds = history.filterIsInstance<ToolMessage>().map { it.id }.toSet()
    return history.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls }.all { it.id in toolIds }
}

/* ------------------------------ R7：SSE 流式中途取消 ------------------------------ */

/**
 * 取消语义两段式断言（QA 最小复现实测校准，2026-10-14，kotlinx 1.8.1 channelFlow）：
 *
 * 【实测语义】channelFlow 的收集方协程被 cancel 后， CancellationException **不会**
 * 立即到达收集方——channelFlow.collect 内部的 coroutineScope 要等 producer 子协程
 * 退出才抛；producer 阻塞在非取消敏感的 source.readUtf8Line() 上（真实场景 = SSE
 * socket 读，受 OkHttp 读超时约束，默认 10s），取消不中断它。因此：
 *   ① 取消窗口（阻塞读未返回）：收集方尚未收到异常——这是**有界**延迟特征
 *      （真实 app = 用户点停止后，send() 最迟在读超时/下一分片/EOF 时上抛，
 *      T05 树尾闭合随之开始；T05 不变量本身不破，只是停止生效有界延迟）；
 *   ② 阻塞读返回（gate 释放模拟 EOF/超时/下一分片）：CancellationException 即时
 *     surface、producer 有界退出、use{} 关闭连接、job 到达终态——无永久悬挂、
 *     无连接泄漏、无 Completed/错误事件残留。
 *   ③ 无尽流（生产侧永不停在可取消点 send，无任何 gate）：取消**即时**自行传播
 *     到底（QA 最小复现实测 ~5ms）——连接关闭、CE surface、job 终态，秒退不悬挂。
 *   ①②③ 合起来覆盖 channelFlow 取消传播链完整语义：可取消点即时、阻塞读有界、
 *   两者均不悬挂、不吞取消、不泄漏连接。
 */
private suspend fun testR7SseMidStreamCancel() = runBlocking {
    println("\n== R7 SSE 流式中途取消（真 OpenAIClient + channelFlow 取消链，两段式） ==")

    check("streamChat：增量先达 → 取消窗口有界（读返回前不上抛）→ 读返回后取消即时 surface + producer 有界退出 + 连接关闭 + 无 Completed") {
        val gate = CountDownLatch(1)
        val closeLatches = mutableListOf<CountDownLatch>()
        val client = OpenAIClient(
            testConfig(),
            http = fakeHttp(
                ScriptedSseInterceptor(
                    scripts = listOf(listOf(sseContent("你"), sseContent("好"))),
                    closeLatches = closeLatches,
                    idleGateForRequest = { gate } // 行吐完后阻塞在 gate（idle 流）
                )
            )
        )
        val (mainDisp, closeMain) = fakeMainDispatcher()
        try {
            val events = Collections.synchronizedList(mutableListOf<ChatStreamEvent>())
            var thrown: Throwable? = null
            val job = launch(mainDisp) {
                try {
                    client.streamChat(emptyList(), emptyList()).collect { events += it }
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            assertTrue("2 deltas received", awaitUntil(5_000) { events.count { it is ChatStreamEvent.TextDelta } == 2 })
            assertEquals("delta texts", listOf("你", "好"), events.filterIsInstance<ChatStreamEvent.TextDelta>().map { it.text })
            // ① 取消窗口：cancel 后阻塞读未返回前，异常不上抛（有界延迟特征，非缺陷断言）
            job.cancel()
            delay(300)
            assertTrue("cancel window: not surfaced while read blocked (documented latency)", thrown == null)
            // ② 读返回（EOF）：取消即时 surface + producer 有界退出 + 连接关闭
            gate.countDown()
            assertTrue("cancellation surfaced promptly after read returns", awaitUntil(2_000) { thrown != null })
            assertTrue("thrown is CancellationException (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
            assertTrue("no Completed after cancel", events.none { it is ChatStreamEvent.Completed })
            assertTrue(
                "producer closed within 5s (no hang)",
                closeLatches.isNotEmpty() && closeLatches[0].await(5, TimeUnit.SECONDS)
            )
            assertTrue("job completed after producer exit", awaitUntil(10_000) { job.isCompleted })
        } finally {
            gate.countDown() // 防御：断言中途失败也不留阻塞读
            closeMain()
        }
    }

    check("streamText：同样的两段式取消链（公开 API 语义不破：增量逐条、取消可 surface、连接有界关闭）") {
        val gate = CountDownLatch(1)
        val closeLatches = mutableListOf<CountDownLatch>()
        val client = OpenAIClient(
            testConfig(),
            http = fakeHttp(
                ScriptedSseInterceptor(
                    scripts = listOf(listOf(sseContent("你"), sseContent("好"))),
                    closeLatches = closeLatches,
                    idleGateForRequest = { gate }
                )
            )
        )
        val (mainDisp, closeMain) = fakeMainDispatcher()
        try {
            val chunks = Collections.synchronizedList(mutableListOf<String>())
            var thrown: Throwable? = null
            val job = launch(mainDisp) {
                try {
                    client.streamText(emptyList(), emptyList()).collect { chunks += it }
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            assertTrue("2 chunks received", awaitUntil(5_000) { chunks.size == 2 })
            assertEquals("chunks", listOf("你", "好"), chunks.toList())
            job.cancel()
            delay(300)
            assertTrue("cancel window: not surfaced while read blocked", thrown == null)
            gate.countDown()
            assertTrue("cancellation surfaced promptly after read returns", awaitUntil(2_000) { thrown != null })
            assertTrue("thrown is CancellationException (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
            assertTrue(
                "producer closed within 5s (no hang)",
                closeLatches.isNotEmpty() && closeLatches[0].await(5, TimeUnit.SECONDS)
            )
            assertTrue("job completed after producer exit", awaitUntil(10_000) { job.isCompleted })
        } finally {
            gate.countDown()
            closeMain()
        }
    }

    check("无尽流：取消无需外部解锁即在可取消点自行传播到底——连接关闭 + CE surface + job 终态（秒退不悬挂）") {
        val closeLatches = mutableListOf<CountDownLatch>()
        val stopGate = CountDownLatch(1) // 仅兜底：断言失败时强制 EOF，防 producer 永转悬挂
        val client = OpenAIClient(
            testConfig(),
            http = fakeHttp(
                ScriptedSseInterceptor(
                    scripts = listOf(List(64) { sseContent("段$it") }),
                    closeLatches = closeLatches,
                    endless = true,
                    endlessStopGate = stopGate
                )
            )
        )
        val (mainDisp, closeMain) = fakeMainDispatcher()
        try {
            val chunks = Collections.synchronizedList(mutableListOf<String>())
            var thrown: Throwable? = null
            val job = launch(mainDisp) {
                try {
                    client.streamText(emptyList(), emptyList()).collect { chunks += it }
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            // 流确实在活跃推送（生产侧不在阻塞读上，而在可取消点 send 上循环）
            assertTrue("endless stream pushing (>= 30 chunks)", awaitUntil(5_000) { chunks.size >= 30 })
            val before = chunks.size
            delay(200)
            assertTrue("stream still pushing (active, not idle)", chunks.size > before)
            // 取消：无任何 gate/外部解锁——必须自行传播到底（channelFlow → 生产协程
            // → withContext(IO) 段 → use{} 关闭连接），秒退不悬挂（QA 最小复现实测：~5ms）
            job.cancel()
            val t0 = System.currentTimeMillis()
            assertTrue(
                "connection closed without external unlock (no hang)",
                awaitUntil(8_000) { closeLatches.isNotEmpty() && closeLatches[0].count == 0L }
            )
            assertTrue("cancellation surfaced", awaitUntil(2_000) { thrown != null })
            assertTrue("thrown is CancellationException (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
            assertTrue("job completed (no dangling coroutine)", awaitUntil(5_000) { job.isCompleted })
            println("    (endless self-propagation took ${System.currentTimeMillis() - t0}ms)")
        } finally {
            stopGate.countDown() // 防御：断言中途失败也强制 EOF
            closeMain()
        }
    }
}

/* ------------------------------ R8：端到端 loop 取消（round2 SSE 流式中途） ------------------------------ */

/**
 * 端到端：RealAgentLoop + 真 OpenAIClient SSE。round1 完整（ToolCall 整条 commit →
 * 工具真执行 → 结果回喂配对）；round2 流式中途取消——按 R7 实测语义，cancel 后
 * send() 在阻塞读返回时上抛 CancellationException；历史不变量成立、round2 的
 * Assistant 从未 commit（无未闭合 tool_call）。
 */
private suspend fun testR8LoopCancelMidSse() = runBlocking {
    println("\n== R8 端到端：RealAgentLoop + 真 SSE，round2 流式中途取消，T05 不变量仍成立 ==")

    check("round1 完整（ToolCall 执行+回喂）→ round2 流式中途取消：读返回后 send() 抛 CancellationException + 不变量 + 无部分 commit") {
        val gate = CountDownLatch(1)
        val closeLatches = mutableListOf<CountDownLatch>()
        val client = OpenAIClient(
            testConfig(),
            http = fakeHttp(
                ScriptedSseInterceptor(
                    scripts = listOf(
                        listOf(sseContent("让我先查"), sseToolCallFirstShard(), sseToolCallSecondShard(), SSE_DONE),
                        listOf(sseContent("北京")) // round2：一个增量后 idle 阻塞
                    ),
                    closeLatches = closeLatches,
                    idleGateForRequest = { i -> if (i == 1) gate else NO_GATE }
                )
            )
        )
        val weather = FakeTool("get_weather", readOnly = true)
        val reg = ToolRegistry().register(weather)
        val round2DeltaSeen = CompletableDeferred<Unit>()
        val deltaCount = AtomicInteger(0)
        val trail = Collections.synchronizedList(mutableListOf<String>())
        var snapshot: List<ChatMessage> = emptyList()
        val loop = RealAgentLoop(
            llmProvider = { client },
            registry = reg,
            providerRetry = 0,
            checkpoint = { snapshot = it.toList(); trail += "checkpoint(${it.size})" },
            onTextDelta = { text ->
                trail += "delta($text)"
                // round1 吐 1 个增量（"让我先查"）；round2 首个增量（"北京"）是第 2 个
                if (deltaCount.incrementAndGet() >= 2) round2DeltaSeen.complete(Unit)
            }
        )
        val (mainDisp, closeMain) = fakeMainDispatcher()
        try {
            var thrown: Throwable? = null
            val job = launch(mainDisp) {
                try {
                    loop.send("北京天气怎么样？")
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            assertTrue("round2 delta seen", withTimeoutOrNull(10_000) { round2DeltaSeen.await(); true } == true)
            job.cancel()
            // R7 实测语义：阻塞读返回前 send() 不上抛（有界延迟窗口）
            delay(300)
            assertTrue("cancel window: send() not yet thrown (documented latency)", thrown == null)
            // 读返回 → send() 抛 CancellationException
            gate.countDown()
            assertTrue("send threw CancellationException after read returns", awaitUntil(2_000) { thrown != null })
            assertTrue("is CancellationException (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
            // round1 完整收尾：工具真执行且回喂配对
            assertEquals("weather executed", 1, weather.executions)
            val toolMsgs = snapshot.filterIsInstance<ToolMessage>()
            assertEquals("one paired tool message", 1, toolMsgs.size)
            assertEquals("paired id", "call_1", toolMsgs[0].id)
            // round2 的 Assistant 从未 commit（取消发生在收集 Completed 之前）——不变量成立
            assertTrue("invariant", invariantHolds(snapshot))
            assertEquals("no partial round2 assistant", 1, snapshot.filterIsInstance<AssistantMessage>().size)
            // producer 有界退出 + 连接关闭 + job 终态
            assertTrue(
                "round2 producer closed within 5s (no hang)",
                closeLatches.size >= 2 && closeLatches[1].await(5, TimeUnit.SECONDS)
            )
            assertTrue("job completed after producer exit", awaitUntil(10_000) { job.isCompleted })
        } finally {
            gate.countDown() // 防御：断言中途失败也不留阻塞读
            closeMain()
        }
    }
}

/* ------------------------------ R9：stop()/send() 入口重置竞态 ------------------------------ */

private suspend fun testR9StopSendRace() = runBlocking {
    println("\n== R9 stop()/send() 竞态：在飞停止不吞、标志不残留 ==")

    check("确定性守卫：工具执行中（已过 send 入口重置）stop() → 必优雅收尾（在飞停止永不被吞）") {
        val entered = CompletableDeferred<Unit>()
        // W-56：让工具**挂住**，直到测试线程发出 stop 再放行。
        // 原来 onExecute 只 complete(entered) 就返回：工具立刻结束、回合可能已推进到下一轮，
        // 于是 stop() 有时落在「在飞」窗口外——这条用例因此 3 次里偶发 1 次失败（实测）。
        // 挂住之后「stop 落在工具执行中」是确定的，失败就一定是实现问题，不是时序运气。
        val release = CompletableDeferred<Unit>()
        val stopper = FakeTool(
            "stopper",
            readOnly = true,
            onExecute = {
                entered.complete(Unit)
                withTimeoutOrNull(5_000) { release.await() }
            }
        )
        val reg = ToolRegistry().register(stopper)
        val calls = listOf(ToolCall("c0", "stopper", argsOf()))
        val fake = ScriptLlm(listOf(AssistantMessage("", calls), AssistantMessage("final")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
        var history: List<ChatMessage>? = null
        val job = launch(Dispatchers.Default) { history = loop.send("x") }
        withTimeout(5_000) { entered.await(); true } // 确保 stop 落在在飞回合内（入口重置早已完成）
        loop.stop()
        release.complete(Unit) // 放行工具，让它走到取消检查点
        withTimeout(10_000) { job.join(); true }
        if (job.isActive) job.cancel() // 防御：join 超时也不让 runBlocking 尾部干等
        assertContains("graceful stop", (history!!.last() as AssistantMessage).content, "已根据请求停止")
    }

    check("随机竞态 30 轮：stop() 与 send() 入口重置并发——结局必合法且竞态后干净回合完整（无残留误伤）") {
        repeat(30) { i ->
            val reg = ToolRegistry().register(FakeTool("slow", readOnly = true, delayMs = 200))
            val calls = listOf(ToolCall("c0", "slow", argsOf()))
            val fake = ScriptLlm(
                listOf(
                    AssistantMessage("", calls),
                    AssistantMessage("final"),
                    AssistantMessage("", calls),
                    AssistantMessage("final")
                )
            )
            val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
            var outcome: String? = null
            var thrown: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try {
                    val h = loop.send("x")
                    outcome = (h.last() as AssistantMessage).content
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            // 竞态窗口：0~150ms 随机停顿后 stop——覆盖「入口重置前 / 重置后 / 在飞中」三种落点
            Thread { Thread.sleep(Random.nextLong(0, 150)); loop.stop() }.start()
            withTimeout(15_000) { job.join(); true }
            if (job.isActive) job.cancel() // 防御：join 超时也不让 runBlocking 尾部干等
            assertTrue("iter $i: no exception (was ${thrown?.javaClass?.simpleName})", thrown == null)
            assertTrue(
                "iter $i: legal outcome (was $outcome)",
                outcome == "final" || outcome?.contains("已根据请求停止") == true
            )
            // 竞态后：不再调用 stop 的干净回合必须完整跑完（证明标志无跨回合残留）
            val clean = loop.send("clean")
            assertEquals("iter $i: post-race clean turn", "final", (clean.last() as AssistantMessage).content)
        }
    }
}

/* ------------------------------ R10：flow{} 不变量实证（channelFlow 出处佐证） ------------------------------ */

private suspend fun testR10FlowInvariant() = runBlocking {
    println("\n== R10 flow{} + withContext(IO) + emit 违例实证（channelFlow 修复 rationale） ==")

    check("flow{} 内 withContext(IO) 嵌套 emit：跨调度器收集抛 Flow invariant is violated（修复模式确有必要）") {
        val bad: Flow<Int> = flow {
            withContext(Dispatchers.IO) { emit(1) }
        }
        val (mainDisp, closeMain) = fakeMainDispatcher()
        try {
            val thrown = runCatching { withContext(mainDisp) { bad.toList() } }.exceptionOrNull()
            assertTrue(
                "Flow invariant violated (was ${thrown?.javaClass?.simpleName}: ${thrown?.message})",
                thrown is IllegalStateException && thrown.message?.contains("Flow invariant") == true
            )
        } finally {
            closeMain()
        }
    }
}

/* ------------------------------ main ------------------------------ */

fun main() = runBlocking {
    println("=== Phase1 T01 Round2 QA 对抗性探针（Edward） ===")
    testR7SseMidStreamCancel()
    testR8LoopCancelMidSse()
    testR9StopSendRace()
    testR10FlowInvariant()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
