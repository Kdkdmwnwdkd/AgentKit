# T07 / T08 交付说明（终端后端抽象 + 终端会话池）

> 负责人：寇豆码（Kou）· Phase 1 · 单模块 `:app`，包 `com.example.agentkit`
> 口径：零新增外部依赖 · 不新增 JUnit · 改 minSdk/targetSdk/abiFilters 的诉求一律拒绝。

---

## 1. 文件清单（新增 / 修改）

| 文件 | 状态 | 说明 |
|---|---|---|
| `core/terminal/backend/TerminalBackend.kt` | 前一位（走读确认） | 接口 + `TerminalBackendSource` + `TerminalBackendProvider` |
| `core/terminal/backend/TerminalIdentity.kt` | 前一位 | 身份枚举（全量 4 值 + `wire`/`fromWire`） |
| `core/terminal/backend/ProotBackend.kt` | 前一位 | 包装 `ContainerSession` + provider + 工厂装配 |
| `core/terminal/backend/LocalUserBackend.kt` | 前一位 | `/system/bin/sh` 本机后端 + provider |
| `core/terminal/backend/TerminalBackendFactory.kt` | 前一位 | 缓存 + `getHighestAvailable`/`getUserPreferred`/`clearCache` |
| `core/terminal/ToolOutputTruncator.kt` | 前一位 + **我补** | 截断器；**本次补了代理对（surrogate）对齐**（见 §5.1） |
| `core/terminal/TerminalSessionPool.kt` | **我新增（T08 核心）** | 会话池：短句柄 / 每会话锁 / 身份固化 / 分池 / 僵尸回收 / 哨兵完成判定 |
| `tools/ShellTool.kt` | **我新增** | `shell_run` / `shell_close` + `terminalShellCommandRunner` 桥 |
| `ui/ChatViewModel.kt` | **我改**（3 处） | import + `terminalPool` 属性 + registry 注册两工具 |
| `core/container/ContainerSession.kt` | 前一位 | 仅新增 `backend(...)` 适配入口，`create`/`runCommand` 原样 |

`scripts/qa/`：`T07T08Verification.kt`（新）、`run-t07t08-verification.sh`（新）、本 NOTES。

**归属结论（如实记）**：T07 六个文件是前一位工程师的产物，本次**走读核对后未发现不完整**，未作功能性改动（仅在 KDoc 已引用的 `ShellTool` 上补齐实体类）。T08 全部实现（`TerminalSessionPool.kt`、shell 工具、注册）与那份代理对对齐修复**为我本次补**。

---

## 2. T07 走读结论（逐项核对）

- **接口 5 方法齐全**：`start` / `send` / `close` / `awaitExit` / `output: Flow`；另加 `identity` / `backendKey` / `isAlive` 三个属性（`isAlive` 供会话池识别僵尸，属必要扩展）。
- **`ProotBackend` 真包装 `ContainerSession`**：`start()` 惰性调用 `ContainerSession.create`（proot 三件套 + rootfs 校验 + argv/env 组装的活仍归 `ContainerSession`/`ProotRunner`），pty 原始输出经 `TerminalSession.onRawOutput` 桥进 `output`，`onExit` 接 `awaitExit`。不含「是容器还是本机」分支。
- **`LocalUserBackend` 用 `/system/bin/sh`**：`DEFAULT_SHELL = "/system/bin/sh"`；零 `android.*` 依赖（纯 `ProcessBuilder`），故 JVM 可换 `/bin/sh` 跑真实端到端。
- **工厂三方法齐全**：`getHighestAvailable()`（proot→回落 user）、`getUserPreferred()`（本机 user）、`clearCache()`；缓存的是**可用性判定**而非后端实例（一后端=一条独立 shell 进程，不能被多会话共享）。
- **身份契约**：`Su`/`Shizuku`/`Ssh` 全量保留枚举位，工厂对它们返回 null，**未引入任何依赖**。

结论：**T07 自洽完整，无需补齐**。

---

## 3. T08 `TerminalSessionPool` 设计要点

1. **对外 4-hex 短句柄**：`TERMINAL_HANDLE_REGEX = ^[0-9a-f]{4}$`，省 token、防泄露内部 UUID。分配 = 随机重试 `HANDLE_ALLOC_RETRIES=8` 次，**反复碰撞则从 `0x0000` 起顺序补位**（上限 65536 会话）。生成器可注入（测试构造碰撞）。
2. **每会话一把 `Mutex`**：同会话命令串行（防两条命令的输出在共享输出流上交织/串读），跨会话各持各锁、互不阻塞。
3. **身份开辟时固化**：身份写入会话元数据；`open` 同别名冲突身份、`run` 声明身份不符 → **显式 `identity_conflict`**，绝不静默复用（防「用户以为提权、命令仍以旧身份跑」）。
4. **user / proot 分池**：别名槽位按 `(backendKey, alias)` 分键，两池各占独立槽位，互不串味；别名缺省由 `TerminalBackendFactory.defaultAliasFor` 推导。
5. **僵尸自愈**：别名槽位上的后端一旦不存活，`run` 报 `session_dead` 并回收；`open` 命中僵尸则先回收再新建——**别名自动释放、可重开**。
6. **完成判定（哨兵协议）**：写完命令后追加 `echo "__AGENTKIT_POOL_END__<token>__$?"`，扫描输出流至匹配「哨兵 + 紧跟数字退出码」，切出本次命令真实输出。哨兵含每次随机 token；**要求其后紧跟数字**，从而**不误判 PTY 回显的 `echo "...$?"`**（回显后是 `$` 不是数字）。
7. **截断**：输出统一过 `ToolOutputTruncator`，上限口径与 `core/ToolLimits.kt` 同源（`MAX_TOOL_RESULT_CHARS = 24000`）。

---

## 4. harness 覆盖（`T07T08Verification.kt`）

| # | 用例 | 手段 |
|---|---|---|
| 1 | 两后端走同一接口 | 假 proot 后端 + 真 `/bin/sh`（`LocalUserBackend`），均经会话池 |
| 2 | `getHighestAvailable()` 降级链 | 假 provider：proot 可用→proot；不可用→user |
| 3 | 缓存 + `clearCache()` 重求值 | provider `probe()` 计数：缓存命中不再 probe；clearCache 后重探并生效 |
| 4 | 句柄格式 + 碰撞 | 默认生成器 8 次全 4-hex 且唯一；脚本化生成器构造「重试」与「顺序补位」 |
| 5 | 身份冲突显式报错 | 宽松 source：`open` 冲突 + `run` 冲突均 `identity_conflict`；同身份复用 |
| 6 | 并发输出不串 | 跨会话并行 + 同会话串行（分片 + 延迟制造交织窗口） |
| 7 | 多轮保持 cwd/env | 假后端（cd/export 状态持久）+ 真 `/bin/sh` |
| 8 | 僵尸释放别名可重开 | 杀后端→`session_dead`+句柄回收；同别名重开新句柄 |
| 9 | `ToolOutputTruncator` 边界 | =上限 / 超限（留头尾）/ BMP 中文 / 表情代理对 20..140 全上限 UTF-8 往返稳定 |
| 10 | shell 工具 + runner 桥 | `shell_run`/`shell_close` 参数校验；`terminalShellCommandRunner` 真跑 + 无后端时 null |

---

## 5. 关键裁决

### 5.1 代理对（surrogate）对齐 —— **我补的一处修复**
`ToolOutputTruncator` 原实现用 `substring(0, headLen)` / `substring(len - tailLen)` 直接切。对 **BMP 多字节字符（中文/重音字母）** 无问题（Java/Kotlin 字符串是 UTF-16 码元，BMP 字符 = 1 码元，`substring` 不会切开）；但对 **代理对字符（emoji 等非 BMP）**，当头/尾切点恰落在代理对中间时，会切出**孤立代理**（半个 `😀`，非法 UTF-8 码点）。本次加 `safeHeadEnd` / `safeTailStart` 两处对齐（仅回退/后移 1，只会让输出变短，不破坏 `≤ maxChars` 不变量），使「多字节字符不被切断」对 BMP 与代理对**双双成立**。判定：属 T08 应有语义的补齐，**不属推倒重来**。

### 5.2 完成判定用哨兵 `echo "...$?"` 而非独立退出码管道
现有 `TerminalBackend` 只有一路 `output: Flow`（无独立 stdout/exitCode 通道），故以哨兵行承载退出码，符合接口现状、零依赖。**代价**：多行命令只反映最后一条语句退出码；命令自身若打印哨兵样式文本（含随机 token）概率极低——已在 KDoc 标注。

### 5.3 工具的 `readOnly` 标注
`shell_run` / `shell_close` 均标 `readOnly=false`：shell 命令可能改设备状态，且终端命令本就需要「整批串行」的语义（含任一写即整批串行，与 T03 并发判据一致）。

### 5.4 会话复用 vs 一次性
`shell_run` 不带 `session` 时**开辟并保留**会话（返回句柄供后续复用），以保证「多轮 cd/export 生效」；不再需要时用 `shell_close` 释放。未做「一次性自动关闭」（否则多轮状态无法保持）。**遗留**：会话在 App 进程存活期内常驻，当前无「空闲超时自动回收」，靠 `shell_close` / 进程退出兜底（见 §7）。

### 5.5 `app_stop` 真实 shell runner —— **接口已落点，注册行待主理人**
`tools/AppControlTools.kt` 定义为公开扩展点 `ShellCommandRunner`；本次在 `tools/ShellTool.kt` 提供 **`terminalShellCommandRunner(pool)`**：把会话池适配成真正可执行的 runner（默认 `preferProot=false`——`am force-stop` 针对宿主 Android，应走本机 user shell）。会话池无可用后端时返回 `null`，app_stop **仍走原有「诚实降级为需用户手动停止」**路径。
**未改动 `AppControlTools.kt` 结构**（按要求）。**注册落点**：主理人在 ChatViewModel 补 appcontrol 注册行时，传入本 runner 即接通真实通道：
```kotlin
registerAppControlTools(registry, app, terminalShellCommandRunner(terminalPool))
```
（本次**未**添加该行，以避让主理人对 appcontrol 注册行的既定归属，避免重复注册。）

---

## 6. 自检证据

见回传正文（编译 39 tasks 全绿 / 新 harness 全绿 / 回归五套）。命令：

```
/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks        # 全量离线编译
bash scripts/qa/run-t07t08-verification.sh                              # 新 harness
bash scripts/qa/run-verification.sh                                     # 回归 T02-T06
bash scripts/qa/run-t01-verification.sh                                 # 回归 T01
bash scripts/qa/run-t01-probe.sh
bash scripts/qa/run-t01-probe2.sh
bash scripts/qa/run-appcontrol-verification.sh                          # 回归补口
```

---

## 7. 遗留 / 注意

1. **proot 端到端仅逻辑验证**：真机 proot 需要 Android `Context` + native pty，JVM harness 以**假后端**验证「同构接口 + 池语义」；真机路径需在设备上跑一次 `shell_run`（`identity=user`，`prefer_proot=true`）确认容器端到端。
2. **无空闲超时回收**：会话常驻直到 `shell_close` 或进程退出；后续可加 LRU/空闲 TTL。
3. **PTY 回显清洗是「尽力」**：容器（proot，有 PTY）会回显命令与 `echo` 行，本池做逐行清洗；无 PTY 的本机后端天然干净。极端命令（命令体含与回显同形文本）可能残留一行回显，属已知取舍。
4. **多行命令退出码**：只反映最后一条语句。
5. `termux` 旧终端页（`terminal/TerminalSession`）与本后端抽象**并存**，未合并（T07 只做「纳入统一接口」，未迁移 UI）。

---

## 8. QA 回修 · Round 1（T07/T08 + 应用操控） — 1 Major + 3 Minor

> 依据 `scripts/qa/QA-T07T08-APPCONTROL.md`（QA 严过关）· 修复人：寇豆码（Kou）· Round 1/2
> 口径不变：零新增依赖 · 无 JUnit · 不推送/不碰网络。

### ① [Major] 资源生命周期收口 —— 已修

- **根因**：`ChatViewModel` 无 `onCleared()`；`TerminalSessionPool` 持自有 `CoroutineScope`，池内后端是**真实子进程**（本机 `/system/bin/sh` / proot），不随 GC 回收 → Activity 结束后泄漏。
- **修法（最小惊讶裁决）**：给 `TerminalSessionPool` 增**非挂起** `releaseAllAsync()`，`ChatViewModel` 增 `override fun onCleared()` 调它。
  - **为何不 `runBlocking`**：`onCleared()` 是主线程同步钩子，`closeAll()`（挂起版）需 `await` 后端关闭，`runBlocking` 会卡主线程且关闭子进程可能耗时 → 选非挂起 best-effort。
  - `releaseAllAsync()` 语义（三段）：**同步**清空两张会话表 + 标记 closed（调用方返回后旧句柄立即失效：`lookup/list` 不可见、`run` 报 `no_such_session`）→ **异步**用池自有作用域**之外**的短命 IO 协程关闭各后端（不与即将 `shutdown()` 的 `scope` 竞争）→ 最后 `scope.cancel()` 取消输出收集协程。
  - **异常安全**：空池提前分支、后端 `close()` `runCatching`、`onCleared` 再包一层 `runCatching`（钩子绝不抛）。
- **验收断言**（`T07T08Verification.kt` 新增 `testReleaseLifecycle`，3 条）：① `releaseAllAsync` 后 `list()`/`lookup()` 立即空 + `run→no_such_session`，且后端最终全部 `isAlive=false`（≤2s 轮询）；② 空池调用不抛；③ 幂等重复调用不抛。

### ② [Minor] app_stop shell runner 接线（启用死代码）—— 已修

- **修法**：`ChatViewModel.kt` 注册行改为 `registerAppControlTools(this, app, terminalShellCommandRunner(terminalPool))`，把 `ShellTool.kt:226` 的 `terminalShellCommandRunner` 从死代码接上真实会话池；`app_stop` 现在真能执行 `am force-stop`（默认 `preferProot=false`，走宿主本机 user shell）。
- **诚实降级保持**：池无可用后端 → runner 返回 `null` → `app_stop` 仍返回 `stop_needs_manual`，绝不静默假装成功。
- **可测性重构**：把 `AppStopTool.stopResolved` 的「结果 → ToolResult」三段映射抽成**纯函数** `internal fun appStopResult(label, pkg, outcome)`（无 Context 依赖），使三路径可在 JVM harness 断言。
- **验收断言**（`AppControlVerification.kt` 新增 `testAppStopResultMapping`，4 条）：exit 0 → `app_stopped`；`null` → `stop_needs_manual`（含「强制停止」）；非零退出码 → `stop_failed`（含退出码）；**注入假 runner**（返回成功 / 返回 null）两分支分别 `app_stopped` / `stop_needs_manual`。

### ③ [Minor] `ContainerSession.kt:39` 死代码 —— 已修（选「改走它」）

- **裁决：让 `ProotBackendProvider.create()` 改走 `ContainerSession.backend(context, distro)`**（而非删除）。
- **理由**：`ProotBackendProvider` 是**生产路径**（`createTerminalBackendFactory` → `TerminalBackendFactory.obtain` → `provider.create()`）。让它改走 `ContainerSession.backend(...)`，使「容器后端的构造」收敛为 `ContainerSession` 单一入口，消除「provider 直接 `new ProotBackend`」与容器模块适配入口**两条重复构造路径**；行为零变化（`backend()` 函数体即 `ProotBackend(context, distro)`）。删除则丢失这条容器包对外适配入口，且无助于「收敛构造路径」。**候选语义**：provider 只负责「探测 + 取 distro」，怎么造 proot 后端归容器模块。

### ④ [Minor] `TerminalIdentity.kt:12` 悬空 KDoc —— 已修（选「删引用」）

- **修法**：删掉指向不存在的 `TerminalSessionPool.META_IDENTITY` 的 KDoc 链接，保留「身份在会话开辟时固化（写入会话元数据）」这一有效陈述（后文仍指向真实的 `TerminalSessionPool.open`）。
- **为何不补常量**：仓库中身份存于 `TerminalSessionPool` 私有 `Entry.identity` 字段，**无**「元数据 map」机制；补一个仅被注释引用的常量不算「被真实使用」，而引入元数据机制属过度设计。

### 不做（记工单，知悉未用）

- `TerminalBackendFactory.getHighestAvailable()`/`getUserPreferred()`（L64/68）：生产零调用，作为公开 API 契约保留。
- `shell_run` 无 `alias` 参数：想同时开两条默认别名会话时只能拿到复用的那条 → 后续可加 `alias`。
- `ToolRegistry.unknownToolMessage` 的「前 20」：工具数增长（~24+）后不含 shell/app_* → 后续调大或按相关性排序。

### 自检（Round 1 复跑）

见回传正文。编译命令：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks`；harness 六套 + QA 对抗探针见 `scripts/qa/run-*.sh`。

