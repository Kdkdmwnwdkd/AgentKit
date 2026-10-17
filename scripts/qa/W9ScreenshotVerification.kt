#!/usr/bin/env kotlin
/**
 * W-9 QA 行为验证 harness：截图通道升级（无障碍 takeScreenshot 主路径）。
 *
 * 背景（真机日志 23:52-23:58 诊断 + W-8 交付后的下一批）：「屏幕捕捉开了和没开
 * 没区别、更久更浪费时间」的剩余耗时在 MediaProjection 授权链（系统弹窗 + 前台
 * 服务启动 + 首帧等待）。本项目已有无障碍服务——`AccessibilityService.takeScreenshot`
 * （API 30+）天然是更快的主路径：用户授过无障碍（读屏）即可截图，零授权链。
 *
 * 本 harness 只测**可脱离 Android 运行时**的部分（JVM 直跑，形态学
 * scripts/qa/AppControlVerification.kt）：
 *  - [shouldPreferA11yScreenshot]：路由真值表（a11yReady × API 级 4×3 全组合）；
 *  - [A11Y_SCREENSHOT_MIN_API]：常量与系统文档一致（API 30）；
 *  - 文案引用一致性：ui_screenshot 描述「无障碍优先」、失败指引含无障碍通道一句；
 *  - DEFAULT_SYSTEM_PROMPT 分工段（与 PromptProtocolVerification 同源，防此处回退）。
 *
 * 服务方法 takeScreenshotViaA11y 的 suspend/回调行为由沙箱 stub 二进制验证覆盖
 * （真实 AgentAccessibilityService 编译 + 假 AccessibilityService.takeScreenshot
 * 三态回调），仓库 harness 不加载 Android 类。
 *
 * 运行方式见 scripts/qa/run-w9-screenshot-verification.sh（离线，仅用 Gradle 缓存内构件）。
 */

import com.example.agentkit.automation.A11Y_SCREENSHOT_MIN_API
import com.example.agentkit.automation.SCREEN_CAPTURE_OFF_GUIDANCE
import com.example.agentkit.automation.UiScreenshotTool
import com.example.agentkit.automation.shouldPreferA11yScreenshot
import com.example.agentkit.core.DEFAULT_SYSTEM_PROMPT
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

private fun assertEquals(name: String, expected: Boolean, actual: Boolean) {
    if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("[$name] expected to contain <$needle> in <${haystack?.take(200)}>")
    }
}

/* ------------------------------ A. 路由真值表 ------------------------------ */

private fun testRoutingTruthTable() {
    println("\n[A] shouldPreferA11yScreenshot 真值表（a11yReady × API 级）")

    check("常量：A11Y_SCREENSHOT_MIN_API = 30（系统 takeScreenshot 自 Android 11 起）") {
        assertEquals("min api", true, A11Y_SCREENSHOT_MIN_API == 30)
    }

    check("就绪 + API30/31/34 → 走无障碍主路径") {
        for (api in listOf(30, 31, 34)) {
            assertEquals("api=$api", true, shouldPreferA11yScreenshot(true, api))
        }
    }

    check("就绪 + API29 及以下 → 回落 MediaProjection（系统无此 API）") {
        for (api in listOf(29, 28, 26)) {
            assertEquals("api=$api", false, shouldPreferA11yScreenshot(true, api))
        }
    }

    check("未就绪（无障碍没开）+ 任意 API → 回落 MediaProjection") {
        for (api in listOf(26, 30, 34)) {
            assertEquals("api=$api", false, shouldPreferA11yScreenshot(false, api))
        }
    }

    check("边界：API 恰为 30 且就绪 → 走主路径（>= 语义，不是 >）") {
        assertEquals("api=30 ready", true, shouldPreferA11yScreenshot(true, 30))
    }
}

/* ------------------------------ B. 文案引用一致性 ------------------------------ */

private fun testCopyConsistency() {
    println("\n[B] 文案引用一致性（描述 / 失败指引 / 提示词分工段）")

    check("ui_screenshot 描述：无障碍优先，兜底才提屏幕捕获") {
        val d = UiScreenshotTool(File("/tmp/w9-verify")).description
        assertContains("a11y first", d, "优先走无障碍通道")
        assertContains("fallback", d, "屏幕捕获")
    }

    check("失败指引：权限中心/去开启/屏幕捕获关键词仍在（MediaProjection 兜底路径可行动）") {
        assertContains("权限中心", SCREEN_CAPTURE_OFF_GUIDANCE, "权限中心")
        assertContains("去开启", SCREEN_CAPTURE_OFF_GUIDANCE, "去开启")
        assertContains("屏幕捕获", SCREEN_CAPTURE_OFF_GUIDANCE, "屏幕捕获")
    }

    check("失败指引：补「无障碍通道」一句（主路径可用时别让用户白开捕获）") {
        assertContains("a11y 通道", SCREEN_CAPTURE_OFF_GUIDANCE, "无障碍通道")
    }

    check("系统提示：分工段在册（ui_tree 定位操作 / 截图看视觉 / 禁每步截图）") {
        assertContains("分工段", DEFAULT_SYSTEM_PROMPT, "工具分工")
        assertContains("禁每步截图", DEFAULT_SYSTEM_PROMPT, "不要每个步骤都截图")
    }
}

fun main() {
    println("=== W-9 截图通道升级 QA 行为验证（无障碍 takeScreenshot 主路径） ===")
    testRoutingTruthTable()
    testCopyConsistency()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
