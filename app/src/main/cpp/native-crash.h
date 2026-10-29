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
 * 当前进程的常驻内存（KB）。读不到返回 -1。
 *
 * 用途：prefill 每算完一块就打一行 RSS。崩溃前那几行连起来看，是不是一路顶到
 * 天花板一目了然——这比事后从崩溃现场读一个孤立的 VmRSS 更有说服力：孤立的
 * 数字没法自证「它在涨」，一串数字可以。
 */
long ak_rss_kb(void);

/**
 * 记一条「不是信号打死的」死亡理由（典型是没被接住的 C++ 异常）。
 *
 * llama.cpp / ggml 内部大量 `throw std::runtime_error("failed to allocate ...")`，
 * 一旦穿过 JNI 边界没人接，就是 std::terminate → abort() → SIGABRT，日志全空。
 * 理由存在固定数组里，信号处理器会把它一起写进现场，不会被随后的 abort 覆盖掉。
 */
void ak_crash_reason(const char* why);

/**
 * 立刻按「信号崩溃」的同一套格式写现场（供 std::terminate 这种还没收到信号的路径用）。
 */
void ak_crash_dump_now(void);

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
