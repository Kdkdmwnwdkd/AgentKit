package com.example.agentkit.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import com.example.agentkit.core.AgentLogger
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 屏幕捕获前台服务（L2 截图视觉通道）。
 *
 * 通过 MediaProjection + ImageReader + VirtualDisplay 抓取屏幕帧，
 * 暴露 captureBitmap() 供 ui_screenshot 工具调用。
 *
 * 设计要点（净室原创，符合 Android 14 要求）：
 *  - 必须以后台前台服务（foregroundServiceType=mediaProjection）形式运行，
 *    MediaProjection 才能正常创建（API 34 强制）。
 *  - 服务实例以 companion 暴露，工具层只依赖 ScreenshotBridge，不直接持有服务。
 *
 * **启动顺序（两条红线 + 一次性 token 硬约束，务必保持）**：
 *  - 红线 A（Android 14 类型强校验）：`startForeground(..., type =
 *    FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` 要求本次捕获的 MediaProjection token
 *    **已经取出**（用户已同意且 `getMediaProjection()` 已成功），否则抛 SecurityException。
 *  - 红线 B（Android 12+ 前台服务超时）：`startForegroundService()` 之后系统只给约 5s
 *    必须 `startForeground()`，否则抛 `ForegroundServiceDidNotStartInTimeException`
 *    ——**该异常不可捕获，进程直接被杀**（真机「反复开启 → 崩 4 次」的根因）。
 *  - **token 一次性硬约束（第五轮修复核心）**：`getMediaProjection()` 消费后即失效，
 *    重复开启复用已消费 token 必失败。故 token 获取**移到服务生命周期之前**——由桥层
 *    [ScreenshotBridge.obtainProjection] 在授权回调后立即执行；**失败就不启动服务**
 *    （`startForegroundService` 根本不会被调用，5 秒超时计时无从开始，红线 B 无从触发）。
 *  - 服务 `onStartCommand` 只做三件事：① 从桥读 [ScreenshotBridge.mediaProjection]
 *    （null = 异常拉起，先保命再收摊）→ ② **第一件事**就是带类型进前台
 *    （[startForegroundSafely]，毫秒级完成，红线 A 与 B 同时满足）→ ③ 才建
 *    ImageReader / VirtualDisplay（[setupCapture]，重活放最后，失败有全兜底）。
 *    顺序策略见 [ScreenCaptureStartPolicy.plan]。
 *  - Android 10～13（API 29-33）：同样的顺序没有副作用（类型已支持但无强制校验），
 *    <29：`startForeground` 无 type 参数，顺序无意义。**全版本共用一条路径**，不做分支。
 *  - 无论哪个版本，前台启动失败都**只允许**「记日志 + 上报 lastError + stopSelf」，
 *    绝不让异常逃出（老实现的 crash 就是异常从 onCreate 逃逸直接杀进程）。
 *  - 另设 2s 看门狗：万一 ① 链路异常拖长，赶在系统 5s 红线之前主动收摊并留现场，
 *    把「无声闪退」变成「日志里看得见的失败」。
 *
 * **全流程兜底（Bug「还是闪退」第二轮修复，本轮重点）**：
 *  顺序修好后仍然闪退 ⇒ 崩点在顺序之外。现在**每一个**可能抛异常的系统调用
 *  （`getMediaProjection` / `ImageReader.newInstance` / `setOnImageAvailableListener` /
 *  `createVirtualDisplay` / `registerCallback` / `acquireLatestImage` / `stopSelf`）
 *  都包在 [ScreenCaptureGuard.guardValue] / [ScreenCaptureGuard.guard] / `runCatching` 里；
 *  `displayMetrics` 尺寸先过 [ScreenCaptureGuard.sanitizeSize] 防御；
 *  任何异常一律走 [failSafe]：**日志带堆栈 + lastError + 按 [ScreenCaptureGuard.cleanupPlan]
 *  逐步安全停止**。**本类不向系统抛任何异常**——宁可「捕获失败可见」，也不要「闪退」。
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

/** 前台是否已成功启动（防闪退生死线的完成标志，看门狗据此放行/收摊）。 */
    @Volatile
    private var foregroundStarted: Boolean = false

/** 前台启动看门狗（startForegroundService 后 2s 未进前台则记日志 + stopSelf）。 */
    private var foregroundWatchdog: Handler? = null

    /** 投影回调：用户从控制中心「停止投屏」时系统会回调 onStop（零依赖，顺手加上）。 */
    private var projectionCallback: MediaProjection.Callback? = null

    /** 帧可用回调的 Handler（主线程 Looper；创建失败时退化为不注册回调，不影响捕获）。 */
    private var frameHandler: Handler? = null

    /** 是否已为「首次拿到帧」打过面包屑（只打一次，避免刷屏）。 */
    @Volatile
    private var firstFrameLogged: Boolean = false

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        // 只做「有帧了」的留痕；真正的取帧在 captureBitmap 里同步做（避免回调里做重活）
        runCatching {
            AgentLogger.d("ScreenCapture", "帧可用（reader=${reader.width}x${reader.height}）")
            if (!firstFrameLogged) {
                firstFrameLogged = true
                ScreenCaptureTrace.log(
                    ScreenCaptureTrace.FIRST_FRAME,
                    "已收到首帧 ${reader.width}x${reader.height}"
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 刻意不在这里启动前台：onCreate 里桥层可能还没有 token（服务被异常拉起时
        // 根本没有授权），此时声称 mediaProjection 类型会在 Android 14 上被
        // SecurityException 拦下。前台启动统一放到 onStartCommand（从桥读到 token 之后），
        // 见类 KDoc。
        AgentLogger.i("ScreenCapture", "服务创建，等待桥层 token 就绪后进前台")
        ScreenCaptureTrace.log(ScreenCaptureTrace.SERVICE_ON_CREATE, "onCreate 进入（尚未进前台）")
        // 看门狗：startForegroundService 后若 2s 仍未进前台，说明 onStartCommand 链路过长，
        // 记 ERROR 面包屑并自尽——赶在系统 5s 超时（ForegroundServiceDidNotStartInTimeException，
        // 不可捕获）之前留下可见日志，而不是无声闪退。
        foregroundWatchdog = Handler(Looper.getMainLooper()).apply {
            postDelayed({
                if (!foregroundStarted) {
                    ScreenCaptureTrace.logFailure(
                        ScreenCaptureTrace.START_FOREGROUND,
                        "看门狗触发：startForegroundService 后 2s 仍未进入前台，主动收摊",
                        null
                    )
                    AgentLogger.e("ScreenCapture", "看门狗：2s 未进前台，stopSelf 保命")
                    failSafe(
                        ScreenCaptureGuard.Stage.START_FOREGROUND,
                        "前台启动看门狗超时（2s 未进前台）",
                        null
                    )
                }
            }, WATCHDOG_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第五轮 Bug 修复（ForegroundServiceDidNotStartInTimeException，崩 4 次）：
        // token 改由桥层在服务生命周期**之前**取好（ScreenshotBridge.obtainProjection，
        // 授权回调后立即执行）。服务里不再调 getMediaProjection——token 一次性，
        // 重复开启时二次消费必失败，老代码失败即收摊、startForeground 从未执行，
        // 撞系统 5s 超时（不可捕获，进程直接杀）。
        //
        // 新顺序：① 从桥读 projection（null = 异常拉起，先保命再收摊）→
        // ② **立即**带 mediaProjection 类型进前台（token 已在手满足红线 A，
        // 且在 onStartCommand 入口毫秒级完成满足红线 B）→ ③ 才建 ImageReader/VirtualDisplay。
        val projection = ScreenshotBridge.mediaProjection
        ScreenCaptureTrace.log(
            ScreenCaptureTrace.SERVICE_ON_START,
            "onStartCommand 进入 projection=${if (projection == null) "null" else "ok"}"
        )

        // ② 立即进前台（**防闪退生死线**）：startForegroundService 之后系统只给 ~5s，
        //    必须赶在创建 ImageReader/VirtualDisplay 这些重活之前完成，否则进程被
        //    ForegroundServiceDidNotStartInTimeException 击杀（该异常不可捕获）。
        startForegroundSafely(projection != null)
        if (!foregroundStarted) {
            // startForegroundSafely 内部失败已 failSafe + stopSelf，这里只挡后续流程
            AgentLogger.w("ScreenCapture", "前台未就绪，放弃后续投影建立")
            return START_NOT_STICKY
        }

        // ① 的 null 分支：被异常拉起（无 token 却进了前台）——保命已完成，安全收摊。
        //    注意：此时 startForeground 已执行（无类型），系统 5s 计时已被满足。
        if (projection == null) {
            AgentLogger.e("ScreenCapture", "无 MediaProjection token 却被拉起：保命后收摊")
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "无 MediaProjection token（授权可能已失效，请重新授予屏幕捕获权限）",
                null
            )
            return START_NOT_STICKY
        }
        mediaProjection = projection

        // ③ 才建 ImageReader + VirtualDisplay（重活放最后；失败走 failSafe，已有全兜底）
        setupCapture(projection)
        if (virtualDisplay == null) {
            AgentLogger.w("ScreenCapture", "投影未就绪，已安全停止")
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * 进前台 + 失败兜底。**任何异常都不从这里逃出**（捕获 Throwable，含 Error）：
     * 失败时按 [ScreenCaptureGuard.cleanupPlan] 记 ERROR 日志（含堆栈）、通过
     * [ScreenshotBridge.lastError] 暴露给 UI、`stopSelf()` 收摊——
     * 宁可「启动失败可见」，也不要「闪退」。
     *
     * @param hasToken 桥层是否已备好本次捕获的 MediaProjection token（token 在服务
     *   生命周期之前由 [ScreenshotBridge.obtainProjection] 预取）；false 时（无授权却被
     *   系统拉起）不带类型地启动前台，避免撞 Android 14 的类型强校验。
     */
    private fun startForegroundSafely(hasToken: Boolean) {
        val guarded = ScreenCaptureStartPolicy.guard {
            val nm = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(chan)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AgentKit 屏幕捕获中")
                .setContentText("正在为 Agent 捕捉屏幕画面")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
            if (hasToken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 规范要求：声称 mediaProjection 类型 → 此前必须已取得 token（Android 14 起强制）
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                // 无 token 时不声称该类型（否则 API 34 必然 SecurityException），
                // 但仍然必须进前台：startForegroundService 后 5s 内不调用会触发 RemoteServiceException
                startForeground(NOTIF_ID, notification)
            }
        }
        val failure = guarded.failure
        if (failure != null) {
            val reason = if (!hasToken) "未取得 MediaProjection 授权（$failure）" else failure
            ScreenCaptureTrace.logFailure(ScreenCaptureTrace.START_FOREGROUND, reason, guarded.cause)
            failSafe(ScreenCaptureGuard.Stage.START_FOREGROUND, reason, guarded.cause)
        } else {
            foregroundStarted = true
            foregroundWatchdog?.let { it.removeCallbacksAndMessages(null) }
            AgentLogger.i("ScreenCapture", "已进入前台（hasToken=$hasToken）")
            ScreenCaptureTrace.log(ScreenCaptureTrace.START_FOREGROUND, "成功 hasToken=$hasToken")
        }
    }

    /**
     * 建立 ImageReader / VirtualDisplay（token 已由桥层在服务启动前备好，
     * [onStartCommand] 从 [ScreenshotBridge.mediaProjection] 读取后传入）。
     *
     * **每一步都单独兜底**（第四轮加固）：任何一步失败都走 [failSafe]，
     * 且释放范围精确等于「已经建出来的那些资源」——见 [heldResources]。
     * 本方法**不抛异常**；调用方以 `virtualDisplay != null` 判定是否就绪。
     */
    private fun setupCapture(projection: MediaProjection) {
        // ① 尺寸防御：0/负数直接放弃（拿 0 去 newInstance 必崩），超大分辨率等比缩放
        val metrics = runCatching { resources.displayMetrics }.getOrNull()
        val rawW = metrics?.widthPixels ?: 0
        val rawH = metrics?.heightPixels ?: 0
        val rawDensity = metrics?.densityDpi ?: 0
        val size = ScreenCaptureGuard.sanitizeSize(rawW, rawH, rawDensity)
        if (!size.ok) {
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                size.reason ?: "屏幕尺寸非法",
                null
            )
            return
        }
        AgentLogger.i(
            "ScreenCapture",
            "屏幕尺寸 ${size.width}x${size.height}@${size.density}" +
                if (size.clamped) "（原始 ${rawW}x${rawH} 已等比缩放）" else ""
        )
        ScreenCaptureTrace.log(
            ScreenCaptureTrace.SIZE,
            "实际使用 ${size.width}x${size.height}@${size.density}" +
                if (size.clamped) "（原始 ${rawW}x${rawH} 已等比缩放）" else ""
        )

        // ② ImageReader：分辨率/格式不合规会抛 IllegalArgumentException
        val readerResult = ScreenCaptureGuard.guardValue {
            ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        }
        val reader = readerResult.value
        if (reader == null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.IMAGE_READER,
                "newInstance 失败：${readerResult.failure}",
                readerResult.cause
            )
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "ImageReader.newInstance 失败：${readerResult.failure}",
                readerResult.cause
            )
            return
        }
        imageReader = reader
        ScreenCaptureTrace.log(ScreenCaptureTrace.IMAGE_READER, "newInstance 成功")

        // ③ 帧可用回调：非必需能力，注册失败只记 WARN，不因此放弃捕获
        val handlerResult = ScreenCaptureGuard.guardValue { Handler(Looper.getMainLooper()) }
        val handler = handlerResult.value
        if (handler != null) {
            frameHandler = handler
            val listenerResult = ScreenCaptureGuard.guard {
                reader.setOnImageAvailableListener(frameListener, handler)
            }
            if (listenerResult.failure != null) {
                AgentLogger.w(
                    "ScreenCapture",
                    "setOnImageAvailableListener 失败（不影响轮询取帧）：${listenerResult.failure}"
                )
            }
        } else {
            AgentLogger.w("ScreenCapture", "Handler 创建失败，跳过帧回调注册：${handlerResult.failure}")
        }

        // ④ VirtualDisplay：这一步是闪退高发区（token 失效 / Surface 无效 / 分辨率超限）
        val displayResult = ScreenCaptureGuard.guardValue {
            projection.createVirtualDisplay(
                "AgentKitCapture", size.width, size.height, size.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        }
        val display = displayResult.value
        if (display == null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.CREATE_VIRTUAL_DISPLAY,
                "失败：${displayResult.failure}",
                displayResult.cause
            )
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "createVirtualDisplay 失败：${displayResult.failure}",
                displayResult.cause
            )
            return
        }
        virtualDisplay = display
        ScreenCaptureTrace.log(ScreenCaptureTrace.CREATE_VIRTUAL_DISPLAY, "成功")

        // ⑥ 投影回调：用户从控制中心「停止投屏」时系统会回调 onStop（不注册就收不到，
        //    服务会一直挂着前台通知却抓不到帧——必须感知并安全停止）
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                AgentLogger.w("ScreenCapture", "系统回调 onStop：用户已停止投屏")
                failSafe(
                    ScreenCaptureGuard.Stage.PROJECTION_STOPPED,
                    "用户已从控制中心停止投屏",
                    null
                )
            }
        }
        // 只有 registerCallback 这一步可能抛（构造回调对象不会），故单独兜底
        val callbackResult = ScreenCaptureGuard.guard {
            projection.registerCallback(callback, frameHandler)
            projectionCallback = callback
        }
        if (callbackResult.failure != null) {
            AgentLogger.w("ScreenCapture", "registerCallback 失败（不致命）：${callbackResult.failure}")
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.REGISTER_CALLBACK,
                "失败（不致命）：${callbackResult.failure}",
                callbackResult.cause
            )
        } else {
            ScreenCaptureTrace.log(ScreenCaptureTrace.REGISTER_CALLBACK, "成功")
        }

        // ⑦ 全部就绪：此时才对外声明可用（避免外部拿到半成品实例）
        activeInstance = this
        ScreenshotBridge.isCapturing = true
        ScreenshotBridge.clearError()
        AgentLogger.i("ScreenCapture", "捕获链路就绪：VirtualDisplay + ImageReader 已建立")
    }

    /** 当前持有的、需要释放的资源（供 [failSafe] 生成精确的安全停止计划）。 */
    private fun heldResources(): Set<ScreenCaptureGuard.Resource> {
        val held = linkedSetOf<ScreenCaptureGuard.Resource>()
        if (virtualDisplay != null) held += ScreenCaptureGuard.Resource.VIRTUAL_DISPLAY
        if (imageReader != null) held += ScreenCaptureGuard.Resource.IMAGE_READER
        if (mediaProjection != null) held += ScreenCaptureGuard.Resource.PROJECTION
        return held
    }

    /**
     * **唯一**的失败出口：按 [ScreenCaptureGuard.cleanupPlan] 逐步安全停止。
     *
     * 每一步单独 `runCatching`——任何一步失败都**不能**阻止后续步骤执行
     * （尤其 [ScreenCaptureGuard.CleanupStep.STOP_SELF] 与 [ScreenCaptureGuard.CleanupStep.REPORT_LAST_ERROR]）。
     *
     * @param stage 出错阶段（决定日志文案）。
     * @param reason 人类可读原因（进 [ScreenshotBridge.lastError]，UI 可见）。
     * @param cause 原始异常（堆栈进日志）；可为 null。
     */
    private fun failSafe(stage: ScreenCaptureGuard.Stage, reason: String, cause: Throwable?) {
        val plan = ScreenCaptureGuard.cleanupPlan(stage, heldResources())
        AgentLogger.i("ScreenCapture", "安全停止序列（${ScreenCaptureGuard.stageLabel(stage)}）：$plan")
        for (step in plan) {
            when (step) {
                ScreenCaptureGuard.CleanupStep.RELEASE_VIRTUAL_DISPLAY -> runCatching {
                    virtualDisplay?.release()
                }
                ScreenCaptureGuard.CleanupStep.CLOSE_IMAGE_READER -> runCatching {
                    imageReader?.close()
                }
                ScreenCaptureGuard.CleanupStep.STOP_PROJECTION -> {
                    runCatching {
                        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
                    }
                    // token 一次性：停止即作废，桥层同步清（下次开启须重新授权，
                    // Android 硬限制；好过「拿着失效 token 二次消费崩进程」）
                    runCatching { ScreenshotBridge.releaseProjection() }
                }
                ScreenCaptureGuard.CleanupStep.CLEAR_STATE -> {
                    // 只清自己：避免把另一个正在运行的实例状态抹掉
                    if (activeInstance === this) activeInstance = null
                    ScreenshotBridge.isCapturing = false
                }
                ScreenCaptureGuard.CleanupStep.REPORT_LAST_ERROR ->
                    runCatching { ScreenshotBridge.reportFailure(reason) }
                ScreenCaptureGuard.CleanupStep.LOG_ERROR ->
                    AgentLogger.e(
                        "ScreenCapture",
                        "捕获失败（${ScreenCaptureGuard.stageLabel(stage)}）：$reason",
                        cause
                    )
                ScreenCaptureGuard.CleanupStep.STOP_SELF -> runCatching { stopSelf() }
            }
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        projectionCallback = null
        frameHandler = null
    }

    /** 抓取当前屏幕帧为 Bitmap（无帧时短暂重试）。调用方应在 IO 线程调用。**绝不抛异常**。 */
    fun captureBitmap(): Bitmap? {
        val reader = imageReader
        if (reader == null) {
            AgentLogger.w("ScreenCapture", "captureBitmap：ImageReader 未就绪（捕获未启动或已停止）")
            return null
        }
        return try {
            var image: Image? = null
            for (i in 0 until 10) {
                image = reader.acquireLatestImage()
                if (image != null) break
                try {
                    Thread.sleep(60)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (image == null) {
                AgentLogger.w("ScreenCapture", "captureBitmap：等待 10 次仍无可用帧")
                null
            } else {
                try {
                    imageToBitmap(image)
                } finally {
                    runCatching { image.close() }
                }
            }
        } catch (t: Throwable) {
            // 取帧/转码任何异常：按计划安全停止并上报，**绝不上抛**（上抛 = 进程崩）
            failSafe(ScreenCaptureGuard.Stage.CAPTURE, "acquireLatestImage/转码失败：${ScreenCaptureGuard.describe(t)}", t)
            null
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // stride 非法（0/负数）会让下面的除法与 createBitmap 直接抛 ArithmeticException /
        // IllegalArgumentException——显式拦下，交给 captureBitmap 的兜底走安全停止。
        if (pixelStride <= 0 || rowStride <= 0 || image.width <= 0 || image.height <= 0) {
            throw IllegalStateException(
                "非法 Image 参数：w=${image.width} h=${image.height} " +
                    "pixelStride=$pixelStride rowStride=$rowStride"
            )
        }
        val rowPadding = rowStride - pixelStride * image.width
        val raw = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        raw.copyPixelsFromBuffer(buffer)
        val realW = image.width - rowPadding / pixelStride
        return if (realW < raw.width) {
            val cropped = Bitmap.createBitmap(raw, 0, 0, realW, raw.height)
            raw.recycle()
            cropped
        } else raw
    }

    override fun onDestroy() {
        // 看门狗收摊：服务无论为何销毁，都不留悬挂回调（否则 2s 后误触发 + Handler 泄漏）
        foregroundWatchdog?.let { it.removeCallbacksAndMessages(null) }
        foregroundWatchdog = null
        // 销毁路径同样逐步 runCatching：释放顺序同 cleanupPlan（下游先、投影后）
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        }
        // token 一次性：与 failSafe 的 STOP_PROJECTION 对齐，统一走桥层停止并同步清桥
        // （服务不再自行 mediaProjection?.stop()——停止语义收口到 ScreenshotBridge 一处）
        runCatching { ScreenshotBridge.releaseProjection() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        projectionCallback = null
        frameHandler = null
        if (activeInstance === this) activeInstance = null
        ScreenshotBridge.isCapturing = false
        super.onDestroy()
        AgentLogger.i("ScreenCapture", "服务已销毁（资源全部释放）")
    }

    companion object {
        /**
         * 当前活跃的服务实例（**仅在捕获链路完全就绪后才赋值**，销毁/失败即刻清空，
         * 防止外部拿到半成品或已死实例 → NPE 与泄漏）。
         */
        var activeInstance: ScreenCaptureService? = null
            private set

        private const val CHANNEL_ID = "agentkit_screen_capture"
        private const val NOTIF_ID = 1001

        /**
         * 前台启动看门狗阈值：`startForegroundService()` 之后系统只给约 5s
         * （Android 12+ 实际更短，超时抛 `ForegroundServiceDidNotStartInTimeException`
         * 且**不可捕获**，进程直接被杀）。留 2s 余量：到点未进前台就主动收摊并留日志，
         * 赶在系统动手之前把现场留下来。
         */
        private const val WATCHDOG_MS = 2000L
    }
}
