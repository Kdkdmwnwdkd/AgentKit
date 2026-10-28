# W-83：把 GPU（Vulkan）后端编进去 —— 天玑与 8 Gen 1 以下唯一的一条提速路

## 0. 这一轮要回答的问题

上一轮把「谁能上 NPU」说清楚了：LiteRT 的 QNN 模型是按具体 Hexagon 版本 AOT 编译的
机器码，Google 的支持列表从 **SM8450（8 Gen 1）** 起；天玑、麒麟、紫光连这条链都没有。
于是那批用户（也是多数用户）只剩两条路：**CPU** 与 **GPU（Vulkan）**。

这一轮把 GPU 那条路真正打通，并且保证它在不该开的机器上**不会假装开着**。

## 1. 先钉死一个源码事实：Vulkan 后端要求 1.2

`ggml/src/ggml-vulkan/ggml-vulkan.cpp`（v0.7.0，`ggml_vk_instance_init`）里写死：

```cpp
if (api_version < VK_API_VERSION_1_2) {
    throw vk::RuntimeError("Vulkan 1.2 is required");
}
```

这句话决定了三件事：

1. Android 10/11 那批只给 Vulkan **1.1** 的驱动，连初始化都过不去。它们不是「慢」，
   是**根本进不来**。
2. 判定顺序必须是**先看版本、再看 GPU 家族**。反过来的话，骁龙 888 + Adreno 660 会被
   判成「可用」，用户点开之后静默退回 CPU——这正是「看起来支持、其实不支持」。
3. 版本号必须问 loader 自己：`dlopen("libvulkan.so")` → `vkEnumerateInstanceVersion`。
   这恰好是 ggml 自己的判据（`ggml_vk_instance_init` 第一件事就是调它），
   所以我们预测的结果与它实际的行为是同一个量。

   *走过的弯路*：第一版读 `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION` 的
   `FeatureInfo.version`。那个字段是 **Int**（按 String 读 CI 当场编不过：
   `Type mismatch: inferred type is Int? but String? was expected`），而且不同
   Android 版本对它的编码不统一。就算编过了，也只是把一个不确定的量当成了版本号。
   只 `dlopen` + 调一个返回常量的函数，同样不建 instance、不碰 device。

## 2. 失败是静默的，所以判据必须有三道

`ggml_vulkan` 在 `ggml_backend_vk_reg()` 外面包了 try/catch，初始化失败返回 `nullptr`，
**注册表直接跳过这个后端**。llama.cpp 随后照常用 CPU 建上下文——加载成功、生成成功、
只是慢。用户看到的是「开了 GPU 还是很慢」，我们看到的日志是「一切正常」。

针对这一点，本轮立了三道各自独立的证据：

| 时机 | 手段 | 挡的是什么 |
|---|---|---|
| 构建期 | CMake 缺工具链时 `FATAL_ERROR`（`AK_REQUIRE_VULKAN`） | 「以为开了 GPU，出的包其实只有 CPU」 |
| 产物期 | `readelf -d libagentkit-llama.so` 必须有 `libvulkan.so` | 「编了但链接器把后端丢了」 |
| 运行期 | `nativeGpuProbe()` 打印 ggml 认得的设备清单 | 「驱动不认这台机器，静默退回 CPU」 |

第三道最有用。用户点开 GPU 之后，日志里会出现这样一行：

```
后端设备：GPU 1 / 共 2：GPU:Vulkan0(Adreno (TM) 750) | CPU:NEON
```

反过来，如果请求了 GPU 却一个 GPU 设备都没有，nativeInit 会当场报错，而不是安静地跑 CPU：

```
请求 GPU 99 层，但没有任何 GPU 后端可用（CPU:NEON）——本次将按 CPU 运行
```

## 3. 判定链：三个前提，缺一不可

```
装没装（编译期宏 AK_HAS_VULKAN）
   × 能不能跑（Vulkan ≥ 1.2 且家族不在劝退名单）
   × 用户想不想（GpuMode: AUTO / FORCE / OFF）
        ↓
    n_gpu_layers = 99 或 0
```

**为什么「装没装」要由 native 回答**：写 Kotlin 常量的话，CMake 里关掉 Vulkan 而常量忘了改，
界面就会显示「GPU 可用」。现在 `LlamaJni.gpuBackendAvailable` 直接读 `#ifdef AK_HAS_VULKAN`，
编译期决定，改不动也不会忘。

**为什么是 `GpuMode` 三档而不是布尔**：「我不想用」和「我不知道该不该用」是两件事。
前者是用户的决定，后者该由规则替他判。

- `AUTO`（默认）：只在 `GpuPath.STABLE`（Adreno + Vulkan 1.2+）上开。
- `FORCE`：明知有风险也用。Mali 属于这档——驱动在长时间推理后可能 `VK_DEVICE_LOST`，
  但短任务上确实快，值不值得由用户自己定。
- `OFF`：永远不用。有些机型 GPU 能跑但更热更耗电，用户有权拒绝。

`GpuPath` 是三态（`STABLE` / `RISKY` / `NONE`），不是布尔——同样因为「能跑但会崩」
没法用 true 表达。三态的来源是 `GpuFamily.maturity`（Adreno GOOD / Mali·Immortalis·Xclipse
MIXED / PowerVR POOR）。**没有另写一份家族分支**：两份表早晚会不一致，而且不一致时没人会注意到。

## 4. 工具链：三件套，缺一不可

| 组件 | 作用 | 来源 |
|---|---|---|
| `glslc` | 把 `.comp` 编成 SPIR-V | CI: `apt-get install glslc` |
| `SPIRV-Headers` | `find_package(SPIRV-Headers CONFIG REQUIRED)` 要它 | CI: `apt-get install spirv-headers` |
| `vulkan.h` + `libvulkan.so` | 编译与链接 | NDK sysroot 自带 |
| `vulkan.hpp` + `spirv.hpp` | `ggml-vulkan-types.h` 直接 include | CI: `libvulkan-dev` + 暂存目录 |

四个细节，都是踩过的：

0. **NDK 不带 `vulkan.hpp`**。第一次 CI 就死在这：
   `ggml-vulkan-types.h:26: fatal error: 'vulkan/vulkan.hpp' file not found`。
   `ggml-vulkan-types.h` 里 `#define VULKAN_HPP_DISPATCH_LOADER_DYNAMIC 1`，
   所有入口点都走动态派发器，所以宿主头 + NDK 的 `libvulkan.so` 桩可以共存。
   但**不能直接把 `/usr/include` 塞进 `-I`**：`-I` 排在 `-isystem` 之前，
   glibc 的 `stdio.h` / `stdint.h` 会顶掉 bionic 的同名头。做法是把
   `vulkan/` 与 `spirv/` 两个目录软链进一个空目录，只挂这一个。

1. **必须显式喂 `Vulkan_GLSLC_EXECUTABLE`**。交叉编译时 `FindVulkan` 自己 `find_program`
   会撞上 `CMAKE_FIND_ROOT_PATH`（只在 sysroot 里搜），必然失败。
2. **本地没有这套工具链要能静默退回 CPU-only**，否则每个用 Android Studio 的人都编不过。
   但 CI 里必须失败——所以 `AK_REQUIRE_VULKAN` 只在 CI 设。

`spirv-headers` 在 Ubuntu noble 里装到 `/usr/share/cmake/SPIRV-Headers/`，所以 CI 这一步
不是写死路径，而是 `dpkg -L` 找 `SPIRV-HeadersConfig.cmake` 再取目录。包若搬家，
在读清楚原因的位置失败，而不是等 CMake 报一句 `find_package failed`。

## 5. 装进去之后，还要敢退回来

GPU 装载失败（驱动拒绝分配设备内存、卸载模型层时报错）都表现为 `nativeInit` 返回 0。
如果就这么把异常抛出去，用户点开 GPU 的后果是「本地推理彻底不可用」——他本来只是想快一点。

所以 `ensureModelLoaded` 里有一条明确的兜底：**带 GPU 装载失败 → 打一行错误 → 用纯 CPU 重试一次**。
退回一定要留话，否则又变成一次静默降级。

## 6. 两个坑

**① 版本解码是位域，不是十进制。**
`VK_MAKE_VERSION` = `major << 22 | minor << 12 | patch`。按十进制解析会得到
天文数字，然后被判成「版本足够高」——正好反了。断言里专门喂了 `10003`（十进制
编码的 1.3）确认它被判为脏值而不是「可用」。

**② `GGML_BACKEND_DEVICE_TYPE_IGPU` 也是 GPU。**
第一版只统计 `TYPE_GPU`，集显设备会被报成「GPU 0」。手机上的 Vulkan 设备恒为 `GPU` 类型，
但漏算的后果是「明明有显卡却说没有」，所以两个都算。

**③ Kotlin 属性初始化顺序。**
`localGpuSummary` 用 `combine(_localGpuMode, _localGpuDevices)`，而 `_localGpuDevices`
一开始写在它后面。`combine(...)` 在**属性初始化时**就要求值，此时后者还是 `null` ——
表现为一进设置页就 NPE。Kotlin 不报编译错，只按声明顺序初始化字段。

## 7. 验证

`scripts/qa/run-vulkan-backend-verification.sh`：**79 条断言**。

- 静态 37 条：CMake 分支、环境变量、编译期宏、链接、JNI 符号、版本探测、装载路径、CI 四件套与暂存目录；
- 行为 40 条：真编真跑（`kotlin-compiler-embeddable`）。覆盖「1.1 判不可用」「1.2 刚好够」「版本位域解码」
  「`Vulkan 1.3` 带前缀也能解析」「Mali 是需验证不是不可用」「强制也不越过版本不够」
  「包没编时哪怕 Adreno 也是 0 层」「降级链不会空」。
- 产物 2 条：`readelf` 找 `libvulkan.so`、`strings` 找 `ggml_vulkan:` 前缀（无产物时 SKIP）。

静态 grep 抓不出「规则反了」，所以行为断言一律真编译真运行。

另外 `llama-jni.cpp` 全文件用 v0.7.0 的头做过 `-fsyntax-only` 编译（有无 `AK_HAS_VULKAN` 两种），
避免出现「CI 编到 800 行才报错」的往返。

## 8. 真机要看的三行

```
DeviceProbe: 设备画像：高通骁龙 · Adreno 750 · 旗舰（12GB+） · Vulkan 1.3 → 首选 NPU
LocalLlama:  GPU 卸载：99 层 → Adreno 750（Vulkan 1.3）
LocalLlama:  后端设备：GPU 1 / 共 2：GPU:Vulkan0(Adreno (TM) 750) | CPU:NEON
```

判定口径：**先看「后端设备」那行有没有 GPU**，再看 `prefill 完成` 与 `生成 X tok/s` 同比变化。
没有 GPU 那一行，后面所有提速都不成立。

## 9. 没做的

- **崩溃记忆**：native 的 SIGSEGV 是 Java 抓不到的。Vulkan 驱动崩起来是整进程，
  现在只能靠用户手动关。这是下一轮的第一优先。
- **Mali 白名单**：`RISKY` 目前是「全部 Mali」，实际应该按具体型号与驱动版本分。
  数据只能从真机收集。
- **GPU 侧 KV 的显存账**：手机上 Vulkan 用的是统一内存，`n_gpu_layers=99` 的额外开销
  主要是 compute buffer（约 +5~10%）。3B + 4K 上下文在 8GB 机上应该够，但没有实测。
- **多后端联合**：仍然是一份包、一个后端生效（CPU 或 GPU）。真正的「CPU+GPU 分层」
  是 llama.cpp 自己做的（`n_gpu_layers` 决定分界），不是我们额外写协调层。
