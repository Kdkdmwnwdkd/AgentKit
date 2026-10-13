# RikkaHub 吃透报告（参照仓库研究 第 2 路）

> 研究对象：`/root/.codebuddy/artifact/repos/rikkahub`（master，1534 文件 / 106MB，AGPL-3.0）
> 研究者：产品经理 许清楚（Xu）｜用途：为 AgentKit（`/workspace`）提供**设计原理 + 改造方案**
> 阅读原则：**只读思路、禁止抄代码**；所有事实均来自实际读码并标 `路径:行号`；未验证处标「待验证」。

---

## ① TL;DR

RikkaHub 是一个**纯聊天客户端**（无设备自动化），但它的**配置体系、聊天体验、记忆系统、预设体系**成熟度极高，恰好是 AgentKit「体验好」这一产品目标最缺的那一块。

一句话概括它的设计内核：**「一切皆可序列化 + Provider 无状态 + 会话即文档树」**。

三条最值得搬的**设计原理**（不是代码）：

1. **Provider 无状态化**：`Provider` 接口全部方法都接收 `ProviderSetting` 作参数（`ai/.../Provider.kt:17-56`），实现类不持有任何配置。→ 一个 Provider 实例可同时服务多个同名不同 Key 的配置，天然支持「多开 + Key 轮询」。AgentKit 现有 `LLMClient` 是「配置即对象」，多套配置要构造多个 client，可借鉴此解耦。

2. **消息分支用「节点 + 游标」而非「树」**：`MessageNode(messages: List<UIMessage>, selectIndex: Int)`（`app/.../data/model/Conversation.kt:113-135`）。重新生成 = 往同一节点 `messages` **追加**一条并把 `selectIndex` 指向新元素（`service/ChatService.kt:1355-1366`）；编辑 = 同样追加（`ChatService.kt:1357-1366`）。→ **扁平 List 就能表达树形分支**，序列化零成本，Room/JSON 都能直接存。这是本次研究最高性价比的一条。

3. **模型能力靠「ID 分词匹配」推断，而非维护模型表**：`ModelRegistry`（`ai/.../registry/ModelRegistry.kt:10`，795 行）用 DSL 把 `gpt-5-chat` 这类 ID tokenize 成 `["gpt","-","5","-","chat"]` 再匹配 token 序列（`ModelDsl.kt:161-175` 匹配器，`ModelDsl.kt:197-229` 分词器）。→ 新模型发布当天就能自动推断出「支持工具/推理/视觉」，无需发版更新模型表。**这条对 AgentKit 的「零新增依赖」红线极其友好**——纯 Kotlin 手写即可。

**对 AgentKit 的价值排序**：Provider/Model 配置体系（★★★★★）＞ 消息分支与上下文管理（★★★★★）＞ 记忆系统（★★★★）＞ 助手预设（★★★）＞ mediagen/speech/search/MCP（★★，AgentKit 已有替代实现）＞ 备份（★★）。

---

## ② 架构分层地图

### 2.1 模块划分（settings.gradle.kts:39-53，实际 14 个 Gradle module）

任务书里提到 8 个，实际读码发现**还有 6 个**：`document`（PDF/Office 解析，含 jniLibs）、`web`（内嵌 Ktor HTTP 服务）、`material3`、`ui`、`workspace`（proot 容器！）、`oauth`。

| 模块 | kt 数 | 职责 | 关键入口 |
|---|---|---|---|
| `:app` | 485 | 业务编排、UI 页面、Service、DB | `RikkaHubApp.kt` / `RouteActivity.kt` |
| `:ai` | 68 | **Provider 抽象 + UIMessage 模型 + 模型能力注册表** | `provider/Provider.kt`、`ui/Message.kt`、`registry/` |
| `:ui` | 46 | 通用 Compose 组件（charts/richtext/markdown/icons/sketch/table/webview） | `ui/src/main/java/me/rerere/ui/` |
| `:document` | 70 | 文档解析（含 arm64/x86_64 jniLibs） | `document/src/main/java/me/` |
| `:highlight` | 51 | **自研语法高亮引擎**（highlight.js 风格 Mode 编译器，含 hljs 测试资源） | `highlight/core/HighlightEngine.kt` |
| `:speech` | 47 | TTS（13 家）+ ASR（5 家） | `tts/provider/TTSManager.kt`、`asr/ASRController.kt` |
| `:mediagen` | 30 | 图片/视频生成（阿里云/MiniMax/OpenAI/OpenRouter/火山） | `provider/MediaGenerationManager.kt` |
| `:search` | 25 | **20 家联网搜索服务** + 网页抓取 | `SearchService.kt` |
| `:common` | 17 | 缓存（LRU/单文件/按 key）、SSE、QuickJS Fetch | `cache/`、`http/SSE.kt`、`js/` |
| `:workspace` | 11 | **proot Linux 容器 + rootfs 安装 + PTY** | `ProotShellRunner.kt` / `RootfsInstaller.kt` |
| `:web` | 3 | Ktor 内嵌 Web 服务 | `web/src/main/java/me/rerere/` |
| `:oauth` | 6 | OAuth（Custom Tabs + loopback 回调，供 MCP 用） | `oauth/src/main/java/me/rerere/oauth/` |
| `:material3` / `app:baselineprofile` | 3 / - | 主题封装 / 基线profile | — |

### 2.2 技术栈（gradle/libs.versions.toml:1-63）

| 项 | 版本 | AgentKit 现状 | 差异 |
|---|---|---|---|
| Kotlin | **2.4.20** | 1.9.25 | 差 2 个大版本 |
| AGP | **9.4.0** | — | — |
| Compose BOM | **2026.09.00**（material3 1.5.0-alpha29） | 2024.09.00 | 差 2 年 |
| OkHttp | **5.5.0** | **4.12.0（红线）** | 大版本不同 |
| kotlinx-serialization-json | **1.11.0** | **1.6.3（红线）** | 版本不同但 API 兼容区重叠 |
| 依赖注入 | **Koin 4.2.2** | 无（手写） | AgentKit 不应引入 |
| 数据库 | **Room 2.8.5 + sqlite-vector 1.0.0** | 文件 JSON | AgentKit 不应引入 |
| 导航 | **Navigation 3（nav3Core 1.2.0）** | 手写返回栈 | AgentKit 不应引入 |
| MCP | **io.modelcontextprotocol 0.15.0-rikka.4 + Ktor 3.5.2** | 自研 mcp 包 | — |
| 脚本引擎 | **QuickJS 1.0.15** | **Rhino 1.7.14（红线）** | — |
| 其他 | coil 3.6.3 / retrofit 3.0 / workmanager / firebase / jlatexmath / jmdns / snakeyaml / termux 0.118.0 | — | — |

> **红线提示**：AgentKit「零新增外部依赖」，所以上表 Room/Koin/Nav3/Ktor/QuickJS/reorderable/haze/ucrop 等**一律只能学思路、净室重写**。

### 2.3 分层与依赖方向

```
:app  (页面/VM/Service/Repository/DB/同步)
  ├── :ai        ← Provider 抽象、UIMessage、Model、ModelRegistry（核心域模型）
  ├── :common    ← 缓存/SSE/JSON 工具
  ├── :ui        ← 通用 Compose 组件
  ├── :highlight ← 代码高亮（被 :ui richtext 消费）
  ├── :search :speech :mediagen :document   ← 能力子域（各自独立可插拔）
  ├── :workspace ← proot 容器（:app 的 WorkspaceRepository 消费）
  ├── :web :oauth ← 内嵌服务与授权
  └── :material3
```

依赖方向干净：**能力子域之间互不依赖，全部被 `:app` 编排**；`:ai` 是唯一的「领域模型」模块，被所有子域引用。这与 AgentKit 的 `core/` 定位一致，但 AgentKit 是单模块，靠包名隔离。

### 2.4 导航结构（`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`）

- 采用 **Navigation 3**：`rememberNavBackStack(startScreen)`（:294）+ `NavDisplay`（:325）+ `entryProvider`（:346 起，逐条 `entry<Screen.X>{}`）。
- 路由定义是**一个 `@Serializable` 的 sealed interface**：`RouteActivity.kt:626` `sealed interface Screen : NavKey`，下面 ~50 个 `@Serializable data class/data object`（:628-780）。
- 主要页面组：`Chat` / `History` / `Favorite` / `Assistant(+Detail/Basic/Prompt/Memory/Request/Mcp/LocalTool/Injections)` / `MemoryFile` / `Translator` / `Setting(+12 个子页)` / `Backup` / `ImageGen` / `MediaCreation(Sessions)` / `WebView` / `Debug` / `Log` / `Extensions` / `QuickMessages` / `Prompts` / `Skills` / `Workspaces(+Detail/Terminal)`。
- **入口**：`Screen.Chat(id)` 为首页（:283），并支持 `ACTION_SEND` / `ACTION_PROCESS_TEXT` / `ACTION_TRANSLATE` / 媒体生成 deep link（:229-260）。

> 值得注意：RikkaHub 也有 **`Workspaces` + `WorkspaceTerminal`** 页面和 **proot 容器**，与 AgentKit 的 `container/proot` + `terminal` 高度同构。详见 ③.7。

---

## ③ 核心功能逐项剖析（按与 AgentKit 相关性排序）

### 3.1 Provider / Model 配置体系 ★核心重点

#### 3.1.1 ProviderSetting：sealed class + 统一抽象

`ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`

- `ProviderSetting.kt:26` `sealed class ProviderSetting` 声明 8 个抽象成员：`id:Uuid / enabled / name / models:List<Model> / balanceOption / customHeaders / builtIn / description / shortDescription`（:27-36）。
- 抽象方法（:38-52）：`addModel / editModel / delModel / moveMove(from,to) / copyProvider(...)` —— **注意这是「类型擦除式」的通用增删改**：返回 `ProviderSetting`，让 UI 不需要 `when` 分支就能操作任意类型的 Provider 配置。
- 三个子类型：
  - `OpenAI`（:54-118）：`apiKey / baseUrl / chatCompletionsPath="/chat/completions" / useResponseApi / includeHistoryReasoning / responsesPath="/responses"`
  - `Google`（:120-187）：`apiKey / baseUrl / vertexAI / useServiceAccount / privateKey / serviceAccountEmail / location / projectId / useInteractionsApi`
  - `Claude`（:189-251）：`apiKey / baseUrl="https://api.anthropic.com/v1" / promptCaching / promptCacheTtl`
- `@SerialName("openai"/"google"/"claude")`（:55/:121/:190）+ `companion object Types`（:253-260）列出三个 `KClass` 供 UI 枚举。
- 序列化细节：`builtIn / description / shortDescription` 标 `@Transient`（:63-65 等），**不落盘**——因为它们带 `@Composable` lambda 且是内置常量。
- `BalanceOption`（:10-14）：`enabled / apiPath="/credits" / resultPath="data.total_usage"` —— 用 JSON 路径表达式从任意端点抠余额，可配置。

**设计要点**：只有 **3 个 Provider 类型**，「OpenAI 兼容」是靠 `baseUrl` 可改实现的（vLLM/OneAPI/Ollama/DeepSeek 全都复用 `ProviderSetting.OpenAI`）。这比「每家一个类型」轻量得多。

#### 3.1.2 Model 数据模型

`ai/src/main/java/me/rerere/ai/provider/Model.kt`

```kotlin
Model(                      // :8-22
  modelId, displayName, id:Uuid, type:ModelType,
  customHeaders, customBodies,
  inputModalities:List<Modality>, outputModalities:List<Modality>,
  abilities:List<ModelAbility>, tools:Set<BuiltInTools>,
  providerOverwrite:ProviderSetting? = null,   // ← 单模型级覆盖 Provider 配置
  contextLength:Int = 1_000_000,
)
enum ModelType { CHAT, IMAGE, EMBEDDING }   // :25-29
enum Modality  { TEXT, IMAGE }              // :32-35
enum ModelAbility { TOOL, REASONING }       // :38-41
sealed BuiltInTools { Search, UrlContext, ImageGeneration }  // :45-59
```

- **`providerOverwrite`（:19）是个巧思**：单个模型可以挂一份独立的 ProviderSetting 覆盖父级（比如某模型走不同的 baseUrl/Key），而不必新建整个 Provider。
- `tools: Set<BuiltInTools>` 声明**模型自带的**服务端工具（Gemini 的 Google Search / url_context / 生图）。
- 注意 `contextLength` 默认 **100 万**，是个宽松兜底。

#### 3.1.3 Provider 接口：无状态设计

`ai/src/main/java/me/rerere/ai/provider/Provider.kt:17-56`

```kotlin
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>
    suspend fun getBalance(providerSetting: T): String          // 默认 "TODO"
    suspend fun generateText(providerSetting: T, messages, params): TextGenerationResult
    suspend fun streamText(providerSetting: T, messages, params): Flow<StreamChunk>
    suspend fun generateEmbedding(...)   // 默认 error(...)
    suspend fun generateImage(...)       // 默认 error(...)
    suspend fun editImage(...)           // 默认 error(...)
}
```

- **所有能力方法都带 providerSetting 参数**，实现类无状态 → `ProviderManager` 只需注册 3 个单例（`ProviderManager.kt:16-21`）。
- **不支持的能力用默认 `error()` 而非 `TODO()`**（:36-55）——这是很好的工程习惯：运行时给明确异常，编译期不强迫每家都实现。
- `TextGenerationParams`（:68-78）携带 `model / temperature / topP / maxTokens / tools / reasoningLevel / customHeaders / customBody / sessionId`。
- `CustomHeader(name,value)`（:119-122）与 `CustomBody(key, value: JsonElement)`（:125-128）——**自定义 Body 用 JsonElement 而非 String**，可以塞对象/数组。

#### 3.1.4 ProviderManager：极简注册表

`ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt`

- `providers: MutableMap<String, Provider<*>>`（:14），`init` 里注册 openai/google/claude（:16-21）。
- `getProviderByType(setting)`（:49-56）用 `when (setting) is OpenAI/Google/Claude` 分派 + `unchecked cast`。
- Koin 注入：`ProviderManager(client: OkHttpClient, context: Context)`。

#### 3.1.5 自定义 Provider：类型转换（convertTo）

`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt`

- `ProviderConfigure(...)`（:51）里可切换 Provider 类型，回调 `onEdit(provider.convertTo(it))`（:69）。
- `fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting`（:90）——**切换类型时保留 id/name/models/enabled 等公共字段**。
- **BaseUrl 智能转换**（:109 / :175-201）：`sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)`，用 OkHttp 的 `toHttpUrlOrNull()` 解析出 `encodedPath`，再做 `convertToTargetPath`（:184）——把 OpenAI 风格的 `/v1` 路径段换成目标类型的默认路径段。例：从 OpenAI→Claude 时，用户已填的 `https://x.com/v1` 会被正确改写成 Claude 的默认路径形态。
- `defaultBaseUrlForReset()`（:134）/ `isUsingDefaultBaseUrl()`（:150）/ `resetBaseUrlButton`（:160）——「重置为默认」按钮只在 URL 被改过时出现。
- 三个类型的配置表单：`ProviderConfigureOpenAI`（:277）/ `ProviderConfigureClaude`（:332）/ `ProviderConfigureGoogle`（:379）。

#### 3.1.6 模型导入：listModels → 勾选 → 落库

`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt`（1585 行，最大的设置页）

- `ModelList` Composable（:457）：`produceState(emptyList(), providerSetting)` 里调 `providerManager.getProviderByType(providerSetting).listModels(providerSetting).sortedBy { it.modelId }`（:464-469）。**失败只 printStackTrace，UI 保持空列表**（:470-472）——不弹错误打扰用户。
- 已选模型用 `ReorderableItem` 拖拽排序（:490-492 → `providerSetting.moveMove(from,to)`）。
- `AddModelButton`（:521-530）是 `HorizontalFloatingToolbar` 里的悬浮按钮，传入 `models = modelList`（远端全量）与 `selectedModels = providerSetting.models`（已选），支持全选/取消全选（:913-922）。
- **去重按 `modelId` 而非 `Uuid`**：`selectedModels.any { model -> model.modelId == it.modelId }`（:990），删除时才按 id 取回（:997-999，注释明确说明「删除需要 id 匹配，而不是 ModelId」）。

#### 3.1.7 三家 listModels 端点实现

| Provider | 位置 | 端点 | 鉴权头 | 解析 |
|---|---|---|---|---|
| OpenAI | `providers/openai/OpenAIProvider.kt:61-89` | `GET {baseUrl}/models` | `Authorization: Bearer $key` | `data[].id`，displayName 也取 id |
| Claude | `providers/claude/ClaudeProvider.kt:247-276` | `GET {baseUrl}/models` | `x-api-key` + `anthropic-version` | `data[].id`，`display_name` 兜底 id |
| Google | `providers/google/GoogleProvider.kt:130-168` | `GET {baseUrl}/models?pageSize=100` | 走 `transformRequest` | `models[]`，`name` 去掉 `models/` 前缀；**按 `supportedGenerationMethods` 过滤**，含 `generateContent` → CHAT，仅 `embedContent` → EMBEDDING，两者都无则跳过（:151-163） |

三家的共同模式：`withContext(Dispatchers.IO)` + `client.newCall(request).await()` + `!isSuccessful → error(...)` + `json.parseToJsonElement(body).jsonObject` 手撸解析（**不用 DTO 数据类**，直接走 `JsonElement`）。

> 对账 AgentKit：AgentKit 的 `core/ModelListFetcher.kt:52-149` 已实现**完全同构**的三家端点（含 Gemini 的 `supportedGenerationMethods` 过滤、`/v1` 与 `/v1beta` 兜底拼接、脱敏异常）。**这一项 AgentKit 已经追平**，差异只在 UI 层（见 ④）。

#### 3.1.8 Key 轮询（KeyRoulette）

`ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt`

- `interface KeyRoulette { fun next(keys: String, providerId: String = ""): String }`。
- 两种策略：`default()` 随机（:28-36）；`lru(context)` LRU（:57-92）。
- **Key 可以是多把**：按 `[\s,]+` 切分（:20，空格/换行/逗号），去重（:26）。
- LRU 持久化到 `cacheDir/lru_key_roulette.json`，结构 `Map<providerId, Map<apiKey, lastUsedTimestamp>>`（:43），过期 24h（:40），全局文件锁防并发（:46）。
- 选型逻辑（:70-72）：**优先选「从未用过」的 key，否则选最久未用的**。
- 调用点：`OpenAIProvider.kt:63`、`ClaudeProvider.kt:252`（都传 `providerSetting.id.toString()` 作 providerId）。

#### 3.1.9 ModelRegistry：模型能力推断（本库最亮眼的设计之一）

`ai/src/main/java/me/rerere/ai/registry/ModelRegistry.kt`（795 行）+ `ModelDsl.kt`（229 行）

**原理**：不维护「模型白名单表」，而是写**匹配规则**，运行时按模型 ID 推断能力。

```kotlin
// ModelRegistry.kt:79-85
val GPT_5 = defineModel {
    tokens("gpt", "5")
    notTokens("gpt", "5", ".")
    notTokens("gpt", "5", "chat")     // 排除 gpt-5-chat
    visionInput()
    toolReasoningAbility()
}
// ModelRegistry.kt:24-28
val OPENAI_O_MODELS = defineModel {
    tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))   // o1/o3/o4...
    visionInput(); toolReasoningAbility()
}
```

**匹配引擎**（`ModelDsl.kt`）：
- `tokenize(modelId)`（:197-229）：把 ID 小写后切成 token 流——连续字母一块、连续数字一块、其他字符各成一块。`"gpt-5-chat"` → `["gpt","-","5","-","chat"]`。
- `TokenSequenceMatcher`（:161-175）：在 token 流里**顺序**匹配 spec 序列（不要求相邻），命中返回 `specs.size` 作为 score。
- `NotTokenSequenceMatcher`（:177-185）：命中则返回 `null`（排除）。
- `ExactIdMatcher`（:149-159）：全等命中给 `EXACT_ID_BONUS=1000 + tokens.size`（:195）。
- `AndMatcher`（:136-147）：多个 matcher 全命中才返回总分。
- `parseTokenSpec`（:187-193）：字符串 spec 支持 `|` 分隔的**备选**（如 `tokens("gpt|claude","5")`）。
- `ModelDefinition.matchScore()`（:22-25）返回 `Int?`，用于**多条规则竞争时选分最高者**。
- `ModelGroup`（:31-35）：聚合多个 selector，`any` 命中即可。

**消费方**（真正决定行为的地方）：
- `providers/openai/ChatCompletionsAPI.kt:443-448`：判断「Moonshot 限制 temperature」——`KIMI_K2_5/K2_6/K3/K3_ALIAS` 命中才受限；`OPENAI_O_MODELS` / `GPT_5` 不支持 temperature。
- `providers/openai/ResponseAPI.kt:710`：同上（Responses API 版本）。
- `providers/google/GoogleProvider.kt:381-382` + `InteractionsAPI.kt:262-263`：`GEMINI_3_SERIES` / `GEMINI_4` 才支持 minimal reasoning level。

> **这就是「不发版也能支持新模型」的实现**：规则写的是「gpt + 5 + 非 chat」这种**形态**，新模型只要命名规律一致就自动命中。

#### 3.1.10 ModelListSheet：模型选择器（搜索/收藏/排序/折叠）

`app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt`（935 行）

**状态类** `ModelListState`（:101-143）：`modelId / providers / type:ModelType / visible`，派生 `currentModel`（:118-119）与 `filteredProviders`（:121-124，只保留 `enabled` 且含该 type 模型的 Provider）。`rememberModelListState`（:145-164）用 `remember{}` 持有 + `.also{ update }` 同步外部变化。

**Sheet 主体** `ModelListSheet`（:305-352）：`ModalBottomSheet` + `rememberBottomSheetState(skipPartiallyExpanded)`（`enabledValues = setOf(Hidden, Expanded)`，:313-316）+ `fillMaxHeight(0.8f)` + `imePadding()`。

**内部列表** `ModelList`（:354-...）：

1. **收藏区**（:367-372）：从 `settings.favoriteModels: List<Uuid>` 反查 `(Model, Provider)` 对，过滤掉 type 不匹配的。
2. **搜索**（:374）：`searchKeywords` 本地 state；过滤条件 `it.displayName.contains(searchKeywords, true)`（:399）——**只搜 displayName，不搜 modelId**（可视为一处取舍）。
3. **Provider 折叠持久化**（:377-388）：`rememberSharedPreferenceString("model_list_collapsed_providers","")`，逗号分隔的 id 集合；`isCollapsed()`（:381-382）在**搜索时强制展开**。
4. **三级派生数据**（:390-408）：`typeFilteredModelsByProvider` → `searchFilteredModelsByProvider` → `visibleModelsByProvider`，全用 `remember(...)` 缓存，避免每次重组重算。
5. **初始滚动定位**（:411-458）：`selectedModelPosition` 手工累加「无 Provider 提示项 + 收藏 header + 收藏数 + 各 Provider header + 模型数」，算出当前选中项在 LazyColumn 里的绝对下标，喂给 `rememberLazyListState(initialFirstVisibleItemIndex)`。**打开选择器时直接定位到当前模型**，不用用户自己找。
6. **收藏拖拽排序**（:459-485）：`rememberReorderableLazyListState` 回调里做下标偏移换算（`favoriteStartIndex`），只处理收藏区间内的拖拽，写回 `settings.favoriteModels`。带 `LocalHapticFeedback` 震动反馈（:610-613）。
7. **搜索框**（:506-533）：`Surface(shape=RoundedCornerShape(50))` 包 `OutlinedTextField`，`colors` 全设 `Transparent`，实现「胶囊搜索框」。
8. **stickyHeader 分组**（:554-563 收藏 / :625-657 Provider）：Provider header 行可点击折叠、右侧显示 `ProviderBalanceText`（实时余额）。
9. **标签**（:763-775 调用点；定义 :849-935）：`ModelTypeTag`（CHAT/IMAGE/EMBEDDING）、`ModelModalityTag`（TEXT/IMAGE 带图标）、`ModelAbilityTag`（TOOL/REASONING 不同 TagType 配色）。
10. **收藏切换**：非收藏项点击心形 → `favoriteModels + model.id`（:685-686 区）；收藏项点击 → `filter { it != model.id }`（:680-681 区）。

> **依赖红线提醒**：该文件用了 `sh.calvin.reorderable`（:97-98）做拖拽、`androidx.compose.material3.ModalBottomSheet`。AgentKit 落地需手写长按拖拽（或先用「上移/下移按钮」降级）。

---

### 3.2 聊天 UI

#### 3.2.1 消息模型 UIMessage

`ai/src/main/java/me/rerere/ai/ui/Message.kt:18-36`

```kotlin
UIMessage(
  id: Uuid, role: MessageRole, parts: List<UIMessagePart>,
  annotations, createdAt, finishedAt,
  modelId: Uuid?, modelSnapshot: ModelSnapshot?,   // ← 快照
  usage: TokenUsage?, translation: String?,
  isContextCheckpoint: Boolean = false,            // ← 压缩检查点
  @Transient isSynthetic: Boolean = false,         // 内部消息，仅内存
)
```

- **`parts: List<UIMessagePart>`** 而非 `content: String`——多模态内容（Text/Image/Video/Audio/Document/Reasoning/Tool/ServerTool/Search）统一为 part 列表。
- **`modelSnapshot: ModelSnapshot(modelId, displayName)`**（:110-114）：模型被删除后，消息仍能显示「由谁生成」（:83-87 `snapshotModel()`，注释明确「不能拿它发起请求」）。落库时由 `Conversation.fillModelSnapshots(settings)`（`data/model/Conversation.kt:150-163`）补齐。
- **`isContextCheckpoint`**（:32）：标记「这条消息是它之前全部历史的摘要」。

`UIMessagePart`（`ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt`）关键设计：
- `ToolApprovalState` sealed：`Auto / Pending / Approved / Denied(reason) / Answered(answer)`（:12-29）——**审批态是可序列化的消息的一部分**，可持久化、可恢复。
- `UIMessagePart.Tool` 把「调用」和「结果」合并在一个 part 里（`input` + `output`），**不再需要独立的 TOOL role 消息**（`Message.kt:366-436` 是历史数据的迁移函数）。

#### 3.2.2 消息分支（重新生成 / 编辑）★重点

**数据结构**（`app/.../data/model/Conversation.kt`）：

```kotlin
Conversation(                       // :19-41
  id, assistantId, title,
  messageNodes: List<MessageNode>,  // ← 节点列表（不是消息列表）
  chatSuggestions, isPinned, createAt, updateAt,
  customSystemPrompt, modeInjectionIds, lorebookIds,
  config: ConversationConfig? = null,   // 会话固定配置
  workspaceCwd, folderId
)
val currentMessages: List<UIMessage>   // :51-54
    get() = messageNodes.map { node -> node.messages[node.selectIndex] }

MessageNode(                        // :113-135
  id, messages: List<UIMessage>,    // ← 同一位置的多个候选
  selectIndex: Int = 0,             // ← 当前选中哪个
  @Transient isFavorite
)
```

**→ 扁平 List + 游标 = 树形分支。** 线性展示取 `currentMessages`，切换分支只改 `selectIndex`。

**四种操作的落点**（`app/.../service/ChatService.kt`）：

| 操作 | 位置 | 做法 |
|---|---|---|
| 重新生成（user 消息） | :511-549 `regenerateAtMessage` | 若是 USER 消息：截断到该节点（`subList(0, indexAt+1)`）后重新生成（:528-533） |
| 重新生成（assistant 消息） | :538-542 | `handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)` —— 从该节点之前重新跑，结果**追加**为兄弟 |
| 编辑消息 | `:1327` `editMessage` 起 | `node.copy(messages = node.messages + UIMessage(role = node.role, parts = processedParts, modelId = original.modelId, ...), selectIndex = node.messages.size)`（:1357-1366）→ **原消息保留，新增一条兄弟并指向它** |
| 切换分支 | :1412-1440 `selectMessageNode` | 校验 `selectIndex in indices` + 与原值不同 → `node.copy(selectIndex = selectIndex)` |
| 删除消息 | :1462-1491 `buildConversationAfterMessageDelete` | 从 node 里 `filterNot`，空了就删整个 node；`selectIndex.coerceAtMost(lastIndex)` |
| 分叉成新会话 | :1374-1408 `forkConversationAtMessage` | 复制 `subList(0, targetNodeIndex+1)`，**全部换新 Uuid**，附件文件也复制一份（`copyWithForkedFileUrl`，:1493+），标题去重后保存 |

**特殊处理**：编辑**压缩检查点**消息时不新建分支，而是原地改写（:1348-1355），注释解释：「新建分支会丢掉检查点标记，删除摘要时还会露出旧版本」。

**UI 组件** `ui/components/message/ChatMessageBranch.kt:32-84`：`ChatMessageBranchSelector`，只在 `node.messages.size > 1` 时显示，左箭头 / `「当前/总数」`文本 / 右箭头，到头时 `alpha(0.5f)` 置灰。

#### 3.2.3 上下文截断：滞回（hysteresis）策略

`ai/src/main/java/me/rerere/ai/ui/Message.kt:161-249` —— 这是很值得学的工程细节。

- `limitContext(limit)`（:171-175）：先找最后一个 `isContextCheckpoint`，从它开始取（检查点本身始终保留），再限条数。
- `limitMessageCount(limit)`（:188-201）：**不是滑动窗口**！注释（:177-187）解释：每轮平移一条会让请求前缀每轮都变，**打不中 prompt cache**。所以做成阶梯式：
  ```
  CONTEXT_KEEP_RATIO = 0.5f                       // :161
  target = (limit * 0.5).roundToInt().coerceIn(1, limit)
  stride = (limit - target).coerceAtLeast(1)
  startIndex = (((size - limit) / stride + 1) * stride).coerceAtMost(size - 1)
  ```
  即：越过 `limit` 才截断，且一次前进 `stride` 条，之后连续多轮截断点不动 → **前缀稳定 → 命中缓存**。保留条数落在 `[limit*0.5, limit)`。
- `alignContextStart(startIndex)`（:209-249）：截断点回退到安全边界——若起点消息含「已执行的 tool」则往前找到对应未执行的 tool call；若含「未执行的 tool call」则往前找到 USER 消息。**避免把 tool call 和它的结果拆散**。带 `visitedIndices` 防死循环（:220-221）。

#### 3.2.4 流式渲染与思考过程

- `StreamChunk` / `StreamChunkHandler`（`ai/src/main/java/me/rerere/ai/ui/StreamChunk.kt`、`StreamChunkHandler.kt`）—— 流式分片与累积处理。
- 思考过程：`UIMessagePart.Reasoning(reasoning, createdAt, finishedAt, reasoningType)`，其中 `ReasoningType { REASONING_TEXT, SUMMARY_TEXT }`（`UIMessagePart.kt:56-64`）——区分「原始思维链」与「摘要」。
- UI：`ui/components/message/ChatMessageReasoning.kt`（折叠展开 + `AnimatedContent` 淡入淡出/上下滑动 + 渐变遮罩 `drawWithCache`/`Brush`/`BlendMode`，:33-38 import 可见）+ `ChatMessageCot.kt`（Chain-of-Thought）。
- `UIMessage.finishReasoning()`（`Message.kt:289-307`）与 `finishPendingTools()`（:309-328）负责在流式结束时**补写时间戳**，把「未完成」状态收尾。

#### 3.2.5 Markdown / 代码高亮

- `ui/components/richtext/`：`Markdown.kt`、`MarkdownWeb.kt`、`HighlightCodeBlock.kt`、`LatexText.kt`、`MathBlock.kt`、`Mermaid.kt`、`ZoomableAsyncImage.kt`。
- **`:highlight` 模块是自研的 highlight.js 移植**：`core/HighlightEngine.kt`、`core/Mode.kt`、`core/ModeCompiler.kt`、`core/MultiRegex.kt`、`core/Regexes.kt`、`core/TokenEmitter.kt`、`core/Keywords.kt`、`core/CommonModes.kt`；`languages/` 下有 bash/c/cmake/cpp/csharp/css/dart/diff/dockerfile/glsl/go/ini/java/javascript/json/kotlin…（51 个 kt，含 `src/test/resources/hljs` 官方测试用例做回归）。
- 支持 **Mermaid 图**与 **LaTeX 公式**（jlatexmath）。

---

### 3.3 记忆系统

#### 3.3.1 存储形态：Markdown 文件 + frontmatter

`app/src/main/java/me/rerere/rikkahub/data/model/MemoryFile.kt`

```kotlin
data class MemoryFile(val path: String, val content: String, val updatedAt: Long)   // :11-15
val version by lazy { memoryVersionOf(content) }   // :16 → SHA-256 前 12 位，写操作乐观锁
```

- **记忆 = 一个 `.md` 文件**，frontmatter（YAML）存 `name / title / description / aliases`，正文是内容（:18-35，复用 `SkillFrontmatterParser`）。
- 路径规范（:66-74）：必须以 `/` 开头、以 `.md` 结尾，禁止控制字符/反斜杠，段名不能为 `.` 开头，最长 128。
- 固定目录（:88-89）：`/profile.md` 与 `/preferences.md` **每次全文注入**；目录顺序 `/`、`/topics`、`/areas`、`/people`。
- 单文件上限 `MAX_BYTES = 48 * 1024`（:63）。
- 正文里的 `[stated]` 标记是写给模型看的，UI 上剥掉（:29、:85）。

#### 3.3.2 给模型的工具（6 个）

`app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt:24-32`

```kotlin
object MemoryToolNames {
  READ="memory_read"; WRITE="memory_write"; STR_REPLACE="memory_str_replace"
  APPEND="memory_append"; DELETE="memory_delete"; LIST="memory_list"
  val WRITES = setOf(WRITE, STR_REPLACE, APPEND, DELETE)
}
```

- `buildMemoryTools(repository, assistantId): List<Tool>`（:42）——**记忆不是自动注入，而是给模型 6 个工具由它自己决定何时读写**（:37-40 注释：「何时读、何时写由 system prompt 里的记忆片段约束，这里只描述每个工具本身」）。
- `memory_read` 支持一次多路径（`MAX_READ_PATHS = 20`，:34），且**容错模型只传字符串而非数组**（:64-69）。
- 写操作带 `ifVersion` 参数做乐观锁（:36-37）。

#### 3.3.3 自动整理（Consolidation）

`app/src/main/java/me/rerere/rikkahub/data/ai/MemoryConsolidationScheduler.kt`（278 行）+ `MemoryConsolidator.kt`（389 行）+ `prompts/MemoryPrompts.kt`（244 行）

- **触发时机**（类注释 :83-86）：对话空闲一段时间后 / 攒够轮数 / 用户离开对话或切走 App / 用户手动触发。空闲阈值 `idleDelay: Duration = 2.minutes`（:95）。
- **调度单位**：以**记忆库**为单位，一次覆盖该记忆库下所有有新内容的对话；同一记忆库同一时间只跑一次。
- `MemoryConsolidationWorker` 接口（:55-81）定义 `pendingOf / memoryIdsWithUpdates / findPending / markConsolidated / consolidate`。
- 结果模型 `MemoryConsolidationRun`（:31-44）含 `changedPaths / deletedPaths / titles / duration / error`，用于在 UI 上展示「刚整理了哪几个记忆」。
- `MemoryDreamKeepAlive`（:88-91）：整理期间拿 WakeLock 防止进程被冻结（拿不到就退化为空实现，整理照常）。
- 招了个好名字：**「Memory Dream」**（见 `RikkaHubApp.kt:58` `MEMORY_DREAM_NOTIFICATION_CHANNEL_ID`）。

#### 3.3.4 与 AgentKit 的差异（重要）

| | RikkaHub | AgentKit 现状 |
|---|---|---|
| 形态 | Markdown 文件树 + frontmatter | `MemoryItem` 扁平 JSON（`memory/MemoryStore.kt:17-24`） |
| 检索 | 模型主动调 `memory_read` | 关键词命中 + salience + 时间衰减打分（`MemoryStore.kt:32-39` 注释） |
| 写入 | 模型主动调 6 个工具 | Agent 调 `MemoryTools` |
| 整理 | 定时/空闲自动 Consolidation | 无自动整理 |
| 容量 | 单文件 48KB | 500 条上限，按 salience 淘汰 |

---

### 3.4 助手预设体系

#### 3.4.1 Assistant 数据模型

`app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt:16-53`（**37 个字段**，是配置体系的集大成者）

```kotlin
Assistant(
  id, chatModelId: Uuid? = null,      // null = 用全局默认模型
  name, avatar, useAssistantAvatar, tags,
  systemPrompt, temperature, topP,
  contextMessageLimit: Int = 0,       // 0 = 不限制（配合 3.2.3 的滞回截断）
  streamOutput = true,
  enableMemory = false, useGlobalMemory = false, enableRecentChatsReference = false,
  messageTemplate = "{{ message }}",  // ← 消息模板
  presetMessages: List<UIMessage>,    // ← 预设消息（few-shot）
  quickMessageIds, regexes,
  reasoningLevel = AUTO, maxTokens,
  customHeaders, customBodies,
  mcpServers: Set<Uuid>,
  localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
  enableWebSearch = false,            // 每助手独立开关
  workspaceId, background, backgroundOpacity, useGradientBackground,
  modeInjectionIds, lorebookIds,
  enabledSkills: Set<String>,
  enableTimeReminder, timeReminderIntervalMinutes = 60,
  allowConversationSystemPrompt = false,
)
```

**亮点**：
- `messageTemplate = "{{ message }}"`（:32）：**用户消息模板**，可包一层（如加前后缀、注入变量）。
- `presetMessages: List<UIMessage>`（:33）：预设对话（few-shot），直接进上下文。
- `regexes: List<AssistantRegex>`（:69-77）：**正则替换规则**，带 `affectingScope{USER, ASSISTANT}` 与 `visualOnly`。`replaceRegexes()`（:92-116）在流式输出时对每个 chunk 都跑——所以做了 `SimpleCache` 编译缓存 10 分钟（:81-90，注释：「否则长回复期间会重复编译上万次」）。
- `useGlobalMemory` vs 助手隔离记忆（:30）。
- `enableTimeReminder` / `timeReminderIntervalMinutes`（:51-52）：定时注入时间提醒（解决模型不知道「现在几点」的问题）。

#### 3.4.2 会话级配置快照

`app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt`

```kotlin
ConversationConfig(                 // :21-29
  chatModelId, reasoningLevel, enableWebSearch, builtInSearch,
  mcpServers, workspaceId, enabledSkills
)
```

- **核心思想**（:13-18 注释）：新会话没有这份配置 → 聊天页切换直接改助手；**会话落库时从助手拍一份快照**（`bindConfig`，:126-141）→ 之后的切换只影响当前会话，「别处的改动不会打乱它的上下文和缓存」。
- `withConversation`（:51-57）/ `withAssistantUpdate`（:60-81）/ `withoutConversationFields`（:84-92）三个纯函数处理「会话视角」与「助手视角」的双向投影。
- **失效引用清理**（:102-108）：会话上固定的 mcpServers/modeInjections/lorebooks 若已被删除，读取设置时滤掉。
- `getChatModelOf`（:112-120）：固定模型被删 → 退回助手当前模型；内置搜索跟随模型自身开关。

> 这条对 AgentKit 很实用：AgentKit 的 `ConfigStore` 是全局单例，多会话并行时改配置会互相干扰。

#### 3.4.3 导入导出

- `app/src/main/java/me/rerere/rikkahub/data/sync/importer/CherryStudioProviderImporter.kt` —— **兼容竞品（Cherry Studio）的配置导入**，这是降低用户迁移成本的狠招。
- 助手预设走 `Settings.assistants`（`data/datastore/Settings.kt:57`）整体 JSON 序列化，随备份一起导出（见 3.8）。

---

### 3.5 媒体生成（`:mediagen`）

`mediagen/src/main/java/me/rerere/mediagen/`（30 kt）

- 抽象：`provider/MediaGenerationProvider.kt` + `MediaGenerationProviderSetting.kt` + `MediaGenerationManager.kt` + `MediaGenerationCapabilities.kt`。
- 5 家实现：**阿里云**（`aliyun/AliyunImageGeneration.kt` + `AliyunVideoGeneration.kt`）、**MiniMax**、**OpenAI**、**OpenRouter**（`OpenRouterImageGeneration/VideoGeneration`）、**火山引擎**（`volcengine/...`）。
- 模型：`model/MediaGenerationModel.kt`、`MediaGenerationTask.kt`（异步任务）、`MediaGenerationRequest.kt`、`MediaGenerationInput.kt`。
- 复用件：`providers/ProviderHttp.kt`、`providers/ProviderImages.kt`。
- App 侧：`ui/pages/mediacreation/`、`ui/pages/imggen/`、`service/MediaCreationService.kt`（前台服务 + 通知渠道 `RikkaHubApp.kt:57`）。

> **对 AgentKit**：任务 #64「第8步 生图/视频工坊」已落地（`core/media/providers/`）。RikkaHub 值得学的是**「异步任务 + 轮询取结果」的任务模型**（`MediaGenerationTask`），以及**把「图片生成」也做成 Provider/Setting 体系**（与 LLM Provider 同构）而非特例代码。

### 3.6 语音（`:speech`）

- **TTS**：13 家 —— ElevenLabs / FishAudio / Gemini / Groq / MiMo / MiniMax / OpenAI / Qwen / Step / **SystemTTS（系统 TTS，零依赖兜底）** / Volcengine / XAI。统一抽象 `tts/provider/TTSProvider.kt` + `TTSProviderSetting.kt` + `TTSManager.kt`。
- 播放链路：`tts/controller/TtsController.kt`（编排）+ `TtsSynthesizer.kt` + `AudioPlayer.kt` + **`TextChunker.kt`**（把长文本切块，边合成边播）+ `model/PlaybackState.kt`。
- **ASR**：5 家 —— DashScope / MiMo / OpenAIRealtime / Step / Volcengine。`asr/ASRController.kt` + `ASRVoiceTurn.kt` + `AudioAmplitude.kt`（波形）。
- App 侧有 `ui/pages/chat/VoiceMode.kt` + `VoiceSessionController.kt` + `TTSAutoPlay.kt`。

> **SystemTTS 兜底**是关键设计：不配任何 API Key 也能用（用 Android 原生 TextToSpeech）。AgentKit 若要加语音，这是零依赖红线下的唯一可行路径。

### 3.7 搜索（`:search`）与 RAG

- **20 家搜索服务**（`search/src/main/java/me/rerere/search/`）：Tavily / Exa / 智谱 / 豆包 / Bing(本地) / SearXNG / LinkUp / Brave / Metaso / Ollama / Perplexity / Firecrawl / Jina / 博查 / RikkaHub(自建) / Grok / Tinyfish / Serper / **CustomJs（用 QuickJS 跑用户自定义 JS 抓取）**。
- 接口（`SearchService.kt:21-40`）：`name / parameters() / scrapingParameters() / Description() @Composable / search() / scrape()` —— **搜索服务同时是「给模型的工具」**：`parameters()` 返回 `InputSchema` 直接注册成 LLM tool。
- `SearchService.getService(options)`（:47-67）是 `when` 分派 + unchecked cast，与 ProviderManager 同构。
- 依赖 `me.rerere.ai.util.KeyRoulette`（:9）——搜索服务也支持多 Key 轮询。
- **向量/RAG**：`sqlite-vector = "1.0.0"`（libs.versions.toml:62）+ `:document` 模块的文档解析 + `Provider.generateEmbedding()`（`Provider.kt:36-41`）。**推断存在向量检索，但本次未深挖，标「待验证」**。

### 3.8 MCP / 工具调用

`app/src/main/java/me/rerere/rikkahub/data/ai/mcp/`（7 kt）

- `McpConfig.kt:63` `sealed class McpServerConfig`：`@SerialName("sse") SseTransportServer`（:73-74）与 `@SerialName("streamable_http") StreamableHTTPServer`（:85-86）两种传输。
- `McpManager.kt:42`：用 **Ktor HttpClient(OkHttp) + `install(SSE)`**（:55-65），OAuth 由 `:oauth` 模块的 `OAuthLoopbackCallbackServer` + `CustomTabsOAuthAuthorizationLauncher` 承担（:69-79）。
- 职责注释（:39-40）明确分工：`McpManager` 只协调配置/OAuth/连接注册表/UI 转换；**单服务器连接状态机归 `McpSessionRegistry`，OAuth 协议细节归 `McpOAuthCoordinator`**。
- `getAllAvailableTools(assistant): List<Triple<Uuid, String, McpTool>>`（:106）——按助手过滤可用工具。

**本地工具**：`data/ai/tools/` 下有 `ChatToolFactory.kt`、`ConversationTools.kt`、`MemoryTools.kt`、`MemoryContentGuard.kt`、`local/JavascriptTool.kt`、`local/ScreenTimeTool.kt` 等，与 MCP 工具统一注册。

**生成循环**（`data/ai/GenerationLoop.kt`）：
- `MAX_TOOL_OUTPUT_CHARS = 32 * 1024`（:56）——工具输出超限截断。
- `MAX_PROVIDER_NETWORK_RETRIES = 3`（:61）。
- `generateText`（:78）流程：先检查是否有「待处理的已审批/拒绝工具调用」（:101-108）→ 有则跳过生成直接执行 → 生成后取 `toolCalls`（:166）→ **需要审批的置为 `Pending` 并 break**（:174-209）→ 用户批准后续跑。

### 3.9 数据备份恢复

`app/src/main/java/me/rerere/rikkahub/data/sync/BackupManager.kt`（:36）

- **统一 ZIP 归档格式**（:35 注释：「Shared archive format and restore lifecycle for local, WebDAV and S3 backups」）：
  - `settings.json`（:51-54）—— 全部设置（含 Provider 与 API Key）
  - 可选数据库快照（:58 `DatabaseBackup.ARCHIVE_DATABASE`）
  - 可选附件目录（:73 `$folder/$relative`）
- **恢复走「暂存 + 发布」两阶段**（`stageRestore`，:87-）：解压到 staging 目录 → 校验（重复条目、非法附件名，:111/:158-159）→ `restore.publish(staging)`（:147）。避免半途失败留下半套数据。
- **损坏兜底**：DataStore 有 `ReplaceFileCorruptionHandler`，损坏时先复制一份 `.corrupt-{ts}` 留档再重建为空（`data/datastore/PreferencesStore.kt:60-70`）。
- 同步通道：`webdav/`、`s3/S3Sync.kt`、`uploadS3Config`（与备份 s3Config 互不影响，`Settings.kt:64-65`）。
- 还有 `BackupReminderConfig`（`Settings.kt:78`）——**主动提醒用户备份**。

### 3.10 附赠发现：proot 工作区（与 AgentKit 高度同构）

`workspace/src/main/java/me/rerere/workspace/`（11 kt + `cpp/termux_pty.cpp` + `cpp/workspace.cpp`）

- `ProotShellRunner.kt:13` 实现 `WorkspaceShellRunner.execute(context)`：
  - 用 `libproot_exec.so` + `libproot_loader.so`（:129-130），通过 **`PROOT_LOADER` 环境变量**指定 loader（:54）。
  - 命令构造（:64-114）：`proot --root-id --link2symlink --kill-on-exit -r {linuxDir} -w {cwd} -b {filesDir}:/workspace` + 自定义 bind mounts（:79-84）+ `WorkspaceManager.KERNEL_FS_MOUNTS`（:86-91）。
  - **环境变量净化**（:94-102）：`/usr/bin/env -i HOME=/root PATH=... TERM=xterm-256color LANG=C.UTF-8`。
  - **非交互约定**（:103-106）：`CI=true NO_COLOR=1 PAGER=cat` —— 抑制 CLI 的确认提示/分页器/颜色转义。**这是很实用的坑经验**。
  - **命令通过位置参数传入，避免任何转义**（:110-114）：`sh -l -c 'cd -- "$1" && eval "$2"' rikkahub {cwd} {command}`。
  - `PROOT_NO_SECCOMP=1` 兼容模式开关（:48-53）。
- `RootfsInstaller.kt:19` `install(root, url, onProgress)`：下载 → `extractTar`（自实现 TAR 解析，含 PAX 头解析 :248、`readTarHeader` :217、硬链接/软链接重建 :180/:196）→ staging 目录 → `renameTo(linuxDir)` 原子切换 → `patcher.patch()`。支持 gz/xz（`GZIPInputStream` / `XZInputStream`）。
- `WorkspaceManager.kt:10`：目录布局 `workspaceDir/{FILES_DIR, LINUX_DIR, TEMP_DIR}`（:33-42），`resolveRootfsPath`（:124）、`exportRootfsFile`（:154）。
- App 侧有 `Screen.WorkspaceDetail` / `Screen.WorkspaceTerminal`（`RouteActivity.kt:773-777`）。

> **对 AgentKit 的直接价值**：AgentKit 已有 `core/container`（proot）与 `terminal`。可立即借鉴两条：① **非交互 env 约定**（`CI=true NO_COLOR=1 PAGER=cat`）避免命令卡在确认提示；② **命令用位置参数 + `eval "$2"` 传入**，彻底规避 shell 转义注入。这两条是纯经验、零依赖。

---

## ④「它怎么做 → 我们怎么适配」对照表

> 约束：AgentKit 单模块 `:app`，Kotlin 1.9.25 + Compose BOM 2024.09.00 + Material3，minSdk 26，**零新增外部依赖**（存量：okhttp 4.12.0 / kotlinx-serialization-json 1.6.3 / rhino 1.7.14）。
> **全部为净室重写方案**，不搬运任何 AGPL 代码。

| # | 借鉴点 | RikkaHub 怎么做（位置） | AgentKit 怎么适配（落地方案） | 依赖 | 优先级 |
|---|---|---|---|---|---|
| A1 | **Provider 无状态化** | `Provider` 接口每方法都传 `ProviderSetting`（`ai/.../Provider.kt:17-56`）；`ProviderManager` 只注册 3 个单例（:16-21） | 把 `LLMClient` 的「构造时持有 config」改为「调用时传 config」。保留 `LLMClient` 接口名不变，新增 `fun clientFor(providerId: String): LLMClient` 工厂 + 注册表 `Map<String, LLMClient>`。三套云端 + local-llama 各注册一个无状态实例。**收益**：支持「同一供应商多套 Key 并存」，为 Key 轮询铺路 | 0 | P1 |
| A2 | **消息分支（节点+游标）** | `MessageNode(messages, selectIndex)`（`Conversation.kt:113-135`）；重新生成/编辑都是**追加兄弟 + 移动游标**（`ChatService.kt:1357-1366`） | `ConversationStore` 的 `MsgRecord` 外包一层 `MsgNode { id, records: List<MsgRecord>, selectIndex }`。线性渲染取 `nodes.map{ it.records[it.selectIndex] }`。UI 加「◀ 2/3 ▶」切换器。**这是本次最高性价比改造**：持久化格式只是多一层，JSON 直接可存；重新生成不再丢历史 | 0 | **P0** |
| A3 | **模型能力注册表** | `ModelRegistry` + token 匹配 DSL（`registry/ModelRegistry.kt:10`、`ModelDsl.kt:161-229`） | 净室写一个 ~150 行 `ModelAbilityInfer`：① `tokenize(id)` 切成字母块/数字块/符号块；② 规则表 `listOf(Rule(tokens=listOf("gpt","5"), notTokens=listOf("gpt","5","chat"), abilities=setOf(TOOL,REASONING), input=setOf(TEXT,IMAGE)))`；③ 取 score 最高者。用于**模型卡片打标签**（工具/推理/视觉）+ 决策是否启用 temperature。**纯 Kotlin，零依赖，约 0.5 天** | 0 | **P0** |
| A4 | **ModelListSheet UX** | 收藏区+拖拽排序、搜索框、Provider 折叠持久化、打开即定位当前项、能力标签（`ModelList.kt:367-657, 849-935`） | 分两步：① **P0 先做无拖拽版**——搜索框（`OutlinedTextField` + `RoundedCornerShape(50)`）+ Provider 折叠（SharedPrefs 存逗号分隔 id）+ `LazyListState(initialFirstVisibleItemIndex=定位)` + 收藏区（`favoriteModels` 存 SharedPrefs）+ 三个能力 Tag。② 拖拽排序 P2 降级为「长按→上移/下移」菜单项。**注意**：RikkaHub 用 `sh.calvin.reorderable`，我们不能引入 | 0 | **P0**（①）／P2（②） |
| A5 | **模型快照 modelSnapshot** | `ModelSnapshot(modelId, displayName)` 存进消息（`Message.kt:110-114`），落库时补齐（`Conversation.kt:150-163`） | AgentKit 消息只存模型名字符串。改造：消息增 `modelDisplayName` 字段（**已经等于快照，不用改结构**），只需确保「模型被删/改名后气泡仍显示当时名字」。**存量已满足，仅需确认 UI 读的是消息上的名字而非当前配置**——零成本 | 0 | P1 |
| A6 | **上下文滞回截断** | `limitMessageCount` 阶梯式截断点 + `alignContextStart` 保 tool 配对（`Message.kt:161-249`） | AgentKit 的 `AgentExecutor` 目前上下文管理策略待确认（**待验证**）。若为「滑动窗口」，改为阶梯式：越过阈值才截断、一次前进 N 条，把前缀稳定下来（对云端 prompt cache 省钱，对本地 llama.cpp 省 prefill 时间——**本地模型收益更大**）。tool 配对保护逻辑照搬思路（纯函数，可单测） | 0 | **P0** |
| A7 | **会话级配置快照** | `ConversationConfig` + `bindConfig`（`ConversationConfig.kt:21-29, 126-141`） | AgentKit `ConfigStore` 是全局单例。改造：会话首条消息落库时拍一份 `ConversationConfig(modelId, temperature, tools)` 存进 `ConversationSummary`；之后聊天页改配置只改当前会话。防「A 会话改模型导致 B 会话上下文错乱」 | 0 | P1 |
| A8 | **Key 多把 + LRU 轮询** | `KeyRoulette`（`ai/util/KeyRoulette.kt`，按 `[\s,]+` 切分，LRU 持久化 json） | AgentKit `ConfigSanitizer.cleanApiKey` 已剔除不可见字符。增量：允许 Key 框填多把（逗号/换行分隔），`ModelListFetcher` 与各 Client 取 key 时走一个 `KeyRoulette.next(keys, providerId)`（~40 行，SharedPrefs 存 lastUsed）。**对免费额度党是高频需求** | 0 | P2 |
| A9 | **记忆改为 Markdown 文件树** | `MemoryFile`（`MemoryFile.kt:11-76`）+ 6 个 memory 工具（`MemoryTools.kt:24-32`）+ 定时 Consolidation（`Scheduler.kt:95` idle 2min） | AgentKit `MemoryStore` 是扁平 JSON。分两步：① **P1**：把 `MemoryItem` 落盘从一条条 JSON 改为「按 topic 分组的 .md 文件 + frontmatter(title/description/aliases)」，并用 `SHA-256(content).take(12)` 做 version 乐观锁；② **P2**：加空闲 2 分钟自动整理（WorkManager 或 AppScope 协程 + `delay`），用 fast model 把新对话轮次归并进记忆文件。**注意 AgentKit 无 WorkManager，用 `AppScope` + `ProcessLifecycleOwner` 即可** | 0 | P1（①）／P2（②） |
| A10 | **助手预设（Assistant）** | 37 字段（`Assistant.kt:16-53`），含 `messageTemplate`、`presetMessages`、`regexes`、`enableTimeReminder` | AgentKit 有 `skill` 包但无「助手/角色预设」。新增 `AgentPreset(id, name, systemPrompt, modelId, temperature, enabledTools, presetMessages)`，存 `ConfigStore`；聊天页顶部可切换 Preset。**优先做 4 个字段最小集**：name/systemPrompt/modelId/temperature。`messageTemplate` 与 `regexes` 留 P2 | 0 | P1 |
| A11 | **Provider 类型转换（convertTo）** | `ProviderSetting.convertTo(KClass)` + BaseUrl 路径段智能改写（`ProviderConfigure.kt:90, 109, 175-201`） | AgentKit 模型配置页已有供应商 tab（任务 #54）。增量：切换供应商时**保留 name/model，改写 baseUrl 的路径段**（用 `HttpUrl.parse()` 的 okhttp，已有依赖）。例如 OpenAI `https://x.com/v1` → Claude 时改写为 `https://x.com/v1`（Claude 默认带 /v1，`ClaudeClient` 已有兜底逻辑，需对齐） | 0 | P2 |
| A12 | **余额查询（BalanceOption）** | `BalanceOption(enabled, apiPath, resultPath="data.total_usage")`（`ProviderSetting.kt:10-14`） | 净室写一个极小的 JSON 路径取值器（`resultPath.split('.')` 逐级下钻，~20 行）。模型配置页显示余额。**纯附加体验点，可延后** | 0 | P2 |
| A13 | **非交互 shell env + 位置参数传命令** | `CI=true NO_COLOR=1 PAGER=cat`；`sh -l -c 'cd -- "$1" && eval "$2"' name "$cwd" "$cmd"`（`ProotShellRunner.kt:94-114`） | **直接采纳，零依赖零风险**。AgentKit 的 proot 命令执行处加这三条 env + 改用位置参数传命令。收益：避免 `apt install` / `git` 等卡在确认提示或分页器——这是「做安装包/推 GitHub」场景下最常见的卡死原因 | 0 | **P0** |
| A14 | **自定义 Header / Body** | `CustomHeader(name,value)` + `CustomBody(key, value: JsonElement)`（`Provider.kt:119-128`） | AgentKit 三家 Client 目前（待验证）无自定义头支持。增量：ModelConfig 加 `customHeaders: List<Pair<String,String>>`（`Map<String,String>` 序列化更简单），请求构造时 `addHeader`。**对国内中转站/自建网关是刚需** | 0 | P1 |
| A15 | **备份 ZIP + 暂存发布两阶段** | `BackupManager`（`sync/BackupManager.kt:36-147`）+ DataStore 损坏留档（`PreferencesStore.kt:60-70`） | AgentKit 有 `ConversationStore`/`MemoryStore` 的 tmp+rename 原子写（已在做）。增量：设置页加「导出/导入 ZIP」，内容 = `settings.json` + `conversations.json` + `memory.json`。**解压先到 staging，校验通过再整体替换**（避免半套数据）。用 `java.util.zip`（JDK 自带，不新增依赖） | 0 | P2 |
| A16 | **Code Block 高亮** | `:highlight` 自研引擎（51 kt，含 hljs 回归测试） | **不建议自研**（51 kt 太大）。AgentKit「能写代码」场景下，P1 用「等宽字体 + 代码块背景 + 复制按钮」即可；P2 再考虑移植一个**精简版**（先支持 kotlin/java/python/js/json/bash 6 种语言的关键字正则高亮，~200 行） | 0 | P2 |
| A17 | **TTS SystemTTS 兜底** | `speech/tts/provider/providers/SystemTTSProvider.kt` | 若做语音播报，**唯一零依赖路径**是 Android 原生 `android.speech.tts.TextToSpeech`。可直接用，不引入 `:speech` 模块 | 0 | P2 |
| A18 | **搜索服务即工具** | `SearchService.parameters(): InputSchema` 直接注册为 LLM tool（`search/SearchService.kt:21-40`） | AgentKit 若加联网搜索：把「搜索」实现成一个 `Tool`（`ToolRegistry` 已存在），而不是特例逻辑。搜索服务配置存 ConfigStore。**但 AgentKit 无搜索需求优先级，列 P2 待定** | 0 | P2 |

---

## ⑤ 优先行动清单

### P0（Must have — 本轮就做，总计约 4.5~6 人日）

| 编号 | 事项 | 对应 | 工作量 | 产出 |
|---|---|---|---|---|
| P0-1 | **消息分支（MsgNode + selectIndex）** | A2 | 1.5~2d | `ConversationStore` 改节点结构；重新生成追加兄弟而非覆盖；UI 加「◀ n/m ▶」切换器 |
| P0-2 | **模型能力推断 `ModelAbilityInfer`** | A3 | 0.5d | 纯 Kotlin ~150 行；模型卡片打「工具/推理/视觉」标签；驱动 temperature 决策 |
| P0-3 | **ModelListSheet 无拖拽版** | A4① | 1~1.5d | 搜索框 + Provider 折叠持久化 + 打开即定位 + 收藏区 + 能力 Tag |
| P0-4 | **上下文阶梯式截断 + tool 配对保护** | A6 | 1d | 纯函数 + 单测；本地模型 prefill 与云端 cache 双收益 |
| P0-5 | **proot 非交互 env + 位置参数传命令** | A13 | 0.5d | 3 条 env + 命令传参改造；解决「做安装包/推 GitHub」卡死 |

### P1（Should have — 下一轮，约 5~7 人日）

| 编号 | 事项 | 对应 | 工作量 |
|---|---|---|---|
| P1-1 | Provider 无状态化重构（多套 Key 并存） | A1 | 1.5d |
| P1-2 | 会话级配置快照 `ConversationConfig` | A7 | 1d |
| P1-3 | 记忆改为 Markdown 文件树 + version 乐观锁 | A9① | 1.5d |
| P1-4 | 助手预设 `AgentPreset`（4 字段最小集） | A10 | 1d |
| P1-5 | 自定义 Header/Body 支持 | A14 | 0.5~1d |
| P1-6 | modelSnapshot 确认与气泡显示对齐 | A5 | 0.25d |

### P2（Nice to have — 有余力再做）

| 编号 | 事项 | 对应 | 工作量 |
|---|---|---|---|
| P2-1 | 模型收藏拖拽排序（降级：长按上移/下移） | A4② | 0.5d |
| P2-2 | Key 多把 + LRU 轮询 | A8 | 0.5d |
| P2-3 | 记忆空闲自动 Consolidation | A9② | 1.5~2d |
| P2-4 | 备份 ZIP 导出/导入（暂存发布两阶段） | A15 | 1d |
| P2-5 | Provider 类型转换 + BaseUrl 路径段改写 | A11 | 0.5d |
| P2-6 | 余额查询 BalanceOption | A12 | 0.5d |
| P2-7 | 精简版代码高亮（6 语言关键字正则） | A16 | 1~2d |
| P2-8 | SystemTTS 语音播报 | A17 | 0.5d |
| P2-9 | 联网搜索做成 Tool | A18 | 待定 |
| P2-10 | `messageTemplate` / `regexes` / `presetMessages` | A10 扩展 | 1d |

### 不做（明确排除）

- ❌ 引入 Room / Koin / Navigation3 / Ktor / QuickJS / reorderable / haze —— 违反零依赖红线。
- ❌ 移植 `:highlight` 全量引擎（51 kt）——投入产出比过低。
- ❌ 移植 `:mediagen` 异步任务模型——AgentKit 第 8 步已自研，除非遇到异步轮询痛点再回看。
- ❌ 升级 Kotlin 1.9.25 → 2.4.20 / Compose BOM 2024.09 → 2026.09 —— 风险大于收益，除非某 P0 项强依赖新 API。

---

## ⑥ 代码位置索引

### 6.1 Provider / Model 配置体系（§3.1）

| 主题 | 路径:行号 |
|---|---|
| ProviderSetting sealed class | `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt:26` |
| ├ OpenAI 子类型 | 同上 `:54-118` |
| ├ Google 子类型（含 Vertex AI） | 同上 `:120-187` |
| ├ Claude 子类型 | 同上 `:189-251` |
| ├ BalanceOption | 同上 `:10-14` |
| └ Types 枚举 | 同上 `:253-260` |
| Model 数据类 | `ai/.../provider/Model.kt:8-22` |
| ModelType / Modality / ModelAbility / BuiltInTools | 同上 `:25-29 / :32-35 / :38-41 / :45-59` |
| Provider 接口（无状态） | `ai/.../provider/Provider.kt:17-56` |
| TextGenerationParams / ImageGenerationParams | 同上 `:68-78 / :81-89` |
| CustomHeader / CustomBody | 同上 `:119-122 / :125-128` |
| ProviderManager 注册表 | `ai/.../provider/ProviderManager.kt:12-57`（`getProviderByType` :49-56） |
| ModelRegistry（795 行规则表） | `ai/.../registry/ModelRegistry.kt:10`（GPT_5 `:79-85`、OPENAI_O_MODELS `:24-28`） |
| ModelDsl（匹配引擎） | `ai/.../registry/ModelDsl.kt:37 defineModel` / `:149 ExactIdMatcher` / `:161 TokenSequenceMatcher` / `:177 NotTokenSequenceMatcher` / `:197 tokenize` |
| Provider 类型转换 | `app/.../ui/pages/setting/components/ProviderConfigure.kt:90 convertTo` / `:175-201 BaseUrl 路径段改写` |
| 模型导入 UI（listModels 调用） | `app/.../ui/pages/setting/SettingProviderDetailPage.kt:457-530` |
| OpenAI listModels | `ai/.../providers/openai/OpenAIProvider.kt:61-89` |
| Claude listModels | `ai/.../providers/claude/ClaudeProvider.kt:247-276` |
| Google listModels | `ai/.../providers/google/GoogleProvider.kt:130-168` |
| Key 轮询 | `ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt`（LRU `:57-92`） |
| ModelListSheet（935 行） | `app/.../ui/components/ai/ModelList.kt:305-352` |
| ├ ModelListState | 同上 `:101-143` |
| ├ 收藏区 | 同上 `:367-372` / `:554-621` |
| ├ 搜索过滤 | 同上 `:374` / `:396-402` / `:506-533` |
| ├ Provider 折叠持久化 | 同上 `:377-388` |
| ├ 初始滚动定位 | 同上 `:411-458` |
| ├ 拖拽排序 | 同上 `:459-485` |
| └ 能力标签（Type/Modality/Ability） | 同上 `:853 / :870 / :907`（`ModelItem` `:764`） |
| 默认 Provider 预设（含 AiHubMix/APIMart 等） | `app/.../data/datastore/DefaultProviders.kt:16-296` |

### 6.2 聊天 UI（§3.2）

| 主题 | 路径:行号 |
|---|---|
| UIMessage | `ai/src/main/java/me/rerere/ai/ui/Message.kt:18-36` |
| ModelSnapshot | 同上 `:110-114`；`snapshotModel()` `:83-87` |
| 上下文滞回截断 | 同上 `:161-249`（`limitContext` :171、`limitMessageCount` :188、`alignContextStart` :209） |
| finishReasoning / finishPendingTools | 同上 `:289-307 / :309-328` |
| UIMessagePart（sealed） | `ai/.../ui/UIMessagePart.kt:73`（Reasoning `:83` 起） |
| ToolApprovalState | 同上 `:12-29` |
| ServerToolStatus / ReasoningType | 同上 `:41-53 / :56-64` |
| Conversation（messageNodes） | `app/.../data/model/Conversation.kt:19-41` |
| currentMessages | 同上 `:51-54` |
| **MessageNode（分支核心）** | 同上 `:113-135` |
| fillModelSnapshots | 同上 `:150-163` |
| regenerateAtMessage | `app/.../service/ChatService.kt:511-549` |
| **编辑消息（追加兄弟）** | 同上 `editMessage` `:1327` 起（关键 `:1357-1366`） |
| forkConversationAtMessage | 同上 `:1374-1408` |
| selectMessageNode | 同上 `:1412-1440` |
| 删除消息 | 同上 `:1462-1491` |
| handleMessageComplete | 同上 `:639-...` |
| 分支切换 UI | `app/.../ui/components/message/ChatMessageBranch.kt:32-84` |
| 思考过程 UI | `app/.../ui/components/message/ChatMessageReasoning.kt` |
| Markdown / Mermaid / LaTeX | `app/.../ui/components/richtext/Markdown.kt`、`Mermaid.kt`、`LatexText.kt`、`HighlightCodeBlock.kt` |
| 高亮引擎 | `highlight/src/main/java/me/rerere/highlight/core/HighlightEngine.kt`、`Mode.kt`、`ModeCompiler.kt`、`languages/` |
| 生成循环（工具审批） | `app/.../data/ai/GenerationLoop.kt:73-...`（`MAX_TOOL_OUTPUT_CHARS` :56、`MAX_PROVIDER_NETWORK_RETRIES` :61、审批置 Pending `:174-209`） |

### 6.3 记忆（§3.3）

| 主题 | 路径:行号 |
|---|---|
| MemoryFile（Markdown + frontmatter） | `app/.../data/model/MemoryFile.kt:11-76`（version `:16`、路径规范 `:66-74`、目录顺序 `:88-89`） |
| 6 个记忆工具 | `app/.../data/ai/tools/MemoryTools.kt:24-32`（`buildMemoryTools` :42、读容错 `:64-69`） |
| Consolidation 调度器 | `app/.../data/ai/MemoryConsolidationScheduler.kt:55-81 接口` / `:95 idleDelay=2.minutes` |
| Consolidation 执行 | `app/.../data/ai/MemoryConsolidator.kt`（389 行） |
| 记忆 Prompt | `app/.../data/ai/prompts/MemoryPrompts.kt`（244 行） |
| MemoryRepository | `app/.../data/repository/MemoryRepository.kt`（174 行） |
| DB 实体 | `app/.../data/db/entity/MemoryFileEntity.kt`、`MemoryConsolidationEntity.kt` |

### 6.4 助手预设（§3.4）

| 主题 | 路径:行号 |
|---|---|
| Assistant（37 字段） | `app/.../data/model/Assistant.kt:16-53` |
| AssistantRegex + 编译缓存 | 同上 `:69-77` / `:81-90` / `replaceRegexes` `:92-116` |
| ConversationConfig | `app/.../data/model/ConversationConfig.kt:21-29` |
| bindConfig（拍快照） | 同上 `:126-141` |
| 会话/助手双向投影 | 同上 `:51-92` |
| getChatModelOf | 同上 `:112-120` |
| CherryStudio 配置导入 | `app/.../data/sync/importer/CherryStudioProviderImporter.kt` |

### 6.5 其他模块（§3.5-3.10）

| 主题 | 路径:行号 |
|---|---|
| mediagen 抽象 | `mediagen/src/main/java/me/rerere/mediagen/provider/MediaGenerationProvider.kt`、`MediaGenerationManager.kt` |
| mediagen 5 家实现 | 同上 `providers/{aliyun,minimax,openai,openrouter,volcengine}/` |
| TTS 抽象 + 13 家 | `speech/src/main/java/me/rerere/tts/provider/TTSProvider.kt`、`providers/`（SystemTTS 兜底） |
| TTS 播放链路 | 同上 `tts/controller/{TtsController,TtsSynthesizer,AudioPlayer,TextChunker}.kt` |
| ASR 5 家 | 同上 `asr/providers/{DashScope,MiMo,OpenAIRealtime,Step,Volcengine}ASRController.kt` |
| 搜索接口（20 家） | `search/src/main/java/me/rerere/search/SearchService.kt:21-40`（分派 `:47-67`） |
| MCP 配置（SSE / StreamableHTTP） | `app/.../data/ai/mcp/McpConfig.kt:63-86` |
| McpManager | `app/.../data/ai/mcp/McpManager.kt:42-140` |
| 备份 ZIP + 暂存发布 | `app/.../data/sync/BackupManager.kt:36-147`（`createBackup` :44、`stageRestore` :87） |
| DataStore 损坏兜底 | `app/.../data/datastore/PreferencesStore.kt:60-70` |
| Settings 全量字段 | `app/.../data/datastore/Settings.kt:33-83` |
| 导航 Screen（~50 路由） | `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt:626-780`（NavDisplay `:325`） |
| **proot 命令构造** | `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt:64-114`（env 净化 `:94-102`、非交互 `:103-106`、位置参数 `:110-114`） |
| rootfs 安装 | 同上 `RootfsInstaller.kt:19-47`（自实现 TAR `:217`、PAX `:248`） |
| WorkspaceManager | 同上 `WorkspaceManager.kt:10-160` |
| 技术栈版本 | `gradle/libs.versions.toml:1-63` |

---

## 附：待验证清单

1. **向量/RAG 实现**：`sqlite-vector = "1.0.0"`（libs.versions.toml:62）+ `:document` 模块 + `Provider.generateEmbedding()` 推断有向量检索，但本次未读到检索入口，**标待验证**。
2. **AgentKit 现有上下文管理策略**：`AgentExecutor` 是否为滑动窗口未确认，P0-4 落地前需先读码确认。
3. **AgentKit 三家 Client 是否支持自定义 Header**：`OpenAIClient`/`ClaudeClient`/`GeminiClient` 未逐行确认，P1-5 落地前需确认。
4. **`ModelList` 搜索只匹配 `displayName` 不含 `modelId`**（`ModelList.kt:399`）——RikkaHub 确实如此，这是它的取舍；AgentKit 落地时建议**两者都匹配**（体验更好）。
5. **`:web` 模块（Ktor 内嵌服务）** 与 `screen mirror`/`webserver` 能力未深挖。
6. **`trace-cli`、`locale-tui`、`web-ui`、`document`** 四个目录本次未读。
