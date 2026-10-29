package com.example.agentkit.core.qr

import java.util.Base64

/**
 * W-RT8：配置二维码负载——导出侧打包、导入侧解析的纯函数层。
 *
 * 格式：`agentkit://config?v=1&d=<base64url>`，base64 内容是固定键序的
 * `k=v` 行（UTF-8）。选 base64 而不是裸文本：扫码 App 展示长串时用户
 * 可能复制粘贴转发，定界符在内容里出现（模型名带竖线之类）不会拆散结构。
 *
 * 纪律：**负载只装非敏感字段**（供应商 / 端点 / 模型名 / 配置名）——
 * Key 绝不进二维码。二维码会被拍照、转发、印在文档里，按"会被公开"
 * 的最坏预期设计；导出方想分享 Key 得另行传，导入方拿到配置自己填 Key。
 */
object ConfigQr {

    const val PREFIX = "agentkit://config?v=1&d="

    /** 一份可分享的模型配置（无敏感字段）。 */
    data class ConfigPayload(
        val providerId: String,
        val baseUrl: String,
        val model: String,
        val name: String
    )

    /** 导出：打包成二维码负载文本。 */
    fun export(providerId: String, baseUrl: String, model: String, name: String): String {
        val body = buildString {
            append("p=").append(providerId.trim()).append('\n')
            append("u=").append(baseUrl.trim()).append('\n')
            append("m=").append(model.trim()).append('\n')
            append("n=").append(name.trim()).append('\n')
        }
        val b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toByteArray(Charsets.UTF_8))
        return PREFIX + b64
    }

    /** 导入：宽容解析。前缀不对 / base64 坏 / 关键字段缺 → null（不抛、不猜）。 */
    fun import(text: String): ConfigPayload? {
        val raw = text.trim()
        if (!raw.startsWith(PREFIX)) return null
        val decoded = try {
            Base64.getUrlDecoder().decode(raw.removePrefix(PREFIX)).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val map = mutableMapOf<String, String>()
        decoded.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
        }
        val provider = map["p"].orEmpty().trim()
        val url = map["u"].orEmpty().trim()
        val model = map["m"].orEmpty().trim()
        if (provider.isEmpty() || url.isEmpty() || model.isEmpty()) return null
        return ConfigPayload(provider, url, model, map["n"].orEmpty().trim())
    }
}
