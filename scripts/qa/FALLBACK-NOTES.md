# Phase 2 DoD⑤ 交付说明：权限/能力申请一次走完降级链

> 负责人：寇豆码（Kou）· Phase 2 第五项 · 单模块 `:app`，包 `com.example.agentkit`
> 口径：零新增外部依赖 · 不新增 JUnit · 不推送/不碰网络 · 简体中文注释与日志。

---

## 1. 文件清单（新增 / 修改）

| 文件 | 状态 | 说明 |
|---|---|---|
| `core/capability/CapabilityChain.kt` | **新增** | 「能力链」核心：`Capability` 枚举、`BackendAvailability` 快照、`TraceEntry` 轨迹、`CapabilityRequest`、`CapabilityResolution`（`Selected`/`Unavailable`）、**纯函数** `resolveCapability` + 可观测旁路 `logTrace()`。零 Android 依赖 |
| `core/capability/CapabilityBridges.kt` | **新增** | 与已有骨架对接的**纯映射**：`TerminalBackendFactory.shellCapabilitySnapshot()`（复用 T07 的 `providersFor` + `isAvailable`）、`accessibilityCapabilitySnapshot()`（无障碍两态文案） |
| `automation/CapabilityAndroidBridge.kt` | **新增** | 无障碍 Android 侧适配：读 `AutomationBridge`（服务连接 + 总开关）→ `screenCapabilitySnapshot()` |
| `core/terminal/backend/TerminalBackendFactory.kt` | **改（+1 方法，obtain 复用之）** | 新增 `providersFor(identity, preferProot)` 公开访问器（降级顺序**单一真源**）；`obtain` 改用它，语义不变 |
| `ui/ChatViewModel.kt` | **改** | 抽出 `terminalBackendFactory` 字段；新增 `capabilityPlans` 状态流 + `refreshCapabilityPlans()`；在权限/开关/无障碍连接变化处触发刷新 |
| `ui/PermissionsScreen.kt` | **改** | 新增「能力就绪度」卡片 + `CapabilityPlanRow`：一次说清「走哪条路 / 缺什么 / 需要你做什么」 |
| `scripts/qa/FallbackVerification.kt` | **新增** | DoD⑤ 行为验证 harness（16 断言） |
| `scripts/qa/run-fallback-verification.sh` | **新增** | 离线编译 + 运行（形态学 `run-appcontrol-verification.sh`） |
| `scripts/qa/FALLBACK-NOTES.md` | **新增** | 本文件 |

---

## 2. DoD⑤ 五项要求 → 落地对照

1. **统一「能力链」抽象（纯函数）**：`resolveCapability(CapabilityRequest) → CapabilityResolution`。
   输入 = 想要的能力 + 各后端可用性快照（有序）；输出 = 选中路径 + 降级轨迹（每级为何不可用）
   + 不可用时的可操作提示。**不做 IO / 不读全局状态 / 不抛异常 / 同样入参结果恒相等**，故可在
   JVM 直接断言（harness 已证「两次结果相等」）。
2. **与已有骨架对接，不重复实现**：
   - shell：候选顺序来自 T07 `TerminalBackendFactory.providersFor`（proot → LocalUser），
     可用性来自带缓存 + `clearCache` 的 `isAvailable`——**未重新实现** rootfs/proot 判定；
   - 无障碍：`accessibilityCapabilitySnapshot(serviceConnected, masterEnabled)`，Android 侧读
     `AutomationBridge`（`isReady()` 拆成两个子状态），保持核心层零 Android。
3. **UI 一次走完**：权限中心页「能力就绪度」卡片逐条能力展示「将走：X」或「需处理：原因 +
   需要你：下一步动作」——文案具体到**下一步点哪里**（去系统「无障碍」设置 / 去容器页安装 rootfs）。
4. **不可用时不静默**：全链不可用 → `CapabilityResolution.Unavailable`，`message` 列出每一级原因，
   `hint` 汇总可操作提示（无提示则回退 `CAPABILITY_FALLBACK_HINT`，保证**恒非空**）；沿用 T02 hint
   语义，与 `app_stop` 的诚实降级一致。
5. **可观测**：`logTrace()` 把轨迹逐级落 `AgentLogger`（每级一行：候选名 + 可用性 + 原因），末尾一条
   裁决行（选中级 / 全链不可用）。**与纯函数解耦**：求解不改状态，可观测由调用方单独触发。

---

## 3. 关键设计裁决

- **纯函数边界**：`resolveCapability` 保持零副作用；日志（副作用）抽到 `CapabilityResolution.logTrace()`，
  兼顾「纯函数化、可脱离 Android 断言」与「降级轨迹进日志」两条要求。
- **入参拆分而非复用 `isReady()` 单布尔**：`AutomationBridge.isReady()` 是 bool，无法区分「服务未连接」
  与「总开关关闭」，而两者**用户动作不同**。故把 `serviceConnected`/`masterEnabled` 两个子状态显式传入，
  文案据此分别给出（harness 断言两种情况 message 与 hint 均不同）。
- **`providersFor` 单一真源**：把降级顺序从 `obtain` 内联逻辑提取为公开方法，`obtain` 与之共用，
  杜绝「能力链顺序」与「工厂顺序」两处漂移；语义、缓存行为不变（T07/T08 回归全绿）。
- **UNKNOWN 兜底**：空候选链与全链不可用走**同一失败语义**，`hint` 用 `CAPABILITY_FALLBACK_HINT`
  兜底，绝不返回空提示、绝不抛。

---

## 4. 自检证据

- **离线编译**：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks` → **BUILD SUCCESSFUL**（39 tasks）。
- **新 harness**：`bash scripts/qa/run-fallback-verification.sh` → **16 passed, 0 failed**。
  覆盖：链式降级（1 级可用→轨迹 1 条；1 级不可用→2 级，轨迹 2 条含原因）、纯函数确定性、
  全链不可用（明确文案 + 可操作提示、不抛）、空候选链（兜底提示、不崩）、
  与 T07 工厂联动（proot 不可用→选中 user，且与 `getHighestAvailable` 一致；proot 可用→选 proot；
  两级皆不可用→Unavailable 与 `getHighestAvailable=null` 一致）、无障碍两态文案不同、`logTrace` 不抛。
- **回归十一套（全绿）**：
  `run-verification`(32) · `run-t01-verification`(35) · `run-t01-probe`(11) · `run-t01-probe2`(7) ·
  `run-appcontrol-verification`(31) · `run-t07t08-verification`(35) · `run-t07t08-adversarial`(17) ·
  `run-markdown-verification`(45) · `run-branch-verification`(20) · `run-retry-verification`(9) ·
  `run-localmodel-verification`(19)。本次未遇到 `run-t01-probe2` 的 R9 竞态。

`IS_PASS: YES`

---

## 5. 遗留 / 说明

- 本批为**独立快照**，等统一推送（已有 9 批排队）；未推送、未碰网络。
- `CapabilityAndroidBridge.screenCapabilitySnapshot()` 依赖 `AutomationBridge`，无法在离线 JVM harness
  真跑；对其等价逻辑（`accessibilityCapabilitySnapshot`）已在 harness 直接断言，Android 适配仅一行转发。
- 后续若新增能力（如「屏幕捕获」「通知读取」），只需在 `Capability` 枚举加项 + 在
  `CapabilityBridges` 加对应快照函数 + 在 `refreshCapabilityPlans` 的 `when` 补分支，能力链裁决逻辑无需改动。
