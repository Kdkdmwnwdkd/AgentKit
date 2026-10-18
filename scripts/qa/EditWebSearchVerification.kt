import com.example.agentkit.tools.EditFileLogic
import com.example.agentkit.tools.EditPlan
import com.example.agentkit.tools.WebSearchParsing

/**
 * W-20 harness：edit_file 决策纯函数 + web_search 解析纯函数（不加载 Android，不联网）。
 *
 * 口径：
 *  edit_file：
 *   - 唯一匹配 → Replace，替换后文本正确、count=1、firstOffset 指向新文本；
 *   - 空 old / old==new → 拒绝（不写坏文件）；
 *   - 找不到 → stale_not_found；
 *   - 多处且未 replace_all → not_unique（message 带行号）；
 *   - replace_all=true → 全部替换，count=出现次数；
 *   - 行号/上下文抽取在文件首行、末行、单行文件等边界不抛异常。
 *  web_search 解析：
 *   - 标准 DDG HTML 提取标题/链接/摘要；uddg 跳转还原真实 URL；
 *   - HTML 实体还原（&amp; 等）；按 URL 去重；max 截断；
 *   - 空页/无结果结构 → 空列表（工具层据此返回 no_results）。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private val FILE = """
    package com.example.demo

    fun main() {
        val a = 1
        val b = 2
        println(a + b)
    }
""".trimIndent()

/** 造一段标准 DDG 结果 HTML（净室 fixture，不走网络）。 */
private fun ddgHtml(vararg items: Triple<String, String, String>): String = buildString {
    append("<html><body>")
    items.forEach { (title, realUrl, snippet) ->
        val uddg = java.net.URLEncoder.encode(realUrl, "UTF-8")
        append(
            """
            <div class="result results_links">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=$uddg&amp;rut=abc">$title</a>
              <a class="result__snippet" href="x">$snippet</a>
            </div>
            """.trimIndent()
        )
    }
    append("</body></html>")
}

fun main() {
    println("=== W-20 edit_file 决策 + web_search 解析 ===")

    // ---------- edit_file：正常替换 ----------
    check("唯一匹配 → Replace，文本正确") {
        val plan = EditFileLogic.plan(FILE, "val a = 1", "val a = 100", replaceAll = false)
        assertTrue("应为 Replace", plan is EditPlan.Replace)
        plan as EditPlan.Replace
        assertTrue("替换后含新值", plan.replaced.contains("val a = 100"))
        assertTrue("旧整行已消失", !plan.replaced.lines().any { it.trim() == "val a = 1" })
        assertTrue("count=1", plan.count == 1)
        assertTrue("firstOffset 指向新文本", plan.replaced.startsWith("val a = 100", plan.firstOffset))
    }

    check("替换为更长/更短文本，行数不变时偏移有效") {
        val plan = EditFileLogic.plan(FILE, "println(a + b)", "println(a * b + 0)", replaceAll = false)
        plan as EditPlan.Replace
        assertTrue("含新表达式", plan.replaced.contains("println(a * b + 0)"))
        assertTrue("旧表达式消失", !plan.replaced.contains("println(a + b)"))
    }

    check("new_string 为空串 = 删除片段") {
        val src = "hello world"
        val plan = EditFileLogic.plan(src, " world", "", replaceAll = false)
        plan as EditPlan.Replace
        assertTrue("删除后为 hello", plan.replaced == "hello")
    }

    // ---------- edit_file：拒绝路径（写坏文件前拦截） ----------
    check("old_string 为空 → empty_old_string 拒绝") {
        val plan = EditFileLogic.plan(FILE, "", "x", replaceAll = false)
        assertTrue("应为 Reject", plan is EditPlan.Reject)
        plan as EditPlan.Reject
        assertTrue("码正确", plan.code == "empty_old_string")
        assertTrue("hint 非空", plan.hint.isNotBlank())
    }

    check("old == new → no_op 拒绝") {
        val plan = EditFileLogic.plan(FILE, "val a = 1", "val a = 1", replaceAll = false)
        plan as EditPlan.Reject
        assertTrue("码正确", plan.code == "no_op")
    }

    check("找不到 old_string → stale_not_found 拒绝") {
        val plan = EditFileLogic.plan(FILE, "val z = 9", "val z = 10", replaceAll = false)
        plan as EditPlan.Reject
        assertTrue("码正确", plan.code == "stale_not_found")
        assertTrue("提示重新读取", plan.hint.contains("read_file"))
    }

    check("缩进不一致也视为找不到（精确匹配语义）") {
        // FILE 经 trimIndent 后是 4 空格缩进；带 8 空格前缀的串必须找不到
        val plan = EditFileLogic.plan(FILE, "        val a = 1", "x", replaceAll = false)
        assertTrue("应找不到", plan is EditPlan.Reject && plan.code == "stale_not_found")
    }

    check("多处出现且未 replace_all → not_unique，message 带行号") {
        val src = "x = 1\ny = 2\nx = 1\n"
        val plan = EditFileLogic.plan(src, "x = 1", "x = 9", replaceAll = false)
        plan as EditPlan.Reject
        assertTrue("码正确", plan.code == "not_unique")
        assertTrue("提示出现 2 次", plan.message.contains("2 次"))
        assertTrue("带行号 1、3", plan.message.contains("1") && plan.message.contains("3"))
    }

    check("replace_all=true → 全部替换，count=出现次数") {
        val src = "x = 1\ny = 2\nx = 1\n"
        val plan = EditFileLogic.plan(src, "x = 1", "x = 9", replaceAll = true)
        plan as EditPlan.Replace
        assertTrue("count=2", plan.count == 2)
        assertTrue("两处都换了", plan.replaced == "x = 9\ny = 2\nx = 9\n")
    }

    check("单处 + replace_all=true 也正常") {
        val plan = EditFileLogic.plan("only one", "one", "two", replaceAll = true)
        plan as EditPlan.Replace
        assertTrue("count=1", plan.count == 1)
        assertTrue("已替换", plan.replaced == "only two")
    }

    // ---------- edit_file：边界不抛异常 ----------
    check("行号计算：首行/末行/越界偏移") {
        assertTrue("首行", EditFileLogic.lineOf(FILE, 0) == 1)
        assertTrue("末行", EditFileLogic.lineOf(FILE, FILE.length - 1) == FILE.lines().size)
        assertTrue("越界取末尾行", EditFileLogic.lineOf(FILE, 99999) == FILE.lines().size)
        assertTrue("负偏移取首行", EditFileLogic.lineOf(FILE, -5) == 1)
    }

    check("上下文回显：首行附近不越界") {
        val ctx = EditFileLogic.contextAround(FILE, 0, contextLines = 3)
        assertTrue("非空", ctx.isNotBlank())
        assertTrue("首行有 → 标记", ctx.lineSequence().first().contains("→"))
    }

    check("上下文回显：单行文件/空文本") {
        assertTrue("单行", EditFileLogic.contextAround("hello", 0).contains("hello"))
        assertTrue("空文本有占位", EditFileLogic.contextAround("", 0) == "(上下文为空)")
    }

    // ---------- web_search 解析 ----------
    check("标准 DDG HTML：标题/链接/摘要提取") {
        val html = ddgHtml(
            Triple("Kotlin 官网", "https://kotlinlang.org/", "Kotlin 是一门现代编程语言"),
            Triple("协程指南", "https://kotlinlang.org/docs/coroutines-guide.html", "结构化并发")
        )
        val items = WebSearchParsing.parse(html, max = 5)
        assertTrue("2 条", items.size == 2)
        assertTrue("标题 1", items[0].title == "Kotlin 官网")
        assertTrue("真实 URL 还原", items[0].url == "https://kotlinlang.org/")
        assertTrue("摘要", items[0].snippet == "Kotlin 是一门现代编程语言")
        assertTrue("第二条", items[1].url == "https://kotlinlang.org/docs/coroutines-guide.html")
    }

    check("uddg 跳转包装还原真实地址（含查询串编码）") {
        val url = "https://example.com/search?q=hello world&lang=zh"
        val html = ddgHtml(Triple("T", url, "S"))
        val items = WebSearchParsing.parse(html, 5)
        assertTrue("还原", items.single().url == url)
    }

    check("HTML 实体还原（&amp; &lt; &gt;）") {
        val html = ddgHtml(
            Triple("A &amp; B &lt;tag&gt;", "https://e.com/?a=1&b=2", "x &amp; y")
        )
        val items = WebSearchParsing.parse(html, 5)
        assertTrue("标题实体还原", items.single().title == "A & B <tag>")
        assertTrue("摘要实体还原", items.single().snippet == "x & y")
    }

    check("非跳转 href 原样返回") {
        assertTrue(
            "普通 URL 不变",
            WebSearchParsing.realUrl("https://example.com/page") == "https://example.com/page"
        )
        assertTrue(
            "空串不炸",
            WebSearchParsing.realUrl("").isEmpty()
        )
    }

    check("按 URL 去重（同链接多条结果只留一条）") {
        val html = ddgHtml(
            Triple("T1", "https://e.com/same", "s1"),
            Triple("T2", "https://e.com/same", "s2"),
            Triple("T3", "https://e.com/other", "s3")
        )
        val items = WebSearchParsing.parse(html, 5)
        assertTrue("去重后 2 条", items.size == 2)
        assertTrue("保留首条", items[0].title == "T1")
    }

    check("max 截断") {
        val html = ddgHtml(
            Triple("T1", "https://e.com/1", "s"),
            Triple("T2", "https://e.com/2", "s"),
            Triple("T3", "https://e.com/3", "s")
        )
        assertTrue("max=2", WebSearchParsing.parse(html, 2).size == 2)
        assertTrue("max=8 但只有 3 条", WebSearchParsing.parse(html, 8).size == 3)
    }

    check("空页/无结果结构 → 空列表") {
        assertTrue("空 HTML", WebSearchParsing.parse("", 5).isEmpty())
        assertTrue("无关 HTML", WebSearchParsing.parse("<html><body>no results here</body></html>", 5).isEmpty())
    }

    check("长摘要截断到 300 字符") {
        val long = "x".repeat(1000)
        val html = ddgHtml(Triple("T", "https://e.com/", long))
        val items = WebSearchParsing.parse(html, 5)
        assertTrue("截断", items.single().snippet.length == 300)
    }

    check("标签剥离：标题带 <b> 高亮标签也干净") {
        val html = ddgHtml(Triple("<b>Kot</b>lin", "https://e.com/", "<b>snip</b>pet"))
        val items = WebSearchParsing.parse(html, 5)
        assertTrue("标题无标签", items.single().title == "Kotlin")
        assertTrue("摘要无标签", items.single().snippet == "snippet")
    }

    // ---------- W-22：Bing 解析（结构取自 2026-10-18 真实页面） ----------
    check("Bing：标准结果块解析（a 在外层包 h2）") {
        val html = """
            <html><body>
            <li class="b_algo" data-id iid=SERP.5260><div class="b_algoheader">
              <a href="https://kotlinlang.org/" h="ID=SERP,1"><h2><strong>Kotlin</strong> 官网</h2></a>
            </div><div class="b_caption"><p class="b_lineclamp3" data-rslinkclamp-iid="">现代编程语言</p></div></li>
            <li class="b_algo"><div class="b_algoheader">
              <a href="https://www.runoob.com/kotlin/" h="ID=SERP,2"><h2>Kotlin 教程</h2></a>
            </div><div class="b_caption"><p class="b_lineclamp4">菜鸟教程</p></div></li>
            </body></html>
        """.trimIndent()
        val items = WebSearchParsing.parseBing(html, 5)
        assertTrue("2 条", items.size == 2)
        assertTrue("标题去标签", items[0].title == "Kotlin 官网")
        assertTrue("URL", items[0].url == "https://kotlinlang.org/")
        assertTrue("摘要", items[0].snippet == "现代编程语言")
        assertTrue("第二条", items[1].url == "https://www.runoob.com/kotlin/")
    }

    check("Bing：跳转包装 u=a1<base64url> 还原真实地址") {
        // base64url("https://example.com/page")
        val html = """
            <li class="b_algo"><a href="https://www.bing.com/ck/a?!&&p=abc&amp;u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS9wYWdl&amp;ntb=1"><h2>T</h2></a>
            <p class="b_lineclamp3">S</p></li>
        """.trimIndent()
        val items = WebSearchParsing.parseBing(html, 5)
        assertTrue("还原成功", items.single().url == "https://example.com/page")
    }

    check("Bing：无结果结构 → 空列表（不误报）") {
        assertTrue("空页", WebSearchParsing.parseBing("", 5).isEmpty())
        assertTrue("无关页", WebSearchParsing.parseBing("<html>no result</html>", 5).isEmpty())
    }

    check("Bing：非 http 链接（javascript: 等）被过滤") {
        val html = """<li class="b_algo"><a href="javascript:void(0)"><h2>假结果</h2></a></li>"""
        assertTrue("被过滤", WebSearchParsing.parseBing(html, 5).isEmpty())
    }

    // ---------- W-22：edit 回显偏移回归（新文本在文件别处已存在） ----------
    check("firstOffset 指向替换点，而非文件里更早出现的同名新文本") {
        val src = "val a = 2\nmiddle\nval a = 1\n"
        // 把第二处的 "val a = 1" 改成 "val a = 2"（新文本在开头早已存在）
        val plan = EditFileLogic.plan(src, "val a = 1", "val a = 2", replaceAll = false)
        plan as EditPlan.Replace
        // 第一处替换点之前的文本没变，所以偏移应指向第 3 行开头（字符下标 16）
        val before = plan.replaced.take(plan.firstOffset)
        assertTrue("偏移应在第 3 行前（换行数=2）", before.count { it == '\n' } == 2)
        assertTrue("偏移处确为新文本", plan.replaced.startsWith("val a = 2", plan.firstOffset))
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
