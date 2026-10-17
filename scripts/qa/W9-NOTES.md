# W-9 批：截图通道升级（无障碍 takeScreenshot 主路径）+ 小模型策略引导

批次目标（接 W-8 真机诊断「屏幕捕捉开了和没开没区别、更久更浪费时间」的剩余耗时项）：

1. **W-9-A 截图双通道**：无障碍 `AccessibilityService.takeScreenshot`（API 30+）为主路径，
   MediaProjection 留兜底；`meta.via` 记录实际通道（`a11y` / `media_projection`）。
2. **W-9-B 小模型策略引导**：系统提示写清 `ui_tree` / `ui_screenshot` 分工，禁每步截图。

## 动机（真机日志实证）

- W-8 诊断定案：任务闸门被熔断挡死的根因已修（见 W8-NOTES.md），但截屏路径本身仍有
  **MediaProjection 授权链耗时**（系统弹窗 + 前台服务启动 + 首帧等待）——23:58:38 一次
  `ui_screenshot ok` 与「开了和没开没区别」的体感差距来自这里，不是权限本身。
- 本项目**已有无障碍服务**（读屏授权比 MediaProjection 更强，截图授权天然蕴含其中），
  `AccessibilityService.takeScreenshot`（API 30+）因此是零授权链的更快主路径。
- 参照策略：Operit 截图三级链（Shizuku shell screencap → 无障碍 takeScreenshot →
  MediaProjection 兜底）的**中间级**；本项目无 Shizuku 通道，直接取无障碍级为主路径，
  MediaProjection 兜底。净室重写，不引其代码。

## 修复批次

| # | 文件 | 改动 | 意图 |
|---|------|------|------|
| A1 | `automation/AgentAccessibilityService.kt` | 新增 `A11Y_SCREENSHOT_MIN_API=30` + 纯函数 `shouldPreferA11yScreenshot(a11yReady, apiLevel)`；服务方法 `takeScreenshotViaA11y()`（`withContext(Dispatchers.Main)` + `suspendCancellableCoroutine` 转系统回调；API<30 / onFailure / 抛异常 → null，绝不逃逸） | 主路径落地；路由判据零 Android 依赖可 harness 断言 |
| A2 | `automation/UiScreenshotTool.kt` | 主路径优先（a11y 就绪且 API≥30 → `takeScreenshotViaA11y`），失败静默落 MediaProjection 兜底；抽出 `saveAndReturn(bmp, via)`，meta 增 `"via"`；描述/系统提示/失败指引补「无障碍优先、兜底才需屏幕捕获」一句 | 双通道 + 通道可观测（日志/harness 断言） |
| B1 | `core/AgentLoop.kt` | `DEFAULT_SYSTEM_PROMPT` 增「工具分工」段：ui_tree 拿结构与坐标（操作前必读）、ui_screenshot 用于「看见」视觉内容、优先 ui_tree、**不要每个步骤都截图** | 治小模型每步截图烧 token 又慢（W-8 复测观察项） |
| H1 | `scripts/qa/W9ScreenshotVerification.kt`（新） | 路由真值表（4×3 全组合 + 边界 API=30 ≥ 语义）+ 文案一致性（描述/指引/分工段） | 仓库 harness（JVM 直跑，不加载 Android 类） |
| H2 | `scripts/qa/PromptProtocolVerification.kt` | 补 W-9 分工段断言；截图描述断言改「无障碍优先」；指引断言加「无障碍通道」 | 防提示词/文案回退 |
| H3 | `scripts/qa/run-w9-screenshot-verification.sh`（新） | H1 的运行脚本（离线，Gradle 缓存内构件） | 与既有 harness 脚本同形态 |

## 沙箱二进制验证（第一道门，17/17 PASS）

`/tmp/w9-check`（kotlinc 1.9.25 + 保真 Android stub + awk 抽取真实提示词常量）：

- **A 路由真值表**（4 项）：常量=30；就绪×API30/31/34→主路径；就绪×API29/28/26→兜底；
  未就绪×任意 API→兜底。
- **B `takeScreenshotViaA11y` 四态**（真实服务源码 + 可编程 Java 桩）：
  onSuccess→位图；onFailure→null；takeScreenshot 抛异常→null（厂商 ROM 双保险）；
  API<30→null 且不触达 takeScreenshot（fail-fast，success 模式下误调会露馅）。
- **C `UiScreenshotTool.execute` 端到端六场景**：① a11y 就绪+API34+成功→ok/via=a11y；
  ② onFailure+捕获未开→capture_not_enabled（指引含无障碍通道）；③ API29→直接兜底；
  ④ a11y 未就绪+捕获中→via=media_projection（兜底照旧）；⑤ 双开仍优先 a11y；
  ⑥ PNG 真实落盘 + meta 尺寸。
- **D 文案/提示词**（3 项）：失败指引关键词+无障碍一句；描述「优先走无障碍通道」；
  提示词分工段在册且长度 ≤ `SYSTEM_PROMPT_MAX_CHARS`。

### 沙箱捕获的真机构筑坑（均已修，否则 CI assembleDebug 会挂）

1. `import kotlinx.coroutines.resume` —— 该扩展在 coroutines **1.8.1 是 internal**
   （javap 实证 `CancellableContinuationKt` 无此公开扩展；成员
   `resume(T, Function1)` 无默认参数）。改用成员形式
   `cont.resume(value, onCancellation = {})`。
2. 兜底路径误写 `svc.captureBitmap()` —— `captureBitmap` 在 `ScreenCaptureService` 上，
   无障碍服务无此方法（git 原版即如此，重写时笔误）。改回
   `ScreenCaptureService.activeInstance`。
3. `downscale` 丢 `src` 首参 —— `Bitmap.createScaledBitmap(src, w, h, filter)` 四参笔误。

### 沙箱环境两个非显然点（复跑时须知）

- kotlinc classpath 须用**绝对路径**（相对 `stub-out` 不被解析，表现为
  "unresolved reference: android" 的误导性报错）。
- 运行期须加 `-Dkotlinx.coroutines.fast.service.loader=false`：桩里有
  `android.os.Build` → `FastServiceLoaderKt.ANDROID_DETECTED` 判真 → 主调度器走
  Android/Test 硬编码工厂（均不存在）→ "Module with the Main dispatcher is missing"。
  禁 Fast 路径即回落 `java.util.ServiceLoader`（沙箱注册的
  `SandboxMainDispatcherFactory` 经 `META-INF/services` 生效）。

## 验收

- [x] 第一道门：沙箱二进制 17/17（含真实 `AgentAccessibilityService` /
      `UiScreenshotTool` / `AutomationBridge` 源码直编 + 真实提示词 awk 抽取）。
- [x] 第二道门：仓库 harness `W9ScreenshotVerification` 9/9（对沙箱产物 +
      `-Xfriend-paths` 跑 internal 访问；本机无 Android SDK，`CLASSES` 由沙箱 out 代替）。
- [ ] 第三道门：push + CI（assembleDebug）。
- [ ] 第四道门：APK dex 反验（特征：`takeScreenshotViaA11y` /
      `shouldPreferA11yScreenshot` / `VIA` 常量 "a11y" / "media_projection" /
      新提示词分工段子串）。
- [ ] 真机复测（见交付给用户的复测指南）。

## 真机复测要点

1. 开无障碍（不点「开始屏幕捕获」）→ 让 Agent 截图 → 日志 `meta.via=a11y`、无授权弹窗。
2. 关无障碍、开屏幕捕获 → 截图 → `via=media_projection`（兜底不退化）。
3. 双开 → 仍 `via=a11y`（主路径优先语义）。
4. 观察模型是否还每步截图（提示词分工段生效性）。
5. API 29 机型（若有）→ 自动走兜底，不崩。

## 遗留

- Shizuku `screencap` 作为更快一级：本批不上（需接 Shizuku SDK + 授权链，价值待真机
  a11y 通道数据再评估）。
- 截图 768px 缩略的 token 成本可再压（质量/尺寸参数化），列为 W-10 候选。
