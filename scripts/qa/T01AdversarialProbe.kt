#!/usr/bin/env kotlin
/**
 * Phase1 T01 QA 对抗性补充探针（Edward 自有资产，不改工程师源码）。
 *
 * 存在理由：工程师的 T01Verification.kt（27 断言）覆盖了主路径，但以下几处
 * 「标题看着像覆盖、断言其实没覆盖」或「对抗边界未构造」的缝隙，由本探针补证：
 *
 *  P1  默认 streamChat 契约：chat() 恰一次 + TextDelta 仅 content 非空 +
 *      Completed 恒发射（E 项「零行为变化」的直接证据，工程师 harness 只间接覆盖）；
 *  P2  OpenAiToolCallAccumulator 对抗边界：分片缺 index（位置兜底）、双调用
 *      交错分片、id 只首片、无 function 元素丢弃、空 arguments 片（E 项）；
 *  P3  取消路径混合态：批内快工具已成功 + 慢工具触发取消 → 已成功但被丢弃的
 *      调用也必须被保守闭合（code=cancelled），绝不把假成功留进历史（A 项）；
 *  P4  stop() 优雅通道的 Hooks 计数：beforeStop 在 stop() 路径恰好 1 次（F 项）；
 *  P5  并行批次 Hooks 计数无丢失：7 个只读调用同批并行 → before/after 各 7（F/C 项）；
 *  P6  update() 全 null = 真·不变：已设三项在空 update() 后保持（I 项）。
 *
 * 运行方式见 scripts/qa/run-t01-probe.sh（kotlin-compiler-embeddable 离线编译 + java 运行）。
 */

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LLMClient
import com.example.agentkit.core.OpenAiToolCallAccumulator
import com.example.agentkit.core.PingResult
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.UserMessage
import com.example.agentkit.core.agent.Hooks
import com.example.agentkit.core.agent.RealAgentLoop
import com.example.agentkit.core.llm.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

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

/* ------------------------------ 测试替身 ------------------------------ */

private fun argsOf(vararg kv: Pair<String, String>): JsonObject = buildJsonObject {
    kv.forEach { (k, v) -> put(k, v) }
}

/** 计数型 Fake LLM：记录 chat() 调用次数；streamChat 吃接口默认实现（P1 的被测对象）。 */
private class CountingLlm(private val reply: AssistantMessage) : LLMClient {
    override val id: String = "counting"
    val chatCalls = AtomicInteger(0)

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        chatCalls.incrementAndGet()
        return reply
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/** 剧本 Fake：按调用次序返回预设 AssistantMessage。 */
private class ScriptLlm(private val script: List<AssistantMessage>) : LLMClient {
    override val id: String = "script"
    private var i = 0
    val rounds = AtomicInteger(0)

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        rounds.incrementAndGet()
        val r = script[minOf(i, script.size - 1)]
        i++
        return r
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
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

/** 记录型 Hooks（原子计数，防并行批次计数丢失）。 */
private class RecordingHooks : Hooks {
    val beforeToolCall = AtomicInteger(0)
    val afterToolCall = AtomicInteger(0)
    val beforeStop = AtomicInteger(0)
    val beforeSerialization = AtomicInteger(0)

    override fun beforeToolCall(call: ToolCall) { beforeToolCall.incrementAndGet() }
    override fun afterToolCall(call: ToolCall, message: ToolMessage) { afterToolCall.incrementAndGet() }
    override fun beforeStop() { beforeStop.incrementAndGet() }
    override fun beforeSerialization(messages: List<ChatMessage>) { beforeSerialization.incrementAndGet() }
}

/** T05 树尾闭合不变量：已 commit 的 ToolCall 必有配对 ToolMessage。 */
private fun invariantHolds(history: List<ChatMessage>): Boolean {
    val toolIds = history.filterIsInstance<ToolMessage>().map { it.id }.toSet()
    return history.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls }.all { it.id in toolIds }
}

/* ------------------------------ P1：默认 streamChat 契约 ------------------------------ */

private suspend fun testP1DefaultStreamChat() {
    println("\n== P1 默认 streamChat：chat() 恰一次 + TextDelta 仅 content 非空 + Completed 恒发射 ==")

    check("content 非空：事件序列恰为 [TextDelta(全文), Completed(整条)]，chat() 恰一次") {
        val reply = AssistantMessage("hello world")
        val llm = CountingLlm(reply)
        val events = llm.streamChat(emptyList(), emptyList()).toList()
        assertEquals("event count", 2, events.size)
        assertTrue("first is TextDelta", events[0] is ChatStreamEvent.TextDelta)
        assertEquals("delta text", "hello world", (events[0] as ChatStreamEvent.TextDelta).text)
        assertTrue("second is Completed", events[1] is ChatStreamEvent.Completed)
        assertEquals("completed message", reply, (events[1] as ChatStreamEvent.Completed).message)
        assertEquals("chat called exactly once", 1, llm.chatCalls.get())
    }

    check("content 为空（纯 ToolCall 轮）：不发射 TextDelta，只发 Completed") {
        val reply = AssistantMessage("", listOf(ToolCall("c0", "t", argsOf())))
        val llm = CountingLlm(reply)
        val events = llm.streamChat(emptyList(), emptyList()).toList()
        assertEquals("event count", 1, events.size)
        assertTrue("only Completed", events[0] is ChatStreamEvent.Completed)
        assertEquals("completed message", reply, (events[0] as ChatStreamEvent.Completed).message)
        assertEquals("chat called exactly once", 1, llm.chatCalls.get())
    }
}

/* ------------------------------ P2：Accumulator 对抗边界 ------------------------------ */

private suspend fun testP2AccumulatorEdges() {
    println("\n== P2 OpenAiToolCallAccumulator 对抗边界 ==")

    check("分片缺 index：按数组位置兜底，跨 absorb 调用仍归并到同一 index") {
        val acc = OpenAiToolCallAccumulator()
        // 不守规矩的兼容端点：两个分片都不带 index（各自单元数组 → position 都=0）
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("id", "call_a")
                putJsonObject("function") {
                    put("name", "get_weather")
                    put("arguments", "{\"ci")
                }
            })
        })
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                putJsonObject("function") { put("arguments", "ty\":\"北京\"}") }
            })
        })
        val calls = acc.build()
        assertEquals("merged to one call", 1, calls.size)
        assertEquals("id", "call_a", calls[0].id)
        assertEquals("name", "get_weather", calls[0].name)
        assertEquals("city", "北京", (calls[0].arguments["city"] as? JsonPrimitive)?.content)
    }

    check("双调用交错分片：按 index 归组互不串话，build 顺序 = 首现顺序") {
        val acc = OpenAiToolCallAccumulator()
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("index", 0); put("id", "id0")
                putJsonObject("function") { put("name", "t0"); put("arguments", "{\"x\":") }
            })
            add(buildJsonObject {
                put("index", 1); put("id", "id1")
                putJsonObject("function") { put("name", "t1"); put("arguments", "{\"y\":") }
            })
        })
        // 交错回来的后续片（均无 id/name）
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("index", 1)
                putJsonObject("function") { put("arguments", "2}") }
            })
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("function") { put("arguments", "1}") }
            })
        })
        val calls = acc.build()
        assertEquals("count", 2, calls.size)
        assertEquals("order name0", "t0", calls[0].name)
        assertEquals("order name1", "t1", calls[1].name)
        assertEquals("x", "1", (calls[0].arguments["x"] as? JsonPrimitive)?.content)
        assertEquals("y", "2", (calls[1].arguments["y"] as? JsonPrimitive)?.content)
        assertEquals("id0 kept", "id0", calls[0].id)
        assertEquals("id1 kept", "id1", calls[1].id)
    }

    check("无 function 元素（只有 index/id）：建了 Delta 但无名 → build 丢弃") {
        val acc = OpenAiToolCallAccumulator()
        acc.absorb(buildJsonArray {
            add(buildJsonObject { put("index", 0); put("id", "orphan") })
        })
        assertEquals("dropped", 0, acc.build().size)
    }

    check("arguments 空片与多空片交错：拼接结果不受影响") {
        val acc = OpenAiToolCallAccumulator()
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("index", 0); put("id", "c")
                putJsonObject("function") { put("name", "t"); put("arguments", "") }
            })
        })
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("function") { put("arguments", "{\"a\":") }
            })
        })
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("function") { put("arguments", "") }
            })
        })
        acc.absorb(buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("function") { put("arguments", "1}") }
            })
        })
        val calls = acc.build()
        assertEquals("count", 1, calls.size)
        assertEquals("a", "1", (calls[0].arguments["a"] as? JsonPrimitive)?.content)
    }
}

/* ------------------------------ P3：取消混合态（快工具成功 + 慢工具取消） ------------------------------ */

private suspend fun testP3MixedCompleteAndCancel() = runBlocking {
    println("\n== P3 取消混合态：已成功但被丢弃的调用也被保守闭合（无假成功入史） ==")

    check("快工具 50ms 成功 / 慢工具 200ms 取消：两者都闭合，快工具也是 code=cancelled 错误态") {
        var runJob: Job? = null
        val reg = ToolRegistry()
        reg.register(FakeTool("fast", readOnly = true, delayMs = 50))
        reg.register(FakeTool("slow", readOnly = true, delayMs = 5_000, onExecute = { runJob?.cancel() }))
        val calls = listOf(
            ToolCall("c0", "fast", argsOf()),
            ToolCall("c1", "slow", argsOf())
        )
        var lastSnapshot: List<ChatMessage> = emptyList()
        val fake = ScriptLlm(listOf(AssistantMessage("", calls)))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 5,
            providerRetry = 0,
            checkpoint = { lastSnapshot = it.toList() }
        )
        var thrown: Throwable? = null
        val done = CompletableDeferred<Unit>()
        runBlocking {
            val job = launch {
                try {
                    loop.send("x")
                } catch (t: Throwable) {
                    thrown = t
                } finally {
                    done.complete(Unit)
                }
            }
            runJob = job
            // 慢工具 200ms 后才取消：保证快工具已成功返回、其结果被 executeBatch 丢弃
            delay(200)
            if (job.isActive) job.cancel()
            done.await()
        }
        assertTrue("rethrown (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
        assertTrue("invariant", invariantHolds(lastSnapshot))
        val filled = lastSnapshot.filterIsInstance<ToolMessage>()
        assertEquals("both filled", 2, filled.size)
        // 关键：已成功的 fast 也不许把假成功（fake_ok）留在历史里——必须保守闭合
        assertContains("fast closed as cancelled", filled.first { it.id == "c0" }.content, "code=cancelled")
        assertTrue(
            "no false success in history",
            filled.none { it.content.contains("fake_ok") && !it.isError }
        )
        assertContains("slow closed as cancelled", filled.first { it.id == "c1" }.content, "code=cancelled")
    }
}

/* ------------------------------ P4：stop() 路径的 Hooks 计数 ------------------------------ */

private suspend fun testP4StopHooks() {
    println("\n== P4 stop() 优雅通道：beforeStop 恰好 1 次，四时机计数符合预期 ==")

    check("工具执行中 loop.stop()：优雅收尾 + beforeStop=1 + 工具钩子配对") {
        var loopRef: com.example.agentkit.core.agent.AgentLoop? = null
        val hooks = RecordingHooks()
        val reg = ToolRegistry()
        reg.register(FakeTool("stopper", readOnly = true, onExecute = { loopRef?.stop() }))
        val calls = listOf(ToolCall("c0", "stopper", argsOf()))
        val fake = ScriptLlm(listOf(AssistantMessage("", calls), AssistantMessage("next")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 5,
            providerRetry = 0,
            hooks = hooks
        )
        loopRef = loop
        val history = loop.send("x")
        assertContains("stop note", (history.last() as AssistantMessage).content, "已根据请求停止")
        assertEquals("second round never happens", 1, fake.rounds.get())
        assertEquals("beforeSerialization", 1, hooks.beforeSerialization.get())
        assertEquals("beforeToolCall", 1, hooks.beforeToolCall.get())
        assertEquals("afterToolCall", 1, hooks.afterToolCall.get())
        assertEquals("beforeStop", 1, hooks.beforeStop.get())
        assertTrue("invariant", invariantHolds(history))
    }
}

/* ------------------------------ P5：并行批次 Hooks 计数无丢失 ------------------------------ */

private suspend fun testP5ParallelHooks() {
    println("\n== P5 并行批次（7 个只读同批，maxParallel=3 分块）：Hooks 计数无丢失 ==")

    check("7 并行调用：beforeToolCall=7 / afterToolCall=7 / beforeStop=1 / serialization=2") {
        val hooks = RecordingHooks()
        val reg = ToolRegistry()
        repeat(7) { i ->
            reg.register(FakeTool("r$i", readOnly = true, delayMs = (30 - i * 3).toLong()))
        }
        val calls = (0 until 7).map { ToolCall("c$it", "r$it", argsOf()) }
        val fake = ScriptLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 3,
            providerRetry = 0,
            hooks = hooks
        )
        loop.send("x")
        assertEquals("beforeSerialization", 2, hooks.beforeSerialization.get())
        assertEquals("beforeToolCall", 7, hooks.beforeToolCall.get())
        assertEquals("afterToolCall", 7, hooks.afterToolCall.get())
        assertEquals("beforeStop", 1, hooks.beforeStop.get())
    }
}

/* ------------------------------ P6：update() 全 null = 真·不变 ------------------------------ */

private suspend fun testP6UpdateNullKeeps() {
    println("\n== P6 update() 全 null：已设三项原样保持（null = 保持现值） ==")

    check("设三项后空 update()：systemPrompt/memoryProvider/maxSteps 均保持") {
        val reg = ToolRegistry()
        val fake = ScriptLlm(listOf(AssistantMessage("done"), AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
        loop.update(systemPrompt = "KEEP-SYS-1", memoryProvider = { "KEEP-MEM-1" }, maxSteps = 3)
        loop.update() // 全 null
        val history = loop.send("hi")
        val sys = (history[0] as SystemMessage).content
        assertContains("systemPrompt kept", sys, "KEEP-SYS-1")
        assertContains("memoryProvider kept", sys, "KEEP-MEM-1")
        assertContains("memory usage prompt still there", sys, "memory_write")
    }

    check("单项 update 不清除其他项：update(registry) 后 systemPrompt 仍保持") {
        val reg1 = ToolRegistry()
        val reg2 = ToolRegistry().register(FakeTool("t", readOnly = true))
        val fake = ScriptLlm(listOf(AssistantMessage("done"), AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg1, providerRetry = 0)
        loop.update(systemPrompt = "KEEP-SYS-2")
        loop.update(registry = reg2)
        val history = loop.send("hi")
        assertContains("systemPrompt kept", (history[0] as SystemMessage).content, "KEEP-SYS-2")
    }
}

/* ------------------------------ main ------------------------------ */

fun main() = runBlocking {
    println("=== Phase1 T01 QA 对抗性补充探针（Edward） ===")
    testP1DefaultStreamChat()
    testP2AccumulatorEdges()
    testP3MixedCompleteAndCancel()
    testP4StopHooks()
    testP5ParallelHooks()
    testP6UpdateNullKeeps()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
