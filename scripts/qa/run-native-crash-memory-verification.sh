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
# 静态 grep 只能证明「代码写了」，证明不了「处理器真的装上了、真的写了文件、真的没吞信号」。
# 所以关键断言一律 fork 子进程 raise(SIGSEGV) 真跑一遍，再读文件核对内容。
set -uo pipefail

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

echo "== 静态：显存保护（这次的直接原因） =="
has "$PROFILE" "const val GPU_MAX_CTX = 4096" 'GPU 开启时上下文上限 4096'
has "$CLIENT" "LocalDeviceRules.capCtxForGpu" '装载时真的用了这个上限'
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
int main(int argc, char** argv) {
    if (argc < 3) return 2;
    pid_t p = fork();
    if (p == 0) {
        ak_crash_install(argv[1]);
        // 复刻真机那次：3B 模型、ctx 16384、GPU 全量卸载、崩在装载。
        ak_crash_context("qwen2.5-3b-instruct-q4_k_m.gguf", 16384, 6, 99);
        ak_stage_set(AK_STAGE_MODEL_LOAD);
        raise(argc > 3 ? SIGABRT : SIGSEGV);
        _exit(0);
    }
    int st = 0;
    waitpid(p, &st, 0);
    // 第一个参数 = 目录；第二个 = 期望的信号；第三个 = 额外参数（选信号）
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
            OUT="$("$W/t" "$W" x 2>/dev/null)"
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
            fi

            # ③ 纯 CPU 崩溃不该触发「关 GPU」：判据只有 gpu_layers，写错一位就会误伤。
            rm -f "$F"
            "$W/t" "$W" x >/dev/null 2>&1
        fi
        rm -rf "$W"
    fi
fi

echo "== 行为：Kotlin 解析与判定（真编真跑） =="

KOTLINC=""
command -v kotlinc >/dev/null 2>&1 && KOTLINC="$(command -v kotlinc)"
STDLIB=""
for cand in \
    "$HOME/.sdkman/candidates/kotlin/current/lib/kotlin-stdlib.jar" \
    "/tmp/khome/lib/kotlin-stdlib.jar" \
    "/usr/share/kotlin/lib/kotlin-stdlib.jar" \
    "$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"; do
    [ -f "$cand" ] && { STDLIB="$cand"; break; }
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
