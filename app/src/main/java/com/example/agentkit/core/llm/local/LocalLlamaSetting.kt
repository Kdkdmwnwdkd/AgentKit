package com.example.agentkit.core.llm.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地推理参数持久化（SharedPreferences）。
 *
 * 与 [com.example.agentkit.core.ConfigStore] 平级：ModelConfig 管「云端供应商三元组」，
 * 这里管「本地 GGUF 模型与推理参数」。两套配置互不污染——
 * providerId 决定用哪套，local-llama 时只看这里。
 *
 * 线程数默认取 min(4, 可用核心)：手机大核通常 4 个以内，再多收益递减还抢主线程。
 */
class LocalLlamaSetting(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前选中的 GGUF 文件名（位于 files/models/ 下）；空串 = 未选择。 */
    var activeModel: String
        get() = sp.getString(KEY_ACTIVE_MODEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_ACTIVE_MODEL, value).apply()

    /** 上下文窗口（token 数）。越大越吃内存，2048~8192 覆盖手机端常见场景。 */
    var nCtx: Int
        get() = sp.getInt(KEY_N_CTX, DEFAULT_N_CTX)
        set(value) = sp.edit().putInt(KEY_N_CTX, value.coerceIn(512, 16384)).apply()

    /** 推理线程数。 */
    var nThreads: Int
        get() = sp.getInt(KEY_N_THREADS, defaultThreads())
        set(value) = sp.edit().putInt(KEY_N_THREADS, value.coerceIn(1, 8)).apply()

    /** 单次生成上限（token）。 */
    var maxTokens: Int
        get() = sp.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = sp.edit().putInt(KEY_MAX_TOKENS, value.coerceIn(64, 8192)).apply()

    /**
     * 默认线程数 = 最高频簇（大核）的核心数，clamp 到 2..6。
     *
     * 手机 SoC 是大小核异构：开满 8 线程时每一步都要等最慢的小核，实际比 4 线程
     * 更慢更烫；llama.cpp 官方建议也是「物理大核数」。读不到 cpufreq 时退回
     * 「可用核心数上限 4」的保守值。
     */
    private fun defaultThreads(): Int {
        val bigCores = runCatching {
            val cpuDirs = java.io.File("/sys/devices/system/cpu")
                .listFiles { f -> f.isDirectory && Regex("cpu\\d+").matches(f.name) }
                ?: return@runCatching 0
            val freqs = cpuDirs.mapNotNull { dir ->
                runCatching {
                    java.io.File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull()
            }
            if (freqs.isEmpty()) return@runCatching 0
            val top = freqs.maxOrNull() ?: return@runCatching 0
            freqs.count { it >= top * 9 / 10 }   // 同簇频率相近，±10% 视为同一簇
        }.getOrDefault(0)
        if (bigCores in 2..8) return bigCores.coerceAtMost(6)
        return Runtime.getRuntime().availableProcessors().coerceAtMost(4).coerceAtLeast(2)
    }

    companion object {
        private const val PREFS_NAME = "agentkit_local_llama"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_N_CTX = "n_ctx"
        private const val KEY_N_THREADS = "n_threads"
        private const val KEY_MAX_TOKENS = "max_tokens"
        const val DEFAULT_N_CTX = 4096
        const val DEFAULT_MAX_TOKENS = 2048

        /** 模型文件统一存放目录（App 私有空间，卸载 App 即清理）。 */
        fun modelsDir(context: Context): java.io.File =
            java.io.File(context.filesDir, "models").apply { mkdirs() }
    }
}
