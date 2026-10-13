// AgentKit 终端 pty 会话 JNI 桥。
//
// 【净室重写声明】本文件按 bionic 的 forkpty(3) / pty(7) / termios(3) man page 与
// xterm ctlseqs 公开规范实现，未参考、未复制 Termux（GPLv3）或任何第三方终端项目的
// 任何一行代码。只做 pty 进程生命周期的六个原语：创建 / 读 / 写 / 改窗口大小 /
// 杀进程组 / 等待退出。协议细节（环境变量、读循环编排）都在 Kotlin 侧 TerminalSession。
//
// 库名与 CMakeLists.txt 的 add_library(agentkit-termux SHARED ...) 一致，
// Kotlin 侧声明见 com.example.agentkit.terminal.TerminalJni。

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

#include <fcntl.h>
#include <pthread.h>
#include <pty.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define LOG_TAG "termux-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** 一条 pty 会话：子进程 pid + master fd。句柄以 jlong 传给 Kotlin。 */
struct Subprocess {
    pid_t pid;              // 子进程 pid（forkpty 内部已 setsid，pid 即进程组 id）
    std::atomic<int> masterFd;  // master 端；销毁后置 -1（原子：读/写/ioctl 无锁快照读）
    int slaveFd;            // 父进程不持有 slave（见 createSubprocess 注释），恒为 -1
    bool reaped;            // 已 waitpid 回收，防僵尸
    int exitCode;           // 回收时记录的退出码（信号杀死 = 128 + 信号）
    // 只保护 reaped/exitCode 与 waitpid 区（全部非阻塞）。
    // 绝对不要用它包阻塞 read/write——主线程的 resize/write 会因此死等读循环的锁
    // （真机实测 ANR「应用无响应」，2026-10-13）。
    pthread_mutex_t mu;
};

std::string jstringToStd(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
}

/** Java 侧传入的 env 数组 → C 字符串 vector（execve 直接使用，不在 C 层拼环境变量）。 */
std::vector<std::string> jStringArrayToVector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto* js = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, i));
        const char* chars = js ? env->GetStringUTFChars(js, nullptr) : nullptr;
        out.emplace_back(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(js, chars);
        if (js) env->DeleteLocalRef(js);
    }
    return out;
}

/** waitpid 状态 → 退出码：正常退出取 WEXITSTATUS，信号杀死取 128 + 信号（shell 惯例）。 */
int statusToExitCode(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

}  // namespace

extern "C" {

/**
 * 创建 pty 子进程并执行 [cmd]。
 *
 * forkpty（bionic API 23+，本项目 minSdk 26 安全）内部完成三件事：
 * openpty、setsid（子进程独立进程组）、slave 设为控制终端并接管 stdio。
 *
 * @param cmd  可执行文件绝对路径（argv 目前仅为该路径本身；容器场景的参数模板在
 *             Kotlin 侧拼成启动脚本后再传路径）
 * @param env  环境变量数组（K=V 形式，execve 原样使用；C 层只负责转换不拼装）
 * @param cwd  子进程工作目录
 * @param rows 终端行数（写入 pty  winsize，触发首次布局）
 * @param cols 终端列数
 * @return 会话句柄（jlong）；0 表示失败（Kotlin 侧抛 IOException）
 */
JNIEXPORT jlong JNICALL
Java_com_example_agentkit_terminal_TerminalJni_createSubprocess(
    JNIEnv* env, jobject /*thiz*/, jstring cmd, jobjectArray jenv,
    jstring cwd, jint rows, jint cols) {
    const std::string cmdStr = jstringToStd(env, cmd);
    const std::string cwdStr = jstringToStd(env, cwd);
    if (cmdStr.empty()) {
        LOGE("createSubprocess: cmd 为空");
        return 0;
    }
    std::vector<std::string> envStore = jStringArrayToVector(env, jenv);
    std::vector<char*> envp;
    envp.reserve(envStore.size() + 1);
    for (auto& e : envStore) envp.push_back(const_cast<char*>(e.c_str()));
    envp.push_back(nullptr);

    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);
    if (pid < 0) {
        LOGE("forkpty 失败: errno=%d (%s)", errno, strerror(errno));
        return 0;
    }

    if (pid == 0) {
        // —— 子进程路径 ——
        // forkpty 已 setsid + slave 接管 stdio（0/1/2 均指向 slave），
        // execve 失败时经 stderr（即 slave）把 errno 写给父进程，再 _exit(127)。
        if (!cwdStr.empty() && chdir(cwdStr.c_str()) != 0) {
            const int e = errno;
            char buf[64];
            const int len = snprintf(buf, sizeof(buf), "chdir(%s) failed errno=%d\n",
                                     cwdStr.c_str(), e);
            ssize_t ignored = write(STDERR_FILENO, buf, static_cast<size_t>(len));
            (void) ignored;
        }
        char* const argv[] = {const_cast<char*>(cmdStr.c_str()), nullptr};
        execve(cmdStr.c_str(), argv, envp.data());
        const int e = errno;
        char buf[64];
        const int len = snprintf(buf, sizeof(buf), "execve(%s) failed errno=%d\n",
                                 cmdStr.c_str(), e);
        ssize_t ignored = write(STDERR_FILENO, buf, static_cast<size_t>(len));
        (void) ignored;
        _exit(127);
    }

    // —— 父进程路径 ——
    // 注意：forkpty 在父进程侧已关闭 slave。这是必要行为——父进程若持有 slave，
    // 子进程退出后 master 的 read 永远拿不到 EOF。句柄里 slaveFd 恒记 -1。
    auto* sp = new Subprocess();
    sp->pid = pid;
    sp->masterFd.store(master);
    sp->slaveFd = -1;
    sp->reaped = false;
    sp->exitCode = -1;
    pthread_mutex_init(&sp->mu, nullptr);
    LOGI("createSubprocess 成功: pid=%d master=%d %dx%d", pid, master,
         ws.ws_row, ws.ws_col);
    return reinterpret_cast<jlong>(sp);
}

/**
 * 创建 pty 子进程并执行 [argv]（多参数命令，供容器 proot 使用）。
 *
 * 与 createSubprocess 同构：forkpty → 子进程 setenv 环境变量 → execvp(argv[0], argv)。
 * 区别在于支持参数向量（如 `proot -r rootfs -b /dev ... /bin/sh`），而非单路径 execve。
 *
 * @param argv Java 字符串数组（argv[0] 为可执行文件绝对路径）
 * @param env  环境变量数组（K=V 形式；子进程内 setenv，供 execvp 经由 environ 继承）
 * @param cwd  子进程工作目录（execvp 前 chdir）
 * @param rows / cols 终端尺寸
 * @return 会话句柄；0 = 失败
 */
JNIEXPORT jlong JNICALL
Java_com_example_agentkit_terminal_TerminalJni_createSubprocessArgv(
    JNIEnv* env, jobject /*thiz*/, jobjectArray jargv, jobjectArray jenv,
    jstring cwd, jint rows, jint cols) {
    if (!jargv) {
        LOGE("createSubprocessArgv: argv 为空");
        return 0;
    }
    const std::string cwdStr = jstringToStd(env, cwd);
    std::vector<std::string> argvStore = jStringArrayToVector(env, jargv);
    if (argvStore.empty() || argvStore[0].empty()) {
        LOGE("createSubprocessArgv: argv[0] 为空");
        return 0;
    }
    std::vector<std::string> envStore = jStringArrayToVector(env, jenv);

    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);
    if (pid < 0) {
        LOGE("forkpty 失败: errno=%d (%s)", errno, strerror(errno));
        return 0;
    }

    if (pid == 0) {
        // —— 子进程路径 ——
        if (!cwdStr.empty() && chdir(cwdStr.c_str()) != 0) {
            const int e = errno;
            char buf[64];
            const int len = snprintf(buf, sizeof(buf), "chdir(%s) failed errno=%d\n",
                                     cwdStr.c_str(), e);
            ssize_t ignored = write(STDERR_FILENO, buf, static_cast<size_t>(len));
            (void) ignored;
        }
        // 把 K=V 环境变量注入子进程 environ（execvp 沿用 environ）
        for (auto& e : envStore) {
            const size_t eq = e.find('=');
            if (eq != std::string::npos && eq > 0) {
                setenv(e.substr(0, eq).c_str(), e.substr(eq + 1).c_str(), 1);
            }
        }
        std::vector<char*> argvp;
        argvp.reserve(argvStore.size() + 1);
        for (auto& a : argvStore) argvp.push_back(const_cast<char*>(a.c_str()));
        argvp.push_back(nullptr);
        execvp(argvStore[0].c_str(), argvp.data());
        const int e = errno;
        char buf[64];
        const int len = snprintf(buf, sizeof(buf), "execvp(%s) failed errno=%d\n",
                                 argvStore[0].c_str(), e);
        ssize_t ignored = write(STDERR_FILENO, buf, static_cast<size_t>(len));
        (void) ignored;
        _exit(127);
    }

    // —— 父进程路径 ——
    auto* sp = new Subprocess();
    sp->pid = pid;
    sp->masterFd.store(master);
    sp->slaveFd = -1;
    sp->reaped = false;
    sp->exitCode = -1;
    pthread_mutex_init(&sp->mu, nullptr);
    LOGI("createSubprocessArgv 成功: pid=%d master=%d %dx%d", pid, master,
         ws.ws_row, ws.ws_col);
    return reinterpret_cast<jlong>(sp);
}

/**
 * 阻塞读 master fd（pty 输出 → 仿真器）。
 *
 * 无锁：fd 取原子快照后直接阻塞 read。锁只保护非阻塞状态区——若像旧版那样持锁
 * read，主线程的 resize/write 会死等读循环释放锁而 ANR（真机实测，2026-10-13）。
 * fd 被销毁路径 close 后，本 read 立即返回 EBADF/EIO → -1，读循环自然收尾。
 *
 * @return 读到的字节数；0 = EOF（子进程关闭了 slave 一侧）；-1 = 句柄无效 / 已销毁 / 读错误
 */
JNIEXPORT jint JNICALL
Java_com_example_agentkit_terminal_TerminalJni_readBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray buf) {
    auto* sp = reinterpret_cast<Subprocess*>(handle);
    if (!sp) return -1;
    const jsize cap = env->GetArrayLength(buf);
    if (cap <= 0) return 0;
    const int fd = sp->masterFd.load();
    if (fd < 0) return -1;
    jbyte* dst = env->GetByteArrayElements(buf, nullptr);
    if (!dst) return -1;
    ssize_t n;
    do {
        n = read(fd, dst, static_cast<size_t>(cap));
    } while (n < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(buf, dst, 0);  // mode 0 = 内容回拷 + 释放
    if (n < 0) {
        // EIO 在 Linux pty 上表示子进程侧已全部关闭，视同 EOF 由 Kotlin 侧统一收尾
        LOGI("read 返回 -1: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    return static_cast<jint>(n);
}

/**
 * 写 master fd（用户输入 → pty）。内部循环写全部，不会写一半就返回。
 *
 * 无锁（fd 原子快照）：输入路径由主线程直达，持锁写在 pty 缓冲满时会阻塞 UI。
 *
 * @return 写入字节数；-1 = 句柄无效 / 已销毁 / 写错误
 */
JNIEXPORT jint JNICALL
Java_com_example_agentkit_terminal_TerminalJni_writeBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray data) {
    auto* sp = reinterpret_cast<Subprocess*>(handle);
    if (!sp) return -1;
    const jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;
    const int fd = sp->masterFd.load();
    if (fd < 0) return -1;
    jbyte* src = env->GetByteArrayElements(data, nullptr);
    if (!src) return -1;
    jsize off = 0;
    while (off < len) {
        ssize_t n = write(fd, src + off, static_cast<size_t>(len - off));
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("write 失败: errno=%d (%s)", errno, strerror(errno));
            off = -1;
            break;
        }
        off += static_cast<jsize>(n);
    }
    env->ReleaseByteArrayElements(data, src, JNI_ABORT);  // 只读不写，不回收
    return static_cast<jint>(off);
}

/** 修改 pty 窗口大小（ioctl TIOCSWINSZ）：子进程收到 SIGWINCH 后自行重排。无锁（ioctl 非阻塞）。 */
JNIEXPORT void JNICALL
Java_com_example_agentkit_terminal_TerminalJni_setPtyWindowSize(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint rows, jint cols) {
    auto* sp = reinterpret_cast<Subprocess*>(handle);
    if (!sp) return;
    const int fd = sp->masterFd.load();
    if (fd < 0) return;
    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        LOGI("TIOCSWINSZ 失败: errno=%d (%s)", errno, strerror(errno));
    }
}

/**
 * 杀整个进程组并回收（幂等）。
 *
 * kill(-pid, SIGKILL)：负 pid 打向进程组；forkpty 已 setsid，pid 即 pgid，
 * 一条命令能把 shell 及其所有前台子进程一起收掉——不留僵尸、不剩后台孤儿。
 * 回收后才关闭 master fd，保证 waitpid 不会永久挂起。
 */
JNIEXPORT void JNICALL
Java_com_example_agentkit_terminal_TerminalJni_killProcessGroup(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* sp = reinterpret_cast<Subprocess*>(handle);
    if (!sp) return;
    // 先把 fd 摘走（后续 readBytes 快照取到 -1 即退出）；阻塞 read 会被 close 唤醒返回错误
    const int fd = sp->masterFd.exchange(-1);
    if (sp->pid > 0 && kill(-sp->pid, SIGKILL) != 0 && errno != ESRCH) {
        LOGE("kill(-%d) 失败: errno=%d (%s)", sp->pid, errno, strerror(errno));
    }
    // SIGKILL 后子进程秒退，waitpid 立即回收（此区持锁但非阻塞）
    pthread_mutex_lock(&sp->mu);
    if (!sp->reaped && sp->pid > 0) {
        int status = 0;
        if (waitpid(sp->pid, &status, 0) == sp->pid) {
            sp->reaped = true;
            sp->exitCode = statusToExitCode(status);
        }
    }
    pthread_mutex_unlock(&sp->mu);
    if (fd >= 0) close(fd);
    LOGI("killProcessGroup 完成: pid=%d exit=%d", sp->pid, sp->exitCode);
}

/**
 * 等待子进程退出（waitpid WNOHANG 轮询，不阻塞超过 timeoutMs）。
 *
 * @param timeoutMs 轮询上限；0 = 只做一次非阻塞探测
 * @return 退出码（信号杀死 = 128 + 信号）；-1 = 仍在运行或无法获取
 */
JNIEXPORT jint JNICALL
Java_com_example_agentkit_terminal_TerminalJni_waitFor(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint timeoutMs) {
    auto* sp = reinterpret_cast<Subprocess*>(handle);
    if (!sp || sp->pid <= 0) return -1;
    pthread_mutex_lock(&sp->mu);
    if (sp->reaped) {
        const int code = sp->exitCode;
        pthread_mutex_unlock(&sp->mu);
        return code;
    }
    pthread_mutex_unlock(&sp->mu);

    const useconds_t kPollUs = 20000;  // 20ms 轮询步长
    const int maxPolls = timeoutMs <= 0 ? 1 : (timeoutMs / 20) + 1;
    for (int i = 0; i < maxPolls; i++) {
        pthread_mutex_lock(&sp->mu);
        int status = 0;
        const pid_t r = waitpid(sp->pid, &status, WNOHANG);
        if (r == sp->pid) {
            sp->reaped = true;
            sp->exitCode = statusToExitCode(status);
            const int code = sp->exitCode;
            pthread_mutex_unlock(&sp->mu);
            return code;
        }
        pthread_mutex_unlock(&sp->mu);
        if (r < 0) return -1;  // ECHILD 等：已被别人回收
        if (i + 1 < maxPolls) usleep(kPollUs);
    }
    return -1;  // 仍在运行
}

}  // extern "C"
