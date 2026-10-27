package com.example.agentkit.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.example.agentkit.automation.adb.AdbCredentials
import com.example.agentkit.automation.adb.AdbDiscovery
import com.example.agentkit.automation.adb.AdbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无线调试（ADB）分区（W-68）。
 *
 * 为什么从设置页搬到这里：Shizuku 与无线调试是同一件事的两条路——「不用无障碍也能拿到
 * uid 2000」。原来 Shizuku 在权限中心有一行（带授权操作），无线调试在设置页另起一组，
 * 用户在两个页面各看到一半，还得自己拼起来。现在两条路都在权限中心，Shizuku 在权限列表里，
 * 这里补上无线调试的配对、测试与解绑。
 *
 * 状态只在点测试/配对后自增 tick 刷新，不做轮询：`adb` 探测要起进程，权限页常驻轮询不值当。
 */
@Composable
internal fun AdbChannelSection() {
    var tick by remember { mutableStateOf(0) }
    var showPair by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val adbStatus = remember(tick) { AdbSession.status() }
    val adbError = remember(tick) { AdbSession.lastError() }
    val host = remember(tick) { AdbCredentials.host() }
    val port = remember(tick) { AdbCredentials.connectPort() }

    AkGroup(
        title = "无线调试（ADB）",
        description = "Shizuku 之外的另一种选法：开发者选项里配对一次即可，不装第三方 App"
    ) {
        AkListItem(
            icon = Icons.Outlined.Terminal,
            title = "配对设备",
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 端点：可能来自扫描（主路径）、上次配对（连接端口已知）、或手动填写（兜底）
    var host by remember { mutableStateOf(AdbCredentials.host().orEmpty()) }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember {
        mutableStateOf(AdbCredentials.connectPort().takeIf { it > 0 }?.toString().orEmpty())
    }
    var code by remember { mutableStateOf("") }

    var scanning by remember { mutableStateOf(true) }
    var scanNote by remember { mutableStateOf("正在寻找本机的无线调试服务…") }
    var showManual by remember { mutableStateOf(false) }
    var scanRound by remember { mutableStateOf(0) }

    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // W-52：进对话框就自动发现端点（配对端口 + 连接端口 + IP），用户只需输 6 位码
    DisposableEffect(scanRound) {
        val session = AdbDiscovery.start(
            context,
            onUpdate = { ep ->
                scope.launch(Dispatchers.Main) {
                    ep.host?.let { host = it }
                    ep.pairPort?.let { pairPort = it.toString() }
                    ep.connectPort?.let { connectPort = it.toString() }
                    scanNote = when {
                        ep.complete ->
                            "已找到 ${ep.host}：配对端口 ${ep.pairPort}、连接端口 ${ep.connectPort}（已自动填好）"
                        ep.pairPort == null && ep.connectPort != null ->
                            "已找到本机 ${ep.host ?: ""}，但配对端口还没出现——" +
                                "请在手机上点「使用配对码配对设备」，这边会自动补上"
                        ep.pairPort != null && ep.connectPort == null ->
                            "已找到配对端口 ${ep.pairPort}，等待连接端口广播…"
                        else -> scanNote
                    }
                }
            },
            onError = { msg ->
                scope.launch(Dispatchers.Main) {
                    scanNote = "$msg（可展开「手动填写」）"
                }
            }
        )
        onDispose { session?.stop() }
    }

    // mDNS 常驻监听耗电，且配对端口只在弹窗打开期间有效——20 秒后收工
    LaunchedEffect(scanRound) {
        scanning = true
        delay(20_000)
        scanning = false
        val complete = host.isNotBlank() && pairPort.isNotBlank() && connectPort.isNotBlank()
        if (!complete) {
            scanNote = "扫描结束：没拿到完整端点（部分系统限制 mDNS），可「重新扫描」或展开「手动填写」"
        }
    }

    val canSubmit = code.length == 6 && host.isNotBlank() &&
        pairPort.isNotBlank() && connectPort.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("无线调试配对", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberPersistentScrollState())) {
                Text(
                    "① 手机上：开发者选项 → 无线调试 → 打开开关\n" +
                        "② 点「使用配对码配对设备」，弹窗里有 6 位码\n" +
                        "③ 在这里输入这 6 位码即可——IP 和两个端口会自动填",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(AgentSpacing.space3))
                Text(
                    text = scanNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scanNote.startsWith("已找到"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!scanning && !done) {
                    TextButton(onClick = { scanRound++ }, enabled = !busy) {
                        Text("重新扫描")
                    }
                }

                Spacer(Modifier.height(AgentSpacing.space2))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("6 位配对码") },
                    placeholder = { Text("手机上弹窗里的那 6 个数字") },
                    singleLine = true,
                    enabled = !busy && !done,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(
                    onClick = { showManual = !showManual },
                    enabled = !busy && !done
                ) {
                    Text(if (showManual) "收起手动填写" else "手动填写 IP / 端口")
                }

                if (showManual) {
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
                        singleLine = true,
                        enabled = !busy && !done,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

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
