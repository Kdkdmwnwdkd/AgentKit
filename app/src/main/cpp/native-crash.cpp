// 原生崩溃记忆：把「进程被信号打死」这一类的现场，在死之前同步写进一个文件。
//
// ## 为什么必须有这个文件
// Kotlin 侧的 CrashLogger 挂的是 `Thread.UncaughtExceptionHandler`，它只能看见
// **Java 异常**。GPU / Vulkan / ggml 这层出的问题（device lost、非法访问、栈溢出）
// 走的是信号（SIGSEGV / SIGABRT / SIGBUS / SIGILL / SIGFPE），进程当场消失，
// Java 那层连一句日志都来不及写。用户看到的就是「闪退，日志里什么都没有」。
//
// ## 三条铁律
//  1. **只用 async-signal-safe 的调用**（open / write / fsync / close / raise）。
//     崩溃时堆可能已经碎了，malloc、snprintf、std::string 都可能再崩一次。
//  2. **必须写在独立的信号栈上**（SA_ONSTACK + sigaltstack）。栈溢出型的崩溃
//     在原生栈上根本执行不了 handler，表现为「装了也抓不到」。
//  3. **写完之后要让系统继续死**。恢复默认处理再 raise 一次，Android 才会照常
//     生成 tombstone（`debuggerd` 那份），不能把信号吞掉。
//
// ## 为什么单独记「阶段」
// 光知道「崩了」没用——崩在装载模型、建上下文、还是推理中，处理方式完全不同。
// 阶段由 llama-jni.cpp 在每个关键节点调 `ak_stage_set()` 更新，这里只读。
// 阶段是 `volatile sig_atomic_t`，单字写入，handler 里读不会读到半个值。

#include <jni.h>

#include <csignal>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

#include "native-crash.h"

namespace {

/** 崩溃时最多写这么多字节。够放阶段/模型名/参数，又不至于在 handler 里拼太久。 */
constexpr size_t kBufSize = 1536;

/** 独立信号栈大小。64KB 足够跑「拼字符串 + write」，且不会挤占正常栈。 */
constexpr size_t kAltStackSize = 64 * 1024;

/** 崩溃文件绝对路径（install 时确定），handler 里直接用，不再依赖任何对象。 */
char g_path[512] = {0};

/** 是否已安装（幂等）。 */
bool g_installed = false;

/** 独立信号栈的存储。必须静态，不能是栈上局部变量。 */
char g_altStack[kAltStackSize];

/** 崩溃时想知道的上下文，全部是 POD 全局，handler 里直接读。 */
volatile sig_atomic_t g_stage = AK_STAGE_IDLE;
volatile sig_atomic_t g_signal = 0;

/** 字符串类上下文用固定数组：handler 里不能碰 std::string。 */
char g_model[128] = {0};
volatile sig_atomic_t g_ctx = 0;
volatile sig_atomic_t g_threads = 0;
volatile sig_atomic_t g_gpuLayers = 0;
volatile sig_atomic_t g_hasVulkan = 0;

/* ------------------------- async-signal-safe 小工具 ------------------------- */

/** 把无符号整数写成十进制，返回写入后的指针。不用 snprintf（非异步信号安全）。 */
char* putUint(char* p, unsigned long v) {
    char tmp[24];
    int n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v > 0) {
        tmp[n++] = static_cast<char>('0' + (v % 10));
        v /= 10;
    }
    while (n > 0) *p++ = tmp[--n];
    return p;
}

/** 追加一个以 '\0' 结尾的字符串（含长度上限，防止越界）。 */
char* putStr(char* p, char* end, const char* s) {
    if (!s) return p;
    while (*s && p < end) *p++ = *s++;
    return p;
}

/** 追加 `key=值\n` 形式的整数行。 */
char* putIntLine(char* p, char* end, const char* key, long v) {
    p = putStr(p, end, key);
    p = putStr(p, end, "=");
    if (v < 0) {
        p = putStr(p, end, "-");
        v = -v;
    }
    p = putUint(p, static_cast<unsigned long>(v));
    if (p < end) *p++ = '\n';
    return p;
}

/**
 * 把 `/proc/self/status` 里的三行内存数据抄进现场。
 *
 * **为什么非要这个**：2026-10-29 真机连续两次 SIGABRT 崩在 prefill（纯 CPU），
 * 崩溃文件只告诉我们「崩在 prefill、没开 GPU」——这已经排除了显卡，但分不出
 * 两种完全不同的病因：
 *
 *   - 内存真的不够（权重 1.9GB + prefill 的中间张量顶到上限，malloc 失败；
 *     ggml 的分配器失败路径直接 abort）→ 该降批、降上下文；
 *   - 断言失败（形状/位置不合法）→ 降参数没用，得改代码。
 *
 * VmRSS 贴着 VmSize / 接近设备可用量，就是前者。这一行数据让下一次崩溃能一句话定性，
 * 不用再靠猜。读 `/proc/self/status` 用的是 open+read，都在 async-signal-safe 列表里。
 */
char* putMemSnapshot(char* p, char* end) {
    // static：信号栈只有 64KB，handler 里再放 4KB 局部数组要跟别的局部量抢空间。
    // handler 已先把自身重置为 SIG_DFL，不会重入，static 是安全的。
    static char sbuf[4096];
    const int fd = ::open("/proc/self/status", O_RDONLY);
    if (fd < 0) return putStr(p, end, "mem=?\n");
    const ssize_t got = ::read(fd, sbuf, sizeof(sbuf) - 1);
    ::close(fd);
    if (got <= 0) return putStr(p, end, "mem=?\n");
    sbuf[got] = '\0';

    p = putStr(p, end, "mem=");
    const char* keys[] = {"VmPeak:", "VmSize:", "VmRSS:"};
    for (int k = 0; k < 3; ++k) {
        const size_t klen = strlen(keys[k]);
        for (const char* line = sbuf; line && *line;) {
            const char* eol = strchr(line, '\n');
            const size_t len = eol ? static_cast<size_t>(eol - line) : strlen(line);
            if (len > klen && memcmp(line, keys[k], klen) == 0) {
                p = putStr(p, end, " ");
                for (size_t i = 0; i < len && p < end; ++i) *p++ = line[i];
                break;
            }
            line = eol ? eol + 1 : nullptr;
        }
    }
    if (p < end) *p++ = '\n';
    return p;
}

/** 信号名（写进文件让人一眼看懂，不用去查 11 是哪个）。 */
const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGFPE:  return "SIGFPE";
        default:      return "SIG?";
    }
}

/** 阶段名。和 native-crash.h 里的枚举一一对应。 */
const char* stageName(int stage) {
    switch (stage) {
        case AK_STAGE_DEVICE_INIT: return "DEVICE_INIT";
        case AK_STAGE_MODEL_LOAD:  return "MODEL_LOAD";
        case AK_STAGE_CTX_CREATE:  return "CTX_CREATE";
        case AK_STAGE_PREFILL:     return "PREFILL";
        case AK_STAGE_DECODE:      return "DECODE";
        default:                   return "IDLE";
    }
}

/**
 * 信号处理器本体。
 *
 * 只做三件事：拼一段定长文本 → 同步写盘 → 让进程照常死。
 * 任何一步失败都直接跳过，绝不在 handler 里重试或报错（重试=再崩一次）。
 */
void crashHandler(int sig, siginfo_t* info, void* /*ctx*/) {
    g_signal = sig;

    // 关掉自己：万一 write 过程中又触发信号，避免无限递归。
    struct sigaction dfl {};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);

    char buf[kBufSize];
    char* end = buf + sizeof(buf) - 1;
    char* p = buf;

    p = putStr(p, end, "kind=native-crash\n");
    p = putStr(p, end, "signal=");
    p = putUint(p, static_cast<unsigned long>(sig));
    p = putStr(p, end, " (");
    p = putStr(p, end, signalName(sig));
    p = putStr(p, end, ")\n");
    p = putStr(p, end, "stage=");
    p = putStr(p, end, stageName(static_cast<int>(g_stage)));
    if (p < end) *p++ = '\n';
    p = putIntLine(p, end, "gpu_layers", static_cast<long>(g_gpuLayers));
    p = putIntLine(p, end, "ctx", static_cast<long>(g_ctx));
    p = putIntLine(p, end, "threads", static_cast<long>(g_threads));
    p = putIntLine(p, end, "vulkan_built", static_cast<long>(g_hasVulkan));
    // si_code：-6 是 SI_TKILL（自己 abort 自己，典型 ggml 断言），
    // 1/2 是 SEGV_MAPERR/ACCERR（野指针），0 是 SI_USER（被别的进程 kill）。
    p = putIntLine(p, end, "sigcode", static_cast<long>(info ? info->si_code : 0));
    p = putStr(p, end, "model=");
    p = putStr(p, end, g_model[0] ? g_model : "?");
    if (p < end) *p++ = '\n';
    // 放在最后：内存快照要读 procfs，耗时比前面几行大，别让它挤掉上面的关键字段。
    p = putMemSnapshot(p, end);
    *p = '\0';

    if (g_path[0]) {
        const int fd = ::open(g_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            const size_t len = static_cast<size_t>(p - buf);
            size_t off = 0;
            // write 可能短写，循环写完；失败就放弃，别在崩溃路径上较劲。
            while (off < len) {
                const ssize_t n = ::write(fd, buf + off, len - off);
                if (n <= 0) break;
                off += static_cast<size_t>(n);
            }
            ::fsync(fd);
            ::close(fd);
        }
    }

    // 交还系统：重发信号让默认处理生效（Android 由此生成 tombstone / 重启应用）。
    ::raise(sig);
}

}  // namespace

/* ============================ 对 llama-jni.cpp 暴露 ============================ */

void ak_stage_set(int stage) { g_stage = stage; }

void ak_crash_context(const char* modelName, int nCtx, int nThreads, int gpuLayers) {
    if (modelName) {
        size_t i = 0;
        for (; modelName[i] && i < sizeof(g_model) - 1; ++i) g_model[i] = modelName[i];
        g_model[i] = '\0';
    }
    g_ctx = nCtx;
    g_threads = nThreads;
    g_gpuLayers = gpuLayers;
}

/* ================================ JNI 入口 ================================ */

extern "C" {

/**
 * 安装原生崩溃捕获。
 *
 * @param crashDir `filesDir/crash` 的绝对路径（Kotlin 侧算好传进来，native 不做路径拼接）
 */
JNIEXPORT void JNICALL
Java_com_example_agentkit_core_llm_local_LlamaJni_nativeInstallCrashHandler(
    JNIEnv* env, jobject /*thiz*/, jstring crashDir) {
    if (!crashDir) return;
    const char* dir = env->GetStringUTFChars(crashDir, nullptr);
    if (!dir) return;
    ak_crash_install(dir);
    env->ReleaseStringUTFChars(crashDir, dir);
}

}  // extern "C"

/* ============================ 不依赖 JNI 的安装入口 ============================ */

/**
 * 把本进程的 stderr 接到 `<crashDir>/native-stderr.txt`。
 *
 * **这是抓 SIGABRT 真凶的唯一手段。** ggml / llama.cpp 在断言失败时会先
 * `fprintf(stderr, "GGML_ASSERT(...) failed")` 再 abort ——那行字就是答案。
 * 但 Android 的 App 进程在 zygote 里就把 fd 0/1/2 接到 /dev/null 了，
 * 所以这行字平时哪都去不了：logcat 看不到、我们的 Java 日志也看不到，
 * 只留下一个「闪退，什么都没有」。
 *
 * 用 O_TRUNC：只保留最近一次运行。llama.cpp 装载时会把每个 tensor 都打一遍，
 * 单次会话几百 KB 到几 MB，按时间戳堆文件没有意义。
 *
 * 失败一律静默返回：这是诊断设施，不能因为它装不上就让推理起不来。
 */
static void redirectStderr(const char* dir) {
    char path[512];
    int n = 0;
    for (; dir[n] && n < static_cast<int>(sizeof(path)) - 24; ++n) path[n] = dir[n];
    const char* leaf = "/native-stderr.txt";
    for (int i = 0; leaf[i] && n < static_cast<int>(sizeof(path)) - 1; ++i) {
        path[n++] = leaf[i];
    }
    path[n] = '\0';

    const int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return;
    // dup2 之后 fd 2 指向同一个打开的文件，原来的 fd 可以关掉。
    if (::dup2(fd, STDERR_FILENO) < 0) {
        ::close(fd);
        return;
    }
    ::close(fd);
    // C 的 stderr 默认无缓冲，不需要额外 setvbuf；显式设一次是为了防止
    // 某些 ROM 的 libc 把它设成行缓冲后，崩溃前那行断言留在用户态缓冲里丢掉。
    ::setvbuf(stderr, nullptr, _IONBF, 0);
}

// 单独抽出来是为了能在**主机上**真跑一遍：断言里 fork 一个子进程、raise(SIGSEGV)、
// 再检查文件内容。只断言「源码里有这个字符串」测不出处理器到底装没装上。
void ak_crash_install(const char* dir) {
    if (g_installed) return;
    if (!dir || !*dir) return;

    // 先接 stderr，再装信号处理器：断言行是在 abort 之前打出来的，
    // 顺序反了也不丢，但接得越早越能把装载期的日志也收进来。
    redirectStderr(dir);

    // 文件名固定为 native-crash.txt：**只保留最近一次**。
    //
    // 不按时间戳堆文件，是因为这类崩溃几乎必然在「下一次尝试」里复现，
    // 堆十份一样的没有信息量；而固定名字让 Kotlin 侧读取时不需要排序。
    int n = 0;
    for (; dir[n] && n < static_cast<int>(sizeof(g_path)) - 32; ++n) g_path[n] = dir[n];
    const char* leaf = "/native-crash.txt";
    for (int i = 0; leaf[i] && n < static_cast<int>(sizeof(g_path)) - 1; ++i) {
        g_path[n++] = leaf[i];
    }
    g_path[n] = '\0';

    // 独立信号栈：栈溢出型崩溃必须在别的栈上跑 handler。
    stack_t ss {};
    ss.ss_sp = g_altStack;
    ss.ss_size = sizeof(g_altStack);
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) != 0) return;

    struct sigaction sa {};
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    // 顺便把 SIGSEGV 等信号从屏蔽集里放开（某些运行时会误屏蔽）。
    sigaddset(&sa.sa_mask, SIGSEGV);

#ifdef AK_HAS_VULKAN
    g_hasVulkan = 1;
#endif

    // SIGFPE 不算崩溃（浮点异常在 ggml 里多为除零），但代价极低，一并装上。
    const int sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (int s : sigs) sigaction(s, &sa, nullptr);

    g_installed = true;
}
