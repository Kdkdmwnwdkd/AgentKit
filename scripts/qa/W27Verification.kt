import com.example.agentkit.core.text.SyntaxHighlighter
import com.example.agentkit.core.workspace.WorkspacePaths

/**
 * W-27 harness：工作区路径规则 + 语法高亮词法器（纯 JVM，不加载 Android）。
 *
 * 重点：
 *  - **路径防穿越**：绝对路径、`..`、空段、控制字符一律拒；
 *    子目录合法（`src/main.kt`），反斜杠统一；
 *  - **语言识别**：常见扩展名 → 语言；未知返回 null（纯文本，不着色）；
 *  - **词法正确性**：注释/字符串/数字/关键字的区间准确、互不重叠；
 *    未闭合字符串到行尾为止；SQL 关键字大小写不敏感；
 *  - **性能护栏**：超长文本直接返回空（不卡输入）。
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

fun main() {
    println("=== W-27 工作区路径 + 语法高亮 ===")

    // ---------- 路径 ----------
    check("合法相对路径（含子目录）") {
        assertTrue("单层", WorkspacePaths.safeRelPath("a.txt") == "a.txt")
        assertTrue("两层", WorkspacePaths.safeRelPath("src/main.kt") == "src/main.kt")
        assertTrue("三层", WorkspacePaths.safeRelPath("src/com/x.kt") == "src/com/x.kt")
        assertTrue("中文", WorkspacePaths.safeRelPath("文档/说明.md") == "文档/说明.md")
        assertTrue("反斜杠统一", WorkspacePaths.safeRelPath("src\\main.kt") == "src/main.kt")
    }

    check("路径穿越/绝对路径/非法字符 拒绝") {
        assertTrue("..", WorkspacePaths.safeRelPath("../etc/passwd") == null)
        assertTrue("中间 ..", WorkspacePaths.safeRelPath("a/../../b") == null)
        assertTrue("绝对", WorkspacePaths.safeRelPath("/data/x") == null)
        assertTrue("空段", WorkspacePaths.safeRelPath("a//b") == null)
        assertTrue("尾斜杠", WorkspacePaths.safeRelPath("a/") == null)
        assertTrue("控制字符", WorkspacePaths.safeRelPath("a\u0000b") == null)
        assertTrue("冒号", WorkspacePaths.safeRelPath("C:/x") == null)
        assertTrue("空", WorkspacePaths.safeRelPath("") == null)
        assertTrue("点", WorkspacePaths.safeRelPath(".") == null)
    }

    check("safeName：含斜杠非法（旧 API 语义不变）") {
        assertTrue("单层通过", WorkspacePaths.safeName("a.txt") == "a.txt")
        assertTrue("子目录拒绝", WorkspacePaths.safeName("src/a.kt") == null)
    }

    check("扩展名与语言识别") {
        assertTrue("kt", WorkspacePaths.languageOf("Main.kt") == "kotlin")
        assertTrue("py", WorkspacePaths.languageOf("a/b.py") == "python")
        assertTrue("json", WorkspacePaths.languageOf("x.json") == "json")
        assertTrue("md", WorkspacePaths.languageOf("README.md") == "markdown")
        assertTrue("无扩展名", WorkspacePaths.languageOf("Dockerfile") == null)
        assertTrue("未知", WorkspacePaths.languageOf("a.xyz") == null)
        assertTrue("大写", WorkspacePaths.languageOf("A.PY") == "python")
    }

    check("文本/二进制判断") {
        assertTrue("txt 文本", WorkspacePaths.isTextLikely("a.txt"))
        assertTrue("无扩展名当文本", WorkspacePaths.isTextLikely("Makefile"))
        assertTrue("png 非文本", !WorkspacePaths.isTextLikely("a.png"))
        assertTrue("apk 非文本", !WorkspacePaths.isTextLikely("a.apk"))
    }

    // ---------- 高亮 ----------
    check("Kotlin：关键字/字符串/数字/注释 命中且不重叠") {
        val code = """fun main() { val x = 42; println("hi") } // 注释"""
        val spans = SyntaxHighlighter.spans(code, "kotlin")
        assertTrue("有片段", spans.isNotEmpty())
        assertTrue("按起点排序", spans.zipWithNext().all { (a, b) -> a.start <= b.start })
        assertTrue("互不重叠", spans.zipWithNext().all { (a, b) -> a.end <= b.start })
        fun kindOf(word: String): SyntaxHighlighter.Kind? {
            val i = code.indexOf(word)
            return spans.firstOrNull { i >= it.start && i < it.end }?.kind
        }
        assertTrue("fun 是关键字", kindOf("fun") == SyntaxHighlighter.Kind.KEYWORD)
        assertTrue("val 是关键字", kindOf("val") == SyntaxHighlighter.Kind.KEYWORD)
        assertTrue("42 是数字", kindOf("42") == SyntaxHighlighter.Kind.NUMBER)
        assertTrue("\"hi\" 是字符串", kindOf("\"hi\"") == SyntaxHighlighter.Kind.STRING)
        assertTrue("注释", kindOf("// 注释") == SyntaxHighlighter.Kind.COMMENT)
    }

    check("Python：# 注释；// 不是注释") {
        val code = "x = 1  # 注释\nurl = 'http://a'"
        val spans = SyntaxHighlighter.spans(code, "python")
        val hashIdx = code.indexOf("# 注释")
        assertTrue(
            "# 是注释",
            spans.any { hashIdx >= it.start && hashIdx < it.end && it.kind == SyntaxHighlighter.Kind.COMMENT }
        )
        // 字符串里的 http:// 不应被当成注释
        val urlIdx = code.indexOf("http://a")
        assertTrue(
            "字符串内不产生注释",
            spans.none { urlIdx >= it.start && urlIdx < it.end && it.kind == SyntaxHighlighter.Kind.COMMENT }
        )
    }

    check("未闭合字符串到行尾为止（不吞下一行）") {
        val code = "a = \"未闭合\nb = 2"
        val spans = SyntaxHighlighter.spans(code, "python")
        val nl = code.indexOf('\n')
        val strSpan = spans.firstOrNull { it.kind == SyntaxHighlighter.Kind.STRING }
        assertTrue("有字符串", strSpan != null)
        assertTrue("不跨行", strSpan!!.end <= nl + 1)
    }

    check("SQL 关键字大小写不敏感") {
        val code = "select * FROM users where id = 1"
        val spans = SyntaxHighlighter.spans(code, "sql")
        assertTrue("select", spans.any { it.kind == SyntaxHighlighter.Kind.KEYWORD })
        assertTrue("FROM", spans.any { it.kind == SyntaxHighlighter.Kind.KEYWORD && it.start > 0 })
    }

    check("未知语言 / null 语言 → 不着色") {
        assertTrue("null", SyntaxHighlighter.spans("fun x() {}", null).isEmpty())
        assertTrue("未知", SyntaxHighlighter.spans("fun x() {}", "brainfuck").isEmpty())
    }

    check("空文本 → 空片段") {
        assertTrue("空", SyntaxHighlighter.spans("", "kotlin").isEmpty())
    }

    check("超长文本不扫描（性能护栏）") {
        val huge = "a".repeat(SyntaxHighlighter.MAX_HIGHLIGHT_CHARS + 1)
        assertTrue("返回空", SyntaxHighlighter.spans(huge, "kotlin").isEmpty())
    }

    check("区间不越界") {
        val code = "val s = \"x\" // c"
        val spans = SyntaxHighlighter.spans(code, "kotlin")
        assertTrue("所有 end ≤ 长度", spans.all { it.end <= code.length })
        assertTrue("所有 start < end", spans.all { it.start < it.end })
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
