# W-17 批次记录：定时任务

> 依据：《四家对照-我们差什么》缺口 #3（Operit `WorkflowExecutor/Scheduler/Worker`）。
> v1 只做「单任务定时」：一句话任务 + 一个时间规则，不做可视化节点图。

## 交付

| 文件 | 作用 |
|---|---|
| `workflow/Workflow.kt` | 模型 + **纯函数** `isWorkflowDue` / `describeTrigger` / `normalized` |
| `workflow/WorkflowStore.kt` | `workflows.json` 原子写 + `.bak` 回退 |
| `workflow/WorkflowWorker.kt` | 15 分钟周期巡检（全部任务共用一个 WorkRequest）+ 一次性立即触发 |
| `workflow/WorkflowNotifier.kt` | 通知渠道 + PendingIntent 携带 `workflow_id` |
| `tools/WorkflowTools.kt` | `workflow_create/list/delete/toggle` |
| `ui/WorkflowScreen.kt` | 列表 / 开关 / 删除 / 立即运行（抽屉新增「定时任务」） |
| `MainActivity.kt` | onCreate + onNewIntent 都消费 `workflow_id` |
| `ui/ChatViewModel.kt` | 注册工具、幂等排程、`runWorkflowNow` 走前台对话 |

## 核心取舍：不在后台硬跑 Agent

Android 上无障碍、截图、前台应用都依赖界面状态；后台跑 Agent 得到的结果不可信。
所以 Worker 到期后**发通知 + 标记**，点开即在前台执行（用户能看到每一步、随时叫停）。
这比「假装后台跑完了」诚实且可用。

## 其他设计

- **到期判定与「今天的目标时刻」比**，不与「上次 + 24h」比：手机可能关机几天，
  后者会在开机瞬间连续补跑多次；前者最多补当天一次。
- **一个周期任务管所有工作流**：增删改无需重新排程，重启后 WorkManager 自动恢复。
- **创建走对话**（模型调 `workflow_create`），界面只做管理——表单填五个字段不符合对话式产品。

## 验证

- harness **11/11 PASS**（daily 5 / weekly 2 / interval 2 / 归一化 1 / 文案 1），
  时间与日历全部注入，任何时区/日期跑结果一致。
- CI run 190（`cd1e7d7`）Build + Publish **success**（新增 WorkManager 依赖编译通过）。
- APK 反验全绿：`Workflow`/`WorkflowStore`/`WorkflowWorker`/`isWorkflowDue` +
  四个 `workflow_*` 工具 + 文案「定时任务」「立即运行」入池。
- 产物：30,588,948 B（+445KB，WorkManager 依赖），sha256 `4f611211…8740f`。

## 已知边界

- 后台只提醒不执行（原因见上）；点通知后若 App 正在跑别的任务，会忽略本次（日志有记录）。
- 未授予通知权限时提醒静默失败（日志 warn）；界面暂无「去开通知权限」引导，下批补。
- 不做条件触发（「连着 Wi-Fi 时」「电量 > 50% 时」），需要时再加约束。
