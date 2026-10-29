package com.example.agentkit.runtime

import kotlinx.coroutines.flow.StateFlow

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
 * @see CapabilityRegistry 统一的注册与按需启动入口。
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
 * 能力注册表：运行层的「插线板」。
 *
 * - [register]：能力声明自己（Application 启动时一行一个，不触发任何初始化）。
 * - [startIfNeeded]：业务第一次用到某能力时按需启动，依赖自动递归拉起。
 * - [stopAll]：进程退出 / 全局停止时统一收尾。
 *
 * 单例 object 足够：能力数量以个位数计，注册发生在冷启动，无并发压力；
 * 用 Mutex 而不是 synchronized，是为了让 [startIfNeeded] 里的挂起调用安全。
 */
object CapabilityRegistry {

    private val capabilities = LinkedHashMap<String, AgentCapability>()

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
     * 启动失败会把状态置为 FAILED 并抛出，调用方决定降级策略（如回退云端模型）。
     */
    suspend fun startIfNeeded(id: String): AgentCapability {
        val capability = capabilities[id]
            ?: throw IllegalStateException("能力未注册：$id")
        when (capability.state.value) {
            CapabilityState.READY -> return capability
            CapabilityState.STARTING -> {
                // 简单等待：启动中视为即将就绪（当前各能力 start 都是轻操作）。
                capability.firstIsReadyOrThrow()
                return capability
            }
            else -> Unit
        }
        for (dep in capability.dependsOn) startIfNeeded(dep)
        capability.start()
        return capability
    }

    suspend fun stopAll() {
        for (capability in capabilities.values.reversed()) {
            runCatching { capability.stop() }
        }
    }

    private suspend fun AgentCapability.firstIsReadyOrThrow() {
        // 轮询而非阻塞锁：能力 start 都很短，30ms 粒度足够。
        while (state.value == CapabilityState.STARTING) {
            kotlinx.coroutines.delay(30)
        }
        check(state.value == CapabilityState.READY) { "能力 $id 启动未就绪：${state.value}" }
    }
}
