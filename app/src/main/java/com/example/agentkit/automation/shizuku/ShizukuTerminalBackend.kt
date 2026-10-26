package com.example.agentkit.automation.shizuku

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.terminal.backend.ProcessBackendBase
import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.core.terminal.backend.TerminalBackendProvider
import com.example.agentkit.core.terminal.backend.TerminalIdentity

/**
 * W-56：把 Shizuku 接成**会话池**的后端——uid 2000 的持久 shell。
 *
 * ## 为什么需要它
 *
 * 在此之前 Shizuku 只有一次性通道（`ShizukuShell.exec`：一条命令一个远端进程）。
 * `shell_run` 在 Shizuku 就绪时默认就走那条路，于是 `cd` / `export` 不跨调用保持
 * （W-54 把这个语义明确告知了模型，但没有解决）。
 *
 * 而 Shizuku 的 `newProcess(arrayOf("sh"))` 返回的 `Process` 其实是可以长期持有的：
 * 有 stdin/stdout 流。W-36 踩的坑是**定时 `waitFor`**（远端代理上会立刻抛），
 * 不是流读写——[ProcessBackendBase] 用 `exitValue()` 轮询，正好避开。
 *
 * 接进池之后，Shizuku 用户也能拿到「持久 shell + 哨兵」的完整语义：
 * `cd` 保持、`export` 保持、多轮操作连续。
 *
 * ## 与其它三档的关系
 *
 * | | proot | **Shizuku** | 无线调试 | 本机 user |
 * |---|---|---|---|---|
 * | 身份 | fake root | **uid 2000** | uid 2000 | App uid |
 * | 能改系统设置 | 否 | **是** | 是 | 否 |
 * | 有包管理器 | 有 | 无 | 无 | 无 |
 * | 依赖 | rootfs | Shizuku 进程 | 系统 adbd | 无 |
 *
 * 顺序 `proot → shizuku → adb → user`：proot 工具最全；两档 uid 2000 里 Shizuku 走 binder
 * （无 TCP 连接维持、探测微秒级），无线调试作为「不装第三方 App」的独立备份。
 *
 * ## 起不来怎么办
 *
 * `openProcess()` 返回 null → 基类判启动失败 → 会话池按 W-55 的回落机制试下一档
 * （无线调试 / 本机 shell）。所以即使 Shizuku 的进程代理在某些 ROM 上不配合，
 * 用户看到的也只是「权限低一点」，不是「shell 用不了」。
 */
internal class ShizukuTerminalBackend : ProcessBackendBase() {

    override val backendKey: String = TerminalBackend.KEY_SHIZUKU

    override fun openProcess(): Process? {
        val status = ShizukuShell.status()
        if (status != ShizukuShell.Status.READY) {
            AgentLogger.w(TAG, "Shizuku 不可用（status=$status），本档启动失败")
            return null
        }
        val p = ShizukuProcess.open(arrayOf("sh"))
        if (p == null) AgentLogger.w(TAG, "newProcess(\"sh\") 返回 null")
        return p
    }

    override val startLog: String get() = "Shizuku 后端已启动（uid 2000，持久 shell）"

    override val startFailLog: String get() = "Shizuku 后端启动失败（未运行/未授权/newProcess 失败）"

    private companion object {
        const val TAG = "ShizukuTerminalBackend"
    }
}

/**
 * Shizuku 后端的可用性 + 构造。
 *
 * `probe()` 走 `ShizukuShell.status()`（binder ping，微秒级），与能力链的判定同源。
 */
internal class ShizukuBackendProvider : TerminalBackendProvider {

    override val identity: TerminalIdentity = TerminalIdentity.User

    override val backendKey: String = TerminalBackend.KEY_SHIZUKU

    override fun probe(): Boolean = ShizukuShell.status() == ShizukuShell.Status.READY

    override fun create(): TerminalBackend? = if (probe()) ShizukuTerminalBackend() else null
}
