# AgentKit 本地推理 native 构建。
#
# 结构：
#   llama-jni.cpp          —— 自研 JNI 桥（本仓库提交）
#   llama.cpp/             —— llama.cpp b4600 源码（构建前由 scripts/fetch-llama.sh 拉取，不进 git）
#
# 只编 arm64-v8a 的纯 CPU 版：GPU 后端（Vulkan/OpenCL）留待后续迭代，
# 先把「能跑」做扎实。关闭一切用不到的组件，APK 体积和编译时间都受益。

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
        "llama.cpp 源码缺失：请先运行 scripts/fetch-llama.sh 拉取固定 tag b4600。")
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
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_CUDA      OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS   OFF CACHE BOOL "" FORCE)
set(GGML_METAL     OFF CACHE BOOL "" FORCE)
set(GGML_SYCL      OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE   OFF CACHE BOOL "" FORCE)
set(GGML_RPC       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_HBM   OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)  # 单架构，不需要多后端变体

# 量化推理的算力几乎全在 Q4 点积上，而基线 armv8-a 只能用通用的 NEON 实现。
# 抬到 armv8.2-a + dotprod + fp16 后，ggml 会编出 dotprod/i8mm 之外的专用
# vec_dot kernel（同一模型同一设备实测生成速度约为基线的 1.5~2 倍）。
#
# 为什么是 dotprod+fp16 而不是 i8mm：i8mm 属 ARMv8.6，只有骁龙 8 Gen 1 及
# 以上才有；dotprod/fp16 从 Cortex-A75（2017）起普及，覆盖面大得多。
# 代价是这版 .so 不能在 armv8.0 老 CPU 上跑 —— 由 LlamaJni 在 loadLibrary
# 前读 /proc/cpuinfo 检查 asimddp/asimdhp，缺了就拒绝加载并给文案，不会 SIGILL。
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
# GGML_CPU_AARCH64 = OFF：显式关掉 aarch64 repack 权重布局。
#
# 这个开关打开时，模型里的 Q4_0/Q4_K 会在首次 decode 时被重排成 4x4/8x8 交错布局，
# 换取更长的向量内积。理论上更快，但真机实测的表现是「加载 5 秒正常，第一次
# decode 就再也不返回」——158 token 的 prefill 卡满 180 秒没有任何进度。
# 重排是懒执行的，正好落在第一次 decode 里，且 b4600 这条路径在部分 ARM 设备上
# 会反复重排（没有正确命中缓存），一次 forward 就够把预算烧光。
#
# 关掉它意味着走标准 NEON + dotprod 内核。IRIDIS 在同款设备上跑出 4.8 tok/s
# 走的也是标准路径，说明这条路的绝对速度够用。宁可少 10% 峰值，也不要「不出字」。
set(GGML_CPU_AARCH64 OFF CACHE BOOL "" FORCE)

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
