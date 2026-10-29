package com.example.agentkit.core.terminal.backend

/**
 * W-RT5：SSH 远端 shell 的纯函数层（零 IO/Android 依赖，harness 真编真跑）。
 *
 * 设计（对标 Operit「远程执行」的思路，净室重写）：
 *  - **不引入 SSH 协议库**（零新增依赖纪律）：传输直接复用现成的 `ssh` 命令行客户端——
 *    本类只负责「档案校验 + argv 拼装」，起进程/读流/哨兵判定全部由 [ProcessBackendBase]
 *    与会话池既有逻辑承担，SSH 与本机/容器在 [TerminalBackend] 接口之后完全同构；
 *  - **仅密钥登录**（BatchMode=yes）：密码交互提示会让无 PTY 的常驻会话永远卡死，
 *    也避免密码出现在任何配置/日志里；不支持密码认证是安全边界，不是缺陷；
 *  - **SSH 不进自动降级链**：远端机器的能力面与手机完全不同（pm/settings 等本机命令
 *    在远端毫无意义），混进 proot → shizuku → user 的候选链会让工具静默跑到别的机器上。
 *    所以 SSH 走显式选择：`identity=ssh` 的 shell 调用经会话池 `obtainByKey` 单独路由。
 */

/** 一条 SSH 连接档案（v1 单档案）：主机、端口、用户名、私钥路径（可选）。 */
data class SshProfile(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val user: String,
    val keyPath: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 22
    }
}

/** 档案校验结果：[Ok]（带规范化后的档案）或 [Invalid]（带给用户看的理由）。 */
sealed class SshProfileResult {
    data class Ok(val profile: SshProfile) : SshProfileResult()
    data class Invalid(val reason: String) : SshProfileResult()
}

/**
 * 校验并规范化 SSH 档案（纯函数）：
 *  - host/user 必填、trim 后不含空白（主机名进 argv，空格必然是手误）；
 *  - port 允许「22」「 22 」这类文本，范围 1–65535；空串按默认 22；
 *  - keyPath 可选（系统默认 `~/.ssh/id_*` 由远端/本地 ssh 配置决定）。
 */
fun normalizeSshProfile(host: String, portText: String, user: String, keyPath: String): SshProfileResult {
    val h = host.trim()
    val u = user.trim()
    val k = keyPath.trim()
    if (h.isEmpty()) return SshProfileResult.Invalid("主机地址不能为空")
    if (h.contains(Regex("""\s"""))) return SshProfileResult.Invalid("主机地址不能包含空格")
    if (u.isEmpty()) return SshProfileResult.Invalid("用户名不能为空")
    if (u.contains(Regex("""\s"""))) return SshProfileResult.Invalid("用户名不能包含空格")
    val port = if (portText.isBlank()) SshProfile.DEFAULT_PORT else portText.trim().toIntOrNull()
        ?: return SshProfileResult.Invalid("端口必须是数字（1–65535）")
    if (port !in 1..65535) return SshProfileResult.Invalid("端口必须在 1–65535 之间")
    return SshProfileResult.Ok(SshProfile(host = h, port = port, user = u, keyPath = k))
}

/**
 * 拼装 ssh 命令 argv（纯函数，与 [SshBackend] 的实际执行一致）。
 *
 * 选项说明：
 *  - `BatchMode=yes`：任何密码提示直接失败而不是挂住会话（见类注释「仅密钥登录」）；
 *  - `StrictHostKeyChecking=accept-new`：首次连接自动接受新主机指纹，
 *    已知主机变更仍然拒绝——防中间人同时不让首次使用卡在确认提示上；
 *  - `ConnectTimeout=10`：起会话的开辟探测有超时预算，网络不通快速失败回落报错。
 */
fun buildSshArgs(profile: SshProfile, sshPath: String): List<String> = buildList {
    add(sshPath)
    add("-o"); add("BatchMode=yes")
    add("-o"); add("StrictHostKeyChecking=accept-new")
    add("-o"); add("ConnectTimeout=10")
    if (profile.keyPath.isNotBlank()) { add("-i"); add(profile.keyPath) }
    add("-p"); add(profile.port.toString())
    add("${profile.user}@${profile.host}")
}
