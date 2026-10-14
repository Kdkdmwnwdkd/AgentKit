package com.example.agentkit.core.terminal.backend

import com.example.agentkit.core.AgentLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStreamReader

/**
 * 本机 user 后端（T07）：以 App 的本机 uid 身份跑一条 `/system/bin/sh`（无 rootfs 时的降级路径）。
 *
 * 零外部依赖：只用 `java.lang.ProcessBuilder` + 协程。**不 import 任何 `android.*`**，
 * 因此可在 JVM harness 里以 `/bin/sh` 直接跑真实会话做端到端验证（cwd / 环境变量跨调用保持）。
 *
 * 数据模型：一条**常驻** shell 进程 —— stdin 写入命令（一行一条），stdout/stderr 合并成
 * 文本 chunk 持续流入 [output]。命令的**完成判定**由上层（会话池）用哨兵行实现，
 * 本后端只做「写入 + 读回」。
 *
 * 已知取舍：无 PTY（`ProcessBuilder` 拿到的不是伪终端），故 `Ctrl-C` 不是真中断、
 * `ls --color` 之类的交互特性不生效——这已写进 [com.example.agentkit.tools.ShellTool] 的工具描述，
 * 让模型知情而不是瞎试。
 *
 * @param shellPath shell 绝对路径（Android = `/system/bin/sh`；JVM harness = `/bin/sh`）
 * @param workingDir 会话初始工作目录（宿主真实目录）
 * @param extraEnv 追加/覆盖的环境变量（`ProcessBuilder` 默认继承 JVM 环境）
 */
class LocalUserBackend(
    private val shellPath: String = DEFAULT_SHELL,
    private val workingDir: File,
    private val extraEnv: Map<String, String> = emptyMap()
) : TerminalBackend {

    override val identity: TerminalIdentity = TerminalIdentity.User

    override val backendKey: String = TerminalBackend.KEY_USER

    private val channel = Channel<String>(Channel.UNLIMITED)

    override val output: Flow<String> = channel.receiveAsFlow()

    private var process: Process? = null
    private val exit = CompletableDeferred<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var waiterJob: Job? = null

    override val isAlive: Boolean
        get() = process?.isAlive == true

    override suspend fun start(): Boolean {
        if (process != null) return isAlive
        val started = runCatching {
            val pb = ProcessBuilder(shellPath)
            pb.directory(workingDir)
            pb.redirectErrorStream(true) // stderr 并入 stdout：模型只看一路输出更省心
            pb.environment().putAll(extraEnv)
            val p = pb.start()
            process = p

            readerJob = scope.launch {
                val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
                val buf = CharArray(READ_BUFFER_CHARS)
                try {
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) channel.send(String(buf, 0, n))
                    }
                } catch (t: Throwable) {
                    AgentLogger.w(TAG, "读取 $shellPath 输出异常：${t.message}")
                } finally {
                    channel.close()
                }
            }
            waiterJob = scope.launch {
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                if (!exit.isCompleted) exit.complete(code)
                channel.close()
            }
            true
        }.getOrElse { e ->
            AgentLogger.e(TAG, "本机 user shell 启动失败（$shellPath）：${e.message}")
            false
        }
        if (started) AgentLogger.i(TAG, "本机 user 后端已启动：$shellPath cwd=${workingDir.absolutePath}")
        return started
    }

    override suspend fun send(input: String): Boolean {
        val p = process ?: return false
        if (!p.isAlive) return false
        return runCatching {
            p.outputStream.write(input.toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
            true
        }.getOrElse { e ->
            AgentLogger.w(TAG, "写入 stdin 失败：${e.message}")
            false
        }
    }

    override suspend fun close() {
        val p = process
        if (p != null) runCatching { p.destroy() }
        runCatching { p?.outputStream?.close() }
        if (!exit.isCompleted) exit.complete(-1)
        channel.close()
        scope.launch {
            readerJob?.join()
            waiterJob?.join()
        }
        AgentLogger.i(TAG, "本机 user 后端已关闭")
    }

    override suspend fun awaitExit(): Int = exit.await()

    companion object {
        private const val TAG = "LocalUserBackend"

        /** Android 上的本机 shell 路径。 */
        const val DEFAULT_SHELL = "/system/bin/sh"

        /** 读缓冲（字符）；8192 与 pty 读循环同量级。 */
        private const val READ_BUFFER_CHARS = 8192
    }
}
