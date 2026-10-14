#!/usr/bin/env kotlin
/**
 * Phase 2 · DoD④「本地模型工具暴露降级」QA 行为验证 harness。
 *
 * 目标：验证「按执行环境（provider）裁剪暴露给 LLM 的工具面」——provider = local-llama
 * 时只暴露核心集（≤7，含 read_file），云端 provider（openai/claude/gemini）行为完全不变；
 * 配套系统提示里的工具 systemPrompt 片段同步减少；被裁工具即便被本地模型瞎调，也走既有
 * 「未知工具」路径（T02 模糊匹配 + 可自纠 hint），**绝不抛异常**；裁剪发生时打一条 INFO。
 *
 * 仓库无测试源集（离线缓存无 JUnit），沿用纯 main() + 手写断言（形态学
 * scripts/qa/RetryVerification.kt）。运行方式见 scripts/qa/run-localmodel-verification.sh。
 */

import com.example.agentkit.core.*
import com.example.agentkit.core.agent.RealAgentLoop
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("$name: expected to contain <$needle> in <${haystack?.take(200)}>")
    }
}

private fun assertNotContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) == true) {
        throw AssertionError("$name: expected NOT to contain <$needle> in <${haystack?.take(200)}>")
    }
}

/* ------------------------------ 测试替身 ------------------------------ */

/** 真实的 25 个工具名（对齐 ChatViewModel 注册面），"20+ 个工具"的现状快照。 */
private val ALL_TOOL_NAMES: List<String> = listOf(
    "http_request", "read_file", "write_file", "clipboard", "send_notification",
    "ui_tree", "ui_tap", "ui_long_press", "ui_swipe", "ui_input", "ui_key", "ui_click",
    "ui_screenshot",
    "device_notifications", "device_foreground_app",
    "memory_write", "memory_search", "memory_list", "memory_forget",
    "shell_run", "shell_close",
    "app_list", "app_launch", "app_stop",
    "use_skill"
)

/**
 * 命名假工具：systemPrompt = `FRAG-<name>`（用于系统提示裁剪断言）、readOnly 可配、
 * 记录真实执行次数（用于证明被裁工具「根本没执行」）。
 */
private class NamedTool(
    override val name: String,
    override val readOnly: Boolean = true
) : AgentTool {
    override val description: String = "fake $name"
    override val systemPrompt: String = "FRAG-$name"
    override val parameters: JsonSchema = JsonSchema.objectSchema()
    var executions: Int = 0
        private set

    override suspend fun execute(args: JsonObject): ToolResult {
        executions++
        return ToolResult.ok(code = "ok", message = "$name ok", data = "$name data")
    }
}

/** 全量注册表（25 个命名假工具），返回 registry 与 name->tool 映射（供执行次数断言）。 */
private class Fixture {
    val tools: LinkedHashMap<String, NamedTool> = LinkedHashMap()
    val registry: ToolRegistry = ToolRegistry()

    init {
        ALL_TOOL_NAMES.forEach { n ->
            val t = NamedTool(n)
            tools[n] = t
            registry.register(t)
        }
    }
}

/**
 * 脚本化假 LLM：记录「最近一次收到的工具名列表 + 系统提示」，按调用序回放脚本，
 * 超出脚本长度复用最后一项。provider id 由构造参数指定（决定是否触发裁剪）。
 */
private class ScriptedLlm(
    override val id: String,
    private val script: List<AssistantMessage>
) : LLMClient {
    var calls: Int = 0
        private set
    var lastToolNames: List<String> = emptyList()
        private set
    var lastSystemPrompt: String = ""
        private set

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        lastToolNames = tools.map { it.name }
        lastSystemPrompt = (messages.firstOrNull { it is SystemMessage } as? SystemMessage)?.content ?: ""
        val r = script[minOf(calls, script.size - 1)]
        calls++
        return r
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

private fun argsOf(vararg kv: Pair<String, String>): JsonObject = buildJsonObject {
    kv.forEach { (k, v) -> put(k, v) }
}

/* ------------------------------ 用例 ------------------------------ */

/** 用例 1：核心集契约（≤7、恰好 7、含 read_file 与全部必需项、无重复、均为真实工具名）。 */
private suspend fun testCoreSetContract() {
    println("\n== 用例 1：本地核心集契约 ==")

    check("核心集大小恰好 7（≤7）且 read_file 必在其中") {
        val core = ToolExposurePolicy.LOCAL_CORE_TOOLS
        assertTrue("≤7（实际 ${core.size}）", core.size <= 7)
        assertEquals("恰好 7", 7, core.size)
        assertTrue("read_file 必含", "read_file" in core)
    }
    check("核心集含全部必需项且无重复、均为真实工具名") {
        val core = ToolExposurePolicy.LOCAL_CORE_TOOLS
        val required = listOf(
            "read_file", "write_file", "http_request", "ui_tree", "ui_tap", "clipboard", "use_skill"
        )
        required.forEach { assertTrue("必含 $it", it in core) }
        assertEquals("无重复", core.size, core.toSet().size)
        assertTrue("均为已注册面内的真实工具名", ALL_TOOL_NAMES.containsAll(core))
    }
}

/** 用例 2：exposureFor 纯函数语义（按 provider 裁剪 / 全量放行）。 */
private suspend fun testExposureSelection() {
    println("\n== 用例 2：exposureFor 按 provider 裁剪 ==")
    val all = Fixture().registry.list()

    check("provider=local-llama → 暴露列表 == 核心集（按重要度序），裁剪 18 个") {
        val ex = ToolExposurePolicy.exposureFor(all, "local-llama")
        assertEquals("exposedNames", ToolExposurePolicy.LOCAL_CORE_TOOLS, ex.exposedNames)
        assertEquals("exposedCount", 7, ex.exposedCount)
        assertEquals("totalCount", 25, ex.totalCount)
        assertEquals("trimmedCount", 18, ex.trimmedCount)
        assertTrue("trimmed", ex.trimmed)
        assertTrue("forcedFull=false", !ex.forcedFull)
    }
    check("provider=openai → 暴露列表 == 全量（25，注册序），行为不变") {
        val ex = ToolExposurePolicy.exposureFor(all, "openai")
        assertEquals("exposedNames == all", ALL_TOOL_NAMES, ex.exposedNames)
        assertEquals("trimmedCount", 0, ex.trimmedCount)
        assertTrue("trimmed=false", !ex.trimmed)
    }
    check("provider=claude → 暴露列表 == 全量") {
        val ex = ToolExposurePolicy.exposureFor(all, "claude")
        assertEquals("claude 全量", ALL_TOOL_NAMES, ex.exposedNames)
        assertTrue("未裁剪", !ex.trimmed)
    }
    check("provider=gemini → 暴露列表 == 全量") {
        val ex = ToolExposurePolicy.exposureFor(all, "gemini")
        assertEquals("gemini 全量", ALL_TOOL_NAMES, ex.exposedNames)
        assertTrue("未裁剪", !ex.trimmed)
    }
    check("未知 provider（fake）→ 保守按全量（不裁剪非本地）") {
        val ex = ToolExposurePolicy.exposureFor(all, "fake")
        assertEquals("fake 全量", ALL_TOOL_NAMES, ex.exposedNames)
        assertTrue("未裁剪", !ex.trimmed)
    }
    check("强制全量开关（local + forceFullTools=true）→ 暴露全量、forcedFull=true") {
        val ex = ToolExposurePolicy.exposureFor(all, "local-llama", forceFullTools = true)
        assertEquals("强制全量", ALL_TOOL_NAMES, ex.exposedNames)
        assertEquals("trimmedCount", 0, ex.trimmedCount)
        assertTrue("forcedFull=true", ex.forcedFull)
    }
    check("纯函数幂等：同入参两次调用结果相等") {
        val a = ToolExposurePolicy.exposureFor(all, "local-llama").exposedNames
        val b = ToolExposurePolicy.exposureFor(all, "local-llama").exposedNames
        assertEquals("idempotent", a, b)
    }
    check("核心工具未注册时只暴露「确实存在」的，且保持核心重要度序") {
        val reg = ToolRegistry()
        reg.register(NamedTool("use_skill"))
        reg.register(NamedTool("ui_tap"))
        reg.register(NamedTool("read_file"))
        val ex = ToolExposurePolicy.exposureFor(reg.list(), "local-llama")
        assertEquals("仅存在的核心工具，按核心序", listOf("read_file", "ui_tap", "use_skill"), ex.exposedNames)
    }
}

/** 用例 3：端到端 —— loop 下按 provider 裁剪工具定义。 */
private suspend fun testLoopExposure() {
    println("\n== 用例 3：RealAgentLoop 端到端按 provider 裁剪 ==")

    check("本地模型：模型只收到核心集工具定义（≤7）") {
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertEquals("收到的工具名", ToolExposurePolicy.LOCAL_CORE_TOOLS, llm.lastToolNames)
        assertTrue("≤7（实际 ${llm.lastToolNames.size}）", llm.lastToolNames.size <= 7)
    }
    check("云端模型（openai）：模型收到全量 25 个工具定义") {
        val fx = Fixture()
        val llm = ScriptedLlm("openai", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertEquals("全量", ALL_TOOL_NAMES, llm.lastToolNames)
    }
    check("强制全量开关（local + forceFullTools）：模型收到全量") {
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(
            llmProvider = { llm }, registry = fx.registry, providerRetry = 0, forceFullTools = true
        )
        loop.send("hi")
        assertEquals("强制全量", ALL_TOOL_NAMES, llm.lastToolNames)
    }
}

/** 用例 4：边界 —— 被裁工具被调用走未知工具路径（不抛异常 + 可自纠 hint）。 */
private suspend fun testTrimmedToolUnknownPath() {
    println("\n== 用例 4：被裁工具被调用 → 未知工具路径 ==")

    check("本地模型调 ui_swipe（已裁）：不抛异常、错误结果回喂、含自纠 hint、且未真正执行") {
        val fx = Fixture()
        val llm = ScriptedLlm(
            "local-llama",
            listOf(
                AssistantMessage("", listOf(ToolCall("c1", "ui_swipe", argsOf("start_x" to "1")))),
                AssistantMessage("done")
            )
        )
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        val history = loop.send("滑动一下") // 不得抛异常

        val tm = history.filterIsInstance<ToolMessage>().firstOrNull { it.id == "c1" }
        assertTrue("有 c1 的 ToolResult", tm != null)
        assertEquals("标记为错误", true, tm!!.isError)
        assertContains("未知工具 code", tm.content, "unknown_tool")
        assertContains("未知工具文案", tm.content, "未知工具 'ui_swipe'")
        assertContains("可自纠 hint", tm.content, "hint:")
        // 可用工具列表只含核心集：不应出现被裁工具 ui_long_press
        assertNotContains("可用列表不含被裁工具", tm.content, "ui_long_press")
        // 关键：被裁工具「根本没有执行」（回合级工具视图里查无此工具）
        assertEquals("ui_swipe 未被执行", 0, fx.tools["ui_swipe"]!!.executions)
        // 正常收尾
        assertEquals("正常收尾", "done", (history.last() as AssistantMessage).content)
    }
}

/** 用例 5：系统提示拼接 —— 工具 systemPrompt 片段随裁剪同步减少。 */
private suspend fun testSystemPromptTrim() {
    println("\n== 用例 5：系统提示拼接随裁剪同步 ==")

    check("本地模型：系统提示含核心工具片段、不含被裁工具片段") {
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertContains("含 read_file 片段", llm.lastSystemPrompt, "FRAG-read_file")
        assertContains("含 use_skill 片段", llm.lastSystemPrompt, "FRAG-use_skill")
        assertNotContains("不含被裁 ui_swipe 片段", llm.lastSystemPrompt, "FRAG-ui_swipe")
        assertNotContains("不含被裁 memory_write 片段", llm.lastSystemPrompt, "FRAG-memory_write")
        assertNotContains("不含被裁 shell_run 片段", llm.lastSystemPrompt, "FRAG-shell_run")
    }
    check("云端模型（claude）：系统提示同时含核心与被裁工具片段（全量，未变）") {
        val fx = Fixture()
        val llm = ScriptedLlm("claude", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertContains("含 read_file 片段", llm.lastSystemPrompt, "FRAG-read_file")
        assertContains("含被裁 ui_swipe 片段", llm.lastSystemPrompt, "FRAG-ui_swipe")
        assertContains("含被裁 memory_write 片段", llm.lastSystemPrompt, "FRAG-memory_write")
    }
}

/**
 * 用例 6：端到端（假 LLMClient 剧本）——本地路径下模型只收到 ≤7 个工具定义，
 * 且一次 read_file 调用能成功执行并回喂。
 */
private suspend fun testEndToEndReadFile() {
    println("\n== 用例 6：端到端 read_file（DoD ④ 直接验收） ==")

    check("本地模型：≤7 工具 + read_file 调用成功执行并回喂") {
        val fx = Fixture()
        val llm = ScriptedLlm(
            "local-llama",
            listOf(
                AssistantMessage("", listOf(ToolCall("r1", "read_file", argsOf("path" to "note.txt")))),
                AssistantMessage("读完啦")
            )
        )
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        val history = loop.send("读一下 note.txt")

        assertTrue("模型收到 ≤7 个工具定义（实际 ${llm.lastToolNames.size}）", llm.lastToolNames.size <= 7)
        assertEquals("收到的正是核心集", ToolExposurePolicy.LOCAL_CORE_TOOLS, llm.lastToolNames)
        assertTrue("read_file 在暴露面内", "read_file" in llm.lastToolNames)

        val tm = history.filterIsInstance<ToolMessage>().firstOrNull { it.id == "r1" }
        assertTrue("有 r1 的 ToolResult", tm != null)
        assertEquals("成功（非错误）", false, tm!!.isError)
        assertContains("回喂 read_file 结果", tm.content, "read_file ok")
        assertEquals("read_file 真的执行了", 1, fx.tools["read_file"]!!.executions)
        assertEquals("正常收尾", "读完啦", (history.last() as AssistantMessage).content)
    }
}

/** 用例 7：日志 —— 裁剪发生时打一条 INFO（provider + 暴露数量 + 裁剪掉的数量）。 */
private suspend fun testTrimLogging() {
    println("\n== 用例 7：裁剪 INFO 日志 ==")

    check("本地裁剪 → 一条 INFO，含 provider / 暴露数 / 裁剪数") {
        AgentLogger.clear()
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")

        val hit = AgentLogger.recent(500).firstOrNull { it.tag == "ToolExposure" }
        assertTrue("有 ToolExposure 日志", hit != null)
        assertEquals("级别 INFO", LogLevel.INFO, hit!!.level)
        assertContains("provider", hit.message, "provider=local-llama")
        assertContains("暴露数量", hit.message, "暴露 7 个")
        assertContains("裁剪数量", hit.message, "裁剪掉 18 个")
    }
    check("云端（未裁剪）→ 不打 ToolExposure 日志（不刷屏）") {
        AgentLogger.clear()
        val fx = Fixture()
        val llm = ScriptedLlm("openai", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        val hit = AgentLogger.recent(500).firstOrNull { it.tag == "ToolExposure" }
        assertTrue("无 ToolExposure 日志", hit == null)
    }
}

/* ------------------------------ 入口 ------------------------------ */

fun main() = runBlocking {
    println("== Phase 2 DoD④ 本地模型工具暴露降级 · QA 行为验证 ==")

    testCoreSetContract()
    testExposureSelection()
    testLoopExposure()
    testTrimmedToolUnknownPath()
    testSystemPromptTrim()
    testEndToEndReadFile()
    testTrimLogging()

    println("\n== 汇总 ==")
    println("PASS: $passed  FAIL: ${failures.size}  TOTAL: ${passed + failures.size}")
    if (failures.isNotEmpty()) {
        println("失败用例：")
        failures.forEach { println("  - $it") }
        throw AssertionError("${failures.size} 个用例失败")
    }
    println("LOCALMODEL_VERIFICATION: ALL PASS")
}
