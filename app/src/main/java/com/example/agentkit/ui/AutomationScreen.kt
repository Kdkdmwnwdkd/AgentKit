package com.example.agentkit.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.automation.DeviceContext

/**
 * 自动化页：能力总控 + 感知 + 技能中心 + 日志 + 记忆库。
 *
 * 本页只做视觉与交互外壳（统一卡片 / 图标 / 徽章 / 按钮层级），
 * 所有能力（权限跳转、截图捕获、技能导入卸载授权、日志导出、记忆增删）一律保留。
 *
 * @param onBack 回顶部导航的「对话」页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val enabled by vm.automationEnabled.collectAsStateWithLifecycle()
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val deviceContext by vm.deviceContext.collectAsStateWithLifecycle()
    val skillInfos by vm.skillInfos.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // L8 运行日志 / L9 记忆库状态
    val logs by vm.logs.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    var memoryQuery by remember { mutableStateOf("") }
    var memoryDraft by remember { mutableStateOf("") }
    var showClearMemoryDialog by remember { mutableStateOf(false) }

    // L7 导入相关状态
    val importStatus by vm.importStatus.collectAsStateWithLifecycle()
    var showImportDialog by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var authTarget by remember { mutableStateOf<SkillUiInfo?>(null) }
    var uninstallTarget by remember { mutableStateOf<SkillUiInfo?>(null) }

    // 从本机选择 .zip 文件（SAF）：只把 Uri 交给 ViewModel，由其切到 IO 线程读字节
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importFromUri(it) }
    }

    // 进入本页时刷新权限授予状态，反映用户在系统设置里的最新操作；同时刷新日志与记忆
    LaunchedEffect(Unit) {
        vm.refreshGrants()
        vm.refreshLogs()
        vm.refreshMemories()
    }

    // 屏幕捕获授权：调起系统“是否允许截取屏幕”弹窗
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            vm.setScreenCapturePermission(res.resultCode, res.data)
            vm.startScreenCapture(ctx)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("自动化", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回对话")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // 本页内容已很多，必须可滚动，否则新增卡片会被挤出屏幕
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // —— 状态总览 ——
            SectionCard(
                icon = Icons.Default.Handyman,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "状态总览",
                description = "Agent 要替你操作手机，无障碍服务与总开关缺一不可。"
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusTile("无障碍服务", connected, Modifier.weight(1f))
                    StatusTile("自动化总开关", enabled, Modifier.weight(1f))
                    StatusTile("屏幕捕获", capturing, Modifier.weight(1f))
                }
            }

            // —— 总开关 ——
            SectionCard(
                icon = Icons.Default.Security,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "总开关",
                description = "关闭后所有手机操作类工具立即失效，Agent 只能对话。"
            ) {
                SwitchRow(
                    icon = Icons.Default.Accessibility,
                    title = "自动化总开关",
                    subtitle = "开启后 Agent 才能操作手机",
                    checked = enabled,
                    onCheckedChange = { vm.setAutomationEnabled(it) }
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "前往系统设置开启无障碍服务",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.openAccessibilitySettings(ctx) }
                )
            }

            // —— L2 屏幕捕获（截图视觉）——
            SectionCard(
                icon = Icons.Default.Photo,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "屏幕捕获（截图视觉）",
                description = "开启后，Agent 可通过 ui_screenshot 截取屏幕并“看见”图标/图片/验证码等 UI 树无法描述的内容。"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        text = if (capturing) "捕获中" else "未开启",
                        color = if (capturing) AgentColors.success else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                if (!capturing) {
                    PrimaryButton(
                        text = "开始屏幕捕获（授权录屏）",
                        icon = Icons.Default.Screenshot,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            captureLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    )
                } else {
                    SecondaryButton(
                        text = "停止捕获",
                        icon = Icons.Default.Stop,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.stopScreenCapture(ctx) }
                    )
                }
            }

            // —— L3 感知层：通知监听 + 使用情况访问 ——
            SectionCard(
                icon = Icons.Default.Visibility,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "设备感知（通知与当前应用）",
                description = "两项权限到位后，Agent 才能读取新通知与当前前台应用。"
            ) {
                GrantRow(
                    icon = Icons.Default.Notifications,
                    title = "通知访问",
                    granted = notificationGranted,
                    grantedHint = "已授权，可读取新消息/推送",
                    missingHint = "未授权，需到系统设置开启"
                )
                Spacer(Modifier.height(8.dp))
                GrantRow(
                    icon = Icons.Default.Apps,
                    title = "使用情况访问",
                    granted = usageGranted,
                    grantedHint = "已授权，可读取当前前台应用",
                    missingHint = "未授权，需到系统设置开启"
                )
                if (!notificationGranted) {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "开启通知访问权限",
                        icon = Icons.Default.Notifications,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                }
                if (!usageGranted) {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "开启使用情况访问权限",
                        icon = Icons.Default.Apps,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )
                }
            }

            // —— L4 主动感知：实时上下文 ——
            DeviceContextCard(deviceContext)

            // —— L6 技能中心 ——
            SectionCard(
                icon = Icons.Default.Extension,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "技能中心（Skills）",
                description = "应用内置安全策略：远程/导入来源默认禁止脚本执行，且能力上限收紧，防止技能越权操控手机。"
            ) {
                PrimaryButton(
                    text = "导入技能",
                    icon = Icons.Default.Download,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showImportDialog = true }
                )
                Spacer(Modifier.height(12.dp))
                val status = importStatus
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                if (skillInfos.isEmpty()) {
                    Text("暂无已安装技能。", style = MaterialTheme.typography.bodySmall)
                } else {
                    skillInfos.forEach { skill ->
                        SkillCard(
                            skill = skill,
                            onAuthorize = { authTarget = skill },
                            onRevoke = { vm.setScriptAuthorization(skill.id, false) },
                            onUninstall = { uninstallTarget = skill }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // —— L8 运行日志 ——
            SectionCard(
                icon = Icons.Default.Description,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "运行日志（日记）",
                description = "Agent 的每一步都会记在这里：LLM 请求/响应、工具调用与结果、HTTP 错误。" +
                    "运行出错时在这里定位，可导出排查。"
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "刷新",
                        icon = Icons.Default.Refresh,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.refreshLogs() }
                    )
                    SecondaryButton(
                        text = "导出",
                        icon = Icons.Default.ContentCopy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val text = vm.exportLogs()
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("AgentKit日志", text))
                            vm.setImportStatus("已复制到剪贴板（${text.length} 字符）")
                        }
                    )
                    SecondaryButton(
                        text = "清空",
                        icon = Icons.Default.Delete,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.clearLogs() }
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (logs.isEmpty()) {
                    Text(
                        "暂无日志。发一条消息或触发一次技能后，这里会实时显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        // 已滚动容器内不使用 LazyColumn（嵌套滚动冲突），直接遍历渲染
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            logs.take(20).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "日志文件：${vm.logFilePath()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // —— L9 记忆库 ——
            SectionCard(
                icon = Icons.Default.Bookmark,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "记忆库（跨会话）",
                description = "当前展示 ${memories.size} 条。Agent 每次成功回答会自动沉淀一条 event 记忆；" +
                    "也可在这里手动记录偏好或关键信息。"
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = memoryQuery,
                        onValueChange = { memoryQuery = it },
                        label = { Text("搜索记忆") },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton(text = "搜索", onClick = { vm.searchMemory(memoryQuery) })
                }
                Spacer(Modifier.height(12.dp))
                if (memories.isEmpty()) {
                    Text("暂无记忆。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        memories.take(20).forEach { item ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusBadge(
                                        text = item.kind,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.content, style = MaterialTheme.typography.bodyMedium)
                                        if (item.tags.isNotEmpty()) {
                                            Text(
                                                "#${item.tags.joinToString(" #")} · 重要度 ${item.salience}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    TextButton(onClick = { vm.forgetMemory(item.id) }) {
                                        Text("忘记")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = memoryDraft,
                    onValueChange = { memoryDraft = it },
                    label = { Text("手动记住一条") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    maxLines = 3
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = "记住这条",
                    icon = Icons.Default.Bookmark,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.addMemory(memoryDraft, "note", emptyList())
                        memoryDraft = ""
                    }
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "全部记忆",
                        icon = Icons.Default.History,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            memoryQuery = ""
                            vm.refreshMemories()
                        }
                    )
                    SecondaryButton(
                        text = "清空记忆",
                        icon = Icons.Default.Delete,
                        modifier = Modifier.weight(1f),
                        onClick = { showClearMemoryDialog = true }
                    )
                }
            }

            // —— 能力说明 ——
            SectionCard(
                icon = Icons.Default.Apps,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "可用能力",
                description = "可用工具：ui_tree（读取界面）、ui_tap / ui_long_press / ui_swipe（手势）、" +
                    "ui_input（输入文本）、ui_key（home/back/recent）、ui_click（按文本或 ID 点击）、" +
                    "ui_screenshot（截取屏幕给视觉模型看）、device_notifications（读取最近通知）、" +
                    "device_foreground_app（读取当前前台应用）。\n" +
                    "在对话里直接说“打开设置并点击某某”“截个屏看看现在是什么界面”“刚才有没有新消息”" +
                    "即可让 Agent 操作并感知手机。"
            ) { /* 说明卡：内容即 description，无需额外控件 */ }

            // —— L7 导入 / 授权 / 卸载 Dialog（功能保持，仅换皮）——
            if (showImportDialog) {
                ImportSkillDialog(
                    url = importUrl,
                    onUrlChange = { importUrl = it },
                    onDismiss = { showImportDialog = false },
                    onImportUrl = {
                        vm.importFromUrl(importUrl)
                        showImportDialog = false
                    },
                    onPickFile = {
                        importLauncher.launch("application/zip")
                        showImportDialog = false
                    },
                    onImportDemo = {
                        vm.importDemo()
                        showImportDialog = false
                    }
                )
            }

            if (authTarget != null) {
                AlertDialog(
                    onDismissRequest = { authTarget = null },
                    shape = MaterialTheme.shapes.large,
                    title = { Text("授权脚本执行") },
                    text = {
                        Text(
                            "该技能来自导入来源，执行脚本(script 形态)可能调用高危工具操控手机。" +
                                "确认授权后它将获得与内置技能同等的脚本执行权限。"
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            authTarget?.let { vm.setScriptAuthorization(it.id, true) }
                            authTarget = null
                        }) { Text("确认授权") }
                    },
                    dismissButton = {
                        TextButton(onClick = { authTarget = null }) { Text("取消") }
                    }
                )
            }

            if (uninstallTarget != null) {
                AlertDialog(
                    onDismissRequest = { uninstallTarget = null },
                    shape = MaterialTheme.shapes.large,
                    title = { Text("卸载技能") },
                    text = {
                        Text("确认卸载「${uninstallTarget?.name ?: ""}」？该技能目录将被删除，且脚本授权一并撤销。")
                    },
                    confirmButton = {
                        Button(onClick = {
                            uninstallTarget?.let { vm.uninstallSkill(it.id) }
                            uninstallTarget = null
                        }) { Text("确认卸载") }
                    },
                    dismissButton = {
                        TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
                    }
                )
            }

            // L9 清空记忆库二次确认 Dialog（删了就没有了，必须拦一道）
            if (showClearMemoryDialog) {
                AlertDialog(
                    onDismissRequest = { showClearMemoryDialog = false },
                    shape = MaterialTheme.shapes.large,
                    title = { Text("清空记忆库") },
                    text = {
                        Text("确认清空全部记忆（${memories.size} 条）？Agent 将忘掉所有偏好与过往，且无法恢复。")
                    },
                    confirmButton = {
                        Button(onClick = {
                            vm.clearMemory()
                            showClearMemoryDialog = false
                        }) { Text("确认清空") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearMemoryDialog = false }) { Text("取消") }
                    }
                )
            }
        }
    }
}

// ———————————————————— 复用构件（统一视觉） ————————————————————

/**
 * 统一分区卡片：行内图标 + 标题 + 说明 + 内容槽。
 *
 * @param icon 分区图标。
 * @param iconTint 图标着色。
 * @param title 分区标题（titleMedium）。
 * @param description 分区说明（bodySmall）。
 * @param content 卡片主体，由调用方填充。
 */
@Composable
private fun SectionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = iconTint.copy(alpha = 0.12f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 三宫格状态：绿色=就绪，灰=未开启。 */
@Composable
private fun StatusTile(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    val color = if (ok) AgentColors.success else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.10f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) { }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (ok) "已开启" else "未开启",
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

/** 带图标的开关项：主标题 + 副标题 + Switch。 */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 权限授予状态行：右侧彩色徽章替代原先的 ✅/⚠️ 文本。 */
@Composable
private fun GrantRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    grantedHint: String,
    missingHint: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (granted) grantedHint else missingHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            StatusBadge(text = "已授权", color = AgentColors.success)
        } else {
            StatusBadge(text = "未授权", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 主操作：实心按钮；宽度由 [modifier] 决定（默认包裹内容）。 */
@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Button(onClick = onClick, modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 次要/破坏性操作：描边按钮；宽度由 [modifier] 决定（默认包裹内容）。 */
@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** L4 主动感知：展示 Agent 实时感知到的设备上下文。 */
@Composable
private fun DeviceContextCard(deviceContext: DeviceContext) {
    SectionCard(
        icon = Icons.Default.Apps,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "当前手机上下文（Agent 主动感知）",
        description = "每次发消息时该上下文会自动注入给模型。"
    ) {
        if (deviceContext.isEmpty) {
            Text(
                "暂无感知数据。开启无障碍服务后切换 App、或收到新通知，这里会实时显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            if (deviceContext.foregroundPackage.isNotBlank()) {
                val fg = if (deviceContext.foregroundLabel.isNotBlank() &&
                    deviceContext.foregroundLabel != deviceContext.foregroundPackage
                ) {
                    "${deviceContext.foregroundLabel} (${deviceContext.foregroundPackage})"
                } else {
                    deviceContext.foregroundPackage
                }
                StatusBadge(text = "前台应用：$fg", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            if (deviceContext.recentNotifications.isNotEmpty()) {
                Text("最近通知：", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    deviceContext.recentNotifications.forEach { n ->
                        Text(
                            "• $n",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 单个技能卡片：信任等级徽章 + 形态摘要 + 工具能力清单 + 来源操作。 */
@Composable
private fun SkillCard(
    skill: SkillUiInfo,
    onAuthorize: () -> Unit,
    onRevoke: () -> Unit,
    onUninstall: () -> Unit
) {
    val trustColor = when (skill.trust.lowercase()) {
        "remote" -> MaterialTheme.colorScheme.error
        "imported" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                StatusBadge(text = skill.trust.uppercase(), color = trustColor)
            }
            if (skill.description.isNotBlank()) {
                Text(skill.description, style = MaterialTheme.typography.bodySmall)
            }
            StatusBadge(
                text = "形态：${if (skill.isScript) "脚本 main.js" else "提示词"} · 允许脚本：" +
                    "${if (skill.allowScript) "是" else "否"} · 能力上限：${skill.maxCapability}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (skill.tools.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    skill.tools.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (t.allowed) Icons.Default.Extension else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = if (t.allowed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${t.name} [${t.capability}] ${if (t.allowed) "放行" else "拦截"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.allowed) LocalContentColor.current else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            // 按「来源」而非 trust 显示操作：授权后仍可撤销，避免一次授权不可逆
            if (skill.isImported) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (skill.trust.lowercase() == "imported") {
                        Text(
                            "允许脚本执行",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        PrimaryButton(text = "授权脚本", onClick = onAuthorize)
                    } else {
                        Text(
                            "已授权脚本执行",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        PrimaryButton(text = "撤销授权", onClick = onRevoke)
                    }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(text = "卸载", onClick = onUninstall)
                }
            }
        }
    }
}

/** L7 导入技能 Dialog：URL 导入 / 本机选择 / 内置示例，三条路径都保留。 */
@Composable
private fun ImportSkillDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImportUrl: () -> Unit,
    onPickFile: () -> Unit,
    onImportDemo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("导入技能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("Zip 下载地址(URL)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true
                )
                Row {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "支持从 URL 下载 zip，或从本机选择 .zip 文件，或载入内置示例。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PrimaryButton(
                    text = "从 URL 导入",
                    icon = Icons.Default.Download,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onImportUrl
                )
                SecondaryButton(
                    text = "从本机选择文件",
                    icon = Icons.Default.Folder,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPickFile
                )
                SecondaryButton(
                    text = "载入示例技能",
                    icon = Icons.Default.Extension,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onImportDemo
                )
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
