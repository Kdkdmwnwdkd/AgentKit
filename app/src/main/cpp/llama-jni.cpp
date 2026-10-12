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
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>

#include "llama.h"

#define LOG_TAG "llama-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** 一个已加载的本地模型实例。句柄以 jlong 传给 Kotlin。 */
struct LlamaInstance {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_batch = 2048;
    std::atomic<bool> stop{false};
};

std::string jstringToStd(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
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

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    cparams.n_batch = std::min(nCtx, 2048);
    inst->n_batch = cparams.n_batch;
    inst->ctx = llama_init_from_model(inst->model, cparams);
    if (!inst->ctx) {
        LOGE("llama_init_from_model 失败");
        llama_model_free(inst->model);
        delete inst;
        return 0;
    }

    char desc[256] = {0};
    llama_model_desc(inst->model, desc, sizeof(desc));
    LOGI("nativeInit 成功: %s (n_ctx=%d)", desc, llama_n_ctx(inst->ctx));
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
    if (!inst || !inst->ctx || !inst->vocab) return env->NewStringUTF("");

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
    int n = llama_tokenize(inst->vocab, text.c_str(), (int32_t) text.size(),
                           nullptr, 0, true, true);
    if (n <= 0) {
        LOGE("tokenize 失败（长度探测=%d）", n);
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    n = llama_tokenize(inst->vocab, text.c_str(), (int32_t) text.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true);
    if (n <= 0) {
        LOGE("tokenize 失败（正式=%d）", n);
        return env->NewStringUTF("");
    }
    tokens.resize(static_cast<size_t>(n));
    LOGI("prompt token 数: %d", n);

    // 2) 分块 decode 整个 prompt（超过 n_batch 时分多批，否则 llama_decode 直接报错）
    for (size_t i = 0; i < tokens.size(); i += static_cast<size_t>(inst->n_batch)) {
        size_t chunk = std::min(static_cast<size_t>(inst->n_batch), tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, (int32_t) chunk);
        if (llama_decode(inst->ctx, batch) != 0) {
            LOGE("prompt decode 失败（offset=%zu）", i);
            return env->NewStringUTF("");
        }
    }

    // 3) 采样链：top-p 0.9 → temp 0.7 → dist(seed 1234)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // 4) 生成循环：每 token 检查停止标志；遇到 EOS 停
    std::string out;
    out.reserve(1024);
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        if (inst->stop.load()) {
            LOGI("生成被用户停止（已产出 %zu 字符）", out.size());
            break;
        }
        llama_token tok = llama_sampler_sample(smpl, inst->ctx, -1);
        if (tok == llama_vocab_eos(inst->vocab)) {
            LOGI("命中 EOS");
            break;
        }
        int pn = llama_token_to_piece(inst->vocab, tok, piece, sizeof(piece), 0, true);
        if (pn <= 0) continue;
        std::string s(piece, static_cast<size_t>(pn));
        out += s;
        if (onToken && listener) {
            jstring js = env->NewStringUTF(s.c_str());
            env->CallVoidMethod(listener, onToken, js);
            env->DeleteLocalRef(js);
        }
        // 下一个 token 的单条 batch
        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(inst->ctx, next) != 0) {
            LOGE("decode 失败（第 %d 个 token）", i);
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGI("生成完成：%zu 字符", out.size());
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
