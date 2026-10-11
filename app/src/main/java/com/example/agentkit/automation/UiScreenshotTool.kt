package com.example.agentkit.automation

import android.graphics.Bitmap
import android.util.Base64
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolPart
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ui_screenshot：截取当前手机屏幕，并以图片形式返回。
 *
 * Agent 借此“看见”屏幕内容（图标、图片、验证码、游戏画面等 UI 树无法描述的区域）。
 * 返回的图片会作为 ToolPart.Image 被 AgentLoop 自动回灌到下一轮对话，供视觉模型分析。
 *
 * 需要先在「自动化」页点「开始屏幕捕获」并授予屏幕录制权限（启动 MediaProjection 前台服务）。
 */
class UiScreenshotTool(private val workspace: File) : AgentTool {
    override val name = "ui_screenshot"
    /** 涉及屏幕内容采集，需用户知情后放行（L10）。 */
    override val needsApproval = true
    override val description =
        "截取当前手机屏幕画面，并以图片形式返回。Agent 可据此“看见”屏幕内容（图标、图片、验证码、游戏画面等 UI 树无法描述的区域），用于理解界面、定位元素、判断状态。返回图片会自动附到下一轮对话供视觉模型分析。需要先在「自动化」页开启屏幕捕获授权。"
    override val systemPrompt =
        "你可以用 ui_screenshot 截取当前屏幕画面并“看见”它（图片会自动附到下一轮对话）。当需要理解屏幕上的视觉内容、确认操作结果、或 UI 树信息不足时，调用它。"
    override val parameters = JsonSchema.objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val svc = ScreenCaptureService.activeInstance
        if (!ScreenshotBridge.isCapturing || svc == null) {
            return@withContext ToolResult(
                "屏幕捕获未启用：请在「设置 → 自动化」中点「开始屏幕捕获」并授予屏幕录制权限。",
                isError = true
            )
        }
        val bmp = svc.captureBitmap()
        if (bmp == null) {
            return@withContext ToolResult("截屏失败：未能获取屏幕帧（请确认捕获已开启）", isError = true)
        }
        try {
            val dir = File(workspace, "screenshots").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val file = File(dir, "shot_$ts.png")
            val full = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, full)
            file.writeBytes(full.toByteArray())

            // 生成缩略图 data URL（限制尺寸与质量，控制 token 成本）
            val thumb = downscale(bmp, maxDim = 768)
            val jpg = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, jpg)
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP)

            val meta = """{"path":"${file.absolutePath}","width":${bmp.width},"height":${bmp.height},"type":"png"}"""
            ToolResult(listOf(ToolPart.Text(meta), ToolPart.Image(dataUrl, "当前屏幕截图")))
        } finally {
            bmp.recycle()
        }
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val scale = (maxDim.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}
