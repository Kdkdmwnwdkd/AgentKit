package com.example.agentkit.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.capability.GuideItem
import com.example.agentkit.core.capability.GuideKey
import com.example.agentkit.core.capability.GuideSnapshot
import com.example.agentkit.core.capability.ShizukuStage
import com.example.agentkit.core.capability.buildGuideItems
import com.example.agentkit.core.capability.capabilitySummary
import com.example.agentkit.core.capability.hasBlockingGap
import com.example.agentkit.core.capability.readyCount
import rikka.shizuku.Shizuku

/**
 * 权限引导页（P0，2026-10-21）。
 *
 * ## 与 [PermissionsScreen] 的分工
 * - 权限中心 = **排查页**：出问题时来看「哪一项没开」，缺项置顶，一行一项，只管状态。
 * - 引导页 = **上手页**：第一次用（或换机）时跟着走一遍，逐项说清「开了能多做什么 /
 *   不开会缺什么」，一键跳到系统设置，回来自动复检。
 *
 * 两页共用同一批探测状态（`ChatViewModel.refreshGrants`），所以数字永远一致；
 * 「待授权项数」仍然只算无障碍 / 通知 / 使用情况三项（`pendingGrantCount`），
 * 电池白名单与 Shizuku 是可选增强，不进入抽屉红点——不拿可选能力制造焦虑。
 *
 * ## 交互约定
 * - 每一项的按钮文案按「缺什么给什么」：去开启 / 去设置 / 一键安装 / 打开 Shizuku / 去授权。
 * - 从系统页返回时（ON_RESUME）自动复检，用户不必手动刷新。
 * - 可跳过：跳过后落 `permission_guide_skipped`，下次不再自动进入，但入口一直保留。
 *
 * @param onDone 完成 / 跳过后的去向（回对话页）
 */
@Composable
fun PermissionGuideScreen(vm: ChatViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val batteryUnrestricted by vm.batteryUnrestricted.collectAsStateWithLifecycle()
    val shizukuUiState by vm.shizukuUiState.collectAsStateWithLifecycle()

    val snapshot = GuideSnapshot(
        accessibility = connected,
        notificationListener = notificationGranted,
        usageAccess = usageGranted,
        batteryUnrestricted = batteryUnrestricted,
        shizuku = shizukuUiState.toStage()
    )
    val items = buildGuideItems(snapshot)

    // 从系统设置页返回即复检：用户刚在设置里开了某项，回到本页必须立刻反映
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshGrants()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Shizuku 的授权 / 启动回调发生在系统弹窗或 Shizuku 自身界面，本页只负责收结果
    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            vm.refreshShizukuStatus()
        }
        val binderReceived = Shizuku.OnBinderReceivedListener { vm.refreshShizukuStatus() }
        val binderDead = Shizuku.OnBinderDeadListener { vm.refreshShizukuStatus() }
        val ok = runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            true
        }.getOrDefault(false)
        onDispose {
            if (ok) runCatching {
                Shizuku.removeRequestPermissionResultListener(permissionListener)
                Shizuku.removeBinderReceivedListener(binderReceived)
                Shizuku.removeBinderDeadListener(binderDead)
            }
        }
    }

    val onOpen: (GuideKey) -> Unit = { key ->
        when (key) {
            GuideKey.ACCESSIBILITY -> vm.openAccessibilitySettings(ctx)
            GuideKey.NOTIFICATION -> ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            GuideKey.USAGE -> ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            GuideKey.BATTERY -> vm.openBatteryOptimizationSettings(ctx)
            GuideKey.SHIZUKU -> vm.handleShizukuAction(ctx)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = "权限引导",
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "重新检测",
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
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space5)
        ) {
            GuideHeader(items)

            // —— 五项能力：一张卡内逐项走，顺序即教学顺序（不按缺失置顶）——
            AkGroupCard {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        AkDivider(Modifier.padding(start = AgentSpacing.space4, end = AgentSpacing.space4))
                    }
                    GuideRow(item = item, onOpen = { onOpen(item.key) })
                }
            }

            // —— 数据去向：授权前把话说清楚，避免「凭什么要这些权限」——
            AkSectionCard(
                title = "这些权限会拿什么数据",
                icon = Icons.Outlined.Shield,
                description = "无障碍：读当前界面结构，用于执行你发出的点击 / 输入指令；" +
                    "通知：读最近通知标题与内容；使用情况：读当前前台应用名。" +
                    "这些内容只在执行你的指令时随对话发给你配置的模型接口，" +
                    "不经过 AgentKit 的任何服务器，也不会上传到别处。"
            ) {}

            // —— 收尾：完成 / 稍后再说。跳过只是不自动进入，入口始终在权限中心 ——
            Column(Modifier.fillMaxWidth()) {
                AkPrimaryButton(
                    text = if (hasBlockingGap(items)) "先这样，去对话" else "开始使用",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        markGuideSkipped(ctx)
                        onDone()
                    }
                )
                if (hasBlockingGap(items)) {
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "没开无障碍也能聊天，但 Agent 不能替你操作手机。以后在「权限中心」随时能开。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space2)
                    )
                }
            }
        }
    }
}

/** 头部：进度 + 一句「现在能做什么」。 */
@Composable
private fun GuideHeader(items: List<GuideItem>) {
    val ak = LocalAkColors
    val ready = readyCount(items)
    val total = items.size
    val blocked = hasBlockingGap(items)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                // 说「已就绪 N / M」而不是「还差 N 项」：后者用就绪数当缺口数，
                // 3/5 会写成「还差 3 项」，正好把意思说反。
                text = "已就绪 $ready / $total 项",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            // 徽章只在真的 5/5 时出现：blocked 只看硬门槛，4/5（可选项没开）不该说「全部就绪」
            if (ready == total) {
                AkBadge(
                    text = "全部就绪",
                    container = ak.successContainer,
                    content = ak.onSuccessContainer,
                    dotColor = ak.success
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        // 进度条：4dp 细条，已就绪段用 success，其余用 surface-1（不做动画，避免逐项跳变）
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ak.surface1)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ready.toFloat() / total.coerceAtLeast(1))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (blocked) ak.warning else ak.success)
            )
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            text = capabilitySummary(items),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 一条引导项：标题 + 状态徽章 + 「开启后」+（未开启时）「现在缺」+ 动作按钮。 */
@Composable
private fun GuideRow(item: GuideItem, onOpen: () -> Unit) {
    val ak = LocalAkColors
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AgentSpacing.space4,
                vertical = AgentSpacing.space4
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.key.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(AgentSpacing.space2))
            when {
                item.granted -> AkBadge(
                    text = "已开启",
                    container = ak.successContainer,
                    content = ak.onSuccessContainer,
                    dotColor = ak.success
                )
                // 硬门槛缺失用 warning，可选缺失用中性——不把「可选」渲染成错误
                item.required -> AkBadge(
                    text = "必需",
                    container = ak.warningContainer,
                    content = ak.onWarningContainer,
                    dotColor = ak.warning
                )
                else -> AkBadge(
                    text = "可选",
                    container = ak.surface1,
                    content = onVar
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        Text(
            text = item.works,
            style = MaterialTheme.typography.bodySmall,
            color = onVar
        )
        if (!item.granted) {
            Spacer(Modifier.height(AgentSpacing.space1))
            Text(
                text = "现在：${item.missing}",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.required) ak.warning else onVar
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AkOutlinedButton(text = item.actionLabel, onClick = onOpen)
            }
        }
    }
}

/** Shizuku 四态 → 纯模型枚举（两个枚举同构，避免 core 依赖 rikka.*）。 */
private fun ShizukuShell.UiState.toStage(): ShizukuStage = when (this) {
    ShizukuShell.UiState.NOT_INSTALLED -> ShizukuStage.NOT_INSTALLED
    ShizukuShell.UiState.INSTALLED_NOT_RUNNING -> ShizukuStage.NOT_RUNNING
    ShizukuShell.UiState.NO_PERMISSION -> ShizukuStage.NO_PERMISSION
    ShizukuShell.UiState.READY -> ShizukuStage.READY
}

/**
 * 记录「引导已走完 / 已跳过」。
 *
 * 只影响**是否自动进入引导页**，不影响入口——权限中心的入口永远在。
 * 键名与对话页首启引导（`ONBOARDING_PREFS`）分开：两者生命周期不同
 * （模型配置是必做的，权限是增强的），混用一个键会导致「跳过权限 = 跳过配模型」。
 */
internal fun markGuideSkipped(ctx: android.content.Context) {
    ctx.getSharedPreferences(GUIDE_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putBoolean(GUIDE_SEEN_KEY, true).apply()
}

/** 引导是否已被主动跳过（首启流程据此决定要不要自动进）。 */
internal fun guideSeen(ctx: android.content.Context): Boolean =
    ctx.getSharedPreferences(GUIDE_PREFS, android.content.Context.MODE_PRIVATE)
        .getBoolean(GUIDE_SEEN_KEY, false)

internal const val GUIDE_PREFS = "agentkit_permission_guide"
internal const val GUIDE_SEEN_KEY = "guide_seen"
