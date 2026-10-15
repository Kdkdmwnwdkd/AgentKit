# HOTFIX5 说明：屏幕捕捉第五轮「反复开启崩 4 次」根治——token 服务外预取

> 作者：齐活林（Qi，主理人）汇总｜环境：`/workspace/AgentKit`，Kotlin 1.9.25 / AGP 8.5.0 / minSdk 26 / targetSdk 34 / arm64-v8a
> 编译：`bash gradlew :app:assembleDebug`（wrapper 发行版经腾讯云镜像补齐；settings 临时换腾讯 maven 镜像，构建后 `git checkout` 还原）
> 零新增外部依赖、不新增 JUnit。注释/日志简体中文。

## 0. 真机事实（用户提供日志，2026-10-15）

- 现象：屏幕捕捉**反复开启/停止**后崩，当天 4 次：09:46 / 11:26 / 16:06×2。
- 崩溃异常：`ForegroundServiceDidNotStartInTimeException`：
  `Context.startForegroundService() did not then call Service.startForeground()`，
  `at android.app.ActivityThread.generateForegroundServiceDidNotStartInTimeException(ActivityThread.java:2435)`。
- **该异常不可捕获**：计时器在 `startForegroundService()` 返回时即由系统持有，
  进程直接被杀——`stopSelf()` 也不能可靠取消它。前四轮的所有 try/catch 对它无效。

## 1. 根因（一句话）

**MediaProjection token 是一次性的**：`getMediaProjection(resultCode, data)` 消费后即失效。
hotfix5（02:10 批次）虽把「取 token → 进前台」的顺序修对了，但取 token 的动作仍留在
服务 `onStartCommand` 里。于是「停止后再次开启」时：服务内二次消费已失效 token →
`getMediaProjection` 失败 → `failSafe` 直接收摊（`stopSelf` + 上报 lastError）→
**`startForeground` 从未执行** → 系统 5 秒超时 → `ForegroundServiceDidNotStartInTimeException` 杀进程。
崩 4 次正对应用户反复开关。

## 2. 修复架构（两条红线的共同解）

- **红线 A**（Android 14 类型强校验）：带 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 的
  `startForeground` 前必须已取得 token，否则 SecurityException。
- **红线 B**（Android 12+ 前台服务超时）：`startForegroundService()` 后约 5s 内必须
  `startForeground()`，否则进程被杀（不可捕获）。
- **根治思路**：把 token 获取移到服务生命周期**之前**——桥层
  `ScreenshotBridge.obtainProjection(context)` 在**授权回调后立即**执行（token 最新鲜）。
  **取不到就不启动服务**：`startForegroundService` 根本不会被调用，红线 B 的 5 秒计时
  无从开始，从源头消灭该崩溃。服务 `onStartCommand` 只做：① 从桥读 projection
  （null = 异常拉起，先保命再收摊）→ ② 第一件事就是带类型进前台 → ③ 才建
  ImageReader / VirtualDisplay。
- **token 一次性生命周期收口**：`setPermission` 先 `releaseProjection()` 再存新授权
  （防新旧 resultCode 串味）；`stopService` / `failSafe(STOP_PROJECTION)` / `onDestroy`
  三处收摊统一走 `ScreenshotBridge.releaseProjection()`（同步清桥）。重开必须重新授权
  （Android 硬限制，所有大厂 App 同理）——UI 由 lastError + 重新授权入口承接，
  好过拿着失效 token 二次消费崩进程。

## 3. 文件清单

| 文件 | 变更 |
| --- | --- |
| `automation/ScreenshotBridge.kt` | 新增 `mediaProjection` 字段（token 桥层预取）、`obtainProjection(context)`（服务外取 token，失败只落 lastError 返回 false，绝不抛）、`releaseProjection()`（停止即作废）；`setPermission` 先清旧投影；`startService` 重构（幂等 → token 兜底 → **无 token 不启动服务**）；`stopService` releaseProjection |
| `automation/ScreenCaptureService.kt` | `onStartCommand` 不再调 `getMediaProjection`，改从桥读；**先** `startForegroundSafely(projection != null)` 后处理 null 收摊；`failSafe` 的 STOP_PROJECTION 与 `onDestroy` 统一走 `ScreenshotBridge.releaseProjection()`；清理 unused import；类 KDoc 重写（两条红线 + 一次性 token 硬约束 + 新启动顺序） |
| `ui/ChatViewModel.kt` | `setScreenCapturePermission` 授权成功后**立即** `ScreenshotBridge.obtainProjection(app)` + OBTAIN_TOKEN 面包屑（失败路径已有 lastError 横幅承接） |
| `scripts/qa/CapturePathVerification.kt` | OBTAIN_TOKEN 面包屑发出点 service→bridge；新增 D 区 9 条断言（D1-D9：桥层双函数、服务代码零 getMediaProjection、服务从桥读、startForeground 早于 setupCapture、收摊两处 releaseProjection、授权回调即时取 token、无 token 不启动、setPermission 先清后存） |
| `scripts/qa/CrashLogVerification.kt` | E5 危险调用清单移除 `getMediaProjection(`（已移桥）；新增 E5b（服务代码零 getMediaProjection + 桥层调用在 guard 内） |
| `scripts/qa/run-*.sh`（13 个）+ `Hotfix3Verification.kt` | 修复仓库搬迁后**全部失效**的路径漂移：`/workspace/scripts|app|docs` → `/workspace/AgentKit/...`；JB_ANNOTATIONS 从不存在的 `scripts/qa/lib/` 副本改指 Gradle 缓存路径（顺手清掉工单 W-6） |
| `scripts/qa/HOTFIX5-NOTES.md` | 本文件 |

## 4. 验证闭环（全绿）

| 门 | 结果 |
| --- | --- |
| `bash gradlew :app:assembleDebug` | BUILD SUCCESSFUL（4m06s；llama.cpp b4600 已在位，仅 Kotlin 增量） |
| run-capture-path-verification.sh | **49 passed / 0 failed**（含新增 D1-D9 九条） |
| run-crashlog-verification.sh | **48 passed / 0 failed**（含新增 E5b） |
| run-hotfix-verification.sh | 17 passed / 0 failed |
| run-appcontrol-verification.sh | 53 passed / 0 failed |
| run-t01-verification.sh | 37 passed / 0 failed |
| run-hotfix3 / fallback / branch | 23 / 16 / 20 passed, 0 failed（sed 抽验） |

## 5. 真机复测要点（给用户）

1. **反复开关**：开启屏幕捕获 → 停止 → 再开启（多轮），应全程不崩；
2. 停止后再开启时，若 token 已消费，应提示「请重新授予屏幕捕获权限」类可读文案
   （lastError 横幅/Toast），**而不是闪退**；
3. 日志 grep `ScreenCapture` 应能看到完整面包屑：授权回调进入 → 缓存授权结果 →
   取 MediaProjection token（桥层）→ 启动前台服务 → 服务 onCreate → onStartCommand →
   进前台 → ImageReader → VirtualDisplay → 首次拿到帧。
