package com.example.agentkit.core.llm.local

/**
 * 一次**原生**崩溃的现场（由 `native-crash.cpp` 的信号处理器写出）。
 *
 * 和 `CrashLogger` 抓的不是一类东西：CrashLogger 挂的是
 * `Thread.UncaughtExceptionHandler`，只能看见 Java 异常。GPU / Vulkan / ggml 崩起来
 * 走的是信号，进程当场消失，Java 那层连日志都来不及写——用户看到的就是「闪退，
 * 日志里什么都没有」。这类崩溃唯一的证据就是这个文件。
 */
data class NativeCrash(
    /** 信号编号（11 = SIGSEGV）。 */
    val signal: Int,
    /** 信号名（SIGSEGV / SIGABRT / …）。 */
    val signalName: String,
    /** 崩在哪一步（DEVICE_INIT / MODEL_LOAD / CTX_CREATE / PREFILL / DECODE / IDLE）。 */
    val stage: String,
    /** 当时卸载到 GPU 的层数（0 = 纯 CPU）。 */
    val gpuLayers: Int,
    /** 当时的上下文窗口。 */
    val ctx: Int,
    /** 当时的线程数。 */
    val threads: Int,
    /** 模型文件名。 */
    val model: String,
    /** 当时包里是否编入了 Vulkan 后端。 */
    val vulkanBuilt: Boolean
) {
    /** 崩的时候是不是在用 GPU。这是「要不要自动关掉 GPU」的唯一判据。 */
    val gpuInvolved: Boolean get() = gpuLayers > 0

    /** 阶段的中文说法（写进日志/界面，别让用户去猜 DEVICE_INIT 是什么）。 */
    val stageLabel: String
        get() = when (stage) {
            "DEVICE_INIT" -> "初始化显卡"
            "MODEL_LOAD" -> "把模型装进显存"
            "CTX_CREATE" -> "分配上下文缓存"
            "PREFILL" -> "读题（prefill）"
            "DECODE" -> "生成文字"
            else -> "空闲"
        }
}

/**
 * 崩溃文件的解析与判定。
 *
 * **刻意不 import 任何 Android 类型**：这些规则决定了「下次要不要关掉用户的 GPU」，
 * 是最该被断言覆盖的一段逻辑，而它必须能在纯 JVM 里被跑起来（QA harness 里没有
 * android.jar）。文件读写、SharedPreferences 那些留在 [NativeCrashMemory]。
 */
object NativeCrashParser {

    /** 与 `native-crash.cpp` 里的文件名必须一致。 */
    const val FILE_NAME: String = "native-crash.txt"

    /**
     * 解析 native 写出的 `key=value` 文本。
     *
     * 认不出的内容一律返回 null 而不是抛异常：这份文件是崩溃现场，
     * 写它的时候进程正在死，**可能只写了一半**（比如只写到 `signal=1`）。
     * 半个文件不能当成有效证据去关用户的 GPU。
     */
    fun parse(text: String): NativeCrash? {
        if (!text.contains("kind=native-crash")) return null
        val map = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val rawSignal = map["signal"] ?: return null
        // gpu_layers 必须存在：它是「要不要关 GPU」的唯一依据。
        // 只写了一半的文件（进程死在 write 中间）拿不到这个键，宁可当成没有记录，
        // 也不要拿残缺证据去关用户的 GPU。
        val gpuLayers = map["gpu_layers"]?.toIntOrNull() ?: return null
        // `signal=11 (SIGSEGV)`：编号在前，括号里是名字。
        val signal = rawSignal.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val name = Regex("\\(([A-Z0-9]+)\\)").find(rawSignal)?.groupValues?.get(1)
            ?: "SIG$signal"
        return NativeCrash(
            signal = signal,
            signalName = name,
            stage = map["stage"] ?: "IDLE",
            gpuLayers = gpuLayers,
            ctx = map["ctx"]?.toIntOrNull() ?: 0,
            threads = map["threads"]?.toIntOrNull() ?: 0,
            model = map["model"] ?: "?",
            vulkanBuilt = map["vulkan_built"] == "1"
        )
    }

    /**
     * 要不要因为这次崩溃把 GPU 关掉。
     *
     * 判据只有一条：**崩的时候 GPU 层数大于 0**。
     * 不按阶段判，是因为「纯 CPU 运行也会崩在 DECODE」（内存不足、模型损坏），
     * 那种情况关掉 GPU 没有任何意义，只会让用户少一个选项还查不出原因。
     */
    fun shouldDisableGpu(crash: NativeCrash?): Boolean = crash != null && crash.gpuInvolved

    /** 给用户看的一句话（设置页 + 日志共用，措辞保持一致）。 */
    fun describe(crash: NativeCrash): String =
        "上次在「${crash.stageLabel}」阶段崩溃（${crash.signalName}，" +
            "GPU ${crash.gpuLayers} 层 / 上下文 ${crash.ctx}），已自动关闭 GPU 加速"
}
