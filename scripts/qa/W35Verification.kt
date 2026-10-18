import com.example.agentkit.core.build.CloneLogic

/**
 * W-35 harness：仓库克隆的条目名安全校验。
 *
 * Zip Slip 是"下载 zip 并解压"这类功能的经典漏洞，所以这里逐条断言：
 * 任何能跳转到目标目录之外的名字都必须被拒绝。
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
    println("== W-35 harness：仓库克隆 ==")

    check("正常条目：剥掉顶层目录后原样返回") {
        assertTrue("应为 src/Main.kt", CloneLogic.safeEntryName("Repo-main/src/Main.kt") == "src/Main.kt")
    }
    check("顶层文件（README.md）也能拿到") {
        assertTrue("应为 README.md", CloneLogic.safeEntryName("Repo-main/README.md") == "README.md")
    }
    check("目录条目保留结尾斜杠（供调用方建目录）") {
        val name = CloneLogic.safeEntryName("Repo-main/src/")
        assertTrue("应为 src/，实际 $name", name == "src/")
        assertTrue("应识别为目录", CloneLogic.isDirectoryEntry(name!!))
    }
    check("Zip Slip：`..` 一律拒绝") {
        assertTrue("应拒绝", CloneLogic.safeEntryName("Repo-main/../../evil.txt") == null)
        assertTrue("应拒绝", CloneLogic.safeEntryName("Repo-main/a/../../../x") == null)
    }
    check("绝对路径拒绝") {
        assertTrue("应拒绝", CloneLogic.safeEntryName("/etc/passwd") == null)
    }
    check("`.git` 目录拒绝（避免把版本库对象铺进工作区）") {
        assertTrue("应拒绝", CloneLogic.safeEntryName("Repo-main/.git/config") == null)
    }
    check("连续斜杠（空段）拒绝") {
        assertTrue("应拒绝", CloneLogic.safeEntryName("Repo-main/src//a.kt") == null)
    }
    check("没有顶层目录（单段名字）→ 拒绝（zip 一定有顶层目录，异常即拒）") {
        assertTrue("应拒绝", CloneLogic.safeEntryName("README.md") == null)
    }
    check("超长路径拒绝") {
        val long = "Repo-main/" + "a".repeat(600)
        assertTrue("应拒绝", CloneLogic.safeEntryName(long) == null)
    }
    check("stripTopDir 正确性") {
        assertTrue("应剥单段", CloneLogic.stripTopDir("Repo-main/x/y.kt") == "x/y.kt")
        assertTrue("无斜杠返回空", CloneLogic.stripTopDir("onlyOne") == "")
    }
    check("目标目录：留空 → repo-<仓库名>") {
        assertTrue("应默认", CloneLogic.safeTargetDir(null, "AgentKit") == "repo-AgentKit")
        assertTrue("空白也默认", CloneLogic.safeTargetDir("   ", "AgentKit") == "repo-AgentKit")
    }
    check("目标目录：清洗危险字符") {
        assertTrue(
            "应清洗，实际 ${CloneLogic.safeTargetDir("a b/c*?.txt", "R")}",
            CloneLogic.safeTargetDir("a b/c*?.txt", "R") == "ab/c.txt"
        )
    }
    check("目标目录：含 .. 回退到默认（不能把仓库解压到工作区外）") {
        assertTrue("应回退", CloneLogic.safeTargetDir("../out", "AgentKit") == "repo-AgentKit")
    }
    check("目标目录：绝对路径回退到默认") {
        assertTrue("应回退", CloneLogic.safeTargetDir("/tmp/x", "AgentKit") == "repo-AgentKit")
    }
    check("上限常量自洽（总量 > 单文件）") {
        assertTrue(
            "总量应大于单文件上限",
            CloneLogic.MAX_TOTAL_BYTES > CloneLogic.MAX_SINGLE_BYTES
        )
        assertTrue("条目上限为正", CloneLogic.MAX_ENTRIES > 0)
    }

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
