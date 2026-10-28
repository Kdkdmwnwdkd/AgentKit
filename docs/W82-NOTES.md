# W-82 笔记：8 Gen 1 以下、天玑，到底能不能适配上

## 1. 问题不是「适配难度」，是「这条路存不存在」

上一轮我给出的判断是「接 LiteRT 要看你有多少台 8 Gen 1 以上的高通机」。
反过来问就是这次的问题：8 Gen 1 以下的高通、还有天玑，能不能覆盖。

先把一件事钉死：**NPU 不是「一类用户」，是「一机一包」。**

LiteRT 的 NPU 路径走 Qualcomm AI Engine Direct（QNN），模型要先用 AOT 插件按具体
Hexagon 版本编译成 context binary。Google 官方支持列表写得很清楚：

> Snapdragon 8 Gen 1 (SM8450) / 8 Gen 2 (SM8550) / 8 Gen 3 (SM8650) / 8 Elite (SM8750)

换一颗芯片不是「慢一点」，是直接 `Failed to create QNN context: 1002`——
context binary 是给那个 DSP 编的机器码，别的 DSP 读不了。同一家高通，跨代也不通用。

天玑的情况更彻底：它有 APU，但 LiteRT 要用它必须经 MediaTek NeuroPilot 单独转换，
我们既没有那条链、也没有官方给的通用包。麒麟的达芬奇、三星的 NPU 同理。

所以答案是：

- **8 Gen 1 以下的高通** → NPU 这条路不存在，不是没适配好。
- **天玑 / 麒麟 / 三星 / 紫光** → 完全不存在。

## 2. 覆盖矩阵

| SoC | LiteRT-NPU | Vulkan-GPU | CPU |
|---|---|---|---|
| 骁龙 8 Elite / 8 Gen 3 / 8 Gen 2 / 8 Gen 1 | ✓ | ✓ 稳 | ✓ |
| 骁龙 888 / 870 / 7+ Gen 2 / 695 | ✗ | ✓ 稳 | ✓ |
| 天玑 9300 / 9200 / 8200 / 7050 / 700 | ✗ | △ 需按机型验证 | ✓ |
| 麒麟 9000 | ✗ | △ 需按机型验证 | ✓ |
| Exynos（Xclipse） | ✗ | △ 算子覆盖不全 | ✓ |
| 紫光展锐 | ✗ | △ 需按机型验证 | ✓ |

**真正能覆盖「大多数人」的那条路不是 NPU，是 GPU（Vulkan）与 CPU。**
这也解释了为什么 IRIDIS 同时接三家：它需要一条在自家所有用户机器上都存在的兜底，
llama.cpp 的 CPU/Vulkan 就是那条兜底；MNN 与 LiteRT 是给特定机器的加速。

## 3. 为什么 Mali 不默认开 GPU

不是因为慢。是因为 Mali 的 Vulkan 驱动在长时间推理后容易 `VK_ERROR_DEVICE_LOST`，
而我们的场景恰恰是「挂一个 Agent 跑几十分钟」。一次 device lost 等于整轮任务作废。

所以策略是「默认 CPU、可手动开 GPU、崩了记住」：

| GPU 家族 | 默认 | 理由 |
|---|---|---|
| Adreno 6xx/7xx | GPU | 同一份 shader 在各代上表现一致 |
| Mali / Immortalis | CPU | 驱动差异大，中低端机还常配小内存 |
| Xclipse | CPU | ggml 算子覆盖不全 |
| PowerVR | CPU | 驱动长期不更新 |

这条策略写在 `LocalDeviceRules.advise()` 里，`VulkanMaturity` 是 GOOD / MIXED / POOR 三档，
不是「支持/不支持」两档——两档表达不了「能跑但会崩」。

## 4. 低端机真正的门槛是内存，不是算力

| 档位 | 内存 | 建议模型 | 峰值预算 |
|---|---|---|---|
| 入门 | 4GB 级 | 0.5B~1B Q4 | 1.8GB |
| 中低端 | 6GB 级 | 1B~1.5B Q4 | 2.8GB |
| 中高端 | 8GB 级 | 1.5B~3B Q4 | 4.2GB |
| 旗舰 | 12GB+ | 3B~4B，7B 勉强 | 7.0GB |

3B Q4_K_M 权重约 1.9GB，4096 上下文再加 0.6~0.9GB，系统和 App 自己还要 1.5GB。
所以 6GB 机跑 3B 是「刚好卡住」，8GB 才叫舒适。

**一句要诚实说的话**：1B 以下的模型能聊天，但基本不会可靠地走工具调用。
也就是说低端机「能用」，但「干不了活」——这不是我们做得不好，是模型本身的能力线。
把这句话写进设置页，比让用户下载 3B 模型然后被系统杀掉要好。

## 5. 本轮落地

- `core/llm/local/LocalDeviceProfile.kt`（纯 Kotlin，不 import android）
  - `gpuOf()`：从 `GL_RENDERER` 解析 Adreno / Mali / Immortalis / Xclipse / PowerVR
  - `vendorOf()`：三路兜底（SOC_MANUFACTURER → SOC_MODEL → HARDWARE → GPU 反推）
  - `npuUsable()`：只有高通 8 系且 Hexagon ≥ v69；其余一律 false **并给出具体原因**
  - `advise()`：档位 + 首选路径 + 模型预算 + 人话说明
- `core/llm/local/AndroidDeviceProbe.kt`（Android 侧，只采集不判定）
  - `SOC_MODEL` 走反射：API 31 才有，而这批老机型正是要覆盖的人
  - `GL_RENDERER` 走最小 EGL：所有版本都能拿到，失败返回 null 不抛异常
  - 内存读 `/proc/meminfo`，不用 `MemoryInfo.totalMem`（部分 ROM 返回的是可用值）
- `LocalEngineEnvironment.device`：画像接进后端目录，GPU 灰掉时写明「本机 Adreno 750，
  开启 Vulkan 后可直接用」。`device = null` 时文案与旧版逐字一致（有断言守着）。
- `AgentKitApplication`：后台线程采一次画像并写日志，不压冷启动。

## 6. 验证

`scripts/qa/run-local-device-profile-verification.sh`，**77 条断言**：

- 24 条静态（规则有没有被「好心」改成乐观值、采集有没有跑到主线程）
- 53 条行为：用 kotlin-compiler-embeddable 真编真跑，喂真实机型串
  - 8 Gen 3 / 8 Gen 1 / 888 / 870 / 695 / 天玑 9300 / 7050 / 1200 / 麒麟 9000 / Exynos / 紫光
  - 老机型（只有 `hardware=qcom` + `Adreno 610`）
  - 只剩一个 GL 串的白牌机
  - 全空（最保守：CPU + 不乐观的档位）

一条值得记的坑：`LocalBackendCatalog` 现在引用 `LocalDeviceProfile`，
所以后端目录那个脚本的编译行也要跟着加文件——不然它单编 catalog 会报
`unresolved reference`。两个脚本互相牵连是这种「分层落地」的必然结果。

## 7. 下一步

1. **Vulkan 编译**（这才是覆盖天玑与老高通的唯一技术路径）。
   代价：NDK 侧要 `find_package(Vulkan COMPONENTS glslc)` + `SPIRV-Headers`，
   还要在 CI 里编 `vulkan-shaders-gen` 做 host 侧 shader 生成，体积 +3~5MB。
   风险：Mali 上需要按机型白名单，否则就是「点了 GPU 卡住」。
   顺带要改名：`LocalEngineEnvironment.hasOpenCl` 是按 OpenCL 起的，开 Vulkan 后
   这个字段名会误导人，应该改成 `hasGpuRuntime` 或 `hasVulkan`，并同步 56 条断言里的引用。
2. **崩溃记忆**：native SIGSEGV 抓不到，装载前埋标记、下次启动比对 `ApplicationExitInfo`。
   它不依赖第二家引擎，单独做也有价值（上一版就出过首次 decode 卡死）。
3. 设置页展示 `LocalDeviceRules.summarize()` 那一行，让用户知道自己在哪条路上。

## 8. 真机看这一行

```
DeviceProbe: 设备画像：<厂商> · <GPU> · <档位> → 首选 <后端>，建议 <模型量级>
```

拿到这一行，就不用再问「你这台是什么芯片」了。
