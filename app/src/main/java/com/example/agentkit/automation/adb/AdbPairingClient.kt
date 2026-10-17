package com.example.agentkit.automation.adb

import android.net.ssl.SSLSockets
import android.os.Build
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * adb 无线配对客户端（W-13 配对层）。
 *
 * 协议（AOSP `pairing_connection`）：
 * ```
 * TCP → TLS（自签、双向不校验证书）
 *   → exportKeyingMaterial("adb-label\0", 64B)
 *   → SPAKE2 口令 = 6 位码 + 上述 64B
 *   → 帧(version=1,type=0) 交换 SPAKE2 消息 → 共享密钥
 *   → AES/GCM 加密交换 PeerInfo（我方 RSA 公钥）→ 设备写入授权列表
 * ```
 *
 * 为什么不用 TLS-PSK：AOSP 用的是「普通 TLS + 导出密钥材料」，Android 12+ 有公开 API
 * （[SSLSockets.exportKeyingMaterial]），不需要 BoringSSL 的 PSK 接口。
 *
 * SPAKE2 通过 [Spake2] 接口注入——算法实现独立演进/单测，配对流程不跟着变。
 */
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val keyPair: KeyPair,
    private val spake2Factory: (password: ByteArray) -> Spake2,
    private val timeoutMs: Int = 8_000
) {

    /** 执行一次完整配对；成功返回设备的 PeerInfo（仅用于日志），失败抛异常。 */
    fun pair(): String {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "无线配对需要 Android 12+（exportKeyingMaterial）"
        }
        val ssl = openTls()
        try {
            ssl.soTimeout = timeoutMs
            // context 用空数组：AOSP 传 null 表示无上下文；Kotlin 侧该参数为非空，
            // 空数组与 null 在 TLS exporter 中等价（无附加上下文）。
            val material = SSLSockets.exportKeyingMaterial(ssl, KEYING_LABEL, ByteArray(0), 64)
            val password = pairingCode.toByteArray(Charsets.UTF_8) + material
            val spake = spake2Factory(password)

            PairingPacket.write(ssl.outputStream, PairingPacket.TYPE_SPAKE2, spake.message)
            val peerMsg = PairingPacket.read(ssl.inputStream, PairingPacket.TYPE_SPAKE2)
            val key = spake.process(peerMsg)
            require(key.size == 32) { "SPAKE2 密钥长度异常：${key.size}" }

            val myInfo = PeerInfo.build(AdbCrypto.encodePublicKey(keyPair.public))
            PairingPacket.write(
                ssl.outputStream,
                PairingPacket.TYPE_PEER_INFO,
                PairingCrypto.encrypt(key, myInfo)
            )
            val reply = PairingPacket.read(ssl.inputStream, PairingPacket.TYPE_PEER_INFO)
            val plain = PairingCrypto.decryptOrNull(key, reply)
                ?: throw IllegalStateException("配对响应解密失败：配对码错误或协议不匹配")
            return PeerInfo.parse(plain) ?: throw IllegalStateException("配对响应不是合法 PeerInfo")
        } finally {
            runCatching { ssl.close() }
        }
    }

    /** 普通 TLS（不校验证书）：配对的身份认证由 SPAKE2 完成，TLS 只提供加密通道。 */
    private fun openTls(): SSLSocket {
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
        }
        val raw = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
        }
        return (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
            startHandshake()
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** AOSP 固定的导出标签（含结尾 NUL，不能省）。 */
        const val KEYING_LABEL = "adb-label\u0000"
    }
}
