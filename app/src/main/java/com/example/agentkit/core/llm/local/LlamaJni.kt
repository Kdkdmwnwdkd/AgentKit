package com.example.agentkit.core.llm.local

import android.util.Log

/**
 * llama.cpp JNI 桥的 Kotlin 侧声明。
 *
 * 对应 app/src/main/cpp/llama-jni.cpp 的五个 native 函数（净室重写，不套任何
 * 第三方封装）。协议细节（消息→模板→工具提示→采样）都在 [LocalLlamaClient] 编排，
 * 这里只做「句柄 + 五个原语」。
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

    /** 首次调用时加载 .so；失败（如在 x86 模拟器上）只记一次日志，后续直接返回 false。 */
    fun ensureLoaded(): Boolean {
        if (available) return true
        if (loadAttempted) return false
        loadAttempted = true
        available = try {
            System.loadLibrary("agentkit-llama")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libagentkit-llama.so 不可用（当前 ABI 未编入？）: ${e.message}")
            false
        }
        return available
    }

    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeChatTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, listener: TokenListener?): String
}
