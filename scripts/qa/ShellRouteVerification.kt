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

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
