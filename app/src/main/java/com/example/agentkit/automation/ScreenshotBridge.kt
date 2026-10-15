package com.example.agentkit.automation

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.example.agentkit.core.AgentLogger

/**
 * 屏幕捕获启动顺序策略（纯函数，零 Android 依赖——QA harness 可直接断言步骤序列）。
 *
 * **为什么需要它（机实测 Bug 2 修复）**：Android 14（API 34，本项目 targetSdk 34）
 * 对 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 有**强顺序约束**——
 * `startForeground(id, notification, type=MEDIA_PROJECTION)` 时，系统要求本次抓取的
 * MediaProjection token **已经被取出**（`MediaProjectionManager.getMediaProjection()`
 * 已带着用户同意的 resultCode/data 调用过且成功）；否则抛 `SecurityException`。
 * 老流程是「`startForegroundService` → 服务里 `startForeground(mediaProjection)` →
 * 之后才在 `onStartCommand` 里 `getMediaProjection()`」，顺序颠倒 → 授权返回后一进
 * App 就崩（用户看到的现象："给了权限，回到应用就闪退"）。
 *
 * 正确顺序：**先取 token，再以 mediaProjection 类型进前台**（Android 10～13 只是建议
 * 顺序，同样无副作用，故所有版本走同一条路径，避免分叉出第二套行为）。
 */
object ScreenCaptureStartPolicy {

    /** 启动过程中的一步（顺序敏感）。 */
    enum class Step {
        /** 由用户已同意的 resultCode + data 取出 MediaProjection token（必须先做）。 */
        OBTAIN_TOKEN,

        /** 以 mediaProjection 类型（Android 10+）启动前台服务。 */
        START_FOREGROUND,

        /** 放弃：没有可用来取 token 的授权数据，启动前台会立刻被系统拒绝。 */
        ABORT
    }

    /**
     * 依据「是否已有 MediaProjection token」给出应当执行的步骤序列。
     *
     * @param hasToken 是否拿到了用户同意的 resultCode + data（token 可取出）。
     * @param sdkInt 设备 API 级别；≥34（Android 14）该顺序是**强制**的，29～33 是建议，
     *   <29 无类型参数（直接用 [Step.START_FOREGROUND] 无类型版）——三者输出一致，
     *   参数留在这里是为了将来某天某版本放开约束时能在此一处调整。
     */
    fun plan(hasToken: Boolean, sdkInt: Int): List<Step> {
        check(sdkInt >= 0) { "sdkInt 非法：$sdkInt" }
        return if (hasToken) listOf(Step.OBTAIN_TOKEN, Step.START_FOREGROUND) else listOf(Step.ABORT)
    }

    /**
     * 失败原因摘要：异常的类名 + message（截断到 120 字符，防止日志爆掉）。
     * 纯函数，给 [failureMessage] 与 [guard] 共用。
     */
    fun describe(t: Throwable?): String {
        if (t == null) return "未知原因"
        val name = t.javaClass.simpleName.ifBlank { "Throwable" }
        val detail = t.message?.trim()?.take(120)?.ifBlank { null }
        return if (detail == null) name else "$name：$detail"
    }

    /**
     * 面向用户的失败文案：**必须带原因、必须可 actionable**（告诉用户能重试），
     * 杜绝「点了没反应 / 静默失败」。UI 层（Components.kt 的权限入口）与日志共用。
     */
    fun failureMessage(reason: String?): String {
        val cause = reason?.trim()?.takeIf { it.isNotBlank() } ?: "未知原因"
        return "屏幕捕获启动失败：$cause，可重试"
    }

    /**
     * 兜底执行：**任何** Throwable（含 Error / SecurityException / 厂商魔改 ROM 上的
     * 奇葩异常）都被吞在这里转换成 [GuardResult]，绝不上抛——这是 Bug 2 的底线修复：
     * 前台服务的启动失败必须「可见 + 可知」，但**绝不能让进程崩掉**。
     *
     * @param block 可能抛异常的启动动作（真实场景下是 startForeground / startService）。
     * @return 成功时 [GuardResult.failure] 为 null；失败时带上原因摘要与原始异常。
     */
    fun guard(block: () -> Unit): GuardResult =
        try {
            block()
            GuardResult(null, null)
        } catch (t: Throwable) {
            GuardResult(describe(t), t)
        }

    /** [guard] 的结果：[failure] 非空即失败；[cause] 供调用方把堆栈交给日志。 */
    data class GuardResult(val failure: String?, val cause: Throwable?) {
        /** 是否成功执行（无异常）。 */
        val succeeded: Boolean get() = failure == null
    }
}

/**
 * 屏幕捕获**运行时**防御策略（纯函数，零 Android 依赖——QA harness 可直接断言）。
 *
 * **为什么还需要它（真机第四轮 Bug「开屏幕捕捉就闪退」第二刀）**：
 * 上一轮只修了「取 token → 进前台」的**顺序**与 [ScreenCaptureStartPolicy.guard] 兜底，
 * 但用户仍然闪退——说明崩点在顺序之外：`createVirtualDisplay` / `ImageReader.newInstance` /
 * `setOnImageAvailableListener` / `acquireLatestImage` / `stopSelf` 以及**尺寸为 0** 这些
 * 位置都可能抛（SecurityException / IllegalArgumentException / IllegalStateException /
 * 厂商 ROM 的奇葩异常），而它们当时全都**裸调用**，异常一路逃到系统 → 进程死。
 *
 * 本对象把两件事抽成可断言的纯函数：
 *  1. [sanitizeSize]：尺寸/密度参数防御（0、负数、超大分辨率）；
 *  2. [cleanupPlan]：任何异常发生后应执行的**安全停止步骤序列**（顺序敏感）。
 *
 * 服务层只负责「照着计划执行」，判断逻辑集中在这里，QA 无需加载 Android 类即可验证。
 */
object ScreenCaptureGuard {

    /** 捕获流程的阶段（仅用于日志/上报文案与步骤计划的入参，步骤集合本身与阶段无关）。 */
    enum class Stage {
        /** 取 MediaProjection token / 建 ImageReader / 建 VirtualDisplay。 */
        INIT_PROJECTION,

        /** 进前台。 */
        START_FOREGROUND,

        /** 抓一帧。 */
        CAPTURE,

        /** 用户从控制中心停止投屏（MediaProjection.Callback.onStop）。 */
        PROJECTION_STOPPED,

        /** 服务销毁。 */
        DESTROY
    }

    /** 当前持有的、需要显式释放的资源（决定 [cleanupPlan] 里出现哪些释放步骤）。 */
    enum class Resource {
        /** MediaProjection（需 stop()）。 */
        PROJECTION,

        /** VirtualDisplay（需 release()）。 */
        VIRTUAL_DISPLAY,

        /** ImageReader（需 close()）。 */
        IMAGE_READER
    }

    /**
     * 安全停止的一步（**顺序敏感**：先释放下游资源，再停投影，最后停服务）。
     */
    enum class CleanupStep {
        /** `VirtualDisplay.release()`。 */
        RELEASE_VIRTUAL_DISPLAY,

        /** `ImageReader.close()`。 */
        CLOSE_IMAGE_READER,

        /** `MediaProjection.stop()`（并注销 Callback）。 */
        STOP_PROJECTION,

        /** 清 `activeInstance` / `isCapturing`，避免悬挂实例与泄漏。 */
        CLEAR_STATE,

        /** 写入 `ScreenshotBridge.lastError`（UI 可见的唯一失败出口）。 */
        REPORT_LAST_ERROR,

        /** `AgentLogger.e(...)` 带堆栈落日志。 */
        LOG_ERROR,

        /** `stopSelf()` 收摊；任何一步失败都不能阻止它执行。 */
        STOP_SELF
    }

    /** 分辨率上限：超过会撞 ImageReader/硬件合成器上限（IllegalArgumentException 高发区）。 */
    const val MAX_DIM: Int = 4096

    /** 密度兜底值：`metrics.densityDpi` 为 0 或负数时用（0 会让 VirtualDisplay 直接抛）。 */
    const val DEFAULT_DENSITY: Int = 320

    /** 尺寸校验结果：[ok] 为 false 时**不得**继续建 VirtualDisplay；[width]/[height]/[density] 是**应当使用**的值。 */
    data class SizeCheck(
        val ok: Boolean,
        val width: Int,
        val height: Int,
        val density: Int,
        val reason: String? = null,
        val clamped: Boolean = false
    )

    /**
     * 尺寸/密度防御（纯函数）。
     *
     * - 宽或高 ≤ 0（真机冷启动/分屏/折叠屏切换瞬间 `displayMetrics` 会给 0）→ [ok] = false，
     *   调用方**必须**走安全停止，绝不能拿 0 去建 ImageReader（必崩）；
     * - 密度 ≤ 0 → 回退 [DEFAULT_DENSITY]（不影响可用性）；
     * - 任一边超过 [MAX_DIM] → **等比缩放**到上限内（保持宽高比，避免变形），[clamped] = true。
     */
    fun sanitizeSize(width: Int, height: Int, density: Int): SizeCheck {
        if (width <= 0 || height <= 0) {
            return SizeCheck(
                ok = false,
                width = width,
                height = height,
                density = density,
                reason = "屏幕尺寸非法（width=$width height=$height）"
            )
        }
        val safeDensity = if (density > 0) density else DEFAULT_DENSITY
        val maxSide = maxOf(width, height)
        if (maxSide <= MAX_DIM) {
            return SizeCheck(ok = true, width = width, height = height, density = safeDensity)
        }
        val scale = MAX_DIM.toDouble() / maxSide.toDouble()
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return SizeCheck(
            ok = true,
            width = w,
            height = h,
            density = safeDensity,
            clamped = true
        )
    }

    /**
     * 异常发生后的**安全停止步骤序列**（纯函数，顺序敏感）。
     *
     * 顺序理由：VirtualDisplay 依赖 ImageReader 的 Surface → 先 release 再 close；
     * 两者都依赖 Projection → 再 stop；随后立刻把 `activeInstance`/`isCapturing` 清掉
     * （防止外部拿到悬挂实例）；**先上报再记日志**（保证 UI 一定能看到原因，日志写失败
     * 不影响 lastError）；最后 `stopSelf()`（放在最后，前面的清理必须有机会执行）。
     *
     * @param stage 出错阶段（决定日志文案，不影响步骤集合）。
     * @param held 当前持有的资源；未持有的资源不产生对应释放步骤（避免 NPE 与重复释放）。
     */
    fun cleanupPlan(stage: Stage, held: Set<Resource> = emptySet()): List<CleanupStep> {
        val steps = mutableListOf<CleanupStep>()
        if (Resource.VIRTUAL_DISPLAY in held) steps += CleanupStep.RELEASE_VIRTUAL_DISPLAY
        if (Resource.IMAGE_READER in held) steps += CleanupStep.CLOSE_IMAGE_READER
        if (Resource.PROJECTION in held) steps += CleanupStep.STOP_PROJECTION
        steps += CleanupStep.CLEAR_STATE
        steps += CleanupStep.REPORT_LAST_ERROR
        steps += CleanupStep.LOG_ERROR
        steps += CleanupStep.STOP_SELF
        // stage 参与计算（保证调用方传参被真实使用，也便于将来按阶段裁剪步骤）
        return if (stage == Stage.DESTROY) steps.filter { it != CleanupStep.STOP_SELF } else steps
    }

    /** 阶段 → 中文名（日志文案用）。 */
    fun stageLabel(stage: Stage): String = when (stage) {
        Stage.INIT_PROJECTION -> "初始化投影"
        Stage.START_FOREGROUND -> "启动前台"
        Stage.CAPTURE -> "抓取屏幕帧"
        Stage.PROJECTION_STOPPED -> "用户停止投屏"
        Stage.DESTROY -> "服务销毁"
    }

    /** 异常摘要（复用 [ScreenCaptureStartPolicy.describe]，保证全链路文案同一来源）。 */
    fun describe(t: Throwable?): String = ScreenCaptureStartPolicy.describe(t)

    /**
     * **带返回值**的兜底执行：与 [ScreenCaptureStartPolicy.guard] 同 philosophy，
     * 但保留成功时的返回值（建对象这种「要么拿到、要么失败」的动作需要它）。
     *
     * @return [GuardValue.value] 非空即成功；失败时 [GuardValue.failure] 为原因摘要。
     */
    fun <T> guardValue(block: () -> T): GuardValue<T> =
        try {
            GuardValue(block(), null, null)
        } catch (t: Throwable) {
            GuardValue(null, describe(t), t)
        }

    /**
     * 兜底执行（无返回值版）：与 [ScreenCaptureStartPolicy.guard] 同一实现，
     * 放在这里是为了让服务层「守卫」只 import 一个名字（可读性），
     * 失败原因文案也走同一个 [describe]。
     */
    fun guard(block: () -> Unit): ScreenCaptureStartPolicy.GuardResult =
        ScreenCaptureStartPolicy.guard(block)

    /** [guardValue] 的结果。 */
    data class GuardValue<T>(val value: T?, val failure: String?, val cause: Throwable?) {
        /** 是否成功拿到非空值。 */
        val succeeded: Boolean get() = value != null
    }
}

/**
 * 屏幕捕获桥：持有 MediaProjection 授权结果（resultCode + data），
 * 供 UI 在用户授予屏幕捕获权限后启动前台服务，并对外暴露捕获状态。
 *
 * 与 AutomationBridge 同构——执行层（服务实例、授权结果）与 UI/工具层解耦，
 * 工具只依赖本桥，不直接持有 Android 服务实例。
 *
 * **Bug 2 修复后的两条新契约**：
 *  1. [startService] 返回成功/失败布尔，并通过 [lastError] 暴露可读原因——
 *     UI 必须据此给「屏幕捕获启动失败：<原因>，可重试」，不允许静默；
 *  2. 启动动作整体包在 [ScreenCaptureStartPolicy.guard] 里，`startForegroundService`
 *     自身（后台启动限制 / SecurityException）也不允许把进程带崩。
 */
object ScreenshotBridge {
    /** MediaProjection 授权结果码。 */
    var resultCode: Int = 0

    /** MediaProjection 授权返回的 Intent（含投影 token）。 */
    var permissionData: Intent? = null

    /**
     * 已取出的 MediaProjection 实例（**一次性 token，取到即消费**）。
     *
     * 第五轮 Bug 修复（ForegroundServiceDidNotStartInTimeException，崩 4 次）的核心：
     * token 获取必须发生在服务生命周期**之前**——放在 [startService] 前由桥层完成，
     * 失败就不启动服务（`startForegroundService` 的 5 秒超时计时根本不会开始，
     * 从源头消除「服务收摊但 startForeground 从未执行 → 系统杀进程」）。
     * 老代码在服务 onStartCommand 里现取 token，重复开启（token 已消费）时
     * getMediaProjection 失败 → failSafe 直接收摊 → 5 秒超时崩进程。
     */
    @Volatile
    var mediaProjection: MediaProjection? = null

    /** 当前是否正在捕获屏幕。 */
    var isCapturing: Boolean = false

    /**
     * 最近一次启动失败的可读原因（null = 无一次性失败）。
     * UI 在启动失败时读取它拼成「屏幕捕获启动失败：<原因>，可重试」，读完即由调用方清掉。
     */
    @Volatile
    var lastError: String? = null

    /**
     * 统一失败出口：把「启动失败」落成 [lastError]（供 UI 显示）并记 ERROR 日志。
     * 服务层与桥自身共用，保证文案唯一来源 = [ScreenCaptureStartPolicy.failureMessage]。
     */
    fun reportFailure(reason: String) {
        val msg = ScreenCaptureStartPolicy.failureMessage(reason)
        lastError = msg
        isCapturing = false
        AgentLogger.e("ScreenCapture", msg)
        logLastError(msg)
    }

    /**
     * 真机第三轮 Bug C（只做确认 + 统一留痕）：用户靠日志报现象，故每次 [lastError]
     * 落成非空值都补一条**统一关键字**的 WARN（原有各处日志前缀不一，不便 grep）。
     */
    private fun logLastError(value: String) =
        AgentLogger.w("ScreenCapture", "lastError 落库：${AgentLogger.sanitize(value).take(200)}")

    /**
     * 清掉上一次的一次性失败文案：捕获**成功建立**后调用，
     * 避免上一次的失败横幅一直挂在 UI 上（第四轮加固新增）。
     */
    fun clearError() {
        if (lastError != null) {
            AgentLogger.i("ScreenCapture", "lastError 已清除（捕获恢复正常）")
        }
        lastError = null
    }

    fun setPermission(resultCode: Int, data: Intent?) {
        // 新授权到达：旧 projection 作废（token 一次性），先停旧的和新授权配对，
        // 防止「拿着新 resultCode 用着旧 projection」的串味。
        releaseProjection()
        this.resultCode = resultCode
        this.permissionData = data
    }

    /**
     * 立即取出 MediaProjection token（**服务生命周期之前**，第五轮 Bug 修复核心）。
     *
     * 授权回调到来时调用最稳妥（token 最新鲜）；[startService] 也会在必要时兜调。
     * 失败只落 [lastError] + 返回 false，**绝不抛异常**——调用方（UI）据此引导
     * 用户重新授权，服务永远不会被拉起，5 秒超时计时不会开始。
     */
    fun obtainProjection(context: Context): Boolean {
        val data = permissionData
        if (data == null || resultCode == 0) {
            lastError = ScreenCaptureStartPolicy.failureMessage("尚未取得屏幕捕获授权")
            logLastError(lastError!!)
            return false
        }
        val failure = ScreenCaptureStartPolicy.guard {
            mediaProjection =
                context.getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(resultCode, data)
        }
        val reason = failure.failure
        if (reason != null) {
            mediaProjection = null
            lastError = ScreenCaptureStartPolicy.failureMessage("获取屏幕捕获令牌失败：$reason")
            AgentLogger.e("ScreenCapture", "getMediaProjection 失败：$reason", failure.cause)
            logLastError(lastError!!)
            return false
        }
        if (mediaProjection == null) {
            lastError = ScreenCaptureStartPolicy.failureMessage("获取屏幕捕获令牌失败：系统返回空")
            logLastError(lastError!!)
            return false
        }
        lastError = null
        AgentLogger.i("ScreenCapture", "getMediaProjection 成功（token 已取出，待启动服务）")
        ScreenCaptureTrace.log(ScreenCaptureTrace.OBTAIN_TOKEN, "桥层取 token 成功")
        return true
    }

    /** 停掉并清空当前 projection（token 一次性：停止后再开启必须重新授权）。 */
    fun releaseProjection() {
        mediaProjection?.let { p ->
            ScreenCaptureStartPolicy.guard { p.stop() }
        }
        mediaProjection = null
    }

    /**
     * 启动前台捕获服务（第五轮 Bug 修复后的契约：**token 必须在服务外取好**）。
     *
     * 前置条件（按序检查，任一不满足都不启动服务——`startForegroundService`
     * 的 5 秒超时计时不会开始，从源头杜绝 ForegroundServiceDidNotStartInTimeException）：
     *  1. [mediaProjection] 已由 [obtainProjection] 取好（授权回调后立即取，最新鲜）；
     *     没有则兜调一次 [obtainProjection]，仍失败 → 引导重新授权；
     *  2. 已在捕获中（[isCapturing] 且服务实例活着）→ 幂等返回 true，不重复拉起。
     *
     * @return true = 已把 Intent 交给系统且未出错；false = 未授权 / token 失效，见 [lastError]。
     * **绝不抛异常**：任何失败都转成 false + [lastError]（Bug 2 底线）。
     */
    fun startService(context: Context): Boolean {
        // ① 幂等：已在捕获就不重复拉起（重复 startForegroundService 会让服务
        //    二次 onStartCommand，老代码在那里二次消费 token 崩进程）
        if (isCapturing && ScreenCaptureService.activeInstance != null) {
            AgentLogger.i("ScreenCapture", "已在捕获中，跳过重复启动")
            ScreenCaptureTrace.log(ScreenCaptureTrace.START_SERVICE, "跳过（已在捕获中）")
            return true
        }
        // ② token 兜底：授权回调里没取成功（或已被停止清掉）时，这里最后试一次
        if (mediaProjection == null && !obtainProjection(context)) {
            ScreenCaptureTrace.log(ScreenCaptureTrace.START_SERVICE, "失败（token 不可得）")
            isCapturing = false
            return false
        }
        ScreenCaptureTrace.log(
            ScreenCaptureTrace.START_SERVICE,
            "token 已就绪，请求 startForegroundService"
        )
        val failure = ScreenCaptureStartPolicy.guard {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.startForegroundService(intent)
        }
        val reason = failure.failure
        if (reason != null) {
            lastError = ScreenCaptureStartPolicy.failureMessage(reason)
            AgentLogger.e("ScreenCapture", "startForegroundService 失败：$lastError", failure.cause)
            logLastError(lastError!!)
            isCapturing = false
            ScreenCaptureTrace.log(ScreenCaptureTrace.START_SERVICE, "失败：$lastError")
            return false
        }
        lastError = null
        ScreenCaptureTrace.log(ScreenCaptureTrace.START_SERVICE, "成功（已交由系统拉起服务）")
        return true
    }

    /** 停止前台捕获服务；同时清掉上一次的一次性失败文案（重开是干净起点）。 */
    fun stopService(context: Context) {
        AgentLogger.i("ScreenCapture", "停止屏幕捕获服务")
        ScreenCaptureStartPolicy.guard {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
        // token 一次性：停止即作废，下次开启必须重新授权（Android 硬限制，
        // 所有大厂 App 同理；UI 侧由重新授权弹窗承接，好过崩进程）
        releaseProjection()
        isCapturing = false
        lastError = null
    }
}

/**
 * 屏幕捕获**面包屑**日志（真机第四轮「点立即开始 → 闪退」最后一刀）。
 *
 * **动机**：用户日志里**完全没有** `ScreenCapture` 相关行，也没有崩溃记录，说明崩点落在
 * 我们既没打日志也没兜底的位置。本对象把「授权回调 → 取 token → 进前台 → 建投影 → 抓首帧」
 * 这条链上的**每个关键步骤**统一留一条 INFO 日志：即使随后进程被系统杀掉，也能凭
 * **最后一条面包屑**精确定位崩点。步骤清单是常量（[KEY_STEPS]），QA harness 可据此断言完整性。
 *
 * 所有详情经 [AgentLogger.sanitize] 脱敏并截断，绝不明文落 key/包名等敏感信息。
 */
object ScreenCaptureTrace {

    /** 授权回调进入（resultCode / data 是否为空）。 */
    const val CB_ENTER: String = "授权回调进入"

    /** 缓存 MediaProjection 授权结果。 */
    const val CACHE_PERMISSION: String = "缓存授权结果"

    /** 请求 startForegroundService（含返回 true/false 与 lastError）。 */
    const val START_SERVICE: String = "启动前台服务"

    /** 服务 onCreate 进入。 */
    const val SERVICE_ON_CREATE: String = "服务 onCreate"

    /** 服务 onStartCommand 进入。 */
    const val SERVICE_ON_START: String = "服务 onStartCommand"

    /** 取 MediaProjection token（成功/失败）。 */
    const val OBTAIN_TOKEN: String = "取 MediaProjection token"

    /** 屏幕尺寸（实际 w/h/dpi）。 */
    const val SIZE: String = "屏幕尺寸"

    /** ImageReader 创建（成功/失败）。 */
    const val IMAGE_READER: String = "ImageReader 创建"

    /** createVirtualDisplay（成功/失败）。 */
    const val CREATE_VIRTUAL_DISPLAY: String = "createVirtualDisplay"

    /** registerCallback（成功/失败）。 */
    const val REGISTER_CALLBACK: String = "registerCallback"

    /** 进前台 startForeground（成功/失败）。 */
    const val START_FOREGROUND: String = "进前台 startForeground"

    /** 首次拿到帧。 */
    const val FIRST_FRAME: String = "首次拿到帧"

    /**
     * 关键步骤清单（**顺序 = 真实执行顺序**）。QA harness 断言其数量与内容，
     * 任一关键步骤缺失即视为面包屑不完整（崩点将无法定位）。
     */
    val KEY_STEPS: List<String> = listOf(
        CB_ENTER,
        CACHE_PERMISSION,
        START_SERVICE,
        SERVICE_ON_CREATE,
        SERVICE_ON_START,
        OBTAIN_TOKEN,
        SIZE,
        IMAGE_READER,
        CREATE_VIRTUAL_DISPLAY,
        REGISTER_CALLBACK,
        START_FOREGROUND,
        FIRST_FRAME
    )

    /** 统一日志标签（与桥/服务同标签，便于 `grep ScreenCapture` 一次捞全）。 */
    const val TAG: String = "ScreenCapture"

    /**
     * 打一条面包屑（INFO）。详情脱敏 + 截断，失败也要能被打出来——故自身绝不抛。
     *
     * @param step [KEY_STEPS] 之一（或失败标记）。
     * @param detail 人类可读详情（可含 resultCode/尺寸等，禁止明文 key）。
     */
    fun log(step: String, detail: String) {
        runCatching {
            AgentLogger.i(TAG, "[面包屑] $step：${AgentLogger.sanitize(detail).take(200)}")
        }
    }

    /**
     * 打一条**失败**面包屑（ERROR + 堆栈）。崩点前最后一条日志应为失败面包屑。
     *
     * @param step [KEY_STEPS] 之一。
     * @param detail 人类可读原因。
     * @param cause 原始异常（可为 null）。
     */
    fun logFailure(step: String, detail: String, cause: Throwable?) {
        runCatching {
            AgentLogger.e(TAG, "[面包屑] $step 失败：${AgentLogger.sanitize(detail).take(200)}", cause)
        }
    }
}

/**
 * 授权回调**全路径兜底**（纯函数，零 Android 依赖——QA harness 可直接断言）。
 *
 * **为什么**：`ActivityResult` 回调运行在主线程，**任何**未捕获异常都会直接杀进程。
 * 用户「点立即开始 → 闪退」且日志里连一条 `ScreenCapture` 都没有，正是回调体里
 * 某处裸调用抛异常的时刻。这里把「异常 → 失败结果 + 可读文案」抽成纯函数：
 * 回调体无论抛什么（含 `Error` / 厂商 ROM 的奇葩异常）都被吞成 [Outcome]，
 * **绝不逃逸**；失败结果再经 [ScreenCaptureStartPolicy.failureMessage] 变成用户可读文案。
 */
object ScreenCaptureAuthGuard {

    /** 回调执行结果：[failure] 非空即失败；[cause] 供调用方把堆栈交给日志。 */
    data class Outcome(val failure: String?, val cause: Throwable?) {
        /** 是否成功（无异常）。 */
        val succeeded: Boolean get() = failure == null
    }

    /**
     * 兜底执行授权回调体。
     *
     * @param block 可能抛异常的授权回调动作（setPermission / startService / 弹 Toast …）。
     * @return 成功时 [Outcome.failure] 为 null；失败时带上原因摘要（= [ScreenCaptureStartPolicy.describe]）。
     */
    fun run(block: () -> Unit): Outcome =
        try {
            block()
            Outcome(null, null)
        } catch (t: Throwable) {
            Outcome(ScreenCaptureStartPolicy.describe(t), t)
        }

    /**
     * 失败结果 → 面向用户的 Toast 文案（成功返回 null，表示无需提示）。
     * 文案唯一来源 = [ScreenCaptureStartPolicy.failureMessage]。
     */
    fun toastText(outcome: Outcome): String? =
        outcome.failure?.let { ScreenCaptureStartPolicy.failureMessage(it) }
}

/**
 * 屏幕捕获「置位」策略（纯函数，零 Android 依赖——QA harness 可直接断言决策表）。
 *
 * **为什么**：`ChatViewModel.startScreenCapture` 之前**无条件**把 `_capturing` 置 true，
 * 结果是「服务其实没起来，UI 却显示捕获中」——用户看到假状态、`ui_screenshot` 又一直失败，
 * 双重困惑。修正语义：**仅当 `startService` 返回 true 才置捕获中**；失败保持 false 并在 UI 可见。
 */
object CaptureFlag {

    /** 启动决策结果：[capturing] 为应写入的捕获状态；失败时 [errorMessage] 给出可读原因。 */
    data class Decision(val capturing: Boolean, val errorMessage: String?)

    /**
     * 依据 `startService` 的返回值与 [ScreenshotBridge.lastError] 给出置位决策。
     *
     * - `startSucceeded == true` → 捕获中，无错误；
     * - `startSucceeded == false` → **不得**置捕获中；错误文案优先取 [lastError]，
     *   为空时兜底「未知原因」（绝不产生 null 静默）。
     */
    fun decide(startSucceeded: Boolean, lastError: String?): Decision =
        if (startSucceeded) {
            Decision(capturing = true, errorMessage = null)
        } else {
            Decision(
                capturing = false,
                errorMessage = lastError?.trim()?.takeIf { it.isNotBlank() } ?: "未知原因"
            )
        }
}
