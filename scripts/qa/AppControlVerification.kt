#!/usr/bin/env kotlin
/**
 * Phase 1 补口 · 应用操控工具集（app_list / app_launch / app_stop）QA 行为验证 harness。
 *
 * 背景：仓库无测试源集，本机离线 Gradle 缓存中没有 JUnit jar，无法跑标准单测。本 harness
 * 用纯 main() + 手写断言，直接对 assembleDebug 编译产物里的「名称/包名解析」等 internal
 * **纯函数**做行为验证（JVM 上跑不了真 PackageManager，故只测可脱离 Android 的逻辑）。
 *
 * 覆盖三类解析结果 + 歧义不启动 + 失败 hint 含可用工具提示 + app_list 文本格式化与
 * limit 截断（含上限 100 钳制）+ app_stop shell 结果映射。
 *
 * W-7 P0-2 追加覆盖（启动后沉降 + 前台确认）：
 *  - [DeviceContextStore.awaitForegroundPackage]：立即命中 / 第 N 次轮询命中（含 sleeper
 *    时延序列）/ 一直不中超时 / 空串短路四类时序分支（假 provider + 假 sleeper 驱动）；
 *  - [launchConfirmationResult]：confirmed 真/假两种输入的 code + message 断言；
 *  - [deviceContextEverUpdated]：空白/非空白两种输入的行为（无障碍未喂数据时的短路判据）。
 *
 * 运行方式见 scripts/qa/run-appcontrol-verification.sh（kotlin-compiler-embeddable 编译 + java 运行）。
 */

import com.example.agentkit.automation.DeviceContextStore
import com.example.agentkit.automation.deviceContextEverUpdated
import com.example.agentkit.tools.*
import kotlinx.coroutines.runBlocking

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

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("expected to contain <$needle> in <${haystack?.take(160)}>")
    }
}

/* ------------------------------ 假数据 ------------------------------ */

/** 一小组假的「可启动应用」，覆盖：唯一/歧义/系统应用/包名精确。 */
private val FAKE_APPS = listOf(
    AppEntry(label = "快手", packageName = "com.smile.gifmaker", isSystem = false),
    AppEntry(label = "微信", packageName = "com.tencent.mm", isSystem = false),
    AppEntry(label = "QQ", packageName = "com.tencent.mobileqq", isSystem = false),
    AppEntry(label = "腾讯视频", packageName = "com.tencent.qqlive", isSystem = false),
    AppEntry(label = "腾讯新闻", packageName = "com.tencent.news", isSystem = false),
    AppEntry(label = "设置", packageName = "com.android.settings", isSystem = true),
    AppEntry(label = "相机", packageName = "com.android.camera", isSystem = true),
)

/* ------------------------------ 解析：三类结果 ------------------------------ */

private fun testResolution() {
    println("\n== app_launch/app_stop 目标解析（唯一/歧义/无命中）==")

    check("精确包名 → 唯一命中") {
        val r = resolveAppTarget("com.tencent.mm", FAKE_APPS)
        assertTrue("is Unique", r is AppResolution.Unique)
        assertEquals("pkg", "com.tencent.mm", (r as AppResolution.Unique).entry.packageName)
    }
    check("精确名称 → 唯一命中（忽略大小写 trim）") {
        val r = resolveAppTarget("  快手 ", FAKE_APPS)
        assertTrue("is Unique", r is AppResolution.Unique)
        assertEquals("label", "快手", (r as AppResolution.Unique).entry.label)
    }
    check("英文名忽略大小写 → 唯一命中") {
        val r = resolveAppTarget("qq", FAKE_APPS)
        assertTrue("is Unique", r is AppResolution.Unique)
        assertEquals("pkg", "com.tencent.mobileqq", (r as AppResolution.Unique).entry.packageName)
    }
    check("模糊匹配「腾讯」命中多包 → 歧义（含候选列表）") {
        val r = resolveAppTarget("腾讯", FAKE_APPS)
        assertTrue("is Ambiguous", r is AppResolution.Ambiguous)
        val cands = (r as AppResolution.Ambiguous).candidates
        assertTrue(">=2 candidates (got ${cands.size})", cands.size >= 2)
        assertTrue("contains 腾讯视频", cands.any { it.packageName == "com.tencent.qqlive" })
        assertTrue("contains 腾讯新闻", cands.any { it.packageName == "com.tencent.news" })
    }
    check("歧义时不得擅自启动：解析结果为 Ambiguous 而非 Unique") {
        // execute() 的 Ambiguous 分支只返回失败、绝不调用 launchResolved（代码分支隔离），
        // 这里从解析层断言「多命中必歧义」，保证「唯一命中才启动」的硬约束。
        val r = resolveAppTarget("com.tencent", FAKE_APPS) // 包名前缀命中 3 个包
        assertTrue("not Unique", r !is AppResolution.Unique)
        assertTrue("is Ambiguous", r is AppResolution.Ambiguous)
    }
    check("前缀收窄：以 target 开头若唯一则视为唯一命中") {
        // 「快手」前缀仅 1 个；构造前缀命中唯一场景
        val r = resolveAppTarget("快手", FAKE_APPS)
        assertTrue("is Unique", r is AppResolution.Unique)
    }
    check("无命中 → NotFound") {
        val r = resolveAppTarget("不存在的应用名XYZ", FAKE_APPS)
        assertEquals("NotFound", AppResolution.NotFound, r)
    }
    check("空 target → NotFound（不误命中）") {
        assertEquals("blank", AppResolution.NotFound, resolveAppTarget("   ", FAKE_APPS))
        assertEquals("empty", AppResolution.NotFound, resolveAppTarget("", FAKE_APPS))
    }
    check("空候选列表 → NotFound（不崩）") {
        assertEquals("no candidates", AppResolution.NotFound, resolveAppTarget("快手", emptyList()))
    }
}

/* ------------------------------ 失败 hint ------------------------------ */

private fun testHints() {
    println("\n== 失败 hint 含可用工具/替代路径提示 ==")

    check("无命中 hint 指向 app_list（可自纠）") {
        val hint = appNotFoundHint("快子")
        assertContains("mentions app_list", hint, "app_list")
        assertContains("mentions query", hint, "query")
    }
    check("歧义 hint 列出候选并提示用准确包名") {
        val cands = listOf(
            AppEntry("QQ", "com.tencent.mobileqq"),
            AppEntry("腾讯视频", "com.tencent.qqlive")
        )
        val hint = appAmbiguousHint("腾讯", cands)
        assertContains("lists QQ pkg", hint, "com.tencent.mobileqq")
        assertContains("lists qqlive pkg", hint, "com.tencent.qqlive")
        assertContains("asks pkg", hint, "包名")
    }
    check("app_stop 降级 hint 写清手动替代路径（不静默成功）") {
        val hint = stopUnavailableHint("快手", "com.smile.gifmaker")
        assertContains("manual step", hint, "强制停止")
        assertContains("settings path", hint, "设置 → 应用")
        assertContains("pkg", hint, "com.smile.gifmaker")
    }
    check("ShellCommandRunner.Unavailable 恒返回 null（默认诚实降级）") {
        runBlocking {
            assertEquals("null", null, ShellCommandRunner.Unavailable.exec("am force-stop x"))
        }
    }
}

/* ------------------------------ app_list 格式化 / limit ------------------------------ */

private fun testListFormatting() {
    println("\n== app_list 文本格式化 + limit 截断（含上限 100 钳制）==")

    check("clampAppListLimit：默认 30") {
        assertEquals("null→30", 30, clampAppListLimit(null))
    }
    check("clampAppListLimit：上限钳制到 100") {
        assertEquals("200→100", 100, clampAppListLimit(200))
        assertEquals("101→100", 100, clampAppListLimit(101))
        assertEquals("exact 100", 100, clampAppListLimit(100))
    }
    check("clampAppListLimit：下界钳制到 1（0/负数）") {
        assertEquals("0→1", 1, clampAppListLimit(0))
        assertEquals("-5→1", 1, clampAppListLimit(-5))
    }
    check("clampAppListLimit：区间内原样返回") {
        assertEquals("30", 30, clampAppListLimit(30))
        assertEquals("1", 1, clampAppListLimit(1))
        assertEquals("57", 57, clampAppListLimit(57))
    }
    check("formatAppList：每行「序号. 名称 (包名)」") {
        val text = formatAppList(FAKE_APPS, limit = 100)
        assertContains("line1", text, "1. 快手 (com.smile.gifmaker)")
        assertContains("line2", text, "2. 微信 (com.tencent.mm)")
        assertTrue("no truncation note", !text.contains("仅显示前"))
    }
    check("formatAppList：条目数 > limit 时截断并提示收窄查询") {
        val many = (1..40).map { AppEntry("App$it", "com.demo.app$it") }
        val text = formatAppList(many, limit = 30)
        assertContains("first", text, "1. App1 (com.demo.app1)")
        assertContains("last shown", text, "30. App30 (com.demo.app30)")
        assertTrue("no 31st", !text.contains("31. App31"))
        assertContains("truncation note", text, "共 40 个")
        assertContains("narrow hint", text, "query")
        assertEquals("line count == limit + note", 31, text.split("\n").size)
    }
    check("formatAppList：恰好等于 limit 不追加提示") {
        val exact = (1..30).map { AppEntry("App$it", "com.demo.app$it") }
        val text = formatAppList(exact, limit = 30)
        assertTrue("no note", !text.contains("仅显示前"))
        assertEquals("line count", 30, text.split("\n").size)
    }
    check("formatAppList + clampAppListLimit：300 条经上限 100 钳制后最多 100 行 + 1 提示行") {
        val many = (1..300).map { AppEntry("App$it", "com.demo.app$it") }
        val text = formatAppList(many, clampAppListLimit(300))
        assertEquals("101 lines", 101, text.split("\n").size)
        assertContains("last shown", text, "100. App100 (com.demo.app100)")
        assertTrue("no 101st entry", !text.contains("101. App101"))
        assertContains("note", text, "共 300 个")
    }
}

/* ------------------------------ 过滤（query / include_system） ------------------------------ */

private fun testFiltering() {
    println("\n== app_list 过滤：query 模糊匹配 + include_system ==")

    check("include_system=false（默认）剔除系统应用") {
        val out = filterAppEntries(FAKE_APPS, query = null, includeSystem = false)
        assertTrue("no 设置", out.none { it.packageName == "com.android.settings" })
        assertTrue("no 相机", out.none { it.packageName == "com.android.camera" })
        assertEquals("count", 5, out.size)
    }
    check("include_system=true 保留系统应用") {
        val out = filterAppEntries(FAKE_APPS, query = null, includeSystem = true)
        assertEquals("count", 7, out.size)
        assertTrue("has 设置", out.any { it.packageName == "com.android.settings" })
    }
    check("query 匹配名称（忽略大小写）") {
        val out = filterAppEntries(FAKE_APPS, query = "腾讯", includeSystem = false)
        assertTrue("has 腾讯视频", out.any { it.label == "腾讯视频" })
        assertTrue("has 腾讯新闻", out.any { it.label == "腾讯新闻" })
        assertEquals("count", 2, out.size)
    }
    check("query 匹配包名前缀") {
        val out = filterAppEntries(FAKE_APPS, query = "com.smile", includeSystem = false)
        assertEquals("count", 1, out.size)
        assertEquals("pkg", "com.smile.gifmaker", out[0].packageName)
    }
    check("空白 query 视为不过滤") {
        val out = filterAppEntries(FAKE_APPS, query = "  ", includeSystem = false)
        assertEquals("count", 5, out.size)
    }
    check("结果按 label 升序稳定") {
        val out = filterAppEntries(FAKE_APPS, query = null, includeSystem = true)
        assertEquals("sorted", out.map { it.label }, out.map { it.label }.sorted())
    }
}

/* ------------------------------ app_stop 结果映射 ------------------------------ */

private fun testAppStopResultMapping() {
    println("\n== app_stop shell 结果映射（成功 / 通道不可用 / 非零退出码）==")

    check("runner 返回成功（exit 0）→ app_stopped") {
        val r = appStopResult("快手", "com.smile.gifmaker", ShellOutcome(0, "Force stopping com.smile.gifmaker"))
        assertTrue("ok", r.ok)
        assertEquals("code", "app_stopped", r.code)
        assertContains("label in message", r.message, "快手")
    }

    check("通道不可用（outcome=null）→ stop_needs_manual 诚实降级（不假装成功）") {
        val r = appStopResult("快手", "com.smile.gifmaker", null)
        assertTrue("not ok", !r.ok)
        assertEquals("code", "stop_needs_manual", r.code)
        assertContains("manual hint", r.hint ?: "", "强制停止")
    }

    check("runner 返回非零退出码 → stop_failed（不假装成功，附退出码）") {
        val r = appStopResult("快手", "com.smile.gifmaker", ShellOutcome(1, "Permission Denial"))
        assertTrue("not ok", !r.ok)
        assertEquals("code", "stop_failed", r.code)
        assertContains("exit code in message", r.message, "1")
        assertContains("manual hint", r.hint ?: "", "强制停止")
    }

    check("注入假 runner：成功/返回 null 两分支行为正确（接线语义）") {
        runBlocking {
            val okRunner = ShellCommandRunner { ShellOutcome(0, "stopped") }
            val nullRunner = ShellCommandRunner { null }
            val ok = appStopResult("微信", "com.tencent.mm", okRunner.exec("am force-stop com.tencent.mm"))
            val deg = appStopResult("微信", "com.tencent.mm", nullRunner.exec("am force-stop com.tencent.mm"))
            assertEquals("ok runner → app_stopped", "app_stopped", ok.code)
            assertEquals("null runner → stop_needs_manual", "stop_needs_manual", deg.code)
        }
    }
}

/* ------------------------------ W-7 P0-2：启动后沉降 + 前台确认 ------------------------------ */

/**
 * [DeviceContextStore.awaitForegroundPackage] 四类时序分支。
 *
 * 用假 provider（按序返回模拟窗口切换）+ 假 sleeper（收集时延参数、不真睡）驱动：
 * 轮询预算计时语义确定，毫秒级跑完全部分支，不依赖宿主机时钟。
 */
private fun testAwaitForegroundTiming() {
    println("\n== W-7 P0-2 awaitForegroundPackage 四类时序分支（假 provider + 假 sleeper）==")

    check("立即命中：provider 恒返回 target → true，sleeper 0 次") {
        runBlocking {
            val sleeps = mutableListOf<Long>()
            val hit = DeviceContextStore.awaitForegroundPackage(
                targetPackage = "com.smile.gifmaker",
                timeoutMs = 3_000,
                pollIntervalMs = 250,
                foregroundProvider = { "com.smile.gifmaker" },
                sleeper = { ms -> sleeps.add(ms) }
            )
            assertTrue("hit", hit)
            assertEquals("sleeper calls", 0, sleeps.size)
        }
    }

    check("第 N 次命中：前两次空串、第三次中 → true，sleeper 2 次且时延序列 [250, 250]") {
        runBlocking {
            val seq = listOf("", "", "com.smile.gifmaker").iterator()
            val sleeps = mutableListOf<Long>()
            val hit = DeviceContextStore.awaitForegroundPackage(
                targetPackage = "com.smile.gifmaker",
                timeoutMs = 3_000,
                pollIntervalMs = 250,
                foregroundProvider = { if (seq.hasNext()) seq.next() else "" },
                sleeper = { ms -> sleeps.add(ms) }
            )
            assertTrue("hit", hit)
            assertEquals("sleeper sequence", listOf(250L, 250L), sleeps)
        }
    }

    check("一直不中：provider 恒空串 → false，时延 [250,250,250,250] 共 4 次、总 1000 = timeoutMs 后退出") {
        runBlocking {
            val sleeps = mutableListOf<Long>()
            val hit = DeviceContextStore.awaitForegroundPackage(
                targetPackage = "com.smile.gifmaker",
                timeoutMs = 1_000,
                pollIntervalMs = 250,
                foregroundProvider = { "" },
                sleeper = { ms -> sleeps.add(ms) }
            )
            assertTrue("miss", !hit)
            assertEquals("sleeper sequence", listOf(250L, 250L, 250L, 250L), sleeps)
            assertTrue("total <= timeoutMs (got ${sleeps.sum()})", sleeps.sum() <= 1_000L)
        }
    }

    check("空串 target：立即 false（短路不查 provider），sleeper 0 次") {
        runBlocking {
            val sleeps = mutableListOf<Long>()
            val hit = DeviceContextStore.awaitForegroundPackage(
                targetPackage = "",
                timeoutMs = 3_000,
                pollIntervalMs = 250,
                foregroundProvider = { error("空串短路分支不应查询 provider") },
                sleeper = { ms -> sleeps.add(ms) }
            )
            assertTrue("miss", !hit)
            assertEquals("sleeper calls", 0, sleeps.size)
        }
    }
}

/** [launchConfirmationResult] 两种输入（confirmed 真/假）的 code + message 断言。 */
private fun testLaunchConfirmationMapping() {
    println("\n== W-7 P0-2 launchConfirmationResult 两种输入（confirmed 真/假）==")

    val entry = AppEntry(label = "快手", packageName = "com.smile.gifmaker")

    check("confirmed=true → app_launched，message 含「已确认切换到前台」") {
        val (code, message) = launchConfirmationResult(entry, confirmed = true)
        assertEquals("code", "app_launched", code)
        assertContains("confirmed msg", message, "已确认切换到前台")
    }

    check("confirmed=false → app_launched_unconfirmed，提示 device_foreground_app 复核且不谎称「已启动」") {
        val (code, message) = launchConfirmationResult(entry, confirmed = false)
        assertEquals("code", "app_launched_unconfirmed", code)
        assertContains("foreground recheck hint", message, "device_foreground_app")
        assertTrue("no misleading 已启动", !message.contains("已启动"))
    }
}

/** [deviceContextEverUpdated]：空白/非空白两种输入（无障碍未喂数据时的短路判据）。 */
private fun testDeviceContextEverUpdated() {
    println("\n== W-7 P0-2 deviceContextEverUpdated（无障碍喂数据短路判据）==")

    check("空白 foregroundPackage → false（从未喂过，确认无从谈起）") {
        assertEquals("empty", false, deviceContextEverUpdated(""))
    }

    check("非空白 foregroundPackage → true") {
        assertEquals("wechat pkg", true, deviceContextEverUpdated("com.tencent.mm"))
    }
}

fun main() {
    println("=== Phase1 补口 · 应用操控工具集（app_list/app_launch/app_stop）QA 行为验证 ===")
    testResolution()
    testHints()
    testListFormatting()
    testFiltering()
    testAppStopResultMapping()
    testAwaitForegroundTiming()
    testLaunchConfirmationMapping()
    testDeviceContextEverUpdated()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
