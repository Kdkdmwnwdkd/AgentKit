# 四家参照仓库源码级吃透报告（2026-10-15 全面重吃版）

> 调研对象：Operit AI（AAswordman/Operit@main）、Coomi（TensorHub-ORG/Coomi@main）、RikkaHub（rikkahub/rikkahub@master）、AiCode（jieapi/AiCode@main），均为当日经 gh-proxy 通道拉取的当前 HEAD 快照。
> 方法：门面阅读（README/AGENTS/CLAUDE/docs）→ 模块解剖 → 核心链路采样 → 关键论断文件级抽验（本报告所有论断均经主理人抽验属实）。
> 与《deepdive-appcontrol-and-beyond.md》的关系：那份聚焦「应用操控能力 + W-7 修复参考」；本份是**全面吃透**——四家整体怎么实现的、怎么做成的。

---

## 一、四家速览

| | Operit AI | Coomi | RikkaHub | AiCode |
|---|---|---|---|---|
| 定位 | Android 上功能最全的开源 AI Agent | 本地优先智能体工作环境 | 原生 Android LLM 聊天客户端 | Android AI Coding Agent |
| 技术栈 | Kotlin 45 万行单 app + 8 个原生模块（llama/MNN/dragonbones/mmd/fbx/shower/quickjs/terminal） | Rust 引擎 8 crate（2.4 万行）+ Android 壳（Termux fork）+ Vue3 前端 | Kotlin 13 模块（app/ai/common/search/speech/document/web/workspace/ui/…） | Kotlin 单 app（12.5 万行，feature 分包）+ 2 个 Termux 终端模块 |
| Agent 形态 | 重 Agent（200 工具、五层系统操控、工作流、角色卡、图记忆） | 重 Agent（终端执行、SKILL/MCP、AI 工作室多智能体） | 轻 Agent（聊天为主 + MCP + 记忆 + proot 环境） | 垂直 Agent（coding：检查点、Git、子代理、三模式） |
| 模型策略 | 云 20+ provider + 本地 MNN/llama.cpp，按 FunctionType 分配模型 | 云 provider + 本地 ollama/llama.cpp | 云 provider（OpenAI/Google/Anthropic 兼容） | 云 provider 三协议 + 多 Key 轮换 |
| 最鲜明特色 | 五权限层级工厂 + 独立无障碍 Provider APK + ToolPkg 生态 | 引擎/壳彻底解耦 + 断线续传事件总线 + AI 工作室波次调度 | provider 抽象与流式解码三层架构 + 消息分支 | MVI Reducer 循环 + 权限策略引擎 + 提示词资产化 |

---

## 二、同一个问题，四家怎么做（横向对比）

### 2.1 Agent 主循环

| 家 | 结构 | 关键设计 |
|---|---|---|
| Operit | 「流完成处理 + 递归」（EnhancedAIService.sendMessage → processStreamCompletion → handleToolInvocation → processToolResults 递归） | 自研 `Stream<T>` + SAVEPOINT/ROLLBACK 事件通道实现流式内容修订；MessageExecutionContext 管取消 |
| Coomi | Rust `'tool_rounds` 循环，默认最多 192 轮（engine/agent.rs:90） | 每轮：修补孤儿 tool 结果 → 压缩 → 流式请求 → 执行回灌；参数纠偏 + 失败指纹防打转 |
| RikkaHub | GenerationLoop（app/data/ai/） | 阶梯滞回截断保 prompt 缓存前缀（limitMessageCount：截断点只在越限时前进，保留量落在 [limit*0.5, limit)） |
| AiCode | MVI：不可变 state + action + **纯函数 reduce()**（StatefulAgentWorkflow.kt:324），IO 全在 effect 执行层（:547-877） | channelFlow + ArrayDeque 动作队列；同批工具全部批准后并行（同路径 Mutex 串行）；拒一批全批取消 |

**趋势判读**：循环本体都在收敛到「小而纯」——AiCode 把循环做成可单测的纯函数是最激进的；Operit 的递归式是历史包袱最重的。

### 2.2 流式输出

- **Operit**：自研 KMP 流式 Markdown 渲染 + SAVEPOINT/ROLLBACK 修订（流到一半发现 XML 工具调用截断，回滚修复而非重发）。
- **Coomi**：Provider SSE → Observer → AgentEvent → mpsc → WS envelope，**每事件带 seq + 未确认队列（unacked_events 上限 2048）+ 客户端 ack 游标**；断线只解绑连接，任务与子进程继续跑，重连先补发。
- **RikkaHub**：三层切分——传输层（自实现 EventSource 防 body 泄露）→ **有状态 decoder（每流一份，completed 可主动断连）**→ 通用 StreamChunk 合并；用**录制的真实 SSE trace 回放做纯 JVM 单测**（stream-traces/*.jsonl + 快照断言）。
- **AiCode**：channelFlow + StreamAccumulator 按 60ms 时间窗节流合并；累积器独立成类的理由是「channelFlow 主体过大被 ART 交给解释器执行」（:886-893 注释）。

### 2.3 工具系统与扩展

| 维度 | Operit | Coomi | RikkaHub | AiCode |
|---|---|---|---|---|
| 内置工具 | ~200 个，ToolRegistration.kt 集中注册 | CoreTools（tools/lib.rs 3755 行）dispatch | ChatToolFactory 装配 | 22 个，di/AgentModule.kt:379-402 一处注册 |
| 工具说明书 | SystemToolPrompts 327 个 ToolPrompt（中英双语、带 rules/examples） | ToolSpec + 系统提示 inventory | 模型能力注册表（token 匹配 DSL 推断 modality） | toJsonSchema 从参数定义生成 |
| 并发控制 | 读批并行/写串行（T03 同款思路） | **静态分级**：ReadOnly/Mutating/Destructive/Interactive，只读且本批无写才并行（Semaphore 5） | — | 同批全批准后并行，**同路径 Mutex 串行** |
| 扩展机制 | ToolPkg（ZIP：JS + Compose DSL UI + WASM + i18n，13 类注册 bucket）+ MCP（部署进 proot Ubuntu）+ Skills（GitHub 导入） | SKILL.md（YAML frontmatter + 关键词路由，≤3 个、64KiB 预算）+ MCP 三传输（stdio/HTTP/SSE）热重载 | 官方 MCP SDK fork + reconcile（配置驱动连接）+ OAuth 2.1 | markdown 定义子代理（frontmatter：model/tools/inject）+ MCP（stdio 跑在容器内 + HTTP） |
| 命名空间 | `packName:toolName` 扁平空间，缺失自动激活包 | `mcp__server__tool` | `mcp__{server}__{tool}`（校验服务名合法） | `mcp__server__tool`（>64 字符加 sha1） |

### 2.4 上下文与记忆

- **压缩**：Operit（token 阈值触发总结，独立 function model）/ Coomi（**三级**：远程 compact 端点优先 → 本地分块累积摘要（固定 COMPLETED/IN PROGRESS/PENDING 结构）→ 紧急裁剪；**失败一律回滚原始历史**；压缩前全量备份 session.archive 供前端回溯）/ RikkaHub（阶梯滞回截断，保 KV 缓存前缀稳定 + 对齐 tool call 边界）/ AiCode（截断续写：isTruncated 自动注入 "Continue..." 续跑；compact-summary 提示词资产化）。
- **记忆**：Operit（HNSW 向量索引 + **记忆不自动注入 prompt、通过 create_memory/query_memory 工具暴露**）/ Coomi（memory 工具 + 本地 skill 路由）/ RikkaHub（**Markdown 文件存 Room + ifVersion 乐观锁 + 后台整理 agent**（主对话不写记忆，会话暂停后带预算整理））/ AiCode（memory 工具 + 全局/项目级 skills）。

### 2.5 Android 系统操控（我们 W-7 的直接参照系）

| 层 | Operit | Coomi | AiCode |
|---|---|---|---|
| 无障碍 | **独立 Provider APK + AIDL**（UIHierarchyManager 绑定 com.ai.assistance.operit.provider，getUIHierarchy/performClick/setTextOnNode/takeScreenshot） | ui_automation 工具（读屏/点击，主路径，adb 仅在输出含「无响应」时兜底） | 无（coding 场景不需要） |
| ADB 级 | Shizuku（兼容 Sui 后端、binder 缓存）+ DebuggerShellExecutor | Shizuku 桥（tools/shizuku.rs，以用户授权 shell 权限执行） | Shizuku 工具（高危不可记忆） |
| Root | libsu（支持 KernelSu 自定义 su） | — | — |
| 视觉操控 | PhoneAgent（run_ui_subagent：截图→视觉模型→do(action)/finish，Shower 虚拟显示） | — | — |
| 权限组织 | **五层级 STANDARD/ACCESSIBILITY/DEBUGGER/ADMIN/ROOT × 工厂自动降级探测**（同一工具名五套并行实现，ToolGetter 按偏好层级切换） | AccessMode 三档 + Hook 系统 | ToolPermissionPolicyEngine（ALLOW/DENY/ASK + 可记忆规则 + shell 静态分段分析 + 灾难 rm 硬防护） |

### 2.6 Linux 环境

四家全部 **proot 路线**（无容器）：
- Operit：proot-distro rootfs 落 app 私有目录；终端执行器是**独立 App**（com.ai.assistance.operit.terminal，submodule）；MCP server 也跑在 proot 里。
- Coomi：Android 壳就是 **Termux fork**；Runtime V2 = PRoot + Ubuntu 24.04（311MB rootfs 内嵌种子，TUNA 源）；路径双视角（guest /workspace ↔ 宿主绝对路径）有环境契约文档（docs/environment-contract.md 唯一事实源）。
- RikkaHub：workspace 模块（唯一带 CMake 的库模块），proot 以 libproot_exec.so 分发；**bindMounts 表单一真源**（同时生成 proot -b 参数和文件工具路径解析，防两处漂移）；rootfs 下载 rename 原子替换。
- AiCode：Termux + PRoot + **targetSdk 锁定 28**（PRoot 需在 App 可写目录执行二进制，Android 10+ W^X 禁止）；远程 SSH 后端与本地容器**统一抽象**（CommandEngine/FileAccessProvider/TerminalSessionProvider 三接口 + 运行时分发委托层）。

### 2.7 多智能体

- **Coomi 最重**：AI 工作室——成员 2-12 人各自独立 provider/model/会话/工具，@提及调度（全角＠归一、**中文无空格边界判定**、邮箱排除、广播词、前缀名冲突只唤醒精确匹配），波次并发（JoinSet 并行，每成员 ≤3 次、全局 ≤8 波，回复中新 @ 进下一波）；另有群聊（round_robin/open/host 三档发言模式）。
- **AiCode 最轻**：子代理 = 「普通会话 + SharedFlow 事件总线」——TaskTool 只建子会话实体发 SPAWNED 事件，ViewModel 用**与用户发消息完全相同的 executeAgentRequestStream** 启动；父子通信靠通知队列搭车送达；能力裁剪只靠工具白名单（黑名单先于白名单、task 永远剔除禁嵌套）；Explore 只读子代理是 assets/agents/explore.md。
- **Operit**：run_ui_subagent（PhoneAgent 视觉子代理）+ send_message_to_ai（会话间通信）。
- **RikkaHub**：无多智能体，有 Agent（助手）定制 + 会话配置快照。

---

## 三、「怎么做成的」——四家共同方法论

### 3.1 引擎与壳解耦是共识，且各家都按自己规模选了合适粒度

- Coomi 最激进：Rust 编成**普通二进制**（libcoomi.so），Android 壳只负责 ProcessBuilder 拉起 + 健康轮询 + **MD5 指纹比对重启**（APK 覆盖安装后引擎版本不一致就杀旧起新）+ fs2 单实例锁；通信只有 loopback HTTP+WS+令牌（Origin 白名单 + token 鉴权，/api/runtime/health 无 token 只回最小字段防误判）。
- RikkaHub 中等：ai/search/document/workspace 等 SDK 化，app 只做编排。
- AiCode 内聚：单 app 但 domain 层纯净（feature 分包 + DDD），委托层做运行时分发。
- **启示**：解耦的本质不是「拆进程/拆模块」这个动作，而是**把「会被独立替换/独立演进的部件」的接口收缩到最小**（Coomi 的 HTTP+WS、AiCode 的 CommandEngine）。

### 3.2 一切差异皆接口 + 运行时分发

provider 差异（RikkaHub 的 sealed ProviderSetting 协议开关 vs AiCode 的三协议实现）、权限层级差异（Operit 五套并行实现 vs AiCode 的评估引擎）、执行后端差异（AiCode 的 DelegatingCommandEngine 读 ExecutionModeHolder.currentMode() 转发，**刻意不用 DI 时绑定**——模式可随时切换）——四家都把「多状态机」做成「一个接口 + 多个实现 + 一个分发点」，LLM/prompt 侧完全无感。

### 3.3 把 LLM 特有的难题当工程问题解（四家花力气最深的地方）

| 难题 | 四家的工程解 |
|---|---|
| 取消传播 | Coomi（tokio abort + 进程组 kill_on_drop）、AiCode（catch CE 全上抛 + 会话 Job 登记）、RikkaHub（ensureActive 防取消被重试）——**和我们 W-7 F1 修的是同一个坑** |
| 并发写冲突 | AiCode（同批同路径 Mutex + 回滚走公共文件锁 + FileVersionRegistry 乐观判据）、RikkaHub（记忆 ifVersion 乐观锁）、Coomi（引擎单实例锁 + 会话目录隔离） |
| 断线/后台续跑 | Coomi（SessionTask 锚定会话而非连接 + event_seq + unacked_events 补发）、Operit（聊天运行时上移前台服务，ChatRuntimeHolder MAIN/FLOATING 双 Core） |
| KV 缓存稳定 | RikkaHub（滞回截断 + 用消息自身 createdAt 渲染而非当前时间）、AiCode（模式提醒刻意移出 system prompt 改消息级注入、静态基线每次读盘、动态内容会话级缓存——**把 KV Cache 当一级设计约束**） |
| 流式修订 | Operit（SAVEPOINT/ROLLBACK）、RikkaHub（decoder 的 completed 主动断连 + 容忍缺失 Start） |
| 模型打转 | Coomi（失败指纹：同批重复直接阻断、历史失败 ≥2 次/需改参数即阻止原样重试）——与我们的 FailureGuard 同思路但更细 |

### 3.4 资产化：提示词/工具/技能/子代理全部文件化、可覆盖、可分享

- AiCode 最系统：**编号即身份**的四级来源覆盖（项目 > 全局 > 本地 > 内置 assets），用户覆盖层在 App 私有目录/工作区，App 升级天然不丢；UI 支持拖拽重排（改编号即改顺序）；DISABLE_BUILTIN_FILE 可弃用内置。
- Coomi：SKILL.md + frontmatter（keywords/file_types/tools/projects/risks）+ 路由打分 + 审计日志；AGENTS.md/COOMI.md 项目指令发现。
- Operit：ToolPkg ZIP 是「可分享的完整扩展包」（JS 逻辑 + Compose DSL UI + WASM + i18n），examples/ 30+ 个示例包同步进 assets。
- RikkaHub：Pebble 模板 + 占位符变量（cur_date/model_name/char…）+ SillyTavern 角色卡导入。

### 3.5 测试缝意识（和我们 harness 路线同构）

- RikkaHub：录制真实 SSE trace 回放 + 快照断言（UPDATE_STREAM_TRACE_SNAPSHOTS 刷新）——流式解析这种最难测的代码做到可回归。
- AiCode：Room 55 版迁移有对账脚本；版本号完全由 git tag 推导。
- Coomi：environment-contract.md 作为环境行为唯一事实源。
- 我们：kotlin-compiler-embeddable 离线 harness + -Xfriend-paths 测 internal 纯函数（同款思路，可进一步学 RikkaHub 的「真实 trace 回放」治流式解析）。

---

## 四、对 AgentKit 的启示（按「投入小 / 收益大」排序）

现状：单模块 :app、3.1 万行、minSdk 26/arm64、零 JUnit、harness 离线编译路线已验证。

1. **AiCode 式提示词资产化**（P1，改动集中）：提示词从代码常量搬到 assets/prompts/<NN>-<名>.md，「编号即身份 + 四级覆盖」，用户可覆盖不丢、可分享。直接解决我们提示词散落代码、无法被用户定制的问题。
2. **AiCode 式权限策略引擎**（P1）：ALLOW/DENY/ASK + 可记忆规则（全局 + 项目两级 JSON）+ shell 静态分段分析 + 高危不可记忆。我们当前审批弱于四家任何一家。
3. **RikkaHub 式阶梯滞回截断**（P2，纯函数直接抄）：limitMessageCount 保 prompt 缓存前缀稳定 + alignContextStart 对齐 tool call 边界。我们 maxHistoryMessages 是硬截断，可升级。
4. **AiCode 式检查点判据**（P2）：FileVersionRegistry「本会话最后看到的版本」判据 + 同路径 Mutex + 公共文件锁。做「撤销 Agent 改动」时的标准答案。
5. **Operit 式工具调用双协议桥接**（P1，若接本地模型）：原生 tool call ↔ XML 文本互转，主循环零差异。我们接本地弱模型时必然遇到。
6. **Coomi 式事件序号 + ack**（P2，若做后台任务）：ConcurrentLinkedQueue + seq + ack 游标，断线补发。
7. **Operit 式五权限工厂**（P3，重）：只在我们决定做深控（无障碍/Shizuku/Root 全通道）时上；当前 W-7 修复后的启动+确认链路已够用。
8. **测试升级**：流式 SSE 解析引入「真实 trace 回放」单测（学 RikkaHub），治我们 SSE 累积器这类难测代码。

**明确不学**：Coomi 的 Rust 引擎（引入新语言栈，团队成本不符）、Operit 的 200 工具军备竞赛（我们场景聚焦）、rikkahub 的 ABI splits（我们只发 arm64）。

---

*报告完。四份成员级解剖报告（含全部文件:行号证据）由 QA/架构视角并行产出并经主理人逐条抽验；本汇编只做横向提炼与迁移判断。*
