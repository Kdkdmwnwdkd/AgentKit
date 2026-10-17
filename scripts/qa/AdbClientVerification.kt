#!/usr/bin/env kotlin
/**
 * W-13 阶段二 a harness：用**回环假 adbd** 验证 AdbClient 的完整握手与 shell 执行。
 *
 * 不需要真机：在 127.0.0.1 起一个 ServerSocket，按 AOSP 协议回应
 * CNXN → AUTH TOKEN → 验签 → CNXN → OPEN → OKAY/WRTE/CLSE。
 * 这样能把「报文分片、认证重试、stdout/stderr 分流、退出码、输出截断」全部跑通。
 *
 * 运行：bash scripts/qa/run-adb-client-verification.sh
 */

import com.example.agentkit.automation.adb.ADB_MAX_PAYLOAD
import com.example.agentkit.automation.adb.ADB_VERSION
import com.example.agentkit.automation.adb.AdbAuth
import com.example.agentkit.automation.adb.AdbAuthException
import com.example.agentkit.automation.adb.AdbClient
import com.example.agentkit.automation.adb.AdbCommand
import com.example.agentkit.automation.adb.AdbCrypto
import com.example.agentkit.automation.adb.AdbPacket
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import kotlin.concurrent.thread

/* ------------------------------ 断言 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name; println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

/* ------------------------------ 假 adbd ------------------------------ */

private fun writePacket(out: OutputStream, p: AdbPacket) {
    out.write(p.encode()); out.flush()
}

private fun readPacket(input: InputStream): AdbPacket? {
    val header = ByteArray(AdbPacket.HEADER_SIZE)
    var read = 0
    while (read < header.size) {
        val n = input.read(header, read, header.size - read)
        if (n < 0) return null
        read += n
    }
    val len = AdbPacket.headerPayloadLength(header)
    if (len < 0) return null
    val payload = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = input.read(payload, off, len - off)
        if (n < 0) return null
        off += n
    }
    return AdbPacket.decode(header, payload)
}

private fun frame(id: Int, data: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(id.toByte()); buf.putInt(data.size); buf.put(data)
    return buf.array()
}

private fun exitFrame(code: Int): ByteArray =
    frame(3, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array())

/**
 * 起一个假 adbd，返回端口；[onShell] 由用例决定如何回 shell 输出。
 * [expectKey] 为 null 时表示「任何签名都接受」（正常路径）；非 null 时验签。
 */
private fun startFakeAdbd(
    expectKey: KeyPair?,
    onShell: (command: String, out: OutputStream) -> Unit
): Pair<ServerSocket, Thread> {
    val server = ServerSocket(0)
    val serverKey = expectKey ?: AdbCrypto.generateKeyPair()
    val t = thread(isDaemon = true) {
        runCatching {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val cnxn = readPacket(input) ?: return@use
                if (cnxn.command != AdbCommand.CNXN) return@use
                val token = ByteArray(20) { (it * 3 + 1).toByte() }
                writePacket(out, AdbPacket(AdbCommand.AUTH, AdbAuth.TOKEN, 0, token))
                val sig = readPacket(input) ?: return@use
                if (sig.command != AdbCommand.AUTH || sig.arg0 != AdbAuth.SIGNATURE) return@use
                val ok = AdbCrypto.verifyToken(serverKey.public, token, sig.payload)
                if (!ok) {
                    // 再问一次 token，让客户端二次签名后放弃（模拟设备拒绝）
                    writePacket(out, AdbPacket(AdbCommand.AUTH, AdbAuth.TOKEN, 0, token))
                    readPacket(input) ?: return@use
                    writePacket(out, AdbPacket(AdbCommand.AUTH, AdbAuth.TOKEN, 0, token))
                    return@use
                }
                writePacket(out, AdbPacket(AdbCommand.CNXN, ADB_VERSION, ADB_MAX_PAYLOAD, "device::fake\u0000".toByteArray()))
                val open = readPacket(input) ?: return@use
                if (open.command != AdbCommand.OPEN) return@use
                val command = String(open.payload, Charsets.UTF_8).removePrefix("shell,v2,raw:")
                val localId = open.arg0
                val remoteId = 7
                writePacket(out, AdbPacket(AdbCommand.OKAY, localId, remoteId, ByteArray(0)))
                onShell(command, out)
                writePacket(out, AdbPacket(AdbCommand.CLSE, remoteId, localId, ByteArray(0)))
                readPacket(input) // 客户端回 CLSE（可无）
            }
        }
    }
    return server to t
}

private fun shellFrames(out: OutputStream, remoteId: Int, localId: Int, vararg frames: ByteArray) {
    for (f in frames) {
        writePacket(out, AdbPacket(AdbCommand.WRTE, remoteId, localId, f))
    }
}

/* ------------------------------ 用例 ------------------------------ */

private fun testHappyPath() {
    println("\n[A] 正常握手 + shell 执行")

    check("握手成功 + echo ok → stdout / exit 0") {
        val key = AdbCrypto.generateKeyPair()
        val (server, _) = startFakeAdbd(key) { _, out ->
            shellFrames(out, 7, 1, frame(1, "ok\n".toByteArray()), exitFrame(0))
        }
        try {
            AdbClient("127.0.0.1", server.localPort, key).use { c ->
                c.connect()
                val r = c.exec("echo ok")
                assertEquals("stdout", "ok\n", r.stdout)
                assertEquals("exit", 0, r.exitCode)
                assertTrue("ok", r.ok)
            }
        } finally { server.close() }
    }
}

private fun testStderrAndExitCode() {
    println("\n[B] stderr 分流 + 非零退出码")

    check("stderr 独立、退出码 1 → ok=false") {
        val key = AdbCrypto.generateKeyPair()
        val (server, _) = startFakeAdbd(key) { _, out ->
            shellFrames(out, 7, 1, frame(1, "partial\n".toByteArray()), frame(2, "denied\n".toByteArray()), exitFrame(1))
        }
        try {
            AdbClient("127.0.0.1", server.localPort, key).use { c ->
                c.connect()
                val r = c.exec("pm clear com.x")
                assertEquals("stdout", "partial\n", r.stdout)
                assertEquals("stderr", "denied\n", r.stderr)
                assertEquals("exit", 1, r.exitCode)
                assertTrue("not ok", !r.ok)
            }
        } finally { server.close() }
    }
}

private fun testAuthRejected() {
    println("\n[C] 设备拒绝密钥 → AdbAuthException（引导去配对，不假装成功）")

    check("密钥不被信任 → 明确失败（AdbAuthException / IO），绝不静默通过") {
        val clientKey = AdbCrypto.generateKeyPair()
        val serverKey = AdbCrypto.generateKeyPair() // 设备只认另一把钥匙
        val (server, _) = startFakeAdbd(serverKey) { _, _ -> }
        try {
            AdbClient("127.0.0.1", server.localPort, clientKey).use { c ->
                var authFailed = false
                try {
                    c.connect()
                } catch (e: AdbAuthException) {
                    authFailed = true
                } catch (e: IOException) {
                    // 服务端提前关闭也视为「不被信任」，但必须是失败而不是静默通过
                    authFailed = true
                }
                assertTrue("auth failed", authFailed)
            }
        } finally { server.close() }
    }
}

private fun testTruncation() {
    println("\n[D] 输出超限截断（与上下文预算口径一致）")

    check("输出 50k、上限 1k → 截到 1000 且标记 truncated") {
        val key = AdbCrypto.generateKeyPair()
        val big = "x".repeat(50_000)
        val (server, _) = startFakeAdbd(key) { _, out ->
            shellFrames(out, 7, 1, frame(1, big.toByteArray()), exitFrame(0))
        }
        try {
            AdbClient("127.0.0.1", server.localPort, key, maxOutputChars = 1_000).use { c ->
                c.connect()
                val r = c.exec("cat big")
                assertEquals("len", 1_000, r.stdout.length)
                assertTrue("truncated", r.truncated)
            }
        } finally { server.close() }
    }
}

private fun testSplitFrames() {
    println("\n[E] stdout 帧跨多个 WRTE 包（增量解析）")

    check("同一 stdout 帧拆到两个 WRTE → 拼回完整输出") {
        val key = AdbCrypto.generateKeyPair()
        val full = frame(1, "hello world".toByteArray())
        val (server, _) = startFakeAdbd(key) { _, out ->
            writePacket(out, AdbPacket(AdbCommand.WRTE, 7, 1, full.copyOfRange(0, 7)))
            writePacket(out, AdbPacket(AdbCommand.WRTE, 7, 1, full.copyOfRange(7, full.size) + exitFrame(0)))
        }
        try {
            AdbClient("127.0.0.1", server.localPort, key).use { c ->
                c.connect()
                val r = c.exec("echo hello")
                assertEquals("stdout", "hello world", r.stdout)
                assertEquals("exit", 0, r.exitCode)
            }
        } finally { server.close() }
    }
}

fun main() {
    println("=== adb 客户端 QA harness（W-13 阶段二 a，回环假 adbd） ===")
    testHappyPath()
    testStderrAndExitCode()
    testAuthRejected()
    testTruncation()
    testSplitFrames()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
