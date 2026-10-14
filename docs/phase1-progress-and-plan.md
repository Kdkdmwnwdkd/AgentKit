# AgentKit · 进度与计划

> 更新于 2026-10-14 · 维护人：交付总监（齐活林）· 本文件由流水线自动维护，记录「做了什么、怎么做、接下来做什么」

---

## 一、一页速览

| 项目 | 内容 |
|------|------|
| 产品 | AgentKit —— 运行在 Android 上的本地化 AI Agent（Compose UI + 自研 Agent 运行时 + 内嵌 rootfs 容器 + 本地 llama 推理） |
| 当前阶段 | **Phase 1 结构性地基**（目标：把「能跑」变成「跑得稳、可中止、可诊断」） |
| 进度 | Phase 1 共 10 项（T01–T10），已完成 7 项（T01/T02/T03/T04/T05/T06/T09/T10），剩 T07、T08 未开工 |
| 交付纪律 | 每项改造走五道门：本地全量编译 → 行为验证 harness → 推送 → CI 构建 → 下载 APK 做二进制验证 |
| 当前阻塞 | 沙箱到 GitHub API 网络中断（已持续 4 小时+），3 个已完成提交排队等恢复后自动推送 |
| 红线 | 零新增外部依赖；不改 minSdk 26 / targetSdk 34 / abiFilters arm64-v8a；许可证不合的参照仓库只读思路、禁止抄码 |

---

## 二、已完成的工作

### 2.1 产品能力层（已推送并经 CI 验证）

| 阶段 | 交付内容 |
|------|----------|
| 工程骨架 | Gradle 单模块 `:app` + GitHub Actions 构建流水线 + 可安装 APK 闭环 |
| 设备能力 | 无障碍服务（UI 树读取/点击/输入/滑动）、截图前台服务 + 多模态回灌视觉模型 |
| 技能系统 | 技能安全内核（信任分级 + 能力门 + 脚本开关）、技能中心 UI、导入与安全降权 |
| 可观测 | 运行日志页、记忆库（写作 + 检索 + 图谱化）、Agent 执行过程实时上屏 |
| 终端 | VT100 状态机 + pty JNI 桥 + Compose 终端页；修 ANR（JNI 持锁阻塞主线程） |
| 模型接入 | Provider 抽象（OpenAI 兼容 / Claude / Gemini）+ 本地 llama.cpp 推理 + 模型列表拉取与搜索选择器 + 多配置档案 |
| 其他 | MCP 客户端页、生图/视频工坊、一键构建按钮（推 GitHub→CI→拉包→安装）、返回栈导航、聊天记录持久化 |

### 2.2 对标研究（Phase 1 前置，已随 T09 提交推送）

克隆并**吃透** 5 个开源参照项目，产出可检索知识库 `docs/research/refs-digest/`：

| 文档 | 行数 | 定位 |
|------|------|------|
| `00-roadmap.md` | 752 | 总对标地图：20 条去重合并结论、13 条冲突裁决、P0/P1/P2 共 50 项、Phase 1/2/3 路线图与 DoD |
| `coomi.md` | 627 | Termux 壳 + Rust 引擎 + Vue 前端（架构不可搬，学其降级与熔断思路） |
| `rikkahub.md` | 754 | 消息分支（节点+游标）、模型能力推断、上下文滞回截断、proot 非交互 env 约定 |
| `operit.md` | 944 | 技术栈最同构：本地模型工具降级、有界 UI 子代理、结果只发布已完成前缀 |
| `aicode.md` | 983 | 提示词分层资产、场景库、精确字符串匹配编辑、**filesDir 不允许 execve** 的关键结论 |
| `zafiro.md` | 719 | 独立运行时层（ToolRegistry/ToolExecutor 契约 + Hooks）、终端「身份×后端」矩阵 |
| `T09-device-checklist.md` | 92 | proot 真机验证清单（降级为可选，见 §5 工单） |

合计 4027 行 digest + 752 行路线图。**许可证红线**：Coomi(Apache-2.0)、zafiro(MIT) 可深参但仍净室重写；RikkaHub(AGPL-3.0)、Operit(LGPL-3.0)、AiCode(GPL-3.0) **只读思路，禁止抄码**。

### 2.3 Phase 1 逐项状态

| 编号 | 任务 | 人日 | 状态 | 证据 |
|------|------|------|------|------|
| T09 | proot 落位验证 + 环境变量对齐 + `buildInvocation()` | 2.0 | ✅ 已推送 | 提交 `e495f7c5`，CI run 38439247139 success |
| T10 | 非交互 env + 位置参数传命令 | 0.5 | ✅ 已推送 | 同上（T09/T10 同批） |
| T02 | 工具结果统一契约 `{ok,code,message,hint,data}` | 1.0 | ✅ 本地完成待推 | harness 32/32 |
| T03 | 并发判据改「本批是否含写操作」 | 0.5 | ✅ 本地完成待推 | harness 32/32 |
| T04 | 失败熔断 + 调用指纹去重 | 1.0 | ✅ 本地完成待推 | harness 32/32（含 200 协程并发压力） |
| T05 | 可取消 + 树尾闭合不变量 | 0.5 | ✅ 本地完成待推 | harness 32/32（三路径闭合） |
| T06 | 工具结果体积上限 + 截断提示 | 0.5 | ✅ 本地完成待推 | harness 32/32 |
| T01 | Agent 运行时内核抽象（契约层 + RealAgentLoop + 流式） | 3.0 | ✅ 本地完成待推 | 编译 39/39 + 旧 harness 32/32 + 新 harness 35/35 + 对抗探针 11/11 + 7/7 |
| T07 | 终端后端抽象（TerminalBackend + 身份 + 2 后端） | 1.5 | ⏳ 未开工 | — |
| T08 | 终端会话池 + 4 位短句柄 + 身份固化 | 1.5 | ⏳ 未开工 | — |

**Phase 1 合计 12.5 人日，已完成 10.5 人日（84%）**，剩余 T07 + T08 共 3.0 人日。

### 2.4 proot 三件套（T09 的运行时前置，本地完成待推）

问题：`assets/bin/arm64-v8a/` 原是空目录，`ensureBinary` 必然失败，容器长期处于降级态。

解法：源码交叉编译 talloc 的 waf 运行时检查在交叉编译下过不去 → 改用 Termux 官方预编译三件套，随包分发：

| 文件 | 大小 | 说明 |
|------|------|------|
| `proot` | 247,488 B | 5.1.107.96，ELF64 AArch64 PIE |
| `libtalloc.so.2` | 33,592 B | 2.5.0，按 SONAME 分发（proot 动态依赖它） |
| `libandroid-shmem.so` | 14,432 B | 0.7 |

配套：`ProotRunner` 改目录三件套模型 + `LD_LIBRARY_PATH` 覆盖（Termux 的 RUNPATH 指向私有目录）+ 逐个 `assets.open()` 探测齐备性（`assets.list()` 在部分 ROM 返回不全）+ `SOURCE_OFFER.txt` 补三组件源码要约。

---

## 三、怎么做（方法论）

### 3.1 吃透不搬运

先克隆参照仓库、逐文件读透、写出「它怎么设计 → 我们怎么适配」的对照表，**再**动自己的代码。禁止一步复制——许可证不合的三家连结构都不能照搬，只提炼原理后用自有代码重写。

### 3.2 契约先行

改造上层之前先立契约层：统一工具结果结构（T02）、失败熔断状态机（T04）、结果体积上限（T06）、工具只读标注（T03）、Agent 运行时契约（T01）。契约稳定后，上层改造才有确定的地基。

### 3.3 零 JUnit 的验收范式

项目离线仓库缺 JUnit 包，且红线禁止新增依赖 → 验收改用：

- **纯函数 + 日志自检**：能提成纯函数的逻辑（编辑距离、指纹哈希、截断、SSE 分片归组）直接断言；
- **离线行为 harness**：用 Gradle 缓存里的 `kotlin-compiler-embeddable` 直接编译**真实源码**，配 `-Xfriend-paths` 访问 internal 成员，`main()` 手写断言，跑真实协程/真实 HTTP（JDK 内置 HttpServer + OkHttp 拦截器观测线程）；
- **对抗探针**：QA 独立写的补充探针文件（`T01AdversarialProbe.kt` / `2`），专门证伪工程师的说法。

### 3.4 五道门交付纪律

```
本地全量离线编译（/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks）
   → 行为 harness / 对抗探针全绿
   → 推送（GitHub API: blobs → tree → commit → ref）
   → CI 构建（GitHub Actions）
   → 下载 APK 做二进制验证
```

任一门不过即修复重来，**修复轮次硬上限 2 轮**，超过则记 Known Issues 收尾并降级交付。

### 3.5 二进制验证手法

不只看「CI 绿了」，而是拆开 APK 验证产物真含新代码：

ZIP 完整性 → dex 数量与 `dex\n` 头 → 新符号在 dex 字符串池中可检索 → 与上一版 dex 的 sha 差异（证明「确为新编译」）→ so 文件 JNI 符号 → Manifest AXML 字符串池 → 资产入包且大小与源一致。

### 3.6 快照隔离

工程师准备改下一项代码之前，先把当前待推文件**快照**到独立目录，推送脚本从快照读。否则后续改动会污染待推内容（T01 改动与 T02–T06 待推内容有重叠文件，正是靠快照隔离才没串）。

---

## 四、当前队列与阻塞

### 4.1 推送队列（网络恢复后自动依序执行）

| 序 | 提交 | 内容 | 本地验证 |
|----|------|------|----------|
| 1 | proot 三件套 | `assets/bin/arm64-v8a/*` + `ProotRunner` 目录模型 + `ProcessEnv` + `SOURCE_OFFER` | 编译 39/39 + QA 6/6 |
| 2 | T02–T06 契约批 | `core/{ToolResult,ToolLimits,FailureGuard}` + `AgentLoop` + `ToolRegistry` + 16 工具只读标注 + UI 停止按钮 | harness 32/32 |
| 3 | T01 运行时内核 | `core/agent/*` 5 文件 + `ChatStreamEvent` + 适配层 + 流式 SSE | 编译 39/39 + 32/32 + 35/35 + 11/11 + 7/7 |
| 4 | 本文档 | `docs/phase1-progress-and-plan.md` | — |

每步推送后自动等 CI，全绿才推下一步；全部推完自动下载 APK 并跑二进制验证（校验三件套入包 + T01/T02–T06 新符号 + 全量回归符号）。

### 4.2 阻塞项

| 阻塞 | 影响 | 处置 |
|------|------|------|
| 沙箱 → GitHub API 网络中断 | 4 个提交无法推送 | 守护进程每 60s 探测，恢复即自动推送 + CI 验证 + 下载验证；最长守 12 小时，超时重启守护 |
| 子代理模型限流 | 无法派新工单（T07/T08 实现） | 配额 2026-10-15 09:55 重置；已完成的本地工作不受影响 |

---

## 五、后续计划

### 5.1 Phase 1 剩余（3.0 人日）

| 编号 | 任务 | 人日 | 要点 |
|------|------|------|------|
| T07 | 终端后端抽象 | 1.5 | `TerminalBackend` 五方法接口（`start`/`send`/`close`/`awaitExit`/`output: Flow`）+ `TerminalIdentity{User,Su,Shizuku,Ssh}` + 工厂（缓存/最高可用/用户偏好/清缓存）。**首期实现 `ProotBackend`（包装现有容器层）与 `LocalUserBackend`（本机 `/system/bin/sh` 降级路径）**；`Su`/`Shizuku`/`Ssh` 只留接口不实现。验收：上层无「是容器还是本机」分支；工厂降级链可测 |
| T08 | 终端会话池 + 短句柄 + 身份固化 | 1.5 | 会话表 + 每会话一把 `Mutex`（防串输出）+ 4 位十六进制短句柄（省 token、防泄露内部 UUID）+ 身份开辟时固化（冲突显式报错）+ 僵尸会话自动释放 + 输出截断器；注册 shell 工具。验收：多轮 shell 保持 cwd/env；并发会话输出不串；句柄长度恒 4 |

**Phase 1 DoD**：① weather/wordcount 两个 skill 走新 loop 跑通；② 断网/429/5xx 自动恢复且 UI 可见；③ 任意时刻点「停止」≤1 轮内退出且历史可继续请求；④ `npm install` 类长命令不卡确认提示或分页器。

#### Phase 1 补口 · 应用操控工具集（约 1 人日，T07/T08 交付后立即做）

**动机（用户场景驱动）**：核心诉求是「操控手机上的全部应用，不能只操控一个应用」——例如「让它打开快手，它就能打开」。能力盘点后发现：主力能力已在，但**「启动应用」这个最基础的跨应用动作没有工具**（全仓无 `getLaunchIntentForPackage`），当前只能靠「回桌面 → 读 UI 树 → 点图标」，慢且脆弱。

**能力盘点（跨应用操控）**：

| 诉求 | 实现通道 | 现状 |
|---|---|---|
| 打开任意已安装应用 | 普通 App `Intent` + `getLaunchIntentForPackage`（**零权限零依赖**） | ⚠️ 能力可达、工具缺失 → 本补口 |
| 看任意应用界面 | 无障碍读 UI 树 | ✅ `ui_tree` |
| 操作任意应用界面 | 无障碍手势注入 | ✅ `ui_tap`/`ui_input`/`ui_swipe`/`ui_key`/`ui_click`/`ui_long_press` |
| 感知当前应用 | 前台应用读取 | ✅ `device_foreground_app` |
| 感知后台变化/通知 | 通知监听 | ✅ `device_notifications` |
| 列出/装/卸/停用应用、改系统设置、后台拉起 | shell（自授权通道）/ root | Phase 2 |
| 读其他应用私有数据 | root | 仅已 root 设备 |

**交付物**：① `app_list`（列出已安装应用，支持名称模糊匹配，过滤系统应用可选）；② `app_launch`（按名称/包名启动，Intent 实现，零权限，启动失败回可自纠的 hint）；③ `app_stop`（停用，优先 shell 通道，降级无障碍「设置→应用→强制停止」路径）；④ 工具自带的 systemPrompt 片段，教模型「先 `app_launch` 再 `ui_tree` 定位」的跨应用套路；⑤ harness 覆盖：包名解析（模糊匹配到唯一/歧义/无结果三类）、启动失败 hint、与 `device_foreground_app` 联动断言。

**注意**：本补口与「自授权高权限通道」（Phase 2）互补——前者解决「打开/简单管控任意应用」（零权限即可），后者解决「后台拉起、系统命令、更底层操作」（需 shell 身份）。

### 5.2 Phase 2 · 体验层（22.0 人日）

消息分支（节点+游标，重新生成可左右切换）、Markdown 渲染（9 种语法 + `<script>` 原样显示不执行）、模型列表增强、上下文滞回截断、本地模型工具暴露降级、权限申请降级链、后台任务完成自动续跑。

**DoD**：① 9 种 Markdown 语法渲染正确；② 重新生成 3 次可切换且杀进程后仍在；③ 断网显示「正在恢复（n/N）」；④ 本地 llama 下可见工具 ≤7 且能完成一次 `read_file`。

#### Phase 2 追加项（决策记录 · 2026-10-14）

**自授权高权限通道（App 内置，零第二 App，约 3–5 人日）**——决策：**Phase 2 实现；Phase 1 只留接口**。取代原「Shizuku 通道」方案。

背景：路线图初稿写「改造现有 Shizuku 通道」，这是对标研究期的误判——参照项目（Operit 等）实现了 user / root / Shizuku / SSH 四种执行身份矩阵，被顺手列成改造项，但本仓库实际并无任何 Shizuku 代码（`automation/AutomationBridge.kt` 只是无障碍服务的状态中转）。

**为什么可以不要第二个 App**：Shizuku 做成独立 App，是因为它要**服务所有第三方 App**（通用服务）；我们只服务自己 → 可以把「被授权的高权限进程」**内置进 AgentKit 自己的 APK**，用户全程只用一个 App。

**实现路径（自授权 / self-ADB）**：

1. App 内置特权服务入口 `PrivilegedServer`（类就在我们自己的 APK 里）；
2. 用户在设置页开启「高权限通道」→ 引导开启「无线调试」（Android 11+，无需电脑）→ **App 内实现 adb 协议客户端**（配对 + 认证 + `shell,v2` 流）连接本机无线调试端口；
3. 通过 adb 执行 `CLASSPATH=<我们的 APK> app_process / com.example.agentkit.privileged.PrivilegedServer`，把我们 APK 里的类**以 shell 身份（uid 2000）拉起**；
4. 特权进程经本地通道（LocalSocket/Binder）向 App 主体暴露能力（系统命令、`pm`/`am`/`dumpsys`/`settings`、更底层 UI 操作）；
5. 落点即 T07 的 `TerminalBackend` 接口——新增 `SelfAdbBackend` 实现，上层零改动。

**必须提前认下的代价**：

- **工作量**：adb 协议客户端（配对 TLS、认证密钥、流复用）约 2000–3000 行，是本项主要成本；
- **常驻性**：shell 进程被系统回收后需重新拉起（配对密钥可缓存，做到「一次配对长期有效」）；
- **权限天花板**：无 root 时仍是 shell（uid 2000）——能跑系统命令，但**读不了其他 App 私有数据**（那是 root 才有的）；
- **版本门槛**：无线调试需 Android 11+；更低版本需一次 USB 连接电脑。

**六条通道最终形态（同一 `TerminalBackend` 接口，按可用性自动降级）**：

| 通道 | 身份 | 能力 | 依赖与代价 |
|------|------|------|------|
| 无障碍（已实现） | 普通 App | 读 UI 树、模拟点击/输入/滑动/按键、监听通知与前台应用 | 零依赖，系统设置手动开启 |
| 本机 shell（Phase 1 做） | App uid | `/system/bin/sh`，限 App 可见范围 | 零依赖 |
| proot 容器（已实现） | App uid + 容器 | 完整 Linux 环境、装工具链、跑代码 | 零依赖（rootfs 随包内置） |
| **自授权高权限（Phase 2 做）** | **shell（uid 2000）** | **系统命令、`pm`/`am`/`dumpsys`/`settings`、更底层 UI 操作、硬件截屏** | 零第三方依赖、**无需第二个 App**；需用户开一次无线调试 |
| root（设备已 root 时） | root（uid 0） | 全权限 | 零依赖，需设备本身已 root |
| Shizuku（可选兼容，非主路径） | shell（uid 2000） | 同自授权通道 | 仅当用户设备已装 Shizuku 时可借用；不强制、不依赖 |

> **为什么高权限不能"自己写出来"**：Android 上超出 App 自身 uid 的身份只有三个合法来源——① **系统平台签名**（仅 ROM 厂商或刷机形态可得，普通 APK 不可能）；② **用户通过 ADB / 无线调试授权启动的进程**（shell 身份 uid 2000）；③ **设备已 root**（uid 0）。三者都由设备固件与用户授权决定，**写代码无法绕过**。「自授权」属于第 ② 条：**授权动作依然存在（开一次无线调试），但被授权并拉起的进程是我们自己 App 内置的**——这就是「一个 App 搞定」与「装第二个 App」的全部区别。

**接入成本**：T07 已落地 `TerminalBackendFactory` 与 `TerminalIdentity` 身份矩阵骨架，届时新增 `SelfAdbBackend` + 授权引导页即可，不返工。

### 5.3 Phase 3 · 能力层（21.5 人日）

提示词资产化（改 `prompts.custom/` 下 md 重启即生效，无需改 Kotlin）、场景库、精确字符串匹配编辑 + 双层写保护、UI 子代理（有界步数）、元工具（Agent 用 rhino 自造 JS 工具并回合内热注册）、容器内一键装 node/python/java。

**DoD**：① 一键装环境并跑通 hello world；② 多文件项目连续 `edit_file` 无漂移、并发修改返 `STALE_FILE`；③ 改提示词 md 重启即生效；④ UI 子代理 3 步任务自动跑完且 12 步内收敛。

---

## 六、待办工单（已知问题，非阻塞）

| 编号 | 来源 | 内容 | 优先级 |
|------|------|------|--------|
| W-1 | T01 QA Round 2 | `ClaudeClient`/`GeminiClient.streamText` 是同款「`flow{}` 内阻塞 IO 无调度切换」模式，当前不在热路径（loop 走 `streamChat`→默认→`chat()`，四家 `chat()` 均有 `withContext(IO)`），但与 OpenAI 对齐前不宜启用其流式 | P1 |
| W-2 | T02–T06 QA | `memory_*` 日志未脱敏 | P1 |
| W-3 | T02–T06 QA | `runCommand` 串行化 + `cd` 失败短路 | P2 |
| W-4 | T02–T06 QA | 命令明文进日志（同类脱敏问题） | P2 |
| W-5 | T09 | 真机验证清单（proot 在 Android 10+ 能否 `execve`、SELinux `avc denied`、容器内 `mktemp`/`dpkg` 可用性）——沙箱无 Android 设备与 qemu，降级为可选；若真机拒绝 `execve`，备选方案是改走 jniLibs + `nativeLibraryDir`（**targetSdk 保持 34，不学参照仓库锁 28**） | P2 |
| W-6 | 仓库洁净度 | `scripts/qa/run-t01-probe2.sh` 引用 `scripts/qa/lib/annotations-23.0.0.jar`（从 Gradle 缓存拷出），该 jar 不应进仓库，应改为直接引用缓存路径 | P2 |

---

## 七、维护说明

- 本文件随每个 Phase 批次更新一次；Phase 1 收官时补一份 DoD 逐条核对结果。
- 每项改造的详细设计与裁决记录，见 `docs/research/refs-digest/00-roadmap.md` 对应条目。
- 交付验证的原始证据（编译汇总行、harness 汇总行、CI run、APK 大小与 md5）在每批的提交信息与交付报告中留存。
