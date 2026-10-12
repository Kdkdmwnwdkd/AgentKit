# 02 · 本地模型：llama.cpp JNI 集成与 GGUF 管理

> 对应九步顺序的第 3 步。目标：手机本地离线跑 GGUF 模型，作为云端 Provider 的补充（弱网/省钱/隐私场景），并在模型管理页与云端模型并列呈现。

## 参考项目

| 项目 | 许可证 | 参考什么 |
|---|---|---|
| ggml-org/llama.cpp | **MIT** | 可直接编译集成的推理引擎本体（唯一可以正大光明用代码的项目） |
| nerve-sparks/iris_android | Apache-2.0 | Android 端 JNI 桥接、模型下载管理、聊天界面集成的完整样板 |
| maid（Android 本地 LLM 客户端） | 调研参考 | GGUF 量化档位选择与内存提示的交互设计 |
| BigMoeOnEdge（论文/项目） | 调研参考 | MoE 大模型在端侧的极限运行参数（超内存换页策略） |

## 它怎么做的（架构思想）

### llama.cpp（MIT，可深度使用）

- **一套 C++ 核心 + 多前端绑定**：核心是 `llama_context` 生命周期（加载模型 → tokenize → decode 循环 → 释放），上层有 `common` 的采样/对话模板逻辑。Android 集成 = NDK CMake 编出 `libllama.so`（arm64-v8a 为主，x86_64 可选）+ 一层薄 JNI。
- **GGUF 单文件模型**：权重 + tokenizer + **内嵌 Jinja 对话模板**，一个文件走天下。量化档位（Q4_K_M / Q5_K_M / Q8_0）由发布方预处理好，用户只选文件。
- **chat template 自动应用**：`llama_chat_apply_template` 按模型自带的模板把 messages 拼成 prompt，**不需要我们为每个模型手写 prompt 格式**——这是本地模型体验不碎片化的关键。
- **mmap 加载**：模型文件内存映射，按需换页，启动快、内存峰值可控。

### iris_android（Apache-2.0，样板级参考）

- JNI 封装只做三件事：`loadModel(path, params)` / `generate(prompt, callback)` / `freeModel()`，**其余全在 Kotlin 侧**——这是我们要学的边界感。
- 模型管理：内置模型目录（HuggingFace 直链）+ 下载进度 + 已装列表，下载完校验文件大小即视为可用。
- 生成回调通过 JNI 往上抛 token 增量，Kotlin 侧转 `Flow<String>` 喂给 UI。

### BigMoeOnEdge（思路参考）

MoE 大模型（如 Qwen3-30B-A3B）单权重超物理内存时的策略：mmap + 按 expert 激活换页 + 限制 context 到 2K-4K。**对我们意义**：12GB 内存的旗舰机也能跑 30B 级 MoE，但要把"内存不足"的预期管理做进 UI，而不是等 OOM 崩溃。

## 我们的实现

### 模块位置与边界

```
core/llm/local/
├── LocalLlamaClient.kt     // 实现 LLMClient（id = "local-llama"）
├── LlamaJni.kt             // external fun 声明（极薄，不写逻辑）
├── GgufModelStore.kt       // 模型文件管理（下载/校验/删除/列表）
└── ModelDownloader.kt      // 断点续传下载 + 进度 Flow

app/src/main/cpp/           // llama.cpp 源码 + CMakeLists（NDK 编译）
ui/ModelManagerScreen.kt    // 新页面：模型市场/已装/参数
```

### LocalLlamaClient 实现要点

```kotlin
class LocalLlamaClient(private val setting: LocalLlamaSetting) : LLMClient {
    override val id = "local-llama"

    // llama.cpp 的 llama_context 非线程安全 → 单线程 Dispatcher 串行化
    private val genDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage =
        withContext(genDispatcher) {
            val prompt = LlamaJni.applyChatTemplate(messages)     // 用模型自带模板
            val text = LlamaJni.generate(prompt, setting.contextSize, setting.threads)
            AssistantMessage(text, emptyList())                    // 本地模型不接工具调用
        }

    override fun streamText(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<String> = flow {
        val prompt = LlamaJni.applyChatTemplate(messages)
        LlamaJni.generateStream(prompt) { token -> /* channel.send(token) */ }
    }.flowOn(genDispatcher)

    override suspend fun listModels(): List<ModelInfo> = GgufModelStore.installedModels()
}
```

关键决策：

1. **本地模型不挂工具调用**。小模型 tool-call 准确率不可靠，挂了反而毁体验；UI 上切到 local-llama 时工具列表自动清空并提示"本地模型暂不支持工具"。
2. **单线程 Dispatcher 串行化**。llama_context 不是线程安全的，并发 generate 会直接段错误；同时这也是天然的"生成中拒绝第二次请求"。
3. **取消传播**。`generate` 循环里每 N 个 token 检查协程取消状态，JNI 侧设 `stop` 标志位——用户点"停止"必须真正停下（现有 ChatScreen 已有停止按钮，直接复用）。
4. **applyChatTemplate 走 JNI**：把 messages 数组（role/content）传给 C++ 侧调 `llama_chat_apply_template`，避免 Kotlin 侧解析 Jinja。

### GGUF 模型管理

- **内置模型目录**（`GgufModelStore` 硬编码 + 远端 JSON 可更新）：

| 模型 | 参数量 | 量化 | 文件大小 | 建议内存 |
|---|---|---|---|---|
| Qwen3-4B | 4B | Q4_K_M | ~2.5 GB | 6 GB+ |
| Gemma-3-4B | 4B | Q4_K_M | ~2.5 GB | 6 GB+ |
| Qwen3-8B | 8B | Q4_K_M | ~5 GB | 10 GB+ |
| Qwen3-30B-A3B（MoE） | 30B-A3B | Q4_K_M | ~18 GB | 12 GB+（mmap 换页） |

- 存储位置：`context.filesDir/models/`（应用私有目录，卸载即清，不污染用户存储）
- 下载：OkHttp 断点续传（`Range` 头），进度 `Flow<Float>` 直接绑 UI；完成后校验文件大小与目录 JSON 中的 `sizeBytes` 一致
- HuggingFace 直链：`https://huggingface.co/{repo}/resolve/main/{file}?download=true`

### 接入模型配置页

现有 `ModelConfigScreen.kt` 是「页面内 Tab」结构（模型 Tab / 提示词 Tab），新增**第三个 Tab「本地模型」**：

- 顶部：设备内存条（`ActivityManager.MemoryInfo`）+ "推荐 6GB+ 内存"提示
- 已装模型列表：文件名 / 大小 / 加载按钮 / 删除
- 模型市场列表：内置目录卡片，点击下载，进度条 + 断点续传
- 参数区：contextSize（2K/4K/8K 滑杆）、threads（默认 `max(2, cores/2)`）、gpuLayers（Adreno 设备可开， Mali 多数不开——给默认 0 + 提示"仅高通 GPU 建议开启"）

### 与第 1 步的衔接

`LocalLlamaSetting` 已在 01 号文档定义；ProviderManager 注册 `"local-llama"` 工厂；模型配置页供应商下拉在第 3 步点亮。**第 3 步单独出可安装包**：只有本地模型 Tab + Provider 注册，不碰终端/容器/编辑器。

## 注意事项

1. **APK 体积**：llama.cpp 四个 ABI 全编会加 ~30MB。**只编 arm64-v8a**（自用旗舰机），x86_64 留待模拟器调试需要时再加。GGUF 模型一律运行时下载，绝不进 assets/APK。
2. **许可证干净**：llama.cpp 是 MIT，**这是整个调研里唯一可以整段编译进来的重型引擎**；Termux/proot/sora-editor 都不行（见 09）。
3. **NDK 版本对齐**：llama.cpp 各版本对 NDK 要求会变，锁一个 llama.cpp tag 全量 vendor 进 `app/src/main/cpp/`，避免上游 CMake 变动搞崩 CI。
4. **内存预期管理**：8B Q4 + 4K context 实际占用 ~5.5GB，切模型前读 `MemoryInfo` 弹确认框；MoE 超内存场景给"允许换页运行"显式开关，不静默卡死。
5. **生成速度诚实告知**：旗舰机 4B Q4 约 8-15 tok/s，8B 约 4-8 tok/s。模型卡片上直接标实测档位，别让用户以为和云端一样快。
6. **不接 embedding**：本地 RAG 留到记忆系统升级时再做（现在 MemoryStore 是关键词检索，够用）。
