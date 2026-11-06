package com.example.agentkit.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * `AppScope` 的 JVM 桩。真身用 `android.util.Log` 做未捕获异常出口，沙箱里编不了。
 *
 * 桩保留**结构语义**（io 不含 Job、app 是进程级 scope）——终端池在 `closeAllAsync()`
 * 里依赖的正是这两点；少了 Log 只影响「异常打去哪」，不影响被测行为。
 */
object AppScope {
    val io: CoroutineContext = Dispatchers.IO
    val app: CoroutineScope = CoroutineScope(SupervisorJob() + io)
}
