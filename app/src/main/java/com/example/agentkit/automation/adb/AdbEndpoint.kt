package com.example.agentkit.automation.adb

/**
 * W-52：无线调试端点发现的**纯逻辑**（零 Android 依赖，JVM 可直接断言）。
 *
 * ## 为什么需要它
 *
 * W-51 的配对对话框要用户手抄四个数字（IP、配对端口、6 位码、连接端口），其中
 * 两个端口长得像、位置还不在一处——用户反馈「比装 Shizuku 还麻烦」。事实上
 * **只有 6 位码是拿不到的**：Android 11+ 的无线调试会用 mDNS 广播两个服务，
 * 端口就在广播里：
 *
 * | 服务类型 | 广播的端口 = |
 * |---|---|
 * | `_adb-tls-pairing._tcp` | 配对端口（仅「使用配对码配对设备」弹窗打开期间广播） |
 * | `_adb-tls-connect._tcp` | 连接端口（无线调试开启期间常驻广播） |
 *
 * 所以把「发现 → 挑一个可信的端点」做成纯函数，Android 侧（`AdbDiscovery`）只负责
 * 调 `NsdManager` 把结果喂进来，这里负责去重、挑优先级——这部分最容易写错，
 * 也最值得测。
 */
data class DiscoveredAdbService(
    /** 解析出的 IPv4 主机地址；系统服务偶发解析为空，交由调用方用本机地址兜底。 */
    val host: String?,
    val port: Int
)

/**
 * 一次合并的产物。三个字段都可空——只发现到连接服务（用户还没点开配对弹窗）时
 * `pairPort` 就是 null，UI 据此提示「请在手机上点『使用配对码配对设备』」。
 */
data class DiscoveredAdbEndpoint(
    val host: String?,
    val pairPort: Int?,
    val connectPort: Int?
) {
    /** 三个值齐了才算能直接用。 */
    val complete: Boolean get() = !host.isNullOrBlank() && pairPort != null && connectPort != null

    /** 是否什么都没发现。 */
    val empty: Boolean get() = host.isNullOrBlank() && pairPort == null && connectPort == null
}

/**
 * 合并两类服务，选出可信端点（纯函数）。
 *
 * 规则：
 * 1. **host 优先取配对服务**——它与「用户此刻正打开的配对弹窗」同源，比常驻的
 *    connect 服务更新（换了 Wi-Fi 后 connect 的广播可能还是旧地址）；
 * 2. 端口各取第一个**合法端口**（1..65535），非法的丢掉而不是当成 0——
 *    0 会被 `AdbSession` 判成「未配置」，静默失败比明确报错更难查；
 * 3. 同类型多条时取**端口最大**的那条：无线调试每次重新开启会换新端口，新广播的
 *    端口通常更大；旧广播（已失效）还留在缓存里时，这条规则能挑中新的。
 */
fun mergeDiscoveredAdbServices(
    pairing: List<DiscoveredAdbService>,
    connect: List<DiscoveredAdbService>
): DiscoveredAdbEndpoint {
    fun pickHost(list: List<DiscoveredAdbService>): String? =
        list.mapNotNull { it.host?.takeIf { h -> h.isNotBlank() } }.firstOrNull()

    fun pickPort(list: List<DiscoveredAdbService>): Int? =
        list.map { it.port }.filter { it in 1..65535 }.maxOrNull()

    return DiscoveredAdbEndpoint(
        host = pickHost(pairing) ?: pickHost(connect),
        pairPort = pickPort(pairing),
        connectPort = pickPort(connect)
    )
}
