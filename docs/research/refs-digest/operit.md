# Operit 吃透报告 —— 参照仓库研究第 3 路

> 研究对象：`/root/.codebuddy/artifact/repos/operit`（main 分支，LGPL-3.0，versionName 1.12.2 / versionCode 51）
> 研究人：产品经理 许清楚（Xu） · 用途：为 AgentKit 改造提供「设计原理 → 我们怎么适配」
> 所有结论均来自实际读码，标注 `路径:行号`；推测或未验证项标注「待验证」。
>
> ⚖️ **合规声明**：Operit 为 **LGPL-3.0**。本报告只提炼**设计原理与实现思路**（架构分层、协议形状、状态机、参数语义），**不搬运任何源码片段**。凡涉及落地，一律**净室重写**；尤以虚拟屏（Shower）与终端（OperitTerminalCore）两块为红线，只读思路、不触碰实现。

---

## ① TL;DR（给主理人的 10 句话）

1. **Operit 是「Kotlin 单 App + 9 个原生 Gradle 模块」的重型单体**，不是 Coomi 那种「Rust 引擎 + Vue 前端」。它的 `app/` 有 **1159 个 kt 文件**（`app/src/main/java` 实测），业务逻辑全在 Kotlin/Compose —— 这跟 AgentKit 的**技术栈几乎同构**，是四个参照仓库里**最可直接借鉴**的一个。
2. **最核心的设计决策：内部统一用 XML 表达工具调用与工具结果，外部按 Provider 能力做协议转换**。`<tool name="x"><param name="y">…</param></tool>` 是 canonical form；`enableToolCall` 只是「XML ↔ OpenAI/Claude/Gemini 原生 tool_calls JSON」的**传输层开关**，执行层永远不变（`docs/doc-src/feature-protocol/toolcall_xml_conversion.md:5-7`）。这是 Operit 能同时支持 39 家云端 Provider + 2 个本地引擎的**根因**。
3. **Agent 主循环是「互相递归」而非 `while` 循环**：`sendMessage` → `processStreamCompletion` → `handleToolInvocation` → `processToolResults` → 回到 `processStreamCompletion`（`EnhancedAIService.kt:2458`）。**没有硬编码最大轮数**——靠 token 阈值（`ModelConfigDefaults.DEFAULT_SUMMARY_TOKEN_THRESHOLD = 0.70f`，`ModelConfigData.kt:65`）与 `isConversationActive` 软停。AgentKit 的 `repeat(maxSteps=16)`（`AgentLoop.kt:68`）是**另一种更安全的做法，建议保留**。
4. **结果有序性是它踩坑最深的地方**：并行执行会乱序完成，早期 bug 导致「同名工具结果认错主人」。解法是 `OrderedToolResults`——**只发布已完成前缀**（`OrderedToolResults.kt:15-45`），且**同一份 XML 只格式化一次**，同时供给「保存的响应流」和「下一轮模型历史」两条路径（`ToolExecutionBatch`，`OrderedToolResults.kt:8`）。这个「一份真源，两处复用」的原则对我们**价值极高**。
5. **截断/畸形 XML 有专门的修复器**：`detectAndRepairTruncatedToolRound`（`EnhancedAIService.kt:1443`）+ `enhanceToolDetection`（`:1335`）+ `buildTruncatedToolRepairSuffix`（`:1502`）—— 模型输出半个 `<tool>` 时，先补齐标签，然后把**本轮所有工具作废并回灌一条警告**让模型重来。这是「**错误恢复**」的实操答案。
6. **设备自动化是「双通道」**：① Shower 虚拟屏（`showerclient/` 模块 + `app/.../core/tools/agent/`）—— 用 `app_process` 起一个独立 Java 进程当 server，通过 **Binder 广播**把 `IShowerService` 交回 App，再建虚拟 Display、发触控/按键、取截图；② 无障碍 / Shizuku / root 通道（`core/tools/defaultTool/{accessbility,admin,root,debugger}/`）。**AgentKit 已有 ②，缺 ① 的「独立进程虚拟屏」与「UI 子代理循环」**。
7. **`PhoneAgent` 是一个「有界步数 + 视觉模型 + 极简动作协议」的 UI 子代理**（`core/tools/agent/PhoneAgent.kt`）：`maxSteps=20`（`:43`），每步「截图 → 喂视觉模型 → 解析 `do(action="Tap", element="[x,y]")` 或 `finish(message="…")` → 执行」（`:602`、`:691`、`:960`）。**它不用 JSON 工具协议，而是「类函数调用文本」**——理由是视觉小模型生成结构化 JSON 能力弱。这是「**按模型能力选协议**」的绝佳范例。
8. **本地模型走「工具暴露降级」**：`ToolExposureMode.resolve()` 对 `LMSTUDIO/OLLAMA/OPENAI_LOCAL/MNN/LLAMA_CPP` 返回 `CLI`，此时**只暴露 2 个工具**（`search` + `proxy`）而非 180 个（`climode/CliToolModeSupport.kt:21-31`、`:68-69`）。进一步，llama.cpp 侧还接了 **GBNF 语法约束**（`llama_sampler_init_grammar_lazy_patterns`，`llm/llama/src/main/cpp/llama_jni_stub.cpp:94`）在采样层把输出锁成 `tool_calls` 结构。**这是我们 local-llama 路径最该抄的一课：小模型不能喂 100+ 工具 schema。**
9. **JS 脚本引擎已切到 QuickJS**（`quickjs/` 模块，`JsEngine.kt:189` 用 `OperitQuickJsEngine()`，跑在专属单线程；`JsToolManager.kt:25` 建 **4 个引擎的池**）。Agent 写的脚本通过一个全局 `Tools.*` 对象回调宿主工具（`JsTools.kt:4`），并注入 `Java`/`Kotlin` 反射桥、CryptoJS/Jimp/OkHttp 等库（`JsLibraries.kt:12-60`）。**AgentKit 已有 rhino 1.7.14（零新增依赖红线内），但「工具暴露给 JS」这一层还没做。**
10. **一句话结论**：向 Operit 学 **① XML 内部协议 + 结果有序性；② 有界 UI 子代理循环 + 极简动作协议；③ 本地模型工具暴露降级；④ Shell 权限分级工厂；⑤ 工具 Hook 生命周期 + 三级权限模型**。不学：它的「无界递归主循环」（我们已有更安全的 maxSteps）、单体 1159 文件的组织方式、39 家 Provider 的铺量。

---

## ② 架构分层地图

### 2.1 Gradle 模块与依赖（`settings.gradle.kts:22-40`）

```
Operit/
├── app/                    ★ 主模块（1159 kt；namespace com.ai.assistance.operit）
│   ├── api/chat/           ★★ Agent 引擎核心
│   │   ├── EnhancedAIService.kt      (3221 行) 主循环 / 流完成 / 错误处理
│   │   ├── llmprovider/              (19054 行) 39 家 Provider + 本地 MNN/llama
│   │   │   ├── OpenAIProvider.kt     (3509 行)
│   │   │   ├── ClaudeProvider.kt  GeminiProvider.kt
│   │   │   ├── MNNProvider.kt  LlamaProvider.kt
│   │   │   └── StructuredToolCallBridge.kt (860 行) XML↔JSON 协议桥
│   │   └── enhance/
│   │       ├── ToolExecutionManager.kt   (793 行) ★ 工具批量执行器
│   │       ├── OrderedToolResults.kt     (47 行)  ★ 结果有序发布
│   │       ├── ConversationMarkupManager.kt       ★ XML 标记/截断
│   │       └── ConversationService.kt             ★ 历史归一化
│   ├── core/
│   │   ├── tools/          ★★ 工具系统
│   │   │   ├── AIToolHandler.kt   (491 行) 注册中心 + 执行入口 + Hook 分发
│   │   │   ├── ToolRegistration.kt(2745 行) 182 处 registerTool
│   │   │   ├── AIToolHook.kt              生命周期钩子接口
│   │   │   ├── ToolExecutionLimits.kt     结果大小上限
│   │   │   ├── defaultTool/{standard,admin,root,debugger,accessbility}/  按权限分实现
│   │   │   ├── system/shell/       ★ ShellExecutor 五级工厂
│   │   │   ├── system/action/      ★ 无障碍动作（tap/swipe/input）
│   │   │   ├── system/shower/      ★ 虚拟屏 ShellRunner 注入
│   │   │   ├── agent/              ★★ PhoneAgent（UI 子代理）
│   │   │   ├── javascript/         ★ QuickJS 引擎 + Tools.* 桥（14995 行）
│   │   │   ├── packTool/           ★ ToolPkg 工具包（PackageManager 167KB）
│   │   │   ├── mcp/  skill/  climode/  calculator/  condition/
│   │   ├── config/         ★ SystemToolPrompts.kt (工具 schema 单一事实源)
│   │   ├── avatar/         ★ 3D/2D 形象（6 种渲染后端）
│   │   ├── chat/{hooks,plugins}/   ★ Prompt / Summary / Runtime Hook 注册表
│   │   ├── workflow/  subpack/(APK 逆向)  performance/  application/
│   ├── data/      ObjectBox + Room 持久化、preferences、repository、mnn 模型下载
│   ├── services/  AIForegroundService / FloatingChatService / 悬浮窗
│   ├── integrations/  http(外部 HTTP 聊天服务) / a2a / intent / tasker / externalchat
│   ├── ui/        528 kt，features/ 下 20+ 功能域，Compose
│   └── util/      ★ ChatMarkupRegex（XML 协议正则）、stream/（KMP 流式解析）
├── llm/
│   ├── mnn/       ★ MNN 本地推理（JNI + CMake 拉上游）
│   └── llama/     ★ llama.cpp 本地推理（JNI + CMake 拉上游，commit 固定）
├── quickjs/       ★ QuickJS JNI 模块（已接入 app）
├── showerclient/  ★ Shower 虚拟屏客户端库（1150 行）
├── terminal/      ⚠️ Git submodule，**本仓库内为空目录**（未 checkout）
├── avator/{mmd,fbx,dragonbones}/   3D 模型原生运行时
├── web-chat/      React + Vite 前端（构建产物进 assets/web-chat）
├── tools/         开发/调试/CI 辅助（ripgrep、MCP Bridge、Compose DSL 生成器…）
└── docs/          ★ doc-src/{architecture,dev-core,feature-protocol,package-dev}
```

> ⚠️ **重要发现**：`terminal/` 是 `.gitmodules` 声明的 submodule（`https://github.com/AAswordman/OperitTerminalCore.git`），**本地为空目录**。因此「终端内部实现」无法从本仓库读到；只能读到 App 侧的封装（`core/tools/system/Terminal.kt`）。凡涉及终端内部实现的一律标「待验证」。

### 2.2 技术栈（实测）

| 层 | 技术 | 证据 |
|---|---|---|
| 构建 | AGP **8.13.2**、Kotlin **2.2.21**、kapt/parcelize/serialization 插件 | `gradle/libs.versions.toml:2-3` |
| UI | **Compose BOM 2026.02.01** + Material3 + navigation-compose 2.7.7 + Coil | `libs.versions.toml:11-12,53`、`app/build.gradle.kts:721-733` |
| Android | namespace `com.ai.assistance.operit`，**minSdk 26 / targetSdk 34 / compileSdk 36**，versionCode 51 | `app/build.gradle.kts:360-400` |
| ABI | **仅 `arm64-v8a`** | `app/build.gradle.kts:410` |
| 持久化 | **ObjectBox 5.3.0**（根 build.gradle）+ Room | `build.gradle.kts:3`、`app/build.gradle.kts:715` |
| 权限通道 | **Shizuku API + provider**、libsu（root）、无障碍 | `app/build.gradle.kts:637-639,736-737` |
| 本地推理 | **MNN**（JNI，上游 CMake 拉取）+ **llama.cpp**（JNI，commit `720d7fa4…`） | `llm/*/CMakeLists.txt` |
| JS 引擎 | **QuickJS**（JNI，上游 CMake 拉取），Kotlin 封装 4 文件 | `quickjs/`、`JsEngine.kt:189` |
| 3D | Filament（glTF）、MMD（Bullet3）、FBX（ufbx）、DragonBones（自研 C++） | `app/build.gradle.kts:580-592` |
| 其他 | ffmpeg-kit(aar)、ExoPlayer、OCR(ML Kit)、TFLite/ONNX/MediaPipe、hnswlib(向量)、jlatexmath、zip4j/commons-compress | `app/build.gradle.kts:595-717` |

### 2.3 Agent 执行主循环结构（核心）

```
EnhancedAIService.sendMessage(options)                          :903
  ├─ prepareConversationHistory(...) → PromptTurn 列表（system/user/tool_result/assistant）
  ├─ applyPromptFinalizeHooks(stage="before_finalize_prompt")   :1061
  ├─ serviceForFunction.sendMessage(history, tools, stream)     :1121  → 流式收集到 streamBuffer
  └─ finally → processStreamCompletion(...)                     :1287
        │
        ├─ enhanceToolDetection(content)                        :1337  ① 流式 XML 标准化
        ├─ detectAndRepairTruncatedToolRound(content)           :1438  ② 截断修复
        │     └─ 命中截断 → 追加缺失后缀 → 本轮工具全作废 → 注入 warning
        │                                → handleToolInvocation(emptyList, overrideMsg)
        ├─ ToolExecutionManager.extractToolInvocations(final)   :1858  ③ 解析 <tool><param>
        ├─ 无工具 → finalizeAssistantResponse(...)              :1985  ④ 收尾（记忆/通知/落库）
        └─ 有工具 → handleToolInvocation(invocations, ...)      :1956
                │
                ├─ ToolExecutionManager.executeInvocations(...)        ToolExecutionManager.kt:504
                │     ├─ ① 工具暴露模式拦截（CLI/FULL）                                  :542
                │     ├─ ② 角色卡工具权限拦截                                            :554
                │     ├─ ③ Hook 拦截 + 权限检查（弹窗/deny_tool）                        :573
                │     ├─ ④ 并行/串行分组（白名单 12 个只读工具并行）                     :621
                │     ├─ ⑤ async 并行 + for 串行 → OrderedToolResults.complete(index, r)
                │     └─ finish() → ToolExecutionBatch(results, message)  ← 同一份 XML
                │
                └─ processToolResults(results, ...)             :2192
                      ├─ 把 batch.message 作为 TOOL_RESULT 加入历史      :2249
                      ├─ normalizeConversationHistoryForModel(...)       :2257
                      ├─ token 超阈值(0.70) → 触发总结并停               :2310-2323
                      ├─ serviceForFunction.sendMessage(...)  ← 下一轮请求 :2337
                      └─ 流收集完 → processStreamCompletion(...)  ←★ 互相递归 :2458
```

**关键特征对照 AgentKit**：

| 维度 | Operit | AgentKit（`core/AgentLoop.kt`） |
|---|---|---|
| 循环形态 | **互相递归**（无硬上限） | `repeat(maxSteps=16)`（`:68`）有界 |
| 工具协议 | 内部 XML（正则解析） | 原生 `tool_calls` JSON（`LLMClient`） |
| 并行 | 白名单 12 个只读工具并行，其余串行 | `take(maxParallelTools=5)` 全部并行 |
| 结果有序 | `OrderedToolResults` 只发布已完成前缀 | `awaitAll()` 保序（无同名错配问题） |
| 错误恢复 | 截断修复 + 纯思考告警 + 未闭合告警（回灌重试） | 仅异常兜底成 `ToolMessage`（`:161`） |
| 停止条件 | token 阈值 + `isConversationActive` | 无 toolCalls / 达 maxSteps |
| 上下文压缩 | 阈值触发 LLM 总结 | 滑动窗口丢最早 User/Tool 对（`:218`） |

---

## ③ 核心功能逐项剖析（按与 AgentKit 相关性排序）

### 3.1 Agent 执行引擎 ★★★★★

#### 3.1.1 工具调用协议：内部 XML 为 canonical form

**它怎么做**（`docs/doc-src/feature-protocol/toolcall_xml_conversion.md`）

- 工具调用（assistant 输出）：`<tool name="read_file"><param name="path">README.md</param></tool>`
- 工具结果（写回历史）：`<tool_result name="read_file" status="success"><content>…</content></tool_result>`
- `enableToolCall=true` **不替换内部执行模型**，只是：
  - 请求前：`ToolPrompt` + 结构化参数 → Provider 原生 `tools` JSON Schema + `tool_choice:"auto"`（文档 `:79-82`）
  - 响应后：`delta.tool_calls` 经 `StreamingJsonXmlConverter` 增量转回 XML（文档 `:100-101`）；非流式走 `convertToolCallsToXml`（`:108`）
- 正则定义在 `util/ChatMarkupRegex.kt:27-38`（`toolCallPattern` 含 3 组捕获：标签名 / `name` 属性 / 体），`:52`（`toolResultTagWithAttrs`）
- 结果侧格式化：`ConversationMarkupManager.formatToolResultForMessage`（`:54`），**每个结果独立按 `MAX_SINGLE_TOOL_RESULT_MESSAGE_CHARS` 截断**（`:111` 注释明确「batch 本身不截断，因为每个调用必须保留对应结果槽位」），截断后缀 `"\n[工具结果过长，已截断]"`（`:19`）
- 图片结果会从文本里剥出，转成 `<link type="image" id="…"></link>` 挂到结果尾部（`splitImageLinksForModel`，`:84-101`）

**为什么这样设计**（推断，非文档明说）：39 家云端 Provider + 2 个本地引擎，每家 tool_calls 格式不同（OpenAI / Responses / Anthropic / Gemini / Codex）。把「执行语义」锚定在一份与 Provider 无关的 XML 上，**新增 Provider 只需写一次双向转换器**，执行器零改动。

**对 AgentKit 的启示**：我们只有 3 家云端 + 1 家本地，且都支持原生 `tool_calls`，**不必上 XML**。但「**结果只格式化一次、UI 与模型共用同一份文本**」这条必须抄（见 3.1.3）。

#### 3.1.2 工具批量执行：`ToolExecutionManager.executeInvocations`

**它怎么做**（`api/chat/enhance/ToolExecutionManager.kt:504-663`）

五段式流水线，每段都把被拦截的调用**按原 index 落到结果槽**（`orderedResults.complete(index, deniedResult)`），保证「被拒绝/被拦截」与「已执行」在历史上位置一致：

1. **工具暴露模式拦截**（`:542-551`）：CLI 模式下只允许 `search`/`proxy`
2. **角色卡工具权限拦截**（`:554-570`）：角色卡可配 custom 工具白名单
3. **Hook 拦截 + 权限检查**（`:573-599`）：
   - `toolHandler.checkToolInterception(tool)` → `Allow` / `Block(reason)`（`AIToolHandler.kt:100-117`，任一 Hook Block 即阻断；Hook 抛异常也按 Block 处理）
   - `checkToolPermission`（`:429`）：**若原始文本含 `deny_tool` 则跳过权限检查**（`:458`），用于「模型自己声明这次不需要授权」的场景（**待验证**：这个标记的触发语义未见文档）
4. **并行/串行分组**（`:621-630`）：
   ```kotlin
   val parallelizableToolNames = setOf(
       "list_files","read_file","read_file_part","read_file_full","file_exists",
       "find_files","file_info","grep_code","calculate","ffmpeg_info",
       "visit_web","download_file")
   ```
   —— **只有只读/无副作用工具并行**，其余一律串行。这是对「并行写文件导致状态错乱」的预防。
5. **执行与聚合**（`:634-660`）：并行 `async` + 串行 `for`，最后 `parallelJobs.awaitAll()`；每个调用经 `executeTool`（`:668`）→ `aggregateToolResults`（`:710`，把流式中间结果 join 成一个最终结果，`success` 取最后一个分片）

`executeTool`（`:668-707`）关键点：
- 用 `ThreadLocal` 传递 `ToolRuntimeContext(callerCardId, toolExposureMode)`（`:677`、`:44`），让工具内部能感知「谁在调用/哪种暴露模式」
- 找不到执行器时不直接报错，而是 `buildToolNotAvailableErrorMessage`（`:739-791`）**按错误形态给出可纠正的提示**：把 `packName.toolName` 写成 `.` 的，提示改用 `packName:toolName`；包名当工具名用的，提示先 `use_package`

**参数大小上限**（`core/tools/ToolExecutionLimits.kt:3-8`）：
```kotlin
MAX_FILE_READ_BYTES = 32_000
DEFAULT_FILE_READ_PART_LINES = 200
MAX_TEXT_RESULT_LENGTH = 5_000
MAX_SINGLE_TOOL_RESULT_MESSAGE_CHARS = MAX_FILE_READ_BYTES * 2   // 64_000
```

#### 3.1.3 结果有序性：`OrderedToolResults`（踩坑最深的一课）

**它怎么做**（`api/chat/enhance/OrderedToolResults.kt:15-47`）

```kotlin
internal class OrderedToolResults(count: Int, private val publish: suspend (String) -> Unit) {
    private val results = arrayOfNulls<ToolResult>(count)
    private val markup  = arrayOfNulls<String>(count)
    private var next = 0
    suspend fun complete(index: Int, result: ToolResult) = mutex.withLock {
        results[index] = result
        markup[index]  = ConversationMarkupManager.formatToolResultForMessage(result)   // ← 只在这里格式化一次
        while (next < results.size && results[next] != null) { publish(checkNotNull(markup[next])); next++ }
    }
    suspend fun finish(): ToolExecutionBatch = mutex.withLock {
        check(next == results.size) { "Tool batch has unpublished results" }
        ToolExecutionBatch(results = results.map { checkNotNull(it) }, message = markup.joinToString("\n") { checkNotNull(it) })
    }
}
```

三个设计要点（文件头注释 `:7-14` 自述）：
1. **同一份 XML 只生成一次**，同时给「保存的响应流」和「下一轮模型历史」两条路径用 —— 避免「UI 看到的」和「模型看到的」不一致
2. **只发布「已完成前缀」**（从 index 0 开始连续完成的部分）—— 后面的调用先完成时要等前面，这样**同名工具的结果不会互换主人**
3. **index 属于本批次**，不按工具名或调用相等性匹配

配套细节：`ensureOwnLine`（`ToolExecutionManager.kt:62-65`）保证注入的 XML 块独占一行，否则「粘在上一个字符后面的标记」会被解析器当成普通模型文本。

#### 3.1.4 错误恢复（三类，都在 `EnhancedAIService` 内）

| 故障 | 检测 | 处置 | 位置 |
|---|---|---|---|
| **输出截断**（半个 `<tool>`） | `detectAndRepairTruncatedToolRound`：找最后一个不在任何完整块内的开标签（`:1448-1459`），确认有 `name=` 属性但没有对应闭标签（`:1462-1473`） | ① 追加缺失后缀补齐标签（不撤回已发内容）；② **本轮所有工具作废**；③ 注入 `<status type="warning">` 并**回灌给模型重来** | `:1443`、`:1502`、`:1886-1947` |
| **纯思考输出**（thinking 后正文为空） | `ChatUtils.removeThinkingContent(content).isEmpty()` | 注入 `R.string.enhanced_pure_thinking_only_warning`，回灌给模型继续生成 | `:1770-1834` |
| **XML 格式不规范** | `enhanceToolDetection` 用 `StreamXmlPlugin` 把字符流切成「XML 块 / 文本」，对工具标签做标准化：`<tool  name =` → `<tool name=` | 输出规范化后的内容 | `:1335-1404`、`:1411-1427` |

另外：`processStreamCompletion` 在**每一段逻辑后都复查 `context.isConversationActive.get()`**（`:1740`、`:1864`、`:1882`），用户取消后不再继续跑完成逻辑；`finally` 里还判断 `hadFatalError`（`:1284`）。

#### 3.1.5 上下文管理

- 历史用 `PromptTurn(kind, content, toolName)` 表达，`kind ∈ {SYSTEM, USER, ASSISTANT, TOOL_CALL, TOOL_RESULT}`（`core/chat/hooks/PromptTurn.kt`）
- `applyFinalizedCurrentUserTurn(...).mergeAdjacentTurns { prev, cur -> 同为 USER 且 toolName 相同 }`（`:1100-1108`）—— 合并相邻同类轮次
- `ConversationService.normalizeConversationHistoryForModel`（`:678-707`）：只对 `apply_file` 的 tool_result 做特殊归一化（把请求内容塞回 `<content>`），其余原样
- **摘要/压缩**：`ModelConfigDefaults`（`ModelConfigData.kt:60-69`）给出默认：上下文 64K、上限 200K、摘要阈值 **0.70**、按消息数摘要阈值 **16 条**。触发点在 `processToolResults`（`:2310-2323`）：`usageRatio >= tokenUsageThreshold` → `onTokenLimitExceeded()` + `isConversationActive=false` + 停服务
- **分功能模型**（`data/model/FunctionType.kt:4-16`）：`CHAT / SUMMARY / TITLE_GENERATION / MEMORY / UI_CONTROLLER / TRANSLATION / GREP / ROLE_RESPONSE_PLANNER / IMAGE_RECOGNITION / AUDIO_RECOGNITION / VIDEO_RECOGNITION` —— **同一 App 内不同任务可配不同模型**

---

### 3.2 工具系统：注册 / Schema / 权限 ★★★★★

#### 3.2.1 注册机制

`AIToolHandler`（`core/tools/AIToolHandler.kt:29`）是单例注册中心：

```kotlin
private val availableTools = ConcurrentHashMap<String, ToolExecutor>()   // :45
private val toolHooks      = CopyOnWriteArrayList<AIToolHook>()           // :46

fun registerTool(name: String,
                 descriptionGenerator: ((AITool) -> String)? = null,      // 给人看的动态描述
                 executor: ToolExecutor)                                   // :179

interface ToolExecutor {                                                   // :479
    fun invoke(tool: AITool): ToolResult
    fun invokeAndStream(tool: AITool): Flow<ToolResult> = flowOf(invoke(tool))   // 默认降级为一次性
    fun validateParameters(tool: AITool): ToolValidationResult = ToolValidationResult(valid = true)
}
```

- **懒注册 + 幂等**：`registerDefaultTools()` 用 `AtomicBoolean + synchronized` 双检（`:211-218`），且注释明确「可安全重复调用」（`ToolExecutionManager.kt:518-522`）
- **按名字自动激活工具包**：`getToolExecutorOrActivate`（`:303-359`）—— 工具名含 `:` 时（`packName:toolName`），若未注册则自动 `packageManager.usePackage(packName)`；若是 MCP 包且 session 不活跃还会重新激活
- **注册集中地**：`core/tools/ToolRegistration.kt:36` 的 `registerAllTools()`，共 **182 处 `registerTool` 调用**

**工具清单（去重后约 160 个，按域归类）**：

| 域 | 代表工具 |
|---|---|
| 文件 | `list_files / read_file / read_file_part / read_file_full / read_file_binary / write_file / write_file_binary / apply_file / create_file / edit_file / delete_file / copy_file / move_file / make_directory / find_files / grep_code / grep_context / file_exists / file_info / zip_files / unzip_files / share_file / download_file` |
| Shell/终端 | `execute_shell / create_terminal_session / execute_in_terminal_session / execute_in_terminal_session_streaming / execute_hidden_terminal_command / input_in_terminal_session / get_terminal_session_screen / close_terminal_session` |
| UI 自动化 | `run_ui_subagent / tap / long_press / swipe / set_input_text / press_key / click_element / capture_screenshot / close_all_virtual_displays` |
| 应用 | `start_app / stop_app / install_app / uninstall_app / list_installed_apps / get_app_usage_time / get_notifications / send_notification / toast` |
| 系统设置 | `get_system_setting / modify_system_setting / device_info / read_environment_variable / write_environment_variable` |
| 脚本/包 | `execute_sandbox_script_direct / list_sandbox_packages / set_sandbox_package_enabled / use_package / package_proxy` |
| 网络 | `http_request / multipart_request / visit_web / manage_cookies` |
| 浏览器会话 | `browser_navigate / browser_click / browser_type / browser_snapshot / browser_evaluate / browser_run_code / browser_take_screenshot / browser_console_messages / browser_network_requests / browser_tabs …（18 个）` |
| 蓝牙 | `bluetooth_*`（16 个：connect / read / send / ble_discover_services / ble_subscribe_characteristic …） |
| 记忆 | `query_memory / create_memory / update_memory / delete_memory / get_memory_by_title / link_memories / query_memory_links / move_memory / update_user_profile / update_user_preferences` |
| 聊天管理 | `list_chats / create_new_chat / switch_chat / delete_chat / find_chat / get_chat_messages / get_chat_messages_range / update_chat_title / send_message_to_ai(_streaming)` |
| 模型配置 | `list_model_configs / create_model_config / update_model_config / delete_model_config / test_model_config_connection / get_function_model_config / set_function_model_config` |
| 角色卡 | `create/update/delete/list/get/set_active/clear_active_character_card`、`import/export_character_card_from/to_tavern_json` |
| 工作流 | `create_workflow / update_workflow / patch_workflow / get_workflow / get_all_workflows / enable_workflow / disable_workflow / trigger_workflow` |
| 媒体 | `ffmpeg_execute / ffmpeg_info / ffmpeg_convert / music_play / music_pause / music_stop / music_seek / music_set_volume / music_status / music_play_queue` |
| 其他 | `calculate / sleep / trigger_tasker_event / execute_intent / send_broadcast / get_device_location / update_workflow / agent_status / proxy / search(CLI)` |

#### 3.2.2 Schema 单一事实源

`core/config/SystemToolPrompts.kt:13` 的 `object SystemToolPrompts` 用 `ToolPrompt` + `ToolParameterSchema` 声明工具契约：

```kotlin
val basicTools = SystemToolPromptCategory(
    categoryName = "Available tools",
    tools = listOf(
        ToolPrompt(
            name = "sleep",
            description = "Demonstration tool that pauses briefly.",
            parametersStructured = listOf(
                ToolParameterSchema(name = "duration_ms", type = "integer",
                                    description = "milliseconds, default 1000, >= 0",
                                    required = false, default = "1000")
            )
        ), … )
```
（`:46-79`；`SystemToolPromptsInternal.kt` 5992 行是完整清单；`basicToolsCn` 为中文版，`:81+`）

**文档化的「改一个工具要同步 7 处」清单**（`docs/doc-src/architecture/DEFAULT_TOOLS_ARCH.md:29-153`）：
1. `SystemToolPrompts.kt` 的 `parametersStructured` / `description` / `details`（中英两份）
2. `ToolRegistration.kt` 注册项
3. Kotlin 执行实现（`defaultTool/{standard,admin,root,debugger,accessbility}/` 各处 override 都要同步）
4. `javascript/JsTools.kt` 的 JS wrapper
5. `examples/types/*.d.ts` 的 TS 类型
6. `examples/**` 示例
7. `app/src/main/assets/packages/*.js` 打包产物 + `docs/doc-src/package-dev/*.md` 文档

> 这份清单本身就是**工程纪律的产物**——它承认「一个工具散落在 7 个地方」是现状，用 checklist 来兜住。我们不必复制这个坏味道，但**「schema 必须是单一事实源」**这条要守住。

#### 3.2.3 权限模型（三级 + 全局默认）

`docs/doc-src/feature-protocol/tool_permissions.md:9-13,25-40`：

- 三个等级：**ALLOW（直接执行）/ ASK（每次弹窗）/ FORBID（直接拒绝）**
- 两层配置：**全局默认权限**（作用于所有未单独配置的工具）× **单工具例外配置**（覆盖全局）
- 运行时入口：`ToolExecutionManager.checkToolPermission`（`:429-493`）→ `ToolPermissionSystem.checkToolPermission(tool)`，返回 `(granted, errorMessageResId)`
- **逃生舱**：模型可在原始文本里写 `deny_tool` 标记跳过权限检查（`:458`、`:487-492`）

#### 3.2.4 Hook 生命周期（`core/tools/AIToolHook.kt:17-36`）

```
onToolCallRequested(tool)                          // 收到调用请求
onToolCallIntercept(tool): Allow | Block(reason)   // 权限检查前，可阻断
onToolPermissionChecked(tool, granted, reason?)    // 权限检查后
onToolExecutionStarted(tool)                       // 即将执行
onToolExecutionResult(tool, result)                // 产出结果（流式会多次）
onToolExecutionError(tool, throwable)              // 抛异常
onToolExecutionFinished(tool)                      // 生命周期结束
```
分发用 `notifyHooks(eventName) { … }` 包 try/catch，单个 Hook 抛异常不影响其他 Hook（`AIToolHandler.kt:84-92`）；但 **intercept 阶段 Hook 抛异常按 Block 处理**（`:105-110`）。

#### 3.2.5 工具包（ToolPkg）与「按权限选实现」

- **ToolPkg**：`core/tools/packTool/PackageManager.kt`（167KB，≈2800 行）管理 JS 工具包 / MCP 包 / 子包，支持容器包、插件配置、资源读取、Compose DSL 脚本、导航入口、工作流模板、WASM 模块（`readToolPkgWasmModuleBytes`，`:1763`）
- **按权限选实现**：`defaultTool/ToolGetter.kt:13-219` 是一个工厂门面，按「当前用户首选权限级别」返回对应实现族（`getFileSystemTools` / `getUITools` / `getShellToolExecutor` / `getTerminalCommandExecutor` …）。配合 `system/shell/ShellExecutorFactory` 形成完整的分级体系（详见 3.4）

---

### 3.3 设备自动化：虚拟屏 / 无障碍 / Shizuku ★★★★★

#### 3.3.1 Shower 虚拟屏（`showerclient/` + `core/tools/agent/`）

**架构（只读思路，不碰实现）**，来自 `showerclient/README.md`：

1. **宿主注入能力**：宿主实现 `ShellRunner { suspend fun run(command, identity): ShellCommandResult }` 并注入 `ShowerEnvironment.shellRunner`。库自己**不执行 shell**（README §2）
2. **内置 server JAR**：库 assets 自带 `shower-server.jar`，运行时复制到 `/data/local/tmp/shower-server.jar`（README §3）
3. **起进程**：`CLASSPATH=/data/local/tmp/shower-server.jar app_process / com.ai.assistance.shower.Main <hostPackage> &`
4. **Binder 交接**：server 用 `IActivityManager.broadcastIntent(...)` 发 `com.ai.assistance.operit.action.SHOWER_BINDER_READY`，并用 `Intent.setPackage(<hostPackage>)` 定向投递；Extra 是 `com.ai.assistance.shower.ShowerBinderContainer`（Parcelable，内含 `IBinder`）。宿主 Manifest 要声明 `exported=true` 的 receiver（README §4）
5. **建虚拟显示**：`ShowerController.ensureDisplay(context, width, height, dpi, bitrateKbps)`
6. **发事件 / 取截图**：`touchDown/touchMove/touchUp/key(KeyEvent)` / `requestScreenshot(): ByteArray?`
7. **可选视频组件**：`ShowerVideoRenderer` + `ui.ShowerSurfaceView`，或自己 `setBinaryHandler { frame -> … }` 解码（README §6）

文件规模：`ShowerController.kt` 485 行、`ShowerVideoRenderer.kt` 267 行、`ShowerServerManager.kt` 156 行、`ShellEnvironment.kt` 104 行、`ShowerBinderRegistry.kt` 33 行；App 侧 `core/tools/system/shower/OperitShowerShellRunner.kt` 30 行（就是把 App 自己的 shell 执行器适配成 `ShellRunner`）。

**App 侧封装**：`core/tools/agent/` 下有一套与 `showerclient` 平行的 App 内实现（`ShowerController.kt` 201 行、`ShowerServerManager.kt` 29 行、`ShowerBinderReceiver.kt` 29 行、`ShowerBinderRegistry.kt` 18 行、`ShowerVideoRenderer.kt` 27 行、`VirtualDisplayManager.kt` 170 行）—— **待验证**：为何存在两套（疑为历史迁移残留），不深究。

#### 3.3.2 UI 子代理 `PhoneAgent`（最有借鉴价值的一块）

**它怎么做**（`core/tools/agent/PhoneAgent.kt`）

```kotlin
data class AgentConfig(val maxSteps: Int = 20)                    // :43
data class StepResult(success, finished, action, thinking, message) // :48
data class ParsedAgentAction(metadata, actionName, fields)         // :57
```

**动作协议是「类函数调用文本」，不是 JSON**（`:691-724`）：
```
<think>…推理…</think>
do(action="Tap", element="[0.52, 0.31]")
do(action="Type", text="hello")
do(action="Swipe", start="[0.5,0.8]", end="[0.5,0.2]")
do(action="Back")  /  do(action="Home")  /  do(action="Wait", duration="2 seconds")
finish(message="已完成")
```
解析用两条正则：先找 `finish(` / `do(` 的**最后一次出现位置**（`:693-702`，防止前面思考文本里出现同名字符串），再用 `(\w+)\s*=\s*(?:\[(.*?)\]|\"(.*?)\"|'([^']*)'|([^,)]+))` 抽字段（`:716`）。

**主循环**（`:355-593`）：
```
run(task, systemPrompt, onStep, isPausedFlow, targetApp)
  ensureRequiredVirtualScreenOrError()          // :371  前置检查
  prewarmMainScreenShowerIfPossible()           // :376  预热主屏/启动目标 App
  prewarmShowerIfNeeded(...)                    // :386
  showAutomationControls(totalSteps=maxSteps, onTogglePauseResume, onExit)   // :412  用户可暂停/接管/退出
  _executeStep(task, isFirst=true)              // :440
  while (_stepCount < config.maxSteps) {        // :507  ★ 有界
      awaitIfPaused()                           // :508  支持暂停
      result = _executeStep(null, isFirst=false)
      if (result.finished) return message
  }
  return "Max steps reached"                    // :566
```

**单步**（`:602-660`）：
```
stepCount++
screenshotLink = actionHandler.captureScreenshotForAgent()          // :606
userMessage = if (isFirst) "$task\n\n[SCREENSHOT] …" else "** Screen Info **\n\n[SCREENSHOT] …"
history += ("user" to userMessage)
response = uiService.sendMessage(history, enableThinking=false, stream=true, preserveThinkInHistory=true)  // :624
(thinking, answer) = parseThinkingAndAction(fullResponse)           // :637
history += ("assistant" to "<think>…</think><answer>…</answer>")    // :638
parsed = parseAgentAction(answer)
actionHandler.removeImagesFromLastUserMessage(history)              // :642  ★ 历史里只留最近一张截图
metadata == "finish" → 结束
metadata == "do"     → executeAgentAction(parsed)
else                 → 结束并报 "Unknown action format"
```

> **`removeImagesFromLastUserMessage`（`:949`）非常关键**：视觉模型每步都发一张截图，若全留在历史里，20 步就 20 张图。它的做法是**每步把上一张图从历史里摘掉**，只保留最新一张——**用「滑动窗口=1」换上下文不爆**。

**动作执行**（`:960-1145`，双通道）：
- 有 Shower 虚拟屏 → `ShowerController.tap/swipe/key/paste`（`:1036`、`:1095`、`:1112`、`:1073`）
- 无 → 回落到 `toolImplementations.tap/swipe/pressKey/setInputText`，即无障碍/Shizuku 通道的 `tap`/`swipe`/`press_key`/`set_input_text` 工具（`:1040`、`:1081`、`:1102`、`:1116`）
- `Launch` 特殊：优先在虚拟屏里起 App；失败则回落到 `com.ai.assistance.operit.desktop` 桌面包；再失败走 `start_app` 工具（`:966-1030`）
- `Type` 在 Shower 下的实现：Ctrl+A → DEL → 清不干净就 `KEYCODE_MOVE_END` 后连按 200 次 DEL → 写剪贴板 → Ctrl+V（`:1052-1074`）。**这是「虚拟屏里没法直接用 IME 输入」的务实绕法**
- `Take_over` → 立即结束并提示「需要用户接管」（`:1142`）—— **登录/验证码场景的优雅退出**
- 动作间有固定延迟：`POST_LAUNCH_DELAY_MS`、非等待动作后 `POST_NON_WAIT_ACTION_DELAY_MS`（`:1044` 等）

**权限前置**：`resolvePrivilegedExecutionState`（`:62-100`）把「权限级别」映射到 `isAdbOrHigher`（DEBUGGER/ADMIN/ROOT）与 `hasDebuggerShizukuAccess`；DEBUGGER 级别还要求 Shizuku 在运行且已授权，否则 `Launch` 直接失败并给出 `phone_agent_shizuku_unavailable` 提示（`:974-976`）。

**UI 呈现**：跑之前隐藏悬浮窗、显示「彩虹边框进度浮层」或「虚拟屏浮层 + 暂停/退出控件」（`:405-431`）；结束/异常一律在 `finally` 里恢复（`:567-592`）。

#### 3.3.3 无障碍 / 动作通道

`core/tools/system/action/`：`ActionManager.kt`(306) / `ActionListener.kt`(107 接口) / `ActionListenerFactory.kt`(160) / `StandardActionListener.kt`(135) / `AdminActionListener.kt`(198) / `DebuggerActionListener.kt`(346) / `RootActionListener.kt`(333) / `AccessibilityActionListener.kt`(159) —— 与 Shell 一样的**五级分级模式**。
另有 `MediaProjectionCaptureManager.kt`(188) + `ScreenCaptureService.kt`(121) + `ScreenCaptureActivity.kt`(92) 走 MediaProjection 截图通道。

---

### 3.4 Shell / 终端执行 ★★★★☆

#### 3.4.1 五级 Shell 执行器（**最值得抄的抽象**）

**它怎么做**（`core/tools/system/shell/ShellExecutor.kt:7-97`）

```kotlin
interface ShellExecutor {
    suspend fun executeCommand(command: String, identity: ShellIdentity = ShellIdentity.DEFAULT): CommandResult
    fun getPermissionLevel(): AndroidPermissionLevel
    fun isAvailable(): Boolean
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun hasPermission(): PermissionStatus          // (granted, reason)
    fun initialize()
    suspend fun startProcess(command: String): ShellProcess    // ★ 长驻进程（logcat/getevent）
}
data class CommandResult(success, stdout, stderr="", exitCode=-1)      // :52
data class PermissionStatus(granted, reason)                            // :61
interface ShellProcess { val stdout: Flow<String>; val stderr: Flow<String>
                         fun destroy(); suspend fun waitFor(): Int; val isAlive: Boolean }   // :75
```

五级（`AndroidPermissionLevel.kt`）：`ROOT > ADMIN(Shizuku) > DEBUGGER(无线调试/ADB) > ACCESSIBILITY > STANDARD`，每级一个实现：`RootShellExecutor`(601) / `AdminShellExecutor`(217) / `DebuggerShellExecutor`(645) / `AccessibilityShellExecutor`(169) / `StandardShellExecutor`(328)。

**工厂**（`ShellExecutorFactory.kt:22-83`）：
- `getExecutor(context, level)`：按级别创建 + `initialize()` + **缓存**
- `getHighestAvailableExecutor(context)`：按 `ROOT → ADMIN → DEBUGGER → ACCESSIBILITY → STANDARD` 依次尝试 `isAvailable() && hasPermission().granted`，返回第一个可用
- `getUserPreferredExecutor(context)`：读 `androidPermissionPreferences.getPreferredPermissionLevel()`（用户自己选），**不做可用性检查**
- `clearCache(level?)`：清缓存（权限变化后必须清）

**调用入口**（`system/AndroidShellExecutor.kt:86-108`）：
```kotlin
suspend fun executeShellCommand(command: String, identityOverride: ShellIdentity?): CommandResult {
    val preferredLevel = getPreferredPermissionLevelCached() ?: AndroidPermissionLevel.STANDARD
    val executor = ShellExecutorFactory.getExecutor(ctx, preferredLevel)
    val permStatus = executor.hasPermission(); val available = executor.isAvailable()
    if (available && permStatus.granted) return executor.executeCommand(command, identity).toCommandResult()
    return CommandResult(false, "", buildStrictUnavailableReason(preferredLevel, available, permStatus), -1)
}
```
> **「严格模式」语义**（`:59-75`、`:104-107`）：用户选了哪级就用哪级，**不可用时直接失败并说明原因，不静默降级到更低权限**。注释直言 `Strict permission mode enabled`。这与 `AGENTS.md` 里「严禁兜底代码」的项目铁律一致。

`ShellIdentity`：`DEFAULT / APP / ROOT / SHELL`（`:139-144`）—— 让同一条命令能以不同身份执行。

#### 3.4.2 终端（Terminal）

- ⚠️ `terminal/` submodule 未 checkout，**内部实现不可读**
- App 侧封装 `core/tools/system/Terminal.kt:31-204`：包一层 `com.ai.assistance.operit.terminal.TerminalManager`
  - 暴露 `commandEvents: SharedFlow<CommandExecutionEvent>` / `directoryEvents` / `terminalState` / `sessions` / `currentDirectory` / `isInteractiveMode` / `interactivePrompt` / `isFullscreen`（`:50-58`）
  - **命令执行用「事件流 + commandId 配对」**（`:101-139`）：先生成 `UUID` 作为 `commandId`，**先订阅事件流再发命令**（`collectorReady.await()` 保证订阅先就绪，注释 `:175` 明说「避免快命令输出在订阅前丢失」），然后 `sendCommandToSession(sessionId, command, commandId)`，等 `isCompleted` 事件后 `deferred.complete()`
  - Flow 版 `executeCommandFlow`（`:157-180`）用 `transformWhile { emit(event); !event.isCompleted }`
  - `executeHiddenCommand(command, executorKey="default", timeoutMs=120_000)`（`:141-151`）—— **「隐藏执行」**：不占会话、给 Agent 用，带超时
  - `sendInterruptSignal(sessionId)` 支持 Ctrl+C（`:193`）
- `OperitTerminalManager.kt:14-51`：终端能力是**独立 APK**（`com.ai.assistance.operit.terminal`），通过 `GithubReleaseUtil` 从 `AAswordman/OperitTerminal` 拉 release 安装（`:16-17`、`:44-50`）
- Agent 侧工具：`create_terminal_session / execute_in_terminal_session / execute_in_terminal_session_streaming / execute_hidden_terminal_command / input_in_terminal_session / get_terminal_session_screen / close_terminal_session`（`ToolRegistration.kt:304-402`）

> **对照 AgentKit**：我们有 `core/container/{ProotRunner,RootfsManager,ContainerSession}` + `terminal/{TerminalJni,TerminalSession,TerminalEmulator,TerminalScreen}`。**缺的是「会话 + commandId 事件流」这套 Agent 友好的终态判定**——目前 Agent 只能靠 `shell_exec` 一次性拿 stdout。

---

### 3.5 本地模型：MNN 与 llama.cpp ★★★★☆

#### 3.5.1 llama.cpp（`llm/llama/`）

**Kotlin 侧**（`LlamaNative.kt` 全文 83 行）：
```kotlin
external fun nativeIsAvailable(): Boolean
external fun nativeGetUnavailableReason(): String
external fun nativeCreateSession(pathModel, nThreads, nCtx, nBatch, nUBatch,
                                 nGpuLayers, useMmap, flashAttention, kvUnified, offloadKqv): Long
external fun nativeReleaseSession(sessionPtr: Long)
external fun nativeCancel(sessionPtr: Long)
external fun nativeCountTokens(sessionPtr: Long, text: String): Int
external fun nativeSetSamplingParams(sessionPtr, temperature, topP, topK,
                                     repetitionPenalty, frequencyPenalty, presencePenalty, penaltyLastN): Boolean
external fun nativeApplyChatTemplate(sessionPtr, roles: Array<String>, contents: Array<String>,
                                     enableThinking, addAssistant): String?
external fun nativeApplyStructuredChatTemplate(sessionPtr, messagesJson, toolsJson, enableThinking, addAssistant): String?
external fun nativeGenerateStream(sessionPtr, prompt, maxTokens, callback: GenerationCallback): Boolean
external fun nativeSetToolCallGrammar(...): Boolean          // ★
external fun nativeClearToolCallGrammar(sessionPtr): Boolean  // ★
external fun nativeParseToolCallResponse(sessionPtr, content): String?  // ★
interface GenerationCallback { fun onToken(token: String): Boolean }    // 返回 false 可中断
```

**默认参数**（`LlamaSession.kt:9-19`）：
```kotlin
data class Config(nThreads = 4, nCtx = 2048, nBatch = 512, nUBatch = 512,
                  nGpuLayers = 0, useMmap = false, flashAttention = false,
                  kvUnified = true, offloadKqv = false)
```
会话内所有调用 `synchronized(lock)` 串行化，释放后再调用抛 `RuntimeException("LlamaSession has been released")`（`:58-67`）。

**★ 语法约束工具调用**（`llm/llama/src/main/cpp/llama_jni_stub.cpp`）：
- `buildToolCallGrammarConfig(const common_chat_params&)`（`:527-533`）从 llama.cpp 的 chat params 里取出 `grammar` / `grammar_lazy` / `grammar_triggers`
- 采样链里插 grammar sampler（`:84-116`）：
  - `lazy=true` → `llama_sampler_init_grammar_lazy_patterns(grammar, "root", triggerPatternsC.data(), triggerTokens.data(), triggerTokens.size(), …)`（`:94-103`）
  - 否则 `llama_sampler_init_grammar(...)`（`:104`）
  - grammar sampler 创建失败则**不加入链**（`:111`）
- `nativeParseToolCallResponse`（`:359`、实现 `:639-651`）：用 `toolCallGrammar.generationPrompt` 做 tokenize 后解析
- 上游固定 commit：`CMakeLists.txt` 里 `operit_prepare_git_source(... "https://github.com/ggml-org/llama.cpp.git" "720d7fa4097f76e5d0eade5a92c1df87c1faf9d9")`，并 `-Wl,-z,max-page-size=16384` 支持 Android 15 的 16KB 页

#### 3.5.2 MNN（`llm/mnn/`）

- `MNNLlmSession.create(modelDir, backendType="cpu", threadNum=4, precision="low", memory="low", tmpPath=null)`（`MNNLlmSession.kt:29-36`）
- JNI 面（`MNNLlmNative.kt`，21 个 external）：`nativeCreateLlm/nativeLoadLlm/nativeReleaseLlm/nativeTokenize/nativeDetokenize/nativeCountTokens/nativeGenerate/nativeGenerateStream/nativeGenerateStreamStructured/nativeApplyChatTemplate*/nativeCountTokens*/nativeDumpConfig/nativeGetContextInfo/nativeReset/nativeSetConfig/nativeSetAudioDataCallback/nativeGenerateWavform/nativeCancel`
- Session API（`MNNLlmSession.kt:126-426`）：`tokenize / detokenize / countTokens / countTokensWithHistory / countTokensStructured(messagesJson, toolsJson) / dumpConfig / getContextInfo / applyChatTemplate(3 种重载) / generate / generateStream / generateStreamStructured / chat / reset / cancel / setConfig(configJson) / setMaxNewTokens / setSystemPrompt / setAssistantPromptTemplate / setThinkingMode / setAudioDataCallback / generateWavform / release`
- **Provider 侧的初始化策略**（`api/chat/llmprovider/MNNProvider.kt:150-208`）：
  - backend 映射：`forwardType 0→cpu / 3→opencl / 4→auto / 6→opengl / 7→vulkan`，未知落 `cpu`（`:158-168`）
  - **内存模式按后端选**：`vulkan/opencl/opengl → "normal"`（否则 Clone error），`cpu → "low"`（`:172-178`）
  - 固定 `precision = "low"`（`:196` 注释「使用低精度以提升性能」）
  - 缓存目录 `context.cacheDir/mnn_cache` 传给 `tmpPath`（`:183-198`）
  - **配置必须在创建时传入**（`:191` 注释）
  - 模型能力从 `llm_config.json` 的 `max_all_tokens`（默认 2048）与 `is_visual/is_audio` 读取（`:229-260`）
- 模型市场：`data/mnn/MnnModelDownloadManager.kt`，从 `https://meta.alicdn.com/data/mnn/apis/model_market.json` 拉清单（`:108`），支持 `fetchModelList / downloadModel / pauseDownload / cancelDownload / deleteModel / getDownloadedModels`，下载状态持久化到 `mnn_download_states.json`（`:110`）

#### 3.5.3 ★ 本地模型的「工具暴露降级」（`core/tools/climode/CliToolModeSupport.kt`）

```kotlin
enum class ToolExposureMode { FULL, CLI;                                  // :18
    companion object { fun resolve(providerType: ApiProviderType) = when (providerType) {
        LMSTUDIO, OLLAMA, OPENAI_LOCAL, MNN, LLAMA_CPP -> CLI             // :23-29
        else -> FULL } } }

object CliToolModeSupport {
    const val SEARCH_TOOL_NAME = "search"          // :68
    const val PROXY_TOOL_NAME  = "proxy"           // :69
    private const val DEFAULT_SEARCH_LIMIT = 8
    private val PUBLIC_TOOL_NAMES = linkedSetOf(SEARCH_TOOL_NAME, PROXY_TOOL_NAME)
}
```

**工作方式**（串联 `ToolExecutionManager` + `EnhancedAIService`）：
- `getAvailableToolsForFunction`（`:2939-2941`）：CLI 模式只构建 `buildCliPublicToolPrompts` 的 **2 个工具**，而不是 `SystemToolPrompts` 的全量分类
- `search`：模型描述意图 → 从**隐藏工具目录**（`HiddenToolCatalogEntry`，`:52-61`，含 `built-in/internal/package/mcp/activation` 五类来源、关键词、建议参数 JSON）里检索出候选工具（默认返回 8 条）
- `proxy`：`{tool_name, params: JSON}` → `resolveProxyParameters`（`ToolExecutionManager.kt:275-299`，把 JSON 摊平成 `ToolParameter` 列表）→ `resolveToolTarget`（`:67-88`）转成真实工具再走正常执行链
- **权限检查对 CLI 公开工具直接放行**（`ToolExecutionManager.kt:445-455`）
- 非 CLI 模式下调用 `search`/`proxy` 会被拒（`buildToolExposureDeniedResult`，`:227-230`）

> 文档侧印证：`toolcall_xml_conversion.md:150-154` 明确写「`LLAMA_CPP` 的 ToolCall 模式：除提示词外，已新增 JNI 原生 grammar 约束；`MNN`：当前仍未接入等价的原生 ToolCall 能力」。

**这是把「180 个工具」压缩成「2 个工具 + 一个可搜索目录」的范式**，对 3B~8B 本地模型是唯一可行解。

---

### 3.6 JS 脚本引擎：QuickJS + 工具暴露 ★★★★☆

#### 3.6.1 引擎现状

- **已接入 App**（`app/build.gradle.kts:587` `implementation(project(":quickjs"))`；`JsEngine.kt:189` 里 `OperitQuickJsEngine()`）
- ⚠️ `quickjs/README.md` 说「还没有接到 app 模块」——**与代码不符，README 已过期**
- 单线程模型：`quickJsExecutor = Executors.newSingleThreadExecutor { Thread(runnable, "OperitQuickJsEngine") }`，配 `quickJsDispatcher` + `engineScope`（`JsEngine.kt:70-77`）；`runOnQuickJsThreadBlocking` 判断当前是否已在引擎线程，避免自锁（`:164-176`）
- 引擎池：`JsToolManager`（`core/tools/javascript/JsToolManager.kt`）建 **4 个 JsEngine + Channel 池**（`:25`、`:52-54`），`withEngine { }` 借还（`:56-62`）
- QuickJS Native 层：`quickjs/src/main/cpp/quickjs_jni.cpp` + `QuickJsNativeRuntime.kt`(190) / `QuickJsNativeHostDispatcher.kt`(91，实现 console 和 timer) / `QuickJsNativeCompatScriptBuilder.kt`(158，JS 兼容层) / `OperitQuickJsEngine.kt`(230)

#### 3.6.2 运行时引导模块（`JsLibraries.kt:12-60`）

```kotlin
buildRuntimeBootstrapModules(context, downloadDir, cleanOnExitDir) = buildInitRuntimeModules(...) + listOf(
    module("quickjs/init/execution-runtime.js", buildExecutionRuntimeBridgeScript()),
    module("quickjs/init/toolpkg-bridge.js",    buildToolPkgRegistrationBridgeScript(), globals = ["ToolPkg"]),
    module("quickjs/init/tools.js",             getJsToolsDefinition(),                 globals = ["Tools"]),
    module("quickjs/init/compose-dsl-bridge.js",buildComposeDslContextBridgeDefinition(),globals=["OperitComposeDslRuntime"]),
    module("quickjs/init/java-bridge.js",       buildJavaClassBridgeDefinition(),        globals = ["Java","Kotlin"]),
    module("quickjs/init/third-party-libs.js",  getJsThirdPartyLibraries(),              globals = ["_","dataUtils","Icons"]),
    module("assets/js/CryptoJS.js",             loadCryptoJs(context),                   globals = ["CryptoJS"]),
    module("assets/js/Jimp.js",                 loadJimpJs(context),                     globals = ["Jimp"]),
    module("assets/js/UINode.js",               loadUINodeJs(context),                   globals = ["UINode"]),
    module("assets/js/AndroidUtils.js",         loadAndroidUtilsJs(context),             globals = ["Android","Intent","PackageManager","ContentProvider","SystemManager","DeviceController"]),
    module("assets/js/OkHttp3.js",              loadOkHttp3Js(context),                  globals = ["OkHttpClientBuilder", …]),
    … )
```

#### 3.6.3 工具如何暴露给 JS（`JsTools.kt:4`）

一个纯字符串模板，注入全局 `Tools` 对象，**所有方法最终都落到同一个 `toolCall(name, params)` 宿主函数**：

```javascript
var Tools = {
    Files: {
        list: (path, environment) => toolCall("list_files", { path, environment }),
        read: (p) => toolCall("read_file_full", typeof p === 'string' ? {path:p} : {...p}),
        readPart: (path, startLine, endLine, env) => toolCall("read_file_part", {path, start_line:String(startLine), …}),
        write: (path, content, append, env) => toolCall("write_file", { path, content, append: append ? "true":"false", … }),
        apply: (path, type, oldContent, newContent, env) => toolCall("apply_file", {path, type, old:…, new:…}),
        edit:  (path, oldContent, newContent, env) => toolCall("edit_file", {path, old:…, new:…}),
        zip/unzip/find/grep/grepContext/exists/move/copy/mkdir/deleteFile/info/readBinary/writeBinary/…
    },
    // … 其余域（Shell / Terminal / UI / Memory / Chat / Http / Package …）
    // 含 run_ui_subagent（JsTools.kt:1181）
}
```

**设计要点**：
1. **只有一条宿主通道**（`toolCall`），JS 侧不持有任何 Android 对象 —— 沙箱边界清晰
2. **布尔值统一转 `"true"/"false"` 字符串**（`:47`、`:53` 等），因为内部工具协议参数全是 `String`
3. **同名 `.d.ts` 类型契约**：`examples/types/*.d.ts`（25 个文件），供脚本作者写 TS
4. **示例即资产**：`examples/*.ts`（48 个）→ 编译成 `*.js`（49 个）→ `sync_example_packages.py` 按 `packages_whitelist.txt` 同步到 `app/src/main/assets/packages/*.js`（31 个）（`DEFAULT_TOOLS_ARCH.md:121-137`）

#### 3.6.4 Agent 可否写 JS 执行？

**可以**，三条入口：
1. **`execute_sandbox_script_direct`** + `list_sandbox_packages` + `set_sandbox_package_enabled`（`ToolRegistration.kt:536`、`:510`、`:522`）—— 直接执行一段沙箱脚本
2. **ToolPkg 包**：包内 `main.js` 注册工具，经 `PackageManager` + `JsToolPkgRegistration.kt`(669) / `JsToolPkgApiRuntime.kt`(202) / `JsToolPkgExecutionContext.kt`(158) 挂进工具注册表，工具名形如 `packName:toolName`
3. **脚本内的 UI 能力**：`JsComposeDslBridge.kt`(921) + `JsComposeDslRuntimeScript.kt`(355) 让 JS 能声明式构造 Compose UI；`ToolPkgComposeDslScreen.kt`(185KB!) 是渲染宿主

---

### 3.7 记忆系统 / 3D 形象 / 工作流 / 集成（中低优先级，简记）

#### 3.7.1 记忆（三步流水线）

`docs/doc-src/architecture/memory_candidate_scoring_formula.md`：

1. **候选检索**：从输入抽「问题核心文本」→ 切关键词 → 分词成检索片段 → 数据库级「标题包含片段」匹配（**不把全量记忆拉进内存扫**）。总分
   $S(m)=S_{kw}(m)+S_{rev}(m)+S_{sem}^{norm}(m)+S_{graph}(m)$
   - 关键词命中：$\Delta_{kw}=\frac{p_m\cdot W_{kw}}{k_0+r}\cdot\lambda_m$，覆盖率增益 $\lambda_m=1+0.6\cdot c_m/F$
   - 反向包含（查询文本包含记忆标题）：$\Delta_{rev}=\frac{p_m\cdot W_{kw}}{k_0+r}$
   - 语义分（过阈值 $\tau$ 才计）：$\Delta_{sem}=\frac{\sqrt{p_m}}{k_0+r_t}+W_{sem}\cdot sim(m,t)$，长度归一化 $S_{sem}^{norm}=\frac{1}{\sqrt{K}}\sum_t \Delta_{sem}$
   - 关系传播：$\Delta_{graph}(u\to v)=S(u)\cdot w_{uv}\cdot W_{edge}+\beta\cdot W_{edge}$
   - **阈值过滤后取前 15 条**
2. **结构化决策**：模型输出 `{main, new, update, merge, links, user}`；判断无长期价值就返回空对象，系统直接结束不写入
3. **有序写回**：**先合并 → 再更新 → 再主事件/新增实体 → 最后建关系**；本地高相似去重优先复用旧记忆；关系建立有硬约束（源和目标都找得到 + 有明确证据，否则不建边）
   仓库：`data/repository/MemoryRepository.kt`（≈2600 行，含 `searchMemories/searchChunksInDocument/rebuildVectorIndices/getGraphForMemories/folder 管理`），向量检索用 **hnswlib**（`app/build.gradle.kts:579,699-700`），支持云端 embedding（`CloudEmbeddingService.kt`）

#### 3.7.2 3D 形象（`core/avatar/`，35 kt）

- **6 种渲染后端**：`AvatarType.DRAGONBONES / WEBP / MP4 / GLTF / FBX / MMD`（`common/model/AvatarType.kt`）
- **统一控制抽象**：`AvatarController`（`common/control/AvatarController.kt`）暴露 `state: StateFlow<AvatarState>` / `availableAnimations` / `setEmotion` / `playEmotion(emotion, loop)` / `playAnimation(name, loop)` / `estimateEmotionDurationMillis` / `playTrigger(name, loop)` / `estimateTriggerDurationMillis` / `lookAt(...)`
- **7 种情绪**：`IDLE / LISTENING / THINKING / HAPPY / SAD / CONFUSED / SURPRISED`（`common/state/AvatarEmotion.kt`）
- 工厂三件套：`AvatarControllerFactory` / `AvatarModelFactory` / `AvatarRendererFactory`（`common/factory/`）；模型抽象 `AvatarModel` / `ISkeletalAvatarModel` / `IFrameSequenceAvatarModel`（`common/model/`）
- 原生模块：`:mmd`（含 Bullet3）、`:fbx`（ufbx）、`:dragonbones`（自研 C++/OpenGL/JNI）；GLTF 用 Filament

> **对 AgentKit 的借鉴价值**：如果我们做 Agent 形象，**抄的是「情绪枚举 → 控制器接口 → 工厂」这层抽象**，而不是 MMD/FBX 引擎。`playTrigger(name)` 尤其值得学——它让模型用 `<mood>` 标签里的任意字符串驱动动画，不需要模型知道有哪些动画名。

#### 3.7.3 工作流 / 外部集成

- `core/workflow/`：`WorkflowExecutor` / `WorkflowScheduler` / `WorkflowWorker` / `WorkflowSchedulerInitializer`，支持 Intent 触发（`docs/doc-src/feature-protocol/workflow_intent_trigger.md`）
- `integrations/`：`http/ExternalChatHttpServer`（本机 HTTP 聊天服务，供外部脚本调用）、`a2a/`（A2A 协议）、`intent/`、`tasker/`、`externalchat/`；配套文档 `external_http_chat.md` / `external_a2a_server.md` / `external_intent_chat.md`
- `core/subpack/`：`ApkReverseEngineer.kt`(721) / `ApkEditor.kt`(299) / `KeyStoreHelper.kt`(197) / `ExeEditor.kt`(152) / `ExeIconChanger.kt`(170) —— **「做安装包」相关的二次打包能力**
- Web：`web-chat/`（React+Vite，产物进 `assets/web-chat`）+ `integrations/http/WebChatHttpBridge.kt`(122KB)

---

## ④ 「它怎么做 → 我们怎么适配」对照表

> 图例：🔴 P0 必做 · 🟡 P1 应做 · 🟢 P2 可选 · ⚪ 不借鉴

| # | 借鉴点 | Operit 怎么做（证据） | AgentKit 现状 | 我们的落地方案（净室重写） | 工作量 |
|---|---|---|---|---|---|
| 1 | **工具结果「一份真源、两处复用」** | `OrderedToolResults` 只格式化一次 XML，同时供给响应流与模型历史（`OrderedToolResults.kt:29-45`）；`processToolResults` 注释明令「不能再次输出」（`EnhancedAIService.kt:2265-2266`） | `AgentLoop.kt:160` 只写 `ToolMessage(content)`，UI 与模型同源（因为都是同一对象），**风险较低** | **保持现状但加断言**：把「工具结果字符串」抽成 `formatToolResult(result)` 单一函数，UI 与历史都调它；补一条单元测试：同一 result 两次调用结果相等 | 0.5d |
| 2 | **有界 UI 子代理循环（PhoneAgent 范式）** 🔴 | `maxSteps=20`；每步「截图→视觉模型→解析 `do(...)`/`finish(...)`→执行」；`Take_over` 优雅退出；每步摘掉上一张截图（`PhoneAgent.kt:43,507,602,642,1142`） | 有 `automation/`（Shizuku/虚拟屏）+ `UiAutomationTools` + `ui_screenshot`，**但没有「循环」**——只能单步人工驱动 | **`core/automation/UiAgentLoop.kt`**：`maxSteps=12`；`StepResult(success, finished, action, thought)`；动作协议用 **JSON**（我们用的都是支持 tool_calls 的模型，不需要类函数调用文本）；`screenshot → chat(messages+image) → toolCalls → execute`；结束条件 `finish` 工具或达上限；每轮只保留最近 1 张截图；`take_over` 工具用于登录/验证码 | 3d |
| 3 | **本地模型工具暴露降级（CLI 模式）** 🔴 | `ToolExposureMode.resolve()` 对 `MNN/LLAMA_CPP/OLLAMA/LMSTUDIO/OPENAI_LOCAL` 只暴露 `search`+`proxy` 两个工具，其余进「隐藏工具目录」按需检索（`CliToolModeSupport.kt:21-31,68-69`） | `core/llm/local/LocalLlamaClient` 直连 llama.cpp JNI，**工具 schema 与云端同一套全量下发** | **`core/llm/local/ExposureMode`**：`isLocalProvider` 为 true 时，`ToolRegistry.list()` 只返回 `tool_search` + `tool_proxy`；其余工具进 `HiddenToolCatalog(name, description, keywords, paramHints)`；`tool_search(query)` 用**关键词包含 + 描述打分**（无向量依赖）返回 top-6；`tool_proxy(name, argsJson)` 转发到真实工具。首批只需 40 行 Kotlin + 一份目录常量 | 2.5d |
| 4 | **Shell 权限分级工厂 + 严格模式** 🔴 | 五级 `ROOT/ADMIN/DEBUGGER/ACCESSIBILITY/STANDARD` 各一个实现 + `ShellExecutorFactory` 缓存/择优/读用户偏好（`shell/ShellExecutor.kt:7`、`ShellExecutorFactory.kt:22-83`）；不可用时**直接失败并说明原因，不静默降级**（`AndroidShellExecutor.kt:99-108`） | `automation/` 有 Shizuku，`core/container/ProotRunner` 有 proot，**但没有统一的执行器抽象与「用户选级」** | **`core/shell/`**：`ShellExecutor { suspend fun exec(cmd): CommandResult; fun level(): Level; fun isAvailable(): Boolean; fun hasPermission(): PermissionStatus; fun initialize() }` + `ShellExecutorFactory`（缓存 + `getHighestAvailable()` + `getUserPreferred()`）；先做 `STANDARD`（App 进程 `sh`）+ `ADMIN`（Shizuku）两级；`DEBUGGER`（无线调试）与 `ROOT`（libsu）留接口不实现 | 2d |
| 5 | **终端会话 + commandId 事件流** 🟡 | `Terminal.executeCommand` 生成 UUID 作 commandId，**先订阅事件流再发命令**（`collectorReady.await()`），等 `isCompleted` 后返回；`executeHiddenCommand(cmd, executorKey, timeoutMs=120s)` 不占会话给 Agent 用（`Terminal.kt:101-180`） | 有 `terminal/TerminalSession` + `TerminalJni` + `TerminalEmulator`，**Agent 侧没有「执行一条命令并等到结束」的原语** | **`terminal/AgentCommandRunner.kt`**：`run(sessionId, cmd, timeoutMs): CommandResult`，内部订阅输出流 + 匹配「命令回显结束」标记（`echo -n "__AGENT_DONE_$uuid__"` 作为哨兵）判定完成；`runHidden()` 走一次性进程；超时可中断。默认超时 60s，可在工具参数覆盖 | 2d |
| 6 | **工具 Hook 生命周期** 🟡 | 7 个回调 + `Allow/Block(reason)` 决策；`notifyHooks` 单 Hook 异常不影响其他；intercept 阶段异常按 Block（`AIToolHook.kt:17-36`、`AIToolHandler.kt:84-117`） | 有 `AgentEvent.ToolStarted/ToolFinished`（`AgentLoop.kt:25-28`）与 `ToolApproval`（`:52`），**但没有「拦截」语义** | **`core/ToolHook.kt`**（接口 7 个默认空实现）+ `AgentLoop` 在执行前调 `hooks.checkIntercept(tool,args)`；首批落地 2 个 Hook：① `DangerousOpHook`（rm -rf / 写系统分区 → Block 并说明）② `LogHook`（统一走 `AgentLogger`）。`onEvent` 保留现有实现即可 | 1.5d |
| 7 | **截断/畸形输出修复 + 纯思考告警** 🟡 | `detectAndRepairTruncatedToolRound` 补齐标签 → 本轮工具全作废 → 注入 warning 回灌重试；`removeThinkingContent` 为空时同样回灌告警（`EnhancedAIService.kt:1443,1770,1886`） | 我们用**原生 tool_calls JSON**，Provider 已保证结构完整，**截断风险大幅降低**；但 `LocalLlamaClient` 走提示词 XML/JSON 时仍有风险 | 只在 **local-llama 路径**加一层 **`ToolCallRepairer`**：JSON 解析失败时，① 尝试补齐未闭合括号 ② 用正则捞出最后一个完整的 `{"name":…,"arguments":…}` ③ 都失败则回灌一条 `ToolMessage(isError=true, "上一次工具调用格式不合法，请重新输出完整 JSON")` 让模型重试，**最多 2 次**，第 3 次直接终止循环 | 1.5d |
| 8 | **并行白名单（只让只读工具并行）** 🟡 | 12 个只读工具进并行组，其余串行（`ToolExecutionManager.kt:621-630`） | `AgentLoop.kt:94-99` 全部并行（`take(maxParallelTools)`） | **`ToolSpec.concurrent: Boolean`**（默认 false）；只读类工具（`read_file/list_files/grep/http_get/get_weather/...`）标 true；`AgentLoop` 按 `partition { it.concurrent }` 分两组：只读组 `async+awaitAll`，有副作用组 `for` 串行 | 0.5d |
| 9 | **工具结果体积上限** 🟡 | `MAX_FILE_READ_BYTES=32_000` / `MAX_TEXT_RESULT_LENGTH=5_000` / `MAX_SINGLE_TOOL_RESULT_MESSAGE_CHARS=64_000`，每个结果独立截断且带明确后缀（`ToolExecutionLimits.kt:3-8`、`ConversationMarkupManager.kt:19,111`） | 无上限，`result.content.take(300)` 只作用于日志（`AgentLoop.kt:159`） | **`core/ToolLimits.kt`**：`MAX_TOOL_RESULT_CHARS = 24_000`；`AgentLoop.executeCallInner` 返回前统一 `truncate(result)`，超长追加 `"\n…[结果过长已截断，共 N 字符]"`，并建议模型改用分页参数 | 0.5d |
| 10 | **JS 工具桥（`Tools.*` 单通道）** 🟡 | 全局 `Tools.Files.read(...)` 全部落到同一个宿主 `toolCall(name, params)`；布尔转 `"true"/"false"`；配 `.d.ts` 契约（`JsTools.kt:4-120`） | 有 `rhino 1.7.14`（红线内），`skill/` 有脚本执行与 `SkillSecurity`，**但「工具暴露给 JS」未做** | **`skill/JsToolBridge.kt`**：`SkillExecutor` 执行前注入一个 Java 对象 `__agent`，只暴露 `call(name, argsJson): String` 一个方法；JS 侧用一段 60 行的 `Tools` 模板（自写，不抄）包装成 `Tools.Files/Tools.Shell/Tools.Ui/Tools.Memory`；**不开放 `Java` 反射桥**（安全风险）；受 `SkillSecurity` 现有信任等级门控 | 2d |
| 11 | **「工具不存在」的可纠正错误信息** 🟢 | 按错误形态分四种：`.` 写成 `:` 的、包不存在、包未激活、工具不存在，每种给出「下一步该调什么」（`ToolExecutionManager.kt:739-791`） | `AgentLoop.kt:141` 只回「未知工具：xxx」 | **`core/ToolErrors.kt`**：未知工具时做**编辑距离 ≤2 的模糊匹配**，回 `"未知工具 'xx'。你是否想调用 'xxx'？当前可用工具：…"`（列出前 20 个）+ 建议调 `tool_search` | 0.5d |
| 12 | **分功能模型（FunctionType）** 🟢 | `CHAT/SUMMARY/TITLE_GENERATION/MEMORY/UI_CONTROLLER/TRANSLATION/GREP/IMAGE_RECOGNITION/...` 各自可配不同模型（`FunctionType.kt:4-16`） | 单一「当前模型配置」，有 `ModelListFetcher` 但无分功能 | **`core/FunctionModelRouter.kt`**：先只分两类——`CHAT`（主模型）与 `AUX`（摘要/标题/视觉，默认复用主模型）；`ConfigStore` 加 `auxConfigId`。UI 侧不动 | 1.5d |
| 13 | **三级权限模型（ALLOW/ASK/FORBID + 全局默认）** 🟢 | 全局默认 × 单工具例外；拦截发生在执行前（`tool_permissions.md:9-40`、`ToolExecutionManager.kt:429-493`） | 有 `ToolApproval` 弹窗 + `needsApproval` 布尔（`AgentLoop.kt:52,144`），**粒度是「是/否」不是「允许/询问/禁止」** | 把 `ToolSpec.needsApproval: Boolean` 升为 `policy: ToolPolicy`（`ALLOW`/`ASK`/`FORBID`），加全局默认；`FORBID` 直接回 `ToolMessage(isError, "该工具已被用户禁用")`，不弹窗 | 1d |
| 14 | **记忆候选打分 + 有序写回** 🟢 | 关键词/反向包含/语义/图传播四路打分，取前 15；模型输出 `{main,new,update,merge,links,user}` 结构化决策；写回顺序固定「合并→更新→主事件→建关系」（`memory_candidate_scoring_formula.md`） | 有 `memory/MemoryStore` + `MemoryTools(memory_write/memory_search)`，**是朴素的全量/关键词检索** | 只借鉴**「结构化决策 + 有序写回」**：给 `memory_write` 增加可选 `merge_with` 参数；写入前做一次标题相似度去重（用现有的字符串相似度即可，不引 hnswlib）；**不引入向量检索**（会突破零依赖红线） | 2d |
| 15 | **形象抽象层（情绪→控制器→工厂）** 🟢 | 7 种情绪枚举 + `AvatarController` 接口 + 三工厂 + 6 种后端；`playTrigger(name)` 允许任意字符串驱动（`common/state/AvatarEmotion.kt`、`common/control/AvatarController.kt`） | 无 | 若做 Agent 形象，先只做 **Lottie/WebP 帧动画**一种后端；定义 `enum class AgentMood { IDLE, LISTENING, THINKING, HAPPY, SAD, CONFUSED, SURPRISED }` + `interface MoodController { val state: StateFlow<AgentMood>; fun setMood(m: AgentMood) }`；模型侧用 `<mood>happy</mood>` 标签驱动 | 2d（若做） |
| 16 | ⚪ **内部 XML 工具协议** | 全部工具调用内部走 XML，外部做协议转换（`toolcall_xml_conversion.md`） | 已用原生 `tool_calls` JSON，3 家云端 + local 都支持 | **不借鉴**。我们 Provider 数量少且都支持原生 tool_calls，引入 XML 反增复杂度 | — |
| 17 | ⚪ **无界互相递归主循环** | `sendMessage → processStreamCompletion → handleToolInvocation → processToolResults → processStreamCompletion`（`EnhancedAIService.kt:2458`） | `repeat(maxSteps=16)` 有界（`AgentLoop.kt:68`） | **不借鉴**。我们的有界循环更安全；但可借鉴「每轮结束后复查 `isConversationActive`」——加一个 `shouldContinue()` 钩子让 UI 能中途取消 | 0.5d |
| 18 | ⚪ **39 家 Provider 铺量 / ToolPkg 工具包生态 / 蓝牙 16 工具 / 浏览器会话 18 工具** | `ApiProviderType` 39 个枚举（`ModelConfigData.kt:7-45`）；`PackageManager` 167KB | 3 家云端 + local，无包生态 | **不借鉴**。产品目标里没有「铺量」，且会突破零依赖红线与工期 | — |
| 19 | ⚪ **Shower 独立进程虚拟屏** | `app_process` 起 server + Binder 广播交回 `IShowerService` + 建虚拟 Display（`showerclient/README.md` §3-5） | 有 `automation/` 的虚拟屏（实现方式不同）与 `ScreenCaptureService` | **只读思路，不实现**。我们的虚拟屏方案已存在且可用；Shower 的 `app_process` 方案依赖独立 JAR 与隐藏 API，风险高、收益不确定 | — |
| 20 | ⚪ **ObjectBox / 1159 文件单体 / FFmpeg / MMD·FBX / 蓝牙 / Web Chat** | — | — | **不借鉴**，与产品目标无关或超出红线 | — |

---

## ⑤ 优先行动清单

### P0 —— 直接决定「设备自动化可用」与「本地模型可用」（合计约 **8 人日**）

| # | 动作 | 产出文件（净室重写） | 人日 | 验收标准 |
|---|---|---|---|---|
| **P0-1** | **UI 子代理循环**（PhoneAgent 范式） | 新增 `core/automation/UiAgentLoop.kt`（≈180 行）、`tools/UiAgentTools.kt`（≈120 行） | **3d** | 下「打开设置→找到版本号→截图回来」这类 3 步任务，能自动跑完并返回结果；过程中可取消；达 12 步自动停并说明「未能在 12 步内完成」 |
| **P0-2** | **本地模型工具暴露降级** | 新增 `core/llm/local/ExposureMode.kt` + `tools/ToolSearchTools.kt`（≈200 行） | **2.5d** | 用 local-llama 时，模型只见 2 个工具；`tool_search("读文件")` 返回 ≥1 条带参数提示的候选；`tool_proxy` 能真正执行并回传结果 |
| **P0-3** | **Shell 权限分级工厂** | 新增 `core/shell/{ShellExecutor,ShellExecutorFactory,StandardShellExecutor,AdminShellExecutor}.kt`（≈260 行） | **2d** | 无 Shizuku 时走 STANDARD 并明确报错；有 Shizuku 时走 ADMIN；用户可在设置里切换，切换后 `clearCache()` 生效；**不可用时失败并说明原因，不静默降级** |
| **P0-4** | **可取消 + 结果截断**（配套加固） | 改 `core/AgentLoop.kt`（+`shouldContinue()` 钩子、+`truncate`）、新增 `core/ToolLimits.kt` | **0.5d** | 单条工具结果 >24K 字符自动截断并带提示；循环中途点「停止」能在 ≤1 轮内退出 |

### P1 —— 明显提升「终端可用」与「工具系统健壮性」（合计约 **6.5 人日**）

| # | 动作 | 产出文件 | 人日 | 验收标准 |
|---|---|---|---|---|
| **P1-1** | **终端命令执行原语（会话 + 事件流 + 超时）** | 新增 `terminal/AgentCommandRunner.kt`（≈150 行）+ `tools/TerminalTools.kt` | **2d** | `run_terminal_command(cmd, timeout_ms)` 能拿到完整 stdout+exitCode；`npm install` 这类长命令能在超时后中断并返回已产出输出 |
| **P1-2** | **工具 Hook 生命周期** | 新增 `core/ToolHook.kt` + 改 `AgentLoop.kt`；新增 `core/hooks/DangerousOpHook.kt`、`LogHook.kt` | **1.5d** | `rm -rf /` 类命令被 `DangerousOpHook` 拦下并给出可读原因；单个 Hook 抛异常不影响主流程 |
| **P1-3** | **JS 工具桥（`Tools.*` 单通道）** | 改 `skill/SkillExecutor.kt` + 新增 `skill/JsToolBridge.kt`、`skill/assets/agent_tools.js` | **2d** | Skill 脚本里 `Tools.Files.read("/sdcard/a.txt")` 能拿到内容；`Tools.Shell.exec("ls")` 能执行；无 `Java` 反射桥；受 `SkillSecurity` 信任等级门控 |
| **P1-4** | **本地模型工具调用修复器** | 新增 `core/llm/local/ToolCallRepairer.kt`（≈120 行） | **1d** | 模型吐半个 JSON 时能补齐或用正则捞回完整调用；连续 2 次失败后终止循环并提示用户 |

### P2 —— 锦上添花 / 长期（合计约 **5.5 人日**，按需取用）

| # | 动作 | 人日 | 说明 |
|---|---|---|---|
| **P2-1** | 并行白名单（`ToolSpec.concurrent` + `partition` 分组） | 0.5d | 与 P0-4 同批改动 `AgentLoop`，可合并 |
| **P2-2** | 未知工具模糊匹配错误提示 | 0.5d | 提升模型自纠率 |
| **P2-3** | 分功能模型（CHAT / AUX） | 1.5d | 摘要、标题、视觉可用便宜模型，省 token |
| **P2-4** | 三级权限策略（ALLOW/ASK/FORBID） | 1d | 把 `needsApproval: Boolean` 升级为策略枚举 |
| **P2-5** | 记忆写入「结构化决策 + 去重」 | 2d | 只抄「合并优先、顺序写回」，不引向量库 |

> **合计**：P0 ≈ **8 人日**；P1 ≈ **6.5 人日**；P2 ≈ **5.5 人日**（总计 ≈ 20 人日，P0+P1 = 14.5 人日为建议投入）。
> **零新增外部依赖红线**：以上全部只用 Kotlin 标准库 + 协程 + 已有 okhttp 4.12.0 / kotlinx-serialization-json 1.6.3 / rhino 1.7.14。**P0/P1 全部通过**。

---

## ⑥ 代码位置索引

### 6.1 架构与构建

| 内容 | 位置 |
|---|---|
| Gradle 模块声明（10 个模块 + 3 个目录映射） | `settings.gradle.kts:22-40` |
| 根构建（ObjectBox 5.3.0 插件） | `build.gradle.kts:1-11` |
| App 构建关键参数（minSdk 26 / targetSdk 34 / compileSdk 36 / arm64 only / v1.12.2） | `app/build.gradle.kts:360-410` |
| App 依赖清单 | `app/build.gradle.kts:579-737` |
| 版本目录（AGP 8.13.2 / Kotlin 2.2.21 / Compose BOM 2026.02.01） | `gradle/libs.versions.toml:2-12` |
| 根目录用途说明 | `Repo_Arch_Basic.md:1-104` |
| Git 子模块（terminal / tools-hotbuild） | `.gitmodules:1-7` |
| 项目工程铁律（禁兜底、禁回退、禁 powershell 编辑、用 rg） | `AGENTS.md:1-33` |

### 6.2 Agent 执行引擎 ★

| 内容 | 位置 |
|---|---|
| `EnhancedAIService` 类 | `api/chat/EnhancedAIService.kt:97` |
| 主入口 `sendMessage(options)` | `api/chat/EnhancedAIService.kt:903` |
| Prompt finalize Hook 分发 | `api/chat/EnhancedAIService.kt:1061-1094` |
| 发起模型请求（流式） | `api/chat/EnhancedAIService.kt:1121-1136` |
| 流式收集 + revision（savepoint/rollback） | `api/chat/EnhancedAIService.kt:1144-1224` |
| 异常与取消处理（`isSocketClosed` 判定） | `api/chat/EnhancedAIService.kt:1249-1280` |
| `enhanceToolDetection`（XML 标准化） | `api/chat/EnhancedAIService.kt:1335-1404` |
| `normalizeToolXml` / `isToolXmlBlock` | `api/chat/EnhancedAIService.kt:1411-1435` |
| ★ `detectAndRepairTruncatedToolRound`（截断修复） | `api/chat/EnhancedAIService.kt:1443-1500` |
| `buildTruncatedToolRepairSuffix` | `api/chat/EnhancedAIService.kt:1502-1597` |
| 部分开/闭标签补全 | `api/chat/EnhancedAIService.kt:1617-1710` |
| ★ `processStreamCompletion` | `api/chat/EnhancedAIService.kt:1712-2008` |
| 纯思考输出告警 | `api/chat/EnhancedAIService.kt:1770-1834` |
| 截断告警 + 本轮作废 | `api/chat/EnhancedAIService.kt:1886-1947` |
| ★ `handleToolInvocation` | `api/chat/EnhancedAIService.kt:2072-2188` |
| ★ `processToolResults`（含 token 阈值总结） | `api/chat/EnhancedAIService.kt:2192-2500` |
| → 递归回 `processStreamCompletion` | `api/chat/EnhancedAIService.kt:2458` |
| `getAvailableToolsForFunction`（CLI/FULL 分流） | `api/chat/EnhancedAIService.kt:2882-2945` |
| ★ `ToolExecutionManager.executeInvocations` | `api/chat/enhance/ToolExecutionManager.kt:504-663` |
| `extractToolInvocations`（XML 解析） | `api/chat/enhance/ToolExecutionManager.kt:306-350` |
| `checkToolPermission`（含 `deny_tool`） | `api/chat/enhance/ToolExecutionManager.kt:429-493` |
| 并行工具白名单（12 个） | `api/chat/enhance/ToolExecutionManager.kt:621-630` |
| `executeTool` + `ThreadLocal` 运行时上下文 | `api/chat/enhance/ToolExecutionManager.kt:668-707` |
| `aggregateToolResults`（流式聚合） | `api/chat/enhance/ToolExecutionManager.kt:710-734` |
| ★ `buildToolNotAvailableErrorMessage`（四类可纠正错误） | `api/chat/enhance/ToolExecutionManager.kt:739-791` |
| `ensureOwnLine`（注入块独占一行） | `api/chat/enhance/ToolExecutionManager.kt:62-65` |
| ★ `OrderedToolResults` + `ToolExecutionBatch` | `api/chat/enhance/OrderedToolResults.kt:8-47` |
| `ConversationMarkupManager`（结果 XML / 截断 / 图片链接） | `api/chat/enhance/ConversationMarkupManager.kt:19-130` |
| `ConversationService.normalizeConversationHistoryForModel` | `api/chat/enhance/ConversationService.kt:678-715` |
| XML 协议正则总表 | `util/ChatMarkupRegex.kt:10-82` |
| `FunctionType`（11 种分功能模型） | `data/model/FunctionType.kt:4-16` |
| 上下文/摘要默认值（阈值 0.70 / 16 条） | `data/model/ModelConfigData.kt:60-69` |
| 工具协议设计文档 | `docs/doc-src/feature-protocol/toolcall_xml_conversion.md:1-165` |
| 执行上下文（MessageExecutionContext） | `api/chat/EnhancedAIService.kt:436-462,965-971` |

### 6.3 工具系统 ★

| 内容 | 位置 |
|---|---|
| `AIToolHandler`（注册中心 / Hook / 执行入口） | `core/tools/AIToolHandler.kt:29-476` |
| 工具注册表 `ConcurrentHashMap` | `core/tools/AIToolHandler.kt:45` |
| `registerTool`（含 descriptionGenerator） | `core/tools/AIToolHandler.kt:179-208` |
| `getToolExecutorOrActivate`（包自动激活） | `core/tools/AIToolHandler.kt:303-359` |
| `executeTool` / `executeToolAndStream` | `core/tools/AIToolHandler.kt:363-475` |
| `interface ToolExecutor`（invoke / invokeAndStream / validateParameters） | `core/tools/AIToolHandler.kt:479-491` |
| ★ `interface AIToolHook`（7 个回调） | `core/tools/AIToolHook.kt:10-36` |
| ★ `ToolExecutionLimits`（4 个上限） | `core/tools/ToolExecutionLimits.kt:3-8` |
| `registerAllTools()`（182 处 registerTool） | `core/tools/ToolRegistration.kt:36` |
| `execute_shell` 注册 | `core/tools/ToolRegistration.kt:269-279` |
| 终端工具注册（7 个） | `core/tools/ToolRegistration.kt:304-402` |
| 沙箱脚本工具注册 | `core/tools/ToolRegistration.kt:510-560` |
| `run_ui_subagent` 注册 | `core/tools/ToolRegistration.kt:2648-2660` |
| ★ 工具 schema 单一事实源 | `core/config/SystemToolPrompts.kt:13,46-79` |
| 完整 schema 清单 | `core/config/SystemToolPromptsInternal.kt`（5992 行） |
| ★「改工具要同步 7 处」checklist | `docs/doc-src/architecture/DEFAULT_TOOLS_ARCH.md:29-153` |
| ★ 三级权限模型文档 | `docs/doc-src/feature-protocol/tool_permissions.md:9-40` |
| `ToolGetter`（按权限选实现族） | `core/tools/defaultTool/ToolGetter.kt:13-219` |
| 路径校验（android / linux / SAF） | `core/tools/defaultTool/PathValidator.kt:6-58` |
| ★ CLI 工具暴露降级 | `core/tools/climode/CliToolModeSupport.kt:18-69` |
| 工具包 PackageManager | `core/tools/packTool/PackageManager.kt:62-2736` |
| MCP 执行器 | `core/tools/mcp/MCPToolExecutor.kt`（610 行） |
| Skill 管理器 | `core/tools/skill/SkillManager.kt`（431 行） |

### 6.4 设备自动化 ★

| 内容 | 位置 |
|---|---|
| ★★ `PhoneAgent` 主循环 | `core/tools/agent/PhoneAgent.kt:355-593` |
| `AgentConfig(maxSteps=20)` | `core/tools/agent/PhoneAgent.kt:43` |
| `StepResult` / `ParsedAgentAction` | `core/tools/agent/PhoneAgent.kt:48-61` |
| 权限状态解析（ADB or higher / Shizuku） | `core/tools/agent/PhoneAgent.kt:62-100` |
| ★ `_executeStep`（截图→模型→解析→执行） | `core/tools/agent/PhoneAgent.kt:602-660` |
| ★ `parseAgentAction`（`do(...)`/`finish(...)` 文本协议） | `core/tools/agent/PhoneAgent.kt:691-724` |
| `parseThinkingAndAction`（`<think>`/`<answer>`） | `core/tools/agent/PhoneAgent.kt:667-689` |
| ★ `executeAgentAction`（Launch/Tap/Type/Swipe/Back/Home/Wait/Take_over） | `core/tools/agent/PhoneAgent.kt:960-1145` |
| Type 的「Ctrl+A→DEL→剪贴板→Ctrl+V」绕法 | `core/tools/agent/PhoneAgent.kt:1047-1087` |
| `removeImagesFromLastUserMessage`（只留最新截图） | `core/tools/agent/PhoneAgent.kt:949` |
| `ActionHandler`（工具实现注入） | `core/tools/agent/PhoneAgent.kt:762-1297` |
| 虚拟屏封装（App 侧） | `core/tools/agent/{ShowerController,ShowerServerManager,ShowerBinderReceiver,ShowerBinderRegistry,ShowerVideoRenderer,VirtualDisplayManager}.kt` |
| ★ Shower 协议与接入文档 | `showerclient/README.md`（§1-§7） |
| Shower 客户端库 | `showerclient/src/main/java/com/ai/assistance/showerclient/*.kt`（1150 行） |
| 虚拟屏 ShellRunner 注入 | `core/tools/system/shower/OperitShowerShellRunner.kt`（30 行） |
| 无障碍动作（五级） | `core/tools/system/action/{ActionManager,ActionListener,ActionListenerFactory,Standard/Admin/Debugger/Root/AccessibilityActionListener}.kt` |
| MediaProjection 截图 | `core/tools/system/{MediaProjectionCaptureManager,ScreenCaptureService,ScreenCaptureActivity}.kt` |
| Shizuku 授权/安装 | `core/tools/system/{ShizukuAuthorizer,ShizukuInstaller}.kt` |
| Root 授权 | `core/tools/system/RootAuthorizer.kt` |

### 6.5 Shell / 终端

| 内容 | 位置 |
|---|---|
| ★ `interface ShellExecutor` + `CommandResult` + `PermissionStatus` + `ShellProcess` | `core/tools/system/shell/ShellExecutor.kt:7-97` |
| ★ `ShellExecutorFactory`（缓存 / 择优 / 用户偏好 / 清缓存） | `core/tools/system/shell/ShellExecutorFactory.kt:22-147` |
| `AndroidShellExecutor`（严格模式，不静默降级） | `core/tools/system/AndroidShellExecutor.kt:59-127` |
| `ShellIdentity`（DEFAULT/APP/ROOT/SHELL） | `core/tools/system/AndroidShellExecutor.kt:139-144` |
| 五级实现 | `shell/{Root,Admin,Debugger,Accessibility,Standard}ShellExecutor.kt` |
| ★ `Terminal`（commandId 事件流 / hidden 执行 / Flow 版） | `core/tools/system/Terminal.kt:31-204` |
| 终端 APK 管理（独立包 + GitHub release） | `core/tools/system/OperitTerminalManager.kt:14-51` |
| 权限级别枚举 | `core/tools/system/AndroidPermissionLevel.kt`（34 行） |

### 6.6 本地模型

| 内容 | 位置 |
|---|---|
| `LlamaNative`（12 个 external + Grammar 3 个） | `llm/llama/src/main/java/com/ai/assistance/llama/LlamaNative.kt:1-83` |
| `LlamaSession.Config`（nThreads4 / nCtx2048 / nBatch512 / kvUnified true） | `llm/llama/src/main/java/com/ai/assistance/llama/LlamaSession.kt:9-19` |
| ★ GBNF 语法约束采样器 | `llm/llama/src/main/cpp/llama_jni_stub.cpp:84-116` |
| `llama_sampler_init_grammar_lazy_patterns` 调用点 | `llm/llama/src/main/cpp/llama_jni_stub.cpp:94-103` |
| `buildToolCallGrammarConfig` | `llm/llama/src/main/cpp/llama_jni_stub.cpp:527-533` |
| `nativeSetToolCallGrammar` / `Clear` / `ParseToolCallResponse` | `llm/llama/src/main/cpp/llama_jni_stub.cpp:335,351,359,639-651` |
| llama.cpp 上游固定 commit + 16KB 页支持 | `llm/llama/CMakeLists.txt:16-21,44` |
| `MNNLlmSession.create`（backend/thread/precision/memory/tmpPath） | `llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt:29-36` |
| MNN Session API（generateStream/Structured/chat/setConfig…） | `llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt:126-426` |
| `MNNLlmNative`（21 个 external） | `llm/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmNative.kt:7-192` |
| ★ MNN Provider 初始化策略（backend 映射 / 内存模式 / precision=low） | `api/chat/llmprovider/MNNProvider.kt:150-214` |
| MNN 模型市场与下载 | `data/mnn/MnnModelDownloadManager.kt:102-598` |
| `ApiProviderType`（39 个，含 MNN/LLAMA_CPP） | `data/model/ModelConfigData.kt:7-58` |

### 6.7 JS 引擎 / 工具桥 ★

| 内容 | 位置 |
|---|---|
| `JsEngine`（QuickJS 单线程 + 引擎实例） | `core/tools/javascript/JsEngine.kt:68-194` |
| `OperitQuickJsEngine` 实例化 | `core/tools/javascript/JsEngine.kt:189` |
| QuickJS Native 封装 | `quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/*.kt`（669 行） |
| ★ `JsToolManager`（4 引擎池） | `core/tools/javascript/JsToolManager.kt:25,52-62` |
| ★ 运行时引导模块清单 | `core/tools/javascript/JsLibraries.kt:12-60` |
| ★ `getJsToolsDefinition()`（`Tools.*` 全局对象） | `core/tools/javascript/JsTools.kt:4-1593` |
| `Tools.Files.*` 示例（含 `toolCall` 单通道、布尔转字符串） | `core/tools/javascript/JsTools.kt:6-120` |
| `run_ui_subagent` 的 JS 封装 | `core/tools/javascript/JsTools.kt:1181` |
| Java/Kotlin 反射桥 | `core/tools/javascript/JsJavaBridge.kt`（1332 行）、`JsJavaBridgeDelegates.kt`（2103 行） |
| Native 接口委托 | `core/tools/javascript/JsNativeInterfaceDelegates.kt`（1099 行） |
| Compose DSL 桥 | `core/tools/javascript/JsComposeDslBridge.kt`（921 行） |
| 工具包注册（JS 侧） | `core/tools/javascript/JsToolPkgRegistration.kt`（669 行） |
| 脚本执行入口 | `core/tools/javascript/ScriptExecutionReceiver.kt`（450 行） |
| 示例与类型契约 | `examples/*.ts`(48) / `examples/*.js`(49) / `examples/types/*.d.ts`(25) / `app/src/main/assets/packages/*.js`(31) |
| 脚本开发指南 | `docs/SCRIPT_DEV_GUIDE.md`、`docs/SCRIPT_DEV_SKILL.md` |

### 6.8 记忆 / 形象 / 工作流 / 集成

| 内容 | 位置 |
|---|---|
| ★ 记忆三步流水线 + 打分公式 | `docs/doc-src/architecture/memory_candidate_scoring_formula.md:1-50` |
| `MemoryRepository`（2600+ 行） | `data/repository/MemoryRepository.kt:55-2239` |
| 云端 embedding | `services/CloudEmbeddingService.kt` |
| ★ `AvatarEmotion`（7 种） | `core/avatar/common/state/AvatarEmotion.kt:1-27` |
| ★ `AvatarType`（6 种后端） | `core/avatar/common/model/AvatarType.kt:1-27` |
| ★ `AvatarController` 接口（含 `playTrigger`） | `core/avatar/common/control/AvatarController.kt:12-80` |
| 三工厂 | `core/avatar/common/factory/{AvatarControllerFactory,AvatarModelFactory,AvatarRendererFactory}.kt` |
| 6 种实现 | `core/avatar/impl/{dragonbones,webp,mp4,gltf,fbx,mmd}/` |
| 工作流 | `core/workflow/{WorkflowExecutor,WorkflowScheduler,WorkflowWorker,WorkflowSchedulerInitializer}.kt` |
| 工作流 Intent 触发协议 | `docs/doc-src/feature-protocol/workflow_intent_trigger.md` |
| 外部集成（HTTP / A2A / Intent / Tasker） | `integrations/{http,a2a,intent,tasker,externalchat}/` |
| 外部聊天协议文档 | `docs/doc-src/feature-protocol/{external_http_chat,external_a2a_server,external_intent_chat}.md` |
| APK/EXE 二次打包 | `core/subpack/{ApkReverseEngineer,ApkEditor,ExeEditor,ExeIconChanger,KeyStoreHelper}.kt` |
| 流式 Markdown 渲染架构（KMP + 插件） | `docs/doc-src/architecture/RENDERER_ARCH.md:1-30` |
| 运行时 Hook 注册表 | `core/chat/hooks/{ChatRuntimeHookRegistry,PromptHookRegistry,SummaryHookRegistry}.kt`；`core/chat/hooks/PromptTurn.kt` |

---

## 附：待验证清单

1. **`terminal/` 子模块内容为空** —— `OperitTerminalCore` 的进程模型、rootfs/容器（`proot` 还是别的）、pty 实现均**无法从本仓库确认**。`core/tools/system/Terminal.kt` 只暴露了 `TerminalManager` 的包装。相关结论（如「终端是独立 APK」）基于 `OperitTerminalManager.kt:15-17` 的包名与 GitHub release 拉取逻辑推断。
2. **`deny_tool` 标记的触发语义** —— `ToolExecutionManager.kt:458` 用 `invocation.rawText.contains("deny_tool")` 跳过权限检查，但未见文档说明「模型在什么情况下该写这个标记」，也未找到对应的提示词文本。**待验证**。
3. **`quickjs/README.md` 与代码不一致** —— README 说「还没接到 app 模块」，但 `app/build.gradle.kts:587` 已 `implementation(project(":quickjs"))`，且 `JsEngine.kt:189` 实际实例化 `OperitQuickJsEngine`。**以代码为准**，README 已过期。
4. **App 侧 `core/tools/agent/` 与 `showerclient/` 为何并存两套 Shower 封装** —— 文件高度同名（`ShowerController` / `ShowerServerManager` / `ShowerBinderRegistry` / `ShowerVideoRenderer`），疑为迁移残留，**未深究**。
5. **`enableToolCall` 在 MNN 侧的实际行为** —— 文档明确「MNN 当前仍未接入等价的原生 ToolCall 能力」（`toolcall_xml_conversion.md:154`），但未验证 MNN 模式下是「降级到提示词 XML」还是「完全不暴露工具」。
6. **`Proxy` 工具在 CLI 模式下 `tool_name` 是否强制要求 `packName:toolName` 限定名** —— `ToolExecutionManager.kt:172-183` 的代码暗示未限定名时直接放行，但 `isInvocationAllowedForRoleCard` 的完整约束链未逐条走查。
7. **`ChatRuntimeHookRegistry` / `PromptHookRegistry` / `SummaryHookRegistry` 三套 Hook 的注册与优先级** —— 只确认了存在与 `stage` 字符串（`"before_finalize_prompt"` / `"before_send_to_model"`），未读实现。
8. **工具总数口径** —— 报告中「182 处 `registerTool`」是 `ToolRegistration.kt` 内的**调用点计数**（含同一工具在不同分支的重复注册），去重后的工具名约 **160 个**。文档与代码均未提供官方口径。

---

*报告完 · 研究人：产品经理 许清楚（Xu） · 研究对象：Operit（LGPL-3.0，只读思路，净室重写）*
