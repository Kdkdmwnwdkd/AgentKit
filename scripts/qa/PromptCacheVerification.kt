#!/usr/bin/env kotlin
/**
 * 「提示缓存」QA harness —— 验证 Claude 的显式缓存断点确实进了请求体，以及失效时的降级。
 *
 * ## 为什么值得单独一份 harness
 *
 * 提示缓存是**静默失败**的典型：断点打错位置、字段名拼错、system 还是字符串，
 * 三种情况都不会报错——请求照发、回答照回，只是账单按全价走。所以不能靠「看着对」，
 * 必须把真实请求体截下来逐字段断言。
 *
 * 这里起一个本地 HTTP server 扮演 Anthropic 端点，让 [ClaudeClient] 真实发一次请求，
 * 然后解析它收到的 JSON。比 mock 掉 client 更有价值的地方在于：**序列化路径也在测**。
 *
 * ## 验什么
 *
 *  1. system 从字符串变成 text 块数组，且 `cache_control` 落在最后一块上
 *  2. 缓存断点只打在**最后一个** tool 上（逐项打会白吃 4 个断点配额）
 *  3. 断点总数 ≤ 2（Anthropic 上限 4，超了 400）
 *  4. 没有 system 时照样能缓存（断点只剩 tools 一处）
 *  5. 端点拒绝 `cache_control` → 自动摘标记重发一次并成功，且此后不再试探
 *  6. 三家 usage 解析器都认自己的缓存字段，且口径差异正确
 *  7. `TokenUsage.sum` 把缓存字段也累加（不然一轮多步的命中率会被算成 0）
 *
 * ## 明确不在这里验
 *
 * 「命中率到底多少」只能真机看账单，harness 造不出来——服务端不按我们的断点回填。
 *
 * 运行：`bash scripts/qa/run-prompt-cache-verification.sh`
 */

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.SystemMessage
import com.example.agentkit.core.TokenUsage
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.UserMessage
import com.example.agentkit.core.llm.ClaudeClient
import com.example.agentkit.core.llm.parseClaudeUsage
import com.example.agentkit.core.llm.parseGeminiUsage
import com.example.agentkit.core.llm.parseOpenAiUsage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/* ------------------------------ 迷你断言框架 ------------------------------ */

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
 * 假 Anthropic 端点。
 *
 * @param rejectCache true = 只要请求体里出现 `cache_control` 就回 400 并给出典型报错文案，
 *   用来模拟不认这个字段的中转站。
 */
private class FakeAnthropic(private val rejectCache: Boolean) {
    /** 收到的全部请求体（按到达顺序），断言直接读它。 */
    val bodies = CopyOnWriteArrayList<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val port: Int get() = server.address.port

    init {
        server.createContext("/v1/messages") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += body
            val (code, text) = if (rejectCache && "cache_control" in body) {
                400 to """{"type":"error","error":{"type":"invalid_request_error",""" +
                    """"message":"messages.0: unexpected field: cache_control"}}"""
            } else {
                // 回一个带缓存字段的用量：顺便验证解析链路在真实响应体上不炸
                200 to """{"id":"msg_1","type":"message","role":"assistant",""" +
                    """"content":[{"type":"text","text":"ok"}],""" +
                    """"usage":{"input_tokens":100,"output_tokens":5,""" +
                    """"cache_read_input_tokens":3000,"cache_creation_input_tokens":0}}"""
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun close() = server.stop(0)

    /** 最近一次请求体的解析结果。 */
    fun lastBody(): JsonObject = Json.parseToJsonElement(bodies.last()).jsonObject
}

/** 命名假工具：description/schema 固定，只为让请求体里有真实长度的 tools 块。 */
private class FakeTool(override val name: String) : AgentTool {
    override val description: String = "fake $name tool"
    override val parameters: JsonSchema = JsonSchema.objectSchema()
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(code = "ok", message = "ok")
}

private fun claudeFor(port: Int): ClaudeClient = ClaudeClient(
    LLMConfig(baseUrl = "http://127.0.0.1:$port", apiKey = "test-key", model = "claude-3-5-sonnet"),
    OkHttpClient()
)

/** 统计请求体里出现几次 `cache_control`（断点配额按这个数算）。 */
private fun breakpointCount(body: String): Int = Regex("cache_control").findAll(body).count()

/* ------------------------------ 用例 ------------------------------ */

fun main() {
    println("== 用例 1：断点落在该落的位置 ==")

    check("system 变成 text 块数组，断点在最后一块，原文不丢") {
        val srv = FakeAnthropic(rejectCache = false)
        try {
            runBlocking {
                claudeFor(srv.port).chat(
                    listOf(SystemMessage("SYS-PROMPT"), UserMessage("hi")),
                    listOf(FakeTool("a"), FakeTool("b"))
                )
            }
            val sys = srv.lastBody()["system"]!!.jsonArray
            assertEquals("system 是块数组", 1, sys.size)
            val block = sys[0].jsonObject
            assertEquals("块类型", "text", block["type"]!!.jsonPrimitive.content)
            assertEquals("原文逐字保留", "SYS-PROMPT", block["text"]!!.jsonPrimitive.content)
            assertEquals(
                "断点类型",
                "ephemeral",
                block["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content
            )
        } finally {
            srv.close()
        }
    }

    check("断点只打在最后一个 tool 上，前面的工具不带") {
        val srv = FakeAnthropic(rejectCache = false)
        try {
            runBlocking {
                claudeFor(srv.port).chat(
                    listOf(SystemMessage("S"), UserMessage("hi")),
                    listOf(FakeTool("a"), FakeTool("b"), FakeTool("c"))
                )
            }
            val tools = srv.lastBody()["tools"]!!.jsonArray
            assertEquals("工具数量", 3, tools.size)
            assertTrue("第 1 个不带断点", tools[0].jsonObject["cache_control"] == null)
            assertTrue("第 2 个不带断点", tools[1].jsonObject["cache_control"] == null)
            assertEquals(
                "最后 1 个带断点",
                "ephemeral",
                tools[2].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content
            )
        } finally {
            srv.close()
        }
    }

    check("断点总数 ≤ 2（Anthropic 上限 4，超了会 400）") {
        val srv = FakeAnthropic(rejectCache = false)
        try {
            runBlocking {
                claudeFor(srv.port).chat(
                    listOf(SystemMessage("S"), UserMessage("hi")),
                    listOf(FakeTool("a"), FakeTool("b"))
                )
            }
            val n = breakpointCount(srv.bodies.last())
            assertTrue("实际断点数 $n 不超过 2", n <= 2)
        } finally {
            srv.close()
        }
    }

    println("\n== 用例 2：没有 system 时照样能缓存 ==")

    check("无 system：断点只落在 tools 上，且不产生空的 system 字段") {
        val srv = FakeAnthropic(rejectCache = false)
        try {
            runBlocking {
                claudeFor(srv.port).chat(listOf(UserMessage("hi")), listOf(FakeTool("a")))
            }
            val body = srv.lastBody()
            assertTrue("没有 system 字段", body["system"] == null)
            val tools = body["tools"]!!.jsonArray
            assertEquals(
                "唯一工具带断点",
                "ephemeral",
                tools[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content
            )
        } finally {
            srv.close()
        }
    }

    println("\n== 用例 3：端点不认缓存时自动降级 ==")

    check("400 提到 cache_control → 摘掉标记重发一次并成功") {
        val srv = FakeAnthropic(rejectCache = true)
        try {
            val reply = runBlocking {
                claudeFor(srv.port).chat(
                    listOf(SystemMessage("S"), UserMessage("hi")),
                    listOf(FakeTool("a"))
                )
            }
            assertEquals("降级后仍拿到回答", "ok", reply.content)
            assertEquals("共发了两次（试探 + 降级）", 2, srv.bodies.size)
            assertTrue("第一次带断点", "cache_control" in srv.bodies[0])
            assertTrue("第二次不带断点", "cache_control" !in srv.bodies[1])
        } finally {
            srv.close()
        }
    }

    check("降级后不再重试试探：第三次请求直接不带标记") {
        val srv = FakeAnthropic(rejectCache = true)
        try {
            val client = claudeFor(srv.port)
            runBlocking {
                client.chat(listOf(SystemMessage("S"), UserMessage("hi")), listOf(FakeTool("a")))
                client.chat(listOf(SystemMessage("S"), UserMessage("hi")), listOf(FakeTool("a")))
            }
            assertEquals("第二次调用只发 1 个请求（累计 3 个）", 3, srv.bodies.size)
            assertTrue("第三次不带断点", "cache_control" !in srv.bodies[2])
        } finally {
            srv.close()
        }
    }

    check("降级后的 system 回到字符串形态（不是残废的数组）") {
        val srv = FakeAnthropic(rejectCache = true)
        try {
            runBlocking {
                claudeFor(srv.port).chat(
                    listOf(SystemMessage("SYS"), UserMessage("hi")),
                    listOf(FakeTool("a"))
                )
            }
            val body = Json.parseToJsonElement(srv.bodies[1]).jsonObject
            assertEquals(
                "system 是字符串",
                "SYS",
                body["system"]!!.jsonPrimitive.content
            )
        } finally {
            srv.close()
        }
    }

    println("\n== 用例 4：用量解析的缓存字段与口径 ==")

    check("Claude：cache_read_input_tokens 单列，不改 promptTokens 口径") {
        val json = Json.parseToJsonElement(
            """{"usage":{"input_tokens":100,"output_tokens":5,"cache_read_input_tokens":3000}}"""
        ).jsonObject
        val u = parseClaudeUsage(json)!!
        assertEquals("计费口径的输入保持原样", 100L, u.promptTokens)
        assertEquals("命中量单列", 3000L, u.cachedPromptTokens)
        assertEquals("输出", 5L, u.completionTokens)
        assertEquals("total 不含缓存（不重复计费）", 105L, u.totalTokens)
    }

    check("OpenAI：命中量藏在 prompt_tokens_details 里") {
        val json = Json.parseToJsonElement(
            """{"usage":{"prompt_tokens":1000,"completion_tokens":20,""" +
                """"prompt_tokens_details":{"cached_tokens":800}}}"""
        ).jsonObject
        val u = parseOpenAiUsage(json)!!
        assertEquals("prompt 原样", 1000L, u.promptTokens)
        assertEquals("命中量", 800L, u.cachedPromptTokens)
    }

    check("DeepSeek：命中量在 usage 顶层（prompt_cache_hit_tokens）") {
        val json = Json.parseToJsonElement(
            """{"usage":{"prompt_tokens":900,"completion_tokens":10,"prompt_cache_hit_tokens":600}}"""
        ).jsonObject
        val u = parseOpenAiUsage(json)!!
        assertEquals("命中量", 600L, u.cachedPromptTokens)
    }

    check("两家都没报缓存字段时记 0，不抛也不猜") {
        val json = Json.parseToJsonElement(
            """{"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
        ).jsonObject
        assertEquals("OpenAI 缺字段", 0L, parseOpenAiUsage(json)!!.cachedPromptTokens)
    }

    check("Gemini：cachedContentTokenCount") {
        val json = Json.parseToJsonElement(
            """{"usageMetadata":{"promptTokenCount":500,"candidatesTokenCount":30,""" +
                """"cachedContentTokenCount":400}}"""
        ).jsonObject
        val u = parseGeminiUsage(json)!!
        assertEquals("命中量", 400L, u.cachedPromptTokens)
        assertEquals("prompt 原样", 500L, u.promptTokens)
    }

    println("\n== 用例 5：汇总不丢缓存字段 ==")

    check("sum 累加 cachedPromptTokens（否则多步回合一算命中率就是 0）") {
        val total = TokenUsage.sum(
            listOf(
                TokenUsage(100, 5, 3000),
                TokenUsage(200, 8, 0),
                null
            )
        )
        assertEquals("prompt 累加", 300L, total.promptTokens)
        assertEquals("completion 累加", 13L, total.completionTokens)
        assertEquals("cached 累加", 3000L, total.cachedPromptTokens)
    }

    check("历史会话读回的旧数据（无缓存列）默认为 0，不影响汇总") {
        val legacy = TokenUsage(50, 5)
        assertEquals("默认 0", 0L, legacy.cachedPromptTokens)
        assertEquals("total 不变", 55L, legacy.totalTokens)
    }

    println("\n---------------------------------------------")
    if (failures.isEmpty()) {
        println("提示缓存：$passed 项通过 / 0 失败")
        return
    }
    println("提示缓存：$passed 项通过 / ${failures.size} 失败")
    failures.forEach { println("  - $it") }
    kotlin.system.exitProcess(1)
}
