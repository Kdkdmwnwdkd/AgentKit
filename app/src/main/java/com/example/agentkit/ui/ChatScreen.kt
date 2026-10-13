package com.example.agentkit.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 对话页（视觉规范 §6.2 气泡 / §6.3 工具卡 / §6.4 审批 / §6.9 空态 / §6.10 输入栏）。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题 = 当前会话名 + 右上「新对话」），抽屉开关由宿主注入。
 * 消息列与输入栏贴底排布；审批弹窗是对话流中的模态事件（UX v1 D4：审批不是导航项）。
 *
 * 功能三（聊天记录，录屏反馈「人家都有聊天记录对话记录，你这都没有」）：顶栏标题实时反映
 * 当前会话名（首条消息自动命名 / 抽屉长按可重命名），右上「＋」新建会话并切过去。
 *
 * @param onOpenModel 空态「去配置模型」→ 模型页。
 * @param onOpenPermissions 空态检查清单 / 抽屉就绪文字 → 权限中心。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenModel: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeConversationId by vm.activeConversationId.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val running by vm.isRunning.collectAsStateWithLifecycle()
    val pendingApproval by vm.pendingApproval.collectAsStateWithLifecycle()
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val pendingGrants = pendingGrantCount(
        serviceConnected, capturing, notificationGranted, usageGranted
    )
    val modelReady =
        config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()

    // 新消息入场后贴底滚动（无 spring 无位移，只做位置归位）
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            // 功能三：标题 = 当前会话名（找不到摘要时回落「对话」——冷启动首帧也可能为空）
            title = conversations.firstOrNull { it.id == activeConversationId }?.title
                ?: AppDest.CHAT.title,
            actionIcon = Icons.Outlined.Add,
            actionDescription = "新对话",
            // 当前会话已为空时禁用（§6.5.4）：再建也是空会话，无信息增量
            actionEnabled = messages.isNotEmpty(),
            onAction = { vm.newConversation() }
        )
        if (messages.isEmpty()) {
            EmptyGuide(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                modelReady = modelReady,
                serviceConnected = serviceConnected,
                pendingGrants = pendingGrants,
                onOpenModel = onOpenModel,
                onOpenPermissions = onOpenPermissions,
                onPick = { vm.input.value = it }
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = AgentSpacing.space4,
                    vertical = AgentSpacing.space2
                )
            ) {
                // 同发送者间距 8dp、切换 16dp（§6.2 通用）；不用 spacedBy——间距随发送者变化
                itemsIndexed(messages) { index, item ->
                    val prev = messages.getOrNull(index - 1)
                    val gap = when {
                        prev == null -> 0.dp
                        sameSender(prev, item) -> AgentSpacing.space2
                        else -> AgentSpacing.space4
                    }
                    Box(Modifier.padding(top = gap)) {
                        MessageRow(item, streaming = running && index == messages.lastIndex)
                    }
                }
            }
        }
        ChatInputBar(
            text = input,
            running = running,
            onTextChange = { vm.input.value = it },
            onSend = { vm.send() }
        )
    }

    // —— L10 审批弹窗（§6.4）：入场 fade+scale 200ms，退场 fade 150ms ——
    // request 消失后本地多撑 150ms 承载退场动画，再卸载（Dialog 无内建退场钩子）
    val request = pendingApproval
    var approvalReq by remember { mutableStateOf<ApprovalRequest?>(null) }
    var approvalVisible by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        if (request != null) {
            approvalReq = request
            approvalVisible = true
        } else if (approvalVisible) {
            approvalVisible = false
            delay(150)
            approvalReq = null
        }
    }
    val currentApproval = approvalReq
    if (currentApproval != null) {
        ApprovalDialog(request = currentApproval, visible = approvalVisible, vm = vm)
    }
}

/** 两条相邻消息是否同一发送者（用户 / AI / 工具三类，工具卡独占一类）。 */
private fun sameSender(a: UiItem, b: UiItem): Boolean = when {
    a is UiItem.User && b is UiItem.User -> true
    a is UiItem.Assistant && b is UiItem.Assistant -> true
    a is UiItem.Tool && b is UiItem.Tool -> true
    else -> false
}

/**
 * 空态引导（§6.9 + UX v1 §3.4「空对话」）：图标 + 标题 + 描述 + 主按钮 +
 * 三行起飞前检查清单 + 三条示例建议卡。无 hero 大卡——空态是「暂时没有内容」。
 */
@Composable
private fun EmptyGuide(
    modifier: Modifier = Modifier,
    modelReady: Boolean,
    serviceConnected: Boolean,
    pendingGrants: Int,
    onOpenModel: () -> Unit,
    onOpenPermissions: () -> Unit,
    onPick: (String) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AgentSpacing.space6, vertical = AgentSpacing.space5),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = LocalAkColors.surface1,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space4))
        Text(
            text = "你好，我是 AgentKit",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        Text(
            text = "我可以看懂你的屏幕、读取通知，并替你完成手机上的操作。" +
                "先完成「起飞前检查」，再发出第一条指令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(280.dp)
        )
        Spacer(Modifier.height(AgentSpacing.space6))
        AkPrimaryButton(text = "去配置模型", onClick = onOpenModel)

        Spacer(Modifier.height(AgentSpacing.space6))
        PreflightRow(
            label = "模型配置",
            ready = modelReady,
            readyText = "已配置",
            pendingText = "未配置，去填写 API Key",
            onClick = onOpenModel
        )
        PreflightRow(
            label = "无障碍服务",
            ready = serviceConnected,
            readyText = "已连接",
            pendingText = "未连接，去开启",
            onClick = onOpenPermissions
        )
        PreflightRow(
            label = "权限就绪",
            ready = pendingGrants == 0,
            readyText = "已就绪",
            pendingText = "${pendingGrants} 项待授权",
            onClick = onOpenPermissions
        )

        Spacer(Modifier.height(AgentSpacing.space6))
        Text(
            text = "试试这样问我",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        SuggestionCard("看看现在屏幕上是什么", Icons.Outlined.PhoneAndroid, onPick)
        SuggestionCard("打开设置并点击 WLAN", Icons.Outlined.TouchApp, onPick)
        SuggestionCard("刚才有没有新消息", Icons.Outlined.Notifications, onPick)
    }
}

/** 起飞前检查单行：6dp 状态点 + 中文标签 + 就绪文字（双编码，UX v1 §5.3）；整行可点直达。 */
@Composable
private fun PreflightRow(
    label: String,
    ready: Boolean,
    readyText: String,
    pendingText: String,
    onClick: () -> Unit
) {
    val ak = LocalAkColors
    AkPressSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgentSpacing.space1),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AgentSpacing.space4,
                    vertical = AgentSpacing.space3
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AkStatusDot(if (ready) ak.success else MaterialTheme.colorScheme.error, 6.dp)
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (ready) readyText else pendingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 示例建议卡：白卡 radius 14dp padding 14dp，13sp on-surface-var（§6.9）；点击填入输入框。 */
@Composable
private fun SuggestionCard(text: String, icon: ImageVector, onPick: (String) -> Unit) {
    AkPressSurface(
        onClick = { onPick(text) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgentSpacing.space1),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgentSpacing.space4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 对话输入栏（§6.10）：贴屏幕底边缘，玻璃拟态全 App 唯一一处。
 *
 * 容器背景延伸至屏幕底（含手势区）：surface @92% + 顶部 1dp outline 装饰线；
 * 交互元素位于 navigationBarsPadding 之上，内容高 68dp（输入框 48 + 上 8 + 下 8 + 底 4）。
 */
@Composable
private fun ChatInputBar(
    text: String,
    running: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 顶部 1dp outline 装饰线（玻璃拟态唯一处，§6.10）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = AgentSpacing.space2,
                        bottom = AgentSpacing.space3
                    )
                    .padding(horizontal = AgentSpacing.space3),
                verticalAlignment = Alignment.Bottom
            ) {
                // 输入框：surface-var 底 radius 20dp，最小高 48dp，多行自增（max 120dp 内滚动）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = AgentDimens.touchTarget, max = 120.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(
                            horizontal = AgentSpacing.space4,
                            vertical = AgentSpacing.space2
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Default
                        ),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = "说点什么…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.width(AgentSpacing.space2))
                if (running) {
                    // 运行中：按钮位显示加载指示（VM 无中止接口，不提供「停止」假按钮）
                    Box(
                        modifier = Modifier.size(AgentDimens.touchTarget),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    ChatSendButton(
                        enabled = text.isNotBlank(),
                        onClick = onSend
                    )
                }
            }
        }
    }
}

/** 发送按钮：触控 48dp / 视觉 40dp，primary 底 + 24dp on-primary 发送图标；输入为空 38% alpha 禁用。 */
@Composable
private fun ChatSendButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(AgentDimens.topBarButtonVisual)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Send,
                contentDescription = "发送",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 单条消息分发。气泡宽度 clamp 的百分比基准是**消息列容器宽**（UX v1 §4.4：
 * Expanded 下 88vw 会溢出限宽内容区），故用 BoxWithConstraints 取容器宽计算。
 */
@Composable
private fun MessageRow(item: UiItem, streaming: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when (item) {
            is UiItem.User -> MessageBubble(
                text = item.text,
                isUser = true,
                streaming = false,
                maxWidth = (maxWidth * 0.76f).coerceIn(240.dp, 400.dp)
            )

            is UiItem.Assistant -> MessageBubble(
                text = item.text,
                isUser = false,
                streaming = streaming,
                maxWidth = (maxWidth * 0.84f).coerceIn(272.dp, 520.dp)
            )

            is UiItem.Tool -> ToolCard(item)
        }
    }
}

/**
 * 消息气泡 + 角色头像（§6.2）。
 *
 * 用户：右对齐，primary 底 + on-primary 文字，右上 4dp 尾角；28dp 头像 surface-1 + 人形图标。
 * AI：左对齐，白底 + on-surface 文字，左上 4dp 尾角；28dp 头像 primary-container + 星点图标。
 */
@Composable
private fun MessageBubble(
    text: String,
    isUser: Boolean,
    streaming: Boolean,
    maxWidth: Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            RoleAvatar(
                icon = Icons.Outlined.AutoAwesome,
                background = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(AgentSpacing.space2))
        }
        val shape = if (isUser) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
        } else {
            RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
        }
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            shape = shape,
            modifier = Modifier.widthIn(max = maxWidth)
        ) {
            SelectionContainer {
                if (streaming) {
                    Row(
                        modifier = Modifier.widthIn(max = maxWidth),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                        StreamCursor()
                        Spacer(Modifier.width(14.dp))
                    }
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
        if (isUser) {
            Spacer(Modifier.width(AgentSpacing.space2))
            RoleAvatar(
                icon = Icons.Outlined.Person,
                background = LocalAkColors.surface1,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 28dp 圆形角色头像：区分用户 / AI 两个发言者。 */
@Composable
private fun RoleAvatar(icon: ImageVector, background: Color, tint: Color) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = Modifier.size(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 流式光标：2×14dp primary 方块，800ms 闪烁——**全 App 唯一循环动画**（§6.2）。
 * reduce-motion 下降级为静态（UX v1 §5.4：装饰动效移除，信息由文字承载）。
 */
@Composable
private fun StreamCursor() {
    val reduceMotion = rememberReduceMotion()
    val alpha = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "streamCursor")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "streamCursorAlpha"
        )
        animated
    }
    Box(
        Modifier
            .padding(start = 3.dp)
            .width(2.dp)
            .height(14.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary)
    )
}

/** 把 data URL（base64）解码成可显示的位图，异步避免阻塞主线程。 */
@Composable
private fun DataImage(dataUrl: String) {
    val bitmap by produceState<ImageBitmap?>(null, dataUrl) {
        value = withContextIo {
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
                .clip(RoundedCornerShape(14.dp))
        )
    }
}

/** 切 IO 线程解码位图（小工具函数，避免每个文件都写 withContext(Dispatchers.IO) 样板）。 */
private suspend fun <T> withContextIo(block: () -> T): T =
    withContext(Dispatchers.IO) { block() }

/** 工具调用四态（§6.3 状态文字双编码）。 */
private enum class ToolStatus { RUNNING, DONE, FAILED, REJECTED }

/**
 * 工具调用四态（§6.3 状态文字双编码）。
 *
 * 「已拒绝」判据 = AgentLoop 在审批被拒时回填的结果文案（core/AgentLoop：「用户拒绝了该危险操作」）：
 * 拒绝是用户决策不是故障，用中性色（继承 v2.1 R3）。
 * Hotfix2 起 UiItem.Tool 有 RUNNING 实例（running=true）：AgentEvent.ToolStarted 已上报、
 * ToolFinished 未到——过程透明化，用户能看见 AI 正在调什么工具。
 */
private fun UiItem.Tool.status(): ToolStatus = when {
    running -> ToolStatus.RUNNING
    isError && result.contains("用户拒绝了") -> ToolStatus.REJECTED
    isError -> ToolStatus.FAILED
    else -> ToolStatus.DONE
}

/**
 * 工具调用卡（§6.3 轻量卡）：白卡 radius 20dp，无状态描边、无色带、无扫描光带。
 *
 * Header 48dp = 8dp 状态点 + 工具名 machineText + 「—」+ 状态文字 12sp + chevron 20dp；
 * 展开区 animateContentSize 250ms，「入参」「结果」两个内嵌块（surface-1 / 13sp mono / maxHeight 140dp）。
 */
@Composable
private fun ToolCard(item: UiItem.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val ak = LocalAkColors
    val status = item.status()
    val statusColor = when (status) {
        ToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolStatus.DONE -> ak.success
        ToolStatus.FAILED -> MaterialTheme.colorScheme.error
        ToolStatus.REJECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (status) {
        ToolStatus.RUNNING -> "执行中"
        ToolStatus.DONE -> "已完成"
        ToolStatus.FAILED -> "已失败"
        ToolStatus.REJECTED -> "已拒绝"
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AkMotionBase,
        label = "toolChevron"
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .padding(horizontal = AgentSpacing.space4)
                .padding(bottom = AgentSpacing.space3)
                .animateContentSize(
                    animationSpec = tween(durationMillis = 250, easing = AkMotionEasing)
                )
        ) {
            // Header：整行可点展开，按压 scale 0.97（§7-M3）
            AkPressSurface(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AgentDimens.touchTarget),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AgentSpacing.space1),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hotfix2：执行中态把静态状态点换成 12dp 转圈，一眼可见「正在跑」
                    if (status == ToolStatus.RUNNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = statusColor
                        )
                    } else {
                        AkStatusDot(statusColor, 8.dp)
                    }
                    Spacer(Modifier.width(AgentSpacing.space2))
                    Text(
                        text = item.call.name,
                        style = machineText,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = " — ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation)
                    )
                }
            }
            if (expanded) {
                EmbedBlock(label = "入参", text = item.call.arguments.toString())
                // 执行中态还没有结果，只展示入参（Hotfix2）
                if (!item.running) {
                    Spacer(Modifier.height(AgentSpacing.space2))
                    EmbedBlock(
                        label = "结果",
                        text = item.result,
                        error = item.isError
                    )
                }
            }
            // 工具返回的图片（如 ui_screenshot 截图）默认可见
            if (item.images.isNotEmpty()) {
                Spacer(Modifier.height(AgentSpacing.space3))
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
                ) {
                    item.images.forEach { DataImage(it) }
                }
            }
        }
    }
}

/** 「入参 / 结果」内嵌块：surface-1 底 radius 14dp，machineText 13sp/20，maxHeight 140dp 可滚动。 */
@Composable
private fun EmbedBlock(label: String, text: String, error: Boolean = false) {
    Column(Modifier.padding(top = AgentSpacing.space2)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LocalAkColors.surface1)
                .verticalScroll(rememberScrollState())
                .padding(AgentSpacing.space3)
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = machineText,
                    color = if (error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 危险操作审批 Dialog（§6.4 招牌组件：简洁但警觉）。
 *
 * 红只出现在 4 处（图标 / 将执行左竖条 / 允许按钮 / 倒计时线），合计 <8% 弹窗面；
 * 标题 18sp w600 正常字重；padding 24dp、块间距 16dp。无扫描线、无遥测读数、无脉冲、无全屏红底。
 *
 * @param visible 退场时置 false：fade 150ms（AkMotionExit）后由宿主卸载。
 */
@Composable
private fun ApprovalDialog(request: ApprovalRequest, visible: Boolean, vm: ChatViewModel) {
    val ak = LocalAkColors
    val view = LocalView.current
    var askNoMore by remember(request) { mutableStateOf(false) }

    // 倒计时：10s linear 排空 → 自动拒绝；剩余 3s 时 polite 播报一次（§6.4 TalkBack）
    var remainingSec by remember(request) { mutableFloatStateOf(10f) }
    var announced by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, visible) {
        if (!visible) return@LaunchedEffect
        val startMs = System.currentTimeMillis()
        while (true) {
            val left = 10f - (System.currentTimeMillis() - startMs) / 1000f
            remainingSec = left.coerceIn(0f, 10f)
            if (!announced && left <= 3f) {
                announced = true
                view.announceForAccessibility("3 秒后自动拒绝")
            }
            if (left <= 0f) break
            delay(100)
        }
        vm.denyApproval()
    }

    val enterSpec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = enterSpec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = enterSpec)

    Dialog(
        onDismissRequest = { vm.denyApproval() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 宽 = min(344dp, 100vw − 48dp)：外层 padding 24dp 给出后者，widthIn 封顶 344dp
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(24.dp)) {
                    // ① 静态警示图标：36dp 圆 danger-container + 20dp danger 线图标（不呼吸）
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ② 标题：陈述事实，不加大不加粗不变红
                    Text(
                        text = "确认执行危险操作",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Agent 正在请求操控你的手机，执行后不可撤销。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ③ 「将执行」块：surface-1 + 左缘 3dp danger 圆角竖条，mono 换行，最多 5 行可滚动
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ak.surface1)
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.error)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(AgentSpacing.space4)
                        ) {
                            Text(
                                text = request.toolName,
                                style = machineText.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = request.argsPreview,
                                style = machineText,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ④ 来源行：无来源时显示「本次对话直接调用」，不留空
                    Text(
                        text = "来源：本次对话直接调用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    // ⑤ 免询行：文字 + checkbox（视觉 40dp / 触控 48dp）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = AgentDimens.touchTarget),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AkCheckbox(
                            checked = askNoMore,
                            onCheckedChange = { askNoMore = it }
                        )
                        Text(
                            text = "本次会话不再询问同类操作",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { askNoMore = !askNoMore }
                                .padding(start = AgentSpacing.space1)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    // ⑥ 按钮行：flex 1 等高，拒绝 Outlined / 允许执行 Danger Filled
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
                    ) {
                        AkOutlinedButton(
                            text = "拒绝",
                            onClick = { vm.denyApproval() },
                            modifier = Modifier.weight(1f)
                        )
                        AkDangerButton(
                            text = "允许执行",
                            onClick = { if (askNoMore) vm.approveAlways() else vm.approveOnce() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ⑦ 倒计时：功能性进度（非装饰动效），reduce-motion 下保留
                    Text(
                        text = "10 秒后自动拒绝",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction = (remainingSec / 10f).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
            }
        }
    }
}
