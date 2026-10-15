# HOTFIX4 说明：屏幕捕捉「点立即开始后闪退」最后一刀

> 作者：寇豆码（Kou，Engineer）｜环境：`/workspace`，Kotlin 1.9.25 / AGP 8.5.0 / minSdk 26 / targetSdk 34
> 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks`（**不用 sdkman gradle 9.3**）
> 零新增外部依赖、不新增 JUnit。注释/日志简体中文。

## 0. 真机事实与定位逻辑（务必吃透）

- 现象：点「开启屏幕捕捉」→ 系统授权框 → 点「立即开始」→ **闪退**。
- 已确认装对：`AndroidManifest` 声明 `.AgentKitApplication`、`CrashLogger.install` 已在 `onCreate` 调用。
- 已确认改造过：`ScreenCaptureService` 按 Android 14 顺序、已注册 `MediaProjection.Callback`、
  对 `createVirtualDisplay`/`ImageReader`/尺寸为 0 全流程兜底。
- **关键矛盾**：用户日志里**完全没有 `ScreenCapture` 相关行**，也没有崩溃记录。
  → 崩点在「我们既没打日志、也没兜底」的位置。

**结论（本次修复的两根支柱）**：
1. **授权回调整段兜底**：`ActivityResult` 回调跑在主线程，任何未捕获 `Throwable` 直接杀进程。
   之前 launcher 回调体里 `setScreenCapturePermission` / `startScreenCapture` /
   `getSystemService(...) as MediaProjectionManager` 等**裸调用**——一旦抛异常，进程死，
   且因为没有面包屑，事后无从定位。现在整段包兜底 + 逐步留痕。
2. **面包屑**：从「授权回调进入」到「首次拿到帧」每一步打一条 INFO 日志；
   即使随后进程被杀，也能凭**最后一条面包屑**精确定位崩点。

## 1. 文件清单

| 文件 | 变更 |
| --- | --- |
| `ui/Components.kt` | launcher 回调整段包 `ScreenCaptureAuthGuard.run`（异常不逃逸）+ 弹系统框也兜底；新增 `AkCrashBanner` 崩溃横幅 |
| `ui/ChatViewModel.kt` | `setScreenCapturePermission`/`startScreenCapture` 加 try/catch + 面包屑；**修正 `_capturing` 语义**（仅 `startService==true` 才置位） |
| `automation/ScreenshotBridge.kt` | 新增 `ScreenCaptureTrace`（面包屑清单+发点）、`ScreenCaptureAuthGuard`（回调纯函数兜底）、`CaptureFlag`（置位决策表）；`startService` 补面包屑 |
| `automation/ScreenCaptureService.kt` | 补面包屑：onCreate/onStartCommand/取 token/尺寸/ImageReader/createVirtualDisplay/registerCallback/进前台/首帧 |
| `core/CrashLogger.kt` | 新增 `unseenCrashFiles`（纯函数）+ `unseenCrashRecords`/`hasUnseen`/`markAllSeen`/`markSeen`（SharedPreferences 已读标记） |
| `AgentKitApplication.kt` | 启动即检测未查看崩溃并留痕（读取失败静默降级） |
| `ui/LogsScreen.kt` | 「最近崩溃」区块并排展示 `ScreenshotBridge.lastError`（C） |
| `ui/AppScaffold.kt` | **边界外 1 文件**（见 §4）：主界面顶部接线 `AkCrashBanner`，点击跳日志页 |
| `scripts/qa/CapturePathVerification.kt` | 新增 harness（40 断言） |
| `scripts/qa/run-capture-path-verification.sh` | 新增离线 runner |
| `scripts/qa/HOTFIX4-NOTES.md` | 本文件 |

## 2. 关键裁决

### 2.1 A1 授权回调全路径兜底

`ScreenCaptureAuthGuard.run(block): Outcome`（纯函数）——`try { block(); Outcome(null,null) } catch (t: Throwable) { Outcome(describe(t), t) }`。
- **捕获 `Throwable`**（含 `Error`/`OutOfMemoryError`/厂商 ROM 的 `SecurityException`），**绝不上抛**。
- 失败文案统一来源 `ScreenCaptureStartPolicy.failureMessage`：`屏幕捕获启动失败：<原因>，可重试`。
- `Components.kt` 的 launcher 回调体 + 返回的点击 lambda（弹系统框）**两处**都走它。

### 2.2 A3 `_capturing` 语义修正（真机事实级 bug）

原实现 `startScreenCapture` **无条件** `_capturing.value = true` → 「显示捕获中，但服务其实没起来」。
现改为 `CaptureFlag.decide(startSucceeded, lastError)`：
- `true` → 置捕获中；
- `false` → **保持 false**，`errorMessage` 优先取 `lastError`，为空则兜底「未知原因」（绝不静默）。

### 2.3 A4 面包屑（本轮最关键）

`ScreenCaptureTrace.KEY_STEPS`（顺序=真实执行顺序，共 **12** 条）：
`授权回调进入 → 缓存授权结果 → 启动前台服务 → 服务 onCreate → 服务 onStartCommand →
取 MediaProjection token → 屏幕尺寸 → ImageReader 创建 → createVirtualDisplay →
registerCallback → 进前台 startForeground → 首次拿到帧`。

每条成功打 INFO（`[面包屑] 步骤：详情`），失败打 `logFailure`（ERROR + 堆栈）。
详情经 `AgentLogger.sanitize` 脱敏 + 截断 200 字，**不明文落 key/包名等敏感信息**（只记 `resultCode`/尺寸等非敏感值）。
- 服务侧**每个**危险调用/分支都配了对应面包屑（见 `ScreenCaptureService`）。
- 首帧只在第一次打（`firstFrameLogged` 标志），避免刷屏。

### 2.4 B 崩溃可见性（用户看不到=白做）

- **纯函数** `CrashLogger.unseenCrashFiles(all, seen)`：挑出「仍存在且不在已读集合」的崩溃文件名，最新在前。
- **已读标记**用 SharedPreferences（`crash_seen/seen_files`，存文件名集合），**不**改文件名
  （改名会破坏 `isCrashFileName`/保留策略识别）。`markAllSeen` 只落**现存**文件名，天然清理陈旧记录。
- **横幅** `AkCrashBanner`：有未查看崩溃时置顶显示「检测到上次崩溃」；
  点「查看」→ 标记已读 + 跳日志页；点关闭 → 标记已读。
- 目录不存在/读取失败 → 全程 `runCatching` 静默降级返回空清单，**不影响启动**。
- `CrashLogger` 原有行为（同步落盘、回传原 handler、保留最近 N 份）**未改**。

### 2.5 C 诊断辅助

- 确认 `ScreenshotBridge.lastError` 每次失败都经 `logLastError` 落 WARN（保留）。
- 日志页「最近崩溃」区块**并排**展示 `lastError`（存在时）；即使无崩溃文件、仅有 `lastError`，区块也会显示
  → 用户一次截图即可给全「崩溃 + 屏幕捕获失败原因」。

## 3. harness `CapturePathVerification.kt`（40 断言，全过）

四组，均**零 Android 依赖**：
1. **授权回调兜底语义**：成功/`RuntimeException`/`Error` 三类；断言任何 `Throwable` 都不逃逸、失败带原因+
   cause、成功无 Toast、文案含「屏幕捕获启动失败/可重试/原因」。
2. **`_capturing` 决策表**：`startService` 成功→true；失败→false 且带 `lastError`；`lastError` 空/空白→「未知原因」。
3. **面包屑完整性**：`KEY_STEPS` 数量=12、内容与顺序精确匹配、无重复；源码扫描断言 12 个 `ScreenCaptureTrace.<STEP>`
   在真实代码里被引用 + 两处回调兜底 + `CaptureFlag.decide` + 横幅定义/接线 + lastError 展示 + CrashLogger 纯函数。
4. **未读崩溃筛选**：已读子集/全未读/全已读/非崩溃名/空目录/已删除文件六种输入。

## 4. 关于文件边界的偏差（**必读**）

任务指定「只碰」7 文件，未列 `ui/AppScaffold.kt`。但需求 B 明确「点击**跳日志页**」——
跳转依赖 `AppScaffold` 持有的 `route` 导航态，**不在边界内无法实现**。
故对 `ui/AppScaffold.kt` 做**最小接线**（宽/窄两分支各一处 `AkCrashBanner(onOpenLogs = { navigateTo(AppDest.LOGS) })`，
且日志页自身不重复显示）。这是满足 B 的**唯一可行落点**，特此标注请示。

## 5. 自检证据

- 编译：`assembleDebug --offline --rerun-tasks` → **BUILD SUCCESSFUL**（仅既有 deprecation 警告）。
- 新 harness：`CapturePathVerification` → **PASS=40 FAIL=0**。
- 回归十六套：见 §6。

## 6. 回归结果（十六套，全绿）

| 套件 | 结果 |
| --- | --- |
| `run-verification.sh` | 32 passed, 0 failed |
| `run-t01-verification.sh` | 35 passed, 0 failed |
| `run-t01-probe.sh` | 11 passed, 0 failed |
| `run-t01-probe2.sh` | 7 passed, 0 failed（已知竞态 R9 本次未触发） |
| `run-appcontrol-verification.sh` | 31 passed, 0 failed |
| `run-t07t08-verification.sh` | 35 passed, 0 failed |
| `run-t07t08-adversarial.sh` | 17 passed, 0 failed |
| `run-markdown-verification.sh` | 45 passed, 0 failed |
| `run-branch-verification.sh` | 20 passed, 0 failed |
| `run-retry-verification.sh` | 9 passed, 0 failed |
| `run-localmodel-verification.sh` | 19 passed, 0 failed |
| `run-fallback-verification.sh` | 16 passed, 0 failed |
| `run-hotfix-verification.sh` | 17 passed, 0 failed |
| `run-hotfix3-verification.sh` | 23 passed, 0 failed |
| `run-crashlog-verification.sh` | 47 passed, 0 failed |
| `run-prompt-protocol-verification.sh` | 17 passed, 0 failed |

新增 `run-capture-path-verification.sh` → **PASS=40 FAIL=0**。

## 7. 遗留 / 未决

- `AppScaffold.kt` 的边界外接线（见 §4）需主理人确认。
- 真机验证仍需用户在设备上复现：期望看到 `[面包屑] ...` 序列，崩点即最后一条；
  若仍闪退，`filesDir/crash/` 会留下崩溃报告且启动横幅可见。
