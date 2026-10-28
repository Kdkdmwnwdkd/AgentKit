// 只为 ggml-vulkan 目标强制包含（见 CMakeLists.txt 里 target_compile_options）。
//
// 背景：bionic 到 **API 28** 才提供 aligned_alloc，而本项目 minSdk 是 26。
// libc++ 的 <cstdlib> 里有这么一行：
//
//     using ::aligned_alloc _LIBCPP_USING_IF_EXISTS;
//
// 符号不存在时这条 using 声明会「保留但解析不了」，平时无人引用就安静地躺着；
// 一旦有人用 std::aligned_alloc，就是：
//
//     error: reference to unresolved using declaration
//     note: using declaration annotated with 'using_if_exists' here
//
// ggml-vulkan-buffers.cpp 的 portable_aligned_malloc 正是这么炸的（第四次 CI）。
//
// 名字查找发生在 <cstdlib> 里那一行，所以补声明必须**早于** <cstdlib> —— 这也是
// 这里用 -include 而不是在源文件里改的原因（改第三方源码会在下次升级时丢掉）。
//
// 实现走 posix_memalign（API 16 起就有）。声明成 static：每个 TU 各一份、
// 不进符号表，不会和系统库或别的目标撞名。
#pragma once

#if defined(__ANDROID__) && defined(__ANDROID_API__) && __ANDROID_API__ < 28

#include <stddef.h>
#include <stdlib.h>

extern "C" {
static inline void* aligned_alloc(size_t alignment, size_t size) noexcept {
    // posix_memalign 要求 alignment 是 sizeof(void*) 的倍数。ggml 传的是 16/32/64，
    // 都满足；万一将来传了不合规的值，返回 null 让调用方自己报错，而不是写坏内存。
    void* p = nullptr;
    if (::posix_memalign(&p, alignment, size) != 0) return nullptr;
    return p;
}
}

#endif  // __ANDROID__ && __ANDROID_API__ < 28
