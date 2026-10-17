package com.example.agentkit.automation.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * adb 无线配对的应用层帧（W-13 配对层，纯 JVM 部分）。
 *
 * 格式（大端，6 字节头 + 载荷）：
 * ```
 * version(1)=1 | type(1) | length(4)
 * type 0 = SPAKE2 消息；type 1 = 加密后的 PeerInfo
 * ```
 * 依据 AOSP `pairing_connection` 的 `PairingPacketHeader`（version=1，最大载荷 16KB）。
 */
object PairingPacket {
    const val VERSION = 1
    const val TYPE_SPAKE2 = 0
    const val TYPE_PEER_INFO = 1
    const val MAX_PAYLOAD = 16_384
    const val HEADER_SIZE = 6

    fun encode(type: Int, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD) { "payload size ${payload.size}" }
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(VERSION.toByte())
        buf.put(type.toByte())
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    fun write(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(encode(type, payload))
        out.flush()
    }

    /** 读一帧；版本/类型/长度不合法 → 抛异常（配对失败必须显式，不静默继续）。 */
    fun read(input: InputStream, expectedType: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        DataInputStream(input).readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        val type = buf.get().toInt() and 0xFF
        val len = buf.int
        if (version != VERSION) throw IllegalStateException("配对帧版本不符：$version")
        if (type != expectedType) throw IllegalStateException("配对帧类型不符：期望 $expectedType，实际 $type")
        if (len <= 0 || len > MAX_PAYLOAD) throw IllegalStateException("配对帧长度非法：$len")
        val payload = ByteArray(len)
        DataInputStream(input).readFully(payload)
        return payload
    }
}

/**
 * 配对时交换的 PeerInfo（AOSP `PeerInfo`：`uint8 type + data[8191]`，共 8192 字节）。
 *
 * type 0 = RSA 公钥。data 放 adb 公钥字符串（base64 + 空格 + 名称 + NUL），其余补零。
 * 双方各自加密发送，设备解密后把我们的公钥写入 adb 授权列表。
 */
object PeerInfo {
    const val SIZE = 8192
    const val TYPE_RSA = 0

    fun build(publicKeyBase64: String, name: String = "agentkit@android"): ByteArray {
        val keyBytes = "$publicKeyBase64 $name\u0000".toByteArray(Charsets.UTF_8)
        require(keyBytes.size < SIZE) { "公钥过长：${keyBytes.size}" }
        val out = ByteArray(SIZE)
        out[0] = TYPE_RSA.toByte()
        keyBytes.copyInto(out, 1)
        return out
    }

    /** 解析对端 PeerInfo；结构不合法 → null。 */
    fun parse(raw: ByteArray): String? {
        if (raw.size != SIZE || raw[0].toInt() != TYPE_RSA) return null
        var end = 1
        while (end < raw.size && raw[end] != 0.toByte()) end++
        if (end <= 1) return null
        return String(raw, 1, end - 1, Charsets.UTF_8)
    }
}

/**
 * 配对消息的对称加密：AES/GCM/NoPadding，12 字节零 IV，128 位 tag。
 *
 * 与 AOSP 一致：SPAKE2 导出的 32 字节密钥直接作 AES 密钥，IV 固定全零（每条连接只用一次，
 * 由 SPAKE2 保证密钥唯一）。GCM 自带完整性校验，解不开即密钥不对。
 */
object PairingCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val ZERO_IV = ByteArray(12)

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ZERO_IV))
            doFinal(plain)
        }

    fun decryptOrNull(key: ByteArray, cipherText: ByteArray): ByteArray? =
        runCatching {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ZERO_IV))
                doFinal(cipherText)
            }
        }.getOrNull()
}

/** SPAKE2 的实现接口：配对层只依赖它，算法实现可替换/单测。 */
interface Spake2 {
    /** 本端要发出的 32 字节消息。 */
    val message: ByteArray

    /** 处理对端消息，返回 32 字节共享密钥；对端口令不一致时应抛异常或返回不可用密钥。 */
    fun process(peerMessage: ByteArray): ByteArray
}
