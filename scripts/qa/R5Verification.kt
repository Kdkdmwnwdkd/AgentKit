#!/usr/bin/env kotlin
/**
 * R5（回合结构化事件 + turnId 贯穿）行为验证 harness。
 *
 * 为什么单独一套：这条能力的价值**只在出问题时才显现**——正常跑一遍看不到差别。
 * 要断言的是「出了事能不能定位到现场」：
 *  - 一次正常回合是否产生完整的 8 类事件；
 *  - turnId 是否**贯穿**（所有事件同一个 id，且跨回合不混）；
 *  - 工具事件是否**成对**（start 必有 end）；
 *  - 一个卡住的工具能不能通过「最后一条 tool.start 且无配对 end」定位到。
 *
 * 最后一条是 R5 的验收判据：用户报「卡住了」时，能定位到最后一个 `tool.start`。
 *
 * 覆盖：
 *  R5-1 正常回合产出完整事件序列（turn.start → prompt.plan → llm.call/end → turn.end）
 *  R5-2 turnId 贯穿：同一回合所有事件同一个 id
 *  R5-3 跨回合不串号：两个回合各自独立
 *  R5-4 工具事件成对：tool.start 与 tool.end 一一对应
 *  R5-5 tool.end 带结果码（不是只有 isError）
 *  R5-6 **卡住定位**：有 start 无 end 的那条能被找出来
 *  R5-6b 用户取消与卡住可区分（取消补 end(code=cancelled)，卡住不补）
 *  R5-7 回合结束状态正确（completed / cancelled / max_steps）
 *  R5-8 观察者抛异常不影响回合（观测不该有副作用能力）
 *
 * ## 一条必须记住的语义区分（R5-6 与 R5-6b 的分工）
 *
 * 「卡住」有两种，**在时间线上必须长得不一样**：
 *  - **卡住**：进程还活着，调用悬着 → 有 `tool.start`，**没有** `tool.end`；
 *  - **用户取消**：树尾闭合补了终态 → 有 `tool.start`，也有 `tool.end(code=cancelled)`。
 *
 * 若两者都表现为「最后一个 start」，排障就会把「用户主动停了」误报成「卡住了」，
 * 指向错误的现场。所以这两条是**分开**断言的，不是同一条的正反面。
 *
 * 运行：scripts/qa/run-r5-verification.sh
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.agent.TurnEvent
import com.beichen.polaris.core.agent.TurnObserver
import com.beichen.polaris.core.llm.ChatStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack == null || !haystack.contains(needle)) {
        throw AssertionError("$name：期望包含「$needle」，实际：${haystack?.take(300)}")
    }
}

/* ------------------------------------------------------------------ */
/* 替身                                                                */

/** 收集全部事件的观察者。 */
private class RecordingObserver : TurnObserver {
    val events = mutableListOf<TurnEvent>()

    override fun onEvent(event: TurnEvent) {
        events += event
    }

    fun ofType(name: String): List<TurnEvent> = events.filter {
        when (it) {
            is TurnEvent.TurnStarted -> name == "turn.start"
            is TurnEvent.PromptPlanned -> name == "prompt.plan"
            is TurnEvent.LlmCallStarted -> name == "llm.call"
            is TurnEvent.LlmCallEnded -> name == "llm.end"
            is TurnEvent.ToolCallStarted -> name == "tool.start"
            is TurnEvent.ToolCallEnded -> name == "tool.end"
            is TurnEvent.StepClosed -> name == "step.close"
            is TurnEvent.TurnEnded -> name == "turn.end"
        }
    }
}

/**
 * 可脚本化的假客户端：按调用次序返回预设消息。
 *
 * 第 [toolRounds] 轮返回带工具调用的消息，之后返回最终回答。
 */
private class ScriptedClient(
    override val id: String = "fake",
    private val toolRounds: Int = 1,
    private val toolName: String = "echo",
    private val delayMs: Long = 0L
) : LLMClient {
    var calls = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage =
        AssistantMessage("chat-not-used")

    override fun streamChat(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<ChatStreamEvent> = flow {
        calls++
        if (delayMs > 0) delay(delayMs)
        val current = calls
        if (current <= toolRounds) {
            emit(
                ChatStreamEvent.Completed(
                    AssistantMessage(
                        content = "",
                        toolCalls = listOf(
                            ToolCall("call_$current", toolName, buildJsonObject { put("text", "hi") })
                        )
                    )
                )
            )
        } else {
            emit(ChatStreamEvent.Completed(AssistantMessage("done")))
        }
    }

    override suspend fun testPing(): PingResult = PingResult(200, "ok")
}

/** 回声工具。 */
private class EchoTool(override val name: String = "echo") : AgentTool {
    override val description: String = "测试用回声工具"
    override val parameters: JsonSchema = JsonSchema()

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.ok(message = "echo:${args["text"]}")
}

/** 永远挂起的工具——用来制造「有 start 无 end」的卡点。 */
private class HangingTool(override val name: String = "hang") : AgentTool {
    override val description: String = "测试用挂起工具"
    override val parameters: JsonSchema = JsonSchema()

    override suspend fun execute(args: JsonObject): ToolResult {
        delay(600_000)
        return ToolResult.ok(message = "never")
    }
}

/** 挂起的只读工具——取消时判定为「确定未完成」（cancelled 而非 cancelled_unknown）。 */
private class ReadOnlyHangingTool : AgentTool {
    override val name: String = "hang_read"
    override val description: String = "测试用挂起只读工具"
    override val parameters: JsonSchema = JsonSchema()
    override val readOnly: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult {
        delay(600_000)
        return ToolResult.ok(message = "never")
    }
}

private fun loopWith(
    client: LLMClient,
    registry: ToolRegistry,
    observer: TurnObserver,
    maxSteps: Int = 4
): RealAgentLoop = RealAgentLoop(
    llmProvider = { client },
    registry = registry,
    systemPrompt = "测试提示",
    maxSteps = maxSteps,
    observer = observer
)

/* ------------------------------------------------------------------ */
/* R5                                                                 */

private suspend fun testEventSequence() = runBlocking {
    println("\n[R5] 回合结构化事件")

    check("R5-1 正常回合产出完整事件序列") {
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val client = ScriptedClient(toolRounds = 1)
        loopWith(client, registry, observer).send("你好")

        val names = observer.events.map { event ->
            when (event) {
                is TurnEvent.TurnStarted -> "turn.start"
                is TurnEvent.PromptPlanned -> "prompt.plan"
                is TurnEvent.LlmCallStarted -> "llm.call"
                is TurnEvent.LlmCallEnded -> "llm.end"
                is TurnEvent.ToolCallStarted -> "tool.start"
                is TurnEvent.ToolCallEnded -> "tool.end"
                is TurnEvent.StepClosed -> "step.close"
                is TurnEvent.TurnEnded -> "turn.end"
            }
        }
        // 首尾必须包住中间所有事件
        assertTrue("第一条应是 turn.start，实际 ${names.firstOrNull()}", names.firstOrNull() == "turn.start")
        assertTrue("最后一条应是 turn.end，实际 ${names.lastOrNull()}", names.lastOrNull() == "turn.end")
        assertTrue("应含 prompt.plan", names.contains("prompt.plan"))
        assertTrue("应含 llm.call", names.contains("llm.call"))
        assertTrue("应含 llm.end", names.contains("llm.end"))
        assertTrue("应含 tool.start", names.contains("tool.start"))
        assertTrue("应含 tool.end", names.contains("tool.end"))
        assertTrue("应含 step.close", names.contains("step.close"))
        // 顺序：turn.start 在 prompt.plan 之前
        assertTrue(
            "prompt.plan 应在 turn.start 之后",
            names.indexOf("turn.start") < names.indexOf("prompt.plan")
        )
        // 顺序：tool.start 在 tool.end 之前
        assertTrue(
            "tool.start 应在 tool.end 之前",
            names.indexOf("tool.start") < names.indexOf("tool.end")
        )
    }

    check("R5-2 turnId 贯穿：同一回合所有事件同一个 id") {
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(EchoTool()) }
        loopWith(ScriptedClient(toolRounds = 1), registry, observer).send("你好")

        val ids = observer.events.map { it.turnId }.toSet()
        assertTrue("应只有一个 turnId，实际 $ids", ids.size == 1)
        assertTrue("turnId 不应为空", ids.first().isNotBlank())
    }

    check("R5-3 跨回合不串号") {
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val loop = loopWith(ScriptedClient(toolRounds = 1), registry, observer)
        loop.send("第一轮")
        val firstId = observer.events.first().turnId
        val countAfterFirst = observer.events.size
        loop.send("第二轮")
        val secondId = observer.events[countAfterFirst].turnId

        assertTrue("两回合的 turnId 应不同", firstId != secondId)
        val firstEvents = observer.events.take(countAfterFirst).map { it.turnId }.toSet()
        val secondEvents = observer.events.drop(countAfterFirst).map { it.turnId }.toSet()
        assertTrue("第一回合事件应同号，实际 $firstEvents", firstEvents.size == 1)
        assertTrue("第二回合事件应同号，实际 $secondEvents", secondEvents.size == 1)
    }

    check("R5-4 工具事件成对：start 与 end 一一对应") {
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(EchoTool()) }
        loopWith(ScriptedClient(toolRounds = 2), registry, observer).send("你好")

        val starts = observer.ofType("tool.start")
        val ends = observer.ofType("tool.end")
        assertTrue("tool.start 数应等于 tool.end 数（${starts.size} vs ${ends.size}）", starts.size == ends.size)
        assertTrue("应至少有一次工具调用", starts.isNotEmpty())
    }

    check("R5-5 tool.end 带结果码（不是只有 isError）") {
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(EchoTool()) }
        loopWith(ScriptedClient(toolRounds = 1), registry, observer).send("你好")

        val end = observer.ofType("tool.end").first() as TurnEvent.ToolCallEnded
        assertTrue("code 不应为空", end.code.isNotBlank())
        assertTrue("成功工具的 code 应为 ok，实际 ${end.code}", end.code == "ok")
        assertTrue("isError 应为 false", !end.isError)
        assertTrue("durationMs 应 >= 0", end.durationMs >= 0)
    }

    check("R5-6 卡住定位：有 start 无 end 的那条能被找出来") {
        // 制造「工具挂起」的回合：发起的工具永不返回。
        //
        // 注意这里**不取消协程**——取消会走树尾闭合，把未完成的调用补一条
        // tool.end(code=cancelled)。那是「用户点了停止」，不是「卡住」，
        // 两者在时间线上必须能区分开：
        //   - 有 start 无 end          → 卡住（进程还活着，调用悬着）
        //   - 有 start 且 end=cancelled → 用户主动停止
        // 若这两种都表现为「最后一个 start」，R5 的判据就形同虚设。
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(HangingTool()) }
        val client = ScriptedClient(toolRounds = 1, toolName = "hang")
        val loop = loopWith(client, registry, observer)

        val job = GlobalScope.launch(Dispatchers.Default) {
            loop.send("卡住吧")
        }
        // 等 tool.start 发出（工具挂起后不会再有 tool.end）
        var waited = 0
        while (observer.ofType("tool.start").isEmpty() && waited < 3_000) {
            delay(20); waited += 20
        }
        try {
            val starts = observer.ofType("tool.start")
            val ends = observer.ofType("tool.end")
            assertTrue("应有 tool.start", starts.isNotEmpty())
            assertTrue("工具挂着时不应有 tool.end（实际 ${ends.size} 条）", ends.isEmpty())

            // 核心判据：定位「最后一个 start 且没有配对 end」
            val startTools = starts.map { (it as TurnEvent.ToolCallStarted).tool }
            val endTools = ends.map { (it as TurnEvent.ToolCallEnded).tool }
            val lastUnfinished = startTools.lastOrNull { it !in endTools }
            assertTrue("应能定位到未完成的工具，实际 start=$startTools end=$endTools", lastUnfinished == "hang")
        } finally {
            job.cancel()
            job.join()
        }
    }

    check("R5-6b 用户取消与卡住可区分：取消会补 tool.end（按副作用分两码）") {
        // 写工具被取消 → cancelled_unknown（可能已生效，先核对）
        val observer = RecordingObserver()
        val registry = ToolRegistry().apply { register(HangingTool()) }
        val loop = loopWith(ScriptedClient(toolRounds = 1, toolName = "hang"), registry, observer)
        val job = GlobalScope.launch(Dispatchers.Default) {
            loop.send("取消我")
        }
        var waited = 0
        while (observer.ofType("tool.start").isEmpty() && waited < 3_000) {
            delay(20); waited += 20
        }
        job.cancel()
        job.join()
        delay(100)

        val ends = observer.ofType("tool.end")
        assertTrue("取消后应补 tool.end（实际 ${ends.size} 条）", ends.isNotEmpty())
        val end = ends.first() as TurnEvent.ToolCallEnded
        assertTrue("写工具取消的 code 应为 cancelled_unknown，实际 ${end.code}", end.code == "cancelled_unknown")
        assertTrue("应是错误结果", end.isError)

        // 只读工具被取消 → cancelled（确定未完成，可安全重试）
        val observer2 = RecordingObserver()
        val registry2 = ToolRegistry().apply { register(ReadOnlyHangingTool()) }
        val loop2 = loopWith(ScriptedClient(toolRounds = 1, toolName = "hang_read"), registry2, observer2)
        val job2 = GlobalScope.launch(Dispatchers.Default) {
            loop2.send("取消我")
        }
        var waited2 = 0
        while (observer2.ofType("tool.start").isEmpty() && waited2 < 3_000) {
            delay(20); waited2 += 20
        }
        job2.cancel()
        job2.join()
        delay(100)

        val ends2 = observer2.ofType("tool.end")
        assertTrue("取消后应补 tool.end（实际 ${ends2.size} 条）", ends2.isNotEmpty())
        val end2 = ends2.first() as TurnEvent.ToolCallEnded
        assertTrue("只读工具取消的 code 应为 cancelled，实际 ${end2.code}", end2.code == "cancelled")
    }

    check("R5-7 回合结束状态正确") {
        // completed
        val obs1 = RecordingObserver()
        val reg1 = ToolRegistry().apply { register(EchoTool()) }
        loopWith(ScriptedClient(toolRounds = 1), reg1, obs1).send("你好")
        val ended1 = obs1.ofType("turn.end").first() as TurnEvent.TurnEnded
        assertTrue("状态应为 completed，实际 ${ended1.status}", ended1.status == "completed")

        // max_steps
        val obs2 = RecordingObserver()
        val reg2 = ToolRegistry().apply { register(EchoTool()) }
        loopWith(ScriptedClient(toolRounds = 99), reg2, obs2, maxSteps = 2).send("你好")
        val ended2 = obs2.ofType("turn.end").first() as TurnEvent.TurnEnded
        assertTrue("状态应为 max_steps，实际 ${ended2.status}", ended2.status == "max_steps")

        // cancelled
        val obs3 = RecordingObserver()
        val reg3 = ToolRegistry().apply { register(HangingTool()) }
        val loop3 = loopWith(ScriptedClient(toolRounds = 1, toolName = "hang"), reg3, obs3)
        val job3 = GlobalScope.launch(Dispatchers.Default) {
            loop3.send("取消我")
        }
        var waited3 = 0
        while (obs3.ofType("tool.start").isEmpty() && waited3 < 3_000) {
            delay(20); waited3 += 20
        }
        job3.cancel()
        job3.join()
        delay(100)
        val ended3 = obs3.ofType("turn.end").firstOrNull() as TurnEvent.TurnEnded?
        assertTrue("取消也应发 turn.end", ended3 != null)
        assertTrue("状态应为 cancelled，实际 ${ended3?.status}", ended3?.status == "cancelled")
    }

    check("R5-8 观察者抛异常不影响回合") {
        val observer = TurnObserver { throw RuntimeException("观察者炸了") }
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val client = ScriptedClient(toolRounds = 1)
        val result = loopWith(client, registry, observer).send("你好")
        assertTrue("回合应正常完成", result.isNotEmpty())
        assertTrue("模型应被调用", client.calls > 0)
    }
}

fun main() {
    println("=== AgentKit R5 验证（回合结构化事件 + turnId 贯穿）===")
    runBlocking { testEventSequence() }
    println("\n=== 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
