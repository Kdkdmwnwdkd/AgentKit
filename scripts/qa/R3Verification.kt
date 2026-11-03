#!/usr/bin/env kotlin
/**
 * R3（运行资源租约）行为验证 harness。
 *
 * 为什么单独一套：租约的价值全在**边界行为**上——
 * 引用计数归零才回收、句柄重复 close 不重复减计数、
 * 有活跃引用时**不**回收、泄漏时**只报警不回收**。
 * 这些都是「跑一遍看不出来」的行为，必须用假租约把时间压缩到毫秒级才能断言。
 *
 * 覆盖：
 *  R3-1 acquire/release 正确增减引用数
 *  R3-2 句柄 close 幂等（重复 close 只减一次）
 *  R3-3 引用数 > 0 时不回收
 *  R3-4 引用数归零 + 空闲超时 + 可安全释放 → 回收
 *  R3-5 不可安全释放时不回收（在途任务保护）
 *  R3-6 空闲未超时时不回收
 *  R3-7 泄漏检测：未归还的句柄被记录（只报警不回收）
 *  R3-8 释放失败不影响后续（不抛异常）
 *  R3-9 准入判据：只有「占内存/占进程」且「可回收」的才登记
 *  R3-10 容器租约按发行版独立，id 形状 container:<distro>
 *  R3-11 同一发行版返回同一实例（引用计数不分裂）
 *  R3-12 容器租约持有期间不回收，归零后销毁会话
 *  R3-13 会话已销毁时不重复调用 destroy
 *  R3-14 快照给出可读状态（类别 / 标题 / 释放器）
 *
 * 运行：scripts/qa/run-r3-verification.sh
 */

import com.example.agentkit.runtime.lease.ContainerLeases
import com.example.agentkit.runtime.lease.LeaseKind
import com.example.agentkit.runtime.lease.LeaseRegistry
import com.example.agentkit.runtime.lease.LeaseHandle
import com.example.agentkit.runtime.lease.RuntimeLease
import kotlinx.coroutines.runBlocking

private var passed = 0
private val failures = mutableListOf<String>()

private suspend fun check(name: String, block: suspend () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

/**
 * 可控的假租约。
 *
 * @param safeToRelease 模拟「有没有在途任务」——回收器必须尊重它。
 * @param releaseThrows 模拟释放动作抛异常。
 */
private class FakeLease(
    override val id: String,
    var safeToRelease: Boolean = true,
    var releaseThrows: Boolean = false,
) : RuntimeLease {

    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    var releaseCount = 0
        private set

    override val activeCount: Int get() = active.get()

    override val isSafeToRelease: Boolean get() = safeToRelease

    override suspend fun acquire(): LeaseHandle {
        active.incrementAndGet()
        return LeaseRegistry.acquire(this) {
            active.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    /** 模拟底层释放动作。 */
    fun doRelease() {
        releaseCount++
        if (releaseThrows) throw IllegalStateException("释放失败")
    }
}

/** 每个用例用独立 id，避免相互干扰；测试后清理。 */
private var counter = 0

private fun nextId(prefix: String): String = "$prefix-${counter++}"

private fun main2() = runBlocking {
    println("\n[R3] 运行资源租约")

    check("R3-1 acquire/release 正确增减引用数") {
        val lease = FakeLease(nextId("t1"))
        assertTrue("初始引用数应为 0", lease.activeCount == 0)
        val h1 = lease.acquire()
        assertTrue("acquire 后应为 1，实际 ${lease.activeCount}", lease.activeCount == 1)
        val h2 = lease.acquire()
        assertTrue("两次 acquire 后应为 2，实际 ${lease.activeCount}", lease.activeCount == 2)
        h1.close()
        assertTrue("关闭一个后应为 1，实际 ${lease.activeCount}", lease.activeCount == 1)
        h2.close()
        assertTrue("全部关闭后应为 0，实际 ${lease.activeCount}", lease.activeCount == 0)
    }

    check("R3-2 句柄 close 幂等（重复 close 只减一次）") {
        val lease = FakeLease(nextId("t2"))
        val h = lease.acquire()
        h.close()
        assertTrue("关闭后应为 0", lease.activeCount == 0)
        // 重复 close：不能把计数减成 -1
        h.close()
        h.close()
        assertTrue("重复 close 后仍应为 0，实际 ${lease.activeCount}", lease.activeCount == 0)
    }

    check("R3-3 引用数 > 0 时不回收") {
        val lease = FakeLease(nextId("t3"))
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        val h = lease.acquire()
        LeaseRegistry.idleTimeoutMs = 0L   // 立刻「空闲超时」
        LeaseRegistry.checkOnce()
        assertTrue("有人持有时不应回收，实际释放 ${lease.releaseCount} 次", lease.releaseCount == 0)
        h.close()
    }

    check("R3-4 引用数归零 + 空闲超时 + 可安全释放 → 回收") {
        val lease = FakeLease(nextId("t4"))
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        val h = lease.acquire()
        h.close()
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("应被回收一次，实际 ${lease.releaseCount} 次", lease.releaseCount == 1)
    }

    check("R3-5 不可安全释放时不回收（在途任务保护）") {
        val lease = FakeLease(nextId("t5"), safeToRelease = false)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("不可安全释放时不应回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 0)
    }

    check("R3-6 空闲未超时时不回收") {
        val lease = FakeLease(nextId("t6"))
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 60 * 60_000L   // 一小时
        LeaseRegistry.checkOnce()
        assertTrue("未到空闲阈值不应回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 0)
    }

    check("R3-7 泄漏检测：未归还的句柄被记录（只报警不回收）") {
        val lease = FakeLease(nextId("t7"))
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        // 借了不还
        lease.acquire()
        assertTrue("未归还数应为 1，实际 ${LeaseRegistry.outstandingCount(lease.id)}",
            LeaseRegistry.outstandingCount(lease.id) == 1L)
        LeaseRegistry.leakDetectMs = 0L        // 立刻判定泄漏
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        // 关键：泄漏的租约**不得**被回收——强行回收会把正在用的资源夺走
        assertTrue("泄漏的租约不应被回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 0)
        assertTrue("未归还数仍应为 1，实际 ${LeaseRegistry.outstandingCount(lease.id)}",
            LeaseRegistry.outstandingCount(lease.id) == 1L)
    }

    check("R3-8 释放失败不影响后续（不抛异常）") {
        val lease = FakeLease(nextId("t8"), releaseThrows = true)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 0L
        // checkOnce 内部对释放动作做了 runCatching，不应抛出
        LeaseRegistry.checkOnce()
        assertTrue("释放动作应被调用过", lease.releaseCount == 1)
    }

    check("R3-9 回收只作用于登记过的租约") {
        val registered = FakeLease(nextId("t9r"))
        LeaseRegistry.register(registered)
        LeaseRegistry.registerIdleReleaser(registered.id) { registered.doRelease() }
        val unregistered = FakeLease(nextId("t9u"))
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("已登记的应回收", registered.releaseCount == 1)
        assertTrue("未登记的不应回收", unregistered.releaseCount == 0)
    }

    /* ------------------------------------------------------------------ */
    /* R8：容器会话租约（container:<distro>）                              */
    /* ------------------------------------------------------------------ */

    check("R3-10 容器租约按发行版独立，id 形状为 container:<distro>") {
        val a = ContainerLeases.forDistro("r3-alpine", alive = { true }, destroy = {})
        val b = ContainerLeases.forDistro("r3-debian", alive = { true }, destroy = {})
        assertTrue("alpine id 应为 container:r3-alpine，实际 ${a.id}", a.id == "container:r3-alpine")
        assertTrue("两个发行版应是不同租约", a !== b)
        assertTrue("类别应识别为容器", LeaseKind.of(a.id) == LeaseKind.CONTAINER)
        // terminal:proot 与 container:alpine 是两批不同的进程，不能归到同一类
        assertTrue("终端租约不应被认成容器", LeaseKind.of("terminal:proot") == LeaseKind.TERMINAL)
    }

    check("R3-11 同一发行版返回同一实例（引用计数不分裂）") {
        val first = ContainerLeases.forDistro("r3-shared", alive = { true }, destroy = {})
        val second = ContainerLeases.forDistro("r3-shared", alive = { true }, destroy = {})
        assertTrue("同一 distro 应返回同一实例，否则两套计数各自归零", first === second)
    }

    check("R3-12 容器租约持有期间不回收，归零后销毁会话") {
        var destroyed = 0
        val lease = ContainerLeases.forDistro("r3-idle", alive = { true }, destroy = { destroyed++ })
        val handle = lease.borrow()
        assertTrue("借用后持有者应为 1，实际 ${lease.activeCount}", lease.activeCount == 1)
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("有人持有时不应销毁，实际 $destroyed 次", destroyed == 0)
        handle.close()
        assertTrue("归还后持有者应为 0，实际 ${lease.activeCount}", lease.activeCount == 0)
        LeaseRegistry.checkOnce()
        assertTrue("空闲后应销毁一次，实际 $destroyed 次", destroyed == 1)
    }

    check("R3-13 会话已销毁时不重复调用 destroy（回收器定期空转）") {
        var destroyed = 0
        var alive = true
        ContainerLeases.forDistro("r3-dead", alive = { alive }, destroy = { destroyed++; alive = false })
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()   // 第一次：会话存活 → 销毁
        LeaseRegistry.checkOnce()   // 第二次：已死 → 必须跳过
        assertTrue("只应销毁一次，实际 $destroyed 次", destroyed == 1)
    }

    check("R3-14 快照给出可读状态（类别 / 标题 / 释放器）") {
        val lease = ContainerLeases.forDistro("r3-snap", alive = { true }, destroy = {})
        val snap = LeaseRegistry.snapshot().firstOrNull { it.id == lease.id }
        assertTrue("快照应包含容器租约", snap != null)
        requireNotNull(snap)
        assertTrue("类别应为容器，实际 ${snap.kind}", snap.kind == LeaseKind.CONTAINER)
        assertTrue("应已注册释放器（否则回收器会跳过）", snap.releaserRegistered)
        assertTrue("标题应含发行版，实际 ${snap.title}", snap.title.contains("r3-snap"))
    }
}

fun main() {
    println("=== AgentKit R3 验证（运行资源租约）===")
    main2()
    println("\n=== 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
