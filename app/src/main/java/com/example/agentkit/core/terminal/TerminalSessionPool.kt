package com.example.agentkit.core.terminal

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.core.terminal.backend.TerminalBackendFactory
import com.example.agentkit.core.terminal.backend.TerminalBackendSource
import com.example.agentkit.core.terminal.backend.TerminalIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 会话对外句柄的格式约束：**恒 4 位十六进制小写**（`^[0-9a-f]{4}$`）。
 *
 * 用短 ID 对外、内部真身（后端实例）不外露，目的是**省 token**（模型每次传 4 字符而非 UUID）
 * 与**防泄露内部结构**。公开为顶层常量，供工具层做「句柄格式」前置校验，避免拿非法字符串
 * 直接查表。
 */
val TERMINAL_HANDLE_REGEX = Regex("^[0-9a-f]{4}$")

/**
 * 会话开辟结果（T08）。
 *
 * @param ok 是否成功
 * @param handle 成功时的对外句柄（[TERMINAL_HANDLE_REGEX]）；失败为 null
 * @param identity 该会话**固化**的身份（开辟时写入，后续不可变）
 * @param backendKey 会话所属池（`user` / `proot`），用于分池
 * @param alias 会话别名（同池内唯一）
 * @param reused true 表示复用了同池同别名的既有存活会话（未新建后端）
 * @param errorCode 失败时的机器可读码（`no_backend` / `identity_unsupported` /
 *   `identity_conflict` / `start_failed`）
 * @param message 失败主文本
 * @param hint 失败修复指引
 */
data class OpenResult(
    val ok: Boolean,
    val handle: String? = null,
    val identity: TerminalIdentity = TerminalIdentity.User,
    val backendKey: String? = null,
    val alias: String? = null,
    val reused: Boolean = false,
    val errorCode: String? = null,
    val message: String = "",
    val hint: String? = null
) {
    companion object {
        /** 成功工厂（[reused] 区分新建 / 复用）。 */
        fun ok(
            handle: String,
            identity: TerminalIdentity,
            backendKey: String,
            alias: String,
            reused: Boolean
        ): OpenResult = OpenResult(
            ok = true,
            handle = handle,
            identity = identity,
            backendKey = backendKey,
            alias = alias,
            reused = reused
        )

        /** 失败工厂。 */
        fun failure(code: String, message: String, hint: String): OpenResult =
            OpenResult(ok = false, errorCode = code, message = message, hint = hint)
    }
}

/**
 * 单条命令的执行结果（T08）。
 *
 * @param ok 是否执行成功（后端送达 + 拿到哨兵 + 未超时）
 * @param exitCode 命令退出码（池层错误时为 -1）
 * @param output 命令输出（**已经过 [ToolOutputTruncator] 截断**）
 * @param truncated 输出是否被截断
 * @param errorCode 池层错误码（`no_such_session` / `identity_conflict` / `session_dead` /
 *   `empty_command` / `send_failed` / `timeout`）
 * @param message 失败主文本
 * @param hint 失败修复指引
 */
data class ExecResult(
    val ok: Boolean,
    val exitCode: Int = -1,
    val output: String = "",
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val message: String = "",
    val hint: String? = null
) {
    companion object {
        /** 成功工厂。 */
        fun ok(exitCode: Int, output: String, truncated: Boolean): ExecResult =
            ExecResult(ok = true, exitCode = exitCode, output = output, truncated = truncated)

        /** 失败工厂（池层错误，未跑通命令）。 */
        fun failure(code: String, message: String, hint: String): ExecResult =
            ExecResult(ok = false, exitCode = -1, errorCode = code, message = message, hint = hint)
    }
}

/**
 * 会话只读快照（供 list / 诊断 / harness 断言；不含后端实例，避免外泄内部真身）。
 */
data class SessionSnapshot(
    val handle: String,
    val identity: TerminalIdentity,
    val backendKey: String,
    val alias: String,
    val alive: Boolean
)

/**
 * 终端会话池（T08，来源：zafiro B2 · libterm session pool，净室重写）。
 *
 * ## 职责
 * 在 [TerminalBackend]（T07）之上维护「**具名会话表**」，对上层（shell 工具）暴露：
 *  - **4 位十六进制短句柄**（[TERMINAL_HANDLE_REGEX]），内部真身不外露；
 *  - **每会话一把 [Mutex]**：同会话命令**串行**（防两条命令输出交织串味），
 *    跨会话互不阻塞（各持各的锁，可并发）；
 *  - **身份开辟时固化**：写入会话元数据，后续传**冲突 identity 显式报错**，绝不静默复用；
 *  - **user / proot 分池**：别名按（backendKey, alias）分键，两种后端各占独立槽位，不串味；
 *  - **僵尸会话自动释放别名并可重开**：别名槽位上的后端一旦不存活即回收，下次同名可新建。
 *
 * ## 完成判定（哨兵协议）
 * 后端 [TerminalBackend.output] 是原始文本流，无「命令边界」。本池在写完命令后追加一行
 * `echo "<哨兵>"$?`，并扫描输出流直到匹配哨兵（其后紧跟数字退出码），据此切出「本次命令
 * 的真实输出」。哨兵 = [SENTINEL_MARKER] + 每次随机的 token + `__`，避免与命令自身输出误撞；
 * 匹配时要求哨兵后紧跟 `数字`（PTY 回显的 `echo "...$?"` 之后是 `$`，不会误判）。
 *
 * ## 零 Android 依赖
 * 只依赖注入的 [TerminalBackendSource]（Android 侧真身 = [TerminalBackendFactory]，
 * JVM harness 侧 = 假 source），故本类可在离线 JVM 直接验证全部语义。
 *
 * ## 已知取舍
 * - 多行命令（含换行）以**整体一次写入**下发，哨兵只反映最后一条语句的退出码；
 * - PTY 回显：容器（proot）走伪终端会回显命令与 `echo` 行，本池做**尽力清洗**（见
 *   [cleanOutput]），无 PTY 的本机 user 后端输出天然干净；
 * - [commandTimeoutMs] 超时后本次命令判失败，但**不杀后端**（命令可能仍在跑，由调用方决定
 *   是否 [close] 会话）。
 *
 * @param source 后端来源（每次 [open] 向其取**新实例**：一后端 = 一条独立 shell 进程）
 * @param truncator 输出截断器；默认口径与工具结果一致（24000）
 * @param commandTimeoutMs 单条命令完成等待上限（毫秒）
 * @param handleGenerator 句柄生成器（默认随机 4-hex，可注入以在测试中构造碰撞）
 */
class TerminalSessionPool(
    private val source: TerminalBackendSource,
    private val truncator: ToolOutputTruncator = ToolOutputTruncator(),
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val handleGenerator: () -> String = { randomHex4() }
) {

    /** 收集输出用的协程作用域（读流并发于「写命令」，故需独立调度）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tokenRandom = Random(System.nanoTime())

    /** 句柄 → 会话真身。 */
    private val byHandle = LinkedHashMap<String, Entry>()

    /** （backendKey, alias）→ 会话真身，用于分池 + 别名唯一性。 */
    private val byAlias = LinkedHashMap<String, Entry>()

    /** 会话表互斥锁（仅保护上面两张表的读写，临界区极短）。 */
    private val lock = Any()

    /**
     * 开辟（或复用）一条会话。
     *
     * 语义：
     *  1. 经 [source] 取后端（`identity` 未实现则报 `identity_unsupported`；无可用后端报 `no_backend`）；
     *  2. 解析池键 =（后端键, 别名[默认按后端键推导]）；
     *  3. 该槽位已有**存活**会话：身份一致 → **复用**（返回既有句柄）；身份冲突 → **显式报错**；
     *     已有**僵尸**会话 → 回收别名后按新建走。
     *
     * @param identity 声明的身份（开辟时固化）
     * @param alias 别名；null = 按后端键取默认别名（[TerminalBackendFactory.defaultAliasFor]）
     * @param preferProot true = 优先 proot 容器，不可用回落本机 user
     * @return [OpenResult]
     */
    suspend fun open(
        identity: TerminalIdentity = TerminalIdentity.User,
        alias: String? = null,
        preferProot: Boolean = true
    ): OpenResult {
        // 1) 取后端（新实例）。identity 未实现 / 无可用后端 → 明确报错，不静默降级到别处。
        val backend = source.obtain(identity, preferProot)
            ?: return if (identity == TerminalIdentity.User) {
                OpenResult.failure(
                    code = "no_backend",
                    message = "当前环境下没有可用的终端后端（proot 不可用且回落失败）。",
                    hint = "确认设备可执行 /system/bin/sh；若期望容器请先解压 rootfs 后重试。"
                )
            } else {
                OpenResult.failure(
                    code = "identity_unsupported",
                    message = "身份「${identity.wire}」首期未实现，无对应后端。",
                    hint = "当前仅支持 identity=user（proot 容器与本机 shell 均以 App 用户身份运行）。"
                )
            }

        val backendKey = backend.backendKey
        val effectiveAlias = normalizeAlias(alias ?: TerminalBackendFactory.defaultAliasFor(backendKey))
        val poolKey = poolKey(backendKey, effectiveAlias)

        // 2) 该槽位是否已有会话
        val existing = synchronized(lock) { byAlias[poolKey] }
        if (existing != null) {
            if (existing.alive) {
                if (existing.identity != identity) {
                    // 身份冲突：绝不静默复用（用户以为提权了，命令仍以旧身份跑）
                    runCatching { backend.close() } // 回收刚建的多余后端
                    return OpenResult.failure(
                        code = "identity_conflict",
                        message = "别名「$effectiveAlias」上已有身份为「${existing.identity.wire}」的存活会话，" +
                            "与请求身份「${identity.wire}」冲突。",
                        hint = "改用同一身份，或先 shell_close(handle=${existing.handle}) 关闭旧会话再以新身份开辟。"
                    )
                }
                // 复用：丢弃刚建的多余后端，返回既有句柄
                runCatching { backend.close() }
                return OpenResult.ok(existing.handle, existing.identity, existing.backendKey, existing.alias, reused = true)
            }
            // 僵尸：释放别名 + 回收资源
            releaseEntry(existing)
            runCatching { existing.backend.close() }
        }

        // 3) 启动后端
        val started = runCatching { backend.start() }.getOrDefault(false)
        if (!started) {
            runCatching { backend.close() }
            return OpenResult.failure(
                code = "start_failed",
                message = "后端启动失败（identity=${identity.wire}, backend=$backendKey）。",
                hint = "确认 rootfs 已就绪 / shell 路径可执行；可重试或改用 identity=user。"
            )
        }

        // 4) 分配 4-hex 句柄（含碰撞处理）并登记
        val handle = allocateHandle()
        val entry = Entry(handle, identity, backendKey, effectiveAlias, backend)
        synchronized(lock) {
            byHandle[handle] = entry
            byAlias[poolKey] = entry
        }
        AgentLogger.i(
            TAG,
            "会话已开辟 handle=$handle identity=${identity.wire} pool=$backendKey alias=$effectiveAlias"
        )
        return OpenResult.ok(handle, identity, backendKey, effectiveAlias, reused = false)
    }

    /**
     * 在指定会话里执行一条命令（同会话串行）。
     *
     * @param handle 会话句柄（[TERMINAL_HANDLE_REGEX]）
     * @param command 命令原文（会 trim 掉结尾换行后单行写入；含内部换行则整体一次下发）
     * @param identity 可选的**声明身份**；非 null 时与会话固化身份不一致 → 显式报错
     * @return [ExecResult]（输出已截断）
     */
    suspend fun run(
        handle: String,
        command: String,
        identity: TerminalIdentity? = null
    ): ExecResult {
        val entry = synchronized(lock) { byHandle[handle] }
            ?: return ExecResult.failure(
                code = "no_such_session",
                message = "会话句柄「$handle」不存在（可能已被关闭或回收）。",
                hint = "用无 session 参数调用一次以开辟新会话，或先确认句柄是否为 4 位十六进制。"
            )

        if (identity != null && identity != entry.identity) {
            return ExecResult.failure(
                code = "identity_conflict",
                message = "会话「$handle」固化身份为「${entry.identity.wire}」，与请求身份「${identity.wire}」不符。",
                hint = "去掉 identity 参数（沿用会话身份），或以匹配身份重新开辟会话。"
            )
        }
        if (command.isBlank()) {
            return ExecResult.failure(
                code = "empty_command",
                message = "命令为空。",
                hint = "提供非空的 command，例如 `pwd` 或 `ls -la`。"
            )
        }

        // 每会话一把锁：同会话串行，跨会话并行
        return entry.mutex.withLock {
            if (!entry.alive) {
                releaseEntry(entry)
                return@withLock ExecResult.failure(
                    code = "session_dead",
                    message = "会话「$handle」已退出（僵尸会话已释放别名）。",
                    hint = "用无 session 参数重新调用以开辟新会话。"
                )
            }
            execute(entry, command)
        }
    }

    /** 关闭指定会话（幂等）：回收后端 + 释放别名与句柄。 */
    suspend fun close(handle: String): Boolean {
        val entry = synchronized(lock) { byHandle[handle] } ?: return false
        releaseEntry(entry)
        runCatching { entry.backend.close() }
        AgentLogger.i(TAG, "会话已关闭 handle=$handle")
        return true
    }

    /** 关闭全部会话（App 退后台 / 进程收尾时调用）。 */
    suspend fun closeAll() {
        val all = synchronized(lock) {
            val snapshot = byHandle.values.toList()
            byHandle.clear()
            byAlias.clear()
            snapshot
        }
        all.forEach {
            it.closed = true
            runCatching { it.backend.close() }
        }
        AgentLogger.i(TAG, "已关闭全部会话（${all.size} 条）")
    }

    /** 取消收集协程（进程收尾；不影响已关闭的后端）。 */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * 非挂起 **best-effort 全量释放**：供**同步生命周期钩子**（如 `ViewModel.onCleared()`）调用。
     *
     * ## 与挂起版 [closeAll] 的差异（最小惊讶裁决）
     * [closeAll] 必须在协程里 `await` 每个后端关闭（[TerminalBackend.close] 是挂起操作），
     * 无法用于 `onCleared()` 这类**主线程同步钩子**（在那儿 `runBlocking` 会卡主线程，且
     * 关闭子进程可能耗时）。本方法因此把「逻辑释放」与「物理关闭」拆开：
     *  1. **同步**完成会话表逻辑释放：清空两张表 + 标记 closed → 调用方一返回，旧句柄立即失效
     *     （[lookup]/[list] 不再可见、[run] 报 `no_such_session`），杜绝「钩子返回后仍有会话可达」；
     *  2. **异步**关闭后端进程：后端 [close] 是挂起操作，交给池自有作用域**之外**的短命 IO 协程完成；
     *  3. 最后 [shutdown] 取消输出收集协程。
     *
     * ## 为什么是「best-effort」
     * 关闭是异步的，理论上进程回收可能「稍后」完成；但句柄已不可达，且本池是 ViewModel 级实例
     * （下次进入会新建池），故不会累积复用——对「防泄漏」这一目标足够。
     *
     * ## 异常安全
     * 池为空、后端 [close] 抛错都不外抛（`runCatching` + 提前分支），钩子可安全直调。
     */
    fun releaseAllAsync() {
        val all = synchronized(lock) {
            val snapshot = byHandle.values.toList()
            byHandle.clear()
            byAlias.clear()
            snapshot
        }
        all.forEach { it.closed = true }
        if (all.isNotEmpty()) {
            // 独立作用域：不与即将被 shutdown() 取消的 [scope] 竞争，保证关闭协程能跑完。
            CoroutineScope(Dispatchers.IO).launch {
                all.forEach { runCatching { it.backend.close() } }
                AgentLogger.i(TAG, "已异步释放全部会话（${all.size} 条）")
            }
        }
        scope.cancel()
    }

    /** 会话快照列表（诊断 / 断言用）。 */
    fun list(): List<SessionSnapshot> = synchronized(lock) { byHandle.values.map { it.snapshot() } }

    /** 按句柄取快照；不存在返回 null。 */
    fun lookup(handle: String): SessionSnapshot? =
        synchronized(lock) { byHandle[handle]?.snapshot() }

    /* ------------------------------------------------------------------ */
    /* 内部实现                                                            */
    /* ------------------------------------------------------------------ */

    /** 执行一条命令：起收集协程 → 写命令 + 哨兵 → 等完成 → 切输出 → 截断。 */
    private suspend fun execute(entry: Entry, command: String): ExecResult {
        val token = newToken()
        val sentinelPrefix = SENTINEL_MARKER + token + "__"
        // 哨兵必须「其后紧跟数字退出码」才算命中（PTY 回显的 echo "...$?" 之后是 '$'，不误判）
        val sentinelRegex = Regex(Regex.escape(sentinelPrefix) + "([0-9]+)")
        val echoLine = "echo \"$sentinelPrefix\$?\""

        val builder = StringBuilder()
        val done = CompletableDeferred<Int>()

        val collector = scope.launch {
            runCatching {
                entry.backend.output.collect { chunk ->
                    builder.append(chunk)
                    if (!done.isCompleted) {
                        val m = sentinelRegex.find(builder)
                        if (m != null) {
                            done.complete(m.groupValues[1].toIntOrNull() ?: -1)
                        }
                    }
                }
            }
        }

        val sentOk = runCatching {
            entry.backend.send(command.trimEnd('\n', '\r') + "\n") &&
                entry.backend.send(echoLine + "\n")
        }.getOrDefault(false)

        if (!sentOk) {
            collector.cancelAndJoin()
            if (!entry.alive) releaseEntry(entry)
            return ExecResult.failure(
                code = "send_failed",
                message = "命令写入会话「${entry.handle}」失败（会话可能已退出）。",
                hint = "重新开辟会话后重试；持续失败请检查后端存活状态。"
            )
        }

        val exitCode = withTimeoutOrNull(commandTimeoutMs) { done.await() }
        collector.cancelAndJoin()

        if (exitCode == null) {
            if (!entry.alive) releaseEntry(entry)
            return ExecResult.failure(
                code = "timeout",
                message = "命令在 ${commandTimeoutMs}ms 内未完成（会话「${entry.handle}」）。",
                hint = "命令可能仍在执行或等待输入；可拆小命令重试，或结束交互式程序（如补上 -y / 非交互参数）。"
            )
        }

        val raw = builder.toString()
        // 用「其后紧跟数字」的正则定位真实哨兵（PTY 回显的 echo 行其后是 `$`，不会被误当边界）
        val mark = sentinelRegex.find(raw)
        val body = if (mark != null) raw.substring(0, mark.range.first) else raw
        val cleaned = cleanOutput(body, command, echoLine)
        val truncated = truncator.truncate(cleaned)
        return ExecResult.ok(exitCode = exitCode, output = truncated.text, truncated = truncated.truncated)
    }

    /** 释放一条会话（从两张表移除 + 标记关闭）；调用方负责关闭后端。 */
    private fun releaseEntry(entry: Entry) {
        synchronized(lock) {
            entry.closed = true
            byHandle.remove(entry.handle)
            byAlias.remove(poolKey(entry.backendKey, entry.alias))
        }
    }

    /**
     * 分配 4-hex 句柄：先多次随机重试，反复碰撞则**按序补位**（从 0x0000 起找首个空闲值）。
     * 句柄空间 65536，越界即抛（远超实际会话数）。
     */
    private fun allocateHandle(): String = synchronized(lock) {
        repeat(HANDLE_ALLOC_RETRIES) {
            val candidate = handleGenerator()
            if (candidate.matches(TERMINAL_HANDLE_REGEX) && candidate !in byHandle) return candidate
        }
        for (i in 0..0xFFFF) {
            val candidate = i.toString(16).padStart(4, '0')
            if (candidate !in byHandle) return candidate
        }
        error("4 位十六进制句柄空间已耗尽（上限 65536 个会话）")
    }

    /** 每次随机 16 位十六进制 token（哨兵去重）；不做安全用途，普通随机即可。 */
    private fun newToken(): String {
        val hex = HEX_CHARS
        return buildString(16) { repeat(16) { append(hex[tokenRandom.nextInt(hex.length)]) } }
    }

    /** 尽力清洗 PTY 回显：去掉与「本命令 / echo 命令」逐字相同的回显行，统一换行。 */
    private fun cleanOutput(body: String, command: String, echoLine: String): String {
        val cmdTrim = command.trim()
        val echoTrim = echoLine.trim()
        val sb = StringBuilder(body.length)
        for (line in body.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) {
                sb.append('\n')
                continue
            }
            if (t == cmdTrim || t == echoTrim) continue // 终端回显的命令行
            if (t.startsWith("echo \"") && t.contains(SENTINEL_MARKER)) continue // 回显的哨兵 echo 行
            sb.append(line).append('\n')
        }
        return sb.toString().trim('\n')
    }

    /** 会话真身（含每会话一把锁）。 */
    private class Entry(
        val handle: String,
        val identity: TerminalIdentity,
        val backendKey: String,
        val alias: String,
        val backend: TerminalBackend
    ) {
        /** 每会话一把互斥锁：同会话命令串行，跨会话互不阻塞。 */
        val mutex = Mutex()

        @Volatile
        var closed: Boolean = false

        val alive: Boolean get() = !closed && backend.isAlive

        fun snapshot(): SessionSnapshot =
            SessionSnapshot(handle, identity, backendKey, alias, alive)
    }

    companion object {
        private const val TAG = "TerminalSessionPool"

        /** 哨兵标记前缀（公开：harness 的假后端需据协议应答）。 */
        const val SENTINEL_MARKER = "__AGENTKIT_POOL_END__"

        /** 单条命令默认完成等待上限（毫秒）。 */
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 30_000L

        /** 句柄随机重试次数；超过则顺序补位。 */
        private const val HANDLE_ALLOC_RETRIES = 8

        private const val HEX_CHARS = "0123456789abcdef"

        /** 随机 4-hex 句柄（[TERMINAL_HANDLE_REGEX]）。 */
        private fun randomHex4(): String =
            (Random.nextInt(0x10000)).toString(16).padStart(4, '0')

        /** 池键：后端键 + 别名（用不可能出现在别名里的分隔符拼接）。 */
        private fun poolKey(backendKey: String, alias: String): String = "$backendKey\u0000$alias"

        /** 归一化别名：trim；空串回退为 "default"。 */
        private fun normalizeAlias(alias: String): String =
            alias.trim().ifEmpty { TerminalBackendFactory.ALIAS_DEFAULT }
    }
}
