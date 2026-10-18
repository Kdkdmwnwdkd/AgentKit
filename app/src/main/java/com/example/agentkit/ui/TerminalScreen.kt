package com.example.agentkit.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.workspace.WorkspaceStore
import com.example.agentkit.terminal.COLOR_DEFAULT_BG
import com.example.agentkit.terminal.COLOR_DEFAULT_FG
import com.example.agentkit.terminal.TerminalJni
import com.example.agentkit.terminal.TerminalSession
import com.example.agentkit.terminal.TerminalSnapshot
import com.example.agentkit.terminal.TerminalRow
import java.io.File

/** 终端输出字号（等宽）。列对齐由 Emulator 的 wcwidth 保证，UI 只管按行渲染。 */
private val TermFontSize: TextUnit = 12.sp

/** 终端输出行高（固定绝对值；includeFontPadding=false 保证每行严格同高）。 */
private val TermLineHeight: TextUnit = 16.sp

/** 等宽字体单列宽度估算（12sp Monospace ≈ 0.6em ≈ 7.2dp），用于按容器宽反算列数。 */
private const val TERM_CHAR_WIDTH_DP = 7.2f

/** 终端输出区左右内边距（dp 值，与 AgentSpacing.space3 一致，供列数反算扣除）。 */
private const val TERM_PAD_H_DP = 12f

/** 终端输出区上下内边距（dp 值，与 AgentSpacing.space2 一致，供行数反算扣除）。 */
private const val TERM_PAD_V_DP = 8f

/** 终端等宽文本样式（noFontPadding，与 Type.kt 全局约定一致）。 */
private val TermTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = TermFontSize,
    lineHeight = TermLineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/**
 * 终端会话的进程级持有者（TERMINAL 页专用）。
 *
 * 为什么需要它：屏幕旋转会销毁并重建 Composition，若把 [TerminalSession] 直接放进
 * remember，每次旋转都会杀掉 shell 再重开一个（pty 句柄是 native 资源，
 * rememberSaveable 保不住它）。这里用「引用计数 + 延迟销毁」兜住旋转窗口期：
 * 退出页面后 1 秒内重新进入（旋转场景）即取消销毁、原会话继续；真正离开才
 * kill 进程组。进程死亡（Process Death）时 holder 随之消失，重新进入即全新会话。
 */
private object TerminalSessionHolder {

    private const val TAG = "TerminalScreen"
    private const val ROTATION_GRACE_MS = 1000L

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var session: TerminalSession? = null
    private var refs = 0
    private var pendingKill: Runnable? = null

    /** 输出刷新滴答（读线程 → 主线程自增，UI 据此重新快照）。 */
    val updateTick = mutableStateOf(0L)

    /** 会话退出码（主线程写入）；null = 未退出。 */
    val exitCode = mutableStateOf<Int?>(null)

    /** 启动失败原因（JNI 不可用 / forkpty 失败）；null = 无。 */
    val startError = mutableStateOf<String?>(null)

    /** 当前会话（无则 null）：供组合初期同步状态，不触发启动。 */
    fun peekSession(): TerminalSession? = session

    /**
     * 获取（必要时创建并启动）会话。
     * @return 会话实例；启动失败返回 null（原因见 [startError]）
     */
    fun acquire(cwd: File, filesDir: File, packageName: String): TerminalSession? {
        pendingKill?.let { main.removeCallbacks(it) }
        pendingKill = null
        refs++
        session?.let { return it }

        startError.value = null
        exitCode.value = null
        val s = TerminalSession(
            shellPath = "/system/bin/sh",
            cwd = cwd,
            filesDir = filesDir,
            packageName = packageName
        )
        s.onUpdate = { main.post { updateTick.value++ } }
        s.onExit = { code -> main.post { exitCode.value = code } }
        session = s
        val ok = runCatching { s.start() }.isSuccess
        if (!ok) {
            startError.value = "无法启动 shell（pty 创建失败），请返回重试"
            AgentLogger.e(TAG, "终端启动失败：forkpty 或 JNI 加载出错")
            s.destroy()
            session = null
            return null
        }
        return s
    }

    /** 释放引用；归零后延迟 [ROTATION_GRACE_MS] 销毁（旋转窗口期内可被 acquire 取消）。 */
    fun release() {
        refs--
        if (refs > 0 || session == null) return
        val kill = Runnable {
            session?.destroy()
            session = null
            pendingKill = null
        }
        pendingKill = kill
        main.postDelayed(kill, ROTATION_GRACE_MS)
    }
}

/**
 * 终端内容区（输出 + 输入栏），TERMINAL 与 CONTAINER 两页共用。
 *
 * 结构：输出区（逐行等宽渲染 + 底部自动跟随 + 长按复制）→ 输入栏（等宽单行 + 发送 +
 * Ctrl 切换 + Tab/↑/↓/Esc 快捷键）。会话存活态来自 holder 的 [updateTick]/[exitCode]/
 * [startError]，本组合只负责渲染，不含进程生命周期。
 *
 * @param session    当前会话（null = 未建立或已退出）
 * @param updateTick 输出刷新滴答（变化时重新快照渲染）
 * @param exitCode   会话退出码（null = 运行中）
 * @param startError 启动失败原因（非 null 时显示降级文案）
 */
@Composable
internal fun TerminalContent(
    session: TerminalSession?,
    updateTick: Long,
    exitCode: Int?,
    startError: String?
) {
    val context = LocalContext.current
    val ak = LocalAkColors

    if (startError != null) {
        AkEmptyState(
            icon = Icons.Outlined.Terminal,
            title = "启动失败",
            description = startError
        )
        return
    }

    val snapshot = remember(session, updateTick) {
        session?.emulator?.snapshot() ?: TerminalSnapshot.EMPTY
    }

    val scrollState = rememberScrollState()
    var autoFollow by remember { mutableStateOf(true) }

    // 用户手势停止时判定是否停在底部：在底部 → 恢复自动跟随；上滑 → 暂停
    LaunchedEffect(scrollState) {
        var wasInProgress = false
        snapshotFlow { scrollState.isScrollInProgress }.collect { inProgress ->
            if (wasInProgress && !inProgress) {
                autoFollow = scrollState.value >= scrollState.maxValue - 2
            }
            wasInProgress = inProgress
        }
    }
    // 新输出自动滚底（仅当用户仍在底部时）
    LaunchedEffect(snapshot.lines.size) {
        if (autoFollow) scrollState.scrollTo(scrollState.maxValue)
    }

    val clipboard = LocalClipboardManager.current
    val currentSnapshot by rememberUpdatedState(snapshot)

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ak.surface1)
        ) {
            // 按容器尺寸反算终端行列数，变化时同步 Emulator 缓冲与 pty winsize
            val rowHeightDp = with(LocalDensity.current) { TermLineHeight.toDp() }
            val cols = (
                (maxWidth.value - TERM_PAD_H_DP * 2) / TERM_CHAR_WIDTH_DP
                ).toInt().coerceAtLeast(20)
            val rows = (
                (maxHeight.value - TERM_PAD_V_DP * 2) / rowHeightDp.value
                ).toInt().coerceAtLeast(4)
            LaunchedEffect(session, cols, rows) { session?.resize(cols, rows) }

            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        horizontal = AgentSpacing.space3,
                        vertical = AgentSpacing.space2
                    )
            ) {
                // 长按复制整屏
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    val text = currentSnapshot.lines
                                        .joinToString("\n") { it.text().trimEnd() }
                                    clipboard.setText(AnnotatedString(text))
                                    Toast.makeText(
                                        context,
                                        "已复制终端输出",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                ) {
                    Column {
                        snapshot.lines.forEach { row ->
                            TerminalLine(
                                row = row,
                                defaultFg = MaterialTheme.colorScheme.onSurface,
                                defaultBg = ak.surface1
                            )
                        }
                        val code = exitCode
                        if (code != null) {
                            Text(
                                text = if (code >= 0) "[进程已退出，退出码 $code]"
                                else "[进程已退出]",
                                style = TermTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        TerminalInputBar(
            enabled = session != null && exitCode == null,
            onSendText = { text -> session?.write("$text\n") },
            onSendCtrl = { c -> session?.sendCtrl(c) },
            onSendRaw = { raw -> session?.write(raw) }
        )
    }
}

/**
 * 终端页（调研文档 03「第 4 步：终端组件」直译）。
 *
 * 结构：透明顶栏（[PageTopBar]「终端」）→ [TerminalContent]。
 * JNI 不可用 / 启动失败时显式降级——不静默崩溃。
 */
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val jniOk = remember { TerminalJni.ensureLoaded() }
    // W-27：终端 cwd 与 Agent/编辑器同一个工作区（agent_workspace，含老目录迁移）
    val cwd = remember { WorkspaceStore.dir(context) }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(title = AppDest.TERMINAL.title)

        if (!jniOk) {
            // 降级：x86 模拟器等未编入 pty 模块的 ABI——明确文案，而不是崩
            AkEmptyState(
                icon = Icons.Outlined.Terminal,
                title = "终端不可用",
                description = "当前设备架构不支持终端（pty 模块仅随 arm64-v8a 构建），可在真机上使用。"
            )
            return@Column
        }

        var session by remember { mutableStateOf(TerminalSessionHolder.peekSession()) }
        LaunchedEffect(Unit) {
            session = TerminalSessionHolder.acquire(cwd, context.filesDir, context.packageName)
        }
        // 退出页面（含返回键导航离开）即释放；销毁延迟到 holder 的旋转窗口期之后
        DisposableEffect(Unit) {
            onDispose { TerminalSessionHolder.release() }
        }

        TerminalContent(
            session = session,
            updateTick = TerminalSessionHolder.updateTick.value,
            exitCode = TerminalSessionHolder.exitCode.value,
            startError = TerminalSessionHolder.startError.value
        )
    }
}

/**
 * 终端输出单行：按 CharCell 属性拼 [AnnotatedString]（粗体 / 斜体 / 下划线 / 反显 / 16+256 色）。
 *
 * 行对象是写时复制的（Emulator 保证），[remember] 以行对象为键——未变化的行不会
 * 重复拼字符串，整屏刷新开销均摊到真正变化的行上。
 */
@Composable
private fun TerminalLine(row: TerminalRow, defaultFg: Color, defaultBg: Color) {
    val len = TerminalRow.visibleLength(row.cells)
    val annotated = remember(row) {
        buildAnnotatedString {
            for (i in 0 until len) {
                val cell = row.cells[i]
                val fg = cell.fg.resolveFg(defaultFg)
                val bg = cell.bg.resolveBg(defaultBg)
                // 反显 = 前后景互换（SGR 7）
                val effFg = if (cell.inverse) bg else fg
                val effBg = if (cell.inverse) fg else bg
                withStyle(
                    SpanStyle(
                        color = if (cell.dim) effFg.copy(alpha = 0.55f) else effFg,
                        // 默认背景不画色块，透出输出区底色（暖纸规范）
                        background = if (effBg == defaultBg) Color.Unspecified else effBg,
                        fontWeight = if (cell.bold) FontWeight.Bold else null,
                        fontStyle = if (cell.italic) FontStyle.Italic else null,
                        textDecoration = if (cell.underline) TextDecoration.Underline else null
                    )
                ) {
                    append(cell.char)
                    val combining = cell.combining
                    if (combining != null) append(combining)
                }
            }
        }
    }
    Text(
        text = annotated,
        style = TermTextStyle,
        maxLines = 1,
        softWrap = false
    )
}

/** 输入栏：等宽单行输入框（IME Send）+ 发送按钮 + Ctrl 切换 + Tab / ↑ / ↓ / Esc 快捷键行。 */
@Composable
private fun TerminalInputBar(
    enabled: Boolean,
    onSendText: (String) -> Unit,
    onSendCtrl: (Char) -> Unit,
    onSendRaw: (String) -> Unit
) {
    // 输入文本跨旋转保留（rememberSaveable：String 可直接存）
    var input by rememberSaveable { mutableStateOf("") }
    // Ctrl 待发态：置位后下一个「发送」把单字母变成控制字符（如 C → 0x03）
    var ctrlArmed by remember { mutableStateOf(false) }

    val submit: () -> Unit = {
        if (ctrlArmed && input.length == 1) {
            onSendCtrl(input[0])
        } else if (input.isNotEmpty()) {
            onSendText(input)
        }
        input = ""
        ctrlArmed = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = AgentSpacing.space3,
                end = AgentSpacing.space3,
                top = AgentSpacing.space2,
                bottom = AgentSpacing.space3
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = AgentDimens.touchTarget)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = AgentSpacing.space4),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    textStyle = TermTextStyle.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    text = if (ctrlArmed) "按字母键发控制字符（如 C = Ctrl+C）"
                                    else "输入命令",
                                    style = TermTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.width(AgentSpacing.space2))
            AkCircleIconButton(
                icon = Icons.AutoMirrored.Outlined.Send,
                description = "发送",
                onClick = submit,
                enabled = enabled
            )
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        // 快捷键行：Tab 补全 / ↑↓ 历史 / Esc / Ctrl 切换
        Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
            AkChip(text = "Tab", selected = false, onClick = { onSendRaw("\t") })
            AkChip(text = "↑", selected = false, onClick = { onSendRaw("\u001B[A") })
            AkChip(text = "↓", selected = false, onClick = { onSendRaw("\u001B[B") })
            AkChip(text = "Esc", selected = false, onClick = { onSendRaw("\u001B") })
            AkChip(
                text = "Ctrl",
                selected = ctrlArmed,
                onClick = { ctrlArmed = !ctrlArmed }
            )
        }
    }
}

/** ARGB → Compose Color：默认色值映射到主题色（仿真器不感知明暗主题）。 */
private fun Int.resolveFg(default: Color): Color =
    if (this == COLOR_DEFAULT_FG) default else Color(this)

/** ARGB → Compose Color（背景侧）：默认背景映射为输出区底色。 */
private fun Int.resolveBg(default: Color): Color =
    if (this == COLOR_DEFAULT_BG) default else Color(this)
