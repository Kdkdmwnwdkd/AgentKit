package com.beichen.polaris.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * 本地模型的生命周期状态机（R3 收缩后）。
 *
 * ## 为什么从「通用能力契约」收缩成「本地模型专用」
 * 原先是 `AgentCapability`（5 方法）+ `CapabilityHost`（注册表），意图是
 * 「所有可插拔功能都实现同一套生命周期」。实际结局：**1 个实现、0 个消费**——
 * `CapabilityHost.startIfNeeded` / `get` / `all` / `stopAll` 全仓无调用点，
 * 只有 `register` 被 `LocalLlmRuntime` 调了一次。
 *
 * 这不是「抽象层次不够高」，是**抽象错了对象**：
 * 浏览器、技能、记忆、工作流都没有「重资源常驻 + 需要空闲回收」的问题，
 * 为它们准备一套 STARTING/READY/FAILED 状态机，只会多出永远走不到的分支。
 *
 * 现在：
 * - 通用能力框架 → 删除（[CapabilityHost] 整体移除）；
 * - 生命周期状态机 → **只留给本地模型**（它真的有 STARTING/FAILED：
 *   加载要十几秒、失败要从云端回退）；
 * - 其他三类重资源 → 走 [com.beichen.polaris.runtime.lease.RuntimeLease] 租约
 *   （引用计数 + 空闲回收），不需要状态机。
 *
 * 判据（写死，防止范围再次膨胀）：
 * > 需要「启动中」这个中间态的能力，才配拥有状态机。
 */
enum class CapabilityState {
    /** 已注册，尚未启动。 */
    REGISTERED,

    /** 启动中（加载模型 / 建目录 / 连服务）。 */
    STARTING,

    /** 就绪，随时可被业务使用。 */
    READY,

    /** 已停止（含被空闲策略释放）。再次使用前需重新启动。 */
    STOPPED,

    /** 启动失败或运行中发生不可恢复错误。 */
    FAILED
}

/**
 * 本地模型的能力契约（R3 收缩后）。
 *
 * **只有一个实现**（[LocalLlmRuntime]），保留接口是为了让「本地模型」与
 * 未来的其它重资源在**类型上**可区分，而不是为了将来可能存在的多个实现。
 * 若将来确有第二个实现，届时再抽共同部分。
 *
 * 设计约束（沿用原契约，仍然有效）：
 * 1. **只有生命周期，没有业务**：不涉及任何模型语义；
 * 2. **幂等**：[start] / [stop] 重复调用安全；
 * 3. **无 Android UI 依赖**：契约保持纯 Kotlin，QA harness 可直接断言。
 */
interface AgentCapability {

    /** 全局唯一标识，如 "local-llm"。 */
    val id: String

    /** 展示名（UI 与日志用），如 "本地模型"。 */
    val displayName: String

    /** 当前状态。实现方负责在生命周期变化时更新。 */
    val state: StateFlow<CapabilityState>

    /**
     * 启动能力。约定：**只做必要的准备**（建目录、连服务、起协程），
     * 不做重活——重活（如加载模型）延迟到能力真正被用到的那一刻。
     *
     * 正常路径必须在返回前把 [state] 置为 [CapabilityState.READY]；
     * 实现方自己吞异常并置 [CapabilityState.FAILED]（不再由注册表代劳）。
     */
    suspend fun start()

    /** 停止能力并释放资源。幂等；停止后再次 [start] 必须可用。 */
    suspend fun stop()
}
