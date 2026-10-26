package com.example.agentkit.automation

import com.example.agentkit.automation.adb.AdbSession
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.capability.BackendAvailability
import com.example.agentkit.core.capability.controlScreenSnapshot

/*
 * Phase 2 · DoD⑤：无障碍能力的 Android 侧适配（读 [AutomationBridge] → 纯快照）。
 *
 * 把「无障碍服务是否连接 + 自动化总开关」两个子状态（即 [AutomationBridge.isReady] 拆开后的
 * 两块）交给 [controlScreenSnapshot] 这一**纯函数**转换成统一能力链快照，
 * 使核心能力链保持零 Android 依赖（可在 JVM harness 直接验证两种缺失的文案差异）。
 *
 * W-50：候选链由「只有无障碍」扩成「无障碍 → 调试通道（Shizuku）」。后者复用
 * `ShizukuShell.status()` 判定，与 run_shell 的候选探测同源，不重复实现。
 *
 * W-51：再加一级「无线调试（ADB）」。判据是 `AdbSession.available()`（已配对即可，
 * 不在此处发网络包——否则每次读能力链都要等一次 TCP 超时）。
 */

/**
 * 「操作手机界面」能力的可用性快照（Android 侧入口）：读取当前 [AutomationBridge] 状态。
 *
 * 与 `AutomationBridge.isReady()` 同源，但保留两个子状态以便能力链给出**分别对应**的引导
 * （服务未连接 → 去系统设置；总开关关闭 → 去 App 设置）；调试通道单独作为第二级候选。
 */
fun screenCapabilitySnapshot(): List<BackendAvailability> =
    controlScreenSnapshot(
        serviceConnected = AutomationBridge.service != null,
        masterEnabled = AutomationBridge.enabled,
        shizukuAvailable = ShizukuShell.status() == ShizukuShell.Status.READY,
        adbAvailable = AdbSession.available()
    )
