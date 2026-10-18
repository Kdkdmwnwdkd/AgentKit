import com.example.agentkit.core.build.FileChange
import com.example.agentkit.core.build.GitHubGitLogic

/**
 * W-32 harness：GitHub 提交的纯逻辑。
 *
 * 重点测 **blob sha 是否与 git 一致**——这是整棵树能不能对齐的地基：
 * 算错了会导致「明明没改也被判成改了」，每次提交都把全部文件重传一遍。
 * 参考值由 `git hash-object` 的公式独立算出（sha1("blob {len}\0" + bytes)）。
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
    println("== W-32 harness：GitHub 提交 ==")

    // ——————————————— blob sha（与 git 对齐）———————————————
    check("空文件的 blob sha = 公认值 e69de29b…") {
        val sha = GitHubGitLogic.blobSha(ByteArray(0))
        assertTrue("实际 $sha", sha == "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")
    }
    check("`hello\\n` 的 blob sha = ce013625…") {
        val sha = GitHubGitLogic.blobSha("hello\n".toByteArray())
        assertTrue("实际 $sha", sha == "ce013625030ba8dba906f756967f9e9ca394464a")
    }
    check("`hello`（无换行）与 `hello\\n` 不同 sha") {
        val a = GitHubGitLogic.blobSha("hello".toByteArray())
        val b = GitHubGitLogic.blobSha("hello\n".toByteArray())
        assertTrue("两者不应相等", a != b)
        assertTrue("无换行版本 = b6fc4c62…", a == "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0")
    }
    check("中文内容按 UTF-8 字节计算（长度是字节数不是字符数）") {
        val sha = GitHubGitLogic.blobSha("你好".toByteArray(Charsets.UTF_8))
        assertTrue("实际 $sha", sha == "f3ca6104089db6e23891a35bfa058d287281c476")
    }

    // ——————————————— 路径规范化 ———————————————
    check("正常路径原样通过") {
        assertTrue("应通过", GitHubGitLogic.normalizePath("src/main/App.kt") == "src/main/App.kt")
    }
    check("前导斜杠被去掉（绝对路径转相对）") {
        assertTrue("应去斜杠", GitHubGitLogic.normalizePath("/src/App.kt") == "src/App.kt")
    }
    check("反斜杠统一成斜杠") {
        assertTrue("应转换", GitHubGitLogic.normalizePath("src\\App.kt") == "src/App.kt")
    }
    check("含 .. 的路径被拒绝（防穿越）") {
        assertTrue("应拒绝", GitHubGitLogic.normalizePath("../secret.txt") == null)
        assertTrue("应拒绝", GitHubGitLogic.normalizePath("src/../../x") == null)
    }
    check("空路径/空白被拒绝") {
        assertTrue("应拒绝", GitHubGitLogic.normalizePath("   ") == null)
        assertTrue("应拒绝", GitHubGitLogic.normalizePath("/") == null)
    }
    check("连续斜杠（空段）被拒绝") {
        assertTrue("应拒绝", GitHubGitLogic.normalizePath("src//App.kt") == null)
    }

    // ——————————————— 差异比对 ———————————————
    check("新增文件被识别（远端没有）") {
        val local = mapOf("new.txt" to "abc".toByteArray())
        val changes = GitHubGitLogic.diff(local, emptyMap())
        assertTrue("应有 1 个变更", changes.size == 1)
        assertTrue("状态应为 added", changes[0].status == "added")
        assertTrue("remoteSha 应为 null", changes[0].remoteSha == null)
    }
    check("内容相同 → 无变更（靠 sha 比对，不靠 mtime）") {
        val bytes = "abc".toByteArray()
        val sha = GitHubGitLogic.blobSha(bytes)
        val changes = GitHubGitLogic.diff(mapOf("a.txt" to bytes), mapOf("a.txt" to sha))
        assertTrue("不应有变更", changes.isEmpty())
    }
    check("内容不同 → modified，且带远端 sha") {
        val bytes = "new".toByteArray()
        val changes = GitHubGitLogic.diff(
            mapOf("a.txt" to bytes),
            mapOf("a.txt" to GitHubGitLogic.blobSha("old".toByteArray()))
        )
        assertTrue("应有 1 个", changes.size == 1)
        assertTrue("状态 modified", changes[0].status == "modified")
        assertTrue("应带远端 sha", changes[0].remoteSha != null)
    }
    check("结果按路径排序（稳定输出）") {
        val local = mapOf(
            "z.txt" to "1".toByteArray(),
            "a.txt" to "2".toByteArray(),
            "m.txt" to "3".toByteArray()
        )
        val changes = GitHubGitLogic.diff(local, emptyMap())
        assertTrue("应按字典序", changes.map { it.path } == listOf("a.txt", "m.txt", "z.txt"))
    }
    check("非法路径被跳过而不是让整次提交失败") {
        val local = mapOf("../evil.txt" to "x".toByteArray(), "ok.txt" to "y".toByteArray())
        val changes = GitHubGitLogic.diff(local, emptyMap())
        assertTrue("只应有 ok.txt", changes.size == 1 && changes[0].path == "ok.txt")
    }

    // ——————————————— 提交消息 ———————————————
    check("空消息 → 用兜底文案") {
        val msg = GitHubGitLogic.normalizeMessage("   ")
        assertTrue("应有兜底，实际 $msg", msg.isNotEmpty() && !msg.startsWith(" "))
    }
    check("首行超 72 字被截断并加省略号") {
        val msg = GitHubGitLogic.normalizeMessage("x".repeat(100))
        val subject = msg.lines().first()
        assertTrue("长度应 ≤72，实际 ${subject.length}", subject.length <= 72)
        assertTrue("应有省略号", subject.endsWith("…"))
    }
    check("正文保留，标题与正文之间空一行") {
        val msg = GitHubGitLogic.normalizeMessage("feat: 加登录\n\n- 表单\n- 校验")
        assertTrue("应含空行分隔", msg.contains("feat: 加登录\n\n- 表单"))
    }
    check("首尾空白被清掉") {
        val msg = GitHubGitLogic.normalizeMessage("\n\n  fix: 修 bug  \n\n")
        assertTrue("应清理，实际 [$msg]", msg == "fix: 修 bug")
    }

    // ——————————————— 摘要与可提交性 ———————————————
    check("摘要用 + / ~ 区分新增与修改") {
        val text = GitHubGitLogic.summarizeChanges(
            listOf(
                FileChange("a.txt", "s1", null),
                FileChange("b.txt", "s2", "old")
            )
        )
        assertTrue("新增前缀", text.contains("+ a.txt"))
        assertTrue("修改前缀", text.contains("~ b.txt"))
    }
    check("摘要超 20 条折叠成「还有 N 个」") {
        val changes = (1..25).map { FileChange("f$it.txt", "s", null) }
        val text = GitHubGitLogic.summarizeChanges(changes)
        assertTrue("应有折叠提示", text.contains("还有 5 个文件"))
    }
    check("空摘要不返回空串") {
        assertTrue("应有占位", GitHubGitLogic.summarizeChanges(emptyList()).contains("没有变更"))
    }
    check("超过 2MB 的文件不可提交") {
        val big = ByteArray(GitHubGitLogic.MAX_FILE_BYTES + 1)
        assertTrue("应拒绝", !GitHubGitLogic.isCommittable("big.bin", big))
        assertTrue("小文件应通过", GitHubGitLogic.isCommittable("a.txt", "x".toByteArray()))
    }
    check("非法路径即使内容很小也不可提交") {
        assertTrue("应拒绝", !GitHubGitLogic.isCommittable("../x", "x".toByteArray()))
    }

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
