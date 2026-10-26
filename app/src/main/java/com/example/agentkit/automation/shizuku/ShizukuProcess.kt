package com.example.agentkit.automation.shizuku

import com.example.agentkit.core.AgentLogger
import rikka.shizuku.Shizuku

/**
 * W-56：Shizuku `newProcess` 的反射封装——把「怎么起一个 shell 身份的进程」收在一处。
 *
 * ## 为什么要反射
 *
 * Shizuku API 13 起把 `newProcess` 改成 private（12.x 也被 provider 的传递依赖顶到 13）。
 * 方法本身还在、签名也没变，只是不可见——这是目前唯一「不改用 UserService」的轻量路径。
 * 反射只在这一处做，`ShizukuShell.exec`（一次性）与 `ShizukuTerminalBackend`（持久）
 * 都走它，避免两份反射代码各自踩坑。
 *
 * ## 返回 null 而不是抛
 *
 * 未授权、binder 已死、厂商改签名——这些都不是「程序错误」，是环境不满足。
 * 调用方据此降级（一次性通道报错 / 会话池回落下一档），而不是把异常抛到 UI 层。
 */
internal object ShizukuProcess {

    private const val TAG = "ShizukuProcess"

    /**
     * 以 shell 身份起一个进程。
     *
     * @param argv 完整 argv，如 `arrayOf("sh")`（持久）或 `arrayOf("sh","-c",cmd)`（一次性）
     * @return 进程；反射失败或 Shizuku 拒绝时返回 null
     */
    fun open(argv: Array<String>): Process? = runCatching {
        val strArr = Class.forName("[Ljava.lang.String;")
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", strArr, strArr, String::class.java)
        m.isAccessible = true
        m.invoke(null, argv, null, null) as Process
    }.onFailure { e ->
        AgentLogger.w(TAG, "newProcess 失败：${e.javaClass.simpleName}: ${e.message}")
    }.getOrNull()
}
