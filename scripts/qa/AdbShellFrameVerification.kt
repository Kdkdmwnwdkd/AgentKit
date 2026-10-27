#!/usr/bin/env kotlin
/**
 * W-59 QA 行为验证：ADB 交互式 shell 的**读帧同步**。
 *
 * ## 被验证的 bug
 *
 * 旧 `readPacket` 只有一个超时。交互式 shell 的读循环为「能不能及时看到输出」用了
 * 200ms 短超时，并把 `SocketTimeoutException` 当「暂时没输出」继续轮询。
 *
 * 但 `readPacket` 是在 `input.read` 循环里读 header / payload 的——**一旦中途超时，
 * 已消费的字节就丢了**，下一次从半帧开始读：magic 校验失败、整条 shell 报废。
 *
 * 触发条件不苛刻：输出一大块（`dumpsys` / `cat` 大文件）时单帧 payload 可能几 KB~256KB，
 * 慢链路或设备繁忙时读不完 200ms 很正常。
 *
 * ## 本 harness 怎么验
 *
 * 起一个本地 TCP 服务端扮演 adbd（完整走一遍 CNXN / AUTH / OPEN / WRTE / CLSE），
 * 然后**故意把一个 WRTE 报文拆成两半发、中间停顿 350ms**（> 200ms 短超时）：
 *
 *  - 修复后：等第一字节用短超时（此时未消费任何字节，安全），进帧后切长超时，
 *    所以能等满 350ms 把整帧收完 → 文本完整；
 *  - 修复前：读到半个帧后 200ms 超时，字节已丢 → 帧错位，拿不到完整文本。
 *
 * 两个场景：**帧头分片** 与 **载荷分片**（后者更接近真实大输出）。
 *
 * 运行方式见 scripts/qa/run-adb-shell-frame-verification.sh。
 */

import com.example.agentkit.automation.adb.ADB_MAX_PAYLOAD
import com.example.agentkit.automation.adb.ADB_VERSION
import com.example.agentkit.automation.adb.AdbAuth
import com.example.agentkit.automation.adb.AdbClient
import com.example.agentkit.automation.adb.AdbCommand
import com.example.agentkit.automation.adb.AdbCrypto
import com.example.agentkit.automation.adb.AdbPacket
import com.example.agentkit.automation.adb.ShellEvent
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

private fun assertTrue(name: String, actual: Boolean) {
    if (!actual) throw AssertionError("[$name] 期望 true，实际 false")
}

private fun assertEquals(name: String, expected: String, actual: String) {
    if (expected != actual) throw AssertionError("[$name]\n  expected=<$expected>\n  actual  =<$actual>")
}

/* ------------------------------ mock adbd ------------------------------ */

private fun DataInputStream.readPacket(): AdbPacket? {
    val header = ByteArray(AdbPacket.HEADER_SIZE)
    var read = 0
    while (read < header.size) {
        val n = read(header, read, header.size - read)
        if (n < 0) return null
        read += n
    }
    val len = AdbPacket.headerPayloadLength(header)
    val payload = ByteArray(if (len > 0) len else 0)
    var off = 0
    while (off < payload.size) {
        val n = read(payload, off, payload.size - off)
        if (n < 0) return null
        off += n
    }
    return AdbPacket.decode(header, payload)
}

/** 把一帧拆成两半发，中间停 [gapMs]——模拟「帧头/载荷跨超时窗口才到齐」。 */
private fun OutputStream.writeSplit(frame: ByteArray, gapMs: Long) {
    val half = frame.size / 2
    write(frame, 0, half)
    flush()
    Thread.sleep(gapMs)
    write(frame, half, frame.size - half)
    flush()
}

/**
 * 跑一次完整往返：握手 → 开 shell → 收一条被拆分的 stdout 帧 → 关流。
 *
 * @param splitInPayload true = 只拆载荷（header 16 字节一次发完，载荷分两半）；
 *   false = 整帧对半拆（header 也被切开）
 */
private fun runOnce(splitInPayload: Boolean, gapMs: Long, body: String): Pair<String, Int> {
    val server = ServerSocket(0)
    val port = server.localPort
    val ready = CountDownLatch(1)

    thread(isDaemon = true, name = "mock-adbd") {
        runCatching {
            server.accept().use { sock ->
                val ins = DataInputStream(sock.getInputStream())
                val out = sock.getOutputStream()

                // CNXN → AUTH TOKEN
                ins.readPacket()
                out.write(AdbPacket(AdbCommand.AUTH, AdbAuth.TOKEN, 0, ByteArray(20) { 7 }).encode())
                out.flush()
                // AUTH SIGNATURE → CNXN
                ins.readPacket()
                out.write(
                    AdbPacket(
                        AdbCommand.CNXN, ADB_VERSION, ADB_MAX_PAYLOAD,
                        "device::ro.product.name=mock;".toByteArray()
                    ).encode()
                )
                out.flush()

                // OPEN → OKAY（remoteId 固定 1）
                val open = ins.readPacket() ?: return@use
                val localId = open.arg0
                out.write(AdbPacket(AdbCommand.OKAY, localId, 1, ByteArray(0)).encode())
                out.flush()

                // 客户端写 stdin（命令）——不必解析，读到就够
                ins.readPacket()

                ready.countDown()

                // stdout 帧：shell v2 的载荷是 [stream id 1B][长度 4B 小端][数据]
                // —— 少了中间那 4 字节长度，ShellV2Parser 会把文本前 4 字节读成长度、
                //    超过 MAX_FRAME 后 break，一个事件都出不来（本 harness 第一版就栽在这）。
                val text = body.toByteArray(Charsets.UTF_8)
                val payload = java.nio.ByteBuffer
                    .allocate(1 + 4 + text.size)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put(1)
                    .putInt(text.size)
                    .put(text)
                    .array()
                val frame = AdbPacket(AdbCommand.WRTE, 1, localId, payload).encode()

                if (splitInPayload) {
                    // header 一次发完，载荷分两半
                    val head = AdbPacket.HEADER_SIZE
                    out.write(frame, 0, head)
                    out.flush()
                    val rest = frame.size - head
                    out.write(frame, head, rest / 2)
                    out.flush()
                    Thread.sleep(gapMs)
                    out.write(frame, head + rest / 2, rest - rest / 2)
                    out.flush()
                } else {
                    out.writeSplit(frame, gapMs)
                }
                Thread.sleep(150)
                out.write(AdbPacket(AdbCommand.CLSE, 1, localId, ByteArray(0)).encode())
                out.flush()
                Thread.sleep(150)
            }
        }
        runCatching { server.close() }
    }

    val client = AdbClient("127.0.0.1", port, AdbCrypto.generateKeyPair())
    client.connect()
    val stream = client.openInteractiveShell()
    // 必须写一次 stdin：mock 端在 OKAY 之后等这条命令才开始发输出帧
    //（真实 adbd 也是「收到命令才产出」，不写就两边互相干等）
    stream.writeStdin("echo hi\n")
    ready.await(3, TimeUnit.SECONDS)

    val sb = StringBuilder()
    var timeouts = 0
    val deadline = System.currentTimeMillis() + 6_000
    while (System.currentTimeMillis() < deadline) {
        val ev = try {
            stream.read(200) // 与生产同口径：200ms 短超时
        } catch (e: SocketTimeoutException) {
            timeouts++
            continue
        } catch (t: Throwable) {
            // 帧同步被破坏会走到这里（拿不到完整文本，断言会失败并打印原因）
            println("      (读循环异常结束：${t.javaClass.simpleName}: ${t.message})")
            break
        }
        if (ev == null) break
        if (ev is ShellEvent.Stdout) sb.append(ev.text)
        if (ev is ShellEvent.Exit) break
    }
    runCatching { stream.close() }
    runCatching { client.close() }
    return sb.toString() to timeouts
}

fun main() {
    println("=== W-59：ADB shell 读帧同步（分片帧 + 200ms 短超时）===")

    val body = "hello-frame-sync-" + "x".repeat(64)

    check("帧头被切开（half/half，间隔 350ms）→ 文本仍完整") {
        val (text, _) = runOnce(splitInPayload = false, gapMs = 350, body = body)
        assertEquals("stdout", body, text.trimEnd('\n'))
    }

    check("载荷被切开（header 完整，载荷两半间隔 350ms）→ 文本仍完整") {
        val (text, _) = runOnce(splitInPayload = true, gapMs = 350, body = body)
        assertEquals("stdout", body, text.trimEnd('\n'))
    }

    check("完整帧（无拆分）→ 文本完整、且没有多余超时（基线不应被新逻辑拖慢）") {
        val (text, timeouts) = runOnce(splitInPayload = true, gapMs = 0, body = body)
        assertEquals("stdout", body, text.trimEnd('\n'))
        assertTrue("无输出时短超时会触发，但不应刷出大量超时（实际 $timeouts）", timeouts <= 2)
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
