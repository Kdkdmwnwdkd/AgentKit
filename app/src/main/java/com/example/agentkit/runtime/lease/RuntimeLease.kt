package com.example.agentkit.runtime.lease

/**
 * 运行资源租约（R3）：**只给三类重资源用的统一借用契约**。
 *
 * ## 为什么不是「通用能力框架」
 * 曾经的 `CapabilityHost` 试图统一所有功能的生命周期，结局是 1 个实现、0 个消费
 * （`startIfNeeded` / `get` / `all` / `stopAll` 全仓无调用点）。教训不是「统一」本身错，
 * 而是**统一的对象选错了**。
 *
 * 本契约的准入判据（写死在这里，防止范围再次膨胀）：
 *
 * > 一个资源只有在「**占内存 > 50MB 或 占进程/端口**」且「**空闲可回收**」时，才进租约。
 *
 * 按此判据，全项目只有三类够格：
 * - **本地模型**：几百 MB 到数 GB 的权重 + KV cache；
 * - **终端会话**：每个会话一个常驻进程；
 * - **容器会话**（proot）：常驻进程 + 独立 rootfs 视图。
 *
 * 浏览器、技能、记忆、工作流**不进租约**——它们没有「重资源常驻」的问题，
 * 把它们拉进来就是重造 `CapabilityHost`。
 *
 * ## 与释放安全性的关系
 * [isSafeToRelease] 回答「能不能安全释放」；本契约回答「**谁在用**」。
 * 两者正交：租约管引用计数，isSafeToRelease 管释放的安全性。
 *
 * ## 使用形状
 * ```
 * val handle = lease.acquire()
 * try { ... } finally { handle.close() }
 * ```
 *
 * [LeaseHandle] 实现 [AutoCloseable]，用 `use { }` 可保证归还——
 * 引用计数不归零，回收器就不会释放资源（这是「有人在用就别动」的第一道闸）。
 */
interface RuntimeLease {

    /**
     * 租约标识（全局唯一）。
     *
     * 约定格式：
     * - 本地模型：`"local-llm"`
     * - 终端会话：`"terminal:<backendKey>"`（如 `terminal:user`、`terminal:proot`）
     * - 容器会话：`"container:<distroId>"`
     */
    val id: String

    /**
     * 本租约的空闲回收阈值（毫秒）。null = 跟随 [LeaseRegistry.idleTimeoutMs] 全局值。
     *
     * ## 为什么阈值要挂在租约上而不是只放全局
     * R6 之前空闲判据有**两个来源**：[com.example.agentkit.runtime.IdleUnloadPolicy]
     * 的实例字段（本地模型用，用户可配）与 [LeaseRegistry.idleTimeoutMs]（全局默认）。
     * 两者语义还**相反**——policy 里 `0` 表示「永不释放」，registry 里 `0` 表示
     * 「立即回收」。结果是：用户在设置里关掉空闲释放，policy 停了，registry 照旧按
     * 全局 30 分钟回收——**用户的设置形同虚设**。
     *
     * 挂到租约上之后，全局值只作为**默认值**，每个租约可覆盖；
     * [LeaseRegistry] 是唯一的判据与执行处，不再有第二个节拍器。
     *
     * **取值语义**（与 [LeaseRegistry.idleTimeoutMs] 一致）：
     * - `> 0`：空闲超过该毫秒数才回收；
     * - `== 0`：**立即**满足空闲条件（仅测试用）；
     * - `< 0`：禁用本租约的空闲回收。
     */
    val idleTimeoutMs: Long? get() = null

    /**
     * 借用资源。返回的句柄**必须**在用完后归还（`close()`）。
     *
     * 重复 acquire 是允许的（引用计数 +1）；同一线程重复 acquire 也安全——
     * 实现方负责保证并发下的计数正确。
     */
    suspend fun acquire(): LeaseHandle

    /**
     * 当前**活跃引用数**。0 表示没人用，回收器可以考虑释放。
     *
     * 实现必须是线程安全的（多协程并发 acquire/release）。
     */
    val activeCount: Int

    /**
     * 现在释放是否安全（无在途任务、无未落盘数据……）。
     *
     * 与 [activeCount] 是**两个独立的闸门**：计数为 0 只说明「没人显式持有」，
     * 不代表「后台任务跑完了」。两者都满足才释放。
     */
    val isSafeToRelease: Boolean
}

/**
 * 租约句柄：一次借用的凭证。
 *
 * [AutoCloseable] 而非 [java.io.Closeable]：它不抛 [java.io.IOException]，
 * 且 Kotlin 的 `use { }` 对它有更好的支持。
 *
 * **幂等**：重复 `close()` 不得重复归还引用（否则计数会变成负数，
 * 让回收器误判「有人在用」而永不释放）。
 */
interface LeaseHandle : AutoCloseable {

    /** 所属租约。 */
    val lease: RuntimeLease

    /** 归还引用。幂等。 */
    override fun close()
}

/**
 * 租约句柄的通用实现：持有一个归还动作 + 一个幂等标记。
 *
 * 幂等用 [AtomicBoolean] 而非普通布尔——`close()` 可能从不同线程并发调用
 * （一个在 finally，一个在超时清理），普通布尔会有竞态。
 */
class SimpleLeaseHandle(
    override val lease: RuntimeLease,
    private val onRelease: () -> Unit,
) : LeaseHandle {

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            onRelease()
        }
    }
}
