/**
 * R12 验证：原子写（AtomicJsonFile）——**真写文件、真读回**，全部在临时目录里跑。
 *
 * 为什么这一项必须真跑而不是看源码：它守的是「断电/进程被杀时不丢数据」，
 * 而这类性质只体现在**文件系统的实际状态**上（tmp 是否残留、bak 是否可回退、
 * 并发下文件是否始终可解析）。用 `src.contains("fsync")` 断言等于什么都没验。
 *
 * 被测的四个历史缺陷：
 *  1. `delete() → renameTo()`：rename 失败即全丢（AgentRunStateStore / BookmarkStore / JobStore）；
 *  2. 无 `fd.sync()`：rename 可能先于数据落盘 → 重启读到 0 字节；
 *  3. 失败分支把 tmp 留在磁盘上；
 *  4. 五份拷贝细节不一致（有的备份、有的不备份、有的删主文件）。
 */
import com.example.agentkit.core.io.AtomicJsonFile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

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
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name: 期望 $expected，实际 $actual")
}

/** 每个用例一个独立临时目录，避免互相污染。 */
private fun tempDir(): File = Files.createTempDirectory("akit-atomic-").toFile()

fun main() {
    println("=== AgentKit R12 验证（原子写）===")

    println("\n[R12] 基本写入与读回")

    check("R12-1 写入后可原样读回") {
        val dir = tempDir()
        val f = File(dir, "a.json")
        assertTrue("write 返回 true", AtomicJsonFile.write(f, """{"k":"v"}"""))
        assertEquals("内容", """{"k":"v"}""", AtomicJsonFile.readOrNull(f))
        assertEquals("无 tmp 残留", 0, dir.listFiles { x -> x.name.contains(".tmp") }?.size ?: 0)
        dir.deleteRecursively()
    }

    check("R12-2 父目录不存在时自动创建") {
        val dir = tempDir()
        val f = File(File(dir, "deep/nested"), "b.json")
        assertTrue("write 返回 true", AtomicJsonFile.write(f, "x"))
        assertTrue("目录已建", f.parentFile.isDirectory)
        assertEquals("内容", "x", AtomicJsonFile.readOrNull(f))
        dir.deleteRecursively()
    }

    check("R12-3 中文与多字节字符按 UTF-8 原样往返") {
        val dir = tempDir()
        val f = File(dir, "c.json")
        val payload = """{"文本":"会话中断，可能已经生效","emoji":"✓"}"""
        AtomicJsonFile.write(f, payload)
        assertEquals("UTF-8 往返", payload, AtomicJsonFile.readOrNull(f))
        dir.deleteRecursively()
    }

    println("\n[R12] 备份与回退")

    check("R12-4 第二次写入把上一版留为 .bak") {
        val dir = tempDir()
        val f = File(dir, "d.json")
        AtomicJsonFile.write(f, "v1")
        AtomicJsonFile.write(f, "v2")
        assertEquals("主文件是新的", "v2", AtomicJsonFile.readOrNull(f))
        assertEquals("备份是旧的", "v1", AtomicJsonFile.readOrNull(AtomicJsonFile.backupOf(f)))
        dir.deleteRecursively()
    }

    check("R12-5 主文件被截断（模拟断电后的空文件）→ 回退 .bak") {
        val dir = tempDir()
        val f = File(dir, "e.json")
        AtomicJsonFile.write(f, """{"ok":1}""")
        AtomicJsonFile.write(f, """{"ok":2}""")
        f.writeText("")   // 模拟「rename 生效但内容没落盘」
        val got = AtomicJsonFile.readWithBackupOrNull(f) { it.isNotBlank() }
        assertEquals("回退到上一版", """{"ok":1}""", got)
        dir.deleteRecursively()
    }

    check("R12-6 主文件是半截 JSON → 回退 .bak（isValid 判定）") {
        val dir = tempDir()
        val f = File(dir, "f.json")
        AtomicJsonFile.write(f, """{"a":1}""")
        AtomicJsonFile.write(f, """{"a":2}""")
        f.writeText("""{"a":""")   // 半截
        val got = AtomicJsonFile.readWithBackupOrNull(f) { s -> s.trim().endsWith("}") }
        assertEquals("回退到完整版", """{"a":1}""", got)
        dir.deleteRecursively()
    }

    check("R12-7 主文件不存在但 .bak 在 → 用 .bak") {
        val dir = tempDir()
        val f = File(dir, "g.json")
        AtomicJsonFile.write(f, "only")
        AtomicJsonFile.write(f, "second")
        f.delete()
        val got = AtomicJsonFile.readWithBackupOrNull(f) { it.isNotBlank() }
        assertEquals("用备份", "only", got)
        dir.deleteRecursively()
    }

    check("R12-8 主文件与备份都不可用 → null（不抛异常）") {
        val dir = tempDir()
        val f = File(dir, "h.json")
        assertEquals("空目录读 null", null, AtomicJsonFile.readWithBackupOrNull(f) { it.isNotBlank() })
        dir.deleteRecursively()
    }

    check("R12-9 keepBackup=false 不产生 .bak") {
        val dir = tempDir()
        val f = File(dir, "i.json")
        AtomicJsonFile.write(f, "v1", keepBackup = false)
        AtomicJsonFile.write(f, "v2", keepBackup = false)
        assertTrue(".bak 不存在", !AtomicJsonFile.backupOf(f).exists())
        assertEquals("主文件", "v2", AtomicJsonFile.readOrNull(f))
        dir.deleteRecursively()
    }

    println("\n[R12] 临时文件生命周期")

    check("R12-10 自定义 tmpSuffix 生效且成功后不残留") {
        val dir = tempDir()
        val f = File(dir, "j.txt")
        AtomicJsonFile.write(f, "text", keepBackup = false, tmpSuffix = ".akitmp")
        assertEquals("内容", "text", AtomicJsonFile.readOrNull(f))
        assertEquals("无 .akitmp 残留", 0, dir.listFiles { x -> x.name.endsWith(".akitmp") }?.size ?: 0)
        dir.deleteRecursively()
    }

    check("R12-11 目标路径被非空目录占用时失败但不抛异常、不残留 tmp") {
        val dir = tempDir()
        val blocked = File(dir, "k.json")
        blocked.mkdirs()
        File(blocked, "occupied.txt").writeText("x")   // 非空目录 → rename 必失败
        var threw = false
        val ok = try {
            AtomicJsonFile.write(blocked, "payload")
        } catch (t: Throwable) {
            threw = true
            false
        }
        assertTrue("不抛异常", !threw)
        assertTrue("返回 false", !ok)
        assertEquals("无 tmp 残留", 0, dir.listFiles { x -> x.name.endsWith(".tmp") }?.size ?: 0)
        dir.deleteRecursively()
    }

    println("\n[R12] 并发：多线程写同一文件")

    check("R12-12 10 线程各写 20 次，文件始终是某一次完整写入（绝无半截）") {
        val dir = tempDir()
        val f = File(dir, "concurrent.json")
        // 每一版长度不同，便于发现「两个线程的字节混在一起」
        fun payload(i: Int) = """{"v":${i},"pad":"${"x".repeat(i % 37)}"}"""

        val threads = 10
        val rounds = 20
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        repeat(threads) { t ->
            Thread {
                try {
                    start.await()
                    repeat(rounds) { r ->
                        AtomicJsonFile.write(f, payload(t * 100 + r), keepBackup = false)
                        // 任意时刻读一次，必须是完整 JSON
                        val now = f.readText()
                        if (!now.startsWith("{") || !now.endsWith("}")) errors.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.await()

        assertEquals("并发期间读到半截的次数", 0, errors.get())
        val fin = f.readText()
        assertTrue("终态是完整 JSON：$fin", fin.startsWith("{") && fin.endsWith("}"))
        assertTrue("终态含 v 字段", fin.contains("\"v\":"))
        dir.deleteRecursively()
    }

    println("\n[R12] 长期运行：反复写入不膨胀")

    check("R12-13 连续 200 次写入后，目录里只有主文件与 1 个 .bak") {
        val dir = tempDir()
        val f = File(dir, "o.json")
        repeat(200) { i -> AtomicJsonFile.write(f, """{"i":$i}""") }
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals("文件集合", listOf("o.json", "o.json.bak"), names)
        assertEquals("主文件是最后一版", """{"i":199}""", AtomicJsonFile.readOrNull(f))
        assertEquals("备份是倒数第二版", """{"i":198}""", AtomicJsonFile.readOrNull(AtomicJsonFile.backupOf(f)))
        dir.deleteRecursively()
    }

    println()
    if (failures.isEmpty()) {
        println("R12 结果：$passed 通过 / 0 失败")
    } else {
        println("R12 结果：$passed 通过 / ${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
