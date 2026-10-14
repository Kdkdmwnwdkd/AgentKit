# QA 报告 · Phase 1 收尾批（T07 终端后端抽象 + T08 会话池 + 应用操控补口 + 主理人集成）

> QA：严过关（Edward） · 日期：2026-10-14 · 分支 main · 环境 /workspace
> 编译口径：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks`

---

## 0. 复跑证据（原始汇总行）

| 项目 | 命令 | 原始结果 |
|---|---|---|
| 编译 | `gradle assembleDebug --offline --rerun-tasks` | `BUILD SUCCESSFUL in 1m 1s` / `39 actionable tasks: 39 executed` |
| T02-T06 | `run-verification.sh` | `=== 汇总: 32 passed, 0 failed ===` |
| T01 | `run-t01-verification.sh` | `=== 汇总: 35 passed, 0 failed ===` |
| T01 探针 | `run-t01-probe.sh` | `=== 汇总: 11 passed, 0 failed ===` |
| T01 探针2 | `run-t01-probe2.sh` | `=== 汇总: 7 passed, 0 failed ===` |
| 应用操控 | `run-appcontrol-verification.sh` | `=== 汇总: 27 passed, 0 failed ===` |
| T07/T08 | `run-t07t08-verification.sh` | `=== 汇总: 32 passed, 0 failed ===` |
| **QA 补充探针（新）** | `run-t07t08-adversarial.sh` | `=== 汇总: 17 passed, 0 failed ===` |
| dex 符号 | `unzip classes*.dex \| strings \| grep -c` | app_launch=12 app_list=11 app_stop=4 shell_run=5 shell_close=6 registerAppControlTools=5 __AGENTKIT_POOL_END__=1 |

六套 harness 全绿且计数与工程师自报一致（32/35/11/7/27/32）。

**git baseline 说明（影响 A 的比对手段）**：仓库仅 2 个 commit（L3/L4），本批新增/改动文件均为 **untracked**（`git ls-files --error-unmatch` 报 did not match），无法用 `git diff` 做逐行比对。A 项改以**代码走读 + 运行时探针**验证 T09/T10 语义。

---

## A. T09/T10 回归（最高优先）— 定级 **None（无回退）**

`core/container/ContainerSession.kt` 本批仅新增 T07 适配入口 `backend(context, distro)`（L39），`create`/`runCommand` 行为与签名原样。

运行时探针 `T07T08AdversarialProbe.testProotInvocationRegression`（真跑 `ProotRunner`）断言全部通过：
- `buildInvocation`：`argv[0]==proot 绝对路径`、`argv.last()==/bin/sh`；env 含 `HOME=/root`、`TMPDIR=/tmp`、`CI=true`、`NO_COLOR=1`、`PAGER=cat`、`GIT_CONFIG_GLOBAL`、`PATH` 含 `/usr/local/bin`、`LD_LIBRARY_PATH==三件套目录`、`PROOT_TMP_DIR`；`envArray.size ≥ envMap.size` 且含自定义项（宿主∪自定义并集）。
- `buildCommandArgv`：恒 **7 项**，`argv[4]=="agentkit"($0)`、`argv[5]==cwd($1)`、`argv[6]==命令原文($2)`，`argv[3]=="cd -- \"$1\" && eval \"$2\""`，且**脚本不含命令文本**——确认命令走**位置参数**、不拼串。
- `runCommand`（T10 PTY 回落）仍经 `toCommandLine → buildCommandArgv.joinToString(shellQuote)`，即逐项单引号强引用，命令按字面保留。

结论：T09（proot 落位/env 对齐）与 T10（位置参数传命令）语义**未回退**。

---

## B. T07 抽象是否真统一 — 定级 **None**

- 上层无「是容器/是本机」分支：`ShellTool.kt`、`ChatViewModel` 只认 `TerminalSessionPool` 句柄与 `TerminalIdentity`（L51-73/366-400）。
- `Su/Shizuku/Ssh` 只留枚举位（`TerminalIdentity.kt` 4 值 + `wire/fromWire`），工厂对非 User 直接 `return null`（`TerminalBackendFactory.kt:41-44`）；**零新依赖**（grep 无 `topjohnwu/rikka/chainfire/connectbot` 等导入；`app/build.gradle.kts` 依赖表未变）。
- 降级链/缓存/clearCache **有真实断言**（非仅 happy path）：harness 用假 provider 计数验证「缓存命中不再 probe（probeCount 恒 1）→ clearCache 后重探并回落 user（probeCount≥2）」。

---

## C. T08 会话池硬点 — 定级 **None**（六子项全过）

| 子项 | 结论 | 证据 |
|---|---|---|
| ① 4-hex 碰撞处理 | **真实现** | `TerminalSessionPool.kt:425-435`（8 次随机重试→`0x0000` 顺序补位）。harness 注入 `abcd,abcd,abce`→h2=abce；`0000`→h2=0001。探针 200 连开恒 `^[0-9a-f]{4}$` 且唯一。 |
| ② 身份固化冲突显式报错 | **真实现，非静默** | open L218-227 / run L282-288 返回 `identity_conflict`。探针额外证明「拒绝后既有句柄仍存活可执行」。 |
| ③ 每会话 Mutex | **真有界** | 探针时间证明：跨会话两条 `sleep 1` = **1004ms**（并行）；同会话两条 = **2006ms**（串行）。 |
| ④ user/proot 分池 | **真分池** | 探针：同别名 `shared` 在 proot/user 得**两个不同句柄**，关闭 user 不影响 proot。键 = `(backendKey, alias)`（L500）。 |
| ⑤ 僵尸释放别名可重开 | **真实现** | harness：`run`→`session_dead`+句柄回收；`open` 命中僵尸→回收后新建新句柄。 |
| ⑥ 截断器边界 | **全过** | 探针：=max-1/=max 不截断、=max+1 截断且 ≤ 上限并保头尾；BMP+代理对混排 `max∈1..200` 全程 `utf8` 稳定；构造「切点落在代理对中间」不产生孤立代理（`safeHeadEnd/safeTailEnd`）。 |

---

## D. 资源生命周期（重点排查遗漏）— 定级 **Major（必须修）**

- **`ChatViewModel` 无 `onCleared()`**；全仓 `TerminalSessionPool.closeAll()`（L321）/`shutdown()`（L336）**零生产调用点**（仅 harness 调用）。
- 池持**自有** `CoroutineScope(SupervisorJob()+Dispatchers.IO)`（L162），**不受** `viewModelScope` 取消影响。
- `MainActivity.kt:36` 用 `viewModel<ChatViewModel>()`（Activity 作用域）→ Activity `finish()` 清 VM 时旧的终端会话（LocalUserBackend 的 `/system/bin/sh` 进程、ProotBackend 的 proot 会话）与收集协程**不会被释放**（子进程不随 GC 回收）→ 会话/进程泄漏。
- **修法**：在 `ChatViewModel` 增 `override fun onCleared() { terminalPool.shutdown(); /* best-effort */ CoroutineScope(Dispatchers.IO).launch { terminalPool.closeAll() } }`（或给池加非挂起 `releaseAllAsync()`）。
- **构造侧安全**（None）：`terminalPool` 构造只装配 provider（`probe()` 惰性、被 `runCatching` 包裹），**不阻塞、不抛异常**；proot 不可用时工厂 `obtain` 返回 null → 池安全降级（`no_backend`）。

---

## E. 应用操控正确性 — 定级 **None**（附覆盖 caveat）

- `app_list` limit 钳制：`clampAppListLimit` → `[1,100]`（默认 30）；harness 全量断言（null/0/-5/100/101/200/区间）。系统应用过滤：`filterAppEntries` 按 `include_system`（harness 断言 5/7 条）。
- `app_launch` 三类解析均**有断言**：Unique（精确包名/精确名/模糊唯一/前缀收窄）、Ambiguous（含候选）、NotFound（含空 target/空候选）。歧义分支 `execute` 返回 `ambiguous_target` 失败、**不调用** `launchResolved`（代码分支隔离，L341-346）。
  - *caveat*：无 Context 时 JVM 无法运行时断言 `execute` 真不 startActivity；仅解析层 + 代码走读。
- `app_stop` 无通道**诚实降级**：`stopResolved` 返回 `stop_needs_manual`（绝不静默成功，L492-499）；harness 断言 `ShellCommandRunner.Unavailable.exec == null` + `stopUnavailableHint` 含「强制停止 / 设置 → 应用」。
- `readOnly` 标注合理：`app_list=true`、`app_launch=false`、`app_stop=false`（代码 L270/326/453）；`shell_run`/`shell_close=false`（探针断言）→ 含写即整批串行。

---

## F. 集成正确性 — 定级 **None**（附 Minor #2）

- `registerAppControlTools` **真实调用**：`ChatViewModel.kt:400`（dex 有符号，非死代码）。
- 注册顺序：appcontrol 追加在 shell 之后；`ToolRegistry.register` 为 `LinkedHashMap` 覆盖写，**无语义影响**（仅影响 `unknownToolMessage` 的「前 20 工具」提示覆盖面，见 Info #7）。
- `this` 用法：位于 `ToolRegistry().apply{...}` 内，`this` == 接收者 ToolRegistry，**正确**（传 `registry` 自身会触发类型推断递归，已注明）。
  - *建议（不阻断）*：可读性更稳的写法是 `val registry = ToolRegistry().apply{...}.also{ registerAppControlTools(it, app) }`，避免在 `apply` 作用域里隐式依赖 `this`；现有写法可接受。
- **Minor #2**：`terminalShellCommandRunner(terminalPool)` **零生产调用点**；`ChatViewModel:400` 用 2 参 → `shellRunner` 取默认 `Unavailable` → **app_stop 恒走诚实降级、真实停不了应用**。已在 KDoc/NOTES 如实标注（"shell runner 待接会话池"），非静默；建议接线一行：`registerAppControlTools(this, app, terminalShellCommandRunner(terminalPool))`。

---

## G. 纪律与哨兵 — 定级 **None**

- 零新增依赖：`app/build.gradle.kts` 依赖表与既有库无变化；无新库 import。
- 无 JUnit：仅注释提及「无 JUnit」，无引用。
- `app_stop` 结构性 TODO 在 KDoc 如实标注（`ShellCommandRunner` L409-413、`AppControlTools` 文件头）。
- 无新日志脱敏问题：池仅记 `handle/identity/pool/alias`（L255-258），**不记命令明文**；`ShellTool` 无日志。

---

## H. 死代码与 KDoc 一致性 — 定级 **Minor**

| # | 位置 | 说明 |
|---|---|---|
| H1 | `tools/ShellTool.kt:226` `terminalShellCommandRunner` | 零生产调用点（= Minor #2）。 |
| H2 | `core/container/ContainerSession.kt:39` `backend(context,distro)` | T07 新增适配入口**无调用点**（`ProotBackend.start()` 直接调 `ContainerSession.create()`，L52）。建议：ProotBackend 改走 `backend(...)` 或删除该函数。 |
| H3 | `terminal/backend/TerminalIdentity.kt:12` | KDoc 引用 `TerminalSessionPool.META_IDENTITY`，该类**无**此常量（身份存于私有 `Entry`）→ 悬空引用。 |
| H4 | `TerminalBackendFactory.getHighestAvailable()/getUserPreferred()` | 生产零调用点（仅 harness）。作为公开 API 可保留，记录在案。 |

`TerminalIdentity` 未实现身份已在 KDoc 写明「首期只留接口不实现」✓。

---

## 问题清单（按定级排序）

| 定级 | 位置 | 场景 | 后果 | 建议修法 |
|---|---|---|---|---|
| **Major** | `ui/ChatViewModel.kt`（缺 `onCleared`）+ `TerminalSessionPool.kt:321/336` | Activity 结束清 VM / 反复进出 | 终端会话与 shell 进程泄漏（子进程不随 GC 回收） | 增 `onCleared()` 调 `shutdown()` + best-effort `closeAll()`（或加非挂起 `releaseAllAsync()`） |
| Minor | `ui/ChatViewModel.kt:400` | app_stop 调用 | 无 shell runner → 恒降级、功能不可用（已如实标注） | 接线 `registerAppControlTools(this, app, terminalShellCommandRunner(terminalPool))`；或记 Phase 2 工单 |
| Minor | `tools/ShellTool.kt:226` | — | 死代码 | 用起来（同上）否则标注为预留 API |
| Minor | `core/container/ContainerSession.kt:39` | — | 死代码（新适配入口未用） | ProotBackend 改走它，或删 |
| Minor | `TerminalIdentity.kt:12` | 阅读 KDoc | 悬空引用误导 | 删引用或补 `META_IDENTITY` 常量 |
| Minor | `TerminalBackendFactory.kt:64/68` | — | 生产零调用 API | 保留即可，知悉未用 |
| Info | `ShellTool.kt` shell_run | 想开第二会话 | 无 alias 参数 → 只能复用默认别名会话 | 后续可加 alias 参数 |
| Info | `ToolRegistry.unknownToolMessage` | 自纠提示 | 工具数已 ~24，「前 20」不含 shell/app_* | 后续可调大/排序 |

---

## 路由判定：**Engineer**

- **必须修（Major，Round 1）**：`ChatViewModel` 资源生命周期收口——新增 `onCleared()` 释放 `terminalPool`（`closeAll()`/`shutdown()`），消除会话/进程泄漏。
- **可记工单（Minor/Info，不阻断交付）**：app_stop runner 接线、两处死代码（`terminalShellCommandRunner` / `ContainerSession.backend()`）、`META_IDENTITY` 悬空 KDoc。
- 其余 A/B/C/E/F/G 全绿；编译与六套 harness 复跑与自报一致；dex 符号齐备。

> 说明：Minor 项**不影响本批编译与已交付逻辑**；若主理人判定「本批先交付、生命周期修复随下一批」，则本批可判 `NoOne`。QA 从对抗性职责出发，将 D 定为 Major 并**建议先修**。
