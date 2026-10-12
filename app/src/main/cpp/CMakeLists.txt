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
# GGML_CPU_AARCH64 保持默认 ON：Q4_0→Q4_X_X 运行时转换，代码内部按 __ARM_FEATURE_DOTPROD
# 守卫，基线 armv8-a 设备上自动退化为空实现，安全。

add_subdirectory("${LLAMA_SOURCE_DIR}" llama.cpp-build EXCLUDE_FROM_ALL)

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
