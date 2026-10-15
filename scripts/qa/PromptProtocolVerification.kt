#!/usr/bin/env kotlin
/**
 * Agent「界面操作协议」QA 行为验证 harness（真机第四轮 Bug 修正）。
 *
 * 背景（真机日志 08:27-08:28，指令「打开设置，把字体大小调到最大」）：
 *   ui_tree ✅ → ui_click「显示和亮度」❌ click_not_found → ui_tap ✅ → ui_tree ✅
 *   → 之后模型乱来：shell_run dumpsys / am start / ui_screenshot 全部失败
 *   → 最终放弃，回复用户「请你手动核对」。
 *
 * 结论：无障碍 ui_tree+ui_tap 组合明明是通的，模型却在 ui_click 失败后转投
 * **shell 绕路**（本机 user shell 无 dumpsys/am/settings 权限，必失败）。
 * 本 harness 断言「提示词层修复」已把这些约束写死，防止回退：
 *   1. DEFAULT_SYSTEM_PROMPT 含「界面操作协议」关键约束（唯一通道 / 禁 shell 操控 /
 *      失败自纠三步 / 每次操作后重新 ui_tree / 不要过早放弃）；
 *   2. 提示词长度 ≤ 上限（防膨胀失控）；
 *   3. ui_click 工具描述提到「候选」重试；
 *   4. ui_tap 描述强调从 ui_tree 的 bounds 取中心坐标；
 *   5. shell_run 描述/系统提示含「不能操控界面」语义；
 *   6. ui_screenshot 失败指引含「权限中心 / 去开启」可行动作。
 *
 * 仓库无测试源集、离线缓存无 JUnit，沿用纯 main() + 手写断言（形态学
 * scripts/qa/FallbackVerification.kt）。运行方式见
 * scripts/qa/run-prompt-protocol-verification.sh（离线，仅用 Gradle 缓存内构件）。
 *
 * 说明：本 harness **只读**工具描述字符串，不调用任何 execute()——因此完全不触发
 * Android 运行时（无需无障碍服务 / 屏幕捕获服务 / Context），可在 JVM 直接跑。
 */

import com.example.agentkit.automation.SCREEN_CAPTURE_OFF_GUIDANCE
import com.example.agentkit.automation.UiClickTool
import com.example.agentkit.automation.UiScreenshotTool
import com.example.agentkit.automation.UiTapTool
import com.example.agentkit.automation.UiTreeTool
import com.example.agentkit.core.DEFAULT_SYSTEM_PROMPT
import com.example.agentkit.core.SYSTEM_PROMPT_MAX_CHARS
import com.example.agentkit.core.terminal.TerminalSessionPool
import com.example.agentkit.core.terminal.backend.TerminalBackendSource
import com.example.agentkit.tools.ShellRunTool
import java.io.File

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

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("[$name] expected to contain <$needle> in <${haystack?.take(200)}>")
    }
}

/** shell_run 只读描述所需的最小 shell 池：本 harness 不执行命令，仅取描述字符串。 */
private fun readOnlyShellPool(): TerminalSessionPool =
    TerminalSessionPool(TerminalBackendSource { _, _ -> null })

/* ------------------------------ A. 界面操作协议 ------------------------------ */

private fun testInterfaceProtocolSection() {
    println("\n[A] DEFAULT_SYSTEM_PROMPT 含「界面操作协议」关键约束")
    val p = DEFAULT_SYSTEM_PROMPT

    check("含段落标题「界面操作协议」") {
        assertContains("title", p, "界面操作协议")
    }

    check("唯一可行通道：无障碍工具（ui_tree 看结构 + ui_tap 操作）") {
        assertContains("ui_tree", p, "ui_tree")
        assertContains("ui_tap", p, "ui_tap")
        assertContains("唯一可行通道", p, "唯一可行通道")
    }

    check("明确禁止用 shell_run 操控界面（点名 dumpsys/am start/settings）") {
        assertContains("shell_run 操控界面", p, "shell_run 操控界面")
        assertContains("dumpsys", p, "dumpsys")
        assertContains("am start", p, "am start")
        assertContains("settings", p, "settings")
        assertContains("权限不足", p, "权限不足")
    }

    check("shell 用途收敛：文件读写/查看信息；应用列表用 app_list、启动用 app_launch") {
        assertContains("app_list", p, "app_list")
        assertContains("app_launch", p, "app_launch")
    }

    check("失败自纠三步：候选重试 → ui_tap 按 bounds → 向用户求助") {
        assertContains("失败自纠", p, "失败就自纠")
        assertContains("候选", p, "候选列表")
        assertContains("bounds 中心坐标", p, "bounds 中心坐标")
        assertContains("向用户求助", p, "向用户求助")
    }

    check("每次操作后必须重新 ui_tree") {
        assertContains("重新 ui_tree", p, "重新 ui_tree")
    }

    check("不要过早放弃：禁「请你手动核对」，至少自纠两轮") {
        assertContains("不要过早放弃", p, "不要过早放弃")
        assertContains("请你手动核对", p, "请你手动核对")
        assertContains("自纠两轮", p, "自纠两轮")
    }
}

/* ------------------------------ C. 提示词预算 ------------------------------ */

private fun testPromptBudget() {
    println("\n[C] 系统提示长度预算")
    val len = DEFAULT_SYSTEM_PROMPT.length
    check("长度 ≤ 上限（$len ≤ $SYSTEM_PROMPT_MAX_CHARS）") {
        assertTrue("length=$len", len <= SYSTEM_PROMPT_MAX_CHARS)
    }
    check("长度 ≥ 下限（协议段确实写进去了，> 500）") {
        assertTrue("length=$len", len > 500)
    }
    check("上限常量本身合理（1..8192）") {
        assertTrue("max=$SYSTEM_PROMPT_MAX_CHARS", SYSTEM_PROMPT_MAX_CHARS in 1..8192)
    }
}

/* ------------------------------ B. 工具侧提示同步 ------------------------------ */

private fun testToolHints() {
    println("\n[B] 工具描述 / 失败 hint 同步")

    check("ui_click 描述提到失败会返回「候选」并重试") {
        val d = UiClickTool().description
        assertContains("候选", d, "候选")
        assertContains("重试一次", d, "重试一次")
        assertContains("ui_tap", d, "ui_tap")
    }

    check("ui_tap 描述强调从 ui_tree 的 bounds 取中心坐标") {
        val d = UiTapTool().description
        assertContains("bounds", d, "bounds")
        assertContains("中心", d, "中心")
        assertContains("ui_tree", d, "ui_tree")
    }

    check("ui_tree 描述/系统提示提示「重新读取 / 未变不重复」") {
        val t = UiTreeTool()
        assertContains("desc 重新", t.description, "重新调用")
        assertContains("sp 重新", t.systemPrompt, "重新")
        assertContains("sp 未变不重复", t.systemPrompt, "不要重复")
        assertContains("sp 唯一通道", t.systemPrompt, "唯一可行通道")
    }

    check("shell_run 描述含「不能用来操控界面」语义（点名 dumpsys/am start/settings）") {
        val d = ShellRunTool(readOnlyShellPool()).description
        assertContains("不能操控", d, "不能用来操控界面")
        assertContains("dumpsys", d, "dumpsys")
        assertContains("am start", d, "am start")
        assertContains("权限不足", d, "权限不足")
        assertContains("ui_", d, "ui_")
    }

    check("shell_run 系统提示含「不能操控界面」语义") {
        val sp = ShellRunTool(readOnlyShellPool()).systemPrompt
        assertContains("不能操控界面", sp, "不能操控界面")
        assertContains("文件读写", sp, "文件读写")
    }

    check("ui_screenshot 失败指引含「权限中心 / 去开启」可行动作") {
        assertContains("权限中心", SCREEN_CAPTURE_OFF_GUIDANCE, "权限中心")
        assertContains("去开启", SCREEN_CAPTURE_OFF_GUIDANCE, "去开启")
        assertContains("屏幕捕获", SCREEN_CAPTURE_OFF_GUIDANCE, "屏幕捕获")
    }

    check("ui_screenshot 工具描述仍说明需先开启屏幕捕获（引用一致性）") {
        val d = UiScreenshotTool(File("/tmp/prompt-verify")).description
        assertContains("屏幕捕获", d, "屏幕捕获")
    }
}

fun main() {
    println("=== Agent「界面操作协议」QA 行为验证（真机第四轮 Bug 修复） ===")
    testInterfaceProtocolSection()
    testPromptBudget()
    testToolHints()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
