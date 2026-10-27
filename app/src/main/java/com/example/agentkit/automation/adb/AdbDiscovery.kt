package com.example.agentkit.automation.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.agentkit.core.AgentLogger
import java.net.Inet4Address
import java.util.ArrayDeque

/**
 * W-52：用 mDNS 自动发现本机的无线调试端点，把「手抄四个数字」压成「只输 6 位码」。
 *
 * ## 背景
 *
 * W-51 的配对要用户填 IP、配对端口、6 位码、连接端口。用户的原话是
 * 「比下 Shizuku 还麻烦」——这个批评成立：Shizuku 只需点一次授权。
 *
 * 但**四个里只有配对码是拿不到的**（它是系统弹窗里的随机数，属系统安全边界）。
 * 其余三个都在 mDNS 广播里：
 *
 * ```
 * _adb-tls-pairing._tcp  → 端口 = 配对端口（仅配对弹窗打开期间广播，约 2 分钟）
 * _adb-tls-connect._tcp  → 端口 = 连接端口（无线调试开启期间常驻）
 * ```
 *
 * ## 为什么用 NsdManager 而不是自己发 mDNS 查询
 *
 * NsdManager 是系统服务，已处理组播加入/退出的权限、IPv6、多网卡选择；自己开
 * `MulticastSocket` 还要申请 `CHANGE_WIFI_MULTICAST_STATE` 并处理一堆兼容问题。
 * 代价是 NsdManager 的两个已知限制，本类做了规避：
 *
 * 1. **`resolveService` 并发会失败**（`FAILURE_ALREADY_ACTIVE`）→ 用串行队列
 *    （[pendingResolve] + [resolving] 标志）逐个解析；
 * 2. **同一 serviceType 重复 discover 会抛**（`listener already in use`）→ 每次
 *    新建 listener、`stop()` 里成对注销，且所有调用包 `runCatching`。
 *
 * ## 失败不是异常，是常态
 *
 * 部分 ROM（尤其国产定制）限制 mDNS 组播，或用户在飞行模式/热点场景下广播不可达。
 * 所以 [start] 的失败一律走 [onError] 回调，UI 侧保留手动填写作为兜底——**自动发现
 * 是加速，不是唯一路径**。
 */
internal object AdbDiscovery {

    private const val TAG = "AdbDiscovery"

    /** AOSP `adb/pairing_connection` 广播的服务类型。 */
    const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"

    /** AOSP `adb/daemon` 无线调试常驻广播的服务类型。 */
    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    /**
     * 一次发现会话。调用方（对话框）必须在退出时 [stop]，否则 listener 泄漏、
     * 且下次打开对话框时同类型 discover 会抛 `listener already in use`。
     */
    class Session internal constructor(
        private val context: Context,
        private val nsd: NsdManager,
        private val onUpdate: (DiscoveredAdbEndpoint) -> Unit,
        private val onError: (String) -> Unit
    ) {
        /**
         * 保护下面所有可变状态。
         *
         * NsdManager 的回调跑在 **binder 线程**，而 [stop] 由 UI（主线程）在 `onDispose` 里调——
         * `pending.addLast` 与 `pending.clear()` 并发会抛 `ConcurrentModificationException`，
         * 表现为"关掉对话框时偶发崩溃"。原先这几个集合裸奔。
         */
        private val stateLock = Any()

        private val pairing = mutableListOf<DiscoveredAdbService>()
        private val connect = mutableListOf<DiscoveredAdbService>()
        private val pending = ArrayDeque<Pair<String, NsdServiceInfo>>()
        private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        /** 服务名 → (类型, 解析结果)：`onServiceLost` 只能靠名字找到要摘哪一条（端口此时可能为 0）。 */
        private val byName = mutableMapOf<String, Pair<String, DiscoveredAdbService>>()

        private var resolving = false

        @Volatile
        private var stopped = false

        internal fun begin() {
            startDiscovery(SERVICE_PAIRING)
            startDiscovery(SERVICE_CONNECT)
        }

        fun stop() {
            stopped = true
            val snapshot = synchronized(stateLock) {
                pending.clear()
                byName.clear()
                pairing.clear()
                connect.clear()
                listeners.toList().also { listeners.clear() }
            }
            snapshot.forEach { listener ->
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }

        private fun startDiscovery(type: String) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    AgentLogger.i(TAG, "开始发现 $serviceType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (stopped) return
                    // 端口必须解析后才可用；serviceInfo.port 在 resolve 前是 0
                    synchronized(stateLock) { pending.addLast(type to service) }
                    drain()
                }

                /**
                 * 端口失效（无线调试重开 / 配对弹窗关闭）：**真的从候选里摘掉**。
                 *
                 * 原实现只打了一行日志，注释却写着「从候选里摘掉」——两处不一致。
                 * 后果是配对弹窗关掉后旧端口仍留在 `pairing` 里，`merge` 继续拿它当配对端口，
                 * 用户下次配对就得到「配对码正确却解密失败」（那正是 W-51 里最想避免的错误）。
                 * `merge` 的「取端口最大」只是碰巧缓解，端口更小就错。
                 */
                override fun onServiceLost(service: NsdServiceInfo) {
                    if (stopped) return
                    val merged = synchronized(stateLock) {
                        val hit = byName.remove(service.serviceName)
                            // 兜底：resolve 前后 serviceName 可能不同，按端口再找一次
                            ?: service.port.takeIf { it > 0 }?.let { p ->
                                byName.entries.firstOrNull { it.value.second.port == p }?.let { e ->
                                    byName.remove(e.key)
                                }
                            }
                        if (hit == null) return@synchronized null
                        val (t, svc) = hit
                        if (t == SERVICE_PAIRING) pairing.remove(svc) else connect.remove(svc)
                        mergeDiscoveredAdbServices(pairing, connect)
                    } ?: return
                    AgentLogger.i(
                        TAG,
                        "服务消失：${service.serviceName}（端口 ${service.port}）→ " +
                            "pair=${merged.pairPort} connect=${merged.connectPort}"
                    )
                    onUpdate(merged)
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AgentLogger.w(TAG, "启动发现失败（$serviceType, code=$errorCode）")
                    onError("系统不允许 mDNS 发现（code=$errorCode）")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            synchronized(stateLock) { listeners += listener }
            runCatching {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                AgentLogger.w(TAG, "discoverServices 抛异常：${it.message}")
                onError("系统不允许 mDNS 发现：${it.message}")
            }
        }

        /** 串行解析：NsdManager 同时只允许一个 resolve 在进行。 */
        private fun drain() {
            if (stopped) return
            val next = synchronized(stateLock) {
                if (resolving) return
                pending.pollFirst()?.also { resolving = true }
            } ?: return
            val (type, info) = next
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(stateLock) { resolving = false }
                        AgentLogger.w(TAG, "解析失败（${serviceInfo.serviceName}, code=$errorCode）")
                        drain()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        synchronized(stateLock) { resolving = false }
                        record(type, serviceInfo)
                        drain()
                    }
                })
            }.onFailure {
                synchronized(stateLock) { resolving = false }
                AgentLogger.w(TAG, "resolveService 抛异常：${it.message}")
                drain()
            }
        }

        private fun record(type: String, info: NsdServiceInfo) {
            if (stopped) return
            // 系统偶发解析不出 host（返回 null），退回本机 IPv4——同机广播，地址等价
            val host = hostOf(info) ?: localIpv4(context)
            val service = DiscoveredAdbService(host, info.port)
            val merged = synchronized(stateLock) {
                if (stopped) return
                if (type == SERVICE_PAIRING) pairing += service else connect += service
                byName[info.serviceName] = type to service
                mergeDiscoveredAdbServices(pairing, connect)
            }
            AgentLogger.i(
                TAG,
                "发现 $type → ${service.host}:${service.port}；" +
                    "合并 host=${merged.host} pair=${merged.pairPort} connect=${merged.connectPort}"
            )
            onUpdate(merged)
        }
    }

    /**
     * 开始发现。返回 null 表示设备不支持 mDNS（调用方走手动填写）。
     *
     * 回调可能在主线程外的 binder 线程触发——UI 侧需自行切回主线程
     * （Compose 的 `mutableStateOf` 写入要在主线程）。
     */
    fun start(
        context: Context,
        onUpdate: (DiscoveredAdbEndpoint) -> Unit,
        onError: (String) -> Unit
    ): Session? {
        val app = context.applicationContext
        val nsd = runCatching {
            app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()
        if (nsd == null) {
            onError("设备不支持 mDNS 发现，请手动填写端口")
            return null
        }
        return Session(app, nsd, onUpdate, onError).also { it.begin() }
    }

    /** 本机当前链路的 IPv4（mDNS 解析不出 host 时的兜底）。 */
    fun localIpv4(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") }
    }.getOrNull()

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<java.net.InetAddress> = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.toList()
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        val v4 = addresses.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { !it.startsWith("127.") && !it.startsWith("169.254.") }
        if (v4.isNotEmpty()) return v4.first()
        return addresses.mapNotNull { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") }
    }
}
