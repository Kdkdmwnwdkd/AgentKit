import com.example.agentkit.core.text.DiffLine
import com.example.agentkit.core.text.LineDiffLogic

/**
 * W-34 harness：行级 diff 纯逻辑。
 *
 * 重点：
 *  - 公共前后缀裁剪后仍能正确给出 + / -（这是"只显示改动附近"的前提）；
 *  - 纯新增（远端无文件）与纯删除两种情况不能崩；
 *  - 超大改动必须**退化并标注**，而不是算出一个几十 MB 的 DP 表把 App 卡死；
 *  - 输出行数有上限（一次 diff 撑爆上下文比不显示更糟）。
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

private fun kinds(lines: List<DiffLine>): String = lines.joinToString("") { it.kind.toString() }

fun main() {
    println("== W-34 harness：行级 diff ==")

    check("完全相同 → 无差异") {
        val text = "a\nb\nc"
        assertTrue("应为空", LineDiffLogic.diff(text, text).isEmpty())
    }
    check("单行改动：一条删除 + 一条新增") {
        val diff = LineDiffLogic.diff("a\nb\nc", "a\nB\nc", context = 1)
        assertTrue("应含 -b，实际 ${kinds(diff)}", diff.any { it.kind == '-' && it.text == "b" })
        assertTrue("应含 +B", diff.any { it.kind == '+' && it.text == "B" })
        assertTrue("上下文应含 a 与 c", diff.any { it.kind == ' ' && it.text == "a" } &&
            diff.any { it.kind == ' ' && it.text == "c" })
    }
    check("上下文按 context 参数裁剪（0 = 只看改动行）") {
        val old = (1..10).joinToString("\n") { "line$it" }
        val new = old.replace("line5", "LINE5")
        val tight = LineDiffLogic.diff(old, new, context = 0)
        assertTrue("不应有上下文行，实际 ${kinds(tight)}", tight.none { it.kind == ' ' })
        val wide = LineDiffLogic.diff(old, new, context = 2)
        assertTrue("应有 4 行上下文（前后各 2）", wide.count { it.kind == ' ' } == 4)
    }
    check("新增文件（远端为空）：全是 + 行") {
        val diff = LineDiffLogic.diff("", "x\ny", context = 3)
        assertTrue("应只有 +，实际 ${kinds(diff)}", diff.all { it.kind == '+' })
        assertTrue("应有 2 行", diff.size == 2)
    }
    check("删除文件（本地为空）：全是 - 行") {
        val diff = LineDiffLogic.diff("x\ny", "", context = 3)
        assertTrue("应只有 -，实际 ${kinds(diff)}", diff.all { it.kind == '-' })
    }
    check("在中间插入一行") {
        val diff = LineDiffLogic.diff("a\nc", "a\nb\nc", context = 1)
        assertTrue("应含 +b", diff.any { it.kind == '+' && it.text == "b" })
        assertTrue("不应有删除行", diff.none { it.kind == '-' })
    }
    check("只改最后一行（后缀裁剪不吞掉改动）") {
        val diff = LineDiffLogic.diff("a\nb\nold", "a\nb\nnew", context = 1)
        assertTrue("应含 -old", diff.any { it.kind == '-' && it.text == "old" })
        assertTrue("应含 +new", diff.any { it.kind == '+' && it.text == "new" })
        assertTrue("应含上下文 b", diff.any { it.kind == ' ' && it.text == "b" })
    }
    check("只改第一行（前缀裁剪不吞掉改动）") {
        val diff = LineDiffLogic.diff("old\nb\nc", "new\nb\nc", context = 1)
        assertTrue("应含 -old", diff.any { it.kind == '-' && it.text == "old" })
        assertTrue("应含 +new", diff.any { it.kind == '+' && it.text == "new" })
    }
    check("超大改动退化为整段替换并明确标注") {
        val old = (1..600).joinToString("\n") { "old$it" }
        val new = (1..600).joinToString("\n") { "new$it" }
        val diff = LineDiffLogic.diff(old, new)
        assertTrue("应有退化提示", diff.any { it.text.contains("未逐行比对") })
        assertTrue("应包含若干 - 与 + 行", diff.any { it.kind == '-' } && diff.any { it.kind == '+' })
    }
    check("输出行数受上限约束") {
        val old = (1..1_000).joinToString("\n") { "old$it" }
        val new = (1..1_000).joinToString("\n") { "new$it" }
        val diff = LineDiffLogic.diff(old, new)
        assertTrue(
            "应 ≤ ${LineDiffLogic.MAX_OUTPUT_LINES}，实际 ${diff.size}",
            diff.size <= LineDiffLogic.MAX_OUTPUT_LINES
        )
    }
    check("统计与摘要：+3 -1") {
        val lines = listOf(
            DiffLine(' ', "ctx"),
            DiffLine('-', "old"),
            DiffLine('+', "n1"),
            DiffLine('+', "n2"),
            DiffLine('+', "n3")
        )
        val (added, removed) = LineDiffLogic.countChanges(lines)
        assertTrue("新增应为 3，实际 $added", added == 3)
        assertTrue("删除应为 1，实际 $removed", removed == 1)
        assertTrue("摘要格式", LineDiffLogic.summary(lines) == "+3  -1")
    }
    check("无差异时摘要为「无差异」") {
        assertTrue("应无差异", LineDiffLogic.summary(emptyList()) == "无差异")
    }
    check("render 前缀正确（unified 风格）") {
        assertTrue("上下文前缀是空格", DiffLine(' ', "x").render() == " x")
        assertTrue("新增前缀是 +", DiffLine('+', "x").render() == "+x")
        assertTrue("删除前缀是 -", DiffLine('-', "x").render() == "-x")
    }

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
