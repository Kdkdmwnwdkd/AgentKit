package com.example.agentkit.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.container.ContainerSession
import com.example.agentkit.core.container.ProotRunner
import com.example.agentkit.core.container.RootfsManager
import com.example.agentkit.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器页（调研文档「第 5 步：容器管理」直译）。
 *
 * 结构：发行版卡片列表（从 assets/containers/ 下探测 *.tar.gz）→ 点选解压 rootfs →
 * 进入 proot 启动的伪终端（复用 [TerminalContent]）。proot 二进制缺失或 ptrace 受限时
 * 明确降级，不静默崩溃。
 */
@Composable
fun ContainerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var distros by remember { mutableStateOf<List<RootfsManager.Distro>>(emptyList()) }
    var extracted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<RootfsManager.Distro?>(null) }

    val prootReady = remember { ProotRunner.isAvailable(context) }

    LaunchedEffect(Unit) {
        distros = RootfsManager.listAvailable(context)
        extracted = distros.filter { RootfsManager.isExtracted(context, it.id) }
            .map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = AppDest.CONTAINER.title,
            actionIcon = if (selected != null) Icons.AutoMirrored.Outlined.ArrowBack else null,
            actionDescription = "返回发行版列表",
            onAction = { selected = null }
        )

        if (!prootReady) {
            AkEmptyState(
                icon = Icons.Outlined.ViewInAr,
                title = "需要 proot 运行时",
                description = "容器依赖 proot（GPL-2.0 独立可执行文件）。请运行仓库 scripts/build-proot.sh 用 NDK 交叉编译，或将 proot 放入 app/src/main/assets/bin/arm64-v8a/ 后重装应用。"
            )
            return@Column
        }

        if (selected == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AgentSpacing.space5)
            ) {
                Text(
                    text = "选择发行版启动容器",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AgentSpacing.space4))
                if (distros.isEmpty()) {
                    AkEmptyState(
                        icon = Icons.Outlined.ViewInAr,
                        title = "无可用 rootfs",
                        description = "请在 app/src/main/assets/containers/ 下放置 <发行版>.tar.gz（如 alpine.tar.gz）。"
                    )
                } else {
                    distros.forEach { d ->
                        DistroCard(
                            distro = d,
                            ready = extracted.contains(d.id),
                            isBusy = busy == d.id,
                            onClick = {
                                if (extracted.contains(d.id)) {
                                    selected = d
                                } else {
                                    busy = d.id
                                    scope.launch(Dispatchers.IO) {
                                        val ok = RootfsManager.ensureExtracted(context, d) != null
                                        withContext(Dispatchers.Main) {
                                            if (ok) extracted = extracted + d.id
                                            busy = null
                                            if (ok) selected = d
                                        }
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.height(AgentSpacing.space3))
                    }
                }
            }
        } else {
            val distro = selected!!
            var session by remember { mutableStateOf<TerminalSession?>(ContainerSessionHolder.peekSession()) }
            LaunchedEffect(distro) {
                session = ContainerSessionHolder.acquire(context, distro)
            }
            DisposableEffect(Unit) {
                onDispose { ContainerSessionHolder.release() }
            }
            TerminalContent(
                session = session,
                updateTick = ContainerSessionHolder.updateTick.value,
                exitCode = ContainerSessionHolder.exitCode.value,
                startError = ContainerSessionHolder.startError.value
            )
        }
    }
}

/** 发行版卡片：图标底座 + 名称 + 状态（已就绪 / 解压中 / 点击解压）+ 整卡点击。 */
@Composable
private fun DistroCard(
    distro: RootfsManager.Distro,
    ready: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    val ak = LocalAkColors
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(AgentSpacing.space5),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ak.surface1,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.ViewInAr,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(AgentSpacing.space4))
            Column(Modifier.weight(1f)) {
                Text(
                    text = distro.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AgentSpacing.space1))
                Text(
                    text = if (isBusy) "正在解压…"
                    else if (ready) "已就绪 · 点击进入"
                    else "点击解压 rootfs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 容器会话的进程级持有者（与 [TerminalSessionHolder] 同构，但创建的是 proot 容器会话）。
 * 引用计数 + 旋转窗口期延迟销毁；acquire 时负责确保 rootfs 已解压、proot 可用。
 */
private object ContainerSessionHolder {
    private const val TAG = "ContainerScreen"
    private const val ROTATION_GRACE_MS = 1000L

    val updateTick = mutableStateOf(0L)
    val exitCode = mutableStateOf<Int?>(null)
    val startError = mutableStateOf<String?>(null)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var session: TerminalSession? = null
    private var refs = 0
    private var pendingKill: Runnable? = null

    fun peekSession(): TerminalSession? = session

    fun acquire(context: Context, distro: RootfsManager.Distro): TerminalSession? {
        pendingKill?.let { main.removeCallbacks(it) }
        pendingKill = null
        refs++
        session?.let { return it }

        startError.value = null
        exitCode.value = null

        if (RootfsManager.ensureExtracted(context, distro) == null) {
            startError.value = "rootfs 解压失败，请检查 assets/containers/${distro.assetName}"
            AgentLogger.e(TAG, "rootfs 解压失败：${distro.id}")
            refs--
            return null
        }
        val s = ContainerSession.create(context, distro)
        if (s == null) {
            startError.value = "无法创建容器会话（proot 不可用？）"
            AgentLogger.e(TAG, "ContainerSession.create 返回 null")
            refs--
            return null
        }
        s.onUpdate = { main.post { updateTick.value++ } }
        s.onExit = { code -> main.post { exitCode.value = code } }
        session = s
        val ok = runCatching { s.start() }.isSuccess
        if (!ok) {
            startError.value = "容器 shell 启动失败（proot 执行失败，可能是 ptrace 受限）"
            AgentLogger.e(TAG, "容器启动失败")
            s.destroy()
            session = null
            refs--
            return null
        }
        return s
    }

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
