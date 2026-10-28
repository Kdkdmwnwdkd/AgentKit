// AgentKit 本地推理 JNI 桥（净室重写，不复制任何第三方 JNI 封装）。
//
// 只做五件事：初始化模型/上下文、释放、对话模板应用、阻塞式生成（带 token 回调）、
// 停止标志。协议细节（消息→模板→采样）都在 Kotlin 侧 LocalLlamaClient 编排。
//
// llama.cpp 本体为 MIT 许可证（ggml-org/llama.cpp），源码在构建时由脚本拉取固定
// tag b4600 到本目录的 llama.cpp/ 子目录，不进 git 仓库（见 CMakeLists.txt 注释）。
// MIT 许可全文随仓库提交于 llama.cpp-LICENSE.txt。

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

#include "llama.h"

#define LOG_TAG "llama-jni"

namespace {

/**
 * 进程内诊断回路：最近 80 条 native 日志。
 *
 * 存在的理由很实在——native 的 logcat 用户在手机上看不到，而「本地模型一句话都不回」
 * 这类问题的全部线索（tokenize 成不成功、decode 返回几、prefill 花了多久、首个 token
 * 是什么）都只在这层。把这些行回传给 Kotlin，用户翻「运行日志」就能把原因带给我，
 * 不需要连电脑抓 logcat。
 */
std::mutex diagMu;
std::deque<std::string> diagLines;

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
    std::lock_guard<std::mutex> lk(diagMu);
    diagLines.emplace_back(buf, static_cast<size_t>(n < static_cast<int>(sizeof(buf)) ? n : static_cast<int>(sizeof(buf)) - 1));
    while (diagLines.size() > 80) diagLines.pop_front();
}

#define LOGI(...) diagPrintf(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGE(...) diagPrintf(ANDROID_LOG_ERROR, __VA_ARGS__)

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
    llama_kv_cache_clear(inst->ctx);
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

/** prefill 单块上限（token）。见 decodeFrom 里对「为什么是 64」的说明。 */
constexpr int PREFILL_CHUNK = 64;

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
 * 超过阈值且线程还能降时，先怀疑线程争用：8 核上开 8 线程跑极小 batch，同步开销
 * 常常比并行收益还大。降一次线程重建上下文重试，两次都慢就认命，但结论写进日志。
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

    if (ms > WARMUP_SLOW_MS && inst->n_threads > 4) {
        const int lower = std::max(4, inst->n_threads / 2);
        LOGE("预热 %lld ms 过慢，线程 %d → %d 重建上下文后重试一次",
             ms, inst->n_threads, lower);
        inst->n_threads = lower;
        if (!rebuildContext(inst)) return false;
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
    const int rc = llama_decode(inst->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        if (inst->stop.load()) {
            // 中止不是故障：llama_decode 可能已经写入了前面若干个 ubatch 才返回非 0。
            // 此时账本没动（成功才记账），所以把 KV 回退到账本长度就能对齐——
            // **不要清空**，前面算好的几千 token 下一轮还能复用。
            llama_kv_cache_seq_rm(inst->ctx, 0, static_cast<llama_pos>(inst->kvTokens.size()), -1);
            LOGI("decode 被用户中止（rc=%d, n=%d, 账本 %zu token，KV 已对齐）",
                 rc, n, inst->kvTokens.size());
            return false;
        }
        LOGE("llama_decode 失败 rc=%d（n=%d, pos0=%d, KV 已占用=%zu, n_ctx=%d）",
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

    // 分块上限 64（而不是 n_batch 的 256）。
    //
    // 块大小不影响总吞吐（llama.cpp 内部本来就按 ubatch 分趟），但决定「第一行进度
    // 多久出现」。256 一块时，慢机上第一块本身就可能要一分钟以上，等于没有反馈，
    // 用户和日志都只能干等。64 一块让「它在动」在几秒内就成立。
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
            if (!inst->stop.load()) resetKv(inst, "prefill decode 失败");
            return false;
        }
        inst->kvTokens.insert(inst->kvTokens.end(), tokens.begin() + i, tokens.begin() + i + chunk);

        // 每个分块打一行进度。**这是真机上唯一能看见「它到底在干嘛」的地方**：
        // 之前只有一行结束日志，用户等到 500 秒仍是空白，分不清是「在算」还是「卡死」。
        // 同时给 tok/s —— 3B Q4 在手机 CPU 上 prefill 十几 tok/s 属正常，个位数就是异常。
        const long long spent = nowMs() - startedAt;
        const size_t done = i + chunk;
        LOGI("prefill 进度：%zu / %zu token，%lld ms（%.1f tok/s）",
             done, tokens.size(), spent,
             spent > 0 ? done * 1000.0 / static_cast<double>(spent) : 0.0);
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
    cp.n_threads_batch = inst->n_threads;
    cp.n_batch = std::min(inst->n_batch, std::min(nCtx, 256));
    cp.n_ubatch = std::min<uint32_t>(cp.n_batch, 512u);
    cp.no_perf = true;
    cp.abort_callback = &abortCb;
    cp.abort_callback_data = inst;
    cp.flash_attn = false;
    inst->ctx = llama_init_from_model(inst->model, cp);
    if (!inst->ctx) {
        LOGE("重建上下文失败（n_ctx=%d）", nCtx);
        return false;
    }
    return true;
}

}  // namespace

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
    LOGI("nativeInit: %s ctx=%d threads=%d gpu=%d", path.c_str(), nCtx, nThreads, nGpuLayers);

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

    auto* inst = new LlamaInstance();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;
    mparams.use_mmap = true;    // 内存映射：启动快、峰值可控
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
    // prefill（batch）与逐 token 生成的开销结构不同：生成是「读 2GB 权重出 1 个 token」，
    // 内存带宽受限于大核，堆线程只会抢带宽；prefill 是「读一遍权重算一批」，纯算力，
    // 把能用的核都拉上更快。之前两者都钉在 nThreads（大核数），长 prompt 的 prefill
    // 因此被白白拖慢一倍以上。
    const int hwCores = static_cast<int>(std::thread::hardware_concurrency());
    // prefill 线程数**不再上浮**到全部核心。
    //
    // 之前的写法是 max(nThreads, min(hwCores, 8))：用户设 6 线程，prefill 却跑 8 线程。
    // 手机 SoC 是 4 大 + 4 小的异构结构，8 线程意味着每一步都要等小核，而且 ggml 的
    // 线程池在「每线程分到的 token 很少」时几乎全花在同步上。真机上 158 token 的
    // prefill 卡 180 秒不出来，这个过度并行是必须排除的嫌疑。
    cparams.n_threads_batch = nThreads;
    // n_batch = 256：一次 llama_decode 吃进去的 token 上限。
    //
    // 这个值不影响吞吐（llama.cpp 内部本来就按 n_ubatch 分趟算），但它决定两件事：
    //   1) 进度粒度 —— 慢设备上 512 token 可能要半分钟，用户盯着空白无从判断死活；
    //   2) 中断粒度 —— 配合 abort 回调后，停止最迟在一个 ubatch 内生效。
    // 256 在「每行日志别太碎」与「别让用户干等」之间取的折中。
    cparams.n_batch = std::min(nCtx, 256);
    // 物理批固定 512 以内：ggml 的 kernel 在这个量级上缓存局部性最好，再大只是多占
    // 计算缓冲。逻辑批（n_batch）可以大于物理批，内部会分趟。
    cparams.n_ubatch = std::min<uint32_t>(cparams.n_batch, 512u);
    // 关掉自带的性能计时：它每次都读时钟计数，纯 CPU 上这部分开销白付。
    cparams.no_perf = true;
    // 中断回调：让 decode 内部（而不只是分块边界）能立刻停下。
    cparams.abort_callback = &abortCb;
    cparams.abort_callback_data = inst;
    // FlashAttention 先关掉。
    //
    // 理由不是「不好」，而是「先隔离变量」：b4600 的 CPU FA 路径按 head_dim 与
    // 量化类型分派，个别组合会退回普通注意力（只打 warning），个别组合直接
    // 让 decode 返回非 0 —— 后者在用户侧的表现就是「一个字都不出」。
    // 先把「能稳定出字」做实，等真机数据说明生成速度确实是瓶颈，再按机型开。
    cparams.flash_attn = false;
    inst->n_batch = cparams.n_batch;
    inst->n_threads = nThreads;
    inst->ctx = llama_init_from_model(inst->model, cparams);
    if (!inst->ctx) {
        LOGE("llama_init_from_model 失败");
        llama_model_free(inst->model);
        delete inst;
        return 0;
    }

    char desc[256] = {0};
    llama_model_desc(inst->model, desc, sizeof(desc));
    LOGI("nativeInit 成功: %s (n_ctx=%d, n_batch=%d, threads=%d/%d, n_vocab=%d, bos=%d, eos=%d)",
         desc, static_cast<int>(llama_n_ctx(inst->ctx)), inst->n_batch,
         cparams.n_threads, cparams.n_threads_batch,
         llama_vocab_n_tokens(inst->vocab),
         llama_vocab_bos(inst->vocab), llama_vocab_eos(inst->vocab));
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
 * 取 native 诊断文本（最近 80 行，含 LOGI/LOGE）。
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
        for (const auto& line : diagLines) {
            out += line;
            out += '\n';
        }
    }
    return env->NewStringUTF(out.c_str());
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
        if (!llama_kv_cache_seq_rm(inst->ctx, 0, static_cast<llama_pos>(common), -1)) {
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
        llama_kv_cache_seq_rm(inst->ctx, 0, static_cast<llama_pos>(common), -1);
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
        // 三级自愈，全部在 native 内完成，用户侧只体现为「这一次慢一点」：
        //   1) KV 已被 decodeFrom 重置 —— 排除账本脏；
        //   2) 分块缩到 512 —— 排除「单批太大被后端拒绝」；
        //   3) 重建上下文 —— 排除 KV/后端状态损坏。
        // 真机上「一个字都不出」大多是 1 或 2 能救回来的情况；救不回来也要留下
        // 明确日志，而不是让 UI 空着。
        LOGE("prefill 失败，降级重试：分块缩到 512");
        inst->n_batch = std::min(inst->n_batch, 512);
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
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.15f, 0.0f, 0.0f));
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

}  // extern "C"
