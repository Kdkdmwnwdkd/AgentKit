package com.example.agentkit.automation.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 提权执行（W-13 B 方案）。
 *
 * 前提：用户安装 Shizuku，并在 Shizuku 内「通过无线调试启动」，再回本 App 授权一次。
 * 授权后本对象可用 shell（uid 2000）执行命令：文件清理、pm、settings、screencap、input。
 *
 * 设计取舍：
 *  - 不持有长连接（Shizuku binder 自己管），每次执行起一个 `sh -c` 进程；
 *  - stdout/stderr 并发读取，避免大输出把管道塞死；
 *  - 超时强杀，返回 timedOut 标记（绝不假装成功）。
 */
object ShizukuShell {

    const val REQUEST_CODE = 4210

    /** Shizuku 服务是否在运行（用户是否启动过 Shizuku）。 */
    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已获得本 App 的授权。 */
    fun hasPermission(): Boolean =
        binderAlive() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 发起授权请求（需 App 在前台；结果通过 Shizuku.addRequestPermissionResultListener 回调）。 */
    fun requestPermission(): Boolean {
        if (!binderAlive()) return false
        return runCatching { Shizuku.requestPermission(REQUEST_CODE); true }.getOrDefault(false)
    }

    /** 可用性摘要（UI/能力链共用，避免两处判断漂移）。 */
    fun status(): Status = when {
        !binderAlive() -> Status.NOT_RUNNING
        !hasPermission() -> Status.NO_PERMISSION
        else -> Status.READY
    }

    enum class Status { NOT_RUNNING, NO_PERMISSION, READY }

    data class ShellOut(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /**
     * 以 shell 身份执行命令。未授权/未运行时返回 exitCode=-1 且 stderr 说明原因，
     * 调用方据此决定是否回退本机 user shell。
     */
    fun exec(command: String, timeoutSec: Long = 20): ShellOut {
        if (!hasPermission()) {
            return ShellOut(-1, "", "Shizuku 未运行或未授权（请先在 Shizuku 内启动并授权本应用）")
        }
        val process = runCatching {
            Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        }.getOrElse {
            return ShellOut(-1, "", "启动 shell 失败：${it.message}")
        }

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val tOut = Thread { runCatching { process.inputStream.use { it.copyTo(out) } } }
        val tErr = Thread { runCatching { process.errorStream.use { it.copyTo(err) } } }
        tOut.start()
        tErr.start()

        val finished = runCatching { process.waitFor(timeoutSec, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            tOut.join(300)
            tErr.join(300)
            return ShellOut(-1, out.text(), err.text(), timedOut = true)
        }
        tOut.join(500)
        tErr.join(500)
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        return ShellOut(code, out.text(), err.text())
    }

    private fun ByteArrayOutputStream.text(): String = String(toByteArray(), Charsets.UTF_8)
}
