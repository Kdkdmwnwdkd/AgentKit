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
 * 截屏不可用时的**可行动指引**（W-12：删除 MediaProjection 后的唯一失败路径）。
 *
 * 截图只走无障碍通道（API 30+）——用户授过无障碍（读屏）即可，不需要屏幕捕捉授权、
 * 不需要前台服务、不会弹「是否允许截取屏幕」。
 */
const val SCREENSHOT_UNAVAILABLE_GUIDANCE =
    "截屏不可用：请先在系统设置里开启本应用的无障碍服务（Android 11 及以上支持无障碍直接截图，无需屏幕捕捉权限）。"

/**
 * ui_screenshot：截取当前手机屏幕，并以图片形式返回。
 *
 * Agent 借此“看见”屏幕内容（图标、图片、验证码、游戏画面等 UI 树无法描述的区域）。
 * 返回的图片会作为 ToolPart.Image 被 AgentLoop 自动回灌到下一轮对话，供视觉模型分析。
 *
 * W-12：**唯一通道 = 无障碍 `AccessibilityService.takeScreenshot`**（API 30+）。
 * 原 MediaProjection 兜底已整条删除——它需要额外授权弹窗 + 前台服务 + 首帧等待，
 * 而截图能力对已授无障碍的用户是冗余的（真机日志：屏幕捕获开着也仍走无障碍）。
 */
class UiScreenshotTool(private val workspace: File) : AgentTool {
    override val name = "ui_screenshot"
    /** 涉及屏幕内容采集，需用户知情后放行（L10；W-8 起按回合缓存，不重复弹窗）。 */
    override val needsApproval = true
    override val description =
        "截取当前手机屏幕画面，并以图片形式返回。Agent 可据此“看见”屏幕内容（图标、图片、验证码、游戏画面等 UI 树无法描述的区域），用于理解界面、定位元素、判断状态。返回图片会自动附到下一轮对话供视觉模型分析。已授予无障碍权限即可使用，无需额外开关。"
    override val systemPrompt =
        "你可以用 ui_screenshot 截取当前屏幕画面并“看见”它（图片会自动附到下一轮对话）。当需要理解屏幕上的视觉内容、确认操作结果、或 UI 树信息不足时，调用它。已授予无障碍权限时无需任何额外开关。"
    override val parameters = JsonSchema.objectSchema()

    /** 只读（按 roadmap 首批标注）：仅采集屏幕画面供观看，不改变设备状态。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val svc = AutomationBridge.service
        if (!canUseA11yScreenshot(AutomationBridge.isReady(), Build.VERSION.SDK_INT) || svc == null) {
            return@withContext ToolResult.fail(
                code = "a11y_screenshot_unavailable",
                message = SCREENSHOT_UNAVAILABLE_GUIDANCE,
                hint = "到「设置 → 无障碍 → AgentKit」开启服务后重试；" +
                    "也可改用 ui_tree 读取界面结构（不要用 shell 绕过）。"
            )
        }
        // 厂商 ROM 可能 onFailure/抛异常：服务方法内部已兜底为 null，此处双保险
        val bmp = try {
            svc.takeScreenshotViaA11y()
        } catch (e: Exception) {
            null
        } ?: return@withContext ToolResult.fail(
            code = "screenshot_failed",
            message = "截屏失败：无障碍服务未能取到屏幕帧。",
            hint = "确认无障碍服务仍在运行后重试；个别厂商 ROM 限制无障碍截图，可改用 ui_tree。"
        )
        saveAndReturn(bmp)
    }

    /**
     * 落盘全尺寸 PNG + 生成缩略图 data URL（限制尺寸与质量，控制 token 成本），
     * 统一产出 [ToolResult]。meta 记录 `via=a11y` 便于日志核对（现仅此一条通道）。
     */
    private fun saveAndReturn(bmp: Bitmap): ToolResult {
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
            """{"path":"${file.absolutePath}","width":$w,"height":$h,"type":"png","via":"a11y"}"""
        val message = "已截屏并保存：${file.name}（${w}x${h}）"
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
}
