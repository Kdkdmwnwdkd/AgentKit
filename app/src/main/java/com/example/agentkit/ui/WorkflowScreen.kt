package com.example.agentkit.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.workflow.Workflow
import com.example.agentkit.workflow.describeTrigger

/**
 * 定时任务页（W-17）。
 *
 * 交互定位：
 *  - **创建走对话**（「每天早上八点帮我整理通知」→ 模型调 workflow_create），不在表单里填五个字段；
 *  - 页面只负责「看、开关、删、立即运行」——低频管理动作，一屏够用。
 */
@Composable
fun WorkflowScreen(vm: ChatViewModel) {
    val list by vm.workflows.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // W-19：通知权限没开时，定时任务到点不会有任何提示——必须在页面上明说并给入口
    val notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.WORKFLOW.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = { vm.refreshWorkflows() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            if (!notificationsOn) {
                AkSectionCard(title = "通知权限未开启", icon = Icons.Outlined.Schedule) {
                    Text(
                        text = "定时任务到点靠通知提醒你；通知权限关着就不会有任何提示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkTextButton(text = "去开启通知", onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                }
            }

            AkSectionCard(
                title = "怎么用",
                icon = Icons.Outlined.Schedule,
                description = "到点会发通知，点开即执行（Agent 需要前台才能操作手机，所以不做后台硬跑）。" +
                    "也可以直接在对话里说「每天早上八点提醒我整理通知」，我会自己建。"
            ) {
                Text(
                    text = "已创建任务会按规则巡检（每 15 分钟一次）；手机重启后自动恢复。" +
                        "停用后不再提醒，删除不可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (list.isEmpty()) {
                AkSectionCard(title = "还没有任务", icon = Icons.Outlined.Schedule) {
                    Text(
                        text = "去对话里说一句：「每天早上 8 点帮我整理通知」或「每周日晚上 9 点清理下载目录」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AkGroupCard {
                    list.forEachIndexed { index, wf ->
                        if (index > 0) AkDivider(Modifier.padding(start = AgentSpacing.space4))
                        WorkflowRow(
                            workflow = wf,
                            onToggle = { vm.setWorkflowEnabled(wf.id, it) },
                            onRunNow = { vm.runWorkflowNow(wf.id) },
                            onDelete = { vm.deleteWorkflow(wf.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowRow(
    workflow: Workflow,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space3)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = workflow.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = describeTrigger(workflow.trigger) +
                        (workflow.lastRunAt?.let { " · 上次已提醒" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = workflow.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        Text(
            text = workflow.prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        AkTextButton(
            text = "立即运行",
            onClick = onRunNow,
            enabled = workflow.enabled
        )
    }
}
