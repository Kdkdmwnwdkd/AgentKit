/**
 * 真机第四轮「点立即开始 → 闪退」最后一刀 QA 行为验证 harness
 * （离线，无 JUnit，纯 main() + 手写断言）。
 *
 * **A 授权回调全路径兜底 + 逐步日志**：
 *  - [ScreenCaptureAuthGuard.run] 断言「任何 Throwable（含 Error）都不逃逸」，
 *    失败 → 原因摘要 + 可读文案（[ScreenCaptureAuthGuard.toastText]）；
 *  - [CaptureFlag.decide] 断言置位决策表：`startService` 失败时**不得**置捕获中；
 *  - [ScreenCaptureTrace.KEY_STEPS] 断言面包屑清单的数量与内容（崩点靠最后一条定位）。
 *
 * **B 崩溃可见性**：
 *  - [CrashLogger.unseenCrashFiles] 断言「输入文件列表 + 已读集合 → 未读清单」的纯函数语义。
 *
 * **C 接线（源码扫描，不加载 Android 类）**：
 *  - 每个面包屑步骤都在真实代码里被 `ScreenCaptureTrace.<STEP>` 引用；
 *  - 回调体走 [ScreenCaptureAuthGuard.run]；`_capturing` 走 [CaptureFlag.decide]；
 *  - 崩溃横幅 [AkCrashBanner] 定义 + 在 AppScaffold 顶部接线；
 *  - 日志页展示 lastError（与崩溃并排）。
 *
 * **D W-7 token 生命周期（第五轮修复，ForegroundServiceDidNotStartInTimeException 根治）**：
 *  - 源码扫描断言：token 获取已移桥层（服务**代码**零 `getMediaProjection` 调用）、
 *    服务先进前台再建重活、收摊统一走桥层 `releaseProjection`、
 *    授权回调即时取 token、桥层 token 不可得就不启动服务（5 秒计时不开始）。
 *
 * 运行方式见 scripts/qa/run-capture-path-verification.sh。
 */

import com.example.agentkit.automation.CaptureFlag
import com.example.agentkit.automation.ScreenCaptureAuthGuard
import com.example.agentkit.automation.ScreenCaptureTrace
import com.example.agentkit.core.CrashLogger
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

private fun assertFalse(name: String, cond: Boolean) {
    if (cond) throw AssertionError("expected false: $name")
}

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("$name: expected <$expected> but was <$actual>")
}

/** 断言 [haystack] 含 [needle]；便于一眼看出缺了哪一项。 */
private fun assertContains(name: String, haystack: String, needle: String) {
    if (!haystack.contains(needle)) {
        throw AssertionError("$name: 缺少 <$needle>")
    }
}

/* ------------------------------ 源码读取（纯文本扫描） ------------------------------ */

private const val APP_SRC = "/workspace/AgentKit/app/src/main/java/com/example/agentkit"

private fun readSource(rel: String): String = File(APP_SRC, rel).readText()

/* =============================== A1 授权回调兜底 =============================== */

private fun testAuthGuard() {
    println("— A1 授权回调全路径兜底（任何 Throwable 都不逃逸） —")

    check("成功执行：failure 为 null、succeeded=true") {
        val o = ScreenCaptureAuthGuard.run { /* 正常回调体 */ }
        assertTrue("succeeded", o.succeeded)
        assertEquals("failure", null, o.failure)
        assertEquals("cause", null, o.cause)
    }

    check("RuntimeException 被吞：不逃逸 + 带原因 + 带 cause") {
        val o = ScreenCaptureAuthGuard.run { throw RuntimeException("供应商炸了") }
        assertFalse("succeeded", o.succeeded)
        assertContains("failure 含原因", o.failure ?: "", "供应商炸了")
        assertTrue("cause 非空", o.cause is RuntimeException)
    }

    check("Error（含 OOM/SecurityException 家族）同样被吞：绝不上抛") {
        var escaped = false
        val o = try {
            ScreenCaptureAuthGuard.run { throw OutOfMemoryError("内存炸了") }
        } catch (t: Throwable) {
            escaped = true
            null
        }
        assertFalse("不得逃逸", escaped)
        assertTrue("failure 非空", !o?.failure.isNullOrBlank())
    }

    check("describe 兜底：null 异常 → 未知原因（绝不产生 null）") {
        val o = ScreenCaptureAuthGuard.run { throw IllegalStateException("") }
        // message 为空时 describe 退化为类名，仍非空
        assertTrue("类名兜底", !o.failure.isNullOrBlank())
    }

    check("失败 → 可读 Toast 文案（含「屏幕捕获启动失败」与「可重试」与原因）") {
        val o = ScreenCaptureAuthGuard.run { throw SecurityException("拒绝") }
        val toast = ScreenCaptureAuthGuard.toastText(o)
        assertTrue("toast 非空", toast != null)
        assertContains("文案前缀", toast!!, "屏幕捕获启动失败")
        assertContains("可重试", toast, "可重试")
        assertContains("带原因", toast, "拒绝")
    }

    check("成功 → 无需 Toast（toastText 返回 null）") {
        val o = ScreenCaptureAuthGuard.run { }
        assertEquals("toast", null, ScreenCaptureAuthGuard.toastText(o))
    }
}

/* =============================== A3 _capturing 置位 =============================== */

private fun testCaptureFlag() {
    println("— A3 _capturing 置位决策表（startService 失败不得置 true） —")

    check("startService 成功 → 置捕获中，无错误") {
        val d = CaptureFlag.decide(startSucceeded = true, lastError = null)
        assertTrue("capturing", d.capturing)
        assertEquals("errorMessage", null, d.errorMessage)
    }

    check("startService 失败 → 不置捕获中，带 lastError") {
        val d = CaptureFlag.decide(startSucceeded = false, lastError = "系统拒绝")
        assertFalse("capturing", d.capturing)
        assertEquals("errorMessage", "系统拒绝", d.errorMessage)
    }

    check("startService 失败且 lastError 为空 → 兜底「未知原因」（绝不静默）") {
        val d = CaptureFlag.decide(startSucceeded = false, lastError = null)
        assertFalse("capturing", d.capturing)
        assertEquals("errorMessage", "未知原因", d.errorMessage)
    }

    check("startService 失败且 lastError 为空白 → 兜底「未知原因」") {
        val d = CaptureFlag.decide(startSucceeded = false, lastError = "   ")
        assertFalse("capturing", d.capturing)
        assertEquals("errorMessage", "未知原因", d.errorMessage)
    }
}

/* =============================== A4 面包屑完整性 =============================== */

private fun testBreadcrumbs() {
    println("— A4 逐步日志面包屑完整性 —")

    val expected = listOf(
        "授权回调进入",
        "缓存授权结果",
        "启动前台服务",
        "服务 onCreate",
        "服务 onStartCommand",
        "取 MediaProjection token",
        "屏幕尺寸",
        "ImageReader 创建",
        "createVirtualDisplay",
        "registerCallback",
        "进前台 startForeground",
        "首次拿到帧"
    )

    check("KEY_STEPS 数量 = 12（授权→投影全链路）") {
        assertEquals("size", 12, ScreenCaptureTrace.KEY_STEPS.size)
    }

    check("KEY_STEPS 内容与顺序完全匹配") {
        assertEquals("内容", expected, ScreenCaptureTrace.KEY_STEPS)
    }

    check("KEY_STEPS 无重复（每条面包屑唯一定位一个步骤）") {
        assertEquals("distinct", ScreenCaptureTrace.KEY_STEPS.size, ScreenCaptureTrace.KEY_STEPS.distinct().size)
    }
}

/* =============================== A4/C 接线（源码扫描） =============================== */

private fun testWiring() {
    println("— A4/C 接线：面包屑与兜底被真实代码引用（源码扫描） —")

    val components = readSource("ui/Components.kt")
    val viewModel = readSource("ui/ChatViewModel.kt")
    val bridge = readSource("automation/ScreenshotBridge.kt")
    val service = readSource("automation/ScreenCaptureService.kt")
    val app = readSource("AgentKitApplication.kt")
    val scaffold = readSource("ui/AppScaffold.kt")
    val logs = readSource("ui/LogsScreen.kt")
    val crashLogger = readSource("core/CrashLogger.kt")

    // 每个面包屑步骤都必须被真实代码发出（step → 应该出现在哪个文件）
    val stepSites = mapOf(
        "ScreenCaptureTrace.CB_ENTER" to components,
        "ScreenCaptureTrace.CACHE_PERMISSION" to viewModel,
        "ScreenCaptureTrace.START_SERVICE" to viewModel,
        "ScreenCaptureTrace.SERVICE_ON_CREATE" to service,
        "ScreenCaptureTrace.SERVICE_ON_START" to service,
        // W-7 修复后：取 token 移到桥层 ScreenshotBridge.obtainProjection
        // （服务生命周期之前，失败不启动服务），OBTAIN_TOKEN 面包屑发出点随之改为 bridge
        "ScreenCaptureTrace.OBTAIN_TOKEN" to bridge,
        "ScreenCaptureTrace.SIZE" to service,
        "ScreenCaptureTrace.IMAGE_READER" to service,
        "ScreenCaptureTrace.CREATE_VIRTUAL_DISPLAY" to service,
        "ScreenCaptureTrace.REGISTER_CALLBACK" to service,
        "ScreenCaptureTrace.START_FOREGROUND" to service,
        "ScreenCaptureTrace.FIRST_FRAME" to service
    )
    for ((marker, src) in stepSites) {
        check("面包屑发出：$marker") { assertContains("marker", src, marker) }
    }

    check("回调体整体走 ScreenCaptureAuthGuard.run（异常不逃逸）") {
        assertContains("Components", components, "ScreenCaptureAuthGuard.run")
    }

    check("Component 启动按钮也兜底（弹系统框同样 try/catch）") {
        // launcher 返回的 lambda 里也用了 run（出现 ≥2 次）
        val hits = Regex("ScreenCaptureAuthGuard\\.run").findAll(components).count()
        assertTrue("≥2 处兜底，实际 $hits", hits >= 2)
    }

    check("_capturing 走 CaptureFlag.decide（失败不置 true）") {
        assertContains("decide 调用", viewModel, "CaptureFlag.decide")
        assertContains("置位用 decision", viewModel, "_capturing.value = decision.capturing")
    }

    check("ScreenshotBridge.startService 打印面包屑 + 失败落 lastError") {
        assertContains("startService 面包屑", bridge, "ScreenCaptureTrace.log")
        assertContains("失败落 lastError", bridge, "logLastError")
    }

    check("崩溃横幅 AkCrashBanner 在 Components 定义") {
        assertContains("定义", components, "internal fun AkCrashBanner")
        assertContains("标记已读", components, "CrashLogger.markAllSeen")
    }

    check("崩溃横幅在 AppScaffold 顶部接线（点击跳日志页）") {
        assertContains("接线", scaffold, "AkCrashBanner(")
        assertContains("跳日志页", scaffold, "navigateTo(AppDest.LOGS)")
    }

    check("Application 启动即检测未查看崩溃") {
        assertContains("检测", app, "CrashLogger.unseenCrashRecords")
    }

    check("日志页展示 lastError（与崩溃并排）") {
        assertContains("读取 lastError", logs, "ScreenshotBridge.lastError")
    }

    check("CrashLogger 提供未读筛选 + 已读标记") {
        assertContains("纯函数", crashLogger, "internal fun unseenCrashFiles")
        assertContains("已读标记", crashLogger, "fun markAllSeen")
        assertContains("SharedPreferences", crashLogger, "getSharedPreferences")
    }
}

/* =============================== B 未读崩溃筛选 =============================== */

private fun testUnseenCrashFiles() {
    println("— B 未查看崩溃文件筛选（纯函数） —")

    val a = "crash-20240101-000000.txt"
    val b = "crash-20240102-000000.txt"
    val c = "crash-20240103-120000.txt"

    check("已读子集 → 只返回未读，且最新在前") {
        val unseen = CrashLogger.unseenCrashFiles(listOf(a, b, c), setOf(a))
        assertEquals("unseen", listOf(c, b), unseen)
    }

    check("全部未读（已读为空）→ 按时间倒序全返回") {
        val unseen = CrashLogger.unseenCrashFiles(listOf(a, b, c), emptySet())
        assertEquals("unseen", listOf(c, b, a), unseen)
    }

    check("全部已读 → 返回空清单（横幅不打扰）") {
        val unseen = CrashLogger.unseenCrashFiles(listOf(a, b, c), setOf(a, b, c))
        assertTrue("empty", unseen.isEmpty())
    }

    check("非崩溃文件名一律忽略（不误报）") {
        val unseen = CrashLogger.unseenCrashFiles(
            listOf(a, "readme.txt", "agent-20240103.log", "crash-bad.txt"),
            emptySet()
        )
        // crash-bad.txt 前缀/后缀合法，属崩溃命名 → 会被计入（宽松识别，符合 isCrashFileName）
        assertEquals("只认崩溃文件", listOf("crash-bad.txt", a), unseen)
    }

    check("空目录 → 空清单（静默降级）") {
        assertTrue("empty", CrashLogger.unseenCrashFiles(emptyList(), emptySet()).isEmpty())
    }

    check("已读集合里含已删除文件 → 不影响现存筛选") {
        val unseen = CrashLogger.unseenCrashFiles(listOf(a, b), setOf("crash-20200101-000000.txt"))
        assertEquals("unseen", listOf(b, a), unseen)
    }
}

/* =============================== D W-7 token 生命周期（第五轮修复） =============================== */

private fun testW7TokenLifecycle() {
    println("— D W-7：token 服务外预取 + 一次性生命周期（ForegroundServiceDidNotStartInTimeException 根治） —")

    val bridge = readSource("automation/ScreenshotBridge.kt")
    val service = readSource("automation/ScreenCaptureService.kt")
    val viewModel = readSource("ui/ChatViewModel.kt")

    check("D1 桥层提供 obtainProjection（服务生命周期之前取 token）") {
        assertContains("定义", bridge, "fun obtainProjection(context: Context): Boolean")
    }

    check("D2 桥层提供 releaseProjection（token 一次性：停止即作废）") {
        assertContains("定义", bridge, "fun releaseProjection()")
    }

    check("D3 服务**代码**不再调用 getMediaProjection（token 只在桥层取一次；KDoc 提及不算）") {
        val codeLines = service.lines().filter { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
        }
        assertFalse("服务代码内取 token", codeLines.any { it.contains("getMediaProjection(") })
    }

    check("D4 服务从桥读 token（ScreenshotBridge.mediaProjection）") {
        assertContains("读取", service, "ScreenshotBridge.mediaProjection")
    }

    check("D5 服务内 startForeground 早于 setupCapture（先进前台，再建重活）") {
        val fgIdx = service.indexOf("startForegroundSafely(projection")
        val capIdx = service.indexOf("setupCapture(projection")
        assertTrue("调用点均存在", fgIdx >= 0 && capIdx >= 0)
        assertTrue("startForeground($fgIdx) 早于 setupCapture($capIdx)", fgIdx < capIdx)
    }

    check("D6 服务收摊统一走桥层 releaseProjection（failSafe + onDestroy 两处）") {
        val hits = Regex("ScreenshotBridge\\.releaseProjection\\(\\)").findAll(service).count()
        assertTrue("实际 $hits 处（应 ≥2）", hits >= 2)
    }

    check("D7 授权回调后立即取 token（ChatViewModel → ScreenshotBridge.obtainProjection(app)）") {
        assertContains("调用", viewModel, "ScreenshotBridge.obtainProjection(app)")
    }

    check("D8 桥层 startService：token 不可得就不启动服务（5 秒计时不开始）") {
        val startSvc = bridge
            .substringAfter("fun startService(context: Context): Boolean")
            .substringBefore("fun stopService(")
        assertContains("兜调 obtainProjection", startSvc, "mediaProjection == null && !obtainProjection(context)")
        assertContains("token 不可得即返回 false", startSvc, "return false")
    }

    check("D9 setPermission 先清旧投影再存新授权（防新旧 resultCode 串味）") {
        val setPerm = bridge
            .substringAfter("fun setPermission(")
            .substringBefore("fun obtainProjection(")
        assertContains("先 releaseProjection", setPerm, "releaseProjection()")
    }
}

/* =============================== 主入口 =============================== */

fun main() {
    println("========== CapturePathVerification（真机第四轮最后一刀 + 第五轮 W-7） ==========")
    testAuthGuard()
    testCaptureFlag()
    testBreadcrumbs()
    testWiring()
    testW7TokenLifecycle()
    testUnseenCrashFiles()

    println("------------------------------------------------------------------")
    println("PASS=$passed  FAIL=${failures.size}")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        throw AssertionError("存在 ${failures.size} 项失败")
    }
    println("全部通过 ✅")
}
