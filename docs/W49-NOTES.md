# W-49 批次记录：容器终止语义 / 上下文锚点 / 重试去重

> 批次时间：2026-10-26
> 起点：`e387b5e`（v1.143，2026-10-25 09:56）
> 触发：一次针对 `core/agent` + `core/container` + `ui/ChatViewModel` 的代码审查，5 条「设计对、落地差一点」的问题

---

## 一、这一批修了什么（现象 → 根因 → 修法）

### 1. 容器超时后 SIGKILL，`--kill-on-exit` 形同虚设（P0）

| 项 | 内容 |
|---|---|
| 现象 | 装 JDK 超时被终止后，下一次安装动辄 `exit 99`：`ERROR: Unable to lock database`。用户看到的是「重装也没用」，实际是上一次的 `apk` 还活着 |
| 根因 | 超时路径是 `process.destroyForcibly()`，即 **SIGKILL**。而 `--kill-on-exit` 在 proot 里是 `atexit` 注册的清理钩子（二进制符号可见 `kill_all_tracees` / `__atexit_handler_wrapper`），**只在进程自己走完 `exit()` 时执行**：SIGKILL 不可捕获，钩子一次都不会跑；proot 被内核摘掉后，ptrace 下的 tracee 被 detach 并继续运行，继续占着 `/lib/apk/db/lock`。原 KDoc 把「加了 `--kill-on-exit` 就够了」当成结论，方向对但结论反了 |
| 修法 | 新增 `terminateTree()`：① `destroy()`（SIGTERM）+ 宽限 3s；② 仍活着 → 递归读 `/proc/<pid>/task/<pid>/children` 记下整棵子树；③ `destroyForcibly()` 杀 proot；④ 逐个 `Os.kill(pid, SIGKILL)`。pid 用反射取 libcore `ProcessImpl.pid`（API 26~32 无公开 `Process.pid()`），取不到则跳过子树清理，只记日志——超时路径不允许再抛异常 |
| 取舍 | **顺序不可换**：proot 一死子树立刻 reparent 到 init，`/proc` 里就查不到了，所以必须先抓后杀。全程 best-effort，SELinux 挡 `/proc` 时少杀由 `runProcessWithLockRetry` 的抢锁重试兜底——那是兜底，不是正解 |
| 证据 | `ContainerCommandRunner.kt` `terminateTree()` / `pidOf()` / `collectDescendants()`；`strings` 与 `nm` 对 `assets/bin/arm64-v8a/proot` 的符号输出 |

### 2. tar 解压无路径穿越防护（P2，为将来导入第三方 rootfs 兜底）

| 项 | 内容 |
|---|---|
| 现象 | 无现网症状——rootfs 只来自 APK 自带 assets。但目录管理已支持删除/重建，一旦开放「导入第三方 rootfs」即为应用私有目录越界写 |
| 根因 | `sanitize(name) = name.removePrefix("./").trimStart('/')`，只去前导，**不处理 `..`**；典型归档条目 `../../databases/x` 会落到 `filesDir` 上层 |
| 修法 | `sanitize()` 含 `..` 时按段过滤，归一化后为空则返回空串，三个分支（目录 / symlink / 常规文件）统一 `skipPad + continue` 跳过该条目——失败关闭，不放行 |
| 附带 | `readString()`（GNU 长文件名）原按 header 里的 `size` 直接 `ByteArray(n)`，恶意/损坏 header 声明 2GB 即 OOM。改为 64KB 上限，**并把超出部分自行消费掉**：只截断不消费会让后续 header 错位，整个归档解出一堆垃圾 |

### 3. 图片裁剪锚点指向了错的那条消息（P1）

| 项 | 内容 |
|---|---|
| 现象 | 多轮会话里，用户贴 3 张图 + agent 截 2 次图，**本轮输入的图会被整条剥掉**，模型接着答「你还没给我图」 |
| 根因 | `pruneImages` 用 `indexOfFirst { it is UserMessage }` 当锚点，那是单回合形态的写法。loop 实际传的是 `[System] + priorMessages + [本轮输入]`，首条 UserMessage 是**历史最老那条**，而且 `buildPriorMessages` 只拼纯文本、不带图——那个「保护」在正常路径上是空操作，测试能过但没保护任何东西 |
| 修法 | `pruneImages(messages, keepLast, anchorIndex)` 新增显式锚点；`RealAgentLoop.runTurn` 记录 `turnInputIndex = messages.lastIndex` 并透传到 `buildRequestMessages`。不传时退回旧语义，既有 harness 与单回合调用点行为不变 |
| 取舍 | 锚点不计入 `keepLast` 的候选集：语义是「保留最近 N 张**工具截图**」，本轮输入不是截图。副作用是候选集变小、裁剪更保守——正是想要的 |
| 证据 | 新增回归用例 B 组，其中一条断言「**旧实现会剥掉本轮输入**」，先复现后修复 |

### 4. 流式重试导致前半段重复上屏（P2）

| 项 | 内容 |
|---|---|
| 现象 | 429 重试后，用户看到已经读过的前半段又从头发了一遍 |
| 根因 | 重试粒度是「整次 `streamChat` 收集」，重来时已通过 `onTextDelta` 推给 UI 的增量会再推一次；`RealAgentLoop` 注释判定为「UI 侧可接受」，但 429 退避现在是 10s/30s/60s，用户早就读完了 |
| 修法 | `ChatViewModel.handleAgentEvent` 的 `RetryStarted` 分支清零 `draftBuffer` / `reasoningBuffer` 并清显示态。不动 loop——`RetryStarted` 事件本来就是为「让恢复过程可见」加的，复用即可 |
| 锁序 | `msgLock → streamDraftLock`；反向不存在（`handleTextDelta` 不取 `msgLock`），无死锁风险 |

### 5. `maxSteps` 默认值文档漂移（P3）

| 项 | 内容 |
|---|---|
| 现象 | 两处注释都写「默认 40，改一处务必同步另一处」，两处代码实际都是 60 |
| 根因 | hotfix5 上调到 40、后续再上调到 60 时只改了值，没回改注释 |
| 修法 | `RealAgentLoop` KDoc 与 `core/AgentLoop.kt` 适配层注释统一为 60，并注明「以本行数值为准」 |

---

## 二、验证

| 项 | 结果 |
|---|---|
| 类型检查 · `core/agent` | 全量编译通过（RealAgentLoop 940 行 / AgentLoop 适配层 / ContextBudget + 14 个 `llm` 文件），290 个 class，0 error |
| 类型检查 · `core/container` | 6 文件 + `ToolchainInstaller` 全量编译通过，43 个 class，0 error |
| 既有回归 · `ContextBudgetVerification` | **26 / 26 通过**（含「首条用户消息的图不裁」这条旧断言——新旧语义兼容） |
| 新增回归 · 锚点多轮场景 | **13 / 13 通过**（旧 bug 复现 + 修复 + 非法锚点不崩 三组） |

> 沙箱无 Android SDK / Gradle 缓存，`scripts/qa/*.sh` 会以 `KS_HARNESS_SKIP` 退出。上述编译与回归用 `kotlin-compiler-embeddable 1.9.24` + Maven Central 依赖 + 手写 `android.*` 存根完成；`ChatViewModel` 那 8 行需 Compose 全量依赖，未编译，仅按结构与锁序复核。

## 三、未做 / 需真机确认

1. **`terminateTree` 的 `/proc` 行为只能真机验**：模拟器没有 proot 场景。建议用「装 JDK 网络限速到超时」复现一次，看日志是否出现「已由 SIGTERM 正常收尾」或「已强杀 proot 及子树 N/M 个进程」，再确认下一次安装不再 exit 99。
2. **`send` / `regenerate` 的双份生命周期未合**：两者各维护一套 `runOwnerConvId → _generatingConvId → draft 清空 → syncDraftDisplay → isRunning → _retryState` 收尾仪式，注释里已出现 6 次「与 send 同策略」。这轮不动，留给下一批的 `TurnRunner`——它不紧急，但每拖一轮分叉就多一处（`reloadActiveMessages` 那处已经开始不一样）。
3. **容器 `execLock` 仍为进程内锁**：若安装入口还从其他进程发起，仍会抢 apk 锁。需先确认 `AgentRunService` 是否独立进程，再决定是否下沉为文件锁。
4. **`scripts/qa` 无容器进程生命周期用例**：`BatchBVerification` 只覆盖 `ToolchainInstaller` 的纯函数与「容器页是否调用了执行器」的源码断言。终止路径目前只能靠真机日志。

---

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `core/container/ContainerCommandRunner.kt` | +`terminateTree` / `pidOf` / `collectDescendants`，超时路径改走它们 |
| `core/container/RootfsManager.kt` | `sanitize()` 过滤 `..`；`readString()` 加 64KB 上限并消费余量；三处调用点补空值跳过 |
| `core/agent/ContextBudget.kt` | `pruneImages` 增加 `anchorIndex`，语义与反例写进 KDoc |
| `core/agent/RealAgentLoop.kt` | 记录 `turnInputIndex` 并透传；`maxSteps` 注释校正 |
| `core/AgentLoop.kt` | 适配层 `maxSteps` 注释校正 |
| `ui/ChatViewModel.kt` | `RetryStarted` 分支清空流式缓冲 |
