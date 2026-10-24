package com.example.agentkit.core.container

import android.content.Context
import com.example.agentkit.core.AgentLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在容器里执行一次性命令并拿到退出码（B 批）。
 *
 * ## 为什么不用 TerminalSession
 * [com.example.agentkit.terminal.TerminalSession] 是 **pty 交互式**会话：它按屏幕语义
 * 解析输出，发一条命令进去只能知道「命令是否已下发」，**取不到退出码**（见
 * [ContainerSession.runCommand] 的 KDoc 明确说明）。
 *
 * 但「一键安装运行时」必须有退出码才能判断成功与否——`apk add` 失败时退出码非 0，
 * 而 pty 通道拿不到。所以这里走**独立的 ProcessBuilder 通道**：
 * 直接以 proot 的 argv 起一个短命进程，命令跑完即退出，退出码与 stdout/stderr 都拿得到。
 *
 * 这与 [com.example.agentkit.core.job.JobRunner] 是同一思路（后台任务用独立进程，
 * 不与会话池共享状态），区别是这里要**同步等退出码**，所以用 `waitFor` 而非轮询。
 *
 * ## 边界
 * - 安装 node/python/java 可能耗时几分钟（下载 + 解包），因此给一个较宽的超时上限。
 * - 超时按「失败」处理并杀掉进程——不留半装状态在后台偷偷跑。
 */
object ContainerCommandRunner {

    private const val TAG = "ContainerCommandRunner"

    /** 执行结果。 */
    data class Result(
        val exitCode: Int,
        val output: String
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 默认超时：装 JDK 慢，但也不该无限等。 */
    private const val DEFAULT_TIMEOUT_MINUTES = 15L

    /** 单次输出上限（安装日志可能很长，截断避免 OOM）。 */
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    /**
     * 在容器中执行一条命令并等待结束。
     *
     * @param context 应用上下文
     * @param distroId 目标发行版 id（须已解压）
     * @param command 命令原文（**原样传入**，由容器内 shell 自己解析）
     * @return 执行结果；前置条件不满足时返回 exitCode=-1
     */
    fun run(
        context: Context,
        distroId: String,
        command: String,
        timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES
    ): Result {
        val prootBin = ProotRunner.ensureBinary(context)
        if (prootBin == null) {
            return Result(-1, "proot 运行时不可用，无法进入容器")
        }
        if (!RootfsManager.isExtracted(context, distroId)) {
            return Result(-1, "rootfs($distroId) 尚未解压")
        }
        val rootfs = RootfsManager.rootfsDir(context, distroId)
        val invocation = ProotRunner.buildInvocation(
            prootBinary = prootBin,
            rootfs = rootfs,
            workDir = ProotRunner.GUEST_HOME,
            packageName = context.packageName,
            // 运行库在 filesDir/bin，proot 本体在 nativeLibraryDir——必须显式区分
            libDir = ProotRunner.libDir(context)
        )
        // 一次性命令：argv = proot + 选项 + guest shell + 命令参数
        val argv = invocation.argv.dropLast(1) + ProotRunner.buildCommandArgv(
            cwd = ProotRunner.GUEST_HOME,
            command = command
        )
        // envArray 已是「宿主 env + guest env」的并集（见 ProotRunner.buildInvocation），
        // 直接透传给子进程即可，不需要再拼一次。
        return runProcess(argv, invocation.envArray, timeoutMinutes)
    }

    /** 起进程、等退出、收输出。 */
    private fun runProcess(argv: List<String>, env: Array<String>, timeoutMinutes: Long): Result =
        runCatching {
            val process = ProcessBuilder(argv)
                .apply {
                    environment().clear()
                    environment().putAll(env.associate { it.substringBefore('=') to it.substringAfter('=') })
                    redirectErrorStream(true)
                }
                .start()
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Result(-1, "命令超时（${timeoutMinutes} 分钟）已终止")
            }
            val out = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            Result(process.exitValue(), out.take(MAX_OUTPUT_CHARS))
        }.getOrElse {
            AgentLogger.e(TAG, "容器命令执行失败：${it.message}")
            Result(-1, "执行失败：${it.message}")
        }
}
