import java.io.File

/**
 * W-46 harness：本批次六项修复的**源码级**回归断言。
 *
 * 为什么是源码扫描而不是行为测试：
 *  - 历史注入的「有没有把历史塞进请求」在纯 JVM 里看不到（要跑通整个 Android VM 链），
 *    但它是一个**结构约束**——`messages.addAll(priorMessages)` 与返回值 `drop(prefixCount)`
 *    两处必须同时存在，缺一个就会「历史重复落盘」或「历史根本没发」。
 *  - 明文 HTTP、超时、@Volatile 这类配置项，本质也是「源码里有没有这一行」。
 *
 * 断言随产品变更同步更新：改动上述实现时，先改这里再改代码（否则回归面直接失效）。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

fun main() {
    println("== W-46 harness：历史注入 / 明文端点 / 超时 / 并发 / 终端会话 ==")

    val chatVm = read("app/src/main/java/com/example/agentkit/ui/ChatViewModel.kt")
    val loop = read("app/src/main/java/com/example/agentkit/core/agent/RealAgentLoop.kt")
    val adapter = read("app/src/main/java/com/example/agentkit/core/AgentLoop.kt")
    val manifest = read("app/src/main/AndroidManifest.xml")
    val nsc = read("app/src/main/res/xml/network_security_config.xml")
    val llmHttp = read("app/src/main/java/com/example/agentkit/core/llm/LlmHttp.kt")
    val oa = read("app/src/main/java/com/example/agentkit/core/LLMClient.kt")
    val claude = read("app/src/main/java/com/example/agentkit/core/llm/ClaudeClient.kt")
    val gemini = read("app/src/main/java/com/example/agentkit/core/llm/GeminiClient.kt")
    val pool = read("app/src/main/java/com/example/agentkit/core/terminal/TerminalSessionPool.kt")
    val builtIn = read("app/src/main/java/com/example/agentkit/tools/BuiltInTools.kt")

    check("H1 历史被摊平并随请求下发（send 路径）") {
        assertTrue("缺 buildPriorMessages 定义", "private fun buildPriorMessages(" in chatVm)
        assertTrue("send 未取历史", "val priorMessages = buildPriorMessages(owner)" in chatVm)
        assertTrue(
            "send 未把历史传给 loop",
            "loop.run(fullInput, images, priorMessages)" in chatVm
        )
    }

    check("H2 重新生成带历史但排除最后一轮（否则会复读旧答案）") {
        assertTrue(
            "regenerate 未排除最后一轮",
            "buildPriorMessages(owner, excludeLastTurn = true)" in chatVm
        )
    }

    check("H3 历史插在系统提示之后、当前输入之前") {
        // 只匹配到 buildSystemPrompt( 为止：具体参数会随实现调整
        // （exposure.tools → exposure0.visible 就变过一次），断言要验的是"顺序"不是"拼写"。
        val sysIdx = loop.indexOf("messages.add(SystemMessage(buildSystemPrompt(")
        val priorIdx = loop.indexOf("messages.addAll(priorMessages)")
        val userIdx = loop.indexOf("messages.add(UserMessage(userInput, images))", sysIdx)
        assertTrue("三处顺序缺失", sysIdx >= 0 && priorIdx > sysIdx && userIdx > priorIdx)
    }

    check("H4 返回值剔除历史前缀（否则调用方会把历史重复落盘）") {
        assertTrue(
            "缺 turnResult()",
            "fun turnResult(): List<ChatMessage> = messages.drop(priorMessages.size)" in loop
        )
        // 所有 return 都必须走 turnResult()，不允许再出现裸 return messages
        assertTrue("仍有裸 return messages", !loop.contains("return messages"))
    }

    check("H5 适配层与接口同步（三参数）") {
        assertTrue("接口未扩参", "priorMessages: List<ChatMessage> = emptyList()" in
            read("app/src/main/java/com/example/agentkit/core/agent/AgentLoop.kt"))
        assertTrue("适配层未透传", "impl.send(userInput, images, priorMessages)" in adapter)
        assertTrue("runAgent 未补参", "impl.send(userInput, emptyList(), emptyList())" in adapter)
    }

    check("C1 明文端点：Manifest 引用了 networkSecurityConfig") {
        assertTrue("Manifest 未引用", "android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest)
        assertTrue("未放开 cleartext", "cleartextTrafficPermitted=\"true\"" in nsc)
    }

    check("T1 LLM 通道不再用 OkHttp 默认 10s 读超时") {
        assertTrue("LlmHttp 缺 readTimeout", "readTimeout(180, TimeUnit.SECONDS)" in llmHttp)
        assertTrue("LlmHttp 缺 callTimeout=0", "callTimeout(0, TimeUnit.SECONDS)" in llmHttp)
        assertTrue("OpenAI 兼容通道未切换", "private val http: OkHttpClient = LlmHttp.shared" in oa)
        assertTrue("Claude 通道未切换", "private val http: OkHttpClient = LlmHttp.shared" in claude)
        assertTrue("Gemini 通道未切换", "private val http: OkHttpClient = LlmHttp.shared" in gemini)
        assertTrue("仍有裸 OkHttpClient()", "= OkHttpClient()" !in oa)
    }

    check("S1 http_request 不再谎称只读（POST 与 GET 不能并发）") {
        val section = builtIn.substring(builtIn.indexOf("class HttpRequestTool"))
        assertTrue("仍是 readOnly = true", "override val readOnly = false" in section)
    }

    check("S2 适配层重试次数与实现层一致（429 第三档生效）") {
        assertTrue("适配层仍是 2", "providerRetry: Int = 3" in adapter)
    }

    check("V1 owner 会话 id 跨线程可见") {
        assertTrue(
            "runOwnerConvId 缺 @Volatile",
            "@Volatile\n    private var runOwnerConvId: String? = null" in chatVm
        )
    }

    check("P1 终端输出边收边截（不再整段堆内存）") {
        assertTrue("缺 MAX_BUFFER_CHARS", "const val MAX_BUFFER_CHARS" in pool)
        assertTrue("收流处未截断", "if (builder.length > MAX_BUFFER_CHARS)" in pool)
        assertTrue("溢出未标注", "droppedChars" in pool)
    }

    check("P2 超时会话标脏，禁止静默复用") {
        assertTrue("缺 desynced 字段", "var desynced: Boolean = false" in pool)
        assertTrue("run 未拒绝脏会话", "code = \"session_desynced\"" in pool)
        assertTrue("超时未中断", "entry.backend.send(\"\\u0003\")" in pool)
    }

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
