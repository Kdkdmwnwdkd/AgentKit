#!/usr/bin/env bash
# Vulkan（GPU）后端验证：能不能编进去、编进去之后准不准、准了之后会不会静默降级。
#
# 为什么这个脚本比别的都值得写：Vulkan 失败的方式是**安静**的。
# ggml 的 vk 后端在 reg_init 里 try/catch，初始化失败返回 nullptr，注册表直接把
# 这个后端跳过——模型照样加载成功，只是全程跑在 CPU 上。用户看到的是「开了 GPU
# 还是很慢」，我们看到的是「一切正常」。所以这一层必须有三道独立的证据：
#
#   1. 构建期：CMake 真的把 GGML_VULKAN 打开了（而不是被某个默认值关掉）；
#   2. 产物期：.so 里真的带着 libvulkan.so 依赖（编了但没链上等于没编）；
#   3. 运行期：设备判定真的会把该开的机型开起来、该关的关掉（规则反了比不编更坏）。
#
# 第 3 条不能靠 grep —— 静态检查查不出「vulkanPath 把 1.1 判成可用」这种事。
# 所以行为断言全部是真编真跑。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

DIR="app/src/main/java/com/example/agentkit/core/llm/local"
PROFILE="$DIR/LocalDeviceProfile.kt"
CATALOG="$DIR/LocalBackendCatalog.kt"
JNI="app/src/main/java/com/example/agentkit/core/llm/local/LlamaJni.kt"
CLIENT="$DIR/LocalLlamaClient.kt"
CMAKELISTS="app/src/main/cpp/CMakeLists.txt"
CPP="app/src/main/cpp/llama-jni.cpp"
WORKFLOW=".github/workflows/build.yml"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

has() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then pass "$name"; else fail "$name（缺：$needle）"; fi
}

hasnt() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then fail "$name（不该出现：$needle）"; else pass "$name"; fi
}

echo "== 静态：CMake 真的把 Vulkan 打开了 =="

# 曾经的写法是无条件 `set(GGML_VULKAN OFF ... FORCE)`。现在必须走「工具链就绪才开」。
hasnt "没有无条件关闭 Vulkan" "$CMAKELISTS" "set(GGML_VULKAN    OFF CACHE BOOL"
has "工具链就绪时打开" "$CMAKELISTS" "set(GGML_VULKAN ON CACHE BOOL"
# 只认 AK_GLSLC / AK_SPIRV_DIR 两个环境变量：Android Studio 里没有这套工具链，
# 本地构建要能静默退回 CPU-only，否则每个开发者都编不过。
has "glslc 路径来自环境变量" "$CMAKELISTS" 'set(_ak_glslc "$ENV{AK_GLSLC}")'
has "SPIRV-Headers 路径来自环境变量" "$CMAKELISTS" 'set(_ak_spirv "$ENV{AK_SPIRV_DIR}")'
# 跳过 FindVulkan 自己的搜索：交叉编译时它只在 sysroot 里找 glslc，必失败。
has "显式喂 glslc 给 FindVulkan" "$CMAKELISTS" 'Vulkan_GLSLC_EXECUTABLE "${_ak_glslc}"'
has "显式喂 SPIRV-Headers_DIR" "$CMAKELISTS" 'SPIRV-Headers_DIR "${_ak_spirv}"'
# CI 里必须失败而不是静默降级：否则「以为开了 GPU，出的包其实只有 CPU」。
has "CI 可要求必须开 Vulkan" "$CMAKELISTS" "AK_REQUIRE_VULKAN"
has "缺工具链时报错而非降级" "$CMAKELISTS" "Vulkan: 要求开启但工具链缺失"

echo "== 静态：编译期事实一路传到 Kotlin =="

has "编了才定义宏" "$CMAKELISTS" "AK_HAS_VULKAN=1"
# 静态库的 PRIVATE 依赖会以 $<LINK_ONLY> 传播，但这类交叉编译里「理论上够」失败过一次。
has "显式链接 libvulkan" "$CMAKELISTS" "target_link_libraries(agentkit-llama vulkan)"
has "native 回答是否编入" "$CPP" "Java_com_example_agentkit_core_llm_local_LlamaJni_nativeGpuBackend"
has "Kotlin 侧读编译期事实" "$JNI" "nativeGpuBackend()"
# 这里必须是 runCatching：.so 是旧的（没有这个符号）时返回 false，而不是整页崩掉。
has "读不到就当没编（不崩）" "$JNI" 'runCatching { nativeGpuBackend() }.getOrDefault(false)'

echo "== 静态：运行期能证明「到底上没上 GPU」 =="

has "设备枚举走 ggml 注册表" "$CPP" "ggml_backend_dev_count()"
has "对外暴露设备清单" "$CPP" "Java_com_example_agentkit_core_llm_local_LlamaJni_nativeGpuProbe"
# 请求了 GPU 却一个 GPU 设备都没有，必须当场报错——这是「以为在用 GPU」的唯一防线。
has "请求 GPU 但无 GPU 设备时报警" "$CPP" "但没有任何 GPU 后端可用"
# IGPU 也是 GPU。漏算的结果是「明明有显卡却说没有」。
has "集显也计入 GPU" "$CPP" "GGML_BACKEND_DEVICE_TYPE_IGPU"

echo "== 静态：装载路径不许再写死 0 层 =="

has "层数由规则层给" "$CLIENT" "engine.acquire(file.absolutePath, setting.nCtx, setting.nThreads, gpu.layers)"
has "两个前提分开判" "$CLIENT" "LlamaJni.gpuBackendAvailable"
has "没画像就退回 CPU" "$CLIENT" "设备画像尚未采集完成"
# GPU 装载失败必须能退回 CPU，且留下原因。少了这条，一台驱动有问题的机器会
# 「点开 GPU 之后本地推理彻底不可用」——比慢得多更糟。
has "GPU 装载失败退回 CPU" "$CLIENT" "退回纯 CPU 重试一次"
has "退回要说明怎么关掉" "$CLIENT" "GPU 加速」里选「关闭」"
# GPU 结论每轮都会重算，日志必须去重，否则十轮对话刷十行同样的内容。
has "GPU 日志去重" "$CLIENT" "if (line != lastGpuLogLine)"

echo "== 静态：CI 装齐工具链三件套 =="

has "CI 装 glslc" "$WORKFLOW" "apt-get install"
has "CI 装 glslc 包" "$WORKFLOW" "glslc"
has "CI 装 SPIRV-Headers" "$WORKFLOW" "spirv-headers"
has "CI 导出 AK_GLSLC" "$WORKFLOW" "AK_GLSLC="
has "CI 导出 AK_SPIRV_DIR" "$WORKFLOW" "AK_SPIRV_DIR="
has "CI 要求必须开 Vulkan" "$WORKFLOW" "AK_REQUIRE_VULKAN=1"

echo "== 行为：真编一遍（规则反了静态 grep 抓不出来） =="

KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="kotlinc"
elif [ -x "$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc" ]; then
    KOTLINC="$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc"
elif [ -x /opt/kotlinc/bin/kotlinc ]; then
    KOTLINC="/opt/kotlinc/bin/kotlinc"
fi

if [ -z "$KOTLINC" ]; then
    echo "  SKIP  未找到 kotlinc，跳过行为断言（CI 里由 gradle 真编）"
else
    WORK="$(mktemp -d)"
    cat > "$WORK/VulkanCheck.kt" <<'KOTLIN'
import com.example.agentkit.core.llm.local.*

private var ok = 0
private var bad = 0

private fun check(name: String, cond: Boolean, hint: String = "") {
    if (cond) { ok++; println("  PASS  $name") }
    else { bad++; println("  FAIL  $name${if (hint.isEmpty()) "" else "（$hint）"}") }
}

private fun p(gl: String?, vk: String?, soc: String? = null, manu: String? = null): LocalDeviceProfile =
    LocalDeviceProfile(
        socModel = soc, socManufacturer = manu, hardware = null,
        glRenderer = gl, totalRamMb = 12288, vulkanVersion = vk,
    )

private fun advice(gl: String?, vk: String?) = LocalDeviceRules.advise(p(gl, vk))

fun main() {
    // ---- vulkanPath：先看版本，再看家族 ----
    val a11 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 660", "1.1"))
    check("Vulkan 1.1 一律不可用", a11.first == GpuPath.NONE && a11.second!!.contains("1.2"), a11.toString())
    val a12 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 660", "1.2"))
    check("Vulkan 1.2 刚好够", a12.first == GpuPath.STABLE, a12.toString())
    val a13 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 750", "1.3"))
    check("Adreno + 1.3 稳妥", a13.first == GpuPath.STABLE && a13.second == null, a13.toString())
    // 系统给的是 "Vulkan 1.3" 这种带前缀的串，不能解析失败。
    val aPre = LocalDeviceRules.vulkanPath(p("Adreno (TM) 750", "Vulkan 1.3"))
    check("带 Vulkan 前缀也能解析", aPre.first == GpuPath.STABLE, aPre.toString())
    val mali = LocalDeviceRules.vulkanPath(p("Immortalis-G720 MC12", "1.3"))
    check("Mali 系是「需验证」不是「不可用」", mali.first == GpuPath.RISKY, mali.toString())
    check("Mali 的原因可解释", mali.second == null, mali.second ?: "null")
    val pvr = LocalDeviceRules.vulkanPath(p("PowerVR Rogue GE8320", "1.3"))
    check("PowerVR 劝退", pvr.first == GpuPath.NONE && pvr.second!!.contains("驱动"), pvr.toString())
    val noVer = LocalDeviceRules.vulkanPath(p("Adreno (TM) 750", null))
    check("没报版本按不支持", noVer.first == GpuPath.NONE && noVer.second!!.contains("未报告"), noVer.toString())
    val noGpu = LocalDeviceRules.vulkanPath(p(null, "1.3"))
    check("没 GPU 型号按不支持", noGpu.first == GpuPath.NONE, noGpu.toString())

    // ---- resolveGpu：两个前提缺一不可 ----
    val stable = advice("Adreno (TM) 750", "1.3")
    val risky = advice("Mali-G715 MC11", "1.3")
    val none = advice("Adreno (TM) 660", "1.1")

    check("自动 + 稳妥 → 开", LocalDeviceRules.resolveGpu(stable, true, GpuMode.AUTO).layers == 99)
    check("自动 + 需验证 → 关", LocalDeviceRules.resolveGpu(risky, true, GpuMode.AUTO).layers == 0)
    check("自动 + 需验证 → 有原因",
        LocalDeviceRules.resolveGpu(risky, true, GpuMode.AUTO).reason?.contains("自动模式") == true,
        LocalDeviceRules.resolveGpu(risky, true, GpuMode.AUTO).reason ?: "null")
    check("强制 + 需验证 → 开", LocalDeviceRules.resolveGpu(risky, true, GpuMode.FORCE).layers == 99)
    // 强制也不越界：版本都不够，开了只会白白多等一次初始化再退回。
    check("强制 + 不可用 → 仍关", LocalDeviceRules.resolveGpu(none, true, GpuMode.FORCE).layers == 0)
    check("关闭 → 关", LocalDeviceRules.resolveGpu(stable, true, GpuMode.OFF).layers == 0)
    // 包没编 Vulkan 时，哪怕设备再强也必须是 0——这一条挡的是「UI 说开了、实际没编」。
    val notCompiled = LocalDeviceRules.resolveGpu(stable, false, GpuMode.AUTO)
    check("包没编 → 关", notCompiled.layers == 0)
    check("包没编 → 原因是「未编入」", notCompiled.reason?.contains("未编入") == true, notCompiled.reason ?: "null")
    check("层数是「全卸」而不是写死模型层数", LocalDeviceRules.GPU_MAX_LAYERS == 99)

    // ---- 后端目录：选项表与规则层同源 ----
    val envOn = LocalEngineEnvironment(llamaGpuEnabled = true, device = p("Adreno (TM) 750", "1.3"))
    val optsOn = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOn)
    val gpuOn = optsOn.first { it.backend == LocalBackend.GPU }
    check("编入 + 稳妥 → GPU 可选", gpuOn.available)
    check("GPU 说明带型号与版本",
        gpuOn.detail.contains("Adreno 750") && gpuOn.detail.contains("1.3"), gpuOn.detail)

    val envMali = LocalEngineEnvironment(llamaGpuEnabled = true, device = p("Mali-G715 MC11", "1.3"))
    val gpuMali = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envMali)
        .first { it.backend == LocalBackend.GPU }
    check("编入 + 需验证 → 仍灰", !gpuMali.available)
    check("灰掉时告诉用户怎么强开", gpuMali.reason!!.contains("强制开启"), gpuMali.reason!!)

    val envOld = LocalEngineEnvironment(llamaGpuEnabled = true, device = p("Adreno (TM) 660", "1.1"))
    val gpuOld = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOld)
        .first { it.backend == LocalBackend.GPU }
    check("编入 + 版本不够 → 灰", !gpuOld.available)
    check("灰掉原因是版本", gpuOld.reason!!.contains("1.2"), gpuOld.reason!!)

    val envOff = LocalEngineEnvironment(llamaGpuEnabled = false, device = p("Adreno (TM) 750", "1.3"))
    val gpuOff = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOff)
        .first { it.backend == LocalBackend.GPU }
    check("没编 → 灰", !gpuOff.available)
    check("没编时说明外加一句本机情况", gpuOff.reason!!.contains("本机 Adreno 750"), gpuOff.reason!!)
    check("没编时仍承诺 CPU 可用",
        LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOff)
            .first { it.backend == LocalBackend.CPU }.available)

    // 没有画像但有 hasVulkan：能开，但说明里要承认「不知道是什么卡」。
    val envBare = LocalEngineEnvironment(llamaGpuEnabled = true, hasVulkan = true)
    val gpuBare = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envBare)
        .first { it.backend == LocalBackend.GPU }
    check("无画像 + hasVulkan → 可选", gpuBare.available)
    check("无画像时承认型号未知", gpuBare.detail.contains("未取得"), gpuBare.detail)

    val envNoVk = LocalEngineEnvironment(llamaGpuEnabled = true, hasVulkan = false)
    val gpuNoVk = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envNoVk)
        .first { it.backend == LocalBackend.GPU }
    check("无画像 + 无 Vulkan → 灰", !gpuNoVk.available)

    // ---- 装载链：GPU 不可用时不能把整条链弄空 ----
    val plan = LocalEngineCatalog.planLoad(LocalBackend.GPU, optsOn)
    check("首选 GPU 时链路从 GPU 起", plan.chain.first() == LocalBackend.GPU, plan.chain.toString())
    val planOld = LocalEngineCatalog.planLoad(LocalBackend.GPU, LocalEngineCatalog.options(
        LocalModelFormat.GGUF, "a.gguf", envOld))
    check("GPU 不可用时降级到 CPU", planOld.chain == listOf(LocalBackend.CPU), planOld.chain.toString())
    check("降级要留话", planOld.notes.any { it.contains("1.2") }, planOld.notes.toString())

    println("SUMMARY $ok $bad")
}
KOTLIN
    STDLIB="${KOTLIN_STDLIB:-}"
    if [ -z "$STDLIB" ]; then
        GUESS="$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"
        [ -f "$GUESS" ] && STDLIB="$GUESS"
    fi
    "$KOTLINC" -nowarn "$PROFILE" "$CATALOG" "$WORK/VulkanCheck.kt" -d "$WORK/check.jar" 2>"$WORK/err.txt"
    if [ ! -f "$WORK/check.jar" ]; then
        fail "Kotlin 编译失败"
        sed -n '1,15p' "$WORK/err.txt" | sed 's/^/        /'
    else
        OUT="$(java -cp "$WORK/check.jar${STDLIB:+:$STDLIB}" VulkanCheckKt 2>&1)"
        echo "$OUT" | grep -v '^SUMMARY'
        BAD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) \([0-9]*\)$/\2/p')"
        GOOD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) \([0-9]*\)$/\1/p')"
        PASS=$((PASS + ${GOOD:-0}))
        FAIL=$((FAIL + ${BAD:-0}))
    fi
    rm -rf "$WORK"
fi

echo "== 产物：.so 里真的链上了 libvulkan =="

# 「编了 Vulkan」和「链上了 Vulkan」是两件事。cmake 把 -DGGML_VULKAN=ON 收下、
# 却在最终链接时把 ggml-vulkan 静态库丢掉，产物照样叫成功——只有 readelf 能证伪。
APK=""
for cand in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/app-debug-unsigned.apk; do
    [ -f "$cand" ] && APK="$cand" && break
done

if [ -z "$APK" ]; then
    echo "  SKIP  本机没有构建产物（CI 构建阶段之后会跑这一项）"
elif ! command -v readelf >/dev/null 2>&1; then
    echo "  SKIP  没有 readelf（binutils 未安装）"
else
    TMP="$(mktemp -d)"
    if unzip -o -q "$APK" "lib/arm64-v8a/*" -d "$TMP" 2>/dev/null; then
        SO="$TMP/lib/arm64-v8a/libagentkit-llama.so"
        if [ ! -f "$SO" ]; then
            fail "APK 里没有 libagentkit-llama.so"
        else
            if readelf -d "$SO" 2>/dev/null | grep -q "libvulkan.so"; then
                pass "产物依赖 libvulkan.so"
            else
                fail "产物没有链上 libvulkan.so（GGML_VULKAN 开了但后端被链接器丢掉）"
            fi
            # ggml-vulkan.cpp 里的日志前缀。它只可能来自那个翻译单元，
            # 比找 "Vulkan" 这种随处可见的词可靠得多（llama.cpp 自己的报错里也有 Vulkan）。
            if strings "$SO" 2>/dev/null | grep -q "ggml_vulkan:"; then
                pass "产物含 ggml-vulkan 代码（日志前缀在）"
            else
                fail "产物里找不到 ggml_vulkan: 前缀，Vulkan 后端可能没被链接进去"
            fi
        fi
    else
        fail "APK 解压失败：$APK"
    fi
    rm -rf "$TMP"
fi

echo
echo "SUMMARY：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
