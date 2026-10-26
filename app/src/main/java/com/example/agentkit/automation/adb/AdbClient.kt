package com.example.agentkit.automation.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair

/**
 * adb 客户端连接（W-13 阶段二 a：连接 / AUTH / shell 执行）。
 *
 * **W-51 起已启用**：作为「无线调试」通道的传输层，服务于 `AdbSession`
 * （配对见 [AdbPairingClient]，密码学核心见 [Spake2Ed25519]，通道抽象见
 * `automation/ShellChannel.kt`）。W-38 曾因「四家参照都走 Shizuku」而停用，
 * 但用户真实场景里「只开无线调试、不装 Shizuku」并不少见，故重新接线。
 *
 * 职责边界：**只做传输与认证**，不负责配对（配对是独立的 TLS+SPAKE2 流程，见配对层）。
 * 所以它适用于「本机或该设备已信任我们的公钥」的场景；未配对时设备会回新的 AUTH TOKEN，
 * 本类抛 [AdbAuthException]，由上层引导用户去配对。
 *
 * 握手序列（AOSP `adb/protocol.h`）：
 * ```
 *  → CNXN(version, maxPayload, "host::<name>\0")
 *  ← AUTH(TOKEN, token20)          // 未认证
 *  → AUTH(SIGNATURE, sign(token))
 *  ← CNXN(...)                     // 认证通过
 * ```
 * 若设备要求公钥（AUTH arg0=RSAPUBLICKEY），回公钥后仍需用户在设备上点「允许」。
 *
 * shell 会话：
 * ```
 *  → OPEN(localId, "shell,v2,raw:<cmd>")
 *  ← OKAY(localId, remoteId)
 *  ← WRTE(remoteId, localId, frames...)   // 可能多包
 *  ← CLSE(remoteId, localId)
 * ```
 */
class AdbAuthException(message: String) : IOException(message)

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val truncated: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0
}

class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyPair: KeyPair,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
    /** 输出上限（与 W-11 的上下文预算口径一致；超出截断并标记）。 */
    private val maxOutputChars: Int = 64_000
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    /** 建连并完成 AUTH 握手；失败抛异常且不留下半开连接。 */
    fun connect() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream()))
            output = BufferedOutputStream(s.getOutputStream())
            write(AdbPacket(AdbCommand.CNXN, ADB_VERSION, ADB_MAX_PAYLOAD, "host::agentkit\u0000".toByteArray()))
            handshake()
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw t
        }
    }

    private fun handshake() {
        var tokenRetries = 0
        while (true) {
            val p = readPacket() ?: throw IOException("adb 连接被对端关闭（握手阶段）")
            when (p.command) {
                AdbCommand.AUTH -> when (p.arg0) {
                    AdbAuth.TOKEN -> {
                        // 同一连接上最多回应 2 次签名：第 1 次机会给「已授权密钥」，
                        // 第 2 次给设备弹窗授权后的重试（USB 场景），再多就是密钥不被信任。
                        if (tokenRetries >= 2) {
                            throw AdbAuthException("设备拒绝本机密钥：请先在「无线调试 → 使用配对码配对设备」完成配对")
                        }
                        tokenRetries++
                        write(AdbPacket(AdbCommand.AUTH, AdbAuth.SIGNATURE, 0, AdbCrypto.signToken(keyPair.private, p.payload)))
                    }
                    AdbAuth.RSAPUBLICKEY -> {
                        // 设备要求公钥（通常出现在 USB 首次授权）：发送后等待用户点「允许」
                        val pub = AdbCrypto.encodePublicKey(keyPair.public).toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                        write(AdbPacket(AdbCommand.AUTH, AdbAuth.RSAPUBLICKEY, 0, pub))
                    }
                    else -> throw IOException("未知 AUTH 子类型 ${p.arg0}")
                }
                AdbCommand.CNXN -> return
                AdbCommand.CLSE -> throw AdbAuthException("设备在握手阶段关闭了连接（多为未配对或已撤销授权）")
                else -> throw IOException("握手阶段收到意外报文 ${p.command}")
            }
        }
    }

    /**
     * 执行一条 shell 命令（`shell,v2`，stdout/stderr 分流 + 真实退出码）。
     *
     * 同步阻塞；调用方应在 IO 线程执行。输出超过 [maxOutputChars] 截断（保留头部），
     * 与原 `truncateToolResult` 的「声明体量」语义一致。
     */
    fun exec(command: String, timeoutMs: Int = readTimeoutMs): ShellResult {
        socket.soTimeout = timeoutMs
        val localId = NEXT_ID.incrementAndGet()
        write(AdbPacket(AdbCommand.OPEN, localId, 0, shellOpenPayload(command)))

        val parser = ShellV2Parser()
        val out = StringBuilder()
        val err = StringBuilder()
        var exit = -1
        var truncated = false
        var remoteId = -1
        var opened = false

        fun append(sb: StringBuilder, text: String) {
            if (sb.length >= maxOutputChars) { truncated = true; return }
            val room = maxOutputChars - sb.length
            if (text.length <= room) sb.append(text) else { sb.append(text, 0, room); truncated = true }
        }

        while (true) {
            val p = readPacket() ?: break
            when (p.command) {
                AdbCommand.OKAY -> {
                    if (p.arg0 == localId) { remoteId = p.arg1; opened = true }
                }
                AdbCommand.WRTE -> {
                    if (!opened || p.arg0 != remoteId) continue
                    for (e in parser.feed(p.payload)) {
                        when (e) {
                            is ShellEvent.Stdout -> append(out, e.text)
                            is ShellEvent.Stderr -> append(err, e.text)
                            is ShellEvent.Exit -> exit = e.code
                        }
                    }
                }
                AdbCommand.CLSE -> {
                    if (p.arg0 == remoteId) {
                        // 回一个 CLSE 关闭本端（adb 的对等关闭语义）
                        runCatching { write(AdbPacket(AdbCommand.CLSE, localId, remoteId, ByteArray(0))) }
                        break
                    }
                }
                else -> Unit // 其他流（如设备主动 OPEN）本客户端不支持，忽略
            }
        }
        return ShellResult(out.toString(), err.toString(), exit, truncated)
    }

    // ---- 读写 ----

    private fun write(p: AdbPacket) {
        output.write(p.encode())
        output.flush()
    }

    /** 读一个完整报文；对端正常关闭 → null。 */
    private fun readPacket(): AdbPacket? {
        val header = ByteArray(AdbPacket.HEADER_SIZE)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) return if (read == 0) null else throw IOException("adb 报文头不完整")
            read += n
        }
        val len = AdbPacket.headerPayloadLength(header)
        if (len < 0) throw IOException("adb 报文头损坏（magic 不匹配）")
        val payload = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(payload, off, len - off)
            if (n < 0) throw IOException("adb 载荷不完整")
            off += n
        }
        return AdbPacket.decode(header, payload) ?: throw IOException("adb 报文校验失败（CRC）")
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** 本端流 id 递增（单连接内唯一）。 */
        private val NEXT_ID = java.util.concurrent.atomic.AtomicInteger(1)
    }
}
