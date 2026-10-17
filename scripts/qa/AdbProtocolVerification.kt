#!/usr/bin/env kotlin
/**
 * W-13 第一阶段 harness：adb 协议层（纯 JVM，不需要 Android / 真机）。
 *
 * 覆盖：
 *  A. [AdbPacket]：24 字节头（小端）+ magic + CRC32 编解码往返、坏 magic 拒绝、
 *     headerPayloadLength（读载荷前判断长度）、空载荷 CRC=0；
 *  B. [AdbCrypto]：RSA-2048 生成、token 签名/验签、篡改拒绝、公钥 adb 格式可解析；
 *  C. [ShellV2Parser]：stdout/stderr/exit 分流、跨分片半包、多帧连读、未知流忽略、
 *     退出码小端解析。
 *
 * 运行：bash scripts/qa/run-adb-protocol-verification.sh
 */

import com.example.agentkit.automation.adb.ADB_VERSION
import com.example.agentkit.automation.adb.AdbAuth
import com.example.agentkit.automation.adb.AdbCommand
import com.example.agentkit.automation.adb.AdbCrypto
import com.example.agentkit.automation.adb.AdbPacket
import com.example.agentkit.automation.adb.ShellEvent
import com.example.agentkit.automation.adb.ShellV2Parser
import com.example.agentkit.automation.adb.shellOpenPayload
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

/* ------------------------------ A. 报文 ------------------------------ */

private fun testPacket() {
    println("\n[A] AdbPacket 编解码")

    check("CNXN 编码：24 字节头 + 载荷，magic = command 取反") {
        val p = AdbPacket(AdbCommand.CNXN, ADB_VERSION, 256 * 1024, "host::\u0000".toByteArray())
        val bytes = p.encode()
        assertEquals("总长", 24 + p.payload.size, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("command", AdbCommand.CNXN.code, buf.getInt(0))
        assertEquals("arg0", ADB_VERSION, buf.getInt(4))
        assertEquals("arg1", 256 * 1024, buf.getInt(8))
        assertEquals("len", p.payload.size, buf.getInt(12))
        assertEquals("crc", AdbPacket.crc32(p.payload), buf.getInt(16))
        assertEquals("magic", AdbCommand.CNXN.code.inv(), buf.getInt(20))
    }

    check("解码往返：CNXN / AUTH / WRTE 三种都相等") {
        val list = listOf(
            AdbPacket(AdbCommand.CNXN, ADB_VERSION, 4096, ByteArray(0)),
            AdbPacket(AdbCommand.AUTH, AdbAuth.TOKEN, 0, ByteArray(20) { it.toByte() }),
            AdbPacket(AdbCommand.WRTE, 1, 0, "echo ok\n".toByteArray())
        )
        for (p in list) {
            val bytes = p.encode()
            val header = bytes.copyOfRange(0, 24)
            val payload = bytes.copyOfRange(24, bytes.size)
            assertEquals("roundtrip ${p.command}", p, AdbPacket.decode(header, payload))
        }
    }

    check("坏 magic → 拒绝；坏 CRC → 拒绝；长度不符 → 拒绝") {
        val p = AdbPacket(AdbCommand.OPEN, 1, 0, "x".toByteArray())
        val bytes = p.encode()
        val badMagic = bytes.copyOf()
        badMagic[23] = (badMagic[23] + 1).toByte()
        assertEquals("bad magic", null, AdbPacket.decode(badMagic.copyOfRange(0, 24), badMagic.copyOfRange(24, badMagic.size)))
        val badCrc = bytes.copyOf()
        badCrc[16] = (badCrc[16] + 1).toByte()
        assertEquals("bad crc", null, AdbPacket.decode(badCrc.copyOfRange(0, 24), badCrc.copyOfRange(24, badCrc.size)))
        assertEquals("bad len", null, AdbPacket.decode(bytes.copyOfRange(0, 24), ByteArray(0)))
    }

    check("headerPayloadLength：读载荷前拿到正确长度；坏 magic → -1") {
        val p = AdbPacket(AdbCommand.WRTE, 0, 0, ByteArray(1234) { 7 })
        assertEquals("len", 1234, AdbPacket.headerPayloadLength(p.encode().copyOfRange(0, 24)))
        val bad = p.encode().copyOfRange(0, 24).apply { this[20] = 0 }
        assertEquals("bad", -1, AdbPacket.headerPayloadLength(bad))
    }

    check("CRC：空载荷 = 0；内容变化 → CRC 变化") {
        assertEquals("empty", 0, AdbPacket.crc32(ByteArray(0)))
        val a = AdbPacket.crc32("hello".toByteArray())
        val b = AdbPacket.crc32("hellp".toByteArray())
        assertTrue("differs", a != b)
        assertEquals("stable", a, AdbPacket.crc32("hello".toByteArray()))
    }
}

/* ------------------------------ B. 密钥 / 签名 ------------------------------ */

private fun testCrypto() {
    println("\n[B] AdbCrypto 密钥与签名")

    val kp = AdbCrypto.generateKeyPair()

    check("生成 RSA-2048 密钥对") {
        val pub = kp.public as java.security.interfaces.RSAPublicKey
        assertEquals("modulus bits", 2048, pub.modulus.bitLength())
    }

    check("token 签名可验签（SHA1withRSA，与 adbd 一致）") {
        val token = ByteArray(20) { (it * 7).toByte() }
        val sig = AdbCrypto.signToken(kp.private, token)
        assertTrue("verify", AdbCrypto.verifyToken(kp.public, token, sig))
    }

    check("篡改 token / 签名 → 验签失败") {
        val token = ByteArray(20) { it.toByte() }
        val sig = AdbCrypto.signToken(kp.private, token)
        val badToken = token.copyOf().apply { this[0] = (this[0] + 1).toByte() }
        assertTrue("bad token", !AdbCrypto.verifyToken(kp.public, badToken, sig))
        val badSig = sig.copyOf().apply { this[10] = (this[10] + 1).toByte() }
        assertTrue("bad sig", !AdbCrypto.verifyToken(kp.public, token, badSig))
    }

    check("公钥按 adb 格式编码：base64 可解、长度字段 524、指数 65537") {
        val encoded = AdbCrypto.encodePublicKey(kp.public)
        val raw = Base64.getDecoder().decode(encoded)
        assertTrue("struct len", raw.size >= 524)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("declared len", 524, buf.getInt(0))
        val exponent = ByteBuffer.wrap(raw, 8 + 256 + 256, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals("e", 65537, exponent)
    }
}

/* ------------------------------ C. shell v2 ------------------------------ */

private fun frame(id: Int, data: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(id.toByte())
    buf.putInt(data.size)
    buf.put(data)
    return buf.array()
}

private fun testShellV2() {
    println("\n[C] ShellV2Parser 增量解析")

    check("stdout + stderr + exit 一包三帧 → 三类事件齐全") {
        val payload = frame(1, "hello\n".toByteArray()) +
            frame(2, "warn\n".toByteArray()) +
            frame(3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        val events = ShellV2Parser().feed(payload)
        assertEquals("size", 3, events.size)
        assertEquals("out", "hello\n", (events[0] as ShellEvent.Stdout).text)
        assertEquals("err", "warn\n", (events[1] as ShellEvent.Stderr).text)
        assertEquals("exit", 0, (events[2] as ShellEvent.Exit).code)
    }

    check("退出码按小端解析（127 / 1 / 2）") {
        for (code in listOf(0, 1, 2, 127)) {
            val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array()
            val e = ShellV2Parser().feed(frame(3, data)).single() as ShellEvent.Exit
            assertEquals("code $code", code, e.code)
        }
    }

    check("半包：同一帧拆成 3 段喂入，最后一段才产出事件") {
        val full = frame(1, "abcdef".toByteArray())
        val p = ShellV2Parser()
        assertTrue("chunk1", p.feed(full.copyOfRange(0, 2)).isEmpty())
        assertTrue("chunk2", p.feed(full.copyOfRange(2, 6)).isEmpty())
        val events = p.feed(full.copyOfRange(6, full.size))
        assertEquals("size", 1, events.size)
        assertEquals("text", "abcdef", (events[0] as ShellEvent.Stdout).text)
    }

    check("多帧跨分片 + 空 stdout 帧 + 未知流 id 被忽略") {
        val payload = frame(1, ByteArray(0)) +
            frame(1, "a".toByteArray()) +
            frame(9, "unknown".toByteArray()) +
            frame(3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array())
        val p = ShellV2Parser()
        val events = ArrayList<ShellEvent>()
        var off = 0
        val step = 3
        while (off < payload.size) {
            events += p.feed(payload.copyOfRange(off, minOf(off + step, payload.size)))
            off += step
        }
        assertEquals("stdout count", 2, events.filterIsInstance<ShellEvent.Stdout>().size)
        assertEquals("text", "a", events.filterIsInstance<ShellEvent.Stdout>().last().text)
        assertEquals("exit", 3, events.filterIsInstance<ShellEvent.Exit>().single().code)
    }

    check("OPEN 载荷 = shell,v2,raw:<命令>（原样，不加 NUL）") {
        val cmd = "pm list packages | head -3"
        val payload = shellOpenPayload(cmd)
        assertEquals("text", "shell,v2,raw:$cmd", String(payload, Charsets.UTF_8))
    }
}

fun main() {
    println("=== adb 协议层 QA harness（W-13 阶段一） ===")
    testPacket()
    testCrypto()
    testShellV2()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
