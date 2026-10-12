package com.example.agentkit.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 权限中心页（UX v1 §1.4）：四项系统能力的就绪度总览 + 缺失置顶 + 一键跳转。
 *
 * 从旧自动化页拆出：权限是「排查型」信息，和「操作型」的控制台分开，
 * 用户出问题时先进权限中心，而不是在一堆开关里找哪一项没开。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「权限中心」+ 右上「刷新」）。
 * 待授权项数与抽屉红点、控制台三宫格共用 [pendingGrantCount]——同一状态源必须同一计数。
 */
@Composable
fun PermissionsScreen(vm: ChatViewModel) {
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val pendingGrants = pendingGrantCount(connected, capturing, notificationGranted, usageGranted)

    // 从系统设置页返回时刷新：用户刚在设置里开了某项，回到本页要立刻反映
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshGrants()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 屏幕捕获走系统录屏授权弹窗（与控制台同一启动器实现）
    val startCapture = rememberScreenCaptureLauncher(vm)

    val grants = listOf(
        GrantItem(
            title = "无障碍服务",
            granted = connected,
            icon = Icons.Outlined.Accessibility,
            grantedHint = "已连接，Agent 可读取界面并执行点击、滑动、输入",
            missingHint = "未连接，Agent 无法操作手机，先开这一项",
            onOpen = { vm.openAccessibilitySettings(ctx) }
        ),
        GrantItem(
            title = "屏幕捕获",
            granted = capturing,
            icon = Icons.Outlined.Screenshot,
            grantedHint = "捕获中，Agent 可截取屏幕给视觉模型看",
            missingHint = "未开启，Agent 看不到图标、图片与验证码",
            onOpen = { startCapture() }
        ),
        GrantItem(
            title = "通知访问",
            granted = notificationGranted,
            icon = Icons.Outlined.Notifications,
            grantedHint = "已授权，Agent 可读取最近通知",
            missingHint = "未授权，Agent 不知道有没有新消息",
            onOpen = {
                ctx.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),
        GrantItem(
            title = "使用情况访问",
            granted = usageGranted,
            icon = Icons.Outlined.Apps,
            grantedHint = "已授权，Agent 可读取当前前台应用",
            missingHint = "未授权，Agent 不知道你此刻在用哪个 App",
            onOpen = {
                ctx.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
    )

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.PERMISSIONS.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = { vm.refreshGrants() }
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
            // —— 就绪度总览：与抽屉头部同一实现、同一措辞（§6.5.2）——
            AkSectionCard(title = "权限就绪度", icon = Icons.Outlined.Shield) {
                AkReadyTextRow(
                    serviceConnected = connected,
                    pendingGrants = pendingGrants,
                    onClick = { vm.refreshGrants() }
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Text(
                    text = "共四项能力：无障碍服务、屏幕捕获、通知访问、使用情况访问。" +
                        "缺项已置顶排列，点「去开启」会跳转对应的系统设置页，" +
                        "授予后回到本页即自动刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // —— 权限列表：未授权置顶（待办优先被处理，§1.4）——
            AkGroupCard {
                // sortedBy 稳定排序：false（未授权）在前，同组内保持上面的固定顺序
                grants.sortedBy { it.granted }.forEachIndexed { index, item ->
                    if (index > 0) {
                        // 分隔线缩进 16dp（与左侧图标底座右缘对齐，§6.1）
                        AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    }
                    GrantRow(item)
                }
            }

            // —— 说明：每项权限的用途与数据去向 ——
            AkSectionCard(
                title = "这些权限用来做什么",
                icon = Icons.Outlined.Info,
                description = "无障碍服务：读取当前界面，在你发出指令后执行点击、滑动、输入，" +
                    "是 Agent 操作手机的基础，没有它其余三项也没有意义。\n" +
                    "屏幕捕获：把屏幕截图交给视觉模型，用来识别图标、图片与验证码。\n" +
                    "通知访问：读取最近通知，回答“刚才有没有新消息”。\n" +
                    "使用情况访问：读取当前前台应用，让 Agent 知道你在用哪个 App。\n" +
                    "前台应用名与通知内容会随对话一起发送到你配置的模型接口，" +
                    "除此之外不会外传到任何地方。"
            )
        }
    }
}

/** 权限中心的一行：静态描述 + 当前授予态 + 跳转动作（不可点整行，只点「去开启」，防误跳）。 */
private class GrantItem(
    val title: String,
    val granted: Boolean,
    val icon: ImageVector,
    val grantedHint: String,
    val missingHint: String,
    val onOpen: () -> Unit
)

@Composable
private fun GrantRow(item: GrantItem) {
    val ak = LocalAkColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(
                horizontal = AgentSpacing.space4,
                vertical = AgentSpacing.space3
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AkIconBase(
            icon = item.icon,
            tint = if (item.granted) ak.success else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(AgentSpacing.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (item.granted) item.grantedHint else item.missingHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(AgentSpacing.space2))
        if (item.granted) {
            AkBadge(
                text = "已授权",
                container = ak.successContainer,
                content = ak.onSuccessContainer,
                dotColor = ak.success
            )
        } else {
            AkTextButton(text = "去开启", onClick = item.onOpen)
        }
    }
}
