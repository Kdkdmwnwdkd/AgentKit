package com.example.agentkit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.example.agentkit.core.agent.AskUserBroker
import com.example.agentkit.core.agent.PlanBroker
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.core.ModelProfile
import com.example.agentkit.core.TokenUsage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    onOpenPermissions: () -> Unit,
    onManagePrompts: () -> Unit
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streamDraft by vm.streamDraft.collectAsStateWithLifecycle()
    // 功能五（P1-9）：流式推理过程草稿（与 streamDraft 同生命周期的「正在思考」内容）
    val reasoningDraft by vm.reasoningDraft.collectAsStateWithLifecycle()
    val retryState by vm.retryState.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeConversationId by vm.activeConversationId.collectAsStateWithLifecycle()
    // hotfix30：正在生成的会话 id——非本会话在生成时输入区给说明行
    val generatingConvId by vm.generatingConvId.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val running by vm.isRunning.collectAsStateWithLifecycle()
    // 功能五（P1-7）：提示词库（快捷指令）——输入栏闪电按钮唤起的选择器数据源
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    var showPromptPicker by remember { mutableStateOf(false) }
    val pendingApproval by vm.pendingApproval.collectAsStateWithLifecycle()
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    // 功能四（模型快速切换，录屏反馈「保存了好多个模型，要换的时候不给换，
    // 都不知道现在用哪个」）：对话页常驻当前模型指示条 + 一键切换弹窗，
    // 不再深埋三层（抽屉 → 模型配置 → 顶部横向档案条）且切换无任何反馈。
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    // 功能五（P1-5）：当前会话累计 token 用量（模型指示条下常驻小条）
    val sessionUsage by vm.sessionUsage.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val pendingGrants = pendingGrantCount(
        serviceConnected, notificationGranted, usageGranted
    )
    val modelReady =
        config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()

    // 新消息入场后贴底滚动（无 spring 无位移，只做位置归位）；
    // 流式草稿出现/消失也算一次「新项」，一并归位到尾部。
    LaunchedEffect(messages.size, streamDraft != null) {
        val count = messages.size + if (streamDraft != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // 「从中间轮重新发问」待编辑的用户气泡（长按/点击「重新发问」入口置位，确认后截断+重问）
    var reaskTarget by remember { mutableStateOf<UiItem.User?>(null) }
    // 功能五（P1-1）：消息「更多」菜单 / 从此处删除确认的目标
    // （存「正文 → 轮次」二元组而非 UiItem：UiItem 是 sealed 接口，
    //   弹窗体里取不到 text/turnIndex，还要再 when 一遍）
    var menuTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 功能四：模型切换弹窗开关（指示条点击打开）
    var showModelSwitch by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // 功能五（P1-2）：TTS 朗读（离开对话页即 shutdown，防系统绑定泄漏）
    val tts = remember { TtsController(ctx) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    // 正在朗读的消息下标（null = 没在朗读）；再点同一按钮即停止
    var speakingIndex by remember { mutableStateOf<Int?>(null) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    // 功能四（P0-2）/ 功能五（P1-4）：待发附件（图片 + 文件）
    val pendingImages by vm.pendingImages.collectAsStateWithLifecycle()
    val pendingFiles by vm.pendingFiles.collectAsStateWithLifecycle()
    // 附件类型选择菜单（「＋」不再直通相册——用户要的是「发东西」，不只是照片）
    var showAttachMenu by remember { mutableStateOf(false) }
    val inputScope = rememberCoroutineScope()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            inputScope.launch {
                val ok = vm.attachImageFromUri(it)
                Toast.makeText(
                    ctx,
                    if (ok) "已添加图片" else "图片读取失败，请重选",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    // 文件选择：OpenDocument + */* —— 具体类型由 VM 按扩展名分派（文本 / PDF / DOCX / 拒绝）
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            inputScope.launch {
                val msg = when (val r = vm.attachFileFromUri(it)) {
                    is FileAttachResult.Text -> "已添加「${r.name}」（${r.chars} 字）"
                    is FileAttachResult.Images -> "已添加 PDF「${r.name}」（${r.pages} 页转图片）"
                    is FileAttachResult.Failed -> r.reason
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // hotfix33：背景图已提升到 App 根层（AppBackgroundLayer）——本页不再自绘，
    // 全局统一（真机反馈「不只是要聊天的地方变，其他地方也要变」）
    Box(Modifier.fillMaxSize()) {
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
        // 功能四：当前模型指示条——一眼看到「在用哪个模型」，点击即弹切换列表。
        // 未配置时整条变引导态（点此去配置），与空态「去配置模型」同语义。
        ModelIndicatorBar(
            profileName = activeProfile?.name,
            modelName = config.model,
            host = hostOf(config.baseUrl),
            ready = modelReady,
            onClick = {
                if (modelReady) showModelSwitch = true else onOpenModel()
            }
        )
        // 功能五（P1-5）：本会话 token 用量小条（无数据时不占位——供应商没给 usage
        // 或还没聊过，都不显示而非摆一个 0）
        if (sessionUsage.totalTokens > 0) {
            UsageSummaryBar(usage = sessionUsage)
        }
        if (messages.isEmpty() && streamDraft == null) {
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
                    Column(Modifier.padding(top = gap)) {
                        MessageRow(
                            item,
                            // 流式光标只在「末尾且无草稿气泡」时显示；有草稿时草稿自己带光标
                            streaming = running && index == messages.lastIndex && streamDraft == null
                        )
                        // Phase 2 DoD②：助手气泡操作区（变体切换器 + 重新生成 + 复制）
                        if (item is UiItem.Assistant) {
                            val showSwitch = item.variantCount > 1
                            val showRegen = !running && index == messages.lastIndex && item.turnIndex >= 0
                            val showCopy = !running && item.text.isNotBlank()
                            // 功能五（P1-1）：落库消息才给「更多」（分享/从此处删除）——
                            // 草稿与失败气泡没有轮次可操作
                            val showMore = !running && item.turnIndex >= 0
                            val showSpeak = !running && item.text.isNotBlank()
                            if (showSwitch || showRegen || showCopy || showMore || showSpeak) {
                                AssistantActions(
                                    variantIndex = item.variantIndex,
                                    variantCount = item.variantCount,
                                    showSwitch = showSwitch,
                                    showRegen = showRegen,
                                    showCopy = showCopy,
                                    showMore = showMore,
                                    showSpeak = showSpeak,
                                    speaking = speakingIndex == index,
                                    onSwitch = { delta -> vm.switchVariant(item.turnIndex, delta) },
                                    onRegenerate = { vm.regenerate() },
                                    onCopy = { copyMessage(ctx, item.text) },
                                    onMore = { menuTarget = item.text to item.turnIndex },
                                    onSpeak = {
                                        if (speakingIndex == index) {
                                            tts.stop()
                                            speakingIndex = null
                                        } else if (tts.speak(item.text)) {
                                            speakingIndex = index
                                        } else {
                                            Toast.makeText(ctx, "朗读器未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                        // 功能四（P0-1）：用户气泡操作行——复制 + 从中间轮重新发问
                        // （运行中隐藏；turnIndex < 0 的草稿态气泡只给复制）
                        if (item is UiItem.User && !running) {
                            UserMessageActions(
                                showReask = item.turnIndex >= 0,
                                showMore = item.turnIndex >= 0,
                                onCopy = { copyMessage(ctx, item.text) },
                                onMore = { menuTarget = item.text to item.turnIndex },
                                onReask = { reaskTarget = item }
                            )
                        }
                    }
                }
                // 流式草稿气泡（重新生成/发送时 T01 增量实时填入，尚未落库）
                if (streamDraft != null) {
                    item(key = "stream-draft") {
                        MessageRow(UiItem.Assistant(streamDraft.orEmpty(), reasoning = reasoningDraft), streaming = true)
                    }
                }
            }
        }
        // Phase 2 DoD③：断网/失败自动恢复横幅——重试中显示「正在恢复（n/N）· 原因」，
        // 恢复成功 / 最终失败 / 取消时置 null 即消失（最终失败仍走既有错误气泡，不重复提示）
        retryState?.let { RetryBanner(it) }
        // W-14：模型用 ask_user 提问时，输入栏上方出现选择卡片（回答后自动消失）
        vm.askUserPending.collectAsStateWithLifecycle().value?.let { q ->
            AskUserCard(question = q, onAnswer = { vm.answerAskUser(q.id, it) })
        }
        // W-25：先计划后执行——模型提交计划时，输入栏上方出现计划卡片（批准/拒绝后消失）
        vm.planPending.collectAsStateWithLifecycle().value?.let { plan ->
            PlanCard(
                plan = plan,
                onApprove = { vm.approvePlan(plan.id) },
                onReject = { reason -> vm.rejectPlan(plan.id, reason) }
            )
        }
        ChatInputBar(
            text = input,
            running = running,
            images = pendingImages,
            files = pendingFiles,
            onTextChange = { vm.input.value = it },
            onSend = { vm.send() },
            onStop = { vm.stop() },
            onPickAttach = { showAttachMenu = true },
            onOpenPrompts = { showPromptPicker = true },
            onRemoveImage = { vm.removePendingImage(it) },
            onRemoveFile = { vm.removePendingFile(it) },
            // hotfix30：后台有其他对话在生成（generatingConvId != 本会话）时，
            // 输入区禁用需有说明——用户切到空闲会话发现发不了消息会以为输入坏了
            runningNotice = if (generatingConvId != null && generatingConvId != activeConversationId)
                "有其他对话正在生成，本对话需等待或停止后才能发送" else null
        )
        }
    }

    // 功能五（P1-7）：快捷指令选择器（点选填入输入框；「管理」进提示词库全功能页）
    if (showPromptPicker) {
        PromptPickerDialog(
            prompts = prompts,
            onDismiss = { showPromptPicker = false },
            onPick = { item ->
                vm.insertPromptIntoInput(item.content)
                showPromptPicker = false
            },
            onManage = {
                showPromptPicker = false
                onManagePrompts()
            }
        )
    }

    // —— Phase 2 DoD②：从中间轮重新发问的编辑对话框 ——
    val reask = reaskTarget
    if (reask != null) {
        ReaskDialog(
            initial = reask.text,
            onDismiss = { reaskTarget = null },
            onConfirm = { newText ->
                val turn = reask.turnIndex
                reaskTarget = null
                vm.reaskFromTurn(turn, newText)
            }
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

    // —— 功能五（P1-4）：附件类型选择（图片 / 文件） ——
    AkModalHost(target = if (showAttachMenu) true else null) { _, visible ->
        AttachPickerDialog(
            visible = visible,
            onPickImage = { showAttachMenu = false; pickImage.launch("image/*") },
            onPickFile = { showAttachMenu = false; pickFile.launch(arrayOf("*/*")) },
            onDismiss = { showAttachMenu = false }
        )
    }

    // —— 功能五（P1-1）：消息更多菜单（分享 / 从此处删除） ——
    AkModalHost(target = menuTarget) { target, visible ->
        MessageActionsDialog(
            visible = visible,
            showDelete = target.second >= 0,
            onShare = { shareMessage(ctx, target.first); menuTarget = null },
            onDelete = { menuTarget = null; deleteTarget = target },
            onDismiss = { menuTarget = null }
        )
    }
    AkModalHost(target = deleteTarget) { target, visible ->
        AkConfirmDialog(
            title = "从此处删除",
            message = "将删除从这条消息起的全部内容（含本条），删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            visible = visible,
            onConfirm = {
                vm.deleteFromTurn(target.second)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // —— 功能四：模型切换弹窗（录屏反馈「保存了好多个模型，要换的时候不给换」） ——
    // AkModalHost 承载退场动画；点选即 vm.switchProfile 即时生效（VM 同步 _config，
    // 下一轮请求读新配置，见 ChatViewModel.switchProfile），Toast 给可见反馈。
    AkModalHost(target = if (showModelSwitch) true else null) { _, visible ->
        ModelSwitchDialog(
            visible = visible,
            profiles = profiles,
            activeId = activeProfileId,
            onSelect = { id ->
                vm.switchProfile(id)
                val name = profiles.firstOrNull { it.id == id }?.name ?: "该配置"
                Toast.makeText(ctx, "已切换到「$name」", Toast.LENGTH_SHORT).show()
                showModelSwitch = false
            },
            onManage = {
                showModelSwitch = false
                onOpenModel()
            },
            onDismiss = { showModelSwitch = false }
        )
    }
}

/**
 * 功能四：对话页「当前模型」指示条（录屏反馈「都不知道我现在用哪个模型」）。
 *
 * 常驻顶栏下方：图标底座 + 配置名（titleMedium w600）+ 模型名 · 域名（bodySmall），
 * 右侧 ExpandMore 暗示「可展开切换」。整条 52dp 触控（§6.0 48dp 下限 + 图标内边距），
 * 按压 surface-2 + scale 0.97（与 AkListItem 同语言）。
 *
 * 未配置态（[ready] = false）：文案变「模型未配置 · 点此前往配置」，图标底座不升色，
 * 点击走 onOpenModel 引导——与空态「去配置模型」按钮同语义，此处只是多一个常驻入口。
 */
@Composable
private fun ModelIndicatorBar(
    profileName: String?,
    modelName: String,
    host: String,
    ready: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    val bg by animateColorAsState(
        targetValue = if (pressed) LocalAkColors.surface2 else Color.Transparent,
        animationSpec = AkMotionFastColor
    )
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AkIconBase(icon = Icons.Outlined.AutoAwesome, selected = ready)
        Spacer(Modifier.width(AgentSpacing.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (ready) profileName ?: "默认配置" else "模型未配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (ready) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    !ready -> "点此前往配置模型"
                    modelName.isBlank() -> "未设置模型名"
                    else -> "$modelName · $host"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (ready) {
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = "切换模型",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 功能五（P1-5）：本会话 token 用量条（模型指示条下常驻，一行小字）。
 *
 * 为什么放这里而不是设置页：用量是「天天要看」的日常数据（这个月跟模型聊了多少、
 * 快到额度了吗），埋在设置里等于没有；对标 RikkaHub / Coomi 的会话级统计。
 * 纯展示不可点——交互（清零/导出）留给后续备份功能，不做半成品入口。
 */
@Composable
private fun UsageSummaryBar(usage: TokenUsage) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space5, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "本会话 tokens · ↑ ${formatTokens(usage.promptTokens)} · " +
                "↓ ${formatTokens(usage.completionTokens)} · 共 ${formatTokens(usage.totalTokens)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * token 数格式化（纯函数）：1234 → "1.2k"、1234567 → "1.2M"。
 * 小于 1000 原样展示——用量小的时候 "856" 比 "0.9k" 更精确。
 * 小数分隔符固定 Locale.US——个别 locale 会输出逗号小数点，"1,2k" 不像个数。
 */
internal fun formatTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
}

/**
 * 功能四：「切换模型配置」弹窗（对齐 [AddProfileDialog] 视觉语言：344dp 白卡 +
 * scale/fade 出入场，AkModalHost 承载退场）。
 *
 * 列出全部档案（[AkListItem] 两行结构：配置名 + 模型名 · 域名），当前生效项
 * selected 高亮（primary 图标底座 + primary 标题 + 右侧对勾）。点选即时生效，
 * 无需先保存——[ChatViewModel.switchProfile] 同步落盘，重启不丢失。
 *
 * 底部「管理模型配置」文字按钮进模型配置页（新建/重命名/删除/编辑字段在那里），
 * 本弹窗只承担「快速切换」单一职责，不与配置页职责重叠。
 */
@Composable
private fun ModelSwitchDialog(
    visible: Boolean,
    profiles: List<ModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                    Text(
                        text = "切换模型配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "共 ${profiles.size} 个配置 · 点选即时生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            val isActive = profile.id == activeId
                            val host = hostOf(profile.config.baseUrl)
                            val desc = when {
                                profile.config.model.isBlank() && host.isBlank() -> "未填写"
                                profile.config.model.isBlank() -> host
                                else -> "${profile.config.model} · $host"
                            }
                            AkListItem(
                                icon = Icons.Outlined.AutoAwesome,
                                title = profile.name,
                                description = desc,
                                selected = isActive,
                                onClick = { onSelect(profile.id) },
                                trailing = if (isActive) {
                                    {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "当前生效",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextButton(
                        text = "管理模型配置",
                        onClick = onManage,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 功能四（P0-1）：复制消息正文到剪贴板并给 Toast 反馈。
 *
 * 复制的是**原文**（助手消息为 Markdown 源文本，用户消息为其输入原文）——
 * 与 RikkaHub「copy all」一致；想只拷一段仍可用系统长按选字。
 * 复制到剪贴板是系统行为，用户看不到变化，因此**必须有 Toast**，
 * 否则点了没反应（这类「静默成功」正是之前开关状态回写缺失的同类问题）。
 */
private fun copyMessage(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null || text.isBlank()) return
    manager.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/**
 * 功能五（P1-2）：TTS 朗读控制器（系统 TextToSpeech，零新增依赖）。
 *
 * 生命周期纪律：TTS 必须显式 shutdown，否则会泄漏系统绑定——由调用方在
 * [DisposableEffect] 里挂 [shutdown]（离开对话页即释放）。
 *
 * 初始化是异步的（OnInitListener 回调），因此 [speak] 用 [ready] 守卫并返回
 * 布尔：未就绪时上层提示「朗读器未就绪」，而不是静默失败。
 */
internal class TtsController(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                // 中文优先（App 面向中文用户）；设备无中文引擎时回落系统默认，不阻断朗读
                runCatching { tts?.language = Locale.CHINA }
            }
        }
    }

    /** 开始朗读；返回 false = 引擎未就绪或是空文本（调用方据此提示）。 */
    fun speak(text: String): Boolean {
        if (!ready || text.isBlank()) return false
        val id = "msg-${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        return true
    }

    /** 停止当前朗读（再点一次同一按钮走这里）。 */
    fun stop() {
        runCatching { tts?.stop() }
    }

    /** 释放系统绑定（Composable 离开时调用）。 */
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }
}

/**
 * 功能五（P1-1）：分享消息正文——走系统分享面板（对标 RikkaHub MessageSheetAction Share）。
 * 机制与 hotfix22 会话导出同源，抽到 [shareText] 共用（ACTION_SEND + text/plain，
 * 零权限；面板无接收方时系统显示空态，不崩）。
 */
private fun shareMessage(context: Context, text: String) = shareText(context, text, "分享消息")

/** 功能四：从 Base URL 提取域名（去 scheme、去路径）；空 URL 返回空串。 */
internal fun hostOf(baseUrl: String): String =
    baseUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .substringBefore("?")

/**
 * 「正在恢复」横幅（Phase 2 DoD③）：供应商瞬时失败自动重试时显示
 * 「正在恢复（n/N）· 原因」；恢复成功 / 最终失败 / 取消时由 VM 将 [ChatViewModel.retryState]
 * 置 null，横幅自动消失。轻量单行（secondaryContainer 底色 + 小号转圈），不抢气泡与输入栏焦点。
 */
@Composable
private fun RetryBanner(state: RetryState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(AgentSpacing.space2))
        Text(
            text = "正在恢复（${state.attempt}/${state.max}）· ${state.reason}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
 * 助手气泡操作区（Phase 2 DoD② + 功能四 P0-1）：变体切换器 `← n/m →` + 「复制」+「重新生成」。
 *
 * 缩进对齐助手气泡（头像 28dp + 间距 8dp = 36dp）；`←`/`→` 用文字而非图标，
 * 规避 material-icons-extended 依赖（本模块只依赖核心图标集）。
 * 序号 1-based 展示（「2/3」比「1/2」更符合用户直觉）。
 *
 * 复制位次：放在「重新生成」**之前**（RikkaHub `ChatMessageActions.kt:99-105` 同序）——
 * 两者都是「作用于本条消息」的高频动作，复制比重新生成更常用且无副作用。
 */
@Composable
private fun AssistantActions(
    variantIndex: Int,
    variantCount: Int,
    showSwitch: Boolean,
    showRegen: Boolean,
    showCopy: Boolean,
    showMore: Boolean,
    showSpeak: Boolean,
    speaking: Boolean,
    onSwitch: (Int) -> Unit,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    onSpeak: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCopy) {
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = AgentSpacing.space2, vertical = 6.dp)
            )
            if (showSwitch || showRegen) Spacer(Modifier.width(AgentSpacing.space3))
        }
        if (showSwitch) {
            SwitchArrow(label = "←", enabled = variantIndex > 0) { onSwitch(-1) }
            Text(
                text = "${variantIndex + 1}/$variantCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AgentSpacing.space2)
            )
            SwitchArrow(label = "→", enabled = variantIndex < variantCount - 1) { onSwitch(1) }
        }
        if (showRegen) {
            if (showSwitch) Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "重新生成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRegenerate)
                    .padding(horizontal = AgentSpacing.space2, vertical = 6.dp)
            )
        }
        // 功能五（P1-2）：朗读 / 停止朗读（同一按钮切换，文案随状态反转）
        if (showSpeak) {
            if (showCopy || showSwitch || showRegen || showMore) Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = if (speaking) "停止朗读" else "朗读",
                style = MaterialTheme.typography.labelMedium,
                color = if (speaking) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSpeak)
                    .padding(horizontal = AgentSpacing.space2, vertical = 6.dp)
            )
        }
        // 功能五（P1-1）：更多（分享 / 从此处删除）。用「⋯」文字而非图标，
        // 与既有 ←/→ 同一套「文字符号代替图标」约定
        if (showMore) {
            if (showCopy || showSwitch || showRegen) Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "⋯",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
            )
        }
    }
}

/** 变体切换箭头：可点文字，禁用时置灰 38%；触控 48dp 由外层 Row 高度与点击热区共同承载。 */
@Composable
private fun SwitchArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AgentSpacing.space1, vertical = 2.dp)
    )
}

/**
 * 功能四（P0-1）：用户气泡操作行——「复制」+「重新发问」（右对齐小字）。
 *
 * 与助手行共享同一套「可点小字」视觉：labelSmall/labelMedium + primary 主色、
 * radius 8dp clip + 4dp 横向内边距；运行中整行隐藏（避免点到旧轮）。
 * [showReask] = false 的草稿态气泡只保留复制——没有轮次可重问。
 */
@Composable
private fun UserMessageActions(
    showReask: Boolean,
    showMore: Boolean,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    onReask: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "复制",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
        )
        if (showReask) {
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "重新发问",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onReask)
                    .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
            )
        }
        if (showMore) {
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "⋯",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
            )
        }
    }
}

/**
 * 功能五（P1-1）：消息操作弹窗（分享 / 从此处删除）。
 *
 * 视觉语言与其它对话框一致（AkModalHost + 344dp 白卡 + scale/fade）。
 * 「从此处删除」文案明确写出「及之后的全部内容」——只写「删除」会让用户
 * 以为是删单条（本 App 不提供删单条，见 [ChatViewModel.deleteFromTurn] 的说明）。
 */
@Composable
private fun MessageActionsDialog(
    visible: Boolean,
    showDelete: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                Column(Modifier.padding(vertical = AgentSpacing.space4)) {
                    Text(
                        text = "消息操作",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space6)
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkListItem(
                        icon = Icons.Outlined.Share,
                        title = "分享",
                        description = "发送到其它应用",
                        onClick = onShare
                    )
                    if (showDelete) {
                        AkListItem(
                            icon = Icons.Outlined.Delete,
                            title = "从此处删除",
                            description = "删除这条及之后的全部内容",
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「从中间轮重新发问」编辑对话框（Phase 2 DoD②）。
 *
 * 预填该轮原提问，用户可改后发送；确认即截断该轮之后的对话并新开一轮
 * （见 `ChatViewModel.reaskFromTurn`）。样式对齐审批弹窗的简洁卡面。
 */
@Composable
private fun ReaskDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 344.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "从此处重新发问",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "确认后将截断这一轮之后的对话，并以新问题开一轮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 140.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(
                                horizontal = AgentSpacing.space3,
                                vertical = AgentSpacing.space2
                            )
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 5,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
                    ) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "发送",
                            onClick = { onConfirm(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
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
    images: List<String>,
    files: List<PendingFile>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickAttach: () -> Unit,
    onOpenPrompts: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    // hotfix30：后台有其他对话在生成时的一行说明（null = 不显示）——
    // 否则用户切到空闲会话发现发不了消息，会以为输入坏了
    runningNotice: String? = null
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
            // hotfix30：生成中提示行（有才占位，轻量 label 不抢焦点）
            if (runningNotice != null) {
                Text(
                    text = runningNotice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = AgentSpacing.space3,
                        end = AgentSpacing.space3,
                        top = AgentSpacing.space2
                    )
                )
            }
            // 功能四（P0-2）/ 功能五（P1-4）：待发附件（缩略图行 + 文件行，有才占位）
            if (images.isNotEmpty()) AttachedImagesRow(images, onRemoveImage)
            if (files.isNotEmpty()) AttachedFilesRow(files, onRemoveFile)
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
                // 功能四（P0-2）/ 功能五（P1-4）：附件入口——「＋」圆按钮，运行中禁用
                // （点开后再选「图片 / 文件」，不再直通相册）
                AttachImageButton(enabled = !running, onClick = onPickAttach)
                Spacer(Modifier.width(AgentSpacing.space2))
                // 功能五（P1-7）：快捷指令入口——「⚡」圆按钮，运行中禁用
                // （与 ＋ 同形制：输入栏不放两个异形按钮，视觉噪声翻倍）
                PromptButton(enabled = !running, onClick = onOpenPrompts)
                Spacer(Modifier.width(AgentSpacing.space2))
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
                    // T05：运行中按钮位升级为「停止」——点它取消 runJob，
                    // AgentLoop ≤1 轮内退出并闭合所有待决 ToolResult（无未闭合 tool_call）
                    ChatStopButton(onClick = onStop)
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

/**
 * 功能四（P0-2）：待发附件缩略图行。
 *
 * 横向滚动的 64dp 圆角缩略图 + 右上角移除按钮（外描边圆点 20dp，× 用文字规避
 * material-icons-extended）。放在输入框上方——附件是「随消息一起走的东西」，
 * 视觉上必须贴着输入框，不能丢到别处。
 *
 * 缩略图只做预览；真正发给模型的是 [images] 里的 data URL 原文。
 */
@Composable
private fun AttachedImagesRow(images: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        images.forEach { dataUrl ->
            val bitmap = remember(dataUrl) { decodeDataUrl(dataUrl) }
            Box(modifier = Modifier.size(64.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "待发图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onRemove(dataUrl) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 功能五（P1-4）：待发文件行——文件 chip（文件名 + 移除）。
 *
 * 与 [AttachedImagesRow] 并排在同一区域：图片看缩略图、文件看名字。
 * 名字截断到 180dp（长文件名不能把「×」挤出屏幕——那是唯一能取消的入口）。
 */
@Composable
private fun AttachedFilesRow(files: List<PendingFile>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        files.forEach { file ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "文件 · ${file.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = "×",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onRemove(file.name) }
                        .padding(horizontal = AgentSpacing.space1)
                )
            }
        }
    }
}

/**
 * 功能五（P1-4）：附件类型选择（图片 / 文件）。
 *
 * 与其它弹窗同一视觉语言（AkModalHost + 344dp 白卡）。两条路径各自的说明写清
 * 「能发什么」与「怎么被处理」——用户事前就知道 PDF 是当图片读的，
 * 不会事后疑惑「为什么模型没引用原文」。
 */
@Composable
private fun AttachPickerDialog(
    visible: Boolean,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                Column(Modifier.padding(vertical = AgentSpacing.space4)) {
                    Text(
                        text = "添加附件",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space6)
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkListItem(
                        icon = Icons.Outlined.Image,
                        title = "图片",
                        description = "从相册选择，视觉模型可直接看图",
                        onClick = onPickImage
                    )
                    AkListItem(
                        icon = Icons.Outlined.Description,
                        title = "文件",
                        description = "PDF / DOCX / 文本类（txt、md、json、csv、代码等）",
                        onClick = onPickFile
                    )
                }
            }
        }
    }
}

/**
 * 功能五（P1-7）：快捷指令入口（输入栏「⚡」圆按钮）。
 *
 * 与 [AttachImageButton] 同形制（touchTarget 圆底 surface-var，按压 0.97 微缩），
 * 只差图标与语义；运行中与附件按钮一同禁用——运行中选提示词会与在途请求抢输入框。
 */
@Composable
private fun PromptButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = "快捷指令",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 功能四（P0-2）：附件入口圆按钮——「＋」图标，禁用时置灰。 */
@Composable
private fun AttachImageButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = "添加图片附件",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 功能四（P0-1/P0-2）：把 data URL 解码成缩略图位图。
 *
 * 缩略图只用于预览，不参与请求——请求始终带 data URL 原文，避免二次编码损耗。
 */
private fun decodeDataUrl(dataUrl: String): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:") || comma < 0) return null
    val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/** 停止按钮：触控 48dp / 视觉 40dp，error 底 + 24dp on-error 停止图标；运行时常驻可点。 */
@Composable
private fun ChatStopButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
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
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(AgentDimens.topBarButtonVisual)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "停止",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(18.dp)
            )
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
                maxWidth = (maxWidth * 0.84f).coerceIn(272.dp, 520.dp),
                // 功能五（P1-5）：token 用量（null = 供应商没给，不显示）
                usage = item.usage,
                // 功能五（P1-9）：推理过程（null = 无思考，不渲染折叠块）
                reasoning = item.reasoning
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
    maxWidth: Dp,
    /** 功能五（P1-5）：助手气泡的 token 用量（null = 无数据不显示；用户气泡恒 null）。 */
    usage: TokenUsage? = null,
    /** 功能五（P1-9）：推理过程原文（null = 模型没给思考，不渲染折叠块；用户气泡恒 null）。 */
    reasoning: String? = null
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
                when {
                    // 用户气泡保持纯文本（用户输入不作 Markdown 解释）
                    isUser -> Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                    // 助手气泡：Markdown 渲染；流式时末尾带光标（流式全 App 唯一循环动画，§6.2）
                    streaming -> Column {
                        // 功能五（P1-9）：思考中默认展开——用户实时围观模型在推理什么
                        if (reasoning != null) {
                            ReasoningBlock(reasoning = reasoning, expandedInitially = true)
                        }
                        Row(
                            modifier = Modifier.widthIn(max = maxWidth),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MarkdownText(
                                text = text,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                            StreamCursor()
                            Spacer(Modifier.width(14.dp))
                        }
                    }
                    else -> Column {
                        // 功能五（P1-9）：完成态默认折叠——想复盘推理时点开头看
                        if (reasoning != null) {
                            ReasoningBlock(reasoning = reasoning, expandedInitially = false)
                        }
                        MarkdownText(
                            text = text,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                        // 功能五（P1-5）：本条回答的 token 用量（↑ 输入 / ↓ 输出）。
                        // 有数据才渲染——供应商没给 usage 时不摆占位
                        if (usage != null && usage.totalTokens > 0) {
                            Text(
                                text = "↑ ${formatTokens(usage.promptTokens)} · " +
                                    "↓ ${formatTokens(usage.completionTokens)} tokens",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                            )
                        }
                    }
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

/**
 * 功能五（P1-9）：推理过程折叠块（净室实现，参照三家「思考过程」交互的自有形制）。
 *
 * 头部「思考过程 · N 字」+ 旋转箭头常驻可反复开合；正文小号字、surface-1 底与正文区分。
 * 展开初值由 [expandedInitially] 决定：流式中传 true（实时围观模型思考），完成态传 false（默认折叠、点开复盘）。
 * 空推理不渲染本块——由调用方以 null 判断，本函数只接收非空。
 */
@Composable
private fun ReasoningBlock(reasoning: String, expandedInitially: Boolean) {
    var expanded by remember { mutableStateOf(expandedInitially) }
    Column(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LocalAkColors.surface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "思考过程 · ${reasoning.length} 字",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
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

/**
 * ask_user 的选择卡片（W-14）。
 *
 * 为什么不用弹窗：弹窗会遮挡上下文，用户往往需要回看上文才能选；
 * 贴在输入框上方既醒目又不打断阅读，选完即消失（pending 归 null）。
 */
/**
 * W-25：计划审批卡片。贴在输入框上方，不遮挡上下文；批准即开始执行，拒绝可附一句修改意见。
 *
 * 交互取舍：
 *  - 「批准执行」是主按钮（实心），「不要执行」是次按钮（描边）——避免误触批准破坏性计划；
 *  - 拒绝时展开一个输入框写原因（可空），模型据此重新规划；
 *  - 步骤多时卡片内部滚动，不把输入栏挤出屏幕。
 */
@Composable
private fun PlanCard(
    plan: PlanBroker.Plan,
    onApprove: () -> Unit,
    onReject: (String) -> Unit
) {
    var rejecting by remember(plan.id) { mutableStateOf(false) }
    var reason by remember(plan.id) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2)
    ) {
        Column(Modifier.padding(AgentSpacing.space4)) {
            Text(
                text = "计划待确认（${plan.steps.size} 步）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = plan.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                plan.steps.forEachIndexed { i, step ->
                    Text(
                        text = "${i + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(AgentSpacing.space3))
            if (rejecting) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = { Text("哪里不对？可留空") }
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkButton(text = "提交意见", onClick = { onReject(reason.trim()) })
                    AkTextButton(text = "返回", onClick = { rejecting = false })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkButton(text = "批准执行", onClick = onApprove, modifier = Modifier.weight(1f))
                    AkTextButton(text = "不要执行", onClick = { rejecting = true }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AskUserCard(
    question: AskUserBroker.Question,
    onAnswer: (String) -> Unit
) {
    var freeText by remember(question.id) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2)
    ) {
        Column(Modifier.padding(AgentSpacing.space4)) {
            Text(
                text = "需要你确认",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = question.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AgentSpacing.space3))
            if (question.options.isNotEmpty()) {
                question.options.forEach { option ->
                    AkTextButton(
                        text = option,
                        onClick = { onAnswer(option) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("输入回答") }
                    )
                    Spacer(Modifier.width(AgentSpacing.space2))
                    AkTextButton(
                        text = "发送",
                        onClick = { if (freeText.isNotBlank()) onAnswer(freeText.trim()) },
                        enabled = freeText.isNotBlank()
                    )
                }
            }
        }
    }
}
