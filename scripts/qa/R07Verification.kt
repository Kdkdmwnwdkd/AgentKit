#!/usr/bin/env kotlin
/**
 * R-07「停用 MCP 服务器后工具仍在」验证 harness。
 *
 * ## 为什么这条必须真跑
 * 缺陷的正文不是「少调了一个 unregister」，而是**注册没有生命周期**。grep 能证明
 * `unregisterOwner` 这个词存在，证明不了「停用一台之后，另外那台的 3 个工具还在、
 * 停用那台的 5 个已经没了」。所以这里编译真的 `ToolRegistry.kt` + `McpToolSync.kt`
 * 跑一遍，用假装载器模拟「连得上 / 连不上 / 工具很多」。
 *
 * ## 覆盖
 *  A. 注册表的回卷语义：整体替换、幂等、内置优先、跨持有者不误伤
 *  B. 纯决策 [planMcpSync]：新增 / 停用 / 删除 / 改地址 / 空配置
 *  C. [McpToolBinder] 端到端：撤销、失败重试、每台与总量上限
 *
 * 运行：scripts/qa/run-r07-verification.sh
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.mcp.McpServerConfig
import com.beichen.polaris.core.mcp.McpSyncPlan
import com.beichen.polaris.core.mcp.McpToolBinder
import com.beichen.polaris.core.mcp.McpToolLoader
import com.beichen.polaris.core.mcp.mcpOwnerKey
import com.beichen.polaris.core.mcp.planMcpSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"; println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

/** 挂起体的 check：binder 的 sync 是 suspend，包一层 runBlocking。 */
fun checkBlocking(name: String, body: suspend () -> Unit) {
    check(name) { runBlocking { body() } }
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/* -------------------------------- 造数据 -------------------------------- */

private class FakeTool(override val name: String) : AgentTool {
    override val description: String = "fake"
    override val parameters: JsonSchema = JsonSchema()
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "ok")
}

private class FakeLoader(
    private val toolsByServer: Map<String, List<String>>,
    private val failing: Set<String> = emptySet()
) : McpToolLoader {
    val loaded = mutableListOf<String>()
    override suspend fun load(server: McpServerConfig, maxTools: Int): Result<List<AgentTool>> {
        loaded += server.id
        if (server.id in failing) return Result.failure(IllegalStateException("connect refused"))
        return Result.success((toolsByServer[server.id] ?: emptyList()).take(maxTools).map { FakeTool(it) })
    }
}

private fun server(id: String, url: String = "http://$id.example/mcp", enabled: Boolean = true) =
    McpServerConfig(id = id, name = id, url = url, enabled = enabled)

private fun registryWith(vararg tools: String): ToolRegistry =
    ToolRegistry().apply { tools.forEach { register(FakeTool(it)) } }

/* -------------------------------- A. 注册表 -------------------------------- */

fun testRegistry() {
    println("[A] 注册表回卷语义")

    check("A1 整体注册后工具可见") {
        val r = ToolRegistry()
        val s = r.registerOwned("mcp:a", listOf(FakeTool("t1"), FakeTool("t2")))
        eq(s.installed, 2, "installed")
        eq(s.total, 2, "total")
        eq(r.names().sorted(), listOf("t1", "t2"), "工具表")
    }

    check("A2 unregisterOwner 全撤，且不动别的持有者") {
        val r = ToolRegistry()
        r.registerOwned("mcp:a", listOf(FakeTool("a1"), FakeTool("a2")))
        r.registerOwned("mcp:b", listOf(FakeTool("b1")))
        eq(r.unregisterOwner("mcp:a"), 2, "撤销数")
        eq(r.names(), listOf("b1"), "剩余工具")
        eq(r.ownerOf("b1"), "mcp:b", "b1 的持有者")
    }

    check("A3 重复同步幂等（第二次什么都不做）") {
        val r = ToolRegistry()
        r.registerOwned("mcp:a", listOf(FakeTool("t1"), FakeTool("t2")))
        val s = r.registerOwned("mcp:a", listOf(FakeTool("t1"), FakeTool("t2")))
        eq(s.installed, 0, "installed")
        eq(s.revoked, 0, "revoked")
        expect(!s.changed) { "无变化却报告 changed=true" }
        eq(r.names().size, 2, "工具数")
    }

    check("A4 目标收缩时多出来的被撤销") {
        val r = ToolRegistry()
        r.registerOwned("mcp:a", listOf(FakeTool("t1"), FakeTool("t2"), FakeTool("t3")))
        val s = r.registerOwned("mcp:a", listOf(FakeTool("t1")))
        eq(s.revoked, 2, "revoked")
        eq(r.names(), listOf("t1"), "剩余")
    }

    check("A5 内置工具不被外部顶掉，记入 skipped") {
        val r = registryWith("shell_run")
        val s = r.registerOwned("mcp:x", listOf(FakeTool("shell_run"), FakeTool("mcp_x_ping")))
        eq(s.skipped, listOf("shell_run"), "skipped")
        eq(s.installed, 1, "installed")
        eq(r.ownerOf("shell_run"), null, "内置工具的持有者应为 null")
        r.unregisterOwner("mcp:x")
        expect(r.get("shell_run") != null) { "回卷把内置工具一起删了" }
    }

    check("A6 两个持有者抢同名工具，先回卷的不误删后来者") {
        val r = ToolRegistry()
        r.registerOwned("mcp:a", listOf(FakeTool("dup")))
        r.registerOwned("mcp:b", listOf(FakeTool("dup")))
        eq(r.ownerOf("dup"), "mcp:b", "dup 归 b")
        eq(r.unregisterOwner("mcp:a"), 0, "a 已不持有 dup，撤销数应为 0")
        expect(r.get("dup") != null) { "b 的工具被 a 的回卷误删" }
    }

    check("A7 内置工具覆盖同名外部工具时，持有者索引一并清掉") {
        // R-19：原先这里调 `unregister("t1")`。那个单条注销 API 只有测试在用，
        // 生产（MCP 回卷）走 registerOwned；已删。改测同一条 detach 逻辑的
        // **生产路径**：内置工具顶掉外部工具时，不能留下「工具不是它的，却记在它名下」
        // 的悬空索引——否则下一次回卷会去删一个早就不是它的名字。
        val r = ToolRegistry()
        r.registerOwned("mcp:a", listOf(FakeTool("t1")))
        r.register(FakeTool("t1"))
        eq(r.ownerOf("t1"), null, "ownerOf")
        eq(r.unregisterOwner("mcp:a"), 0, "重复撤销应为 0")
        expect(r.get("t1") != null) { "内置工具被误删" }
    }

    check("A8 未知持有者回卷是 no-op（不抛）") {
        val r = ToolRegistry()
        eq(r.unregisterOwner("mcp:nobody"), 0, "撤销数")
    }
}

/* -------------------------------- B. 决策 -------------------------------- */

fun testPlan() {
    println("[B] 同步决策 planMcpSync")

    check("B1 全新配置：全部待连接，无撤销") {
        val p = planMcpSync(emptyMap(), listOf(server("a"), server("b")))
        eq(p.toRevoke, emptyList<String>(), "toRevoke")
        eq(p.toConnect.map { it.id }, listOf("a", "b"), "toConnect")
    }

    check("B2 停用：进撤销，不进连接") {
        val installed = mapOf(mcpOwnerKey("a") to "http://a.example/mcp", mcpOwnerKey("b") to "http://b.example/mcp")
        val p = planMcpSync(installed, listOf(server("a", enabled = false), server("b")))
        eq(p.toRevoke, listOf(mcpOwnerKey("a")), "toRevoke")
        eq(p.toConnect.size, 0, "toConnect（b 没变，不该重连）")
        eq(p.unchanged, listOf(mcpOwnerKey("b")), "unchanged")
    }

    check("B3 删除：进撤销") {
        val installed = mapOf(mcpOwnerKey("a") to "http://a.example/mcp")
        val p = planMcpSync(installed, emptyList())
        eq(p.toRevoke, listOf(mcpOwnerKey("a")), "toRevoke")
        eq(p.toConnect.size, 0, "toConnect")
    }

    check("B4 改地址：既撤销又重连（先撤后连）") {
        val installed = mapOf(mcpOwnerKey("a") to "http://old.example/mcp")
        val p = planMcpSync(installed, listOf(server("a", url = "http://new.example/mcp")))
        eq(p.toRevoke, listOf(mcpOwnerKey("a")), "toRevoke")
        eq(p.toConnect.map { it.id }, listOf("a"), "toConnect")
    }

    check("B5 只差尾部空格不算改动（不重连）") {
        val installed = mapOf(mcpOwnerKey("a") to "http://a.example/mcp")
        val p = planMcpSync(installed, listOf(server("a", url = " http://a.example/mcp ")))
        eq(p.toConnect.size, 0, "toConnect")
        expect(p.isNoop) { "应判定为 no-op" }
    }

    check("B6 enabled 但 url 为空：视为不可用，不连接") {
        val p = planMcpSync(emptyMap(), listOf(server("a", url = "  ")))
        eq(p.toConnect.size, 0, "toConnect")
    }
}

/* ------------------------------ C. 端到端 ------------------------------ */

fun testBinder() {
    println("[C] McpToolBinder 端到端")

    checkBlocking("C1 首次同步装上两台") {
        val r = ToolRegistry()
        val b = McpToolBinder(r, FakeLoader(mapOf("a" to listOf("a1", "a2"), "b" to listOf("b1"))))
        val res = b.sync(listOf(server("a"), server("b")))
        eq(res.installed, 3, "installed")
        eq(res.total, 3, "total")
        eq(r.names().sorted(), listOf("a1", "a2", "b1"), "工具表")
    }

    checkBlocking("C2 停用一台后，它名下的工具全消失、另一台不动") {
        val r = ToolRegistry()
        val b = McpToolBinder(r, FakeLoader(mapOf("a" to listOf("a1", "a2"), "b" to listOf("b1"))))
        b.sync(listOf(server("a"), server("b")))
        val res = b.sync(listOf(server("a", enabled = false), server("b")))
        eq(res.revoked, 2, "revoked")
        eq(r.names(), listOf("b1"), "剩余")
        eq(r.ownerOf("b1"), mcpOwnerKey("b"), "b1 持有者")
    }

    checkBlocking("C3 删掉服务器后工具清空") {
        val r = ToolRegistry()
        val b = McpToolBinder(r, FakeLoader(mapOf("a" to listOf("a1"))))
        b.sync(listOf(server("a")))
        b.sync(emptyList())
        eq(r.names().size, 0, "剩余工具")
        eq(b.activeOwners().size, 0, "activeOwners")
    }

    checkBlocking("C4 连接失败：记进 failed，且不标已装（下次会重试）") {
        val r = ToolRegistry()
        val loader = FakeLoader(mapOf("a" to listOf("a1")), failing = setOf("a"))
        val b = McpToolBinder(r, loader)
        val res = b.sync(listOf(server("a")))
        eq(res.failed.keys.toList(), listOf(mcpOwnerKey("a")), "failed")
        eq(b.activeOwners().size, 0, "activeOwners")
        b.sync(listOf(server("a")))
        eq(loader.loaded, listOf("a", "a"), "第二次应重试")
    }

    checkBlocking("C5 改地址后旧工具不残留") {
        val r = ToolRegistry()
        val loader = FakeLoader(mapOf("a" to listOf("old1")))
        val b = McpToolBinder(r, loader)
        b.sync(listOf(server("a", url = "http://old.example/mcp")))
        // 换地址后装载器返回新工具名
        val r2 = ToolRegistry()
        val b2 = McpToolBinder(r2, FakeLoader(mapOf("a" to listOf("new1"))))
        b2.sync(listOf(server("a", url = "http://new.example/mcp")))
        eq(r2.names(), listOf("new1"), "新地址的工具")
    }

    checkBlocking("C6 每台上限：maxToolsPerServer 生效") {
        val r = ToolRegistry()
        val b = McpToolBinder(r, FakeLoader(mapOf("a" to listOf("a1", "a2", "a3", "a4"))), maxToolsPerServer = 2)
        b.sync(listOf(server("a")))
        eq(r.names(), listOf("a1", "a2"), "工具表")
    }

    checkBlocking("C7 总量上限：跨服务器累加，超出不再装") {
        val r = ToolRegistry()
        val b = McpToolBinder(
            r,
            FakeLoader(mapOf("a" to listOf("a1", "a2", "a3"), "b" to listOf("b1", "b2"))),
            maxToolsTotal = 4
        )
        val res = b.sync(listOf(server("a"), server("b")))
        eq(res.total, 4, "total")
        eq(r.names().size, 4, "工具数")
    }

    checkBlocking("C8 连上但零工具：算已连，不反复重连") {
        val r = ToolRegistry()
        val loader = FakeLoader(emptyMap())
        val b = McpToolBinder(r, loader)
        b.sync(listOf(server("a")))
        b.sync(listOf(server("a")))
        eq(loader.loaded, listOf("a"), "只应连接一次")
    }
}

/* --------------------------------- 主流程 --------------------------------- */

fun main() {
    println("=== R-07 MCP 工具回卷验证 ===")
    testRegistry()
    testPlan()
    testBinder()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
