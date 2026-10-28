// Vulkan 1.1 入口点的兜底定义 —— 只在需要时由 CMake 编进 agentkit-llama。
//
// ## 为什么需要这个文件
//
// 第五次 CI（run 459）在链接期挂掉，只差一个符号：
//
//     ld.lld: error: undefined symbol: vkGetPhysicalDeviceFeatures2
//     >>> referenced by ggml-vulkan.cpp:4475 (ggml_vk_get_device)
//     >>> referenced by ggml-vulkan.cpp:5112 (ggml_vk_instance_init)
//     >>> did you mean: vkGetPhysicalDeviceFeatures
//     >>> defined in: sysroot/usr/lib/aarch64-linux-android/26/libvulkan.so
//
// 原因是 **Android 的 Vulkan loader 分版本导出入口点**：
//
//   - `vkGetPhysicalDeviceFeatures2` 是 **Vulkan 1.1** 的核心函数；
//   - Android 的 `libvulkan.so` 从 **API 28（Android 9）** 才开始导出 1.1 的入口点；
//   - 本项目 minSdk 是 **26**，链接时取的是 `.../android26/libvulkan.so` 桩文件，
//     那份桩里只有 1.0 的函数（所以 lld 才建议 `vkGetPhysicalDeviceFeatures`）。
//
// 编译期一点问题都没有：`vulkan.h` 是完整头文件（含 1.3 的类型与 PFN 声明），
// 只有**库桩**是按 API 级别裁剪的。于是编到最后一步才炸。
//
// ## 为什么不用「链接到 API 28 的桩」
//
// 那样确实能链过，但会把「能不能加载」变成设备侧的赌注：Android 8.x 的机器上
// `libvulkan.so` 里根本没有这个符号，而 bionic 的链接器对新目标基本是急绑定，
// 缺一个符号就是**整个 .so 加载失败**——连纯 CPU 推理一起废掉，正是前几轮一直在
// 避免的那种回归。GPU 慢一点可以接受，CPU 都跑不起来不行。
//
// ## 这里的做法
//
// 自己提供这个符号（强定义，满足 `-Wl,--no-undefined`），运行时再转发：
//
//   1. `dlopen("libvulkan.so")` 拿设备上真实的 loader，`dlsym` 找真身；
//   2. 找到了（Android 9+，也就是所有真正能跑 Vulkan 1.2 的机器）→ 原样转发；
//   3. 没找到（Android 8.x 的 1.0/1.1 loader）→ 把可选特性按「全不支持」填零。
//
// 第 3 步不会让老机器误判成「能用 GPU」：`ggml_vk_instance_init()` 里
// `enumerateInstanceVersion() < VK_API_VERSION_1_2` 会先抛异常，后端初始化失败 →
// 注册表跳过 → `llama` 照常用 CPU 建上下文。零填充只是保证那条路走得下去，
// 而不是在半路炸一个空指针。
//
// ## 两个必须守住的细节
//
// **不能拿 RTLD_DEFAULT 去 dlsym**：本文件定义的符号就在全局作用域里，
// 那样查到的会是自己，转发变成无限递归，表现为一进 GPU 初始化就栈溢出。
// 必须对 `dlopen` 的**句柄**查，句柄的搜索范围是 libvulkan 及其依赖，不含我们。
//
// **调用约定必须用 `VKAPI_ATTR`/`VKAPI_CALL`**：armeabi-v7a 上它们是
// `pcs("aapcs-vfp")`，手写 `void f(...)` 会与调用方的约定不一致——编译能过，
// 32 位设备上一调就崩。

#include <dlfcn.h>

#include <vulkan/vulkan.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define AK_VK_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AgentKitVk", __VA_ARGS__)
#define AK_VK_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AgentKitVk", __VA_ARGS__)
#else
// 让这个文件在本机（Linux 主机）也能编，好跑行为断言。
#define AK_VK_LOGE(...) ((void) 0)
#define AK_VK_LOGI(...) ((void) 0)
#endif

namespace {

using Features2Fn = PFN_vkGetPhysicalDeviceFeatures2;

// 真身只找一次。static 局部变量的初始化在 C++11 起是线程安全的（首次调用时加锁），
// 而 ggml 只会从加载流程里单线程调到这里。
Features2Fn resolveReal() {
    void* h = ::dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (h == nullptr) {
        AK_VK_LOGE("Vulkan 1.1 转发：dlopen libvulkan.so 失败（%s）", ::dlerror());
        return nullptr;
    }
    auto fn = reinterpret_cast<Features2Fn>(::dlsym(h, "vkGetPhysicalDeviceFeatures2"));
    // 句柄不 dlclose：这个指针会被长期持有，关掉句柄等于把代码段也交回去。
    if (fn == nullptr) {
        AK_VK_LOGI("Vulkan 1.1 转发：本机 loader 无 vkGetPhysicalDeviceFeatures2（Android 9 以前）");
    }
    return fn;
}

}  // namespace

extern "C" VKAPI_ATTR void VKAPI_CALL
__attribute__((visibility("default")))  // 必须进 .dynsym：校验脚本要能在产物里找到它
vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice, VkPhysicalDeviceFeatures2* pFeatures) {
    static Features2Fn real = resolveReal();

    if (real != nullptr) {
        real(physicalDevice, pFeatures);
        return;
    }

    // 本机 loader 没有 1.1 入口点。走到这里说明设备连 Vulkan 1.1 都没有，
    // ggml 的 1.2 门槛紧接着就会把它挡掉；这里只负责「不崩」。
    if (pFeatures != nullptr) {
        pFeatures->features = VkPhysicalDeviceFeatures{};
    }

    // 每个进程只抱怨一次：这个函数在设备枚举阶段可能被调多次。
    static bool warned = false;
    if (!warned) {
        warned = true;
        AK_VK_LOGE("Vulkan 1.1 转发：无 vkGetPhysicalDeviceFeatures2，可选特性按「全不支持」处理");
    }
}
