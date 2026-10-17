# W-12 批：删除屏幕捕捉（MediaProjection），截图只走无障碍

## 决策依据（用户当场指出）

> 「无障碍模式也能。操控都不需要这个屏幕捕捉权限。」

对：**操作**只走无障碍，从来不需要屏幕捕捉；**截图**也已由无障碍 `takeScreenshot`
（API 30+）覆盖。MediaProjection 对 Android 11+ 设备是纯冗余，代价却是实打实的：
系统授权弹窗、前台服务、常驻通知、首帧等待。真机日志（2026-10-17 10:35）也证明：
屏幕捕获开着时，截图仍然走无障碍通道。

## 删除清单

| 层 | 改动 |
|---|---|
| 服务 | 删除 `automation/ScreenCaptureService.kt`（前台服务 + VirtualDisplay/ImageReader） |
| 桥 | 删除 `automation/ScreenshotBridge.kt`（授权缓存、启动策略、面包屑、清理计划） |
| 清单 | 移除 `<service .ScreenCaptureService>` 与 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限 |
| 工具 | `UiScreenshotTool` 改为**无障碍唯一通道**；失败码 `a11y_screenshot_unavailable` / `screenshot_failed`；文案明确「Android 11+，无需屏幕捕捉权限」 |
| 判据 | `shouldPreferA11yScreenshot` → `canUseA11yScreenshot`（不再有「优先/兜底」两条路） |
| ViewModel | 移除 `capturing` 状态、`setScreenCapturePermission` / `startScreenCapture` / `stopScreenCapture` |
| UI | 控制台：删「屏幕捕获」状态格与整张卡片；权限中心：删该项（四项→三项）；日志页：删「屏幕捕获最近失败」块；设置/骨架文案同步 |
| 失败码 | `FailureGuard` 移除 `capture_not_enabled`（该失败路径已不存在） |

## 验证

- 第一道门（沙箱二进制编译）：`UiScreenshotTool` + `AgentAccessibilityService`
  用真实源码 + Android 桩编译，**0 error**。
- 第二道门（harness）：
  - 重写 `W9ScreenshotVerification`：路由真值表 5 项 + 文案 4 项 +
    **删除反证 3 项**（工具代码无服务/桥引用、清单无服务与权限、源文件已删），**12/12 PASS**；
  - `PromptProtocolVerification` / `CrashLogVerification` / `HotfixVerification` /
    `Hotfix3Verification` / `T02T06Verification` 同步移除或改写依赖已删类的断言；
  - 删除 `CapturePathVerification` 与其 runner（整份针对 MediaProjection）。
- 第三道门：push + CI。run 156（`ce358b1`）**Build debug APK success**——
  UI 层删除（控制台/权限中心/日志页/设置页）无残留引用，Compose 编译通过。
- 第四道门：APK dex 反验 **全绿**。对象：run 156/157 产物（28,223,001 B，
  sha256 `daf04a25…575bc`，比 W-11 包小约 33KB——删掉的捕获代码）。
  判据：`canUseA11yScreenshot` / 新文案 2 项 / 完整 meta 段
  `,"type":"png","via":"a11y"}` 入池；反证：`ScreenCaptureService` /
  `ScreenshotBridge` 类描述符、`ScreenCaptureStartPolicy` / `ScreenCaptureTrace` /
  `capture_not_enabled` 均不在包内。

### 反验判据的两个坑（本次实证）

| 坑 | 真相 | 修法 |
|---|---|---|
| 裸 `a11y` 池条目消失 | 新模板把 `via` 写成字面量 `"a11y"`，整段编译为 `,"type":"png","via":"a11y"}`，不再有独立 `a11y` 条目 | 判据改为完整静态段 |
| `media_projection` 仍在包里 | 它是 **AndroidX/系统库**的通用常量（与 `media_router` 相邻），不是我们的代码 | 反证改用本项目自有符号：`ScreenCaptureStartPolicy` / `ScreenCaptureTrace` / `capture_not_enabled` |

## 行为边界（删后）

| 设备 | 截图 | 操作 |
|---|---|---|
| Android 11+（API 30+） | 无障碍，无授权弹窗 | 无障碍，不变 |
| Android 10 及以下 | **不可用**（返回可行动指引） | 无障碍，不受影响 |

Android 10 及以下的截图能力，后续由调试通道（adb `screencap`）补——见下一批。

## 下一步（未开工）

调试权限（自授权 adb shell）：配对 → 连接 → shell v2 → 工具路由。
落地后文件清理/应用管理/设置/截图可在后台完成，不再跳屏。
