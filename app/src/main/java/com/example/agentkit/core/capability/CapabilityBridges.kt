package com.example.agentkit.core.capability

import com.example.agentkit.core.terminal.backend.TerminalBackend
import com.example.agentkit.core.terminal.backend.TerminalBackendFactory
import com.example.agentkit.core.terminal.backend.TerminalIdentity

/*
 * Phase 2 · DoD⑤：把已有骨架的可用性判断**接入统一能力链**（不重复造轮子）。
 *
 *  - 「执行 shell 命令」的候选来自 T07 的 [TerminalBackendFactory]：复用其 `providersFor`
 *    给出的 proot → user 顺序，以及带缓存 + `clearCache` 的 `isAvailable` 探测，
 *    **不重新实现** proot/rootfs 的判定；
 *  - 「操作手机界面」的候选来自无障碍状态（[accessibilityCapabilitySnapshot]，纯函数入参 =
 *    「服务是否连接」+「自动化总开关是否开启」），Android 侧读取 `AutomationBridge` 的适配
 *    见 automation/CapabilityAndroidBridge.kt。
 *
 * 本文件只做「骨架状态 → [BackendAvailability] 快照」的**纯映射**，零 Android 依赖，
 * 可在 JVM harness 直接断言。
 */

/** 无障碍能力候选的后端键（与能力链其它候选区分）。 */
const val BACKEND_ACCESSIBILITY = "accessibility"

/* ------------------------------------------------------------------ */
/* shell 能力：调试通道(Shizuku) → proot 容器 → 本机 user shell        */
/* ------------------------------------------------------------------ */

/**
 * shell 后端键 → 展示名（纯函数）。
 */
fun shellBackendLabel(backendKey: String): String = when (backendKey) {
    TerminalBackend.KEY_SHIZUKU -> "调试通道（Shizuku）"
    TerminalBackend.KEY_PROOT -> "proot 容器"
    TerminalBackend.KEY_USER -> "本机 user shell"
    else -> backendKey
}

/**
 * shell 后端键 → 不可用原因（纯函数，面向诊断）。
 */
fun shellBackendReason(backendKey: String): String = when (backendKey) {
    TerminalBackend.KEY_SHIZUKU -> "调试通道未就绪（Shizuku 未运行或未授权本应用）"
    TerminalBackend.KEY_PROOT -> "proot 容器不可用（未安装 rootfs 或缺少 proot 组件）"
    TerminalBackend.KEY_USER -> "本机 shell（/system/bin/sh）不可执行"
    else -> "后端「$backendKey」不可用"
}

/**
 * shell 后端键 → 不可用时的**可操作提示**（纯函数，具体到下一步）。
 */
fun shellBackendHint(backendKey: String): String = when (backendKey) {
    TerminalBackend.KEY_SHIZUKU -> "到「权限中心 → 调试通道」按引导完成一次授权：装好 Shizuku 并在其中「通过无线调试启动」，再回来点「去授权」。"
    TerminalBackend.KEY_PROOT -> "可在「容器」页一键安装并解压 rootfs 后重试；若只想跑普通命令，本机 shell 已足够。"
    TerminalBackend.KEY_USER -> "确认设备可执行本机 shell（一般无需处理），可重启应用后重试。"
    else -> "确认「$backendKey」所需的依赖已就绪后重试。"
}

/**
 * 一条 shell 候选的可用性快照（纯函数）：可用 → 仅带展示名；不可用 → 附原因 + 可操作提示。
 */
fun shellBackendAvailability(backendKey: String, available: Boolean): BackendAvailability =
    if (available) {
        BackendAvailability(backendKey, shellBackendLabel(backendKey), available = true)
    } else {
        BackendAvailability(
            backendId = backendKey,
            label = shellBackendLabel(backendKey),
            available = false,
            reason = shellBackendReason(backendKey),
            hint = shellBackendHint(backendKey)
        )
    }

/**
 * 从 T07 工厂取「执行 shell 命令」的候选可用性快照（proot → user，按优先级）。
 *
 * **复用既有判断**：候选顺序来自 [TerminalBackendFactory.providersFor]，可用性来自带缓存的
 * [TerminalBackendFactory.isAvailable]（proot 探测要碰文件系统，缓存判定；权限/可用性变化后
 * 由调用方 `clearCache()` 让其重新探测）。本函数**不重复实现**任何探测。
 */
fun TerminalBackendFactory.shellCapabilitySnapshot(
    shizukuAvailable: Boolean = false
): List<BackendAvailability> = buildList {
    // W-13：调试通道（uid 2000）能做的活最多（改系统设置/停应用/读文件），排在最前；
    // 不可用时仅作为「诊断条目」呈现，不影响后续 proot/user 的降级链。
    add(shellBackendAvailability(TerminalBackend.KEY_SHIZUKU, shizukuAvailable))
    addAll(
        providersFor(TerminalIdentity.User, preferProot = true).map { provider ->
            shellBackendAvailability(provider.backendKey, isAvailable(provider))
        }
    )
}

/* ------------------------------------------------------------------ */
/* 无障碍能力：操作手机界面                                             */
/* ------------------------------------------------------------------ */

/**
 * 无障碍候选的可用性快照（纯函数）。
 *
 * 入参拆成两个子状态（**不与 `AutomationBridge.isReady()` 的单布尔混淆**），因为两种缺失的
 * 用户动作**不同**（一个去系统设置开无障碍、一个去 App 设置开总开关），文案必须分别给出：
 *  1. 服务未连接 → 引导「到系统设置开启无障碍服务」；
 *  2. 总开关关闭 → 引导「在设置页打开自动化总开关」；
 *  3. 两者都缺 → 合并两条动作，按先系统后 App 的顺序。
 *
 * @param serviceConnected 无障碍服务是否已连接（`AutomationBridge.service != null`）
 * @param masterEnabled 自动化总开关是否开启（`AutomationBridge.enabled`）
 */
fun accessibilityBackendAvailability(
    serviceConnected: Boolean,
    masterEnabled: Boolean
): BackendAvailability {
    if (serviceConnected && masterEnabled) {
        return BackendAvailability(BACKEND_ACCESSIBILITY, "无障碍服务", available = true)
    }
    val missing = buildList {
        if (!serviceConnected) add("无障碍服务未连接")
        if (!masterEnabled) add("自动化总开关未开启")
    }.joinToString("，且")
    val actions = buildList {
        if (!serviceConnected) add("到系统「设置 → 无障碍」开启 AgentKit 的无障碍服务")
        if (!masterEnabled) add("到 AgentKit「设置 → 自动化」打开总开关")
    }.joinToString("，再")
    return BackendAvailability(
        backendId = BACKEND_ACCESSIBILITY,
        label = "无障碍服务",
        available = false,
        reason = missing,
        hint = "$actions，然后重试。"
    )
}

/**
 * 「操作手机界面」的候选可用性快照（纯函数）：**无障碍服务 → 调试通道（Shizuku）**。
 *
 * W-50 之前这里只有无障碍一条候选，注释还写着「无更低一级可用路径」。但那条判断不成立：
 * shell（uid 2000）能做 `input tap/swipe/text/keyevent`、`uiautomator dump`、`screencap`，
 * 足以覆盖界面操作的大头。于是「只开无线调试、没开无障碍」的用户被整条链路挡住，
 * 明明有可用通道却被告知「全链不可用」。
 *
 * @param serviceConnected 无障碍服务是否已连接
 * @param masterEnabled 自动化总开关是否开启
 * @param shizukuAvailable 调试通道是否就绪（Shizuku 已运行且已授权本应用）
 */
fun controlScreenSnapshot(
    serviceConnected: Boolean,
    masterEnabled: Boolean,
    shizukuAvailable: Boolean
): List<BackendAvailability> = listOf(
    // 首选：实时、无需 dump、能拿界面变化事件
    accessibilityBackendAvailability(serviceConnected, masterEnabled),
    // 兜底：读树靠 uiautomator dump（慢 1~2 秒），其余动作与无障碍等价
    shellBackendAvailability(TerminalBackend.KEY_SHIZUKU, shizukuAvailable)
)

/**
 * 旧的单候选快照（仅无障碍）。
 *
 * @Deprecated 保留是为了不破坏既有 harness 断言；新调用点请用 [controlScreenSnapshot]。
 */
@Deprecated("改用 controlScreenSnapshot(...)，它包含调试通道兜底")
fun accessibilityCapabilitySnapshot(serviceConnected: Boolean, masterEnabled: Boolean): List<BackendAvailability> =
    listOf(accessibilityBackendAvailability(serviceConnected, masterEnabled))
