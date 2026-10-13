# Coomi 吃透报告 —— 参照仓库研究第 1 路

> 研究对象：`/root/.codebuddy/artifact/repos/coomi`（main 分支，Apache-2.0）
> 研究人：产品经理 许清楚（Xu） · 用途：为 AgentKit 改造提供「设计原理 → 我们怎么适配」
> 所有结论均来自实际读码，标注 `路径:行号`；推测或未验证项标注「待验证」。

---

## ① TL;DR（给主理人的 8 句话）

1. **Coomi 不是「一个 Android App」，是「Termux 壳 + Rust 引擎 + Vue 前端」三件套**：Android 层几乎不写业务逻辑，只负责跑引擎进程、宿主 WebView、提供无障碍/Shizuku 能力；**所有 Agent 逻辑在 Rust**（`apps/coomi-rs`，约 5.2 万行 Rust），**所有 UI 在 Vue**（`apps/web`，约 2.4 万行 TS/Vue）。
2. 这套架构对 AgentKit **不可直接搬**（我们是 Kotlin 单模块 + Compose 原生 UI，且红线是零新增依赖），但它的**协议设计、容错策略、能力建模**极具参考价值——这些是与语言无关的「设计知识」。
3. **最值得抄的三件事**：① Provider 的**多协议归一化 + 400 错误自降级重试**（`provider.rs:1089-1178`）；② Agent 循环的**工具调用指纹去重 + 失败熔断**（`agent.rs:641-708`）；③ **Markdown 白名单消毒 + 分块渲染 + 60ms 节流**（`utils/markdown.ts:14-105`、`MessageBubble.vue:1-11`）。
4. **Coomi 的短板恰好是 AgentKit 已做的**：Coomi **没有真正的 AI 生图/视频**——`capabilities.rs:101` 只有 `image_generation` 一个**能力标记**（按模型名 `dall-e/gpt-image/imagen` 推断，`:132`），全仓无生图/视频调用实现。AgentKit 已有 `core/media/providers/` 四家（OpenAI/DashScope/MiniMax/Volc），这块**不用向它学，反而要守住领先**。
5. Coomi 的「写游戏/做安装包」靠 **CoomiDev 自定义迭代**：内置 Skill + 隔离工作区 + 版本化 Build Kit（`catalogs/src/lib.rs:194`、`catalogs/coomi-custom-iteration.md`），思路是「给 Agent 一套构建链脚本 + 隔离目录」，而非 App 内建构建器——与 AgentKit 的 `core/build/`（推 GitHub→CI→拉回）是**两条不同路线**，AgentKit 的云端 CI 路线更轻，**建议保留现状**。
6. **设备自动化**：Coomi 用**文件队列**做无障碍指令桥（无障碍服务读 `*.cmd.json`、写回结果，`CoomiAccessibilityService.java:134/154/237`），并用 `environment="shizuku"` 让 `shell` 工具跑 Android 本机 shell（`tools/src/shizuku.rs:35`、RELEASE_NOTES_v1.5.1o3.md）。AgentKit 已有 `AutomationBridge` + `AgentAccessibilityService`，**Coomi 的「文件队列」模式对我们价值有限**（我们是同进程直接调用），但 **`shell`/`local_shell` 的 Shizuku 通道**值得补。
7. **本地模型**：Coomi 的 `local_model` 工具是**外部命令兜底**——调用 `ollama`/`llama.cpp`，没装就报「怎么装」（`tools/src/lib.rs:2325-2337`）。AgentKit 是 **JNI 直连 llama.cpp**（`core/llm/local/LocalLlamaClient.kt`），**比 Coomi 深一层，不用改**。
8. **一句话结论**：向 Coomi 学「**Provider 容错与能力建模**」「**Agent 循环的反卡死机制**」「**聊天渲染的消毒与性能**」三块；**不学**媒体生成、本地模型、构建链（我们已更强或路线不同）。

---

## ② 架构分层地图

### 2.1 Monorepo 全貌

```
coomi/
├─ apps/
│  ├─ coomi-app/          # Android 壳：Termux 分支（namespace com.termux）
│  │  ├─ app/                    # settings.gradle:29 → :app
│  │  ├─ termux-shared/          # settings.gradle:32
│  │  ├─ terminal-emulator/      # settings.gradle:35
│  │  └─ terminal-view/          # settings.gradle:38
│  ├─ coomi-rs/           # ★ Rust 智能体引擎（workspace，8 个 crate）
│  │  ├─ engine/         # 会话/上下文/Agent 循环（agent.rs 2109 行）
│  │  ├─ services/       # Provider 适配、MCP、记忆、Git/AI-Git、能力探测
│  │  ├─ tools/          # 内置工具集（lib.rs 3755 行）+ shizuku/patch/agents/processes
│  │  ├─ security/       # 访问策略 + Hook 钩子
│  │  ├─ ui/             # `coomi serve`：本地 HTTP/WS 服务（web.rs 11780 行）+ TUI
│  │  ├─ catalogs/       # 内置 Skill/MCP 目录（skills.json / mcp.json）
│  │  ├─ telemetry/      # 匿名统计
│  │  └─ experience/     # 体验层
│  └─ web/               # ★ Vue 3 + Pinia 对话前端（打包为 web.zip 内嵌）
├─ coomi-deck/           # 独立 Vite+TS 控制台（index.html / src/views）
├─ extensions/coomi-life # 「数字生命」扩展
├─ packages/tokens/      # 设计令牌
├─ server/               # feedback / site / stats / testrig（Node）
├─ tools/                # Python 部署测试脚本 + mobile-build
└─ deploy/ scripts/ docs/
```

Gradle 侧只暴露 4 个模块：`settings.gradle:26-38`。注意 **`apps/coomi-rs` 根本不在 Gradle 里**——Rust 引擎编译成原生二进制（`coomi`），由 Android 层拉起，通过 **本地 HTTP/WebSocket** 与 Vue 前端通信。

### 2.2 技术栈（实测）

| 层 | 技术 | 证据 |
|---|---|---|
| Android 壳 | Java（非 Kotlin）、Termux fork、namespace `com.termux` | `apps/coomi-app/app/build.gradle:37` |
| Android 依赖 | androidx + Material + **Markwon**（Markdown）+ **Shizuku API 12.2.0** | `build.gradle:56-68`（Shizuku `:67-68`） |
| Android 限制 | **targetSdk 必须 ≤ 28**，否则 Termux bootstrap 的 `usr/bin/bash` 被 EACCES 拦 | `build.gradle:45-49` |
| 引擎 | Rust **edition 2024** / MSRV 1.85 / toolchain **1.95.0** | `Cargo.toml:9-13`、`rust-toolchain.toml:2` |
| 引擎依赖 | tokio、reqwest(rustls)、axum(ws)、serde_json、**wasmi 0.31**(WASM 沙箱)、pulldown-cmark | `Cargo.toml:16-44` |
| 引擎质量闸门 | `unsafe_code = "forbid"`、`unwrap_used = "deny"`、`todo = "deny"` | `Cargo.toml:46-51` |
| 前端 | Vue 3.4 + Pinia 2 + vue-router 4 + **marked 12** + gsap + **vue-virtual-scroller** | `apps/web/package.json:14-24` |

> ⚠️ **架构红线提醒**：Coomi 的 `targetSdk ≤ 28` 是被 Termux bootstrap 逼出来的历史包袱。AgentKit 的 `minSdk 26` + 正常 targetSdk 是**更健康的状态**，不要为抄 Coomi 而降级。

### 2.3 导航结构（前端为主）

Android 壳只提供 8 个原生 Activity（`AndroidManifest.xml:111-154`：`CoomiLauncher/Setup/Backup/Feedback/HomeSetting/Appearance/Dashboard`），**主导航全在 Vue router 里**：

- `views/ChatView.vue`（352 行，会话主界面，虚拟滚动）
- `views/ProvidersView.vue` / `ProviderDetailView.vue`（861 行，Provider 配置核心）
- `views/SessionsView.vue`、`FileManagerView.vue`、`GitPanelView.vue`（1970 行，最大）、`CatalogView.vue`（919 行，扩展广场）、`SettingsView.vue`、`MemoryView.vue`、`RuntimeView.vue`、`WorkflowView.vue`、`OpsView.vue`、`LifeView.vue`
- `components/SideDrawer.vue`（411 行抽屉）、`TopBar.vue`、`Composer.vue`（857 行输入框）、`MessageBubble.vue`、`ToolCardItem.vue`、`ToolGroup.vue`、`QuestionSheet.vue`、`ApprovalSheet.vue`、`ModelPickerSheet.vue`

---

## ③ 核心功能逐项剖析（按与 AgentKit 相关性排序）

### 3.1 AI 模型 / Provider 配置管理 ★★★★★（最值得学）

#### 3.1.1 五协议归一化

```rust
// services/src/config.rs:12-19
pub enum ProviderKind {
    OpenAiCompatible, OpenAiResponses, AnthropicMessages, GeminiNative, DeepseekAccount,
}
```

归一化入口 `ProviderKind::from_config`（`config.rs:30-47`）：先取 `tool_protocol`，回退 `provider_type`，小写化并把 `-`/空格统一成 `_`，再匹配别名表——`generic|deepseek|openai|openai_compatible|chat_completions → OpenAiCompatible` 等。**一个 Provider 记录的「协议」与「显示类型」是解耦的**。

配置模型 `ProviderSettings`（`config.rs:124-170`）字段值得逐条看：

| 字段 | 行号 | 说明 |
|---|---|---|
| `type` / `tool_protocol` | `:126/:128` | 类型与协议分离，默认 `openai_compatible`（`:172-174`） |
| `api_key` / `api_keys` | `:132/:135` | **多 Key 轮换**：`api_keys` 非空时忽略 `api_key`，每次请求轮换 |
| `base_url` | `:137` | 原样保存，由预设控制 `/v1` 后缀（RELEASE_NOTES_v1.4.5.md） |
| `model` / `fast_model` | `:139/:141` | 主模型 + 快模型（用于摘要、压缩等轻任务） |
| `context_window` / `model_context_windows` | `:143/:145` | **模型级上下文窗口覆盖** |
| `effective_context_window_percent` | `:147` | 默认 95%，clamp(1,100)（`config.rs:244-247`） |
| `supports_vision/native_tools/web_search/parallel_tool_calls` | `:161-167` | **能力开关手动配置**（v1.4.0 起从自动探测改为手动） |
| `extra: BTreeMap` (flatten) | `:168-169` | 承载 `models[]`、`modelParameters`、`capabilityOverrides`、`headers`、DeepSeek 开关 |

#### 3.1.2 模型选择器与「已声明模型」校验

`ProviderRegistry::choices()`（`config.rs:343-366`）为每个 Provider 生成 1~2 个 `ModelChoice`，selector 形如 `provider_id` 或 `provider_id:fast_model`。

`resolve()`（`config.rs:368-416`）是核心：

1. 先按 provider id 精确/忽略大小写查找（`:418-424`）；
2. 再遍历 `choices()` 按 selector 或 model 名匹配（`:374-387`）；
3. 最后处理 `provider:model` 形式，**只在「已声明」集合内放行**——`model` / `fast_model` / `extra.models` 三者之一命中才允许（`:392-402`），否则 `bail!("model ... is not declared for provider ...")`（`:409-412`）。

命中后 `apply_model_context_window`（`:427-434`）**按当前模型覆盖上下文窗口与视觉能力**。

> 💡 **设计要点**：模型不是自由文本，而是「Provider 声明过才能用」。这避免了用户手打一个 Provider 不存在的模型导致每次请求 404。

#### 3.1.3 草稿态校验（很巧妙）

`ProviderRegistry::load`（`config.rs:181-337`）：**未填模型的 Provider 直接跳过、不加入运行时**，但文件保留（`:185-189`）。
`validate()`（`:453-479`）只在 Provider **被激活时**才要求 model/base_url 完整（`:465-474`），未激活的允许是草稿。

> 这解决了「先填 Key 拉模型列表、再选模型」的交互死结——**用户可以在模型还没选好的情况下保存 Provider**。AgentKit 的 `ConfigSanitizer.sanitized()`（`core/ConfigSanitizer.kt:104`）目前是一次性校验 baseUrl/apiKey/model，可参考引入「草稿态」。

#### 3.1.4 密钥保护与附加头

`ProviderConfig` 的 `Debug` 实现**手工把 `api_key` 打成 `[redacted]`**（`config.rs:78-96`）——防止日志泄漏。另支持 `extra_headers`（`:308-317`，如 ZCode 验证码头）。

#### 3.1.5 请求侧的「400 自降级」——最值得抄的一段

`services/src/provider.rs` 有一整套**针对上游 400 错误的字段降级重试**：

- `downgrade_xhigh_reasoning`（`:948`）：部分网关只认 `high`，先把最强档降下来再考虑整个移除 reasoning；
- `apply_reasoning_effort` / `has_reasoning_field` / `remove_reasoning_fields`（`:1089/:1100/:1108`）；
- `rejects_reasoning_field`（`:1129`）、`rejects_model_capability`（`:1145`）：**从错误体文本判断**上游拒绝了哪个字段；
- `remove_optional_capability_fields`（`:1154`）：支持能力字段被拒时摘掉；
- `openai_responses_tools`（`:1197`）：按协议生成工具数组。

配合 `capabilities.rs:390-413` 的 `prune_unsupported_fields(payload, profile)`：按模型能力画像**在发出前就剪掉不支持的字段**（含 `strip_images`，`:540-556`）。

> 💡 **这是 Coomi 跨多 Provider 存活的关键**：不假设上游都标准，而是「试 → 读错误 → 降级 → 重试」。AgentKit 三家 Provider（openai/claude/gemini）目前缺少这一层。

#### 3.1.6 四种消息体与流式解析

`provider.rs` 为每种协议单独序列化：`openai_messages`（`:1936`）、`responses_input`（`:2006`）、`anthropic_messages`（`:2080`）、`gemini_messages`（`:2143`）；用量解析也分三套（`:2397/:2417/:2439`）。

流式侧：`read_sse`（`:1334`）读 SSE；三种流状态机 `ChatStreamState`（`:1415`）、`CompactionStreamState`（`:1560`）、`ResponsesStreamState`（`:1604`）；`PartialToolCall`（`:1408`）做增量 tool call 拼接。

工具调用解析的健壮性处理非常细：`parse_openai_tool_calls`（`:2209`）→ `recover_wrapped_tool_arguments`（`:2268`）→ `parse_argument_text`（`:2290`）→ `strip_json_fence`（`:2308`，去 ```json 围栏）→ `extract_json_object`（`:2318`，括号配对扫描）。还会把解析失败的调用包装成 `InvalidToolCall`（`:2234`）交给 Agent 层去纠正。

传输层：`send_request`（`:1272`）、`transport_error`（`:1279`）、`retryable_transport_kind`（`:1324`，把连接发送/连接重置/TLS 抖动纳入可重试）；`endpoint(base_url, suffix)`（`:1762`）统一拼 URL；`safe_http_error_detail`（`:1835`）脱敏错误详情。

#### 3.1.7 能力画像与端点解析

`services/src/capabilities.rs`：`ModelCapabilityProfile`（`:97-161`）记录 `image_generation` 等能力的 `CapabilityEvidence`（`:39`，含 `inferred`/`unknown(error)`/`verified` 三态）。`inferred()`（`:113-161`）按模型名前缀猜：`["dall-e","gpt-image","imagen"] → image_generation`（`:132`）。
`CapabilityRegistry`（`:213-271`）带缓存与 `invalidate_provider`（`:259`）。
`EndpointResolver`（`:300-358`）提供 `models()`（`:317`，`/models`）与 `inference()`（`:324`）。

#### 3.1.8 粘贴即配置（AutoConfig）

`services/src/auto_config.rs:31-55` `detect_auto_config`：用户把一段 JSON 或 `skill: xxx` 粘进输入框，自动识别是 Provider / MCP / Skill 并落盘。别名匹配用 `normalized_key`（`:399-405`，只留字母数字并小写）实现「`apiKey` / `api_key` / `API KEY` 都认」。

---

### 3.2 Agent 执行引擎 ★★★★★

#### 3.2.1 主循环骨架

`engine/src/agent.rs:373-718` `run_turn_message()`，逐段拆解：

| 阶段 | 行号 | 做什么 |
|---|---|---|
| 生命周期钩子 | `:382-410` | 首轮触发 `session_start`，每轮触发 `turn_start`，返回的上下文作为 `internal_user` 消息注入（`:392/:409`） |
| 轮次循环 | `:420` | `'tool_rounds: for round in 1..=self.max_tool_rounds` |
| 历史归一化 | `:422` | `normalize_history()`（修孤儿 tool 消息等） |
| 上下文重算 | `:425-429` | `context.recompute(...)` + 推送 `ContextUpdated` 给 UI |
| 压缩判断 | `:430-442` | 强制压缩（round==1）或 `should_compact` |
| 图片降级开关 | `:462-469` | `vision_replay=false` 时清空所有消息的图片，**但会话记录里仍保留**（注释 `:463-465`） |
| 模型请求 | `:470-538` | 见下方「三层重试」 |
| XML 工具兜底 | `:544-549` | 模型把工具调用写成 `<dots_function_call>`/`<invoke name=...>` 文本时，解析为原生调用并剥离（`parse_executable_xml_calls:1841`、`parse_alias_xml_calls:1848`） |
| 用量累计 | `:551-555` | `session.usage.add()` + `ModelUsage` 事件 |
| 非法参数纠正 | `:580-616` | **只纠正一次**：注入 `<tool_call_correction>`（`:611-612`），再失败就直接阻止执行并回 `recovery_message`（`:595-602`） |
| 无工具调用 | `:618-624` | 先尝试接收排队输入（`:619`），否则 `session.touch()` 返回 |
| 工具调度 | `:630-672` | 见下方「并发与熔断」 |
| 结果收集 | `:672-709` | `FuturesUnordered`，结果写成 `ChatMessage::tool(call.id, "success: ..." / "error: ...")`（`:690-692`） |
| 超轮 | `:715-717` | `AgentError::ToolRoundLimit` |

#### 3.2.2 三层重试（模型请求 `:482-538`）

```
Err + is_context_window_error(&error)      → 压缩后 continue 'tool_rounds（只做一次，:489-496）
Err + 有图片 + is_image_compatibility_error → 剥离图片重试（只做一次，:497-515），触发 vision_fallback 回调
Err + is_transient_provider_error(&error)  → 指数退避重试（最多 provider_retry_count，:516-535）
其他                                        → 返回 AgentError::Provider
```

每次重试都会先 `StreamReset` 再 `ConnectionRetry` 事件（`:527-533`），**UI 能看到「网络或上游服务暂时不可用，正在自动恢复」**——这是把失败透明化，而不是静默重试。重试判定见 `is_transient_provider_error`（`:937`）、`is_context_window_error`（`:1073`）、`is_image_compatibility_error`（`:1002`）、`retry_delay_ms`（`:966`）。

#### 3.2.3 工具并发与失败熔断（**AgentKit 最该抄的一段**，`:630-672`）

```rust
let has_mutation = calls.iter().any(|call|
    specs_by_name.get(call.name.as_str())
        .is_none_or(|spec| spec.concurrency() != ToolConcurrency::ReadOnly));   // :634-637
let parallel_limit = Arc::new(Semaphore::new(self.max_parallel_tools));          // :638
let serial_gate    = Arc::new(AsyncMutex::new(()));                             // :639
```

- **并发判据不是「能不能并行」，而是「这批里有没有写操作」**：只要 `has_mutation` 为真，整批走 `serial_gate` 串行；只有**全批只读**才用 `Semaphore` 并发（`:656-666`）。这样避免「读读并行」与「写操作」交错。
- **调用指纹去重**：`tool_call_fingerprint`（`:895`）→ 同一批内指纹重复 → `repeated_in_batch` 直接拦截（`:643`）；
- **失败熔断**：`tool_failures: HashMap<指纹, ToolFailureState>`（`:418`）。判定 `blocked = repeated_in_batch || previous.requires_changed_call || previous.executions >= 2`（`:645-646`）——**同一个调用最多真跑 2 次**，且如果失败信息表明「必须改参数才能重试」（`tool_failure_requires_changed_call:904`）则不再重试。被拦下的返回 `tool_retry_block_reason`（`:927`）作为**错误结果**喂回模型，而不是让模型无脑重试。
- 成功后清除指纹记录（`:701-702`），失败则累加计数（`:703-708`）。

> 💡 AgentKit 现状（`core/AgentLoop.kt:94-99`）是 `resp.toolCalls.take(maxParallelTools).map{async{...}}.awaitAll()`——**无条件并行、无指纹去重、无失败熔断**。模型一旦在某个工具上打转就会耗尽 `maxSteps`。这是 P0 改造点。

#### 3.2.4 上下文与压缩

`engine/src/context.rs:184-218` `estimate_request_tokens`：**字节数 / 4** 粗估（`:217`），每条消息 +32 开销，每个 tool call +24。**图片特殊处理**——base64 数据不计入预算，每张图固定按 `85 * 4` 字节估（`:206-208`，注释明确说明「否则读一张图就触发压缩」）。

压缩产物：`compacted_history`（`:220-224`）= 保留的用户历史（`retained_user_history:226-248`，倒序取、按剩余 token 截断）+ 一条 `summary` 消息。`trim_history_to_fit`（`:250-284`）先把 tool 消息替换成 `CONTEXT_WINDOW_TRUNCATED_OUTPUT`，仍超则从头部删。

触发：`should_compact`（`agent.rs:720-728`）按 `effective_context_window × percent` 与 `safe_input = context_window - max_output_tokens` 取小，或 `comp_hash` 变化（Provider 侧压缩配置变了）。

#### 3.2.5 类型与事件契约

`engine/src/types.rs` 定义了完整契约：`ModelCapabilities`（`:9`）、`Role`（`:89`）、`ChatMessage`（`:97`，含 `internal`/`compaction_summary` 标记位）、`ToolCall`（`:272`）、`InvalidToolCall`（`:296`）、`ToolSpec`（`:303`）、`ToolConcurrency`（`:310`，ReadOnly/…）、`TokenUsage`（`:384`，`add/saturating_sub`）、`PlanState`（`:559`）、`LoopState`（`:599`）、`UserInputRequest`（`:629`）、`AgentEvent`（`:987`）、以及四个 trait：`AgentObserver`（`:1033`）、`TurnControl`（`:1038`）、`ApprovalHandler`（`:1051`）、`ModelProvider`（`:1064`）、`ToolRuntime`（`:1104`）。

> 💡 **Trait 分层（ModelProvider / ToolRuntime / ApprovalHandler / AgentObserver）是这套引擎可测试的根本**：`agent.rs:1165-2110` 有 30+ 个单测，全部用 mock Provider（`agent.rs:2049`）驱动真实循环，断言「并发保序」「非法参数只纠正一次」「瞬时故障最多重试两次」等。

#### 3.2.6 任务规划与自主循环

- `update_plan` 工具（`tools/src/lib.rs:2491-2515`）：「最多一个步骤处于 in_progress」（description 明写）；
- `create_loop` / `get_loop` / `update_loop`（`:2515-2548`）：持久自主循环目标，「阻塞需要连续三轮同样条件」；
- 事件侧对应 `LoopStepStart/Done/Progress/IssueCreated`（`protocol/events.ts:48-51`）。

---

### 3.3 聊天 UI（消息渲染 / 流式 / Markdown / 代码块） ★★★★☆

#### 3.3.1 Markdown：先解析，再白名单消毒

`apps/web/src/utils/markdown.ts`——这是**整个文件都在讲安全**：

```ts
// :3-10 注释：marked 会把裸 HTML 原样吐出，而文字来自模型和工具结果——不可信。
// 直接 v-html 就等于把 <img onerror=…> 放进 WebView，而这个 WebView 和引擎同源
// （127.0.0.1，手里有 shell）。
```

实现：
- `ALLOWED` 白名单 19 个标签（`:14-20`），`DROP` 连内容都丢弃的 10 个（`:23`，`script/style/iframe/object/embed/template/svg/math/link/meta`）；
- 白名单外标签**拆掉只留文字**，避免「一个 `<div>` 吞掉半段回答」（`:58-62`）；
- 属性白名单 `ATTRS`（`:25-32`），`cleanAttr`（`:41-52`）逐个校验：`href` 先去空白/控制字符再匹配 `^(https?:|mailto:)`（`:34/:38`，注释点名 `"java\tscript:"` 真能跑）、`class` 只放行 `^language-[\w+#.-]{1,24}$`、**`a` 标签强制 `target="_blank" rel="noopener noreferrer nofollow"`**（`:70-74`）；
- 用 `DOMParser` 解析（`:80`），惰性文档不跑脚本不拉 src；
- **LRU 缓存 240 条**（`:92-105`），因为流式期间同一段文字会被反复渲染。

#### 3.3.2 分块渲染 + 节流

`MessageBubble.vue:1-11` 注释说明设计意图：

> 助手消息按**段落切块**渲染——已写完的段落是稳定 DOM，只有最后一块随 token 重绘，新段落做 8px 上浮。**整条消息整体重排会闪，切块之后不会**。marked 调用被 **60ms 节流**（`:22` `const RATE = 60`，节流生效处 `:126`）。

另外 `MessageBubble.vue:56-60` 有个实用细节：**从助手文本中识别本地文件路径**渲染成可点击文件卡片，相对路径用会话 cwd 拼绝对（注释解释：`./build/x.apk` 曾被截断成 `/build/x.apk`）。

#### 3.3.3 虚拟滚动与跟随

`views/ChatView.vue:12` 引入 `DynamicScroller/DynamicScrollerItem`（vue-virtual-scroller）；`:20` `useAutoScroll`；`:145` 注释：**流式输出时气泡高度持续变化，而 DynamicScroller 内部容器不是 ResizeObserver 观察对象**，需用 ResizeObserver 兜住。

#### 3.3.4 流式事件协议

`protocol/events.ts:26-59` 定义 20+ 事件类型，按用途分组：

```
文本/推理：  text_chunk / reasoning_chunk
工具：      tool_start / tool_running / tool_done / tool_cache_hit
            （tool_done 带 elapsed、result_preview、is_error、images）
监控：      usage_update / connection_retry / stream_reset / compression(_started)
异常：      agent_error(含 is_fatal、captcha_required) / configuration_required(route:'/providers')
            / retry_confirmation / agent_cancelled
后台任务：  bg_task_detached / bg_task_completed
自主循环：  loop_step_start / loop_step_done / loop_progress / loop_issue_created
人机交互：  tool_approval_request(access:'read_only'|'write'|'destructive', risk_summary)
            / user_question_request(最多 5 问，每问 3-7 选项) / file_transfer_request
收尾：      turn_end / session_state / session_loaded
```

> 💡 `configuration_required` 带 `route:'/providers'` —— **配置缺失时直接告诉前端跳哪一页**，比抛一个裸错误友好得多。AgentKit 的 `ConfigSanitizer` 可借鉴。

---

### 3.4 技能 / 扩展系统（Skill、MCP、插件） ★★★★☆

#### 3.4.1 Skill 格式

`SKILL.md` + YAML front matter，`SkillFrontMatter`（`services/src/skill_router.rs:17-33`）：

```
name, description, keywords, file-types, tools, projects, risks
```

其中 `keywords/file_types/tool_requirements/project_types/risks` 用 `string_or_list` 反序列化（`:517`），**既接受字符串也接受数组**。索引产物 `SkillIndexEntry`（`:36-49`）额外带 `enabled/indexed_at_ms/index_error`。

限制（`skill_router.rs:12-15`）：单文件 ≤ **128 KiB**、注入上下文 ≤ **64 KiB**、引用深度 ≤ **3**。

#### 3.4.2 Skill 路由（不是全量注入！）

`SkillRouter::route`（`:180-256`）：

1. 遍历索引 `score_entry` 打分（`:427`），按 `Reverse(score)` 排序，同分按名字（`:184`）；
2. `!enabled` 或 `index_error` → `Skipped`（`:190-199`）；
3. `score <= 0 || used >= 3` → `Discovered`/`Skipped`（`:200-213`）——**一轮最多注入 3 个 Skill**；
4. `route_conflict`（`:475`）检查冲突（如要求被禁的工具/网络）→ `Conflict`（`:214-223`）；
5. 读 `SKILL.md` 树（`read_skill_tree:379`，带深度与去重）拼成 `## Routed Skill: {name}` 段（`:225-230`）；
6. **预算检查**：`instructions.len() + section.len() > 64 KiB` 就跳过（`:231-240`）；
7. 每次路由写审计 `skill-routing/events.jsonl`，含 `request_sha256`、字符数、全部 decisions（`:277-299`）。

索引落盘用 **tmp + rename 原子替换**（`:258-275`）。索引重建时做**路径逃逸检查**：`canonical` 必须仍在 skills 根下，否则标记 `index_error`（`:117-131`）。

> 💡 **「按相关性打分、按需注入、限数量、限字节、留审计」** 这套思路，比「把所有 Skill 描述塞进 system prompt」高一个量级。AgentKit 的 `skill/SkillProtocol.kt` 目前是声明式注册，可做路由层增强（P1）。

#### 3.4.3 内置目录（扩展广场）

- `catalogs/skills.json`：`{version, entries:[{id, name, description, repository, ref, subdir}]}` —— 直接引用第三方仓库，如 `anthropics/skills` 的 `frontend-design`、`webapp-testing`，`wshobson/agents` 的 `code-review-excellence`，`github/awesome-copilot` 的 `codeql`，`vercel-labs/agent-skills` 的 `react-nextjs`。
- `catalogs/mcp.json`：同构，条目带 `transport`/`command`/`args` + `required_parameters`（如 `filesystem` 用 `npx -y @modelcontextprotocol/server-filesystem {{allowed_path}}`，占位符 `{{key}}` 由必填参数填充）。
- 安装器 `catalogs/src/lib.rs`：`install_mcp:82`、`install_skill:141`、`install_custom_iteration_skill:148`、`install_runtime_environment_skill:171`、`install_custom_iteration_buildkit:194`、`update_skill:209`、`install_remote_skill:215`、`uninstall_skill:220`、`import_skill_zip:251`、`register_skill_directory:305`、`import_mcp_config:326`。
- 内置 Skill 源码：`catalogs/coomi-custom-iteration.md`、`catalogs/runtime-environments.md`。

GitHub 安装走 `git clone --depth 1`（`auto_config.rs:198-215`），记录 `commit`；更新时**先 rename 备份再装，失败回滚**（`auto_config.rs:280-303`）；安装成功/失败记匿名事件（`:168-178`）。

#### 3.4.4 MCP 运行时

`services/src/mcp.rs`：`McpRuntime`（`:38`）启动后按 `transport` 分派客户端（`:339-351`）——`stdio` / `http` / `sse`，其他 `bail!`。连接后依次 `initialize`（`:352-363`）→ `notifications/initialized`（`:365`）→ `tools/list`（`:367-371`）。
SSE 有独立解析：`read_sse_stream:665`、`next_sse_event:695`（处理 CRLF 与多行字段，有单测 `:795-801`）、`parse_sse_response:717`。
HTTP 客户端区分 SSE 响应（`:558-564`）。
工具名命名空间：`mcp_tool_name(server, tool)`（`:747`）前缀化避免冲突，格式为 `mcp__{server}__{tool}`（`:748`，并对两段做 `sanitize`）。

前端/系统提示注入：`list_mcp` 工具（`tools/src/lib.rs:2568-2580`）让 Agent 自己查已配置的 MCP；README 说明「已配置的 MCP 会注入系统提示供 Agent 直接调用」。

Agent 可自管：工具 `list_skills/read_skill/list_mcp/configure_mcp/install_skill/uninstall_skill/uninstall_mcp`（`tools/src/lib.rs:2549-2637`）。

#### 3.4.5 钩子与安全策略

`security/src/lib.rs`：`AccessMode`（`:16`）、`Decision`（`:33`）、`SecurityPolicy`（`:40`）提供 `assess_read/assess_write/assess_shell`（`:196-204`），支持 `allowed_roots`（`:170`）、`blocked`（`:81`）与附加 blocked（`:154`）。
`security/src/hooks.rs`：`HookEvent`（`:14`）、`HookOutcome`（`:35`）、`HookRunner::load`（`:88`）；引擎侧在 `session_start`/`turn_start` 调用（`agent.rs:383/:396`），输出作为 `internal_user` 注入。

---

### 3.5 本地模型 / 媒体生成 / 代码能力 / 设备自动化 ★★★☆☆

#### 3.5.1 本地模型（**Coomi 弱于 AgentKit**）

`tools/src/lib.rs:2325-2337` `local_model` 工具描述原文：

> "Run a local LLM inference (Operit-style built-in). **Uses ollama or llama.cpp when installed; otherwise reports how to install.**"

即：**Coomi 自己不推理，靠外部 `ollama`/`llama.cpp` 可执行文件**；没装就返回安装指引。同类还有 `local_ocr`（`:2338`，靠 tesseract）、`local_tts`（`:2348`，靠 espeak/edge-tts）。

> ✅ AgentKit 是 JNI 直连 llama.cpp（`core/llm/local/LocalLlamaClient.kt` + `LlamaJni.kt` + `GgufModelStore.kt`），**已比 Coomi 深一层**。结论：**这块不向 Coomi 学**。可借鉴的只有「没装就给安装指引」的兜底话术。

#### 3.5.2 媒体生成（**Coomi 没有**）

全仓检索 `image_generation|generate_image|text_to_image|video_gen|generateVideo`，**只有能力标记，无生成实现**：

- `services/src/capabilities.rs:101` `pub image_generation: CapabilityEvidence`
- `:132` 推断逻辑 `["dall-e","gpt-image","imagen"]`
- `apps/web/src/views/ProviderDetailView.vue:748` UI 上一个「生图」勾选框
- `apps/web/src/components/TopBar.vue:55` 按 `capabilities.image_generation` 过滤模型分类

**没有任何调用生图/视频 API 的代码**。Coomi 的「图片」能力是**读图与展示**：`view_image`（`tools/src/lib.rs:2424`，视觉模型真实读图）与 `show_image`（`:2434`，面向任意模型的大图展示，不要求视觉能力）。

> ✅ AgentKit 已有 `core/media/`（MediaProvider/MediaRegistry/MediaTask + 4 家 provider）与 `ui/MediaScreen.kt`，**明显领先**。结论：**这块反向输出，不借鉴**。

#### 3.5.3 代码能力（写文件 / Git / 构建）

文件与补丁工具（`tools/src/lib.rs`）：

| 工具 | 行号 | 要点 |
|---|---|---|
| `read_file` | `:2204` | 默认只读**前 64 KiB**，支持 `offset`(1-based 行) + `limit` 续读；>4096 字符的行截断 |
| `write_file` | `:2218` | 工作区内创建/替换 UTF-8 文本 |
| `edit_file` | `:2231` | 精确片段替换，要求「先 read_file 并逐字节复制 old_string」 |
| `apply_patch` | `:2388` | 原子应用含 add/update/move/delete 的补丁（`tools/src/patch.rs`） |
| `search` / `grep_files` | `:2246/:2288` | 正则检索 |
| `shell` | `:2260` | 单条命令，受策略约束 |
| `local_shell` | `:2302` | **持久进程**：exec/write/wait/terminate，支持 `yield-time_ms 0` 后台化 |
| `list_dir` | `:2275` | 列目录 |
| `runtime_doctor` | `:2320` | 报告 Host/Termux/ProotLinux 可用性与路径映射 |

Git 能力分两块：

- `services/src/git_engine.rs`（1947 行）：`GitEngine`（`:139`）封装 `run_git`（`:1024`），提供 `GitStatus/DiffInfo/BranchInfo/CommitInfo/StashEntry/RemoteInfo/Snapshot/SnapshotPreview/RestoreReport/ProjectInfo`（`:40-138`），`map_guest_path`（`:1150`）做宿主机↔容器路径映射，`detect_platform`（`:1409`）识别 GitHub/GitLab 等。
- `services/src/ai_git.rs`（1928 行）：`AiGit`（`:144`）让模型写 commit message（`heuristic_commit_message:926` 兜底）、做 code review（`ReviewIssue:48`、`parse_review_issues:1315`、`fallback_review:1005`）、生成 PR 描述（`fallback_pr_description:1166`）、解冲突（`parse_conflict_blocks:1034`、`fallback_conflict:1073`）、生成 README（`readme_template:1142`）。**每个 AI 环节都有规则兜底**——模型挂了也不会卡死流程。

子代理：`tools/src/agents.rs` + 工具 `spawn_agent`（`:2705`）、`wait_agent`（`:2725`）、`close_agent`（`:2738`）。
工作流：`list_workflows/create_workflow/get_workflow/save_workflow/delete_workflow`（`:2638-2700`），引擎侧 `workflow_store.rs`/`workflow_runner.rs` 支持 `depends_on` 与 `topological_order`（`types.rs:895`）。

构建/打包：Coomi 走 **CoomiDev 自定义迭代**——`install_custom_iteration_buildkit`（`catalogs/src/lib.rs:194`）把构建链脚本装到 `.coomi-dev`（`services/src/runtime.rs:423-430`）并映射进容器 `/opt/coomi-dev`（`:450`、`tools/src/lib.rs:1523`），配合 `catalogs/coomi-custom-iteration.md` Skill 指导 Agent 在手机上跑 gradle 构建。

> ⚠️ 这条路要求设备上真装一套 ARM64 Build Kit + Android SDK，**很重**。AgentKit 现有 `core/build/`（BuildEngine + GitHubBuildService：推 GitHub → CI → 拉回 → 安装）更轻且已落地。**建议保留 AgentKit 路线**，仅可借鉴 Coomi 的「构建链就绪检测」概念（`build_kit_ready`：`ui/src/web.rs:3734`）。

#### 3.5.4 设备自动化

- **无障碍**：`app/coomi/CoomiAccessibilityService.java`（383 行）——`describeScreen()`（`:262`）遍历 `AccessibilityNodeInfo` 输出可点击元素（带预算 `int[] budget`，`:285`）；**指令通道是文件队列**：`controlQueueDir()`（`:134`）目录下写 `*.cmd.json`，服务线程 `queueLoop`（`:109`）轮询、`handleCommandFile`（`:154`）执行、`writeResult`（`:237`）写回结果文件。Rust 侧对应 `ui_automation` 工具（`tools/src/lib.rs:2358`）。
- **Shizuku**：`tools/src/shizuku.rs:6 request` / `:35 local_shell` / `:62 shell`；Java 侧 `ShizukuShellBridge.java`、`ShizukuAccessController.java`；权限 `moe.shizuku.manager.permission.API_V23`（`AndroidManifest.xml:45`）。v1.5.1o3 新增 `shell`/`local_shell` 的 `environment="shizuku"`，**独立于无障碍权限**执行 Android 本机 shell。
- **悬浮窗**：`CoomiFloatService`（`AndroidManifest.xml:199`）、`CoomiFloatingWindow.java`。
- **读屏规避**：v1.5.1o3 记录「无障碍读屏避开自身悬浮窗、返回控件坐标」。

> 💡 AgentKit 已有 `automation/`（AgentAccessibilityService、AutomationBridge、UiAutomationTools、ScreenCaptureService、ScreenshotBridge、ui_screenshot 工具），**能力面已对齐甚至更全**（多了虚拟屏截图）。Coomi 可借鉴的只有两点：① `shell` 工具的 **Shizuku 通道**；② 「返回控件坐标」这类**读屏自规避**细节。

---

## ④ 「它怎么做 → 我们怎么适配」对照表

> 约束：**零新增外部依赖**（已有 okhttp 4.12.0 / kotlinx-serialization-json 1.6.3 / rhino 1.7.14），净室重写，保持 AgentKit 代码库一致性（Kotlin + Compose + Material3）。

| # | 借鉴点 | Coomi 做法（代码位置） | AgentKit 落地方案 | 优先级 |
|---|---|---|---|---|
| A1 | **工具调用指纹去重 + 失败熔断** | `agent.rs:641-708`：`tool_call_fingerprint` + `tool_failures` Map；`blocked = 同批重复 \|\| requires_changed_call \|\| 已执行≥2`；拦截时把原因作为**错误结果**回喂模型 | 新建 `core/FailureGuard.kt`（或并入 `core/AgentContract.kt`）：`fun fingerprint(call: ToolCall): String`；`AgentLoop` 持 `MutableMap<String, FailureState>`；`executeCall`（`core/AgentLoop.kt:125`）前置判定，命中则直接返回 `ToolMessage(error="同一调用已失败 N 次，请修改参数")`，不走真实执行 | **P0** |
| A2 | **并发判据改为「本批是否含写操作」** | `agent.rs:634-637` `has_mutation` → 全批只读才并发（`Semaphore`），否则整批串行（`AsyncMutex`） | `core/AgentContract.kt` 的 `AgentTool` 增加 `val readOnly: Boolean`；`AgentLoop.kt:94-99` 改为：先算 `hasMutation`，只读批用 `awaitAll()`，含写批用 `for` 串行。现有 `BuiltInTools`/`UiAutomationTools`/`MemoryTools` 逐个标注 | **P0** |
| A3 | **瞬时错误分层重试 + 事件透明化** | `agent.rs:482-538`：上下文超限→压缩重试；图片被拒→剥离图片重试；瞬时故障→退避重试；每次先发 `StreamReset` 再 `ConnectionRetry`（含 attempt/max/delay/message） | 扩展 `core/AgentLoop.kt:200 isRetryable` 为三分支；新增 `AgentEvent.ConnectionRetry`（现已有 `ToolStarted/ToolFinished` 两个事件，`core/AgentLoop.kt:25-28`）；`ui/ChatViewModel.kt` 收该事件显示「网络/上游暂时不可用，正在恢复（第 n/N 次）」 | **P0** |
| A4 | **Provider 请求侧 400 字段降级** | `provider.rs:948-1178`：`rejects_reasoning_field` / `remove_optional_capability_fields` / `downgrade_xhigh_reasoning`；`capabilities.rs:390-413 prune_unsupported_fields` | 新增 `core/llm/RequestHardening.kt`：在 `LLMClient` 实现（OpenAI/Claude/Gemini）发出前按模型名剪字段；对 400 响应做一次「去 reasoning/去 capability 字段」重试。**纯 okhttp + 字符串处理，零新依赖** | **P0** |
| A5 | **Markdown 渲染 + 白名单消毒** | `apps/web/src/utils/markdown.ts:14-105`：19 标签白名单、10 标签整块丢弃、属性逐项校验、`href` 去控制字符后校验 scheme、`a` 强制 `target=_blank rel=noopener`、DOMParser 惰性解析、LRU 240 | **Compose 侧不能用 DOMParser**。改为新增 `ui/MarkdownText.kt`：**手写极简 Markdown 解析**（标题/粗体/斜体/行内码/围栏代码块/列表/引用/链接/表格 → `AnnotatedString` + `SpanStyle`），天然无 HTML 注入面（不渲染 HTML 即无 XSS）。代码块用 `SelectionContainer` + 等宽字体 + 复制按钮。零依赖 | **P0** |
| A6 | **流式分块渲染 + 节流** | `MessageBubble.vue:1-11,24`：按段落切块，只有最后一块随 token 重绘；`marked` 调用 60ms 节流 | `ui/ChatScreen.kt` 中助手气泡改为「已定稿段落列表 + 当前流段落」两段结构（`ChatViewModel.UiItem.Assistant`，`ui/ChatViewModel.kt:119`）；解析结果按段落缓存（`LinkedHashMap` + 上限）；Compose 侧用 `derivedStateOf` 降低重组 | P1 |
| A7 | **上下文 token 估算：图片按固定成本** | `context.rs:206-208`：base64 不计入预算，每张图固定 `85*4` 字节；整体 `bytes/4`；每条消息 +32、每 tool call +24 | `core/AgentLoop.kt:218 maybeCompact` 目前逻辑较简（待核实估算口径）。新增 `core/TokenEstimator.kt`：port Coomi 的字节/4 + 固定开销公式，**图片走固定值**避免「一张截图触发压缩」 | P1 |
| A8 | **Provider 草稿态保存** | `config.rs:185-189`（未填模型的 Provider 跳过加载但保留）+ `:465-474`（只有激活的必须完整） | `core/ConfigStore.kt:66 save` / `core/ConfigSanitizer.kt:104` 增加「草稿」概念：`ModelProfile` 允许 `model` 为空且非激活时不报错；UI 上 `ui/ModelConfigScreen.kt` 允许「先存 Key → 拉列表 → 再选模型」 | P1 |
| A9 | **模型级能力覆盖（视觉/上下文窗口）** | `config.rs:145 model_context_windows`、`:210-241 capabilityOverrides → model_vision_support`；`apply_model_context_window:427-434` | `core/ConfigStore.kt:248 ModelProfile` 增加 `modelContextWindows: Map<String,Long>` 与 `visionOverrides: Map<String,Boolean>`；`ConfigSanitizer` 增加 `resolveCapabilities(profile, model)` | P1 |
| A10 | **多 Key 轮换** | `config.rs:135 api_keys`：「非空时忽略 api_key，每次请求轮换」 | `ModelProfile`（`ConfigStore.kt:248`）增加 `apiKeys: List<String>`；`core/llm/*Client.kt` 取 Key 时按轮转索引。零依赖 | P2 |
| A11 | **粘贴即配置（AutoConfig）** | `auto_config.rs:31-55 detect_auto_config` + `normalized_key:399`（别名归一化）+ `extract_json:306`（首个 `{` 到末个 `}`） | 新增 `core/ConfigImport.kt`：用 `kotlinx-serialization-json` 解析剪出的 JSON，识别 Provider（`model`+`api_key` 同时存在）/ MCP（`mcpServers`/`servers`）；`ui/ModelConfigScreen.kt` 增加「粘贴配置」入口 | P2 |
| A12 | **Skill 路由打分 + 限流注入** | `skill_router.rs:180-256`：打分排序、最多 3 个、64 KiB 预算、冲突检查、`events.jsonl` 审计 | 新增 `skill/SkillRouter.kt`：读取 `SkillProtocol` 清单的 keywords/description，与用户输入做**词元交集打分**（参考 `skill_router.rs:485 tokenize`）；`SkillExecutor` 注入时限制数量与字节；审计写入 `core/AgentLogger` | P1 |
| A13 | **SKILL.md front matter 兼容字符串或数组** | `skill_router.rs:517 string_or_list` | `skill/SkillProtocol.kt` 的反序列化改为 tolerant：字符串或数组都接受（用 `JsonElement` 手工判断） | P2 |
| A14 | **MCP 工具名命名空间** | `mcp.rs:747 mcp_tool_name(server, tool)` | `core/mcp/McpClient.kt` 注册工具时统一加 `mcp_<server>_` 前缀，避免与内置工具（`core/ToolRegistry.kt:10`）冲突 | P1 |
| A15 | **MCP 传输分派 + SSE 解析** | `mcp.rs:339-351`（stdio/http/sse）、`:665-731` SSE 流解析（含 CRLF/多行，有单测） | 核对 `core/mcp/McpClient.kt` 支持范围：若缺 SSE，用 **okhttp 的 `EventSource` 或手工按行缓冲**（`okio` 已随 okhttp 引入，无需新依赖）补齐 | P2 |
| A16 | **`local_shell` 持久进程** | `tools/src/lib.rs:2302`：`exec/write/wait/terminate` + `yield-time_ms 0` 后台化；`tools/src/processes.rs` | AgentKit 已有 `core/container/ContainerSession.kt` + `terminal/TerminalSession.kt`。核对是否已对外暴露为 Agent 工具；若无，在 `tools/BuiltInTools.kt` 注册 `shell_exec/shell_write/shell_wait` 三件套（复用 TerminalSession） | P1 |
| A17 | **`shell` 的 Shizuku 通道** | `tools/src/shizuku.rs:35 local_shell` / `:62 shell`；v1.5.1o3 `environment="shizuku"` | `automation/` 包内新增 `ShizukuShellTool.kt`，参数 `environment: "proot" \| "shizuku"`；复用 `automation/AutomationBridge.kt` 已建立的 Shizuku 连接 | P2 |
| A18 | **AI-Git 的规则兜底** | `ai_git.rs`：`heuristic_commit_message:926`、`fallback_review:1005`、`fallback_conflict:1073`、`fallback_pr_description:1166` | `core/build/BuildEngine.kt` 提交环节增加「模型不可用时用规则生成 commit message」（从 `git diff --stat` 提取文件名 + 动词）。保证推 GitHub 链路不因模型失败而中断 | P2 |
| A19 | **配置缺失导向修复页** | `protocol/events.ts:43 configuration_required { message, route:'/providers' }` | `core/AgentLoop.kt` 抛错时携带 `targetRoute`；`ui/ChatViewModel.kt` 转成可点击提示，跳 `ui/ModelConfigScreen.kt` | P2 |
| A20 | **Debug 日志脱敏 Key** | `config.rs:78-96`：`Debug` 手工实现，api_key 打 `[redacted]` | 核对 `core/ConfigSanitizer.kt` 与 `core/AgentLogger.kt`：确保任何 `ModelConfig` 的 `toString`/日志输出不落明文 Key | **P0**（合规） |
| A21 | **原子写配置（tmp + rename）** | `skill_router.rs:258-275`；`auto_config.rs:280-303` 更新前 rename 备份、失败回滚 | `core/ConfigStore.kt:66 save` 与 `core/ConversationStore.kt` 改为「写 `.tmp` → `rename`」；大改前先备份 | P1 |
| A22 | **`unsafe_code/unwrap/todo` 编译期禁止** | `Cargo.toml:46-51` lint 闸门 | AgentKit 侧：在 `app/build.gradle.kts` 增加 `kotlinOptions { allWarningsAsErrors = false }` + 引入 **detekt/lint 规则**（注：若受「零新增依赖」约束，可改为 **CI 脚本 grep 检查** `!!` 与 `TODO()`） | P2 |

### 明确**不借鉴**的清单（附理由）

| Coomi 做法 | 不借鉴理由 |
|---|---|
| Termux fork 作为 App 壳（`build.gradle:37` namespace `com.termux`，targetSdk ≤28） | AgentKit 是原生 Compose 单模块，且 targetSdk 健康。**引入 Termux 会让包体与权限面失控** |
| Rust 引擎 + 本地 HTTP/WS + Vue WebView 三层（52k 行 Rust + 24k 行前端） | 与「Kotlin 单模块 + Compose + 零新增依赖」红线根本冲突。学其**协议与策略**，不学其架构 |
| `local_model` 靠外部 ollama/llama.cpp（`tools/src/lib.rs:2325`） | AgentKit 已 JNI 直连 llama.cpp，**更深** |
| 媒体生成（无实现） | AgentKit 已有 4 家 provider，**领先** |
| CoomiDev 手机端 Build Kit（`catalogs/src/lib.rs:194`） | 太重；AgentKit 的 GitHub CI 路线已落地且更轻 |
| 无障碍「文件队列」指令桥（`CoomiAccessibilityService.java:134/154/237`） | AgentKit 同进程直调 `AutomationBridge`，更高效；文件队列是为跨进程/跨语言服务的 |
| DeepSeek 账号登录 + PoW（`services/src/deepseek/`） | 灰色地带且非产品目标（用户原话未提），**不做** |
| AI 工作室 / 数字生命 / 群聊协作（`services/src/studio/`、`extensions/coomi-life`） | 超出产品目标（对标 4 个 App 的能力清单里没有），**不做** |

---

## ⑤ 优先行动清单

### P0 —— 直接决定「体验好」与「不卡死」（合计约 8~11 人日）

| # | 动作 | 涉及文件（AgentKit） | 工作量 | 验收标准 |
|---|---|---|---|---|
| P0-1 | 工具失败熔断 + 指纹去重 | 新增 `core/FailureGuard.kt`；改 `core/AgentLoop.kt:94-99,125` | 1.5d | 同一工具调用参数不变时最多真执行 2 次；第 3 次返回「请修改参数」错误结果，模型能看到并改变策略 |
| P0-2 | 并发判据改为「本批是否含写操作」 | `core/AgentContract.kt`（加 `readOnly`）；`core/AgentLoop.kt:94-99`；`tools/BuiltInTools.kt`、`automation/UiAutomationTools.kt`、`memory/MemoryTools.kt` 逐标注 | 1d | 只读批（如两次 read_file）并行；含一次 write_file 的批整体串行 |
| P0-3 | 瞬时错误分层重试 + 重试事件 | `core/AgentLoop.kt:173-201`；`core/AgentContract.kt`（加 `AgentEvent.ConnectionRetry`）；`ui/ChatViewModel.kt` | 1.5d | 断网/429/5xx 时最多重试 3 次并指数退避；UI 显示「正在恢复（n/N）」 |
| P0-4 | Provider 400 字段降级 | 新增 `core/llm/RequestHardening.kt`；改 3 个 `*Client.kt` 的 400 分支 | 2d | 对不支持 reasoning 的模型自动摘字段重试成功；不再因 `reasoning_effort` 导致整轮失败 |
| P0-5 | Markdown 渲染 + 消毒 | 新增 `ui/MarkdownText.kt`；改 `ui/ChatScreen.kt` 助手气泡 | 2.5d | 助手回复中的标题/列表/代码块/表格正确渲染；**不解析任何 HTML**（无注入面）；代码块可长按复制 |
| P0-6 | 密钥日志脱敏复核 | `core/ConfigSanitizer.kt`、`core/AgentLogger.kt`、`core/ConfigStore.kt` | 0.5d | 全仓搜索确认无任何路径会把 apiKey 明文写入日志/崩溃栈 |

**P0 合计 ≈ 9 人日**（P0-5 若只做标题/粗体/行内码/代码块/列表五要素可压到 1.5d）

### P1 —— 明显提升质感（合计约 8~10 人日）

| # | 动作 | 涉及文件 | 工作量 |
|---|---|---|---|
| P1-1 | 流式分块渲染 + 解析缓存 | `ui/ChatScreen.kt`、`ui/ChatViewModel.kt:119` | 1.5d |
| P1-2 | Token 估算器（图片固定成本） | 新增 `core/TokenEstimator.kt`；改 `core/AgentLoop.kt:218` | 1d |
| P1-3 | Provider 草稿态 + 模型级能力覆盖 | `core/ConfigStore.kt:66,248`、`core/ConfigSanitizer.kt`、`ui/ModelConfigScreen.kt` | 2d |
| P1-4 | Skill 路由打分与限流注入 | 新增 `skill/SkillRouter.kt`；改 `skill/SkillExecutor.kt` | 2d |
| P1-5 | MCP 工具名命名空间 | `core/mcp/McpClient.kt`、`core/ToolRegistry.kt:10` | 0.5d |
| P1-6 | `local_shell` 持久进程工具三件套 | `tools/BuiltInTools.kt`、`terminal/TerminalSession.kt` | 1.5d |
| P1-7 | 配置原子写（tmp+rename） | `core/ConfigStore.kt:66`、`core/ConversationStore.kt` | 0.5d |

### P2 —— 锦上添花 / 长期（合计约 5~7 人日）

| # | 动作 | 涉及文件 | 工作量 |
|---|---|---|---|
| P2-1 | 多 Key 轮换 | `core/ConfigStore.kt:248` + 3 个 `*Client.kt` | 1d |
| P2-2 | 粘贴即配置（AutoConfig） | 新增 `core/ConfigImport.kt`；`ui/ModelConfigScreen.kt` | 1.5d |
| P2-3 | SKILL.md front matter 字符串/数组兼容 | `skill/SkillProtocol.kt` | 0.5d |
| P2-4 | MCP SSE 传输补齐 | `core/mcp/McpClient.kt` | 1.5d |
| P2-5 | `shell` 的 Shizuku 通道 | 新增 `automation/ShizukuShellTool.kt` | 1d |
| P2-6 | AI-Git 规则兜底 commit message | `core/build/BuildEngine.kt` | 0.5d |
| P2-7 | 配置缺失导向修复页 | `core/AgentContract.kt`、`ui/ChatViewModel.kt` | 0.5d |
| P2-8 | CI 静态检查（`!!`/`TODO()` 门禁） | `.github/workflows/` | 0.5d |

---

## ⑥ 代码位置索引

### 6.1 架构与构建

| 内容 | 位置 |
|---|---|
| Gradle 模块声明（4 个） | `settings.gradle:26-38` |
| Android 包名/命名空间 | `apps/coomi-app/app/build.gradle:37` |
| targetSdk ≤28 硬约束 | `apps/coomi-app/app/build.gradle:45-49` |
| Shizuku 依赖 | `apps/coomi-app/app/build.gradle:67-68` |
| Rust workspace（8 crate） | `apps/coomi-rs/Cargo.toml:1-3` |
| Rust edition 2024 / MSRV 1.85 | `apps/coomi-rs/Cargo.toml:9-13` |
| lint 闸门（forbid unsafe / deny unwrap） | `apps/coomi-rs/Cargo.toml:46-51` |
| toolchain 1.95.0 | `apps/coomi-rs/rust-toolchain.toml:2` |
| 前端依赖清单 | `apps/web/package.json:14-24` |
| AndroidManifest 权限与服务 | `apps/coomi-app/app/src/main/AndroidManifest.xml:21-45,111-218` |

### 6.2 Provider / 模型配置

| 内容 | 位置 |
|---|---|
| `ProviderKind` 五协议 | `apps/coomi-rs/services/src/config.rs:12-19` |
| 协议别名归一化 | `config.rs:30-47` |
| `ProviderConfig` 结构 | `config.rs:51-76` |
| Debug 脱敏 api_key | `config.rs:78-96` |
| `ProviderSettings` 全字段 | `config.rs:124-170` |
| `ProviderRegistry::load`（草稿跳过） | `config.rs:181-337`（草稿态 `:185-189`） |
| `choices()` / `resolve()` | `config.rs:343-366` / `:368-416` |
| 已声明模型校验 | `config.rs:392-412` |
| 模型级窗口/视觉覆盖 | `config.rs:427-434` |
| `validate()` 只校验激活项 | `config.rs:453-479` |
| DeepSeek 账号固定配置 | `config.rs:513-532` |
| 单测（含 legacy 兼容） | `config.rs:534-647` |
| `HttpModelProvider` | `apps/coomi-rs/services/src/provider.rs:34` |
| reasoning 降级 / 字段移除 | `provider.rs:948, 1089-1178` |
| SSE 读取 / 三种流状态机 | `provider.rs:1334, 1415, 1560, 1604` |
| URL 拼接 / 错误脱敏 | `provider.rs:1762, 1835` |
| 四协议消息序列化 | `provider.rs:1936, 2006, 2080, 2143` |
| tool call 解析与容错 | `provider.rs:2209-2368` |
| 用量解析（三协议） | `provider.rs:2397, 2417, 2439` |
| 能力画像 / 端点解析 / 字段剪裁 | `apps/coomi-rs/services/src/capabilities.rs:97-161, 300-358, 390-413` |
| 粘贴即配置 | `apps/coomi-rs/services/src/auto_config.rs:31-55, 306-405` |
| Skill 的 git clone 安装与回滚 | `auto_config.rs:162-253, 255-304` |

### 6.3 Agent 引擎

| 内容 | 位置 |
|---|---|
| 主循环 `run_turn_message` | `apps/coomi-rs/engine/src/agent.rs:373-718` |
| 生命周期钩子注入 | `agent.rs:382-410` |
| 三层重试 | `agent.rs:482-538` |
| XML 工具调用兜底解析 | `agent.rs:544-549, 1841, 1848` |
| 非法参数只纠正一次 | `agent.rs:580-616` |
| **并发判据 + 指纹熔断** | `agent.rs:630-672`（`has_mutation :634-637`、`blocked :645-646`） |
| 结果写回与失败记账 | `agent.rs:672-709` |
| 压缩触发 | `agent.rs:720-728` |
| 指纹 / 失败判定 / 重试退避 | `agent.rs:895, 904, 937, 966` |
| 引擎单测（30+） | `agent.rs:1165-2110` |
| 类型契约（ToolSpec/Event/Trait） | `apps/coomi-rs/engine/src/types.rs:303, 987, 1033-1104` |
| token 估算（图片固定成本） | `apps/coomi-rs/engine/src/context.rs:184-218`（图片 `:206-208`） |
| 压缩产物 / 裁剪 | `context.rs:220-248, 250-284` |
| 会话存储 | `apps/coomi-rs/engine/src/session.rs:34-355` |

### 6.4 聊天 UI

| 内容 | 位置 |
|---|---|
| Markdown 白名单消毒 | `apps/web/src/utils/markdown.ts:14-105` |
| 分块渲染 + 60ms 节流 | `apps/web/src/components/MessageBubble.vue:1-11, 22, 126` |
| 本地路径识别成文件卡片 | `MessageBubble.vue:56-60` |
| 虚拟滚动 + 自动跟随 | `apps/web/src/views/ChatView.vue:12, 20, 145` |
| 流式事件协议（20+ 类型） | `apps/web/src/protocol/events.ts:26-59` |
| Provider 配置页（861 行） | `apps/web/src/views/ProviderDetailView.vue`（能力勾选 `:748`） |
| 输入框（857 行） | `apps/web/src/components/Composer.vue` |
| 工具卡片 / 分组 | `apps/web/src/components/ToolCardItem.vue`（572）、`ToolGroup.vue` |
| 审批 / 提问弹层 | `apps/web/src/components/ApprovalSheet.vue`、`QuestionSheet.vue` |

### 6.5 Skill / MCP / 工具

| 内容 | 位置 |
|---|---|
| Skill front matter 定义 | `apps/coomi-rs/services/src/skill_router.rs:17-33` |
| 大小/深度限制常量 | `skill_router.rs:12-15` |
| **路由打分与限流注入** | `skill_router.rs:180-256` |
| 原子写索引 / 审计 jsonl | `skill_router.rs:258-275, 277-299` |
| 路径逃逸检查 | `skill_router.rs:117-131` |
| 打分函数 | `skill_router.rs:427-474` |
| MCP 传输分派 / initialize | `apps/coomi-rs/services/src/mcp.rs:339-371` |
| MCP SSE 解析 | `mcp.rs:665-731` |
| MCP 工具名命名空间 | `mcp.rs:747` |
| 工具清单（ToolSpec） | `apps/coomi-rs/tools/src/lib.rs:2199-2740` |
| `CoreTools` 结构 | `tools/src/lib.rs:58-74` |
| 常量（输出上限/超时） | `tools/src/lib.rs:55-56` |
| `local_model` / `local_ocr` / `local_tts` | `tools/src/lib.rs:2325, 2338, 2348` |
| `ui_automation` / `local_shell` / `runtime_doctor` | `tools/src/lib.rs:2358, 2302, 2320` |
| `view_image` / `show_image` | `tools/src/lib.rs:2424, 2434` |
| 补丁 / 子代理 / 进程 | `tools/src/patch.rs`、`agents.rs`、`processes.rs` |
| Shizuku 桥 | `tools/src/shizuku.rs:6, 35, 62` |
| 内置 Skill 目录 | `apps/coomi-rs/catalogs/skills.json` |
| 内置 MCP 目录 | `apps/coomi-rs/catalogs/mcp.json` |
| 目录安装器 | `apps/coomi-rs/catalogs/src/lib.rs:82-326` |
| 自定义迭代 Skill / Build Kit | `catalogs/coomi-custom-iteration.md`、`catalogs/src/lib.rs:194` |
| 安全策略 | `apps/coomi-rs/security/src/lib.rs:16-204` |
| Hook 钩子 | `apps/coomi-rs/security/src/hooks.rs:14-106` |

### 6.6 Git / 构建 / 设备自动化

| 内容 | 位置 |
|---|---|
| GitEngine（1947 行） | `apps/coomi-rs/services/src/git_engine.rs:139, 1024, 1150, 1409` |
| AiGit（1928 行）+ 规则兜底 | `apps/coomi-rs/services/src/ai_git.rs:144, 926, 1005, 1073, 1166` |
| Build Kit 就绪检测 | `apps/coomi-rs/ui/src/web.rs:3719-3734`；`services/src/runtime.rs:423-450` |
| 无障碍服务（文件队列） | `apps/coomi-app/app/src/main/java/app/coomi/CoomiAccessibilityService.java:33, 109, 134, 154, 237, 262, 363` |
| WebView 宿主 | `apps/coomi-app/app/src/main/java/com/termux/app/CoomiActivity.java:25-26` |
| 引擎监控/服务 | `app/coomi/CoomiService.java`、`CoomiEngineMonitor.java` |

---

## 附：待验证清单

1. **AgentKit `maybeCompact` 的估算口径**（`core/AgentLoop.kt:218`）——仅看了函数签名与调用点，未读实现；P1-2 落地前需先读。
2. **AgentKit 三家 Provider 的 400 处理现状**——未逐行读 `core/llm/*Client.kt` 的错误分支；P0-4 排期前需确认。
3. **AgentKit `core/mcp/McpClient.kt` 支持的传输类型**——仅知道文件存在，未确认是否已支持 SSE/HTTP；P2-4 依赖此项。
4. **AgentKit ChatScreen 是否已具备任何 Markdown 能力**——`ui/ChatScreen.kt` 中 grep `markdown` 无命中，仅见 `LazyColumn:160` 与 Base64 图片解码 `:691-692`，据此判断目前为纯文本渲染；建议复核。
5. **Coomi `ui/src/web.rs`（11780 行）的 HTTP/WS 路由与鉴权细节**——因与 AgentKit 架构不同（我们无本地服务层），未深入；若后续要做「外部客户端连 AgentKit」可参考。
6. **Coomi 引擎与前端的鉴权方式**（本地 127.0.0.1 服务的 token 机制）——未找到明确实现，标注待验证。
7. **Coomi `studio`/`collab`/`life` 模块**（`services/src/studio/`、`group_chat.rs`、`ui/src/life.rs`）——超出本次产品目标范围，未深入。
