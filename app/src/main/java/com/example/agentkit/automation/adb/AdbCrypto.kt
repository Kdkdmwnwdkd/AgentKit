package com.example.agentkit.automation.adb

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * adb 认证用 RSA 密钥（W-13 第一阶段，纯 JVM）。
 *
 * 流程（与 `adb` 客户端一致）：
 *  1. 生成 RSA-2048 密钥对，存 App 私有目录；
 *  2. 设备下发 `AUTH TOKEN`（20 字节随机数）；
 *  3. 用私钥做 **SHA1withRSA**（PKCS#1 v1.5）签名，回 `AUTH SIGNATURE`；
 *  4. 设备校验通过后进入 `CNXN`。未配对过的密钥会被拒（设备回新的 TOKEN），
 *     此时必须先走配对（第二阶段）。
 *
 * 为什么是 SHA1：adbd 用 `RSA_sign(NID_sha1, token, 20, ...)`，SHA1withRSA 与之逐字节一致。
 * SHA1 在这里只做「挑战-响应」的签名摘要，不用于存储或传输加密，不构成安全问题。
 */
object AdbCrypto {

    /** adb 只接受 RSA；2048 位是 adb 的默认与上限区间。 */
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val SIGN_ALGORITHM = "SHA1withRSA"

    /** 生成 adb 用密钥对（耗时约数十毫秒，放 IO 线程）。 */
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply { initialize(KEY_SIZE) }.generateKeyPair()

    /** 用私钥对设备下发的 token 签名（PKCS#1 v1.5 + SHA1，与 adbd 校验方式一致）。 */
    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray =
        Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(privateKey)
            update(token)
            sign()
        }

    /** 校验签名（真机不需要，供 harness 自证与排障）。 */
    fun verifyToken(publicKey: PublicKey, token: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance(SIGN_ALGORITHM).run {
                initVerify(publicKey)
                update(token)
                verify(signature)
            }
        }.getOrDefault(false)

    /**
     * 把公钥编码成 adb 的 `adbkey.pub` 格式：
     * base64(`uint32 len | uint32 n0inv | modulus[64] | rr[64] | uint32 exponent`)。
     *
     * 仅用于「未配对时把公钥发给设备」的兜底路径（AUTH RSAPUBLICKEY）与排查；
     * 正常配对流程由第二阶段完成，不依赖它。
     */
    fun encodePublicKey(publicKey: PublicKey): String {
        val rsa = publicKey as java.security.interfaces.RSAPublicKey
        val modulus = rsa.modulus.toLittleEndianFixed(256) // 2048 bit = 64 words
        val exponent = rsa.publicExponent.toLittleEndianFixed(4)
        val body = java.io.ByteArrayOutputStream()
        body.write(intLe(524)) // struct 长度（AOSP: 64*4*2 + 4*3）
        body.write(intLe(0))   // n0inv 占位（设备不校验此字段的客户端公钥）
        body.write(modulus)
        body.write(ByteArray(256)) // rr 占位
        body.write(exponent)
        val name = " agentkit@android\u0000".toByteArray(Charsets.UTF_8)
        body.write(name)
        return java.util.Base64.getEncoder().encodeToString(body.toByteArray())
    }

    private fun intLe(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** BigInteger → 固定长度小端字节（adb 的 RSAPublicKey 结构要求定长）。 */
    private fun java.math.BigInteger.toLittleEndianFixed(size: Int): ByteArray {
        val be = toByteArray() // 大端，可能带符号位前导 0
        val out = ByteArray(size)
        for (i in 0 until minOf(be.size, size)) {
            out[i] = be[be.size - 1 - i] // 最低有效字节在前
        }
        return out
    }
}
