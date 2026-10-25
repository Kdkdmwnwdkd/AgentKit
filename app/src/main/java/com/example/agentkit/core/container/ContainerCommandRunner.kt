package com.example.agentkit.core.container

import android.content.Context
import com.example.agentkit.core.AgentLogger
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 在容器里执行一次性命令并拿到退出码（B 批）。
 *
 * ## 为什么不用 TerminalSession
 * [com.example.agentkit.terminal.TerminalSession] 是 **pty 交互式**会话：它按屏幕语义
 * 解析输出，发一条命令进去只能知道「命令是否已下发」，**取不到退出码**（见
 * [ContainerSession.runCommand] 的 KDoc 明确说明）。
 *
 * 但「一键安装运行时」必须有退出码才能判断成功与否——`apk add` 失败时退出码非 0，
 * 而 pty 通道拿不到。所以这里走**独立的 ProcessBuilder 通道**：
 * 直接以 proot 的 argv 起一个短命进程，命令跑完即退出，退出码与 stdout/stderr 都拿得到。
 *
 * 这与 [com.example.agentkit.core.job.JobRunner] 是同一思路（后台任务用独立进程，
 * 不与会话池共享状态），区别是这里要**同步等退出码**，所以用 `waitFor` 而非轮询。
 *
 * ## 并发：这里必须只有一个写者（真机实测反例）
 * 真机上连点两次「安装 Node.js 20」，结果是：
 * ```
 * 安装失败（exit 99）：ERROR: Unable to lock database: temporary error (try again later)
 * ```
 * 因为 apk 用 `lib/apk/db/lock` 做进程间互斥，两个 proot 同时跑 `apk add` 时会抢锁。
 * 这不是网络问题、也不是 rootfs 损坏——**并发本身**就是 bug。所以本类加了两道闸：
 * 1. [execLock]：进程级公平锁，所有容器命令**排队串行**执行（不同命令也不许并跑）；
 * 2. 同命令去重：一模一样的命令已在执行时直接返回 [EXIT_BUSY]，不排队、不重复下载。
 *
 * 另外对「抢锁」做了原地重试（见 [LOCK_RETRY_DELAYS_MS]），兜住极端情况：上一次
 * 安装被系统杀掉后，apk 的锁可能还没完全释放。
 *
 * ## 边界
 * - 安装 node/python/java 可能耗时几分钟（下载 + 解包），因此给一个较宽的超时上限。
 * - 超时按「失败」处理并杀掉进程——不留半装状态在后台偷偷跑。
 */
object ContainerCommandRunner {

    private const val TAG = "ContainerCommandRunner"

    /** 执行结果。 */
    data class Result(
        val exitCode: Int,
        val output: String
    ) {
        val ok: Boolean get() = exitCode == 0

        /** 是否因为「同一命令已在执行」被拒（不是失败，是别重复点）。 */
        val busy: Boolean get() = exitCode == EXIT_BUSY
    }

    /**
     * 退出码：同一命令已在执行。
     *
     * 与「前置条件不满足」的 -1 分开，是因为两者对用户的含义完全不同：
     * -1 要用户去修环境；-2 只要等一会儿，UI 该显示「正在进行」而不是红色报错。
     */
    const val EXIT_BUSY = -2

    /** 默认超时：装 JDK 慢，但也不该无限等。 */
    private const val DEFAULT_TIMEOUT_MINUTES = 15L

    /** 单次输出上限（安装日志可能很长，截断避免 OOM）。 */
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    /** 抢锁失败后的重试间隔；长度即重试次数（不含首次）。 */
    private val LOCK_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)

    /**
     * 全局串行闸门（公平锁：先来先服务，不会饿死）。
     *
     * 为什么是**进程级**而不是「按发行版一把锁」：`/data` 上不同 rootfs 的 apk
     * 各自有自己的 lock 文件，理论上可以并跑；但真机上多个 proot 同时跑时内存
     * 压力会触发 LMK，pty 会话被 SIGKILL（退出码 137）。串行是更稳的选择，
     * 而「同时装两个发行版的运行时」本来也不是真实诉求。
     */
    private val execLock = ReentrantLock(true)

    /** 在飞命令登记表：key = distroId + 命令原文 → 开始时间(ms)。 */
    private val inFlight = HashMap<String, Long>()

    /** 只保护 [inFlight] 的短锁（不覆盖执行期，避免阻塞去重判断）。 */
    private val registryLock = ReentrantLock()

    /**
     * 在容器中执行一条命令并等待结束。
     *
     * @param context 应用上下文
     * @param distroId 目标发行版 id（须已解压）
     * @param command 命令原文（**原样传入**，由容器内 shell 自己解析）
     * @return 执行结果；前置条件不满足返回 exitCode=-1，重复提交返回 [EXIT_BUSY]
     */
    fun run(
        context: Context,
        distroId: String,
        command: String,
        timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES
    ): Result {
        val key = "$distroId\u0000$command"
        // 先登记再去排队：同一条命令正在跑时立刻拒绝，别让用户连点十下攒出十个下载任务
        registryLock.withLock {
            val since = inFlight[key]
            if (since != null) {
                val waited = (System.currentTimeMillis() - since) / 1000
                AgentLogger.w(TAG, "拒绝重复命令：distro=$distroId 已在执行 ${waited}s")
                return Result(EXIT_BUSY, "同一条命令已在容器内执行（已 ${waited}s），请等它结束")
            }
            inFlight[key] = System.currentTimeMillis()
        }
        try {
            // 不同命令也必须串行：apk/apt 是单实例包管理器，并跑必然抢锁
            execLock.withLock {
                AgentLogger.i(TAG, "容器命令开始：distro=$distroId cmd=${command.take(160)}")
                val started = System.currentTimeMillis()
                val result = execute(context, distroId, command, timeoutMinutes)
                val cost = (System.currentTimeMillis() - started) / 1000
                AgentLogger.i(
                    TAG,
                    "容器命令结束：distro=$distroId exit=${result.exitCode} 耗时=${cost}s"
                )
                return result
            }
        } finally {
            registryLock.withLock { inFlight.remove(key) }
        }
    }

    /** 前置检查 + 组装 argv + 带抢锁重试地执行。 */
    private fun execute(
        context: Context,
        distroId: String,
        command: String,
        timeoutMinutes: Long
    ): Result {
        val prootBin = ProotRunner.ensureBinary(context)
        if (prootBin == null) {
            return Result(-1, "proot 运行时不可用，无法进入容器")
        }
        if (!RootfsManager.isExtracted(context, distroId)) {
            return Result(-1, "rootfs($distroId) 尚未解压")
        }
        val rootfs = RootfsManager.rootfsDir(context, distroId)
        // 容器内 DNS：rootfs 自带的 resolv.conf 是空/缺失，不写就解析不了域名
        ContainerNetwork.ensureResolvConf(context, rootfs)
        val invocation = ProotRunner.buildInvocation(
            prootBinary = prootBin,
            rootfs = rootfs,
            workDir = ProotRunner.GUEST_HOME,
            packageName = context.packageName,
            // 运行库在 filesDir/bin，proot 本体在 nativeLibraryDir——必须显式区分
            libDir = ProotRunner.libDir(context)
        )
        // 一次性命令：argv = proot + 选项 + guest shell + 命令参数
        val argv = invocation.argv.dropLast(1) + ProotRunner.buildCommandArgv(
            cwd = ProotRunner.GUEST_HOME,
            command = command
        )
        // envArray 已是「宿主 env + guest env」的并集（见 ProotRunner.buildInvocation），
        // 直接透传给子进程即可，不需要再拼一次。
        return runProcessWithLockRetry(argv, invocation.envArray, timeoutMinutes)
    }

    /**
     * 执行，并在「抢锁」时重试。
     *
     * 判断依据是 apk 的两种表现之二：退出码 99，或输出里出现
     * `Unable to lock database`。只看退出码不够——apt 的锁冲突退出码是 100，
     * 且某些版本会把锁错误包一层包装；同时看文本更稳。
     */
    private fun runProcessWithLockRetry(
        argv: List<String>,
        env: Array<String>,
        timeoutMinutes: Long
    ): Result {
        var attempt = 0
        while (true) {
            val result = runProcess(argv, env, timeoutMinutes)
            val conflict = ToolchainInstaller.isLockConflict(result.exitCode, result.output)
            if (!conflict || attempt >= LOCK_RETRY_DELAYS_MS.size) {
                if (conflict) {
                    AgentLogger.e(
                        TAG,
                        "包管理器仍被占用（重试 ${attempt} 次后放弃）：exit=${result.exitCode}"
                    )
                    return Result(
                        result.exitCode,
                        result.output + "\n[AgentKit] 包管理器数据库被占用，重试 ${attempt} 次仍失败；" +
                            "可能有上一次安装的进程没退出，稍等一会儿再点一次。"
                    )
                }
                return result
            }
            val delay = LOCK_RETRY_DELAYS_MS[attempt]
            attempt++
            AgentLogger.w(TAG, "包管理器被占用（exit=${result.exitCode}），${delay}ms 后第 $attempt 次重试")
            runCatching { Thread.sleep(delay) }
        }
    }

    /** 起进程、等退出、收输出。 */
    private fun runProcess(argv: List<String>, env: Array<String>, timeoutMinutes: Long): Result =
        runCatching {
            val process = ProcessBuilder(argv)
                .apply {
                    environment().clear()
                    environment().putAll(env.associate { it.substringBefore('=') to it.substringAfter('=') })
                    redirectErrorStream(true)
                }
                .start()
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Result(-1, "命令超时（${timeoutMinutes} 分钟）已终止")
            }
            val out = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            Result(process.exitValue(), out.take(MAX_OUTPUT_CHARS))
        }.getOrElse {
            AgentLogger.e(TAG, "容器命令执行失败：${it.message}")
            Result(-1, "执行失败：${it.message}")
        }
}
