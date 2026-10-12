# 01 · 模型层 Provider 抽象

> 对应九步顺序的第 1、2 步。目标：把「OpenAI 兼容一家通吃」扩成「Claude / Gemini / 本地 llama.cpp / 任意 OpenAI 兼容」四家可切换，且 AgentLoop 与 ChatViewModel 公开接口零改动。

## 参考项目

| 项目 | 参考位置 | 许可证 | 参考什么 |
|---|---|---|---|
| RikkaHub | `ai/` 包（`ai/Provider.kt`、`ai/ProviderManager.kt`、`ai/providers/`） | AGPL-3.0 | Provider 接口职责切分、设置序列化、注册表工厂（**只吸收架构，代码不抄**） |
| iris_android | `online/nervensparks/iris/` | Apache-2.0 | Provider 设置数据类的建模方式与落盘思路（代码风格可借鉴） |

## 它怎么做的（架构思想）

RikkaHub 的 `ai/` 包是本次调研中**最干净的 Provider 抽象**，核心四条：

1. **接口极窄、能力全枚举**。`Provider<T : ProviderSetting>` 只暴露 5 个方法：
   - `listModels()` —— 动态拉取可用模型（本地内置 + 远端 `/models` 二合一）
   - `generateText()` / `streamText()` —— 非流式 / 流式两条路
   - `generateImage()` / `editImage()` —— 图像能力**不混进文本接口**，同层并列
   （embedding 单独一个接口，按需实现，见 06 号文档复用同一模式）

2. **设置即数据**。每家供应商一个 `ProviderSetting` 数据类（`OpenAISetting` / `ClaudeSetting` / `GeminiSetting`…），`@Serializable` 注解直接落 DataStore。Provider 实例 = 接口 + 设置，**无状态、可随时重建**——用户改一个 baseUrl，下一个请求立即生效，没有"重连"概念。

3. **注册表工厂**。`ProviderManager` 持 `Map<String, (ProviderSetting) -> Provider>`，UI 层只认 `providerId: String`。新增供应商 = 注册一个工厂 + 加一个设置数据类，**UI 不用改**。

4. **协议差异内聚在实现类里**。OpenAI 的 `messages/tools` JSON 拼装、Claude Messages API（`system` 独立字段、`tool_use`/`tool_result` block 结构）、Gemini 的 `contents/parts/functionCall`，各自在实现类内部消化，对外统一成同一套 `ChatMessage / AssistantMessage / ToolCall`。

**给我们的关键启发**：「设置数据类序列化 + 工厂注册表」这两件事，把"加一家供应商"的成本压到 200 行以内；而 `listModels()` 动态化，正是第 3 步本地 GGUF 模型管理能直接复用的同一套。

## 我们的实现

### 现状盘点（已就位的部分）

`core/LLMClient.kt` 已有雏形，**第 1 步的接口层其实已完成**：

- `interface LLMClient { chat(); streamText() }` —— AgentLoop 只依赖它（`AgentLoop.kt:30` 的 `llmProvider: () -> LLMClient`）
- `LLMConfig(baseUrl, apiKey, model, customHeaders, customBody, reasoningLevel)` —— 已是"无状态、每次调用携带"的形态
- `ProviderManager` 注册表已存在（`LLMClient.kt:318`）

**缺口**：`ChatViewModel.kt:412/457` 仍在硬构造 `OpenAIClient(...)`，ProviderManager 无人使用；Claude/Gemini 实现缺失。

### 接口演进（保持向后兼容）

```kotlin
// core/llm/Provider.kt（新包，由 LLMClient.kt 平移）
interface LLMClient {
    val id: String                                    // "openai" / "claude" / "gemini" / "local-llama"
    suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage
    fun streamText(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<String>
    suspend fun listModels(): List<ModelInfo> = emptyList()   // 默认空，实现类覆盖
    suspend fun testPing(): PingResult? = null
}

data class ModelInfo(val id: String, val displayName: String, val contextLength: Int? = null)
```

- `chat/streamText` 签名不动 → AgentLoop、ChatViewModel 的调用点零改动
- `listModels` 默认空实现 → 模型配置页模型输入框旁加「获取模型列表」按钮（OpenAI 兼容端点有 `/models` 的才亮；本地 llama 走内置列表，见 02）
- `testPing` 收进接口 → ChatViewModel 两处硬构造消失，测试连接不再绑死 OpenAI 协议

### 供应商设置：从 LLMConfig 到 ProviderSetting

保留 `LLMConfig` 作为「OpenAI 兼容通用配置」（现有用户配置**零迁移**），新增三家：

```kotlin
@Serializable data class ClaudeSetting(
    val apiKey: String,
    val model: String = "claude-sonnet-4-5",
    val baseUrl: String = "https://api.anthropic.com"
)
@Serializable data class GeminiSetting(
    val apiKey: String,
    val model: String = "gemini-2.5-pro",
    val baseUrl: String = "https://generativelanguage.googleapis.com"
)
@Serializable data class LocalLlamaSetting(
    val modelPath: String,          // GGUF 绝对路径（见 02 号文档）
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val gpuLayers: Int = 0
)
```

三家的公共字段（自定义头、推理强度）抽象成 `CommonOptions`，由 UI 层统一收纳——**不塞进协议实现类**。

### 三家协议适配差异表（实现时的速查）

| 能力 | OpenAI | Claude Messages | Gemini generateContent |
|---|---|---|---|
| 端点 | `POST /chat/completions` | `POST /v1/messages` | `POST /v1beta/models/{model}:generateContent`（流式加 `?alt=sse`） |
| 鉴权 | `Authorization: Bearer` | `x-api-key` + `anthropic-version: 2023-06-01` | `x-goog-api-key` |
| system | messages[0] role=system | 顶层 `system` 字段 | `systemInstruction` |
| 工具声明 | `tools[].function` | `tools[].{name,description,input_schema}` | `tools[].functionDeclarations` |
| 工具回传 | role=tool + tool_call_id | user 消息内 `tool_result` block | `functionResponse` part |
| 多模态 | `image_url.url`（支持 data URI） | `image` block + base64 source | `inlineData`（base64 + mimeType） |
| 流式 | SSE `data:` 行 | SSE `content_block_delta` 事件序列 | SSE `data:` 行 |

**AgentKit 统一模型**：现有 `ChatMessage` 家族（System/User/Assistant/Tool）+ `AssistantMessage.toolCalls` 已是最简公共集。Claude 把一次工具调用拆成两条消息（assistant 带 `tool_use` block + user 带 `tool_result` block）的细节，全部封在 `ClaudeClient.toApiMessages()` 内部，对外不可见。

### 接线改造清单（第 1 步验收点）

1. `ChatViewModel` 删掉 412/457 两处 `OpenAIClient(...)` 硬构造，改为 `providerManager.create(config.providerId, setting)`
2. 模型配置页（`ModelConfigScreen.kt`）顶部加「供应商」下拉：openai / claude / gemini（local-llama 第 3 步再亮），选项持久化进现有 ConfigStore
3. `AgentLoop` 不动；`ChatViewModel` 公开接口不动（硬性原则 3）
4. 回归验收：现有 OpenAI 配置打开即用，对话 / 流式 / 测试连接 / Key 自检四项不回归

### 目标目录

```
core/llm/
├── Provider.kt                // LLMClient 接口 + ModelInfo + 协议无关数据模型
├── settings/                  // 各 ProviderSetting（@Serializable，落 DataStore）
├── openai/OpenAIClient.kt     // 现有代码平移（含 streamText/Ping）
├── claude/ClaudeClient.kt     // 新写：Messages API + content_block 事件流解析
├── gemini/GeminiClient.kt     // 新写：generateContent + SSE 解析
├── local/LocalLlamaClient.kt  // 第 3 步，见 02 号文档
└── ProviderManager.kt         // 现有注册表扩展（init 块注册四家）
```

## 注意事项

1. **绝不把三家差异写成 if-else 分发**。RikkaHub 早期版本吃过这个亏（协议分支全堆在一个 Client 里，后来才拆文件）。我们从第一天就一家一文件。
2. **customHeaders / customBody 逃生舱必须保留**。现在有真实用户靠它接 OneAPI / Ollama / vLLM；Claude / Gemini 设置里也要留同款字段，别"为了干净"砍掉——这是 AgentKit 兼容任意端点的心智资产。
3. **脱敏与日志复用**：新 Client 一律走 `AgentLogger.apiTrace / sanitize`。401 响应体回显密钥的坑已经踩过（`LLMClient.kt:101`），`resp.body?.string()` 只能读一次、必须先存变量。
4. **流式能力矩阵要告知用户**：某家流式未实现时，输入框旁流式开关置灰并提示，而不是点了才报错。
5. **apiKey 存储**：沿用现有 ConfigStore 方案，不引入新加密体系（自用优先，加密留到开源前统一做）。
