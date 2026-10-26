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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.agentkit.core.agent.SurvivalCheckService
import com.example.agentkit.automation.adb.AdbCredentials
import com.example.agentkit.automation.adb.AdbSession
import com.example.agentkit.automation.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                .navigationBarsPadding()
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // —— 外观：主题三选 ——
            AkGroup(
                title = "外观",
                description = "主题只影响本次使用，不写入磁盘"
            ) {
                // 卡片自 v4 起无内边距（行靠 AkListItem 自带的 16dp），
                // 自定义控件必须由调用方补齐，否则会贴到卡片边缘
                Row(
                    modifier = Modifier.padding(horizontal = AgentSpacing.space4),
                    horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
                ) {
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
                    AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    AkListItem(
                        icon = null,
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
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                ChatBackgroundSection(vm)
            }

            // —— 功能五（P1-10）：提醒 ——
            AkGroup(
                title = "提醒",
                description = "回答生成完毕时通知；仅 App 在后台时发送"
            ) {
                AkListItem(
                    icon = null,
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
            AkGroup(
                title = "自动化",
                description = "关闭后所有手机操作类工具立即失效"
            ) {
                AkListItem(
                    icon = null,
                    title = "自动化总开关",
                    description = if (enabled) "已开启，Agent 可操作手机" else "已关闭，Agent 只能对话",
                    trailing = {
                        AkSwitch(
                            checked = enabled,
                            onCheckedChange = { vm.setAutomationEnabled(it) }
                        )
                    }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                // 权限中心入口：保留在自动化组内（与管理组不重复——同一页面不放两个相同入口）
                AkListItem(
                    icon = null,
                    title = "权限中心",
                    description = "无障碍服务、通知与使用情况三项授予状态",
                    onClick = { onNavigate(AppDest.PERMISSIONS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                // 存活自检：给没有电脑、拉不了 adb 的用户一个自测入口。
                // 判据写在通知里——锁屏回来数字还在涨就是没被冻结。
                AkListItem(
                    icon = null,
                    title = "后台存活自检",
                    description = "锁屏 5 分钟，通知里的秒数还在涨说明任务不会被冻结",
                    onClick = {
                        SurvivalCheckService.start(context)
                        Toast.makeText(
                            context,
                            "已开始：锁屏后回来看通知里的秒数",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }

            // —— W-51：调试通道（不用无障碍也能操作界面的两条路）——
            DebugChannelSection()

            // —— 工具与创作：从抽屉搬来的常用工具（用户反馈侧边栏拥挤，参照 RikkaHub 收纳）——
            AkGroup(
                title = "工具与创作",
                description = "模型、容器、编辑器与创作工坊等常用工具"
            ) {
                AkListItem(
                    icon = null,
                    title = "模型配置",
                    description = "接口地址、API Key、模型名与系统提示词",
                    onClick = { onNavigate(AppDest.MODEL) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "容器",
                    description = "Termux 容器会话与命令执行状态",
                    onClick = { onNavigate(AppDest.CONTAINER) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "编辑器",
                    description = "查看与修改工作区内的代码文件",
                    onClick = { onNavigate(AppDest.EDITOR) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "提示词库",
                    description = "输入栏闪电按钮唤起的快捷指令，可新建与编辑",
                    onClick = { onNavigate(AppDest.PROMPTS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "收藏",
                    description = "对话里收藏的消息；删会话也不丢",
                    onClick = { onNavigate(AppDest.BOOKMARKS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "MCP",
                    description = "管理外部 MCP 服务器与工具接入",
                    onClick = { onNavigate(AppDest.MCP) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "创作工坊",
                    description = "生图、视频等多模态创作与结果管理",
                    onClick = { onNavigate(AppDest.MEDIA) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "构建",
                    description = "推源码到 GitHub、CI 构建并装回本机",
                    onClick = { onNavigate(AppDest.BUILD) }
                )
            }

            // —— 任务模式（W-25）：先计划后执行 / 自动执行 ——
            val planMode by vm.planMode.collectAsStateWithLifecycle()
            AkGroup(
                title = "任务模式",
                description = "「先计划后执行」多步任务先列计划，批准后才动手；「自动执行」直接做"
            ) {
                AkListItem(
                    icon = null,
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
            AkGroup(
                title = "任务模型",
                description = "标题生成等轻量任务用哪个模型，默认跟随主配置"
            ) {
                AkListItem(
                    icon = null,
                    title = "跟随主配置",
                    description = "与当前聊天模型相同（默认）",
                    selected = taskProfileId.isBlank(),
                    onClick = { vm.setTaskProfile("") }
                )
                profiles.forEach { profile ->
                    AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    AkListItem(
                        icon = null,
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
            AkGroup(
                title = "管理与数据",
                description = "技能、日志、记忆与版本信息"
            ) {
                AkListItem(
                    icon = null,
                    title = "技能中心",
                    description = "已安装技能的启停、导入与信任等级",
                    onClick = { onNavigate(AppDest.SKILLS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "运行日志",
                    description = "LLM 请求、工具调用与错误记录",
                    onClick = { onNavigate(AppDest.LOGS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "记忆库",
                    description = "Agent 的跨会话长期记忆，图谱式浏览",
                    onClick = { onNavigate(AppDest.MEMORY) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "关于",
                    description = "版本、设计规范与开源许可",
                    onClick = { onNavigate(AppDest.ABOUT) }
                )
            }

            // —— 引导（P2，2026-10-21）：把「第一次怎么用」变成可重放的入口 ——
            // 引导只自动出现一次（权限引导 + 聚光灯各一个标记），跳过的人事后得能找回来，
            // 否则「跳过」等于「永远错过」。
            AkGroup(
                title = "引导",
                description = "第一次怎么用、每项权限能多做什么，随时可重看"
            ) {
                AkListItem(
                    icon = null,
                    title = "重看上手引导",
                    description = "回到对话页，重新指出输入框、发送键与抽屉的位置",
                    onClick = {
                        // 两件事一起做：清聚光灯标记 + 标记权限引导已看过。
                        // 只清前者的话，没用过权限引导的用户点「重看」会什么都不发生——
                        // 聚光灯的触发条件里有「权限引导已走过」，用户主动要求重看时不该被它挡住。
                        markGuideSkipped(ctx)
                        resetSpotlight(ctx)
                        onNavigate(AppDest.CHAT)
                    }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
                    title = "权限引导",
                    description = "五项设备能力逐项开启，说明「开了能做什么、不开会缺什么」",
                    onClick = { onNavigate(AppDest.PERMISSION_GUIDE) }
                )
            }

            // —— 备份与恢复：全局数据（会话 + 记忆 + 模型配置）打包到用户自选位置 ——
            AkGroup(
                title = "备份与恢复",
                description = "导出 ZIP（含会话、记忆与模型配置）。⚠️ 内含 API Key 请勿外传；" +
                    "不含 GitHub PAT。恢复会覆盖当前数据。"
            ) {
                AkListItem(
                    icon = null,
                    title = "导出备份",
                    description = "选择保存位置，生成 ZIP 备份文件",
                    onClick = { exportLauncher.launch("agentkit-backup.zip") }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = null,
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

    AkGroup(
        title = "备用 Key",
        description = "主 Key 被限流（429）或额度耗尽时自动换下一把，冷却期内不再浪费请求"
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
    Column {
        AkListItem(
            icon = null,
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
        // 按钮与滑杆不是列表行，自己补 16dp 边距（与行内文字左对齐）
        Column(
            modifier = Modifier.padding(
                start = AgentSpacing.space4,
                end = AgentSpacing.space4,
                top = AgentSpacing.space2,
                bottom = AgentSpacing.space2
            ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
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
}

/* ------------------------------------------------------------------ */
/* W-51：调试通道（Shizuku / 无线调试）                                  */
/* ------------------------------------------------------------------ */

/**
 * 「调试通道」分组：uid 2000 的两条路——不依赖无障碍服务也能操作界面。
 *
 * 为什么值得单列：
 * - 用户真实反馈是「**两个模式必须开一个，不开就全都用不了**」——根因是 `ui_*` 只认无障碍；
 * - Shizuku 要装第三方 App，无线调试只要开发者选项里开一次并配对，门槛更低；
 * - 两条通道可独立失效（Shizuku 被杀进程 / 无线调试端口变化），状态必须分别显示，
 *   否则用户无法判断该修哪条。
 *
 * 状态只在本页刷新（点测试/配对后 tick 自增），不做轮询：`Shizuku.pingBinder()` 是
 * 跨进程调用，`adb` 探测更贵，设置页常驻轮询不值当。
 */
@Composable
private fun DebugChannelSection() {
    var tick by remember { mutableStateOf(0) }
    var showPair by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shizukuStatus = remember(tick) { ShizukuShell.status() }
    val adbStatus = remember(tick) { AdbSession.status() }
    val adbError = remember(tick) { AdbSession.lastError() }
    val host = remember(tick) { AdbCredentials.host() }
    val port = remember(tick) { AdbCredentials.connectPort() }

    AkGroup(
        title = "调试通道",
        description = "不用无障碍也能操作界面；Shizuku 与无线调试任选其一"
    ) {
        AkListItem(
            icon = null,
            title = "Shizuku",
            description = when (shizukuStatus) {
                ShizukuShell.Status.READY ->
                    "已就绪：界面操作用 Shizuku（实时，无需 dump）"
                ShizukuShell.Status.NO_PERMISSION ->
                    "已安装但未授权：打开 Shizuku，在应用列表里授权 AgentKit"
                ShizukuShell.Status.NOT_RUNNING ->
                    "未运行：装好 Shizuku 并在其中「通过无线调试启动」"
            }
        )
        AkDivider(Modifier.padding(start = AgentSpacing.space4))
        AkListItem(
            icon = null,
            title = "无线调试（ADB）",
            description = when (adbStatus) {
                AdbSession.Status.CONNECTED ->
                    "已连接 $host:$port（以 shell 身份执行）"
                AdbSession.Status.NOT_CONNECTED ->
                    "已配对 $host:$port，用到时自动连"
                AdbSession.Status.NOT_CONFIGURED ->
                    adbError ?: "未配对：点右侧配对，输入手机上的配对码"
            },
            onClick = { showPair = true },
            trailing = {
                Text(
                    text = if (adbStatus == AdbSession.Status.NOT_CONFIGURED) "配对" else "重配",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
        if (adbStatus != AdbSession.Status.NOT_CONFIGURED) {
            AkDivider(Modifier.padding(start = AgentSpacing.space4))
            AkListItem(
                icon = null,
                title = "测试连接",
                description = if (testing) "正在执行 echo…" else "执行 echo 验证 shell 身份是否可用",
                onClick = {
                    if (testing) return@AkListItem
                    testing = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            AdbSession.exec("echo agentkit-debug-ok", timeoutSec = 12)
                        }
                        testing = false
                        tick++
                        val msg = if (r.ok) {
                            "连接正常（${r.elapsedMs}ms）"
                        } else {
                            r.startError ?: "退出码 ${r.exitCode}：${r.stderr.take(120)}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
            AkDivider(Modifier.padding(start = AgentSpacing.space4))
            AkListItem(
                icon = null,
                title = "断开并清除凭据",
                description = "换设备或怀疑凭据泄漏时用；清除后需重新配对",
                onClick = {
                    AdbSession.disconnect()
                    AdbCredentials.resetAll()
                    tick++
                    Toast.makeText(context, "已清除无线调试凭据", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showPair) {
        AdbPairDialog(
            onClose = {
                showPair = false
                tick++
            }
        )
    }
}

/**
 * 无线调试配对对话框。
 *
 * 三个数字缺一不可，且**两个端口极易混淆**（手机界面上一处显示配对端口、另一处显示
 * 连接端口），所以文案里把「在哪个弹窗/哪一行」写清楚——这是配对失败最常见的原因，
 * 比任何错误码都值得强调。
 */
@Composable
private fun AdbPairDialog(onClose: () -> Unit) {
    var host by remember { mutableStateOf(AdbCredentials.host().orEmpty()) }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connectPort by remember {
        mutableStateOf(AdbCredentials.connectPort().takeIf { it > 0 }?.toString().orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val canSubmit = host.isNotBlank() && pairPort.isNotBlank() &&
        connectPort.isNotBlank() && code.length == 6

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("无线调试配对", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "手机上：开发者选项 → 无线调试 → 打开开关 → 点「使用配对码配对设备」。\n" +
                        "弹窗里是 6 位配对码和【配对端口】；无线调试主页那行 IP:端口 是【连接端口】——" +
                        "两个端口不一样，填反了会提示解密失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("IP 地址") },
                    placeholder = { Text("192.168.1.8") },
                    singleLine = true,
                    enabled = !busy && !done,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                OutlinedTextField(
                    value = pairPort,
                    onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("配对端口（配对弹窗里）") },
                    placeholder = { Text("如 37123") },
                    singleLine = true,
                    enabled = !busy && !done,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("6 位配对码") },
                    placeholder = { Text("如 042618") },
                    singleLine = true,
                    enabled = !busy && !done,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("连接端口（主页 IP:端口）") },
                    placeholder = { Text("如 35123") },
                    singleLine = true,
                    enabled = !busy && !done,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                val msg = message
                if (msg != null) {
                    Spacer(Modifier.height(AgentSpacing.space3))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (done || canSubmit),
                onClick = {
                    if (done) {
                        onClose()
                    } else {
                        scope.launch {
                            busy = true
                            message = null
                            val r = withContext(Dispatchers.IO) {
                                AdbSession.pairAndConnect(
                                    host = host,
                                    pairPort = pairPort.toIntOrNull() ?: 0,
                                    code = code,
                                    connectPort = connectPort.toIntOrNull() ?: 0
                                )
                            }
                            busy = false
                            done = r.ok
                            message = r.message
                        }
                    }
                }
            ) {
                Text(if (busy) "配对中…" else if (done) "完成" else "配对")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy) { Text(if (done) "关闭" else "取消") }
        }
    )
}
