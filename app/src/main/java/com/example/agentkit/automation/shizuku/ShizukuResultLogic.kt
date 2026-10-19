package com.example.agentkit.automation.shizuku

/**
 * Shizuku UserService 执行结果的语义映射（纯函数，零 Android 依赖）。
 *
 * 为什么要单独抽出来：退出码是**约定**而非事实——`-1000` 表示「命令跑超时被杀」，
 * `-1` 表示「通道自己出错（进程没起来 / 读取异常）」。二者对模型的动作要求完全相反：
 * 超时该拆小命令，通道坏了该换路径且**不要重试**。以前的 bug 就是把「通道出错」
 * 当成「超时」，模型被 hint 骗着连试 12 次。
 *
 * harness: scripts/qa/ShizukuResultVerification.kt
 */
data class ShizukuServiceOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val startError: String?
)

object ShizukuResultLogic {

    /** 命令超时、进程被强杀时的退出码（与 UserService 实现约定）。 */
    const val EXIT_TIMEOUT = -1000

    /** 通道自身异常（启动失败 / 读取失败）时的退出码。 */
    const val EXIT_FAILURE = -1

    /**
     * @param stdout 标准输出（可能因上限被截断，截断标记由服务端追加）
     * @param stderr 标准错误
     * @param exitCode 服务端回传的退出码
     */
    fun map(stdout: String, stderr: String, exitCode: Int): ShizukuServiceOutcome = when (exitCode) {
        EXIT_TIMEOUT -> ShizukuServiceOutcome(
            exitCode = EXIT_TIMEOUT,
            stdout = stdout,
            stderr = stderr,
            timedOut = true,
            startError = null
        )
        EXIT_FAILURE -> ShizukuServiceOutcome(
            exitCode = EXIT_FAILURE,
            stdout = stdout,
            stderr = stderr,
            timedOut = false,
            startError = stderr.ifBlank { "UserService 执行异常（无错误输出）" }
        )
        else -> ShizukuServiceOutcome(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            timedOut = false,
            startError = null
        )
    }
}
