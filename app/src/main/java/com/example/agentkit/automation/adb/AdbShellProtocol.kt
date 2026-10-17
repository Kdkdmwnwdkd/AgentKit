package com.example.agentkit.automation.adb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb `shell,v2` 子协议（W-13 第一阶段，纯 JVM）。
 *
 * 建立 shell 会话：`OPEN("shell,v2,raw:<命令>")`。之后设备用 `WRTE` 回三类帧：
 * ```
 * id(1) length(4, LE) data(length)
 *   1 = stdout
 *   2 = stderr
 *   3 = exit（data 为 4 字节小端退出码）
 * ```
 * 帧可能**跨 TCP 分片**到达，故用增量解析器 [ShellV2Parser]。
 *
 * 为什么用 v2 而不是 `raw`：v2 能分离 stdout/stderr 并拿到真实退出码；
 * raw 只能拿到合并输出、没有退出码，Agent 无法判断命令成功与否。
 */
sealed interface ShellEvent {
    data class Stdout(val text: String) : ShellEvent
    data class Stderr(val text: String) : ShellEvent
    data class Exit(val code: Int) : ShellEvent
}

/** OPEN 载荷：`shell,v2,raw:<命令>`（adbd 解析 `shell[,v2][,raw]:cmd`）。 */
fun shellOpenPayload(command: String): ByteArray =
    "shell,v2,raw:".toByteArray(Charsets.UTF_8) + command.toByteArray(Charsets.UTF_8)

class ShellV2Parser {
    private val buffer = ByteArrayOutputStream()

    /**
     * 喂入一段字节，返回本次能解析出的完整事件。
     * 不完整帧留在内部缓冲，等下一段；退出帧之后的多余字节忽略。
     */
    fun feed(chunk: ByteArray): List<ShellEvent> {
        buffer.write(chunk)
        val bytes = buffer.toByteArray()
        val out = ArrayList<ShellEvent>()
        var offset = 0
        while (offset < bytes.size) {
            // 至少需要 5 字节头
            if (bytes.size - offset < 5) break
            val id = bytes[offset].toInt() and 0xFF
            val len = ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0 || len > MAX_FRAME) break
            val frameEnd = offset + 5 + len
            if (frameEnd > bytes.size) break // 半包，等下一段
            val data = bytes.copyOfRange(offset + 5, frameEnd)
            when (id) {
                1 -> out += ShellEvent.Stdout(String(data, Charsets.UTF_8))
                2 -> out += ShellEvent.Stderr(String(data, Charsets.UTF_8))
                3 -> {
                    val code = if (data.size >= 4) {
                        ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    } else {
                        -1
                    }
                    out += ShellEvent.Exit(code)
                }
                else -> Unit // 未知流（如 stdin 回显）：忽略，不破坏解析
            }
            offset = frameEnd
        }
        // 保留未消费的尾部
        val rest = if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset, bytes.size)
        buffer.reset()
        buffer.write(rest)
        return out
    }

    companion object {
        /** 单帧上限 8MB：防御坏长度字段导致的无限等待。 */
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}
