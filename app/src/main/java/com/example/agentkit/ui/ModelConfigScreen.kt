package com.example.agentkit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.core.ConfigSanitizer
import com.example.agentkit.core.DEFAULT_SYSTEM_PROMPT
import com.example.agentkit.core.ModelProfile

/**
 * 模型配置页（视觉规范 §6.8 + §6.5.3 页面内 Tab）。
 *
 * 一级页面：顶栏「模型配置」无右上动作（保存有毁损性，不进顶栏防误触，§6.5.4）；
 * 内容区顶部为页面内 Tab「模型 / 提示词」——内容分区，非 App 导航。
 *
 * 功能二（多模型配置，录屏反馈「别人的模型可以搞好几个」）：顶栏下方是配置档案切换条，
 * 页面语义变为「编辑当前选中的配置」；保存 / 测连通 / 提示词全部作用于当前档案。
 *
 * @param onBack 「取消 / 保存」后回对话页（抽屉路由由宿主持有）。
 */
@Composable
fun ModelConfigScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    // 以已落盘配置为基准：config 变化（保存成功 / 切换档案）即重置编辑态，避免二次进入残留旧值
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var providerId by remember(config) { mutableStateOf(config.providerId) }
    var tab by rememberSaveable { mutableStateOf(0) }
    // 功能二：新建档案对话框开关（用户录屏反馈「点＋新增没有任何 visible 反馈」——
    // 旧行为直接静默建副本，新 chip 还被横向滚动条挤到屏外；参照 App 改为先弹命名框）
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current

    val dirty = baseUrl != config.baseUrl || apiKey != config.apiKey ||
        model != config.model || providerId != config.providerId

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(title = AppDest.MODEL.title)
        // 切换档案会静默丢弃未保存编辑（编辑器随 config 重置）——档案是顶层上下文，
        // 切走即换编辑对象，与「保存后才生效」的语义一致，不另做挽留弹窗
        ProfileSwitcher(
            profiles = profiles,
            activeId = activeProfileId,
            onSelect = { vm.switchProfile(it) },
            onAdd = { showAdd = true }
        )
        LocalTabRefreshEffect(vm = vm, tab = tab)
        ModelPageTabs(
            selected = tab,
            onSelect = { tab = it }
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
            when (tab) {
                0 -> {
                    // 功能四（P0-3）：提供商列表 = 「换模型」的一级入口
                    ProviderPickerSection(
                        profiles = profiles,
                        activeId = activeProfileId,
                        onSelect = { id ->
                            vm.switchProfile(id)
                            val name = profiles.firstOrNull { it.id == id }?.name ?: "该配置"
                            Toast.makeText(ctx, "已切换到「$name」", Toast.LENGTH_SHORT).show()
                        },
                        onCreate = {
                            vm.addProfile(null)
                            Toast.makeText(ctx, "已新建配置", Toast.LENGTH_SHORT).show()
                        }
                    )
                    ModelTab(
                        vm = vm,
                        baseUrl = baseUrl,
                        onBaseUrl = { baseUrl = it },
                        apiKey = apiKey,
                        onApiKey = { apiKey = it },
                        model = model,
                        onModel = { model = it },
                        providerId = providerId,
                        onProviderChange = { id ->
                            if (id != providerId) {
                                // 智能预填：仅当当前值是旧供应商默认值或空时才填新默认值，
                                // 用户手工改过的一律不覆盖（可预期 > 聪明）
                                if (baseUrl == ProviderDefaults.defaultUrl(providerId) || baseUrl.isBlank()) {
                                    baseUrl = ProviderDefaults.defaultUrl(id)
                                }
                                if (model == ProviderDefaults.defaultModel(providerId) || model.isBlank()) {
                                    model = ProviderDefaults.defaultModel(id)
                                }
                                providerId = id
                            }
                        },
                        dirty = dirty,
                        onSave = {
                            // 保存时统一过 ConfigSanitizer：剔除不可见字符、规范化 URL，避免带着 \u00A0 落盘
                            vm.updateConfig(
                                ConfigSanitizer.sanitized(baseUrl, apiKey, model, providerId)
                        )
                            onBack()
                        },
                        onCancel = onBack
                    )
                }

                1 -> LocalModelsTab(vm = vm)

                else -> PromptTab(config = config)
            }
        }
    }

    // 功能二：新建档案对话框（用户录屏反馈「新增无 visible 反馈」+ 参照 App：先弹命名框再创建）。
    // 预填自动名「配置 N」，用户可直接点创建；清空则按 null 交 VM 走自动命名，绝不建空白名档案。
    // AkModalHost 用法与 ModelTab 的重命名对话框一致：退场播完再卸载，content 离开组合后
    // 内部 remember 的输入状态随之重置，下次打开重新预填。
    AkModalHost(target = if (showAdd) true else null) { _, visible ->
        var name by remember { mutableStateOf("配置 ${profiles.size + 1}") }
        AddProfileDialog(
            name = name,
            onNameChange = { name = it },
            visible = visible,
            onConfirm = { vm.addProfile(name.ifBlank { null }); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }
}

/** 「本地模型」页签打开时刷新清单与加载状态（下载完成后回到前台也刷新）。 */
@Composable
private fun LocalTabRefreshEffect(vm: ChatViewModel, tab: Int) {
    LaunchedEffect(tab) { if (tab == 1) vm.refreshLocalModels() }
}

/** 三家供应商的默认 Base URL / 模型名（切换供应商时预填用）。 */
private object ProviderDefaults {
    fun defaultUrl(id: String): String = when (id) {
        "claude" -> "https://api.anthropic.com"
        "gemini" -> "https://generativelanguage.googleapis.com"
        else -> "https://api.openai.com/v1"
    }

    fun defaultModel(id: String): String = when (id) {
        "claude" -> "claude-sonnet-4-5"
        "gemini" -> "gemini-2.5-pro"
        // 本地模式的模型由「本地模型」页签的 active 文件决定，这里只给个占位
        "local-llama" -> ""
        else -> "gpt-4o-mini"
    }
}

/**
 * 页面内 Tab（§6.5.3，全 App 仅此一处）：文字 labelLarge，未选 on-surface-var w500、
 * 选中 primary w600 + 底部 2dp primary 下划线（宽随文字）；仅点选，变色 150ms，无药丸无背景。
 */
@Composable
private fun ModelPageTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("模型", "本地模型", "提示词")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space5),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            val lineColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = AkMotionFastColor
            )
            Column(
                Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .clickable { onSelect(index) }
                    .padding(
                        horizontal = AgentSpacing.space4,
                        vertical = AgentSpacing.space3
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                // 列宽自然 wrapContentWidth（= 最宽子项，即文字宽），下划线随之等宽。
                // 注意：不要用 Modifier.width(IntrinsicSize.Min)——在 Row 的普通测量路径下
                // 宽度会塌缩到单字宽，中文逐字换行表现为竖排（真机实测，2026-10-13）。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(lineColor)
                )
            }
            if (index < tabs.lastIndex) Spacer(Modifier.width(AgentSpacing.space2))
        }
    }
}

/**
 * 功能二：配置档案切换条（录屏反馈「别人的模型可以搞好几个，你就只能搞一个」）。
 *
 * Row + horizontalScroll：档案多了一行放不下时横向滚动，不换行、不挤压编辑器；
 * 每档案一枚 [AkChip]（选中态 = 当前生效配置），条尾「＋」chip 视觉与 AkChip 对齐。
 * 切换是即时生效的（VM 同步 _config 与旧四键镜像），未经保存的编辑器改动随切换丢弃。
 */
@Composable
private fun ProfileSwitcher(
    profiles: List<ModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    val scrollState = rememberScrollState()
    // 用户录屏反馈「点＋新增后根本没有增加，他还是在最开始那个页面，根本就没变」：
    // 新档案 chip 追加在条尾，一屏放不下时被横向滚动条挤到屏幕外，点了跟没点一样。
    // 档案数量变化即平滑滚到最右，让最新档案和「＋」按钮进入可见区（数量没变不滚，
    // 避免切 tab 等无关重组把用户已经手动滚开的位置强行拽回）。
    LaunchedEffect(profiles.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profiles.forEach { profile ->
            AkChip(
                text = profile.name,
                selected = profile.id == activeId,
                onClick = { onSelect(profile.id) }
            )
            Spacer(Modifier.width(AgentSpacing.space2))
        }
        AddProfileChip(onClick = onAdd)
    }
}

/** 「＋」chip：按 [AkChip] 视觉自绘（32dp 高 / radius full / surface-1 底 / 按压 surface-2 + scale 0.97）。 */
@Composable
private fun AddProfileChip(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    val bg by animateColorAsState(
        targetValue = if (pressed) LocalAkColors.surface2 else LocalAkColors.surface1,
        animationSpec = AkMotionFastColor
    )
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "新增",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 供应商选择器（§6.8 模型 Tab 顶部）：2×2 等高胶囊（48dp 触控，§6.0）。
 *
 * 四家两行：上行云端三家，下行本地模型——本地是「另一种模式」而非第四家云端，
 * 单独一行在语义上更诚实。选中：primary 底 + on-primary 文字 w600；未选：透明底 +
 * 1.5dp outline 描边 + on-surface-variant 文字 w500。背景色 150ms 过渡（AkMotionFastColor）。
 */
@Composable
private fun ProviderSelector(selected: String, onSelect: (String) -> Unit) {
    val row1 = listOf(
        "openai" to "OpenAI 兼容",
        "claude" to "Claude",
        "gemini" to "Gemini"
    )
    val row2 = listOf("local-llama" to "本地模型")
    Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
        ProviderRow(row1, selected, onSelect)
        ProviderRow(row2, selected, onSelect)
    }
}

@Composable
private fun ProviderRow(
    providers: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        providers.forEach { (id, label) ->
            val isSelected = id == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = AkMotionFastColor
            )
            Surface(
                onClick = { onSelect(id) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = bg,
                border = if (isSelected) null
                else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        // 单枚胶囊的行（本地模型）补一个不可见占位，保持左右边距与上行一致
        if (providers.size == 1) Spacer(Modifier.weight(2f))
    }
}

/** 「模型」Tab：接入字段 + Key 自检 + 测试连接四态 + 结果卡 + 保存/取消（§6.8）。 */
@Composable
private fun ModelTab(
    vm: ChatViewModel,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    providerId: String,
    onProviderChange: (String) -> Unit,
    dirty: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var keyRevealed by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    // 「选择模型」弹窗：从当前端点拉取可用模型列表搜索选择（用户反馈「给了路径我就能搜到有什么模型」）
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    // 本地模式没有端点/Key：测试按钮放行，字段区换成状态卡
    val isLocal = providerId == "local-llama"
    val canTest = isLocal || (baseUrl.isNotBlank() && apiKey.isNotBlank())

    AkSectionCard(
        title = "模型接入",
        description = "三家云端供应商可切换：OpenAI 兼容端点（含 vLLM / Ollama / OneAPI）、" +
            "Claude Messages API、Gemini generateContent。" +
            "填错 Key 时对话页会直接提示「API Key 无效」，不会再反复重试。"
    ) {
        Text(
            text = "供应商",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        ProviderSelector(selected = providerId, onSelect = onProviderChange)
        Spacer(Modifier.height(AgentSpacing.space3))

        if (isLocal) {
            // 本地模式：端点/Key/模型名三个字段全部不适用，换成当前本地模型状态卡
            LocalModeSummaryCard(vm = vm)
        } else {
            AkTextField(
                value = baseUrl,
                onValueChange = onBaseUrl,
                label = "Base URL",
                placeholder = ProviderDefaults.defaultUrl(providerId),
                machine = true
            )
            Spacer(Modifier.height(AgentSpacing.space3))
            // API Key：默认 masked，眼睛 IconButton（48dp 触控）切换明文
            AkTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = "API Key",
                placeholder = "sk-…",
                machine = true,
                visualTransformation = if (keyRevealed) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailing = {
                    AkCircleIconButton(
                        icon = if (keyRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        description = if (keyRevealed) "隐藏 Key" else "显示 Key",
                        onClick = { keyRevealed = !keyRevealed },
                        iconSize = 20.dp
                    )
                }
            )
            KeySelfCheck(apiKey)
            Spacer(Modifier.height(AgentSpacing.space3))
            // 模型名： trailing 放大镜 IconButton（48dp 触控）打开「选择模型」弹窗，
            // 从端点拉取可用模型搜索选择，不必盲手填（用法同上方 API Key 字段的眼睛按钮）
            AkTextField(
                value = model,
                onValueChange = onModel,
                label = "模型名",
                placeholder = ProviderDefaults.defaultModel(providerId),
                machine = true,
                trailing = {
                    AkCircleIconButton(
                        icon = Icons.Outlined.Search,
                        description = "获取模型列表",
                        onClick = { showModelPicker = true },
                        iconSize = 20.dp
                    )
                }
            )
        }
    }

    TestConnectionButton(
        testing = testing,
        result = testResult,
        enabled = canTest && !testing,
        onClick = { vm.testConnection(baseUrl, apiKey, model, providerId) }
    )
    if (!canTest) {
        Text(
            text = "填写 Base URL 与 API Key 后即可测试连接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    testResult?.let { result ->
        TestResultCard(result = result, onCopy = { copyToClipboard(ctx, result) })
    }

    // 保存 / 取消：flex 1 等高（§6.8）；无改动时保存禁用（38% alpha）
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
    ) {
        AkOutlinedButton(
            text = "取消",
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        )
        AkPrimaryButton(
            text = "保存",
            onClick = onSave,
            enabled = dirty,
            modifier = Modifier.weight(1f)
        )
    }

    // 「选择模型」弹窗：AkModalHost 承载退场动画（用法同重命名/删除对话框）。
    // 打开即拉取；local-llama 模式字段区换成 LocalModeSummaryCard，天然没有该入口。
    AkModalHost(target = if (showModelPicker) true else null) { _, visible ->
        ModelPickerSheet(
            vm = vm,
            baseUrl = baseUrl,
            apiKey = apiKey,
            providerId = providerId,
            onPick = { onModel(it); showModelPicker = false },
            onDismiss = { vm.clearModelList(); showModelPicker = false },
            visible = visible
        )
    }

    // 功能二：当前档案管理。删除是不可逆操作，AkConfirmDialog danger 二次确认；
    // 只剩一个档案时删除项置灰（VM 侧 deleteProfile 同样拒绝，双保险）
    AkGroupCard {
        AkListItem(
            icon = Icons.Outlined.Edit,
            title = "重命名当前配置",
            description = activeProfile?.name ?: "未命名配置",
            onClick = { showRename = true }
        )
        AkDivider()
        AkListItem(
            icon = Icons.Outlined.Delete,
            title = "删除当前配置",
            description = if (profiles.size > 1) "删除后不可恢复，将切换到其余配置"
            else "至少保留一个配置",
            enabled = profiles.size > 1,
            onClick = { showDelete = true }
        )
    }

    // 重命名对话框：AkModalHost 承载退场动画（直接随 flag 卸载会闪断）
    AkModalHost(target = if (showRename) activeProfileId else null) { id, visible ->
        val profile = profiles.firstOrNull { it.id == id }
        var text by remember(id) { mutableStateOf(profile?.name ?: "") }
        RenameProfileDialog(
            name = text,
            onNameChange = { text = it },
            visible = visible,
            onConfirm = { vm.renameProfile(id, text); showRename = false },
            onDismiss = { showRename = false }
        )
    }

    // 删除确认：danger 红色只出现在审批与不可逆确认两处（§3.1）
    AkModalHost(target = if (showDelete) activeProfileId else null) { id, visible ->
        val profile = profiles.firstOrNull { it.id == id }
        AkConfirmDialog(
            title = "删除当前配置",
            message = "确定删除「${profile?.name ?: "该配置"}」吗？" +
                "其 Base URL 与 API Key 将一并移除，删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            visible = visible,
            onConfirm = { vm.deleteProfile(id); showDelete = false },
            onDismiss = { showDelete = false }
        )
    }
}

/**
 * 功能四（P0-3）：提供商列表——「换模型」的一级入口。
 *
 * 对标 Coomi `ProvidersView.vue` / RikkaHub `SettingPage`：把「有哪些提供商 +
 * 当前用哪个 + 点一下就换」放在同一层，而不是让用户先认出顶部那排横向
 * 芯片条、再横向找被挤出去的那个。
 *
 * 每行：图标底座 + 配置名 +「模型名 · 域名」；当前生效项 selected（primary
 * 图标底座 + primary 标题 + 右侧对勾）。点选即 [ChatViewModel.switchProfile]
 * 生效——VM 同步 _config 并落盘，下一轮请求即走新配置，无需先点保存。
 *
 * 布局用 Column 而非 LazyColumn：档案数量级是个位数，纵向滚动交给宿主
 * Column（本页已有 verticalScroll），避免嵌套滚动。
 */
@Composable
private fun ProviderPickerSection(
    profiles: List<ModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit
) {
    AkSectionCard(
        title = "提供商",
        description = "共 ${profiles.size} 个配置 · 点选即时生效，无需先保存"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)) {
            profiles.forEach { profile ->
                val isActive = profile.id == activeId
                val host = hostOf(profile.config.baseUrl)
                val modelText = profile.config.model.ifBlank { "未设置模型名" }
                AkListItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = profile.name,
                    description = if (host.isBlank()) modelText else "$modelText · $host",
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
            Spacer(Modifier.height(AgentSpacing.space2))
            AkTextButton(
                text = "＋ 新建提供商",
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 功能二：档案重命名对话框（[AkModalHost] 承载退场动画）。
 *
 * 与 [AkConfirmDialog] 同语言：344dp 白卡 + scale/fade 出入场；提交空名由 VM 忽略
 * （renameProfile 对 blank 直接 return），此处按钮同步禁用给即时反馈。
 */
@Composable
private fun RenameProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
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
                    Text(
                        text = "重命名配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = "配置名称",
                        placeholder = "例如：家里的 OpenAI"
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "确定",
                            onClick = onConfirm,
                            enabled = name.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 功能二：「新建模型配置」对话框（用户录屏反馈「点＋新增没有任何 visible 反馈，
 * 还是在最开始那个页面，根本就没变」+ 参照 App：点「＋新建」→ 命名框 → 创建后才可见）。
 *
 * 与 [RenameProfileDialog] 同语言同机制（AkModalHost 承载退场 + 344dp 白卡 + scale/fade）：
 * 标题 + 一句描述 + 名称输入 + 取消/创建。创建按钮永不禁用——名称清空时由 VM 回退
 * 自动命名（addProfile 对空白名走「配置 N」），绝不创建空白名档案。
 */
@Composable
private fun AddProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
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
                        text = "新建模型配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "创建新的模型配置，自定义 API 和参数设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = "配置名称",
                        placeholder = "例如：GPT-4 配置、Claude 配置..."
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "创建",
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「选择模型」弹窗（用户反馈「给了路径我就能搜索到他有什么模型，我不是说模型我自己搞」）：
 * 从当前端点拉取可用模型，点击选择；也可以返回手动输入。
 *
 * 视觉语言对齐 [AddProfileDialog] / [RenameProfileDialog]：AkModalHost 承载退场 +
 * 344dp 圆角白卡 + scale/fade 出入场。四态渲染（消费 VM 的 modelListLoading /
 * modelListError / modelList 三个 StateFlow）：
 *  ① loading：圆环 +「正在获取模型列表…」；
 *  ② error：脱敏后的错误文案 +「重试」；
 *  ③ 空列表且非 loading：「未获取到模型」+「重试」+「该端点可能不支持 /models，可返回手动输入」；
 *  ④ 有数据：搜索框 +「共 N 个模型」+ 封顶 360dp 的 LazyColumn，点击行即选定；
 *     当前已选中的行（与已落盘的 config.model 比对）文字升 primary 并附对勾。
 */
@Composable
private fun ModelPickerSheet(
    vm: ChatViewModel,
    baseUrl: String,
    apiKey: String,
    providerId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean
) {
    val loading by vm.modelListLoading.collectAsStateWithLifecycle()
    val error by vm.modelListError.collectAsStateWithLifecycle()
    val models by vm.modelList.collectAsStateWithLifecycle()
    // 当前已选中的模型：以已落盘配置为准（不是编辑器里未保存的值）
    val config by vm.config.collectAsStateWithLifecycle()
    // 搜索关键词：纯本地状态，无需上提到 VM
    var keyword by remember { mutableStateOf("") }

    // 打开即拉（弹窗随退场动画卸载后，再次打开重新组合会重新触发）
    LaunchedEffect(Unit) { vm.fetchModelList(baseUrl, apiKey, providerId) }

    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
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
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "从当前端点拉取可用模型，点击选择；也可以返回手动输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    when {
                        // ① loading
                        loading -> Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = AgentSpacing.space6),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                text = "正在获取模型列表…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // ② error：文案 + 重试
                        error != null -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = error ?: "未知错误",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            AkTextButton(
                                text = "重试",
                                onClick = { vm.fetchModelList(baseUrl, apiKey, providerId) }
                            )
                        }

                        // ③ 空列表（非 loading）：未获取到 + 重试 + 端点不支持提示
                        models.isEmpty() -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "未获取到模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            AkTextButton(
                                text = "重试",
                                onClick = { vm.fetchModelList(baseUrl, apiKey, providerId) }
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            Text(
                                text = "该端点可能不支持 /models，可返回手动输入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        // ④ 有数据：搜索框 + 计数 + 封顶列表
                        else -> {
                            AkTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                label = "搜索模型",
                                placeholder = "搜索模型",
                                machine = true
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            Text(
                                text = "共 ${models.size} 个模型",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            // 本地过滤：modelId 含关键词（忽略大小写）即命中
                            val filtered = models.filter { it.contains(keyword, ignoreCase = true) }
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                            ) {
                                items(filtered) { id ->
                                    val isCurrent = id == config.model
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onPick(id) }
                                            .padding(
                                                vertical = AgentSpacing.space3,
                                                horizontal = AgentSpacing.space2
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = id,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isCurrent) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isCurrent) {
                                            Spacer(Modifier.width(AgentSpacing.space2))
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = "当前模型",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space6))
                    // 底部取消：fillMaxWidth，与顶部动作行的视觉重量一致
                    AkOutlinedButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 「本地模型」页签（九步第 3 步）：本地 llama.cpp 推理的完整控制台。
 *
 * 四个分区：运行状态 → 已下载模型（选用/删除）→ 内置目录（下载）→ 推理参数。
 * 所有状态都由 ChatViewModel 的 StateFlow 驱动，Composable 无副作用（除刷新 effect）。
 */
@Composable
private fun LocalModelsTab(vm: ChatViewModel) {
    val models by vm.localModels.collectAsStateWithLifecycle()
    val active by vm.localActive.collectAsStateWithLifecycle()
    val loaded by vm.localLoaded.collectAsStateWithLifecycle()
    val downloading by vm.localDownloading.collectAsStateWithLifecycle()
    val nCtx by vm.localNCtx.collectAsStateWithLifecycle()
    val threads by vm.localThreads.collectAsStateWithLifecycle()
    val maxTokens by vm.localMaxTokens.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val catalog = remember(ctx) { com.example.agentkit.core.llm.local.GgufModelStore(ctx).catalog() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    // —— 运行状态 ————————————————————————————————————————————
    val nativeOk = remember { com.example.agentkit.core.llm.local.LlamaJni.ensureLoaded() }
    AkSectionCard(
        title = "运行状态",
        description = "本地推理由 llama.cpp（b4600，MIT）驱动，模型常驻内存随用随聊；" +
            "仅支持 arm64 真机，模拟器暂不可用。"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(
                if (nativeOk) LocalAkColors.success else MaterialTheme.colorScheme.error,
                6.dp
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (nativeOk) "推理引擎就绪（arm64）" else "推理引擎不可用（当前设备 ABI 不支持）",
                style = MaterialTheme.typography.labelLarge,
                color = if (nativeOk) LocalAkColors.success else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            text = "当前模型：${active.ifBlank { "未选择" }}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "内存状态：${if (loaded) "已加载（可立即对话）" else "未加载（首次对话时加载，约需数秒）"}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (loaded) {
            Spacer(Modifier.height(AgentSpacing.space3))
            AkOutlinedButton(
                text = "卸载模型（释放内存）",
                onClick = { vm.unloadLocalModel() },
                icon = Icons.Outlined.Delete
            )
        }
    }

    // —— 已下载模型 ——————————————————————————————————————————
    AkSectionCard(
        title = "已下载模型",
        description = "点选即设为当前模型，下次对话生效。删除会同时清理文件。"
    ) {
        if (models.isEmpty()) {
            Text(
                text = "还没有模型。从下方「模型目录」下载一个（建议 1.5B 起步）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            models.forEach { m ->
                val isActive = m.fileName == active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setActiveLocalModel(m.fileName) }
                        .padding(vertical = AgentSpacing.space3, horizontal = AgentSpacing.space2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AkStatusDot(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        8.dp
                    )
                    Spacer(Modifier.width(AgentSpacing.space3))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = m.fileName,
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "${m.sizeBytes / 1024 / 1024} MB" +
                                if (isActive) " · 当前使用" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AkCircleIconButton(
                        icon = Icons.Outlined.Delete,
                        description = "删除 ${m.fileName}",
                        onClick = { pendingDelete = m.fileName },
                        iconSize = 20.dp
                    )
                }
            }
        }
    }

    // —— 模型目录 ————————————————————————————————————————————
    AkSectionCard(
        title = "模型目录",
        description = "Q4_K_M 量化（体积/质量黄金分割点），均来自 HuggingFace 公开仓库。" +
            "下载支持断点续传，中途退出下次继续。"
    ) {
        catalog.forEach { entry ->
            val isDownloaded = models.any { it.fileName == entry.fileName }
            val isDownloading = downloading?.first == entry.fileName
            Column(Modifier.padding(vertical = AgentSpacing.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${entry.displayName} · ${entry.paramSize}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${entry.description} · 约 ${entry.approxBytes / 1024 / 1024} MB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        isDownloading -> Text(
                            text = "下载中…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        isDownloaded -> Text(
                            text = "已下载",
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalAkColors.success
                        )
                        else -> AkOutlinedButton(
                            text = "下载",
                            onClick = { vm.downloadLocalModel(entry) },
                            icon = Icons.Outlined.Download,
                            enabled = downloading == null
                        )
                    }
                }
                if (isDownloading) {
                    Spacer(Modifier.height(AgentSpacing.space2))
                    val progress = downloading?.second
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }
            if (entry != catalog.last()) AkDivider()
        }
    }

    // —— 推理参数 ————————————————————————————————————————————
    AkSectionCard(
        title = "推理参数",
        description = "上下文越大越吃内存（4K 约额外 0.5GB 级）；线程数超过大核数收益递减。" +
            "改动保存后下次加载生效。"
    ) {
        LocalParamField(
            label = "上下文长度（token）",
            value = nCtx.toString(),
            hint = "512 ~ 16384，常用 2048 / 4096",
            onValue = { vm.setLocalNCtx(it.toIntOrNull() ?: return@LocalParamField) }
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        LocalParamField(
            label = "推理线程数",
            value = threads.toString(),
            hint = "1 ~ 8，默认取 min(4, 大核数)",
            onValue = { vm.setLocalThreads(it.toIntOrNull() ?: return@LocalParamField) }
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        LocalParamField(
            label = "单次生成上限（token）",
            value = maxTokens.toString(),
            hint = "64 ~ 8192，一次回复的最大长度",
            onValue = { vm.setLocalMaxTokens(it.toIntOrNull() ?: return@LocalParamField) }
        )
    }

    pendingDelete?.let { name ->
        val sizeMb = models.firstOrNull { it.fileName == name }?.sizeBytes?.div(1024 * 1024)
        AkConfirmDialog(
            title = "删除模型文件",
            message = "确定删除 $name 吗？文件将从本机移除" +
                (sizeMb?.let { "（$it MB），" } ?: "，") + "需要时需重新下载。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            onConfirm = {
                vm.deleteLocalModel(name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** 「模型」页签里本地模式的状态摘要（点「本地模型」页签可管理）。 */
@Composable
private fun LocalModeSummaryCard(vm: ChatViewModel) {
    val active by vm.localActive.collectAsStateWithLifecycle()
    val loaded by vm.localLoaded.collectAsStateWithLifecycle()
    val models by vm.localModels.collectAsStateWithLifecycle()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(
                if (active.isNotBlank()) LocalAkColors.success
                else MaterialTheme.colorScheme.error,
                6.dp
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (active.isBlank()) "未选择本地模型" else "已选用 $active",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(AgentSpacing.space2))
        Text(
            text = buildString {
                append("本地模式不使用 Base URL 与 API Key。")
                if (active.isBlank()) {
                    append("请切到「本地模型」页签下载并选用一个 GGUF 模型（约 1GB 起）。")
                } else {
                    append("模型文件共 ${models.size} 个，推理参数见「本地模型」页签。")
                    if (loaded) append("当前已在内存中，可直接对话。")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 本地参数数字输入行：label + 数值框 + 提示。 */
@Composable
private fun LocalParamField(
    label: String,
    value: String,
    hint: String,
    onValue: (String) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        AkTextField(
            value = value,
            onValueChange = onValue,
            label = label,
            placeholder = hint,
            machine = true
        )
    }
}

/**
 * API Key 实时自检行（§6.8）：每次输入实时刷新。
 *
 * 正常：6dp success 点 +「长度 56 · sk-a…7F3A · 校验通过」12sp（指纹 machineSmall）；
 * 异常：danger 点 + 「检测到 3 个不可见字符，保存时自动清除」danger——
 * 在保存前就把「粘贴不全 / 夹带不可见字符」暴露给用户。
 */
@Composable
private fun KeySelfCheck(rawKey: String) {
    if (rawKey.isBlank()) return
    val insp = remember(rawKey) { ConfigSanitizer.inspectKey(rawKey) }
    val hasIssue = insp.hasIllegalChars || insp.length < 8
    val dot = if (hasIssue) MaterialTheme.colorScheme.error else LocalAkColors.success
    Column(Modifier.padding(top = AgentSpacing.space3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(dot, 6.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append("长度 ${insp.length} · ")
                    }
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append("${insp.head}…${insp.tail}")
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(if (hasIssue) " · 存在异常" else " · 校验通过")
                    }
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (insp.hasIllegalChars) {
            Text(
                text = "检测到 ${insp.removedCount} 个不可见字符，保存时自动清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * 「测试连接」按钮四态（§6.0 / §6.8）：
 * idle = Filled 主按钮全宽；testing = 左 18dp 圆环 + 「测试中…」；
 * ok = success-container 底 + 对勾 + 「连接正常」；fail = danger-container 底 + 状态码。
 */
@Composable
private fun TestConnectionButton(
    testing: Boolean,
    result: TestResult?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val ak = LocalAkColors
    val container = when {
        result == null || testing -> MaterialTheme.colorScheme.primary
        result.ok -> ak.successContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when {
        result == null || testing -> MaterialTheme.colorScheme.onPrimary
        result.ok -> ak.onSuccessContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AgentDimens.touchTarget)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled || testing) container else container.copy(alpha = 0.38f)
            )
            .clickable(enabled = enabled || testing, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (enabled || testing) content else content.copy(alpha = 0.38f)
        when {
            testing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = fg
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text("测试中…", style = MaterialTheme.typography.labelLarge, color = fg)
            }

            result?.ok == true -> {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text("连接正常", style = MaterialTheme.typography.labelLarge, color = fg)
            }

            result != null -> {
                Text(
                    text = if (result.code > 0) "${result.code} · 连接失败" else "网络异常 · 连接失败",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg
                )
            }

            else -> {
                Text("测试连接", style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

/** 「测试连接」结果卡（§6.8）：端点 / 指纹 / 响应预览 machineSmall，右下「复制」。 */
@Composable
private fun TestResultCard(result: TestResult, onCopy: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(AgentSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkStatusDot(
                    if (result.ok) LocalAkColors.success else MaterialTheme.colorScheme.error,
                    6.dp
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (result.ok) "连接正常" else "连接失败 · ${result.code}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (result.ok) LocalAkColors.success else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.weight(1f))
                AkTextButton(
                    text = "复制",
                    onClick = onCopy,
                    icon = Icons.Outlined.ContentCopy,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "端点：${result.endpoint}",
                        style = machineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Key 指纹：${result.fingerprint}",
                        style = machineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (result.bodyPreview.isNotBlank()) {
                        Text(
                            text = "响应：${result.bodyPreview}",
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result.suggestion.isNotBlank()) {
                        Text(
                            text = result.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** 复制诊断结果到剪贴板（导出排查用，不动日志文件）。 */
private fun copyToClipboard(ctx: Context, result: TestResult) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = buildString {
        appendLine("结果：${if (result.ok) "连接正常" else "失败 ${result.code}"}")
        appendLine("端点：${result.endpoint}")
        appendLine("Key 指纹：${result.fingerprint}")
        if (result.bodyPreview.isNotBlank()) appendLine("响应：${result.bodyPreview}")
        if (result.suggestion.isNotBlank()) appendLine("建议：${result.suggestion}")
    }
    cm.setPrimaryClip(ClipData.newPlainText("AgentKit 连接诊断", text))
}

/**
 * 「提示词」Tab：透明展示每轮发往模型的系统提示拼装顺序（只读）。
 *
 * 提示词由内核按「基础人设 → 工具自带提示 → 记忆摘要 → 记忆使用引导」拼装
 * （core/AgentLoop.buildSystemPrompt），当前版本不提供手动编辑——改措辞属于内核行为。
 */
@Composable
private fun PromptTab(config: com.example.agentkit.skill.ModelConfig) {
    AkSectionCard(
        title = "系统提示词",
        description = "每轮对话发往模型前，AgentKit 按下面的顺序拼装系统提示，随对话实时生效。",
        icon = Icons.Outlined.Visibility
    ) {
        PromptBlock(
            index = "1",
            title = "基础人设",
            body = DEFAULT_SYSTEM_PROMPT
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        PromptBlock(
            index = "2",
            title = "工具提示",
            body = "已注册工具自带的说明片段（如 use_skill 的技能调用指引），随技能增删动态变化。"
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        PromptBlock(
            index = "3",
            title = "记忆摘要",
            body = "最近 12 条长期记忆、上限 1200 字，每轮注入；写满后由新记忆顶替最早的一条。"
        )
    }
    AkSectionCard(
        title = "当前生效配置",
        description = "以上提示词由下面的端点与模型执行。"
    ) {
        Text(
            text = "端点：${config.baseUrl}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "模型：${config.model}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Key：${if (config.apiKey.isBlank()) "未配置" else "已配置（${config.apiKey.length} 字符）"}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 提示词拼装块：序号徽标 + 标题 + mono 正文（surface-1 内嵌块，maxHeight 140dp 可滚动）。 */
@Composable
private fun PromptBlock(index: String, title: String, body: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(AgentSpacing.space2))
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
                    text = body,
                    style = machineText,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
