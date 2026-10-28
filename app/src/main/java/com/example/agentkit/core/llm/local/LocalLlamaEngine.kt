package com.example.agentkit.core.llm.local

import com.example.agentkit.core.AgentLogger
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 llama 推理引擎（进程级单例）。
 *
 * 存在的原因：llama.cpp 加载模型是秒级操作（含 mmap 预热），而 ChatViewModel 的
 * provider 工厂每轮对话都会新建一个 client——若由 client 各自持句柄，等于每轮
 * 重新加载模型，体验不可接受。这里把「当前加载的实例」提到进程级，按
 * (模型路径 + 推理参数) 复用；参数变化或显式 [unload] 时才真正释放。
 *
 * 内存取舍：模型常驻是本地推理的常规做法（RikkaHub / llama.cpp server 同理），
 * 用户切换模型或点「卸载」才回收。真机 8GB+ 内存跑 1.5B~4B Q4 模型无压力。
 *
 * 线程安全：所有公开方法都在 [lock] 内完成状态变更；nativeGenerate 本身阻塞，
 * 由调用方自行放到 Dispatchers.IO。
 */
object LocalLlamaEngine {

    private const val TAG = "LocalLlama"

    private val lock = Any()

    /** 已加载实例的快照（句柄 + 加载参数）。句柄为 0 表示未加载。 */
    private data class Loaded(
        val handle: Long,
        val modelPath: String,
        val nCtx: Int,
        val nThreads: Int,
        val nGpuLayers: Int
    )

    private var current: Loaded? = null

    /** 单调递增的代际号：每次加载/卸载 +1，供 UI 判断「加载中的是不是最新请求」。 */
    private val generation = AtomicLong(0)

    /** 当前代际（加载成功后与 generation 对齐）。 */
    @Volatile
    var loadedGeneration: Long = -1L
        private set

    /** 已加载模型的展示名（文件名），未加载为 null。供 UI 状态卡展示。 */
    val loadedModelName: String?
        get() = synchronized(lock) { current?.let { File(it.modelPath).name } }

    val isLoaded: Boolean
        get() = synchronized(lock) { current != null }

    /**
     * 取（必要时加载）推理实例。
     *
     * @return native 句柄；0 表示加载失败（原因看 AgentLogger）
     */
    fun acquire(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long {
        if (!LlamaJni.ensureLoaded()) {
            AgentLogger.e(TAG, LlamaJni.unavailableReason ?: "native 库不可用，本地推理终止")
            return 0L
        }
        synchronized(lock) {
            val cur = current
            if (cur != null &&
                cur.modelPath == modelPath &&
                cur.nCtx == nCtx &&
                cur.nThreads == nThreads &&
                cur.nGpuLayers == nGpuLayers
            ) {
                AgentLogger.i(TAG, "复用已加载实例: ${File(modelPath).name} (ctx=$nCtx, threads=$nThreads)")
                return cur.handle
            }
            // 参数变了：释放旧实例再加载（模型文件级切换）
            releaseLocked()
        }
        return load(modelPath, nCtx, nThreads, nGpuLayers)
    }

    private fun load(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long {
        val file = File(modelPath)
        if (!file.isFile) {
            AgentLogger.e(TAG, "模型文件不存在: $modelPath")
            return 0L
        }
        val gen = generation.incrementAndGet()
        AgentLogger.i(
            TAG,
            "加载模型 ${file.name}（${file.length() / 1024 / 1024} MB, ctx=$nCtx, threads=$nThreads" +
                (if (nGpuLayers > 0) ", gpu=$nGpuLayers 层" else ", 纯 CPU") + "）…"
        )
        val startedAt = System.currentTimeMillis()
        val handle = LlamaJni.nativeInit(modelPath, nCtx, nThreads, nGpuLayers)
        if (handle == 0L) {
            AgentLogger.e(TAG, "nativeInit 失败（详见 logcat llama-jni）: ${file.name}")
            return 0L
        }
        if (nGpuLayers > 0) {
            // 请求了 GPU 就把「ggml 到底认出了哪些设备」记下来。
            // Vulkan 初始化失败是静默的：加载照样成功、只是全程跑在 CPU 上。
            // 没有这一行，用户报「开了 GPU 还是很慢」时我们只能猜。
            val probe = LlamaJni.gpuProbe()
            if (probe.isNotBlank()) AgentLogger.i(TAG, "后端设备：$probe")
        }
        synchronized(lock) {
            current = Loaded(handle, modelPath, nCtx, nThreads, nGpuLayers)
            loadedGeneration = gen
        }
        AgentLogger.i(
            TAG,
            "模型就绪: ${file.name}，耗时 ${System.currentTimeMillis() - startedAt} ms"
        )
        return handle
    }

    /** 卸载当前模型，释放 native 内存。幂等。 */
    fun unload() {
        synchronized(lock) {
            releaseLocked()
            generation.incrementAndGet()
            loadedGeneration = -1L
        }
        AgentLogger.i(TAG, "模型已卸载")
    }

    private fun releaseLocked() {
        current?.let {
            LlamaJni.nativeFree(it.handle)
        }
        current = null
    }

    /** 请求停止当前生成（协程取消时调用；生成循环每 token 检查停止标志）。 */
    fun stop() {
        synchronized(lock) { current?.handle }?.let { handle ->
            if (handle != 0L) LlamaJni.nativeStop(handle)
        }
    }

    /** 应用模型自带对话模板；未加载时返回空串。 */
    fun chatTemplate(roles: Array<String>, contents: Array<String>): String {
        val handle = synchronized(lock) { current?.handle } ?: return ""
        if (handle == 0L) return ""
        return LlamaJni.nativeChatTemplate(handle, roles, contents)
    }

    /** 阻塞式生成；未加载时返回空串。listener 可空。 */
    fun generate(prompt: String, maxTokens: Int, listener: LlamaJni.TokenListener?): String {
        val handle = synchronized(lock) { current?.handle } ?: return ""
        if (handle == 0L) return ""
        return LlamaJni.nativeGenerate(handle, prompt, maxTokens, listener)
    }
}
