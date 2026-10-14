# HOTFIX-NOTES · 修真机实测两个 Bug 的修复与验证

> 环境：Kotlin 1.9.25 / AGP 8.5.0 / minSdk 26 / targetSdk 34，零新增外部依赖，不新增 JUnit。
> 编译命令：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks`
> 本轮**未推送**、未碰网络（除 Gradle 离线缓存）。

---

## 1. Bug 1（必修）断网时看不到「正在恢复（n/N）」，直接甩错误

### 现象与根因（已确认）
`core/agent/RealAgentLoop.kt` 的 `callWithRetry` 里 `repeat(providerRetry + 1)` 循环**没有任何等待**。
断网时 `UnknownHostException` 是**瞬时返回**的 → 3 次尝试几十毫秒内打完 →
`AgentEvent.RetryStarted` 刚置位就被 `finally` 的 `RetryFinished` 清掉 → 横幅来不及渲染，
联网后也因为重试额度已在几十毫秒内耗尽而**不会自动续上**。
`isRetryable(e) = (e as? LlmHttpException)?.retryable ?: true` 判定 UnknownHostException 可重试——**判定没错**，缺的是退避。

### 修法（落地）
1. **宣布重试之后、下一次尝试之前插入退避**：`RetryStarted` 先抛（保持原顺序），随后
   `retryBackoff(attempt + 1)`，默认 1000ms → 2000ms → 封顶 4000ms
   （`RetryBackoffPolicy.backoffMillis`，指数 + 封顶）。
2. **可注入**：构造新参数 `retryBackoff: suspend (attempt: Int) -> Unit = RetryBackoffPolicy.DEFAULT`，
   参数为 1-based 重试序号；harness 注入即时版/小值版保持毫秒级。
3. **取消优先**：`delay` 自带取消语义；退避钩子本身是否可取消是注入方的自由，故钩子返回后补一次
   `currentCoroutineContext().ensureActive()`——即使钩子是 `{ Thread.sleep(n) }` 这种不看取消的实现，
   点停止也不会再多跑一次无用请求、也不会残留「正在恢复」（T05）。
4. 不可重试错误（401/403/400）**不等待**、一次即失败（快败路径零新增延迟）。

为什么要等：① 给 UI 稳定可见时长，否则「重试」对用户体验等于没发生；② 给网络真实恢复窗口，
这才是重试的意义。

### 断言（HotfixVerification.kt，Bug1 段，17/17 全绿）
| # | 断言 | 结果 |
|---|---|---|
| A | `backoffMillis` 序列 = 1000/2000/4000 且之后封顶 4000；序号 ≤0 按第 1 次处理 | PASS |
| B | 前 2 次失败第 3 次成功 → 序列 (1/2)(2/2) + 最终成功 + `RetryFinished` 清状态 | PASS |
| B | **退避钩子调用次数 == 重试次数**（2 次，序号 1、2） | PASS |
| C | 注入小值（50ms×序号）→ 单次确实等了 ≥50ms、整轮 ≥150ms（无退避时是毫秒级） | PASS |
| D | `delay` 型退避中取消 → CancellationException 原样上抛、无残留、不多等（<5s）、不再发请求 | PASS |
| D | 不可取消钩子（`Thread.sleep`）→ 由 `ensureActive` 兜底，仍上抛 CancellationException | PASS |
| E | 401 → 退避钩子 0 次调用、请求 1 次、无 RetryStarted/RetryFinished | PASS |
| E | 首次即成功 → 钩子 0 次调用 | PASS |
| E | 耗尽重试 → 钩子恰好 `providerRetry` 次（最后一次失败不再等待） | PASS |

---

## 2. Bug 2（必修）屏幕捕捉给权限后回到 App 就闪退

### 现象（用户原话）
开了过后**给了权限**，回到应用就**直接闪退**；桌面点图标三次都是「点了就退」，第三次才进得去；
最终权限没开，`ui_screenshot` 一直失败。

### 走读裁决（Root Cause）
Manifest（`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限、
`foregroundServiceType="mediaProjection"` 服务声明）**齐全，不是缺声明**。
真因是**启动顺序颠倒 + 无兜底**：

- Android 14（API 34 = 本项目 targetSdk）要求：以 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`
  调用 `startForeground()` 时，本次捕获的 MediaProjection token **必须已经取出**
  （用户已同意、`MediaProjectionManager.getMediaProjection(resultCode, data)` 已成功）；
  否则抛 `SecurityException`。
- 老流程：`ScreenshotBridge.startService` → `startForegroundService` → `ScreenCaptureService.onCreate`
  → `startForeground(..., MEDIA_PROJECTION)`（此时**还没取 token**）→ `onStartCommand` 才 `getMediaProjection()`。
  顺序颠倒，且 `startForegroundSafely()` **无 try/catch** → 异常从服务生命周期回调逃逸 → 进程崩。
- 版本差异（写进 KDoc）：≤28 `startForeground` 无 type 参数，顺序无意义；29～33 类型已支持但无强制校验；
  **≥34 才强制**。修法对全版本走同一条「先取 token 再进前台」路径（29～33 亦无副作用），不做分支。

### 修法（落地）
1. **兜底（底线，无论根因为何）**：`ScreenshotBridge.startService`、`ScreenCaptureService` 取 token 与
   `startForegroundSafely()` 全部包在 `ScreenCaptureStartPolicy.guard { }` 里（catch **Throwable**，
   含 Error / SecurityException）→ `AgentLogger.e(tag, msg, cause)` **带堆栈**记日志 +
   `ScreenshotBridge.reportFailure(reason)` + `stopSelf()`。**任何异常都不再上抛**。
2. **纠正顺序**：`onCreate` 不再进前台（它拿不到带授权 Intent 的 extras）；`onStartCommand` 里
   ① 先 `initProjection`（取 token）② 再 `startForegroundSafely(hasToken)`。
   且仅在**确实持有 token** 时才声称 `MEDIA_PROJECTION` 类型（无 token 时走无类型 `startForeground`，
   避免必然失败；同时保证不触发「`startForegroundService` 后 5s 未调用 startForeground」的系统崩溃）。
3. **失败可见**：`ScreenshotBridge.startService` 返回 Boolean + `lastError`；
   `Components.kt` 的授权回调在启动后读取 `lastError`，Toast 一条
   `屏幕捕获启动失败：<原因>，可重试`（读完即清，避免下次成功还在弹旧错）。文案唯一来源 =
   `ScreenCaptureStartPolicy.failureMessage`，UI 与日志同源。**未大改 ChatViewModel**。
4. **可测**：顺序判定抽成零 Android 依赖的纯对象 `ScreenCaptureStartPolicy`
   （`plan(hasToken, sdkInt)` / `describe` / `failureMessage` / `guard`），harness 可直接断言。

### 断言（HotfixVerification.kt，Bug2 段，全绿）
| # | 断言 | 结果 |
|---|---|---|
| F | 有 token @34 → `[OBTAIN_TOKEN, START_FOREGROUND]`，且 token 早于前台 | PASS |
| F | 无 token @34 → `[ABORT]`（绝不声称 mediaProjection 类型） | PASS |
| F | @29 / @33 同 @34（建议顺序，无副作用） | PASS |
| G | SecurityException → 转失败原因 + 保留原始异常（供记堆栈），**不上抛** | PASS |
| G | Error / IllegalStateException（后台启动限制）/ NPE 一律被吞 | PASS |
| G | 正常路径 → 判成功、不误报失败 | PASS |
| H | 失败文案非空、含前缀与具体原因、含「可重试」 | PASS |
| H | 原因 null/空白 → 退化为「未知原因」但仍「可重试」（绝不静默） | PASS |

---

## 3. 文件清单

改动（均在授权边界内，未碰 `core/agent/` 其它文件、`tools/`、`core/terminal/`）：

| 文件 | 改动摘要 |
|---|---|
| `app/.../core/agent/RealAgentLoop.kt` | 新增 `retryBackoff` 构造参数；`callWithRetry` 插入退避 + `ensureActive`；新增 `RetryBackoffPolicy` 纯对象；KDoc 更新 |
| `app/.../automation/ScreenshotBridge.kt` | 新增 `ScreenCaptureStartPolicy`（顺序/描述/文案/兜底纯函数）；`startService` 返回 Boolean + `lastError` + guard 兜底；新增 `reportFailure` |
| `app/.../automation/ScreenCaptureService.kt` | `onCreate` 不再进前台；`onStartCommand` 先取 token 后进前台；`startForegroundSafely(hasToken)` 全 try/catch + 记堆栈 + stopSelf；类 KDoc 写清版本差异 |
| `app/.../ui/Components.kt` | 授权回调读取 `lastError` → Toast「屏幕捕获启动失败：<原因>，可重试」；新增两处 import |
| `scripts/qa/HotfixVerification.kt` | 新增 harness（17 条断言） |
| `scripts/qa/run-hotfix-verification.sh` | 新增离线运行脚本（照 `run-retry-verification.sh` 形态） |
| `scripts/qa/RetryVerification.kt` | 9 处构造改注入 `INSTANT_BACKOFF`（见下） |
| `scripts/qa/HOTFIX-NOTES.md` | 本文档 |

**为何改既有 `RetryVerification.kt`**：加退避后该套（9 条）假 client 是瞬时返回的，不注入即时退避
会每用例多等约 3 秒且毫无收益。故统一改成构造 `RealAgentLoop(...)` + `retryBackoff = INSTANT_BACKOFF`，
把「真退避行为」交给新的 `HotfixVerification.kt` 覆盖。真机/VM 默认路径不受影响。

## 4. 自检证据

- 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks` → **BUILD SUCCESSFUL**（无 error）
- 新 harness：`bash scripts/qa/run-hotfix-verification.sh` → **PASS: 17 FAIL: 0 TOTAL: 17 / HOTFIX_VERIFICATION: ALL PASS**
- 回归十二套（目标条数 / 实测）：

| 套件 | 目标 | 实测 | 结论 |
|---|---|---|---|
| run-verification.sh | 32 | 32/32 | 绿 |
| run-t01-verification.sh | 35 | 35/35 | 绿 |
| run-t01-probe.sh | 11 | 11/11 | 绿 |
| run-t01-probe2.sh | 7 | 7/7 | 绿（R9 竞态本轮未复现，未改它） |
| run-appcontrol-verification.sh | 31 | 31/31 | 绿 |
| run-t07t08-verification.sh | 35 | 35/35 | 绿 |
| run-t07t08-adversarial.sh | 17 | 17/17 | 绿 |
| run-markdown-verification.sh | 45 | 45/45 | 绿 |
| run-branch-verification.sh | 20 | 20/20 | 绿 |
| run-retry-verification.sh | 9 | 9/9 | 绿（已注入即时退避，耗时无变化） |
| run-localmodel-verification.sh | 19 | 19/19 | 绿 |
| run-fallback-verification.sh | 16 | 16/16 | 绿 |

其余 harness 之所以不受退避影响：它们统一 `providerRetry = 0`，`repeat(1)` 循环内
`attempt < providerRetry` 恒假 → 根本进入不到退避分支（已逐个核对全部 45+ 处构造点）。

## 5. 关键裁决

1. **Bug 1 修在确定处**：后退避（先抛 `RetryStarted` 再 `delay`），不可重试错误零等待——保证「快败」路径
   不被这次修复拖慢。
2. **Bug 2 采用「token → startForeground」顺序**（Android 14 官方约束方向），且**全版本共用一条路径**，
   不在服务里铺版本分支；KDoc 明确写明 ≤28 / 29-33 / ≥34 的差异。
3. **兜底优先于归因**：即使将来证明某台设备是别的原因（如厂商 ROM 魔改），`guard` 也会把它变成
   「一条失败 Toast + 一条 ERROR 日志」而不是进程崩溃——这是本次对用户最有价值的一处改动。
4. **UI Toast 是即时检测**：命中桥侧同步失败（未授权 / `startForegroundService` 被拒）必弹；
   服务内部的**异步**失败会落成 `lastError` + AgentLogger ERROR（含堆栈），用户可在运行日志页看到，
   下一次再点「开始」也不会误弹旧错（成功路径会主动清空 `lastError`）。

## 6. 遗留（不在本轮边界，留给下一轮）

1. `ChatViewModel.startScreenCapture` 仍无条件 `_capturing.value = true`——启动失败时 UI 开关会短暂显示
   「捕获中」。建议下一轮改为依据 `ScreenshotBridge.startService` 的返回值置位（本轮按指示未大改 VM）。
2. 屏幕捕获的异步失败目前只能走日志 + 下次交互时的 `lastError`；若要「实时弹提示」，需让 VM 观察
   `ScreenshotBridge.lastError`（新增状态流），属 UI 层改造，未在本轮文件边界内。
3. `MediaProjection` 未注册 `Callback` 释放：用户从系统设置撤销授权时进程内的 token 失效不会回调停止，
   建议下一轮补 `mediaProjection.registerCallback(..., stopSelf())`。
4. 真机行为仍需一次实机验证（飞行模式看横幅 / 授权后不再闪退），本轮仅保证编译 + harness + 十二套回归全绿。
