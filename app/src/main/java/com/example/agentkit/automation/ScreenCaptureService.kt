package com.example.agentkit.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.IBinder

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
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 刻意不在这里启动前台：onCreate 拿不到带有授权 Intent 的 extras，
        // 此时声称 mediaProjection 类型会在 Android 14 上被 SecurityException 拦下。
        // 前台启动统一放到 onStartCommand（取到 token 之后），见类 KDoc。
        AgentLogger.i("ScreenCapture", "服务创建，等待授权 Intent 后取 token 再进前台")
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

        // ① 先取 token（顺序敏感：Android 14 要求它早于 startForeground）
        if (hasToken && data != null) {
            val guarded = ScreenCaptureStartPolicy.guard { initProjection(resultCode, data) }
            val failure = guarded.failure
            if (failure != null) {
                AgentLogger.e("ScreenCapture", "取 MediaProjection token 失败：$failure", guarded.cause)
                ScreenshotBridge.reportFailure(failure)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // ② 再进前台（失败被整体兜底，绝不抛出去）
        startForegroundSafely(hasToken)
        return START_NOT_STICKY
    }

    /**
     * 进前台 + 失败兜底。**任何异常都不从这里逃出**（捕获 Throwable，含 Error）：
     * 失败时记 ERROR 日志（含堆栈）、通过 [ScreenshotBridge.lastError] 暴露给 UI、
     * `stopSelf()` 收摊——宁可「启动失败可见」，也不要「闪退」。
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
            AgentLogger.e("ScreenCapture", "startForeground 失败：$reason", guarded.cause)
            ScreenshotBridge.reportFailure(reason)
            stopSelf()
        }
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AgentKitCapture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        activeInstance = this
        ScreenshotBridge.isCapturing = true
    }

    /** 抓取当前屏幕帧为 Bitmap（无帧时短暂重试）。调用方应在 IO 线程调用。 */
    fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        for (i in 0 until 10) {
            image = reader.acquireLatestImage()
            if (image != null) break
            try { Thread.sleep(60) } catch (_: InterruptedException) { break }
        }
        if (image == null) return null
        return try {
            imageToBitmap(image)
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
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
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        activeInstance = null
        ScreenshotBridge.isCapturing = false
    }

    companion object {
        var activeInstance: ScreenCaptureService? = null
            private set
        private const val CHANNEL_ID = "agentkit_screen_capture"
        private const val NOTIF_ID = 1001
    }
}
