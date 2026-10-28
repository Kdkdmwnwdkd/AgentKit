package com.example.agentkit.tools

import com.example.agentkit.core.llm.primOrNull
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.agent.ShellCommandRules
import com.example.agentkit.core.terminal.TERMINAL_HANDLE_REGEX
import com.example.agentkit.core.terminal.TerminalSessionPool
import com.example.agentkit.core.terminal.backend.TerminalBackend
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

/**
 * 提权通道的执行结果（W-13 B 方案：Shizuku / uid 2000）。
 *
 * 与 [com.example.agentkit.core.terminal.TerminalSessionPool] 的结果解耦：提权通道是**一次性**
 * 执行（无持久会话），语义只有「退出码 + stdout + stderr + 是否超时」。
 */
data class PrivilegedShellOut(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    /** 通道启动即失败的真实原因（null = 进程起来了）。 */
    val startError: String? = null,
    val elapsedMs: Long = 0
)

/**
 * 纯函数：这条 shell_run 是否应走**一次性提权通道**（W-13 的 `sh -c` 路径）。
 *
 * ## W-57 起默认不再走它
 *
 * 原规则是「identity 未给 → 通道就绪时自动优先」，理由是「uid 2000 能干的活最多」。
 * 但那条通道一条命令一个进程，`cd` / `export` 不跨调用保持（W-54 只是把它说清楚了）。
 * W-56 把 Shizuku 做成了**会话池后端**之后，「uid 2000」和「持久 shell」不再二选一——
 * 所以默认应当进池，由候选链 proot → shizuku → adb → user 自己挑，且 cwd 保有。
 *
 * 现在的规则：
 *  - 带了 session 句柄 → **不走**（会话已绑定某个后端，中途换通道会让 cwd/环境变量语义断裂）；
 *  - identity 显式给了 shizuku → 走（调用方明确表示「就要一条命令、不要开会话」；
 *    池的 `TerminalIdentity.Shizuku` 首期无后端，只能靠这条通道拿到 uid 2000）；
 *  - 其余（含 identity 未给）→ 不走，交给会话池。
 *
 * 注意：**「要 uid 2000」不必非走这里**——传 `prefer_proot=false` 进池，候选链会跳过容器、
 * 优先 shizuku / adb，同样是 uid 2000 但**持久**。这条通道只在「不想留会话」时才划算。
 */
fun shouldUsePrivilegedShell(
    identity: String?,
    hasSession: Boolean,
    privilegedAvailable: Boolean
): Boolean {
    if (hasSession || !privilegedAvailable) return false
    return identity == "shizuku"
}

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
    private val defaultPreferProot: Boolean = SHELL_RUN_DEFAULT_PREFER_PROOT,
    /**
     * 提权通道（W-13 B 方案）：Shizuku 就绪时由外部注入，执行 `sh -c <cmd>`（uid 2000）。
     * 返回 null = 通道此刻不可用（未运行/未授权/启动失败）→ 调用方回落到普通会话池。
     *
     * 用函数注入而不是直接 import Shizuku：本文件保持零 Android 依赖，可离线 harness 断言路由。
     */
    private val privilegedExec: (suspend (String) -> PrivilegedShellOut?)? = null
) : AgentTool {

    override val name = "shell_run"
    override val description =
        "在终端会话里执行一条 shell 命令并返回输出与退出码。无 session 时自动开辟一条会话并返回其句柄；" +
            "复用同一 session 句柄可保持工作目录与环境变量（多轮 cd/export 生效）。" +
            "**默认就进会话池**：按 proot 容器 → Shizuku（uid 2000）→ 无线调试（uid 2000）→ 本机 shell " +
            "的顺序试，起不来的自动跳到下一档。" +
            "要系统权限（pm/settings/appops 等）请传 prefer_proot=false：跳过容器、优先 Shizuku/无线调试，" +
            "同样是 uid 2000 但 cwd 会保持。" +
            "identity=shizuku 是**例外**：走一次性提权通道，不返回句柄、cd/export 不跨调用保持——" +
            "只在「跑一条命令、不想留会话」时用。" +
            "无 PTY/交互支持：不要用需要交互输入的命令（如不带 -y 的 apt、分页器 less），必要时改用非交互参数。" +
            "注意：shell_run 不能用来操控界面——dumpsys、am start、settings put/get、input tap 都会因权限不足失败；" +
            "操控界面请改用 ui_tree/ui_tap/ui_click 等 ui_* 工具。"
    override val systemPrompt =
        "用 shell_run 执行终端命令。首次不带 session 调用会返回一个 4 位十六进制句柄，后续把该句柄作为 " +
            "session 传入即可在同一 shell 里继续操作（cwd 与 export 的环境变量会保持）。不再需要的会话用 " +
            "shell_close 关闭。命令输出过长会被截断（只保留头尾），必要时拆小命令分批查看。" +
            "要执行 pm / settings / appops 这类需要系统权限的命令，传 prefer_proot=false：" +
            "会话池会跳过容器、优先 Shizuku 或无线调试（uid 2000），且 cwd 与环境变量照常保持。" +
            "identity=shizuku 走的是一次性通道（一条命令一个进程），cd/export 不跨调用保持，" +
            "只适合跑完就走的单条命令。" +
            "shell_run 只用于文件读写、命令执行、查看本机信息，**不能操控界面**" +
            "（dumpsys/am start/settings put|get 都会因权限不足失败）；操控界面请用 ui_* 工具。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("command", "string", "要执行的 shell 命令原文（不要预先转义，不要包含命令结尾换行）"),
        JsonSchema.prop(
            "identity", "string", "执行身份：user/su/shizuku/ssh，默认走会话池（user 身份）。" +
                "仅有 shizuku 是特殊值：走一次性提权通道（无句柄、cd 不保持）",
            enum = listOf("user", "su", "shizuku", "ssh")
        ),
        JsonSchema.prop("session", "string", "复用的会话句柄（4 位十六进制），缺省则自动开辟新会话"),
        JsonSchema.prop(
            "prefer_proot", "boolean",
            "是否优先 proot 容器，默认 true。传 false 会跳过容器、优先 Shizuku / 无线调试（uid 2000），" +
                "适合 pm / settings 等系统命令"
        )
    )

    /** 写操作：shell 命令可能改变设备状态，保守走串行批次。 */
    override val readOnly = false

    /** A 批：shell 是最危险的工具之一，执行前必须过人工审批（会话内可按「shell 命令」整组放行）。 */
    override val needsApproval = true

    /**
     * W-79：审批细化到命令内容。
     *
     * `needsApproval = true` 是兜底（规则看不懂的命令一律问）；但 `ls`/`cat`/`grep`/`pwd`
     * 这类纯读命令**不该**弹窗——每一条都问，用户三天就会形成「见窗就点允许」的习惯，
     * 真正危险的 `rm -rf` 也就没人看了。判定逻辑见 [ShellCommandRules]（纯函数、有单测）。
     */
    override fun needsApprovalFor(args: JsonObject): Boolean {
        val command = args["command"]?.primOrNull()?.contentOrNull.orEmpty()
        return ShellCommandRules.needsApproval(command)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args["command"]?.primOrNull()?.contentOrNull
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
        val rawIdentity = args["identity"]?.primOrNull()?.contentOrNull
        val declaredIdentity: TerminalIdentity? = when {
            rawIdentity.isNullOrBlank() -> null
            else -> TerminalIdentity.fromWire(rawIdentity) ?: return ToolResult.fail(
                code = "bad_identity",
                message = "无效身份「$rawIdentity」。",
                hint = "identity 取值必须是 user、su、shizuku、ssh 之一（首期仅 user 可用）。"
            )
        }

        val preferProot = args["prefer_proot"]?.primOrNull()?.contentOrNull
            ?.toBooleanStrictOrNull() ?: defaultPreferProot

        val sessionArg = args["session"]?.primOrNull()?.contentOrNull?.trim()

        // —— 提权通道（W-13）：一次性执行，不占用会话池 ——
        val privileged = privilegedExec
        if (privileged != null &&
            shouldUsePrivilegedShell(
                identity = rawIdentity,
                hasSession = !sessionArg.isNullOrBlank(),
                privilegedAvailable = true
            )
        ) {
            val out = privileged(command)
            if (out != null) {
                // W-36：通道**启动失败** ≠ 命令**超时**。原来二者共用 code=timeout 与
                // 「拆小命令重试」的 hint，模型被骗着反复重试（真机日志：`echo hello`
                // 6ms 报 timeout，连试 12 次，40 步用光任务没完成）。
                out.startError?.let { why ->
                    return ToolResult.fail(
                        code = "privileged_unavailable",
                        message = "提权通道（Shizuku）不可用：$why",
                        hint = "不要重试。依次确认：① Shizuku 里已点「通过无线调试启动」且服务在运行；" +
                            "② 本应用已在 Shizuku 内授权（授权后需回本应用点一次「检测」）；" +
                            "③ 仍不行就传 prefer_proot=false 走无线调试（同样是 uid 2000），" +
                            "或直接用默认参数（普通文件操作不需要提权）。",
                        data = why
                    )
                }
                if (out.timedOut) {
                    // 秒级「超时」= 进程根本没起来，不是命令跑得慢——两者对模型的动作要求完全不同
                    if (out.elapsedMs < 1_500 && out.stdout.isBlank() && out.stderr.isBlank()) {
                        return ToolResult.fail(
                            code = "privileged_unavailable",
                            message = "提权通道（Shizuku）无响应（${out.elapsedMs}ms 内即断）。",
                            hint = "这不是命令慢，是通道没通。不要原样重试——传 prefer_proot=false 改走无线调试" +
                                "（同为 uid 2000），或直接用默认参数；" +
                                "需要提权时去「自动化」页按提示重新启动 Shizuku 的无线调试服务。"
                        )
                    }
                    return ToolResult.fail(
                        code = "timeout",
                        message = "提权通道执行超时（已等待 ${out.elapsedMs / 1000} 秒，命令可能仍在后台运行）。",
                        hint = "拆小命令或加超时参数后重试；需要长时间运行的请放到后台并另行查询。"
                    )
                }
                val denied = out.stderr.contains("Permission Denial", ignoreCase = true) ||
                    out.stderr.contains("SecurityException", ignoreCase = true) ||
                    out.stderr.contains("requires android.permission", ignoreCase = true)
                val output = out.stdout.ifBlank { out.stderr }
                if (denied) {
                    return ToolResult.fail(
                        code = "permission_denied",
                        message = "提权通道（Shizuku）退出码 ${out.exitCode}（系统拒绝）",
                        // W-58：本通道已是 uid 2000，被拒只剩「要真 root」一种解释；
                        // 与池路径共用同一份分档文案，两处不再各自演化
                        hint = permissionDeniedHint(TerminalBackend.KEY_SHIZUKU),
                        data = output.take(400)
                    )
                }
                // W-54：这条通道是**一次性执行**（一条命令一个远端进程），cd/export 不会留到
                // 下一次调用。命令里带了状态变更时必须明说，否则模型会以为 cwd 已切换，
                // 后续命令全在默认目录跑、还以为是路径写错了——与 W-36「启动失败被误报成超时」
                // 同一类问题：不是执行错，是语义被误读。
                val stateHint = if (mutatesShellState(command)) {
                    "\n\n注意：本通道为一次性执行，本次的 cd / export 只在本次生效，" +
                        "下一次调用回到默认工作目录。要继续以 uid 2000 连续操作，改用 prefer_proot=false " +
                        "开一个会话（走 Shizuku / 无线调试，cwd 会保持），再复用返回的句柄。"
                } else {
                    ""
                }
                val header = "通道 Shizuku（uid 2000）退出码 ${out.exitCode}$stateHint"
                return if (out.exitCode == 0) {
                    ToolResult.ok(code = "shell_ok", message = header, data = output.ifBlank { "(命令无输出)" })
                } else {
                    ToolResult.fail(
                        code = "shell_failed",
                        message = header,
                        hint = "看 stderr 定位原因；命令本身失败时修正参数重试，不要原样重复。",
                        data = output.take(2_000)
                    )
                }
            }
            // null = 通道此刻不可用（授权被收回等）→ 继续走普通会话池，由普通路径如实报权限不足
        }

        val handle: String
        var openedNew = false
        /** 传进来的句柄格式合法但已失效时，记下来在返回里说清楚（不静默替换）。 */
        var staleHandle: String? = null
        val arg = sessionArg?.takeIf { it.isNotBlank() }
        if (arg != null && !arg.matches(TERMINAL_HANDLE_REGEX)) {
            return ToolResult.fail(
                code = "bad_handle",
                message = "会话句柄「$arg」格式非法。",
                hint = "句柄恒为 4 位十六进制（如 1a2f）。不要自造句柄——不带 session 调用一次即可获得。"
            )
        }
        if (arg != null && pool.lookup(arg) != null) {
            handle = arg
        } else {
            // W-63：句柄失效不再直接失败。
            //
            // 真机 15:16：App 重启后模型沿用了历史对话里的「b69f」，工具报 no_such_session，
            // 整轮白跑——用户只看到一张红色失败卡片，还得再发一次。语义上「句柄没了」
            // 就是「没有会话」，用户要的是把命令跑起来，不是先看一条错误。
            //
            // 但**必须说出来**：新会话的 cwd 是默认目录，原来 `cd` 过的位置没了。
            // 静默替换会让模型以为还在老目录里，后续命令全跑偏（比直接失败更难查）。
            if (arg != null) staleHandle = arg
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
            staleHandle?.let {
                append("（原句柄 ").append(it)
                    .append(" 已失效——App 重启或会话被回收，工作目录已重置为默认）")
            }
            if (result.truncated) append("，输出已截断")
        }
        // 权限拒绝显式化（真机 2026-10-15 19:04 实证：`settings put system font_scale 2.0`
        // 在 user shell 下退出码 255，但输出里是 SecurityException/Permission Denial——
        // 老逻辑把它当 shell_ok 原样回喂，模型误以为「命令跑过了」，紧接着再试一次，
        // 白扔两步。这里识别系统拒绝指纹，直接转 permission_denied 失败，
        // hint 明确告诉模型：此路不通，改走 UI 操作。
        val denied =
            result.output.contains("Permission Denial", ignoreCase = true) ||
                result.output.contains("SecurityException", ignoreCase = true) ||
                result.output.contains("requires android.permission", ignoreCase = true)
        if (denied) {
            return ToolResult.fail(
                code = "permission_denied",
                message = header + "（系统拒绝：权限不足）",
                // W-58：原来一律指向「改走 ui_*」。但 pm / settings / appops 根本没法靠点击替代，
                // 而 W-57 之后默认进 proot（容器内没有系统服务），这条 hint 会把模型直接劝退。
                // 现在按当前后端分档：容器 / 本机 user 都先指 prefer_proot=false；已是 uid 2000
                // 的才说「需要真 root，别重试」——避免死循环。
                hint = permissionDeniedHint(info?.backendKey),
                data = result.output.take(400)
            )
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
        val session = args["session"]?.primOrNull()?.contentOrNull?.trim()
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

/**
 * W-54：这条命令是否会改变 shell 状态（工作目录 / 环境变量）。
 *
 * 只对「一次性通道」有意义：[ShellTool] 的 Shizuku 分支一条命令一个远端进程，
 * `cd` / `export` 不会留到下一次调用。函数用来决定要不要在返回文案里加那句提示——
 * 没有状态变更的普通命令（`ls` / `cat` / `pm list`）不加，避免每条都啰嗦。
 *
 * 纯函数：零 Android 依赖，可在 JVM harness 直接断言。
 */
internal fun mutatesShellState(command: String): Boolean {
    val c = command.trim()
    if (c.isEmpty()) return false
    // 语句开头或 `;` `&&` `||` `|` 之后出现 cd / pushd / popd
    if (Regex("(^|[;&|]\\s*)(cd|pushd|popd)(\\s|$)").containsMatchIn(c)) return true
    if (Regex("(^|[;&|]\\s*)(export|unset)(\\s|$)").containsMatchIn(c)) return true
    // 变量赋值前缀（`FOO=bar cmd` 或 `FOO=bar`）也算改环境
    if (Regex("(^|[;&|]\\s*)[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(c)) return true
    return false
}


/**
 * W-58：`permission_denied` 的分档修复指引。
 *
 * 为什么需要分档：命令被系统拒绝有**三种完全不同的原因**，混成一句话就会把模型引向错误方向：
 *
 * | 当前后端 | 真实原因 | 正确下一步 |
 * |---|---|---|
 * | proot | 容器是 fake root，看不到 Android 系统服务（`pm` / `settings` / `appops` 在里面根本不存在） | **`prefer_proot=false` 换 uid 2000** |
 * | user | App 身份只有 `u0_aXXX`，没有 `android.permission.*` 之外的任何系统权限 | **`prefer_proot=false` 换 uid 2000** |
 * | shizuku / adb | 已经是 ADB 级（uid 2000），仍被拒 = 要真 root 或系统签名 | 别再重试，走 UI 或让用户手动 |
 *
 * 原来一律说「改走 ui_*」：对 `pm` / `settings` 这类命令根本没有等价点击路径，
 * 模型被劝退后任务就卡住了。而 W-57 让默认路径进 proot 之后，这种情况反而变多了。
 *
 * 纯函数：零 Android 依赖，可在 JVM harness 直接断言。
 *
 * @param poolKey 当前会话的后端键（`proot` / `user` / `shizuku` / `adb-wireless`；
 *   null = 会话已消失或查不到，走通用文案）
 */
internal fun permissionDeniedHint(poolKey: String?): String = when (poolKey) {
    TerminalBackend.KEY_SHIZUKU, TerminalBackend.KEY_ADB ->
        "当前会话**已经是 ADB 级权限**（uid 2000），仍被系统拒绝说明这条操作需要真 root 或系统签名。" +
            "不要重试同一命令——改走无障碍 UI 路径：ui_tree 读界面 → ui_click/ui_tap 点对应项；" +
            "也可以如实告知用户「该操作需要手动完成」。"

    TerminalBackend.KEY_PROOT ->
        "容器内是 fake root，**不等于**宿主 root：容器看不到 Android 系统服务，pm / settings / appops 在里面无效。" +
            "**先传 prefer_proot=false 重试一次**——会话池会改走 Shizuku / 无线调试（uid 2000），" +
            "这类命令在那条通道上可行。若返回里仍是 pool=user，说明两条调试通道都没配置，" +
            "再改走 UI：ui_tree → ui_click/ui_tap。"

    TerminalBackend.KEY_USER ->
        "本机 App 身份没有系统权限。若已配置 Shizuku 或无线调试，**传 prefer_proot=false 重试一次**" +
            "（会话池会优先 uid 2000 的通道）；两者都未配置时只能改走 UI：ui_tree → ui_click/ui_tap。"

    else ->
        "该命令需要比当前身份更高的权限。若已配置 Shizuku / 无线调试，传 prefer_proot=false 重试一次；" +
            "否则改走 UI：ui_tree → ui_click/ui_tap。"
}
