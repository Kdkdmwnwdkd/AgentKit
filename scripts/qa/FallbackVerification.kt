#!/usr/bin/env kotlin
/**
 * Phase 2 · DoD⑤「权限/能力申请一次走完降级链」QA 行为验证 harness。
 *
 * 目标：验证统一「能力链」抽象（core/capability）——纯函数 + 与 T07 工厂联动：
 *   1. 链式降级：第 1 级可用 → 直接选中（轨迹只含 1 条）；
 *      第 1 级不可用第 2 级可用 → 选中第 2 级（轨迹含两条，含第 1 级原因）；
 *   2. 全链不可用 → 返回明确最终文案（含至少一个可操作提示），不抛异常；
 *   3. 空候选链 → 不崩，返回兜底提示；
 *   4. 与 T07 工厂联动：proot 不可用 → 回落到本机 user（复用既有降级/探测，不重复造轮子）；
 *   5. 无障碍未连接 / 总开关关闭 两种情况文案不同（分别给对应提示）。
 *
 * 仓库无测试源集、离线缓存无 JUnit，沿用纯 main() + 手写断言（形态学
 * scripts/qa/LocalModelVerification.kt）。运行方式见 scripts/qa/run-fallback-verification.sh。
 */

import com.example.agentkit.core.capability.BackendAvailability
import com.example.agentkit.core.capability.CAPABILITY_FALLBACK_HINT
import com.example.agentkit.core.capability.Capability
import com.example.agentkit.core.capability.CapabilityRequest
import com.example.agentkit.core.capability.CapabilityResolution
import com.example.agentkit.core.capability.controlScreenSnapshot
import com.example.agentkit.core.capability.logTrace
import com.example.agentkit.core.capability.resolveCapability
import com.example.agentkit.core.capability.shellBackendAvailability
import com.example.agentkit.core.capability.shellCapabilitySnapshot
import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.core.terminal.backend.TerminalBackendFactory
import com.example.agentkit.core.terminal.backend.TerminalBackendProvider
import com.example.agentkit.core.terminal.backend.TerminalIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
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

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("[$name] expected to contain <$needle> in <${haystack?.take(160)}>")
    }
}

private fun assertNotContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) == true) {
        throw AssertionError("[$name] expected NOT to contain <$needle> in <${haystack.orEmpty().take(160)}>")
    }
}

/* ------------------------------ 假后端 / 假 provider ------------------------------ */

/** 极简假后端：仅用于让工厂 `create()` 返回非 null（验证 getHighestAvailable 的选中）。 */
private class FakeBackend(override val backendKey: String) : TerminalBackend {
    override val identity: TerminalIdentity = TerminalIdentity.User
    override val isAlive: Boolean get() = true
    override val output: Flow<String> = flowOf()
    override suspend fun start(): Boolean = true
    override suspend fun send(input: String): Boolean = true
    override suspend fun close() {}
    override suspend fun awaitExit(): Int = 0
}

/** 假 provider：`probe()` 计数供缓存语义断言；`create()` 仅在可用时返回后端。 */
private class FakeProvider(
    override val identity: TerminalIdentity,
    override val backendKey: String,
    var available: Boolean
) : TerminalBackendProvider {
    var probeCount = 0
    override fun probe(): Boolean {
        probeCount++
        return available
    }
    override fun create(): TerminalBackend? = if (available) FakeBackend(backendKey) else null
}

/** 构造只含 proot + user 两条候选的假工厂。 */
private fun fakeFactory(prootAvailable: Boolean, userAvailable: Boolean): TerminalBackendFactory =
    TerminalBackendFactory(
        listOf(
            FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_PROOT, prootAvailable),
            FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, userAvailable)
        )
    )

private fun avail(id: String, label: String, ok: Boolean, reason: String? = null, hint: String? = null) =
    BackendAvailability(id, label, ok, reason, hint)

/* ================================================================== */
/* 1. 链式降级                                                          */
/* ================================================================== */

private fun testChainDegrade() {
    println("\n== 链式降级：命中即止 / 逐级回落 ==")

    check("第 1 级可用 → 直接选中，轨迹只含 1 条") {
        val req = CapabilityRequest(
            Capability.RUN_SHELL,
            listOf(
                avail("proot", "proot 容器", true),
                avail("user", "本机 user shell", true)
            )
        )
        val r = resolveCapability(req)
        assertTrue("Selected", r is CapabilityResolution.Selected)
        r as CapabilityResolution.Selected
        assertEquals("selected=proot", "proot", r.backend.backendId)
        assertEquals("trace size", 1, r.trace.size)
        assertEquals("trace[0]=proot", "proot", r.trace[0].backendId)
        assertTrue("trace[0] available", r.trace[0].available)
    }

    check("第 1 级不可用、第 2 级可用 → 选中第 2 级，轨迹含 2 条（第 1 条带原因）") {
        val req = CapabilityRequest(
            Capability.RUN_SHELL,
            listOf(
                avail("proot", "proot 容器", false, reason = "未安装 rootfs", hint = "去容器页安装"),
                avail("user", "本机 user shell", true)
            )
        )
        val r = resolveCapability(req)
        assertTrue("Selected", r is CapabilityResolution.Selected)
        r as CapabilityResolution.Selected
        assertEquals("selected=user", "user", r.backend.backendId)
        assertEquals("trace size", 2, r.trace.size)
        assertEquals("trace[0]=proot", "proot", r.trace[0].backendId)
        assertTrue("trace[0] unavailable", !r.trace[0].available)
        assertEquals("trace[0] reason", "未安装 rootfs", r.trace[0].reason)
        assertEquals("trace[1]=user", "user", r.trace[1].backendId)
        assertTrue("trace[1] available", r.trace[1].available)
    }

    check("纯函数确定性：同样入参两次求解结果相等（且不抛）") {
        val req = CapabilityRequest(
            Capability.RUN_SHELL,
            listOf(avail("proot", "proot 容器", false, "x"), avail("user", "本机 user shell", true))
        )
        assertEquals("equal results", resolveCapability(req), resolveCapability(req))
    }

    check("命中还可在第 3 级（兜底级）——轨迹随之增长到 3 条") {
        val req = CapabilityRequest(
            Capability.RUN_SHELL,
            listOf(
                avail("a", "A", false, "ra"),
                avail("b", "B", false, "rb"),
                avail("c", "C", true)
            )
        )
        val r = resolveCapability(req) as CapabilityResolution.Selected
        assertEquals("selected=c", "c", r.backend.backendId)
        assertEquals("trace size", 3, r.trace.size)
    }
}

/* ================================================================== */
/* 2. 全链不可用 / 3. 空候选链                                          */
/* ================================================================== */

private fun testUnavailableAndEmpty() {
    println("\n== 全链不可用 + 空候选链（不静默 / 不崩）==")

    check("全链不可用 → Unavailable，message 列出各级原因 + hint 含可操作提示，不抛") {
        val req = CapabilityRequest(
            Capability.RUN_SHELL,
            listOf(
                shellBackendAvailability(TerminalBackend.KEY_PROOT, available = false),
                shellBackendAvailability(TerminalBackend.KEY_USER, available = false)
            )
        )
        val r = resolveCapability(req)
        assertTrue("Unavailable", r is CapabilityResolution.Unavailable)
        r as CapabilityResolution.Unavailable
        assertEquals("trace size", 2, r.trace.size)
        assertContains("message says cannot", r.message, "无法")
        assertContains("message has proot reason", r.message, "rootfs")
        assertTrue("hint non-blank", r.hint.isNotBlank())
        // 至少一个可操作提示（proot → 容器页一键安装）
        assertContains("hint actionable", r.hint, "容器")
        assertEquals("selected backend field absent path", 2, r.trace.count { !it.available })
    }

    check("空候选链 → 不崩，返回兜底提示") {
        val r = resolveCapability(CapabilityRequest(Capability.RUN_SHELL, emptyList()))
        assertTrue("Unavailable", r is CapabilityResolution.Unavailable)
        r as CapabilityResolution.Unavailable
        assertEquals("trace empty", 0, r.trace.size)
        assertContains("bootstrap message", r.message, "无法")
        assertTrue("fallback hint non-blank", r.hint.isNotBlank())
        assertEquals("bootstrap hint", CAPABILITY_FALLBACK_HINT, r.hint)
    }

    check("logTrace 对两种结果都不抛（可观测性旁路）") {
        val sel = resolveCapability(
            CapabilityRequest(Capability.RUN_SHELL, listOf(avail("proot", "proot 容器", true)))
        )
        val uns = resolveCapability(
            CapabilityRequest(Capability.RUN_SHELL, listOf(avail("proot", "proot 容器", false, "r")))
        )
        sel.logTrace()
        uns.logTrace()
    }
}

/* ================================================================== */
/* 4. 与 T07 工厂联动：proot 不可用 → 回落本机 user                     */
/* ================================================================== */

private fun testFactoryLinkage() {
    println("\n== 与 T07 工厂联动：proot 不可用 → 回落本机 user ==")

    check("shellCapabilitySnapshot 顺序 = shizuku(诊断) → proot → user，且可用性来自工厂 probe") {
        val factory = fakeFactory(prootAvailable = false, userAvailable = true)
        val snapshot = factory.shellCapabilitySnapshot()
        // 3 个候选：首项是 shizuku 调试通道的「诊断条目」。
        // 它排在最前是因为 uid 2000 能做的活最多，但**不可用时只作诊断呈现**，
        // 不影响 proot → user 的降级链（见 CapabilityBridges.shellCapabilitySnapshot 的 KDoc）。
        // 注意：本断言原先写的是 2 candidates / 1st=proot，是 Shizuku 诊断条目加入前的旧期望，
        // 一直报红——现已对齐当前实现语义。
        assertEquals("3 candidates", 3, snapshot.size)
        assertEquals("1st=shizuku(diagnostic)", TerminalBackend.KEY_SHIZUKU, snapshot[0].backendId)
        assertTrue("shizuku unavailable", !snapshot[0].available)
        assertEquals("2nd=proot", TerminalBackend.KEY_PROOT, snapshot[1].backendId)
        assertTrue("proot unavailable", !snapshot[1].available)
        assertTrue("proot has reason", !snapshot[1].reason.isNullOrBlank())
        assertEquals("3rd=user", TerminalBackend.KEY_USER, snapshot[2].backendId)
        assertTrue("user available", snapshot[2].available)
    }

    check("proot 不可用 → 能力链选中 user（与工厂 getHighestAvailable 一致）") {
        val factory = fakeFactory(prootAvailable = false, userAvailable = true)
        val r = resolveCapability(CapabilityRequest(Capability.RUN_SHELL, factory.shellCapabilitySnapshot()))
        assertTrue("Selected", r is CapabilityResolution.Selected)
        r as CapabilityResolution.Selected
        assertEquals("chain selects user", TerminalBackend.KEY_USER, r.backend.backendId)
        // 轨迹含诊断条目：shizuku → proot → user，共 3 条
        assertEquals("trace size", 3, r.trace.size)
        // 复用既有断言思路：工厂自身的降级结果也回落到 user
        assertEquals("factory highest=user", TerminalBackend.KEY_USER, factory.getHighestAvailable()?.backendKey)
    }

    check("proot 可用 → 能力链直接选中 proot（轨迹 1 条），工厂亦返回 proot") {
        val factory = fakeFactory(prootAvailable = true, userAvailable = true)
        val r = resolveCapability(CapabilityRequest(Capability.RUN_SHELL, factory.shellCapabilitySnapshot()))
        r as CapabilityResolution.Selected
        assertEquals("chain selects proot", TerminalBackend.KEY_PROOT, r.backend.backendId)
        // 轨迹含诊断条目：shizuku → proot，共 2 条
        assertEquals("trace size", 2, r.trace.size)
        assertEquals("factory highest=proot", TerminalBackend.KEY_PROOT, factory.getHighestAvailable()?.backendKey)
    }

    check("两级都不可用 → 能力链 Unavailable（与 getHighestAvailable=null 一致）") {
        val factory = fakeFactory(prootAvailable = false, userAvailable = false)
        val r = resolveCapability(CapabilityRequest(Capability.RUN_SHELL, factory.shellCapabilitySnapshot()))
        assertTrue("Unavailable", r is CapabilityResolution.Unavailable)
        assertEquals("factory highest=null", null, factory.getHighestAvailable()?.backendKey)
    }
}

/* ================================================================== */
/* 5. 无障碍：两种缺失文案不同                                          */
/* ================================================================== */

private fun testAccessibilityCopy() {
    println("\n== 无障碍：未连接 / 总开关关闭 文案不同 ==")

    check("服务连接 + 总开关开 → 可用（Selected）") {
        val snap = controlScreenSnapshot(serviceConnected = true, masterEnabled = true, shizukuAvailable = false)
        val r = resolveCapability(CapabilityRequest(Capability.CONTROL_SCREEN, snap))
        assertTrue("Selected", r is CapabilityResolution.Selected)
    }

    check("无障碍服务未连接 → 文案指向系统「无障碍」设置") {
        val snap = controlScreenSnapshot(serviceConnected = false, masterEnabled = true, shizukuAvailable = false)
        val r = resolveCapability(CapabilityRequest(Capability.CONTROL_SCREEN, snap))
        assertTrue("Unavailable", r is CapabilityResolution.Unavailable)
        r as CapabilityResolution.Unavailable
        assertContains("reason mentions not connected", r.message, "未连接")
        assertContains("hint mentions accessibility settings", r.hint, "无障碍")
        assertNotContains("hint should not mention master switch", r.hint, "自动化总开关")
    }

    check("自动化总开关关闭 → 文案指向 App「自动化」总开关") {
        val snap = controlScreenSnapshot(serviceConnected = true, masterEnabled = false, shizukuAvailable = false)
        val r = resolveCapability(CapabilityRequest(Capability.CONTROL_SCREEN, snap))
        assertTrue("Unavailable", r is CapabilityResolution.Unavailable)
        r as CapabilityResolution.Unavailable
        assertContains("reason mentions master switch", r.message, "总开关")
        assertContains("hint mentions master switch action", r.hint, "总开关")
    }

    check("两种缺失的最终文案/hint 不相同，且都非空") {
        val notConnected = resolveCapability(
            CapabilityRequest(Capability.CONTROL_SCREEN, controlScreenSnapshot(false, true, false))
        ) as CapabilityResolution.Unavailable
        val masterOff = resolveCapability(
            CapabilityRequest(Capability.CONTROL_SCREEN, controlScreenSnapshot(true, false, false))
        ) as CapabilityResolution.Unavailable
        assertTrue("messages differ", notConnected.message != masterOff.message)
        assertTrue("hints differ", notConnected.hint != masterOff.hint)
        assertTrue("both non-blank", notConnected.hint.isNotBlank() && masterOff.hint.isNotBlank())
    }

    check("W-50：无障碍缺失但调试通道可用 → 选中 Shizuku（降级 1 级）") {
        val snap = controlScreenSnapshot(serviceConnected = false, masterEnabled = true, shizukuAvailable = true)
        val r = resolveCapability(CapabilityRequest(Capability.CONTROL_SCREEN, snap))
        assertTrue("Selected", r is CapabilityResolution.Selected)
        r as CapabilityResolution.Selected
        assertTrue("backend = shizuku", r.backend.backendId == "shizuku")
        assertTrue("降级 1 级 = 轨迹 2 条", r.trace.size == 2)
        assertTrue("首条是无障碍且不可用", r.trace[0].available.not())
    }

    check("W-50：两条都不可用 → 仍明确告知，并同时给出两种修复路径") {
        val snap = controlScreenSnapshot(serviceConnected = false, masterEnabled = false, shizukuAvailable = false)
        val r = resolveCapability(CapabilityRequest(Capability.CONTROL_SCREEN, snap)) as CapabilityResolution.Unavailable
        assertContains("hint 提到无障碍", r.hint, "无障碍")
        assertContains("hint 提到调试通道", r.hint, "调试通道")
        assertTrue("轨迹含两级候选", r.trace.size == 2)
    }

    check("两者都缺 → 合并给出两条动作（先系统后 App）") {
        val r = resolveCapability(
            CapabilityRequest(Capability.CONTROL_SCREEN, controlScreenSnapshot(false, false, false))
        ) as CapabilityResolution.Unavailable
        assertContains("has accessibility action", r.hint, "无障碍")
        assertContains("has master switch action", r.hint, "总开关")
    }
}

fun main() {
    println("=== Phase 2 · DoD⑤ 能力/权限申请一次走完降级链 QA 行为验证 ===")
    testChainDegrade()
    testUnavailableAndEmpty()
    testFactoryLinkage()
    testAccessibilityCopy()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
