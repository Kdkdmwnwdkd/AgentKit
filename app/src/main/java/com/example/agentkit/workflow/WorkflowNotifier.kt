package com.example.agentkit.workflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.agentkit.MainActivity
import com.example.agentkit.core.AgentLogger

/**
 * 定时任务到点通知（W-17）。
 *
 * 为什么是「通知 + 一键执行」而不是「后台直接跑完整 Agent」：
 *  - Agent 的操作链路（无障碍、截图、读前台应用）依赖界面状态，后台跑出来的结果不可信；
 *  - Android 对后台启动界面/长任务限制严格，硬跑只会得到一堆失败。
 *  - 通知点开后进入前台执行，用户能看到过程，也能随时叫停——这是诚实且可用的形态。
 *    （Operit 的 WorkflowScheduler 也会受同样限制，只是它把失败藏起来了。）
 */
object WorkflowNotifier {

    private const val TAG = "WorkflowNotifier"
    const val CHANNEL_ID = "agentkit_workflows"

    /** 通知 → 打开 App 并携带 workflow id（MainActivity 消费后触发执行）。 */
    fun notify(context: Context, workflow: Workflow) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WORKFLOW_ID, workflow.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            workflow.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("定时任务：${workflow.name}")
            .setContentText(workflow.prompt.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(workflow.prompt.take(200)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(workflow.id.hashCode(), notification)
            AgentLogger.i(TAG, "已提醒定时任务「${workflow.name}」")
        }.onFailure {
            AgentLogger.w(TAG, "通知发送失败（可能未授予通知权限）：${it.message}")
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "定时任务",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "到点提醒你执行设定好的任务" }
        )
    }

    const val EXTRA_WORKFLOW_ID = "workflow_id"
}
