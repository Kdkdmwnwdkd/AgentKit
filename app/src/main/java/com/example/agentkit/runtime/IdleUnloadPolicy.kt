package com.example.agentkit.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 可被空闲释放的资源：实现方回答两个问题——「现在能安全释放吗」和「怎么释放」。
 *
 * 「能安全释放」必须由实现方自己保证：**有在途任务时返回 false**。运行层只做
 * 节拍器和决策，不了解、也不该了解资源的内部并发结构。
 */
interface IdleReleasable {

    /** 当前是否处于可安全释放的状态（无在途任务、无未落盘数据……）。 */
    val isSafeToRelease: Boolean

    /** 执行释放。实现方需自证幂等；抛异常只会被记录，不会重试。 */
    fun releaseNow(reason: String)
}

/**
 * 空闲释放策略：通用节拍器，解决「重资源无人使用时仍占着内存 / 电池」的问题。
 *
 * ## 工作方式
 * 业务每次用到资源时调 [touch]（刷新心跳）；[start] 后台协程周期性检查：
 * 距上次心跳超过 [idleTimeoutMs] 且资源自报「可安全释放」→ 调 [IdleReleasable.releaseNow]，
 * 释放原因写入事件流（[events]，供 UI / 日志消费）。释放后业务下次使用会自然重建资源
 * （对本地模型而言就是下一轮推理时重新 acquire），除首 token 延迟外用户无感。
 *
 * ## 与引擎并发保护的关系
 * 本类**不负责**并发安全——即使误判，底层引擎的「延迟回收」机制（有使用者时句柄
 * 延迟 free）也兜得住；这里的 isSafeToRelease 检查是第一道、也是最省事的闸门：
 * 绝大多数情况下让节拍器绕开在途任务，避免无谓的「卸载-重载」抖动。
 *
 * @param name 资源名（日志与事件里用），如 "local-llm"。
 */
class IdleUnloadPolicy(
    private val name: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** 空闲判定阈值。默认 5 分钟；0 = 永不释放（等效停用）。 */
    @Volatile
    var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS

    /** 检查间隔。间隔越短释放越准时，但空转越多；30s 对分钟级超时足够。 */
    @Volatile
    var checkIntervalMs: Long = 30_000L

    /** 释放事件（含原因），订阅方：日志页 / 状态卡。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    @Volatile
    private var lastUseMs: Long = System.currentTimeMillis()

    private var watchJob: Job? = null

    /** 心跳：业务每次触达资源时调用。 */
    fun touch() {
        lastUseMs = System.currentTimeMillis()
    }

    /** 启动节拍器。重复调用是 no-op（以最近一次为准）。 */
    fun start(releasable: IdleReleasable) {
        if (watchJob?.isActive == true) return
        touch()
        watchJob = scope.launch {
            while (isActive) {
                delay(checkIntervalMs)
                runCatching { maybeRelease(releasable) }
            }
        }
    }

    /** 停止节拍器（不主动释放资源——是否释放由调用方语义决定）。 */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    private fun maybeRelease(releasable: IdleReleasable) {
        val timeout = idleTimeoutMs
        if (timeout <= 0L) return
        val idleFor = System.currentTimeMillis() - lastUseMs
        if (idleFor < timeout) return
        if (!releasable.isSafeToRelease) return
        val reason = "空闲 ${idleFor / 60_000L} 分钟无人使用"
        releasable.releaseNow(reason)
        _events.tryEmit("[$name] 已按空闲策略释放（$reason）")
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 5 * 60_000L
    }
}
