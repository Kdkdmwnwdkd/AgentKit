package com.example.agentkit.automation

import android.content.Context
import android.content.Intent

/**
 * 屏幕捕获桥：持有 MediaProjection 授权结果（resultCode + data），
 * 供 UI 在用户授予屏幕捕获权限后启动前台服务，并对外暴露捕获状态。
 *
 * 与 AutomationBridge 同构——执行层（服务实例、授权结果）与 UI/工具层解耦，
 * 工具只依赖本桥，不直接持有 Android 服务实例。
 */
object ScreenshotBridge {
    /** MediaProjection 授权结果码。 */
    var resultCode: Int = 0

    /** MediaProjection 授权返回的 Intent（含投影 token）。 */
    var permissionData: Intent? = null

    /** 当前是否正在捕获屏幕。 */
    var isCapturing: Boolean = false

    fun setPermission(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.permissionData = data
    }

    fun startService(context: Context) {
        val data = permissionData ?: return
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        context.startForegroundService(intent)
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, ScreenCaptureService::class.java))
        isCapturing = false
    }
}
