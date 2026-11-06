package com.example.agentkit.startup

import android.app.Application
import com.example.agentkit.automation.adb.AdbSession
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.AppScope
import com.example.agentkit.core.CrashLogger
import com.example.agentkit.core.llm.local.AndroidDeviceProbe
import com.example.agentkit.core.llm.local.LlamaJni
import com.example.agentkit.core.llm.local.NativeCrashMemory
import com.example.agentkit.core.telemetry.TraceSink
import com.example.agentkit.runtime.LocalLlmRuntime
import com.example.agentkit.runtime.lease.LeaseRegistry
import java.io.File

/**
 * 启动顺序（R-15）。
 *
 * ## 为什么需要这个文件
 *
 * 原先这十步全部平铺在 `AgentKitApplication.onCreate` 里，顺序**只存在于语句次序**，
 * 唯一表达约束的地方是一行注释：「日志目录就绪——必须早于运行层初始化」。
 * 这行注释是真的：`AgentLogger.init` 曾经在 `ChatViewModel` 里，而运行层在
 * `Application.onCreate` 更早跑，那段时间的日志全部落不了盘，排障时等于黑的。
 *
 * 但注释拦不住重排。谁把 `AgentLogger.init` 挪到 `LocalLlmRuntime.init` 之后，
 * 编译通过、启动正常、harness 全绿——**只有下一次真机排障时才会发现日志缺了一段**。
 * 这类「顺序错了但不报错」的失效，必须靠结构而不是靠注释来守。
 *
 * ## 顺序为什么是现在这样
 *
 * | 步 | 必须在前面的理由 |
 * |---|---|
 * | `CRASH_LOGGER` | 它之后的任何崩溃都要有证据；自己出错没有第二道防线 |
 * | `LOGGER` | 从这一步起，后面每一步的失败都能落盘（否则失败也是静默的） |
 * | `NATIVE_CRASH_MEMORY` | 只依赖 `filesDir/crash`，与 logger 无关；但它可能要写日志 |
 * | `CRASH_REPORT` | 要读 `crash/` 并用 logger 留痕，所以在两者之后 |
 * | `SHIZUKU` / `ADB` | 只读 SharedPreferences，不依赖日志 |
 * | `TRACE` | 与 logger 同目录，必须在 `LOGGER` 之后 |
 * | `RUNTIME` | 启动即写日志，必须在 `LOGGER` 之后 |
 * | `LEASE` | 回收的是 runtime 注册的资源，必须在 `RUNTIME` 之后 |
 * | `DEVICE_PROBE` | 最后，且必须异步——它要起 EGL 上下文，同步会拖慢冷启动 |
 *
 * ## 失败语义
 *
 * 每一步独立 `runCatching`，**一步失败不影响后续**。这不是偷懒：本地模型跑不起来
 * 不该阻止终端可用，Shizuku 没装不该阻止聊天。启动期的正确姿态是「尽力而为 + 留痕」。
 */
object BootSequence {

    /**
     * 启动步骤。**枚举声明顺序就是执行顺序**（`entries` 保序）。
     *
     * 用枚举而不是字符串列表：`when` 对枚举是穷尽的，少写一个分支编译期就红；
     * 字符串拼错只会在运行时静默跳过。
     */
    enum class Step(val label: String) {
        CRASH_LOGGER("崩溃捕获"),
        LOGGER("日志目录"),
        NATIVE_CRASH_MEMORY("原生崩溃记忆"),
        CRASH_REPORT("未查看崩溃"),
        SHIZUKU("Shizuku 通道"),
        ADB("无线调试凭据"),
        TRACE("机器可读流水"),
        RUNTIME("运行层"),
        LEASE("租约回收节拍"),
        DEVICE_PROBE("设备画像"),
    }

    /** 供日志页/排障显示，也是 harness 断言的对照串。 */
    fun describe(): String =
        Step.entries.joinToString(" → ") { it.name.lowercase().replace('_', '-') }

    fun run(app: Application) {
        for (step in Step.entries) {
            val error = runCatching { execute(step, app) }.exceptionOrNull()
            if (error != null) {
                // LOGGER 之前失败时这一步是 no-op（AgentLogger 未初始化即静默），
                // 但 CRASH_LOGGER 自己的 install 内部已经兜住了，实际走不到这里。
                runCatching {
                    AgentLogger.w("Boot", "步骤 ${step.label} 失败，已降级继续：${error.message}")
                }
            }
        }
    }

    private fun execute(step: Step, app: Application) {
        when (step) {
            Step.CRASH_LOGGER -> CrashLogger.install(app)

            Step.LOGGER -> {
                AgentLogger.init(File(app.filesDir, "logs"))
                AgentLogger.i("Boot", "启动顺序：${describe()}")
            }

            Step.NATIVE_CRASH_MEMORY -> {
                // 信号处理器要往里写文件，目录必须先存在（mkdirs 失败会让下面的赋值指向
                // 一个不存在的路径，但 NativeCrashMemory 内部全程 runCatching，不影响启动）。
                val crashDir = File(app.filesDir, CrashLogger.DIR_NAME)
                crashDir.mkdirs()
                LlamaJni.crashDir = crashDir.absolutePath
                NativeCrashMemory.handleStartup(app)
            }

            Step.CRASH_REPORT -> {
                val unseen = CrashLogger.unseenCrashRecords(app)
                if (unseen.isNotEmpty()) {
                    AgentLogger.w(
                        "CrashLogger",
                        "检测到 ${unseen.size} 次未查看崩溃：${unseen.first().fileName}" +
                            "（可在日志页「最近崩溃」查看）"
                    )
                }
            }

            Step.SHIZUKU -> ShizukuShell.init(app)

            Step.ADB -> AdbSession.init(app)

            Step.TRACE -> TraceSink.init(File(app.filesDir, "logs"))

            Step.RUNTIME -> LocalLlmRuntime.init(app)

            // 显式传 AppScope.app：这个节拍器活到进程结束，不该跟着某个实例走。
            Step.LEASE -> LeaseRegistry.start(AppScope.app)

            Step.DEVICE_PROBE -> Thread {
                // 这里的 runCatching 不是冗余——上面的 runCatching 在**当前线程**上，
                // 抓不到新线程里抛出的异常。少了这一层，probe 崩了就是一行
                // `Exception in thread "device-probe"`，日志里什么都没有。
                runCatching { AndroidDeviceProbe.profile(app) }
            }.apply { isDaemon = true; name = "device-probe" }.start()
        }
    }
}
