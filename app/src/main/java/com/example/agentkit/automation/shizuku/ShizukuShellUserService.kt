package com.example.agentkit.automation.shizuku

import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：由 Shizuku 在 **shell 进程（uid 2000）** 中实例化，代 App 执行命令。
 *
 * ## 为什么不用 `Shizuku.newProcess`
 * `newProcess` 返回的是**远端进程代理**（ShizukuRemoteProcess），它的定时
 * `waitFor(timeout, unit)` 未实现，调用即抛异常。我们把异常吞成 `false`，
 * 于是「启动失败」被误报成「超时」——真机日志里 `echo hello` 6ms 报 timeout。
 *
 * 这里换成官方推荐的 UserService：本类运行在 shell 进程内，`ProcessBuilder` 起的是
 * **本地进程**，`waitFor(timeout)` 语义正常，且进程常驻、无需每次跨 binder 建管道。
 * （AiCode 同款路线；Operit / Coomi 走的是 `newProcess` + 无参 `waitFor()`。）
 *
 * ## 本进程的限制（务必遵守）
 * 不是合法应用进程：**不能用 Context 相关能力**（getContentResolver / registerReceiver /
 * startActivity 都不行），只能用 Java 与系统级 API。类名与构造器会被 Shizuku 反射加载，
 * 不可混淆（见 proguard 规则）。
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {

    /** Shizuku 保留的销毁入口：unbindUserService(remove=true) 时触发，结束本进程。 */
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val result = Bundle()
        var process: Process? = null
        val started = System.currentTimeMillis()
        try {
            // 不用 redirectErrorStream：调用方需要区分「命令失败」与「通道失败」
            val proc = ProcessBuilder("/system/bin/sh", "-c", command).start()
            process = proc

            val stdout = CappedBuffer(MAX_BYTES)
            val stderr = CappedBuffer(MAX_BYTES)
            val tOut = Thread { runCatching { proc.inputStream.use { it.copyToCapped(stdout) } } }
            val tErr = Thread { runCatching { proc.errorStream.use { it.copyToCapped(stderr) } } }
            tOut.start()
            tErr.start()

            val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS.toInt())
            val finished = proc.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
            }
            tOut.join(READER_JOIN_MS)
            tErr.join(READER_JOIN_MS)

            result.putString(KEY_STDOUT, stdout.text())
            result.putString(KEY_STDERR, stderr.text())
            result.putInt(
                KEY_EXIT_CODE,
                if (finished) runCatching { proc.exitValue() }.getOrDefault(ShizukuResultLogic.EXIT_FAILURE)
                else ShizukuResultLogic.EXIT_TIMEOUT
            )
            result.putLong(KEY_ELAPSED_MS, System.currentTimeMillis() - started)
        } catch (e: Throwable) {
            result.putString(KEY_STDOUT, "")
            result.putString(KEY_STDERR, "${e.javaClass.simpleName}: ${e.message}")
            result.putInt(KEY_EXIT_CODE, ShizukuResultLogic.EXIT_FAILURE)
            result.putLong(KEY_ELAPSED_MS, System.currentTimeMillis() - started)
        } finally {
            runCatching { process?.destroy() }
        }
        return result
    }

    /** 带上限的输出缓冲：Binder 事务缓冲约 1MB，超限会抛 TransactionTooLargeException。 */
    private class CappedBuffer(private val cap: Int) {
        private val buf = ByteArrayOutputStream()
        private var truncated = false

        @Synchronized
        fun write(bytes: ByteArray, len: Int) {
            val room = cap - buf.size()
            if (room <= 0) {
                truncated = true
                return
            }
            if (len <= room) {
                buf.write(bytes, 0, len)
            } else {
                buf.write(bytes, 0, room)
                truncated = true
            }
        }

        @Synchronized
        fun text(): String {
            val s = String(buf.toByteArray(), Charsets.UTF_8)
            return if (truncated) "$s\n…（输出超过 ${cap / 1024}KB，已截断）" else s
        }
    }

    private fun java.io.InputStream.copyToCapped(sink: CappedBuffer) {
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            sink.write(chunk, n)
        }
    }

    companion object {
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_ELAPSED_MS = "elapsedMs"

        private const val MAX_BYTES = 256 * 1024
        private const val MAX_TIMEOUT_MS = 1_800_000L
        private const val READER_JOIN_MS = 2_000L
    }
}
