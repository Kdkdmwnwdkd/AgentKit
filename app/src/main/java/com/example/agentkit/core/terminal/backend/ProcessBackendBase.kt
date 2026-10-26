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
import java.io.InputStreamReader

/**
 * W-56：以 `java.lang.Process` 为底的终端后端基类——把「起进程 + 读流 + 写 stdin + 回收」
 * 这段从具体来源里剥出来，子类只需要回答**进程从哪来**。
 *
 * ## 为什么抽这一层
 *
 * 本机 user shell（`ProcessBuilder`）与 Shizuku（`newProcess` 反射）在拿到 `Process` 之后，
 * 其余行为**完全一致**：一条常驻 shell、stdout/stderr 合并成文本 chunk、写入即执行、
 * 完成判定交给上层的哨兵协议。W-56 之前这段逻辑只存在于 `LocalUserBackend`；再复制一份
 * 到 Shizuku 后端就会变成「读循环有两份、close 语义有两份」，日后必然分叉。
 *
 * ## 数据模型
 *
 * 一条**常驻** shell 进程；[send] 写入一行（或多行）命令，[output] 持续吐出合并输出。
 * 退出码由上层哨兵 `echo "<哨兵>$?"` 决定，本类不解析命令边界。
 *
 * ## 线程契约
 *
 * [output] 由单一读协程投递（`Channel.UNLIMITED`，先产出后订阅也不丢）；
 * [send] 只写 stdin、不等待命令结束。
 *
 * ## 已知取舍
 *
 * 无 PTY：`Ctrl-C` 不是真中断、依赖终端能力的程序（分页器/颜色）行为受限——两条通道
 * 都一样，已写进 shell 工具描述。
 */
abstract class ProcessBackendBase(
    override val identity: TerminalIdentity = TerminalIdentity.User
) : TerminalBackend {

    private val channel = Channel<String>(Channel.UNLIMITED)

    override val output: Flow<String> = channel.receiveAsFlow()

    private var process: Process? = null
    private val exit = CompletableDeferred<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var waiterJob: Job? = null

    /**
     * 构造一条新的 shell 进程。
     *
     * 实现方约定：
     * - 返回的进程 stdout 应包含 stderr（`redirectErrorStream` 或等价），本类按单一流读；
     * - 失败返回 null（本类按启动失败处理，不抛）；
     * - 不得在此处做长时间阻塞（启动超时由调用方的会话开辟负责）。
     */
    protected abstract fun openProcess(): Process?

    /** 启动成功/失败时打什么日志（默认用 backendKey 拼，子类可覆盖）。 */
    protected open val startLog: String get() = "后端已启动：$backendKey"

    protected open val startFailLog: String get() = "后端启动失败：$backendKey"

    override val isAlive: Boolean
        get() = process?.isAlive == true

    override suspend fun start(): Boolean {
        if (process != null) return isAlive
        val started = runCatching {
            val p = openProcess() ?: return false
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
                    AgentLogger.w(TAG, "读取 $backendKey 输出异常：${t.message}")
                } finally {
                    channel.close()
                }
            }
            waiterJob = scope.launch {
                // 用轮询而不是 waitFor(timeout)：Shizuku 的远端 Process 代理对定时 waitFor
                // 会立刻抛异常（W-36 真机日志：echo hello 6ms 报 timeout）。exitValue 轮询
                // 对所有 Process 实现都成立。
                val code = awaitExitCode(p)
                if (!exit.isCompleted) exit.complete(code)
                channel.close()
            }
            true
        }.getOrElse { e ->
            AgentLogger.e(TAG, "$startFailLog（${e.javaClass.simpleName}: ${e.message}）")
            false
        }
        // 不记 pid：Android 的 java.lang.Process 没有实例 pid()（只有 Process.myPid()），
        // 反射取值不值得——日志里有 backendKey 已够定位。
        if (started) AgentLogger.i(TAG, startLog)
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
            AgentLogger.w(TAG, "写入 stdin 失败（$backendKey）：${e.message}")
            false
        }
    }

    override suspend fun close() {
        val p = process
        if (p != null) runCatching { p.destroy() }
        runCatching { p?.outputStream?.close() }
        if (!exit.isCompleted) exit.complete(-1)
        runCatching { channel.close() }
        scope.launch {
            runCatching { readerJob?.join() }
            runCatching { waiterJob?.join() }
        }
        AgentLogger.i(TAG, "后端已关闭：$backendKey")
    }

    override suspend fun awaitExit(): Int = exit.await()

    /** 阻塞直到进程结束（在 IO 协程里跑），带异常保护。 */
    private suspend fun awaitExitCode(p: Process): Int {
        while (true) {
            val code = runCatching { p.exitValue() }
            if (code.isSuccess) return code.getOrDefault(-1)
            if (!p.isAlive && runCatching { p.exitValue() }.isFailure) return -1
            kotlinx.coroutines.delay(EXIT_POLL_MS)
        }
    }

    companion object {
        private const val TAG = "ProcessBackend"

        /** 读缓冲（字符）；8192 与 pty 读循环同量级。 */
        private const val READ_BUFFER_CHARS = 8192

        /** 退出码轮询间隔：进程结束的感知延迟上限。 */
        private const val EXIT_POLL_MS = 200L
    }
}
