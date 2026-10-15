package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.terminal.TERMINAL_HANDLE_REGEX
import com.example.agentkit.core.terminal.TerminalSessionPool
import com.example.agentkit.core.terminal.backend.TerminalIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Phase 1 补口 · shell 工具集（T08 上层出口）。
 *
 * 在 [TerminalSessionPool]（T08）之上包一层 [AgentTool]，把「终端会话池」暴露给模型：
 *   - shell_run  ：在会话里跑一条命令（无 session 则先开辟）；带 identity 与 session 句柄参数；
 *   - shell_close：关闭不再使用的会话（保持会话表干净，避免后端进程泄漏）。
 *
 * 设计要点：
 *   - **上层无「容器还是本机」分支**：本文件只认 [TerminalSessionPool] 的 4-hex 句柄与
 *     [TerminalIdentity]，具体走 proot 还是本机 shell 由 T07 后端抽象内部决定；
 *   - **零 Android 依赖**：本文件不 import 任何 `android.*`，故可在 JVM harness 直接验证
 *     参数解析 / 句柄校验逻辑；
 *   - 另提供 [terminalShellCommandRunner]：把会话池适配成 app_stop 需要的 [ShellCommandRunner]
 *     扩展点（接上后 am force-stop 走真实本机 shell，通道不可用时仍诚实降级）。
 */

/** shell_run 默认是否优先 proot 容器（不可用回落本机 user）。 */
private const val SHELL_RUN_DEFAULT_PREFER_PROOT = true

/** 会话池化 shell 的执行器：`am force-stop` 等系统命令经此走真实本机 shell。 */
private const val SHELL_BRIDGE_ALIAS = "app_control"

/**
 * shell_run：在一条**可复用**的终端会话里执行命令。
 *
 * 参数：
 *   - command（必填）：要执行的命令原文（一行或整体一次下发）；
 *   - identity（可选）：执行身份，取值 user/su/shizuku/ssh，默认 user（首期仅 user 有后端）；
 *   - session（可选）：复用既有会话的 4 位十六进制句柄；缺省则自动开辟一条并返回其句柄；
 *   - prefer_proot（可选）：是否优先 proot 容器，默认 true（不可用自动回落本机 shell）。
 *
 * 返回：会话句柄 + 退出码 +（已截断的）命令输出。**复用同一 session 可保持 cwd / 环境变量**。
 */
class ShellRunTool(
    private val pool: TerminalSessionPool,
    private val defaultPreferProot: Boolean = SHELL_RUN_DEFAULT_PREFER_PROOT
) : AgentTool {

    override val name = "shell_run"
    override val description =
        "在终端会话里执行一条 shell 命令并返回输出与退出码。无 session 时自动开辟一条会话并返回其句柄；" +
            "复用同一 session 句柄可保持工作目录与环境变量（多轮 cd/export 生效）。identity 取值 " +
            "user/su/shizuku/ssh（首期仅 user 可用），prefer_proot 是否优先容器（默认 true，不可用回落本机 shell）。" +
            "无 PTY/交互支持：不要用需要交互输入的命令（如不带 -y 的 apt、分页器 less），必要时改用非交互参数。" +
            "注意：shell_run 不能用来操控界面——dumpsys、am start、settings put/get、input tap 都会因权限不足失败；" +
            "操控界面请改用 ui_tree/ui_tap/ui_click 等 ui_* 工具。"
    override val systemPrompt =
        "用 shell_run 执行终端命令。首次不带 session 调用会返回一个 4 位十六进制句柄，后续把该句柄作为 " +
            "session 传入即可在同一 shell 里继续操作（cwd 与 export 的环境变量会保持）。不再需要的会话用 " +
            "shell_close 关闭。命令输出过长会被截断（只保留头尾），必要时拆小命令分批查看。" +
            "shell_run 只用于文件读写、命令执行、查看本机信息，**不能操控界面**" +
            "（dumpsys/am start/settings put|get 都会因权限不足失败）；操控界面请用 ui_* 工具。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("command", "string", "要执行的 shell 命令原文（不要预先转义，不要包含命令结尾换行）"),
        JsonSchema.prop(
            "identity", "string", "执行身份：user/su/shizuku/ssh，默认 user（首期仅 user 有后端）",
            enum = listOf("user", "su", "shizuku", "ssh")
        ),
        JsonSchema.prop("session", "string", "复用的会话句柄（4 位十六进制），缺省则自动开辟新会话"),
        JsonSchema.prop("prefer_proot", "boolean", "是否优先 proot 容器，默认 true（不可用自动回落本机 shell）")
    )

    /** 写操作：shell 命令可能改变设备状态，保守走串行批次。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 command",
                hint = "shell_run 必须提供 command，例如 `pwd` 或 `ls -la`。"
            )
        if (command.isBlank()) {
            return ToolResult.fail(
                code = "empty_command",
                message = "command 为空。",
                hint = "提供非空的 command，例如 `pwd`。"
            )
        }

        // identity：未给 = null（沿用会话身份 / 开辟时默认 user）；给了但非法 = 显式报错
        val rawIdentity = args["identity"]?.jsonPrimitive?.contentOrNull
        val declaredIdentity: TerminalIdentity? = when {
            rawIdentity.isNullOrBlank() -> null
            else -> TerminalIdentity.fromWire(rawIdentity) ?: return ToolResult.fail(
                code = "bad_identity",
                message = "无效身份「$rawIdentity」。",
                hint = "identity 取值必须是 user、su、shizuku、ssh 之一（首期仅 user 可用）。"
            )
        }

        val preferProot = args["prefer_proot"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: defaultPreferProot

        val sessionArg = args["session"]?.jsonPrimitive?.contentOrNull?.trim()

        val handle: String
        var openedNew = false
        if (!sessionArg.isNullOrBlank()) {
            if (!sessionArg.matches(TERMINAL_HANDLE_REGEX)) {
                return ToolResult.fail(
                    code = "bad_handle",
                    message = "会话句柄「$sessionArg」格式非法。",
                    hint = "句柄恒为 4 位十六进制（如 1a2f）。不要自造句柄——不带 session 调用一次即可获得。"
                )
            }
            if (pool.lookup(sessionArg) == null) {
                return ToolResult.fail(
                    code = "no_such_session",
                    message = "会话句柄「$sessionArg」不存在。",
                    hint = "会话可能已关闭或被回收；不带 session 重新调用一次以开辟新会话。"
                )
            }
            handle = sessionArg
        } else {
            val opened = pool.open(
                identity = declaredIdentity ?: TerminalIdentity.User,
                alias = null,
                preferProot = preferProot
            )
            val h = opened.handle
            if (!opened.ok || h == null) {
                return ToolResult.fail(
                    code = opened.errorCode ?: "open_failed",
                    message = opened.message.ifBlank { "开辟终端会话失败。" },
                    hint = opened.hint ?: "确认设备可执行本机 shell；若期望容器请先准备 rootfs。"
                )
            }
            openedNew = true
            handle = h
        }

        val result = pool.run(handle, command, identity = declaredIdentity)
        if (!result.ok) {
            return ToolResult.fail(
                code = result.errorCode ?: "exec_failed",
                message = result.message.ifBlank { "命令执行失败。" },
                hint = result.hint ?: "检查命令写法后重试；会话失效时重新开辟会话。"
            )
        }

        val info = pool.lookup(handle)
        val header = buildString {
            append("会话 ").append(handle)
            info?.let { append("（身份 ").append(it.identity.wire).append("，池 ").append(it.backendKey).append("）") }
            append(" 退出码 ").append(result.exitCode)
            if (openedNew) append("，已开辟新会话")
            if (result.truncated) append("，输出已截断")
        }
        return ToolResult.ok(
            code = "shell_ok",
            message = header,
            data = result.output.ifBlank { "(命令无输出)" }
        )
    }
}

/**
 * shell_close：关闭不再使用的终端会话（幂等释放后端进程 + 别名槽位）。
 *
 * 参数：session（必填）——要关闭的 4 位十六进制会话句柄。
 */
class ShellCloseTool(private val pool: TerminalSessionPool) : AgentTool {

    override val name = "shell_close"
    override val description =
        "关闭一个不再使用的终端会话，释放其后端进程。session 传 shell_run 返回的 4 位十六进制句柄。"
    override val systemPrompt = "不再需要某条终端会话时用 shell_close 关闭它，避免后端进程长期占用。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("session", "string", "要关闭的会话句柄（4 位十六进制）")
    )

    /** 写操作：释放资源，改变会话表状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val session = args["session"]?.jsonPrimitive?.contentOrNull?.trim()
        if (session.isNullOrBlank()) {
            return ToolResult.fail(
                code = "missing_param",
                message = "缺少 session",
                hint = "shell_close 必须提供 session（4 位十六进制句柄）。"
            )
        }
        if (!session.matches(TERMINAL_HANDLE_REGEX)) {
            return ToolResult.fail(
                code = "bad_handle",
                message = "会话句柄「$session」格式非法。",
                hint = "句柄恒为 4 位十六进制（如 1a2f）。"
            )
        }
        val closed = pool.close(session)
        return if (closed) {
            ToolResult.ok(code = "shell_closed", message = "已关闭会话 $session。")
        } else {
            ToolResult.fail(
                code = "no_such_session",
                message = "会话句柄「$session」不存在或已关闭。",
                hint = "无需重复关闭；如需新会话请用 shell_run（不带 session）。"
            )
        }
    }
}

/**
 * 把 [TerminalSessionPool] 适配成 app_stop 的 shell 通道 [ShellCommandRunner] 扩展点。
 *
 * 接上后 `am force-stop` 经**真实本机 shell**执行；会话池无可用后端时返回 null，
 * app_stop 仍走原有「诚实降级为需用户手动停止」路径。默认 `preferProot=false`——`am`
 * 是针对**宿主 Android**的命令，应走本机 user shell 而非容器。
 *
 * @param pool 会话池
 * @param alias 桥接会话的别名（默认 app_control；同池内复用同一 shell，省去反复 fork）
 * @param preferProot 是否优先容器（默认 false）
 */
fun terminalShellCommandRunner(
    pool: TerminalSessionPool,
    alias: String = SHELL_BRIDGE_ALIAS,
    preferProot: Boolean = false
): ShellCommandRunner = ShellCommandRunner { command ->
    runCatching {
        val opened = pool.open(TerminalIdentity.User, alias, preferProot)
        val handle = opened.handle ?: return@runCatching null
        val result = pool.run(handle, command)
        if (result.ok) {
            ShellOutcome(result.exitCode, result.output)
        } else {
            // 池层失败：给非零退出码 + 失败说明，app_stop 据此回报失败（不假装成功）
            ShellOutcome(
                exitCode = result.exitCode.coerceAtLeast(1),
                output = listOfNotNull(
                    result.message.ifBlank { null },
                    result.hint?.ifBlank { null }
                ).joinToString("\n")
            )
        }
    }.getOrNull()
}
