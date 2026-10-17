import com.example.agentkit.core.llm.KeyPoolPolicy
import com.example.agentkit.core.llm.KeyPoolPolicy.KeySlot

/**
 * W-21 harness：多 Key 轮询与限流切换的**决策纯函数**（不加载 Android，不联网）。
 *
 * 口径：
 *  - 429/402/503 → 限流，值得换 Key；
 *  - 401/403 → 鉴权失败，**不换 Key**（换一把也是同样结果）；
 *  - 400 但 body 提额度（部分厂商这么回）→ 也值得换；
 *  - 冷却：优先 Retry-After（封顶 600s），否则指数退避 30→60→120→300；
 *  - 轮询选 Key 跳过冷却中的槽位，cursor 保证不总撞同一把；
 *  - 全冷却 → pickUsable 返回 null，且能给出最早恢复时间；
 *  - 摘要文案不含 Key 本体。
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

private val SLOTS = listOf(
    KeySlot("primary", "主 Key", "sk-primary-0001"),
    KeySlot("k1", "备用 1", "sk-backup-0002"),
    KeySlot("k2", "备用 2", "sk-backup-0003")
)

private const val T0 = 1_000_000_000_000L

fun main() {
    println("=== W-21 多 Key 轮询与限流切换 ===")

    // ---------- 失败判定 ----------
    check("429 → 限流，值得换 Key") {
        assertTrue("429 应判限流", KeyPoolPolicy.isRateLimited(429, ""))
        assertTrue("worthFailover", KeyPoolPolicy.worthFailover(429, ""))
    }

    check("402/503 → 也算额度/过载，值得换 Key") {
        assertTrue("402", KeyPoolPolicy.worthFailover(402, ""))
        assertTrue("503", KeyPoolPolicy.worthFailover(503, ""))
    }

    check("401/403 → 鉴权失败，不换 Key") {
        assertTrue("401 判鉴权", KeyPoolPolicy.isAuthFailure(401))
        assertTrue("401 不换", !KeyPoolPolicy.worthFailover(401, "invalid api key"))
        assertTrue("403 不换", !KeyPoolPolicy.worthFailover(403, "forbidden"))
    }

    check("400 但 body 提额度 → 值得换（部分厂商如此返回）") {
        assertTrue(
            "quota 词命中",
            KeyPoolPolicy.worthFailover(400, "You exceeded your current quota")
        )
        assertTrue(
            "rate limit 词命中",
            KeyPoolPolicy.worthFailover(400, "Rate limit reached for requests")
        )
    }

    check("400 普通参数错误 → 不换 Key") {
        assertTrue("不换", !KeyPoolPolicy.worthFailover(400, "invalid json body"))
    }

    check("500 服务器错误不提额度 → 不换 Key（换也白换）") {
        assertTrue("不换", !KeyPoolPolicy.worthFailover(500, "internal server error"))
    }

    // ---------- 冷却时长 ----------
    check("无 Retry-After：指数退避 30→60→120→300 秒") {
        assertTrue("第 1 次 30s", KeyPoolPolicy.cooldownMs(429, 0) == 30_000L)
        assertTrue("第 2 次 60s", KeyPoolPolicy.cooldownMs(429, 1) == 60_000L)
        assertTrue("第 3 次 120s", KeyPoolPolicy.cooldownMs(429, 2) == 120_000L)
        assertTrue("第 4 次 300s", KeyPoolPolicy.cooldownMs(429, 3) == 300_000L)
        assertTrue("再多也封顶 300s", KeyPoolPolicy.cooldownMs(429, 99) == 300_000L)
    }

    check("有 Retry-After：听服务端的（秒）") {
        assertTrue("45 秒", KeyPoolPolicy.cooldownMs(429, 0, 45) == 45_000L)
    }

    check("Retry-After 超长 → 封顶 600 秒，不让用户无限等") {
        assertTrue("封顶", KeyPoolPolicy.cooldownMs(429, 0, 3600) == 600_000L)
    }

    check("Retry-After 为 0/负数 → 回落指数退避") {
        assertTrue("0 回落", KeyPoolPolicy.cooldownMs(429, 0, 0) == 30_000L)
        assertTrue("负数回落", KeyPoolPolicy.cooldownMs(429, 0, -5) == 30_000L)
    }

    // ---------- 状态迁移 ----------
    check("afterFailure：失败计数 +1 且进入冷却") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "限流", T0)
        assertTrue("计数 1", st.failCount == 1)
        assertTrue("冷却到 T0+30s", st.cooldownUntilMs == T0 + 30_000L)
        assertTrue("T0 时冷却中", st.cooling(T0))
        assertTrue("T0+31s 已恢复", !st.cooling(T0 + 31_000L))
        assertTrue("剩余时间正确", st.remainingMs(T0 + 10_000L) == 20_000L)
    }

    check("连续失败：计数累加、冷却递增") {
        var st = KeyPoolPolicy.KeySlotState()
        st = KeyPoolPolicy.afterFailure(st, 429, "x", T0)
        st = KeyPoolPolicy.afterFailure(st, 429, "x", T0)
        assertTrue("计数 2", st.failCount == 2)
        assertTrue("第二次 60s", st.cooldownUntilMs == T0 + 60_000L)
    }

    check("afterSuccess：清零（连续成功不受历史影响）") {
        val dirty = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        val clean = KeyPoolPolicy.afterSuccess()
        assertTrue("清零计数", clean.failCount == 0)
        assertTrue("无冷却", !clean.cooling(T0))
        assertTrue("脏状态确实有冷却", dirty.cooling(T0))
    }

    check("remainingMs 不为负（超时后返回 0）") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        assertTrue("过期返回 0", st.remainingMs(T0 + 999_999L) == 0L)
    }

    // ---------- 选 Key ----------
    check("全可用时从 cursor 起轮转") {
        val idx0 = KeyPoolPolicy.pickUsable(SLOTS, emptyMap(), T0, 0)
        val idx1 = KeyPoolPolicy.pickUsable(SLOTS, emptyMap(), T0, 1)
        val idx2 = KeyPoolPolicy.pickUsable(SLOTS, emptyMap(), T0, 2)
        assertTrue("cursor0→0", idx0 == 0)
        assertTrue("cursor1→1", idx1 == 1)
        assertTrue("cursor2→2", idx2 == 2)
    }

    check("cursor 越界取模（负数/超过 size）") {
        assertTrue("cursor 3 → 0", KeyPoolPolicy.pickUsable(SLOTS, emptyMap(), T0, 3) == 0)
        assertTrue("cursor -1 → 2", KeyPoolPolicy.pickUsable(SLOTS, emptyMap(), T0, -1) == 2)
        assertTrue("cursor 100 → 1", KeyPoolPolicy.pickUsable(SLOTS, emptyMap(), T0, 100) == 1)
    }

    check("跳过冷却中的槽位") {
        val states = mapOf(
            "primary" to KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        )
        assertTrue("主 Key 冷却中→选 k1", KeyPoolPolicy.pickUsable(SLOTS, states, T0, 0) == 1)
    }

    check("主 Key + 备用 1 都冷却 → 选备用 2") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        val states = mapOf("primary" to st, "k1" to st)
        assertTrue("选 k2", KeyPoolPolicy.pickUsable(SLOTS, states, T0, 0) == 2)
    }

    check("全部冷却 → 返回 null（调用方据此报全部限流）") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        val states = SLOTS.associate { it.id to st }
        assertTrue("null", KeyPoolPolicy.pickUsable(SLOTS, states, T0, 0) == null)
    }

    check("空池 → null（不启用切换时不会走到这里）") {
        assertTrue("null", KeyPoolPolicy.pickUsable(emptyList(), emptyMap(), T0, 0) == null)
    }

    check("冷却过期后重新可选") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        val states = mapOf("primary" to st)
        val later = T0 + 31_000L
        assertTrue("恢复后选主 Key", KeyPoolPolicy.pickUsable(SLOTS, states, later, 0) == 0)
    }

    // ---------- 恢复时间与统计 ----------
    check("earliestRecoveryMs：取最早恢复的剩余时间") {
        val short = KeyPoolPolicy.KeySlotState(cooldownUntilMs = T0 + 10_000L)
        val long = KeyPoolPolicy.KeySlotState(cooldownUntilMs = T0 + 100_000L)
        val states = mapOf("primary" to long, "k1" to short)
        assertTrue("取 10 秒", KeyPoolPolicy.earliestRecoveryMs(SLOTS, states, T0) == 10_000L)
    }

    check("usableCount：数未冷却的槽位") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        val states = mapOf("primary" to st)
        assertTrue("2 把可用", KeyPoolPolicy.usableCount(SLOTS, states, T0) == 2)
        assertTrue("全冷却 0 把", KeyPoolPolicy.usableCount(SLOTS, SLOTS.associate { it.id to st }, T0) == 0)
    }

    check("summary 文案：可用/冷却/恢复时间，且不含 Key 本体") {
        val st = KeyPoolPolicy.afterFailure(KeyPoolPolicy.KeySlotState(), 429, "x", T0)
        val s = KeyPoolPolicy.summary(SLOTS, mapOf("primary" to st), T0)
        assertTrue("含可用数", s.contains("2/3"))
        assertTrue("含恢复秒数", s.contains("30"))
        assertTrue("不含 Key", !SLOTS.any { s.contains(it.key) })
    }

    check("summary 空池文案") {
        assertTrue("提示未配备用", KeyPoolPolicy.summary(emptyList(), emptyMap(), T0).contains("未配置"))
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
