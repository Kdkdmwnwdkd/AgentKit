# HOTFIX6 说明：第六轮——启动顺序回正（hotfix6 桥层预取被真机证伪）

> 作者：齐活林（Qi，主理人）｜环境：`/workspace/AgentKit`，Kotlin 1.9.25 / AGP 8.5.0 / minSdk 26 / targetSdk 34
> 编译：`bash gradlew :app:assembleDebug`（wrapper 发行版经腾讯云镜像补齐；settings 临时换腾讯 maven 镜像，构建后还原）
> 零新增外部依赖。注释/日志简体中文。

## 0. 真机事实（用户日志，2026-10-15 17:13）

hotfix6 包装上后**不崩了，但功能全废**：点「开启屏幕捕获」→ 授权 → 立即失败，反复 5 次全一样：

```
java.lang.SecurityException: Media projections require a foreground service of type
ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    at android.media.projection.MediaProjection.<init>(MediaProjection.java:93)
    at android.media.projection.MediaProjectionManager.getMediaProjection(MediaProjectionManager.java:284)
    at com.example.agentkit.automation.ScreenshotBridge$obtainProjection$failure$1.invoke(...)
```

## 1. 根因：hotfix6 的「红线 A」假设是错的

hotfix6 据「startForeground 带 mediaProjection 类型前必须先取到 token」的**错误假设**，
把 `getMediaProjection()` 挪到服务生命周期之前（桥层授权回调里预取）。真机实证：
Android 14（targetSdk 34）的规则恰好**相反**——`getMediaProjection()` 本身要求
**本应用已有以 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 类型运行的前台服务**，
否则系统在投影 start 时抛 SecurityException。服务没起就取 token = 必抛。

（顺带厘清前几轮：崩溃链的真根因也是这条——老代码在服务内**先取 token 后进前台**，
SecurityException → failSafe 收摊 → startForeground 从未执行 → 5s 超时
`ForegroundServiceDidNotStartInTimeException` 杀进程。「token 一次性」只是次要因素。）

**Operit 源码实证**（`ScreenCaptureActivity.kt:47` 原话注释）：
> Android 14 requirement: Start foreground service with mediaProjection type BEFORE calling getMediaProjection

且其做法是：先 `ScreenCaptureService.start()`，**轮询 `isMediaProjectionForegroundReady`
（30ms 间隔 / 1.5s 超时）**，就绪后才 `getMediaProjection()`。

## 2. 修复：Android 14 两条硬规则的唯一正确顺序

```
onStartCommand：
  ① startForegroundSafely()          ← 带 mediaProjection 类型进前台（第一句！）
  ② obtainProjectionWithRetry(...)   ← 前台就绪后才允许取 token（规则 A 前置满足）
  ③ setupCapture(...)                ← 建 ImageReader/VirtualDisplay（重活放最后）
```

- **规则 B**（5s 超时，不可捕获）：① 在入口第一句完成，与后续成败无关——②③ 任何失败
  都只走 failSafe 可见失败，**结构上不可能**再触发超时崩溃；
- **规则 A**（getMediaProjection 前置）：① 完成即满足；
- ① 带类型进前台万一被个别 ROM 拒：**退化无类型保命**（5s 红线优先），随后②会可见地
  失败（failSafe），可接受降级；
- ② 仅对 SecurityException 有限重试（初始 + 2 次 × 100ms）：覆盖 startForeground 返回后
  个别 ROM 前台状态传播的极小异步窗口（Operit 轮询思路的服务内等价版）；
- 桥层回滚为「只缓存授权 extras → 随 intent 传服务 → 停服务 → 暴露状态」，
  **不持有、不获取 token**；服务自行 stop 自己的投影（failSafe / onDestroy）。

## 3. 文件清单

| 文件 | 变更 |
| --- | --- |
| `automation/ScreenCaptureService.kt` | `onStartCommand` 重写为「先进前台 → 取 token（有限重试）→ 建投影」；`startForegroundSafely()` 带类型优先 + 无类型保底；新增 `obtainProjectionWithRetry`；`failSafe`/`onDestroy` 停自己的投影；类 KDoc 重写为两条硬规则 |
| `automation/ScreenshotBridge.kt` | 回滚 hotfix6：删 `mediaProjection` 字段 / `obtainProjection` / `releaseProjection`；`setPermission` 只缓存；`startService` 恢复 extras 传参 + 无授权数据不启动（ABORT）；`stopService` 简化；`ScreenCaptureStartPolicy.plan` 顺序翻转为 `[START_FOREGROUND, OBTAIN_TOKEN]` |
| `ui/ChatViewModel.kt` | `setScreenCapturePermission` 去掉 obtainProjection 接线，回到「只缓存」 |
| `scripts/qa/CapturePathVerification.kt` | D 区重写为 10 条（D1-D10）：顺序三步行、带类型、无类型保底、有限重试、失败收摊、桥零取 token、extras 传参、ABORT、授权回调只缓存、服务自 stop |
| `scripts/qa/CrashLogVerification.kt` | E5 恢复 `getMediaProjection` 入危险清单；E5b 翻转为「桥零取 token + 服务 startForeground 早于取 token」 |
| `scripts/qa/HotfixVerification.kt` | plan 顺序断言翻转 |
| `scripts/qa/HOTFIX6-NOTES.md` | 本文件 |

## 4. 验证闭环（全绿）

| 门 | 结果 |
| --- | --- |
| `bash gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| run-capture-path-verification.sh | **50 passed / 0 failed**（D1-D10） |
| run-crashlog-verification.sh | **48 passed / 0 failed** |
| run-hotfix-verification.sh | 17 passed / 0 failed |
| run-appcontrol / t01 / hotfix3 / fallback / branch | 53 / 37 / 23 / 16 / 20 passed，0 failed |

**诚实声明**：以上均为源码级与纯函数级验证，**不能证明** Android 14 真机行为——
规则 A/B 的时序只有真机授权一次才能证实。本版按 Operit 实证顺序实现，若真机仍失败，
下一步不再修 MediaProjection，直接走用户已备选的 B（砍自动截屏，改手动发图）或
C（引入 Shizuku 调试权限）。

## 5. 真机复测要点

1. 开启屏幕捕获 → 点「立即开始」：**不再报 SecurityException**，通知栏出现「AgentKit 屏幕捕获中」；
2. 日志 grep `ScreenCapture` 期望顺序：授权回调进入 → 缓存授权结果 → 启动前台服务 →
   服务 onCreate → onStartCommand → **进前台 startForeground 成功** →
   取 MediaProjection token 成功 → ImageReader → VirtualDisplay → 首次拿到帧；
3. 若「进前台成功」后出现「第 N 次遇 SecurityException，重试」属预期的传播窗口兜底；
4. 反复开关多轮：应不崩；失败时给可读文案（重新授权），不闪退。
