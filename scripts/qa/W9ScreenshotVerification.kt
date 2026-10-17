#!/usr/bin/env kotlin
/**
 * 截图通道 QA harness（W-9 建立，W-12 改为「无障碍唯一通道」）。
 *
 * W-12 决策：删除 MediaProjection 整条链路。截图只走无障碍 `takeScreenshot`（API 30+）——
 * 用户授过无障碍（读屏）即可，无授权弹窗、无前台服务、无首帧等待。
 *
 * 本 harness 只测**可脱离 Android 运行时**的部分（JVM 直跑）：
 *  A. [canUseA11yScreenshot] 真值表（a11yReady × API 级全组合 + 边界 API=30）；
 *  B. 文案一致性：描述/失败指引不再引导用户去开屏幕捕获；
 *  C. 接线反证（源码扫描）：MediaProjection 链路（服务/桥/权限/清单）确实已删除，
 *     防止后续批次把旧路径又加回来。
 *
 * 运行方式见 scripts/qa/run-w9-screenshot-verification.sh（离线）。
 */

import com.example.agentkit.automation.A11Y_SCREENSHOT_MIN_API
import com.example.agentkit.automation.SCREENSHOT_UNAVAILABLE_GUIDANCE
import com.example.agentkit.automation.UiScreenshotTool
import com.example.agentkit.automation.canUseA11yScreenshot
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
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("[$name] expected to contain <$needle> in <${haystack?.take(200)}>")
    }
}

private fun assertNotContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) == true) {
        throw AssertionError("[$name] expected NOT to contain <$needle>")
    }
}

/* ------------------------------ A. 路由真值表 ------------------------------ */

private fun testRoutingTruthTable() {
    println("\n[A] canUseA11yScreenshot 真值表（a11yReady × API 级）")

    check("常量：A11Y_SCREENSHOT_MIN_API = 30（系统 takeScreenshot 自 Android 11 起）") {
        assertEquals("min api", true, A11Y_SCREENSHOT_MIN_API == 30)
    }

    check("就绪 + API30/31/34 → 可用") {
        for (api in listOf(30, 31, 34)) {
            assertEquals("api=$api", true, canUseA11yScreenshot(true, api))
        }
    }

    check("就绪 + API29 及以下 → 不可用（系统无此 API）") {
        for (api in listOf(29, 28, 26)) {
            assertEquals("api=$api", false, canUseA11yScreenshot(true, api))
        }
    }

    check("未就绪（无障碍没开）+ 任意 API → 不可用") {
        for (api in listOf(26, 30, 34)) {
            assertEquals("api=$api", false, canUseA11yScreenshot(false, api))
        }
    }

    check("边界：API 恰为 30 且就绪 → 可用（>= 语义，不是 >）") {
        assertEquals("api=30 ready", true, canUseA11yScreenshot(true, 30))
    }
}

/* ------------------------------ B. 文案引用一致性 ------------------------------ */

private fun testCopyConsistency() {
    println("\n[B] 文案引用一致性（描述 / 失败指引 / 提示词分工段）")

    check("ui_screenshot 描述：只说无障碍，不再提屏幕捕获") {
        val d = UiScreenshotTool(File("/tmp/w12-verify")).description
        assertContains("a11y", d, "无障碍")
        assertNotContains("no capture", d, "屏幕捕获")
        assertNotContains("no capture switch", d, "屏幕录制")
    }

    check("失败指引：指向无障碍服务 + 明确无需屏幕捕捉权限") {
        assertContains("a11y", SCREENSHOT_UNAVAILABLE_GUIDANCE, "无障碍服务")
        assertContains("no capture", SCREENSHOT_UNAVAILABLE_GUIDANCE, "无需屏幕捕捉权限")
        assertContains("android11", SCREENSHOT_UNAVAILABLE_GUIDANCE, "Android 11")
    }

    check("失败指引：不再出现「权限中心 → 屏幕捕获 → 去开启」旧引导") {
        assertNotContains("old path", SCREENSHOT_UNAVAILABLE_GUIDANCE, "权限中心")
        assertNotContains("old path 2", SCREENSHOT_UNAVAILABLE_GUIDANCE, "去开启")
    }

    check("系统提示：分工段在册（ui_tree 定位操作 / 截图看视觉 / 禁每步截图）") {
        assertContains("分工段", DEFAULT_SYSTEM_PROMPT, "工具分工")
        assertContains("禁每步截图", DEFAULT_SYSTEM_PROMPT, "不要每个步骤都截图")
    }
}

/* ------------------------------ C. 接线反证（源码扫描） ------------------------------ */

private fun read(rel: String): String = File(rel).readText()

private fun testWiringRemoved() {
    println("\n[C] MediaProjection 链路删除反证（源码扫描）")

    val tool = read("app/src/main/java/com/example/agentkit/automation/UiScreenshotTool.kt")
    val manifest = read("app/src/main/AndroidManifest.xml")

    check("UiScreenshotTool 代码不再引用屏幕捕获服务/桥/MediaProjection（注释除外）") {
        // 只看代码行：KDoc 里会写「为什么删掉 MediaProjection」，那是有价值的说明
        val code = tool.lines().filter {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
        }.joinToString("\n")
        assertNotContains("service", code, "ScreenCaptureService")
        assertNotContains("bridge", code, "ScreenshotBridge")
        assertNotContains("projection", code, "MediaProjection")
        assertNotContains("capture flag", code, "capture_not_enabled")
    }

    check("清单不再声明屏幕捕获服务与 mediaProjection 前台类型") {
        assertNotContains("service", manifest, "ScreenCaptureService")
        assertNotContains("fgs type", manifest, "foregroundServiceType=\"mediaProjection\"")
        assertNotContains("permission", manifest, "FOREGROUND_SERVICE_MEDIA_PROJECTION")
    }

    check("源码目录中屏幕捕获服务/桥文件已删除") {
        assertTrue(
            "ScreenCaptureService.kt 应已删除",
            !File("app/src/main/java/com/example/agentkit/automation/ScreenCaptureService.kt").exists()
        )
        assertTrue(
            "ScreenshotBridge.kt 应已删除",
            !File("app/src/main/java/com/example/agentkit/automation/ScreenshotBridge.kt").exists()
        )
    }
}

fun main() {
    println("=== 截图通道 QA harness（W-12：无障碍唯一通道） ===")
    testRoutingTruthTable()
    testCopyConsistency()
    testWiringRemoved()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
