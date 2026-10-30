package com.example.agentkit.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 能力契约：AgentKit 运行层的「可插拔功能」统一形状。
 *
 * ## 为什么需要这个
 * 运行层的目标是「小核心 + 按需加载」：AgentLoop / ToolRegistry / 会话存储常驻，
 * 其余重能力（本地模型、终端容器、无障碍、浏览器、语音、工作流……）各自成为
 * [AgentCapability]，用到才启动，不用就完全不占资源。以后加功能 = 加一个
 * [AgentCapability] 实现 + 注册一行，核心和别的模块互不知晓、互不拖累。
 *
 * ## 设计约束（故意保持极简）
 * 1. **只有生命周期，没有业务**：本契约不涉及任何具体能力语义；业务接口由各能力
 *    自己另行暴露（如本地模型继续走 LLMClient 契约）。运行层只管「开、关、状态」。
 * 2. **幂等**：[start] / [stop] 重复调用必须安全（已启动再 start 是 no-op）。
 * 3. **无 Android UI 依赖**：能力实现可以碰 Context / 系统服务，但契约本身保持纯
 *    Kotlin，QA harness 可直接断言。
 *
 * @see CapabilityHost 统一的注册与按需启动入口。
 */
interface AgentCapability {

    /** 全局唯一标识，如 "local-llm" / "terminal" / "browser"。注册表以此为准。 */
    val id: String

    /** 展示名（UI 与日志用），如 "本地模型"。 */
    val displayName: String

    /** 依赖的其他能力 id。按需启动时会先递归启动依赖。 */
    val dependsOn: List<String> get() = emptyList()

    /**
     * 是否必须跑在独立进程。进程隔离由各能力自行落地（Service / 独立 native 进程），
     * 本字段是声明式的：运行层据此决定崩溃时如何降级（重启该能力而非整个 App）。
     */
    val requiresIsolatedProcess: Boolean get() = false

    /** 当前状态。实现方负责在生命周期变化时更新。 */
    val state: StateFlow<CapabilityState>

    /**
     * 启动能力。约定：**只做必要的准备**（建目录、连服务、起协程），
     * 不做重活——重活（如加载模型）延迟到能力真正被用到的那一刻。
     *
     * 正常路径必须在返回前把 [state] 置为 [CapabilityState.READY]；抛异常则由
     * [CapabilityHost] 记录并转成 [CapabilityStartException] 抛给调用方，
     * 实现方不必自己吞异常。
     */
    suspend fun start()

    /** 停止能力并释放资源。幂等；停止后再次 [start] 必须可用。 */
    suspend fun stop()

    /**
     * 使用心跳：能力被业务触达时调用（发起一次推理、打开一次终端……）。
     * 空闲策略据此判断「多久没人用了」。默认空实现，无空闲需求的能力不用管。
     */
    fun onUse() {}
}

/** 能力生命周期状态。 */
enum class CapabilityState {
    /** 已注册，尚未启动。 */
    REGISTERED,

    /** 启动中（[AgentCapability.start] 执行期间）。 */
    STARTING,

    /** 就绪，随时可被业务使用。 */
    READY,

    /** 已停止（含被空闲策略释放）。再次使用前需重新 [AgentCapability.start]。 */
    STOPPED,

    /** 启动失败或运行中发生不可恢复错误。 */
    FAILED
}

/**
 * 能力启动失败。[cause] 是实现方抛出的原始异常，保留原样供上层记录堆栈。
 */
class CapabilityStartException(
    val capabilityId: String,
    cause: Throwable,
) : IllegalStateException("能力启动失败：$capabilityId（${cause.javaClass.simpleName}: ${cause.message}）", cause)

/**
 * 能力注册表：运行层的「插线板」。
 *
 * 命名刻意不叫 `CapabilityRegistry`——`com.example.agentkit.skill.CapabilityRegistry`
 * 已经存在（工具权限分级的 fail-closed 真相源），两者语义完全不同（一个是「工具
 * 有多危险」，一个是「功能何时启动」），同名会让 grep 与自动导入互相误伤。
 *
 * - [register]：能力声明自己（Application 启动时一行一个，不触发任何初始化）。
 * - [startIfNeeded]：业务第一次用到某能力时按需启动，依赖自动递归拉起。
 * - [stopAll]：进程退出 / 全局停止时统一收尾。
 *
 * 单例 object 足够：能力数量以个位数计，注册发生在冷启动，无并发压力；
 * 用挂起函数而不是锁，是为了让 [startIfNeeded] 里的递归启动安全。
 */
object CapabilityHost {

    private val capabilities = LinkedHashMap<String, AgentCapability>()

    /**
     * 等待一个「正在启动」的能力就绪的上限。
     *
     * 没有这个上限，一个 start 抛异常却把状态留在 STARTING 的能力，会让后来所有
     * 调用永久挂住（2026-10-30 审查发现：原实现是无超时 while 轮询）。15 秒对
     * 「建目录 / 连服务 / 起协程」这类轻准备已经非常宽裕。
     */
    private const val START_WAIT_TIMEOUT_MS = 15_000L

    /** 轮询粒度。能力 start 都很短，30ms 足够，比加锁简单且无死锁面。 */
    private const val START_POLL_MS = 30L

    fun register(capability: AgentCapability) {
        require(capabilities.none { it.key == capability.id }) {
            "能力 id 重复注册：${capability.id}"
        }
        capabilities[capability.id] = capability
    }

    fun get(id: String): AgentCapability? = capabilities[id]

    fun all(): List<AgentCapability> = capabilities.values.toList()

    /**
     * 按需启动：能力未就绪则先递归启动依赖，再启动自己。已就绪直接返回（幂等）。
     *
     * 三种失败出口，全部有明确类型，调用方可以据此降级（如回退云端模型）：
     * - 未注册 → [IllegalStateException]
     * - start 抛异常 → [CapabilityStartException]（原始异常在 cause 里）
     * - start 返回但状态仍不是 READY/STARTING，或 STARTING 超过 15 秒 → [CapabilityStartException]
     */
    suspend fun startIfNeeded(id: String): AgentCapability {
        val capability = capabilities[id]
            ?: throw IllegalStateException("能力未注册：$id")
        when (capability.state.value) {
            CapabilityState.READY -> return capability
            CapabilityState.STARTING -> {
                capability.awaitReady()
                return capability
            }
            else -> Unit
        }
        for (dep in capability.dependsOn) startIfNeeded(dep)
        try {
            capability.start()
        } catch (t: Throwable) {
            // 不吞异常，也不替实现方改状态（状态只有实现方自己写得了）：
            // 包装成有类型、能定位到能力 id 的异常抛出去。
            throw CapabilityStartException(id, t)
        }
        // start() 允许同步置 READY，也允许先置 STARTING 再异步就绪——两种都收，
        // 但如果返回后还是 REGISTERED/STOPPED，说明这个能力压根没打算启动。
        if (capability.state.value == CapabilityState.STARTING) {
            capability.awaitReady()
        } else if (capability.state.value != CapabilityState.READY) {
            // 返回了却没就绪：多半是 start() 忘了改状态。不能当成功，否则调用方
            // 会在一个「自称能用其实没准备」的能力上继续跑，问题延后到更难查的地方。
            throw CapabilityStartException(
                id,
                IllegalStateException("start() 返回了，状态却是 ${capability.state.value}——start 必须把状态置为 READY"),
            )
        }
        return capability
    }

    suspend fun stopAll() {
        for (capability in capabilities.values.reversed()) {
            runCatching { capability.stop() }
        }
    }

    /** 等到 READY；超时或落到 FAILED/STOPPED 都按启动失败处理。 */
    private suspend fun AgentCapability.awaitReady() {
        val finished = withTimeoutOrNull(START_WAIT_TIMEOUT_MS) {
            while (state.value == CapabilityState.STARTING) delay(START_POLL_MS)
            true
        }
        if (finished == null) {
            throw CapabilityStartException(
                id,
                IllegalStateException("启动超时（>${START_WAIT_TIMEOUT_MS / 1000} 秒仍停在 STARTING）"),
            )
        }
        when (val s = state.value) {
            CapabilityState.READY -> Unit
            else -> throw CapabilityStartException(id, IllegalStateException("启动结束时状态是 $s"))
        }
    }
}
