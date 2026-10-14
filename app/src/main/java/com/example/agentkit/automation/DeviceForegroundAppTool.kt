package com.example.agentkit.automation

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * device_foreground_app：获取当前前台运行的应用（L3 感知层）。
 *
 * 基于 UsageStatsManager 查询最近置顶的包名，让 Agent “知道”用户此刻正在用哪个 App。
 * 需要 PACKAGE_USAGE_STATS 权限（已在 Manifest 声明，需用户在设置中手动授予）。
 */
class DeviceForegroundAppTool(private val context: Context) : AgentTool {
    override val name = "device_foreground_app"
    override val description =
        "获取当前正在前台运行的应用包名与名称。用于感知用户此刻正在使用哪个 App，判断操作上下文。需要 PACKAGE_USAGE_STATS 权限（在「自动化」页引导授予）。"
    override val systemPrompt =
        "你可以用 device_foreground_app 获取当前前台应用，感知用户此刻在哪个 App 里。当任务需要判断当前界面归属、或“用户在用什么 App”时调用它。"
    override val parameters = JsonSchema.objectSchema()

    /** 只读：仅查询使用统计，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return ToolResult.fail(
                code = "service_unavailable",
                message = "无法获取 UsageStatsManager。",
                hint = "当前设备不支持使用情况统计；请改用 ui_tree 判断当前界面。"
            )

        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 1000L * 60 * 5,
            now
        )
        val recent = stats
            .filter { it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }

        if (recent == null) {
            return ToolResult.fail(
                code = "permission_missing",
                message = "未能确定前台应用：可能尚未授予“使用情况访问权限”（PACKAGE_USAGE_STATS）。请在「自动化」页引导开启。",
                hint = "引导用户授予使用情况访问权限后重试；或改用 ui_tree 读取界面。"
            )
        }
        val label = try {
            context.packageManager
                .getApplicationLabel(
                    context.packageManager.getApplicationInfo(recent.packageName, 0)
                ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            recent.packageName
        }
        return ToolResult.ok(
            code = "foreground_app_ok",
            message = "当前前台应用：$label（包名：${recent.packageName}）"
        )
    }
}
