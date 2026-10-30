package com.example.agentkit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.example.agentkit.core.agent.SurvivalCheckService
import kotlinx.coroutines.launch

/**
 * 设置页（UX v1 §1.8）：自动化、设备能力、各处入口的收纳页。
 *
 * 设置只放「低频但要能找到」的东西：自动化总开关、通知、其他一级页面的入口。
 *
 * W-67 起**外观不再占这里的位置**：主题三选、强调色、动态取色、背景图整体搬到
 * [AppearanceScreen]（「壁纸与颜色」子页），本页只留一行入口 + 当前状态摘要。
 * 原因很直接——原来那一组从模式 chip 一路排到浓度滑杆，占掉整整一屏，
 * 把真正的设置项全推到折叠线以下；而调外观是低频动作，找设置是每次都要做的事。
 *
 * 用户反馈侧边栏拥挤（15 个入口），参照 RikkaHub 极简风：抽屉只留 4+1，
 * 其余 10 个入口全部收进本页——路由不动，只搬家。
 *
 * @param themeMode 当前明暗模式（只用于「壁纸与颜色」这一行的摘要，本页不再改它）。
 * @param dynamicColor 动态取色开关（同上，只作摘要）。
 * @param onNavigate 跳转到其他一级目的地。
 */
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    themeMode: ThemeMode,
    dynamicColor: Boolean = false,
    onNavigate: (AppDest) -> Unit
) {
    val enabled by vm.automationEnabled.collectAsStateWithLifecycle()
    // 功能五（P1-10）：回答完成提醒（落盘开关，与主题偏好不同纪律——见 VM 注释）
    val completionNotify by vm.completionNotifyEnabled.collectAsStateWithLifecycle()
    // W-67：外观入口的摘要要知道有没有设背景图（有没有 / 什么色，是这一行的全部信息量）
    val backgroundUri by vm.chatBackgroundUri.collectAsStateWithLifecycle()
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
                .verticalScroll(rememberPersistentScrollState())
                .navigationBarsPadding()
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // W-64：整页唯一的视觉重心——「你现在用的是什么模型」。
            // 之前这页从第一行开始就是一串等重的白卡，眼睛没有落点；
            // 模型又是这页几乎所有设置的前提（没配模型，下面全都没意义）。
            ModelHeroCard(vm, onNavigate = onNavigate)

            // —— W-68：轻量任务模型紧跟 hero 卡 ——
            // 它是「当前模型」的下一个问题（主模型用它，跑标题用哪个），两件事挨着才连得上。
            val taskProfileId by vm.taskProfileId.collectAsStateWithLifecycle()
            val profiles by vm.profiles.collectAsStateWithLifecycle()
            AkGroup(
                title = "轻量任务",
                description = "标题生成等轻量任务用哪个模型，默认跟随主配置"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Cloud,
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

            // —— W-68：小组收拢成「通用」——
            // 之前外观、通知、任务执行各占一张卡，每张只有一行。卡片一多，页面读起来
            // 是「一串分隔条」而不是「几件事」；而这三项本来就是同一类东西——App 怎么表现、
            // 会不会打断我、Agent 怎么做活。合成一组后，设置页从 10 张卡降到 6 张。
            val accentLabel = AppearanceState.accentHex?.let { hex ->
                AccentPreset.entries
                    .firstOrNull { it.color(false).toHexString().equals(hex, ignoreCase = true) }
                    ?.label ?: hex
            } ?: "默认色"
            val modeLabel = when (themeMode) {
                ThemeMode.SYSTEM -> "跟随系统"
                ThemeMode.LIGHT -> "浅色"
                ThemeMode.DARK -> "深色"
            }
            val planMode by vm.planMode.collectAsStateWithLifecycle()
            AkGroup(
                title = "通用",
                description = "界面、通知与执行方式"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Palette,
                    title = "壁纸与颜色",
                    description = buildString {
                        append(modeLabel)
                        append(" · ")
                        append(accentLabel)
                        if (dynamicColor) append(" · 动态取色")
                        if (backgroundUri != null) append(" · 有背景图")
                    },
                    onClick = { onNavigate(AppDest.APPEARANCE) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
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
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.AccountTree,
                    title = "先计划后执行",
                    description = if (planMode) {
                        "已开启：3 步以上任务先弹计划卡片，批准后才动手"
                    } else {
                        "已关闭：直接执行；删除、装应用等破坏性操作仍会先确认"
                    },
                    trailing = {
                        AkSwitch(
                            checked = planMode,
                            onCheckedChange = { vm.setPlanMode(it) }
                        )
                    }
                )
            }

            // —— W-103：工具范围 ——
            //
            // 「粘性激活」的设计是「被叫到就开、一直开着」，但原先只有「新建会话」能清空。
            // 用户在长会话里用完 shell，那 4 个工具（2676 字符 ≈ 1300 token）就跟着每轮
            // 请求一直发到会话结束——这正是这轮改造想省的钱，却没有开关可关。
            //
            // 这里只提供「关」，不提供「开」：开组是模型自己的判断（`__use_tools`），
            // 用户手动去开一个自己用不上的组，只会白付 token、还会占掉淘汰名额。
            val toolGroups by vm.toolGroupsUi.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.refreshToolGroups() }
            val activeRows = toolGroups.filter { it.active }
            val inactiveCount = toolGroups.count { !it.active }
            AkGroup(
                title = "工具范围",
                description = "每轮请求都会带上这些工具的定义。用不到的关掉，就别再为它付 token"
            ) {
                activeRows.forEachIndexed { i, row ->
                    if (i > 0) AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    AkListItem(
                        icon = null,
                        title = row.name,
                        description = row.note + " · " + row.count + " 个工具",
                        trailing = {
                            if (row.pinned) {
                                Text(
                                    text = "常驻",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                AkTextButton(
                                    text = "关闭",
                                    onClick = { vm.dropToolGroup(row.name) },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
                if (inactiveCount > 0) {
                    if (activeRows.isNotEmpty()) AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    // 不把 12 个「关着的组」逐行列出来：设置页要能一屏扫完，
                    // 而「未启用」这个状态没有信息量——真正需要它的是模型，不是用户。
                    AkListItem(
                        icon = null,
                        title = "另有 $inactiveCount 个组未启用",
                        description = "模型需要时会自己开（`__use_tools`）；关掉的组不会自己回来",
                        enabled = false
                    )
                }
            }

            // —— 设备能力：自动化总开关 + 权限入口 + 存活自检 ——
            // W-68：Shizuku / 无线调试原来单独一张「调试通道」卡，和本组的「权限中心」说的是
            // 同一件事（拿 uid 2000 的权限），现在两条调试通道都收进权限中心页。
            AkGroup(
                title = "设备能力",
                description = "关掉后 Agent 只能对话，不能操作手机"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Bolt,
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
                // 权限中心：无障碍、通知、使用情况，以及 Shizuku / 无线调试两条调试通道
                AkListItem(
                    icon = Icons.Outlined.Shield,
                    title = "权限中心",
                    description = "无障碍、通知、使用情况与调试通道（Shizuku / 无线调试）",
                    onClick = { onNavigate(AppDest.PERMISSIONS) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                // 存活自检：给没有电脑、拉不了 adb 的用户一个自测入口。
                // 判据写在通知里——锁屏回来数字还在涨就是没被冻结。
                AkListItem(
                    icon = Icons.Outlined.Refresh,
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

            // —— 工具：从抽屉搬来的常用入口（用户反馈侧边栏拥挤，参照 RikkaHub 收纳）——
            AkGroup(
                title = "工具",
                description = "日常用的入口都在这里"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Cloud,
                    title = "模型配置",
                    description = "接口地址、API Key、模型名与系统提示词",
                    onClick = { onNavigate(AppDest.MODEL) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Apps,
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
                    icon = Icons.Outlined.Extension,
                    title = "MCP",
                    description = "管理外部 MCP 服务器与工具接入",
                    onClick = { onNavigate(AppDest.MCP) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Image,
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

            // —— W-68：数据与备份合成一组 ——
            // 两件事都在回答「这台手机上存了什么、怎么带走」：技能/日志/记忆是本机数据，
            // 导出/恢复是它的迁移方式。原来分成两张卡，中间隔着一整组「帮助」，找备份
            // 得往下翻两屏。
            AkGroup(
                title = "数据",
                description = "技能、日志、记忆与备份；导出含 API Key，请勿外传"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Extension,
                    title = "技能中心",
                    description = "启停、导入与信任等级",
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
                    icon = Icons.Outlined.Folder,
                    title = "记忆库",
                    description = "跨会话长期记忆",
                    onClick = { onNavigate(AppDest.MEMORY) }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Save,
                    title = "导出备份",
                    description = "选择保存位置，生成 ZIP（含会话、记忆与模型配置）",
                    onClick = { exportLauncher.launch("beichen-backup.zip") }
                )
                AkDivider(Modifier.padding(start = AgentSpacing.space4))
                AkListItem(
                    icon = Icons.Outlined.Folder,
                    title = "恢复备份",
                    description = "从 ZIP 备份恢复，覆盖当前全部数据",
                    onClick = { confirmRestore = true }
                )
            }

            // —— W-68：帮助与关于合成一组 ——
            // 两行都在回答「不会用 / 想了解这个 App 时去哪」。关于页里第一张卡就是应用介绍，
            // 紧接着是「权限怎么给」，点一次两件事都看得到。
            AkGroup(
                title = "帮助",
                description = "上手引导、产品介绍与权限怎么给"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Refresh,
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
                    icon = Icons.Outlined.Info,
                    title = "关于与帮助",
                    description = "产品介绍 · 权限怎么给 · 版本与开源许可",
                    onClick = { onNavigate(AppDest.ABOUT) }
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

/**
 * 顶部 hero 卡（W-64）：当前模型 + 一个「更换」入口。
 *
 * 为什么值得单独占一块：这是全页唯一「一眼要知道」的状态。其余设置都是调一次就不动的，
 * 只有模型会被频繁切换（限流了换一个、任务重了换贵的）。它放在最上面，同时充当整页的
 * 视觉锚点——下面那些卡片才有「次要信息」的层次。
 */
@Composable
private fun ModelHeroCard(vm: ChatViewModel, onNavigate: (AppDest) -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeProfileId.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId }
    val profileName = active?.name?.takeIf { it.isNotBlank() }
    val modelName = active?.config?.model?.takeIf { it.isNotBlank() } ?: profileName

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "当前模型",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = modelName ?: "还没配置模型",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (modelName != null && profileName != null && profileName != modelName) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }
            Spacer(Modifier.height(AgentSpacing.space4))
            AkPrimaryButton(
                text = if (modelName == null) "去配置模型" else "更换模型",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(AppDest.MODEL) }
            )
        }
    }
}

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
                // 原来 enabled = input.isNotBlank()：没填 Key 时按钮是灰的、点下去毫无反应。
                // 真机反馈「这个功能都没用」——用户分不清是「还没填」还是「功能坏了」。
                // 现在按钮常亮，点了再告诉他缺什么：禁用态不该是唯一的解释渠道。
                enabled = true,
                onClick = {
                    if (input.isBlank()) {
                        Toast.makeText(
                            ctx,
                            "先把 API Key 填进上面的框（sk- 开头那串），再点添加",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@AkTextButton
                    }
                    vm.addBackupKey(input, label)
                    input = ""
                    label = ""
                    Toast.makeText(ctx, "已添加，主 Key 限流时会自动切换", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
