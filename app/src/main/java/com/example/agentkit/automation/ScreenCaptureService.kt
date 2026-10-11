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
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        if (resultCode != 0 && data != null) initProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        val chan = NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentKit 屏幕捕获中")
            .setContentText("正在为 Agent 捕捉屏幕画面")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
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
