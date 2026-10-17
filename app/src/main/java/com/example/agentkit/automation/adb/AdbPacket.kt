package com.example.agentkit.automation.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * adb 传输层报文（W-13 第一阶段，纯 JVM，可脱离 Android 直测）。
 *
 * 线上格式（小端，24 字节头 + 载荷）：
 * ```
 * command(4) arg0(4) arg1(4) data_length(4) data_crc32(4) magic(4) payload(N)
 * magic = command xor 0xFFFFFFFF
 * ```
 * 与 AOSP `adb/protocol.h` 的 `amessage` 一致。CRC 用标准 CRC-32（adb 的 `adb_crc32`）。
 */
enum class AdbCommand(val code: Int) {
    CNXN(0x4E584E43),
    AUTH(0x48545541),
    OPEN(0x4E45504F),
    OKAY(0x59414B4F),
    CLSE(0x45534C43),
    WRTE(0x45545257),
    SYNC(0x434E5953),
    STLS(0x534C5453);

    companion object {
        fun from(code: Int): AdbCommand? = entries.firstOrNull { it.code == code }
    }
}

/** AUTH 子类型（`AUTH` 的 arg0）。 */
object AdbAuth {
    const val TOKEN = 1
    const val SIGNATURE = 2
    const val RSAPUBLICKEY = 3
}

/** CNXN 版本号（AOSP `A_VERSION`）。 */
const val ADB_VERSION = 0x01000001

/** 单包最大载荷（AOSP 默认 4096；我们用 256KB 提升大输出吞吐，adbd 会按自己的上限回包）。 */
const val ADB_MAX_PAYLOAD = 256 * 1024

data class AdbPacket(
    val command: AdbCommand,
    val arg0: Int = 0,
    val arg1: Int = 0,
    val payload: ByteArray = ByteArray(0)
) {
    /** 编码为线上字节（头 + 载荷）。 */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command.code)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(crc32(payload))
        buf.putInt(command.code.inv())
        buf.put(payload)
        return buf.array()
    }

    companion object {
        const val HEADER_SIZE = 24

        /**
         * 从 24 字节头 + 已读载荷还原报文；magic 不匹配 / 长度不符 → null。
         *
         * 调用方负责按 [headerPayloadLength] 读满载荷——本方法只做纯校验，不碰 IO。
         */
        fun decode(header: ByteArray, payload: ByteArray): AdbPacket? {
            if (header.size != HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdCode = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val len = buf.int
            val crc = buf.int
            val magic = buf.int
            if (magic != cmdCode.inv()) return null
            if (len < 0 || len != payload.size) return null
            if (crc != crc32(payload)) return null
            val cmd = AdbCommand.from(cmdCode) ?: return null
            return AdbPacket(cmd, arg0, arg1, payload)
        }

        /** 头里的载荷长度（未读载荷前先判断要读多少）。magic 不对 → -1。 */
        fun headerPayloadLength(header: ByteArray): Int {
            if (header.size != HEADER_SIZE) return -1
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmdCode = buf.getInt(0)
            val magic = buf.getInt(20)
            if (magic != cmdCode.inv()) return -1
            val len = buf.getInt(12)
            return if (len in 0..(16 * 1024 * 1024)) len else -1
        }

        /** 标准 CRC-32（空载荷为 0，与 adb 一致）。 */
        fun crc32(data: ByteArray): Int {
            if (data.isEmpty()) return 0
            val crc = CRC32()
            crc.update(data)
            return crc.value.toInt()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        ((command.hashCode() * 31 + arg0) * 31 + arg1) * 31 + payload.contentHashCode()
}
