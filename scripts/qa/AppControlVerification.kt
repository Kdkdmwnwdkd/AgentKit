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
 * W-7 审核修复追加覆盖（F1 取消传播 / F2 入参防护）：
 *  - F1：settle/轮询期间协程被取消 → CancellationException 上抛而非返回 ToolResult。
 *    经 internal 可测缝（[launchWithConfirmation] / [runCatchingRethrowCancel]，
 *    -Xfriend-paths 可见）驱动**真实** confirmLaunch；修前形态（confirmLaunch 包在
 *    catch (e: Exception) 里 + execute 裸 runCatching）会把取消吞成 launch_failed /
 *    launch_error 假失败 → 本组断言失败；修后原样上抛 → 全绿。
 *  - F2：pollIntervalMs ≤ 0 → 入口 fail-fast（修前无限忙等，看门狗都杀不死）。
 *
 * W-7 P0-1/P0-3/P1-1 追加覆盖（「打开快手」失败的三根因修复）：
 *  - P0-1 静态字典：[AppNameDictionary.lookup] 命中/别名同包/大小写不敏感/未收录 null；
 *    [looksLikePackageName] 包名形态判据（含点、无空白、段首字母）；
 *    [resolveAppTargetWithDict] 三级解析链（字典命中用候选 label / 合成条目 / 回落
 *    候选匹配保持歧义 / 包名兜底直启 / 空串 NotFound）——纯函数，假候选即可断言。
 *  - P0-3 事件配对：[pickForegroundPackageFromEvents] 取时间序最后 RESUMED、
 *    非 RESUMED 不参与、空包名跳过、时间戳并列后者覆盖、乱序输入不预排序。
 *  - P1-1 陈旧窗口防护：[shouldRetryForFreshWindow]：刚切换且 root 为 null/自身包 →
 *    值得再等；root 已是目标应用 / 距切换已久 / maxWaitMs≤0 → 不等。
 *
 * 运行方式见 scripts/qa/run-appcontrol-verification.sh（kotlin-compiler-embeddable 编译 + java 运行）。
 */

import com.example.agentkit.automation.DeviceContextStore
import com.example.agentkit.automation.EVENT_ACTIVITY_RESUMED
import com.example.agentkit.automation.UsageEventRecord
import com.example.agentkit.automation.deviceContextEverUpdated
import com.example.agentkit.automation.pickForegroundPackageFromEvents
import com.example.agentkit.automation.shouldRetryForFreshWindow
import com.example.agentkit.core.ToolResult
import com.example.agentkit.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

/**
 * 断言 [block] 以 CancellationException 收尾（F1：取消绝不是失败，
 * 绝不允许被 catch (e: Exception) / runCatching 吞成 ToolResult 假失败）。
 *
 * 正常返回（没抛）→ AssertionError；抛出非取消异常 → 原样透传（check 记为 FAIL）。
 */
private suspend fun assertThrowsCancellation(cond: String, block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("expected CancellationException but returned normally: $cond")
    } catch (c: CancellationException) {
        // 期望路径：取消原样上抛（TimeoutCancellationException 等子类也走这里）
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

/* ------------------------------ F1：取消传播（W-7 审核必修） ------------------------------ */

/**
 * 把 [DeviceContextStore] 的前台包名播种为指定值（F1 harness 专用测试后门）。
 *
 * 动机：`confirmLaunch` 的短路判据读全局单例 [DeviceContextStore.snapshot]——
 * 空白走短路（零沉降），非空白才进入 1s 沉降 + 前台轮询。JVM 上构造不了 Android
 * Context 调 `updateForeground`，故用反射直接写私有字段做**测试播种**（被测行为
 * confirmLaunch/launchWithConfirmation 全是生产代码，不涉及此字段自身逻辑）。
 */
private fun seedForegroundPackage(pkg: String) {
    val f = DeviceContextStore::class.java.getDeclaredField("foregroundPackage")
    f.isAccessible = true
    f.set(null, pkg)
}

/**
 * F1 取消传播（W-7 审核必修项）：
 * 「settle/轮询期间协程被取消 → CancellationException 上抛而非返回 ToolResult」。
 *
 * 区分修前/修后的原理：修前形态把 confirmLaunch 包在 `try { startActivity(...);
 * confirmLaunch(...) } catch (e: Exception)` 里、execute 再包一层裸 runCatching——
 * 取消（CancellationException 继承自 Exception）被吞成 launch_failed / launch_error
 * 假失败，withTimeout 正常收口 → 断言「必须抛 CE」失败；修后形态（确认阶段移出
 * try + runCatchingRethrowCancel）取消原样上抛 → 断言通过。
 * 取消点用 withTimeout 精确落在「1s 沉降」与「250ms 轮询休眠」窗口内。
 */
private fun testCancellationPropagation() {
    println("\n== F1 取消传播：settle/轮询期间取消 → CancellationException 上抛（修前吞/修后抛）==")

    check("awaitForegroundPackage：轮询休眠期间取消 → 上抛 CE（不返回 false）") {
        runBlocking {
            assertThrowsCancellation("轮询休眠内取消") {
                withTimeout(150) { // 250ms 轮询间隔 → 150ms 必落在休眠窗口内
                    DeviceContextStore.awaitForegroundPackage(
                        targetPackage = "com.smile.gifmaker",
                        timeoutMs = 60_000,
                        pollIntervalMs = 250,
                        foregroundProvider = { "" }, // 永不到前台，保持轮询
                        sleeper = { ms -> delay(ms) } // 真 delay：可取消
                    )
                }
            }
        }
    }

    check("launchWithConfirmation：start 抛 CancellationException → 原样上抛（取消绝不是失败）") {
        runBlocking {
            assertThrowsCancellation("start 阶段取消") {
                launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) {
                    throw CancellationException("stop during startActivity")
                }
            }
        }
    }

    check("launchWithConfirmation：start 抛 SecurityException → launch_failed（startActivity 自身异常行为保持）") {
        runBlocking {
            var startCalls = 0
            val r = launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) {
                startCalls++
                throw SecurityException("Permission Denial: startActivity")
            }
            assertEquals("start 只调一次", 1, startCalls)
            assertTrue("not ok", !r.ok)
            assertEquals("code", "launch_failed", r.code)
            assertContains("message 含应用名", r.message, "快手")
            assertContains("hint 含手动路径", r.hint ?: "", "手动打开")
        }
    }

    check("launchWithConfirmation：start 正常 + 无障碍未喂数据（store 空白）→ 短路 ok(app_launched)，零沉降") {
        runBlocking {
            seedForegroundPackage("") // 从未喂过 → deviceContextEverUpdated=false → 短路
            var startCalls = 0
            val r = launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) { startCalls++ }
            assertEquals("start 已调用", 1, startCalls)
            assertTrue("ok", r.ok)
            assertEquals("code", "app_launched", r.code)
            assertContains("提示复核", r.message, "device_foreground_app")
        }
    }

    check("launchWithConfirmation【F1 核心】：settle 期间取消 → 上抛 CE，绝不变 launch_failed 假失败") {
        runBlocking {
            seedForegroundPackage("com.other.app") // 喂过数据 → 不走短路，进入 1s 沉降
            var startCalls = 0
            assertThrowsCancellation("settle 窗口内取消") {
                withTimeout(300) { // POST_LAUNCH_SETTLE_MS=1000 → 300ms 必落在沉降期内
                    launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) { startCalls++ }
                }
            }
            assertEquals("start 已执行（取消发生在 start 之后的沉降期）", 1, startCalls)
        }
    }

    check("launchWithConfirmation【F1 核心】：前台确认轮询期间取消 → 上抛 CE") {
        runBlocking {
            seedForegroundPackage("com.other.app") // 沉降后进入轮询，目标永不到前台
            assertThrowsCancellation("轮询窗口内取消") {
                withTimeout(1_400) { // 1000ms 沉降 + 250ms 轮询 → 1400ms 落在轮询休眠内
                    launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) { }
                }
            }
        }
    }

    check("launchWithConfirmation：未取消路径不变——沉降后目标到前台 → ok(app_launched)") {
        runBlocking {
            seedForegroundPackage("com.other.app")
            val switcher = launch {
                delay(200) // 模拟窗口切换：目标在沉降期内到前台
                seedForegroundPackage("com.smile.gifmaker")
            }
            val r = launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) { }
            switcher.join()
            assertTrue("ok", r.ok)
            assertEquals("code", "app_launched", r.code)
            assertContains("已确认前台", r.message, "已确认切换到前台")
        }
    }

    check("launchWithConfirmation：未取消路径不变——3s 内未确认 → ok(app_launched_unconfirmed)（意图已发出）") {
        runBlocking {
            seedForegroundPackage("com.other.app") // 永不变目标 → 轮询满 3s 超时
            val r = launchWithConfirmation(AppEntry("快手", "com.smile.gifmaker")) { }
            assertTrue("ok（意图确已发出）", r.ok)
            assertEquals("code", "app_launched_unconfirmed", r.code)
            assertContains("复核提示", r.message, "device_foreground_app")
        }
    }

    check("app_stop 执行段：shell runner 挂起期间取消 → 上抛 CE（不被 stop_error 吞）") {
        runBlocking {
            var runnerCalls = 0
            assertThrowsCancellation("runner 挂起期间取消") {
                withTimeout(150) {
                    // 复刻 AppStopTool.execute 的包裹形状：runner.exec 是挂起点
                    // （真实实现走终端会话池），必须经取消安全包装调用
                    runCatchingRethrowCancel(
                        block = {
                            runnerCalls++
                            val outcome = ShellCommandRunner { delay(10_000); null }
                                .exec("am force-stop com.smile.gifmaker")
                            appStopResult("快手", "com.smile.gifmaker", outcome)
                        },
                        onFailure = { ToolResult.fail("stop_error", "停止异常：${it.message}", "重试或告知用户手动停止") }
                    )
                }
            }
            assertEquals("runner 已调用一次", 1, runnerCalls)
        }
    }
}

/** F1：execute 层裸 runCatching 的取消安全替代（[runCatchingRethrowCancel]）三分支。 */
private fun testRunCatchingRethrowCancel() {
    println("\n== F1 runCatchingRethrowCancel（替代裸 runCatching，取消不被吞成 ToolResult）==")

    check("block 抛 CancellationException → 原样上抛，onFailure 不被调用") {
        runBlocking {
            var failureCalled = false
            assertThrowsCancellation("CE 上抛") {
                runCatchingRethrowCancel(
                    block = { throw CancellationException("user pressed stop") },
                    onFailure = {
                        failureCalled = true
                        ToolResult.fail("launch_error", "启动异常：${it.message}", "重试或告知用户手动打开")
                    }
                )
            }
            assertTrue("onFailure 未被调用（取消绝不是失败）", !failureCalled)
        }
    }

    check("block 抛业务异常（SecurityException）→ onFailure 接管（既有行为保持）") {
        runBlocking {
            val r = runCatchingRethrowCancel(
                block = { throw SecurityException("Permission Denial") },
                onFailure = { ToolResult.fail("launch_error", "启动异常：${it.message}", "重试或告知用户手动打开") }
            )
            assertTrue("not ok", !r.ok)
            assertEquals("code", "launch_error", r.code)
            assertContains("message 透传", r.message, "Permission Denial")
        }
    }

    check("block 正常返回 → 值透传，onFailure 不调用") {
        runBlocking {
            var failureCalled = false
            val r = runCatchingRethrowCancel(
                block = { ToolResult.ok("app_launched", "已启动") },
                onFailure = { failureCalled = true; ToolResult.fail("x", "y", "hint") }
            )
            assertTrue("ok", r.ok)
            assertEquals("code", "app_launched", r.code)
            assertTrue("onFailure 未被调用", !failureCalled)
        }
    }
}

/**
 * F2：`awaitForegroundPackage` 的 pollIntervalMs 非正入参防护。
 *
 * 修前：≤0 时 sleepMs 恒 ≤0、waitedMs 永不增长 → 无限忙等（看门狗都杀不死）。
 * 修后：入口 require fail-fast。断言用 withTimeout(2s) 兜底——即便跑在修前代码上
 * 也是「看门狗拦截」的 FAIL，不会把 harness 挂死。
 */
private fun testAwaitForegroundParamGuard() {
    println("\n== F2 awaitForegroundPackage 入参防护：pollIntervalMs ≤ 0 → fail-fast（修前无限循环）==")

    check("pollIntervalMs=0 → fail-fast 抛 IllegalArgumentException（修前：无限循环被看门狗拦）") {
        runBlocking {
            var msg: String? = null
            val outcome = try {
                withTimeout(2_000) {
                    DeviceContextStore.awaitForegroundPackage(
                        targetPackage = "com.smile.gifmaker",
                        timeoutMs = 1_000,
                        pollIntervalMs = 0,
                        foregroundProvider = { "" },
                        sleeper = { delay(1) } // 真挂起：修前无限循环也能被看门狗拦截，不挂死 harness
                    )
                    "returned-normally"
                }
            } catch (e: IllegalArgumentException) {
                msg = e.message
                "fail-fast"
            } catch (c: CancellationException) {
                "watchdog-killed-infinite-loop"
            }
            assertEquals("fail-fast（修前为 watchdog-killed-infinite-loop）", "fail-fast", outcome)
            assertContains("报错含参数名", msg, "pollIntervalMs")
            assertContains("报错为中文", msg, "轮询")
        }
    }

    check("pollIntervalMs=-250 → 同样 fail-fast（负间隔同样是无限循环）") {
        runBlocking {
            val outcome = try {
                withTimeout(2_000) {
                    DeviceContextStore.awaitForegroundPackage(
                        targetPackage = "com.smile.gifmaker",
                        timeoutMs = 1_000,
                        pollIntervalMs = -250,
                        foregroundProvider = { "" },
                        sleeper = { delay(1) }
                    )
                    "returned-normally"
                }
            } catch (e: IllegalArgumentException) {
                "fail-fast"
            } catch (c: CancellationException) {
                "watchdog-killed-infinite-loop"
            }
            assertEquals("fail-fast", "fail-fast", outcome)
        }
    }
}

/* ------------------------------ W-7 P0-1：静态应用字典 ------------------------------ */

private fun testAppNameDictionary() {
    println("\n== W-7 P0-1 静态应用字典（lookup / looksLikePackageName / resolveAppTargetWithDict）==")

    check("字典非空且含「快手」（W-7 起点案例）") {
        assertTrue("size ≥ 50", AppNameDictionary.size >= 50)
        assertEquals("快手", "com.smile.gifmaker", AppNameDictionary.lookup("快手"))
    }
    check("别名同包：B站/哔哩哔哩/bilibili → tv.danmaku.bili") {
        assertEquals("B站", "tv.danmaku.bili", AppNameDictionary.lookup("B站"))
        assertEquals("哔哩哔哩", "tv.danmaku.bili", AppNameDictionary.lookup("哔哩哔哩"))
        assertEquals("bilibili", "tv.danmaku.bili", AppNameDictionary.lookup("bilibili"))
    }
    check("拉丁名大小写不敏感 + trim：「 WeChat 」→ com.tencent.mm") {
        assertEquals("wechat", "com.tencent.mm", AppNameDictionary.lookup(" WeChat "))
        assertEquals("KUAISHOU", "com.smile.gifmaker", AppNameDictionary.lookup("KUAISHOU"))
    }
    check("未收录名称 → null（字典绝不是唯一真相）") {
        assertEquals("未收录", null, AppNameDictionary.lookup("快手极速版"))
        assertEquals("空串", null, AppNameDictionary.lookup("   "))
    }
    check("looksLikePackageName：形如包名才放行") {
        assertTrue("com.smile.gifmaker", looksLikePackageName("com.smile.gifmaker"))
        assertTrue("com.mm_2", looksLikePackageName("com.mm_2"))
        assertTrue("trim 后判定", looksLikePackageName("  com.tencent.mm  "))
        assertTrue("中文名不是包名", !looksLikePackageName("快手"))
        assertTrue("「打开微信」不是包名", !looksLikePackageName("打开微信"))
        assertTrue("无点号不是包名", !looksLikePackageName("tencentmm"))
        assertTrue("空段不是包名", !looksLikePackageName("com..mm"))
        assertTrue("段首数字不是包名", !looksLikePackageName("com.1mm"))
        assertTrue("含空白不是包名", !looksLikePackageName("com.mm x"))
        assertTrue("空串不是包名", !looksLikePackageName(""))
    }
    check("字典命中 + 候选无此包 → 合成条目直启（label 用入参名）") {
        val r = resolveAppTargetWithDict("B站", emptyList())
        assertTrue("Unique", r is AppResolution.Unique)
        assertEquals("pkg", "tv.danmaku.bili", (r as AppResolution.Unique).entry.packageName)
        assertEquals("label 用入参名", "B站", r.entry.label)
    }
    check("字典命中 + 候选有此包 → 用候选真实 label") {
        val r = resolveAppTargetWithDict("快手", FAKE_APPS)
        assertTrue("Unique", r is AppResolution.Unique)
        assertEquals("pkg", "com.smile.gifmaker", (r as AppResolution.Unique).entry.packageName)
        assertEquals("label 来自候选", "快手", (r as AppResolution.Unique).entry.label)
    }
    check("字典未收录 → 回落候选匹配：「腾讯」仍歧义（语义不变）") {
        val r = resolveAppTargetWithDict("腾讯", FAKE_APPS)
        assertTrue("Ambiguous", r is AppResolution.Ambiguous)
        assertEquals("两个候选", 2, (r as AppResolution.Ambiguous).candidates.size)
    }
    check("精确包名入参：字典未收录 → 候选精确包名命中") {
        val r = resolveAppTargetWithDict("com.tencent.mm", FAKE_APPS)
        assertTrue("Unique", r is AppResolution.Unique)
        assertEquals("pkg", "com.tencent.mm", (r as AppResolution.Unique).entry.packageName)
    }
    check("包名兜底：形如包名但候选没有 → Unique 直启") {
        val r = resolveAppTargetWithDict("com.bilibili.app.in", emptyList())
        assertTrue("Unique", r is AppResolution.Unique)
        assertEquals("pkg", "com.bilibili.app.in", (r as AppResolution.Unique).entry.packageName)
        assertEquals("label 用入参名", "com.bilibili.app.in", (r as AppResolution.Unique).entry.label)
    }
    check("空/空白 target → NotFound（入口防护）") {
        assertTrue("空串", resolveAppTargetWithDict("", FAKE_APPS) is AppResolution.NotFound)
        assertTrue("空白", resolveAppTargetWithDict("   ", FAKE_APPS) is AppResolution.NotFound)
    }
    check("字典只做精确键匹配：「抖音极速版」未收录 → NotFound（不模糊扩字典）") {
        val r = resolveAppTargetWithDict("抖音极速版", emptyList())
        assertTrue("NotFound", r is AppResolution.NotFound)
    }
}

/* ------------------------------ W-7 P0-3：前台事件配对 ------------------------------ */

private fun testForegroundEventPairing() {
    println("\n== W-7 P0-3 device_foreground_app 事件配对（pickForegroundPackageFromEvents）==")

    check("事件类型常量与系统同值（API29+ ACTIVITY_RESUMED=1）") {
        assertEquals("EVENT_ACTIVITY_RESUMED", 1, EVENT_ACTIVITY_RESUMED)
    }
    check("单个 RESUMED → 其包名") {
        val pkg = pickForegroundPackageFromEvents(
            listOf(UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.tencent.mm", 1_000L))
        )
        assertEquals("pkg", "com.tencent.mm", pkg)
    }
    check("多事件取时间序最后一个 RESUMED（输入乱序不预排序）") {
        val pkg = pickForegroundPackageFromEvents(
            listOf(
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.tencent.mm", 1_000L),
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.smile.gifmaker", 3_000L),
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "tv.danmaku.bili", 2_000L)
            )
        )
        assertEquals("最新时间戳者胜", "com.smile.gifmaker", pkg)
    }
    check("非 RESUMED（PAUSED=2 等）不参与配对") {
        val pkg = pickForegroundPackageFromEvents(
            listOf(
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.tencent.mm", 1_000L),
                UsageEventRecord(2, "com.smile.gifmaker", 9_000L) // 更晚的 PAUSED 不顶替
            )
        )
        assertEquals("仍是 mm", "com.tencent.mm", pkg)
    }
    check("只有非 RESUMED → null（调用方据此回落桶法）") {
        assertEquals("null", null, pickForegroundPackageFromEvents(listOf(UsageEventRecord(2, "com.tencent.mm", 1_000L))))
    }
    check("空列表 → null") {
        assertEquals("null", null, pickForegroundPackageFromEvents(emptyList()))
    }
    check("空包名事件被跳过（不污染结果）") {
        val pkg = pickForegroundPackageFromEvents(
            listOf(
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.tencent.mm", 1_000L),
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "  ", 9_000L)
            )
        )
        assertEquals("仍是 mm", "com.tencent.mm", pkg)
    }
    check("时间戳并列 → 列表中后者覆盖（与系统事件追加序一致）") {
        val pkg = pickForegroundPackageFromEvents(
            listOf(
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.tencent.mm", 1_000L),
                UsageEventRecord(EVENT_ACTIVITY_RESUMED, "com.smile.gifmaker", 1_000L)
            )
        )
        assertEquals("后者胜", "com.smile.gifmaker", pkg)
    }
}

/* ------------------------------ W-7 P1-1：ui_tree 陈旧窗口防护 ------------------------------ */

private fun testFreshWindowGuard() {
    println("\n== W-7 P1-1 ui_tree 陈旧窗口防护（shouldRetryForFreshWindow 判据）==")

    val self = "com.example.agentkit"
    val other = "com.smile.gifmaker"

    check("刚切换（elapsed<maxWait）且 root 为 null → 值得再等（窗口没建起来）") {
        assertTrue("wait", shouldRetryForFreshWindow(null, self, elapsedSinceSwitchMs = 100L, maxWaitMs = 1_500L))
    }
    check("刚切换且 root 仍是 AgentKit 自身 → 值得再等（审批弹窗/聊天页没退）") {
        assertTrue("wait", shouldRetryForFreshWindow(self, self, elapsedSinceSwitchMs = 100L, maxWaitMs = 1_500L))
    }
    check("刚切换但 root 已是目标应用 → 不等（窗口就绪，抓）") {
        assertTrue("no-wait", !shouldRetryForFreshWindow(other, self, elapsedSinceSwitchMs = 100L, maxWaitMs = 1_500L))
    }
    check("距切换已久（elapsed≥maxWait）→ 不等（没有新窗口要等）") {
        assertTrue("no-wait", !shouldRetryForFreshWindow(null, self, elapsedSinceSwitchMs = 2_000L, maxWaitMs = 1_500L))
        assertTrue("no-wait(自身包也如此)", !shouldRetryForFreshWindow(self, self, elapsedSinceSwitchMs = 9_999L, maxWaitMs = 1_500L))
    }
    check("maxWaitMs≤0 → 恒不等（fail-safe，禁无限等）") {
        assertTrue("0", !shouldRetryForFreshWindow(null, self, elapsedSinceSwitchMs = 0L, maxWaitMs = 0L))
        assertTrue("负值", !shouldRetryForFreshWindow(null, self, elapsedSinceSwitchMs = 0L, maxWaitMs = -1L))
    }
    check("elapsed=0（刚切换完立刻查）且 root 为 null → 等") {
        assertTrue("wait", shouldRetryForFreshWindow(null, self, elapsedSinceSwitchMs = 0L, maxWaitMs = 1_500L))
    }
    check("无窗口切换发生过（elapsed 极大）→ 不等（行为与旧版一致）") {
        val neverSwitched = 1_000_000_000_000L // 远大于任何 maxWaitMs
        assertTrue("no-wait", !shouldRetryForFreshWindow(null, self, elapsedSinceSwitchMs = neverSwitched, maxWaitMs = 1_500L))
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
    testCancellationPropagation()
    testRunCatchingRethrowCancel()
    testAwaitForegroundParamGuard()
    testAppNameDictionary()
    testForegroundEventPairing()
    testFreshWindowGuard()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
