# zafiro 参照仓库吃透报告

> 研究者：许清楚（Xu，产品经理） · 第 5 路
> 研究对象：`/root/.codebuddy/artifact/repos/zafiro`（main 分支，934 文件）
> 许可证：MIT — 可深度参考实现，但 AgentKit 侧按**净室重写**保持代码库一致性
> 一句话定位：**Android 原生 AI Agent（BYOK），能看屏、操控设备、跑 Shell / Python、接 MCP，并通过 LSPosed 接管系统语音助手**

---

## ① TL;DR

zafiro 是我看过的几个参照仓库里**工程成熟度最高、抽象最干净**的一个——它不是"能用"的 Demo，而是一套**按契约分层、每层都有明确不变量**的产品级 Agent 系统。

**三个最值得 AgentKit 抄的"设计原理"（不是代码）：**

1. **Agent 运行时被抽成了一个独立库层（okia）**，与 App 业务完全解耦。okia 只做一件事：**「模型 ↔ 工具」的回合循环**，它不认识 Android、不认识 Shell、不认识 Python。工具以 `ToolRegistry + ToolExecutor` 契约注入，扩展面统一收敛到 `Hooks`（10 个时机，Xposed 风格命名）。okia 的依赖只有 okhttp + kotlinx-serialization + coroutines。
   → AgentKit 现在 `AgentExecutor` 与 UI/业务混在一起，这是最大的结构性差距。

2. **终端被抽成「身份 × 后端」二维矩阵**（libterm）。`TerminalIdentity = User / Su / Shizuku / Ssh`，后端可插拔（libsu / shizuku / ssh）。会话身份在**开辟时固化**，后续传参不会静默提权。上层再套一个 `TerminalSessionPool`，用 4 位十六进制短句柄 + 每会话 Mutex + 异步态，把一个"终端"包装成可复用的 Agent 工具。
   → AgentKit 有 terminal + proot + Shizuku/虚拟屏，但**没有"会话身份"与"后端抽象"这一层**，权限与执行混在一起。

3. **权限模型是「权限 × API → 授予机制 → 通道链」三张正交表**。`PermissionSpec` 是全仓**唯一**的 `SDK_INT` 分叉点；`PermissionEngine` 是纯 Kotlin、不依赖 Android、可按通道链依次降级（ROOT_SHELL → SHIZUKU → SYSTEM_DIALOG → JUMP_SETTINGS）。加上执行期的 `ToolExecutionPreflight`（正则规则 + 静态路径扫描 + 用户审批）。
   → AgentKit 有危险操作审批与脚本沙箱（L10/L11），但**权限获取没有统一门面与降级链**。

**与 AgentKit 产品目标的匹配度（用户原话 6 项）：**

| 目标 | zafiro 有无 | 备注 |
|---|---|---|
| 能写代码（本地/云端） | 有（Python 3 via Chaquopy + Shell） | 云端靠 SSH 接 Claude Code |
| 本地模型 | **无** | 全 BYOK 云端，AgentKit 已有 llama.cpp 反超 |
| 写游戏 | 无 | 理论可用 Python 生成 |
| AI 视频/照片 | **无** | AgentKit 已有生图/视频工坊反超 |
| 做安装包 | 有（README 演示：Py 下载 APK + Root 安装） | 可直接对齐 |
| 推 GitHub 拉回 | 部分（Git 走 shell） | AgentKit 已有 CI 构建按钮反超 |

**结论**：zafiro 的价值在**Agent 运行时的工程抽象与终端/权限模型**，不在产品功能面。AgentKit 应"抄架构、补差距、不抄功能"。

---

## ② 架构分层地图

### 2.1 模块清单与规模（实测）

来源：`settings.gradle.kts:34-65`（模块声明）、实测 `find | wc -l`。

| 模块 | 文件 | 行数 | 职责 |
|---|---:|---:|---|
| `app` | 263 | 48,416 | 主应用：Compose UI、AgentRuntimeService、Xposed 钩子 |
| `agent-runtime` | 124 | 23,993 | **Agent 运行时**：LLM 调用、工具/Skill/MCP 执行、Python 运行时 |
| `libs/okia` | 86 | 16,484 | **Okia Agent 运行时基础库**（回合循环、协议、传输、MCP 客户端） |
| `ui-kit` | 57 | 7,163 | 共享 Compose 组件、LiquidScreen 壳、**自研导航** |
| `libs/libterm` | 90 | 7,813 | 终端库（core / runtime / 4 个后端） |
| `business/*` | 58 | 6,415 | api(契约) / agent / files / notification / permission / application |
| `remote-view` | 8 | 2,133 | 跨进程 / 悬浮窗 RemoteView（浮动球） |
| `store` | 13 | 1,001 | Store 持久化、IPC 桥（XIpcBridge） |
| `xposed-runtime` | 12 | 666 | Xposed 运行时、Hook 基类 |
| `libs/logging` | 10 | 225 | 日志库 |
| `xposed-api` | 6 | 210 | Xposed 事件类型、共享常量 |
| `xsettings` | 1 | 23 | 轻量配置模块（懒加载，业务按需依赖） |

`business` 子模块拆分（`settings.gradle.kts:46-51`）：`api`（对外公共契约）、`agent`（核心逻辑/会话编排/状态流）、`files`、`notification`、`permission`、`application`。**契约与实现分离，业务方只依赖 `api`**。

### 2.2 依赖分层（金字塔）

```
┌─────────────────────────────────────────────────────────┐
│  app  (Compose UI / AgentRuntimeService / Xposed mod)   │  48k
├─────────────────────────────────────────────────────────┤
│  business:agent  (会话编排、状态流)                        │
│  business:permission / files / notification / application │  6.4k
│  business:api  ← 纯契约，被上层依赖                        │
├─────────────────────────────────────────────────────────┤
│  agent-runtime  (LLM / 工具 / Skill / MCP / Python)       │  24k
├─────────────────────────────────────────────────────────┤
│  libs/okia   libs/libterm   libs/logging                 │  24.5k
│  (回合循环)   (终端:core/runtime/4后端)                     │
├─────────────────────────────────────────────────────────┤
│  ui-kit / remote-view / store / xposed-api / xposed-runtime │
└─────────────────────────────────────────────────────────┘
```

- `agent-runtime` 依赖（`agent-runtime/build.gradle.kts:52-58`）：`xposed-api`、`libs:logging`、`libs:okia`、`libs:libterm-runtime`、`business:api`、`business:permission`、`business:notification`。
- **okia 不反向依赖任何上层**——这是它能被净室借鉴的根本原因。

### 2.3 技术栈（实测）

| 类别 | 选型 | 出处 |
|---|---|---|
| 语言 | Kotlin（README 徽标 81.2k 行） | README_CN.md:16 |
| UI | Jetpack Compose + Material 3 Expressive | README_CN.md:106-107 |
| 液态玻璃 | AndroidLiquidGlass + Capsule（G2 圆角） | README_CN.md:108,114 |
| Agent 运行时 | **Okia**（作者自研，vendored 进 `libs/`） | README_CN.md:109 |
| Python | **Chaquopy 17.0.0 / Python 3.11**（独立 py 进程） | `agent-runtime/build.gradle.kts:4,38-45`；AGENTS.md:105 |
| 系统接管 | LSPosed + Xposed API | README_CN.md:111 |
| 终端/SSH | **libterm**（自研，4 后端） | README_CN.md:112 |
| MCP | `io.modelcontextprotocol:kotlin-sdk-server:0.14.0` + `ktor-server-cio:3.5.1` | `agent-runtime/build.gradle.kts:63-64` |
| 传输 | OkHttp 4.12.0（okia 的默认 HttpEngine） | `libs/okia/build.gradle.kts:44` |
| 序列化 | kotlinx-serialization-json 1.7.3（okia）/ 1.6.3（runtime） | 各自 build.gradle |
| SVG | coil-resvg | README_CN.md:113 |
| 构建 | AGP 9.1.1 / Kotlin 2.2.0 / JDK 17 / compileSdk 37 / **minSdk 26** / **arm64-v8a**（debug 追加 x86_64） | `agent-runtime/build.gradle.kts:1-36` |

> **与 AgentKit 对照**：同为 minSdk 26 + 仅 arm64-v8a + okhttp 4.12.0 + kotlinx-serialization 1.6.3。**技术底座几乎一致，零新增外部依赖的借鉴可行性极高。**

### 2.4 导航结构（自研，非 nav3）

`AGENTS.md:63` 明确：**"导航是自行实现的（ui-kit 的 nav 包），而不是使用 nav3。每个 entry 自带 viewmodel 生命周期"**。

- `ui-kit/src/main/java/com/niki914/uikit/infra/nav/NavigationController.kt:64-71` — `NavigationEntry<P>` 自带 `ViewModelStore`，实现 `ViewModelStoreOwner`。**每页独立 VM 作用域**。
- 同上 `:96-103` `push(page, direction)`；`:105-114` `pop` 时 `removedEntry.viewModelStore.clear()`（`:111`）。
- 同上 `:148-158` **300ms 导航防抖**（`debounceMillis`），防手速连点。
- `:82` `lastDirection`；`AGENTS.md:65` 说明 push 分正推与从左推（`onPushFromLeft`），返回按钮位置跟方向走。
- `NavigationController.kt:28-62` `TitleBarScrollState`：顶栏折叠信号**归属导航条目**，切页天然隔离、返回首帧即恢复。
- 顶栏只有左右两个图标槽，内容由页面通过 `PageChromeContribution` 覆盖（AGENTS.md:69）。

### 2.5 数据流：MVI（强制）

`AGENTS.md:37-43`：**"项目强制使用 MVI 心智：UI 只发意图、只取 VM 的 State，VM 通过更新 State 驱动 UI"**。

- 基类 `ui-kit/.../base/ComposeMVIViewModel.kt:24-113`：`<Intent, State, Effect>`。
  - `:30` `uiStateFlow: StateFlow<State>`（持久）
  - `:41-47` `_uiEffect: MutableSharedFlow<Effect>`（一次性，无 replay，`extraBufferCapacity = 1`）
  - `:50` `intentChannel = Channel<Intent>(Channel.UNLIMITED)`，`:57-67` init 启动消费
- **State vs Effect 判据**（AGENTS.md:41，值得原样抄进 AgentKit 规范）：*"换个时间再读还成立吗"*——列表数据、弹窗开关是 State；Toast、聚焦、退出页面是 Effect。弹窗最容易误判：*"触发弹窗是一次动作，但「弹窗开着」是界面当前的样子…所以它是 State"*（AGENTS.md:43）。

---

## ③ 核心功能逐项剖析

### 3.1 agent-runtime：Agent 运行时架构（核心重点）

#### 3.1.1 三层结构

```
AgentSessionEngine          ← 每 Agent 实例：独立会话树 + 工具注册表 + 流管道
   ├── OkiaSessionAssembler ← Okia 实例装配 + 协议映射 + Hooks 挂载
   ├── ToolRegistrySynchronizer ← 工具注册表同步（支持回合内热注册）
   ├── LlmStreamPipeline    ← 流执行管道与回合状态
   └── PromptComposer       ← 稳定层/易变层提示词组装
```

`agent-runtime/.../chat/AgentSessionEngine.kt:41` 类注释：*"每个 Agent 实例独立持有一份：独立的底层 Okia 会话树与 Hooks、独立的工具注册表、独立的流执行管道与回合状态"*。`:46` `val tenantId: String = UUID.randomUUID().toString()` —— **多租户隔离从入口就设计好了**。

`:109-206` `refresh()` 是核心编排：读配置 → 解析协议 → 读 MCP/自定义 Py 工具/内置工具开关/启用的 Skills → `toolManager.resolve()` → 获取/更新会话 → `update{}` 热更新配置 → `syncLocalTools()` → 调度 MCP 刷新 → `promptComposer.compose()` → 返回 `LlmRuntimeSnapshot`。

#### 3.1.2 工具三层：Builtin / CustomPy / MCP

`agent-runtime/.../chat/ToolManager.kt:20-42` `resolve()` 返回 `ResolvedTools(builtinTools, customPyTools, mcpServers)`。

**内置工具共 14 个**（`agent-runtime/.../chat/agentic/buildin/BuiltinToolRegistry.kt:27-46`）：

| 工具 | 文件 |
|---|---|
| `execute_python` | `impl/ExecutePythonBuiltin.kt` |
| `launch_app` | `impl/LaunchAppBuiltin.kt` |
| `py_meta_tools` | `impl/PyMetaToolsBuiltin.kt`（406 行，元工具） |
| `memory` | `impl/MemoryBuiltin.kt` |
| `notify` | `impl/NotifyBuiltin.kt` |
| `open_uri` | `impl/OpenUriBuiltin.kt` |
| `load_skill` | `impl/LoadSkillBuiltin.kt` |
| `shell` | `impl/ShellBuiltin.kt` |
| `ssh` | `impl/SshBuiltin.kt` |
| `find_installed_apps` | `impl/FindInstalledAppsBuiltin.kt` |
| `screen_operation_accessibility` | `impl/ScreenOperationAccessibilityBuiltin.kt` |
| `screen_operation_shell` | `impl/ScreenOperationShellBuiltin.kt` |
| `screenshot` | `impl/ScreenshotBuiltin.kt` |
| `view_image` | `impl/ViewImageBuiltin.kt` |

**BuiltinTool 抽象**（`buildin/BuiltinTool.kt:6-19`）——极简：
```kotlin
abstract class BuiltinTool {
    abstract val name: String
    open val description: String
    open val defaultEnabled: Boolean = false
    open val inputSchemaJson: String? = null   // JSON Schema，null = 无参数约束
    abstract suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult
}
```

**结果契约** `BuiltinToolResult`（`:30-86`）：`{ok, code, message, hint, field_errors, data}`。`hint` 字段很妙——**失败时告诉模型怎么修**（如 `LocalToolExecutor.kt:125-129` 返回 `LOCAL_TOOL_NOT_EXECUTABLE` + hint `"Check builtin_tool_flags or py_meta_tools configuration."`）。

**两种结果编码**：JSON 结构（`BuiltinToolResult.toJsonString()`）与文本协议（`#!tool-result` 头，见 `buildin/TextToolResultCodec.kt`）。`LocalToolExecutor.kt:134-165` `decodeOutcome()` 两种都能解，未知格式**保守成功、原文回喂**（`:163-164`）。

#### 3.1.3 与 UI 层的边界

- **UI 只认 `TurnEvent` 流与 `Conversation` 状态流**。`AgentSessionEngine.kt:238-322` `stream()` 返回 `Flow<LlmStreamEvent>`（`channelFlow` + `flowOn(Dispatchers.IO)`）。
- UI 与 Agent 之间隔了 `business:agent`（`AgentImpl.kt` 500 行 + `ConversationReducer.kt` 357 行 + `AgentStateReducer.kt`），负责把运行时事件**归约成 UI 状态**。
- **主进程限定**：AGENTS.md:103 *"agent 的对话功能是限定在主进程之内的…Agent 相关的调用逻辑依然用 Binder 包裹在主进程之内"*。跨进程只传**纯语义数据**（Thinking / Tools / Content），复杂数据结构从不穿过 Binder（AGENTS.md:47）。

#### 3.1.4 执行前检（Preflight）—— 安全模型

`agent-runtime/.../chat/agentic/ToolExecutionPreflight.kt:31-42` 注释概括得极好：

> 1. `evaluate`：用户配置的执行规则（正则）命中即拦下。保存期与执行期都调用。
> 2. `ensurePathAccess`：静态扫描文本里的绝对路径，命中沙箱外的公共区域（/sdcard、/storage 等）就阻塞申请一次存储权限。只在执行期调用；**结果不影响执行**——被拒绝、拿不到都照常放行，失败的读写由执行期自然暴露。
> *"用户不需要 review agent 的每行代码，一次权限弹窗的代价远小于漏报。"*

- **规则四种模式**（`:98-125`）：`ALWAYS`（拦）/ `LOCKED_ONLY`（锁屏时拦）/ `CONFIRM`（弹审批）/ `DISABLED`。`CONFIRM` 经 `AgentControl.decideApproval()` 拿 `ApprovalDecision.Allow / Deny / Abstain`（`:111-123`）。**`Abstain` = 会话无法请求用户 → 拒绝**（`:118-122`），这个"无法审批即拒绝"的默认很安全。
- **root 会话注入 `su` 候选**（`:271-277`）：*`"root 会话本身就等于「用了 su」，因此额外把 su 当成一个候选：默认规则里的 \bsu\b 因此能拦住 root 会话，用户删掉那条规则就等于放弃这层管控"*`。
- **Shell 语法感知的候选展开**（`:161-257`）：`shellLikeTokens()` 做引号/转义切词，`nestedShellPayloads()` 递归挖 `sh -c "..."` / `eval` 嵌套载荷（深度上限 8，`:276`）。**防绕过很扎实**。
- 路径正则（`:292-293`）：`(?<![\w.:/])/[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)*/?`，lookbehind 排除 URL。
- 沙箱根（`:300-309`）：`filesDir / cacheDir / noBackupFilesDir / /data/data/<pkg> / /data/user/0/<pkg>`。

#### 3.1.5 Python 运行时

- Chaquopy，**独立 py 进程**（AGENTS.md:105），`PyRuntime.kt` 363 行、`PythonWorkerService.kt` 113 行、`IPythonWorkerService.kt` 171 行。
- 预装 pip 包（`agent-runtime/build.gradle.kts:41-44`）：`requests==2.34.2`、`beautifulsoup4==4.15.0`（→ 网络搜索/网页读取开箱即用）。
- **`py_meta_tools`：造工具的元工具**（`impl/PyMetaToolsBuiltin.kt:29-33` 注释）：
  - write 先做**签名反射**（PyRuntime introspection）提取 `main` 的类型标注与 docstring，作为 description/schemaJson 缓存进 store；失败即拒绝并回传**可修复的错误信息**（*"agent 自调试闭环的一半"*）。
  - write 成功的条目由 `LocalToolExecutor` **回合内热注册**（D20 机制）。
  - test 用同一 harness 试跑草稿 code，write 前可先 test。
- 落地机制：`LocalToolExecutor.kt:187-228` `registerInlinePyIfWritten()` —— 解析 `py_meta_tools` 结果的 `ok/data`，校验工具名正则 `^[a-z][a-z0-9_]{0,63}$`（`:50`），写入 `inlineCustomPyTools` 并回调 host 注册。因为 `RealAgentLoop` **每段现取 `registry.snapshot()`**，所以**同回合下一轮模型请求即可见新工具**（`:31-34` 注释）。

#### 3.1.6 MCP

- **客户端**：okia 内 `mcp/` 包（`McpWire.kt` 434、`McpDiscovery.kt` 266、`McpExecutor.kt` 105、`AutoDetectMcpClient` / `DiscoveryStreamableHttpMcpClient` / `LegacyStreamableHttpMcpClient`）。支持 HTTP transport（Streamable HTTP 两种形态自动探测）。
- **服务端**：`agent-runtime/.../mcp/service/McpServiceImpl.kt:27-40` —— **Ktor CIO 内嵌服务器 + `mcpStatelessStreamableHttp`**，把本地工具暴露成 MCP 服务器，**供真机调试用**（AGENTS.md:111：*"release 版可提供 zafiro-mcp 用于真机调试…先连上 adb 并 forward 8791 端口"*）。这个"把手机变成 MCP Server 让 PC 调"的思路很实用。
- 刷新调度：`chat/McpRefreshScheduler.kt` 134 行 + `ToolRegistrySynchronizer.kt:107-122` `mcpServersSignature()`（name/url/headers/enabled 确定性序列化）**按签名变化决定是否刷新**。

#### 3.1.7 提示词组装（PromptComposer）

`agent-runtime/.../chat/agentic/PromptComposer.kt:19-236`。**稳定层 / 易变层二分**（`:22-27`）：

- **Stable tier**（`:32-47`，可跨回合缓存）：身份 → 环境块（私有存储路径白名单，`:125-138`）→ 工具上下文（`<builtin_tools>` / `<custom_py_tools>` 名字清单）→ Skills 上下文（仅当 `load_skill` 启用）→ 任务完成准则 → 工具使用强制 → 执行规则说明 → 记忆准则 → Skills 准则。
- **Volatile tier**（`:51-57`）：记忆快照，`══` 分隔 + `§` 分条，每会话注入。

**Skills 是"提示词清单 + 按需加载"**（`:104-119`）：系统提示词里只放 `<available_skills>` 的 id/name/description/dir 清单，标注 `## Skills (mandatory)`，强制模型 *"Err on the side of loading"*；真正内容由 `load_skill` 工具返回 `SKILL.md`。`LoadSkillBuiltin.kt:94-107` 双限制（2000 行 / 50KB）head 截断，**截断时在尾部附绝对路径**，让模型用 shell 自取全量。

**记忆准则**（`:201-222`）写得极好，可直接借鉴规则文本：*"Prioritize what reduces future user steering"*、*"do NOT save task progress… if a fact will be stale in a week, it does not belong in memory"*、*"Write memories as declarative facts, not instructions to yourself"*（`'User prefers concise responses' ✓` vs `'Always respond concisely' ✗`）。

### 3.2 okia 库：Agent 运行时基础库（86 kt，重点）

**它是什么**：不是 OkHttp 封装（虽然用了 OkHttp 做默认传输），而是**一个完整的、协议无关的 Agent 回合循环引擎**。作者自研，独立仓库 `github.com/niki914/okia`，vendored 进 `libs/`。

#### 3.2.1 门面 `Okia`（`libs/okia/.../Okia.kt:33-140`）

```kotlin
interface Okia {
    val conversation: StateFlow<Conversation>   // 协议无关，UI 观察它渲染全部内容
    val events: SharedFlow<TurnEvent>           // 一次性事件流
    suspend fun send(text, images, options, onEvent): TurnResult
    suspend fun stop()                          // kill-then-stop
    suspend fun rewind(entryId)                 // 原地移动 leafId，被跳过的尾部保留在树中
    suspend fun export(): SessionSnapshot
    suspend fun update(block: OkiaConfig.Builder.() -> Unit)
    suspend fun config(): OkiaConfig
    suspend fun refreshMcpTools(): McpRefreshResult
    suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot
    suspend fun close()
}
```

**并发契约**（`:26-30` 注释，写的极其明确）：*"活跃回合存在时，send 与任何改变会话状态的操作（rewind / update / close）都抛异常；stop 是唯一例外（取消路径）…无 fork：分支由下游 export() + open(restore) 自行实现，库只维护单棵对话树"*。

**关键设计**：`rewind()` + `export()` 让**会话是一棵树而不是一条列表**，回退/重新生成天然支持（README_CN.md:65 宣称"每一次对话都能进行回退与重新生成"）。

#### 3.2.2 回合循环 `RealAgentLoop`（`libs/okia/.../loop/RealAgentLoop.kt`，1091 行）

**主循环**（`:83-142`）：

```
TurnStarted → beforeInput → afterInput
while (true) {
    runSegment()            // 一次模型往返（含双层重试）
      → commit Assistant 整条消息（含 ToolCall）
      → if stopReason == Length && 有 toolCalls: 补失败结果闭合树尾（:121-124）
      → else if 有 toolCalls: executeTools() → history += results → continue
      → else: TurnCompleted → return Completed(Stop|Length)
}
```

**工具执行三段式**（`:610-742`）：
- **Phase 1（顺序）**（`:622-670`）：解析 + `beforeToolCall` 链。hook 可**改写参数**（write）或**阻断**（writeOutcome，短路后续 hook 且不执行）。
- **Phase 2（并发）**（`:675-703`）：先按调用顺序发 `ToolRunning`，再 `coroutineScope { async{}.awaitAll() }` 并发执行。
- **Phase 3（顺序保序）**（`:739-741`）：`afterToolCall`（可替换结果）→ `encodeToolResult` → **批量 commit + 事件**。

**"永不抛异常"契约**（`tooling/ToolExecutor.kt:11-18`）：`execute()` 永不抛、总产出 `ToolCallOutcome`；`onInterrupt()` 由 **executor 自身状态**判定是 `Interrupted`（未运行）还是 `Unknown`（可能已远程执行，永不重试）。
- executor 违反契约 → 回合 `Failed(ToolExecutionFailed)`（`RealAgentLoop.kt:674,705-715`）——*"业务方 bug 显形，不打包回喂模型"*。
- **未知工具 → Failure 结果回喂，回合继续**（`:624-641`）——*"模型可自纠（换名/放弃调用）"*。这与 executor bug 区分开：**模型能修的回喂，模型不能修的终止**。这个区分非常漂亮。

**取消语义**（`:69-70, 716-734`）：外部取消在 `NonCancellable` 内由 `onInterrupt` **补全待决工具结果**再重抛。*注释解释得极好*：*"不信任取消时 execute() 的返回值——工具可能被 kill 后返回假成功（如 terminal 会话关闭后同步返回 exited）"*。
- **不变量**：已 commit 的 Assistant 里的 ToolCall **必须**有配对 ToolResult（`:119-120`）——*"缺配对会让历史协议非法（严格 Provider 直接报错），UI 侧对应块永远转圈"*。

**双层重试**（分层，`:51-58`）：
1. **传输层重试**（`:297-367`）：网络错误 / 可重试状态码，`Retry-After` 优先于指数退避。
2. **回合层段首重试**（`:179-292`）：发送阶段耗尽或流中断 → **丢弃 partial（未 commit）、history 不变、重新 buildRequest**。*"重试安全性由「段内未 commit 任何产出」保证"*。
3. 不可重试（Auth/Quota/ContextOverflow/Parse）直接 `Failed`。

**idle 检测**（`:59-63, 418-456`）：`channel + select { onReceiveCatching / onTimeout }`。**任何 ProtocolEvent 到达重置计时**（agent 活跃度），**keep-alive（SseLine 层，不产出 ProtocolEvent）不重置**；**工具执行段不计入**。超时 → partial commit 进历史 + `TurnIdleTimeout` 事件 + `IdleTimeout` 终态（独立终态，不重试、不取消）。

**HTML 黑名单快速失败**（`:337-344`）：`Content-Type: text/html` 直接判 `Parse` 不可重试——*"如风控页"*。

**回调异常隔离**（`:397-403`）：`onEvent` 抛异常 → `HookFailed` **不可重试**。注释解释：*"否则配置 turnRetryPolicy 时同一次 LLM 请求被重发：重复计费 / 重复事件 / 掩盖业务错误"*。

#### 3.2.3 协议层（Protocol）

- `protocol/ChatProtocol.kt`（91 行，接口）+ 三个实现：
  - `OpenAIChatCompletionProtocol.kt`（511 行）
  - `OpenAIResponsesProtocol.kt`（617 行）
  - `AnthropicMessagesProtocol.kt`（620 行）
- `protocol/Compat.kt`（182 行）+ `OpenAIChatCompletionCompat` / `GoogleOpenAiCompat` → **同一协议实现 + 不同 Compat 适配多家**（`OkiaSessionAssembler.kt:176-186`）。
- `ProtocolCompatMapper`（67 行）三个方法：`buildRequest` / `parseStream` / `encodeToolResult` —— **协议差异全收敛到这三个点**。
- `ProtocolEvent`（68 行）：`TextDelta` / `ThinkingDelta` / `ThinkingSignature` / `ThinkingOpaquePayload` / `ToolCallStarted` / `ToolCallDelta` / `ToolCallReady` / `Completed` / `Error`。
- 传输：`transport/OkHttpEngine.kt`（215）、`SseLineParser` / `SseEventParser`、`HttpEngine` 接口（26 行，**可替换**）。

#### 3.2.4 工具线缆名消歧（`tooling/ToolWireName.kt:29-95`）

**问题**：MCP 工具名是用户可控字符串，可含 `.`、空格；OpenAI/Anthropic 对 function name 约束 `^[a-zA-Z0-9_-]{1,64}$`。

**解法**：**原始名（供 MCP 调用）与线缆名（供 Provider）分离**。
- 本地：`sanitize(name)` + 截断（`:41`）
- MCP：`mcp__<server>__<tool>`，server 段上限 32，tool 段按剩余预算截断（`:44-49`）
- 碰撞（`a.b` 与 `a_b`）→ **FNV-1a 64 位哈希后缀**消歧，`attempt` 迭代保证唯一（`:58-69, 86-94`）
- `ToolDescriptor`（`ToolRegistry.kt:45-51`）同时带 `name` 与 `wireName`，`ToolKind` 区分 `Local` / `Mcp(serverName)`（`:54-61`）

#### 3.2.5 扩展面 `Hooks`（`hooks/Hooks.kt:15-49`）

10 个时机，**Xposed 风格命名**（before/after 成对）：

| 时机 | 用途 |
|---|---|
| `beforeInput` / `afterInput` | 输入规范化 / 改写 |
| `beforeSerialization` / `afterSerialization` | **数据脱敏主战场** / 拿到 Provider 请求 |
| `beforeRequest` / `afterRequest` | HTTP 层兜底脱敏 / 埋点（只覆盖模型请求路径，unary 不触发） |
| `beforeToolCall` / `afterToolCall` | **审批 / 拦截 / 参数改写** / 审计埋点 |
| `beforeStop` / `afterStop` | **kill-then-stop 的 kill 步骤**（每回合至多一次）/ 统计 |

zafiro 挂的两个 hook（`OkiaSessionAssembler.kt`）：
- `fixIncompleteToolCallsHook`（`:194-210`）：`beforeSerialization` 补齐缺失的 ToolResult，防历史协议非法。
- `killToolResourcesHook`（`:253-260`）：`beforeStop` 时 `PyRuntime.kill()` + `TerminalSessionPool.closeAll()` + `SessionRegistry.clear()`。

#### 3.2.6 配置快照 `OkiaConfig`（`OkiaConfig.kt:19-42`）

不可变，热更新走 `update{}` 整体替换。字段：`endpoint / apiKey / model / temperature / maxTokens / 三个超时 / idleTimeoutSeconds / headers / retryPolicy / mcpServers / hooks / toolRegistry / httpEngine / imageLoader / imageSaver / supportsImages / thinkingLevel / proxy`。
- `toString()` **自动脱敏** apiKey 与敏感 header（`:45-51`）。
- `ThinkingLevel`（39 行）抽象思考强度，`null` = 不发送该字段（Provider 默认行为）。

### 3.3 自动化执行

#### 3.3.1 libterm：终端库（4 后端）

**核心抽象极简**（`libs/libterm/libterm-core/.../TerminalBackend.kt:5-18`）：
```kotlin
interface TerminalBackend {
    val identity: TerminalIdentity
    val output: Flow<OutputChunk>
    suspend fun start(openOptions): BackendStartResult
    suspend fun send(input: TerminalBytes): SendResult
    suspend fun close()
    suspend fun awaitExit(): TerminalFailure?
}
```

**身份枚举**（`TerminalIdentity.kt:3-8`）：`User / Su / Shizuku / Ssh`。

**门面**（`libs/libterm/libterm-runtime/.../LibTerm.kt:26-178`）：
- `runtime(context, scope, bufferConfig, configure)` → `LibTermRuntime`（`:27-42`）
- `openUserTerm(cwd)` / `openSuTerm(cwd)` / `openShizukuTerm(context, cwd)` / `openSshTerm(host, port, username, password)`（`:44-93`）
- `createRuntimeManager`（`:117-177`）：按 `TerminalIdentity` 选后端工厂（`:126-161`），Shizuku **必须有 Context**（`:148-150` 抛 `IllegalStateException`）。

**TerminalSession**（`libterm-core/.../TerminalSession.kt:16-45`）：`id` + `backend` + `clock` + `bufferConfig` + `scope`；`state: StateFlow<SessionState>`、`output: Flow<OutputChunk>`（`MutableSharedFlow(replay=0, extraBufferCapacity=64)`）。
- `start()`（`:50-142`）用 `lifecycleLock` 同步 + `CompletableDeferred` 处理**并发 start/close 竞态**（start 中被 close → 启动完立即关）。
- 状态机：`Closed / Starting / Running / Failed`。
- `latest(limit)` 取缓冲尾部（`:144-152`）。

**四个后端模块**：
| 后端 | 关键文件 |
|---|---|
| libsu（root） | `RealLibsuShellAdapter.kt`(269) / `LibsuTerminalBackend.kt`(178) / `LibsuPrivilegeProvider.kt`(51) |
| shizuku | `ShizukuUserServiceClient.kt`(339) / `LibTermShizukuShellUserService.kt`(182) / `ShizukuTerminalBackend.kt`(185) / `ShizukuPrivilegeAuthorizer.kt`(129) / `ShizukuPermissionRequester.kt`(56) |
| ssh | `JschSshShellAdapterFactory.kt`(190) / `SshTerminalBackend.kt`(181) / `JschFailureMapper.kt`(96) |
| runtime（命令层） | `DefaultTerm.kt`(186) / `TerminalCommandExecutor.kt`(242) / `AnsiStrippingTerminalOutputDecoder.kt`(222) / `LibTermSession.kt`(76) |

> 注：zafiro 自带的 `screen_operation` 走的是 **AccessibilityService + shell `input` 命令**，我在仓库里**没有**发现 VirtualDisplay / 虚拟屏实现（`remote-view` 是 RemoteView 悬浮窗，不是虚拟屏）。AgentKit 的"虚拟屏"能力是 zafiro 没有的——**此项 AgentKit 反超**。

#### 3.3.2 TerminalSessionPool（1329 行，`agent-runtime/.../shell/TerminalSessionPool.kt`）

把终端包装成**可复用的 Agent 工具**。核心数据结构（`:52-62`）：
```kotlin
private val sessions: MutableMap<String, TerminalSessionEntry>   // 会话表
private val asyncStates: MutableMap<String, AsyncState>          // 异步执行态
private val interactiveStates: MutableMap<String, InteractiveState>
private val executionLocks: MutableMap<String, Mutex>            // 每会话一把锁
private val pendingNotifications = mutableListOf<String>()       // 待排空通知
```

- **短句柄**：`PUBLIC_HANDLE_LENGTH = 4`、`PUBLIC_HANDLE_REGEX = ^[0-9a-f]{4}$`（`:49-50`），`MAX_HANDLE_GENERATION_ATTEMPTS = 64`（`:48`）。**给模型的是 4 位短 ID，不是内部 UUID**——省 token、防泄漏。
- 文件头 `:1` 有 `// githooks:ignore-file monitor-lock` —— 终端进程句柄池**刻意用细粒度监视器锁**保障原子状态切换（与项目"禁止监视器锁"的坏味道规则做了显式例外）。
- 输出截断：`DEFAULT_READ_MAX_BYTES` 与 `ToolOutputTruncator.DEFAULT_MAX_BYTES` 对齐（`:47-49`），**截断时导出全量到文件**。
- `pendingNotifications` → `AgentSessionEngine.kt:280` `drainPendingNotifications()` 注入下一轮前缀——**后台任务完成后主动通知模型**，而不是让模型轮询。

#### 3.3.3 shell 工具（`impl/ShellBuiltin.kt:20-257`）

- **持久会话**：默认复用 `alias="default"`，`workdir` / 环境变量 / 历史跨调用保持（`:17`）。
- **身份固化**：`identity: user / root / shizuku`（`:226-230` schema enum）。`identityMismatchOrNull()`（`:140-151`）——*"会话身份在开辟时固化，后续传 identity 不会提权。显式声明了冲突身份时报错，而不是静默复用一个身份不符的会话（用户以为提权了，命令其实仍以旧身份执行）"*。
- **按身份分池**（`:157-158, 206-208`）：`defaultAliasFor(identity)` —— user 用 `default`，root/shizuku 各占一个别名，*"这样 identity=root 真的会拿到 root shell，而不是静默复用 user 会话"*。
- **超时降级后台**（`:31` description）：超过 `timeout`（默认 30s）→ `status='running', timed_out=true`，用 `action='read'` 后续取。
- **Action**：`list / read / submit / write / close`（`:246`）。
- **僵尸会话清理**（`:102-105`）：登记项是僵尸（会话被回收）→ 释放别名重开，*"本地会话重建零成本"*。
- **无 PTY 明示**（`:36` 规则 3）：*"The local shell has no PTY, so Ctrl-C (\u0003) is NOT a real interrupt here"* —— 把运行时限制**写进工具描述**让模型知道，而不是让它瞎试。

#### 3.3.4 屏幕操控（Phone-Use）

- `accessibility/AccessibilityController.kt`（1037 行）：`IAccessibility` 接口（`:54+`）—— `windowRoot` / `performAction` / `dispatchGesture` / 屏幕捕获 / 节点动作 / 手势 / 按键注入。**shell 回退：root > shizuku > user**（`:51-52` 注释）。
- `accessibility/TreeFormatter.kt`（253）+ `PruningRules.kt`（140）+ `NodeModel.kt` + `SemanticToken.kt` —— 把 AccessibilityNodeInfo 树**剪枝并格式化**成给模型的文本。
- 两套工具：`screen_operation_accessibility`（节点级，首选）与 `screen_operation_shell`（坐标级，**回退**，`:10-19` 注释明确标注 *"FALLBACK method — prefer ScreenOperationAccessibilityBuiltin when possible"*）。
- **每次成功写操作后自动回传更新后的屏幕树**（`:16`）。
- 鼠标指针可视化：`animation/PointerCurveMath.kt`（460 行）+ `app/.../overlay/PointerOverlay.kt`（343）——README_CN.md:58 *"屏幕上的鼠标展示 Agent 的每一步操作"*。

### 3.4 权限模型（`business/permission`）

**三张正交表 + 一个纯 Kotlin 引擎**：

1. **`PermissionSpec`**（`PermissionSpec.kt:35-73`）—— **「权限 × API → 授予机制」，全仓唯一的 SDK_INT 分叉点**（`:31-32` 注释：*"全仓的 SDK_INT 比较只许住在这里"*）：
   ```kotlin
   sealed interface GrantMechanism {
       data object None                                  // 该 API 上概念不存在，恒可用
       data class Runtime(names, appOps)                 // pm grant + appops
       data class AppOp(op)                              // 只能 appops set
       data object Accessibility                         // settings secure 写值
   }
   ```
   - `NOTIFICATION`：<33 → None；≥33 → Runtime(POST_NOTIFICATIONS, op=POST_NOTIFICATION)（`:46-54`）
   - `STORAGE`：≥R → AppOp(MANAGE_EXTERNAL_STORAGE)；<R → Runtime(READ/WRITE_EXTERNAL_STORAGE)（`:57-67`）
   - `OVERLAY` → AppOp(SYSTEM_ALERT_WINDOW)（`:69`）
   - `ACCESSIBILITY` → Accessibility（`:70`）
   - `ROOT / SHIZUKU` → `null`（能力型，与版本无关）（`:71`）

2. **通道（Channel）与默认降级链**（`PermissionManager.kt:92-118`）：
   - `ROOT_SHELL` / `SHIZUKU` / `SYSTEM_DIALOG` / `JUMP_SETTINGS`
   - `OVERLAY, ACCESSIBILITY` → `[ROOT_SHELL, SHIZUKU, JUMP_SETTINGS]`
   - `NOTIFICATION, STORAGE` → `[ROOT_SHELL, SHIZUKU, SYSTEM_DIALOG, JUMP_SETTINGS]`
   - `ROOT` → `[ROOT_SHELL]`；`SHIZUKU` → `[SHIZUKU]`
   - 注释（`:97-98`）：*"有 root / Shizuku 时优先静默授权（shell 执行 pm grant / appops），不可用或命令被系统拒纳时降级到系统弹窗与跳设置页"*

3. **`PermissionEngine`**（`PermissionEngine.kt:9-63`）—— **纯 Kotlin，不依赖 Android**（`:6-7`）：
   - `status()`：任一 handler 报 GRANTED → GRANTED；否则取任一真实状态；全 UNKNOWN → UNKNOWN（`:17-27`）
   - `request(permission, channels)`：按链依次尝试，检查 `minSdk`、捕获异常为 `FAILED`，**成功即返回**，否则继续下一环（`:29-62`）。返回 `PermissionResult(permission, state, attempts)` ——**带完整尝试轨迹**，便于 UI 展示"我试了什么"。

4. **`PermissionManager` 门面**（`PermissionManager.kt:13-29`）：`status()`（静默查，同步）/ `request(p)` / `request(p, vararg channels)` / `applyScope(vararg channels): PermissionScope`（自定义链入口，挂起式不占线程等回调，`:27`）。

**Shell 代授权命令**（`ShellGrants.kt:22-93`）：
- `grantRuntime`：先 `pm grant <pkg> <name>`，再 `appops set <pkg> <op> allow`，**两条路都试**（不同 ROM 接受度不一致），每条后 `verify()` 复查真实状态（`:39-58`）。
- **`命令退出码不等于系统里的权限状态，因此每条命令后都用 verify 复查真实状态收尾`**（`:9-10` 注释）—— 这个认知很关键。
- 无障碍（`:77-93`）：`settings get secure enabled_accessibility_services` → 合并去重（`:95-103`）→ `settings put secure ...` + `settings put secure accessibility_enabled 1`。
- 状态语义：`GRANTED` / `DENIED_BY_USER`（命令被接受但未授权，让链继续降级）/ `FAILED`（一条都没跑起来）（`:16-21`）。

### 3.5 ui-kit（对照 AgentKit 自绘 Ak* 组件）

| 组件 | 文件 | 行数 |
|---|---|---:|
| `LiquidScreen`（页面壳） | `infra/LiquidScreen.kt` | 211 |
| `LiquidScreenState` / `LiquidScreenContentContext` | 同名 | 183 / 122 |
| `LiquidTopBar`（顶栏） | `infra/LiquidTopBar.kt` | 273 |
| `LiquidDialog`（弹窗） | `infra/LiquidDialog.kt` | 287 |
| `LiquidPopover` | `infra/LiquidPopover.kt` | 543 |
| `OptionSheet`（BottomSheet） | `infra/component/OptionSheet.kt` | 145 |
| `LiquidToggle` + 状态机 | `component/LiquidToggle.kt` / `LiquidToggleStateMachine.kt` | 376 / 180 |
| `LiquidButton` / `TintLiquidButton` | `component/` | 118 / 178 |
| `LiquidTextFieldContainer` | `component/` | 266 |
| Settings 家族（页面骨架/分组卡片/行/分隔线） | `SettingsSpecPageContent` / `SettingsListPageContent` / `SettingsListItem` / `SettingsItemSurface` / `SettingsDetailFormScaffold` / `SettingExpandableTextItem` / `SettingsToggleListItemCard` / `SwipeDismissSettingsItemCard` | 234/85/117/141/252/293/100/343 |
| `NavigationController` | `infra/nav/` | 190 |
| `ComposeMVIViewModel` | `base/` | 113 |
| `G2Shapes` | `infra/shape/` | 96 |
| `BaseTheme` | `base/` | 129 |
| 交互 | `InteractiveHighlight` / `DragGestureInspector` | 120 / 83 |

**约定**（AGENTS.md:57-81）：
- 导航、导航栏、弹窗、设置项都用 ui-kit 既定的那套，**不要另起一套**（`:59`）。
- 弹窗必须挂 `LiquidScreen` 内容树里，**返回键由弹窗自己接管**（`:75`）。
- 设置页统一用 `Settings*` 组件，**"如无明确要求，禁止手搓"**（`:81`）。
- 每个组件都有配套 `preview/` 预览（`SettingsInfraPreview`、`OptionSheetPreview`、`LiquidDialogPreview`、`LiquidScreenTitlePreview`）—— **预览驱动开发**。

### 3.6 store / business-api / xsettings：数据层

- **`store/XIpcBridge.kt:17-26`**：`StoreClient` 接口 —— `readStore` / `writeStore` / `mutateStore(storeId, path, valueJson)` / `postNotification`。支持 `WEB_SETTINGS_ID` / `LOCAL_SETTINGS_ID`（`:28-50`）。**同一份 JSON 既能本地读写又能跨进程 IPC 读写**。
- `store/StoreDescriptorRegistry.kt`（114）+ `XIpcStoreRepository.kt`（85）。
- **持久化策略**（AGENTS.md:96-99）：*对话列表用 Room 数据库；其他通过在沙箱内直接读写文件实现，主要通过多 Json 单元（见 XRepo API）*。
- `app/repo/XRepo.kt`（1549 行）—— 设置仓库，配 `LocalSettingsCodec.kt`（304）+ `SettingsDomainCodecs`。
- **`xsettings`** 只有 1 个文件 23 行（`settings.gradle.kts:42-43` 注释：*"本地配置读取口：给 app 模块以外的模块按需依赖，见 XSettings.kt 的「懒」规则"*）—— **极轻量的跨模块配置读取口**，避免底层模块依赖重量级 repo。
- 运行时配置网关：`agent-runtime/.../settings/RuntimeEnvironment.kt`（41）+ `RuntimeSettingsGateway.kt`（55）+ `RuntimeBridge.kt`（6）+ `RuntimeHostGateway.kt`（5）—— **agent-runtime 不直接读 app 的设置，而是经"宿主网关"注入**，保证运行时库可被别的宿主复用。

### 3.7 xposed 模块（Hook 能力）

- `app/.../mod/feat/oppo/`：`BreenoCardRenderer.kt`(408) / `BreenoConfigProvider.kt` / 多个 subhook —— 接管 **OPPO 小布助手**
- `app/.../mod/feat/hyper/`：`XiaoaiChatHook.kt` / `XiaoaiConfigProvider.kt` / `XiaoaiRenderSession.kt` + subhooks（`CaptureInputHook`、`CaptureResponseTargetHook`、`RenderTextStreamCardHook`、`BlockNativeTtsPlaybackHook`、`BlockNativeInstructionByWhitelistHook`）—— 接管**小爱同学**
- `app/.../mod/feat/AbstractAssistantHook.kt` / `HookTarget.kt` / `SubHook.kt` / `BaseConfigProvider.kt`
- `xposed-runtime`：`runtime/Hook.kt` / `Runtime.kt` / `RuntimeBootstrap.kt` + `util/`（ActivityHook、ContextHook、FloatWindowHook、ReflectionExtensions、Inspector、XTryExt、HookSideLoader）
- **架构约束**（AGENTS.md:47）：*"宿主架构采用分层治理：上层 `*Hook`（如 `BreenoHook`）为纯胶水调度层，严禁堆砌反射与类名硬编码"*
- **角色定位**（AGENTS.md:49）：*"我们把取代原生 agent 的功能称为 takeover / 接管。宿主是比较边缘的业务，在重要决策时，不应该为了宿主的业务而去妥协，应该牺牲宿主"*

### 3.8 remote-view（悬浮球）

- `floatingball/FloatingBallMorphCard.kt`(686) / `FloatingBallDetailCard.kt`(497) / `FloatingBallCard.kt`(158) / `FloatingBallSpecs.kt`(177) / `FloatingBallViewModel.kt`(199) + `FloatingBallGeometryTest`(116) / `FloatingBallViewModelTest`(282)
- `app/.../overlay/FloatingBallOverlayManager.kt`(579) + `PointerOverlay.kt`(343)
- 用途：README_CN.md:53 *"支持美观的悬浮窗和通知栏 UI，便于实时跟进 Agent"* —— **后台也能看到 Agent 在干什么**。

---

## ④ 「它怎么做 → 我们怎么适配」对照表

> 原则：**学原理、抄契约、重写实现**。以下每条都写清 zafiro 做法（含 file:line）+ AgentKit 落地方案 + 工作量。
> AgentKit 现状基线：单模块 `:app`，约 80 个 kt，`core/`（build / container / llm / mcp / media / workspace）、automation、memory、skill、terminal、tools、ui（Ak* 自绘组件）。

| # | 维度 | zafiro 怎么做（含 file:line） | AgentKit 怎么适配 | 工作量 |
|---|---|---|---|---|
| **A1** | **Agent 运行时抽象** | 抽出独立库层 okia（86 kt，依赖仅 okhttp+serialization+coroutines）：`Okia` 门面（`Okia.kt:33-140`）+ `AgentLoop`（`AgentLoop.kt:24-31`）+ 协议三实现 + `ToolRegistry`/`ToolExecutor` 契约（`ToolRegistry.kt:12-25` / `ToolExecutor.kt:11-18`）。**运行时不认识 Android**。 | **净室重写为 `core/agent/` 子包**（不拆模块，保持单模块一致性）：① `AgentLoop` 接口 + `RealAgentLoop`（回合循环，先做「流式 → 工具 → 回喂」主循环，不做树/rewind）；② `ToolRegistry` + `ToolExecutor`（`execute` 永不抛异常，`onInterrupt` 自判状态）；③ `ToolDescriptor{name, wireName, inputSchemaJson}`；④ `Hooks` 10 时机接口（先落地 `beforeToolCall`/`afterToolCall`/`beforeStop`/`beforeSerialization` 四个）。现有 `AgentExecutor` 改为调用 `AgentLoop`。 | **L（3-4 天）** |
| **A2** | **工具线缆名消歧** | `ToolWireName.kt:29-95`：64 长度上限、`mcp__<server>__<tool>`、FNV-1a 哈希消歧；`name`(原始，供 MCP) 与 `wireName`(供 Provider) 分离。 | 新增 `core/agent/ToolWireName.kt`（纯函数，~60 行）。**立刻解决**：AgentKit 接 MCP 后工具名含 `.`/空格会被 OpenAI/Claude 拒。先只做本地 `sanitize + truncate` + MCP 前缀，哈希消歧放 P1。 | **S（0.5 天）** |
| **A3** | **工具结果契约** | `BuiltinToolResult{ok, code, message, hint, field_errors, data}`（`BuiltinTool.kt:30-86`）；**失败带 `hint` 教模型怎么修**（`LocalToolExecutor.kt:125-129`）。两种编码：JSON + `#!tool-result` 文本头。 | 统一 AgentKit `tools/` 的返回结构为 `{ok, code, message, hint, data}`（现有各工具返回格式不一）。`hint` 字段对本地小模型尤其重要——**模型越弱越需要可修复的错误提示**。 | **M（1 天）** |
| **A4** | **未知工具 vs executor bug 的区分** | 未知工具 → Failure 回喂、**回合继续**（模型可自纠，`RealAgentLoop.kt:624-641`）；executor 抛异常 → 回合 `Failed`（`RealAgentLoop.kt:674,705-715`）。注释：*"模型能修的回喂，模型不能修的终止"*。 | 在 `RealAgentLoop` 实现中原样落地这个二分。AgentKit 目前遇到未知工具大概率直接报错中断——**改成回喂让本地小模型自纠，命中率会明显提升**。 | **S（含在 A1）** |
| **A5** | **取消时补全书树** | 取消在 `NonCancellable` 内调 `onInterrupt` 补全待决 ToolResult 再重抛（`RealAgentLoop.kt:716-734`）。不变量：*"已 commit 的 ToolCall 必须有配对 ToolResult，否则严格 Provider 直接报错、UI 永远转圈"*。 | 落地 `onInterrupt` + 补写结果。AgentKit 用户点"停止"后，历史里若留未闭合的 tool_call，下次请求会失败——**这是个真实线上 bug 的预防**。 | **S（0.5 天）** |
| **A6** | **双层重试** | 传输层（网络/状态码，Retry-After 优先）包在回合层段首重试内；重试安全性由"段内未 commit"保证（`RealAgentLoop.kt:179-292, 297-367`）。`onEvent` 回调异常判 **不可重试**（`:397-403`，防止重复计费）。 | 在 `RealAgentLoop` 内先做**传输层单层重试**（指数退避 + Retry-After），回合层重试放 P1。回调异常隔离这一条**必须一起做**。 | **M（1 天）** |
| **A7** | **流式 idle 超时** | `channel + select{onTimeout}`（`:418-456`）；只算收集段、工具执行不计；任何 ProtocolEvent 重置、keep-alive 不重置；超时 partial commit + 独立 `IdleTimeout` 终态。 | 新增 idle 检测（对**本地 llama.cpp 推理**尤其重要——本地模型卡顿/跑飞概率远高于云端）。超时阈值建议 60s。 | **S（0.5 天）** |
| **A8** | **HTML 内容类型快速失败** | `Content-Type: text/html` 直接判 `Parse` 不可重试（`RealAgentLoop.kt:337-344`，注释：*"如风控页"*）。 | 在 HTTP 响应前置校验加一行。成本低、收益高（国内 API 网关返回风控 HTML 页是常见故障）。 | **XS（0.1 天）** |
| **B1** | **终端后端抽象** | `TerminalBackend` 接口 5 方法（`TerminalBackend.kt:5-18`）+ `TerminalIdentity{User,Su,Shizuku,Ssh}`（`TerminalIdentity.kt:3-8`）+ 4 个可插拔后端（libsu/shizuku/ssh）+ `LibTerm` 门面（`LibTerm.kt:26-178`）。 | 新增 `core/terminal/backend/` 包：`TerminalBackend` 接口（start/send/close/awaitExit/output）+ `TerminalIdentity` 枚举 + `ProotBackend`（AgentKit 已有 proot 容器，包装成 backend）+ `ShizukuBackend`（现有 Shizuku 代码改造）。**让 AgentKit 的 proot 容器与 Shizuku 走同一套接口**。 | **M（1.5 天）** |
| **B2** | **会话身份固化 + 按身份分池** | 身份开辟时固化，冲突显式报错不静默提权（`ShellBuiltin.kt:140-151`）；user 用 `default`、root/shizuku 各占别名（`:157-158, 206-208`）。 | `TerminalSessionPool` 落地 `META_IDENTITY` + `identityMismatch` 检查。**AgentKit 有 Shizuku 提权路径，最怕的就是"以为提权了其实没有"**——这条是安全项。 | **S（0.5 天）** |
| **B3** | **终端会话池** | `TerminalSessionPool.kt:52-62`：会话表 + 异步态 + 每会话 `Mutex` + 待排空通知；**4 位十六进制短句柄给模型**（`:49-50`）；输出截断与 `ToolOutputTruncator` 对齐并导出全量（`:47-49`）。 | 新增 `core/terminal/TerminalSessionPool.kt`：短句柄（省 token）、每会话 Mutex（防并发串输出）、**后台任务完成后 `drainPendingNotifications()` 注入下一轮前缀**（`AgentSessionEngine.kt:280`）——这条能显著减少模型无意义轮询。 | **M（1.5 天）** |
| **B4** | **超时降级后台 + 持久会话** | 超时 → `status='running'` + 后续 `action='read'` 取（`ShellBuiltin.kt:31`）；cwd/环境变量跨调用保持（`:17`）；僵尸会话自动释放别名重开（`:102-105`）。 | shell 工具改造：cwd 持久 + 超时转后台 + `read` 轮询。**配合 AgentKit 的"推 GitHub→CI→拉回"长任务场景是刚需**。 | **M（1 天）** |
| **B5** | **把运行时限制写进工具描述** | 无 PTY 明示（`:36` 规则 3）、禁跑交互式命令（`:34` 规则 1）、超时建议（`:35` 规则 2）。 | **审视 AgentKit 现有每个工具的 description**，把运行时限制、坐标系来源、超时建议写进去。成本极低、对弱模型效果显著。 | **S（0.5 天，全量梳理）** |
| **C1** | **权限门面 + 降级链** | `PermissionManager` 4 方法（`PermissionManager.kt:13-29`）+ `PermissionEngine` 纯 Kotlin 按链降级（`:9-63`，带完整 `attempts` 轨迹）+ 默认链（`:92-118`）。 | 新增 `core/permission/`：`Permission` 枚举 + `Channel` 枚举 + `PermissionEngine`（纯 Kotlin，可单测）+ `PermissionManagerImpl`。AgentKit 现分散在各处的权限申请收敛进来。**先做 SHIZUKU / SYSTEM_DIALOG / JUMP_SETTINGS 三通道**（AgentKit 不一定需要 root shell 通道）。 | **M（1.5 天）** |
| **C2** | **唯一 SDK 分叉点** | `PermissionSpec.appLevelMechanism(permission, api)`（`PermissionSpec.kt:45-72`），注释：*"全仓的 SDK_INT 比较只许住在这里"*。 | 新增 `PermissionSpec`：`NOTIFICATION`(33) / `STORAGE`(R) / `OVERLAY` / `ACCESSIBILITY`。**把散落在各处的 `Build.VERSION` 判断收进来**。 | **S（0.5 天）** |
| **C3** | **"退出码≠权限状态"** | 每条 shell 授权命令后都 `verify()` 复查真实状态（`ShellGrants.kt:9-10, 39-58`）；`DENIED_BY_USER` 让链继续降级（`:16-21`）。 | 在 Shizuku 授权后**必须复查**再返回成功。AgentKit 若目前只看命令退出码，会有"显示已授权实际没授权"的假阳性。 | **S（0.5 天）** |
| **C4** | **执行规则 Preflight** | 规则 4 模式 ALWAYS/LOCKED_ONLY/CONFIRM/DISABLED；`Abstain` = 无法请求用户 → 拒绝（`ToolExecutionPreflight.kt:98-125`）；shell 语法感知 + 嵌套载荷挖掘（深度 8，`:161-257`）；root 会话注入 `su` 候选（`:271-277`）。 | 新增 `core/security/ExecutionRules.kt`（规则表 + 正则匹配 + 嵌套展开）。AgentKit L10/L11 已有危险操作审批，**把它从"硬编码黑名单"升级为"用户可配规则 + 4 种模式"**。shell 嵌套挖掘和 `su` 注入优先做。 | **M（1.5 天）** |
| **C5** | **静态路径扫描换权限** | `ensurePathAccess()`：扫绝对路径 → 命中 `/sdcard /storage /mnt /data` 且在沙箱外 → **阻塞申请一次**存储权限；**结果不影响执行，失败照常放行**（`:31-42, 134-144`）。注释：*"一次权限弹窗的代价远小于漏报"*。 | 在 shell/文件工具执行前调一次。**注意它刻意"不拦"**——别做成硬拦截，否则会误伤正常读写。 | **S（0.5 天）** |
| **D1** | **Skills：清单 + 按需加载** | 提示词只放 `<available_skills>` 清单（id/name/description/dir），真内容由 `load_skill` 返回；截断时附绝对路径让模型用 shell 自取（`PromptComposer.kt:104-119` + `LoadSkillBuiltin.kt:94-107`）。 | AgentKit `skill/` 改造：**系统提示词只放清单**，加 `load_skill` 工具（默认启用）。现有 weather/wordcount 两个 skill 内容短，改造成本低但**为后续大量 skill 铺路**（省 token 是本地小模型的关键）。 | **S（0.5 天）** |
| **D2** | **Skills 强制加载语义** | `## Skills (mandatory)` + *"Err on the side of loading — it is always better to have context you don't need"*（`PromptComposer.kt:107-114`）。 | 原样借鉴这段提示词措辞。措辞本身对弱模型命中率影响很大。 | **XS** |
| **D3** | **记忆准则文本** | `MEMORY_GUIDANCE`（`PromptComposer.kt:201-222`）：*"只存减少未来用户纠偏的事实"*、*"一周内会过期的不要存"*、*"陈述句而非祈使句"*（`'User prefers X' ✓` / `'Always do X' ✗`）。 | 直接借鉴进 AgentKit `memory/` 的系统提示词。**这套规则能防止记忆库被垃圾填满**。 | **XS（0.2 天）** |
| **D4** | **提示词稳定层/易变层二分** | `buildStableTier` / `buildVolatileTier`（`PromptComposer.kt:32-57`）。 | 拆开后**稳定层可被 Provider 端 prompt caching 命中**（省 token 省钱），易变层（记忆）每轮注入。 | **S（0.5 天）** |
| **D5** | **元工具：造工具的元工具** | `py_meta_tools`（`PyMetaToolsBuiltin.kt:29-33`）：write 先做签名反射提取 schema，失败回传可修复错误；write 成功**回合内热注册**（`LocalToolExecutor.kt:187-228`），因 loop 每段现取 `registry.snapshot()` 所以**下一轮即可见**。 | 对应 AgentKit"能写代码"目标：**让 Agent 自己造并注册 JS/Rhino 工具**（AgentKit 已有 rhino 1.7.14，零新增依赖！）。`ToolRegistry` 支持运行时注册是关键前置（A1）。 | **L（2-3 天，依赖 A1）** |
| **D6** | **MCP 客户端线缆名 + 自动探测** | `AutoDetectMcpClient` 包装 Legacy/Discovery 两种 Streamable HTTP；签名变化才刷新（`ToolRegistrySynchronizer.kt:107-122`）。 | AgentKit `mcp/` 已有，补：工具名走 A2 的 `ToolWireName.forMcp`、按配置签名决定刷新时机（避免每轮重 discover）。 | **S（0.5 天）** |
| **D7** | **MCP Server（反向）** | Ktor CIO 内嵌 + `mcpStatelessStreamableHttp`（`McpServiceImpl.kt:27-40`），**把手机变 MCP Server 供 PC 调试**（AGENTS.md:111）。 | **需新增 ktor 依赖 → 违反红线，不做**。但思路可记：若将来放开依赖，这是"PC 调试手机 Agent"的优雅方案。 | **不做** |
| **E1** | **MVI 基类** | `ComposeMVIViewModel<Intent, State, Effect>`（`ComposeMVIViewModel.kt:24-113`）+ State/Effect 判据（AGENTS.md:41-43）：*"换个时间再读还成立吗"*。 | 新增 `ui/base/AkMviViewModel.kt`（~60 行）。**并把那条判据写进 AgentKit 的开发规范注释**——能一次性消除大量"Toast 用 State 导致重复弹"类 bug。 | **S（0.5 天）** |
| **E2** | **自研导航：每页独立 VM 作用域** | `NavigationEntry` 自带 `ViewModelStore` 实现 `ViewModelStoreOwner`，pop 时 `clear()`（`NavigationController.kt:64-71, 105-114`）+ **300ms 防抖**（`:148-158`）。 | AgentKit 已有"返回栈导航"（任务 #71），补两点：每 entry 独立 `ViewModelStore`、push/pop **加防抖**（防手速连点导致重复入栈）。 | **S（0.5 天）** |
| **E3** | **UI 组件库纪律** | AGENTS.md:57-81：*"导航、导航栏、弹窗、设置项都用 ui-kit 既定的那套，不要另起一套"*、*"如无明确要求，禁止手搓"*；每个组件配 preview。 | **写进 AgentKit 的 Ak* 组件规范**：新增组件必须配 `@Preview`。Zhafiro 这条纪律是它 UI 一致性的根因。 | **XS（规范）** |
| **E4** | **悬浮球后台可见** | `remote-view/floatingball/*`(2.1k) + `FloatingBallOverlayManager`(579)。README_CN.md:53 *"便于实时跟进 Agent"*。 | AgentKit 任务 #69 已做"执行过程实时可见"，再补**悬浮球**（后台/锁屏跟进度）。对"推 GitHub→CI→拉回"这种分钟级任务价值高。 | **M（1.5 天）** |
| **F1** | **日志落盘** | AGENTS.md:107-113：调试构建额外落沙箱 `filesDir/logs/`，文件名 `<进程>-<MMdd-HHmmss>.log`，单文件 8MB 换下一个、最多 10 个。 | AgentKit 已有 `AgentLogger`，补**落盘 + 轮转**（8MB/10 个）。logcat 被冲掉时能 `adb pull` 出来。 | **S（0.5 天）** |
| **F2** | **会话树与回退** | `rewind(entryId)` + `export()`/`open(restore)`（`Okia.kt:55-63`）；会话是树不是列表。 | **P2**：AgentKit 已有聊天持久化（任务 #71）。若要"回退到某一轮重新生成"，需把存储从消息列表改成树。 | **L（P2，2-3 天）** |
| **F3** | **坏味道 githooks** | AGENTS.md:140：`.githooks/` 提交前拦（监视器锁、变化叙述注释、内联全限定名、绕过 Logger 的日志、日志 TAG 前缀），支持按模块清理与放行标记。 | **可抄思路**：AgentKit 加一个 pre-commit 检查（至少拦"绕过 AgentLogger 的 println/Log.d"）。 | **S（0.5 天，可选）** |
| **F4** | **ServiceRegistry 依赖注入** | AGENTS.md:134：*"在开辟大型业务时，优先考虑用 ServiceRegistry 来做依赖注入，避免在构造函数、方法签名里面堆砌太多字段"*；`requireService<>()` 应直接在函数体或成员变量处调用，**避免出现在构造函数/方法签名**（AGENTS.md:149-151）。 | AgentKit 单模块，可用一个轻量 `ServiceRegistry` object 替代手工传 Context/Application。**先只在新增代码用，不做存量重构**。 | **S（0.5 天，可选）** |
| **G1** | **Xposed 接管** | LSPosed 接管系统语音助手（`mod/feat/oppo`、`mod/feat/hyper`），Binder + AgentRuntimeService，IPC 只传纯语义数据（AGENTS.md:45-47）。 | **不抄**（需 Root + LSPosed，与 AgentKit 目标不符）。**只借鉴一条架构原则**（AGENTS.md:49）：*"边缘业务不应让主干妥协"* —— 写入开发规范。另外"**IPC 只传纯语义数据、复杂结构不穿 Binder**"（AGENTS.md:47）可直接用于 AgentKit 的 Service 通信。 | **原则借鉴** |
| **G2** | **虚拟屏** | zafiro 用 AccessibilityService + shell `input`，**未发现 VirtualDisplay 实现**（remote-view 是悬浮窗 RemoteView）。 | **AgentKit 已有虚拟屏 + Shizuku 自动化，此项反超，无需借鉴**。但 AccessibilityController 的**树剪枝/格式化**（`TreeFormatter.kt` 253 + `PruningRules.kt` 140）值得看——把节点树压成给模型的文本是省 token 关键。 | **L 可选（P2）** |

---

## ⑤ 优先行动清单

> 工作量单位：人天（基于 AgentKit 单模块、零新增依赖前提估算）。
> 排序依据：**对"Agent 运行时抽象 / 终端-shizuku backend / 权限模型"三大差距的弥补力度 × 实施成本**。

### P0 — 必做，直接补三大结构性差距（合计约 11-13 人天）

| 序 | 行动 | 对应 | 工作量 | 验收标准 |
|---|---|---|---|---|
| **P0-1** | **净室重写 Agent 运行时内核**：`core/agent/` 新增 `AgentLoop` 接口 + `RealAgentLoop`（回合主循环：流式收集 → 整条 commit → 工具并发执行 → 保序回喂）+ `ToolRegistry`/`ToolExecutor` 契约 + `ToolDescriptor{name,wireName,inputSchemaJson}` + `Hooks`（先落 4 个时机）。`AgentExecutor` 改为调用它。 | A1 | **3-4 天** | 现有 weather/wordcount 两个 skill 走新 loop 跑通；并发工具调用结果保序；停止后树尾闭合 |
| **P0-2** | **工具线缆名消歧**：`core/agent/ToolWireName.kt`（64 上限、sanitize+truncate、MCP `mcp__<server>__<tool>`）。 | A2 | **0.5 天** | 含 `.`/空格/超长的 MCP 工具名能正常上线缆并被正确回查 |
| **P0-3** | **工具结果统一契约**：`{ok, code, message, hint, data}`，`hint` 必填（失败时教模型怎么修）。 | A3 | **1 天** | 全部 `tools/` 下工具返回统一结构；单测覆盖 hint 生成 |
| **P0-4** | **终端后端抽象**：`core/terminal/backend/` 新增 `TerminalBackend` 接口 + `TerminalIdentity{User,Su,Shizuku,Ssh}` + `ProotBackend`（包装现有 proot）+ `ShizukuBackend`（改造现有 Shizuku）。 | B1 | **1.5 天** | proot 与 Shizuku 走同一接口，上层无感知切换 |
| **P0-5** | **终端会话池 + 短句柄**：`TerminalSessionPool`（4 位短句柄 + 每会话 Mutex + 异步态 + 待排空通知注入）。 | B3 | **1.5 天** | 多轮 shell 保持 cwd；并发不串输出；后台任务完成主动通知模型 |
| **P0-6** | **权限门面 + 降级链**：`core/permission/` 新增 `Permission` / `Channel` / `PermissionEngine`（纯 Kotlin，带 attempts 轨迹）/ `PermissionManagerImpl`；先做 SHIZUKU / SYSTEM_DIALOG / JUMP_SETTINGS 三通道。 | C1 | **1.5 天** | 一键申请走完降级链；`PermissionEngine` 有纯 JVM 单测 |
| **P0-7** | **唯一 SDK 分叉点**：`PermissionSpec`（NOTIFICATION/STORAGE/OVERLAY/ACCESSIBILITY × API），收拢散落的 `Build.VERSION` 判断。 | C2 | **0.5 天** | 全仓 `Build.VERSION.SDK_INT` 判断收敛到一处 |
| **P0-8** | **会话身份固化 + 按身份分池**：`META_IDENTITY` + identityMismatch 显式报错，user/root/shizuku 分池不串味。 | B2 | **0.5 天** | 声明 root 但实际 user 会话 → 明确报错而非静默执行 |
| **P0-9** | **取消时补全树 + 未知工具回喂**：`onInterrupt` 补写 ToolResult；未知工具回 Failure 让模型自纠（区别于 executor bug 直接终止）。 | A4 / A5 | **0.5 天** | 停止后历史可继续请求不报错；未知工具名回合能自恢复 |

### P1 — 应做，明显提升健壮性与体验（合计约 7-9 人天）

| 序 | 行动 | 对应 | 工作量 |
|---|---|---|---|
| **P1-1** | **双层重试 + 回调异常隔离**：传输层指数退避（Retry-After 优先）+ 回合层段首重试；`onEvent` 异常判不可重试（防重复计费）。 | A6 | 1 天 |
| **P1-2** | **流式 idle 超时**：`channel+select` idle 检测（60s），本地 llama.cpp 场景刚需。 | A7 | 0.5 天 |
| **P1-3** | **执行规则 Preflight 升级**：用户可配规则 + 4 模式（ALWAYS/LOCKED_ONLY/CONFIRM/DISABLED）+ shell 嵌套载荷挖掘（深度 8）+ root 注入 `su` 候选。把 L10/L11 硬编码黑名单升级为规则引擎。 | C4 | 1.5 天 |
| **P1-4** | **静态路径扫描换权限**：`ensurePathAccess()`（扫绝对路径 → 试申请存储权限 → **不拦执行**）。 | C5 | 0.5 天 |
| **P1-5** | **"退出码≠权限状态"复查**：Shizuku 授权后必 `verify()` 再报成功。 | C3 | 0.5 天 |
| **P1-6** | **Skills 改清单 + 按需加载**：提示词只放 `<available_skills>` 清单，新增 `load_skill` 工具（默认启用），截断附绝对路径。 | D1 / D2 | 0.5 天 |
| **P1-7** | **提示词稳定层/易变层二分 + 记忆准则文本**：借鉴 `MEMORY_GUIDANCE` 三条规则。 | D3 / D4 | 0.7 天 |
| **P1-8** | **shell 超时降级后台 + 持久会话 + 僵尸清理**：cwd 持久、超时转 `running`、后续 `read`。适配"推 GitHub→CI→拉回"长任务。 | B4 | 1 天 |
| **P1-9** | **工具 description 全量梳理**：把运行时限制（无 PTY、禁交互式命令、超时建议）写进描述。 | B5 | 0.5 天 |
| **P1-10** | **MVI 基类 `AkMviViewModel`** + 把 State/Effect 判据写进开发规范注释。 | E1 | 0.5 天 |
| **P1-11** | **导航防抖 + 每 entry 独立 ViewModelStore** | E2 | 0.5 天 |
| **P1-12** | **日志落盘轮转**（8MB × 10 个，`filesDir/logs/`） | F1 | 0.5 天 |

### P2 — 可做，锦上添花（合计约 5-7 人天）

| 序 | 行动 | 对应 | 工作量 | 备注 |
|---|---|---|---|---|
| **P2-1** | **元工具（造工具）**：让 Agent 用 Rhino 自造 JS 工具并回合内热注册（AgentKit 已有 rhino 1.7.14，**零新增依赖**）。 | D5 | 2-3 天 | 强依赖 P0-1；对齐"能写代码"目标 |
| **P2-2** | **会话树 + rewind**（回退到某一轮重新生成） | F2 | 2-3 天 | 需改持久化结构 |
| **P2-3** | **悬浮球后台可见** | E4 | 1.5 天 | 分钟级任务跟进度 |
| **P2-4** | **线缆名哈希消歧**（FNV-1a 后缀） | A2 | 0.3 天 | 工具量大后才需要 |
| **P2-5** | **Accessibility 树剪枝/格式化**借鉴 | G2 | 1 天 | 可选，AgentKit 已走虚拟屏 |
| **P2-6** | **轻量 ServiceRegistry** + **pre-commit 坏味道钩子** | F3 / F4 | 1 天 | 可选，只约束新增代码 |
| **P2-7** | **Ak\* 组件 preview 纪律**写入规范 | E3 | 0.1 天 | 规范类 |

### 明确不做

- **Xposed / LSPosed 接管**（G1）：需 Root + LSPosed，与 AgentKit 产品目标无关。仅借鉴两条原则（边缘业务不让主干妥协、IPC 只传纯语义数据）。
- **Chaquopy Python**（体积大、需新增依赖）：AgentKit 已有 **rhino 1.7.14**，走 JS 路线即可（见 P2-1）。
- **MCP Server / Ktor**（D7）：违反零新增依赖红线。
- **本地模型**：AgentKit 已有 llama.cpp，**反超** zafiro（zafiro 全 BYOK 云端）。
- **AI 视频/照片**：AgentKit 已有生图/视频工坊，**反超**。
- **虚拟屏**：AgentKit 已有，**反超**（zafiro 用 Accessibility + shell input）。

---

## ⑥ 代码位置索引

### 6.1 文档与构建
| 内容 | 路径 |
|---|---|
| 项目知识/架构约束/HARD GATE | `AGENTS.md`（`CLAUDE.md` 是其软链） |
| 中文 README（能力、技术栈、结构） | `README_CN.md` |
| 未完成项（含"多 Agent 架构"、"Replay 录制回放"） | `TODO.md` |
| 模块声明 | `settings.gradle.kts:34-65` |
| agent-runtime 依赖与 Chaquopy 配置 | `agent-runtime/build.gradle.kts:52-71` |
| okia 最小依赖 | `libs/okia/build.gradle.kts:39-51` |

### 6.2 okia（Agent 运行时基础库）— 借鉴优先级最高
| 内容 | 路径:行号 |
|---|---|
| `Okia` 门面接口 + 并发契约 | `libs/okia/.../Okia.kt:33-140` |
| `AgentLoop` 接口 / `TurnResult` sealed / `LoopRequest` | `libs/okia/.../loop/AgentLoop.kt:24-31 / 42-55 / 78-90` |
| **回合主循环** | `libs/okia/.../loop/RealAgentLoop.kt:83-142` |
| 段执行（双层重试） | `RealAgentLoop.kt:179-292` |
| 传输层重试 | `RealAgentLoop.kt:297-367` |
| 流收集 / idle 检测 | `RealAgentLoop.kt:371-413 / 418-456` |
| 协议事件处理 | `RealAgentLoop.kt:460-608` |
| 工具三段式（解析→并发→保序提交） | `RealAgentLoop.kt:612-742` |
| 结果提交 + afterToolCall | `RealAgentLoop.kt:746-777` |
| `Hooks` 10 时机 | `libs/okia/.../hooks/Hooks.kt:15-49` |
| `ToolRegistry` / `ToolDescriptor` / `ToolKind` | `libs/okia/.../tooling/ToolRegistry.kt:12-25 / 45-51 / 54-61` |
| **`ToolWireName` 消歧** | `libs/okia/.../tooling/ToolWireName.kt:29-95` |
| `ToolExecutor`（永不抛异常 + onInterrupt） | `libs/okia/.../tooling/ToolExecutor.kt:11-18` |
| `OkiaConfig`（不可变 + 脱敏 toString） | `libs/okia/.../OkiaConfig.kt:19-51` |
| `TurnEvent` sealed 事件协议 | `libs/okia/.../event/TurnEvent.kt:20-115` |
| 协议三实现 | `protocol/OpenAIChatCompletionProtocol.kt`(511) / `OpenAIResponsesProtocol.kt`(617) / `AnthropicMessagesProtocol.kt`(620) |
| 协议兼容映射（3 个收敛点） | `protocol/ProtocolCompatMapper.kt`(67) |
| OkHttp 传输引擎 | `transport/OkHttpEngine.kt`(215) |
| MCP 客户端 | `mcp/McpWire.kt`(434) / `McpDiscovery.kt`(266) / `AutoDetectMcpClient.kt`(43) |

### 6.3 agent-runtime
| 内容 | 路径:行号 |
|---|---|
| `AgentSessionEngine`（每 Agent 实例） | `agent-runtime/.../chat/AgentSessionEngine.kt:41-355` |
| `refresh()` 核心编排 | `AgentSessionEngine.kt:109-206` |
| `stream()` 流出口 | `AgentSessionEngine.kt:238-322` |
| `OkiaSessionAssembler` + 协议映射 | `chat/OkiaSessionAssembler.kt:47-261 / 164-186` |
| 两个 hook（补树 / kill 资源） | `OkiaSessionAssembler.kt:194-210 / 253-260` |
| `ToolManager.resolve()` | `chat/agentic/ToolManager.kt:20-42` |
| `LocalToolExecutor`（ToolExecutor 适配） | `chat/agentic/LocalToolExecutor.kt:37-229` |
| py 回合内热注册（D20） | `LocalToolExecutor.kt:187-228` |
| `BuiltinTool` 抽象 / `BuiltinToolResult` | `chat/agentic/buildin/BuiltinTool.kt:6-19 / 30-86` |
| 14 个内置工具注册表 | `chat/agentic/buildin/BuiltinToolRegistry.kt:27-46` |
| **`ToolExecutionPreflight`** | `chat/agentic/ToolExecutionPreflight.kt:43-311` |
| `ToolRegistrySynchronizer` | `chat/ToolRegistrySynchronizer.kt:19-123` |
| **`PromptComposer`**（稳定/易变层、Skills、记忆准则） | `chat/agentic/PromptComposer.kt:19-236` |
| `TerminalSessionPool` | `chat/agentic/shell/TerminalSessionPool.kt:39+` |
| `ShellBuiltin` | `chat/agentic/buildin/impl/ShellBuiltin.kt:20-257` |
| `LoadSkillBuiltin` | `chat/agentic/buildin/impl/LoadSkillBuiltin.kt:15-129` |
| `MemoryBuiltin` | `chat/agentic/buildin/impl/MemoryBuiltin.kt:17+` |
| `PyMetaToolsBuiltin`（元工具） | `chat/agentic/buildin/impl/PyMetaToolsBuiltin.kt:29-45` |
| `AccessibilityController`（Phone-Use） | `chat/agentic/accessibility/AccessibilityController.kt:54+` |
| 屏幕操作 shell 回退 | `chat/agentic/buildin/impl/ScreenOperationShellBuiltin.kt:10-71` |
| MCP Server（Ktor） | `mcp/service/McpServiceImpl.kt:27-40` |
| 运行时配置网关 | `settings/RuntimeEnvironment.kt`(41) / `RuntimeSettingsGateway.kt`(55) |

### 6.4 libterm
| 内容 | 路径:行号 |
|---|---|
| **`TerminalBackend` 接口** | `libs/libterm/libterm-core/.../TerminalBackend.kt:5-18` |
| **`TerminalIdentity`** | `.../TerminalIdentity.kt:3-8` |
| `TerminalSession`（状态机/缓冲/并发 start-close） | `.../TerminalSession.kt:16-152` |
| `LibTerm` 门面（open*Term / backendFactory） | `libterm-runtime/.../LibTerm.kt:26-178` |
| Shizuku 后端 | `libterm-backend-shizuku/.../ShizukuTerminalBackend.kt`(185) / `internal/ShizukuUserServiceClient.kt`(339) / `LibTermShizukuShellUserService.kt`(182) / `ShizukuPrivilegeAuthorizer.kt`(129) |
| libsu 后端 | `libterm-backend-libsu/.../LibsuTerminalBackend.kt`(178) / `RealLibsuShellAdapter.kt`(269) |
| SSH 后端 | `libterm-backend-ssh/.../SshTerminalBackend.kt`(181) / `JschSshShellAdapterFactory.kt`(190) |
| 命令执行层 | `libterm-runtime/.../internal/TerminalCommandExecutor.kt`(242) / `DefaultTerm.kt`(186) / `AnsiStrippingTerminalOutputDecoder.kt`(222) |

### 6.5 permission（权限模型）
| 内容 | 路径:行号 |
|---|---|
| **`PermissionManager` 门面** | `business/permission/.../PermissionManager.kt:13-29` |
| 默认降级链 | `PermissionManager.kt:92-118` |
| **`PermissionEngine`（纯 Kotlin）** | `business/permission/.../PermissionEngine.kt:9-63` |
| **`PermissionSpec`（唯一 SDK 分叉点）** | `business/permission/.../PermissionSpec.kt:35-73` |
| `GrantMechanism` sealed | `PermissionSpec.kt:9-28` |
| **`ShellGrants`（pm grant / appops / settings）** | `business/permission/.../ShellGrants.kt:22-93` |
| Shizuku / Root 通道 | `ShizukuHandler.kt`(261) / `RootShellHandler.kt`(99) |
| 系统弹窗 / 跳设置 | `UiHandlers.kt`(208) |

### 6.6 ui-kit / store / xposed / remote-view / app
| 内容 | 路径:行号 |
|---|---|
| **`NavigationController`**（每 entry 独立 VM + 防抖） | `ui-kit/.../infra/nav/NavigationController.kt:64-190` |
| **`ComposeMVIViewModel`** | `ui-kit/.../base/ComposeMVIViewModel.kt:24-113` |
| `LiquidScreen` / `LiquidDialog` / `OptionSheet` | `ui-kit/.../infra/LiquidScreen.kt`(211) / `LiquidDialog.kt`(287) / `component/OptionSheet.kt`(145) |
| Settings 组件家族 | `ui-kit/.../infra/component/`（`SettingsSpecPageContent`234 / `SwipeDismissSettingsItemCard`343 / `SettingExpandableTextItem`293 …） |
| `XIpcBridge.StoreClient` | `store/.../XIpcBridge.kt:17-26` |
| Xposed 接管（小布） | `app/.../mod/feat/oppo/BreenoCardRenderer.kt`(408) |
| Xposed 接管（小爱） | `app/.../mod/feat/hyper/XiaoaiChatHook.kt` + `subhooks/` |
| Xposed 运行时基类 | `xposed-runtime/.../runtime/Hook.kt` / `Runtime.kt` / `RuntimeBootstrap.kt` |
| 悬浮球 | `remote-view/.../floatingball/FloatingBallMorphCard.kt`(686) / `FloatingBallViewModel.kt`(199) |
| 悬浮球管理 / 指针 | `app/.../overlay/FloatingBallOverlayManager.kt`(579) / `PointerOverlay.kt`(343) |
| `AgentRuntimeService` / Client / IPC | `app/.../runtime/service/AgentRuntimeService.kt`(674) / `client/AgentRuntimeClient.kt`(370) / `ipc/IAgentRuntimeService.kt` |
| 设置仓库 | `app/.../repo/XRepo.kt`(1549) + `LocalSettingsCodec.kt`(304) |
| 极轻量跨模块配置口 | `xsettings/XSettings.kt`(23) |

---

## 附：待验证项

以下为本次读码未能完全确认，标记为**待验证**（不影响上述结论）：

1. `TerminalSessionPool` 内部 `mapIdentity()` 对 `shizuku` 无 Context 时的具体降级路径（`TerminalSessionPool.kt:63-70` 仅读到开头）。
2. `remote-view` 的 `RemoteView` 是否用于**宿主进程（Breeno/小爱）渲染卡片**，还是仅主进程悬浮窗——`FloatingBall*` 均在主进程，但 `BreenoCardRenderer` 在宿主侧，二者桥接方式未深挖。
3. `store` 的 `XIpcBridge` 与主进程 `XRepo` 的读写双通道一致性（谁拥有写权限、冲突如何解决）未完整追踪。
4. okia 的 `refreshMcpTools()` 在 `Okia.kt:71-75` 注释写"MCP 推迟 T9，当前实现抛未实现"，但 `mcp/` 包已有完整实现——**注释可能过时**，实际是否可用需跑测试确认（`libs/okia/src/test/.../mcp/McpDiscoveryTest.kt` 405 行存在，倾向于**已实现**）。
5. `business/agent/ConversationStore_Tmp.kt`（61 行）带 `_Tmp` 后缀，疑似过渡态，未纳入分析。

---

*报告结束 · 所有事实均来自实际读码并标注 `路径:行号`*
