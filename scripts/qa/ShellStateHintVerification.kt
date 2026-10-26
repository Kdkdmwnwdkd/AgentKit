#!/usr/bin/env kotlin
/**
 * W-54 QA 行为验证：[com.example.agentkit.tools.mutatesShellState]。
 *
 * 背景：`shell_run` 在 Shizuku 就绪时默认走**一次性通道**（一条命令一个远端进程）。
 * 这条通道里 `cd /tmp` 之后下一条命令仍在默认目录——但工具描述一直写着「复用同一
 * session 可保持工作目录」，模型因此以为自己已经切好了目录，后续命令全跑错位置，
 * 还以为是路径写错。
 *
 * 本 harness 只测纯函数（零 Android 依赖，JVM 直跑）：判定「这条命令有没有改
 * shell 状态」，决定要不要在返回文案里补那句「本次不保留」。**宁可多提示一句，
 * 也不能让模型误以为 cwd 变了**；但普通命令（ls/cat/pm）不能加，否则每条都啰嗦。
 *
 * 运行方式见 scripts/qa/run-shell-state-hint-verification.sh。
 */

import com.example.agentkit.tools.mutatesShellState

/* ------------------------------ 迷你断言框架 ------------------------------ */

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
    println("=== 一次性通道：状态变更检测验证 ===")

    check("cd 到目录 → 需要提示") {
        assertEquals("cd /data/local/tmp", true, mutatesShellState("cd /data/local/tmp"))
    }

    check("单独的 cd（回 home）→ 需要提示") {
        assertEquals("cd", true, mutatesShellState("cd"))
    }

    check("export → 需要提示") {
        assertEquals("export FOO=bar", true, mutatesShellState("export FOO=bar"))
    }

    check("变量赋值前缀 → 需要提示") {
        assertEquals("FOO=bar cmd", true, mutatesShellState("FOO=bar cmd"))
        assertEquals("FOO=bar", true, mutatesShellState("FOO=bar"))
    }

    check("组合命令里带 cd → 需要提示") {
        assertEquals("a && cd b", true, mutatesShellState("ls /sdcard && cd /sdcard/Download"))
        assertEquals("; cd", true, mutatesShellState("echo hi; cd /tmp"))
    }

    check("普通命令 → 不加提示（否则每条都啰嗦）") {
        assertEquals("ls -la", false, mutatesShellState("ls -la"))
        assertEquals("cat file", false, mutatesShellState("cat /sdcard/a.txt"))
        assertEquals("pm list", false, mutatesShellState("pm list packages"))
        assertEquals("echo cd 文本", false, mutatesShellState("echo cd"))
        assertEquals("echo A=1", false, mutatesShellState("echo A=1"))
    }

    check("空命令 → 不加提示") {
        assertEquals("empty", false, mutatesShellState(""))
        assertEquals("blank", false, mutatesShellState("   "))
    }

    check("大小写：CD 不是 shell 命令（区分大小写，避免把 echo CD 当状态变更）") {
        assertEquals("CD", false, mutatesShellState("CD /tmp"))
    }

    check("`cd` 出现在参数位置不算（如 find -name cd）") {
        assertEquals("find -name cd", false, mutatesShellState("find / -name cd"))
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
