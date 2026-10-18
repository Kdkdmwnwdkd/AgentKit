package com.example.agentkit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast

/**
 * 设置页（UX v1 §1.8）：外观 / 自动化 / 各处入口的收纳页。
 *
 * 设置只放「低频但要能找到」的东西：主题三选、自动化总开关、其他一级页面的入口。
 * 主题模式由 MainActivity 用 rememberSaveable 持有（进程内有效，不落盘——见 [ThemeMode] 注释），
 * 本页只负责把选择写回去。
 *
 * 用户反馈侧边栏拥挤（15 个入口），参照 RikkaHub 极简风：抽屉只留 4+1，
 * 其余 10 个入口全部收进本页的「工具与创作」「管理与数据」两组——路由不动，只搬家。
 *
 * @param themeMode 当前明暗模式。
 * @param onThemeModeChange 切换回调（写回宿主持有的状态）。
 * @param dynamicColor 功能五（P1-10）：Material You 动态取色开关（进程内有效）。
 * @param onDynamicColorChange 动态取色切换回调。
 * @param onNavigate 跳转到其他一级目的地。
 */
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
    onNavigate: (AppDest) -> Unit
) {
    val enabled by vm.automationEnabled.collectAsStateWithLifecycle()
    // 功能五（P1-10）：回答完成提醒（落盘开关，与主题偏好不同纪律——见 VM 注释）
    val completionNotify by vm.completionNotifyEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 13+ 通知运行时权限：开开关时按需申请，被拒则回滚 + Toast 说明
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.setCompletionNotifyEnabled(true)
        } else {
            Toast.makeText(context, "未授予通知权限，无法开启完成提醒", Toast.LENGTH_SHORT).show()
        }
    }

    // —— 功能五（P1-4b）：备份/恢复。SAF 文档选择器（零存储权限，用户自选落点）——
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { vm.exportBackupTo(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.restoreBackupFrom(it) } }
    // 恢复是破坏性操作：先弹确认，用户点头才开文件选择器（双闸门）
    var confirmRestore by remember { mutableStateOf(false) }
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(backupState) {
        when (val s = backupState) {
            is ChatViewModel.BackupUiState.Done -> {
                Toast.makeText(ctx, s.message, Toast.LENGTH_SHORT).show()
                vm.consumeBackupState()
            }
            is ChatViewModel.BackupUiState.Failed -> {
                Toast.makeText(ctx, s.reason, Toast.LENGTH_LONG).show()
                vm.consumeBackupState()
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // 设置页无右上动作：本页所有操作都在内容区，顶栏不放可能误触的全局按钮（§6.5.4）
        PageTopBar(title = AppDest.SETTINGS.title)
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
            // —— 外观：主题三选 ——
            AkSectionCard(
                title = "外观",
                icon = Icons.Outlined.Palette,
                description = "浅色「暖纸」为主设计套，深色为配套平行套；主题只影响本次使用，不写入磁盘。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    ThemeModeChip(
                        label = "跟随系统",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
                    )
                    ThemeModeChip(
                        label = "浅色",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { onThemeModeChange(ThemeMode.LIGHT) }
                    )
                    ThemeModeChip(
                        label = "深色",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { onThemeModeChange(ThemeMode.DARK) }
                    )
                }
                // 功能五（P1-10）：Material You 动态取色——Android 12+ 专属，
                // 低版本不摆开关（没有系统能力）；与主题三选同纪律：进程内有效不落盘
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkListItem(
                        icon = Icons.Outlined.Palette,
                        title = "动态取色",
                        description = if (dynamicColor) {
                            "已开启：界面跟随系统壁纸取色（Android 12+）"
                        } else {
                            "关闭：使用「暖纸」品牌色"
                        },
                        trailing = {
                            AkSwitch(
                                checked = dynamicColor,
                                onCheckedChange = onDynamicColorChange
                            )
                        }
                    )
                }
                // —— hotfix32：聊天背景图（真机反馈「我可以用我照片的壁纸当成壁纸」：
                // 动态取色只染控件色，这里给照片本体当对话页背景；SAF 选图 +
                // 持久化读授权，位图不占 App 存储，浓度可调）——
                Spacer(Modifier.height(AgentSpacing.space2))
                ChatBackgroundSection(vm)
            }

            // —— 功能五（P1-10）：提醒 ——
            AkSectionCard(
                title = "提醒",
                icon = Icons.Outlined.Notifications,
                description = "回答生成完毕时通知你；仅 App 退到后台时发送，在前台不打扰。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Notifications,
                    title = "回答完成提醒",
                    description = if (completionNotify) {
                        "已开启：回答完成且 App 在后台时发通知"
                    } else {
                        "关闭：静默完成，不打断"
                    },
                    trailing = {
                        AkSwitch(
                            checked = completionNotify,
                            onCheckedChange = { want ->
                                if (!want) {
                                    // 关开关不需要任何权限，直接落盘
                                    vm.setCompletionNotifyEnabled(false)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    // Android 13+ 未授权：走系统申请，结果回写开关
                                    notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    vm.setCompletionNotifyEnabled(true)
                                }
                            }
                        )
                    }
                )
            }

            // —— 自动化：总开关（与控制台同一开关，这里也留一份；权限中心入口留在这张卡里）——
            AkSectionCard(
                title = "自动化",
                icon = Icons.Outlined.TouchApp,
                description = "关闭后所有手机操作类工具立即失效，Agent 只能对话。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Security,
                    title = "自动化总开关",
                    description = if (enabled) "已开启，Agent 可操作手机" else "已关闭，Agent 只能对话",
                    trailing = {
                        AkSwitch(
                            checked = enabled,
                            onCheckedChange = { vm.setAutomationEnabled(it) }
                        )
                    }
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                // 权限中心入口：保留在自动化卡内（与管理组不重复——同一页面不放两个相同入口）
                AkListItem(
                    icon = Icons.Outlined.Shield,
                    title = "权限中心",
                    description = "无障碍服务、通知与使用情况三项授予状态",
                    onClick = { onNavigate(AppDest.PERMISSIONS) }
                )
            }

            // —— 工具与创作：从抽屉搬来的常用工具（用户反馈侧边栏拥挤，参照 RikkaHub 收纳）——
            AkSectionCard(
                title = "工具与创作",
                icon = Icons.Outlined.Apps,
                description = "模型、容器、编辑器与创作工坊等常用工具，抽屉里放不下的都在这。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.ViewInAr,
                    title = "模型配置",
                    description = "接口地址、API Key、模型名与系统提示词",
                    onClick = { onNavigate(AppDest.MODEL) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Widgets,
                    title = "容器",
                    description = "Termux 容器会话与命令执行状态",
                    onClick = { onNavigate(AppDest.CONTAINER) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Edit,
                    title = "编辑器",
                    description = "查看与修改工作区内的代码文件",
                    onClick = { onNavigate(AppDest.EDITOR) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "提示词库",
                    description = "输入栏闪电按钮唤起的快捷指令，可新建与编辑",
                    onClick = { onNavigate(AppDest.PROMPTS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Bookmark,
                    title = "收藏",
                    description = "对话里收藏的消息；删会话也不丢",
                    onClick = { onNavigate(AppDest.BOOKMARKS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Cloud,
                    title = "MCP",
                    description = "管理外部 MCP 服务器与工具接入",
                    onClick = { onNavigate(AppDest.MCP) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Palette,
                    title = "创作工坊",
                    description = "生图、视频等多模态创作与结果管理",
                    onClick = { onNavigate(AppDest.MEDIA) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Build,
                    title = "构建",
                    description = "推源码到 GitHub、CI 构建并装回本机",
                    onClick = { onNavigate(AppDest.BUILD) }
                )
            }

            // —— 任务模式（W-25）：先计划后执行 / 自动执行 ——
            val planMode by vm.planMode.collectAsStateWithLifecycle()
            AkSectionCard(
                title = "任务模式",
                icon = Icons.Outlined.AccountTree,
                description = "「先计划后执行」：多步任务先列计划，你批准后才动手；" +
                    "「自动执行」：直接做，只有破坏性操作才来问你。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.AccountTree,
                    title = if (planMode) "先计划后执行（PLAN）" else "自动执行（AUTO）",
                    description = if (planMode) {
                        "3 步以上任务会先弹计划卡片等你批准；被拒绝可按你的意见重规划"
                    } else {
                        "直接开始执行；批量删除/装应用等破坏性操作仍会先确认"
                    },
                    trailing = {
                        AkSwitch(
                            checked = planMode,
                            onCheckedChange = { vm.setPlanMode(it) }
                        )
                    }
                )
            }

            // —— 任务模型（W-26）：标题生成等轻量任务用便宜模型，省钱降延迟 ——
            val taskProfileId by vm.taskProfileId.collectAsStateWithLifecycle()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            AkSectionCard(
                title = "任务模型",
                icon = Icons.Outlined.Bolt,
                description = "标题生成等轻量任务单独用哪个模型。选便宜的能省 token、降延迟；" +
                    "默认跟随主配置。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Bolt,
                    title = "跟随主配置",
                    description = "与当前聊天模型相同（默认）",
                    selected = taskProfileId.isBlank(),
                    onClick = { vm.setTaskProfile("") }
                )
                profiles.forEach { profile ->
                    AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    AkListItem(
                        icon = Icons.Outlined.Bolt,
                        title = profile.name,
                        description = profile.config.model.ifBlank { "未命名模型" },
                        selected = taskProfileId == profile.id,
                        onClick = { vm.setTaskProfile(profile.id) }
                    )
                }
            }

            // —— 备用 Key（W-21）：主 Key 被限流时自动换下一把，不再直接断流 ——
            BackupKeySection(vm)

            // —— 管理与数据：出事时才用的排查型页面 + 关于（同样从抽屉搬来）——
            AkSectionCard(
                title = "管理与数据",
                icon = Icons.Outlined.Storage,
                description = "技能、日志、记忆与版本信息；权限中心在「自动化」卡里。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Extension,
                    title = "技能中心",
                    description = "已安装技能的启停、导入与信任等级",
                    onClick = { onNavigate(AppDest.SKILLS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Description,
                    title = "运行日志",
                    description = "LLM 请求、工具调用与错误记录",
                    onClick = { onNavigate(AppDest.LOGS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Storage,
                    title = "记忆库",
                    description = "Agent 的跨会话长期记忆，图谱式浏览",
                    onClick = { onNavigate(AppDest.MEMORY) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Info,
                    title = "关于",
                    description = "版本、设计规范与开源许可",
                    onClick = { onNavigate(AppDest.ABOUT) }
                )
            }

            // —— 备份与恢复：全局数据（会话 + 记忆 + 模型配置）打包到用户自选位置 ——
            AkSectionCard(
                title = "备份与恢复",
                icon = Icons.Outlined.Folder,
                description = "导出为 ZIP 备份件（含全部会话、记忆与模型配置）；恢复会用备份内容覆盖当前数据。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Save,
                    title = "导出备份",
                    description = "选择保存位置，生成 ZIP 备份文件",
                    onClick = { exportLauncher.launch("agentkit-backup.zip") }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Refresh,
                    title = "恢复备份",
                    description = "从 ZIP 备份文件恢复，覆盖当前全部数据",
                    onClick = { confirmRestore = true }
                )
            }
        }
    }

    if (confirmRestore) {
        AkConfirmDialog(
            title = "恢复备份",
            message = "恢复将用备份文件的内容覆盖本机全部会话、记忆与模型配置，当前数据不可找回。确定继续吗？",
            confirmText = "选择备份文件",
            danger = true,
            onConfirm = {
                confirmRestore = false
                importLauncher.launch(arrayOf("application/zip"))
            },
            onDismiss = { confirmRestore = false }
        )
    }
}

/** 主题三选 chip（§6.7 筛选 chip 语汇：选中 primary@12% + on-primary-container）。 */
/**
 * 备用 Key 管理（W-21）：一个 Key 被限流就自动换下一把。
 *
 * 界面纪律：列表只显示脱敏 Key（前 4 后 4），明文永不进 UI 状态也不上屏；
 * 「重置冷却」用于误判或用户已充值后立刻恢复全部 Key。
 */
@Composable
private fun BackupKeySection(vm: ChatViewModel) {
    val slots by vm.backupKeys.collectAsStateWithLifecycle()
    val summary by vm.keyPoolSummary.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.reloadBackupKeys() }

    AkSectionCard(
        title = "备用 Key",
        icon = Icons.Outlined.VpnKey,
        description = "同一个模型服务可以填多把 Key。主 Key 被限流（429）或额度耗尽时自动换下一把，" +
            "冷却期内不再浪费请求；只影响 OpenAI 兼容协议的服务。"
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AgentSpacing.space4)
        )
        Spacer(Modifier.height(AgentSpacing.space2))

        if (slots.isEmpty()) {
            Text(
                text = "还没有备用 Key。填一把后，主 Key 限流时会自动顶上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AgentSpacing.space4)
            )
        } else {
            slots.forEach { slot ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space1),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            slot.masked,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.removeBackupKey(slot.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除这把 Key")
                    }
                }
            }
        }

        Spacer(Modifier.height(AgentSpacing.space3))
        AkTextField(
            value = label,
            onValueChange = { label = it },
            label = "备注名（可选）",
            placeholder = "如：备用账号",
            modifier = Modifier.padding(horizontal = AgentSpacing.space4)
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        AkTextField(
            value = input,
            onValueChange = { input = it },
            label = "API Key",
            placeholder = "sk-...",
            modifier = Modifier.padding(horizontal = AgentSpacing.space4),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            AkTextButton(
                text = "添加备用 Key",
                icon = Icons.Outlined.Add,
                enabled = input.isNotBlank(),
                onClick = {
                    vm.addBackupKey(input, label)
                    input = ""
                    label = ""
                    Toast.makeText(ctx, "已添加，主 Key 限流时会自动切换", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    AkChip(text = label, selected = selected, onClick = onClick)
}

/**
 * hotfix32：聊天背景图分区（外观卡内嵌）——SAF 选图（持久化读授权）+
 * 「换一张 / 清除」+ 浓度滑杆（有图才显示）。
 *
 * 位图本体不落 App 存储（只存 content:// URI 字符串），换机/清缓存即失效，
 * 符合「照片是用户的、App 只借来显示」的语义；intent 必须带
 * FLAG_GRANT_PERSISTABLE_URI_PERMISSION，否则 takePersistableUriPermission
 * 会抛（临时授权在进程重启后失效）。
 */
@Composable
private fun ChatBackgroundSection(vm: ChatViewModel) {
    val context = LocalContext.current
    val bgUri by vm.chatBackgroundUri.collectAsStateWithLifecycle()
    val bgAlpha by vm.chatBackgroundAlpha.collectAsStateWithLifecycle()
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        // SAF 默认只授予临时读权限——不 take 的话重启后背景就丢了；
        // 失败（个别 ROM 策略）也不拦选图，临时授权本次会话仍可用
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        vm.setChatBackground(uri.toString())
    }
    Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
        AkListItem(
            icon = Icons.Outlined.Image,
            title = "背景图",
            description = if (bgUri != null) {
                // hotfix33：背景提升为全 App（抽屉/设置/模型/记忆等页面全部透出）
                "全 App 生效（含抽屉与各功能页），当前浓度 ${(bgAlpha * 100).toInt()}%"
            } else {
                "未设置：选一张照片当全 App 背景（不占 App 存储）"
            },
            trailing = if (bgUri != null) {
                {
                    AkTextButton(
                        text = "清除",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { vm.setChatBackground(null) }
                    )
                }
            } else null
        )
        AkOutlinedButton(
            text = if (bgUri != null) "换一张" else "选择图片",
            icon = Icons.Outlined.Image,
            onClick = {
                pickImage.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        // 持久化授权必须随 intent 一起请求（否则 take 会抛）
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                )
            }
        )
        if (bgUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "浓度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(AgentSpacing.space3))
                Slider(
                    value = bgAlpha,
                    onValueChange = { vm.setChatBackgroundAlpha(it) },
                    // hotfix33：上限放到 100%（原图）——真机反馈「60% 都不够清楚，要 100%；
                    // 为什么要设置 60%，我们自己不可以弄吗」：浓度是用户的选择，不是产品规定
                    valueRange = 0.05f..1.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = "${(bgAlpha * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
