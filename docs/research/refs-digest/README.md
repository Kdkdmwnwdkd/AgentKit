# refs-digest · 参照仓库「吃透」研究库

> 用途：把 5 个开源参照仓库**吃透后的设计原理**，沉淀成一份**可以照着一步步做**的改造依据。
> 使用者：AgentKit（`/workspace`，单模块 `:app`，Kotlin 1.9.25 + Compose + Material3，minSdk 26，仅 arm64-v8a，约 80 kt）。
> 维护人：产品经理 许清楚（Xu）

---

## 这个文件夹是什么

**不是**参考资料堆，**是**施工蓝图。用户原话：「你把你找到的能用的就组成一个文件夹，之后在一步一步的做」。
所以本文件夹的组织原则是——**每一份都能直接翻到「改哪个文件、做什么、验收什么」**。

### 第一原则（用户原话级）

> 「让你吃透知道怎么做，然后再改我们的，不是说让你直接一步复制过来。」

因此：

1. **只提炼设计原理**（架构分层、协议形状、状态机、参数语义、踩坑经验），**不搬运代码**。
2. **`rikkahub.md`（AGPL-3.0）、`operit.md`（LGPL-3.0）、`aicode.md`（GPL-3.0）三份为「只读思路」**，任何落地一律**净室重写**：理解了原理的工程师从零实现，不看其源码。
3. **`coomi.md`（Apache-2.0）与 `zafiro.md`（MIT）** 许可更宽松，但同样按净室重写保持 AgentKit 代码库一致性（Kotlin + Compose + Material3 + 单模块）。

### 硬红线

**零新增外部依赖**。已有且仅可用的三方库：`okhttp 4.12.0` / `kotlinx-serialization-json 1.6.3` / `rhino 1.7.14`。
任何需要引入 Gradle 依赖的方案，一律降级为「自研极简版」或列入「不做」。

### 阅读顺序

| 顺序 | 文件 | 什么时候读 |
|---|---|---|
| ① | **`00-roadmap.md`** ← **先看这份** | 开工前、每个迭代排期前 |
| ② | `zafiro.md` | 做 Agent 运行时内核 / 终端抽象 / 权限模型时 |
| ③ | `operit.md` | 做工具系统 / 本地模型 / UI 子代理时 |
| ④ | `aicode.md` | 做代码能力 / 提示词资产化 / 容器场景库时 |
| ⑤ | `rikkahub.md` | 做聊天体验 / 消息分支 / 模型选择器时 |
| ⑥ | `coomi.md` | 做 Provider 容错 / 渲染性能 / 反卡死时 |

---

## 索引表：5 份 digest 一览

| digest | 研究对象 | 许可证 | 一句话定位 | 最大借鉴点（学什么） | 明确不学什么 |
|---|---|---|---|---|---|
| `coomi.md`<br>627 行 | Coomi<br>Termux 壳 + Rust 引擎 + Vue 前端 | **Apache-2.0** | 「三件套跨语言架构」：Android 壳不写业务，Agent 逻辑全在 Rust（5.2 万行），UI 全在 Vue | ① **Provider 请求侧 400 字段自降级重试**（`provider.rs:948-1178`）<br>② **Agent 循环的工具调用指纹去重 + 失败熔断**（`agent.rs:641-708`，同一调用最多真跑 2 次）<br>③ **并发判据 = 本批是否含写操作**（`agent.rs:634-637`）<br>④ **Markdown 白名单消毒 + 分块渲染 + 60ms 节流**（`markdown.ts:14-105`）<br>⑤ 上下文 token 估算：图片按固定成本 | Termux fork 壳与 `targetSdk ≤ 28` 包袱；Rust+HTTP/WS+Vue 三层架构；`local_model` 靠外部 ollama/llama.cpp；无实现的能力标记式生图；CoomiDev 手机端 Build Kit；无障碍「文件队列」桥；DeepSeek 账号登录；AI 工作室/数字生命 |
| `rikkahub.md`<br>754 行 | RikkaHub<br>纯聊天客户端（14 个 Gradle 模块） | **AGPL-3.0**<br>只读思路 | 「一切皆可序列化 + Provider 无状态 + 会话即文档树」的成熟体验层 | ① **消息分支 = 节点 + 游标**（`MessageNode(messages, selectIndex)`，`Conversation.kt:113-135`）——扁平 List 表达树，序列化零成本<br>② **模型能力靠 ID 分词推断，不维护模型表**（`ModelRegistry` + `ModelDsl.kt:161-229`）<br>③ **上下文滞回截断**（阶梯式截断点 + `alignContextStart` 保 tool 配对，`Message.kt:161-249`）<br>④ Provider 无状态化 / 会话级配置快照 / KeyRoulette | Room / Koin / Navigation3 / Ktor / QuickJS / reorderable / haze 等全部依赖；自研 `:highlight` 引擎（51 kt）；`:mediagen` 任务模型；升级 Kotlin 2.4.20 与 Compose BOM 2026.09 |
| `operit.md`<br>944 行 | Operit<br>Kotlin 单 App + 9 个原生模块（1159 kt） | **LGPL-3.0**<br>只读思路 | 与 AgentKit 技术栈**最同构**的重型单体；39 家 Provider + 2 个本地引擎 | ① **本地模型工具暴露降级**（CLI 模式只给 2 个工具 + 隐藏目录检索，`CliToolModeSupport.kt:21-31`）<br>② **有界 UI 子代理循环** PhoneAgent（`maxSteps=20`，每步只留最新一张截图）<br>③ **结果「一份真源、两处复用」+ 只发布已完成前缀**（`OrderedToolResults.kt:15-45`）<br>④ **Shell 五级权限工厂 + 严格模式**（不可用时失败并说明，不静默降级）<br>⑤ 工具 Hook 生命周期 7 回调 / 三级权限模型 / 可纠正的「工具不存在」错误 | 内部 XML 工具协议（我们 Provider 少且都支持原生 tool_calls）；**无界互相递归主循环**；39 家 Provider 铺量 / ToolPkg 包生态 / 蓝牙 16 工具 / 浏览器 18 工具；Shower 独立进程虚拟屏（`app_process` + 隐藏 API）；ObjectBox / FFmpeg / MMD·FBX / 1159 文件单体 |
| `aicode.md`<br>983 行 | AiCode<br>Kotlin App + 2 个 Termux 模块（440 kt） | **GPL-3.0**<br>只读思路 | 唯一「AI + 完整 Linux 容器 + 真实编辑器 + 提示词/场景工程体系」四件套齐全 | ① **提示词是分层配置资产**：`assets/prompts/<NN>-<名>.md` 编号即顺序，四层优先级 PROJECT>GLOBAL>LOCAL>BUILTIN（`PromptFragmentCatalog.kt:13`）<br>② **场景库把「装开发环境」产品化**（`scenarios/{node,python,java,go...}.sh`，只暴露两个函数）<br>③ **`editFile` 精确字符串匹配而非行号 + 整批原子**（`EditFileTool.kt:37-56`）<br>④ **双层写保护**：路径锁 + 写前指纹校验返 `STALE_FILE`<br>⑤ **`notify=true` 反转轮询**（后台命令跑完系统推事件唤醒 AI） | sora-editor / TextMate / oniguruma / Hilt / Room / Retrofit / markdown-renderer / commons-compress / snakeyaml 等全部依赖；容器内跑 Gradle 编译 APK（慢且耗电，我们走 GitHub CI）；`targetSdk` 锁 28；JitPack 依赖的 Termux 模块；git 凭据只是混淆非加密 |
| `zafiro.md`<br>719 行 | zafiro<br>Android 原生 Agent（BYOK）+ Xposed | **MIT**<br>可深参但净室重写 | 工程成熟度最高、抽象最干净：**按契约分层、每层有明确不变量** | ① **Agent 运行时抽成独立库层 okia**（依赖仅 okhttp + serialization + coroutines，运行时不认识 Android）<br>② **终端 = 身份 × 后端二维矩阵**（`TerminalIdentity{User,Su,Shizuku,Ssh}` × 4 后端，会话开辟时固化身份）<br>③ **权限 = 权限×API → 授予机制 → 通道链 三张正交表**，`PermissionSpec` 是全仓唯一 SDK_INT 分叉点<br>④ **工具结果契约 `{ok,code,message,hint,data}`，失败带 `hint` 教模型怎么修**<br>⑤ 未知工具回喂 vs executor bug 终止的二分；双层重试；流式 idle 超时 | Xposed / LSPosed 接管系统语音助手；Chaquopy Python（体积大，我们已有 rhino 走 JS）；**MCP Server / Ktor**（违反零依赖红线）；本地模型与媒体生成（AgentKit 已反超）；虚拟屏（AgentKit 已反超） |

---

## 交叉引用：某个主题去哪几份查

> 格式：`digest 文件 §章节 / 对照表编号`。同一主题多份论述的，**以「去重合并」后的唯一方案为准**（见 `00-roadmap.md` §1）。

| 主题 | 有论述的 digest | 一句话结论（合并后方向） |
|---|---|---|
| **Agent 运行时内核抽象** | `zafiro.md` §3.2 + A1／`operit.md` §3.1 + #1,6,8／`coomi.md` §3.2 + A1,A2,A3／`aicode.md` §3.2.4 AgentTool | 净室重写为 `core/agent/` 子包（不拆模块）：`AgentLoop` 接口 + `RealAgentLoop` + `ToolRegistry`/`ToolExecutor` 契约 + `Hooks`。运行时不认识 Android |
| **终端后端 / 身份抽象** | `zafiro.md` §3.3 + B1,B2,B3,B4／`operit.md` §3.4 + #4,#5／`rikkahub.md` §3.10 + A13／`aicode.md` §3.3 + C1,C4,C5 | `TerminalBackend` 接口 + `TerminalIdentity` 枚举 + `ProotBackend`/`ShizukuBackend`；会话开辟固化身份，冲突显式报错 |
| **Agent 主循环（有界 vs 无界）** | `operit.md` §2.3 + #17／`coomi.md` §3.2.1／`zafiro.md` §3.2.2 | **保留 AgentKit 现有 `repeat(maxSteps=16)` 有界**，补 `shouldContinue()` 钩子支持中途取消 |
| **工具表达协议（XML/JSON/文本）** | `operit.md` §3.1.1 + #16／`zafiro.md` §3.2.4／`operit.md` §3.3.2（PhoneAgent） | **采纳原生 `tool_calls` JSON**。XML 仅作 local-llama 提示词兜底；类函数调用文本仅用于可选 UI 子代理 |
| **工具结果契约** | `zafiro.md` §3.1.2 + A3,A4／`operit.md` §3.1.3 + #1,#9,#11／`aicode.md` §3.2.4 ToolResult | 统一为 `{ok, code, message, hint, data}`；**失败必带 `hint`**；一份真源两处复用（UI 与模型同源） |
| **并发判据与失败熔断** | `coomi.md` §3.2.3 + A1,A2／`operit.md` §3.1.2 + #8／`zafiro.md` §3.2.2 | 并发判据 = **本批是否含写操作**（含写则整批串行）；同指纹最多真执行 2 次，拦截时把原因作为错误结果回喂模型 |
| **可取消与截断** | `operit.md` #4,#7,#9 + §3.1.4／`zafiro.md` A5,A7 + §3.2.2／`aicode.md` C3 BoundedOutput | 取消时在 `NonCancellable` 内补全待决 ToolResult 再重抛（**不变量：已 commit 的 ToolCall 必有配对 ToolResult**）；单条结果 >24K 字符截断带提示 |
| **权限模型** | `zafiro.md` §3.4 + C1-C5／`operit.md` §3.2.3 + #6,#13／`aicode.md` §3.2.5 + D7／`coomi.md` §3.4.5 | **终端身份走 Operit 严格模式（不静默降级）；权限获取走 zafiro 降级链**（ROOT_SHELL→SHIZUKU→SYSTEM_DIALOG→JUMP_SETTINGS）+ 用户可配规则 4 模式 |
| **Markdown 渲染 / 代码高亮** | `coomi.md` §3.3 + A5,A6／`aicode.md` §3.1.4 + A1,A8／`rikkahub.md` §3.2.5 + A16 | **自研极简 Markdown → `AnnotatedString`**（不引库、不解析 HTML，天然无注入面）；高亮做 10 语言关键字正则版，异步分块 |
| **消息分支 / 会话树** | `rikkahub.md` §3.2.2 + A2／`zafiro.md` F2 + §3.2.1 rewind | `MsgNode { records, selectIndex }` 扁平 List + 游标；重新生成/编辑 = 追加兄弟 + 移动游标 |
| **上下文管理与压缩** | `rikkahub.md` §3.2.3 + A6／`coomi.md` §3.2.4 + A7／`operit.md` §3.1.5 | **滞回（阶梯）截断 + tool 配对保护**；token 估算图片按固定成本（防一张截图触发压缩） |
| **提示词资产化** | `aicode.md` §3.2.1 + B1,B2,B3,B9／`zafiro.md` §3.1.7 + D2,D4／`coomi.md` A12／`rikkahub.md` A10 | `assets/prompts/<NN>-<名>.md` 编号即顺序 + `{{VAR}}` 变量 + 稳定层/易变层二分；用户覆盖层 `prompts.custom/` 不删 |
| **场景库 / 开发环境** | `aicode.md` §3.2 B4,B5 + `assets/aicode/lib/scenarios/`／`coomi.md` §3.5.3（CoomiDev，反向） | `assets/env/scenarios/<id>.sh` 只暴露两个 shell 函数 + `env-common.sh` 包管理器探测与镜像源；**不学 CoomiDev 手机端 Build Kit** |
| **技能（Skill）体系** | `zafiro.md` D1,D2 + §3.1.7／`aicode.md` §3.2.3 + B8／`coomi.md` §3.4.2 + A12,A13 | **系统提示词只放清单**（id/name/description），正文靠 `load_skill` 按需加载；可选加相关性打分限流注入 |
| **MCP** | `zafiro.md` A2,D6 + §3.1.6／`coomi.md` §3.4.4 + A14,A15／`aicode.md` §3.2.4 | 工具名走 `mcp__<server>__<tool>` 线缆名消歧；按配置签名决定刷新时机；**不做 MCP Server（Ktor 违红线）** |
| **Provider 容错 / 重试** | `coomi.md` §3.1.5 + A3,A4／`zafiro.md` A6,A7,A8 + §3.2.2／`rikkahub.md` A8／`aicode.md` E1 | 双层重试（传输层指数退避 + Retry-After 优先；回合层「未 commit 才重试」）+ **400 响应按错误文本摘字段降级重试** + `Content-Type: text/html` 快速失败 |
| **多 Key 轮换** | `rikkahub.md` §3.1.8 + A8／`coomi.md` §3.1.1 + A10／`aicode.md` §3.5 + E1 | Key 框支持多把（逗号/换行分隔），`KeyRoulette.next()` 按 LRU/加权取；失败切走并冷却 |
| **本地模型** | `operit.md` §3.5 + #3,#7／`coomi.md` §3.5.1（反向）／`zafiro.md` §1（无，反向） | **工具暴露降级**（阈值驱动：工具总数 >12 时只给核心集 + `tool_search`/`tool_proxy`）；local-llama 路径加 `ToolCallRepairer`；**不学 Coomi 的外部命令兜底** |
| **媒体生成（图/视频）** | `coomi.md` §3.5.2（无实现，反向）／`rikkahub.md` §3.5（反向）／`zafiro.md` §1（无，反向） | **AgentKit 已有 `core/media/providers/` 四家，反向输出、保持领先**；只借鉴「异步任务 + 轮询取结果」的任务模型 |
| **文件编辑与写保护** | `aicode.md` §3.1.1 + A4,A5,A6,A7／`coomi.md` §3.5.3 工具表 | `edit_file` 精确字符串匹配 + 整批原子；`read_file` 登记 sha256 指纹，写前校验返 `STALE_FILE`；Checkpoint 改懒快照 |
| **代码能力 / 文件探索** | `aicode.md` §3.1.2 + A3,A4,A5,A7／`coomi.md` §3.5.3 工具表 | `list_dir` + `search_code` + `@` 文件提及三件套；`rg --files` 遵守 .gitignore，清单不跨会话缓存 |
| **设备自动化 / UI 子代理** | `operit.md` §3.3.2 + #2／`zafiro.md` §3.3.4 + G2／`coomi.md` §3.5.4 | 有界 UI 子代理循环（`maxSteps=12`）+ 每轮只留最近 1 张截图 + `take_over` 优雅退出；**虚拟屏 AgentKit 已反超** |
| **构建 / 打包 / 推 GitHub** | `aicode.md` §3.6.3 + D8, C1-C6／`coomi.md` §3.5.3（CoomiDev，反向）／`zafiro.md` §1 表 | **保留 AgentKit「推 GitHub→CI→拉回→安装」路线**；只借鉴 `apk` 安装预检 `canRequestPackageInstalls` 与构建链就绪检测 |
| **备份 / 原子写** | `rikkahub.md` §3.9 + A15／`coomi.md` A21／`aicode.md` D5 | 配置与会话「写 `.tmp` → `rename`」；备份 ZIP 走「暂存 → 校验 → 发布」两阶段（`java.util.zip`，JDK 自带） |
| **记忆系统** | `rikkahub.md` §3.3 + A9／`operit.md` §3.7.1 + #14／`zafiro.md` D3 | Markdown 文件树 + frontmatter + version 乐观锁；**只抄「结构化决策 + 去重 + 有序写回」，不引向量检索** |
| **UI 架构 / MVI / 导航** | `zafiro.md` §2.4,§2.5 + E1,E2,E3／`aicode.md` §2.4 双栏 | MVI 基类 + **State/Effect 判据（"换个时间再读还成立吗"）**；导航每 entry 独立 ViewModelStore + 300ms 防抖；Ak* 组件必须配 `@Preview` |
| **日志与可观测** | `zafiro.md` F1／`coomi.md` A20,A3 | `AgentLogger` 补落盘轮转（8MB × 10）；配置 `toString` 强制脱敏 apiKey；重试事件对 UI 透明（「正在恢复 n/N」） |

---

## 三份「只读思路」的合规边界

| digest | 许可 | 允许的阅读深度 | 落地要求 |
|---|---|---|---|
| `rikkahub.md` | AGPL-3.0 | 架构、数据模型形状、交互取舍 | 净室重写；不复刻其 `ProviderSetting`/`MessageNode` 结构命名 |
| `operit.md` | LGPL-3.0 | 协议形状、状态机、参数语义；**虚拟屏（Shower）与终端（OperitTerminalCore）两块为红线，只读思路、不触碰实现** | 净室重写 |
| `aicode.md` | GPL-3.0 | 提示词工程思路、文件编辑安全性工程、proot 踩坑经验 | 净室重写；其 shell 脚本（场景库）为思路借鉴，自写 |
| `coomi.md` | Apache-2.0 | 可深参 | 仍按净室重写保持一致性 |
| `zafiro.md` | MIT | 可深参 | 仍按净室重写保持一致性 |

> **引用规范**：本文件夹内所有引用均标注 `digest 文件名 + 原仓库路径:行号`，便于回查；落地文档引用本库时请标注 `来源：xxx.md`。

---

*索引完 · 下一步：打开 `00-roadmap.md` 按 Phase 1 开工*
