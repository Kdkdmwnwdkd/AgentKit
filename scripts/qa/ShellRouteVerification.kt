#!/usr/bin/env kotlin
/**
 * W-57 QA 行为验证：[com.example.agentkit.tools.shouldUsePrivilegedShell] 的真值表。
 *
 * 背景：`shell_run` 原先在 identity 未给时**自动走一次性提权通道**，理由是「uid 2000 能干的活最多」。
 * 但那条通道一条命令一个进程，`cd` / `export` 不跨调用保持——W-54 只是把它写进文案告知模型，
 * W-56 才把 Shizuku 做成**持久会话后端**。两者都到位后，默认就该进池：
 * 「uid 2000」与「cwd 保持」不再是二选一。
 *
 * 本 harness 钉住三件事：
 *  1. 默认（identity 未给）**不再**走一次性通道；
 *  2. identity=shizuku 仍走（它是唯一能拿到 `TerminalIdentity.Shizuku` 语义的路径，
 *     且调用方明确表示「跑完就走、不想留会话」）；
 *  3. 有 session 时不走——会话已绑定某个后端，中途换通道会让 cwd / 环境变量语义断裂。
 *
 * 运行方式见 scripts/qa/run-shell-route-verification.sh。
 */

import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.tools.permissionDeniedHint
import com.example.agentkit.tools.shouldUsePrivilegedShell

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

private fun assertEquals(name: String, expected: Boolean, actual: Boolean) {
    if (expected != actual) throw AssertionError("[$name] expected $expected but was $actual")
}

private fun assertTrue(name: String, actual: Boolean) {
    if (!actual) throw AssertionError("[$name] 期望为 true，实际为 false")
}

fun main() {
    println("=== W-57：shell_run 路由真值表 ===")

    check("identity 未给 → 进会话池（不再自动走一次性）") {
        assertEquals("null", false, shouldUsePrivilegedShell(null, false, true))
    }

    check("identity=shizuku → 走一次性通道（显式要求）") {
        assertEquals("shizuku", true, shouldUsePrivilegedShell("shizuku", false, true))
    }

    check("带 session + identity=shizuku → 不走（会话已绑定后端）") {
        assertEquals("hasSession", false, shouldUsePrivilegedShell("shizuku", true, true))
    }

    check("通道不可用 + identity=shizuku → 不走（交给池报权限不足，不假装成功）") {
        assertEquals("unavailable", false, shouldUsePrivilegedShell("shizuku", false, false))
    }

    check("identity=user → 不进池以外的分支") {
        assertEquals("user", false, shouldUsePrivilegedShell("user", false, true))
    }

    check("identity=su / ssh → 不走一次性通道（首期无后端，由池如实报身份不支持）") {
        assertEquals("su", false, shouldUsePrivilegedShell("su", false, true))
        assertEquals("ssh", false, shouldUsePrivilegedShell("ssh", false, true))
    }

    check("空串按「未给」处理（不进一次性通道）") {
        assertEquals("blank", false, shouldUsePrivilegedShell("", false, true))
    }

    check("大小写敏感：Shizuku != shizuku（线格式恒为小写，不做模糊匹配）") {
        assertEquals("Shizuku", false, shouldUsePrivilegedShell("Shizuku", false, true))
    }

    println("\n=== W-58：permission_denied 的分档指引 ===")

    check("proot 档：先指 prefer_proot=false（容器是 fake root，不是宿主 root）") {
        val h = permissionDeniedHint(TerminalBackend.KEY_PROOT)
        assertTrue("提到 prefer_proot=false", h.contains("prefer_proot=false"))
        assertTrue("解释 fake root", h.contains("fake root"))
        assertTrue("保留 UI 兜底", h.contains("ui_tree"))
    }

    check("user 档：也指 prefer_proot=false（本机 App 身份没有系统权限）") {
        val h = permissionDeniedHint(TerminalBackend.KEY_USER)
        assertTrue("提到 prefer_proot=false", h.contains("prefer_proot=false"))
        assertTrue("保留 UI 兜底", h.contains("ui_tree"))
    }

    check("shizuku 档：**不再**建议 prefer_proot=false（否则死循环）") {
        val h = permissionDeniedHint(TerminalBackend.KEY_SHIZUKU)
        // 已经是 uid 2000，再让它 prefer_proot=false 只会回到同一档——
        // 这是本组最关键的负向断言：以前一律说「改走 ui_*」，现在必须是
        // 「别再重试 + ui_* + 告知用户手动」。
        assertTrue("不应出现 prefer_proot=false", !h.contains("prefer_proot=false"))
        assertTrue("说明已是 ADB 级", h.contains("uid 2000"))
        assertTrue("指向 ui_", h.contains("ui_tree"))
    }

    check("adb 档：与 shizuku 档同结论（都是 uid 2000，被拒=要真 root）") {
        val adb = permissionDeniedHint(TerminalBackend.KEY_ADB)
        val shizuku = permissionDeniedHint(TerminalBackend.KEY_SHIZUKU)
        assertTrue("不应出现 prefer_proot=false", !adb.contains("prefer_proot=false"))
        assertTrue("与 shizuku 档一致", adb == shizuku)
    }

    check("null（会话已消失）：走通用文案，仍给出 prefer_proot=false") {
        val h = permissionDeniedHint(null)
        assertTrue("提到 prefer_proot=false", h.contains("prefer_proot=false"))
        assertTrue("保留 UI 兜底", h.contains("ui_tree"))
    }

    check("三档文案互不相同（防止以后又被合并成一句）") {
        val a = permissionDeniedHint(TerminalBackend.KEY_PROOT)
        val b = permissionDeniedHint(TerminalBackend.KEY_USER)
        val c = permissionDeniedHint(TerminalBackend.KEY_SHIZUKU)
        assertTrue("proot != user", a != b)
        assertTrue("proot != shizuku", a != c)
        assertTrue("user != shizuku", b != c)
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
