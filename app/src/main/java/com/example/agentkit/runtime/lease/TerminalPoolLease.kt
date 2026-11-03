package com.example.agentkit.runtime.lease

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.terminal.TerminalSessionPool
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
 * 容器（proot）本身就是终端后端的一种（[TerminalBackend.KEY_PROOT]），
 * 所以**容器租约就是本类的 proot 实例**——不另造一套。
 * 这也是「只有三类资源」判据的自然结果：本地模型、终端、容器，
 * 其中终端与容器共用同一套会话机制。
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

    /** 该后端当前存活会话数（用于判断「有没有东西可回收」）。 */
    private fun liveSessions(): Int =
        pool.list().count { snapshot -> snapshot.backendKey == backendKey && snapshot.alive }

    override val activeCount: Int
        get() = active.get()

    private val active = AtomicInteger(0)

    /**
     * 释放安全性：**没有活跃引用**且**没有存活会话**。
     *
     * 注意第二项：会话是池管的资源，有人显式持有会话句柄时把它关掉，
     * 用户的下一次命令就会失败。有活跃引用时同理。
     */
    override val isSafeToRelease: Boolean
        get() = active.get() == 0 && liveSessions() == 0

    override suspend fun acquire(): LeaseHandle {
        active.incrementAndGet()
        return LeaseRegistry.acquire(this) { release() }
    }

    /** 归还引用（引用数 -1）。 */
    private fun release() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * 空闲释放：关闭该后端上的全部会话。
     *
     * 池的 [TerminalSessionPool.closeAll] 是挂起函数（要 await 每个后端关闭），
     * 而回收器的释放动作是非挂起回调（[LeaseRegistry.registerIdleReleaser]）。
     * 这里用独立协程跑：回收是尽力而为，失败只记日志。
     */
    fun releaseIdle(reason: String) {
        val count = liveSessions()
        if (count == 0) return
        AgentLogger.i(TAG, "空闲回收终端会话：后端=$backendKey 会话数=$count（$reason）")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { pool.closeAll() }
                .onFailure { AgentLogger.w(TAG, "终端会话回收失败（$reason）", it) }
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
