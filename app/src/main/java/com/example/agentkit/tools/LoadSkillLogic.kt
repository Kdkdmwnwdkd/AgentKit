package com.example.agentkit.tools

/**
 * load_skill 的校验纯函数（W-23）：URL 安全 / 体积上限 / 参数判定。
 * 无 Android、无网络，QA harness 可直接验证。
 *
 * ## 安全口径（为什么这些检查必须有）
 * 让模型自己下载并安装技能包 = 把「往设备写文件」的入口交给模型。必须挡住：
 *  ① 非 HTTPS（明文可被中间人替换）；
 *  ② 内网地址（SSRF：让设备去打 192.168.x.x / 10.x / 127.0.0.1）；
 *  ③ 超大包（zip 炸弹 / 把用户流量耗光）。
 * 安装侧另有 Zip Slip / zip bomb / 强制降权防护（见 ImportedSkillRepository），两层独立。
 */
object LoadSkillLogic {

    /** 技能包体积上限：2 MB（技能是文本 + 少量脚本，正常远小于此）。 */
    const val MAX_ZIP_BYTES = 2 * 1024 * 1024

    /** 校验下载地址；返回错误文案，null 表示通过。 */
    fun validateUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "URL 为空"
        if (!url.startsWith("https://", ignoreCase = true)) {
            return "只允许 https 地址（明文 http 可能被中间人替换技能包）"
        }
        val host = hostOf(url) ?: return "URL 解析失败，请给出完整地址"
        if (isPrivateHost(host)) {
            return "不允许访问内网地址（$host）：技能包必须来自公网 https"
        }
        return null
    }

    /** 取 host（去掉端口/路径），失败返回 null。 */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPort.isEmpty()) return null
        // 处理 [IPv6]:port 与 host:port
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
        return host.ifBlank { null }
    }

    /** 内网/环回/链路本地地址判定（防 SSRF）。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "0.0.0.0" || h == "::1" || h == "::") return true
        // IPv4 字面量
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return when {
                a == 10 -> true                       // 10.0.0.0/8
                a == 127 -> true                      // 环回
                a == 0 -> true                        // 本网络
                a == 169 && b == 254 -> true          // 链路本地（云元数据）
                a == 172 && b in 16..31 -> true       // 172.16/12
                a == 192 && b == 168 -> true          // 192.168/16
                a == 100 && b in 64..127 -> true      // CGNAT
                else -> false
            }
        }
        // IPv6 私有段（fc00::/7 唯一本地、fe80::/10 链路本地）——按前缀粗判
        if (h.contains(':')) {
            if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9")
                || h.startsWith("fea") || h.startsWith("feb")
            ) return true
        }
        return false
    }

    /** 体积校验；返回错误文案，null 表示通过。 */
    fun validateZipSize(bytes: Long): String? = when {
        bytes <= 0L -> "技能包为空"
        bytes > MAX_ZIP_BYTES -> "技能包过大（${bytes / 1024} KB），上限 ${MAX_ZIP_BYTES / 1024} KB"
        else -> null
    }

    /** base64 解码（容忍换行/空白与 data URI 前缀）；返回字节或 null。 */
    fun decodeBase64(raw: String): ByteArray? {
        var s = raw.trim()
        if (s.startsWith("data:", ignoreCase = true)) {
            s = s.substringAfter(',', "")
        }
        s = s.filterNot { it.isWhitespace() }
        if (s.isEmpty()) return null
        // URL 安全字符集兼容
        val normalized = s.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
    }

    /** 技能 id 合法性（与安装器一致的白名单，提前给模型明确错误）。 */
    fun validateId(id: String): String? {
        val v = id.trim()
        if (v.isEmpty()) return "id 为空"
        if (!v.matches(Regex("[A-Za-z0-9._-]+"))) return "非法技能 id：只允许字母/数字/点/下划线/短横线"
        return null
    }
}
