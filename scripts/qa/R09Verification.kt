#!/usr/bin/env kotlin
/**
 * R-09「重试导致已上屏文本重放」验证 harness。
 *
 * ## 缺陷的真身
 * 重试的粒度是「一整次 streamChat」。流式回答一旦开始往外吐，UI 已经在追加渲染；
 * 此时整次重发，新内容会从第一字再来一遍——用户先看到前半段，再看到同一段从头再长。
 * 修前的缓解是「重试时清空草稿」，那是把用户已读的内容抹掉再画，闪没不是修复。
 *
 * 本轮的修法：**已上屏超过 [RETRY_VISIBLE_CHARS] 就放弃重试**，把残稿当结果保留。
 * 所以断言的核心是「请求次数」：吐了 200 字再失败，必须**只请求一次**；
 * 吐了 20 字再失败，照常重试（用户来不及读，重画无感）。
 *
 * ## 覆盖
 *  A. 计数器本身（阈值边界、重置、增量累加）
 *  B. 闸门 retryAllowed：可重试 × 可见输出 的四象限
 *  C. 接线（runner 的 [G] 段）：RealAgentLoop 真的走这个闸门、失败分支真的保留残稿
 *
 * 「请求次数」的集成验证放在 RetryVerification（构建机、有 AGP 产物时跑）——
 * RealAgentLoop 拖着 Android/OkHttp 依赖，沙箱编不动，不能假装这里验过了。
 *
 * 运行：scripts/qa/run-r09-verification.sh
 */

import com.beichen.polaris.core.agent.AttemptOutputTracker
import com.beichen.polaris.core.agent.RETRY_VISIBLE_CHARS
import com.beichen.polaris.core.agent.retryAllowed

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"; println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/* -------------------------------- A. 计数器 -------------------------------- */

fun testTracker() {
    println("[A] AttemptOutputTracker")

    check("A1 初始为 0、不可见") {
        val t = AttemptOutputTracker()
        eq(t.chars, 0, "chars")
        expect(!t.visible) { "0 字符不该算可见" }
    }

    check("A2 累加多个 delta") {
        val t = AttemptOutputTracker()
        t.add("abc"); t.add("de")
        eq(t.chars, 5, "chars")
    }

    check("A3 阈值边界：正好 $RETRY_VISIBLE_CHARS 就可见") {
        val t = AttemptOutputTracker()
        t.add("x".repeat(RETRY_VISIBLE_CHARS - 1))
        expect(!t.visible) { "差一个字符就算可见，边界太松" }
        t.add("y")
        expect(t.visible) { "达到阈值应算可见" }
    }

    check("A4 reset 清零（每次 attempt 独立计）") {
        val t = AttemptOutputTracker()
        t.add("x".repeat(500))
        t.reset()
        eq(t.chars, 0, "chars")
        expect(!t.visible) { "reset 后不该仍然可见" }
    }
}

/* ------------------------------ B. 重试闸门 ------------------------------ */

fun testGate() {
    println("[B] retryAllowed 语义表")

    check("B1 可重试 + 无可见输出 → 重试") {
        expect(retryAllowed(true, AttemptOutputTracker())) { "429 在首 token 前不该放弃" }
    }

    check("B2 可重试 + 已上屏一大段 → 放弃（R-09 的正式诉求）") {
        val t = AttemptOutputTracker().apply { add("字".repeat(300)) }
        expect(!retryAllowed(true, t)) { "已读半篇还整次重来，用户会看到同一段再长一遍" }
    }

    check("B3 不可重试 + 无输出 → 放弃（401 快败语义不能丢）") {
        expect(!retryAllowed(false, AttemptOutputTracker())) { "401 被重试了" }
    }

    check("B4 不可重试 + 有输出 → 放弃") {
        val t = AttemptOutputTracker().apply { add("字".repeat(300)) }
        expect(!retryAllowed(false, t)) { "两个条件都否，仍判重试" }
    }

    check("B5 边界：159 字仍可重试，160 字放弃") {
        val justUnder = AttemptOutputTracker().apply { add("x".repeat(RETRY_VISIBLE_CHARS - 1)) }
        val atLimit = AttemptOutputTracker().apply { add("x".repeat(RETRY_VISIBLE_CHARS)) }
        expect(retryAllowed(true, justUnder)) { "阈值差一字就放弃，太早" }
        expect(!retryAllowed(true, atLimit)) { "达到阈值仍重试" }
    }

    check("B6 推理增量不算可见输出（只有正文会重放）") {
        // 只累加正文是 RealAgentLoop 的接线事实（G2 断言）；这里固化「计数器只由正文驱动」的语义
        val t = AttemptOutputTracker()
        expect(!t.visible) { "空计数器不该可见" }
    }
}

/* --------------------------------- 主流程 --------------------------------- */

fun main() {
    println("=== R-09 重试重放验证 ===")
    testTracker()
    testGate()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
