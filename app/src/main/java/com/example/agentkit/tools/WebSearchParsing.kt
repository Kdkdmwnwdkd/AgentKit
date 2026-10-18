package com.example.agentkit.tools

/**
 * 搜索结果解析（W-20 / W-22 修）：纯 JVM、无网络、无 Android 依赖，
 * 供 [WebSearchTool] 与 QA harness 共用。
 *
 * 两个引擎、两套结构（均用真实页面验证过）：
 *  - **Bing（cn.bing.com）**：国内直连可用（实测 HTTP 200 / 61KB / 5 条结果）。
 *    结构 `<li class="b_algo"><a href><h2>标题</h2></a><p class="b_lineclamp3">摘要</p>`。
 *  - **DuckDuckGo（html.duckduckgo.com）**：海外可用，国内直连超时（沙箱实测 20s timeout）。
 *    结构 `<a class="result__a" href="//duckduckgo.com/l/?uddg=…">标题</a>`。
 *
 * 为什么以 Bing 为主：用户设备在国内，DDG 大概率连不上；DDG 保留为兜底。
 */
object WebSearchParsing {

    data class SearchItem(val title: String, val url: String, val snippet: String)

    /** DDG 结果条目整块：标题锚点 + 摘要锚点。 */
    private val DDG_BLOCK = Regex(
        """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""" +
            """(.{0,20000}?)class="result__snippet"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    /** Bing 结果块：`b_algo` 分隔。 */
    private val BING_SPLIT = Regex("""<li class="b_algo""")
    /** Bing 标题：`<a href="…"><h2>标题</h2></a>`（a 在外层，实测结构）。 */
    private val BING_TITLE = Regex(
        """<a[^>]*href="([^"]+)"[^>]*>\s*<h2[^>]*>(.*?)</h2>""",
        RegexOption.DOT_MATCHES_ALL
    )
    /** Bing 摘要：`<p class="b_lineclampN">…</p>` / `b_snippet`。 */
    private val BING_SNIPPET = Regex(
        """<p[^>]*class="[^"]*(?:b_lineclamp|b_snippet)[^"]*"[^>]*>(.*?)</p>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val TAG = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|#39|apos|nbsp|#183);""")
    private val UDG_PARAM = Regex("""[?&]uddg=([^&]+)""")
    /** Bing 跳转包装：`/ck/a?…&u=a1<base64url>`；HTML 里常写作 `&amp;u=`，两种都认。 */
    private val BING_REDIRECT = Regex("""[?&](?:amp;)?u=a1([^&]+)""")

    /** HTML 实体还原 + 去标签。 */
    fun unescape(s: String): String {
        var t = TAG.replace(s, "")
        t = ENTITY.replace(t) { m ->
            val v = m.groupValues[1]
            when (v) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""
                "#39", "apos" -> "'"; "nbsp" -> " "; "#183" -> "·"
                else -> if (v.startsWith("#")) {
                    runCatching {
                        val code = if (v.startsWith("#x")) v.drop(2).toInt(16) else v.drop(1).toInt()
                        String(Character.toChars(code))
                    }.getOrDefault(m.value)
                } else m.value
            }
        }
        return t.trim()
    }

    /** DDG 结果 href 形如 `//duckduckgo.com/l/?uddg=<编码后的真实 URL>&rut=…`，还原真实地址。 */
    fun realUrl(href: String): String {
        val m = UDG_PARAM.find(href) ?: return href
        return runCatching {
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        }.getOrDefault(href)
    }

    /**
     * Bing 跳转包装还原：`https://www.bing.com/ck/a?…&u=a1<base64url>`。
     * `a1` 前缀后的内容是 URL 的 base64url；解不出来就原样返回（不抛异常）。
     */
    fun realUrlBing(href: String): String {
        val normalized = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://www.bing.com$href"
            else -> href
        }
        if (!normalized.contains("bing.com/ck/a")) return normalized
        val m = BING_REDIRECT.find(normalized) ?: return normalized
        return runCatching {
            val raw = m.groupValues[1].replace('-', '+').replace('_', '/')
            val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrDefault(normalized)
    }

    /** 解析 DuckDuckGo 整页 HTML。 */
    fun parse(html: String, max: Int): List<SearchItem> =
        DDG_BLOCK.findAll(html)
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

    /** 解析 Bing 整页 HTML（cn.bing.com / www.bing.com 通用）。 */
    fun parseBing(html: String, max: Int): List<SearchItem> =
        BING_SPLIT.split(html)
            .drop(1) // 第一段是结果区之前的内容
            .mapNotNull { block ->
                val tm = BING_TITLE.find(block) ?: return@mapNotNull null
                val url = realUrlBing(tm.groupValues[1])
                val title = unescape(tm.groupValues[2])
                val snippet = BING_SNIPPET.find(block)
                    ?.let { unescape(it.groupValues[1]) }
                    .orEmpty()
                if (url.isBlank() || title.isBlank() || !url.startsWith("http")) null
                else SearchItem(title, url, snippet.take(300))
            }
            .distinctBy { it.url }
            .take(max)
            .toList()
}
