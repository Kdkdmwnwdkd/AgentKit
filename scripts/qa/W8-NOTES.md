# W-8 交付笔记：熔断回合作用域 + 瞬时豁免 + app_launch 去诱导 + 截图审批缓存

> 触发源：用户真机日志（23:52–23:58）诊断。任务「打开设置把字体调到最大」失败，
> 用户体感「屏幕捕捉权限开了和没开没区别、更久更浪费时间」。

## 1. 根因（日志实证 + 代码定位）

任务实际闸门**不是**截图，是无障碍/ui_tree：

| 时间 | 事件 | 代码位置 |
|------|------|----------|
| 23:56:28 / 23:57:19 | ui_tree 两次 `service_not_ready`（无障碍未就绪） | UiAutomationTools.kt `notReady()` |
| 23:57:47 | 无障碍服务就绪（available=true） | — |
| 23:58:01 / 23:58:09 | **新回合** ui_tree 被熔断拦截 `requires_changed_call` | FailureGuard.check |
| 23:58:38 | ui_screenshot ok 932ms（截图通道一直是好的） | — |
| 23:58:30 | 模型转 shell_run 改字体 → permission_denied | 系统提示禁 shell 操控界面 |
| 23:58:56 | 用户停止，任务失败 | — |

三条代码级根因：

1. **熔断状态跨回合永久存活（P0 主因）**：`failureGuard` 是 `RealAgentLoop` 字段，
   `loop` 是 `ChatViewModel` 字段（ViewModel 生命周期 = App 进程），`clear()` 全仓
   **无人调用**，`newConversation()` / `switchConversation()` 也不重置——只有杀 App 能清。
   ui_tree 无参数 → 指纹恒定 → 两次瞬时 `service_not_ready` 后，本会话内所有 ui_tree
   全部被挡死（哪怕服务早就绪）。
2. **瞬时故障计入失败**：`service_not_ready` 是基础设施未就绪（可自愈），不是参数错误，
   却被 `onExecuted(failed=true)` 计数——熔断本意（阻止同参打转）被误伤。
3. **app_launch 文案诱导轮询**：早返回分支「建议用 device_foreground_app 确认」→ 模型
   连查 5 次 device_foreground_app + 重复 app_launch 两次，纯烧时间。

## 2. 修复批次（4 项）

| ID | 文件 | 改动 |
|----|------|------|
| P0-A | core/FailureGuard.kt | ① `TRANSIENT_FAILURE_CODES` + `isTransientFailureCode` 纯函数判据；② `onExecuted` 加 `transient` 三态（不计数/不清除/不拦截）；③ `resetForTurn()` 回合重置 |
| P0-A | core/agent/RealAgentLoop.kt | `send()` 入口 `failureGuard.resetForTurn()`（与 stopRequested 重置同款回合作用域模式） |
| P0-A | core/agent/ToolExecutor.kt | `onExecuted(..., transient = result.isError && isTransientFailureCode(result.code))`；修正「跨轮次持有」注释 |
| P0-B | tools/AppControlTools.kt | 早分支文案去掉 device_foreground_app 诱导，改引 ui_tree；未确认分支 ui_tree 前置 + 「复核一次不要反复」 |
| P1-C | ui/ChatViewModel.kt | `screenshotApprovedThisTurn` 按回合缓存 ui_screenshot 批准（send() 入口重置），消掉重复弹窗 |

**语义边界**：回合内跨批次保留熔断（同参打转防护不退化——T04 原意完整保留）；
瞬时故障（service_not_ready / service_unavailable / capture_not_enabled）三不；
换参数/换工具的自纠路径不受影响。

## 3. 验收

- 仓库 harness：`scripts/qa/T02T06Verification.kt` 新增 `testW8GuardTurnScope`（5 项：
  判据真值表 / 三不·不计数 / 三不·不清除 / 跨回合清除 / 重置后打转防护不退化）；
  `scripts/qa/AppControlVerification.kt` 更新 app_launch 两条文案断言（早分支不再含
  device_foreground_app；未确认分支 ui_tree 前置 + 禁反复）。
- 沙箱二进制验证（/tmp/w8-check，不进仓库）：11 个真实生产文件 + 6 个保真 stub +
  kotlinc 1.9.25 编译，driver 12/12 全绿——含**经真实 RegistryToolExecutor 的端到端**：
  - 日志复现修复：ui_tree 恒定指纹 + 6 次 service_not_ready → 6 次全真执行（修前第 3 次起挡死）
  - T04 不退化：io_error 恒失败仍 2 次真执行后第 3 次熔断
  - 混合时序：io_error×2 熔断后 service_not_ready×3 不解锁，换参数即放行
  - 新旧回合：旧回合瞬时×3 → resetForTurn → 新回合 io_error×2 才熔断
- CI：Build Debug APK。
- APK dex 反验：特征串（TRANSIENT_FAILURE_CODES 成员 / resetForTurn / 新文案）。

## 4. 真机复测要点（对应用户日志场景）

1. 不开无障碍直接发「打开设置把字体调到最大」→ 前两次 ui_tree service_not_ready 后
   **开无障碍**，继续对话 → ui_tree 应立即放行真执行（不再 requires_changed_call）。
2. app_launch 后模型应直接走 ui_tree，不再连查 device_foreground_app。
3. 同一回合内第二次起截图不再弹审批窗；新回合首次截图仍询问一次。

## 5. 遗留 / 下一批候选

- **截图通道升级（W-9 候选）**：无障碍 `takeScreenshot()` 主路径 + MediaProjection 兜底
  （对齐 Operit 三级链），进一步压缩「授权链 + 每次审批」的耗时。
- ui_tree kept==0 指引里仍提 device_foreground_app（上下文合理：树为空时的兜底建议），
  暂不动。
