// 原生崩溃记忆的阶段标记（见 native-crash.cpp）。
//
// 为什么单独一个头：`llama-jni.cpp` 只需要「打个标记」，不需要看到信号处理的实现。
// 把这些函数放在头里，两边就不用互相 include 一堆平台头文件。

#ifndef AGENTKIT_NATIVE_CRASH_H
#define AGENTKIT_NATIVE_CRASH_H

/** 崩溃发生时程序处在哪一步。崩在哪里，处理方式完全不同。 */
enum AkStage {
    /** 空闲：不在任何加载/推理路径上（崩在这里多半与 GPU 无关）。 */
    AK_STAGE_IDLE = 0,
    /** 枚举/初始化 GPU 后端（Vulkan 实例、设备）。 */
    AK_STAGE_DEVICE_INIT = 1,
    /** 读 GGUF、把权重搬到显存。 */
    AK_STAGE_MODEL_LOAD = 2,
    /** 建 llama_context（KV cache 分配）。 */
    AK_STAGE_CTX_CREATE = 3,
    /** 首字之前的 prefill。 */
    AK_STAGE_PREFILL = 4,
    /** 逐 token 生成。 */
    AK_STAGE_DECODE = 5,
};

/** 更新当前阶段。可从任意线程调用（单字写入，无需锁）。 */
void ak_stage_set(int stage);

/**
 * 登记崩溃时要写进文件的上下文。
 *
 * @param modelName 模型文件名（只取 basename，路径太长且没信息量）
 * @param nCtx      上下文窗口
 * @param nThreads  线程数
 * @param gpuLayers 卸载到 GPU 的层数（0 = 纯 CPU）
 */
void ak_crash_context(const char* modelName, int nCtx, int nThreads, int gpuLayers);

/**
 * 安装崩溃捕获，崩溃文件写到 `dir/native-crash.txt`。
 *
 * 从 JNI 入口里抽出来，是为了能在主机上直接跑一遍（fork → raise(SIGSEGV) →
 * 检查文件内容）。只断言「源码里有这行字符串」测不出处理器到底装没装上。
 *
 * @param dir 已存在的目录绝对路径
 */
void ak_crash_install(const char* dir);

#endif  // AGENTKIT_NATIVE_CRASH_H
