# 00 · 总对标地图与分阶段路线图

> 本文件是 5 份 digest（`coomi.md` / `rikkahub.md` / `operit.md` / `aicode.md` / `zafiro.md`）的**横向合并产物**。
> 第一原则：**只输出改造方案与路线图，不做代码搬运**。AGPL/LGPL/GPL 三库只提炼设计原理，落地一律净室重写。
> 制定人：产品经理 许清楚（Xu）｜基线：AgentKit 单模块 `:app`，Kotlin 1.9.25 + Compose BOM 2024.09.00 + Material3，minSdk 26，仅 arm64-v8a，约 80 kt。

---

## 0. 开工前必读

### 0.1 现状核对说明（实测扫描结果，与 digest 描述的两处差异）

| 项 | digest 描述 | 实测（`/workspace` 扫描） | 处置 |
|---|---|---|---|
| kt 文件数 | 约 80 | **80** ✅ | — |
| 关键文件 | `core/AgentLoop.kt`（`maxSteps=16` / `maxParallelTools=5` / `awaitAll` / `isRetryable:200` / `maybeCompact:218`） | 一致 ✅ | — |
| 工具现状 | — | `tools/BuiltInTools.kt` 仅 **5 个**工具：`http_request` / `read_file` / `write_file` / `clipboard` / `send_notification`；`core/ToolRegistry.kt` 为 `LinkedHashMap` + `runCatching` 兜底 | 写文件只有整文件覆盖，无 `edit_file` |
| `core/AgentExecutor` | digest 多次提及 | **当前仓库未见此文件** | 路线图中相关改造落到 `core/AgentLoop.kt` / `ui/ChatViewModel.kt` |
| Markdown 渲染 | 待验证 | `ui/ChatScreen.kt` grep `markdown` / `AnnotatedString` **均无命中** | 确认为纯文本渲染，T11 是真实缺口 |

> 所有路径默认前缀：`app/src/main/java/com/example/agentkit/`。

### 0.2 两个维度不要混淆

| 维度 | 含义 | 取值 |
|---|---|---|
| **优先级 P0/P1/P2** | 投入产出比 × 对产品目标的贡献 | §3 |
| **施工顺序 Phase 1/2/3** | 依赖关系与地基先后 | §4 |

**Phase 1 里的不全是 P0，P0 也不全在 Phase 1**——例如「提示词资产化」是高价值 P1，但它依赖稳定的工具契约与场景库，所以排在 Phase 3。

### 0.3 产品目标与权重（用户原话）

| 目标 | 权重 | 判定标准 |
|---|---|---|
| **体验好** | ★★★★★ | 不卡死、渲染对、可取消、可回头、模型能选 |
| **能写代码（本地/云端）** | ★★★★★ | 容器有环境、能精确改文件、有 diff、能跑能看 |
| **本地模型** | ★★★★☆ | 小模型喂得动（工具降级）、不爆上下文、卡顿可中断 |
| **推 GitHub 拉回 / 做安装包** | ★★★★☆ | 长命令不卡、凭据可回源、安装可预检 |
| **写游戏** | ★★★☆☆ | = 能写代码 + 场景库（node/python） |
| **AI 视频/照片** | ★★☆☆☆ | **已达成**（`core/media/providers/` 四家），仅保持领先 |

---

## 1. 去重合并：跨 5 份的唯一方案

> 5 份里重复出现的建议，此处合并成**一条唯一方案**。格式为：唯一方案 / 来源 / 合并掉了哪些重复表述。

### M-01 · Agent 运行时内核抽象
- **唯一方案**：净室重写为 **`core/agent/` 子包（不拆 Gradle 模块）**——`AgentLoop` 接口 + `RealAgentLoop`（回合主循环：流式收集 → 整条 commit → 工具执行 → 保序回喂）+ `ToolRegistry` / `ToolExecutor` 契约（`execute` 永不抛异常，`onInterrupt` 自判状态）+ `ToolDescriptor{name, wireName, inputSchemaJson}` + `Hooks` 接口（首期落 `beforeToolCall` / `afterToolCall` / `beforeStop` / `beforeSerialization` 四时机）。运行时层不认识 Android。现有 `core/AgentLoop.kt` 改为调用它，`ui/ChatViewModel.kt` 只认事件流与状态流。
- **来源**：`zafiro.md` A1（okia，P0-1 3-4d）／`operit.md` #1,#6,#8／`coomi.md` A1,A2,A3（trait 分层 `ModelProvider/ToolRuntime/ApprovalHandler/AgentObserver`）／`aicode.md` §3.2.4（AgentTool 抽象基类）
- **合并掉了**：zafiro「抽独立库」、operit「EnhancedAIService 内聚」、coomi「trait 分层可测试」、aicode「AgentTool 基类」四种表述 → 统一为**单模块内的 `core/agent/` 包 + 接口契约**（拆模块与红线/工期冲突，不拆）。

### M-02 · 终端后端与身份抽象
- **唯一方案**：`core/terminal/backend/` 新增 `TerminalBackend` 接口（`start/send/close/awaitExit/output`）+ `TerminalIdentity{User, Su, Shizuku, Ssh}` 枚举 + `ProotBackend`（包装现有 `core/container/`）+ `ShizukuBackend`（改造现有 `automation/` 的 Shizuku 通道）；上层 `TerminalSessionPool` 用**4 位十六进制短句柄** + 每会话 `Mutex` + 异步态。**会话开辟时固化身份，后续传参冲突则显式报错，绝不静默提权。**
- **来源**：`zafiro.md` B1,B2,B3,B4（P0-4/P0-5/P0-8）／`operit.md` #4,#5（五级工厂 + 严格模式 + commandId 事件流）／`rikkahub.md` A13（非交互 env）／`aicode.md` C4,C5（argv[0] 与 env 合并）
- **合并掉了**：zafiro「4 后端可插拔」+ operit「五级权限工厂」→ 统一为「**身份枚举（4 级）× 后端实现（2 个起步）**」，ROOT/Su 与 SSH 只留接口不实现。

### M-03 · 工具协议与结果契约
- **唯一方案**：① 对外一律**原生 `tool_calls` JSON**（不上 XML，见 §2.2）；② 工具返回统一为 **`{ok, code, message, hint, data}`**，**失败必带 `hint`**（教模型怎么修）；③ 结果字符串抽成 `formatToolResult(result)` 单一函数，**UI 与模型历史共用同一份文本**（一份真源、两处复用）。
- **来源**：`zafiro.md` A3,A4（`BuiltinToolResult`）／`operit.md` #1,#9,#11（`OrderedToolResults` 只格式化一次 / 可纠正的「工具不存在」错误）／`coomi.md` A1／`aicode.md` §3.2.4（`ToolResult` sealed）
- **合并掉了**：「结果契约」「hint 可修复提示」「未知工具模糊匹配」「UI/模型同源」四条 → 一个契约 + 一个格式化函数。

### M-04 · 并发判据与失败熔断
- **唯一方案**：`AgentTool` 增加 `readOnly: Boolean`。**并发判据从「能不能并行」改为「本批是否含写操作」**——含任一写工具则整批串行，全批只读才 `async + awaitAll`。同时 `tool_call_fingerprint(name + args)` 去重：**同一指纹最多真执行 2 次**，第 3 次直接返回 `ToolMessage(isError=true, "同一调用已失败 N 次，请修改参数")` 回喂模型，不走真实执行。
- **来源**：`coomi.md` A1,A2（`agent.rs:634-637` has_mutation / `:895` 指纹 / `:645-646` blocked）／`operit.md` #8（12 个只读工具白名单并行）
- **合并掉了**：coomi「信号量 + 串行门」与 operit「白名单并行」两种实现 → 统一为 `readOnly` 标注 + 批量判据。

### M-05 · 可取消与截断
- **唯一方案**：① 取消时在 `NonCancellable` 内调 `onInterrupt` **补全待决 ToolResult 再重抛**（不变量：已 commit 的 ToolCall 必须有配对 ToolResult，否则严格 Provider 直接报错、UI 永远转圈）；② 主循环每轮结束复查 `shouldContinue()`，点「停止」≤1 轮内退出；③ `MAX_TOOL_RESULT_CHARS = 24_000`，超限截断并追加 `"\n…[结果过长已截断，共 N 字符]"`；④ 流式 idle 超时 60s（`channel + select{onTimeout}`，工具执行段不计入）。
- **来源**：`operit.md` #4,#7,#9（`ToolExecutionLimits` / 截断修复 / `isConversationActive` 复查）／`zafiro.md` A5,A7（onInterrupt / idle 检测）／`aicode.md` C3（`BoundedOutput` 保留开头+结尾）
- **合并掉了**：「取消」「截断」「idle」「输出限幅」四条 → 一个加固批次。

### M-06 · 消息分支
- **唯一方案**：`ConversationStore` 的 `MsgRecord` 外包一层 **`MsgNode { id, records: List<MsgRecord>, selectIndex: Int }`**。线性渲染取 `nodes.map { it.records[it.selectIndex] }`；重新生成 / 编辑 = **往同一节点 `records` 追加兄弟 + 把 `selectIndex` 指向新元素**（原消息永不丢）；UI 加「◀ 2/3 ▶」切换器，仅 `records.size > 1` 时显示。**扁平 List + 游标即表达树形分支，JSON 直接可存。**
- **来源**：`rikkahub.md` A2（`Conversation.kt:113-135`、`ChatService.kt:1357-1366`）／`zafiro.md` F2（`rewind` + `export`）
- **合并掉了**：rikkahub 的「节点+游标」与 zafiro 的「会话树 + rewind」→ 先做**扁平游标版**（成本 1.5d），完整树 + rewind 留 P2。

### M-07 · Markdown 渲染与代码高亮
- **唯一方案**：**不引任何库**。新增 `ui/MarkdownText.kt` 手写极简解析（标题 / 粗体 / 斜体 / 行内码 / 围栏代码块 / 列表 / 引用 / 链接 / 表格 → `AnnotatedString` + `SpanStyle`）——**不解析 HTML 即天然无 XSS 注入面**，比 Coomi 的 DOMPurify 白名单更彻底。代码块用 `SelectionContainer` + 等宽字体 + 复制按钮。语法高亮另做 `core/editor/SimpleHighlighter.kt`（10 语言关键字正则 + 字符串/注释状态机，`Dispatchers.Default` 按 500 行分块产出）。
- **来源**：`coomi.md` A5,A6（白名单消毒 + 分块渲染 + 60ms 节流）／`aicode.md` A1,A8（自研高亮 / WebView 预览三条安全配置）／`rikkahub.md` A16（不建议自研 51kt 引擎）
- **合并掉了**：Coomi「白名单」、AiCode「引库 / WebView」、RikkaHub「自研 51kt」三种 → 统一为**零依赖极简版**，明确不做完整 TextMate。

### M-08 · 提示词资产化
- **唯一方案**：新增 `assets/prompts/<NN>-<名>.md`（**两位数字编号即身份即注入顺序**）+ `core/prompt/{PromptCatalog, PromptResolver, SystemPromptBuilder}.kt`。启动时释放到 `filesDir/prompts`（每次覆盖），用户覆盖写 `filesDir/prompts.custom/`（**永不删除**）。动态内容全部走 `{{VAR}}` 变量并**集中在最后一个片段**（`99-runtime-context.md`），先做 4 个：`{{SKILLS}}` `{{MEMORY}}` `{{WORKSPACE}}` `{{DATE}}`。**稳定层 / 易变层二分**（记忆快照进易变层，每会话注入）。
- **来源**：`aicode.md` B1,B2,B3,B9（编号片段 + 四层优先级 + 会话级缓存 + 项目规则）／`zafiro.md` D2,D4（`PromptComposer` 稳定/易变层 + 记忆准则文本）／`coomi.md` A12（Skill 路由打分限流）／`rikkahub.md` A10（助手预设）
- **合并掉了**：AiCode 的四层优先级（PROJECT>GLOBAL>LOCAL>BUILTIN）→ **首期只做两层**（项目 / 全局）；zafiro 的稳定/易变二分并入同一实现。

### M-09 · 场景库与开发环境
- **唯一方案**：`assets/env/scenarios/{base,node,python,java,go}.sh`，每个脚本**只暴露两个 shell 函数** `scenario_name_<id>` / `install_scenario_<id>`，由 `SCENARIO_IDS` 列表驱动菜单（分发走函数名前缀）；配套 `env-common.sh` 做包管理器探测（apk/apt/dnf/pacman）与国内镜像源替换、运行时版本探测。容器页加「环境安装」一键入口。**不学 CoomiDev 手机端 Build Kit（太重）。**
- **来源**：`aicode.md` B4,B5（`env-common.sh:613,622-634`、`provision.sh`）／`coomi.md` §3.5.3（CoomiDev，**反向结论**）
- **合并掉了**：场景库 + provision 入口 + 版本号 marker 机制 → 一个交付批次。

### M-10 · Provider 容错
- **唯一方案**：**双层重试**——① 传输层：网络错误 / 可重试状态码，指数退避且 `Retry-After` 优先；② 回合层：段内未 commit 任何产出才重试（重试安全性保证）。外加三条：③ **400 响应按错误体文本判断被拒字段**（reasoning / capability / 图片），摘掉后重试一次；④ `Content-Type: text/html` 直接判不可重试（风控页快速失败）；⑤ `onEvent` 回调抛异常判**不可重试**（防重复计费）。重试对用户透明：发 `ConnectionRetry(attempt, max, delay)` 事件，UI 显示「网络/上游暂时不可用，正在恢复（n/N）」。
- **来源**：`coomi.md` A3,A4（`provider.rs:948-1178` 400 降级 / `agent.rs:482-538` 三层重试）／`zafiro.md` A6,A7,A8（双层重试 / idle / HTML 快速失败）／`rikkahub.md` A8（KeyRoulette）／`aicode.md` E1（多 Key + 冷却）
- **合并掉了**：coomi 的「三层」与 zafiro 的「双层」→ 统一为双层（coomi 的「上下文超限→压缩重试」并入 M-13）。

### M-11 · 本地模型工具暴露降级
- **唯一方案**：**阈值驱动**（不是无条件降级）。`ToolRegistry.list()` 的可见集按当前 provider 决定：当 `isLocalProvider == true` **或** 工具总数 > 12 时，只暴露**核心集 + `tool_search` + `tool_proxy`**；其余进 `HiddenToolCatalog(name, description, keywords, paramHints)`。`tool_search(query)` 用关键词包含 + 描述打分（**无向量依赖**）返回 top-6；`tool_proxy(name, argsJson)` 转发真实工具。local-llama 路径另加 `ToolCallRepairer`：JSON 解析失败先补括号 → 再正则捞最后一个完整调用 → 都失败则回灌 `ToolMessage(isError=true, "上次工具调用格式不合法，请重新输出完整 JSON")`，**最多 2 次**，第 3 次终止循环。
- **来源**：`operit.md` #3,#7（`CliToolModeSupport.kt:21-31` / `buildTruncatedToolRepairSuffix`）／`coomi.md` §3.5.1（**反向**：外部命令兜底）／`zafiro.md` §1（**反向**：无本地模型）
- **合并掉了**：「降级」+「修复器」两条 → 一个批次；**不照搬 operit 的「只给 2 个」**（我们当前仅 5 个内置工具，见 §2.4 裁决）。

### M-12 · 权限模型
- **唯一方案**：**两套语义分开，不要混**：① **终端身份**走 operit **严格模式**——用户选了哪级就用哪级，不可用时**直接失败并说明原因，不静默降级**（防「以为提权了其实没有」）；② **权限获取**走 zafiro **降级链**——`Permission` × `Channel`，`PermissionEngine`（纯 Kotlin、可单测、带完整 `attempts` 轨迹）按链 `SHIZUKU → SYSTEM_DIALOG → JUMP_SETTINGS` 依次尝试；③ `PermissionSpec` 作为**全仓唯一的 `Build.VERSION.SDK_INT` 分叉点**，收拢散落判断；④ Shizuku 授权后**必须 `verify()` 复查真实状态**（退出码 ≠ 权限状态）；⑤ 执行前 `ExecutionRules`（用户可配正则规则 + ALWAYS/LOCKED_ONLY/CONFIRM/DISABLED 四模式 + shell 嵌套载荷挖掘深度 8）。
- **来源**：`zafiro.md` C1-C5（`PermissionSpec:35-73` / `PermissionEngine:9-63` / `ShellGrants:9-10` / `ToolExecutionPreflight:98-125`）／`operit.md` #6,#13（Hook 7 回调 / 三级权限 ALLOW-ASK-FORBID）／`aicode.md` §3.2.5（五步评估，**按「程序名+子命令」记忆**）／`coomi.md` §3.4.5（Hook + SecurityPolicy）
- **合并掉了**：四家的权限表述 → 一张「身份严格 / 获取降级」的双轨表 + 一个规则引擎。

### M-13 · 上下文管理与压缩
- **唯一方案**：**滞回（阶梯）截断**替代滑动窗口——越过阈值才截断且一次前进 `stride` 条，之后连续多轮截断点不动（**前缀稳定 → 命中云端 prompt cache、省本地 prefill**）；截断点用 `alignContextStart` 回退到安全边界，**避免把 tool call 与其结果拆散**。token 估算：`bytes/4` + 每条消息 32 + 每 tool call 24，**图片按固定成本（约 `85*4` 字节）不计 base64**（防一张截图就触发压缩）。
- **来源**：`rikkahub.md` A6（`Message.kt:161-249`，`CONTEXT_KEEP_RATIO=0.5`）／`coomi.md` A7（`context.rs:184-218`）／`operit.md` §3.1.5（阈值 0.70 触发总结）
- **合并掉了**：三家的「估算 / 截断 / 压缩」→ 先做**估算 + 滞回截断**（1d），LLM 摘要压缩留 P2（T37）。

### M-14 · 技能体系
- **唯一方案**：**系统提示词只放清单**（id / name / description），真正文由 `load_skill` 工具按需返回，截断时附绝对路径让模型用 shell 自取；提示词里标注 `## Skills (mandatory)` 并用强制措辞（"Err on the side of loading"）。可选增强：与用户输入做词元交集打分，一轮最多注入 3 个、64 KiB 预算、写审计到 `AgentLogger`。
- **来源**：`zafiro.md` D1,D2（`PromptComposer.kt:104-119` + `LoadSkillBuiltin.kt:94-107`）／`aicode.md` B8（三层来源 + 清单/正文分离）／`coomi.md` A12,A13（打分路由 + frontmatter 兼容）
- **合并掉了**：zafiro「清单+按需」、AiCode「三层来源」、Coomi「打分限流」→ 首期只做**清单 + load_skill**（0.5d），打分与项目级来源留 P2。

### M-15 · 文件编辑与写保护
- **唯一方案**：新增 `edit_file`（`old_string` / `new_string` / `replace_all`，`edits` 数组**整批原子**：内存全算完，任一不匹配则整体不写盘）——**用精确字符串匹配而非行号**（行号在连续编辑时会漂移）。配套双层保护：`WorkspaceFileLock`（路径互斥）+ `FileVersionRegistry`（`read_file` 时登记 sha256，写前在锁内重读比对，不一致返 `STALE_FILE` 并提示「先重新 read_file」）。
- **来源**：`aicode.md` A4,A5,A6,A7（`EditFileTool.kt:37-56` / `FileVersionRegistry` / Checkpoint 懒快照 / `LineDiff` LCS）／`coomi.md` §3.5.3 工具表（`edit_file` 要求先 read_file）
- **合并掉了**：「编辑工具」「写保护」「懒快照」「diff」四条 → 拆成 T20/T21/T29/T30 四个可独立验收的改造项。

### M-16 · 模型能力与选择器
- **唯一方案**：净室写 `core/ModelAbilityInfer.kt`（约 150 行，纯 Kotlin）：`tokenize(modelId)` 切成字母块/数字块/符号块 → 规则表 `Rule(tokens, notTokens, abilities, inputModalities)` → 取 score 最高者。**不维护模型表**，新模型发布当天即可推断「工具 / 推理 / 视觉」。消费方：模型卡片打能力 Tag + 决策是否下发 temperature + 是否启用视觉。
- **来源**：`rikkahub.md` A3,A4,A5（`ModelRegistry` + `ModelDsl.kt:161-229` / ModelListSheet UX / modelSnapshot）／`coomi.md` §3.1.7（`capabilities.rs` 能力画像 + `inferred` 三态）／`aicode.md` E3（models.dev 单价表）
- **合并掉了**：「能力推断」「选择器 UX」「模型快照」→ 首期做推断 + 能力 Tag（0.5d）；收藏/拖拽、单价表留 P2。

### M-17 · 多 Key 与自定义 Header
- **唯一方案**：Key 框支持多把（按 `[\s,]+` 切分去重），`KeyRoulette.next(keys, providerId)` 按 LRU 取（优先「从未用过」，否则「最久未用」），失败切走并按 `keyCooldownMinutes` 冷却；命中 `keySwitchStatusCodes` 即切换。另加 `customHeaders: Map<String,String>`（值支持 `{{API_KEY}}` 占位符，发送前替换）。
- **来源**：`rikkahub.md` A8,A14／`coomi.md` §3.1.1（`api_keys` 轮换）+ A10／`aicode.md` E1,E4（加权 + 冷却 + 每 provider 独立代理）
- **合并掉了**：三家的多 Key 表述 → 一条（P2，不做加权与独立代理）。

### M-18 · 备份与原子写
- **唯一方案**：配置与会话落盘统一「写 `.tmp` → `rename`」，大改前先备份；备份 ZIP 用 `java.util.zip`（JDK 自带）走**「暂存 → 校验 → 发布」两阶段**，内容 = `settings.json` + `conversations.json` + `memory.json`；DataStore 损坏时先复制 `.corrupt-{ts}` 留档再重建。
- **来源**：`rikkahub.md` A15（`BackupManager:36-147` / `PreferencesStore:60-70`）／`coomi.md` A21（tmp+rename）／`aicode.md` D5（资产提取三策略）
- **合并掉了**：备份 + 原子写 + 资产提取三策略 → 一条 P2。

### M-19 · UI 架构与 MVI
- **唯一方案**：新增 `ui/base/AkMviViewModel.kt`（`<Intent, State, Effect>`，`Effect` 用无 replay 的 `SharedFlow`），并把 **State/Effect 判据写进开发规范注释**：*"换个时间再读还成立吗"*——列表数据、弹窗开关是 State；Toast、聚焦、退出页面是 Effect。导航补两点：每 entry 独立 `ViewModelStore`、push/pop 加 300ms 防抖。Ak* 组件新增必须配 `@Preview`。
- **来源**：`zafiro.md` E1,E2,E3（`ComposeMVIViewModel:24-113` / AGENTS.md:41-43 / `NavigationController:64-190`）／`aicode.md` §2.4（大屏双栏 `WorkbenchPane`）
- **合并掉了**：MVI + 导航 + 组件纪律 → 一条 P1；双栏留 P2（T46）。

### M-20 · 日志与可观测
- **唯一方案**：`AgentLogger` 补**落盘轮转**（`filesDir/logs/<进程>-<MMdd-HHmmss>.log`，单文件 8MB 换下一个、最多 10 个）；配置类 `toString()` 强制脱敏 apiKey（**全仓搜索确认无任何路径把明文 Key 写进日志或崩溃栈**）；模型请求失败时抛 `ConfigError(targetRoute)`，UI 转成可点击提示直接跳 `ModelConfigScreen`。
- **来源**：`zafiro.md` F1／`coomi.md` A19,A20（`configuration_required{route}` / Debug 脱敏）／`operit.md` #10（LogHook）
- **合并掉了**：日志落盘 + 脱敏 + 配置缺失导向修复页 → 一条（含 P0 合规子项 T17）。

---

## 2. 冲突裁决

> 不同仓库给出相反建议时，明确取舍并说明理由。

### 2.1 Agent 主循环：无界递归 vs 有界 `repeat(16)`

| 方案 | 主张 |
|---|---|
| `operit.md` #17 | `sendMessage → processStreamCompletion → handleToolInvocation → processToolResults → 递归`，**无硬编码最大轮数**，靠 token 阈值 0.70 与 `isConversationActive` 软停 |
| `zafiro.md` §3.2.2 | `while(true)` + 停止原因（Stop/Length/IdleTimeout）终态机 |
| AgentKit 现状 | `core/AgentLoop.kt:68` `repeat(maxSteps = 16)` |

**裁决：保留有界 `maxSteps`，不学无界递归。** 理由：
1. 我们的 Provider 是**按量计费的云端 3 家 + 手机端 llama.cpp**，无界递归的最坏情况是**烧钱 / 手机发烫卡死**，而 Operit 的软停依赖「token 阈值」这一单点，阈值算错即失控；
2. 有界循环**可观测性更好**：能明确告诉用户「已达最大步数，任务可能未完成」（现状 `AgentLoop.kt:110` 已有该文案）；
3. 但**吸收 Operit 的两个软机制**：① 每轮结束复查 `shouldContinue()` 支持 UI 中途取消；② 达上限时回灌一条结构化提示让模型**自我总结而非戛然而止**。
4. `maxSteps` 从常量升为**可配置项**（默认 16，本地模型场景建议降到 8）。

### 2.2 工具表达：内部 XML vs JSON 工具协议 vs 类函数调用文本

| 方案 | 主张 | 适用前提 |
|---|---|---|
| `operit.md` §3.1.1 / #16 | 内部统一 XML 为 canonical form，外部按 Provider 做协议转换 | 需同时支持 39 家 Provider + 2 个本地引擎 |
| `zafiro.md` §3.2 / `aicode.md` §3.2.4 | 原生 `tool_calls` JSON Schema | Provider 都支持原生 tool_calls |
| `operit.md` §3.3.2 PhoneAgent | `do(action="Tap", element="[x,y]")` 类函数调用文本 | 视觉小模型生成结构化 JSON 能力弱 |

**裁决：主路径采纳 zafiro 的原生 `tool_calls` JSON。** 理由：
1. 我们只有 3 家云端（openai/claude/gemini）+ local-llama，**全部支持原生 tool_calls**，XML 的收益（一次转换适配 39 家）在我们这儿不成立，反而徒增一层解析与截断修复复杂度；
2. 但**局部吸收另两种**：① **local-llama 提示词兜底路径**保留一个极简 XML/JSON 修复器（T16 的 `ToolCallRepairer`）——这正是 Operit 踩过的坑；② **若做 UI 子代理（T28，P1）**，其视觉小模型步采用类函数调用文本，理由与 Operit 一致（小模型 JSON 能力弱）。
3. 一句话：**按模型能力选协议，主路径不搞双协议。**

### 2.3 构建路线：容器内 Build Kit vs 容器内 Gradle vs 推 GitHub CI

| 方案 | 主张 |
|---|---|
| `coomi.md` §3.5.3 | CoomiDev 自定义迭代：手机装 ARM64 Build Kit + Android SDK，容器内跑 gradle |
| `aicode.md` §3.6.3 | 容器内装 Android SDK，用户在终端跑 `gradlew assembleDebug`，App 只做安装 |
| AgentKit 现状 | `core/build/`（BuildEngine + GitHubBuildService：推 GitHub → CI → 拉回 → 安装） |

**裁决：保留 AgentKit 的「推 GitHub → CI → 拉回 → 安装」，不退回容器内编译。** 理由：
1. 手机上跑 Gradle 的编译耗时与耗电是**体验灾难**（对标「体验好」这一最高权重目标直接冲突），且占满内存易触发 LMK 杀进程；
2. 我们的 CI 链路**已闭环且有二进制验证**（任务 #65/#68）；
3. 但从两份里各借一件东西：① AiCode 的 **`canRequestPackageInstalls` 预检 + `FLAG_ACTIVITY_NEW_TASK`**（没有这行会「点了安装没反应」）；② Coomi 的**构建链就绪检测**（`build_kit_ready`）→ 落地为「推 GitHub 前预检 git remote / 凭据 / 工作区干净」。

### 2.4 本地模型工具暴露：只给 2 个 vs 全量 schema

| 方案 | 主张 |
|---|---|
| `operit.md` #3 | CLI 模式**只暴露 2 个工具**（`search` + `proxy`），其余进隐藏目录 |
| AgentKit 现状 | `ToolRegistry.list()` 全量下发（当前 5 个内置 + UI 自动化若干 + MCP + Skill） |

**裁决：采纳降级，但改为「阈值驱动」，不照搬「只给 2 个」。** 理由：
1. Operit 的极端降级是因为它有 **160~180 个工具**；我们当前内置只有 **5 个**，此刻硬降到 2 个会**砍掉刚需能力、徒增一轮 `tool_search` 往返**；
2. 但增长是确定的（MCP 接入后工具名会暴增、Skill 会注册工具），所以做成**自动触发**：`isLocalProvider == true` **或** 工具总数 > **12** 时启用 CLI 模式，只暴露「核心集（read_file / write_file / edit_file / bash / list_dir）+ `tool_search` + `tool_proxy`」；
3. 阈值做成常量 `CLI_MODE_TOOL_THRESHOLD = 12`，可在设置里覆盖。**这样既不在今天过度设计，也不会在工具涨到 40 个时本地模型彻底不可用。**

### 2.5 终端身份：严格模式 vs 降级链（看似冲突，实为两层）

| 方案 | 主张 |
|---|---|
| `operit.md` #4 | **严格模式**：用户选了哪级就用哪级，不可用时直接失败并说明原因，**不静默降级** |
| `zafiro.md` C1 | 权限获取按 `ROOT_SHELL → SHIZUKU → SYSTEM_DIALOG → JUMP_SETTINGS` 链依次降级 |

**裁决：两者都要，但作用域不同——执行用严格、获取用降级。** 理由：
1. **终端/命令执行**关乎"我以为提权了其实没有"的**安全语义**，必须严格（否则 `su` 命令在 user 会话静默执行，用户得到错误的安全预期）；
2. **权限获取**关乎"能不能用起来"的**可用性**，必须降级（ROM 差异极大，单通道必然失败）；
3. 这条裁决化解了表面冲突：**同一个 `ShizukuBackend` 在「获取授权」时走链，在「执行命令」时严格判身份不匹配即报错**（T08/T23 分别落地）。

### 2.6 proot 二进制落位：`assets→filesDir` vs `jniLibs→nativeLibraryDir`

- `aicode.md` §3.4.1 明确：**rootfs 可在 assets→filesDir，但 proot 二进制必须在 jniLibs → `nativeLibraryDir`（唯一允许 `execve` 的 SELinux 位置）**，且必须 `useLegacyPackaging = true`。
- AgentKit 现状：`assets/bin/arm64-v8a/proot` → 提取到 `filesDir/bin/proot` + `chmod 0700`（`ProotRunner.kt:27,30,52`）。

**裁决：不预先改造，先验证（T09，Phase 1 第一个做）。** 理由：
1. 改造代价高（要改打包形态、`useLegacyPackaging`、命名改 `libproot.so`），而 AiCode 为绕此问题把 `targetSdk` 锁 28（**上不了 Google Play**），这个代价我们不愿付；
2. 先做 1 天的真机/多版本实测：Android 10+ 上 `filesDir` 执行 proot 是否被 W^X/SELinux 拦；
3. **只有实测失败才改造**，且改造时保持 targetSdk 不变（用 `nativeLibraryDir` 方案本身不需要降 targetSdk）。

### 2.7 其余裁决速查

| # | 冲突 | 裁决 | 理由 |
|---|---|---|---|
| 7 | `rikkahub.md` / `aicode.md` 复用 Termux 终端模块 vs AgentKit 自研 `TerminalEmulator.kt` | **保留自研** | 引入 JitPack 依赖违反零新增红线；自研 VT100 状态机已可用 |
| 8 | `zafiro.md` Chaquopy Python vs AgentKit `rhino 1.7.14` | **走 rhino（JS）** | Chaquopy 体积大且需新增依赖；rhino 已在红线内 |
| 9 | `coomi.md` 白名单消毒（Web 侧 DOMPurify）vs `aicode.md` 引 `markdown-renderer-m3` vs 自研 | **自研极简 → `AnnotatedString`，不解析 HTML** | 零依赖；Compose 侧无 DOMParser，不解析 HTML 就根本没有注入面 |
| 10 | `coomi.md` / `aicode.md` 为容器能力锁 `targetSdk 28` vs AgentKit 正常 targetSdk | **绝不降级** | 锁 28 的代价是上不了 Google Play，与产品目标冲突 |
| 11 | `operit.md` 39 家 Provider 铺量 vs AgentKit 3 家 + local | **不铺量** | 产品目标未要求；靠「OpenAI 兼容 + baseUrl 可改」覆盖中转站即可 |
| 12 | `coomi.md` 无界重试 vs `zafiro.md` 回调异常判不可重试 | **采纳 zafiro** | 回调异常若可重试会导致同一次 LLM 请求被重发（重复计费 / 重复事件） |
| 13 | `rikkahub.md` 升级 Kotlin 2.4.20 / Compose BOM 2026.09 vs 保持 1.9.25 / 2024.09 | **不升级** | 风险大于收益；除非某个 P0 项强依赖新 API（目前无） |

---

## 3. 综合优先级排序（投入产出比 × 产品目标贡献）

> 图例：目标列 E=体验好、C=能写代码、L=本地模型、B=推GitHub/做安装包、G=写游戏、M=媒体。
> 红线列：✅ = 只用 Kotlin 标准库 + 协程 + 已有 okhttp 4.12.0 / kotlinx-serialization-json 1.6.3 / rhino 1.7.14。

### P0 —— Must have（17 项，约 **22.5 人日**）

| # | ID | 改造项 | 来源 | 人日 | 主要贡献 | Phase | 红线 |
|---|---|---|---|---:|---|---|---|
| 1 | **T09** | proot 落位验证 + 环境变量对齐 + `buildInvocation()`（argv[0]） | `aicode.md` D1,D2,C4,C5 | 2.0 | C/B/L | 1 | ✅ |
| 2 | **T01** | Agent 运行时内核抽象（`core/agent/` 契约层 + RealAgentLoop 最小版） | `zafiro.md` A1 / `operit.md` #1,#6 / `coomi.md` A1 | 3.0 | E/C/L | 1 | ✅ |
| 3 | **T07** | 终端后端抽象（TerminalBackend + TerminalIdentity + 2 个后端） | `zafiro.md` B1 / `operit.md` #4 | 1.5 | C/B | 1 | ✅ |
| 4 | **T08** | 终端会话池 + 4 位短句柄 + 身份固化 + 僵尸清理 | `zafiro.md` B2,B3 / `operit.md` #4 | 1.5 | C/B | 1 | ✅ |
| 5 | **T10** | 非交互 env（`CI=true NO_COLOR=1 PAGER=cat`）+ 位置参数传命令 | `rikkahub.md` A13 | 0.5 | B（卡死根因） | 1 | ✅ |
| 6 | **T02** | 工具结果统一契约 `{ok,code,message,hint,data}` + hint 必填 | `zafiro.md` A3 / `operit.md` #1 | 1.0 | E/L | 1 | ✅ |
| 7 | **T05** | 可取消 + 树尾闭合（`onInterrupt` 补全 ToolResult） | `zafiro.md` A4,A5 / `operit.md` #17 | 0.5 | E（线上 bug 预防） | 1 | ✅ |
| 8 | **T04** | 失败熔断 + 调用指纹去重（同指纹最多真跑 2 次） | `coomi.md` A1 | 1.0 | E/L | 1 | ✅ |
| 9 | **T03** | 并发判据改为「本批是否含写操作」+ `readOnly` 标注 | `coomi.md` A2 / `operit.md` #8 | 1.0 | E/C | 1 | ✅ |
| 10 | **T06** | 工具结果体积上限（24K）+ 未知工具模糊匹配回喂 | `operit.md` #9,#11 / `zafiro.md` A4 | 0.5 | E/L | 1 | ✅ |
| 11 | **T11** | Markdown 极简渲染 → `AnnotatedString` | `coomi.md` A5 / `aicode.md` A8 | 2.0 | **E（感知最强单项）** | 2 | ✅ |
| 12 | **T12** | 消息分支（MsgNode + selectIndex + ◀n/m▶） | `rikkahub.md` A2 / `zafiro.md` F2 | 1.5 | **E** | 2 | ✅ |
| 13 | **T16** | 本地模型工具暴露降级（阈值驱动）+ `ToolCallRepairer` | `operit.md` #3,#7 | 2.5 | **L（决定性）** | 2 | ✅ |
| 14 | **T15** | Provider 400 字段降级 + HTML 快速失败 + 传输层重试 | `coomi.md` A4 / `zafiro.md` A6,A8 | 2.0 | E | 2 | ✅ |
| 15 | **T14** | 上下文滞回截断 + tool 配对保护 | `rikkahub.md` A6 / `coomi.md` A7 | 1.0 | L/C | 2 | ✅ |
| 16 | **T13** | 模型能力推断 `ModelAbilityInfer` + 能力 Tag | `rikkahub.md` A3 / `coomi.md` §3.1.7 | 0.5 | E/L | 2 | ✅ |
| 17 | **T17** | 密钥脱敏复核 + 日志落盘轮转 | `coomi.md` A20 / `zafiro.md` F1 | 0.5 | 合规 | 2 | ✅ |

### P1 —— Should have（18 项，约 **33.5 人日**）

| ID | 改造项 | 来源 | 人日 | 主要贡献 | Phase |
|---|---|---|---:|---|---|
| **T23** | 权限门面 + 降级链 + `PermissionSpec`（唯一 SDK 分叉点） | `zafiro.md` C1,C2,C3 | 2.0 | E/C | 2 |
| **T25** | `bash` / `terminal` 双工具 + `notify` 唤醒下一轮 | `aicode.md` C1,C2,C6 | 3.0 | **E/B（分水岭）** | 2 |
| **T24** | 终端命令执行原语（commandId 事件流 + 超时可中断） | `operit.md` #5 | 2.0 | C/B | 2 |
| **T31** | 工具 Hook 生命周期 + `DangerousOpHook` + `LogHook` | `operit.md` #6 | 1.5 | 安全 | 2 |
| **T34** | Provider 无状态化（`clientFor(providerId)` + 注册表） | `rikkahub.md` A1 | 1.5 | E | 2 |
| **T33** | 会话级配置快照 `ConversationConfig` | `rikkahub.md` A7 | 1.0 | E | 2 |
| **T35** | MVI 基类 `AkMviViewModel` + 导航防抖 + 独立 ViewModelStore | `zafiro.md` E1,E2 | 1.0 | E | 2 |
| **T18** | **提示词资产化**（编号片段 + `{{VAR}}` + 稳定/易变层 + prompts.custom） | `aicode.md` B1,B2 / `zafiro.md` D4 | 3.0 | C/L/G | 3 |
| **T19** | **场景库 v1**（base/node/python/java/go + env-common + 容器页入口） | `aicode.md` B4,B5 | 3.0 | **C/G/L** | 3 |
| **T20** | `edit_file` 精确字符串匹配 + `edits` 整批原子 | `aicode.md` A4 | 1.5 | **C** | 3 |
| **T21** | 双层写保护（`FileVersionRegistry` + `WorkspaceFileLock` + STALE_FILE） | `aicode.md` A5 | 1.5 | **C** | 3 |
| **T26** | 文件探索三件套（`list_dir` / `search_code` / `@` 提及） | `aicode.md` A3 | 2.0 | **C** | 3 |
| **T27** | 极简语法高亮（10 语言，异步 500 行分块） | `aicode.md` A1 | 2.0 | E/C | 3 |
| **T28** | UI 子代理循环（`maxSteps=12` + 只留最新 1 张截图 + `take_over`） | `operit.md` #2 | 3.0 | E/C | 3 |
| **T22** | 技能清单 + `load_skill` 按需加载 + 强制加载语义 | `zafiro.md` D1,D2 | 0.5 | L | 3 |
| **T32** | JS 工具桥 `Tools.*`（rhino 单通道，禁 Java 反射桥） | `operit.md` #10 | 2.0 | C | 3 |
| **T29** | Checkpoint 懒快照 + 按 sessionId 隔离 + `skipped` 清单 | `aicode.md` A6 | 1.5 | C | 3 |
| **T30** | `LineDiff` LCS + 聊天 diff 卡片 | `aicode.md` A7 | 1.5 | C/E | 3 |

### P2 —— Nice to have（15 项，约 **21.3 人日**，有余力再做）

| ID | 改造项 | 来源 | 人日 |
|---|---|---|---:|
| **T36** | 元工具：Agent 用 rhino 自造 JS 工具并**回合内热注册**（依赖 T01） | `zafiro.md` D5 | 2.5 |
| **T37** | 上下文压缩（检查点 + LLM 摘要 + 图片固定成本估算） | `coomi.md` §3.2.4 / `operit.md` §3.1.5 | 2.0 |
| **T38** | 多 Key 轮换 + 冷却 + 状态码切换 | `rikkahub.md` A8 / `aicode.md` E1 | 1.0 |
| **T39** | 自定义 Header / Body（含 `{{API_KEY}}` 占位符） | `rikkahub.md` A14 / `aicode.md` E4 | 1.0 |
| **T40** | 备份 ZIP 导出/导入（暂存 → 校验 → 发布两阶段） | `rikkahub.md` A15 | 1.0 |
| **T41** | 记忆改 Markdown 文件树 + `version` 乐观锁 | `rikkahub.md` A9① | 1.5 |
| **T42** | 记忆空闲自动 Consolidation（idle 2min） | `rikkahub.md` A9② | 2.0 |
| **T43** | 助手预设 `AgentPreset`（name/systemPrompt/modelId/temperature 四字段） | `rikkahub.md` A10 | 1.0 |
| **T44** | 分功能模型 CHAT / AUX | `operit.md` #12 | 1.5 |
| **T45** | 三级权限策略 ALLOW/ASK/FORBID + 全局默认 | `operit.md` #13 | 1.0 |
| **T46** | 大屏双栏 `WorkbenchPane`（EDITOR/TERMINAL/GIT 右栏） | `aicode.md` §2.4 | 2.0 |
| **T47** | 悬浮球后台可见（分钟级任务跟进度） | `zafiro.md` E4 | 1.5 |
| **T48** | 项目规则自动读取（`AGENTS.md` > `.cursorrules`，16K 截断） | `aicode.md` B9 | 1.0 |
| **T49** | Git 全线 `-c core.quotepath=false` + 凭据文件 IPC 回源（base64+反转混淆） | `aicode.md` D4,D6 | 2.0 |
| **T50** | 工具线缆名哈希消歧（FNV-1a 后缀） | `zafiro.md` A2 | 0.3 |

---

## 4. 分阶段路线图

> 划分原则：**先结构性地基（运行时内核、工具契约、终端抽象）→ 再体验层（消息分支、Markdown、模型选择器）→ 再能力层（场景库、提示词资产化、媒体增强）**。
> 每个改造项格式：新增/修改哪个文件 · 做什么 · 验收标准（可验证）· 人日 · 红线。

---

### Phase 1 · 结构性地基（目标：把「能跑」变成「跑得稳、可中止、可诊断」）

**阶段目标**：运行时有契约、工具有统一结果、终端有后端与身份、容器命令不再卡死。
**阶段 DoD**：① 现有 weather/wordcount 两个 skill 走新 loop 跑通；② 断网/429/5xx 能自动恢复且 UI 可见；③ 任意时刻点「停止」≤1 轮内退出且历史可继续请求；④ `npm install` 类长命令不再卡在确认提示或分页器。
**合计约 12.5 人日**（全部为 P0）。

---

**T09 · proot 落位验证 + 环境变量对齐 + `buildInvocation()`**（阻塞项，**必须第一个做**）
- **来源**：`aicode.md` D1, D2, C4, C5
- **文件**：`修改 core/container/ProotRunner.kt`（`buildArgs():64-73` → `buildInvocation()`）；`核对 app/build.gradle.kts`（targetSdk / `useLegacyPackaging`）
- **做什么**：① 真机/多版本实测 Android 10+ 上 `filesDir/bin/proot` 是否可被 `execve`（若被 W^X/SELinux 拦，才改造为 `libproot.so` 走 jniLibs + `nativeLibraryDir`，**targetSdk 保持不变**）；② 环境变量补齐 `PROOT_TMP_DIR=<rootfs>/tmp`、`TMPDIR=/tmp`（覆盖宿主指向 App cache 的值）、`PATH` 显式含 `/usr/local/bin`、`GIT_CONFIG_GLOBAL`；③ `buildArgs()` 改为 `buildInvocation()`，返回 `{ executable, argv（**含 argv[0] = proot 二进制本身**）, envMap, envArray }`，其中 `envArray = System.getenv() + env`（PTY 不继承父进程环境，只喂自定义 env 会让 proot 因缺 `ANDROID_ROOT`/`LD_LIBRARY_PATH` 瞬间退出且日志无报错）
- **验收**：① 输出一份实测结论（含设备 Android 版本、是否改造）；② 容器内 `mktemp`、`dpkg`、`/usr/local/bin` 下的自建命令均可执行；③ `buildInvocation()` 有单测断言 `argv[0] == executable`；④ 接 PTY 时无需再改调用点
- **2.0 人日** · 红线 ✅（仅 Kotlin + ProcessBuilder）

---

**T01 · Agent 运行时内核抽象**
- **来源**：`zafiro.md` A1 / `operit.md` #1,#6,#8 / `coomi.md` A1,A2,A3 / `aicode.md` §3.2.4
- **文件**：`新增 core/agent/{AgentLoop, RealAgentLoop, ToolDescriptor, ToolExecutor, Hooks}.kt`；`修改 core/AgentLoop.kt`（改为薄适配层，保留对外 API）、`core/ToolRegistry.kt`、`core/AgentContract.kt`、`ui/ChatViewModel.kt`
- **做什么**：抽出**运行时契约层**（不拆 Gradle 模块）：`AgentLoop` 接口（`send` / `stop` / `update`）；`RealAgentLoop` 实现回合主循环（流式收集 → **整条 Assistant commit**（含 ToolCall）→ 工具执行三段式：解析+beforeToolCall → 并发执行 → 保序 commit → 回喂）；`ToolDescriptor{name, wireName, inputSchemaJson}`；`ToolExecutor.execute()` **永不抛异常**，总产出 `ToolCallOutcome`，`onInterrupt()` 由 executor 自判 `Interrupted` / `Unknown`；`Hooks` 先落 `beforeToolCall` / `afterToolCall` / `beforeStop` / `beforeSerialization` 四时机。UI 只认事件流与状态流。
- **验收**：① weather/wordcount 两个 skill 走新 loop 全流程跑通（含流式 + 工具 + 回喂）；② 并发工具调用结果**保序**；③ `ToolExecutor` 抛异常时回合 `Failed(ToolExecutionFailed)` 而**不回喂模型**（业务 bug 显形）；④ 新增 3 个纯 JVM 单测（保序 / 异常不回喂 / stop 后树尾闭合）
- **3.0 人日** · 红线 ✅

---

**T07 · 终端后端抽象**
- **来源**：`zafiro.md` B1 / `operit.md` #4
- **文件**：`新增 core/terminal/backend/{TerminalBackend, TerminalIdentity, ProotBackend, ShizukuBackend, TerminalBackendFactory}.kt`；`修改 core/container/ContainerSession.kt`、`automation/AutomationBridge.kt`
- **做什么**：`TerminalBackend` 接口 5 方法（`start` / `send` / `close` / `awaitExit` / `output: Flow`） + `TerminalIdentity{User, Su, Shizuku, Ssh}` + `TerminalBackendFactory`（缓存 + `getHighestAvailable()` + `getUserPreferred()` + `clearCache()`）。**首期只实现 2 个后端**：`ProotBackend`（包装现有 `core/container/`）、`ShizukuBackend`（改造现有 Shizuku 通道）；`Su` / `Ssh` 只留接口不实现。
- **验收**：① proot 与 Shizuku 走同一接口，上层代码无 `if (isShizuku)` 分支；② 工厂 `getHighestAvailable()` 有单测（Shizuku 不可用 → 回落 User）；③ 权限变化后 `clearCache()` 生效
- **1.5 人日** · 红线 ✅

---

**T08 · 终端会话池 + 短句柄 + 身份固化**
- **来源**：`zafiro.md` B2, B3 / `operit.md` #4
- **文件**：`新增 core/terminal/TerminalSessionPool.kt`、`core/terminal/ToolOutputTruncator.kt`；`修改 tools/BuiltInTools.kt`（注册 shell 工具）
- **做什么**：会话表 + 异步态 + **每会话一把 `Mutex`**（防并发串输出）+ 待排空通知；对外句柄用 **4 位十六进制短 ID**（`^[0-9a-f]{4}$`，省 token、防泄漏内部 UUID）；**身份在开辟时固化**写入 `META_IDENTITY`，后续传冲突 identity → 显式报错而非静默复用；user / shizuku 分池不串味；僵尸会话自动释放别名重开。
- **验收**：① 多轮 shell 保持 cwd 与环境变量；② 两个并发会话的输出**不串**；③ 声明 `identity=shizuku` 但落在 user 会话时**明确报错**；④ 句柄长度恒为 4
- **1.5 人日** · 红线 ✅

---

**T10 · 非交互 env + 位置参数传命令**
- **来源**：`rikkahub.md` A13
- **文件**：`修改 core/container/ProotRunner.kt`（或 `ContainerSession.kt` 的命令构造处）
- **做什么**：容器内命令执行统一注入 `CI=true NO_COLOR=1 PAGER=cat`（抑制确认提示 / 分页器 / 颜色转义）；命令**通过位置参数传入**而非拼进 shell 字符串：`sh -l -c 'cd -- "$1" && eval "$2"' agentkit "$cwd" "$cmd"`。
- **验收**：① `apt install -y xxx`、`git log`（会进分页器）在容器内执行**不卡住**；② 含空格/引号/通配符的命令（如 `echo "*.vue"`）按字面执行不被二次转义；③ 输出无 ANSI 转义残留
- **0.5 人日** · 红线 ✅（**成本最低、价值最高的一条**）

---

**T02 · 工具结果统一契约**
- **来源**：`zafiro.md` A3 / `operit.md` #1,#11 / `aicode.md` §3.2.4
- **文件**：`新增 core/agent/ToolResult.kt`；`修改 tools/BuiltInTools.kt`、`automation/UiAutomationTools.kt`、`memory/MemoryTools.kt`、`core/ToolRegistry.kt`
- **做什么**：统一返回 `{ok, code, message, hint, data}`；**失败时 `hint` 必填**（教模型下一步该调什么）。结果字符串抽成 `formatToolResult(result)` 单一函数，UI 与模型历史**共用同一份文本**（一份真源、两处复用）。未知工具名做**编辑距离 ≤2 的模糊匹配**，回 `"未知工具 'xx'。你是否想调用 'xxx'？当前可用工具：…"`（列前 20 个）。
- **验收**：① 全仓 `tools/` 下工具返回结构一致，单测覆盖 `hint` 生成；② 同一 result 两次调 `formatToolResult` 结果**完全相等**；③ 模型打错工具名时能从错误里自纠（用 weather skill 实测一次）
- **1.0 人日** · 红线 ✅

---

**T05 · 可取消 + 树尾闭合**
- **来源**：`zafiro.md` A4, A5 / `operit.md` #17
- **文件**：`修改 core/agent/RealAgentLoop.kt`、`core/AgentLoop.kt`
- **做什么**：取消在 `NonCancellable` 内调 `onInterrupt()` **补全所有待决 ToolResult 再重抛**（不信任取消时 `execute()` 的返回值——工具被 kill 后可能返回假成功）。主循环每轮结束复查 `shouldContinue()`，支持 UI 中途取消。**不变量：已 commit 的 Assistant 里的 ToolCall 必须有配对 ToolResult。**
- **验收**：① 流式过程中点「停止」，≤1 轮内退出；② 停止后把历史再发一次请求**不报错**（无未闭合 tool_call）；③ UI 上不存在永远转圈的工具卡
- **0.5 人日** · 红线 ✅

---

**T04 · 失败熔断 + 调用指纹去重**
- **来源**：`coomi.md` A1（`agent.rs:641-708`）
- **文件**：`新增 core/agent/FailureGuard.kt`；`修改 core/agent/RealAgentLoop.kt`
- **做什么**：`fingerprint = hash(name + normalizedArgs)`；`AgentLoop` 持 `MutableMap<String, FailureState>`；执行前判定 `blocked = 同批重复 || requiresChangedCall || executions >= 2`，命中则**直接返回错误结果回喂模型**（"同一调用已失败 N 次，请修改参数"），不走真实执行；成功清指纹，失败累加计数。
- **验收**：① 模型在同一工具同一参数上打转时，第 3 次不再真执行；② 模型能收到错误提示并**改变策略**（用 weather skill 构造错误参数实测）；③ 单测覆盖 `blocked` 三个判据
- **1.0 人日** · 红线 ✅

---

**T03 · 并发判据改为「本批是否含写操作」**
- **来源**：`coomi.md` A2 / `operit.md` #8
- **文件**：`修改 core/AgentContract.kt`（`AgentTool` 加 `readOnly: Boolean`）、`core/agent/RealAgentLoop.kt`；`逐项标注 tools/BuiltInTools.kt`、`automation/UiAutomationTools.kt`、`memory/MemoryTools.kt`
- **做什么**：先算 `hasMutation = 本批任一工具 !readOnly`；**含写则整批串行**，全批只读才 `async + awaitAll`。首批标注：`read_file` / `http_request` / `clipboard` / `ui_screenshot` / `memory_search` 为只读；`write_file` / `send_notification` / `memory_write` / UI 操作类为写。
- **验收**：① 两次 `read_file` 并行（耗时 ≈ 单次）；② 含一次 `write_file` 的批次**整体串行**（日志可见顺序执行）；③ 现有 5 个内置工具全部完成标注，无漏标
- **1.0 人日** · 红线 ✅

---

**T06 · 工具结果体积上限 + 截断提示**
- **来源**：`operit.md` #9 / `aicode.md` C3
- **文件**：`新增 core/agent/ToolLimits.kt`；`修改 core/agent/RealAgentLoop.kt`
- **做什么**：`MAX_TOOL_RESULT_CHARS = 24_000`；每个结果**独立**截断（不可整批截，因为每个调用必须保留对应结果槽位），超长追加 `"\n…[结果过长已截断，共 N 字符]"` 并建议模型改用分页参数。
- **验收**：① `read_file` 一个 100KB 文件，回喂模型的内容 ≤ 24K 且带提示；② 批量 3 个工具调用时**每个都有各自的结果槽位**（不丢、不错位）
- **0.5 人日** · 红线 ✅

---

### Phase 2 · 体验层（目标：让「能用」变成「好用」）

**阶段目标**：渲染正确、可回头、模型能挑、网络抖动自愈、小模型喂得动。
**阶段 DoD**：① 助手回复的标题/列表/代码块/表格正确渲染；② 重新生成不丢历史且可左右切换；③ 断网重连 UI 有「正在恢复 n/N」提示；④ local-llama 下模型只见精简工具集且能完成一次 read_file；⑤ 权限申请一次走完降级链。
**合计约 22.0 人日**（P0 部分 10.0 + P1 部分 12.0，可按迭代拆：**先做 7 项 P0，P1 的 7 项作为第二小批**）。

---

**T11 · Markdown 极简渲染** · P0 · 2.0 人日
- **来源**：`coomi.md` A5,A6 / `aicode.md` A8 / `rikkahub.md` A16
- **文件**：`新增 ui/MarkdownText.kt`；`修改 ui/ChatScreen.kt`（助手气泡）
- **做什么**：手写极简解析 → `AnnotatedString` + `SpanStyle`，支持标题 / 粗体 / 斜体 / 行内码 / 围栏代码块 / 有序无序列表 / 引用 / 链接 / 表格。**不解析任何 HTML**（无注入面）。代码块用 `SelectionContainer` + 等宽字体 + 复制按钮；解析结果按段落 `LinkedHashMap` 缓存（上限 240），流式期间用 `derivedStateOf` 降重组。
- **验收**：① 一段含上述 9 种语法的回复渲染正确；② 回复里含 `<script>alert(1)</script>` 时**原样显示为文本**；③ 代码块可长按复制；④ 长回复滚动不掉帧（帧率用 `JankStats` 或肉眼 + `adb shell dumpsys gfxinfo` 抽查）

---

**T12 · 消息分支** · P0 · 1.5 人日
- **来源**：`rikkahub.md` A2 / `zafiro.md` F2
- **文件**：`修改 core/ConversationStore.kt`、`ui/ChatViewModel.kt`、`ui/ChatScreen.kt`
- **做什么**：`MsgRecord` 外包 `MsgNode { id, records: List<MsgRecord>, selectIndex }`；线性渲染取 `nodes.map { it.records[it.selectIndex] }`；重新生成 / 编辑 = **追加兄弟 + 移动游标**（原消息永不丢）；删除时 `selectIndex.coerceAtMost(lastIndex)`，空则删节点。UI 加「◀ 2/3 ▶」切换器（`records.size > 1` 才显示，到头置灰）。**存量数据用迁移函数包成单元素节点。**
- **验收**：① 对同一条助手消息点「重新生成」3 次，可左右切换看到 3 个版本且原版本未丢；② 杀进程重进后分支关系仍正确（JSON 持久化）；③ 存量会话升级后不崩、不丢消息

---

**T16 · 本地模型工具暴露降级 + ToolCallRepairer** · P0 · 2.5 人日
- **来源**：`operit.md` #3,#7 / 裁决 §2.4
- **文件**：`新增 core/llm/local/ExposureMode.kt`、`core/llm/local/ToolCallRepairer.kt`、`tools/ToolSearchTools.kt`
- **做什么**：**阈值驱动**：`isLocalProvider == true` **或** `ToolRegistry.list().size > CLI_MODE_TOOL_THRESHOLD(12)` 时启用 CLI 模式，只暴露核心集（`read_file`/`write_file`/`edit_file`/`bash`/`list_dir`）+ `tool_search` + `tool_proxy`；其余进 `HiddenToolCatalog(name, description, keywords, paramHints)`。`tool_search(query)` 关键词包含 + 描述打分返回 top-6（无向量）；`tool_proxy(name, argsJson)` 转发真实工具。local-llama 路径加 `ToolCallRepairer`：JSON 解析失败 → 补齐未闭合括号 → 正则捞最后一个完整调用 → 都失败则回灌「格式不合法，请重新输出完整 JSON」，**最多 2 次**，第 3 次终止循环。
- **验收**：① 用 local-llama 时模型可见工具数 ≤ 7；② `tool_search("读文件")` 返回 ≥1 条带参数提示的候选；③ `tool_proxy` 能真正执行 `read_file` 并回传结果；④ 模型吐半个 JSON 时能被修复或得到明确重试提示，连续 2 次失败后终止并提示用户

---

**T15 · Provider 400 字段降级 + HTML 快速失败 + 传输层重试** · P0 · 2.0 人日
- **来源**：`coomi.md` A4 / `zafiro.md` A6,A8 / `operit.md` 裁决 §2.7-12
- **文件**：`新增 core/llm/RequestHardening.kt`；`修改 core/llm/{OpenAIClient, ClaudeClient, GeminiClient}.kt`、`core/agent/RealAgentLoop.kt`、`ui/ChatViewModel.kt`
- **做什么**：① 400 响应**按错误体文本判断**被拒字段（reasoning / capability / 图片），摘掉后重试**一次**；② 响应 `Content-Type: text/html` 直接判不可重试（风控页快速失败）；③ 传输层重试：网络错误 / 可重试状态码，指数退避且 `Retry-After` 优先；④ `onEvent` 回调抛异常判**不可重试**（防重复计费）；⑤ 每次重试发 `ConnectionRetry(attempt, max, delay)` 事件。
- **验收**：① 对不支持 `reasoning_effort` 的中转模型，自动摘字段后重试成功；② 上游返回风控 HTML 页时**立即失败**（不重试 3 次）；③ UI 显示「网络/上游暂时不可用，正在恢复（n/N）」；④ 回调异常场景只产生一次计费请求

---

**T14 · 上下文滞回截断 + tool 配对保护** · P0 · 1.0 人日
- **来源**：`rikkahub.md` A6 / `coomi.md` A7 / `operit.md` §3.1.5
- **文件**：`新增 core/agent/ContextTrimmer.kt`；`修改 core/AgentLoop.kt`（改 `maybeCompact:218`）
- **做什么**：**阶梯式截断**替代滑动窗口——越过 `limit` 才截断且一次前进 `stride` 条（`target = limit*0.5`，`stride = limit - target`），之后连续多轮截断点不动（前缀稳定 → 命中云端 prompt cache、省本地 prefill）；`alignContextStart` 把截断点回退到安全边界（**不拆散 tool call 与它的 result**，带 `visitedIndices` 防死循环）；token 估算 `bytes/4` + 每条消息 32 + 每 tool call 24，**图片按固定成本约 `85*4` 字节**（不计 base64）。
- **验收**：① 纯函数 + 单测（含「截断点落在 tool call 与其 result 之间时被回退」用例）；② 连续 10 轮对话，前 5 轮的请求前缀**完全一致**（可打印 hash 对比）；③ 发一张截图后**不触发**压缩

---

**T13 · 模型能力推断 + 能力 Tag** · P0 · 0.5 人日
- **来源**：`rikkahub.md` A3,A4,A5 / `coomi.md` §3.1.7
- **文件**：`新增 core/ModelAbilityInfer.kt`；`修改 ui/ModelConfigScreen.kt`
- **做什么**：纯 Kotlin ~150 行：`tokenize(modelId)` 切成字母块/数字块/符号块 → 规则表 `Rule(tokens, notTokens, abilities, inputModalities)` → 取 score 最高者。用于模型卡片打「工具 / 推理 / 视觉」Tag + 决策是否下发 temperature。
- **验收**：① `gpt-5` 命中 TOOL+REASONING，`gpt-5-chat` **不**命中（notTokens 生效）；② `o3` / `gpt-4o` / `claude-*` / `gemini-*` 各有正确 Tag；③ 新增一个未知模型 ID 不崩、回落默认

---

**T17 · 密钥脱敏复核 + 日志落盘轮转** · P0（合规）· 0.5 人日
- **来源**：`coomi.md` A19,A20 / `zafiro.md` F1
- **文件**：`修改 core/ConfigSanitizer.kt`、`core/AgentLogger.kt`、`core/ConfigStore.kt`
- **做什么**：全仓搜索确认无任何路径把明文 apiKey 写进日志 / 崩溃栈（配置类 `toString()` 强制脱敏）；`AgentLogger` 补落盘轮转（`filesDir/logs/<进程>-<MMdd-HHmmss>.log`，8MB × 10）；模型请求因配置缺失失败时抛带 `targetRoute` 的错误，UI 转成可点击提示跳 `ModelConfigScreen`。
- **验收**：① grep 全仓 `apiKey` 的所有日志/字符串拼接点均脱敏；② `adb pull filesDir/logs` 能拿到完整日志；③ 删掉 apiKey 后发消息，UI 出现可点击跳转提示而非裸错误

---

**T23 · 权限门面 + 降级链 + PermissionSpec** · P1 · 2.0 人日
- **来源**：`zafiro.md` C1,C2,C3 / 裁决 §2.5
- **文件**：`新增 core/permission/{Permission, Channel, PermissionSpec, PermissionEngine, PermissionManagerImpl, ShizukuGrantVerifier}.kt`；`修改 ui/PermissionsScreen.kt`、散落各处的 `Build.VERSION.SDK_INT` 判断点
- **做什么**：`PermissionEngine` 纯 Kotlin 不依赖 Android，按链 `SHIZUKU → SYSTEM_DIALOG → JUMP_SETTINGS` 依次尝试，返回带完整 `attempts` 轨迹的 `PermissionResult`；`PermissionSpec` 作为**全仓唯一 SDK_INT 分叉点**（NOTIFICATION@33 / STORAGE@R / OVERLAY / ACCESSIBILITY）；Shizuku 授权后**必须 `verify()` 复查真实状态**（退出码 ≠ 权限状态）。
- **验收**：① `PermissionEngine` 有纯 JVM 单测（链上第一环失败 → 自动走第二环）；② 全仓 `Build.VERSION.SDK_INT` 判断收敛到 `PermissionSpec` 一处（grep 验证）；③ 用户拒绝一次后 UI 能展示「我试了什么」

---

**T25 · `bash` / `terminal` 双工具 + `notify` 唤醒** · P1 · 3.0 人日
- **来源**：`aicode.md` C1,C2,C6
- **文件**：`新增 tools/ShellTools.kt`（`bash` + `terminal` 两工具）；`修改 core/terminal/TerminalSessionPool.kt`、`core/agent/RealAgentLoop.kt`（支持外部事件注入触发下一轮）
- **做什么**：`bash` = 一次性同步命令（默认 120s 超时）；`terminal` = **一个工具 + `action` 参数**（`start`/`send`/`key`/`read`/`close`）取代三个工具（少两份 schema = 少 token）。**`notify=true` 反转轮询**：命令退出时系统推事件（含退出码 + 末尾 ~40 行）主动唤醒 AI 下一轮；提示词里明确写「不要轮询或 sleep 等待」。
- **验收**：① `terminal(action=start, notify=true)` 跑 `sleep 30 && echo done`，30 秒后 AI **自动**收到结果并继续（无需用户再发消息）；② `npm install` 超时后转 `status='running'`，用 `action=read` 可取回后续输出；③ 工具数量只增 2 个

---

**T24 · 终端命令执行原语** · P1 · 2.0 人日
- **来源**：`operit.md` #5
- **文件**：`新增 terminal/AgentCommandRunner.kt`；`修改 terminal/TerminalSession.kt`
- **做什么**：`run(sessionId, cmd, timeoutMs): CommandResult`——生成 UUID 作 `commandId`，**先订阅事件流再发命令**（`collectorReady.await()`，避免快命令输出在订阅前丢失），等 `isCompleted` 事件后返回；`runHidden()` 走一次性进程不占会话；超时可中断；支持 Ctrl+C。
- **验收**：① `run_terminal_command("ls -la", 5000)` 拿到完整 stdout + exitCode；② 一条极快命令（`echo hi`）的输出**不丢**；③ `sleep 100` 在 5s 超时后被中断并返回已产出输出

---

**T31 · 工具 Hook 生命周期 + DangerousOpHook** · P1 · 1.5 人日
- **来源**：`operit.md` #6 / `zafiro.md` Hooks
- **文件**：`新增 core/agent/Hooks.kt`、`core/hooks/{DangerousOpHook, LogHook}.kt`；`修改 core/agent/RealAgentLoop.kt`
- **做什么**：7 个回调（默认空实现）；`beforeToolCall` 返回 `Allow` / `Block(reason)`；单 Hook 抛异常不影响其他 Hook，但 **intercept 阶段异常按 Block 处理**。首批落 2 个 Hook：`DangerousOpHook`（`rm -rf /`、写系统分区 → Block 并给可读原因）、`LogHook`（统一走 `AgentLogger`）。
- **验收**：① 模型尝试 `rm -rf /` 被拦下且收到可读原因（非裸异常）；② 故意让 `LogHook` 抛异常，主流程**照常完成**；③ `beforeToolCall` 能改写参数

---

**T34 · Provider 无状态化** · P1 · 1.5 人日
- **来源**：`rikkahub.md` A1
- **文件**：`修改 core/llm/LLMClient.kt` 及三家 Client、`core/ConfigStore.kt`
- **做什么**：把「构造时持有 config」改为「调用时传 config」；新增 `clientFor(providerId): LLMClient` 工厂 + 注册表 `Map<String, LLMClient>`，三套云端 + local-llama 各注册一个无状态实例。**收益：同一供应商可并存多套 Key，为多 Key 轮换铺路。**
- **验收**：① 配两套同名不同 Key 的 OpenAI 配置可同时使用；② `LLMClient` 实现类中无 `val config` 成员（grep 验证）；③ 现有对话流程回归通过

---

**T33 · 会话级配置快照** · P1 · 1.0 人日
- **来源**：`rikkahub.md` A7
- **文件**：`修改 core/ConversationStore.kt`、`core/ConfigStore.kt`、`ui/ChatViewModel.kt`
- **做什么**：会话首条消息落库时拍一份 `ConversationConfig(modelId, temperature, tools)` 存进会话；之后聊天页改配置**只影响当前会话**。失效引用（模型被删）自动回落。
- **验收**：① A 会话改模型后，B 会话的上下文与所用模型**不受影响**；② 删掉某会话固定的模型后，该会话回落且不崩

---

**T35 · MVI 基类 + 导航防抖** · P1 · 1.0 人日
- **来源**：`zafiro.md` E1,E2,E3
- **文件**：`新增 ui/base/AkMviViewModel.kt`；`修改 ui/AppScaffold.kt`（导航）
- **做什么**：`<Intent, State, Effect>` 基类，`Effect` 用无 replay `SharedFlow`；**把 State/Effect 判据写进开发规范注释**（"换个时间再读还成立吗"）。导航补每 entry 独立 `ViewModelStore`（pop 时 `clear()`）+ push/pop **300ms 防抖**。
- **验收**：① Toast 用 Effect 后不再重复弹；② 手速连点 5 次跳转，返回栈只入栈 1 次

---

### Phase 3 · 能力层（目标：让 Agent 真正「能干活」）

**阶段目标**：容器有开发环境、能精确改真实项目、提示词不用发版就能调、有子代理自动跑 UI。
**阶段 DoD**：① 容器页一键装好 node/python/java 并跑通一个 hello world；② AI 能对一个多文件项目做精确 `edit_file` 且不覆盖并发修改；③ 改提示词只需改 `assets/prompts/` 里的 md，无需改 Kotlin；④ 下「打开设置→找到版本号→截图回来」这类 3 步任务能自动跑完。
**合计约 21.5 人日**。

> **总量核对**：P0 **22.5** + P1 **33.5** = **56 人日**（Phase 1 12.5 + Phase 2 22.0 + Phase 3 21.5 = 56.0，闭合）；P2 21.3 人日另计。

---

**T18 · 提示词资产化** · P1 · 3.0 人日
- **来源**：`aicode.md` B1,B2,B9 / `zafiro.md` D4
- **文件**：`新增 assets/prompts/{00-identity,10-communication,20-coding-discipline,50-safety,60-tools-and-paths,99-runtime-context}.md`、`core/prompt/{PromptCatalog, PromptResolver, SystemPromptBuilder}.kt`；`修改 core/AgentLoop.kt`（抽出硬编码 system prompt）、`core/container/RootfsManager.kt`（启动时释放）
- **做什么**：两位数字编号即身份即顺序（`Regex("^(\\d{2})-(.+)\\.md$")`）；启动时把 `assets/prompts/` 释放到 `filesDir/prompts`（**每次覆盖**），用户覆盖写 `filesDir/prompts.custom/`（**永不删除**）；动态内容走 `{{VAR}}` 并集中在最后的 `99-runtime-context.md`（先做 `{{SKILLS}}` `{{MEMORY}}` `{{WORKSPACE}}` `{{DATE}}`）；**稳定层 / 易变层二分**（记忆快照进易变层）。首期只做两层（项目 / 全局）。
- **验收**：① 改 `filesDir/prompts.custom/50-safety.md` 后**重启即生效，无需改 Kotlin**；② `{{DATE}}` 被正确替换且未写变量的片段原样保留；③ 稳定层内容在前 N 轮**逐字节不变**（打印 hash 对比）

---

**T19 · 场景库 v1** · P1 · 3.0 人日
- **来源**：`aicode.md` B4,B5 / `coomi.md` §3.5.3（反向）
- **文件**：`新增 assets/env/scenarios/{base,node,python,java,go}.sh`、`assets/env/env-common.sh`、`assets/env/provision.sh`；`修改 ui/ContainerScreen.kt`（加「环境安装」入口）
- **做什么**：每个场景脚本**只暴露两个 shell 函数** `scenario_name_<id>` / `install_scenario_<id>`，由 `SCENARIO_IDS` 列表驱动菜单（按函数名前缀分发）；`env-common.sh` 做包管理器探测（apk/apt/dnf/pacman）+ 国内镜像源替换 + 运行时版本探测；`provision.sh` 带 `PROVISION_VERSION` 常量与 marker 文件比对（脚本改了才重跑）。**不学 CoomiDev 手机端 Build Kit。**
- **验收**：① 容器页一键安装 node 后 `node -v` 有输出；② 装 python 后 `pip install requests` 走国内镜像成功；③ 改一行 shell 并 `PROVISION_VERSION += 1`，存量设备重进会重跑（且不重解压 rootfs）

---

**T20 · `edit_file` 精确字符串匹配** · P1 · 1.5 人日
- **来源**：`aicode.md` A4 / `coomi.md` §3.5.3
- **文件**：`新增 tools/EditFileTool.kt`；`修改 tools/BuiltInTools.kt`（注册）、`core/ToolRegistry.kt`
- **做什么**：参数 `old_string` / `new_string` / `replace_all` + `edits` 数组；**整批原子**（内存里全算完，任一不匹配则整体不写盘）。**用精确字符串匹配而非行号**——行号在连续编辑时会漂移。一个语义覆盖替换 / 删除 / 插入三种。
- **验收**：① 对同一文件连续 3 次 `edit_file` 不出现行号漂移导致的错位；② `edits` 里第 2 条不匹配时，第 1 条**也不写盘**；③ `old_string` 在文件中出现多次且未指定 `replace_all` 时，明确报错而非静默替换第一个

---

**T21 · 双层写保护** · P1 · 1.5 人日
- **来源**：`aicode.md` A5
- **文件**：`新增 core/workspace/{FileVersionRegistry, WorkspaceFileLock}.kt`；`修改 tools/BuiltInTools.kt`（`read_file` 登记指纹、`write_file`/`edit_file` 写前校验）
- **做什么**：`ConcurrentHashMap<path, sha256>` + `ConcurrentHashMap<path, Mutex>`；**`read_file` 必须登记指纹**（这是拿到保护的前提）；写前**在锁内重读**比对，不一致返 `STALE_FILE` 并明确告诉模型「先重新 read_file」。
- **验收**：① 模拟「AI 读到旧内容后，外部改了文件再写」，得到 `STALE_FILE` 而非覆盖；② 并发两个 `write_file` 同一路径，串行完成且内容不交错；③ 错误信息里含「先重新 read_file」的指引

---

**T26 · 文件探索三件套** · P1 · 2.0 人日
- **来源**：`aicode.md` A3
- **文件**：`新增 tools/FileExploreTools.kt`（`list_dir` / `search_code`）、`ui/ChatScreen.kt`（`@` 提及）
- **做什么**：`list_dir`（宿主 `File.walk` 有限深度 + 简易 `.gitignore` 匹配，`MAX_ENTRIES=500` 保护）；`search_code`（容器内 `rg`，缺 rg 时降级为 Kotlin 子串搜索，**只允许 `| head` 管道**）；`@` 文件提及（`rg --files` 拉清单 + 内存模糊匹配，清单**每次打开刷新不跨会话缓存**）。**不做重型文件树组件。**
- **验收**：① `list_dir` 在含 10 万文件的目录上 ≤500 条且 2 秒内返回；② `search_code "class Agent"` 命中并带行号；③ 输入 `@` 弹出文件候选，新建文件后重新打开能看到

---

**T27 · 极简语法高亮** · P1 · 2.0 人日
- **来源**：`aicode.md` A1 / `rikkahub.md` A16（反向：不引 51kt 引擎）
- **文件**：`新增 core/editor/SimpleHighlighter.kt`；`修改 ui/EditorScreen.kt`、`ui/MarkdownText.kt`（代码块复用同一套）
- **做什么**：**不做完整 TextMate**（需 oniguruma）。正则 + 状态机的极简着色器（~400 行），覆盖 10 种高频语言（kt/java/py/js/ts/json/yaml/sh/md/html），词法用「关键词表 + 字符串/注释转义状态」。**必须异步分块**：`Dispatchers.Default` 按 500 行分块产出 `AnnotatedString`，`LaunchedEffect` 增量更新 UI。编辑器另补行号 gutter + undo/redo（`undoStack: ArrayDeque<TextSnapshot>`）+ dirty 用 baseline 比对。
- **验收**：① 打开一个 800 行的 kt 文件**首屏 300ms 内**出现着色（不等全文分析完）；② 10 种语言关键字与字符串/注释着色正确；③ 滚动到 3000 行不卡顿

---

**T28 · UI 子代理循环** · P1 · 3.0 人日
- **来源**：`operit.md` #2 / 裁决 §2.2
- **文件**：`新增 core/automation/UiAgentLoop.kt`、`tools/UiAgentTools.kt`
- **做什么**：有界 `maxSteps=12`；`StepResult(success, finished, action, thought)`；每轮「截图 → 喂视觉模型 → 解析动作 → 执行」；**每轮把上一张截图从历史里摘掉，只留最新一张**（滑动窗口=1，防 12 步 12 张图爆上下文）；`finish` 工具或达上限结束；`take_over` 工具用于登录/验证码场景优雅退出；过程可暂停/取消（`finally` 里恢复悬浮窗）。**动作协议用 JSON**（裁决 §2.2：主路径不搞双协议，视觉小模型若命中率低再考虑类函数调用文本）。
- **验收**：① 下「打开设置→找到版本号→截图回来」这类 3 步任务能自动跑完并返回结果；② 过程中可取消；③ 达 12 步自动停并说明「未能在 12 步内完成」；④ 跑 12 步后上下文中的图片数恒为 1

---

**T22 · 技能清单 + `load_skill` 按需加载** · P1 · 0.5 人日
- **来源**：`zafiro.md` D1,D2 / `aicode.md` B8 / `coomi.md` A12
- **文件**：`修改 skill/SkillExecutor.kt`、`core/prompt/SystemPromptBuilder.kt`；`新增 tools/LoadSkillTool.kt`
- **做什么**：系统提示词只放 `<available_skills>` 清单（id / name / description），标注 `## Skills (mandatory)` 并用强制措辞；真正文由 `load_skill` 工具按需返回，**截断时附绝对路径**让模型用 shell 自取。
- **验收**：① 未启用 skill 时系统提示词里只有清单不含正文；② `load_skill("weather")` 返回完整 SKILL.md；③ 正文超限时尾部带绝对路径

---

**T32 · JS 工具桥 `Tools.*`** · P1 · 2.0 人日
- **来源**：`operit.md` #10 / 裁决 §2.7-8（走 rhino，不用 Chaquopy）
- **文件**：`新增 skill/JsToolBridge.kt`、`skill/assets/agent_tools.js`；`修改 skill/SkillExecutor.kt`
- **做什么**：执行前注入一个 Java 对象 `__agent`，**只暴露 `call(name, argsJson): String` 一个方法**；JS 侧用自写的 ~60 行模板包装成 `Tools.Files` / `Tools.Shell` / `Tools.Ui` / `Tools.Memory`。**不开放 `Java` 反射桥**（安全风险）；受 `SkillSecurity` 现有信任等级门控。
- **验收**：① Skill 脚本里 `Tools.Files.read("/sdcard/a.txt")` 拿到内容；② `Tools.Shell.exec("ls")` 能执行；③ 脚本里 `Java.type(...)` **不可用**；④ 低信任等级 Skill 调用被门控拒绝

---

**T29 · Checkpoint 懒快照** · P1 · 1.5 人日
- **来源**：`aicode.md` A6
- **文件**：`修改 core/workspace/CheckpointStore.kt`
- **做什么**：从**整目录 `copyRecursively`** 改为**懒快照**——`write_file` / `edit_file` 执行前调 `snapshotFile(path)`，同一轮次同文件**只保留最原始一次**快照；`activeCheckpointIds` **按 sessionId 隔离**（防并行会话互相覆盖）；回滚时逐个比对 `lastModified + size`，变更则跳过并收集 `skipped` 清单返给 UI。
- **验收**：① 改 1 个文件只快照 1 个（不是整目录）；② 两个并行会话各自撤销**互不干扰**；③ 快照后外部改过该文件时，回滚返回 `skipped` 清单且 UI 展示

---

**T30 · `LineDiff` + diff 卡片** · P1 · 1.5 人日
- **来源**：`aicode.md` A7
- **文件**：`新增 core/workspace/LineDiff.kt`；`修改 ui/ChatScreen.kt`（`edit_file` 结果渲染）
- **做什么**：纯 Kotlin LCS（~70 行），统一格式「每行第 0 列为标记位 `+/-/空格`」（即便代码本身以 `+` 开头也不歧义）。**必须加保护**：行数 > 4000 时退化为「首尾各 50 行」，避免 `(n+1)×(m+1)` IntArray OOM。行号在算 diff 时一并算好（避免 LazyColumn 下 UI 层维护可变计数）。
- **验收**：① `edit_file` 结果在聊天里渲染成 +/- 行；② 一个 5000 行文件的 diff **不 OOM** 且给出降级提示；③ 单测覆盖「行首为 +/- 的代码」不歧义

---

## 5. 明确「不做什么」清单

> 汇总 5 份 digest 里的反向结论与红线否决项。**这些是有意为之的排除，不是遗漏。**

### 5.1 架构与依赖红线（违反「零新增外部依赖」）

| 不做什么 | 来源 | 理由 |
|---|---|---|
| 引入 **Room / Koin / Navigation3 / Ktor / QuickJS / reorderable / haze / ucrop** | `rikkahub.md` 不做清单 | 违反零新增依赖红线；只学思路，净室重写 |
| 引入 **sora-editor / TextMate / oniguruma / Hilt / Retrofit / markdown-renderer-m3 / commons-compress / snakeyaml / Vico** | `aicode.md` §2.2 | 同上；高亮与 Markdown 走自研极简版 |
| 引入 **Chaquopy / Python 3.11** | `zafiro.md` §1 | 体积大；AgentKit 已有 `rhino 1.7.14`，走 JS 路线 |
| **MCP Server（Ktor CIO 内嵌）** | `zafiro.md` D7 | 需新增 ktor 依赖，**明确违反红线，不做**（思路记录备查） |
| 引入 **hnswlib / sqlite-vector** 做向量检索 | `operit.md` §3.7.1 / `rikkahub.md` §3.7 | 违反红线；记忆只做关键词 + 字符串相似度 |
| 引入 **detekt** 做静态门禁 | `coomi.md` A22 | 违反红线；改为 CI 脚本 grep 检查 `!!` 与 `TODO()` |
| 引入 JitPack 的 **Termux `terminal-emulator` / `terminal-view`** | `aicode.md` §2.1、§3.3.1 | 违反红线；AgentKit 自研 `TerminalEmulator.kt` 已可用 |
| 升级 **Kotlin 1.9.25 → 2.4.20 / Compose BOM 2024.09 → 2026.09** | `rikkahub.md` 不做清单 | 风险大于收益；除非某个 P0 项强依赖新 API（目前无） |

### 5.2 架构不可搬（与产品形态冲突）

| 不做什么 | 来源 | 理由 |
|---|---|---|
| **Coomi 架构不可搬**：Termux fork 壳 + Rust 引擎 + 本地 HTTP/WS + Vue WebView 三层 | `coomi.md` §2.1、④ 不借鉴表 | 与「Kotlin 单模块 + Compose + 零依赖」红线根本冲突；**学其协议与策略，不学其架构** |
| 为容器能力把 **targetSdk 锁 28** | `coomi.md` §2.2（≤28）／`aicode.md` §3.4.1 | 代价是上不了 Google Play；**绝不降级** |
| **Operit 无界递归主循环** | `operit.md` #17 | 见裁决 §2.1：保留 `maxSteps=16` 有界 |
| **Operit 内部 XML 工具协议** | `operit.md` #16 | 见裁决 §2.2：我们仅 3 家 + local，都支持原生 tool_calls |
| **Operit 39 家 Provider 铺量 / ToolPkg 包生态 / 蓝牙 16 工具 / 浏览器会话 18 工具** | `operit.md` #18 | 产品目标未要求；靠「OpenAI 兼容 + baseUrl 可改」覆盖中转站 |
| **Shower 独立进程虚拟屏**（`app_process` + Binder 广播 + 隐藏 API） | `operit.md` #19 | AgentKit 虚拟屏**已反超**；该方案依赖独立 JAR 与隐藏 API，风险高收益不确定 |
| **Xposed / LSPosed 接管系统语音助手** | `zafiro.md` G1 | 需 Root + LSPosed，与产品目标无关。**只借鉴两条原则**：① 边缘业务不应让主干妥协；② IPC 只传纯语义数据、复杂结构不穿 Binder |
| Operit **1159 文件单体组织方式** / ObjectBox / FFmpeg / MMD·FBX 3D 形象 | `operit.md` #20 | 与产品目标无关或超出红线 |
| Coomi **AI 工作室 / 数字生命 / 群聊协作** / DeepSeek 账号登录 + PoW | `coomi.md` ④ 不借鉴表 | 超出产品目标；PoW 属灰色地带 |
| **自建代码编辑器**（完整 IDE） | — | 保留 Compose `BasicTextField` 自绘，只补高亮 / 行号 / undo；不做文件树组件 |

### 5.3 我们已领先、反向输出（不借鉴）

| 能力 | 我们的现状 | 参照仓库 | 结论 |
|---|---|---|---|
| **AI 生图 / 视频** | `core/media/providers/` 四家（OpenAI/DashScope/MiniMax/Volc）+ `ui/MediaScreen.kt` | Coomi **只有能力标记无实现**；RikkaHub mediagen 我们已有替代 | **保持领先，不借鉴**（Coomi 无真生图，我们已领先） |
| **本地模型推理** | JNI 直连 llama.cpp（`LocalLlamaClient` / `LlamaJni` / `GgufModelStore`） | Coomi 靠外部 `ollama`/`llama.cpp` 可执行文件；zafiro **无**本地模型 | **不向 Coomi 学**，只借「没装就给安装指引」的兜底话术 |
| **虚拟屏 / 设备自动化** | `automation/`（Shizuku / 虚拟屏 / 无障碍）+ `ScreenCaptureService` | zafiro 用 Accessibility + shell `input`，**无 VirtualDisplay**；Coomi 无障碍「文件队列」桥 | **已反超**；只借 Shizuku shell 通道与读屏自规避细节 |
| **构建 / 推 GitHub** | `core/build/`（推 GitHub → CI → 拉回 → 安装，已闭环） | AiCode 容器内跑 Gradle；Coomi CoomiDev 手机端 Build Kit | **保留我们的路线**（裁决 §2.3），只借 `canRequestPackageInstalls` 预检与就绪检测 |
| **终端仿真** | 自研 `TerminalEmulator.kt`（纯 Kotlin VT100） | AiCode 复用 Termux 两模块 | **不换**，只补 PTY 相关的 env / argv[0] 坑 |

### 5.4 明确延后（不是不做，是现在不值得）

| 项 | 延后到 | 理由 |
|---|---|---|
| 完整**会话树 + rewind**（回退到任意一轮） | T36 之后 / P2 | 需改持久化结构；先做扁平游标版（T12）已覆盖 80% 场景 |
| **记忆自动 Consolidation** | T42（P2） | 依赖 T41 的 Markdown 文件树改造 |
| **多 Key 加权轮询 / 每 provider 独立代理** | T38/T39（P2） | 先做 Provider 无状态化（T34）铺路 |
| **完整 TextMate 语法 / Mermaid / LaTeX** | 不做 | 投入产出比过低（51kt 引擎） |
| **大屏双栏 / 悬浮球** | T46/T47（P2） | 体验加分项，非阻塞 |
| **联网搜索做成 Tool** | 待定 | 产品目标未提，优先级最低 |

---

## 6. 硬约束复核

### 6.1 全量复核结论

**T01 ~ T50 共 50 条改造项，全部满足「零新增外部依赖」。** 逐条在 §3 优先级表与 §4 路线图条目的「红线」列标注 ✅。
可用范围严格限定为：Kotlin 标准库 + 协程 + Compose/Material3 + **okhttp 4.12.0** + **kotlinx-serialization-json 1.6.3** + **rhino 1.7.14** + Android SDK + JDK 自带（`java.util.zip` / `java.security.MessageDigest` / `java.io`）。

| 类别 | 条数 | 依赖状态 |
|---|---|---:|
| P0 改造项（T01–T17） | 17 | ✅ 全部零新增 |
| P1 改造项（T18–T35） | 18 | ✅ 全部零新增 |
| P2 改造项（T36–T50） | 15 | ✅ 全部零新增 |
| **合计** | **50** | **✅ 50 / 50** |

### 6.2 需特别注意的「擦边」项（已做降级处理）

| 项 | 原方案（参照仓库） | 降级后方案 | 是否越线 |
|---|---|---|---|
| **A8 Markdown 预览** | WebView + marked.js + highlight.js + DOMPurify | 若做，仅作为 **assets 内置 JS**（非 Gradle 依赖），且必须配 `allowFileAccess=false` + 外部链接不跳转 + 补 `assets/licenses/` 许可清单 | **不越线**（assets 非 Gradle 依赖），但列为 P2 延后 |
| **T27 语法高亮** | TextMate + oniguruma（含 `.so`） | 正则 + 状态机极简着色器，纯 Kotlin | ✅ |
| **T49 凭据文件 IPC** | `git-credential-aicode` shell 脚本 | 自写 shell（base64 + 整体反转**混淆**，注释坦白非加密），目的是挡住 AI 把凭据读进上下文 | ✅ |
| **T39 自定义 Body** | `CustomBody(key, value: JsonElement)` | 用 `Map<String,String>`（kotlinx-serialization 已有） | ✅ |
| **T50 线缆名哈希** | FNV-1a 64 位 | 手写 20 行哈希 | ✅ |
| **T40 备份 ZIP** | — | `java.util.zip`（JDK 自带） | ✅ |

### 6.3 违反红线、已明确否决的方案

| 方案 | 来源 | 需要的依赖 | 处置 |
|---|---|---|---|
| MCP Server（把手机变 MCP Server 供 PC 调） | `zafiro.md` D7 / §3.1.6 | `ktor-server-cio` + `kotlin-sdk-server` | **否决**（思路记入备查） |
| Python 运行时 + `py_meta_tools` 元工具 | `zafiro.md` §3.1.5 | Chaquopy 17.0.0 | **改为 rhino 版元工具**（T36） |
| 向量检索（记忆 / RAG） | `operit.md` §3.7.1 / `rikkahub.md` §3.7 | hnswlib / sqlite-vector | **否决**，改关键词 + 字符串相似度 |
| 完整代码编辑器 | `aicode.md` §3.1.1 | sora-editor + TextMate | **否决**，走 T27 极简高亮 |
| 拖拽排序模型收藏 | `rikkahub.md` A4② | `sh.calvin.reorderable` | **降级为「长按 → 上移/下移」** |
| 静态分析门禁 | `coomi.md` A22 | detekt | **改为 CI grep** |
| 20 家联网搜索服务 | `rikkahub.md` §3.7 | 各家 SDK | **否决** |

### 6.4 合规复核（许可证）

| digest | 许可 | 落地要求 | 状态 |
|---|---|---|---|
| `coomi.md` | Apache-2.0 | 可深参，仍净室重写 | ✅ 无 GPL 传染 |
| `zafiro.md` | MIT | 可深参，仍净室重写 | ✅ 无 GPL 传染 |
| `rikkahub.md` | **AGPL-3.0** | **只读思路，净室重写；不复刻其 `ProviderSetting`/`MessageNode` 结构命名** | ⚠️ 需遵守 |
| `operit.md` | **LGPL-3.0** | **只读思路；虚拟屏（Shower）与终端（OperitTerminalCore）两块为红线，不触碰实现** | ⚠️ 需遵守 |
| `aicode.md` | **GPL-3.0** | **只读思路；其 shell 脚本（场景库）为思路借鉴，自写** | ⚠️ 需遵守 |

> **落地文档引用规范**：引用本库时标注 `来源：<digest 文件名>`，禁止在代码注释里粘贴参照仓库的源码片段。

---

## 7. 验收总闸

| Phase | 完成判据（全部满足才算过） |
|---|---|
| **Phase 1** | ① weather/wordcount 两个 skill 走新 loop 跑通；② 点「停止」≤1 轮内退出且历史可继续请求（无未闭合 tool_call）；③ `npm install` / `git log` 在容器内不卡住；④ proot 落位有实测结论；⑤ 并发工具结果保序 |
| **Phase 2** | ① 含 9 种 Markdown 语法的回复渲染正确，`<script>` 原样显示；② 重新生成 3 次可左右切换且杀进程后仍在；③ 断网时 UI 显示「正在恢复（n/N）」；④ local-llama 下可见工具 ≤7 且能完成一次 `read_file`；⑤ 权限申请一次走完降级链；⑥ `terminal(notify=true)` 后台任务完成后 AI 自动继续 |
| **Phase 3** | ① 容器页一键装 node/python/java 并跑通 hello world；② 对多文件项目连续 `edit_file` 无漂移、并发修改返 `STALE_FILE`；③ 改 `prompts.custom/` 的 md 重启即生效（无需改 Kotlin）；④ UI 子代理 3 步任务自动跑完且 12 步内收敛 |

---

*总对标地图完 · 制定人：产品经理 许清楚（Xu） · 来源：`coomi.md` / `rikkahub.md` / `operit.md` / `aicode.md` / `zafiro.md` · 下一步：按 Phase 1 的 T09 开工*
