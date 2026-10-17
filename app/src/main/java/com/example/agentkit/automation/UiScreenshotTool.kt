package com.example.agentkit.automation

import android.graphics.Bitmap
import android.os.Build
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
 * 屏幕捕获未开启时的一步到位**可行动指引**（真机第四轮修正：泛泛报错 → 明确告诉用户去哪开）。
 *
 * 既是 [UiScreenshotTool] MediaProjection 兜底路径失败返回的 message 文案，也供 QA harness
 * 直接断言——无需启动 Android 服务即可校验「权限中心 / 去开启」这两个关键动作词始终在册。
 *
 * W-9 补一句：无障碍服务已就绪时截屏走无障碍通道，无需此操作——避免模型在
 * 主路径可用时还去引导用户开屏幕捕获（真机日志里用户「开了和没开没区别」的
 * 部分来源：文案没告诉他无障碍开着就能截图）。
 */
const val SCREEN_CAPTURE_OFF_GUIDANCE =
    "屏幕捕获未开启：请让用户到「权限中心 → 屏幕捕获 → 去开启」，然后重试。" +
        "（若已授予本应用无障碍权限，截屏会自动走无障碍通道，无需手动开启屏幕捕获。）"

/**
 * ui_screenshot：截取当前手机屏幕，并以图片形式返回。
 *
 * Agent 借此“看见”屏幕内容（图标、图片、验证码、游戏画面等 UI 树无法描述的区域）。
 * 返回的图片会作为 ToolPart.Image 被 AgentLoop 自动回灌到下一轮对话，供视觉模型分析。
 *
 * W-9 双通道（主 → 兜底）：
 *  1. **无障碍 takeScreenshot**（API 30+ 且无障碍服务就绪）：用户授过无障碍即可用，
 *     无授权链、无前台服务启动等待；
 *  2. **MediaProjection**（兜底）：需先在「自动化」页点「开始屏幕捕获」并授予屏幕录制权限。
 */
class UiScreenshotTool(private val workspace: File) : AgentTool {
    override val name = "ui_screenshot"
    /** 涉及屏幕内容采集，需用户知情后放行（L10；W-8 起按回合缓存，不重复弹窗）。 */
    override val needsApproval = true
    override val description =
        "截取当前手机屏幕画面，并以图片形式返回。Agent 可据此“看见”屏幕内容（图标、图片、验证码、游戏画面等 UI 树无法描述的区域），用于理解界面、定位元素、判断状态。返回图片会自动附到下一轮对话供视觉模型分析。优先走无障碍通道（已授予无障碍权限即可，无需额外开关）；无障碍不可用时需先在「自动化」页开启屏幕捕获授权。"
    override val systemPrompt =
        "你可以用 ui_screenshot 截取当前屏幕画面并“看见”它（图片会自动附到下一轮对话）。当需要理解屏幕上的视觉内容、确认操作结果、或 UI 树信息不足时，调用它。已授予无障碍权限时无需任何额外开关；仅当无障碍不可用才需要用户开启屏幕捕获。"
    override val parameters = JsonSchema.objectSchema()

    /** 只读（按 roadmap 首批标注）：仅采集屏幕画面供观看，不改变设备状态。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val svc = AutomationBridge.service
        // W-9 主路径：无障碍 takeScreenshot（API 30+ 且服务就绪）——免 MediaProjection 授权链
        if (shouldPreferA11yScreenshot(AutomationBridge.isReady(), Build.VERSION.SDK_INT) && svc != null) {
            val bmp = try {
                svc.takeScreenshotViaA11y()
            } catch (e: Exception) {
                // 服务方法内部已兜底为 null，此处双保险：任何异常都落 MediaProjection 兜底
                null
            }
            if (bmp != null) {
                return@withContext saveAndReturn(bmp, via = VIA_A11Y)
            }
            // 主路径失败不报错：静默落 MediaProjection 兜底（其未开时文案自会引导用户）
        }
        // 兜底路径：MediaProjection 前台服务（原有逻辑，capture 在 ScreenCaptureService 上）
        val capture = ScreenCaptureService.activeInstance
        if (!ScreenshotBridge.isCapturing || capture == null) {
            return@withContext ToolResult.fail(
                code = "capture_not_enabled",
                message = SCREEN_CAPTURE_OFF_GUIDANCE,
                hint = "优先引导用户授予无障碍权限（截图可自动走无障碍通道）；" +
                    "或到「权限中心 → 屏幕捕获 → 去开启」后重试；" +
                    "也可改用 ui_tree 读取界面结构（不要用 shell 绕过）。"
            )
        }
        val bmp = capture.captureBitmap()
        if (bmp == null) {
            return@withContext ToolResult.fail(
                code = "capture_failed",
                message = "截屏失败：未能获取屏幕帧（请确认捕获已开启）",
                hint = "确认屏幕捕获服务运行中后重试。"
            )
        }
        saveAndReturn(bmp, via = VIA_MEDIA_PROJECTION)
    }

    /**
     * 落盘全尺寸 PNG + 生成缩略图 data URL（限制尺寸与质量，控制 token 成本），
     * 统一产出 [ToolResult]（主/兜底两通道共用；[via] 记录实际通道，便于日志与 harness 断言）。
     */
    private fun saveAndReturn(bmp: Bitmap, via: String): ToolResult {
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

        // 尺寸先取到局部变量：recycle 之后不再触碰 bmp（与 W-7 前 finally 回收语义一致）
        val w = bmp.width
        val h = bmp.height
        val meta =
            """{"path":"${file.absolutePath}","width":$w,"height":$h,"type":"png","via":"$via"}"""
        val message = "已截屏并保存：${file.name}（${w}x${h}，通道 $via）"
        bmp.recycle()
        return ToolResult.ok(
            code = "screenshot_ok",
            message = message,
            data = meta,
            parts = listOf(ToolPart.Text(meta), ToolPart.Image(dataUrl, "当前屏幕截图"))
        )
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val scale = (maxDim.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private companion object {
        /** meta/日志里的通道标记：无障碍 takeScreenshot（W-9 主路径）。 */
        const val VIA_A11Y = "a11y"

        /** meta/日志里的通道标记：MediaProjection 前台服务（兜底）。 */
        const val VIA_MEDIA_PROJECTION = "media_projection"
    }
}
