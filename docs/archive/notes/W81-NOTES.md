# W-81：三家引擎怎么接才不打架（吃透 IRIDIS 2.1.8）

> 结论先放这里：**IRIDIS 从来没有同时跑过三家引擎。**
> 它做的是「一个插座、三个插头、一份说明书」——任一时刻只有一个模型驻留、只有一个引擎活着。
> 所谓「联合」，是把三家的差异**提前判定掉**，而不是让它们在运行时互相协调。

---

## 1. 它到底怎么做到的

源码位置：`IRIDIS-2.1.8 / app/src/{main,full,lite}/java/com/iris/localai/engine/`。
下面每条都标了文件和行数，可以直接对。

### 1.1 窄接口：七个方法

`InferenceEngine.kt`（150 行）定义的能力面小得出奇：

| 方法 | 作用 |
|---|---|
| `load(model, config, onProgress)` | 装模型，返回 `ModelDetails`（含 `activeBackend`） |
| `generate(history, params): Flow<GenEvent>` | 流式出字，必然以一个 `Done` 收尾 |
| `cancel()` | 线程安全、非阻塞的停止 |
| `resetContext()` | 丢弃 KV / 会话 |
| `unload()` | 释放 |
| `applyResourcesLive(plan)` / `livePolicy()` | 能不能在回复中途改 CPU 亲和（可选能力） |
| `reusesCommonPrefix` | 能不能自己按公共前缀复用 KV（可选能力） |

只有五个是必须实现的。后三个都是 `open` 带默认值——**能力用「有没有实现」表达，而不是用 `if` 判断引擎名字**。
`GenEvent` 只有两种：`Piece(text)` 与 `Done(reason, stats, error)`。停止原因统一成
`END / MAX_TOKENS / CANCELLED / CONTEXT_FULL / ERROR` 五档，上层拿这个决定 UI 文案，不问引擎是谁。

### 1.2 单一驻留：`EngineManager` 是唯一所有者

`EngineManager.kt`（986 行）里 `engine` 是一个 `@Volatile var`，`loadLocked()` 开头就是：

```kotlin
generation.withLock {
    engine?.unload()
    engine = null
    ...
}
```

也就是说：**换模型 = 先卸旧的，再装新的**。不存在「llama 在跑、MNN 在等」的场面。
`generation` 是一把 `Mutex`，所有生成串行——HTTP API 的请求会排在聊天回复后面，而不是并发抢显存。

这三件事顺带被解决了：内存峰值只有一个模型、线程池只有一套、GPU/NPU 上下文只有一个。

### 1.3 后端抽象：`Backend` 只有三个值

`Backend.kt`（285 行）里 `enum class Backend { CPU, GPU, NPU }`，`FALLBACK_ORDER = [NPU, GPU, CPU]`。

它把「三家引擎」和「三种硬件」拆成两个正交维度：

```
格式 (GGUF / MNN / LITERT / QAIRT)  ×  后端 (CPU / GPU / NPU)  →  引擎
```

`EngineFactory.create(format, engine)` 只做这一个映射：

| 格式 | CPU / GPU | NPU |
|---|---|---|
| GGUF | `LlamaEngine` | `GenieXEngine`（可选） |
| MNN | `MnnEngine` | —— |
| LITERT | `LiteRtEngine` | `LiteRtEngine`（AOT 模型） |
| QAIRT | 不允许 | `GenieXEngine` |

### 1.4 「引擎内部不许自行降级」——这条最容易被忽略

`InferenceEngine.kt:20` 那行注释是整份代码里最关键的一句：

> `1.7.1: the one backend to try. Engines no longer fall back internally; [EngineManager] walks the chain.`

早期版本是每个引擎自己 try GPU→CPU，结果是：llama 回退了一次、MNN 又回退一次，
用户看到 8 条互相矛盾的日志，谁也说不清最后跑在哪。改成「引擎只管试自己那一个后端，
失败就抛出带人话的 `EngineException`」，降级收敛到一处：

```kotlin
val chain = BackendPlanner.chain(preferred, options, crashed)   // NPU → GPU → CPU
for (backend in chain) {
    e = EngineFactory.create(...)      // 每个后端一个新引擎实例
    crashMemory.arm(model.id, backend) // 埋雷：这一步可能把进程打死
    try { details = e.load(...); crashMemory.disarm(); break }
    catch (t) { e?.unload(); e = null; errors.append("...") }
}
```

注意 `e = EngineFactory.create(...)` 在循环**里面**：每次换后端都新建一个引擎实例，而不是复用。
用完的实例立刻 `unload()`。这样「上一家没释放干净」这种脏状态不会带到下一家。

### 1.5 崩溃记忆：native 的 SIGSEGV 是 Java 抓不到的

`BackendCrashMemory`（`Backend.kt:234-281`）的机制很小，但没它整个降级链就是纸糊的：

1. 装载前 `arm(modelId, backend)` 往 SharedPreferences 写一个 `armed` 标记；
2. 装载成功 `disarm()`；
3. 下次启动时如果发现 `armed` 还在，说明**上次就是死在这一步**；
4. 再用 `ActivityManager.getHistoricalProcessExitReasons()` 确认那确实是崩溃
   （`REASON_CRASH_NATIVE` / `SIGNALED` / `ANR`），排除「用户手动划掉」；
5. 记进 `bad:<backend>|<modelId>`，下次 `chain()` 直接跳过。

不做这个的后果很具体：一台机器上 Adreno 驱动有 bug，开 GPU 必崩，用户每次点开 App 都闪退一次。

### 1.6 能力位：把「同名不同义」写成布尔

`EngineFeatures`（`Backend.kt:69-83`）声明的是**参数语义**，不是性能：

| 字段 | GGUF | MNN | LiteRT |
|---|---|---|---|
| `contextShift`（挤掉最老 token 而不是停） | ✅ | ❌ | ❌ |
| `kvQuant`（Q8_0/Q4_0 KV） | ✅ | ❌ | ❌ |
| `mmap` | ✅ | ✅ | ❌ |
| `minP` | ✅ | ✅ | ❌ |
| `presenceFrequency` / `seed` / `batchSize` | ✅ | ❌ | ❌ |
| `liveThreads`（回复中改线程不重载） | ✅ | ❌ | ❌ |
| `fixedContext`（KV 容量编译期写死） | —— | —— | `_ekv1280` → 1280 |

UI 直接拿这张表把不支持的表单项灰掉（`ModelConfigSheet.kt:80`），
`BackendCapabilities.contextShiftUnsupportedReason()` 还能给出「为什么不能平移」的原话。
**没有一处 `if (engine == "mnn")`。**

### 1.7 构建分档：默认包根本不含后两家

`app/build.gradle.kts:62-76`：

```kotlin
create("lite") { isDefault = true; buildConfigField("boolean","HAS_LITERT","false") }
create("full") { versionNameSuffix = "-full"; ... -DIRIS_MNN=ON }
```

`src/lite/EngineFactory.kt` 里 `MNN / LITERT / QAIRT` 三个分支直接抛
「此版本仅支持 GGUF 模型」。同一份 `InferenceEngine` 接口，两套实现，编译期二选一。

这解释了为什么它敢「接三家」：**默认包只带一家**。三家是给愿意多下 26MB 的人准备的。

---

## 2. 六个真实冲突点，以及各自的解法

| # | 冲突 | 不管会怎样 | IRIDIS 的解法 |
|---|---|---|---|
| 1 | **符号冲突**：三家都带自己的 ggml / kernel / OpenCL 封装 | 后加载的 `.so` 覆盖前一个的同名符号，A 引擎跑出 B 引擎的算子，随机崩 | 每引擎一个独立 `.so` + `-fvisibility=hidden`（`app/src/main/cpp/CMakeLists.txt:57-96`） |
| 2 | **谁负责分词和对话模板** | 上层拼一份 prompt 喂三家，必然有一家对不上模板 | 由引擎自己决定：GGUF 用模型自带 template，MNN 读 `tokenizer.txt`+Jinja，LiteRT 内部渲染（`MnnEngine.kt:218-224`、`LiteRtEngine.kt:40-44`） |
| 3 | **KV 语义不同** | 新对话沿用旧 KV → 串味；或每轮全量 prefill → 慢十倍 | `reusesCommonPrefix`：只有 llama.cpp 是 `true`，`resetForNewChat()` 据此决定要不要 reset（`EngineManager.kt:955-958`） |
| 4 | **采样参数不同名不同义** | 透传后静默失效（设了 minP 其实没生效） | 统一 `SamplingConfig` + `EngineFeatures` 声明 + 引擎侧翻译 |
| 5 | **资源争用**（线程/亲和/内存） | 两个引擎各自开线程池，互相抢核，双双变慢 | 单一驻留 + 统一 `ThreadPolicy`，且 **CPU 与 GPU/NPU 用不同 profile**（`EngineManager.kt:352-353`） |
| 6 | **崩溃不可捕获** | 换后端试一次就闪退一次，永远起不来 | `BackendCrashMemory` arm/disarm + `ApplicationExitInfo` 判定 |

---

## 3. 三个「看着能抄、抄了就错」的细节

这三个是**真金白银的坑**，不读源码绝对想不到：

### 3.1 MNN 的 `thread_num` 在 OpenCL 上不是线程数

`MnnEngine.kt:35-44` + `MnnThreadArg`：

> `llm.cpp:211-215` 把 `thread_num` 按 `numThread | 64 | 512` 传下去，`OpenCLBackend.cpp:1659-1683`
> 再把它**当调优模式位解码**（1 NONE / 2 HEAVY / 4 WIDE / 8 NORMAL / 16 FAST）。

它自己踩过的坑写在注释里：≤1.7.7 直接把档位的线程数传进去，结果「全部」档 = 8 = NORMAL
（最慢的一档），「省电」档 = 1 = 一点调优都不做。MNN 文档要求的是 68（BUFFER|WIDE），
`llm.cpp` 已经 OR 了 BUFFER，所以传 `4` 才对。

**这条直接否掉了「统一参数透传」的幻想**：同一个 `threads=8`，在 llama.cpp 是 8 线程，
在 MNN-OpenCL 上是「用 NORMAL 档调优」。必须由引擎自己翻译。

### 3.2 LiteRT 的 `Conversation` 只能精确续接

`LiteRtEngine.kt:40-44`：

> 只有新历史**扩展**了当前 conversation 已见过的内容时，才只发新消息；
> 其它情况（换会话、编辑、重新生成、改了采样参数）一律新建 conversation，
> 并把旧对话作为 `initialMessages` 重新喂一遍。

判定靠 `sameHistory(conversationHistory, prior)`（`LiteRtEngine.kt:226-227`）。
所以它**不能**像 llama.cpp 那样「复用 system prompt 的 KV」——`reusesCommonPrefix` 是 `false`。

### 3.3 NPU 版的 KV 是编译期写死的

文件名 `Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm` 里 `ekv1280` 就是 KV 容量上限，
`_sm8750` 是按那颗芯片 AOT 编译的目标。`BackendCapabilities.options()` 因此把 CPU/GPU
标成「只能在 NPU 上运行」，把跨芯片 NPU 标成「模型为 SM8750 编译，本机是 SM8650」。

`_ekv1280` 里的 1280 不是建议值，**在设置里把上下文调到 4096 也没用**——所以有 `fixedContext`。

---

## 4. 体积账：native 不是障碍

实测（GitHub Release 资产）：

| 包 | 引擎 | 体积 |
|---|---|---|
| IRIDIS 2.1.8 **full** | llama.cpp + MNN 3.6.1 + LiteRT-LM 0.18 + GenieX | **29.9 MB** |
| 北辰 1.450 | llama.cpp v0.7.0 | 34.96 MB |

三引擎的包比我们单引擎的还小 5MB。差别不在推理引擎：我们的包里还装着
`termux-jni`（proot 终端）、Compose、以及一堆 Agent 侧的东西。

**结论：接第二家的成本是工程量，不是 APK 体积。** 这条把「不划算」的理由削掉了一半。

---

## 5. 我们现在差在哪

| 维度 | IRIDIS | 北辰现状 |
|---|---|---|
| 引擎抽象 | `InferenceEngine` 7 方法 | 无。`LocalLlamaClient` 直接调 JNI，句柄在 `LocalLlamaEngine` 里 |
| 后端维度 | `Backend{CPU,GPU,NPU}` + 能力矩阵 | 无。`nGpuLayers` 一个 int 直通 |
| 降级链 | `BackendPlanner.chain` + 崩溃记忆 | 无。失败就报错 |
| 能力位 | `EngineFeatures` | 无。上下文平移、KV 量化都是散落的 `if` |
| 格式路由 | 4 种 | 1 种（`.gguf`） |
| 分档 | lite（默认）/ full | 无 |

我们不是「差一点」，是**这一层根本没建**。之前能跑，是因为只有一家，不需要。

---

## 6. 该怎么做

### 6.1 三层，只做中间那层

| 层 | 内容 | 谁来做 |
|---|---|---|
| 内核 | ggml / MNN 的算子实现 | **不自研**。8 年积累、十几种量化格式、十几个后端，追不上，也不是差异化 |
| 适配层 | 接口 + 后端矩阵 + 降级 + 崩溃记忆 + 参数翻译 | **自己做**。这是「三家不打架」真正发生的地方 |
| 运行时 | 提示、工具协议、KV 复用策略、审批 | 已有，且比 IRIDIS 强 |

用户说的「只做内核不搞中层外层」——那条路是重写 ggml，代价是上面那 8 年。
反过来才是对的：**内核全部用别人的，中层自己写，外层我们已经有了。**

### 6.2 三步走，不要一次全上

**S1（本轮已做）：后端目录表**
`core/llm/local/LocalBackendCatalog.kt` + 56 条断言（20 条静态 + 36 条真编真跑）。
纯逻辑、零运行时依赖、不被现有链路调用。
它把「哪个模型能在哪个后端跑、为什么不能」写死成可测的表。

**S2：接 MNN（建议先做）**
理由：
- 它的 CPU 路径（ARMv8.2 FP16 + KleidiAI）和我们的 llama.cpp CPU 路径**正面可比**——同一台机器上能直接对比出「我们是不是真落后」；
- 模型是文件夹格式，下载/校验逻辑独立，不会污染 GGUF 那条链路；
- 冲突面最小：没有 NPU，只有一个 OpenCL 开关。

**S3：接 LiteRT（看 NPU 覆盖率再定）**
只有一件事值得为它付钱：**Hexagon NPU**。通用 `.litertlm` 在 CPU/GPU 上打不过同尺寸 GGUF。
所以这步的前置条件是「我们的用户里有多少台 8 Gen 1 以上的高通机」。

### 6.3 与「优化聊天体验」的关系

用户这一路真正想要的不是「能接三家」，是「本地模型能干活、还不卡」。
接第二家**只在一种情况下加速这件事**：LLM 推理确实换引擎更快。
但目前 170ms/token 的瓶颈在 3B Q4 的带宽，不在引擎选择。所以：

> S2 的价值是「拿 MNN 当标尺，验证我们 llama.cpp 的配置有没有浪费」，
> 不是「再装一个引擎给用户选」。

---

## 7. 本轮落地

| 文件 | 内容 |
|---|---|
| `app/src/main/java/com/example/agentkit/core/llm/local/LocalBackendCatalog.kt` | 后端目录：格式识别、NPU 编译版文件名规则、后端可用性、能力位、降级链 |
| `scripts/qa/run-local-backend-catalog-verification.sh` | 静态断言 + 有 kotlinc 时真编真跑的行为断言 |

设计上刻意与 IRIDIS 有两处不同：

1. **表里的值按我们自己的构建能力填。** 我们的 llama.cpp v0.7.0 只编了 CPU 后端，
   所以 GGUF 的 GPU 选项是 `available = false` 且带原因，而不是抄它的 `true`。
   什么时候开了 Vulkan，改 `LocalEngineEnvironment.llamaGpuEnabled` 一处即可。
2. **降级必须留话。** `LoadPlan.notes` 强制记录「首选项为什么没用上」。
   静默降级是这类应用最招人恨的行为：用户以为在用 GPU，跑了两周才发现一直在 CPU 上。

---

## 8. 下一步要决定的事

1. S2 的 MNN 是编进主包还是做成 flavor？（IRIDIS 选了 flavor，代价是两条发布线）
2. 我们的 Agent 提示与工具协议，换引擎后要不要跟着变？（MNN/LiteRT 的模板能力不同，`LocalAgentPrompt` 的英文精简提示需要重新对一遍 token 账）
3. 崩溃记忆要不要现在就建？（它不依赖第二家引擎，单独做也有价值：我们上一版就出过「首次 decode 卡死」）
