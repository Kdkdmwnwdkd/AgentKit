/**
 * R11 验证：供应商身份在「配置 → 客户端 → 预算」这条链路上是否一路不丢。
 *
 * ## 为什么单独一份
 *
 * R10 建立了 `ProviderCatalog.contextWindow` 与 `ContextBudget.budgetForProvider`，
 * 也写了 21 条断言，全部通过。但那 21 条测的是**纯函数入参**：
 *
 *   `contextWindowOf("deepseek") == 128_000`   ✓
 *   `budgetForProvider("deepseek", 200_000, 128_000) == 64_000`   ✓
 *
 * 生产代码却从来不这样传——`RealAgentLoop.buildRequestMessages` 传的是
 * `llmProvider().id`，而 OpenAI 兼容客户端的 `id` 是**协议名** `"openai"`。
 * 目录里确实有一条 `id = "openai"`，但那是「OpenAI 兼容」**通用兜底条目**（窗口 0），
 * 不代表任何具体厂商。于是 `contextWindowOf("openai")` 恒返回 0，
 * 收敛分支从未进入——而且不会报「未知 id」，所以查不出异常。
 * **断言测对了组件，却没人测过接线。**
 *
 * 这份验证只测接线，不重复 R10 的纯函数断言。
 *
 * ## 判据为什么这样选
 *
 * 默认预算 24k 低于所有已填窗口的 `窗口/2`（64k / 64k / 64k / 100k），
 * 所以在默认配置下，**收敛与否的结果完全一样**——这也是这个 bug 活了这么久的原因。
 * 要让它现形，必须把 configured 抬到超过 `窗口/2`（本文件取 200_000）。
 *
 * 全部纯对象构造，不联网、不加载 Android 类。
 */
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.ProviderManager
import com.beichen.polaris.core.agent.ContextBudget
import com.beichen.polaris.core.llm.ProviderCatalog

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
    if (expected != actual) throw AssertionError("$name: 期望 $expected，实际 $actual")
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

/** 造一个不带 Key 的配置：构造客户端不需要网络，Key 只影响请求期。 */
private fun config(model: String = "test-model"): LLMConfig =
    LLMConfig(baseUrl = "https://example.invalid/v1", apiKey = "k-test", model = model)

fun main() {
    println("=== AgentKit R11 验证（供应商身份不丢）===")

    val pm = ProviderManager()

    // ── A. 接线：客户端必须记得自己来自哪家 ────────────────────────────────
    println("\n[A] create() 之后，客户端自报的身份")

    check("R11-1 deepseek 客户端自报 deepseek（不是协议名 openai）") {
        val c = pm.create("deepseek", config("deepseek-chat"))
        assertEquals("providerId", "deepseek", c.providerId)
    }

    check("R11-2 moonshot / zhipu 同样带得住（目录里填了窗口的三家）") {
        for (id in listOf("moonshot", "zhipu")) {
            val c = pm.create(id, config())
            assertEquals("$id -> providerId", id, c.providerId)
        }
    }

    check("R11-3 未收录的自定义端点原样保留 id，不得抹成 openai") {
        val c = pm.create("my-private-endpoint", config())
        assertEquals("providerId", "my-private-endpoint", c.providerId)
    }

    check("R11-4 对照：claude 自报 claude（改前即绿，用于证明断言有辨别力）") {
        val c = pm.create("claude", config("claude-sonnet-4-5"))
        assertEquals("providerId", "claude", c.providerId)
    }

    check("R11-5 create(local-llama) 会落到兼容客户端，但身份仍是 local-llama") {
        // 注意：ProviderManager 只注册了三种协议，没有注册 local-llama。
        // 真实的本地客户端由 ChatViewModel:1701 直接构造（wrapLocalLlm(LocalLlamaClient(...))），
        // 不经 create。这里测的是「create 对任意 name 都盖章」，不假设它返回本地实现。
        val c = pm.create("local-llama", config())
        assertEquals("providerId", "local-llama", c.providerId)
    }

    // ── B. 端到端：预算必须按「客户端自报的身份」去查窗口 ──────────────────
    println("\n[B] 端到端预算收敛（configured = 200k，超出各家窗口/2）")

    check("R11-6 deepseek：200k 预算必须收敛到 128k/2 = 64k") {
        val pid = pm.create("deepseek", config("deepseek-chat")).providerId
        val budget = ContextBudget.budgetForProvider(
            pid, 200_000, ProviderCatalog.contextWindowOf(pid)
        )
        assertEquals("budget", 64_000, budget)
    }

    check("R11-7 对照：claude：200k 预算收敛到 200k/2 = 100k（改前即绿）") {
        val pid = pm.create("claude", config()).providerId
        val budget = ContextBudget.budgetForProvider(
            pid, 200_000, ProviderCatalog.contextWindowOf(pid)
        )
        assertEquals("budget", 100_000, budget)
    }

    check("R11-8 不得修过头：默认 24k 预算下 deepseek 仍是 24k（窗口/2=64k 比它大）") {
        val pid = pm.create("deepseek", config()).providerId
        val budget = ContextBudget.budgetForProvider(
            pid, ContextBudget.DEFAULT_BUDGET_TOKENS, ProviderCatalog.contextWindowOf(pid)
        )
        assertEquals("budget", ContextBudget.DEFAULT_BUDGET_TOKENS, budget)
    }

    check("R11-9 接口默认实现：原生协议不覆盖 providerId 也取得到，本地预算特判仍生效") {
        // LocalLlamaClient 不上 override——依赖接口默认 `providerId get() = id`。
        // 用匿名实现验证这条默认值本身，避免为了测它去加载 JNI。
        val fakeLocal = object : LLMClient {
            override val id: String = "local-llama"
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<AgentTool>
            ): AssistantMessage = throw UnsupportedOperationException("仅用于断言接口默认值")
            override suspend fun testPing(): PingResult =
                throw UnsupportedOperationException("仅用于断言接口默认值")
        }
        assertEquals("providerId 默认取 id", "local-llama", fakeLocal.providerId)
        val budget = ContextBudget.budgetForProvider(
            fakeLocal.providerId,
            ContextBudget.DEFAULT_BUDGET_TOKENS,
            ProviderCatalog.contextWindowOf(fakeLocal.providerId)
        )
        assertEquals("budget", ContextBudget.LOCAL_LLAMA_BUDGET_TOKENS, budget)
    }

    // ── C. 反向：不许用「给 openai 编一个窗口」蒙混过关 ────────────────────
    println("\n[C] 反向约束")

    check("R11-10 「OpenAI 兼容」兜底条目的窗口必须保持 0，不得填值") {
        // 目录第 89 行那条 `id = STYLE_OPENAI / label = "OpenAI 兼容"` 是**通用兜底**，
        // 代表「任何 OpenAI 兼容端点」，不代表某一家、更不代表某个具体模型。
        // 它一旦被填上窗口（比如图省事写 128_000），28 家兼容厂商会共用这一个值——
        // 8k 窗口的小模型会被按 128k 预算喂爆，且没有任何日志。
        //
        // 注意这条**不是**在说「目录里不许有 openai」：那条目本来就该在，
        // 用户选「OpenAI 兼容」时 providerId 就是 "openai"，此时查不到窗口是正确行为
        // （不知道是哪家，就不该猜）。要防的是给它编一个窗口。
        val fallback = ProviderCatalog.byId("openai")
        assertTrue("兜底条目应存在（用户要能选「OpenAI 兼容」）", fallback != null)
        assertEquals("兜底条目窗口", 0, fallback!!.contextWindow)
    }

    check("R11-11 create() 必须把身份写进 config，不能留空串给下游") {
        val c = pm.create("deepseek", config())
        assertTrue("providerId 不应为空", c.providerId.isNotBlank())
        assertTrue("基类默认实现不得退化成空", c.providerId != "")
    }

    // ── D. 不变性：id 的语义不许被顺手改掉 ────────────────────────────────
    println("\n[D] 既有契约不得破坏")

    check("R11-12 OpenAI 兼容客户端的 id 仍是协议名 openai") {
        val c = pm.create("deepseek", config())
        assertEquals("id（协议名）", "openai", c.id)
        assertTrue(
            "id 是协议名，providerId 才是厂商——两者必须不同，否则 7 处按 id 分档的既有逻辑会跟着漂",
            c.id != c.providerId
        )
    }

    check("R11-13 claude 的 id 与 providerId 相同（原生协议的目录 id 就是协议名）") {
        val c = pm.create("claude", config())
        assertEquals("id", "claude", c.id)
        assertEquals("providerId", "claude", c.providerId)
    }

    println("\n=== 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
