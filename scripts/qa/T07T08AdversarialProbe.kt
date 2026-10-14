#!/usr/bin/env kotlin
/**
 * Phase 1 · T07/T08 + 应用操控补口 · QA 对抗性补充探针（Edward/严过关）。
 *
 * 目的：补足既有 harness 未覆盖 / 只覆盖 happy path 的硬点：
 *   A. T09/T10 回归：ProotRunner.buildInvocation 的 argv/env 完整性 + 「位置参数传命令」
 *      （命令作为 $2 原样传入，不拼进脚本字符串）。
 *   C④. user/proot 分池：**同一别名**字符串在两后端键下各占独立槽位，互不串味。
 *   C③. 每会话 Mutex 的**时间证明**：跨会话并行（≈1×）、同会话串行（≈2×）。
 *   C②. 身份冲突：拒绝后既有会话不受影响、仍可正常执行。
 *   C⑤. 复用不泄漏：同别名同身份复用会**关闭刚建的多余后端**（存活后端恒为 1 个）。
 *   C①. 句柄：默认生成器大批量仍恒 4-hex 且唯一。
 *   C⑥. 截断器：=max-1/=max/=max+1 边界 + ASCII/emoji 混排 1..200 全程 utf8 稳定。
 *   E.  只读标注：shell_run / shell_close 均 readOnly=false；identity=su 显式不可用。
 *
 * 运行：scripts/qa/run-t07t08-adversarial.sh
 */

import com.example.agentkit.core.container.ProotRunner
import com.example.agentkit.core.terminal.TERMINAL_HANDLE_REGEX
import com.example.agentkit.core.terminal.TerminalSessionPool
import com.example.agentkit.core.terminal.ToolOutputTruncator
import com.example.agentkit.core.terminal.backend.LocalUserBackend
import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.core.terminal.backend.TerminalBackendSource
import com.example.agentkit.core.terminal.backend.TerminalIdentity
import com.example.agentkit.tools.ShellCloseTool
import com.example.agentkit.tools.ShellRunTool
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private suspend fun check(name: String, block: suspend () -> Unit) {
    try {
        block(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name -> ${t.message}"; println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("[$name] expected <$needle> in <${haystack?.take(160)}>")
    }
}

private fun json(vararg kv: Pair<String, String>): JsonObject =
    JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

/* ------------------------------ 极简假后端 ------------------------------ */

private class FakeEcho(
    override val backendKey: String,
    override val identity: TerminalIdentity = TerminalIdentity.User
) : TerminalBackend {
    private val ch = Channel<String>(Channel.UNLIMITED)
    override val output: Flow<String> = ch.receiveAsFlow()
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile var forceDead = false
    private var lastExit = 0

    override val isAlive: Boolean get() = started && !closed && !forceDead

    override suspend fun start(): Boolean { started = true; return true }

    override suspend fun send(input: String): Boolean {
        if (!isAlive) return false
        val line = input.trimEnd('\n', '\r')
        val i = line.indexOf(TerminalSessionPool.SENTINEL_MARKER)
        if (i >= 0 && line.trimStart().startsWith("echo")) {
            val token = line.substring(i + TerminalSessionPool.SENTINEL_MARKER.length).substringBefore("__")
            ch.send("${TerminalSessionPool.SENTINEL_MARKER}${token}__$lastExit")
            return true
        }
        lastExit = 0
        ch.send("OUT:$line")
        return true
    }

    override suspend fun close() { closed = true; started = false; ch.close() }
    override suspend fun awaitExit(): Int = lastExit
}

/* ================================================================== */
/* A. T09/T10 回归：ProotRunner argv/env 完整性 + 位置参数传命令          */
/* ================================================================== */

private suspend fun testProotInvocationRegression() {
    println("\n== A. T09/T10 回归：buildInvocation argv/env + 位置参数命令 ==")

    val tmp = Files.createTempDirectory("agentkit-probe-proot").toFile()
    val proot = File(tmp, "bin/proot").apply { parentFile.mkdirs(); writeText("stub") }
    val rootfs = File(tmp, "rootfs").apply { mkdirs() }

    check("argv[0]==proot 自身；末位==guest shell；env 关键项齐全") {
        val inv = ProotRunner.buildInvocation(proot, rootfs, ProotRunner.GUEST_HOME, "com.example.agentkit")
        assertEquals("argv0", proot.absolutePath, inv.argv.first())
        assertEquals("argv last = guest shell", ProotRunner.GUEST_SHELL, inv.argv.last())
        val env = inv.envMap
        assertEquals("HOME", "/root", env["HOME"])
        assertEquals("TMPDIR(guest)", "/tmp", env["TMPDIR"])
        assertEquals("CI", "true", env["CI"])
        assertEquals("NO_COLOR", "1", env["NO_COLOR"])
        assertEquals("PAGER", "cat", env["PAGER"])
        assertEquals("GIT_CONFIG_GLOBAL", ProotRunner.GIT_CONFIG_GLOBAL, env["GIT_CONFIG_GLOBAL"])
        assertTrue("PATH 含 /usr/local/bin", env["PATH"]!!.contains("/usr/local/bin"))
        assertEquals("LD_LIBRARY_PATH=三件套目录", proot.parentFile.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("PROOT_TMP_DIR", File(rootfs, "tmp").absolutePath, env["PROOT_TMP_DIR"])
        // envArray = 宿主 env ∪ 自定义 env（并集，用 forkpty/execve）
        assertTrue("envArray 为宿主+自定义并集 (size=${inv.envArray.size})", inv.envArray.size >= env.size)
        assertTrue("envArray 含 CI=true", inv.envArray.any { it == "CI=true" })
    }

    check("位置参数传命令：命令作为 $2 原样传入，脚本不含命令文本") {
        val cmd = "echo \"*.vue\" && cd /x; rm -rf \$HOME"
        val argv = ProotRunner.buildCommandArgv("/root", cmd)
        assertEquals("argv 长度 7", 7, argv.size)
        assertEquals("$0 标识位", "agentkit", argv[4])
        assertEquals("$1=cwd", "/root", argv[5])
        assertEquals("$2=命令原文", cmd, argv[6])
        assertEquals("脚本=位置参数形式", "cd -- \"\$1\" && eval \"\$2\"", argv[3])
        assertTrue("脚本不含命令文本（不拼串）", !argv[3].contains("*.vue"))
    }

    check("toCommandLine：argv 逐项单引号强引用，命令按字面保留") {
        val cmd = "echo \"*.vue\""
        val line = ProotRunner.toCommandLine("/root", cmd)
        assertContains("含命令字面量", line, cmd)
        assertContains("含位置参数脚本", line, "cd --")
        assertContains("cwd 被引用", line, "'/root'")
    }
}

/* ================================================================== */
/* C④. user / proot 分池：同别名两池独立                                */
/* ================================================================== */

private suspend fun testCrossPoolIsolation() {
    println("\n== C④. user/proot 分池：同一别名两池各占独立槽位 ==")

    val source = TerminalBackendSource { identity, preferProot ->
        if (identity != TerminalIdentity.User) null
        else if (preferProot) FakeEcho(TerminalBackend.KEY_PROOT) else FakeEcho(TerminalBackend.KEY_USER)
    }
    val pool = TerminalSessionPool(source)

    check("同别名 'shared' 在 proot 与 user 下 → 两个不同句柄、均存活") {
        val p = pool.open(TerminalIdentity.User, "shared", preferProot = true)
        val u = pool.open(TerminalIdentity.User, "shared", preferProot = false)
        assertTrue("proot ok", p.ok); assertTrue("user ok", u.ok)
        assertEquals("proot backendKey", TerminalBackend.KEY_PROOT, p.backendKey)
        assertEquals("user backendKey", TerminalBackend.KEY_USER, u.backendKey)
        assertTrue("两个不同句柄", p.handle != u.handle)
        assertEquals("list=2", 2, pool.list().size)
        // 命令各自归属正确
        assertEquals("proot out", "OUT:pa", pool.run(p.handle!!, "pa").output)
        assertEquals("user out", "OUT:ua", pool.run(u.handle!!, "ua").output)
    }

    check("关闭 user 会话不影响 proot 会话（分池不串味）") {
        val prootHandle = pool.list().first { it.backendKey == TerminalBackend.KEY_PROOT }.handle
        val userHandle = pool.list().first { it.backendKey == TerminalBackend.KEY_USER }.handle
        assertTrue("close user", pool.close(userHandle))
        assertEquals("user gone", null, pool.lookup(userHandle))
        assertTrue("proot 仍在", pool.lookup(prootHandle)?.alive == true)
        assertTrue("proot 仍可跑", pool.run(prootHandle, "still").ok)
    }

    pool.closeAll()
}

/* ================================================================== */
/* C②. 身份冲突后既有会话不受影响                                         */
/* ================================================================== */

private suspend fun testConflictDoesNotDamage() {
    println("\n== C②. 身份冲突拒绝后，既有会话仍完好 ==")

    // 宽松 source：对 Su 也给一个后端（用于隔离验证池层身份冲突）
    val source = TerminalBackendSource { identity, _ ->
        when (identity) {
            TerminalIdentity.User -> FakeEcho(TerminalBackend.KEY_USER, TerminalIdentity.User)
            TerminalIdentity.Su -> FakeEcho(TerminalBackend.KEY_USER, TerminalIdentity.Su)
            else -> null
        }
    }
    val pool = TerminalSessionPool(source)
    val h = pool.open(TerminalIdentity.User, "s", preferProot = false).handle!!

    check("冲突 open 报错，且原会话句柄仍可正常执行") {
        val conflict = pool.open(TerminalIdentity.Su, "s", preferProot = false)
        assertTrue("conflict not ok", !conflict.ok)
        assertEquals("code", "identity_conflict", conflict.errorCode)
        assertTrue("原句柄仍在", pool.lookup(h)?.alive == true)
        assertEquals("原会话可用", "OUT:ok", pool.run(h, "ok").output)
    }
    pool.closeAll()
}

/* ================================================================== */
/* C⑤. 复用不泄漏：只保留 1 个存活后端                                   */
/* ================================================================== */

private suspend fun testReuseNoLeak() {
    println("\n== C⑤. 同别名同身份复用：关闭多余后端，存活数恒为 1 ==")

    val created = mutableListOf<FakeEcho>()
    val source = TerminalBackendSource { identity, _ ->
        if (identity == TerminalIdentity.User) FakeEcho(TerminalBackend.KEY_USER).also { created += it } else null
    }
    val pool = TerminalSessionPool(source)

    check("连续两次开辟同别名同身份 → reused=true 且多余后端被关闭") {
        val r1 = pool.open(TerminalIdentity.User, "dup", preferProot = false)
        val r2 = pool.open(TerminalIdentity.User, "dup", preferProot = false)
        assertTrue("r1 ok", r1.ok); assertTrue("r2 reused", r2.reused)
        assertEquals("同句柄", r1.handle, r2.handle)
        assertEquals("source 造了 2 个后端", 2, created.size)
        assertEquals("存活后端恒为 1（多余的被 close）", 1, created.count { it.isAlive })
        assertEquals("list=1", 1, pool.list().size)
    }
    pool.closeAll()
    check("closeAll 后所有后端均已关闭") {
        assertEquals("0 alive", 0, created.count { it.isAlive })
    }
}

/* ================================================================== */
/* C①. 句柄：默认生成器大批量恒 4-hex 且唯一                             */
/* ================================================================== */

private suspend fun testHandleBatchUnique() {
    println("\n== C①. 默认生成器 200 连开：恒 4-hex 且唯一 ==")

    val source = TerminalBackendSource { identity, _ ->
        if (identity == TerminalIdentity.User) FakeEcho(TerminalBackend.KEY_USER) else null
    }
    val pool = TerminalSessionPool(source)
    val handles = (1..200).map { pool.open(TerminalIdentity.User, "h$it", preferProot = false).handle!! }

    check("200 个句柄全部匹配 ^[0-9a-f]{4}$ 且互不重复") {
        handles.forEach { assertTrue("match $it", it.matches(TERMINAL_HANDLE_REGEX)) }
        assertEquals("unique", 200, handles.toSet().size)
    }
    pool.closeAll()
}

/* ================================================================== */
/* C③. 每会话 Mutex 的时间证明                                          */
/* ================================================================== */

private suspend fun testMutexTiming() {
    println("\n== C③. 每会话 Mutex：跨会话并行（≈1×）/ 同会话串行（≈2×）==")

    val tmp = Files.createTempDirectory("agentkit-probe-timing").toFile()
    val source = TerminalBackendSource { identity, _ ->
        if (identity == TerminalIdentity.User) LocalUserBackend("/bin/sh", tmp) else null
    }
    val pool = TerminalSessionPool(source, commandTimeoutMs = 20_000)

    check("跨会话：两条 sleep 1 并行，总耗时 < 1.8s（不互相阻塞）") {
        val hA = pool.open(TerminalIdentity.User, "A", preferProot = false).handle!!
        val hB = pool.open(TerminalIdentity.User, "B", preferProot = false).handle!!
        val t0 = System.currentTimeMillis()
        coroutineScope {
            val a = async { pool.run(hA, "sleep 1") }
            val b = async { pool.run(hB, "sleep 1") }
            assertTrue("A ok", a.await().ok); assertTrue("B ok", b.await().ok)
        }
        val dt = System.currentTimeMillis() - t0
        println("     cross-session elapsed=${dt}ms")
        assertTrue("并行 (dt=$dt < 1800)", dt < 1800)
    }

    check("同会话：两条 sleep 1 串行，总耗时 ≥ 1.8s（每会话一把锁）") {
        val h = pool.open(TerminalIdentity.User, "S", preferProot = false).handle!!
        val t0 = System.currentTimeMillis()
        coroutineScope {
            val a = async { pool.run(h, "sleep 1") }
            val b = async { pool.run(h, "sleep 1") }
            assertTrue("r1 ok", a.await().ok); assertTrue("r2 ok", b.await().ok)
        }
        val dt = System.currentTimeMillis() - t0
        println("     same-session elapsed=${dt}ms")
        assertTrue("串行 (dt=$dt ≥ 1800)", dt >= 1800)
        assertTrue("上界 (dt=$dt < 4500)", dt < 4500)
    }

    pool.closeAll()
}

/* ================================================================== */
/* C⑥. 截断器边界（含 ASCII/emoji 混排全程 utf8 稳定）                    */
/* ================================================================== */

private fun utf8Stable(s: String): Boolean =
    String(s.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == s

private suspend fun testTruncatorEdges() {
    println("\n== C⑥. ToolOutputTruncator 边界（=max-1 / =max / =max+1 / 混排）==")

    check("=max-1 与 =max 均不截断，原文返回") {
        val t = ToolOutputTruncator(100)
        assertEquals("max-1 not truncated", false, t.truncate("x".repeat(99)).truncated)
        val r = t.truncate("x".repeat(100))
        assertEquals("max not truncated", false, r.truncated)
        assertEquals("max original", "x".repeat(100), r.text)
    }

    check("=max+1 截断，长度 ≤ max，且保留头尾") {
        val t = ToolOutputTruncator(100)
        val r = t.truncate("H" + "m".repeat(99) + "T")
        assertTrue("truncated", r.truncated)
        assertTrue("len ≤ max (${r.text.length})", r.text.length <= 100)
        assertContains("head", r.text, "H")
        assertContains("tail", r.text, "T")
    }

    check("ASCII+emoji 混排：max 1..200 全程 len ≤ max 且 utf8 稳定（无孤立代理）") {
        val mixed = buildString { repeat(60) { append("ab😀中"); } } // 60*(2+2+1)=300 码元
        for (max in 1..200) {
            val r = ToolOutputTruncator(max).truncate(mixed)
            assertTrue("len ≤ $max (${r.text.length})", r.text.length <= max)
            assertTrue("utf8 stable at max=$max", utf8Stable(r.text))
        }
    }

    check("构造『切点恰在代理对中间』：不产生孤立代理") {
        // 用 ASCII 前缀把 emoji 的代理对起点推到各种位置
        for (prefix in 0..7) {
            val text = "a".repeat(prefix) + "😀".repeat(80)
            for (max in 10..120) {
                val r = ToolOutputTruncator(max).truncate(text)
                assertTrue("stable p=$prefix max=$max", utf8Stable(r.text))
            }
        }
    }
}

/* ================================================================== */
/* E. 只读标注 + 未实现身份显式不可用                                     */
/* ================================================================== */

private suspend fun testReadOnlyAndUnsupportedIdentity() {
    println("\n== E. readOnly 标注 + identity=su 显式不可用 ==")

    val source = TerminalBackendSource { identity, _ ->
        if (identity == TerminalIdentity.User) FakeEcho(TerminalBackend.KEY_USER) else null
    }
    val pool = TerminalSessionPool(source)
    val run = ShellRunTool(pool)
    val close = ShellCloseTool(pool)

    check("shell_run / shell_close 均 readOnly=false（进串行批）") {
        assertEquals("shell_run readOnly", false, run.readOnly)
        assertEquals("shell_close readOnly", false, close.readOnly)
    }

    check("identity=su（首期未实现）经 shell_run → identity_unsupported") {
        val r = run.execute(json("command" to "pwd", "identity" to "su"))
        assertTrue("not ok", !r.ok)
        assertEquals("code", "identity_unsupported", r.code)
    }

    pool.closeAll()
}

/* ================================================================== */

fun main() = runBlocking {
    println("=== Phase1 · T07/T08 + 应用操控补口 · QA 对抗性补充探针（Edward）===")
    testProotInvocationRegression()
    testCrossPoolIsolation()
    testConflictDoesNotDamage()
    testReuseNoLeak()
    testHandleBatchUnique()
    testMutexTiming()
    testTruncatorEdges()
    testReadOnlyAndUnsupportedIdentity()

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
