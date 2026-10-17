package com.example.agentkit.tools

/**
 * DuckDuckGo HTML 端点结果解析（W-20）：纯 JVM、无网络、无 Android 依赖，
 * 供 [WebSearchTool] 与 QA harness（scripts/qa/EditWebSearchVerification.kt）共用。
 *
 * 净室实现：项目不引 jsoup（离线 CI 不加新依赖），两条正则 + 实体还原完成解析。
 */
object WebSearchParsing {

    data class SearchItem(val title: String, val url: String, val snippet: String)

    /** 结果条目整块：标题锚点 + 摘要锚点（DDG HTML 端点稳定结构）。 */
    private val RESULT_BLOCK = Regex(
        """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""" +
            """(.{0,20000}?)class="result__snippet"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val TAG = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(amp|lt|gt|quot|#39|apos|nbsp);""")
    private val UDG_PARAM = Regex("""[?&]uddg=([^&]+)""")

    /** HTML 实体还原 + 去标签。 */
    fun unescape(s: String): String {
        var t = TAG.replace(s, "")
        t = ENTITY.replace(t) { m ->
            when (m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""
                "#39" -> "'"; "apos" -> "'"; "nbsp" -> " "; else -> "&"
            }
        }
        return t.trim()
    }

    /**
     * DDG 结果 href 形如 `//duckduckgo.com/l/?uddg=<编码后的真实 URL>&rut=...`，
     * 还原为真实地址；非跳转包装的 href（已是真实 URL）原样返回。
     */
    fun realUrl(href: String): String {
        val m = UDG_PARAM.find(href) ?: return href
        return runCatching {
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        }.getOrDefault(href)
    }

    /** 解析整页 HTML，最多返回 [max] 条去重结果（按 URL 去重，保序）。 */
    fun parse(html: String, max: Int): List<SearchItem> =
        RESULT_BLOCK.findAll(html)
            .mapNotNull { m ->
                val url = realUrl(unescape(m.groupValues[1]))
                val title = unescape(m.groupValues[2])
                val snippet = unescape(m.groupValues[4])
                if (url.isBlank() || title.isBlank()) null
                else SearchItem(title, url, snippet.take(300))
            }
            .distinctBy { it.url }
            .take(max)
            .toList()
}
