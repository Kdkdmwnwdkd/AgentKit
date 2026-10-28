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
    int n_batch = 2048;
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
 */
bool decodeFrom(LlamaInstance* inst, std::vector<llama_token>& tokens, size_t from) {
    for (size_t i = from; i < tokens.size(); i += static_cast<size_t>(inst->n_batch)) {
        size_t chunk = std::min(static_cast<size_t>(inst->n_batch), tokens.size() - i);
        if (!decodeExplicit(inst, tokens.data() + i, static_cast<int32_t>(chunk),
                            static_cast<llama_pos>(i))) {
            resetKv(inst, "prefill decode 失败");
            return false;
        }
        inst->kvTokens.insert(inst->kvTokens.end(), tokens.begin() + i, tokens.begin() + i + chunk);
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
    cp.n_batch = std::min(inst->n_batch, std::min(nCtx, 2048));
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
    cparams.n_threads_batch = nThreads;
    cparams.n_batch = std::min(nCtx, 2048);
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
    LOGI("nativeInit 成功: %s (n_ctx=%d, n_batch=%d, n_vocab=%d, bos=%d, eos=%d)",
         desc, static_cast<int>(llama_n_ctx(inst->ctx)), inst->n_batch,
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

    const long long prefillStart = nowMs();
    if (!decodeFrom(inst, tokens, common)) {
        // 三级自愈，全部在 native 内完成，用户侧只体现为「这一次慢一点」：
        //   1) KV 已被 decodeFrom 重置 —— 排除账本脏；
        //   2) 分块缩到 512 —— 排除「单批太大被后端拒绝」；
        //   3) 重建上下文 —— 排除 KV/后端状态损坏。
        // 真机上「一个字都不出」大多是 1 或 2 能救回来的情况；救不回来也要留下
        // 明确日志，而不是让 UI 空着。
        LOGE("prefill 失败，降级重试：分块缩到 512");
        inst->n_batch = std::min(inst->n_batch, 512);
        if (!decodeFrom(inst, tokens, 0)) {
            LOGE("小分块仍失败，重建上下文后重试");
            if (!rebuildContext(inst)) {
                return env->NewStringUTF("");
            }
            if (!decodeFrom(inst, tokens, 0)) {
                LOGE("prefill 三次尝试均失败，本回合无法生成");
                return env->NewStringUTF("");
            }
        }
        common = 0;
    }
    const long long prefillMs = nowMs() - prefillStart;
    LOGI("prefill 完成：复用 %zu / 共 %zu token，补算 %zu，耗时 %lld ms",
         common, tokens.size(), tokens.size() - common, prefillMs);

    // 3) 采样链：top-p 0.9 → temp 0.7 → dist(seed 1234)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
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
