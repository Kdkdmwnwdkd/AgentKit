package com.example.agentkit.automation

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 通知监听服务（L3 感知层）：实时捕获系统通知，供 Agent “感知”手机正在发生的事。
 *
 * 设计（净室原创）：NotificationListenerService 由用户在系统设置中手动授权开启；
 * 收到的通知只做结构化落库（package / title / text / time），不读取通知以外的任何内容，
 * 也不拦截、不转发到外部，符合最小权限与隐私底线。
 *
 * 注：这是系统级服务而非普通权限，必须在
 *   “设置 → 通知 → 通知访问权限” 中手动授予本应用。
 */
data class NotificationRecord(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val key: String
)

/** 通知结构化落库：只缓存最近 N 条，供 device_notifications 工具读取。 */
object NotificationStore {
    private val queue = ConcurrentLinkedQueue<NotificationRecord>()
    private const val MAX = 60

    val all: List<NotificationRecord> get() = queue.toList()

    fun add(r: NotificationRecord) {
        queue.add(r)
        while (queue.size > MAX) queue.poll()
    }

    fun clear() = queue.clear()
}

class AgentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        // 忽略无正文的系统占位通知，避免噪声
        if (title.isBlank() && text.isBlank()) return
        NotificationStore.add(
            NotificationRecord(
                packageName = sbn.packageName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                key = sbn.key
            )
        )
        // L4 主动感知：把通知摘要同步到设备上下文，供对话层自动注入
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Throwable) { sbn.packageName }
        val body = if (title.isNotBlank()) {
            if (text.isNotBlank()) "$title：$text" else title
        } else text
        DeviceContextStore.addNotification("$label - $body")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
