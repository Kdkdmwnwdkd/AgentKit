#!/usr/bin/env kotlin
/**
 * Phase 2 · T11「聊天消息 Markdown 渲染」QA 行为验证 harness。
 *
 * 目标：对**纯 JVM 的** [com.example.agentkit.ui.MarkdownParser] 做逐条断言——不依赖 Android、
 * 不依赖 JUnit（本机离线 Gradle 缓存无 JUnit jar）。运行方式见 scripts/qa/run-markdown-verification.sh。
 *
 * 覆盖：
 *   A 块级 9 类语法（标题 / 代码块 / 列表 / 引用 / 分割线 / 段落）
 *   B 行内 5 类片段（粗体 / 斜体 / 行内码 / 链接 / 混合嵌套）
 *   C 安全：`<script>` 等 HTML 一律落为普通文本，AST 无任何可执行 / HTML 节点
 *   D 未闭合标记三例及更多：按字面渲染、不抛异常
 *   E 边界：空输入 / 全空白 / 超长单行
 *   F 流式：半截标记逐前缀解析全程不崩
 */

import com.example.agentkit.ui.MdBlock
import com.example.agentkit.ui.MdListItem
import com.example.agentkit.ui.MdSpan
import com.example.agentkit.ui.MarkdownParser

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("期望为 true：$name")
}

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("$name：期望 <$expected> 实际 <$actual>")
}

/* ------------------------------ AST 便捷读取 ------------------------------ */

private fun parse(text: String): List<MdBlock> = MarkdownParser.parse(text)
private fun inline(text: String): List<MdSpan> = MarkdownParser.parseInline(text)

/** 取块级 AST 里出现的全部行内片段（含列表、引用递归）。 */
private fun allSpans(blocks: List<MdBlock>): List<MdSpan> = blocks.flatMap { blockSpans(it) }

private fun blockSpans(b: MdBlock): List<MdSpan> = when (b) {
    is MdBlock.Heading -> b.content
    is MdBlock.Paragraph -> b.content
    is MdBlock.Quote -> b.content
    is MdBlock.BulletList -> b.items.flatMap { itemSpans(it) }
    is MdBlock.OrderedList -> b.items.flatMap { itemSpans(it) }
    is MdBlock.CodeBlock -> emptyList()
    MdBlock.Divider -> emptyList()
}

private fun itemSpans(item: MdListItem): List<MdSpan> =
    item.content + (item.subList?.let { blockSpans(it) } ?: emptyList())

/** 仅取「纯文本」内容，用于字面渲染断言。 */
private fun plainText(spans: List<MdSpan>): String = spans.joinToString("") { span ->
    when (span) {
        is MdSpan.Plain -> span.text
        is MdSpan.Code -> span.text
        is MdSpan.Bold -> plainText(span.children)
        is MdSpan.Italic -> plainText(span.children)
        is MdSpan.Link -> plainText(span.children)
    }
}

/* ------------------------------ A. 块级 ------------------------------ */

private fun testBlocks() {
    println("\n== A. 块级语法 ==")

    check("① 标题 H1：# 解析为 Heading(level=1)") {
        val b = parse("# 标题")
        assertEquals("块数", 1, b.size)
        val h = b[0] as MdBlock.Heading
        assertEquals("级别", 1, h.level)
        assertEquals("文字", "标题", plainText(h.content))
    }

    check("① 标题 H2/H3：# 数量即级别") {
        assertEquals("H2", 2, (parse("## 二级")[0] as MdBlock.Heading).level)
        assertEquals("H3", 3, (parse("### 三级")[0] as MdBlock.Heading).level)
    }

    check("① #hashtag（无空格）不是标题，退回段落") {
        assertTrue("应为段落", parse("#hashtag")[0] is MdBlock.Paragraph)
    }

    check("① 七个井号不识别为标题") {
        assertTrue("应为段落", parse("####### x")[0] is MdBlock.Paragraph)
    }

    check("① 多行普通文本合并为一个段落，保留换行") {
        val b = parse("第一行\n第二行")
        assertEquals("块数", 1, b.size)
        assertEquals("文字", "第一行\n第二行", plainText((b[0] as MdBlock.Paragraph).content))
    }

    check("⑤ 代码块带语言标签：```kotlin") {
        val cb = parse("```kotlin\nval x = 1\n```")[0] as MdBlock.CodeBlock
        assertEquals("语言", "kotlin", cb.language)
        assertEquals("代码", "val x = 1", cb.code)
    }

    check("⑤ 代码块无语言：language 为空串") {
        val cb = parse("```\nplain\n```")[0] as MdBlock.CodeBlock
        assertEquals("语言", "", cb.language)
        assertEquals("代码", "plain", cb.code)
    }

    check("⑤ 代码块内的 Markdown 记号不被二次解析") {
        val cb = parse("```\n# 不是标题\n- 不是列表\n**不是粗体**\n```")[0] as MdBlock.CodeBlock
        assertEquals("原样保留", "# 不是标题\n- 不是列表\n**不是粗体**", cb.code)
    }

    check("⑥ 无序列表 -，3 项") {
        val list = parse("- a\n- b\n- c")[0] as MdBlock.BulletList
        assertEquals("项数", 3, list.items.size)
        assertEquals("首项", "a", plainText(list.items[0].content))
    }

    check("⑥ 无序列表 * 标记同样识别") {
        val list = parse("* a\n* b")[0] as MdBlock.BulletList
        assertEquals("项数", 2, list.items.size)
    }

    check("⑥ 有序列表 1. / 2.，2 项") {
        val list = parse("1. a\n2. b")[0] as MdBlock.OrderedList
        assertEquals("项数", 2, list.items.size)
        assertEquals("次项", "b", plainText(list.items[1].content))
    }

    check("⑥ 有序列表 ) 标记同样识别") {
        val list = parse("1) a\n2) b")[0] as MdBlock.OrderedList
        assertEquals("项数", 2, list.items.size)
    }

    check("⑥ 嵌套列表（一层）：子项挂到父项 subList") {
        val list = parse("- a\n  - a1\n  - a2\n- b")[0] as MdBlock.BulletList
        assertEquals("顶层项数", 2, list.items.size)
        val sub = list.items[0].subList as MdBlock.BulletList
        assertEquals("子项数", 2, sub.items.size)
        assertEquals("子项 1", "a1", plainText(sub.items[0].content))
        assertTrue("第二项无子列表", list.items[1].subList == null)
    }

    check("⑦ 引用：> 行解析为 Quote") {
        val q = parse("> 引用内容")[0] as MdBlock.Quote
        assertEquals("文字", "引用内容", plainText(q.content))
    }

    check("⑦ 多行引用合并为一块，保留换行") {
        val q = parse("> a\n> b")[0] as MdBlock.Quote
        assertEquals("文字", "a\nb", plainText(q.content))
    }

    check("⑨ 分割线 --- 与 ***") {
        assertTrue("--- 是分割线", parse("---")[0] is MdBlock.Divider)
        assertTrue("*** 是分割线", parse("***")[0] is MdBlock.Divider)
    }

    check("混合文档：块序列符合预期") {
        val doc = """
            # 标题
            正文段落
            - 项一
            - 项二

            ```kotlin
            val x = 1
            ```
            > 引用
            ---
        """.trimIndent()
        val b = parse(doc)
        val kinds = b.map { it::class.simpleName }
        assertEquals("块序列", listOf("Heading", "Paragraph", "BulletList", "CodeBlock", "Quote", "Divider"), kinds)
    }

    check("列表项内含行内粗体：- **粗** 项") {
        val list = parse("- **粗** 项")[0] as MdBlock.BulletList
        val spans = list.items[0].content
        assertTrue("含 Bold", spans.any { it is MdSpan.Bold })
    }
}

/* ------------------------------ B. 行内 ------------------------------ */

private fun testInline() {
    println("\n== B. 行内语法 ==")

    check("② 粗体 **x**") {
        val s = inline("**x**")
        assertEquals("片段数", 1, s.size)
        val b = s[0] as MdSpan.Bold
        assertEquals("子文本", "x", (b.children[0] as MdSpan.Plain).text)
    }

    check("③ 斜体 *x*") {
        val s = inline("*x*")
        assertEquals("片段数", 1, s.size)
        assertTrue("是 Italic", s[0] is MdSpan.Italic)
        assertEquals("子文本", "x", plainText((s[0] as MdSpan.Italic).children))
    }

    check("④ 行内代码 `x`") {
        val s = inline("`x`")
        assertEquals("片段数", 1, s.size)
        assertEquals("内容", "x", (s[0] as MdSpan.Code).text)
    }

    check("⑧ 链接 [文字](url)") {
        val s = inline("[文档](https://example.com)")
        assertEquals("片段数", 1, s.size)
        val l = s[0] as MdSpan.Link
        assertEquals("文字", "文档", plainText(l.children))
        assertEquals("地址", "https://example.com", l.url)
    }

    check("混合行内：a **b** c → 三段") {
        val s = inline("a **b** c")
        assertEquals("片段数", 3, s.size)
        assertTrue("中段是 Bold", s[1] is MdSpan.Bold)
    }

    check("粗体内嵌斜体：**a *b* c** → Bold 内含 Italic") {
        val s = inline("**a *b* c**")
        val bold = s[0] as MdSpan.Bold
        assertTrue("含 Italic", bold.children.any { it is MdSpan.Italic })
    }

    check("行内代码优先：`**x**` 不被解析为粗体") {
        val s = inline("`**x**`")
        assertEquals("片段数", 1, s.size)
        assertEquals("字面", "**x**", (s[0] as MdSpan.Code).text)
    }

    check("标题行内含粗体：## **粗** 标题") {
        val h = parse("## **粗** 标题")[0] as MdBlock.Heading
        assertTrue("标题含 Bold", h.content.any { it is MdSpan.Bold })
    }
}

/* ------------------------------ C. 安全 ------------------------------ */

private fun testSecurity() {
    println("\n== C. 安全（不可信输入 → 无注入面）==")

    check("`<script>alert(1)</script>` 解析为普通文本，AST 不含任何非 Plain 节点") {
        val b = parse("<script>alert(1)</script>")
        assertEquals("块数", 1, b.size)
        val p = b[0] as MdBlock.Paragraph
        assertEquals("片段数", 1, p.content.size)
        assertTrue("是普通文本", p.content[0] is MdSpan.Plain)
        assertEquals("原样字面", "<script>alert(1)</script>", (p.content[0] as MdSpan.Plain).text)
        assertTrue("全 AST 无非 Plain 节点", allSpans(b).all { it is MdSpan.Plain })
    }

    check("段落内 HTML 标签原样保留：**<b>x</b>** → Bold 内是字面标签") {
        val b = parse("**<b>x</b>**")
        val bold = (b[0] as MdBlock.Paragraph).content[0] as MdSpan.Bold
        assertEquals("字面", "<b>x</b>", plainText(bold.children))
    }

    check("带事件的标签 <img src=x onerror=alert(1)> 原样为文本") {
        val text = "<img src=x onerror=alert(1)>"
        val p = parse(text)[0] as MdBlock.Paragraph
        assertEquals("原样字面", text, plainText(p.content))
    }

    check("HTML 与 Markdown 混排：标签落 Plain，斜体仍生效") {
        val b = parse("<div>*强调*</div>")
        val spans = (b[0] as MdBlock.Paragraph).content
        assertTrue("含 Italic", spans.any { it is MdSpan.Italic })
        assertTrue("含字面 <div>", plainText(spans).contains("<div>"))
    }
}

/* ------------------------------ D. 未闭合 ------------------------------ */

private fun testUnclosed() {
    println("\n== D. 未闭合标记（按字面渲染，不抛异常）==")

    check("`**粗` 未闭合 → 字面 [Plain(\"**粗\")]") {
        val s = inline("**粗")
        assertEquals("片段数", 1, s.size)
        assertEquals("字面", "**粗", (s[0] as MdSpan.Plain).text)
    }

    check("`` `code `` 未闭合 → 字面 [Plain(\"`code\")]") {
        val s = inline("`code")
        assertEquals("片段数", 1, s.size)
        assertEquals("字面", "`code", (s[0] as MdSpan.Plain).text)
    }

    check("孤立 ``` （无闭合围栏）→ 段落，非代码块") {
        val b = parse("```")
        assertEquals("块数", 1, b.size)
        assertTrue("是段落", b[0] is MdBlock.Paragraph)
        assertEquals("字面", "```", plainText((b[0] as MdBlock.Paragraph).content))
    }

    check("未闭合斜体 `*ital` → 字面") {
        assertEquals("字面", "*ital", (inline("*ital")[0] as MdSpan.Plain).text)
    }

    check("未闭合链接 `[t](u` → 字面") {
        assertEquals("字面", "[t](u", (inline("[t](u")[0] as MdSpan.Plain).text)
    }

    check("块级未闭合加粗 `**未闭合` → 段落字面，不吞内容") {
        val p = parse("**未闭合")[0] as MdBlock.Paragraph
        assertEquals("字面", "**未闭合", plainText(p.content))
    }
}

/* ------------------------------ E. 边界 ------------------------------ */

private fun testBoundary() {
    println("\n== E. 边界 ==")

    check("空串 → 空块列表") {
        assertEquals("块数", 0, parse("").size)
    }

    check("纯空白 → 空块列表") {
        assertEquals("块数", 0, parse("   \n\t\n  ").size)
    }

    check("超长单行（约 4 万字）解析不抛异常且结构正确") {
        val long = "a".repeat(20000) + " **b** " + "c".repeat(20000)
        val b = parse(long)
        assertEquals("块数", 1, b.size)
        val spans = (b[0] as MdBlock.Paragraph).content
        assertTrue("含 Bold", spans.any { it is MdSpan.Bold })
    }
}

/* ------------------------------ F. 流式 ------------------------------ */

private fun testStreaming() {
    println("\n== F. 流式半截文本 ==")

    check("半截 `这是**加粗` → 字面，不崩") {
        assertEquals("字面", "这是**加粗", plainText(inline("这是**加粗")))
    }

    check("闭合后 `这是**加粗**` → 粗体生效") {
        val s = inline("这是**加粗**")
        assertTrue("含 Bold", s.any { it is MdSpan.Bold })
        assertEquals("整段文本", "这是加粗", plainText(s))
    }

    check("带内嵌代码的富文本：逐前缀解析全程不抛异常") {
        val full = "**标题** 与 `code` 与 [链接](https://e.com) 与 ~~~ 结束"
        for (i in 0..full.length) {
            parse(full.substring(0, i))
            inline(full.substring(0, i))
        }
    }

    check("半截代码块 ```` ```kotlin\\nfun main` → 段落，不崩") {
        val b = parse("```kotlin\nfun main")
        assertTrue("是段落", b[0] is MdBlock.Paragraph)
    }

    check("半截链接流式 `[文档](http` → 字面，不崩") {
        assertEquals("字面", "[文档](http", plainText(inline("[文档](http")))
    }

    check("半截引用 `>` 与 `> x` 均不崩") {
        parse(">")
        parse("> x")
        assertTrue("> x 是引用", parse("> x")[0] is MdBlock.Quote)
    }
}

/* ------------------------------ main ------------------------------ */

fun main() {
    println("=== Phase 2 · T11 Markdown 渲染 QA 行为验证 ===")
    testBlocks()
    testInline()
    testSecurity()
    testUnclosed()
    testBoundary()
    testStreaming()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
