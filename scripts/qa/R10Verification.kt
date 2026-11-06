/**
 * R10 验证：供应商级重试档位 + 错误归一化 + 上下文窗口收敛。
 *
 * 全部是纯函数 / 纯数据断言，不加载 Android 类、不联网。三个被测点分别对应
 * DeepSeek Harness 对照里的三个洞（见 docs 下的对照文档）：
 *
 *  - 洞 1：`providerRetry` 全局一套 → 改成按供应商档位；
 *  - 洞 2：只认 HTTP 状态码 → 三层归一化（状态码 / 结构化字段 / 关键词）；
 *  - 洞 3：全局 24k 预算 → 按模型窗口收敛。
 */
import com.beichen.polaris.core.agent.ContextBudget
import com.beichen.polaris.core.llm.ErrorNormalizer
import com.beichen.polaris.core.llm.ErrorNormalizer.FailureClass
import com.beichen.polaris.core.llm.ProviderCatalog
import com.beichen.polaris.core.llm.RetryProfile

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name: 期望 $expected，实际 $actual")
}

fun main() {
    println("=== AgentKit R10 验证（供应商重试档位与错误归一化）===")

    println("\n[R10] 错误归一化：第一层状态码")

    check("R10-1 401/403 归 AUTH，且不可重试") {
        for (code in listOf(401, 403)) {
            val v = ErrorNormalizer.classify(code, "")
            assertEquals("$code -> class", FailureClass.AUTH, v.cls)
            assertTrue("$code 不应重试", !ErrorNormalizer.isRetryableByDefault(v.cls))
        }
    }

    check("R10-2 429 归 RATE_LIMIT 且可重试") {
        val v = ErrorNormalizer.classify(429, """{"error":"slow down"}""")
        assertEquals("class", FailureClass.RATE_LIMIT, v.cls)
        assertTrue("应可重试", ErrorNormalizer.isRetryableByDefault(v.cls))
        assertTrue("状态码层不应标记 fromKeyword", !v.fromKeyword)
    }

    check("R10-3 402 归 QUOTA（不是 BAD_REQUEST）") {
        val v = ErrorNormalizer.classify(402, "")
        assertEquals("class", FailureClass.QUOTA, v.cls)
    }

    check("R10-4 5xx 归 SERVER 且可重试；504 归 NETWORK") {
        assertEquals("500", FailureClass.SERVER, ErrorNormalizer.classify(500, "").cls)
        assertEquals("503", FailureClass.SERVER, ErrorNormalizer.classify(503, "").cls)
        assertEquals("504", FailureClass.NETWORK, ErrorNormalizer.classify(504, "").cls)
        assertTrue("SERVER 应可重试", ErrorNormalizer.isRetryableByDefault(FailureClass.SERVER))
    }

    println("\n[R10] 错误归一化：第二层结构化字段")

    check("R10-5 400 + error.code=rate_limit_exceeded 归 RATE_LIMIT") {
        val body = """{"error":{"code":"rate_limit_exceeded","message":"too fast"}}"""
        val v = ErrorNormalizer.classify(400, body)
        assertEquals("class", FailureClass.RATE_LIMIT, v.cls)
        assertTrue("字段层不应标记 fromKeyword", !v.fromKeyword)
    }

    check("R10-6 400 + insufficient_quota 归 QUOTA") {
        val body = """{"error":{"type":"insufficient_quota"}}"""
        assertEquals("class", FailureClass.QUOTA, ErrorNormalizer.classify(400, body).cls)
    }

    println("\n[R10] 错误归一化：第三层关键词兜底")

    check("R10-7 400 + 纯文本 quota exceeded 归 QUOTA 且标记 fromKeyword") {
        val v = ErrorNormalizer.classify(400, """{"error":"quota exceeded"}""")
        assertEquals("class", FailureClass.QUOTA, v.cls)
        assertTrue("应标记 fromKeyword（调用方据此记 misclassified）", v.fromKeyword)
    }

    check("R10-8 400 + 中文「余额不足」也能认出来") {
        val v = ErrorNormalizer.classify(400, """{"error":"账户余额不足，请充值"}""")
        assertEquals("class", FailureClass.QUOTA, v.cls)
        assertTrue("应标记 fromKeyword", v.fromKeyword)
    }

    check("R10-9 不误伤：参数错误仍是 BAD_REQUEST 且不重试") {
        val v = ErrorNormalizer.classify(400, """{"error":"unknown model: gpt-5"}""")
        assertEquals("class", FailureClass.BAD_REQUEST, v.cls)
        assertTrue("不应标记 fromKeyword", !v.fromKeyword)
        assertTrue("BAD_REQUEST 不应重试", !ErrorNormalizer.isRetryableByDefault(v.cls))
    }

    check("R10-10 状态码优先于关键词：429 的 body 提到 billing 也仍是限流") {
        val v = ErrorNormalizer.classify(429, """{"error":"billing quota"}""")
        assertEquals("class", FailureClass.RATE_LIMIT, v.cls)
    }

    println("\n[R10] 重试档位")

    check("R10-11 官方档 3 次、聚合档 4 次、本地档 0 次") {
        assertEquals("official", 3, RetryProfile.OFFICIAL.maxRetries)
        assertEquals("aggregator", 4, RetryProfile.AGGREGATOR.maxRetries)
        assertEquals("local", 0, RetryProfile.LOCAL.maxRetries)
    }

    check("R10-12 只有聚合档允许额度类失败换上游重试") {
        assertTrue("聚合档应允许", RetryProfile.AGGREGATOR.allowQuotaRetry)
        assertTrue("官方档不应允许", !RetryProfile.OFFICIAL.allowQuotaRetry)
    }

    check("R10-13 目录按分组推导档位：聚合组→AGGREGATOR，本地→LOCAL，其余→OFFICIAL") {
        assertEquals("openrouter", 4, ProviderCatalog.retryOf("openrouter").maxRetries)
        assertEquals("siliconflow", 4, ProviderCatalog.retryOf("siliconflow").maxRetries)
        assertEquals("local-llama", 0, ProviderCatalog.retryOf("local-llama").maxRetries)
        assertEquals("deepseek", 3, ProviderCatalog.retryOf("deepseek").maxRetries)
    }

    check("R10-14 未收录的 id 兜底官方档（不是聚合档）") {
        val r = ProviderCatalog.retryOf("some-custom-endpoint")
        assertEquals("name", "official", r.name)
        assertEquals("maxRetries", 3, r.maxRetries)
    }

    check("R10-15 官方档不认 402，聚合档认") {
        assertTrue("官方档 402 不该重试", !RetryProfile.OFFICIAL.isRetryableCode(402))
        assertTrue("聚合档 402 该重试", RetryProfile.AGGREGATOR.isRetryableCode(402))
    }

    println("\n[R10] 上下文窗口收敛")

    check("R10-16 窗口未知（0）时预算不变——不能因为不知道就砍半") {
        assertEquals("unknown", 24_000, ContextBudget.budgetForProvider("deepseek", 24_000, 0))
    }

    check("R10-17 配置超出窗口一半时按窗口收敛") {
        // 用 64k 窗口举例（纯函数入参，与目录里的实际值无关）：预算调到 100k，只能用 32k
        assertEquals("capped", 32_000, ContextBudget.budgetForProvider("deepseek", 100_000, 64_000))
    }

    check("R10-18 配置小于窗口一半时尊重配置（绝不上调）") {
        assertEquals("user", 12_000, ContextBudget.budgetForProvider("deepseek", 12_000, 64_000))
    }

    check("R10-19 本地通道仍按 n_ctx 收敛，窗口参数不能把它放大") {
        val budget = ContextBudget.budgetForProvider("local-llama", 24_000, 1_000_000)
        assertEquals("local", ContextBudget.LOCAL_LLAMA_BUDGET_TOKENS, budget)
    }

    check("R10-20 目录里的窗口值只填了确定项，未确认的保持 0") {
        // DeepSeek 官方 Models & Pricing：V3.2 的 chat 与 reasoner 同为 128K。
        // 这条断言曾钉 64_000（V3/R1 时代口径），已随官方更新订正。
        assertEquals("deepseek", 128_000, ProviderCatalog.contextWindowOf("deepseek"))
        assertEquals("claude", 200_000, ProviderCatalog.contextWindowOf("claude"))
        // 「OpenAI 兼容」是万能兜底条目，可能对接任意端点，不能猜窗口
        assertEquals("openai 兜底不填", 0, ProviderCatalog.contextWindowOf("openai"))
        assertEquals("未收录", 0, ProviderCatalog.contextWindowOf("nope"))
    }

    check("R10-21 填了的窗口不得反过来压低默认预算（目录填小会静默砍预算）") {
        // 语义：预算 = min(configured, 窗口/2)，默认 configured=24_000。
        // 若某家窗口填成 32_000（/2=16k），默认预算会被悄悄砍到 16k——改的是目录，
        // 退化却发生在预算上，且没有任何日志。这条拦住那种填法。
        val offenders = ProviderCatalog.all
            .filter { it.contextWindow > 0 }
            .filter { it.contextWindow / 2 < ContextBudget.DEFAULT_BUDGET_TOKENS }
            .map { "${it.id}=${it.contextWindow}" }
        assertTrue(
            "窗口/2 低于默认预算的条目：$offenders",
            offenders.isEmpty()
        )
    }

    println("\n=== 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
