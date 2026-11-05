/**
 * 「输出为什么结束」的归一化验证（纯 JVM，不联网、不加载 Android）。
 *
 * 被测点是 [FinishReason]：三家字段名与取值都不同，统一成一套语义。
 *
 * 为什么值得单独一个 harness：这个字段此前**全仓库没有一处读取**，所以
 * 「回答被截断」在 App 里是完全不可见的。现在开始读它，读错的表现比不读更糟——
 * 把正常结束判成截断会让 UI 反复喊「被截断了」，把截断判成正常则等于没修。
 *
 * 断言分两组：
 *  - 语义组：三家各自的截断值都要归到同一个 `length`；
 *  - 鲁棒组：缺字段 / null / 空串 / 空数组 / 大小写混排，一律不崩且不误判。
 */
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.llm.FinishReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name: 期望 <$expected>，实际 <$actual>")
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private val json = Json { ignoreUnknownKeys = true }

private fun obj(text: String): JsonObject =
    json.parseToJsonElement(text) as JsonObject

fun main() {
    println("=== AgentKit FinishReason 验证（三家结束原因归一化）===")

    println("\n[1] OpenAI 兼容：choices[0].finish_reason")

    check("F-01 length → length（撞输出上限）") {
        assertEquals("openai-length", FinishReason.LENGTH, FinishReason.fromOpenAi(obj("""{"choices":[{"finish_reason":"length"}]}""")))
    }

    check("F-02 stop → stop（模型自己说完）") {
        assertEquals("openai-stop", FinishReason.STOP, FinishReason.fromOpenAi(obj("""{"choices":[{"finish_reason":"stop"}]}""")))
    }

    check("F-03 tool_calls 原样保留（不是结束，是等工具结果）") {
        // 把 tool_calls 也归一成 stop/length 会让「被截断」与「正常调工具」混为一谈
        assertEquals("openai-tool", "tool_calls", FinishReason.fromOpenAi(obj("""{"choices":[{"finish_reason":"tool_calls"}]}""")))
    }

    check("F-04 缺 finish_reason → null（未知，不伪造成 stop）") {
        assertEquals("openai-missing", null, FinishReason.fromOpenAi(obj("""{"choices":[{"delta":{"content":"hi"}}]}""")))
    }

    check("F-05 finish_reason 显式为 null → null 且不崩") {
        assertEquals("openai-null", null, FinishReason.fromOpenAi(obj("""{"choices":[{"finish_reason":null}]}""")))
    }

    check("F-06 choices 为 null → null 且不崩") {
        // 收尾 chunk 里 choices 可能是 null（部分中转会这么发）
        assertEquals("choices-null", null, FinishReason.fromOpenAi(obj("""{"choices":null}""")))
    }

    check("F-07 choices 为空数组 → null") {
        assertEquals("choices-empty", null, FinishReason.fromOpenAi(obj("""{"choices":[]}""")))
    }

    check("F-08 流末 usage-only chunk（无 choices）→ null") {
        assertEquals("usage-only", null, FinishReason.fromOpenAi(obj("""{"usage":{"prompt_tokens":1}}""")))
    }

    check("F-09 finish_reason 为空串 → null（不能被当成 stop）") {
        // 有兼容端点会下发 ""；当成 stop 就等于把「未知」洗成「正常」
        assertEquals("empty", null, FinishReason.fromOpenAi(obj("""{"choices":[{"finish_reason":""}]}""")))
    }

    println("\n[2] Claude：stop_reason")

    check("F-10 max_tokens → length（Claude 的截断说法）") {
        assertEquals("claude-max", FinishReason.LENGTH, FinishReason.fromClaude(obj("""{"stop_reason":"max_tokens"}""")))
    }

    check("F-11 end_turn → stop") {
        assertEquals("claude-end", FinishReason.STOP, FinishReason.fromClaude(obj("""{"stop_reason":"end_turn"}""")))
    }

    check("F-12 tool_use 原样保留") {
        assertEquals("claude-tool", "tool_use", FinishReason.fromClaude(obj("""{"stop_reason":"tool_use"}""")))
    }

    check("F-13 缺 stop_reason → null") {
        assertEquals("claude-missing", null, FinishReason.fromClaude(obj("""{"content":[]}""")))
    }

    println("\n[3] Gemini：candidates[0].finishReason")

    check("F-14 MAX_TOKENS → length") {
        assertEquals("gemini-max", FinishReason.LENGTH, FinishReason.fromGemini(obj("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")))
    }

    check("F-15 STOP → stop") {
        assertEquals("gemini-stop", FinishReason.STOP, FinishReason.fromGemini(obj("""{"candidates":[{"finishReason":"STOP"}]}""")))
    }

    check("F-16 SAFETY → safety（原样，不强行归到 stop/length）") {
        // 三家对「被安全策略拦下」的含义并不一致，合并会造假象
        assertEquals("gemini-safety", "safety", FinishReason.fromGemini(obj("""{"candidates":[{"finishReason":"SAFETY"}]}""")))
    }

    check("F-17 candidates 为空 → null 且不崩") {
        assertEquals("gemini-empty", null, FinishReason.fromGemini(obj("""{"candidates":[]}""")))
    }

    println("\n[4] 归一化边界：大小写与空白")

    check("F-18 'MAX_TOKENS' / ' max_tokens ' 大小写与空白都能认") {
        assertEquals("upper", FinishReason.LENGTH, FinishReason.normalize("MAX_TOKENS"))
        assertEquals("padded", FinishReason.LENGTH, FinishReason.normalize("  max_tokens  "))
    }

    check("F-19 'Stop' 混排 → stop") {
        assertEquals("mixed", FinishReason.STOP, FinishReason.normalize("Stop"))
    }

    check("F-20 未知取值原样小写返回（不吞、不猜）") {
        assertEquals("unknown", "hit_rate_limit", FinishReason.normalize("HIT_RATE_LIMIT"))
    }

    println("\n[5] isTruncated 双向判定")

    check("F-21 length 判为截断") {
        assertTrue("length", FinishReason.isTruncated(FinishReason.LENGTH))
    }

    check("F-22 stop / null / 空串 / 其他值都不算截断") {
        assertTrue("stop", !FinishReason.isTruncated(FinishReason.STOP))
        assertTrue("null", !FinishReason.isTruncated(null))
        assertTrue("empty", !FinishReason.isTruncated(""))
        assertTrue("safety", !FinishReason.isTruncated("safety"))
    }

    println("\n[6] 数据类接线")

    check("F-23 AssistantMessage 默认 finishReason 为 null（不破坏既有 21 个构造点）") {
        assertEquals("default", null, AssistantMessage("hi").finishReason)
    }

    check("F-24 AssistantMessage 能带上 length") {
        // 这条钉住「字段真的能传出去」，否则解析对了也白搭
        assertEquals("carried", FinishReason.LENGTH, AssistantMessage("hi", finishReason = FinishReason.LENGTH).finishReason)
    }

    println()
    if (failures.isEmpty()) {
        println("✅ FinishReason 全部通过（$passed 条）")
    } else {
        println("❌ FinishReason 失败 ${failures.size} 条：")
        failures.forEach { println("   - $it") }
        kotlin.system.exitProcess(1)
    }
}
