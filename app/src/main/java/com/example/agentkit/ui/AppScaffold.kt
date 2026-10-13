package com.example.agentkit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 抽屉 9 个一级目的地（视觉规范 §6.5.2「入口清单」完整覆盖）。
 *
 * @param route 路由键（UX v1 §1.7 路由表直译，用于 rememberSaveable 持久化）。
 * @param label 抽屉菜单项文案（单行中文，§6.5.2）。
 * @param title 顶栏标题（§6.5.4；「模型」项的页面标题是「模型配置」）。
 * @param icon 24dp 线性图标（1.5dp stroke 语汇，禁 emoji）。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val title: String,
    val icon: ImageVector
) {
    CHAT("chat", "对话", "对话", Icons.Outlined.Chat),
    MODEL("model", "模型", "模型配置", Icons.Outlined.ViewInAr),
    CONSOLE("console", "控制台", "控制台", Icons.Outlined.Dashboard),
    TERMINAL("terminal", "终端", "终端", Icons.Outlined.Terminal),
    CONTAINER("container", "容器", "容器", Icons.Outlined.ViewInAr),
    PERMISSIONS("permissions", "权限中心", "权限中心", Icons.Outlined.Shield),
    SKILLS("skills", "技能中心", "技能中心", Icons.Outlined.Extension),
    LOGS("logs", "运行日志", "运行日志", Icons.Outlined.Description),
    MEMORY("memory", "记忆库", "记忆库", Icons.Outlined.Storage),
    SETTINGS("settings", "设置", "设置", Icons.Outlined.Settings),
    ABOUT("about", "关于", "关于", Icons.Outlined.Info);

    companion object {
        fun fromRoute(route: String): AppDest = values().firstOrNull { it.route == route } ?: CHAT
    }
}

/** 抽屉「主要」组：天天用（§6.5.2）。 */
private val PrimaryGroup = listOf(
    AppDest.CHAT, AppDest.MODEL, AppDest.CONSOLE, AppDest.TERMINAL, AppDest.CONTAINER
)

/** 抽屉「管理」组：出事时用（§6.5.2）。 */
private val ManageGroup = listOf(AppDest.PERMISSIONS, AppDest.SKILLS, AppDest.LOGS, AppDest.MEMORY)

/** 抽屉底部位：装一次看一次，语义自知不配分组标题（§6.5.2）。 */
private val BottomGroup = listOf(AppDest.SETTINGS, AppDest.ABOUT)

/**
 * 待授权项数：无障碍服务 / 屏幕捕获 / 通知访问 / 使用情况访问 四项中未就绪的数量。
 *
 * 抽屉红点、抽屉就绪文字、权限中心页三处共用同一函数——同一状态源必须同一计数，
 * 否则用户会看到「抽屉说 3 项、页面说 2 项」的信任裂缝（§6.5.2「与权限中心红点同源」）。
 */
internal fun pendingGrantCount(
    serviceConnected: Boolean,
    capturing: Boolean,
    notificationGranted: Boolean,
    usageGranted: Boolean
): Int = listOf(serviceConnected, capturing, notificationGranted, usageGranted).count { !it }

/**
 * 日志行是否为 ERROR 级。日志格式 `HH:mm:ss.SSS LEVEL [tag] message`（core/AgentLogger.format），
 * 级别固定位于第二段，按段匹配避免消息正文里出现 "ERROR" 造成误判。
 */
internal fun String.isErrorLogLine(): Boolean = split(' ').getOrNull(1) == "ERROR"

/**
 * App 级导航宿主（视觉规范 §6.5）：抽屉是唯一 App 级导航，承载 9 个一级目的地。
 *
 * 职责边界：本文件只做「路由 + 抽屉 + 顶栏装配 + 进入页面时的数据刷新」，
 * 页面视觉由各屏幕文件自持（共享基件见 Components.kt，页面用 [PageTopBar] 画顶栏）。
 *
 * 双模态（UX v1 §4.2 断点 840dp）：
 * - 宽屏：抽屉常驻 280dp 无 scrim 无滑入，内容区 = min(屏宽 − 280dp, 680dp) 居中；
 * - 窄屏：overlay 抽屉 clamp(280dp, 82vw, 320dp)，250ms motionBase 滑入 / 200ms motionDialog 滑出，
 *   scrim 取 LocalAgentKitColors.current.scrim，点击 scrim / 选中一项 / 返回键关闭，
 *   左缘 24dp 热区支持跟手右滑（超过 1/3 吸附，150ms motionFast）。
 *
 * @param themeMode 明暗模式（设置页三选，由 MainActivity 用 rememberSaveable 持有）。
 */
@Composable
fun AppScaffold(
    vm: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    // 路由态：抽屉 9 入口的唯一真相源；页面切换无转场动画（v3 无此 token，§6.5.2）
    var route by rememberSaveable { mutableStateOf(AppDest.CHAT.route) }
    val dest = AppDest.fromRoute(route)

    // —— 角标 / 就绪文字数据源（与权限中心页、日志页同源）——
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val pendingGrants = pendingGrantCount(
        serviceConnected, capturing, notificationGranted, usageGranted
    )

    // 未读 ERROR：进入日志页即标记已读（读后归零即隐藏，§6.5.2）
    var logsSeenCount by rememberSaveable { mutableStateOf(0) }
    val unreadErrors = remember(logs, logsSeenCount) {
        logs.take((logs.size - logsSeenCount).coerceAtLeast(0)).count { it.isErrorLogLine() }
    }

    // 进入对应页面时刷新数据：权限/日志/记忆都是「打开就要看到最新」的排查型页面
    LaunchedEffect(dest) {
        when (dest) {
            AppDest.CONSOLE, AppDest.PERMISSIONS -> vm.refreshGrants()
            AppDest.LOGS -> {
                vm.refreshLogs()
                logsSeenCount = vm.logs.value.size
            }

            AppDest.MEMORY -> vm.refreshMemories()
            else -> Unit
        }
    }

    // —— 抽屉开合进度（0f..1f）——
    // 拖拽中 = 手势进度（snap 跟手）；松手/点击 = token 动画（openTarget 决定去向）
    var openTarget by remember { mutableStateOf(false) }
    var gestureProgress by remember { mutableStateOf<Float?>(null) }
    var settleSpec by remember { mutableStateOf<AnimationSpec<Float>>(AkMotionBase) }
    val progress by animateFloatAsState(
        targetValue = gestureProgress ?: if (openTarget) 1f else 0f,
        animationSpec = if (gestureProgress != null) snap() else settleSpec,
        label = "drawerProgress"
    )

    val ak = LocalAkColors
    val view = LocalView.current
    val density = LocalDensity.current
    val hamburgerFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }

    val openDrawer: () -> Unit = {
        openTarget = true
        settleSpec = AkMotionBase // 汉堡开启：250ms motionBase 滑入
    }
    val closeDrawer: () -> Unit = {
        openTarget = false
        settleSpec = AkMotionDialog // 关闭：200ms motionDialog 滑出
    }

    // 抽屉打开：焦点移入第一项 + announce「已打开」；关闭：焦点返回汉堡 + announce「已关闭」（§6.5.5）
    var drawerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openTarget) {
        if (openTarget) {
            // 等一帧让抽屉真正组合出来（progress 越过 0 阈值），否则 FocusRequester 尚未初始化
            withFrameNanos { }
            runCatching { firstItemFocus.requestFocus() }
            view.announceForAccessibility("导航抽屉已打开")
        } else if (drawerWasOpen) {
            runCatching { hamburgerFocus.requestFocus() }
            view.announceForAccessibility("导航抽屉已关闭")
        }
        drawerWasOpen = openTarget
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        // clamp(280dp, 82vw, 320dp)：280 保触控下限，320 保「抽屉感」上限（§6.5.2）
        val drawerWidth = minOf(320.dp, maxOf(280.dp, maxWidth * 0.82f))
        val drawerWidthPx = with(density) { drawerWidth.toPx() }

        val onSelect: (AppDest) -> Unit = { target ->
            // 点当前所在页项 = 仅关闭抽屉（不重复导航，§6.5.2）
            if (target != dest) route = target.route
            if (!wide) closeDrawer()
        }

        // 抽屉打开器注入页面：页面只消费本地值，不感知导航宿主细节（宽屏常驻态为空操作）
        CompositionLocalProvider(
            LocalDrawerOpener provides { if (!wide && !openTarget) openDrawer() },
            LocalPermanentDrawer provides wide,
            LocalHamburgerFocus provides if (wide) null else hamburgerFocus
        ) {
            if (wide) {
                // —— 宽屏：常驻抽屉 280dp（无 scrim 无滑入）+ 内容限宽 680dp 居中 ——
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        color = ak.surface1,
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                    ) {
                        DrawerContent(
                            dest = dest,
                            serviceConnected = serviceConnected,
                            pendingGrants = pendingGrants,
                            unreadErrors = unreadErrors,
                            onSelect = onSelect,
                            firstItemFocus = null
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .statusBarsPadding(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 680.dp)
                                .fillMaxSize()
                        ) {
                            ScreenHost(dest, vm, themeMode, onThemeModeChange, onSelect, unreadErrors)
                        }
                    }
                }
            } else {
                // —— 窄屏：overlay 抽屉 + scrim + 左缘 24dp 右滑热区 ——
                Box(Modifier.fillMaxSize()) {
                    // 内容层：状态栏 inset 由宿主统一处理，页面只画 56dp 透明顶栏 + 内容
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        ScreenHost(dest, vm, themeMode, onThemeModeChange, onSelect, unreadErrors)
                    }

                    // 左缘 24dp 热区：跟手拖拽（抽屉位移与 scrim 透明度随手势进度）
                    if (progress < 1f) {
                        val dragState = rememberDraggableState { delta ->
                            gestureProgress =
                                ((gestureProgress ?: 0f) + delta / drawerWidthPx).coerceIn(0f, 1f)
                        }
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(24.dp)
                                .fillMaxHeight()
                                .draggable(
                                    state = dragState,
                                    orientation = Orientation.Horizontal,
                                    onDragStopped = {
                                        // 超过抽屉宽 1/3 吸附开启，否则收回；吸附过渡 150ms = motionFast（§6.5.2）
                                        val shouldOpen = (gestureProgress ?: 0f) > 1f / 3f
                                        gestureProgress = null
                                        openTarget = shouldOpen
                                        settleSpec = AkMotionFast
                                    }
                                )
                        )
                    }

                    // scrim：点击关闭；背后页面内容不位移（省算力，§6.5.2）
                    if (progress > 0.02f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .alpha(progress)
                                .background(ak.scrim)
                                .clickable { closeDrawer() }
                        )
                    }

                    // 抽屉本体：滑入 250ms + fade；右缘 1dp outline 装饰线
                    if (progress > 0.001f) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(drawerWidth)
                                .offset(x = drawerWidth * (progress - 1f))
                                .alpha(progress)
                                .background(ak.surface1)
                        ) {
                            DrawerContent(
                                dest = dest,
                                serviceConnected = serviceConnected,
                                pendingGrants = pendingGrants,
                                unreadErrors = unreadErrors,
                                onSelect = onSelect,
                                firstItemFocus = firstItemFocus
                            )
                            // 右缘 1dp outline 装饰线（全 App 唯一抽屉描边，规范明文；其余场景 0 描边）
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                        }
                    }
                }

                // 返回键关闭抽屉（§6.5.2）；二级返回语义由各页自持
                BackHandler(enabled = openTarget) { closeDrawer() }
            }
        }
    }
}

/**
 * 路由分发：9 个一级目的地 → 各屏幕文件。
 * 页面切换无转场动画（v3 无此 token）；顶栏由各页用 [PageTopBar] 自渲染（§6.5.1 骨架统一）。
 */
@Composable
private fun ScreenHost(
    dest: AppDest,
    vm: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (AppDest) -> Unit,
    unreadErrors: Int
) {
    when (dest) {
        AppDest.CHAT -> ChatScreen(
            vm = vm,
            onOpenModel = { onNavigate(AppDest.MODEL) },
            onOpenPermissions = { onNavigate(AppDest.PERMISSIONS) }
        )
        AppDest.MODEL -> ModelConfigScreen(vm) { onNavigate(AppDest.CHAT) }
        AppDest.CONSOLE -> ConsoleScreen(vm, unreadErrors, onNavigate)
        AppDest.TERMINAL -> TerminalScreen()
        AppDest.CONTAINER -> ContainerScreen()
        AppDest.PERMISSIONS -> PermissionsScreen(vm)
        AppDest.SKILLS -> SkillsScreen(vm)
        AppDest.LOGS -> LogsScreen(vm)
        AppDest.MEMORY -> MemoryScreen(vm)
        AppDest.SETTINGS -> SettingsScreen(vm, themeMode, onThemeModeChange, onNavigate)
        AppDest.ABOUT -> AboutScreen()
    }
}

/**
 * 页面顶栏便捷包装：自动接上抽屉打开器（LocalDrawerOpener），
 * 页面只声明「标题 + 右上 0–1 动作」，骨架细节（56dp / 透明 / 48dp 触控）全部收口在 AkTopBar。
 */
@Composable
internal fun PageTopBar(
    title: String,
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {}
) {
    AkTopBar(
        title = title,
        onOpenDrawer = LocalDrawerOpener.current,
        actionIcon = actionIcon,
        actionDescription = actionDescription,
        actionEnabled = actionEnabled,
        onAction = onAction
    )
}

/**
 * 抽屉内容：头部（应用标记 + 就绪文字三态）+ 两分组 + 底部位（§6.5.2）。
 *
 * @param firstItemFocus 焦点请求器：抽屉打开时移入第一项（§6.5.5）；宽屏常驻态传 null。
 */
@Composable
private fun DrawerContent(
    dest: AppDest,
    serviceConnected: Boolean,
    pendingGrants: Int,
    unreadErrors: Int,
    onSelect: (AppDest) -> Unit,
    firstItemFocus: FocusRequester?
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // 内容高 <740dp 的老旧小屏允许抽屉内部滚动：不加折叠、不加「查看更多」（§6.5.2）
            .verticalScroll(rememberScrollState())
    ) {
        DrawerHeader(
            serviceConnected = serviceConnected,
            pendingGrants = pendingGrants,
            onReadyClick = { onSelect(AppDest.PERMISSIONS) }
        )
        Spacer(Modifier.height(AgentSpacing.space5))

        DrawerGroupTitle("主要")
        PrimaryGroup.forEach { item ->
            DrawerItem(
                dest = item,
                isSelected = dest == item,
                badge = null,
                talkBack = item.label,
                onClick = { onSelect(item) },
                modifier = if (item == PrimaryGroup.first() && firstItemFocus != null)
                    Modifier.focusRequester(firstItemFocus) else Modifier
            )
        }

        Spacer(Modifier.height(AgentSpacing.space5))
        DrawerGroupTitle("管理")
        ManageGroup.forEach { item ->
            val badge: (@Composable () -> Unit)? = when (item) {
                // 角标规则：红点 = 待办（静态不脉冲）/ 计数 = 存量 / 零不显示（§6.5.2）
                AppDest.PERMISSIONS -> if (pendingGrants > 0) {
                    { AkStatusDot(MaterialTheme.colorScheme.error, 8.dp) }
                } else null

                AppDest.LOGS -> if (unreadErrors > 0) {
                    {
                        Text(
                            text = "$unreadErrors",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else null

                else -> null
            }
            // 红点/计数语义并入父项 TalkBack，不单独聚焦（§6.5.2）
            val talkBack = when (item) {
                AppDest.PERMISSIONS ->
                    if (pendingGrants > 0) "${item.label}，${pendingGrants} 项待授权" else item.label

                AppDest.LOGS ->
                    if (unreadErrors > 0) "${item.label}，${unreadErrors} 条未读错误" else item.label

                else -> item.label
            }
            DrawerItem(
                dest = item,
                isSelected = dest == item,
                badge = badge,
                talkBack = talkBack,
                onClick = { onSelect(item) }
            )
        }

        // 底部位：与上方间距 20dp，不加分隔线、不配分组标题（§6.5.2）
        Spacer(Modifier.height(AgentSpacing.space5))
        BottomGroup.forEach { item ->
            DrawerItem(
                dest = item,
                isSelected = dest == item,
                badge = null,
                talkBack = item.label,
                onClick = { onSelect(item) }
            )
        }
        Spacer(Modifier.height(AgentSpacing.space3))
    }
}

/** 抽屉头部：40dp 圆角应用标记 +「AgentKit」+ 就绪文字三态（§6.5.2）。 */
@Composable
private fun DrawerHeader(
    serviceConnected: Boolean,
    pendingGrants: Int,
    onReadyClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AgentSpacing.space5),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(AgentSpacing.space3))
        Column {
            Text(
                text = "AgentKit",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AgentSpacing.space1))
            // 就绪文字三态与权限中心页共用实现（§6.5.2）：同一状态源、同一措辞
            AkReadyTextRow(
                serviceConnected = serviceConnected,
                pendingGrants = pendingGrants,
                onClick = onReadyClick
            )
        }
    }
}

/** 抽屉分组标题：单行中文 13sp w500 on-surface-var，无英文微标签（§6.5.2）。 */
@Composable
private fun DrawerGroupTitle(text: String) {
    Text(
        text = text,
        // Type.kt 九 slot 无 13sp 级：复用 bodySmall(13sp) 提字重，保持 noFontPadding 一致
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = AgentSpacing.space5, bottom = AgentSpacing.space2)
    )
}

/**
 * 抽屉菜单项：高 52dp · radius 14dp · 项间距 4dp · 左右边距 8dp · 无 chevron（§6.5.2）。
 *
 * 选中态三件套：bg primary@12% + 图标与文字 primary + 左缘 3dp primary 圆角竖条（贴抽屉左缘、与项等高）。
 * 按压 = surface-2 底（§7-M3 统一按压语言）；整行为触控目标，TalkBack 合并为单节点。
 */
@Composable
private fun DrawerItem(
    dest: AppDest,
    isSelected: Boolean,
    badge: (@Composable () -> Unit)?,
    talkBack: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ak = LocalAkColors
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space2)
            .heightIn(min = AgentDimens.drawerItemHeight)
    ) {
        // 选中三件套之③：左缘 3dp primary 圆角竖条
        if (isSelected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val bg by animateColorAsState(
            targetValue = when {
                isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                pressed -> ak.surface2
                else -> Color.Transparent
            },
            animationSpec = AkMotionFastColor
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
                // 合并为单节点：一次聚焦读全（图标/文字 decorative），selected 态读作「已选中」
                .semantics(mergeDescendants = true) {
                    contentDescription = talkBack
                    selected = isSelected
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = dest.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (badge != null) {
                Spacer(Modifier.width(AgentSpacing.space2))
                badge()
            }
        }
    }
}
