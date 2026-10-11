package com.example.agentkit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import com.example.agentkit.automation.DeviceContext
import com.example.agentkit.skill.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppNav(vm: ChatViewModel) {
    var route by remember { mutableStateOf("chat") }
    when (route) {
        "chat" -> ChatScreen(vm, { route = "config" }, { route = "automation" })
        "config" -> ModelConfigScreen(vm) { route = "chat" }
        "automation" -> AutomationScreen(vm) { route = "chat" }
        else -> ChatScreen(vm, { route = "config" }, { route = "automation" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenConfig: () -> Unit, onOpenAutomation: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val running by vm.isRunning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AgentKit") },
                actions = {
                    TextButton(onClick = onOpenAutomation) { Text("自动化") }
                    TextButton(onClick = onOpenConfig) { Text("模型") }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { vm.input.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.send() }, enabled = !running) {
                    Text(if (running) "…" else "发送")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { MessageRow(it) }
        }
    }
}

@Composable
private fun MessageRow(item: UiItem) {
    when (item) {
        is UiItem.User -> MessageBubble(item.text, MaterialTheme.colorScheme.primaryContainer, Alignment.End)
        is UiItem.Assistant -> MessageBubble(item.text, MaterialTheme.colorScheme.surfaceVariant, Alignment.Start)
        is UiItem.Tool -> ToolCard(item)
    }
}

@Composable
private fun MessageBubble(text: String, color: androidx.compose.ui.graphics.Color, alignment: Alignment.Horizontal) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignment == Alignment.End) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            SelectionContainer { Text(text, Modifier.padding(10.dp)) }
        }
    }
}

/** 把 data URL（base64）解码成可显示的位图，异步避免阻塞主线程。 */
@Composable
private fun DataImage(dataUrl: String) {
    val bitmap by produceState<ImageBitmap?>(null, dataUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val comma = dataUrl.indexOf(',')
                if (comma < 0 || !dataUrl.startsWith("data:image")) return@runCatching null
                val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "工具返回的图片",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        )
    }
}

/** 工具调用用折叠卡片展示：默认只显示工具名，点「详情」展开入参与结果；图片默认可见。 */
@Composable
private fun ToolCard(item: UiItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧 ${item.call.name}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "详情")
                }
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        "入参：${item.call.arguments}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        item.result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isError) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // 工具返回的图片（如 ui_screenshot 截图）直接渲染在卡片内，默认可见
            if (item.images.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.images.forEach { DataImage(it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") })
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key") })
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名") })
            Button(onClick = {
                vm.updateConfig(ModelConfig(baseUrl, apiKey, model))
                onBack()
            }) { Text("保存") }
        }
    }
}

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
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            vm.setScreenCapturePermission(res.resultCode, res.data)
            vm.startScreenCapture(ctx)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动化") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (connected) "✅ 无障碍服务已连接" else "⚠️ 无障碍服务未连接（需在系统设置中开启 AgentKit 自动化）"
                    )
                    Text(if (enabled) "✅ 自动化总开关：开" else "⛔ 自动化总开关：关")
                    Text(if (capturing) "✅ 屏幕捕获中（视觉模型可读取画面）" else "⛔ 屏幕捕获未开启")
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自动化总开关（开启后 Agent 才能操作手机）", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { vm.setAutomationEnabled(it) })
            }

            Button(
                onClick = { vm.openAccessibilitySettings(ctx) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("前往系统设置开启无障碍服务") }

            // L2 截图视觉：开启后 Agent 可“看”屏幕
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("屏幕捕获（截图视觉）", style = MaterialTheme.typography.titleMedium)
                    Text("开启后，Agent 可通过 ui_screenshot 截取屏幕并“看见”图标/图片/验证码等 UI 树无法描述的内容。")
                    if (!capturing) {
                        Button(
                            onClick = {
                                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                captureLauncher.launch(mpm.createScreenCaptureIntent())
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("开始屏幕捕获（授权录屏）") }
                    } else {
                        Button(
                            onClick = { vm.stopScreenCapture(ctx) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) { Text("停止捕获") }
                    }
                }
            }

            // L3 感知层：通知监听 + 当前前台应用感知
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("设备感知（通知与当前应用）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notificationGranted) "✅ 通知访问已授权（可读取新消息/推送）"
                        else "⚠️ 通知访问未授权（需在系统设置中开启）"
                    )
                    Text(
                        if (usageGranted) "✅ 使用情况访问已授权（可读取当前前台应用）"
                        else "⚠️ 使用情况访问未授权（需在系统设置中开启）"
                    )
                    if (!notificationGranted) {
                        Button(
                            onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("开启通知访问权限") }
                    }
                    if (!usageGranted) {
                        Button(
                            onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("开启使用情况访问权限") }
                    }
                }
            }

            // L4 主动感知：展示 Agent 实时感知到的设备上下文，发消息时自动注入对话
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前手机上下文（Agent 主动感知）", style = MaterialTheme.typography.titleMedium)
                    if (deviceContext.isEmpty) {
                        Text("暂无感知数据。开启无障碍服务后切换 App、或收到新通知，这里会实时显示；每次发消息时该上下文会自动注入给模型。")
                    } else {
                        if (deviceContext.foregroundPackage.isNotBlank()) {
                            val fg = if (deviceContext.foregroundLabel.isNotBlank() && deviceContext.foregroundLabel != deviceContext.foregroundPackage)
                                "${deviceContext.foregroundLabel} (${deviceContext.foregroundPackage})"
                            else deviceContext.foregroundPackage
                            Text("前台应用：$fg")
                        }
                        if (deviceContext.recentNotifications.isNotEmpty()) {
                            Text("最近通知：", style = MaterialTheme.typography.labelMedium)
                            deviceContext.recentNotifications.forEach { n ->
                                Text("• $n", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // L6 技能中心：把 L5 的全局能力门与安全策略可视化，让安全策略「看得见」
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("技能中心（Skills）", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("＋ 导入技能") }
                    Text(
                        "应用内置安全策略：远程/导入来源默认禁止脚本执行，且能力上限收紧，防止技能越权操控手机。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (skillInfos.isEmpty()) {
                        Text("暂无已安装技能。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        skillInfos.forEach { skill ->
                            val trustColor = when (skill.trust.lowercase()) {
                                "remote" -> MaterialTheme.colorScheme.error
                                "imported" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(skill.name, style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        color = trustColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            skill.trust.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = trustColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (skill.description.isNotBlank()) {
                                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "形态：${if (skill.isScript) "脚本(main.js)" else "提示词"} · 允许脚本：${if (skill.allowScript) "是" else "否"} · 能力上限：${skill.maxCapability}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (skill.tools.isNotEmpty()) {
                                    skill.tools.forEach { t ->
                                        Text(
                                            "• ${t.name} [${t.capability}] ${if (t.allowed) "放行" else "拦截"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (t.allowed) LocalContentColor.current else MaterialTheme.colorScheme.error
                                        )
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
                                            Button(onClick = { authTarget = skill }) { Text("授权脚本") }
                                        } else {
                                            Text(
                                                "已授权脚本执行",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(onClick = { vm.setScriptAuthorization(skill.id, false) }) {
                                                Text("撤销授权")
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = { uninstallTarget = skill }) { Text("卸载") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    val status = importStatus
                    if (status != null) {
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // L8 运行日志（日记）：真机出错时唯一的现场证据来源
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("运行日志（日记）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Agent 的每一步都会记在这里：LLM 请求/响应、工具调用与结果、HTTP 错误。" +
                            "运行出错时在这里定位，可导出排查。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.refreshLogs() }, modifier = Modifier.weight(1f)) { Text("刷新") }
                        Button(
                            onClick = {
                                val text = vm.exportLogs()
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("AgentKit日志", text))
                                vm.setImportStatus("已复制到剪贴板（${text.length} 字符）")
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("导出到剪贴板") }
                        Button(
                            onClick = { vm.clearLogs() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) { Text("清空") }
                    }
                    if (logs.isEmpty()) {
                        Text("暂无日志。发一条消息或触发一次技能后，这里会实时显示。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        // 已滚动容器内不使用 LazyColumn（嵌套滚动冲突），直接遍历渲染
                        logs.take(20).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text("日志文件：${vm.logFilePath()}", style = MaterialTheme.typography.labelSmall)
                }
            }

            // L9 记忆库：跨会话长期记忆，让 Agent “记得”用户的偏好与过往
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("记忆库（跨会话）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "当前展示 ${memories.size} 条。Agent 每次成功回答会自动沉淀一条 event 记忆；" +
                            "也可在这里手动记录偏好或关键信息。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = memoryQuery,
                            onValueChange = { memoryQuery = it },
                            label = { Text("搜索记忆") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.searchMemory(memoryQuery) }) { Text("搜索") }
                    }
                    if (memories.isEmpty()) {
                        Text("暂无记忆。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        memories.take(20).forEach { item ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        item.kind,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(item.content, style = MaterialTheme.typography.bodySmall)
                                    if (item.tags.isNotEmpty()) {
                                        Text(
                                            "#${item.tags.joinToString(" #")} · 重要度 ${item.salience}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                TextButton(onClick = { vm.forgetMemory(item.id) }) { Text("忘记") }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = memoryDraft,
                            onValueChange = { memoryDraft = it },
                            label = { Text("手动记住一条") },
                            modifier = Modifier.weight(1f),
                            maxLines = 3
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            vm.addMemory(memoryDraft, "note", emptyList())
                            memoryDraft = ""
                        }) { Text("记住") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { memoryQuery = ""; vm.refreshMemories() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) { Text("全部记忆") }
                        Button(
                            onClick = { showClearMemoryDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) { Text("清空记忆") }
                    }
                }
            }

            Text(
                "可用工具：ui_tree（读取界面）、ui_tap / ui_long_press / ui_swipe（手势）、ui_input（输入文本）、ui_key（home/back/recent）、ui_click（按文本或 ID 点击）、ui_screenshot（截取屏幕给视觉模型看）、device_notifications（读取最近通知）、device_foreground_app（读取当前前台应用）。\n" +
                    "在对话里直接说“打开设置并点击某某”“截个屏看看现在是什么界面”“刚才有没有新消息”即可让 Agent 操作并感知手机。",
                style = MaterialTheme.typography.bodySmall
            )

            // L7 导入技能 Dialog
            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    title = { Text("导入技能") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = importUrl,
                                onValueChange = { importUrl = it },
                                label = { Text("Zip 下载地址(URL)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "支持从 URL 下载 zip，或从本机选择 .zip 文件，或载入内置示例。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Column {
                            Button(onClick = { vm.importFromUrl(importUrl); showImportDialog = false }) {
                                Text("从 URL 导入")
                            }
                            Button(onClick = { importLauncher.launch("application/zip"); showImportDialog = false }) {
                                Text("从本机选择文件")
                            }
                            Button(onClick = { vm.importDemo(); showImportDialog = false }) {
                                Text("载入示例技能")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportDialog = false }) { Text("取消") }
                    }
                )
            }

            // L7 授权脚本执行确认 Dialog
            if (authTarget != null) {
                AlertDialog(
                    onDismissRequest = { authTarget = null },
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

            // L7 卸载技能二次确认 Dialog
            if (uninstallTarget != null) {
                AlertDialog(
                    onDismissRequest = { uninstallTarget = null },
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
