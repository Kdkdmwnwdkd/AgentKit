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
import android.media.projection.MediaProjectionManager
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
 * **启动顺序（Bug 2 修复，务必保持）**：
 *  - Android 14（API 34，本项目 targetSdk 34）起，`startForeground(..., type =
 *    FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` 要求本次捕获的 MediaProjection token
 *    **已经取出**（用户已同意且 `getMediaProjection()` 已成功），否则抛 SecurityException
 *    → 进程崩。故本服务的顺序是：`onStartCommand` 里先取 token（[initProjection]）
 *    再进前台（[startForegroundSafely]），顺序策略见 [ScreenCaptureStartPolicy.plan]。
 *  - Android 10～13（API 29-33）：同样的顺序没有副作用（类型已支持但无强制校验），
 *    <29：`startForeground` 无 type 参数，顺序无意义。**全版本共用一条路径**，不做分支。
 *  - 无论哪个版本，前台启动失败都**只允许**「记日志 + 上报 lastError + stopSelf」，
 *    绝不让异常逃出（老实现的 crash 就是异常从 onCreate 逃逸直接杀进程）。
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
        // 刻意不在这里启动前台：onCreate 拿不到带有授权 Intent 的 extras，
        // 此时声称 mediaProjection 类型会在 Android 14 上被 SecurityException 拦下。
        // 前台启动统一放到 onStartCommand（取到 token 之后），见类 KDoc。
        AgentLogger.i("ScreenCapture", "服务创建，等待授权 Intent 后取 token 再进前台")
        ScreenCaptureTrace.log(ScreenCaptureTrace.SERVICE_ON_CREATE, "onCreate 进入（尚未进前台）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        val hasToken = resultCode != 0 && data != null
        val plan = ScreenCaptureStartPolicy.plan(hasToken, Build.VERSION.SDK_INT)
        AgentLogger.i("ScreenCapture", "启动顺序=$plan（Android ${Build.VERSION.SDK_INT}）")
        ScreenCaptureTrace.log(
            ScreenCaptureTrace.SERVICE_ON_START,
            "onStartCommand 进入 resultCode=$resultCode data=${if (data == null) "null" else "ok"}"
        )

        // ① 先取 token（顺序敏感：Android 14 要求它早于 startForeground）
        if (hasToken && data != null) {
            // initProjection 内部已全兜底（失败即 failSafe + stopSelf），这里不再二次上报
            initProjection(resultCode, data)
            if (virtualDisplay == null) {
                AgentLogger.w("ScreenCapture", "投影未就绪，放弃进前台（已安全停止）")
                return START_NOT_STICKY
            }
        }
        // ② 再进前台（失败被整体兜底，绝不抛出去）
        startForegroundSafely(hasToken)
        return START_NOT_STICKY
    }

    /**
     * 进前台 + 失败兜底。**任何异常都不从这里逃出**（捕获 Throwable，含 Error）：
     * 失败时按 [ScreenCaptureGuard.cleanupPlan] 记 ERROR 日志（含堆栈）、通过
     * [ScreenshotBridge.lastError] 暴露给 UI、`stopSelf()` 收摊——
     * 宁可「启动失败可见」，也不要「闪退」。
     *
     * @param hasToken 是否已在本次启动中取到 MediaProjection token；false 时（无授权却被
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
            AgentLogger.i("ScreenCapture", "已进入前台（hasToken=$hasToken）")
            ScreenCaptureTrace.log(ScreenCaptureTrace.START_FOREGROUND, "成功 hasToken=$hasToken")
        }
    }

    /**
     * 建立投影 / ImageReader / VirtualDisplay。
     *
     * **每一步都单独兜底**（第四轮加固）：任何一步失败都走 [failSafe]，
     * 且释放范围精确等于「已经建出来的那些资源」——见 [heldResources]。
     * 本方法**不抛异常**；调用方以 `virtualDisplay != null` 判定是否就绪。
     */
    private fun initProjection(resultCode: Int, data: Intent) {
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

        // ② 取 MediaProjection token（顺序敏感，且可能抛 SecurityException / 厂商 ROM 异常）
        val projectionResult = ScreenCaptureGuard.guardValue {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            mpm.getMediaProjection(resultCode, data)
        }
        val projection = projectionResult.value
        if (projection == null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.OBTAIN_TOKEN,
                "getMediaProjection 失败：${projectionResult.failure}",
                projectionResult.cause
            )
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "getMediaProjection 失败：${projectionResult.failure}",
                projectionResult.cause
            )
            return
        }
        mediaProjection = projection
        ScreenCaptureTrace.log(ScreenCaptureTrace.OBTAIN_TOKEN, "getMediaProjection 成功")

        // ③ ImageReader：分辨率/格式不合规会抛 IllegalArgumentException
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

        // ④ 帧可用回调：非必需能力，注册失败只记 WARN，不因此放弃捕获
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

        // ⑤ VirtualDisplay：这一步是闪退高发区（token 失效 / Surface 无效 / 分辨率超限）
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
                    runCatching { mediaProjection?.stop() }
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
        // 销毁路径同样逐步 runCatching：释放顺序同 cleanupPlan（下游先、投影后）
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        }
        runCatching { mediaProjection?.stop() }
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
    }
}
