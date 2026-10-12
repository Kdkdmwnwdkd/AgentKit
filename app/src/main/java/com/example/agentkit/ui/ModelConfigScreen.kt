package com.example.agentkit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.core.ConfigSanitizer
import com.example.agentkit.core.DEFAULT_SYSTEM_PROMPT

/**
 * 模型配置页（视觉规范 §6.8 + §6.5.3 页面内 Tab）。
 *
 * 一级页面：顶栏「模型配置」无右上动作（保存有毁损性，不进顶栏防误触，§6.5.4）；
 * 内容区顶部为页面内 Tab「模型 / 提示词」——内容分区，非 App 导航。
 *
 * @param onBack 「取消 / 保存」后回对话页（抽屉路由由宿主持有）。
 */
@Composable
fun ModelConfigScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    // 以已落盘配置为基准：config 变化（保存成功）即重置编辑态，避免二次进入残留旧值
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var providerId by remember(config) { mutableStateOf(config.providerId) }
    var tab by rememberSaveable { mutableStateOf(0) }

    val dirty = baseUrl != config.baseUrl || apiKey != config.apiKey ||
        model != config.model || providerId != config.providerId

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(title = AppDest.MODEL.title)
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
                0 -> ModelTab(
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

                else -> PromptTab(config = config)
            }
        }
    }
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
        else -> "gpt-4o-mini"
    }
}

/**
 * 页面内 Tab（§6.5.3，全 App 仅此一处）：文字 labelLarge，未选 on-surface-var w500、
 * 选中 primary w600 + 底部 2dp primary 下划线（宽随文字）；仅点选，变色 150ms，无药丸无背景。
 */
@Composable
private fun ModelPageTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("模型", "提示词")
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
                    )
                    // 下划线宽随文字：列宽取子节点固有最小宽（= 文字宽）
                    .width(IntrinsicSize.Min),
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
 * 供应商选择器（§6.8 模型 Tab 顶部）：三枚等高胶囊（48dp 触控，§6.0）。
 *
 * 选中：primary 底 + on-primary 文字 w600；未选：透明底 + 1.5dp outline 描边 +
 * on-surface-variant 文字 w500。背景色 150ms 过渡（AkMotionFastColor），按压涟漪
 * 走 Surface 原生 onClick——与全 App 交互语言一致，无自绘按压态。
 */
@Composable
private fun ProviderSelector(selected: String, onSelect: (String) -> Unit) {
    val providers = listOf(
        "openai" to "OpenAI 兼容",
        "claude" to "Claude",
        "gemini" to "Gemini"
    )
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
    val ctx = LocalContext.current
    var keyRevealed by rememberSaveable { mutableStateOf(false) }

    val canTest = baseUrl.isNotBlank() && apiKey.isNotBlank()

    AkSectionCard(
        title = "模型接入",
        description = "三家供应商可切换：OpenAI 兼容端点（含 vLLM / Ollama / OneAPI）、" +
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
        AkTextField(
            value = model,
            onValueChange = onModel,
            label = "模型名",
            placeholder = ProviderDefaults.defaultModel(providerId),
            machine = true
        )
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
