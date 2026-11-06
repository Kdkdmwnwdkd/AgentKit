# 04 · 系统执行与运行时（只读评审）

> 基线 `7dd0f88`。只读源码，未跑 gradle、无 Android SDK、未上真机。范围：`automation/`、`automation/adb/`、`automation/shizuku/`、`terminal/`、`core/terminal/`、`core/container/`、`runtime/lease/`、`core/build/`。
> 判定标记：✔ 逐行复核过源码；○ 由结构、注释或文档推断，仍需运行/真机确认。

## 结论摘要

1. **降级链是唯一的，顺序与 v1.0 文档一致**：`providersFor` 在 `preferProot=true` 时给 `proot → shizuku → adb → user`，`false` 时给 `shizuku → adb → user`，顺序表之外的后端追加在末尾（`TerminalBackendFactory.kt:98-137`）。`getHighestAvailable` 只是 `obtain(User, true)` 的薄包装（`:146-147`）。
2. **R-12 的身份维度是真的**：`TerminalIdentity` 带 `uid`（`User=null / Shizuku=2000 / Su=0 / Ssh=null`）与 `satisfies()`（`TerminalIdentity.kt:47,62-79,98-105`）；「请求是下限、后端声明是实际」确实落在**工厂筛选、会话复用、执行校验**三处（`TerminalBackendFactory.kt:136`、`TerminalSessionPool.kt:253,346`），会话固化的是后端实际身份（`:300`）。
3. **没有后端再把 uid 2000 自称 `User`**：现存四个实现分别是 proot=User、本机 shell=User、Shizuku=Shizuku、无线 ADB=Shizuku（`ProotBackend.kt:33,107,135`、`ProcessBackendBase.kt:43`、`ShizukuTerminalBackend.kt:44,76`、`AdbTerminalBackend.kt:57,188`）。但**一次性 Shizuku 通道仍把「uid 2000」写死在回执里**（`ShellTool.kt:276`），不是从实际身份推导；若设备用 Sui（root 版）启动，实际可能是 uid 0，声明会低报（○，需真机 `id`）。
4. **会话池的锁、句柄、哨兵不是 20 并发的第一瓶颈**。先坏的是 `open()` 的**同别名检查-启动-写入不是原子段**（`TerminalSessionPool.kt:245` 查、`:301` 写，中间隔着最长 30s 的 `start()`），两个并发 open 就会双开同一别名，后写覆盖 `byAlias`，且 `releaseEntry` 会把**新会话的别名映射删掉**（`:556-561`）。第二是内存：每会话一条 proot + pty，池没有会话上限，也没有可用的空闲回收（见第 6 条）。
5. **`desynced` 不覆盖「进程被系统杀」**。它只在「命令超时且后端仍自称存活」时置位（`TerminalSessionPool.kt:516-527`）；而 proot 的 `isAlive` 来自 `TerminalSession.isAlive`，后者只判 `started && !destroyed && handle != 0`，**子进程退出不会翻假**（`terminal/TerminalSession.kt:83-84,147-177`）；ADB 后端的 `isAlive` 只看 `stream != null`，读循环退出也不置空（`AdbTerminalBackend.kt:75-76,141-143`）。结果是「死会话仍被当活会话复用 → 每次白等 30s」。更糟的是 `open()` 复用分支**不检查 `desynced`**（`:246-268`），超时后的毒会话会被当作 `reused` 返回，直到用户显式 `shell_close`。
6. **终端租约今天实际上永不回收**。`TerminalPoolLease.isSafeToRelease = active==0 && liveSessions()==0`（`TerminalPoolLease.kt:65-66`），而 `checkOnce` 只对「安全」的租约调用释放器（`LeaseRegistry.kt:192-199`）；释放器里 `if (count == 0) return`（`TerminalPoolLease.kt:85-93`）。「可释放」时已无会话，「有会话」时又不可释放——双闸互斥。若它真被调用，`pool.closeAll()` 还会关掉**所有后端**的会话，不只本池。
7. **运行时能力不是「最接近工业级」那一块**：骨架（接口、工厂、池）是干净的，但「死会话识别、租约回收、命令超时与工具预算一致、并发打开」四处有实质缺陷；容器侧则存在三条独立 proot 执行路径，包管理器互斥只覆盖其中一条。
8. **`SelfAdbBackend` 不存在**（全仓 grep 零命中）。Phase 2 第 1 项只完成了「身份」这一半；自动配对、被回收后重拉、`clearCache` 触发都没做，`AdbClient` 是自研协议最小子集而非壳（360 行，整包 2,083 行），但配对端到端仍未真机验证。
9. **容器启动是六段串联，失败只返回 `null`**：内容探针 → 解压 → 三件套提取 → DNS → argv 组装 → fork/execve（`ContainerSession.kt:47-84`）。`create()` 的契约是 `TerminalSession?`：显式两处 `return null`（`:50,54`），加上 `ensureBinary` / `ensureExtracted` 的 null 传递，任一段失败都退化成同一个 `null`；调用方（`ProotBackend.start`、`ContainerScreen`）只能各自猜，失败原因只进了 `AgentLogger` 而不进返回值。
10. **失败回执不可归因**：池层 `OpenResult` 有 `backendKey`，但 `ExecResult` 只有 `exitCode/errorCode/message/hint`（`TerminalSessionPool.kt:48-57,94-101`），**没有后端、没有失败阶段、没有 errno**；容器 runner 反而把失败详情落盘了（`ContainerCommandRunner.kt:218-241`），但 `Result` 只有 `exitCode + output`（`:49-51`），不告诉调用方日志路径。池层与容器两侧的「可诊断性」方向相反，没有统一口径。

## 关键实证

| 结论 | file:line | 复核 |
|---|---|---|
| 唯一链条 `proot → shizuku → adb → user`；`preferProot=false` 为 `shizuku → adb → user`；未知 key 追加末尾 | `core/terminal/backend/TerminalBackendFactory.kt:98-137` | ✔ |
| `uid` 为 `Int?`；`satisfies` 用显式 when 而非数值等级；`Su` 满足本地三档、`Ssh` 只满足自己 | `core/terminal/backend/TerminalIdentity.kt:47,62-79,98-105` | ✔ |
| 工厂按 `satisfies` 筛候选；池按 `satisfies` 判复用；`run` 按 `satisfies` 判执行；固化取 `candidate.identity` | `TerminalBackendFactory.kt:136`；`TerminalSessionPool.kt:253,300,346` | ✔ |
| 四实现身份：proot/user 为 `User`，shizuku/adb 为 `Shizuku(2000)` | `ProotBackend.kt:33,107,135`；`ProcessBackendBase.kt:43`；`ShizukuTerminalBackend.kt:44,76`；`AdbTerminalBackend.kt:57,188` | ✔ |
| 一次性通道回执硬编码「uid 2000」，不经 `describe()` | `tools/ShellTool.kt:276` | ✔ |
| 分池键 `(backendKey, alias)`；每会话一把 `Mutex`；句柄恒 4-hex；哨兵 16-hex token + 后续数字 | `TerminalSessionPool.kt:30,613,668`；`:463-466,581-584` | ✔ |
| `open()` 先查 `byAlias`、再 `start()`、最后写入；两段之间无池级互斥 | `TerminalSessionPool.kt:245,280-304` | ✔ |
| `releaseEntry` 无条件按 `(backendKey, alias)` 删除，可能删掉同键的新会话 | `TerminalSessionPool.kt:556-561` | ✔ |
| `desynced` 仅在超时且 `alive==true` 时置位；超时先发 0x03、300ms 后仍活才标脏 | `TerminalSessionPool.kt:516-527` | ✔ |
| `open()` 复用分支只看 `alive` 与 `satisfies`，不看 `desynced` | `TerminalSessionPool.kt:246-268` | ✔ |
| `TerminalSession.isAlive` 不随子进程退出翻转；`readLoop` 结束后无状态位 | `terminal/TerminalSession.kt:83-84,147-177` | ✔ |
| proot `isAlive` 代理 `TerminalSession.isAlive`；ADB `isAlive` 只看 `stream != null` | `ProotBackend.kt:44-45`；`AdbTerminalBackend.kt:75-76` | ✔ |
| 池单命令超时 30s；`ShellRunTool` 预算却是 20 分钟，二者无接线 | `TerminalSessionPool.kt:650`；`ShellTool.kt:151` | ✔ |
| `close(handle)` 不取会话 Mutex，可在命令执行中直接关后端 | `TerminalSessionPool.kt:384-390` | ✔ |
| 终端租约双闸互斥；释放器调 `pool.closeAll()`（跨所有后端） | `TerminalPoolLease.kt:65-66,85-93`；`TerminalSessionPool.kt:393-405` | ✔ |
| 容器租约只看 `active==0`，与终端租约语义不同；LLM 租约要求「已加载」 | `ContainerLease.kt:55,81-89`；`LocalLlmLease.kt:54-55` | ✔ |
| 租约注册表是进程级 object；终端池与租约集合是 `ChatViewModel` 实例级 | `LeaseRegistry.kt:45`；`ChatViewModel.kt:747,755` | ✔ |
| 容器命令的进程级公平锁只在 `ContainerCommandRunner` 内；交互式 proot/池不取这把锁 | `ContainerCommandRunner.kt:99,132-170` | ✔ |
| `ensureBinary` / `ensureExtracted` 无锁，可并发写同一 proot 三件套/同一 rootfs | `ProotRunner.kt:205-279`；`RootfsManager.kt:158-185` | ✔ |
| proot 三选项在位：`--link2symlink`、`--root-id`、`--kill-on-exit` | `ProotRunner.kt:311-321` | ✔ |
| 发行版→运行时版本表 + 显式版本路径；未知发行版线放行（`shipped==null → ok`） | `ToolchainInstaller.kt:620-635,187-208` | ✔ |
| 装完自检只在 UI 安装控制器；Agent 工具安装路径没有自检 | `ContainerInstallController.kt:177-189`；`ContainerToolchainTool.kt:270-284` | ✔ |
| `core/build/` 是 GitHub Actions 云端构建，不做容器 node/python/java 映射 | `core/build/BuildEngine.kt:45-159`；`BuildModels.kt:12-16` | ✔ |
| `AdbClient` 360 行、整包 2,083 行；CNXN/AUTH、shell v2、交互式 shell、两阶段读超时均为自写 | `automation/adb/AdbClient.kt:71-114,122-168,189-272,311-350` | ✔ |
| `AdbPairingClient` 要求 `SDK>=31`（Android 12），而 `AdbSession.MIN_SDK=30` | `AdbPairingClient.kt:46-48`；`AdbSession.kt:43` | ✔ |
| `clearCache()` 全仓零调用点（注释承诺「权限变化后调用」） | `TerminalBackendFactory.kt:162-168`；grep 无调用 | ✔ |
| 容器执行失败落盘、超时带最后一行、日志含 distro/exit/耗时 | `ContainerCommandRunner.kt:152-164,218-241,335-350` | ✔ |
| `ExecResult` 没有 `backendKey` 字段；所有池层错误统一 `exitCode=-1` | `TerminalSessionPool.kt:94-102,109-110` | ✔ |

### 五条通道的实际身份与能力边界（问题 3）

| 通道 | 实际 uid | 能做 | 不能做 | 证据 | 判定 |
|---|---|---|---|---|---|
| 无障碍服务 | App uid（同进程，`u0_aXX`） | 读活动窗口 UI 树、`dispatchGesture` 点击/长按/滑动、`ACTION_SET_TEXT`、`GLOBAL_ACTION_HOME/BACK/RECENTS`、API 30+ 截图 | 起 shell、读写其它应用私有目录、执行任意命令 | `AgentAccessibilityService.kt:593-615,633,660-662`；`AndroidManifest.xml:110-111` | ✔ 代码 / ○ 沙箱结论 |
| Shizuku | 2000（shell） | 持久 `sh`、`pm`/`am`/`input`/`uiautomator`、读多数 `/data/local/tmp` | 写 `/system`、真 root、进程受 Shizuku manager 生命周期约束 | `ShizukuShell.kt:97-100,221,297`；`ShellChannel.kt:9,57-62`；`TerminalIdentity.kt:70` | ✔ 声明 / ○ 运行时 uid |
| 无线 ADB | 2000（adbd shell） | 同 shell；`shell,v2` 分离 stdout/stderr/exit | 用户须手动开无线调试并配对；端口变化/密钥失效即断；Android 11 无法配对 | `AdbTerminalBackend.kt:57`；`AdbSession.kt:43`；`AdbPairingClient.kt:46-48` | ✔ 代码 / ○ adbd uid |
| proot | App uid（`--root-id` 只是 guest 内假 0） | 完整 Linux 用户态、apk/apt、可写 rootfs | 真提权、访问宿主其它应用私有目录、SELinux 下的 ptrace 行为待真机 | `ProotRunner.kt:311-321`；`ProotBackend.kt:33` | ✔ 参数 / ○ SELinux |
| 本机 `/system/bin/sh` | App uid | toybox、读写自身 `filesDir`、无依赖 | 包管理器、跨应用访问、任何特权操作 | `LocalUserBackend.kt:44`；`ProcessBackendBase.kt:43` | ✔ |

一句话概括：**今天真正「高于 App」的只有 uid 2000 这一档，且有两个独立入口**（Shizuku、无线 ADB）；proot 与本地 shell 都是 App 权限，`Root`/`Ssh` 在 `satisfies()` 里被显式排除（`TerminalIdentity.kt:62-79`）。所以「无 root」这个前提在实现上是自洽的——不存在偷偷提权的第五条路。

### 并发打开时的降解次序（问题 2）

| 次序 | 机制 | 何时先坏 | 证据 |
|---|---|---|---|
| 1 | 4-hex 句柄空间 65,536 | 20 会话远未触及；真正的风险是**关闭后句柄复用**（无代次） | `TerminalSessionPool.kt:30,568-578` |
| 2 | **同别名 open 的检查-启动-写入非原子** | **2 个并发 open 即触发**（默认别名相同） | `TerminalSessionPool.kt:245,280-304` |
| 3 | 每会话 `Mutex`（同会话串行） | 设计正确；但 `close(handle)` 不取这把锁，可在命令中途抽走后端 | `TerminalSessionPool.kt:613,384-390` |
| 4 | 内存 / proot 进程数 | 每会话一条 proot + 一条 pty；池无上限、无可用的空闲回收 | `TerminalSessionPool.kt:176-190`；`TerminalPoolLease.kt:65-66` |
| 5 | 单命令 30s 超时 | 与工具 20min 预算冲突，长命令必失败并污染会话 | `TerminalSessionPool.kt:650`；`ShellTool.kt:151` |

结论：**20 并发不是有意义的压力点**。句柄、互斥、内存都撑得住；先坏的是并发语义（第 2 项），再坏的是「长命令根本跑不完」（第 5 项）。把并发数从 2 提到 20 不会让问题更容易被发现，只会让第 2 项的后果从「一个别名指向错会话」扩大成「N 个半孤儿」。

### 容器启动路径（问题 5）

串联六段，每段失败都退化成 `null`：

1. `RootfsManager.isExtracted`：内容探针（`bin`/`etc` 目录、`bin/sh` lstat、`bin/busybox` 非空）+ `.agentkit-layout-v2` 标记（`RootfsManager.kt:79-101`）。
2. `RootfsManager.ensureExtracted`：自写 tar 解析（`L` 长名、`x/g` 跳过、`2` 符号链接相对化、Zip-Slip 消毒），**无锁**，重解压先删目录（`:158-185`）。
3. `ProotRunner.ensureBinary`：三件套 `proot + libtalloc.so.2 + libandroid-shmem.so`，优先 `nativeLibraryDir` 的 `libproot.so`，ELF magic 校验、`chmod 0700`，**无锁**（`ProotRunner.kt:205-279`）。
4. `ContainerNetwork.ensureResolvConf`：从宿主 `LinkProperties` 抄 DNS 进 rootfs（`ContainerNetwork.kt:42-70`）。
5. `ProotRunner.buildInvocation`：`-r/-b/-w/--link2symlink/--root-id/--kill-on-exit` + guest env（`ProotRunner.kt:311-321,402-450`）。
6. `ContainerSession.create` → `TerminalSession(argv, env)` → JNI `createSubprocessArgv` fork/execve（`ContainerSession.kt:47-84`；`terminal/TerminalSession.kt:91-130`）。

`core/container` 的 2,331 行分布也说明重心：`ToolchainInstaller` 636（发行版探测 + 版本表 + 自检脚本）、`ProotRunner` 604（二进制/argv/env）、`ContainerCommandRunner` 505（一次性命令 + 并发闸）、`RootfsManager` 386（解压 + 探针）、`ContainerSession` 127（会话组装）、`ContainerNetwork` 73（DNS）。**没有一行是「proot 本身」**——proot 是预编译产物，这个包只负责把它拼对。

并发修复后仍剩的竞态只有两处，都在 `create()` 的上游：`ensureBinary` 与 `ensureExtracted` 无锁（`ProotRunner.kt:205-279`；`RootfsManager.kt:158-185`），以及 `rootfs` 正在被 UI 重解压时池里已有会话仍持有旧 inode 的路径（○，需真机/压力验证）。前者是 A 级隐患的低概率形态，后者是纯推断，本文不作为结论。

## 结构性问题

### A 级（会导致错误行为）

**A1 · 死会话被当作活会话（proot / ADB）**
`TerminalSession.isAlive` 不跟踪子进程退出（`terminal/TerminalSession.kt:83-84`），`AdbTerminalBackend.isAlive` 不跟踪读循环结束（`:75-76,141-143`）。后果：shell 已死，`entry.alive` 仍为真 → 下一次 `run` 先 `send`，失败后仍不释放（因为 `alive` 真）→ 要么立即 `send_failed` 卡死，要么 30s 超时并标 `desynced`。这不是「晚一个命令」的问题，而是**会话永远不会自己变成 `session_dead`**。

**A2 · 30 秒池超时与 20 分钟工具预算冲突**
`TerminalSessionPool.DEFAULT_COMMAND_TIMEOUT_MS=30_000`（`:650`），`ShellRunTool.timeoutBudgetMs=20*60_000`（`ShellTool.kt:151`）且工具描述明确说编译类命令「要好几分钟」。实际任何超过 30s 的 `shell_run` 都在池层被判超时并标脏，工具层的长预算从未生效。`npm install`、`gradle build`、`apk add` 走 `shell_run` 都会稳定失败，且会话被污染。

**A3 · 同别名并发 open 的 TOCTOU**
`open()` 在锁内只做查表，随后在锁外启动后端（最长 30s），再在锁内写入（`TerminalSessionPool.kt:245,280-304`）。两个并发 `shell_run`（默认别名都是 `proot`/`default`）会各自起一个 proot，后写覆盖 `byAlias`；先写者的句柄仍在 `byHandle`，但别名已不可达。更严重的是随后 `releaseEntry` 会按同一 poolKey 删除**新会话的别名**（`:556-561`），使新会话也变成「只能按句柄访问、不能再复用」的半孤儿。当前工具面只有 `alias=null` 与 `app_control` 两种别名（`ShellTool.kt:315-319,467`），所以现实触发概率取决于并发工具/后台任务；不是 20 个会话才出现，2 个就够。

**A4 · 容器包管理器互斥只覆盖 runner 路径**
`ContainerCommandRunner` 的 `execLock` 只保护自己（`:99,132-170`）；交互式容器（`ContainerSession.create` → `TerminalSession`）和池里的 `ProotBackend` 完全不取这把锁。用户在容器终端里跑 `apk add`，Agent 同时通过工具安装运行时，仍是两个 apk 抢 `/lib/apk/db/lock`，回到已经修过的 `exit 99`。UI 的 `busyFor` 只挡「进终端」且只看 runner（`ContainerScreen.kt:197-210`），挡不住 `shell_run`。

### B 级（阻碍演进 / 功能缺口）

**B1 · 租约与池的生命周期职责重叠且语义相反**
租约注册表进程级、池与 `TerminalLeases` 是 `ChatViewModel` 实例级（`ChatViewModel.kt:747,755`）；每次 VM 重建都会以同一 id 重新 `register`（`LeaseRegistry.kt:95-101` 只告警并替换），旧池的异步关闭与新租约并存。租约本应回答「谁在用」，池本应回答「怎么关」，现在两者都能关；`TerminalPoolLease.releaseIdle` 又用 `closeAll()` 扩大到所有后端。再加上 `terminalShellCommandRunner`（`ShellTool.kt:461-483`）不借租约，`activeCount` 并不代表真实使用面。

**B2 · `clearCache()` 从未接线**
工厂缓存的是 `probe()` 结果（`TerminalBackendFactory.kt:157-168`）。用户在 App 运行期间授权 Shizuku、完成无线调试配对、或解压完 rootfs 后，没有调用点清缓存；下一次 `obtain` 仍按第一次的 false 走低档，直到进程/VM 重建。注释说「权限变化后调用即可」，但全仓零调用。

**B3 · 容器有三条执行路径、两套安装语义**
- 交互终端：`ContainerSession.create` + `TerminalSession`（pty，`ContainerSession.kt:47-84`）；
- 会话池：`ProotBackend` → 同一个 `ContainerSession.create`（`ProotBackend.kt:47-71`）；
- 一次性命令：`ContainerCommandRunner` 独立 `ProcessBuilder`（`:315-358`）。

`ContainerSession.runCommand` 只能证明「命令已下发」，拿不到退出码（`ContainerSession.kt:109-126`）；池用哨兵能拿退出码；runner 用 `waitFor` 能拿退出码。三条路径的并发、超时、错误语义各自演化。安装同样有 UI 控制器（带自检）与 Agent 工具（不带自检）两套。

**B4 · 版本一致性只覆盖「已知发行版线 + 显式版本」**
`ToolchainInstaller` 用发行版线表把 node/python 的「官方源实际版本」写死（`:620-635`），已知线不匹配会拒绝（`:187-208`）；但未知线 `shipped==null` 被当作 `ok=true`，会放行并装到发行版默认版本。更关键的是 `ContainerToolchainTool` 在解析版本之前先看「该语言是否已安装」，已装则直接 `already_installed`（`:203-211`），用户要 node 18、容器里是 node 22 时根本不会进入版本校验；`verifyScript` 只验命令可执行，不比对版本（`ToolchainInstaller.kt:542-548`）。「版本一致」目前只是「包名/候选表一致」，不是「装完的版本一致」。

**B5 · `core/build/` 的命名与职责错位**
`core/build` 是「把工作区推到 GitHub、dispatch workflow、轮询 run、下载 APK」的云端构建（`BuildEngine.kt:45-159`、`GitHubBuildService.kt:36-45`），不包含 node/python/java 的工具链映射。真正的映射在 `core/container/ToolchainInstaller`。把 `core/build` 当作「容器内工具链」会得到错误结论。

**B6 · 身份仍有一处硬编码 + 一处语义低报**
一次性 Shizuku 分支把回执写成「通道 Shizuku（uid 2000）」（`ShellTool.kt:276`），而实际身份可能来自 Sui（root 启动，uid 0）或未来其它通道；`TerminalIdentity.Shizuku` 这个名字本身是通道名复用为权限档（`TerminalIdentity.kt:67-76`），ADB 通道也报 `Shizuku`。不是 R-12 那种「假 User」，但「声明=实际」在单次通道上尚未闭环。

**B7 · 失败回执不可归因（问题 6 的直接答案：不能）**
把四条失败路径摊开看：

| 路径 | 错误里有什么 | 缺什么 | 证据 |
|---|---|---|---|
| 池层 `ExecResult.failure` | `errorCode` / `message` / `hint`，`exitCode=-1` | **哪个后端、哪一步（open/start/send/等哨兵）、errno** | `TerminalSessionPool.kt:94-110` |
| 池层 `OpenResult.failure` | 同上；成功时有 `backendKey` | 失败时 `backendKey` 也丢了（`failure()` 不填） | `:48-57,77-78` |
| 一次性通道 | `ShellExecResult(channel, exitCode, startError)` | 有 `channel`；无阶段，`startError` 是自由文本 | `ShellChannel.kt:29-39` |
| 容器 runner | `Result(exitCode, output)`；完整日志落 `logs/container-fail-*.log` | **日志路径不在返回值里**；无 distro、无耗时 | `ContainerCommandRunner.kt:49-51,218-241` |

实际后果：`ShellTool` 的成功头部能靠 `pool.lookup(handle).identity.describe()` 说出「proot / uid 与 App 相同」（`ShellTool.kt:351-372`），失败路径却只剩一句通用 `hint`；容器侧则相反——细节全在磁盘上，但用户/Agent 不知道文件名。**同一个「哪一步坏了」的问题，四条路径给了四种不相交的答案**，日志成了唯一交集，而日志不可编程读取。

### C 级（可读性 / 一致性）

**C1 · 文档与代码的过期点集中**：`ProotRunner` KDoc 仍写「targetSdk 保持 34 不变」「targetSdk 34 下 W^X」（`ProotRunner.kt:87-89,199-200`），实际 `targetSdk=28`（`app/build.gradle.kts:43`）；`TerminalIdentity` 注释仍说「七处声明全是 User」（`TerminalIdentity.kt:11-12`），现在只有四个实现。
**C2 · 4-hex 句柄没有代次**：`allocateHandle` 只查当前表（`TerminalSessionPool.kt:568-578`），关闭后的句柄可被新会话复用；`ShellTool` 只要 `lookup` 到就接受（`ShellTool.kt:303`），历史对话里的旧句柄可能静默命中另一个会话。65536 空间对 20 会话足够，但「旧句柄指向新会话」是设计缺口。
**C3 · 错误码仍偏字符串**：`open`/`exec` 的 code 是散字符串（`TerminalSessionPool.kt:48-58,94-102`），没有枚举；不同调用点靠字符串匹配拼文案，和 v1.0 §5 的判断一致。
**C4 · 后端键存在字面量重复**：`AdbShellChannel.backendId` 写死 `"adb-wireless"`（`ShellChannel.kt:77`），而同一值有常量 `TerminalBackend.KEY_ADB`（`TerminalBackend.kt:82`）；注释也承认「应与 `CapabilityBridges.BACKEND_ADB` 一致」（`ShellChannel.kt:76`）却没用常量。改一处漏一处的风险落在「能力链候选 vs shell 通道」的日志对齐上。

## 与 v1.0 文档的差异

| 文档说法 | 源码事实 | 证据 |
|---|---|---|
| §4.3「三层资源由 runtime/lease 统一节拍回收，`activeCount>0` 与 `isSafeToRelease` 双闸」 | 终端池的 `isSafeToRelease` 要求「无存活会话」，导致有资源时永不回收；回收器只处理 safe 的租约 | `docs/architecture/AgentKit-架构方案-2026-11-05.md:197-200`；`TerminalPoolLease.kt:65-66,85-93`；`LeaseRegistry.kt:192-199` |
| §4.3「只告警不夺资源」 | 对**泄漏检测**成立（`detectLeaks` 只记日志）；对**空闲回收**不成立，回收器会主动关后端。两句被写在同一段，容易被读成「永不回收」 | `LeaseRegistry.kt:237-252`；`TerminalPoolLease.kt:85-93` |
| §4.3「`identity=shizuku` 也能进池开持久会话」 | 正常路径下 `identity=shizuku` 被路由到**一次性**通道（`shouldUsePrivilegedShell`），只有该通道返回 null 才回落池；持久 uid 2000 应使用 `prefer_proot=false` | `ShellRoute.kt:20-44`；`ShellTool.kt:210-218`；文档 `:207` |
| §7.2「六级后端回落 `proot→shizuku→adb→user`」 | 链上是 4 个后端；su/ssh `usable=false`，工厂无候选。「六级」无来源 | `docs/...:303`；`TerminalIdentity.kt:65,79`；`TerminalBackendFactory.kt:98-137` |
| §8 R-12「七个后端实现全自称 User」已修 | 现存 4 个实现已无假 User；但单次通道仍硬编码 uid 2000 | `docs/...:343`；`ShellTool.kt:276` |
| 路线图 Phase 2 第 1 项「新增 `SelfAdbBackend`，上层零改动」 | 没有 `SelfAdbBackend`；只有 `AdbBackendProvider` / `AdbTerminalBackend`。配对需用户在开发者选项开无线调试并输 6 位码，端口变化需重配 | `docs/architecture/AgentKit-现状与路线图-2026-10-16.md:127`；grep `SelfAdb` 零命中；`ui/AdbChannelSection.kt:164-200,307-315` |
| `w13-adb-channel-plan.md`「Android 11+」 | 代码 `MIN_SDK=30`，但配对 `require(SDK>=31)`，Android 11 实际只能走到「需要 Android 12」的失败文案 | `docs/archive/fixes/w13-adb-channel-plan.md:63,69`；`AdbSession.kt:43`；`AdbPairingClient.kt:46-48` |
| 容器并发修复文档「所有容器命令串行」「安装失败补自检」 | `execLock` 只覆盖 `ContainerCommandRunner`；自检只在 `ContainerInstallController`，Agent 工具路径没有 | `docs/archive/fixes/容器安装并发修复-2026-10-25.md:44-48,134-145`；`ContainerCommandRunner.kt:99`；`ContainerToolchainTool.kt:270-284` |
| 容器修复文档「还没验证」仍写「需先确认 proot 支持 `--kill-on-exit`」 | 同一文档第 11 项又说已加；源码已显式加三个长选项 | `docs/archive/fixes/容器安装并发修复-2026-10-25.md:243-255`；`ProotRunner.kt:311-321` |
| 「运行层第一块」文档的 `CapabilityHost` / `IdleUnloadPolicy` | 已被 `runtime/lease` 取代；`IdleUnloadPolicy` 不存在，`CapabilityHost` 已删除 | `docs/archive/fixes/运行层第一块-接线说明.md:22-24`；`runtime/AgentCapability.kt:9-19` |

## 建议

| # | 落点 | 验收判据 | 量级 |
|---|---|---|---|
| 1 | `TerminalSessionPool.open`：按 poolKey 加「占位/启动中」状态或按 poolKey 的互斥段，把查-启-写收成临界区；`releaseEntry` 只在 `byAlias[poolKey] === entry` 时删除 | 两个协程同时 `open(alias="x")`：只起一个后端、返回同一 handle、`list()` 只有一条；关闭旧 handle 不影响新会话 | S，约 60–100 行 + 1 个并发 harness |
| 2 | `TerminalSessionPool.open` 复用分支增加 `if (existing.desynced) { release+close; continue }`；或在 `run` 超时后主动 `releaseEntry` | 超时后不带 session 再调用，必须新建会话而不是返回 `reused=true`；`session_desynced` 不再能通过默认别名反复命中 | XS，约 10 行 + 1 条断言 |
| 3 | `TerminalSession` 增加 `exited` 状态（`readLoop` 结束时置位）；`ProotBackend`/`AdbTerminalBackend` 在退出时把句柄/stream 置空 | 杀掉 proot/关闭 adbd 后，下一次 `run` 在 ≤2s 内返回 `session_dead`，不再等 30s；`list()` 的 `alive` 为 false | S，约 20–30 行 + 假后端关流用例 |
| 4 | 池超时改为「按调用传入」：`run(handle, command, timeoutMs)`，`ShellRunTool` 把 20min 预算传下去；无 PTY 通道超时直接 `close()` 后端而不是发 0x03 | `sleep 60` 经 `shell_run` 成功；`sleep 60` 超时后会话被释放，下一次不返回 `desynced`；有 PTY 的 proot 仍能 Ctrl-C | S，约 30–50 行 |
| 5 | 终端租约改为「`isSafeToRelease = active==0 && 该后端无在途命令`」，释放动作改成 `pool.closeBackend(backendKey)`；租约按池注册一次，VM 销毁时 `unregister`（新增）或复用单例 | 30min 空闲后只关 `terminal:proot` 的会话，`terminal:user` 仍在；VM 重建不产生重复 id 告警；`snapshot()` 的 `active/outstanding` 与实际命令数一致 | M，约 80–120 行 |
| 6 | `clearCache()` 接线：Shizuku 授权成功、ADB 配对成功、`RootfsManager.ensureExtracted` 成功后各调一次；或直接删除缓存 | 冷启动时 proot 不可用 → 解压后不重启即可 `obtain(proot)` 成功；授权 Shizuku 后下一次 `shell_run` 直接进 shizuku | XS，约 10 行 + 1 条集成断言 |
| 7 | 容器并发：把 runner 的 `execLock` 提升为「包管理器闸门」，交互式 proot/池在同一发行版有安装时拒绝或排队；`ensureBinary`/`ensureExtracted` 加进程级锁 | `shell_run("apk add ...")` 与 `ContainerToolchainTool` 同时发起：串行完成、零 `exit 99`；并发解压同一 rootfs 不出现删目录/半树 | M，约 100–150 行 |
| 8 | 安装语义收口：`ContainerToolchainTool` 复用 `ContainerInstallController.installAndVerify`（或至少失败后跑 `verifyScript`）；`already_installed` 前先比对请求版本 | 装 JDK 出现 `1 error` 但 `javac` 可用时，工具返回「已就位（自检通过）」而不是 `install_failed`；已装 node 22 时请求 node 18 返回 `version_not_matched` 而不是 `already_installed` | S，约 40–60 行 |
| 9 | 可观测：`ExecResult` / `OpenResult` 增加 `backendKey` 与 `stage`（open/start/send/sentinel），`ShellTool` 失败回执带上后端与阶段；保留 `errno` 字段（可为 null） | 一次失败回执能读出「proot / start / rootfs 未解压」或「adb / connect / ECONNREFUSED」，不要求用户翻日志 | S，约 50 行 + 文案断言 |
| 10 | Phase 2 表述收口：在 `SelfAdbBackend` 落地前，路线图不要写「上层零改动已完成」；把「自授权」拆成「一次手动配对 + 后续自动重连/重配对」两项 | 文档中的 Phase 2 第 1 项有明确状态（身份完成 / 后端未开始）；配对失败/端口变化的用户动作只有一条 | XS，文档 + 1 条状态断言 |

## 证据边界

- **✔ 已复核**：上表标 ✔ 的条目均直接读源码与注释；行号以 `7dd0f88` 为准。仓库规模、文件行数与范围（automation 12/2,518、adb 12/2,083、shizuku 6/773、terminal 3/1,381、core/terminal 9/1,718、container 6/2,331、lease 5/808、build 7/1,183）与题面一致。
- **○ 推断，需运行验证**：20 个并发会话的内存/LMK 拐点、`open` 竞态在真实工具并发下的触发频率、4-hex 句柄复用概率、Sui 启动时 `newProcess` 的实际 uid、proot 在 `targetSdk=28` 真机上的 ptrace/SELinux 行为。
- **真机才能确认、本文不作为结论**：Shizuku 持久 shell 能否稳定起（`docs/archive/notes/W56-NOTES.md:76-89` 明确未验）；无线调试端到端配对（`docs/archive/notes/W51-NOTES.md:106-107` 明确未验；`AdbCrypto.encodePublicKey` 的 `n0inv/rr` 写零，`AdbCrypto.kt:58-71`，设备是否接受只能实测）；ADB 交互式 shell（`docs/archive/notes/W53-NOTES.md:103`）；`identity` 不传默认进池后的 `prefer_proot` 行为（`docs/archive/notes/W57-NOTES.md:117-124`）；容器 `--root-id` 的 trigger 副作用与 Java 安装耗时（`docs/archive/fixes/容器安装并发修复-2026-10-25.md:243-255`）。
- **`DEVICE-VERIFICATION-PENDING.md` 的未闭环项（逐条）**：
  - `P1` 容器租约成功路径确实借出（需 arm64，`docs/ops/DEVICE-VERIFICATION-PENDING.md:18`）
  - `P2` 退出归还 + 空闲回收（`:33`）
  - `P3` 切换发行版不串会话（`:51`）
  - `P4` 失败路径不借租约（任意设备，`:67`）
  - `P5` 诊断包含回合事件（`:86`）
  - `P6` 输出被截断时界面要有话说（`:105`）
  - `O1–O14` 观察项（`:127-146`），以及 `:342-346` 汇总的挂账「arm64 真机 O1–O9/O11、容器租约、无障碍、真实分享面板、首帧耗时」。
  - **关键缺口**：该文档没有列入终端会话池、Shizuku 持久后端、无线调试配对。这三条的真机状态只散落在 `W51-NOTES.md:106-107`、`W53-NOTES.md:103`、`W56-NOTES.md:76-89`、`W57-NOTES.md:117-135` 的「没验的」小节。也就是说：本文 A1/A2/A3 所在的模块，恰好是**清单覆盖为零**的模块——没有一个 P 编号、没有一条通过标准。这是评审能给出的最明确的一条「流程缺口」。
- **未做**：未编译、未跑 harness、未启动模拟器/真机；因此「断言通过」不能当作行为证明。`run-r12-identity-verification.sh` 确实用真池 + 假后端跑了身份与复用/冲突，但没有覆盖同别名并发 open、`desynced` 复用、进程被杀后的 `isAlive`，这些正是本文 A1/A3 的证据缺口。
- **文档时效**：v1.0 架构文档的 §4.3、§7.2、§8 与 `运行层第一块` / `容器安装并发修复` 均含已过期表述；本文以源码为准，逐条列在「与 v1.0 文档的差异」。
