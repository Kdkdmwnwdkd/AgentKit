# AgentKit 本地推理 native 构建。
#
# 结构：
#   llama-jni.cpp          —— 自研 JNI 桥（本仓库提交）
#   llama.cpp/             —— llama.cpp v0.7.0 源码（构建前由 scripts/fetch-llama.sh 拉取，不进 git）
#
# 只编 arm64-v8a。CPU 后端恒开，Vulkan 后端由工具链是否就绪决定（见下方 AK_HAS_VULKAN）。

cmake_minimum_required(VERSION 3.22.1)
project(agentkit-llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# 优化级别：不管外面传的是 Debug 还是 Release，ggml 一律按 -O3 编译。
#
# 这是真机上「1 个 token 要 24 秒」的根因。AGP 在 debug 构建里会给
# externalNativeBuild 传 `-DCMAKE_BUILD_TYPE=Debug`，而 CMake 的 Debug 默认
# flags 只有 `-g`（等价 -O0）。Q4_K 的点积在 -O0 下退化成逐元素标量循环，
# 没有向量化、没有寄存器复用、每个内层迭代都在做函数调用，比 -O3 慢 50 倍上下。
#
# 实测对照（同一台手机、同一个 3B Q4_K_M，1.444 出字那版）：
#   -O0：预热 1 token 24.5 秒，prefill 0.4 tok/s，生成 0.3 tok/s，首字 220 秒
#   -O3：预期 1 token 0.2~0.4 秒，prefill 20~40 tok/s，生成 3~5 tok/s
#
# 三层保险，任意一层单独成立就够：
#   ① 直接把 build type 改成 Release（FORCE 能压掉命令行 -D 传进来的值）
#   ② 把 Debug 的 flags 字符串整个替换成 -O3（万一 ① 被 AGP 又改回去）
#   ③ 给每个 ggml target 追加 PRIVATE -O3（万一是 target 级 flags 覆盖了全局）
# ---------------------------------------------------------------------------
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)

foreach(_lang C CXX)
    set(CMAKE_${_lang}_FLAGS_DEBUG "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_${_lang}_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG" CACHE STRING "" FORCE)
endforeach()
message(STATUS "[agentkit] build type: ${CMAKE_BUILD_TYPE} / CXX debug flags: ${CMAKE_CXX_FLAGS_DEBUG}")

set(LLAMA_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (NOT EXISTS "${LLAMA_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp 源码缺失：请先运行 scripts/fetch-llama.sh 拉取固定 tag v0.7.0。")
endif()

# ---- llama.cpp 裁剪：Android 单架构 CPU 版 ----
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)  # 静态链进我们的 .so，APK 只多一个文件

set(GGML_NATIVE    OFF CACHE BOOL "" FORCE)  # 交叉编译禁 native 检测，否则会把主机 x86  flag 带给 arm64
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)  # NDK 无 OpenMP 运行时
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL    OFF CACHE BOOL "" FORCE)
set(GGML_CUDA      OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS   OFF CACHE BOOL "" FORCE)
set(GGML_METAL     OFF CACHE BOOL "" FORCE)
set(GGML_SYCL      OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE   OFF CACHE BOOL "" FORCE)
set(GGML_RPC       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_HBM   OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)  # 单架构，不需要多后端变体

# ---------------------------------------------------------------------------
# GPU 后端（Vulkan）：覆盖「非高通」与「8 Gen 1 以下」的唯一一条路
#
# 为什么值得开：NPU 是一机一包（LiteRT 的 QNN context 按具体 Hexagon 版本 AOT 编译），
# 天玑/麒麟/老骁龙连这条路都没有。Vulkan 是唯一「一份 shader 跑遍所有支持它的 GPU」
# 的方案，Adreno 与 Mali 都吃。
#
# 一个必须先说清的门槛：ggml-vulkan 要求 **Vulkan 1.2**（不是 1.1），源码里是
#   if (api_version < VK_API_VERSION_1_2) throw ...
# 这意味着 Android 10/11 且驱动只给 1.1 的老机仍然只能走 CPU。判定在 Kotlin 侧
# （LocalDeviceRules.vulkanUsable），这里只负责「编得出来」。
#
# 失败是安全的：ggml_backend_vk_reg() 内部 try/catch，初始化失败返回 nullptr，
# 注册表跳过这个后端，llama 继续用 CPU。所以一个包可以同时给有/没有 Vulkan 的设备。
#
# 工具链三件套（缺一不可，CI 里由 workflow 安装）：
#   1. vulkan.h + libvulkan.so  —— NDK sysroot 自带
#   2. glslc（host 侧可执行）    —— 把 .comp 编成 SPIR-V
#   3. SPIRV-Headers 的 CMake 包 —— find_package(SPIRV-Headers CONFIG REQUIRED) 要它
#
# 为什么用环境变量而不是写死路径：Android Studio 里没有这套工具链，本地构建要能
# 静默退回 CPU-only（否则每个开发者都编不过）。但 CI 里必须失败而不是静默降级——
# 设了 AK_REQUIRE_VULKAN 就 FATAL，避免「以为开了 GPU，出的包其实只有 CPU」。
# ---------------------------------------------------------------------------
set(AK_HAS_VULKAN OFF)
set(_ak_glslc "$ENV{AK_GLSLC}")
set(_ak_spirv "$ENV{AK_SPIRV_DIR}")
# Vulkan-Hpp（vulkan.hpp）与 spirv.hpp 的暂存目录，见下方说明。
set(_ak_vkinc "$ENV{AK_VULKAN_INCLUDE}")

if (_ak_glslc AND EXISTS "${_ak_glslc}" AND _ak_spirv AND EXISTS "${_ak_spirv}")
    # FindVulkan 的变量名不是 AK_*：写成 cache 变量让它跳过搜索，直接认我们给的。
    # 跨编译时它自己找 glslc 会撞上 CMAKE_FIND_ROOT_PATH（只在 sysroot 里搜），必失败。
    set(Vulkan_GLSLC_EXECUTABLE "${_ak_glslc}" CACHE FILEPATH "" FORCE)
    set(SPIRV-Headers_DIR "${_ak_spirv}" CACHE PATH "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(AK_HAS_VULKAN ON)
    message(STATUS "[agentkit] Vulkan: ON（glslc=${_ak_glslc}，spirv=${_ak_spirv}）")

    # NDK 的 sysroot 里只有 C 头 vulkan.h，**没有 Vulkan-Hpp（vulkan.hpp）**，
    # 而 ggml-vulkan-types.h 第一行就 include 它；同理 spirv.hpp 也只有宿主包里有。
    # 两者都是纯头文件、与架构无关，用宿主的同一份即可。
    #
    # 但**绝不能直接把 /usr/include 塞进 -I**：那会把 glibc 的 stdio.h 等一起暴露给
    # NDK 编译（-I 排在 -isystem 前面），bionic 的头会被顶掉，报一堆莫名其妙的错。
    # 所以约定一个「只含 vulkan/ 与 spirv/ 两个符号链接」的暂存目录，由 CI 准备。
    if (_ak_vkinc AND EXISTS "${_ak_vkinc}/vulkan/vulkan.hpp")
        message(STATUS "[agentkit] Vulkan-Hpp: ${_ak_vkinc}")
    elseif (DEFINED ENV{AK_REQUIRE_VULKAN})
        message(FATAL_ERROR
            "[agentkit] Vulkan: 缺 Vulkan-Hpp（AK_VULKAN_INCLUDE=${_ak_vkinc}）。"
            "NDK sysroot 不带 vulkan.hpp，需要 libvulkan-dev 的头，并做成只含 "
            "vulkan/ 与 spirv/ 的暂存目录（不要把 /usr/include 整个塞进 -I）。")
    endif()
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    if (DEFINED ENV{AK_REQUIRE_VULKAN})
        message(FATAL_ERROR
            "[agentkit] Vulkan: 要求开启但工具链缺失（AK_GLSLC=${_ak_glslc}，AK_SPIRV_DIR=${_ak_spirv}）。"
            "CI 应安装 glslc 与 SPIRV-Headers；不想要 GPU 后端就不要设 AK_REQUIRE_VULKAN。")
    endif()
    message(STATUS "[agentkit] Vulkan: OFF（未提供 glslc / SPIRV-Headers，本次构建为 CPU-only）")
endif()

# 量化推理的算力几乎全在 Q4 点积上，而基线 armv8-a 只能用通用的 NEON 实现。
# 抬到 armv8.2-a + dotprod + fp16 后，ggml 会编出 dotprod/i8mm 之外的专用
# vec_dot kernel（同一模型同一设备实测生成速度约为基线的 1.5~2 倍）。
#
# 为什么是 dotprod+fp16 而不是 i8mm：i8mm 属 ARMv8.6，只有骁龙 8 Gen 1 及
# 以上才有；dotprod/fp16 从 Cortex-A75（2017）起普及，覆盖面大得多。
# 代价是这版 .so 不能在 armv8.0 老 CPU 上跑 —— 由 LlamaJni 在 loadLibrary
# 前读 /proc/cpuinfo 检查 asimddp/asimdhp，缺了就拒绝加载并给文案，不会 SIGILL。
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
# 权重重排（repack）= OFF：显式关掉运行时把 Q4_0/Q4_K 重排成 4x4/8x8 交错布局。
#
# 这个开关打开时，模型里的量化权重会在首次 decode 时被重排，换取更长的向量内积。
# 理论上更快，但真机实测的表现是「加载 5 秒正常，第一次 decode 就再也不返回」
# ——158 token 的 prefill 卡满 180 秒没有任何进度。重排是懒执行的，正好落在第一次
# decode 里，且 b4600 这条路径在部分 ARM 设备上会反复重排（没有正确命中缓存），
# 一次 forward 就够把预算烧光。
#
# 关掉它意味着走标准 NEON + dotprod 内核。IRIDIS 在同款设备上跑出 4.8 tok/s
# 走的也是标准路径，说明这条路的绝对速度够用。宁可少 10% 峰值，也不要「不出字」。
#
# v0.7.0 把开关改了名（GGML_CPU_AARCH64 → GGML_CPU_REPACK），语义也从「aarch64 专用」
# 泛化成「Q4_0 运行时转换」，**默认值仍是 ON**。两个名字都显式设 OFF：
#   - 新名对 v0.7.0 生效，这条是必需的（不设就会吃到默认 ON，旧问题原样复现）；
#   - 旧名对 b4600 生效，留着是为了 tag 回退时不用再改一遍 CMake。
# 未使用的 cache 变量在 CMake 里只是躺在那儿，不报错。
set(GGML_CPU_REPACK   OFF CACHE BOOL "" FORCE)
set(GGML_CPU_AARCH64  OFF CACHE BOOL "" FORCE)
# KleidiAI（ARM 官方微内核库）= OFF。
#
# v0.7.0 新增，默认关。它会给 k-quant matmul 换上手写汇编（SME2/i8mm 路径），
# 对 arm64 是实打实的加速；但构建时要额外从 GitHub Release 下 ARM 的源码包，
# 在 CI 里是一处新的网络依赖。等版本本身在真机跑稳了再单独开，一次只动一个变量。
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SOURCE_DIR}" llama.cpp-build EXCLUDE_FROM_ALL)

# 第二层保险：直接给 ggml 的每个编译 target 追加 -O3。
#
# 上面改的是 CMAKE_CXX_FLAGS_DEBUG，那是全局 flags；如果某个 backend 用自己的
# opts 覆盖（ggml 的部分 CMakeLists 会显式 set target 级 flags），全局值就失效。
# gcc/clang 对多个 -O 取「最后一个」，所以这里的 PRIVATE -O3 追加在编译命令尾部，
# 能压掉前面任何一个 -O0。
foreach(_t ggml ggml-base ggml-cpu llama)
    if (TARGET ${_t})
        target_compile_options(${_t} PRIVATE -O3)
        message(STATUS "[agentkit] ${_t}: 追加 -O3")
    endif()
endforeach()

# Vulkan 后端的 shader 是预编译的 SPIR-V 数组，-O0/-O3 对它的体积与速度都没影响，
# 但统一加上能省掉「为什么只有这个 target 是 -O0」的疑问。
if (TARGET ggml-vulkan)
    target_compile_options(ggml-vulkan PRIVATE -O3)
    message(STATUS "[agentkit] ggml-vulkan: 追加 -O3")
    # BEFORE：宿主的 vulkan/ 与 spirv/ 必须排在 NDK sysroot 之前才能被找到
    # （sysroot 里没有这两个目录，其实谁先都行；写 BEFORE 是为了让意图明确，
    # 也防止将来某个 NDK 版本开始带一份老 vulkan.hpp 时被悄悄用上）。
    if (_ak_vkinc AND EXISTS "${_ak_vkinc}/vulkan/vulkan.hpp")
        target_include_directories(ggml-vulkan BEFORE PRIVATE "${_ak_vkinc}")
    endif()
    # bionic 到 API 28 才有 aligned_alloc，本项目 minSdk 26。ggml-vulkan-buffers.cpp
    # 用 std::aligned_alloc，会撞上 libc++ 的 `using ::aligned_alloc _LIBCPP_USING_IF_EXISTS`
    # （名字不存在 → 引用即「unresolved using declaration」）。
    #
    # 补声明必须早于 <cstdlib>（名字查找发生在那一行），所以这里用 -include 塞一个
    # 兼容头，而不是去改第三方源码——改了下次升级 llama.cpp 就丢。
    # 头文件自己按 __ANDROID_API__ 判空，API >= 28 时什么也不做。
    target_compile_options(ggml-vulkan PRIVATE
        -include "${CMAKE_CURRENT_LIST_DIR}/vulkan-aligned-compat.h")
endif()

# ---- 我们的 JNI 桥 ----
add_library(agentkit-llama SHARED llama-jni.cpp)

target_include_directories(agentkit-llama PRIVATE
    "${LLAMA_SOURCE_DIR}/include"
    "${LLAMA_SOURCE_DIR}/ggml/include"
)

target_link_libraries(agentkit-llama
    llama          # llama.cpp 静态库（内部已链 ggml + 线程）
    android
    log
)

# 静态库里带了 libggml-vulkan.a 时，libvulkan.so 的符号要在最终链接这一步解析。
# 静态库的 PRIVATE 依赖会以 $<LINK_ONLY:...> 传播，理论上够；显式再写一次是因为
# 「理论上够」在这类交叉编译里失败过一次就够让人怀疑人生，成本只有一行。
if (AK_HAS_VULKAN)
    # 链接期必炸的一个符号：`vkGetPhysicalDeviceFeatures2` 是 Vulkan **1.1** 的核心函数，
    # 而 Android 的 libvulkan.so 从 **API 28** 才导出 1.1 的入口点；本项目 minSdk 26，
    # 链接时取的是 .../android26/libvulkan.so 桩，里面只有 1.0 的函数（run 459 的报错）。
    #
    # 不能拿 API 28 的桩去链：Android 8.x 机器上根本没这个符号，急绑定会让**整个 .so
    # 加载失败**——纯 CPU 推理一起废。所以这里自己提供符号、运行时转发（见源文件注释）。
    target_sources(agentkit-llama PRIVATE vulkan-entry-shim.cpp)
    target_link_libraries(agentkit-llama vulkan)
    # 让 C++ 侧用编译期事实回答「这个包装了 GPU 后端没有」（见 nativeGpuBackend）。
    # 用宏而不是让 Kotlin 写常量：常量要人同步，忘了改就会 UI 显示 GPU 可用、实际只有 CPU。
    target_compile_definitions(agentkit-llama PRIVATE AK_HAS_VULKAN=1)
endif()

# 静态库里的 1000+ 个 llama_*/ggml_* 符号不该出现在 .so 的动态导出表：
# 既污染符号空间又有潜在冲突风险。JNI 函数在本文件里编译，不受影响。
target_link_options(agentkit-llama PRIVATE "-Wl,--exclude-libs,ALL")

# ---- 终端组件 JNI 桥（pty 薄封装，自写，无第三方依赖）----
# forkpty/openpty 由 bionic（libc）直接提供，无需额外链接库。
add_library(agentkit-termux SHARED termux-jni/termux-jni.cpp)

target_link_libraries(agentkit-termux
    android
    log
)
