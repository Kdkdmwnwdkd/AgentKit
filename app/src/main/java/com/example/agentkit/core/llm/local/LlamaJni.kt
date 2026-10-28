package com.example.agentkit.core.llm.local

import android.util.Log
import java.io.File

/**
 * llama.cpp JNI 桥的 Kotlin 侧声明。
 *
 * 对应 app/src/main/cpp/llama-jni.cpp 的 native 函数（净室重写，不套任何
 * 第三方封装）。协议细节（消息→模板→工具提示→采样）都在 [LocalLlamaClient] 编排，
 * 这里只做「句柄 + 几个原语」。
 *
 * 库名与 CMakeLists.txt 的 add_library(agentkit-llama SHARED ...) 一致。
 * 只在真机 arm64 上存在——模块缺失时 [ensureLoaded] 返回 false，UI 走降级文案。
 */
object LlamaJni {

    private const val TAG = "LlamaJni"

    /** 生成过程中的逐 token 回调（native 线程调用，实现方必须快速返回）。 */
    fun interface TokenListener {
        fun onToken(piece: String)
    }

    @Volatile
    private var loadAttempted = false

    @Volatile
    var available: Boolean = false
        private set

    /** 加载失败的用户可读原因（ABI 不符 / CPU 指令集不足）；成功时为 null。 */
    @Volatile
    var unavailableReason: String? = null
        private set

    /** 首次调用时加载 .so；失败（如在 x86 模拟器上）只记一次日志，后续直接返回 false。 */
    fun ensureLoaded(): Boolean {
        if (available) return true
        if (loadAttempted) return false
        loadAttempted = true
        missingCpuFeatures()?.let { reason ->
            Log.w(TAG, "不加载 libagentkit-llama.so：$reason")
            unavailableReason = reason
            return false
        }
        available = try {
            System.loadLibrary("agentkit-llama")
            true
        } catch (e: UnsatisfiedLinkError) {
            unavailableReason = "native 库不可用（当前 ABI 未编入？）"
            Log.w(TAG, "libagentkit-llama.so 不可用: ${e.message}")
            false
        }
        return available
    }

    /**
     * .so 按 `armv8.2-a+dotprod+fp16` 编译（见 CMakeLists.txt），在缺这些扩展的
     * 老 CPU 上加载即 SIGILL —— 必须在 dlopen 之前拦下来，否则是崩溃而不是降级。
     *
     * 读不到 /proc/cpuinfo 时返回 null（放行）：宁可让 dlopen 自己报错，
     * 也不要因为一个不可读的伪文件把能用的设备挡在外面。
     */
    private fun missingCpuFeatures(): String? {
        val features = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')?.trim()
                    ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet()
            }
        }.getOrNull()
        if (features.isNullOrEmpty()) return null
        val missing = buildList {
            if ("asimddp" !in features) add("dotprod")
            if ("asimdhp" !in features) add("fp16")
        }
        if (missing.isEmpty()) return null
        return "CPU 缺少 ${missing.joinToString("、")} 指令集，本地推理需 2018 年后机型（骁龙 845 及以上）"
    }

    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeChatTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, listener: TokenListener?): String

    /**
     * 原生层最近 80 条日志（tokenize 长度、prefill 复用/补算、decode 返回码、
     * 首个 token、空输出原因…）。
     *
     * 为什么要这个通道：这些线索原本只在 logcat 里，而用户在手机上排查时没有电脑。
     * 生成结束后无条件读一次写进「运行日志」，用户复制一段文本就能把现场带回来。
     */
    external fun nativeDiagnostics(): String
}
