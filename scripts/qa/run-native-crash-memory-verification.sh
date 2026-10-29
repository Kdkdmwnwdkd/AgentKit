#!/usr/bin/env bash
# 原生崩溃记忆 harness（W-84）。
#
# 起因：2026-10-29 真机——3B + GPU 全量卸载 + 上下文 16384，用户发一句话，
# 「回答不出来然后闪退」。日志里一条异常都没有：崩溃发生在 native（信号），
# Java 那层 `Thread.UncaughtExceptionHandler` 根本没机会跑。
#
# 所以这一版做两件事：
#   ① 在 native 装信号处理器，死之前把现场同步写盘（阶段 / GPU 层数 / ctx / 模型）；
#   ② 下次启动读这份现场，**自动关掉 GPU** 并把原因写到设置页。
#
# W-85（2026-10-29 第二次闪退）补三件事：
#   ③ 崩溃现场加「内存快照」（VmRSS/VmSize），用来区分 OOM 与断言失败；
#   ④ 把 stderr 接到 native-stderr.txt —— ggml 断言失败会先 fprintf(stderr) 再 abort，
#      那行字是唯一能证伪/证实的线索，而 Android 上它默认被丢进 /dev/null；
#   ⑤ 纯 CPU 崩在 prefill → 开「安全模式」（ctx 2048 / 批 64），而不是只写一句「与 GPU 无关」。
#
# 静态 grep 只能证明「代码写了」，证明不了「处理器真的装上了、真的写了文件、真的没吞信号」。
# 所以关键断言一律 fork 子进程 raise(SIGSEGV) 真跑一遍，再读文件核对内容。
set -uo pipefail
# 下面的断言会 fork 子进程 raise(SIGABRT)：不开这个的话每次跑完都会在仓库根目录
# 掉几个几十 MB 的 core dump（core.<pid>）。
ulimit -c 0 2>/dev/null || true

cd "$(dirname "$0")/../.." || exit 1

CPP=app/src/main/cpp/native-crash.cpp
HDR=app/src/main/cpp/native-crash.h
JNI=app/src/main/cpp/llama-jni.cpp
CMAKE=app/src/main/cpp/CMakeLists.txt
APP=app/src/main/java/com/example/agentkit/AgentKitApplication.kt
JNIKT=app/src/main/java/com/example/agentkit/core/llm/local/LlamaJni.kt
PARSER=app/src/main/java/com/example/agentkit/core/llm/local/NativeCrashRecord.kt
MEMORY=app/src/main/java/com/example/agentkit/core/llm/local/NativeCrashMemory.kt
PROFILE=app/src/main/java/com/example/agentkit/core/llm/local/LocalDeviceProfile.kt
CATALOG=app/src/main/java/com/example/agentkit/core/llm/local/LocalBackendCatalog.kt
CLIENT=app/src/main/java/com/example/agentkit/core/llm/local/LocalLlamaClient.kt
SCREEN=app/src/main/java/com/example/agentkit/ui/ModelConfigScreen.kt

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { grep -qF -- "$2" "$1" && pass "$3" || fail "$3（$1 里缺：$2）"; }
hasnt() { grep -qF -- "$2" "$1" && fail "$3（$1 里不该有：$2）" || pass "$3"; }

for f in "$CPP" "$HDR" "$JNI" "$CMAKE" "$APP" "$JNIKT" "$PARSER" "$MEMORY" "$PROFILE" "$CLIENT"; do
    [ -f "$f" ] || { echo "  FAIL  缺文件 $f"; exit 1; }
done

echo "== 静态：信号捕获真的接进了链路 =="

# 必须是 .so 里的同一个目标：崩溃时进程已经死了，跨库调用不可靠。
has "$CMAKE" "llama-jni.cpp native-crash.cpp" '崩溃捕获编进同一个 .so'
has "$CPP" "SA_ONSTACK" '用独立信号栈（栈溢出型崩溃也能进 handler）'
has "$CPP" "sigaltstack" '确实调了 sigaltstack'
hasnt "$CPP" "malloc(" 'handler 里不碰 malloc（崩溃时堆可能已碎）'
has "$CPP" "::raise(sig)" '写完后重发信号，让系统照常生成 tombstone'
hasnt "$CPP" "SIG_IGN" '不吞信号（吞掉等于假装没崩）'
# 阶段标记：没有它只知道「崩了」，不知道崩在哪一步。
has "$HDR" "AK_STAGE_MODEL_LOAD" '定义了装载阶段'
has "$JNI" "ak_stage_set(AK_STAGE_CTX_CREATE)" '建上下文前标阶段'
has "$JNI" "ak_stage_set(AK_STAGE_DEVICE_INIT)" '枚举显卡前标阶段'
has "$JNI" "ak_crash_context(" '装载前登记模型/ctx/GPU 层数'

# Kotlin 侧
has "$JNIKT" "nativeInstallCrashHandler" 'Kotlin 声明了安装入口'
has "$JNIKT" "crashDir" '崩溃目录由上层注入'
has "$APP" "NativeCrashMemory.handleStartup(this)" '启动时消费崩溃现场'
has "$MEMORY" "setting.gpuMode = GpuMode.OFF" '崩过就把 GPU 关掉'
has "$MEMORY" "lastGpuCrashNote = note" '把原因留在设置页'

echo "== 静态：W-85 —— 能分辨 OOM，且纯 CPU 崩溃也有救 =="
# stderr 落盘：Android 上 fd 2 默认指向 /dev/null，ggml 的断言行平时无处可去。
has "$CPP" "dup2(fd, STDERR_FILENO)" 'stderr 接到文件（抓 GGML_ASSERT 那行）'
has "$CPP" "native-stderr.txt" 'stderr 文件用固定名（只留最近一次）'
has "$CPP" "_IONBF" 'stderr 设无缓冲（崩溃前那行不能留在用户态缓冲里）'
# 内存快照
has "$CPP" "/proc/self/status" '崩溃时读进程内存'
has "$CPP" "VmRSS:" '记录常驻内存（区分 OOM 与断言）'
has "$CPP" "putMemSnapshot" '内存快照拼进现场'
# 安全模式
has "$PROFILE" "const val SAFE_MAX_CTX = 2048" '安全模式上下文上限 2048'
has "$PROFILE" "fun effectiveCtx" '安全模式与 GPU 上限合起来判'
has "$CLIENT" "LocalDeviceRules.effectiveCtx" '装载时用的是合判结果'
has "$CLIENT" "LlamaJni.applySafeMode" '建 context 前把开关推给 native'
has "$JNI" "g_safeMode" 'native 侧有进程级开关'
has "$JNI" "g_safeMode.load() ? 64 : 256" '安全模式把批上限压到 64'
has "$JNI" "cparams.n_ubatch = std::min<uint32_t>(cparams.n_batch" '物理批跟着逻辑批走（写死 512 等于没降）'
has "$MEMORY" "setting.safeMode = true" '纯 CPU 推理崩溃时开安全模式'
has "$SCREEN" "安全模式" '设置页能看见也能关掉'
has "$PARSER" "fun shouldEnableSafeMode" '判定做成纯函数（可断言）'

echo "== 静态：显存保护（这次的直接原因） =="
has "$PROFILE" "const val GPU_MAX_CTX = 4096" 'GPU 开启时上下文上限 4096'
# 盯语义不盯字面：装载入口现在走 effectiveCtx（安全模式 + GPU 上限一起判），
# 而 effectiveCtx 内部必须仍然调 capCtxForGpu。只认旧名字会把「改好了」判成红。
has "$CLIENT" "LocalDeviceRules.effectiveCtx" '装载时真的用了上下文上限'
has "$PROFILE" "return capCtxForGpu(capped, gpuLayers)" 'effectiveCtx 仍然复用 GPU 上限'
has "$CLIENT" "GPU 开启时上下文限制为" '压上下文时留了日志（不许静默改用户设置）'
has "$SCREEN" "gpuCrashNote" '设置页显示崩溃原因'

echo "== 行为：真 fork 一个进程，真 raise(SIGSEGV) =="

GXX=""
command -v g++ >/dev/null 2>&1 && GXX=g++
if [ -z "$GXX" ]; then
    # 没编译器就什么也没验证——按失败处理，别让它变成一次安静的全绿。
    fail '本机没有 g++，崩溃捕获一条都没跑起来（不是跳过，是没验证）'
else
    JAVA_HOME_GUESS="${JAVA_HOME:-}"
    if [ -z "$JAVA_HOME_GUESS" ] || [ ! -f "$JAVA_HOME_GUESS/include/jni.h" ]; then
        JAVA_HOME_GUESS="$(dirname "$(dirname "$(readlink -f "$(command -v javac 2>/dev/null || command -v java)")")")"
    fi
    if [ ! -f "$JAVA_HOME_GUESS/include/jni.h" ]; then
        fail '找不到 jni.h（设 JAVA_HOME），崩溃捕获没验证'
    else
        W="$(mktemp -d)"
        cat > "$W/t.cpp" <<'CPP'
#include "native-crash.h"
#include <sys/wait.h>
#include <unistd.h>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// 阶段用字符串传：断言里写 AK_STAGE_PREFILL 这种宏更可读，
// 顺带保证 native-crash.h 里的枚举名字和 llama-jni.cpp 用的是同一套。
static int stageOf(const char* s) {
    if (!strcmp(s, "MODEL_LOAD"))  return AK_STAGE_MODEL_LOAD;
    if (!strcmp(s, "CTX_CREATE"))  return AK_STAGE_CTX_CREATE;
    if (!strcmp(s, "DEVICE_INIT")) return AK_STAGE_DEVICE_INIT;
    if (!strcmp(s, "PREFILL"))     return AK_STAGE_PREFILL;
    if (!strcmp(s, "DECODE"))      return AK_STAGE_DECODE;
    return AK_STAGE_IDLE;
}

// 用法：t <dir> <stage> <gpuLayers> <ctx> <signal>
int main(int argc, char** argv) {
    if (argc < 6) return 2;
    const char* dir = argv[1];
    const int stage = stageOf(argv[2]);
    const int gpu   = atoi(argv[3]);
    const int ctx   = atoi(argv[4]);
    const int sig   = atoi(argv[5]);

    pid_t p = fork();
    if (p == 0) {
        ak_crash_install(dir);
        ak_crash_context("qwen2.5-3b-instruct-q4_k_m.gguf", ctx, 6, gpu);
        ak_stage_set(stage);
        // 复刻 ggml 的失败顺序：**先 fprintf(stderr) 那行断言，再 abort**。
        // 真机上这行字默认被丢进 /dev/null，所以能读到它本身就是一条断言。
        fprintf(stderr, "GGML_ASSERT(n_tokens <= n_batch) failed\n");
        fflush(stderr);
        raise(sig);
        _exit(0);
    }
    int st = 0;
    waitpid(p, &st, 0);
    printf("%d %d\n", WIFSIGNALED(st), WTERMSIG(st));
    return 0;
}
CPP
        if $GXX -std=c++17 -Iapp/src/main/cpp -I"$JAVA_HOME_GUESS/include" \
            -I"$JAVA_HOME_GUESS/include/linux" \
            "$CPP" "$W/t.cpp" -o "$W/t" 2>"$W/err.txt"; then
            pass 'native-crash.cpp 编得过（含 jni.h）'
        else
            fail 'native-crash.cpp 编不过'
            sed -n '1,8p' "$W/err.txt" | sed 's/^/        /'
        fi

        if [ -x "$W/t" ]; then
            # ——— A. 上一版就有的形态：GPU 全量卸载 + ctx16384，崩在装载（SIGSEGV）
            OUT="$("$W/t" "$W" MODEL_LOAD 99 16384 11 2>/dev/null)"
            # ① 进程必须**真的**死于信号：handler 不能把崩溃吞掉变成正常退出。
            [ "$OUT" = "1 11" ] && pass '装完处理器后进程仍按 SIGSEGV 死亡（没吞信号）' \
                || fail "进程退出状态异常：$OUT（期望 1 11）"

            F="$W/native-crash.txt"
            if [ -f "$F" ]; then
                pass '崩溃现场写出了文件'
            else
                fail '没有生成 native-crash.txt（处理器没生效）'
            fi
            if [ -f "$F" ]; then
                # ② 内容必须够归因：只知道「崩了」没有用。
                grep -q "kind=native-crash" "$F" && pass '文件带 kind 标记（能和其他崩溃文件区分）' \
                    || fail '文件缺 kind 标记'
                grep -q "signal=11 (SIGSEGV)" "$F" && pass '记录了信号名（不用去查 11 是什么）' \
                    || fail '没记录信号名'
                grep -q "stage=MODEL_LOAD" "$F" && pass '记录了阶段（崩在装载而不是推理）' \
                    || fail '没记录阶段'
                grep -q "gpu_layers=99" "$F" && pass '记录了 GPU 层数（判断要不要关 GPU 的唯一依据）' \
                    || fail '没记录 GPU 层数'
                grep -q "ctx=16384" "$F" && pass '记录了上下文长度（复现的关键参数）' \
                    || fail '没记录上下文长度'
                grep -q "sigcode=" "$F" && pass '记录了 si_code（自己 abort 还是被 kill 能分开）' \
                    || fail '没记录 si_code'
            fi

            # ——— B. 这次的形态：纯 CPU + ctx4096 + 崩在 prefill，且是 SIGABRT。
            #     专门测它，是因为两次真机崩溃的形态**不一样**，只测 A 等于没测 B。
            rm -f "$F" "$W/native-stderr.txt"
            OUT2="$("$W/t" "$W" PREFILL 0 4096 6 2>/dev/null)"
            [ "$OUT2" = "1 6" ] && pass 'SIGABRT 也被接住（真机崩的就是这个）' \
                || fail "SIGABRT 退出状态异常：$OUT2（期望 1 6）"
            if [ -f "$F" ]; then
                grep -q "signal=6 (SIGABRT)" "$F" && pass '写下的信号是 SIGABRT' \
                    || fail 'SIGABRT 现场的信号记错了'
                grep -q "stage=PREFILL" "$F" && pass '写下的阶段是 PREFILL（这次崩在读题）' \
                    || fail 'PREFILL 阶段没记下来'
                grep -q "gpu_layers=0" "$F" && pass '写下 gpu_layers=0（据此判定「与 GPU 无关」）' \
                    || fail '没记 gpu_layers'
                # ③ 内存快照：能不能分辨 OOM 全靠这一行。
                if [ -r /proc/self/status ]; then
                    grep -q "mem=" "$F" && pass '现场带内存快照行' || fail '现场没有 mem= 行'
                    grep -q "VmRSS:" "$F" && pass '内存快照里有 VmRSS（OOM 判据）' \
                        || fail '内存快照里没有 VmRSS'
                    # procfs 里是 "VmRSS:\t   504 kB"（制表符 + 空格），
                    # 只写 "[ ]*" 会一位数字都匹配不到——这条断言第一次就是因此假红。
                    RSS="$(sed -n 's/.*VmRSS:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$F" | head -1)"
                    if [ -n "$RSS" ] && [ "$RSS" -gt 0 ] 2>/dev/null; then
                        pass "VmRSS 是真实数字（$RSS KB），不是占位符"
                    else
                        fail "VmRSS 不是有效数字：'$RSS'"
                    fi
                else
                    printf '  SKIP  本机没有 /proc/self/status（非 Linux），内存快照未验证\n'
                fi
            else
                fail 'SIGABRT 没有生成崩溃文件'
            fi

            # ④ stderr 落盘：ggml 那行断言是唯一能一句话定性的证据。
            E="$W/native-stderr.txt"
            if [ -f "$E" ]; then
                pass 'stderr 被接到了文件（不再丢进 /dev/null）'
                grep -q "GGML_ASSERT(n_tokens <= n_batch) failed" "$E" \
                    && pass 'ggml 的断言行原样落盘（下次真机能读到真凶）' \
                    || fail 'stderr 文件里没有断言那行'
            else
                fail '没有生成 native-stderr.txt（stderr 没被接走）'
            fi

            # ⑤ 别把 stderr 接到父进程：父进程的 stdout 还得能读。
            [ "$OUT" = "1 11" ] && pass 'stderr 重定向没影响父进程输出（工具链还能拿到结果）' \
                || fail '父进程输出被 stderr 重定向污染了'
        fi
        rm -rf "$W"
    fi
fi

echo "== 行为：Kotlin 解析与判定（真编真跑） =="

# 编译器的找法要和 CI 对齐：CI 在 `Prepare kotlinc shim` 步骤里造了一个
# `/tmp/kotlinc/bin/kotlinc`（Gradle 缓存里的 embeddable 编译器 + 自己补 -classpath），
# **没有加进 PATH**。只查 `command -v kotlinc` 在 CI 上必然落空——
# 而落空时如果按 0 累加，就又是一次安静的假绿（见 W-83 笔记）。
KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="$(command -v kotlinc)"
elif [ -x /tmp/kotlinc/bin/kotlinc ]; then
    KOTLINC="/tmp/kotlinc/bin/kotlinc"
elif [ -x "$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc" ]; then
    KOTLINC="$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc"
elif [ -x /opt/kotlinc/bin/kotlinc ]; then
    KOTLINC="/opt/kotlinc/bin/kotlinc"
fi
STDLIB=""
for cand in \
    "${KOTLIN_STDLIB:-}" \
    "/tmp/kotlinc/lib/kotlin-stdlib.jar" \
    "$HOME/.sdkman/candidates/kotlin/current/lib/kotlin-stdlib.jar" \
    "/tmp/khome/lib/kotlin-stdlib.jar" \
    "/usr/share/kotlin/lib/kotlin-stdlib.jar" \
    "$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"; do
    [ -n "$cand" ] && [ -f "$cand" ] && { STDLIB="$cand"; break; }
done

if [ -z "$KOTLINC" ] || [ -z "$STDLIB" ]; then
    fail '找不到 kotlinc / kotlin-stdlib，解析规则一条都没跑（不是跳过，是没验证）'
else
    W2="$(mktemp -d)"
    cat > "$W2/C.kt" <<'KT'
import com.example.agentkit.core.llm.local.NativeCrashParser

fun main() {
    // native 真实输出（与 native-crash.cpp 的格式逐字对齐）
    val real = "kind=native-crash\n" +
        "signal=11 (SIGSEGV)\nstage=MODEL_LOAD\ngpu_layers=99\nctx=16384\n" +
        "threads=6\nvulkan_built=1\nmodel=qwen2.5-3b-instruct-q4_k_m.gguf\n"
    val c = NativeCrashParser.parse(real)
    if (c == null) { println("SUMMARY 0 1"); return }
    var ok = 0
    val bad = ArrayList<String>()
    fun ck(name: String, cond: Boolean) { if (cond) ok++ else bad.add(name) }

    ck("信号号", c.signal == 11)
    ck("信号名", c.signalName == "SIGSEGV")
    ck("阶段", c.stage == "MODEL_LOAD")
    ck("GPU 层数", c.gpuLayers == 99)
    ck("上下文", c.ctx == 16384)
    ck("模型名", c.model.startsWith("qwen2.5-3b"))
    ck("认得出在用 GPU", c.gpuInvolved)
    ck("阶段有中文说法", c.stageLabel == "把模型装进显存")
    ck("该关 GPU", NativeCrashParser.shouldDisableGpu(c))
    ck("说明里有层数", NativeCrashParser.describe(c).contains("99"))

    // 纯 CPU 崩溃：崩在推理但没开 GPU → 不许关 GPU（否则用户白少一个选项）。
    val cpu = NativeCrashParser.parse("kind=native-crash\nsignal=6 (SIGABRT)\nstage=DECODE\ngpu_layers=0\nctx=2048")
    ck("纯 CPU 崩溃不关 GPU", cpu != null && !NativeCrashParser.shouldDisableGpu(cpu))
    ck("纯 CPU 阶段是生成", cpu?.stageLabel == "生成文字")

    // —— W-85：第二次真机闪退的形态（纯 CPU + SIGABRT + prefill + 内存快照）——
    val cpuPf = NativeCrashParser.parse(
        "kind=native-crash\nsignal=6 (SIGABRT)\nstage=PREFILL\ngpu_layers=0\nctx=4096\n" +
            "threads=6\nvulkan_built=1\nsigcode=-6\nmodel=qwen2.5-3b-instruct-q4_k_m.gguf\n" +
            "mem= VmPeak: 3000000 kB VmSize: 2900000 kB VmRSS: 1850000 kB\n"
    )
    ck("纯 CPU prefill 开安全模式", NativeCrashParser.shouldEnableSafeMode(cpuPf))
    ck("纯 CPU prefill 不关 GPU", cpuPf != null && !NativeCrashParser.shouldDisableGpu(cpuPf))
    ck("解析出 VmRSS", cpuPf?.rssKb == 1850000)
    ck("解析出 VmSize", cpuPf?.vmKb == 2900000)
    ck("安全模式说明带内存", NativeCrashParser.describeSafeMode(cpuPf!!).contains("1806 MB"))
    ck("纯 CPU 生成崩溃也开安全模式", NativeCrashParser.shouldEnableSafeMode(cpu))
    // 开 GPU 崩的那次不该动上下文参数：那是显卡的事，降批救不了。
    ck("GPU 崩溃不开安全模式", !NativeCrashParser.shouldEnableSafeMode(c))
    ck("装载阶段崩溃不开安全模式", !NativeCrashParser.shouldEnableSafeMode(
        NativeCrashParser.parse("kind=native-crash\nsignal=6 (SIGABRT)\nstage=MODEL_LOAD\ngpu_layers=0\nctx=4096")
    ))
    // 老版本写的现场没有 mem 行：解析不能因此变成 null（那样会连 GPU 判定一起丢）。
    ck("没有 mem 行时 RSS 为 0 而不是解析失败", cpu?.rssKb == 0)

    // 写到一半的文件（进程死在 write 中间）：不能拿残缺证据关用户的 GPU。
    ck("半个文件当没有", NativeCrashParser.parse("kind=native-crash\nsignal=1") == null)
    // 别的模块写的文件。
    ck("别家文件不认", NativeCrashParser.parse("hello=world") == null)

    println("SUMMARY $ok ${bad.size}")
    bad.forEach { println("  MISS $it") }
}
KT
    "$KOTLINC" -nowarn "$PARSER" "$W2/C.kt" -d "$W2/c.jar" 2>"$W2/err.txt"
    if [ ! -f "$W2/c.jar" ]; then
        fail 'Kotlin 解析断言编译失败'
        sed -n '1,10p' "$W2/err.txt" | sed 's/^/        /'
    else
        OUT="$(java -cp "$W2/c.jar:$STDLIB" CKt 2>&1)"
        echo "$OUT" | grep -v '^SUMMARY' | grep -v '^  MISS' | head -5
        BAD="$(echo "$OUT" | sed -n 's/^SUMMARY [0-9]* \([0-9]*\)$/\1/p')"
        GOOD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) [0-9]*$/\1/p')"
        if [ -z "$GOOD" ]; then
            # 跑都没跑起来（缺 stdlib / 类加载失败）——必须算失败，不能按 0 累加。
            fail 'Kotlin 解析断言没跑起来（没有 SUMMARY）'
            echo "$OUT" | sed -n '1,6p' | sed 's/^/        /'
        else
            PASS=$((PASS + GOOD))
            FAIL=$((FAIL + BAD))
            [ "$BAD" -eq 0 ] && printf '  PASS  Kotlin 解析与判定 %s 条\n' "$GOOD"
            echo "$OUT" | grep '^  MISS' | sed 's/^  MISS/        FAIL /'
        fi
    fi
    rm -rf "$W2"
fi

echo "== 行为：上下文上限（纯函数，真跑） =="
if [ -n "$KOTLINC" ] && [ -n "$STDLIB" ]; then
    W3="$(mktemp -d)"
    cat > "$W3/K.kt" <<'KT'
import com.example.agentkit.core.llm.local.LocalDeviceRules

fun main() {
    var ok = 0
    val bad = ArrayList<String>()
    fun ck(n: String, c: Boolean) { if (c) ok++ else bad.add(n) }
    ck("GPU 开时 16384 压到 4096", LocalDeviceRules.capCtxForGpu(16384, 99) == 4096)
    ck("GPU 开时 2048 不动", LocalDeviceRules.capCtxForGpu(2048, 99) == 2048)
    ck("GPU 开时 8192 压到 4096", LocalDeviceRules.capCtxForGpu(8192, 99) == 4096)
    ck("纯 CPU 保持 16384", LocalDeviceRules.capCtxForGpu(16384, 0) == 16384)
    ck("上限常量就是 4096", LocalDeviceRules.GPU_MAX_CTX == 4096)

    // W-85 安全模式：两个上限叠着判，取更严的那个。
    ck("安全模式把 8192 压到 2048", LocalDeviceRules.effectiveCtx(8192, 0, true) == 2048)
    ck("安全模式把 16384 压到 2048", LocalDeviceRules.effectiveCtx(16384, 0, true) == 2048)
    ck("安全模式下小值不放大", LocalDeviceRules.effectiveCtx(1024, 0, true) == 1024)
    ck("安全模式 + GPU 仍是 2048", LocalDeviceRules.effectiveCtx(8192, 99, true) == 2048)
    ck("非安全 + GPU 仍是 4096", LocalDeviceRules.effectiveCtx(8192, 99, false) == 4096)
    ck("非安全纯 CPU 原样", LocalDeviceRules.effectiveCtx(8192, 0, false) == 8192)
    ck("安全上限常量 2048", LocalDeviceRules.SAFE_MAX_CTX == 2048)
    println("SUMMARY $ok ${bad.size}")
    bad.forEach { println("  MISS $it") }
}
KT
    # LocalDeviceProfile 引用了 LocalBackend（枚举在 Catalog 里），必须一起编。
    "$KOTLINC" -nowarn "$PROFILE" "$CATALOG" "$W3/K.kt" -d "$W3/k.jar" 2>"$W3/err.txt"
    if [ ! -f "$W3/k.jar" ]; then
        fail '上下文上限断言编译失败'
        sed -n '1,8p' "$W3/err.txt" | sed 's/^/        /'
    else
        OUT="$(java -cp "$W3/k.jar:$STDLIB" KKt 2>&1)"
        BAD="$(echo "$OUT" | sed -n 's/^SUMMARY [0-9]* \([0-9]*\)$/\1/p')"
        GOOD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) [0-9]*$/\1/p')"
        if [ -z "$GOOD" ]; then
            fail '上下文上限断言没跑起来'
            echo "$OUT" | sed -n '1,6p' | sed 's/^/        /'
        else
            PASS=$((PASS + GOOD))
            FAIL=$((FAIL + BAD))
            [ "$BAD" -eq 0 ] && printf '  PASS  上下文上限 %s 条\n' "$GOOD"
            echo "$OUT" | grep '^  MISS' | sed 's/^  MISS/        FAIL /'
        fi
    fi
    rm -rf "$W3"
fi

echo
echo "SUMMARY：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
