import com.example.agentkit.core.text.MentionLogic

/**
 * W-28 harness：@提及文件解析与匹配（纯 JVM）。
 *
 * 重点：
 *  - 只认行首/空白后的 `@`（邮箱 `a@b.com` 不能当提及）；
 *  - 关键词遇空格即结束（用户打完文件就继续写句子）；
 *  - `extract` 只收「像文件」的（带点或斜杠），`@张三` 不当文件；
 *  - 匹配优先级：前缀 > 目录内文件名前缀 > 包含 > 子序列；
 *  - 空关键词返回前 N 个（让用户先看到有什么）。
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

private val FILES = listOf(
    "README.md",
    "src/main.kt",
    "src/util.kt",
    "src/com/deep/file.kt",
    "docs/guide.md",
    "notes.txt"
)

fun main() {
    println("=== W-28 @提及文件 ===")

    check("末尾 @ 触发，并带回关键词") {
        val q = MentionLogic.activeQueryAtEnd("帮我改一下 @src/ma")
        assertTrue("应触发", q != null)
        assertTrue("关键词", q!!.keyword == "src/ma")
        assertTrue("位置正确", "帮我改一下 @src/ma".substring(q.atIndex, q.atIndex + 1) == "@")
    }

    check("行首 @ 触发") {
        assertTrue("空关键词", MentionLogic.activeQueryAtEnd("@")?.keyword == "")
        assertTrue("行首关键词", MentionLogic.activeQueryAtEnd("@README")?.keyword == "README")
    }

    check("邮箱里的 @ 不触发") {
        assertTrue("a@b.com", MentionLogic.activeQueryAtEnd("发到 a@b.com") == null)
        assertTrue("紧贴中文", MentionLogic.activeQueryAtEnd("联系@张三") == null)
    }

    check("关键词遇空格即结束（不触发）") {
        assertTrue("已打完", MentionLogic.activeQueryAtEnd("@README.md 然后") == null)
    }

    check("无 @ 返回 null") {
        assertTrue("普通文本", MentionLogic.activeQueryAtEnd("帮我看看这个文件") == null)
        assertTrue("空串", MentionLogic.activeQueryAtEnd("") == null)
    }

    check("超长关键词不触发（用户不是在选文件）") {
        val long = "@" + "a".repeat(MentionLogic.MAX_KEYWORD_LEN + 1)
        assertTrue("超长", MentionLogic.activeQueryAtEnd(long) == null)
    }

    check("extract：多个提及去重保序") {
        val text = "改 @src/main.kt 和 @notes.txt，再看 @src/main.kt"
        val out = MentionLogic.extract(text)
        assertTrue("2 个", out.size == 2)
        assertTrue("顺序", out[0] == "src/main.kt" && out[1] == "notes.txt")
    }

    check("extract：@张三 不算文件（无点无斜杠）") {
        assertTrue("无文件", MentionLogic.extract("@张三 你好").isEmpty())
        assertTrue("有文件", MentionLogic.extract("@张三 看下 @a.txt") == listOf("a.txt"))
    }

    check("extract：邮箱不误收") {
        assertTrue("邮箱", MentionLogic.extract("发到 a@b.com").isEmpty())
    }

    check("replaceTailWith：替换末尾提及并补尾空格") {
        val out = MentionLogic.replaceTailWith("帮我改 @src/ma", "src/main.kt")
        assertTrue("结果=$out", out == "帮我改 @src/main.kt ")
    }

    check("replaceTailWith：没有末尾提及则原样返回") {
        assertTrue("不变", MentionLogic.replaceTailWith("普通文本", "a.txt") == "普通文本")
    }

    check("match：前缀优先于包含") {
        val out = MentionLogic.match(FILES, "src/", 8)
        assertTrue("src 开头优先", out.first().startsWith("src/"))
        assertTrue("全部命中 3 个 src", out.count { it.startsWith("src/") } == 3)
    }

    check("match：目录内文件名前缀次优") {
        val out = MentionLogic.match(FILES, "main", 8)
        assertTrue("main.kt 第一", out.first() == "src/main.kt")
    }

    check("match：包含匹配") {
        val out = MentionLogic.match(FILES, "uide", 8)
        assertTrue("guide.md", out.contains("docs/guide.md"))
    }

    check("match：子序列兜底（src 里深路径）") {
        val out = MentionLogic.match(FILES, "scfile", 8)
        assertTrue("含 deep/file.kt", out.contains("src/com/deep/file.kt"))
    }

    check("match：空关键词返回前 N 个") {
        val out = MentionLogic.match(FILES, "", 3)
        assertTrue("3 个", out.size == 3)
        assertTrue("按原顺序", out == FILES.take(3))
    }

    check("match：limit 生效且无命中返回空") {
        assertTrue("limit", MentionLogic.match(FILES, "src/", 2).size == 2)
        assertTrue("无命中", MentionLogic.match(FILES, "zzzzz", 8).isEmpty())
        assertTrue("空列表", MentionLogic.match(emptyList(), "a", 8).isEmpty())
    }

    check("match：大小写不敏感") {
        assertTrue("README", MentionLogic.match(FILES, "readme", 8).contains("README.md"))
        assertTrue("大写关键词", MentionLogic.match(FILES, "README", 8).contains("README.md"))
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
