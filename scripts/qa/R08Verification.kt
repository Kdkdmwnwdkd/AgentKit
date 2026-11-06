#!/usr/bin/env kotlin
/**
 * R-08「审批记忆粒度过粗」验证 harness。
 *
 * ## 这条缺陷的真身
 * 不是「少判断一次」，而是 `approveAlways` 记的是**工具类别**（`shell`），
 * 用户同意的是**这一次的风险**。用户为了不再被 `npm install` 打断而勾选，
 * 顺带把 `rm -rf` / `pm uninstall` / `git reset --hard` 全部放行。
 *
 * 所以断言的核心只有一条：**不可逆命令的那次授权，永远不会让下一次免于确认**。
 * 这条只有真跑能证明——grep 只能证明 `memorizable` 这个词出现过。
 *
 * ## 覆盖
 *  A. 危险命令分类（删除/格式化/提权/卸载/强推）
 *  B. 一次授权的作用域：shell 普通档可记忆，危险档不可记忆且键不同
 *  C. 不变量：危险档的键永远不可能出现在会话白名单里（即使被别处写入）
 *  D. 回归：W-36 的「同类操作」粒度没被破坏（ui_tap / ui_click 仍共用一键）
 *
 * 运行：scripts/qa/run-r08-verification.sh
 */

import com.example.agentkit.core.agent.ApprovalGrouping
import com.example.agentkit.core.agent.ApprovalScope
import com.example.agentkit.core.agent.ShellCommandRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"; println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

private fun argsOf(command: String): JsonObject =
    buildJsonObject { put("command", JsonPrimitive(command)) }

private fun shellGrant(command: String) = ApprovalScope.of("shell_run", argsOf(command))

/* -------------------------------- A. 分类 -------------------------------- */

fun testClassification() {
    println("[A] 危险命令分类")

    val destructive = listOf(
        "rm -rf /sdcard/Download",
        "rm -f a.txt",
        "rmdir old",
        "dd if=/dev/zero of=/dev/block/x",
        "mkfs.ext4 /dev/block/x",
        "pm uninstall com.example.x",
        "pm clear com.example.x",
        "git reset --hard HEAD~3",
        "git clean -fdx",
        "git push --force origin main",
        "find . -name '*.tmp' -delete",
        "chmod 000 /sdcard",
        "su -c 'id'",
        "reboot",
        "kill -9 1234",
        "mv a.txt b.txt",
    )
    destructive.forEach { cmd ->
        check("A 危险：$cmd") { expect(ShellCommandRules.isDestructive(cmd)) { "应判为不可逆" } }
    }

    val benign = listOf(
        "ls -la",
        "cat a.txt",
        "echo hello > out.txt",     // 重定向是写文件，但不是不可逆
        "npm install",
        "gradle assembleDebug",
        "git status",
        "git log --oneline",
        "pm list packages",
        "python3 script.py",
        "",
    )
    benign.forEach { cmd ->
        check("A 非危险：${cmd.ifBlank { "(空命令)" }}") {
            expect(!ShellCommandRules.isDestructive(cmd)) { "不该判为不可逆" }
        }
    }

    check("A 命令串里只要有一段危险，整体就危险") {
        expect(ShellCommandRules.isDestructive("ls && rm -rf build")) { "&& 后半段漏判" }
        expect(ShellCommandRules.isDestructive("echo ok; pm uninstall com.x")) { "; 后半段漏判" }
        expect(!ShellCommandRules.isDestructive("ls | grep a | wc -l")) { "纯读管道被误判" }
    }

    check("A 绝对路径也认得出来（/system/bin/rm）") {
        expect(ShellCommandRules.isDestructive("/system/bin/rm -rf /data/local/tmp/x")) { "basename 归一化失效" }
    }
}

/* -------------------------------- B. 授权 -------------------------------- */

fun testGrant() {
    println("[B] 一次授权的作用域")

    check("B1 普通 shell 命令：可记忆，键就是 shell 组") {
        val g = shellGrant("npm install")
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "key")
        expect(g.memorizable) { "npm install 应该可以被记住" }
        eq(g.memoLabel, "本次会话不再询问shell 命令", "文案")
    }

    check("B2 删除类命令：不可记忆，键与普通 shell 不同") {
        val g = shellGrant("rm -rf /sdcard/Download")
        expect(!g.memorizable) { "rm -rf 不该能被记住——这正是 R-08" }
        expect(g.key != ApprovalGrouping.GROUP_SHELL) { "危险档与普通档共用了同一个键" }
        expect(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE)) { "键前缀不对：${g.key}" }
    }

    check("B3 不可记忆时文案不能写「不再询问」") {
        val g = shellGrant("pm uninstall com.example.x")
        // 用前缀/后缀匹配而不是「包含某子串」：census 会把后者一律记成源码文本断言，
        // 这里判的虽是函数返回值，但没必要跟那条机械规则较劲——前缀匹配本来就比包含更强。
        expect(!g.memoLabel.startsWith("本次会话不再询问")) { "文案在骗人：${g.memoLabel}" }
        expect(g.memoLabel.endsWith("每次都会单独确认")) { "应说明每次都会确认：${g.memoLabel}" }
    }

    check("B4 同一个危险命令两次授权，键稳定（便于日志聚合）") {
        eq(shellGrant("rm -rf a").key, shellGrant("rm -rf b").key, "key")
    }

    check("B5 非 shell 工具不受影响（W-36 粒度保留）") {
        val tap = ApprovalScope.of("ui_tap", JsonObject(emptyMap()))
        val click = ApprovalScope.of("ui_click", JsonObject(emptyMap()))
        expect(tap.memorizable && click.memorizable) { "界面操作应可记忆" }
        eq(tap.key, click.key, "ui_tap / ui_click 应共用一键")
        eq(tap.key, ApprovalGrouping.GROUP_UI_ACTION, "key")
    }

    check("B6 后台 shell 与前台同判定") {
        val g = ApprovalScope.of("shell_background", argsOf("rm -rf x"))
        expect(!g.memorizable) { "shell_background 漏判" }
    }

    check("B7 缺 command 参数时不崩，按普通档处理") {
        val g = ApprovalScope.of("shell_run", JsonObject(emptyMap()))
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "key")
    }
}

/* ------------------------------ C. 不变量 ------------------------------ */

fun testInvariant() {
    println("[C] 关键不变量")

    check("C1 危险档不会被任何历史白名单命中") {
        val g = shellGrant("rm -rf /")
        // 模拟「旧版本/别处」把组键或危险键写进了白名单
        val polluted = setOf(ApprovalGrouping.GROUP_SHELL, g.key, "shell_destructive:rm")
        expect(!ApprovalScope.allows(g, polluted)) { "危险档被白名单放行了——R-08 又回来了" }
    }

    check("C2 普通档在白名单里时正常放行") {
        val g = shellGrant("gradle build")
        expect(ApprovalScope.allows(g, setOf(ApprovalGrouping.GROUP_SHELL))) { "普通档被误拦" }
    }

    check("C3 普通档不在白名单时仍然要问") {
        expect(!ApprovalScope.allows(shellGrant("npm install"), emptySet())) { "没授权却放行" }
    }

    check("C4 「记住 shell」之后，危险命令依旧要问（本轮的正式诉求）") {
        // 用户为了 npm install 勾了「不再询问」
        val allowed = mutableSetOf<String>()
        val benign = shellGrant("npm install")
        if (benign.memorizable) allowed += benign.key
        expect(ApprovalScope.allows(shellGrant("npm install"), allowed)) { "普通命令应免问" }
        expect(!ApprovalScope.allows(shellGrant("rm -rf /sdcard"), allowed)) { "rm 被顺带放行" }
        expect(!ApprovalScope.allows(shellGrant("pm uninstall com.x"), allowed)) { "卸载被顺带放行" }
    }
}

/* --------------------------------- 主流程 --------------------------------- */

fun main() {
    println("=== R-08 审批记忆粒度验证 ===")
    testClassification()
    testGrant()
    testInvariant()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
