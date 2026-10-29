// AgentKit 本地推理 JNI 桥（净室重写，不复制任何第三方 JNI 封装）。
//
// 只做五件事：初始化模型/上下文、释放、对话模板应用、阻塞式生成（带 token 回调）、
// 停止标志。协议细节（消息→模板→采样）都在 Kotlin 侧 LocalLlamaClient 编排。
//
// llama.cpp 本体为 MIT 许可证（ggml-org/llama.cpp），源码在构建时由脚本拉取固定
// tag v0.7.0 到本目录的 llama.cpp/ 子目录，不进 git 仓库（见 CMakeLists.txt 注释）。
// MIT 许可全文随仓库提交于 llama.cpp-LICENSE.txt。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <exception>
#include <cstdarg>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

#include "llama.h"
// 设备枚举（ggml_backend_dev_count / dev_name / dev_type）。llama.h 只带 ggml.h，
// 设备注册表这层在 ggml-backend.h 里——「有没有认出 GPU」只能从这里回答。
// CPU-only 构建下这个头照样能编（注册表里只有 CPU 一个设备），不需要条件编译。
#include "ggml-backend.h"
// 原生崩溃记忆：只往这里打「当前阶段」标记，实现见 native-crash.cpp。
// 没有它，GPU 崩掉时进程直接消失，Java 那层一句日志都留不下。
#include "native-crash.h"

// ---------------------------------------------------------------------------
// llama.cpp API 兼容层：b4600（2025-01）↔ v0.7.0（2026-10）
//
// 上游在 2025 年中做了一次接口收敛，把 KV 缓存提升为通用「memory」抽象，三处
// 我们直接用到的 API 因此变了名/变了类型：
//
//   b4600                                   v0.7.0
//   llama_kv_cache_clear(ctx)           →   llama_memory_clear(llama_get_memory(ctx), true)
//   llama_kv_cache_seq_rm(ctx, ...)     →   llama_memory_seq_rm(llama_get_memory(ctx), ...)
//   cp.flash_attn = false               →   cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED
//   mparams.use_mmap = true             →   mparams.load_mode = LLAMA_LOAD_MODE_MMAP
//
// 判据用 LLAMA_SESSION_VERSION（b4600 是 9，v0.7.0 是 11）：上游没提供 API 版本宏，
// 这个值是随 API 变更一起 bumped 的，比 `__has_include` 或嗅探函数声明都可靠。
// 保留双向分支的理由是回退成本——万一新版在真机上出现无法快速定位的回归，把
// fetch-llama.sh 的 tag 改回 b4600 就能立刻编出可用包，不用再改一遍 C++。
// ---------------------------------------------------------------------------
#if LLAMA_SESSION_VERSION >= 10
#define AK_LLAMA_MEMORY_API 1
#else
#define AK_LLAMA_MEMORY_API 0
#endif

namespace {
#if AK_LLAMA_MEMORY_API
/** 清空整块 KV（含数据缓冲）。ctx 无 memory 模块时静默返回，不抛。 */
inline void akKvClear(llama_context* ctx) {
    if (llama_memory_t mem = llama_get_memory(ctx)) llama_memory_clear(mem, true);
}
/** 删除 [p0, p1) 区间；p1 < 0 表示删到末尾。返回 false 表示该模型不支持部分删除。 */
inline bool akKvSeqRm(llama_context* ctx, llama_seq_id seq, llama_pos p0, llama_pos p1) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (!mem) return false;   // 当作「不支持」，交给调用方走整块重算
    return llama_memory_seq_rm(mem, seq, p0, p1);
}
#else
inline void akKvClear(llama_context* ctx) { llama_kv_cache_clear(ctx); }
inline bool akKvSeqRm(llama_context* ctx, llama_seq_id seq, llama_pos p0, llama_pos p1) {
    return llama_kv_cache_seq_rm(ctx, seq, p0, p1);
}
#endif

/**
 * 重复惩罚采样器。v0.7.0 起需要在构造时显式给 n_vocab（内部按词表大小预分配
 * 惩罚环），b4600 不需要。其余四个参数含义、量纲完全一致，所以这个包装只是
 * 把「多出来的那个参数」在旧版路径上咽掉，不改变任何采样行为。
 */
inline llama_sampler* akSamplerPenalties(const llama_vocab* vocab, int32_t last_n,
                                         float repeat, float freq, float present) {
#if AK_LLAMA_MEMORY_API
    return llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), last_n, repeat, freq, present);
#else
    (void) vocab;
    return llama_sampler_init_penalties(last_n, repeat, freq, present);
#endif
}
}  // namespace

#define LOG_TAG "llama-jni"

namespace {

/**
 * 进程内诊断回路：最近 80 条 native 日志，每行带一个**进程级单调序号**。
 *
 * 存在的理由很实在——native 的 logcat 用户在手机上看不到，而「本地模型一句话都不回」
 * 这类问题的全部线索（tokenize 成不成功、decode 返回几、prefill 花了多久、首个 token
 * 是什么）都只在这层。把这些行回传给 Kotlin，用户翻「运行日志」就能把原因带给我，
 * 不需要连电脑抓 logcat。
 *
 * **为什么必须带序号**：这个环形缓冲是进程级的，跨请求累积。Kotlin 侧每个请求新建
 * 一个「已读集合」去重，等于第一轮把缓冲里**上一轮**的几十行全当新行又打了一遍 ——
 * 真机日志里出现过「第二轮 1.5 秒内复读出上一轮 13436ms 的预热行」，用户以为是又卡了
 * 13 秒；更糟的是看门狗拿这个假的 warmupMs 去算首字预算。序号让「这一轮到底产出了
 * 哪些行」变成一个确定的事实，而不是靠猜。
 */
std::mutex diagMu;
std::deque<std::pair<long long, std::string>> diagLines;

/** 已写入诊断回路的行数（也从 1 开始，0 表示「还没读过任何行」）。 */
std::atomic<long long> g_diagSeq{0};

/** 同时写 logcat 与诊断回路（va_list 两个消费者，必须 copy）。 */
void diagPrintf(int prio, const char* fmt, ...) {
    va_list ap, ap2;
    va_start(ap, fmt);
    va_copy(ap2, ap);
    __android_log_vprint(prio, LOG_TAG, fmt, ap);
    va_end(ap);
    char buf[1024];
    const int n = vsnprintf(buf, sizeof(buf), fmt, ap2);
    va_end(ap2);
    if (n <= 0) return;
    const long long seq = g_diagSeq.fetch_add(1) + 1;
    std::lock_guard<std::mutex> lk(diagMu);
    diagLines.emplace_back(seq, std::string(buf, static_cast<size_t>(
        n < static_cast<int>(sizeof(buf)) ? n : static_cast<int>(sizeof(buf)) - 1)));
    while (diagLines.size() > 80) diagLines.pop_front();
}

#define LOGI(...) diagPrintf(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGE(...) diagPrintf(ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGW(...) diagPrintf(ANDROID_LOG_WARN, __VA_ARGS__)

/**
 * 把 llama.cpp / ggml 自己的日志接进「运行日志」。
 *
 * **这是「闪退且日志空白」的另一半原因。** 这两层的输出默认只走 stderr，
 * 而 Android 的 App 进程在 zygote 里 fd 2 就指向 /dev/null 了 ——
 * ggml 在 abort 前明明说了话（`GGML_ASSERT(...) failed`、`failed to allocate`、
 * `unknown model architecture`），我们一个字都收不到。
 *
 * 分级刻意收窄：
 *  - ERROR / WARN：全收。这类行一出现就基本等于「这就是死因」；
 *  - CONT（续行）：只在上一条是 ERROR/WARN 时收，否则会把长表格拆散；
 *  - INFO / DEBUG：一律丢。decode 期间每批都有一行图构建信息，全转进来会把
 *    80 行的环形缓冲冲干净，反而埋掉真正有用的那几行。
 *    （细节仍然照抄一份进 native-stderr.txt，需要时按文件看。）
 */
void akLlamaLog(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text || !*text) return;
    // thread_local：prefill 用多线程，续行标志必须按线程分，混用会串行。
    static thread_local bool prevNotable = false;
    const bool isErr = (level == GGML_LOG_LEVEL_ERROR);
    const bool isWarn = (level == GGML_LOG_LEVEL_WARN);
    const bool isCont = (level == GGML_LOG_LEVEL_CONT);
    const bool emit = isErr || isWarn || (isCont && prevNotable);
    prevNotable = isErr || isWarn;
    if (!emit) return;

    // 去掉行尾换行：日志页是逐行渲染的，带 '\n' 会多出一行空白。
    char line[256];
    size_t n = 0;
    for (; text[n] && n < sizeof(line) - 1; ++n) line[n] = text[n];
    while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) --n;
    line[n] = '\0';
    if (n == 0) return;
    if (isErr) LOGE("[ggml] %s", line);
    else LOGI("[ggml] %s", line);
}

/**
 * 未捕获的 C++ 异常 → std::terminate → abort() → SIGABRT，**一行日志都不留**。
 *
 * llama.cpp 内部大量 `throw std::runtime_error(...)`（分配失败、架构不认、形状
 * 不合法）。这些异常穿过 JNI 边界没人接，就变成「闪退 + 日志空白」——和「被系统
 * 杀掉」在用户侧完全同形。装一个 terminate 处理器，至少把 what() 留下来。
 *
 * 刻意绕开 LOGE：terminate 可能发生在正持有 diagMu 的线程里，走日志回路有死锁
 * 风险。直接写 stderr（已被接进 native-stderr.txt）最稳。
 */
static void akTerminate() {
    char msg[192];
    const char* what = "std::terminate（无活动异常）";
    if (auto e = std::current_exception()) {
        try {
            std::rethrow_exception(e);
        } catch (const std::exception& ex) {
            snprintf(msg, sizeof(msg), "未捕获的 C++ 异常: %s", ex.what());
            what = msg;
        } catch (...) {
            what = "未捕获的 C++ 异常（非 std::exception）";
        }
    }
    fprintf(stderr, "[terminate] %s\n", what);
    fflush(stderr);
    ak_crash_reason(what);
    // 先把现场写一份：随后的 abort() 会再走一遍信号处理器（它会把 reason 一起带上）。
    ak_crash_dump_now();
    abort();
}

/**
 * 装一次就够（进程级）。llama_log_set 是全局的，重复设只会重复覆盖。
 */
void installLlamaLogging() {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(akLlamaLog, nullptr);
        std::set_terminate(akTerminate);
    });
}

/**
 * 安全模式：压物理批 + 由 Kotlin 侧同时压上下文，专门对付「prefill 阶段 OOM」。
 *
 * 由 `LlamaJni.nativeSetSafeMode()` 在 `nativeInit` **之前**设置，进程级生效；
 * 库随进程重启而重置，所以 Kotlin 每次启动都要重新设一次。
 */
static std::atomic<bool> g_safeMode{false};

/**
 * 进程级「GPU 已判死刑」标记。
 *
 * 真机 2026-10-29 暴露了一个致命组合：Vulkan 后端一旦被编进 .so，**即使 n_gpu_layers=0**
 * 它仍会参与 context 的 compute buffer 分配（日志里有铁证：
 * `~llama_context: Vulkan0 compute buffer size of 25.07 MiB`），于是「纯 CPU」也在
 * Adreno 上建 mul_mat_vec 管线 → 同一个 VK_ERROR_UNKNOWN。
 *
 * 结果就是用户在「关闭 / 自动 / 强制开启」三档里怎么切都失败——关闭也失败，因为
 * 关闭只是不卸载层，并没有把 Vulkan 设备从 model.devices 里摘掉。
 *
 * 这里记在 native：Kotlin 侧每个请求都会新建一个 client，实例字段活不过一轮，
 * 只有进程级状态才靠得住。一旦置位，之后所有 nativeInit 一律按 CPU 装（devices 只给 CPU）。
 */
static std::atomic<bool> g_gpuDisabled{false};

/**
 * 最近一次 nativeInit 的诊断序号水位。
 *
 * 诊断缓冲是进程级的，跨请求累积。之前 nativeDiagnostics() 一次性倒出全部 80 行，
 * 用户看到「本轮」诊断里混着上一轮的预热/prefill 行，两次的耗时还被并排比较——
 * 13:09 那轮到底是纯 CPU 还是 GPU，光看那坨文本根本分不清。
 * 只倒出「本次加载之后」的行，诊断才是可以按时间顺序读的。
 */
static std::atomic<long long> g_loadSeq{0};

/** 一个已加载的本地模型实例。句柄以 jlong 传给 Kotlin。 */
struct LlamaInstance {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_batch = 512;   // 与 n_ubatch 同量级：进度可分块上报、停止能尽快生效
    int n_threads = 4;
    std::atomic<bool> stop{false};

    /**
     * KV cache（seq 0）当前持有的 token 序列 —— 增量 prefill 的账本。
     *
     * 对话每一轮都把「完整 prompt」交给 native（Kotlin 侧无状态：历史是消息列表，
     * 模板每轮重渲染），若每次都从头 decode，首字延迟随对话长度线性增长：3000
     * token 的历史在手机 CPU 上要重算好几秒，用户看到的就是「点了没反应」。
     * 这里记下已进 KV 的 token，下一轮与新 prompt 求最长公共前缀，只补算差异段：
     * 第二轮起（历史原样保留时）prefill 通常只剩「上一条回复 + 新提问」那几十个 token。
     *
     * 只维护一条序列：nativeGenerate 用 llama_batch_get_one，其 seq_id 固定为 0。
     */
    std::vector<llama_token> kvTokens;

    /**
     * 串行化生成：KV 账本是有状态的，两轮同时 decode 会互相踩。
     * 锁只包住 nativeGenerate 本体（不包括 nativeStop —— 停止必须能随时生效）。
     */
    std::mutex genMu;

    /** 加载时实际卸载到 GPU 的层数。0 = 纯 CPU，用来判断异常该不该记成「GPU 故障」。 */
    int gpuLayers = 0;

    /**
     * GPU 后端自己炸了（Vulkan 驱动在 llama_decode 内部抛 C++ 异常）。
     *
     * 真机 2026-10-29：Adreno 740 / Vulkan 1.4，99 层全卸载，首次生成时
     * `vk::Device::createComputePipeline` 返回 VK_ERROR_UNKNOWN 并 throw，异常穿过
     * JNI 边界直接 terminate，整个 App 闪退，用户只看到「点了就没了」。
     *
     * 现在在 decodeExplicit 里就地接住：进程不崩，标记成故障，由 Kotlin 侧关掉 GPU
     * 重载 CPU 后重试这一轮。**不做三级自愈**——GPU 后端已坏，再 decode 一次只是
     * 把同一个异常再抛一遍，纯属浪费用户十几秒。
     */
    std::atomic<bool> gpuFault{false};
    std::string faultReason;
    std::mutex faultMu;

    void markGpuFault(const char* what) {
        std::lock_guard<std::mutex> g(faultMu);
        if (!gpuFault.load()) {
            faultReason = (what && *what) ? what : "未知 C++ 异常";
            gpuFault.store(true);
        }
        // 进程级：不只是「这个实例坏了」，而是「这台机器的 GPU 这条路走不通」。
        // 之后任何 nativeInit 都不再尝试卸载，避免用户切一次档就多崩一轮。
        g_gpuDisabled.store(true);
    }

    /** 重载（新建实例）时复位：新实例本来就没有故障。 */
    void clearGpuFault() {
        std::lock_guard<std::mutex> g(faultMu);
        faultReason.clear();
        gpuFault.store(false);
    }
};

/**
 * ggml 的中断回调：llama_decode 内部每算完一个 ubatch 就调一次，**返回 true = 立刻收手**。
 *
 * 与「每块 token 之间查一次 stop」不是一回事：后者只能在 n_batch（几百 token）的边界
 * 生效，而慢设备上几百 token 可能要几十秒——用户点了停止，界面还能再转半分钟，
 * 主观感受就是「停止键没用」。注册进 context 后，decode 内部任何时刻都能中断。
 *
 * 只对 CPU 后端有效（llama.h 明确写了 currently works only with CPU execution），
 * 北辰目前就是纯 CPU，正好。
 */
bool abortCb(void* data) {
    return static_cast<LlamaInstance*>(data)->stop.load(std::memory_order_relaxed);
}

/** 单调时钟毫秒（只用于打点，不受系统时间调整影响）。 */
long long nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

/**
 * 返回 s 里可安全按 UTF-8 输出的字节数：末尾不完整的多字节序列先攒着。
 *
 * 一个汉字常被 BPE 切成 2~3 个 token，逐个 token 直接 NewStringUTF 必然出现
 * 「半个字符」——Android 上表现为乱码方块，看起来像卡住或渲染坏了。
 */
size_t utf8SafeLen(const std::string& s) {
    const auto* p = reinterpret_cast<const unsigned char*>(s.data());
    size_t i = 0, safe = 0;
    while (i < s.size()) {
        const unsigned char c = p[i];
        size_t need;
        if (c < 0x80) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else { i++; safe = i; continue; }        // 非法首字节：原样吐出去，别卡住
        if (i + need > s.size()) return i;       // 尾部不完整：留给下一片
        i += need;
        safe = i;
    }
    return safe;
}

/** 清空 KV 并同步账本（任何「账本与 KV 可能不一致」的路径都必须走这里）。 */
void resetKv(LlamaInstance* inst, const char* why) {
    if (!inst || !inst->ctx) return;
    akKvClear(inst->ctx);
    inst->kvTokens.clear();
    if (why) LOGI("KV 会话重置：%s", why);
}

/**
 * tokenize 整个 prompt（add_special=true, parse_special=true）。失败返回 false。
 *
 * **llama_tokenize 的返回值约定（踩过的坑，别再改回去）**：
 *   - n_tokens_max 装得下 → 返回实际 token 数（正数）
 *   - 装不下            → 返回 **负数**，绝对值是「需要多少」
 *
 * 所以「传 nullptr/0 先问需要多大」这一趟**正常就返回负数**。早期把 `n <= 0`
 * 一律当失败，等于每一个非空 prompt 都判死：模型能加载、80ms 就报错、一个字不出，
 * 而且报的还是「分词失败」这种听起来像词表不兼容的文案 —— 真机上看起来就像
 * 「模型配好了但发不出去」。分词器本身对这种输入毫无问题。
 */
bool tokenizeAll(const llama_vocab* vocab, const std::string& text, std::vector<llama_token>& out) {
    const int32_t len = static_cast<int32_t>(text.size());
    if (len <= 0) return false;

    int32_t n = llama_tokenize(vocab, text.c_str(), len, nullptr, 0, true, true);
    int32_t need = n < 0 ? -n : n;   // 负数是「需要多少」，不是错误
    if (need <= 0) return false;

    // 两趟之间词表不会变，正常一趟就够；留重试是因为个别版本在
    // parse_special 合并相邻特殊 token 时，第二趟仍会报「还差一点」。
    for (int attempt = 0; attempt < 3; attempt++) {
        out.resize(static_cast<size_t>(need));
        const int32_t got = llama_tokenize(vocab, text.c_str(), len, out.data(), need, true, true);
        if (got > 0) {
            out.resize(static_cast<size_t>(got));
            return true;
        }
        if (got == 0) return false;     // 真空输入，无法继续
        need = -got;                    // 仍不够：按新需求再来一趟
    }
    return false;
}

bool rebuildContext(LlamaInstance* inst);
bool decodeExplicit(LlamaInstance* inst, const llama_token* toks, int32_t n, llama_pos pos0);

/**
 * 把 [LlamaInstance::n_threads] 应用到当前 context（运行期生效，不重建）。
 *
 * 1.444 那版是「改线程 → 重建 context → 重测」，重建本身要几十毫秒且会清掉 KV。
 * b4600 提供了 `llama_set_n_threads`，改线程池不需要动 context，代价可以忽略。
 */
void applyThreads(LlamaInstance* inst) {
    if (!inst || !inst->ctx) return;
    llama_set_n_threads(inst->ctx, inst->n_threads, inst->n_threads);
}

/**
 * prefill 单块上限（token）。
 *
 * 早先是 64，理由是「进度别太碎、停止能尽快生效」。但真机上一轮轻聊 prompt 只有
 * 83 token，被切成 64 + 19 两块：第二块只装 19 个 token，却要付一次完整的同步开销，
 * prefill 16 tok/s 里相当一部分是这个固定成本。现在 abort 回调已能随时中断，
 * 进度粒度不再是硬约束，改 128 —— 常见 prompt 一次算完，长 prompt 也不会太粗。
 */
constexpr int PREFILL_CHUNK = 128;

/** 单 token 预热的判定阈值：超过它就认定「引擎本身慢」，与 prompt 长短无关。 */
constexpr long long WARMUP_SLOW_MS = 8000;

/**
 * 单 token 预热探针：把第一个 token 单独 decode 一次并计时。
 *
 * 这是把「算得慢」和「卡死」分开的最低成本手段 —— 只花 1 个 token 的代价。
 * 3B Q4_K_M 在 8 核手机上，1 token 的 forward 正常是 100~400 ms。真机上出现过
 * 「158 token 的 prefill 180 秒不出第一块」，那种情况下这行日志会直接给出答案：
 *
 *   - 1 token 就要 5 秒以上 → 慢在权重布局/线程/内存带宽，跟 prompt 长度无关；
 *   - 1 token 几十毫秒，但整段 prefill 很久 → 慢在批处理，调分块才有意义。
 *
 * **「慢一次就降线程」是错的（1.446 真机推翻了它）**：日志里同一台机器
 * 「6 线程 13436 ms → 4 线程 170 ms」，看似线程越多越慢，其实那 13 秒是 2GB 权重
 * 第一次被触碰的页错误 + Q4_K 重排，是一次性的；第二次无论几线程都是百毫秒级。
 * 于是这一版改成：
 *   ① 冷启动成本在 nativeInit 里就付掉（见「冷启动预热」）；
 *   ② 这里慢一次先复测一次，取较快的那个；
 *   ③ 两次都慢、且线程还能降，才动线程 —— 而且用 `llama_set_n_threads`
 *      就地改，不重建 context、不清 KV。
 *
 * @return true 表示 tokens[0] 已进 KV 并记账（调用方应从下标 1 继续）
 */
bool warmupDecode(LlamaInstance* inst, const std::vector<llama_token>& tokens) {
    if (tokens.empty()) return false;

    // 1 token 的 forward 不需要「最后一个 token 要 logits」之外的任何东西，
    // decodeExplicit 天然满足（n=1 时 i==n-1）。
    auto probe = [&]() -> long long {
        const long long t0 = nowMs();
        if (!decodeExplicit(inst, tokens.data(), 1, 0)) return -1;
        return nowMs() - t0;
    };

    long long ms = probe();
    if (ms < 0) {
        LOGE("预热失败：连 1 个 token 都 decode 不了，本回合直接放弃（不进入长 prefill）");
        return false;
    }
    LOGI("预热 decode：1 token / %lld ms（%s）", ms,
         ms <= 400 ? "正常" : (ms <= WARMUP_SLOW_MS ? "偏慢" : "异常慢"));

    if (ms > WARMUP_SLOW_MS) {
        // 后台任务、发热降频、页缓存被回收都可能让单次计时失真。先复测一次，
        // 用两次里较快的那个判定——真机上 13436 ms 与 170 ms 的差距就是这一条。
        const long long again = probe();
        if (again >= 0 && again < ms) {
            LOGI("预热复测：1 token / %lld ms，取较快的 %lld ms（首次含冷启动成本）", again, again);
            ms = again;
        } else if (again >= 0) {
            LOGI("预热复测：1 token / %lld ms（两次都慢，判定为真实算力）", again);
        }
    }

    if (ms > WARMUP_SLOW_MS && inst->n_threads > 4) {
        const int lower = std::max(4, inst->n_threads / 2);
        LOGE("预热两次都超过 %lld ms，线程 %d → %d 就地重测（不重建上下文）",
             WARMUP_SLOW_MS, inst->n_threads, lower);
        inst->n_threads = lower;
        applyThreads(inst);
        LOGI("线程已切到 %d，KV 保留 %zu token", inst->n_threads, inst->kvTokens.size());
        ms = probe();
        if (ms < 0) {
            LOGE("降线程后预热仍失败，本回合放弃");
            return false;
        }
        LOGI("预热 decode 重试：1 token / %lld ms（线程 %d）", ms, inst->n_threads);
    }

    inst->kvTokens.push_back(tokens[0]);
    return true;
}

/**
 * 显式构造 batch 并 decode：pos / seq_id / logits 全部手工填。
 *
 * **不用 llama_batch_get_one 的原因**：它靠 pos=nullptr 让 llama.cpp 内部按
 * 「KV 里该序列的最大位置 + 1」自动续号。这条隐式规则在增量 prefill 下是致命的
 * —— 上一轮我们主动删过 KV（`llama_kv_cache_seq_rm`），内部 max_pos 是否回落到
 * 删除点取决于版本实现；一旦它没回落，新 prompt 就被写到后面去，位置全错。
 * 更糟的是这种错位不一定报错，只表现为「回答离谱」或「立刻 EOS、一个字不出」。
 *
 * 因此这里自己记 pos（调用方传 pos0），不向 llama.cpp 猜任何东西。
 *
 * @param pos0 第一个 token 的位置（= 该序列已进 KV 的 token 数）
 */
bool decodeExplicit(LlamaInstance* inst, const llama_token* toks, int32_t n, llama_pos pos0) {
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int32_t i = 0; i < n; i++) {
        batch.token[i] = toks[i];
        batch.pos[i] = pos0 + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        // 只要最后一个 token 的 logits（采样用 idx=-1）；全算会让长 prompt 的
        // 输出层开销翻几十倍 —— 这也是「首字要等好几秒」的一个隐性来源。
        batch.logits[i] = (i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;
    // 第一次进 decode 之前是 prefill（吃 prompt），之后是逐 token 生成。
    // 两个阶段的崩溃原因不同：prefill 看重算力与大 batch，生成看重显存带宽。
    ak_stage_set(batch.n_tokens > 1 ? AK_STAGE_PREFILL : AK_STAGE_DECODE);

    // llama.cpp 的后端实现大量 `throw std::runtime_error(...)`（Vulkan 那一侧尤其多，
    // 例如 vk::Device::createComputePipeline 拿到 VK_ERROR_UNKNOWN 就直接 throw）。
    // 异常穿过 JNI 边界 = terminate = SIGABRT = 用户那句「又闪退了」，而且什么都留不下。
    // 所以**在这里就地接住**：标记故障、返回失败，把「崩溃」降级成「这一轮没算出来」。
    int rc = -100;
    try {
        rc = llama_decode(inst->ctx, batch);
    } catch (const std::exception& e) {
        llama_batch_free(batch);
        if (inst->gpuLayers > 0) {
            inst->markGpuFault(e.what());
            LOGE("llama_decode 抛出异常（GPU %d 层，已标记为 GPU 故障）：%s",
                 inst->gpuLayers, e.what());
        } else {
            LOGE("llama_decode 抛出异常（纯 CPU）：%s", e.what());
        }
        return false;
    } catch (...) {
        llama_batch_free(batch);
        if (inst->gpuLayers > 0) inst->markGpuFault("未知 C++ 异常");
        LOGE("llama_decode 抛出未知异常（GPU %d 层）", inst->gpuLayers);
        return false;
    }
    llama_batch_free(batch);
    if (rc != 0) {
        if (inst->stop.load()) {
            // 中止不是故障：llama_decode 可能已经写入了前面若干个 ubatch 才返回非 0。
            // 此时账本没动（成功才记账），所以把 KV 回退到账本长度就能对齐——
            // **不要清空**，前面算好的几千 token 下一轮还能复用。
            akKvSeqRm(inst->ctx, 0, static_cast<llama_pos>(inst->kvTokens.size()), -1);
            LOGI("decode 被用户中止（rc=%d, n=%d, 账本 %zu token，KV 已对齐）",
                 rc, n, inst->kvTokens.size());
            return false;
        }
        // 失败也要把 KV 回退到账本。
        //
        // llama.h 对非 0 返回值只说「成功处理的 ubatch 会留在 memory 里」——留多少不知道。
        // 账本是唯一真相（成功才记账），所以这里按账本长度切一刀，把多写的删掉。
        // 不切的话，下一轮从账本长度推出的 pos 会和 KV 里残留的条目冲突，
        // 报 `inconsistent sequence positions`，然后每一轮都 rc=-1（真机 13:13:16 那次）。
        akKvSeqRm(inst->ctx, 0, static_cast<llama_pos>(inst->kvTokens.size()), -1);
        LOGE("llama_decode 失败 rc=%d（n=%d, pos0=%d, KV 已占用=%zu, n_ctx=%d，KV 已回退到账本）",
             rc, n, static_cast<int>(pos0), inst->kvTokens.size(),
             static_cast<int>(llama_n_ctx(inst->ctx)));
        return false;
    }
    return true;
}

/**
 * 把 tokens[from..] 分块 decode 进 KV，并把吃进去的 token 追加到账本。
 *
 * 位置由账本长度推出：账本里已有的 token 就占着 0..from-1，故本块从 from 开始。
 *
 * 每块都打一行进度（token 数 / 累计毫秒 / tok/s），并且**每块都检查停止标志**——
 * 之前 prefill 是不可中断的，用户点「停止」要等到整段算完才生效，看起来像没反应。
 *
 * @param startedAt prefill 起点（毫秒），用于算累计速率；重试时传新的起点
 */
bool decodeFrom(LlamaInstance* inst, std::vector<llama_token>& tokens, size_t from,
                const long long startedAt) {
    // 进门先留一行。之前 158 token 的 prefill 卡了 180 秒，日志里连「进没进 decode」
    // 都判断不了，只能靠猜；有了这行，下一份日志一眼就能分出三种情况：
    //   有「预热」但没「开始」 → 卡在 KV 回退/账本；
    //   有「开始」但没「进度」 → 卡在 llama_decode 内部；
    //   有进度但很慢         → 才是真的算力不够。
    LOGI("prefill 开始：从第 %zu 个起，共 %zu token，线程 %d，分块 %d",
         from, tokens.size(), inst->n_threads, std::min(inst->n_batch, PREFILL_CHUNK));

    // 分块上限取 PREFILL_CHUNK（现在是 128，理由见它的定义）。
    //
    // 块大小不影响总吞吐（llama.cpp 内部本来就按 ubatch 分趟），但决定两件事：
    // 第一行进度多久出现，以及要付几次「同步 + 启动」的固定成本。64 的时候一轮
    // 83 token 的轻聊 prompt 被切成 64 + 19，第二块只装 19 个 token 却付一次全价。
    const int chunkCap = std::min(inst->n_batch, PREFILL_CHUNK);

    for (size_t i = from; i < tokens.size(); i += static_cast<size_t>(chunkCap)) {
        if (inst->stop.load()) {
            LOGI("prefill 被用户停止（已算 %zu / %zu token）", i, tokens.size());
            return false;
        }
        const size_t chunk = std::min(static_cast<size_t>(chunkCap), tokens.size() - i);
        if (!decodeExplicit(inst, tokens.data() + i, static_cast<int32_t>(chunk),
                            static_cast<llama_pos>(i))) {
            // 中止：KV 已在 decodeExplicit 里对齐到账本，账本本身没动，可以直接留用。
            // 真失败：账本与 KV 的关系不再可信，必须整块清掉。
            // GPU 故障也照样清：llama_kv_cache_seq_rm 只动元数据，不碰 GPU，安全。
            if (!inst->stop.load()) resetKv(inst, "prefill decode 失败");
            return false;
        }
        inst->kvTokens.insert(inst->kvTokens.end(), tokens.begin() + i, tokens.begin() + i + chunk);

        // 每个分块打一行进度。**这是真机上唯一能看见「它到底在干嘛」的地方**：
        // 之前只有一行结束日志，用户等到 500 秒仍是空白，分不清是「在算」还是「卡死」。
        // 同时给 tok/s —— 3B Q4 在手机 CPU 上 prefill 十几 tok/s 属正常，个位数就是异常。
        const long long spent = nowMs() - startedAt;
        const size_t done = i + chunk;
        // 速率只用「本轮真正补算的 token」当分子，不用绝对下标。
        //
        // 增量 prefill 时 from > 0，两者差得很远：真机 2026-10-29 16:08 这一行报
        //   prefill 进度：118 / 118 token，1347 ms（87.6 tok/s）
        // 而紧接着的汇总行是「复用 98 / 共 118，补算 20，耗时 1347 ms（14.8 tok/s）」。
        // 分母是这一块的耗时，分子却把复用的 98 个也算进去了，于是凭空多出 6 倍速率。
        // 虚高的数字比没有数字更坏——它会让人以为 prefill 没问题，转去查别的地方。
        const size_t computed = done - from;
        // RSS 一起打：三次真机闪退都崩在 prefill，而我们手上连「当时占了多少内存」
        // 都没有。孤立的一个数字说服力有限，一串数字可以——它涨得快不快，
        // 「算不动」和「装不下」在日志里就是两种形状。
        char rssBuf[32] = {0};
        const long rssKb = ak_rss_kb();
        if (rssKb > 0) snprintf(rssBuf, sizeof(rssBuf), "，RSS %ld MB", rssKb / 1024);
        LOGI("prefill 进度：%zu / %zu token（本轮补算 %zu），%lld ms（%.1f tok/s）%s",
             done, tokens.size(), computed, spent,
             spent > 0 ? computed * 1000.0 / static_cast<double>(spent) : 0.0, rssBuf);
    }
    return true;
}

std::string jstringToStd(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
}

/**
 * 重建推理上下文（保留模型）。
 *
 * 兜底用：KV 状态一旦被写坏（decode 中途失败、账本与 KV 不一致），只清 KV 未必
 * 能救回来。重建是最后一个「不用重启 App」的自愈手段，代价是几毫秒的分配。
 */
bool rebuildContext(LlamaInstance* inst) {
    if (!inst || !inst->model) return false;
    const int nCtx = inst->ctx ? static_cast<int>(llama_n_ctx(inst->ctx)) : 4096;
    if (inst->ctx) {
        llama_free(inst->ctx);
        inst->ctx = nullptr;
    }
    inst->kvTokens.clear();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_threads = inst->n_threads;
    // 与 nativeInit 同口径：prefill 线程数 = 生成线程数，**不要 +2**（见 W-90）。
    // 这条曾经在重建路径上漏掉：自愈重建后 prefill 又摊上小核，于是「重建过一次
    // 之后一直慢」——比崩溃更难查，因为它不报错，只是慢十倍。
    cp.n_threads_batch = inst->n_threads;
    cp.n_batch = std::min(inst->n_batch, std::min(nCtx, 256));
    cp.n_ubatch = std::min<uint32_t>(cp.n_batch, 512u);
    cp.no_perf = true;
    cp.abort_callback = &abortCb;
    cp.abort_callback_data = inst;
#if AK_LLAMA_MEMORY_API
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
#else
    cp.flash_attn = false;
#endif
    inst->ctx = llama_init_from_model(inst->model, cp);
    if (!inst->ctx) {
        LOGE("重建上下文失败（n_ctx=%d）", nCtx);
        return false;
    }
    return true;
}

}  // namespace

/**
 * 把 ggml 认得的后端设备列成一行，如：
 *   `GPU 1 / 共 2：GPU:Vulkan0(Adreno (TM) 750) | CPU:NEON`
 *
 * 为什么这件事值得单独一个函数：用户点开 GPU 之后，唯一能回答「到底上没上」的证据
 * 就是这个列表。没有它，「GPU 开了但还是很慢」只能靠猜——而猜错的方向通常是去调
 * 线程数，白费一轮。
 *
 * 注意：这个调用会**首次初始化设备注册表**，Vulkan 后端在 reg_init 里创建 VkInstance
 * （源码里 try/catch，失败返回 nullptr 并跳过该后端，不会把异常抛到我们这边）。
 * 所以第一次调用可能要几百毫秒，不要在 UI 线程上调。
 */
struct GpuDevices {
    int gpus = 0;
    size_t total = 0;
    std::string list;
};

static GpuDevices enumerateDevices() {
    GpuDevices out;
    out.total = ggml_backend_dev_count();
    for (size_t i = 0; i < out.total; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        const char* name = ggml_backend_dev_name(dev);
        const char* desc = ggml_backend_dev_description(dev);
        // IGPU 也是 GPU（集显，用系统内存）。手机上的 Vulkan 设备恒为 GPU 类型，
        // 但把 IGPU 一起算进来是为了不误报——漏算的结果是「明明有显卡却说没有」。
        const bool isGpu = type == GGML_BACKEND_DEVICE_TYPE_GPU ||
                           type == GGML_BACKEND_DEVICE_TYPE_IGPU;
        const char* kind = type == GGML_BACKEND_DEVICE_TYPE_GPU     ? "GPU"
                         : type == GGML_BACKEND_DEVICE_TYPE_IGPU    ? "IGPU"
                         : type == GGML_BACKEND_DEVICE_TYPE_ACCEL   ? "ACCEL"
                                                                   : "CPU";
        if (isGpu) ++out.gpus;
        if (!out.list.empty()) out.list += " | ";
        out.list += kind;
        out.list += ":";
        out.list += (name && *name) ? name : "?";
        if (desc && *desc) {
            out.list += "(";
            out.list += desc;
            out.list += ")";
        }
    }
    return out;
}

static std::string gpuDeviceSummary() {
    const GpuDevices d = enumerateDevices();
    return "GPU " + std::to_string(d.gpus) + " / 共 " + std::to_string(d.total) +
           (d.list.empty() ? "（注册表为空）" : "：" + d.list);
}

extern "C" {

/**
 * 加载 GGUF 模型并创建推理上下文。
 *
 * @param modelPath GGUF 文件绝对路径
 * @param nCtx      上下文窗口（token 数）
 * @param nThreads  推理线程数
 * @param nGpuLayers 卸载到 GPU 的层数（0 = 纯 CPU；高通 Adreno 可试 >0）
 * @return 实例句柄；0 表示失败（Kotlin 侧读日志定位）
 */
JNIEXPORT jlong JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring modelPath,
    jint nCtx, jint nThreads, jint nGpuLayers) {
    std::string path = jstringToStd(env, modelPath);

    // 诊断只留「本次加载之后」的行。
    g_loadSeq.store(g_diagSeq.load());

    // 进程内已经判过 GPU 死刑：无论调用方要几层，一律按 CPU 装。
    //
    // 这不是「不信任上层」，而是上层的状态活不过一轮：LocalLlamaClient 每轮新建，
    // 用户点一次「强制开启」就能把刚记住的故障状态覆盖掉，然后同一台机器再崩一次。
    // 判据必须放在唯一不会丢的地方。
    int gpuLayers = nGpuLayers;
    if (gpuLayers > 0 && g_gpuDisabled.load()) {
        LOGW("nativeInit: 进程内已标记 GPU 驱动故障，请求的 %d 层不予卸载，本次按 CPU 加载",
             gpuLayers);
        gpuLayers = 0;
    }
    LOGI("nativeInit: %s ctx=%d threads=%d gpu=%d", path.c_str(), nCtx, nThreads, gpuLayers);

    // 第一件事：把 ggml 的日志接过来、把 std::terminate 接住。
    // 必须在任何 llama_* 调用之前——装载期就会抛异常（架构不认、文件损坏），
    // 那正是我们最需要 what() 的时候。
    installLlamaLogging();

    // ggml 时间基准与 f16 查找表初始化（进程级，仅一次；CPU 后端由静态注册表自动注册）
    static std::once_flag backendOnce;
    std::call_once(backendOnce, [] { llama_backend_init(); });

    // 打印后端自报的能力清单（CPU 特性、向量宽度、启用的 kernel 家族）。
    //
    // 这行是「prefill 慢到底是算法还是编译」的唯一证据：同样的 Q4_K_M，走 dotprod
    // 内核和走通用 NEON 能差 3~5 倍。真机上如果这里只写着 scalar / 没有 dotprod，
    // 那再怎么调线程数、批大小都没用——问题在构建参数，不在推理参数。
    static std::once_flag infoOnce;
    std::call_once(infoOnce, [] {
        const char* info = llama_print_system_info();
        if (info && *info) LOGI("后端能力：%s", info);
        // 编译优化级别要在运行时亲口说出来。
        //
        // 「1 个 token 算 24 秒」那次排查绕了很久，就是因为没有任何一行日志能证明
        // ggml 是按 -O0 编出来的。__OPTIMIZE__ 由编译器在 -O1 及以上时定义，
        // 它缺失就说明整个 .so 是未优化的，后面所有性能调参都是白费力气。
#ifdef __OPTIMIZE__
        LOGI("编译优化：已开启（-O2/-O3 内核）");
#else
        LOGE("编译优化：未开启（-O0）！这次推理会慢几十倍，问题在构建参数而不是设备");
#endif
    });

    // 请求了 GPU，就先报一次「这台机器到底有几个 GPU 设备」。
    //
    // 这是「用户以为在用 GPU、其实一直在 CPU 上」的唯一防线。Vulkan 后端初始化失败
    // 是**静默**的（源码里 try/catch 后返回 nullptr，注册表直接跳过），所以如果只看
    // 「加载成功」，一台驱动不支持的机器会安静地退回 CPU 跑上几周。这里当场说清楚。
    if (gpuLayers > 0) {
        // Vulkan 实例与物理设备就在这一次枚举里首次创建（ggml 的 reg_init），
        // 驱动不兼容时崩的就是这里，所以必须单独标一个阶段。
        ak_stage_set(AK_STAGE_DEVICE_INIT);
        const GpuDevices devs = enumerateDevices();
        if (devs.gpus == 0) {
            LOGE("请求 GPU %d 层，但没有任何 GPU 后端可用（%s）——本次将按 CPU 运行",
                 gpuLayers, devs.list.empty() ? "注册表为空" : devs.list.c_str());
        } else {
            LOGI("请求 GPU %d 层，可用设备：%s", gpuLayers,
                 devs.list.empty() ? "（空）" : devs.list.c_str());
        }
    }

    auto* inst = new LlamaInstance();
    // 记下这次真的卸载了几层：llama_decode 抛异常时据此决定「算 GPU 故障」还是
    // 「纯 CPU 上的普通错误」——前者要降级重载，后者降级没意义。
    inst->gpuLayers = gpuLayers;

    // 预读提示：让内核在我们解析 GGUF 头部的同时，把权重从闪存拉进 page cache。
    //
    // mmap 只是建映射，真正读盘发生在第一次 decode 的页错误里 —— 2GB 权重按 4KB 页
    // 随机触发，真机上实测 13 秒。POSIX_FADV_WILLNEED 是异步的顺序预读，和 mmap
    // 共用同一份 page cache，能把这段时间压掉一大截（具体看闪存，UFS 3.x 通常能到
    // 1 GB/s 以上）。失败了也不影响功能，只是回到「按需缺页」。
    {
        const int fd = ::open(path.c_str(), O_RDONLY);
        if (fd >= 0) {
            ::posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED);
            ::close(fd);
        }
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpuLayers;

    // **纯 CPU 时必须把设备列表钉死成「只有 CPU」**，这是这次真机事故的根因修复。
    //
    // llama_model_params.devices 的语义是「卸载可用的设备集合，NULL = 全部可用设备」。
    // 传 NULL 时，v0.7.0 会把 Vulkan 设备也放进 model.devices —— 而 context 的
    // compute buffer 是**按 model.devices 建**的，与 n_gpu_layers 无直接关系。
    // 真机铁证（2026-10-29 13:13，明确选了「关闭 GPU」）：
    //   nativeInit ... gpu=0
    //   [ggml] ~llama_context: Vulkan0 compute buffer size of 25.0747 MiB, does not match...
    //   llama_decode 抛出异常（纯 CPU）：vk::Device::createComputePipeline: ErrorUnknown
    // 也就是说「关掉 GPU」只关掉了权重卸载，没关掉 Vulkan 参与计算；
    // Adreno 740 上 q4_k 的管线建不出来，于是纯 CPU 也跟着一起死（还会卡住 45 秒）。
    //
    // 这不是配置问题，是「设备列表不设就等于全都要」。显式只给 CPU，Vulkan 连
    // buffer 都不会被分配，pipeline 更不会创建 —— CPU 路径从此与 Vulkan 彻底隔离。
    static ggml_backend_dev_t cpuOnlyDevs[2] = {nullptr, nullptr};
    if (gpuLayers <= 0) {
        cpuOnlyDevs[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (cpuOnlyDevs[0] != nullptr) {
            mparams.devices = cpuOnlyDevs;
            LOGI("设备列表：仅 CPU（Vulkan 不参与本次加载的任何 buffer 分配）");
        } else {
            LOGW("没有可用的 CPU 后端设备，设备列表保持默认（可能退回全部设备）");
        }
    }
    // 内存映射：启动快、峰值可控。
    //
    // v0.7.0 把 use_mmap/use_mlock 两个布尔合并成 load_mode 枚举，并额外引入
    // lazy_mode（按需读 tensor 的行，而非整块缺页）。lazy_mode 正是我们那个
    // 「冷启动 13.4 秒」问题的对口开关，但它把缺页成本从加载阶段推到了首次
    // decode 阶段——首字延迟会变难看。先保持 OFF（=旧行为，一次读全），
    // 等新版基线数据出来再单独评估，不要和新版本一起上，否则回归了分不清是谁的锅。
#if AK_LLAMA_MEMORY_API
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.lazy_mode = LLAMA_LAZY_MODE_OFF;
#else
    mparams.use_mmap = true;
#endif
    // 崩溃前要知道「崩在哪个组合上」：模型多大、ctx 多长、开了几层 GPU。
    // 这三项决定了复现难度——同一个模型 ctx=2048 和 ctx=16384 完全是两件事。
    {
        const size_t slash = path.find_last_of('/');
        const char* base = slash == std::string::npos ? path.c_str() : path.c_str() + slash + 1;
        ak_crash_context(base, nCtx, nThreads, gpuLayers);
    }
    ak_stage_set(AK_STAGE_MODEL_LOAD);
    inst->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!inst->model) {
        LOGE("llama_model_load_from_file 失败: %s", path.c_str());
        delete inst;
        return 0;
    }
    inst->vocab = llama_model_get_vocab(inst->model);
    if (!inst->vocab) {
        LOGE("llama_model_get_vocab 返回空，模型词表不可用: %s", path.c_str());
        llama_model_free(inst->model);
        delete inst;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_threads = nThreads;
    // prefill 线程数 = 生成线程数。**不要 +2。**
    //
    // 之前这里写 `min(hwCores, nThreads+2)`，理由是「prefill 是纯算力，多用两个核净赚」。
    // 真机 2026-10-29 13:49（骁龙 8 Gen 2，8 核 = 4 大 + 4 小）把这个假设打穿了：
    //   生成（n_threads=4，全大核）：110 ms/token → 8~9.6 tok/s
    //   prefill（n_threads_batch=6）：65 token 19719 ms → 3.3 tok/s
    // 多出来的两个线程落在 Cortex-A510 小核上：barrier 每次都要等最慢的那个，
    // 小核算 Q4_K 的速度约为大核 1/3，于是整批被拖慢到比逐 token 生成还慢。
    // 「核多就快」只对同构多核成立，对手机的大小核（且 ggml 用 spin-wait barrier）是反的。
    cparams.n_threads_batch = nThreads;
    // n_batch = 256：一次 llama_decode 吃进去的 token 上限。
    //
    // 这个值不影响吞吐（llama.cpp 内部本来就按 n_ubatch 分趟算），但它决定两件事：
    //   1) 进度粒度 —— 慢设备上 512 token 可能要半分钟，用户盯着空白无从判断死活；
    //   2) 中断粒度 —— 配合 abort 回调后，停止最迟在一个 ubatch 内生效。
    // 256 在「每行日志别太碎」与「别让用户干等」之间取的折中。
    //
    // **安全模式的上限从 64 提到 128。**
    //
    // 64 是 1.468 定的，当时的判据是「纯 CPU + prefill → SIGABRT，VmRSS 贴到进程上限」。
    // 现在根因已经查清：那些 SIGABRT 是 Vulkan 的 q4_k 管线在每个 ubatch 上抛异常
    // （见 W-89），不是内存。把一个 90 token 的 prompt 切成 64 + 26 两块，等于多付一次
    // 整批的固定开销——真机实测第一块 19.7 秒、第二块 18 秒，总 37.8 秒。
    // 128 让常见 prompt 一次算完，内存余量仍然充足（3B 的 128 ubatch 中间张量约几十 MB）。
    const int batchCap = g_safeMode.load() ? 128 : 256;
    cparams.n_batch = std::min(nCtx, batchCap);
    // 物理批跟 batchCap 走，不再写死 512。
    //
    // 早先的注释说「固定 512 缓存局部性最好」，但那是**速度**视角；内存视角下
    // 逻辑批 64、物理批 512，建图仍然按 512 算，等于没降。两处必须同口径。
    cparams.n_ubatch = std::min<uint32_t>(cparams.n_batch, static_cast<uint32_t>(batchCap));
    // 关掉自带的性能计时：它每次都读时钟计数，纯 CPU 上这部分开销白付。
    cparams.no_perf = true;
    // 中断回调：让 decode 内部（而不只是分块边界）能立刻停下。
    cparams.abort_callback = &abortCb;
    cparams.abort_callback_data = inst;
    // FlashAttention 先关掉。
    //
    // 理由不是「不好」，而是「先隔离变量」：旧版（b4600）的 CPU FA 路径按 head_dim 与
    // 量化类型分派，个别组合会退回普通注意力（只打 warning），个别组合直接
    // 让 decode 返回非 0 —— 后者在用户侧的表现就是「一个字都不出」。
    // 先把「能稳定出字」做实，等真机数据说明生成速度确实是瓶颈，再按机型开。
    //
    // v0.7.0 把布尔开关换成了三态枚举（AUTO/DISABLED/ENABLED），这里显式选 DISABLED
    // 而不是留 AUTO：AUTO 会按设备能力自作主张，回归时变量就多了一个。
#if AK_LLAMA_MEMORY_API
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
#else
    cparams.flash_attn = false;
#endif
    inst->n_batch = cparams.n_batch;
    inst->n_threads = nThreads;
    // KV cache 就在这一步按 nCtx 分配；GPU 开启时它和权重抢同一块显存。
    ak_stage_set(AK_STAGE_CTX_CREATE);
    inst->ctx = llama_init_from_model(inst->model, cparams);
    if (!inst->ctx) {
        LOGE("llama_init_from_model 失败");
        llama_model_free(inst->model);
        delete inst;
        return 0;
    }

    // 装载全部成功，回到空闲态：之后再崩就该归到推理/其他模块，而不是加载。
    ak_stage_set(AK_STAGE_IDLE);

    char desc[256] = {0};
    llama_model_desc(inst->model, desc, sizeof(desc));
    LOGI("nativeInit 成功: %s (n_ctx=%d, n_batch=%d, threads=%d/%d, n_vocab=%d, bos=%d, eos=%d)",
         desc, static_cast<int>(llama_n_ctx(inst->ctx)), inst->n_batch,
         cparams.n_threads, cparams.n_threads_batch,
         llama_vocab_n_tokens(inst->vocab),
         llama_vocab_bos(inst->vocab), llama_vocab_eos(inst->vocab));

    // ---- 冷启动预热：把「权重第一次被触碰」的一次性成本付在加载阶段 ----
    //
    // 1.446 真机日志（同一台设备、同一个 3B Q4_K_M）：
    //   模型加载 2993 ms（mmap 只建映射，没有读数据）
    //   → 首次 decode 1 token 13436 ms
    //   → 紧接着再 decode 1 token 170 ms
    // 这 13 秒是 2GB 权重缺页 + Q4_K 重排，跟线程数、prompt 长度都无关，而且**只付一次**。
    // 但它付在了用户最难受的位置：发完消息、看着空白的第 13 秒。
    //
    // 放到这里之后，用户看到的是「模型就绪，耗时 16 秒」，然后第一句话立刻开始出字。
    // 顺带把 bos 记进账本：下一轮 prompt 也以 bos 开头，公共前缀正好复用这 1 个 token。
    {
        const llama_token bos = llama_vocab_bos(inst->vocab);
        if (bos >= 0) {
            const long long t0 = nowMs();
            if (decodeExplicit(inst, &bos, 1, 0)) {
                inst->kvTokens.push_back(bos);
                LOGI("冷启动预热完成：1 token / %lld ms（含权重首次载入与重排，一次性成本）",
                     nowMs() - t0);
            } else {
                // 失败必须清 KV：llama_decode 返回非 0 时，KV 里可能已经留下 pos 0 的条目，
                // 而账本（kvTokens）仍是空的。下一轮 prefill 从 pos0 起，就会撞上
                //   [ggml] init: the tokens of sequence 0 ... inconsistent sequence positions
                // 然后**每一轮**都 rc=-1 —— 用户看到的是「模型不兼容」，实际是我们自己的脏状态。
                // 真机 2026-10-29 13:13:16 就是这条链：预热失败 → 留脏 → 之后全废。
                resetKv(inst, "冷启动预热失败");
                LOGE("冷启动预热失败（decode 返回非 0），KV 已清空，由首轮 prefill 重新开始");
            }
        } else {
            LOGI("模型没有 bos token，跳过冷启动预热");
        }
    }
    return reinterpret_cast<jlong>(inst);
}

/** 释放实例（幂等）。 */
JNIEXPORT void JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeFree(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return;
    if (inst->ctx) llama_free(inst->ctx);
    if (inst->model) llama_model_free(inst->model);
    delete inst;
    LOGI("nativeFree 完成");
}

/** 请求停止当前生成（协程取消时由 Kotlin 调用；生成循环每 token 检查）。 */
JNIEXPORT void JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* inst = reinterpret_cast<LlamaInstance*>(handle);
    if (inst) inst->stop.store(true);
}

/**
 * 取走「GPU 后端抛过异常」这件事，并把标记清空（取走即消费）。
 *
 * 返回空串 = 没发生过 GPU 故障；非空 = 故障原因（vk::... 那串）。
 *
 * 为什么要「取走」而不是「查询」：Kotlin 侧拿到原因后会关 GPU、重载 CPU、重试这一轮。
 * 若不消费，重试那一轮（新实例，但旧句柄可能还在）会读到上一轮的残留标记，
 * 于是把一次已经修好的重试判成「又炸了」，陷入无限降级。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeTakeGpuFault(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return env->NewStringUTF("");
    std::lock_guard<std::mutex> g(inst->faultMu);
    if (!inst->gpuFault.load()) return env->NewStringUTF("");
    std::string reason = inst->faultReason;
    inst->faultReason.clear();
    inst->gpuFault.store(false);
    return env->NewStringUTF(reason.c_str());
}

/**
 * 这个 APK 有没有编进 GPU（Vulkan）后端。
 *
 * 为什么由 native 回答而不是 Kotlin 里写个常量：常量需要人手动同步。一旦 CMake 里
 * 关掉了 Vulkan 而常量忘了改，界面就会显示「GPU 可用」、点下去转三分钟再退回 CPU。
 * 这里由编译器决定，改不动，也不会忘。
 *
 * 注意它回答的是「编进去了没有」，不是「这台设备能不能用」——后者要看设备的
 * Vulkan 版本（ggml-vulkan 要求 1.2）与 GPU 家族，判定在 LocalDeviceRules 里。
 */
JNIEXPORT jboolean JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeGpuBackend(
    JNIEnv* /*env*/, jobject /*thiz*/) {
#ifdef AK_HAS_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}


/**
 * 这台机器上 ggml 认得的后端设备清单（给「运行日志」用，也是 GPU 排查的唯一入口）。
 *
 * 返回空串表示注册表初始化失败（连 CPU 都没有，属于不可能发生的情况）。
 */
JNIEXPORT jstring JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeGpuProbe(
    JNIEnv* env, jobject /*thiz*/) {
    static std::once_flag backendOnce;
    std::call_once(backendOnce, [] { llama_backend_init(); });
    const std::string s = gpuDeviceSummary();
    LOGI("GPU 探测：%s", s.c_str());
    return env->NewStringUTF(s.c_str());
}

/**
 * 系统的 Vulkan 实例版本（VK_MAKE_VERSION 打包值，如 1.3.275 → 4206875），0 = 没有 loader。
 *
 * 为什么不用 PackageManager 的 FEATURE_VULKAN_HARDWARE_VERSION：
 * 那个 FeatureInfo.version 是 **int**，而不同 Android 版本对它的编码并不统一
 * （打包值 / 十进制 / 主次版本拼数三种写法都出现过）。第一版按字符串读，CI 直接
 * 编译不过（Type mismatch: Int? vs String?）——那其实是好事，它拦下了一次静默错判。
 *
 * 这里换成「问 loader 自己」：dlopen libvulkan.so → vkEnumerateInstanceVersion。
 * 这恰好也是 ggml-vulkan 自己的判据（ggml_vk_instance_init 里就是
 * `vk::enumerateInstanceVersion() < VK_API_VERSION_1_2 → throw`），
 * 所以我们预测的结果与它实际的行为是同一个量，不会出现「我们判可用、它抛异常」。
 *
 * 只 dlopen + 调一个返回常量的函数，不建 instance、不碰 device，零崩溃风险。
 */
JNIEXPORT jint JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeVulkanVersion(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    void* h = ::dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        LOGI("Vulkan 探测：dlopen libvulkan.so 失败，按无 Vulkan 处理");
        return 0;
    }
    using EnumerateVersionFn = uint32_t (*)(uint32_t*);
    uint32_t version = 0;
    // vkEnumerateInstanceVersion 是 1.1 才有的入口；1.0 的 loader 上 dlsym 拿不到，
    // 那种情况按 1.0 处理（ggml 也同样把 nullptr 当成「1.0，不够」）。
    auto fn = reinterpret_cast<EnumerateVersionFn>(::dlsym(h, "vkEnumerateInstanceVersion"));
    if (fn != nullptr) {
        const uint32_t ok = fn(&version);   // 返回 VkResult，VK_SUCCESS = 0
        if (ok != 0) version = 0;
    } else {
        version = (1u << 22);               // VK_MAKE_VERSION(1, 0, 0)
    }
    ::dlclose(h);
    LOGI("Vulkan 探测：实例版本打包值=%u（%u.%u.%u）",
         version, version >> 22, (version >> 12) & 0x3FFu, version & 0xFFFu);
    return static_cast<jint>(version);
}

/**
 * 取 native 诊断文本（最近 80 行，含 LOGI/LOGE）。
 *
 * 每行格式 `#<seq>|<正文>`。seq 让调用方只消费「本轮新产出的行」——不加这个，
 * 进程级环形缓冲会把上一轮的预热/prefill 行原样复读一遍（见 diagLines 的注释）。
 * 只要正文的旧解析方式（contains / find 正则）都还不受影响。
 *
 * Kotlin 在生成结束后无条件读一次写进「运行日志」——native 的 logcat 用户在手机上
 * 看不到，而「本地模型不回话」的全部线索都在这层。这个函数只读取快照，不清空。
 */
JNIEXPORT jstring JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeDiagnostics(
    JNIEnv* env, jobject /*thiz*/) {
    std::string out;
    {
        std::lock_guard<std::mutex> lk(diagMu);
        // 只给「本次加载之后」的行：缓冲是进程级的，全量倒出会把上一轮的预热/prefill
        // 混进本轮诊断，用户（和我）会拿两次不同配置的耗时做对比，越看越乱。
        const long long floor = g_loadSeq.load();
        out.reserve(diagLines.size() * 96);
        for (const auto& entry : diagLines) {
            if (entry.first <= floor) continue;
            out += '#';
            out += std::to_string(entry.first);
            out += '|';
            out += entry.second;
            out += '\n';
        }
    }
    return env->NewStringUTF(out.c_str());
}

/**
 * 当前诊断序号（下一条日志会是 seq+1）。
 *
 * 调用方在**开始一轮生成前**取一次，之后只看 seq 更大的行，就等于「本轮 native 日志」。
 * 返回 0 表示缓冲还没写过任何行（首次加载前）。
 */
JNIEXPORT jlong JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeLogSeq(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(g_diagSeq.load());
}

/**
 * 应用模型自带的对话模板（Jinja），把 (role, content) 数组拼成 prompt 字符串。
 * 本地模型 prompt 格式各不相同，交给模板而不是 Kotlin 手拼——这是体验不碎片化的关键。
 */
JNIEXPORT jstring JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeChatTemplate(
    JNIEnv* env, jobject /*thiz*/, jlong handle,
    jobjectArray roles, jobjectArray contents) {
    auto* inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->model) return env->NewStringUTF("");

    const char* tmpl = llama_model_chat_template(inst->model, nullptr);
    if (!tmpl || !*tmpl) {
        // 模型没有内嵌模板：退回最朴素的 role\ncontent 拼接（ChatML 风格兜底）
        LOGI("模型无内嵌对话模板，使用 ChatML 兜底");
        tmpl = "{% for message in messages %}{{ message.role }}\n{{ message.content }}\n{% endfor %}";
    }

    jsize n = env->GetArrayLength(roles);
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    std::vector<std::string> roleStore(static_cast<size_t>(n));
    std::vector<std::string> contentStore(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto* rj = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto* cj = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, i));
        roleStore[i] = jstringToStd(env, rj);
        contentStore[i] = jstringToStd(env, cj);
        msgs[i].role = roleStore[i].c_str();
        msgs[i].content = contentStore[i].c_str();
        env->DeleteLocalRef(rj);
        env->DeleteLocalRef(cj);
    }

    // 第一遍：取所需长度；第二遍：真正写出
    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
    if (need < 0) {
        LOGE("llama_chat_apply_template 取长度失败: %d", need);
        return env->NewStringUTF("");
    }
    std::vector<char> buf(static_cast<size_t>(need) + 1, '\0');
    int32_t written = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (written < 0) {
        LOGE("llama_chat_apply_template 失败: %d", written);
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(buf.data());
}

/**
 * 阻塞式生成：tokenize → 分块 decode → 采样循环 → token 回调 → 返回完整文本。
 *
 * 采样参数固定一档（top-p 0.9 / temp 0.7 / dist seed 1234）——本地模型是自用场景，
 * 不为调参开 API 面；要调就在 Kotlin 侧加参数。
 *
 * @param listener TokenListener 对象，逐 token 回调 onToken(String)；可为空
 * @return 完整生成文本；空字符串且日志有 error 表示失败
 */
JNIEXPORT jstring JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring prompt,
    jint maxTokens, jobject listener) {
    auto* inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->ctx || !inst->vocab) {
        // 句柄还在但上下文没了（重建失败 / 已被释放）：必须留一行，否则上层只看到
        // 「0 字」而完全不知道原因。
        LOGE("nativeGenerate 被调用但实例不可用（inst=%p ctx=%p vocab=%p）",
             static_cast<void*>(inst),
             inst ? static_cast<void*>(inst->ctx) : nullptr,
             inst ? static_cast<const void*>(inst->vocab) : nullptr);
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> genLock(inst->genMu);
    inst->stop.store(false);

    // 缓存回调方法（每次调用取一次，开销可忽略）
    jmethodID onToken = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!onToken) LOGE("找不到 TokenListener.onToken 方法");
    }

    std::string text = jstringToStd(env, prompt);

    // 1) tokenize（add_bos=true, parse_special=true）
    std::vector<llama_token> tokens;
    if (!tokenizeAll(inst->vocab, text, tokens)) {
        // 真失败才走到这里。顺手把「需要多少 token」探出来一起打：负数返回值
        // 与真正失败的区分全靠这一行，否则下次又要从头猜。
        const int32_t probe = llama_tokenize(inst->vocab, text.c_str(),
                                             static_cast<int32_t>(text.size()),
                                             nullptr, 0, true, true);
        LOGE("tokenize 失败（输入 %zu 字节，所需 token 探测=%d，开头：%.80s）",
             text.size(), probe, text.c_str());
        return env->NewStringUTF("");
    }
    LOGI("prompt 长度：%zu 字节 → %zu token", text.size(), tokens.size());
    if (tokens.size() < 2) {
        // 模板渲染出空/极短 prompt：位置、logits、采样全都还成立，但模型看到的
        // 是一个「没有内容的对话」，通常立刻吐结束符 —— 用户侧就是「一个字不出」。
        LOGE("prompt 异常地短（%zu token），对话模板可能没渲染出内容：%.120s",
             tokens.size(), text.c_str());
    }

    const int nCtx = static_cast<int>(llama_n_ctx(inst->ctx));

    // 2) 超窗保护：历史长到塞满窗口时丢掉最旧的中段（保留首 token 的特殊标记）。
    //    不这么做 llama_decode 直接失败，用户侧表现是「发了消息毫无反应」。
    //    截断后前缀必然对不上，强制全量重算。
    //    预算按「输入留 3/4、生成留 1/4」切，两边都不至于被对方饿死。
    bool reuseDisabled = false;
    const int genReserve = std::min(static_cast<int>(maxTokens), std::max(64, nCtx / 4));
    const int inputCap = nCtx - genReserve - 8;
    if (inputCap > 0 && static_cast<int>(tokens.size()) > inputCap) {
        std::vector<llama_token> trimmed;
        trimmed.reserve(static_cast<size_t>(inputCap));
        trimmed.push_back(tokens.front());
        trimmed.insert(trimmed.end(),
                       tokens.end() - (inputCap - 1), tokens.end());
        LOGI("prompt 超窗：%zu → %d token（丢弃最旧的中段）", tokens.size(), inputCap);
        tokens.swap(trimmed);
        reuseDisabled = true;
    }
    // 收下输入后还剩多少窗口，就最多生成多少（不再越过窗口，否则 decode 必失败）。
    // 至少留 1：早期写成 max(16, …)，窗口吃满时硬生成 16 个 token 反而必然 decode 失败。
    const int genBudget = std::max(1, std::min(static_cast<int>(maxTokens),
                                                nCtx - static_cast<int>(tokens.size()) - 1));

    // 3) 增量 prefill：与上一轮已进 KV 的 token 求最长公共前缀
    size_t common = 0;
    if (!reuseDisabled) {
        const size_t lim = std::min(tokens.size(), inst->kvTokens.size());
        while (common < lim && tokens[common] == inst->kvTokens[common]) common++;
    }
    // 新 prompt 比账本短或在中途岔开：把 KV 回退到公共前缀末尾。
    // 只删不补（common == kvTokens.size() 时无需动作），p1 < 0 表示删到末尾。
    if (common < inst->kvTokens.size()) {
        if (!akKvSeqRm(inst->ctx, 0, static_cast<llama_pos>(common), -1)) {
            // 个别模型（滑窗/混合注意力）不支持部分删除：整块清掉重来，宁可慢也不能脏
            LOGE("KV 部分回退失败（common=%zu），改为整块重算", common);
            resetKv(inst, "部分回退失败");
            common = 0;
        } else {
            inst->kvTokens.resize(common);
        }
    }
    // 「新 prompt 恰好是 KV 的严格前缀」——用户重发同一条、或只是重进了同一个会话。
    //
    // 此时 common == tokens.size()，下面的 decodeFrom 一个 token 都不会 decode，
    // 采样器读到的是上一轮留在内存里的旧 logits：界面会把上一轮那句话再吐一遍。
    // 这种「内容对、但来源错」的 bug 极难查（日志里 prefill 用时 0ms、复用 100%）。
    // 回退一个 token 让模型真正算一次，代价是一个 token，换来「状态只由 KV 决定」。
    if (common == tokens.size() && common > 0) {
        common--;
        // 账本与 KV 同步回退，不留「账本比 KV 少一个但那里的位置仍有旧值」的歧义。
        inst->kvTokens.resize(common);
        akKvSeqRm(inst->ctx, 0, static_cast<llama_pos>(common), -1);
    }

    // common == 0 表示这一轮要从头算。先花 1 个 token 探一下引擎本身的吞吐，
    // 把「引擎慢」和「prompt 长」在日志里彻底分开；探针成功时 tokens[0] 已进 KV，
    // 所以下面的起点顺延一位。（KV 里已有前缀时不做探针，那 1 个 token 会白算。）
    size_t prefillFrom = common;
    if (prefillFrom == 0 && tokens.size() > 1) {
        if (!warmupDecode(inst, tokens)) {
            return env->NewStringUTF("");
        }
        prefillFrom = 1;
    }

    const long long prefillStart = nowMs();
    if (!decodeFrom(inst, tokens, prefillFrom, prefillStart)) {
        // 用户点了停止 → 这不是失败，别去走三级自愈（那会把「停止」变成「再算两遍」）。
        if (inst->stop.load()) {
            LOGI("prefill 被用户停止，本回合直接结束（未产出文本）");
            return env->NewStringUTF("");
        }
        // GPU 后端自己炸了：这时候三级自愈全是白费——分块再小、上下文再重建，
        // 下次 still 是同一个 Vulkan 驱动抛同一个异常。直接收手，交给 Kotlin 侧
        // 关 GPU 重载 CPU 再重试，比在这里把同一个异常再踩两遍快得多。
        if (inst->gpuFault.load()) {
            LOGE("prefill 阶段 GPU 后端抛出异常，放弃本轮（不重试，交由上层降级 CPU）");
            return env->NewStringUTF("");
        }
        // 三级自愈，全部在 native 内完成，用户侧只体现为「这一次慢一点」：
        //   1) KV 已被 decodeFrom 重置 —— 排除账本脏；
        //   2) 单块上限降到 64 —— 排除「单批太大被后端拒绝」；
        //   3) 重建上下文 —— 排除 KV/后端状态损坏。
        // 真机上「一个字都不出」大多是 1 或 2 能救回来的情况；救不回来也要留下
        // 明确日志，而不是让 UI 空着。
        //
        // 这里原来写的是「缩到 512」、也真的 min(n_batch, 512)——安全模式下 n_batch 本来就是
        // 64，等于**什么都没做却声称做了**。日志和实际行为必须一致，否则下次排查又要在
        // 「明明缩了还失败」上绕圈。
        LOGE("prefill 失败，降级重试：单块上限降到 64 token（当前 %d）", inst->n_batch);
        inst->n_batch = std::min(inst->n_batch, 64);
        const long long retryStart = nowMs();
        if (!decodeFrom(inst, tokens, 0, retryStart)) {
            if (inst->stop.load()) {
                LOGI("prefill 重试期间被用户停止，本回合结束");
                return env->NewStringUTF("");
            }
            LOGE("小分块仍失败，重建上下文后重试");
            if (!rebuildContext(inst)) {
                return env->NewStringUTF("");
            }
            if (!decodeFrom(inst, tokens, 0, nowMs())) {
                LOGE("prefill 三次尝试均失败，本回合无法生成");
                return env->NewStringUTF("");
            }
        }
        common = 0;
    }
    const long long prefillMs = nowMs() - prefillStart;
    LOGI("prefill 完成：复用 %zu / 共 %zu token，补算 %zu，耗时 %lld ms（%.1f tok/s）",
         common, tokens.size(), tokens.size() - common, prefillMs,
         prefillMs > 0 ? (tokens.size() - common) * 1000.0 / static_cast<double>(prefillMs) : 0.0);

    // 3) 采样链：重复惩罚 → top-k 40 → top-p 0.9 → min-p 0.05 → 温度 0.7 → 随机抽取
    //
    // 顺序有讲究，不是随手排的：惩罚必须先于截断，否则被压过的 logits 再参与概率
    // 排序，等于没罚；温度必须放最后，否则前面的阈值是按未缩放的概率算的。
    //
    // 两道「别让 3B 跑飞」的保险：
    //   - penalties(1.15)：小模型中文短对话极易复读同一句，且复读时不吐结束符，
    //     用户侧就是「一直重复一句」+「永远不结束」；
    //   - top-k 40 / min-p 0.05：把长尾的胡话切掉。只开 top-p 时，概率分布在
    //     300 token 的长尾上仍可能累计到 10%，小模型最容易在这些位置翻车。
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, akSamplerPenalties(inst->vocab, 64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // 4) 生成循环：每 token 检查停止标志；遇到 EOS 或窗口写满即停
    std::string out;
    out.reserve(1024);
    char piece[512];
    std::string pending;   // 跨 token 的 UTF-8 残片：凑满一个码点再回调，避免半个汉字变乱码
    int produced = 0;
    int firstTok = -1;
    const long long genStart = nowMs();
    for (int i = 0; i < genBudget; i++) {
        if (inst->stop.load()) {
            LOGI("生成被用户停止（已产出 %zu 字符）", out.size());
            break;
        }
        if (static_cast<int>(inst->kvTokens.size()) >= nCtx) {
            LOGI("上下文窗口写满（%d），停止生成", nCtx);
            break;
        }
        llama_token tok = llama_sampler_sample(smpl, inst->ctx, -1);
        if (firstTok < 0) firstTok = tok;
        // 用 is_eog 而不是 `== llama_vocab_eos`：Qwen2.5 这类模型有多个结束符
        // （<|endoftext|> 与 <|im_end|>），只比 eos 会漏掉其中一个，漏了就一直
        // 往下吐 padding 或重复开头，看起来像「模型在原地打转」。
        if (llama_vocab_is_eog(inst->vocab, tok)) {
            LOGI("命中结束符 token=%d（第 %d 步）", tok, i);
            break;
        }

        // token → 文本片段。缓冲区不够时按返回的所需长度重试一次
        // （原实现直接 continue，会让该 token 既不进 KV 也不记账）。
        int pn = llama_token_to_piece(inst->vocab, tok, piece, sizeof(piece), 0, true);
        std::string s;
        if (pn < 0) {
            std::vector<char> big(static_cast<size_t>(-pn));
            pn = llama_token_to_piece(inst->vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, true);
            if (pn > 0) s.assign(big.data(), static_cast<size_t>(pn));
        } else if (pn > 0) {
            s.assign(piece, static_cast<size_t>(pn));
        }
        out += s;
        pending += s;
        const size_t safeLen = utf8SafeLen(pending);
        if (safeLen > 0 && onToken && listener) {
            std::string chunk = pending.substr(0, safeLen);
            pending.erase(0, safeLen);
            jstring js = env->NewStringUTF(chunk.c_str());
            if (js) {
                env->CallVoidMethod(listener, onToken, js);
                env->DeleteLocalRef(js);
            }
        }
        produced++;

        // 每 32 token 打一行：长回答（本地模型常常要吐几百 token）在中途就能看见
        // 「在动、多快」，而不是只有结束时那行汇总。用户中途点停止也有据可查。
        if (produced % 32 == 0) {
            const long long el = nowMs() - genStart;
            LOGI("生成进度：%d token / %zu 字符，%lld ms（%.1f tok/s）",
                 produced, out.size(), el, el > 0 ? produced * 1000.0 / el : 0.0);
        }

        // 下一个 token：显式 pos（账本长度即下一位），成功后才记入账本
        if (!decodeExplicit(inst, &tok, 1, static_cast<llama_pos>(inst->kvTokens.size()))) {
            resetKv(inst, "生成中 decode 失败");
            if (inst->gpuFault.load()) {
                LOGE("生成到第 %d 个 token 时 GPU 后端抛出异常，本轮提前结束（已产出 %zu 字符）",
                     produced, out.size());
            }
            break;
        }
        inst->kvTokens.push_back(tok);
    }
    // 收尾：残片不足一个码点时也要吐出去（多半是非法 UTF-8，原样交给上层）
    if (!pending.empty() && onToken && listener) {
        jstring js = env->NewStringUTF(pending.c_str());
        if (js) {
            env->CallVoidMethod(listener, onToken, js);
            env->DeleteLocalRef(js);
        }
    }

    const long long genMs = nowMs() - genStart;
    llama_sampler_free(smpl);
    LOGI("生成完成：%d token / %zu 字符，%lld ms（%.1f tok/s）",
         produced, out.size(), genMs, genMs > 0 ? produced * 1000.0 / genMs : 0.0);
    // 空输出是「界面没反应」的唯一来源，必须留下可判读的原因：
    // 首个 token 就是结束符 → 模板/prompt 问题；produced>0 但 0 字符 → token 全被
    // 当成特殊 token 丢掉了（词表不匹配）；produced=0 且没命中结束符 → 循环没进。
    if (out.empty()) {
        LOGE("生成为空：produced=%d 首个token=%d 预算=%d promptTokens=%zu 已复用=%zu prefill=%lldms",
             produced, firstTok, genBudget, tokens.size(), common, prefillMs);
    }
    return env->NewStringUTF(out.c_str());
}

/**
 * 开关安全模式（必须在 nativeInit 之前调用，进程级生效）。
 *
 * 单独一个入口而不是往 nativeInit 加参数：nativeInit 的签名是 Kotlin 与 native
 * 的契约，加参数就得同时改调用链（engine.acquire → LocalLlamaClient），而安全模式
 * 是「配置层」的东西，跟模型路径无关，混在一个签名里只是噪音。
 */
JNIEXPORT void JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeSetSafeMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    g_safeMode.store(on == JNI_TRUE);
    LOGI("安全模式：%s（n_batch 上限 %d）", on == JNI_TRUE ? "开" : "关",
         on == JNI_TRUE ? 64 : 256);
}

}  // extern "C"
