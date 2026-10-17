#!/usr/bin/env kotlin
/**
 * W-13 配对层（纯 JVM 部分）harness：
 *  - PairingPacket：6 字节头（大端）+ 版本/类型/长度校验；
 *  - PeerInfo：8192 字节结构（type + 公钥串 + 补零）构造/解析；
 *  - PairingCrypto：AES/GCM 加解密、错密钥失败；
 *  - 端到端：两个 Spake2 桩（同口令 → 同密钥）交换加密 PeerInfo。
 *
 * TLS 与 SPAKE2 真算法不在此层（前者需 Android SSLSocket，后者单独立项测试）。
 * 运行：bash scripts/qa/run-adb-pairing-verification.sh
 */

import com.example.agentkit.automation.adb.PairingCrypto
import com.example.agentkit.automation.adb.PairingPacket
import com.example.agentkit.automation.adb.PeerInfo
import com.example.agentkit.automation.adb.Spake2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try { block(); passed++; println("  PASS  $name") }
    catch (t: Throwable) { failures += name; println("  FAIL  $name -> ${t.message}") }
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

/** 仅用于测试流程的假 SPAKE2：同口令双方导出同密钥，不同口令导出不同密钥。 */
private class FakeSpake2(private val password: ByteArray) : Spake2 {
    override val message: ByteArray = sha256("msg".toByteArray() + password)
    override fun process(peerMessage: ByteArray): ByteArray = sha256(password + peerMessage)
}

private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

fun main() {
    println("=== adb 配对层 QA harness（帧 / PeerInfo / AES-GCM / 端到端） ===")

    println("\n[A] PairingPacket")
    check("SPAKE2 帧编码往返：version=1、type=0、长度正确") {
        val payload = ByteArray(32) { it.toByte() }
        val bytes = PairingPacket.encode(PairingPacket.TYPE_SPAKE2, payload)
        assertEquals("总长", 6 + 32, bytes.size)
        assertEquals("version", 1, bytes[0].toInt())
        assertEquals("type", 0, bytes[1].toInt())
        val back = PairingPacket.read(ByteArrayInputStream(bytes), PairingPacket.TYPE_SPAKE2)
        assertTrue("payload equal", back.contentEquals(payload))
    }
    check("类型不符 / 版本不符 / 长度非法 → 明确抛错") {
        val good = PairingPacket.encode(PairingPacket.TYPE_PEER_INFO, ByteArray(10))
        var e1 = false; var e2 = false; var e3 = false
        try { PairingPacket.read(ByteArrayInputStream(good), PairingPacket.TYPE_SPAKE2) } catch (e: Exception) { e1 = true }
        val badVer = good.copyOf().apply { this[0] = 2 }
        try { PairingPacket.read(ByteArrayInputStream(badVer), PairingPacket.TYPE_PEER_INFO) } catch (e: Exception) { e2 = true }
        val badLen = good.copyOf().apply { this[5] = 0; this[4] = 0; this[3] = 0; this[2] = 0 }
        try { PairingPacket.read(ByteArrayInputStream(badLen), PairingPacket.TYPE_PEER_INFO) } catch (e: Exception) { e3 = true }
        assertTrue("type", e1); assertTrue("version", e2); assertTrue("len", e3)
    }

    println("\n[B] PeerInfo")
    check("构造 8192 字节：type=0，公钥串可原样解析") {
        val info = PeerInfo.build("AAABBBBCCCC", "agentkit@android")
        assertEquals("size", 8192, info.size)
        assertEquals("type", 0, info[0].toInt())
        assertEquals("parse", "AAABBBBCCCC agentkit@android", PeerInfo.parse(info))
    }
    check("解析拒绝：长度不对 / type 不对 / 空 data") {
        assertEquals("size", null, PeerInfo.parse(ByteArray(100)))
        assertEquals("type", null, PeerInfo.parse(ByteArray(8192).apply { this[0] = 1 }))
        assertEquals("empty", null, PeerInfo.parse(ByteArray(8192)))
    }

    println("\n[C] PairingCrypto（AES/GCM）")
    check("加解密往返；错密钥 → null（GCM 完整性校验）") {
        val key = ByteArray(32) { it.toByte() }
        val plain = ByteArray(8192) { (it % 251).toByte() }
        val enc = PairingCrypto.encrypt(key, plain)
        assertTrue("ciphertext longer (tag)", enc.size > plain.size)
        val dec = PairingCrypto.decryptOrNull(key, enc)
        assertTrue("roundtrip", dec != null && dec.contentEquals(plain))
        val wrong = ByteArray(32) { (it + 1).toByte() }
        assertEquals("wrong key", null, PairingCrypto.decryptOrNull(wrong, enc))
    }

    println("\n[D] 端到端（同口令 → 同密钥 → 解密 PeerInfo）")
    check("双方同口令：SPAKE2 密钥一致，PeerInfo 可解密并解析") {
        val code = "123456"
        val material = ByteArray(64) { (it * 5).toByte() }
        val password = code.toByteArray() + material
        val alice = FakeSpake2(password)
        val bob = FakeSpake2(password)
        val aliceKey = alice.process(bob.message)
        val bobKey = bob.process(alice.message)
        assertTrue("same key", aliceKey.contentEquals(bobKey))
        val info = PeerInfo.build("PUBKEY", "agentkit@android")
        val dec = PairingCrypto.decryptOrNull(bobKey, PairingCrypto.encrypt(aliceKey, info))
        assertEquals("peer", "PUBKEY agentkit@android", PeerInfo.parse(dec!!))
    }
    check("口令不一致 → 密钥不同 → 解密失败（配对码错误必失败）") {
        val a = FakeSpake2("123456".toByteArray() + ByteArray(64))
        val b = FakeSpake2("654321".toByteArray() + ByteArray(64))
        val ka = a.process(b.message)
        val kb = b.process(a.message)
        assertTrue("different", !ka.contentEquals(kb))
        val info = PeerInfo.build("PUBKEY")
        assertEquals("cannot decrypt", null, PairingCrypto.decryptOrNull(kb, PairingCrypto.encrypt(ka, info)))
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
