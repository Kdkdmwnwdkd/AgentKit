package com.example.agentkit.automation

import com.example.agentkit.automation.adb.AdbSession
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.terminal.backend.TerminalBackend

/**
 * W-51：**shell 身份的通道抽象**——把「以 uid 2000 执行命令」从具体实现里解耦。
 *
 * ## 为什么需要这一层
 *
 * 界面操作（`ui_tree` / `ui_click` / `input`）不关心 shell 是哪来的，只关心
 * 「能不能用、快不快、失败时怎么说」。W-50 时 [ShellUiBackend] 直接写死了
 * `ShizukuShell.exec`，于是「没装 Shizuku、只开了无线调试」的用户依然被挡在门外——
 * 明明系统 adbd 就在那儿。
 *
 * 现在两条通道都实现同一接口，按优先级取第一个可用的：
 *
 * ```
 * Shizuku（binder，毫秒级探测，无需常驻连接）
 *   → 无线调试 ADB（TCP，惰性建连，断线自动重连一次）
 * ```
 *
 * ## 为什么不合并成一个「哪个先连上算哪个」
 *
 * 两条通道的**失败模式不同**：Shizuku 是被系统杀进程，ADB 是端口变化/连接被回收。
 * 分开实现才能给出分别对应的修复动作（前者「重开 Shizuku」，后者「重新配对/核对端口」）。
 */
internal data class ShellExecResult(
    val channel: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    /** 通道没起来时的原因（null = 命令确实执行过）。 */
    val startError: String? = null
) {
    val ok: Boolean get() = startError == null && !timedOut && exitCode == 0
}

internal interface ShellChannel {
    /** 稳定键（与能力链候选同键，日志可对齐）。 */
    val backendId: String

    /** 展示名（写进工具返回文案，让用户知道走的是哪条路）。 */
    val label: String

    fun available(): Boolean

    /** 阻塞执行；调用方负责切 IO 线程。 */
    fun exec(command: String, timeoutSec: Long): ShellExecResult
}

/** Shizuku（binder）通道。 */
internal object ShizukuShellChannel : ShellChannel {
    override val backendId: String = TerminalBackend.KEY_SHIZUKU
    override val label: String = "调试通道（Shizuku）"

    override fun available(): Boolean = ShizukuShell.status() == ShizukuShell.Status.READY

    override fun exec(command: String, timeoutSec: Long): ShellExecResult {
        val out = ShizukuShell.exec(command, timeoutSec)
        return ShellExecResult(
            channel = backendId,
            exitCode = out.exitCode,
            stdout = out.stdout,
            stderr = out.stderr,
            timedOut = out.timedOut,
            startError = out.startError
        )
    }
}

/** 无线调试（adb over TCP）通道。 */
internal object AdbShellChannel : ShellChannel {
    /** 与能力链的候选键一致（`core/capability/CapabilityBridges.kt` 的 BACKEND_ADB）。 */
    override val backendId: String = "adb-wireless"
    override val label: String = "无线调试（ADB）"

    override fun available(): Boolean = AdbSession.available()

    override fun exec(command: String, timeoutSec: Long): ShellExecResult {
        val out = AdbSession.exec(command, timeoutSec)
        return ShellExecResult(
            channel = backendId,
            exitCode = out.exitCode,
            stdout = out.stdout,
            stderr = out.stderr,
            timedOut = out.timedOut,
            startError = out.startError
        )
    }
}

internal object ShellChannels {

    /** 全部候选，按优先级。 */
    fun all(): List<ShellChannel> = listOf(ShizukuShellChannel, AdbShellChannel)

    /**
     * 当前可用的最高优先级通道；都不可用返回 null。
     *
     * 探测成本：Shizuku 走 binder（`pingBinder`，微秒级）；ADB 只看「是否配对过」
     * （不发网络包），真正连不上会由 [exec] 如实报错——不在探测阶段做 5 秒建连，
     * 否则每次 `ui_tree` 都要先卡一次超时。
     */
    fun active(): ShellChannel? = all().firstOrNull { it.available() }
}
