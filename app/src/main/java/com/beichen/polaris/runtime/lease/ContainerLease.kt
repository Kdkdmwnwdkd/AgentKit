package com.beichen.polaris.runtime.lease

import com.beichen.polaris.core.AgentLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 容器会话租约（R8）：**按发行版**独立持有的第三条重资源。
 *
 * ## 为什么不能复用 `terminal:proot`
 * 容器（proot）确实是终端后端的一种，`TerminalPoolLease` 的注释里写过
 * 「容器租约就是本类的 proot 实例」。但那条推论只在**会话由池创建**时成立。
 *
 * 实际路径不是这样：容器页（[com.beichen.polaris.ui.ContainerScreen]）走的是
 * `ContainerSession.create` 直连，会话不进 [com.beichen.polaris.core.terminal.TerminalSessionPool]。
 * 于是：
 * - 池的 `liveSessions()` 永远数不到容器会话 → 池租约认为「没人在用」；
 * - 池的 `closeAll()` 永远关不掉容器会话 → 空闲回收对容器**完全失效**。
 *
 * 结论：`terminal:proot` 与 `container:<distro>` 指的是两批不同的进程，
 * 混用会得到一个「看得到但关不掉」的租约——比没有更坏，因为它会让观察面
 * 显示「已回收」而容器还在后台跑。
 *
 * ## 为什么 id 带发行版
 * 同一时刻只允许一个容器会话存活（共享 pty 与 rootfs 视图），但用户会在
 * alpine / debian 之间切换。id 带 distroId 才能回答「现在这个 proot 进程
 * 跑的是哪个 rootfs」——不带的话，切换发行版后观察面仍显示旧名字，
 * 排障时会把两段完全不同的生命周期看成同一段。
 *
 * ## 与池租约的语义差异
 * [isSafeToRelease] 只看引用数，**不**看会话是否存活。
 * 池租约要求「没人持有 + 没有存活会话」才释放，是因为池里的会话可能被别人开着；
 * 容器会话由 UI 独占（holder 引用计数即全部持有者），
 * 「有人显式持有」是唯一的阻止条件；存活与否恰恰是要释放的对象。
 */
class ContainerLease(
    /** 发行版 id（如 `alpine`）。 */
    val distroId: String,
    /** 会话当前是否存活。回收动作据此跳过空转。 */
    private val alive: () -> Boolean,
    /** 销毁会话（由持有者提供，须幂等）。 */
    private val destroySession: () -> Unit,
    override val id: String = "container:$distroId",
) : RuntimeLease {

    private companion object {
        const val TAG = "ContainerLease"
    }

    private val active = AtomicInteger(0)

    override val activeCount: Int get() = active.get()

    /** 没人显式持有即可释放；存活不是阻碍，反而正是回收对象。 */
    override val isSafeToRelease: Boolean get() = active.get() == 0

    override suspend fun acquire(): LeaseHandle = borrow()

    /**
     * 非挂起的借用入口。
     *
     * 为什么需要它：容器页在 Compose 的 `LaunchedEffect` 里同步调用 holder，
     * 而 [RuntimeLease.acquire] 是挂起函数（本地模型要等加载，接口形状如此）。
     * 容器借用没有任何异步前置，为它套一层协程只会把 UI 生命周期和调度器绑在一起。
     * 借用的三件事（计数 +1、刷新时间戳、登记未归还）都在 [LeaseRegistry.acquire] 里同步完成。
     */
    fun borrow(): LeaseHandle {
        active.incrementAndGet()
        return LeaseRegistry.acquire(this) {
            active.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 空闲释放：销毁容器会话。
     *
     * 幂等：会话已经没了就直接返回，不重复 destroy。
     * 回收器每 [LeaseRegistry.checkIntervalMs] 检查一次，而销毁后租约仍登记在册，
     * 所以「什么都不做」必须是合法的常态。
     */
    fun releaseIdle(reason: String) {
        if (!alive()) {
            // 会话已不在（用户退出/切换发行版时被销毁）。租约只是还挂着名字，不必动。
            return
        }
        AgentLogger.i(TAG, "空闲回收容器会话：$id（$reason）")
        runCatching { destroySession() }
            .onFailure { AgentLogger.w(TAG, "容器会话回收失败：$id（$reason）", it) }
    }
}

/**
 * 容器租约集合：按发行版 id 惰性创建并登记。
 *
 * 与 [TerminalLeases] 同构——调用方只关心「给我这个发行版的租约」，
 * 不关心登记与释放器注册。首次获取时登记；同一 distro 后续获取返回同一实例，
 * 否则引用计数会各算各的，回收判定就失去意义。
 */
object ContainerLeases {

    private const val TAG = "ContainerLeases"

    private val leases = ConcurrentHashMap<String, ContainerLease>()

    /**
     * 取（并登记）某发行版的租约。
     *
     * @param distroId 发行版 id
     * @param alive    会话存活探测（每次回收检查都会调用）
     * @param destroy  销毁会话（幂等）
     */
    fun forDistro(
        distroId: String,
        alive: () -> Boolean,
        destroy: () -> Unit,
    ): ContainerLease = leases.computeIfAbsent(distroId) { id ->
        val lease = ContainerLease(id, alive, destroy)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.releaseIdle("租约空闲回收") }
        AgentLogger.i(TAG, "容器租约就绪：${lease.id}")
        lease
    }

    /** 全部已登记容器租约（观察面用）。 */
    fun all(): List<ContainerLease> = leases.values.toList()
}
