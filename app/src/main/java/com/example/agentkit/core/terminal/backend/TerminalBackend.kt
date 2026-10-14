package com.example.agentkit.core.terminal.backend

import kotlinx.coroutines.flow.Flow

/**
 * 终端后端统一接口（T07，来源：zafiro B1 · libterm `TerminalBackend`，净室重写）。
 *
 * 契约 5 方法：`start` / `send` / `close` / `awaitExit` / `output: Flow`。
 * **上层（会话池 / shell 工具）只认本接口**，因此不存在「是容器还是本机」的分支——
 * proot 容器（[ProotBackend]）与本机 user shell（[LocalUserBackend]）从这里之后完全同构。
 *
 * 线程契约：实现须保证 [output] 可被**单一消费者**持续收集；[send] 只负责把输入写入
 * 后端（不等待命令结束，完成判定交由上层按哨兵实现）。
 */
interface TerminalBackend {

    /** 后端以何种身份执行命令（首期实现均为 [TerminalIdentity.User]）。 */
    val identity: TerminalIdentity

    /**
     * 后端种类键，用于会话池**分池不串味**（同一身份下的本机/proot 各占独立别名）。
     * 取值用 [KEY_USER] / [KEY_PROOT] 等常量，不要硬编码字符串。
     */
    val backendKey: String

    /** 后端是否仍存活（进程/连接在）。会话池据此识别僵尸会话并释放别名。 */
    val isAlive: Boolean

    /** 输出流（文本 chunk）。实现须允许被持续收集，已产出的数据不因订阅时机而丢失。 */
    val output: Flow<String>

    /** 启动后端。返回值表示是否成功；失败时上层降级/报错。 */
    suspend fun start(): Boolean

    /** 向终端写入输入（通常是一整行命令文本，含结尾换行）。返回是否成功写入。 */
    suspend fun send(input: String): Boolean

    /** 关闭后端并回收资源（幂等）。 */
    suspend fun close()

    /** 等待后端退出，返回退出码（信号杀死 = 128 + 信号；无法获取 = -1）。 */
    suspend fun awaitExit(): Int

    companion object {
        /** 本机 user 后端键。 */
        const val KEY_USER = "user"

        /** proot 容器后端键。 */
        const val KEY_PROOT = "proot"

        /** root（su）后端键：首期未实现。 */
        const val KEY_SU = "su"

        /** Shizuku 后端键：首期未实现。 */
        const val KEY_SHIZUKU = "shizuku"

        /** SSH 后端键：首期未实现。 */
        const val KEY_SSH = "ssh"
    }
}

/**
 * 后端来源：会话池据此按身份惰性获取后端实例（**每次调用返回新实例**，
 * 因为一个后端 = 一条独立 shell 进程，不能被多个会话共享）。
 *
 * 把「取后端」抽象成一个函数式接口，让 [com.example.agentkit.core.terminal.TerminalSessionPool]
 * 零 Android 依赖、可在 JVM harness 里注入假实现。
 */
fun interface TerminalBackendSource {
    /**
     * 取一条后端。
     *
     * @param identity 声明的身份；未实现的身份返回 null（调用方报「不可用」）
     * @param preferProot true = 优先 proot（最高可用），不可用时回落本机 user
     * @return 后端实例；不可用返回 null
     */
    fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend?
}

/**
 * 后端「可用性 + 构造」提供者（T07）：把 Android 侧的探测（rootfs/proot 三件套是否就绪）
 * 与构造从工厂剥离，使 [TerminalBackendFactory] 本身零 Android 依赖、可离线验证。
 */
interface TerminalBackendProvider {

    /** 该提供者实现的身份。 */
    val identity: TerminalIdentity

    /** 后端键（见 [TerminalBackend] 常量）。 */
    val backendKey: String

    /** 当前环境下该后端是否可用（可能触及文件系统；工厂会缓存结果直到 [TerminalBackendFactory.clearCache]）。 */
    fun probe(): Boolean

    /** 构造后端实例；不可用时返回 null。 */
    fun create(): TerminalBackend?
}
