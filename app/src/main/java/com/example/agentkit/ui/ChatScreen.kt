package com.example.agentkit.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.skill.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 底部导航的三个一级页面。 */
private enum class NavDest(val route: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "对话", Icons.Default.Chat),
    MODEL("config", "模型", Icons.Default.Tune),
    AUTOMATION("automation", "自动化", Icons.Default.Settings)
}

/**
 * 全 App 唯一的导航宿主：底部三页切换，选中态高亮。
 *
 * 三屏各自仍持有自己的 Scaffold（各自的 TopAppBar），这里只负责
 * 撑一层底部导航并把剩余空间（[padding]）交给子页面，避免来回传 onBack 路由。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(vm: ChatViewModel) {
    var route by remember { mutableStateOf(NavDest.CHAT.route) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavDest.values().forEach { dest ->
                    NavigationBarItem(
                        selected = route == dest.route,
                        onClick = { route = dest.route },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                NavDest.CHAT.route -> ChatScreen(
                    vm,
                    onOpenConfig = { route = NavDest.MODEL.route },
                    onOpenAutomation = { route = NavDest.AUTOMATION.route }
                )

                NavDest.MODEL.route -> ModelConfigScreen(vm) { route = NavDest.CHAT.route }
                NavDest.AUTOMATION.route -> AutomationScreen(vm) { route = NavDest.CHAT.route }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenConfig: () -> Unit, onOpenAutomation: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val running by vm.isRunning.collectAsStateWithLifecycle()
    // L10：危险工具（点击/输入/滑动/按键/截图）执行前在此弹窗征求用户同意
    val pendingApproval by vm.pendingApproval.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("AgentKit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = onOpenAutomation) {
                        Icon(Icons.Default.Settings, contentDescription = "自动化")
                    }
                    IconButton(onClick = onOpenConfig) {
                        Icon(Icons.Default.Tune, contentDescription = "模型配置")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                text = input,
                running = running,
                onTextChange = { vm.input.value = it },
                onSend = { vm.send() }
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            EmptyGuide(
                modifier = Modifier.fillMaxSize().padding(padding),
                onPick = { vm.input.value = it }
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { MessageRow(it) }
            }
        }

        // L10 审批弹窗：操作发生在对话过程中，因此在对话页弹出
        val request = pendingApproval
        if (request != null) {
            ApprovalDialog(request, vm)
        }
    }
}

/** 空态引导卡片 + 三条示例建议（点击即填入输入框）。 */
@Composable
private fun EmptyGuide(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Box(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Text("你好，我是 AgentKit", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "我可以看懂你的屏幕、读取通知，并替你完成手机上的操作。" +
                            "先去「模型」页填好 API Key，就可以开始对话了。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "试试这样问我",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            SuggestionItem("看看现在屏幕上是什么", Icons.Default.AutoAwesome, onPick)
            SuggestionItem("打开设置并点击 WLAN", Icons.Default.Build, onPick)
            SuggestionItem("刚才有没有新消息", Icons.Default.Chat, onPick)
        }
    }
}

/** 单条示例建议：整行可点，点击后把文案填进输入框。 */
@Composable
private fun SuggestionItem(text: String, icon: ImageVector, onPick: (String) -> Unit) {
    Surface(
        onClick = { onPick(text) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "↗",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 胶囊输入框 + 圆形发送按钮；运行中按钮位骨架花瓣为加载指示器。 */
@Composable
private fun ChatInputBar(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                placeholder = {
                    Text("给 Agent 发消息…", style = MaterialTheme.typography.bodyMedium)
                },
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send)
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                val canSend = text.isNotBlank()
                Surface(
                    shape = CircleShape,
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(52.dp)
                ) {
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "发送",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * L10 危险操作审批 Dialog：展示工具名与入参预览，三选一决策。
 * 拒绝或关闭弹窗 → AgentLoop 收到「用户拒绝了该危险操作」并作为工具结果继续推理。
 */
@Composable
private fun ApprovalDialog(request: ApprovalRequest, vm: ChatViewModel) {
    AlertDialog(
        onDismissRequest = { vm.denyApproval() },
        title = { Text("确认危险操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent 请求操控手机执行：${request.toolName}")
                Text(
                    "参数预览：${request.argsPreview}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Column {
                Button(onClick = { vm.approveOnce() }) { Text("允许一次") }
                Button(onClick = { vm.approveAlways() }) { Text("本次会话始终允许") }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.denyApproval() }) { Text("拒绝") }
        }
    )
}

@Composable
private fun MessageRow(item: UiItem) {
    when (item) {
        is UiItem.User -> MessageBubble(
            text = item.text,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            isUser = true
        )

        is UiItem.Assistant -> MessageBubble(
            text = item.text,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            isUser = false
        )

        is UiItem.Tool -> ToolCard(item)
    }
}

/**
 * 消息气泡 + 角色头像。
 *
 * 气泡圆角差异化：助手左上 4.dp、用户右上 4.dp，形成朝向头像的“尾巴”视觉。
 */
@Composable
private fun MessageBubble(
    text: String,
    containerColor: Color,
    contentColor: Color,
    isUser: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            RoleAvatar(
                icon = Icons.Default.SmartToy,
                background = MaterialTheme.colorScheme.tertiaryContainer,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(8.dp))
        }
        val shape = if (isUser) {
            RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 4.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            )
        } else {
            RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            )
        }
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = shape,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            RoleAvatar(
                icon = Icons.Default.Person,
                background = MaterialTheme.colorScheme.primary,
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** 32.dp 圆形角色头像：区分用户 / 助手 / 工具三方发言者。 */
@Composable
private fun RoleAvatar(icon: ImageVector, background: Color, tint: Color) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
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
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
        )
    }
}

/**
 * 工具调用卡片：图标 + 工具名 + 成功/失败状态色 + 展开/收起，
 * 展开后显示入参与结果（等宽小字），工具返回的图片始终可见。
 */
@Composable
private fun ToolCard(item: UiItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val toneColor = if (item.isError) MaterialTheme.colorScheme.error else AgentColors.success
    Card(
        modifier = Modifier.fillMaxWidth(0.92f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = toneColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = toneColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.call.name, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (item.isError) "执行失败" else "执行完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(
                    text = if (item.isError) "失败" else "成功",
                    color = toneColor
                )
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开"
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        "入参：${item.call.arguments}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        item.result,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.isError) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // 工具返回的图片（如 ui_screenshot 截图）直接渲染在卡片内，默认可见
            if (item.images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.images.forEach { DataImage(it) }
                }
            }
        }
    }
}

/**
 * 彩色状态徽章：半透明底色 + 同色文字 + 图标，替代过去堆砌的 ✅/⚠️ 文本。
 */
@Composable
internal fun StatusBadge(text: String, color: Color, icon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
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
            CenterAlignedTopAppBar(
                title = { Text("模型配置", style = MaterialTheme.typography.titleMedium) }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("Base URL") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("API Key") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("模型名") },
                        singleLine = true
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        vm.updateConfig(ModelConfig(baseUrl.trim(), apiKey.trim(), model.trim()))
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (baseUrl.isBlank() || apiKey.isBlank()) Icons.Default.ErrorOutline
                    else Icons.Default.Check,
                    contentDescription = null,
                    tint = if (baseUrl.isBlank() || apiKey.isBlank())
                        MaterialTheme.colorScheme.error else AgentColors.success,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "兼容任意 OpenAI 格式端点（含 vLLM / Ollama / OneAPI）。" +
                        "填错 Key 时对话页会直接提示「API Key 无效」，不会再反复重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
