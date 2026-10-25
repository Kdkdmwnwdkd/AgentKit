package com.example.agentkit.ui

import android.content.Context
import android.os.Build
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
import androidx.compose.material.icons.outlined.DeveloperMode
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.agentkit.core.container.ContainerCommandRunner
import com.example.agentkit.core.container.ContainerSession
import com.example.agentkit.core.container.ProotRunner
import com.example.agentkit.core.container.RootfsManager
import com.example.agentkit.core.container.ToolchainInstaller
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
    /** B 批：运行时安装后的刷新计数——探测结果按 (id, tick) 重算。 */
    var toolchainTick by remember { mutableStateOf(0) }
    /**
     * C 批：用户为每种语言填写的目标版本号。
     *
     * key 用 "<distroId>:<langId>"，因为不同发行版的可用版本不一样，
     * 分开记才不会串。
     */
    val versionInputs = remember { mutableStateMapOf<String, String>() }

    /**
     * 正在执行的安装任务（key = "<发行版id>:<语言id>" 或 "<发行版id>:all"）；null = 空闲。
     *
     * 存在的理由（真机 exit 99）：按钮原来没有运行态，连点两下就会起两个 proot
     * 同时跑 `apk add`，apk 数据库抢锁，第二个必然
     * `exit 99: Unable to lock database`。底层 [ContainerCommandRunner] 已加串行闸门，
     * 这一层再把按钮禁用掉——让用户在点下去之前就知道「已经在装了」，而不是等一个
     * 失败提示才反应过来。
     */
    var installing by remember { mutableStateOf<String?>(null) }

    /**
     * 最近一次安装的结果（内联常驻）。
     *
     * 原来只有 Toast：一闪而过，且 `take(80)` 截断会把真正的报错行切掉，
     * 用户只能截图问「这什么意思」。现在把完整尾段留在卡片里，能看懂也能复制。
     */
    var installResult by remember { mutableStateOf<InstallReport?>(null) }

    // proot 三件套是**首次使用时**才从 assets 提取的（ensureBinary）。
    // 原来这里只判断 isAvailable，新装应用还没提取过 → 永远显示「需要 proot」，
    // 用户根本没有触发提取的入口，等于容器功能对新用户完全不可用。
    // 现在进页面就后台提取一次（幂等，已就绪直接返回）。
    var prootReady by remember { mutableStateOf(ProotRunner.isAvailable(context)) }
    var prootError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!prootReady) {
            val bin = withContext(Dispatchers.IO) { ProotRunner.ensureBinary(context) }
            prootReady = bin != null
            prootError = bin == null
        }
    }

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
                title = if (prootError) "proot 运行时不可用" else "正在准备容器运行时…",
                description = if (prootError) {
                    "没能从安装包里取出 proot（当前设备 ABI：${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}）。" +
                        "正式包只带 arm64-v8a；在 x86_64 模拟器上做容器验证，请用 " +
                        "`-Px86Rootfs=true` 构建调试包（会额外带 x86_64 的 proot 与 rootfs）。"
                } else {
                    "首次进入需要把 proot 从安装包释放到应用私有目录，通常一两秒。"
                }
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

                Spacer(Modifier.height(AgentSpacing.space4))

                // B 批：容器运行时一键安装。
                // 容器默认只有 busybox 一套基础工具，node/python/java 都没有——
                // 这里按发行版探测现状，缺什么给一条可执行命令，点一下就在容器里装好。
                AkSectionCard(
                    title = "语言运行时",
                    icon = Icons.Outlined.DeveloperMode,
                    description = "容器默认不带开发语言运行时。检测缺失后一键安装（node / python / java）。"
                ) {
                    if (extracted.isEmpty()) {
                        Text(
                            "请先解压一个发行版。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        extracted.forEach { id ->
                            val detection = remember(id, toolchainTick) {
                                ToolchainInstaller.detect(RootfsManager.rootfsDir(context, id))
                            }
                            val missing = ToolchainInstaller.Lang.entries.filter { it !in detection.installed }
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    id,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(AgentSpacing.space1))
                                Text(
                                    text = when {
                                        detection.pkgManager == null ->
                                            detection.reason ?: "该 rootfs 无包管理器，无法自动安装"
                                        detection.installed.isEmpty() -> "未检测到任何语言运行时"
                                        else -> "已安装：" + detection.installed.joinToString("、") { it.displayName }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (detection.pkgManager != null && missing.isNotEmpty()) {
                                    // 命令先算出来，UI 与执行共用同一份——避免文案与实际执行不一致
                                    val installCmd = ToolchainInstaller.installMissingCommand(detection)
                                    val allKey = "$id:all"
                                    val allLabel = missing.joinToString("、") { it.displayName }
                                    Spacer(Modifier.height(AgentSpacing.space2))
                                    AkTextButton(
                                        text = if (installing == allKey) "正在安装 $allLabel…"
                                        else "安装 $allLabel",
                                        enabled = installCmd != null && installing == null,
                                        onClick = {
                                            val cmd = installCmd
                                            if (cmd == null) {
                                                Toast.makeText(context, "无法生成安装命令", Toast.LENGTH_SHORT).show()
                                                return@AkTextButton
                                            }
                                            // 立刻上锁：从点击这一刻起其它安装按钮全禁用，
                                            // 不靠「底层会串行」兜底（那会让用户以为点了没反应）
                                            installing = allKey
                                            installResult = null
                                            // 装完刷新探测结果（列表项用 remember(id, toolchainTick) 重算）
                                            scope.launch(Dispatchers.IO) {
                                                val res = runInstallInContainer(context, id, cmd)
                                                withContext(Dispatchers.Main) {
                                                    installing = null
                                                    toolchainTick++
                                                    installResult = InstallReport(id, allLabel, res)
                                                    Toast.makeText(
                                                        context,
                                                        if (res.ok) "安装完成：$allLabel" else "安装失败（exit ${res.exitCode}）",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(AgentSpacing.space1))
                                    Text(
                                        text = "将在容器内执行：${installCmd ?: "（无法生成命令）"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // C 批：按语言单独指定版本安装。
                                //
                                // 「一键装齐」装的是各语言的**当前默认版**——但用户真正要的
                                // 往往是特定版本（项目 lockfile 卡在 node 18、代码用了
                                // python 3.11 的新语法）。所以这里逐语言给出版本输入框：
                                // 留空 = 用默认版；填了 = 装指定版本。
                                if (detection.pkgManager != null && missing.isNotEmpty()) {
                                    Spacer(Modifier.height(AgentSpacing.space3))
                                    Text(
                                        text = "指定版本安装",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "留空则安装默认版本；填入版本号则装对应版本。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(AgentSpacing.space2))
                                    missing.forEach { lang ->
                                        val vKey = "$id:${lang.id}"
                                        val vInput = versionInputs[vKey].orEmpty()
                                        Column(Modifier.fillMaxWidth()) {
                                            AkTextField(
                                                value = vInput,
                                                onValueChange = { versionInputs[vKey] = it },
                                                label = "${lang.displayName} 版本",
                                                placeholder = "默认",
                                                machine = true
                                            )
                                            val pm = detection.pkgManager
                                            val spec = if (vInput.isBlank()) null
                                            else ToolchainInstaller.resolveVersion(lang, vInput, detection.distro)
                                            // 版本号非法时**明确报错**，不静默回落成默认版——
                                            // 用户要 node 18 却悄悄装了 node 20，比报错更糟。
                                            val unavailable = spec != null && pm != null && !spec.availableOn(pm)
                                            if (vInput.isNotBlank() && spec == null) {
                                                Spacer(Modifier.height(AgentSpacing.space1))
                                                Text(
                                                    text = "版本号非法：只接受数字与点（如 18 / 3.11）",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            } else if (unavailable) {
                                                // 官方源没有这个版本：说清原因，且不给可点的按钮。
                                                // 换成相近版本悄悄装上去，比装不上更难排查。
                                                Spacer(Modifier.height(AgentSpacing.space1))
                                                Text(
                                                    text = spec?.note?.takeIf { it.isNotBlank() }
                                                        ?: "该发行版装不到 ${lang.displayName} ${spec?.version ?: vInput}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            } else {
                                                val cmd = ToolchainInstaller.installCommand(
                                                    lang, detection.pkgManager, spec
                                                )
                                                if (cmd != null) {
                                                    Spacer(Modifier.height(AgentSpacing.space1))
                                                    val langKey = "$id:${lang.id}"
                                                    val langLabel = lang.displayName +
                                                        (spec?.let { " ${it.version}" } ?: "")
                                                    AkTextButton(
                                                        text = if (installing == langKey) "正在安装 $langLabel…"
                                                        else "安装 $langLabel",
                                                        enabled = installing == null,
                                                        onClick = {
                                                            // cmd 已被外层 if (cmd != null) 收窄为非空，
                                                            // 这里不再重复判 null（编译器会报恒真警告）。
                                                            installing = langKey
                                                            installResult = null
                                                            scope.launch(Dispatchers.IO) {
                                                                val res = runInstallInContainer(context, id, cmd)
                                                                withContext(Dispatchers.Main) {
                                                                    installing = null
                                                                    toolchainTick++
                                                                    installResult = InstallReport(id, langLabel, res)
                                                                    Toast.makeText(
                                                                        context,
                                                                        if (res.ok) "安装完成：$langLabel"
                                                                        else "安装失败（exit ${res.exitCode}）",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                }
                                                            }
                                                        }
                                                    )
                                                    Spacer(Modifier.height(AgentSpacing.space1))
                                                    Text(
                                                        text = "将执行：$cmd",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    // 版本说明：Debian 官方源没有 node 18 这类分版本包，
                                                    // 用户得知道「可能装不上、或者装到的是默认版」。
                                                    spec?.note?.takeIf { it.isNotBlank() }?.let { note ->
                                                        Text(
                                                            text = note,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(AgentSpacing.space3))
                                        }
                                    }
                                }
                                // 安装结果常驻展示：Toast 一闪而过，而 apk/apt 的失败原因
                                // 几乎都在输出的最后几行——真机上的 exit 99 就是这样被截成半句
                                // 「ERROR: Unable to lock database: temporary error」，等于没给信息。
                                installResult?.takeIf { it.distroId == id }?.let { report ->
                                    Spacer(Modifier.height(AgentSpacing.space2))
                                    Text(
                                        text = report.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (report.result.ok) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.height(AgentSpacing.space3))
                            }
                        }
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

/**
 * B 批：在容器里执行一条安装命令（同步等退出码）。
 *
 * 走 [ContainerCommandRunner] 的独立进程通道而非 pty 会话——pty 拿不到退出码，
 * 而「装成功没有」只能靠退出码判断。
 */
private suspend fun runInstallInContainer(
    context: Context,
    distroId: String,
    command: String
): ContainerCommandRunner.Result = withContext(Dispatchers.IO) {
    ContainerCommandRunner.run(context, distroId, command)
}

/**
 * 一次安装的结果（内联展示用）。
 *
 * 为什么不用 Toast 汇报失败：Toast 只能容纳两行，且马上消失；而 apk / apt 的失败
 * 原因几乎总在输出的**最后几行**（`ERROR: ...`），前 80 字截断恰好把它们切掉。
 * 真机上用户拿到的就是 `exit 99` 加一句被切一半的话，等于没给信息。
 */
private data class InstallReport(
    val distroId: String,
    val label: String,
    val result: ContainerCommandRunner.Result
) {
    /** 失败时保留最后几行（错误在尾部），成功时只报一句。 */
    val summary: String
        get() = when {
            result.ok -> "$label 安装完成。"
            result.busy -> "$label：同一条命令已在执行，等它结束再点。"
            else -> {
                val tail = result.output.trim().lines()
                    .filter { it.isNotBlank() }
                    .takeLast(4)
                    .joinToString("\n")
                "$label 安装失败（exit ${result.exitCode}）：" +
                    if (tail.isBlank()) "（无输出）" else "\n$tail"
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
