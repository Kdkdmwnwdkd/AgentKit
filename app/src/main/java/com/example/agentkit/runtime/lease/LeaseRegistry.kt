package com.example.agentkit.runtime.lease

import com.example.agentkit.core.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 「禁用空闲回收」的阈值取值。
 *
 * R6：必须是**负数**。本注册表的取值语义里 `0` 表示「立即满足空闲条件」
 * （测试用的快进档），拿它表示「禁用」会让资源每次检查都被回收——
 * 这正是 R6 修掉的那个 bug 的同一族错误（旧 policy 里 `0` 才是「永不释放」）。
 *
 * 单独命名而不是写 `-1` 字面量：调用方（用户设置关闭空闲释放）读代码时
 * 一眼能看出意图，而不是去查「这里为什么是 -1」。
 */
const val DISABLED_IDLE_TIMEOUT_MS: Long = -1L

/**
 * 租约注册表（R3）：登记三类资源 + 统一空闲回收 + 泄漏检测。
 *
 * ## 为什么要有注册表
 * 回收需要一个**统一的节拍器**。没有它，每类资源各自实现一遍「多久没用就释放」，
 * 结果是三套阈值、三套日志、三套边界条件——而空闲释放这种逻辑最怕的就是
 * 某一路漏判（资源永远不释放，用户的手机越来越热）。
 *
 * 注册表只做三件事，**不含任何业务语义**：
 * 1. 登记（[register]）；
 * 2. 周期性检查：引用数为 0 且空闲超阈值且可安全释放 → 释放；
 * 3. 泄漏检测：句柄借出后超过 [leakDetectMs] 仍未归还 → 记一条警告。
 *
 * ## 泄漏检测为什么在这里而不是各实现里
 * 泄漏的**共性**是「借了不还」，与资源是什么无关。在注册表统一看，
 * 一行代码就能覆盖三类资源；在各实现里各写一遍，第三次就一定会漏。
 *
 * 泄漏检测**只记警告不强制回收**——强回收会把正在用的资源夺走，
 * 故障从「泄漏」升级成「数据损坏」。
 */
object LeaseRegistry {

    private const val TAG = "Lease"

    private val leases = ConcurrentHashMap<String, RuntimeLease>()

    /** 各租约的最后使用时刻（毫秒）。回收器据此算空闲时长。 */
    private val lastUsed = ConcurrentHashMap<String, AtomicLong>()

    /** 借出但未归还的句柄数（按租约 id 计）。用于泄漏检测与自检。 */
    private val outstanding = ConcurrentHashMap<String, AtomicLong>()

    /**
     * 空闲阈值（毫秒）——**全局默认值**。
     *
     * 取 30 分钟的理由与本地模型一致：覆盖「聊—查资料—回来继续聊」的常见节奏，
     * 同时仍能让长时间闲置的机器真正降温。
     *
     * **取值语义**（与 [RuntimeLease.idleTimeoutMs] 一致）：
     * - `> 0`：空闲超过该毫秒数才回收；
     * - `== 0`：**立即**满足空闲条件（测试用——harness 借此免去真实等待）；
     * - `< 0`：禁用回收（永久保留）。
     *
     * `0` 与负号必须区分开：把 0 当成「禁用」会让 harness 没法测回收路径，
     * 而把负号当成立即回收则会让「临时禁用回收」变成「疯狂回收」。
     *
     * 租约可用 [RuntimeLease.idleTimeoutMs] 覆盖本值（如本地模型跟随用户设置）。
     */
    @Volatile
    var idleTimeoutMs: Long = 30 * 60_000L

    /** 检查间隔（毫秒）。30 秒对分钟级超时足够。 */
    @Volatile
    var checkIntervalMs: Long = 30_000L

    /**
     * 泄漏判定阈值（毫秒）。默认 10 分钟。
     *
     * 比空闲阈值短：正常用法下句柄都是**借完即还**（`use { }`），
     * 借超过 10 分钟说明调用方漏了归还或压根没关。
     */
    @Volatile
    var leakDetectMs: Long = 10 * 60_000L

    private var watchJob: Job? = null

    /**
     * 登记一个租约。重复登记同一 id 会**替换**旧的（并告警）——
     * 通常意味着两次初始化，不该静默接受。
     */
    fun register(lease: RuntimeLease) {
        if (leases.put(lease.id, lease) != null) {
            AgentLogger.w(TAG, "租约 id 重复登记，已替换：${lease.id}")
        }
        lastUsed[lease.id] = AtomicLong(System.currentTimeMillis())
        outstanding.putIfAbsent(lease.id, AtomicLong(0))
        AgentLogger.i(TAG, "已登记租约：${lease.id}")
    }

    /** 取回租约（未登记返回 null）。 */
    fun get(id: String): RuntimeLease? = leases[id]

    /** 全部已登记的租约。 */
    fun all(): List<RuntimeLease> = leases.values.toList()

    /**
     * 标记「刚用过」。业务侧在 acquire/release 时调用，刷新空闲计时。
     *
     * 回收器只看这个时间戳判断空闲——**不看**调用方什么时候注册的。
     */
    fun touch(id: String) {
        lastUsed[id]?.set(System.currentTimeMillis())
    }

    /**
     * 借出一个句柄（供实现方调用）。
     *
     * 封装在这里而不是各实现里，是为了让「计数 +1 / 刷新时间戳 / 登记未归还」
     * 这三件事**永远同时发生**——分开写，第三类资源就会漏掉其中一件。
     */
    /**
     * 造一个句柄（供租约实现方调用）。
     *
     * **计数责任在租约自己**：本方法只负责登记「未归还数」并刷新时间戳，
     * **不碰** [RuntimeLease.activeCount]——那是租约的实现细节（本地模型数在途推理，
     * 终端数活跃会话），registry 替它加减会让两套计数漂移。
     *
     * @param onRelease 归还回调（由租约实现方提供，负责把自己的计数减回去）。
     */
    fun acquire(lease: RuntimeLease, onRelease: () -> Unit): LeaseHandle {
        touch(lease.id)
        outstanding[lease.id]?.incrementAndGet()
        return SimpleLeaseHandle(lease) {
            onRelease()
            release(lease.id)
        }
    }

    /** 归还一个句柄（供 [SimpleLeaseHandle] 调用）。 */
    private fun release(id: String) {
        touch(id)
        outstanding[id]?.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** 当前未归还的句柄数（按租约 id）。自检与测试用。 */
    fun outstandingCount(id: String): Long = outstanding[id]?.get() ?: 0L

    /**
     * 启动节拍器。幂等（已在跑则 no-op）。
     *
     * 启动回收器**不等于**立刻释放任何资源——第一次检查在 [checkIntervalMs] 之后。
     */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                delay(checkIntervalMs)
                runCatching { checkOnce() }
            }
        }
    }

    /** 停止节拍器（不主动释放任何资源）。 */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * 执行一轮检查（节拍器调用；测试也可直接调用）。
     *
     * 暴露出来是为了让 harness 能**不依赖真实等待**地断言回收逻辑——
     * 把 [idleTimeoutMs] 调到 0 再手动调一次，比等 30 分钟现实得多。
     */
    fun checkOnce() {
        val now = System.currentTimeMillis()
        for (lease in leases.values) {
            val used = lastUsed[lease.id]?.get() ?: continue
            val idleFor = now - used
            // R6：阈值取「租约自身覆盖 ?: 全局默认」。租约覆盖优先——
            // 用户在设置里改的空闲时长必须真的作用到这一类资源上。
            val timeout = lease.idleTimeoutMs ?: idleTimeoutMs
            // 引用数为 0：没人显式持有
            if (lease.activeCount > 0) continue
            // 空闲超阈值。timeout == 0 表示「立即满足」（测试用），负数表示禁用回收
            if (timeout < 0L) continue
            if (timeout > 0L && idleFor < timeout) continue
            // 安全闸门：无在途任务
            if (!lease.isSafeToRelease) continue
            AgentLogger.i(TAG, "租约 ${lease.id} 空闲 ${idleFor / 60_000L} 分钟，执行回收")
            runCatching { releaseIdle(lease) }
                .onFailure { AgentLogger.w(TAG, "回收租约 ${lease.id} 失败", it) }
        }
        detectLeaks()
    }

    /**
     * 空闲释放。**默认什么都不做**——释放是**资源特有**的动作
     * （本地模型 unload、终端 closeAll），运行层不该知道怎么做。
     *
     * 实现方通过 [registerIdleReleaser] 注册自己的释放动作。
     */
    private fun releaseIdle(lease: RuntimeLease) {
        val releaser = idleReleasers[lease.id]
        if (releaser == null) {
            AgentLogger.w(TAG, "租约 ${lease.id} 没有注册释放器，跳过回收")
            return
        }
        releaser()
    }

    private val idleReleasers = ConcurrentHashMap<String, () -> Unit>()

    /**
     * 注册某个租约的空闲释放动作。
     *
     * 由资源实现方在 [register] 时一并调用。动作应当**幂等**——
     * 释放本身失败也不应把资源置于「半释放」的坏状态。
     */
    fun registerIdleReleaser(id: String, releaser: () -> Unit) {
        idleReleasers[id] = releaser
    }

    /**
     * 泄漏检测：未归还的句柄超过 [leakDetectMs] → 记一条警告。
     *
     * **只记不回收**：强行关闭一个「可能正在用」的句柄，故障从泄漏升级成数据损坏。
     */
    private fun detectLeaks() {
        if (leakDetectMs <= 0L) return
        val now = System.currentTimeMillis()
        for ((id, count) in outstanding) {
            if (count.get() <= 0L) continue
            val used = lastUsed[id]?.get() ?: continue
            val idleFor = now - used
            if (idleFor >= leakDetectMs) {
                AgentLogger.w(
                    TAG,
                    "租约 $id 有 ${count.get()} 个句柄未归还（超过 ${leakDetectMs / 60_000L} 分钟）。" +
                        "调用方可能漏了 close()——租约将**不会**被空闲回收（引用数不归零）"
                )
            }
        }
    }
}
