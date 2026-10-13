package com.example.agentkit.core.container

import android.content.Context
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.terminal.TerminalSession
import java.io.File

/**
 * 容器会话工厂：把 proot 二进制 + 解压后的 rootfs 组装成一条 [TerminalSession]。
 *
 * 复用 TerminalSession 的 pty 机制（forkpty + 读循环 + 仿真器），只是把要执行的程序
 * 从 `/system/bin/sh` 换成 `proot -r <rootfs> ... /bin/sh`——argv 形式走新增的
 * [com.example.agentkit.terminal.TerminalJni.createSubprocessArgv]。
 *
 * 任何前置条件不满足（proot 缺失 / rootfs 未解压）都返回 null，由 UI 降级。
 */
object ContainerSession {

    private const val TAG = "ContainerSession"

    /**
     * 创建并配置一条容器终端会话（不自动 start，start 由 holder 在 UI 线程调用）。
     * @return 就绪的 [TerminalSession]；前置条件不满足返回 null。
     */
    fun create(context: Context, distro: RootfsManager.Distro): TerminalSession? {
        val prootBin = ProotRunner.ensureBinary(context) ?: run {
            AgentLogger.e(TAG, "proot 二进制不可用")
            return null
        }
        if (!RootfsManager.isExtracted(context, distro.id)) {
            AgentLogger.e(TAG, "rootfs(${distro.id}) 尚未解压")
            return null
        }
        val rootfs = RootfsManager.rootfsDir(context, distro.id)
        val home = File(rootfs, "root")
        val argv = buildList {
            add(prootBin.absolutePath)
            addAll(ProotRunner.buildArgs(rootfs, "/root"))
            add("/bin/sh") // guest shell
        }
        return TerminalSession(
            argv = argv,
            // 宿主侧真实目录，chdir 可成功；proot -w /root 再切到 guest /root
            cwd = rootfs,
            filesDir = context.filesDir,
            packageName = context.packageName,
            homeDir = home,
            env = ProotRunner.guestEnv(context.packageName)
        )
    }
}
