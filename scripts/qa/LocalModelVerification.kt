#!/usr/bin/env kotlin
/**
 * 「端侧小模型工具暴露」QA harness。
 *
 * ## 这份 harness 验什么
 *
 * 它最早验证的是 `ToolExposurePolicy` —— 一张写死的 7 条白名单（本地只给这 7 个）。
 * 现役实现换成了 `KernelExposure`：按名字分组、按能力档位分派、元工具按需展开，
 * 白名单已无调用方。所以这里改为验证**同一条意图在新实现下的落地**：
 *
 *  1. 端侧小模型拿到的是最小集（file 组 + 两个元工具），不是全量
 *  2. 云端拿到基础三组 + 元工具——比全量小，但覆盖最常用的能力
 *  3. 被裁的工具既不执行、也不消失：
 *     直接调 → 未知工具路径（可自纠 hint，不抛异常）；查 → `__find_tools` 能找回来
 *  4. 系统提示与首轮暴露面一致（不会「给了工具却没教」或「教了工具却没给」）
 *  5. 裁剪发生时打一条 INFO，含 provider / 暴露数 / 裁剪数
 *
 * ## 明确不在这里验
 *
 * 分组规则本身、三模式语义、预算折叠 —— 那些在 `KernelExposureVerification`（34 项）
 * 和 `KernelCoreVerification`（47 项）。重复验会让两份 harness 互相拖累：
 * 改个分组规则要同时改两处断言，久而久之就没人敢改了。
 *
 * 运行：`bash scripts/qa/run-localmodel-verification.sh`
 */

import com.example.agentkit.core.*
import com.example.agentkit.core.agent.RealAgentLoop
import com.example.agentkit.core.kernel.ExposureMode
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
 * 各档位的预期暴露面（对着上面 25 个工具算出来的，改分组规则时必须同步改这里）。
 *
 * W-102 起云端与端侧默认面一致：都只给 file 组（read_file / write_file），
 * 加 2 个元工具 → 4。改之前云端是三组（file+ui+skill = 13）。
 * 两个常量都留着而不是合并成一个：将来档位再分化时，改动面只有数值。
 */
private const val CLOUD_EXPOSED = 4
private const val TINY_EXPOSED = 4

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
 * 超出脚本长度复用最后一项。provider id 由构造参数指定（决定档位）。
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

private fun planFor(provider: String, forceFull: Boolean = false): KernelExposure.Plan =
    KernelExposure.plan(Fixture().registry.list(), provider, forceFull, mutableSetOf())

/* ------------------------------ 用例 ------------------------------ */

/**
 * 用例 1：端侧最小集 —— 本地模型能看见什么，一开始就定死。
 *
 * 为什么连 ui_* 都不给：1.5B~4B 的模型在十几个工具里选错的概率，比「先用 __use_tools
 * 开一组」贵得多；而 ui_* 的 description 又特别长，对小模型是纯噪声。
 */
private suspend fun testTinySurface() {
    println("\n== 用例 1：端侧最小集 ==")

    check("本地初始集 = file 组 + 两个元工具，共 $TINY_EXPOSED 个") {
        val plan = planFor("local-llama")
        assertEquals("暴露数", TINY_EXPOSED, plan.visible.size)
        assertEquals("模式", ExposureMode.META, plan.mode)
        assertTrue("含 read_file", "read_file" in plan.names)
        assertTrue("含 write_file", "write_file" in plan.names)
        assertTrue("含检索元工具", KernelExposure.FIND_TOOLS in plan.names)
        assertTrue("含启用元工具", KernelExposure.USE_TOOLS in plan.names)
    }
    check("本地初始集不含屏幕操作、记忆、命令执行") {
        val plan = planFor("local-llama")
        for (n in listOf("ui_swipe", "ui_tap", "ui_screenshot", "memory_write", "shell_run", "use_skill")) {
            assertTrue("不含 $n", n !in plan.names)
        }
    }
    check("本地裁掉的数量 = 全量 - file 组（元工具不算被裁）") {
        val plan = planFor("local-llama")
        assertEquals("hiddenCount", ALL_TOOL_NAMES.size - 2, plan.hiddenCount)
        assertTrue("trimmed 为真", plan.trimmed)
    }
}

/** 用例 2：云端初始面 —— W-102 起与端侧一致：只给 file 组 + 元工具。 */
private suspend fun testCloudSurface() {
    println("\n== 用例 2：云端初始面 = file 组 ==")

    check("云端初始集 = file 组 + 元工具，共 $CLOUD_EXPOSED 个") {
        val plan = planFor("openai")
        assertEquals("暴露数", CLOUD_EXPOSED, plan.visible.size)
        assertEquals("模式", ExposureMode.GROUPED, plan.mode)
        // misc 只有存在「前缀推不出组」的工具时才出现；这份 fixture 里没有，
        // 但断言写成减法，避免将来往 fixture 加一个自定义工具就红。
        assertEquals("激活组", setOf("file"), plan.activated - "misc")
    }
    check("云端默认只给读写文件，其余靠 __use_tools") {
        val plan = planFor("openai")
        assertTrue("少于全量", plan.visible.size < ALL_TOOL_NAMES.size)
        for (n in listOf("read_file", "write_file")) {
            assertTrue("含 $n", n in plan.names)
        }
        for (n in listOf("ui_click", "ui_screenshot", "use_skill", "memory_write", "shell_run")) {
            assertTrue("不含 $n（要靠 __use_tools 开）", n !in plan.names)
        }
    }
    check("任何云端 provider 都走同一档（不再按 provider 名逐个登记）") {
        val sizes = listOf("openai", "claude", "gemini", "deepseek", "unknown-thing")
            .map { planFor(it).visible.size }
        assertEquals("各 provider 暴露数一致", List(sizes.size) { CLOUD_EXPOSED }, sizes)
    }
    check("强制全量开关：返回全量、无元工具、不裁剪") {
        val plan = planFor("local-llama", forceFull = true)
        assertEquals("暴露数", ALL_TOOL_NAMES.size, plan.visible.size)
        assertEquals("模式", ExposureMode.FULL, plan.mode)
        assertTrue("不含元工具", KernelExposure.FIND_TOOLS !in plan.names)
        assertTrue("trimmed=false", !plan.trimmed)
    }
    check("幂等：同入参两次调用结果一致") {
        val a = planFor("local-llama").names
        val b = planFor("local-llama").names
        assertEquals("两次结果相同", a, b)
    }
    check("只有 3 个工具时不会崩，且暴露的都在注册面内") {
        val reg = ToolRegistry()
        reg.register(NamedTool("read_file"))
        reg.register(NamedTool("ui_tap"))
        reg.register(NamedTool("use_skill"))
        val plan = KernelExposure.plan(reg.list(), "local-llama", false, mutableSetOf())
        assertTrue("暴露数不超过注册数", plan.visible.size <= 5)
        assertTrue("含 read_file", "read_file" in plan.names)
        assertTrue("不含未注册的 write_file", "write_file" !in plan.names)
    }
}

/** 用例 3：端到端 —— RealAgentLoop 下模型实际收到的工具定义。 */
private suspend fun testLoopExposure() {
    println("\n== 用例 3：RealAgentLoop 端到端 ==")

    check("本地模型：模型收到 $TINY_EXPOSED 个工具定义（不是全量）") {
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertEquals("收到的工具名个数", TINY_EXPOSED, llm.lastToolNames.size)
        assertTrue("少于全量", llm.lastToolNames.size < ALL_TOOL_NAMES.size)
        assertTrue("含 read_file", "read_file" in llm.lastToolNames)
    }
    check("云端模型（openai）：模型收到 $CLOUD_EXPOSED 个工具定义") {
        val fx = Fixture()
        val llm = ScriptedLlm("openai", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertEquals("收到的工具名个数", CLOUD_EXPOSED, llm.lastToolNames.size)
    }
    check("强制全量开关：模型收到全量 25 个") {
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(
            llmProvider = { llm }, registry = fx.registry, providerRetry = 0, forceFullTools = true
        )
        loop.send("hi")
        assertEquals("全量", ALL_TOOL_NAMES, llm.lastToolNames)
    }
}

/** 用例 4：被裁工具被调用 → 未知工具路径（不抛异常 + 可自纠 hint + 真的没执行）。 */
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
        // 可用工具列表只含本回合暴露面：不应出现另一个被裁工具
        assertNotContains("可用列表不含被裁工具", tm.content, "ui_long_press")
        // 关键：被裁工具「根本没有执行」（回合级工具视图里查无此工具）
        assertEquals("ui_swipe 未被执行", 0, fx.tools["ui_swipe"]!!.executions)
        // 正常收尾
        assertEquals("正常收尾", "done", (history.last() as AssistantMessage).content)
    }
}

/** 用例 5：系统提示与暴露面一致 —— 教了哪些工具，就只教这些。 */
private suspend fun testSystemPromptSync() {
    println("\n== 用例 5：系统提示与首轮暴露面一致 ==")

    check("本地模型：提示含 file 组片段、不含未暴露组片段") {
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertContains("含 read_file 片段", llm.lastSystemPrompt, "FRAG-read_file")
        assertContains("含 write_file 片段", llm.lastSystemPrompt, "FRAG-write_file")
        assertNotContains("不含 ui_swipe 片段", llm.lastSystemPrompt, "FRAG-ui_swipe")
        assertNotContains("不含 memory_write 片段", llm.lastSystemPrompt, "FRAG-memory_write")
        assertNotContains("不含 shell_run 片段", llm.lastSystemPrompt, "FRAG-shell_run")
        assertNotContains("不含 use_skill 片段（本地不给技能组）", llm.lastSystemPrompt, "FRAG-use_skill")
    }
    check("云端模型（claude）：初始提示与端侧一致，只含 file 组片段") {
        val fx = Fixture()
        val llm = ScriptedLlm("claude", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        assertContains("含 read_file 片段", llm.lastSystemPrompt, "FRAG-read_file")
        assertNotContains("不含 ui_swipe 片段（未激活）", llm.lastSystemPrompt, "FRAG-ui_swipe")
        assertNotContains("不含 use_skill 片段（未激活）", llm.lastSystemPrompt, "FRAG-use_skill")
        assertNotContains("不含 memory_write 片段", llm.lastSystemPrompt, "FRAG-memory_write")
    }
    check("提示只拼一次：第二轮不再重拼（保 KV 前缀稳定）") {
        val fx = Fixture()
        val llm = ScriptedLlm(
            "local-llama",
            listOf(
                AssistantMessage("", listOf(ToolCall("r1", "read_file", argsOf("path" to "a.txt")))),
                AssistantMessage("好")
            )
        )
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("读 a.txt")
        // 两轮都在，但系统提示是同一份（ScriptedLlm 每次覆盖，最后一次即为第二轮）
        assertEquals("两轮都发生了", 2, llm.calls)
        assertContains("第二轮提示仍含 read_file 片段", llm.lastSystemPrompt, "FRAG-read_file")
    }
}

/**
 * 用例 6：端到端 —— 一次 read_file 调用能成功执行并回喂。
 *
 * 这条是 DoD 的直接验收：裁剪之后「最常用的那个工具仍然能用」，而不是只剩元工具。
 */
private suspend fun testEndToEndReadFile() {
    println("\n== 用例 6：端到端 read_file ==")

    check("本地模型：read_file 调用成功执行并回喂") {
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

        assertEquals("模型收到的工具数", TINY_EXPOSED, llm.lastToolNames.size)
        assertTrue("read_file 在暴露面内", "read_file" in llm.lastToolNames)

        val tm = history.filterIsInstance<ToolMessage>().firstOrNull { it.id == "r1" }
        assertTrue("有 r1 的 ToolResult", tm != null)
        assertEquals("成功（非错误）", false, tm!!.isError)
        assertContains("回喂 read_file 结果", tm.content, "read_file ok")
        assertEquals("read_file 真的执行了", 1, fx.tools["read_file"]!!.executions)
        assertEquals("正常收尾", "读完啦", (history.last() as AssistantMessage).content)
    }
}

/** 用例 7：日志 —— 裁剪发生时打一条 INFO（provider + 暴露数 + 裁剪数 + 已启用组）。 */
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
        assertContains("暴露数量", hit.message, "暴露 $TINY_EXPOSED 个")
        assertContains("裁剪数量", hit.message, "裁掉 ${ALL_TOOL_NAMES.size - 2} 个")
        assertContains("已启用组", hit.message, "已启用组=file")
    }
    check("云端也裁剪 → 同样打一条（否则云端省了多少无从得知）") {
        AgentLogger.clear()
        val fx = Fixture()
        val llm = ScriptedLlm("openai", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(llmProvider = { llm }, registry = fx.registry, providerRetry = 0)
        loop.send("hi")
        val hit = AgentLogger.recent(500).firstOrNull { it.tag == "ToolExposure" }
        assertTrue("有 ToolExposure 日志", hit != null)
        assertContains("暴露数量", hit!!.message, "暴露 $CLOUD_EXPOSED 个")
        assertContains("已启用组", hit.message, "已启用组=file")
    }
    check("强制全量 → 不打日志（没裁剪，不该刷屏）") {
        AgentLogger.clear()
        val fx = Fixture()
        val llm = ScriptedLlm("local-llama", listOf(AssistantMessage("ok")))
        val loop = RealAgentLoop(
            llmProvider = { llm }, registry = fx.registry, providerRetry = 0, forceFullTools = true
        )
        loop.send("hi")
        val hit = AgentLogger.recent(500).firstOrNull { it.tag == "ToolExposure" }
        assertTrue("无 ToolExposure 日志", hit == null)
    }
}

/* ------------------------------ 入口 ------------------------------ */

fun main() = runBlocking {
    println("== 端侧小模型工具暴露 · QA 行为验证 ==")

    testTinySurface()
    testCloudSurface()
    testLoopExposure()
    testTrimmedToolUnknownPath()
    testSystemPromptSync()
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
