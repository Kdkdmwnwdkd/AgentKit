import com.beichen.polaris.core.workspace.PathGuard
import java.io.File
import java.nio.file.Files

/**
 * PathGuard harness：工作区越界校验的行为断言（纯 JVM，真建临时目录）。
 *
 * 这条规则被写错过两次：`canonicalPath.startsWith(root)` 少了分隔符，
 * 于是 `/w/polaris-evil/x` 会被认成 `/w/polaris` 的子路径。字符串前缀不等于
 * 目录前缀——本 harness 用真实文件系统把这句话钉死，包括符号链接逃逸。
 *
 * 为什么不用文本断言：判据是「解析结果」，不是「源码里有没有某个字符串」。
 * 改法可以换、判据不能变，所以断言落在行为上。
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

private fun assertNull(msg: String, f: File?) {
    if (f != null) throw AssertionError("$msg：期望拒绝，实际放行到 ${f.path}")
}

private fun assertNotNull(msg: String, f: File?) {
    if (f == null) throw AssertionError("$msg：期望放行，实际被拒")
}

fun main() {
    println("=== PathGuard：工作区越界校验 ===")

    val root = Files.createTempDirectory("pathguard").toFile().canonicalFile
    File(root, "sub").mkdirs()
    File(root, "sub/a.txt").writeText("x")
    // 与根同前缀的兄弟目录：字符串上 `/tmp/pathguard123-evil` 以 `/tmp/pathguard123` 开头
    val sibling = File(root.parentFile, root.name + "-evil").apply { mkdirs() }
    File(sibling, "x.txt").writeText("x")

    try {
        check("根内相对路径放行") {
            assertNotNull("sub/a.txt", PathGuard.resolveInside(root, "sub/a.txt"))
        }
        check("首尾空白容忍（模型常把路径连空格一起抄进来）") {
            assertNotNull("  sub/a.txt  ", PathGuard.resolveInside(root, "  sub/a.txt  "))
        }
        check("根内多级嵌套放行") {
            File(root, "a/b/c").mkdirs()
            File(root, "a/b/c/d.bin").writeText("x")
            assertNotNull("a/b/c/d.bin", PathGuard.resolveInside(root, "a/b/c/d.bin"))
        }
        check(".. 回退到根外 → 拒绝") {
            assertNull("../outside.txt", PathGuard.resolveInside(root, "../outside.txt"))
            assertNull("sub/../../outside.txt", PathGuard.resolveInside(root, "sub/../../outside.txt"))
        }
        check("绝对路径逃逸 → 拒绝") {
            assertNull("/etc/passwd", PathGuard.resolveInside(root, "/etc/passwd"))
            assertNull(sibling.absolutePath + "/x.txt", PathGuard.resolveInside(root, sibling.absolutePath + "/x.txt"))
        }
        check("同前缀兄弟目录 → 拒绝（少写分隔符的那个 bug）") {
            // 复现旧实现：字符串前缀会认为它在根内；正确实现必须拒绝
            val attack = "../" + sibling.name + "/x.txt"
            assertTrue(
                "前置条件：旧判据确实会放行",
                File(root, attack).canonicalPath.startsWith(root.canonicalPath)
            )
            assertNull(attack, PathGuard.resolveInside(root, attack))
        }
        check("符号链接指向根外 → 拒绝（canonicalPath 会解析链接）") {
            val link = File(root, "escape-link")
            val created = runCatching { Files.createSymbolicLink(link.toPath(), File("/etc").toPath()) }.isSuccess
            if (created) {
                assertNull("escape-link/hostname", PathGuard.resolveInside(root, "escape-link/hostname"))
            }
            // 建不了链接（受限文件系统）时只验前置条件，不判红：链接解析由 canonicalPath 负责，
            // 上面几条已覆盖同一套判定。
        }
        check("空路径 / 点路径 → 拒绝（根目录本身不是文件）") {
            assertNull("空串", PathGuard.resolveInside(root, ""))
            assertNull("点", PathGuard.resolveInside(root, "."))
        }
        check("不存在的路径仍给出 File（存在性由调用方判）") {
            assertNotNull("sub/not-yet.txt", PathGuard.resolveInside(root, "sub/not-yet.txt"))
        }
        check("isInside：根本身不算在内") {
            assertTrue("根自己", !PathGuard.isInside(root, root))
            assertTrue("根内子目录", PathGuard.isInside(root, File(root, "sub")))
            assertTrue("同前缀兄弟", !PathGuard.isInside(root, sibling))
        }
    } finally {
        root.deleteRecursively()
        sibling.deleteRecursively()
    }

    println("\n== 汇总: PASS=$pass FAIL=$fail ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
