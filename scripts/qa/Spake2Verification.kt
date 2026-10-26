#!/usr/bin/env kotlin
/**
 * W-51 QA 行为验证 harness：adb 无线配对的 SPAKE2（[com.example.agentkit.automation.adb.Spake2Ed25519]）。
 *
 * 背景：`automation/adb` 这套协议实现从 W-38 起被停用（零引用）。W-51 要把它接成
 * 「操作手机界面」的第三条候选，第一步必须补上**唯一缺失的那块**——SPAKE2 只有接口没有实现，
 * 而它是配对的密码学核心：写错了不是报错，而是「配对码正确却提示解密失败」，且无法从日志
 * 分辨是口令错、常量错还是 transcript 顺序错。
 *
 * 本 harness 只测**纯函数**（零 Android 依赖，JVM 直跑），分三层：
 *
 *  A. 群运算：基点编码 roundtrip、L·B = 单位元、M/N 的阶为 8L（带 8-torsion）——
 *     最后一条解释了「为什么私钥与口令标量都要 ×8」，是理解代码的前提；
 *  B. 向量对拍：固定私钥 + 固定口令，`message` 与最终密钥必须与一份独立实现（Python，
 *     同一份 spake25519.cc 流程）逐字节一致。**这是唯一能证明「不是自己跟自己一致」的手段**；
 *  C. 协议行为：双方密钥一致、错口令必然不同、非法点必须抛异常、名字常量含结尾 NUL。
 *
 * 运行方式见 scripts/qa/run-spake2-verification.sh。
 */

import com.example.agentkit.automation.adb.Ed25519
import com.example.agentkit.automation.adb.Spake2Ed25519
import java.math.BigInteger

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

private fun bytes(n: Int, offset: Int = 0): ByteArray = ByteArray(n) { (it + offset).toByte() }

/* ------------------------------ 参考向量 ------------------------------ */
/*
 * 由 /tmp/spake_ref.py 产出（Python + spake2.ed25519_basic 的群运算，独立实现同一份
 * spake25519.cc 流程）。任何一项变化都说明本实现偏离了 AOSP。
 */
private val PW = "123456".toByteArray() + bytes(64)                      // 6 位码 + 64B TLS 材料
private val SEED_A = bytes(64, 0)                                        // alice 私钥种子
private val SEED_B = bytes(64, 64)                                       // bob 私钥种子

private const val EXPECT_ALICE_MSG = "001c2afa55b8c2de1d12a38958367cb5c27e95411595e7bbeb37ca5f6a2375df"
private const val EXPECT_BOB_MSG = "229166379209e3209eaa352ccd1736af9049c560686d7d47467905f3127e7c98"
private const val EXPECT_KEY = "9cb6d1ad4d53a88d85d5e764ac6ca14348e53f15ce0fb36e6f70c6cfdcc8f2c4"

fun main() {
    println("=== SPAKE2（adb 配对）验证 ===")

    /* -------------------- A. 群运算 -------------------- */
    println("\n[A] 群运算")

    check("基点压缩编码 = 标准 Ed25519 基点（58 66 … 66）") {
        assertEquals("base", "58" + "66".repeat(31), hex(Ed25519.encode(Ed25519.B)))
    }

    check("编解码互逆：encode(decode(x)) == x") {
        val enc = Ed25519.encode(Ed25519.B)
        val back = Ed25519.decode(enc) ?: throw AssertionError("解码失败")
        assertEquals("roundtrip", hex(enc), hex(Ed25519.encode(back)))
    }

    check("L·B = 单位元（基点是素数阶子群生成元）") {
        val r = Ed25519.mul(Ed25519.B, Ed25519.L)
        val enc = Ed25519.encode(r)
        // 单位元压缩编码：y = 1，x = 0 → 01 00 00 ...
        assertEquals("identity", "01" + "00".repeat(31), hex(enc))
    }

    check("2·B = double(B)（加法与倍点公式自洽）") {
        // ByteArray 的 == 是引用比较，必须转 hex 再断言
        val a = hex(Ed25519.encode(Ed25519.mul(Ed25519.B, BigInteger.TWO)))
        val b = hex(Ed25519.encode(Ed25519.double(Ed25519.B)))
        assertEquals("2B", a, b)
    }

    check("M/N 的阶为 8L 而非 L（带 8-torsion —— 这就是私钥要 ×8 的原因）") {
        // 从 Spake2 的常量编码解出点。这里用反射无关的方式：M/N 是 private，借
        // encode/decode 的公开能力间接验证不现实，因此沿用同一份编码字符串。
        val mEnc = Ed25519.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        val nEnc = Ed25519.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
        val identity = "01" + "00".repeat(31)
        for ((name, enc) in listOf("M" to mEnc, "N" to nEnc)) {
            val p = Ed25519.decode(enc) ?: throw AssertionError("$name 解码失败")
            assertEquals("$name·8L", identity, hex(Ed25519.encode(Ed25519.mul(p, Ed25519.L.multiply(BigInteger.valueOf(8))))))
            assertTrue("$name·L ≠ 单位元（确认含 torsion）", hex(Ed25519.encode(Ed25519.mul(p, Ed25519.L))) != identity)
        }
    }

    check("非法点（非二次剩余）解码返回 null，不静默通过") {
        // y = 2 时 x² = (y²−1)/(dy²+1) 不是二次剩余（协议实现必须拒绝，否则会接受攻击者构造的点）
        val bad = ByteArray(32).also { it[0] = 2 }
        assertTrue("null", Ed25519.decode(bad) == null)
    }

    /* -------------------- B. 向量对拍 -------------------- */
    println("\n[B] 与独立实现对拍（固定私钥 + 固定口令）")

    val alice = Spake2Ed25519(PW, isClient = true, privateKeySeed = SEED_A)
    val bob = Spake2Ed25519(PW, isClient = false, privateKeySeed = SEED_B)

    check("alice 消息逐字节等于参考实现") {
        assertEquals("alice msg", EXPECT_ALICE_MSG, hex(alice.message))
    }

    check("bob 消息逐字节等于参考实现") {
        assertEquals("bob msg", EXPECT_BOB_MSG, hex(bob.message))
    }

    check("会话密钥逐字节等于参考实现") {
        assertEquals("key", EXPECT_KEY, hex(alice.process(bob.message)))
    }

    /* -------------------- C. 协议行为 -------------------- */
    println("\n[C] 协议行为")

    check("双方独立计算得到同一密钥") {
        val a = Spake2Ed25519(PW, isClient = true)
        val b = Spake2Ed25519(PW, isClient = false)
        assertEquals("same key", hex(a.process(b.message)), hex(b.process(a.message)))
    }

    check("随机种子重复 5 组均一致（私钥生成无状态残留）") {
        repeat(5) { i ->
            val a = Spake2Ed25519(PW, isClient = true)
            val b = Spake2Ed25519(PW, isClient = false)
            assertEquals("round $i", hex(a.process(b.message)), hex(b.process(a.message)))
        }
    }

    check("口令错一位 → 密钥必然不同（不会静默配成）") {
        val right = Spake2Ed25519(PW, isClient = true)
        val wrongPw = "123457".toByteArray() + bytes(64)
        val wrong = Spake2Ed25519(wrongPw, isClient = false)
        assertTrue("不同", hex(right.process(wrong.message)) != hex(wrong.process(right.message)))
    }

    check("对端消息长度非法 → 抛异常（不返回垃圾密钥）") {
        val a = Spake2Ed25519(PW, isClient = true)
        var thrown = false
        try {
            a.process(ByteArray(31))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("抛 IllegalArgumentException", thrown)
    }

    check("名字常量含结尾 NUL、各 16 字节（AOSP 用 sizeof 传长度）") {
        assertEquals("client len", 16, Spake2Ed25519.CLIENT_NAME.size)
        assertEquals("server len", 16, Spake2Ed25519.SERVER_NAME.size)
        assertEquals("client nul", 0, Spake2Ed25519.CLIENT_NAME.last().toInt())
        assertEquals("server nul", 0, Spake2Ed25519.SERVER_NAME.last().toInt())
        assertEquals("client text", "adb pair client", String(Spake2Ed25519.CLIENT_NAME, 0, 15))
        assertEquals("server text", "adb pair server", String(Spake2Ed25519.SERVER_NAME, 0, 15))
    }

    check("消息是 32 字节、且是曲线上合法点（对端能解码）") {
        val a = Spake2Ed25519(PW, isClient = true)
        assertEquals("len", 32, a.message.size)
        assertTrue("可解码", Ed25519.decode(a.message) != null)
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
