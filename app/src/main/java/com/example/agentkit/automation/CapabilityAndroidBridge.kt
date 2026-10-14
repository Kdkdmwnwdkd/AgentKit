package com.example.agentkit.automation

import com.example.agentkit.core.capability.BackendAvailability
import com.example.agentkit.core.capability.accessibilityCapabilitySnapshot

/*
 * Phase 2 · DoD⑤：无障碍能力的 Android 侧适配（读 [AutomationBridge] → 纯快照）。
 *
 * 把「无障碍服务是否连接 + 自动化总开关」两个子状态（即 [AutomationBridge.isReady] 拆开后的
 * 两块）交给 [accessibilityCapabilitySnapshot] 这一**纯函数**转换成统一能力链快照，
 * 使核心能力链保持零 Android 依赖（可在 JVM harness 直接验证两种缺失的文案差异）。
 */

/**
 * 「操作手机界面」能力的可用性快照（Android 侧入口）：读取当前 [AutomationBridge] 状态。
 *
 * 与 `AutomationBridge.isReady()` 同源，但保留两个子状态以便能力链给出**分别对应**的引导
 * （服务未连接 → 去系统设置；总开关关闭 → 去 App 设置）。
 */
fun screenCapabilitySnapshot(): List<BackendAvailability> =
    accessibilityCapabilitySnapshot(
        serviceConnected = AutomationBridge.service != null,
        masterEnabled = AutomationBridge.enabled
    )
