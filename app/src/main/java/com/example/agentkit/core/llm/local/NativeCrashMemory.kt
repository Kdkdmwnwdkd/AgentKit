package com.example.agentkit.core.llm.local

import android.content.Context
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.CrashLogger
import java.io.File

/**
 * 原生崩溃记忆：读上次的崩溃现场，决定这次还要不要开 GPU。
 *
 * ## 为什么要「下次启动才处理」
 * 崩溃已经发生了，当场什么也做不了（进程正在死）。能做的只有两件事：
 *  1. 把现场留下来，让下一次启动知道「上次是开 GPU 崩的」；
 *  2. 下次**自动关掉 GPU**，让人至少能先把对话用起来，而不是一开就崩、再开再崩。
 *
 * ## 为什么不问用户就直接关
 * 闪退对用户来说是「这个 App 坏了」，不是「一个选项需要权衡」。先退回能用的状态、
 * 再把原因写在设置页，比弹一个「是否继续使用 GPU？」的对话框负责——
 * 后者在最坏的循环里会变成每次启动都弹一次。
 *
 * 解析与判定在 [NativeCrashParser]（纯 JVM，可断言）；这里只做文件与配置的读写。
 */
object NativeCrashMemory {

    /** 启动时调用：读现场 → 必要时关 GPU → 删文件（只处理一次）。 */
    fun handleStartup(context: Context) {
        val crash = runCatching { read(context) }.getOrNull() ?: return
        // 先删：无论后面怎么处理，这份现场都只该被消费一次。
        // 留着的话，用户手动重开 GPU 后每次启动都会被「上次崩过」再关一次，永远开不回来。
        runCatching { crashFile(context).delete() }

        if (!NativeCrashParser.shouldDisableGpu(crash)) {
            AgentLogger.w(
                "LocalLlama",
                "上次运行被 ${crash.signalName} 终止（${crash.stageLabel}），当时是纯 CPU 运行——与 GPU 无关"
            )
            return
        }

        val note = NativeCrashParser.describe(crash)
        runCatching {
            val setting = LocalLlamaSetting(context)
            setting.gpuMode = GpuMode.OFF
            setting.lastGpuCrashNote = note
        }
        AgentLogger.e(
            "LocalLlama",
            "上次运行在「${crash.stageLabel}」阶段崩溃（${crash.signalName}，" +
                "GPU ${crash.gpuLayers} 层 / ctx ${crash.ctx} / ${crash.model}）——已自动关闭 GPU 加速"
        )
    }

    /** 崩溃文件位置（和 Java 崩溃日志同一个目录，便于一起清理）。 */
    fun crashFile(context: Context): File =
        File(File(context.filesDir, CrashLogger.DIR_NAME), NativeCrashParser.FILE_NAME)

    /** 读取并解析；文件不存在或内容认不出时返回 null（绝不影响启动）。 */
    fun read(context: Context): NativeCrash? {
        val f = crashFile(context)
        if (!f.exists()) return null
        return NativeCrashParser.parse(f.readText())
    }
}
