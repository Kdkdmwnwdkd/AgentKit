#!/usr/bin/env kotlin
/**
 * W-52 QA 行为验证 harness：无线调试端点发现的合并逻辑（[com.example.agentkit.automation.adb.AdbEndpoint]）。
 *
 * 背景：W-51 的配对要用户手抄「IP + 配对端口 + 6 位码 + 连接端口」，用户反馈
 * 「比装 Shizuku 还麻烦」。W-52 改成用 mDNS 自动发现——但 mDNS 本身只能在真机跑，
 * 真正容易写错、也最值得测的是**发现结果怎么挑**：
 *
 *  - 两类服务（pairing / connect）的端口含义不同，混用会「配对码正确却解密失败」；
 *  - 无线调试重开一次就换端口，缓存里的旧广播会和新广播同时存在，必须挑新的；
 *  - 端口为 0 / 越界时必须丢弃，否则会被下游判成「未配置」而静默失败。
 *
 * 本 harness 只测纯函数（零 Android 依赖，JVM 直跑）。
 * 运行方式见 scripts/qa/run-adb-discovery-verification.sh。
 */

import com.example.agentkit.automation.adb.DiscoveredAdbService
import com.example.agentkit.automation.adb.mergeDiscoveredAdbServices

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun svc(host: String?, port: Int) = DiscoveredAdbService(host, port)

fun main() {
    println("=== 无线调试端点发现（合并逻辑）验证 ===")

    check("两类服务都发现 → 三个字段齐全、complete=true") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc("192.168.1.8", 37123)),
            connect = listOf(svc("192.168.1.8", 35123))
        )
        assertEquals("host", "192.168.1.8", ep.host)
        assertEquals("pairPort", 37123, ep.pairPort)
        assertEquals("connectPort", 35123, ep.connectPort)
        assertEquals("complete", true, ep.complete)
    }

    check("只发现连接服务（用户还没点开配对弹窗）→ pairPort 为 null、不可提交") {
        val ep = mergeDiscoveredAdbServices(
            pairing = emptyList(),
            connect = listOf(svc("192.168.1.8", 35123))
        )
        assertEquals("pairPort", null, ep.pairPort)
        assertEquals("connectPort", 35123, ep.connectPort)
        assertEquals("complete", false, ep.complete)
        assertEquals("not empty", false, ep.empty)
    }

    check("只发现配对服务 → connectPort 为 null") {
        val ep = mergeDiscoveredAdbServices(listOf(svc("10.0.0.5", 40001)), emptyList())
        assertEquals("host", "10.0.0.5", ep.host)
        assertEquals("pairPort", 40001, ep.pairPort)
        assertEquals("connectPort", null, ep.connectPort)
    }

    check("host 优先取配对服务（它比常驻的 connect 更新，换 Wi-Fi 后 connect 可能是旧地址）") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc("192.168.1.20", 37123)),
            connect = listOf(svc("192.168.1.9", 35123))
        )
        assertEquals("host", "192.168.1.20", ep.host)
    }

    check("配对服务解析不出 host → 退回连接服务的 host") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc(null, 37123)),
            connect = listOf(svc("192.168.1.9", 35123))
        )
        assertEquals("host", "192.168.1.9", ep.host)
        assertEquals("pairPort 仍保留", 37123, ep.pairPort)
    }

    check("同类多条（旧广播没清掉）→ 取端口最大的那条（重开无线调试会换新端口）") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc("192.168.1.8", 37123), svc("192.168.1.8", 41234)),
            connect = listOf(svc("192.168.1.8", 35123), svc("192.168.1.8", 39876))
        )
        assertEquals("pairPort 取新", 41234, ep.pairPort)
        assertEquals("connectPort 取新", 39876, ep.connectPort)
    }

    check("非法端口（0 / 越界）被丢弃，不会当成有效值传给下游") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc("192.168.1.8", 0)),
            connect = listOf(svc("192.168.1.8", 70000))
        )
        assertEquals("pairPort", null, ep.pairPort)
        assertEquals("connectPort", null, ep.connectPort)
    }

    check("合法端口与非法端口混在一起 → 只取合法的") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc("192.168.1.8", 0), svc("192.168.1.8", 37123)),
            connect = listOf(svc("192.168.1.8", 65535))
        )
        assertEquals("pairPort", 37123, ep.pairPort)
        assertEquals("connectPort 边界 65535 合法", 65535, ep.connectPort)
    }

    check("空列表 → empty=true、complete=false（UI 据此提示手动填写）") {
        val ep = mergeDiscoveredAdbServices(emptyList(), emptyList())
        assertEquals("empty", true, ep.empty)
        assertEquals("complete", false, ep.complete)
        assertEquals("host", null, ep.host)
    }

    check("host 是空串时按未解析处理（不让空串覆盖已知地址）") {
        val ep = mergeDiscoveredAdbServices(
            pairing = listOf(svc("", 37123)),
            connect = listOf(svc("192.168.1.9", 35123))
        )
        assertEquals("host", "192.168.1.9", ep.host)
    }

    check("幂等：同一输入两次合并结果一致") {
        val p = listOf(svc("192.168.1.8", 37123))
        val c = listOf(svc("192.168.1.8", 35123))
        assertEquals("same", mergeDiscoveredAdbServices(p, c), mergeDiscoveredAdbServices(p, c))
    }

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
