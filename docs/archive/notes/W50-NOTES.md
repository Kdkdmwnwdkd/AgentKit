# W-50 批次记录：界面操作不再只靠无障碍（调试通道兜底）

> 批次时间：2026-10-26
> 起点：`027fffe`（W-49，CI 绿）
> 触发：真机反馈「只开了无线调试 / Shizuku，没开无障碍，界面类工具全灭」

---

## 一、问题：`control_screen` 只有一条候选

真机日志（v1.384）：

```
[CapabilityChain] [run_shell]     候选「调试通道（Shizuku）」可用=true → 选中（降级 1 级）
[CapabilityChain] [control_screen] 候选「无障碍服务」可用=false，原因=无障碍服务未连接
[CapabilityChain] [control_screen] 全链不可用：无法操作手机界面
```

对比很刺眼：**同一个「调试通道」，`run_shell` 认得它，`control_screen` 不认**。

| 项 | 内容 |
|---|---|
| 现象 | 用户已有可用的 shell 提权通道（Shizuku / 无线调试），但只要没开无障碍，`ui_tree` / `ui_click` 就直接失败，模型连"屏幕上有什么"都读不到 |
| 根因 | `Capability.CONTROL_SCREEN` 的候选链只挂了一条 `accessibility`，`CapabilityBridges` 里还写着"无更低一级可用路径"。这个判断不成立——shell（uid 2000）能做 `input tap/swipe/text/keyevent`、`uiautomator dump`、`screencap` |
| 补充根因 | 更早的 W-18 其实已经给 `ui_tap/ui_long_press/ui_swipe/ui_input` 加了 `shizukuFallback`，但**唯独漏了读界面这条**。而 tap 类兜底要生效，前提是模型已经从 `ui_tree` 知道点哪里——读不到树，后面全是空转 |

## 二、这一批修了什么

### 1. 新增 `automation/ShellUiBackend.kt`（调试通道的界面后端）

| 能力 | 实现 |
|---|---|
| 读界面结构 | `uiautomator dump` → 解析 XML → 产出与无障碍快照**同形**的 JSON（text/desc/id/cls/bounds/clickable/scrollable/editable/checked） |
| 按文本 / id 点击 | 复用 `UiNodeMatcher`（归一化 / 精确 / 包含 / 前缀 / 可点祖先上溯），行为与无障碍路径一致，候选列表也一致 |
| 系统键 | `input keyevent KEYCODE_HOME/BACK/APP_SWITCH` |
| 就绪判定 | `ShizukuShell.status() == READY`，与 `run_shell` 的候选探测同源 |

解析用 `XmlPullParser` 而不是正则（属性里有中文与引号）；`parentIndex` 由栈维护，`UiNodeMatcher` 的祖先上溯全靠它。

### 2. 工具层补兜底

- `ui_tree`：无障碍不可用 → `ShellUiBackend.dumpTree()`，返回文案明确写"调试通道、比无障碍慢 1~2 秒"，不假装等价。
- `ui_click`：同样落到 shell；失败时仍返回候选列表（与无障碍路径同一套 hint）。
- `ui_key`：补 `input keyevent` 兜底（原先这里连兜底都没有）。
- `notReady()` 文案：从"去开无障碍"改成"无障碍 **或** 调试通道，任选其一"。

### 3. 能力链补第二级候选

```kotlin
fun controlScreenSnapshot(serviceConnected, masterEnabled, shizukuAvailable) = listOf(
    accessibilityBackendAvailability(...),          // 首选：实时、免 dump
    shellBackendAvailability(KEY_SHIZUKU, ...)      // 兜底：uiautomator dump
)
```

`accessibilityCapabilitySnapshot` 保留但标 `@Deprecated`（harness 不破坏），新调用点统一走 `controlScreenSnapshot`。

## 三、验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `scripts/qa/run-fallback-verification.sh` | **18 / 18 通过** |
| 新增用例 | ① 无障碍缺失 + 调试通道可用 → 选中 Shizuku、轨迹 2 条；② 两条都不可用 → hint 同时给出两种修复路径 |

> 端到端（真机点一下）需要在有 Shizuku 的设备上验：模拟器里没有 Shizuku，只能验到"无障碍缺失时不再硬失败、文案正确"。

## 四、未做 / 下一批

1. **无线调试（`automation/adb/AdbClient.kt`）仍是孤岛**：202 行协议客户端（配对 + shell 流）全仓没有调用方。要在"没有 Shizuku 但开了无线调试"的设备上也能兜底，得把它包成第二个 shell 后端并补配对 UI 的接线。
2. **`uiautomator dump` 的 ROM 差异**：部分国产 ROM 对 shell 跑 uiautomator 有 SELinux 限制（返回空/报错）。当前行为是如实失败并提示开无障碍，不静默成功。
3. **读树性能**：dump 单次 1~3 秒，多步任务会明显变慢。若后续要优化，可考虑 `uiautomator dump --compressed` 或直接走 `AccessibilityService` 的事件缓存。

## 五、改动清单

| 文件 | 改动 |
|---|---|
| `automation/ShellUiBackend.kt` | 新增：dump / 解析 / 匹配后点击 / 系统键 |
| `automation/UiAutomationTools.kt` | `ui_tree`、`ui_click` 补兜底；`ui_key` 补 keyevent；`notReady()` 文案 |
| `automation/AgentAccessibilityService.kt` | `UiNodeSummary` 增 `cls/scrollable/editable/checked`（默认值，两条路径共用一份节点模型） |
| `automation/CapabilityAndroidBridge.kt` | 改用 `controlScreenSnapshot`，带上 Shizuku 就绪状态 |
| `core/capability/CapabilityBridges.kt` | 新增 `controlScreenSnapshot`；旧单候选函数标 deprecated |
| `core/capability/CapabilityChain.kt` | `CONTROL_SCREEN` 语义与注释更新为两级候选 |
| `scripts/qa/FallbackVerification.kt` | 断言切到新函数 + 两条 W-50 新用例 |
