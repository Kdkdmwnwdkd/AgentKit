package com.example.agentkit.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
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
    val ctx = LocalContext.current

    // 进入本页时刷新权限授予状态，反映用户在系统设置里的最新操作
    LaunchedEffect(Unit) { vm.refreshGrants() }

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
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
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

            Text(
                "可用工具：ui_tree（读取界面）、ui_tap / ui_long_press / ui_swipe（手势）、ui_input（输入文本）、ui_key（home/back/recent）、ui_click（按文本或 ID 点击）、ui_screenshot（截取屏幕给视觉模型看）、device_notifications（读取最近通知）、device_foreground_app（读取当前前台应用）。\n" +
                    "在对话里直接说“打开设置并点击某某”“截个屏看看现在是什么界面”“刚才有没有新消息”即可让 Agent 操作并感知手机。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
