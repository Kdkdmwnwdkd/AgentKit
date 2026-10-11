package com.example.agentkit.automation

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 设备上下文聚合（L4 主动感知层）。
 *
 * 由两个系统服务实时喂数据，再统一向对话层（LLM）暴露“当前手机在干什么”的快照：
 *  - [AgentAccessibilityService]：窗口切换时写入前台应用包名/应用名
 *  - [AgentNotificationListener]：收到新通知时写入最近通知摘要
 *
 * 设计原则（净室原创、最小权限）：
 *  - 只缓存轻量文本摘要，不保存通知正文之外的任何隐私内容；
 *  - 通知只保留最近 8 条，前台应用只保留最近一个；
 *  - 所有写操作都在系统回调线程之外做轻量只读（PackageManager 转应用名），失败静默降级。
 */
object DeviceContextStore {

    @Volatile private var foregroundPackage: String = ""
    @Volatile private var foregroundLabel: String = ""
    private val recentNotifs = ConcurrentLinkedQueue<String>()
    private const val MAX_NOTIFS = 8

    /** 无障碍服务在窗口切换时调用：记录当前前台应用。 */
    fun updateForeground(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        foregroundPackage = packageName
        foregroundLabel = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Throwable) { packageName }
    }

    /** 通知监听服务在收到通知时调用：追加一条摘要（已含应用名，调用方负责转换）。 */
    fun addNotification(summary: String) {
        if (summary.isBlank()) return
        recentNotifs.add(summary)
        while (recentNotifs.size > MAX_NOTIFS) recentNotifs.poll()
    }

    /** 取一份不可变快照，供 UI 与对话层使用。 */
    fun snapshot(): DeviceContext = DeviceContext(
        foregroundPackage = foregroundPackage,
        foregroundLabel = foregroundLabel,
        recentNotifications = recentNotifs.toList().asReversed(),
        updatedAt = System.currentTimeMillis()
    )
}

/**
 * 设备上下文快照（值对象）。[toPrompt] 输出可直接拼进 LLM 系统提示/用户输入的文本段落。
 */
data class DeviceContext(
    val foregroundPackage: String,
    val foregroundLabel: String,
    val recentNotifications: List<String>,
    val updatedAt: Long
) {
    /** 尚无任何感知数据时返回 true，表示上下文为空、无需注入。 */
    val isEmpty: Boolean
        get() = foregroundPackage.isBlank() && recentNotifications.isEmpty()

    /** 生成 "[当前设备上下文]" 段落；为空则返回空串，调用方据此决定是否注入。 */
    fun toPrompt(): String {
        if (isEmpty) return ""
        val sb = StringBuilder("[当前设备上下文]\n")
        if (foregroundPackage.isNotBlank()) {
            if (foregroundLabel.isNotBlank() && foregroundLabel != foregroundPackage) {
                sb.append("- 前台应用：$foregroundLabel ($foregroundPackage)\n")
            } else {
                sb.append("- 前台应用：$foregroundPackage\n")
            }
        }
        if (recentNotifications.isNotEmpty()) {
            sb.append("- 最近通知：\n")
            recentNotifications.forEachIndexed { i, n -> sb.append("  ${i + 1}. $n\n") }
        }
        return sb.toString().trimEnd()
    }
}
