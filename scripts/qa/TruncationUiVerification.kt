#!/usr/bin/env kotlin
/**
 * 「输出被截断」从**可查**走到**可见**的验证 harness。
 *
 * 上一轮（`FinishReasonVerification.kt`）只验证了三家响应字段能归一化成
 * [com.beichen.polaris.core.llm.FinishReason.LENGTH]——那是「取到了」。
 * 取到但没有消费者，用户界面上依然看不见，截断这件事对用户仍是隐形的。
 *
 * 本轮补的是后半程：`RealAgentLoop` 在拿到 `finishReason=length` 的那一轮抛出
 * [AgentEvent.OutputTruncated]，UI 层据此显示提示条。本 harness 只验 loop 侧
 * （事件该不该发、发几次、带什么），UI 侧的状态置位由
 * `GenerationRunVerification.kt` 的 `onStarted` 用例守着。
 *
 * ## 为什么每条断言都要能红
 *
 * 「不发事件」是最容易写错的实现：`finishReason` 是可选字段，写
 * `if (resp.finishReason != null)` 或 `if (x == "LENGTH")` 都会静默通过——
 * 前者把 `stop` 也当成截断，后者因为大小写永远不成立。所以这里必须同时有
 * **正例**（length 要发）与**反例**（stop / null 不能发），只留正例等于没测。
 *
 * 运行：bash scripts/qa/run-truncation-ui-verification.sh
 */

import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LlmHttpException
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.llm.FinishReason
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking

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

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("$name: expected <$expected> but was <$actual>")
}

/* ------------------------------ 测试替身 ------------------------------ */

/**
 * 脚本化 LLM：第 n 次调用执行 `behaviors[n]`（超出取最后一项）。
 *
 * 只覆盖 `chat()`——`LLMClient.streamChat` 的默认实现就是「chat() + 单条 Completed」，
 * 这正是四个客户端里未覆盖流式的那几个的真实形态，测它等于测默认路径。
 */
private class ScriptedLlm(
    private val behaviors: List<suspend (Int) -> AssistantMessage>
) : LLMClient {
    override val id: String = "scripted"
    var calls: Int = 0
        private set

    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage {
        val index = calls
        calls++
        return behaviors.getOrElse(index) { behaviors.last() }.invoke(index)
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/** 截断事件探针：只关心 [AgentEvent.OutputTruncated]，其余事件一概忽略。 */
private class TruncationProbe {
    private val lock = Any()
    private val got = mutableListOf<String?>()

    fun onEvent(ev: AgentEvent) {
        if (ev is AgentEvent.OutputTruncated) synchronized(lock) { got += ev.reason }
    }

    fun count(): Int = synchronized(lock) { got.size }

    fun reasons(): List<String?> = synchronized(lock) { got.toList() }
}

private class StubTool(override val name: String) : AgentTool {
    override val description: String = "stub $name"
    override val parameters: JsonSchema = JsonSchema.objectSchema()
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok("ok", "done", "payload")
}

private fun argsOf(vararg kv: Pair<String, String>): JsonObject = buildJsonObject {
    kv.forEach { (k, v) -> put(k, v) }
}

/** 一条「因长度上限而结束」的回答。 */
private fun truncated(text: String) =
    AssistantMessage(text, finishReason = FinishReason.LENGTH)

/** 用例外壳：把 suspend 用例包成同步 check，单条失败不影响后续。 */
private fun case(name: String, block: suspend () -> Unit) {
    check(name) { runBlocking { block() } }
}

/* ------------------------------ 用例 ------------------------------ */

fun main() {
    println("== 输出截断：从可查到可见 ==")

    println("\n[1] 最终回答撞长度上限 → 恰好上报一次，且带上原因")
    case("length → 1 次事件 + 原因透传") {
        val probe = TruncationProbe()
        val llm = ScriptedLlm(listOf { truncated("说到一半") })
        RealAgentLoop(llmProvider = { llm }, registry = ToolRegistry(), onEvent = probe::onEvent).send("hi")

        assertEquals("事件数", 1, probe.count())
        assertEquals("原因透传", listOf<String?>(FinishReason.LENGTH), probe.reasons())
    }

    println("\n[2] 正常说完（stop）→ 绝不能上报（否则每轮都弹提示）")
    case("stop → 0 次事件") {
        val probe = TruncationProbe()
        val llm = ScriptedLlm(listOf { AssistantMessage("说完了", finishReason = FinishReason.STOP) })
        RealAgentLoop(llmProvider = { llm }, registry = ToolRegistry(), onEvent = probe::onEvent).send("hi")

        assertEquals("事件数", 0, probe.count())
    }

    println("\n[3] 供应商没给 finish_reason（null）→ 也不能上报")
    case("null → 0 次事件（不伪造截断）") {
        val probe = TruncationProbe()
        val llm = ScriptedLlm(listOf { AssistantMessage("没有该字段") })
        RealAgentLoop(llmProvider = { llm }, registry = ToolRegistry(), onEvent = probe::onEvent).send("hi")

        assertEquals("事件数", 0, probe.count())
    }

    println("\n[4] 退化为空串 → 视作未知，不上报（某些兼容端点会下发 \"\"）")
    case("空串 → 0 次事件") {
        val probe = TruncationProbe()
        val llm = ScriptedLlm(listOf { AssistantMessage("空串", finishReason = "") })
        RealAgentLoop(llmProvider = { llm }, registry = ToolRegistry(), onEvent = probe::onEvent).send("hi")

        assertEquals("事件数", 0, probe.count())
    }

    println("\n[5] 多步回合每一步都被截断 → 整回合只报一次（不刷屏）")
    case("多步重复截断 → 仍只 1 次") {
        val probe = TruncationProbe()
        val registry = ToolRegistry()
        registry.register(StubTool("probe_tool"))
        val llm = ScriptedLlm(
            listOf(
                // 第 1 步：既要调工具，又撞了长度上限
                {
                    AssistantMessage(
                        "先看一眼",
                        listOf(ToolCall("c1", "probe_tool", argsOf())),
                        finishReason = FinishReason.LENGTH
                    )
                },
                // 第 2 步：还是要调工具，仍然被截断
                {
                    AssistantMessage(
                        "再看一眼",
                        listOf(ToolCall("c2", "probe_tool", argsOf())),
                        finishReason = FinishReason.LENGTH
                    )
                },
                // 第 3 步：收尾，同样被截断
                { AssistantMessage("最终答案", finishReason = FinishReason.LENGTH) },
            )
        )
        RealAgentLoop(
            llmProvider = { llm },
            registry = registry,
            onEvent = probe::onEvent,
            forceFullTools = true,
        ).send("hi")

        assertTrue("确实跑了不止一步（否则本用例没测到多步）", llm.calls >= 2)
        assertEquals("事件数（每回合至多一次）", 1, probe.count())
    }

    println("\n[6] 重试救回来的请求同样上报（字段不因重试丢失）")
    case("重试后成功但截断 → 仍上报 1 次") {
        val probe = TruncationProbe()
        val llm = ScriptedLlm(
            listOf(
                { throw LlmHttpException(500, "server boom") },
                { truncated("重试后的回答也没说完") },
            )
        )
        RealAgentLoop(
            llmProvider = { llm },
            registry = ToolRegistry(),
            providerRetry = 2,
            onEvent = probe::onEvent,
            retryBackoff = { },
        ).send("hi")

        assertEquals("确实重试过", 2, llm.calls)
        assertEquals("事件数", 1, probe.count())
        assertEquals("原因", listOf<String?>(FinishReason.LENGTH), probe.reasons())
    }

    println("\n[7] 未配置 onEvent（null）→ 截断不应导致崩溃")
    case("onEvent=null 时不崩") {
        val llm = ScriptedLlm(listOf { truncated("没有监听者") })
        RealAgentLoop(llmProvider = { llm }, registry = ToolRegistry(), onEvent = null).send("hi")
        assertTrue("没有监听者也能正常跑完", llm.calls == 1)
    }

    println("\n==== 结果：PASS=$passed FAIL=${failures.size} ====")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
