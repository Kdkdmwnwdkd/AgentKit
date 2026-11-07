package com.beichen.polaris.runtime.lease

import com.beichen.polaris.core.AppScope
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.terminal.SessionSnapshot
import com.beichen.polaris.core.terminal.TerminalSessionPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 终端会话池租约（R3）。
 *
 * ## 为什么是「池级」而不是「会话级」
 * 会话池按后端分池（`user` / `proot`），每个后端一个租约。
 *
 * 租约的语义是「**这个后端上有人吗**」，而回收的动作是「关掉这个后端上的全部会话」。
 * 会话级的租约看似更细，实则没意义——会话本身就是池管的，池一关就没了，
 * 再给每个会话套一层引用计数只会让「谁能关会话」的判定分散到两处。
 *
 * ## 与容器的关系
 * 容器（proot）是终端**后端**的一种（[TerminalBackend.KEY_PROOT]），但容器**会话**
 * 不归本类管。R8 之前这里写着「容器租约就是本类的 proot 实例——不另造一套」，
 * 那条推论只在「会话由池创建」时成立，而容器页走的是 `ContainerSession.create` 直连：
 *
 * - 池的 `liveSessions()` 数不到容器会话 → 池租约认为「没人在用」；
 * - 池的 `closeAll()` 也关不掉它 → 空闲回收**看得到却关不掉**。
 *
 * 一个有名字但关不掉的租约比没有更坏：观察面会显示「已回收」，而 proot 还在后台跑。
 * 因此容器改用独立租约 [ContainerLease]（`container:<distroId>`），
 * 本类只管池内的终端会话（`terminal:user` / `terminal:proot`）。
 *
 * ## 释放的副作用
 * 空闲回收会**关掉该后端上的全部会话**。用户若还持有会话句柄，
 * 下次调用会得到 `no_such_session`（会话池已有这个错误码与提示）。
 * 这是可接受的：会话本身是短命的中间态，且 30 分钟不碰终端会话极不寻常。
 */
class TerminalPoolLease(
    private val pool: TerminalSessionPool,
    /** 所属后端键（`user` / `proot`）。直接持有，不从 id 反解。 */
    private val backendKey: String,
    override val id: String = "terminal:$backendKey",
) : RuntimeLease {

    private companion object {
        const val TAG = "TerminalPoolLease"
    }

    /** 该后端当前存活会话（回收的**对象**；空 = 无东西可回收，跳过）。 */
    private fun liveSessions(): List<SessionSnapshot> =
        pool.list().filter { snapshot -> snapshot.backendKey == backendKey && snapshot.alive }

    override val activeCount: Int
        get() = active.get()

    private val active = AtomicInteger(0)

    /**
     * 释放安全性：**只看引用数**，不看会话是否存活——与 [ContainerLease] 同一条判据。
     *
     * 存活会话恰恰是**要释放的对象**，不是否决条件。曾经写成
     * `active == 0 && liveSessions() == 0`，等于要求「先没有东西可回收，才允许回收」：
     * 池里的会话不会自己消失（没有别的回收者），所以 `liveSessions()` 一旦 > 0
     * 就再也不会归零，30 分钟空闲回收从未触发过一次——一个文档写着会回收、
     * 实际永不回收的回收器。
     *
     * 「有人在用」由引用数表达：[ShellTool] 在命令执行期间 acquire、结束即 close。
     * 没人跑命令时留着后端进程只会白占内存与电。
     */
    override val isSafeToRelease: Boolean
        get() = active.get() == 0

    override suspend fun acquire(): LeaseHandle {
        active.incrementAndGet()
        return LeaseRegistry.acquire(this) { release() }
    }

    /** 归还引用（引用数 -1）。 */
    private fun release() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * 空闲释放：关闭**本后端**上的全部会话。
     *
     * 池的 [TerminalSessionPool.close] 是挂起函数（要 await 后端关闭），
     * 而回收器的释放动作是非挂起回调（[LeaseRegistry.registerIdleReleaser]）。
     * 这里用独立协程跑：回收是尽力而为，失败只记日志。
     *
     * 曾经这里调 `pool.closeAll()`——它关的是**全部后端**，于是回收 `terminal:user`
     * 会顺手把有人正在用的 `terminal:proot` 一起杀掉，而 proot 租约本身并不同意释放。
     * 租约的粒度是「一个后端」，回收动作必须同样按后端切分。
     */
    fun releaseIdle(reason: String) {
        val alive = liveSessions()
        if (alive.isEmpty()) return
        AgentLogger.i(TAG, "空闲回收终端会话：后端=$backendKey 会话数=${alive.size}（$reason）")
        AppScope.app.launch {
            alive.forEach { snapshot ->
                runCatching { pool.close(snapshot.handle) }
                    .onFailure {
                        AgentLogger.w(TAG, "终端会话回收失败 handle=${snapshot.handle}（$reason）", it)
                    }
            }
        }
    }
}

/**
 * 终端会话池的租约集合（R3）。
 *
 * 按后端分池，所以「终端资源」实际是 N 个租约（`terminal:user`、`terminal:proot`）。
 * 本对象负责按需创建并登记它们，避免调用方关心池的内部结构。
 */
class TerminalLeases(private val pool: TerminalSessionPool) {

    private val leases = ConcurrentHashMap<String, TerminalPoolLease>()

    /**
     * 取（并登记）某后端的租约。
     *
     * 首次调用时创建并登记；后续调用返回同一个实例（租约的引用计数才有意义）。
     */
    fun forBackend(backendKey: String): TerminalPoolLease =
        leases.computeIfAbsent(backendKey) { key ->
            val lease = TerminalPoolLease(pool, key)
            LeaseRegistry.register(lease)
            LeaseRegistry.registerIdleReleaser(lease.id) { lease.releaseIdle("租约空闲回收") }
            lease
        }

    /** 全部已登记的终端租约。 */
    fun all(): List<TerminalPoolLease> = leases.values.toList()
}
