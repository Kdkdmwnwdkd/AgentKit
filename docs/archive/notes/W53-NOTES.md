# W-53 批次记录：`run_shell` 接上无线调试 + 抽出 TurnRunner

> 批次时间：2026-10-26
> 起点：`7aca2f00`（W-52，CI 绿：run #387）
> 触发：W-51/W-52 收尾时留下的两条待办——「`run_shell` 还没走 ADB」与「`send`/`regenerate` 两份同构收尾」

---

## 一、`run_shell` 接入无线调试（第三级后端）

### 为什么不是「把 `AdbSession.exec` 包一层」

最容易想到的做法是写个后端，`send()` 里调 `AdbSession.exec`。但这会**悄悄破坏语义**：

```
cd /tmp          ← exec 起一个进程，cd 在这个进程里生效，进程退出即丢
ls              ← exec 又起一个进程，cwd 还在原地
```

而 `TerminalSessionPool` 是**持久 shell + 哨兵**模型：写入命令 → 写入
`echo "<哨兵>$?"` → 从输出流等哨兵。它要求 `cd`、环境变量、`export` 跨命令保持。
`LocalUserBackend` 和 `ProotBackend` 都是「一条常驻 shell 进程」，所以能做到。

所以给 `AdbClient` 加了 `openInteractiveShell()`：发 `OPEN("shell,v2,raw:")`
（**不带命令**），adbd 会起一个常驻 `sh`，之后持续写 stdin（stream id 0）、读事件。
这样三级的语义才完全一致。

| | proot 容器 | **无线调试** | 本机 user |
|---|---|---|---|
| 身份 | fake root | **uid 2000** | App uid |
| 能改系统设置 | 不能（只改容器内） | **能** | 不能 |
| 有包管理器 | 有（apk/pacman） | 无 | 无 |
| 首命令开销 | 中 | 中（TCP+OPEN） | 低 |

排序按**能力**而不是速度：`proot → adb → user`。shell 工具的痛点是权限不足，
所以最快的 user 排最后。

### 三个实现细节

1. **独占连接**：一条 `AdbClient` 只能承载一条交互式 shell（单 socket、单读循环）。
   所以 `AdbTerminalBackend` 自己建连接，**不走** `AdbSession` 的单连接——否则
   `ui_tree` 的 dump 会和常驻 shell 抢同一个 socket。
2. **读超时不等于会话结束**：`read(200ms)` 超时后继续轮询，只有连接关闭（null）
   或退出帧才收工。把超时当结束会让「命令在跑但暂时没输出」被误判成 shell 退出，
   哨兵永远等不到。
3. **分层**：`AdbBackendProvider` 在 `automation.adb`，而工厂在 `core`。让 core
   反向依赖 automation 会破坏分层，所以 `createTerminalBackendFactory` 加了
   `extraProviders` 参数，由 `ChatViewModel` 注入。

## 二、TurnRunner：两份逐行同构的仪式合成一份

### 改动前

`send()` 与 `regenerate()` 各有一份开场（6~8 行状态复位）和一份收尾，代码里出现过
**6 次**「与 send 同策略」「send 同套保险」——用注释去同步两份本该只有一份的代码。
而且已经分叉：

| | send | regenerate |
|---|---|---|
| 收尾 `reloadActiveMessages()` | 有 | 无（在 onSuccess/onFailure 里各自调） |
| 收尾 `AgentRunService.stop()` | 有 | 无（它不启动前台服务） |
| 开场草稿值 | `null` | `""` |

### 改动后

```kotlin
private inner class TurnRunner {
    fun begin(owner: String, placeholderDraft: Boolean)
    fun finish(rebuildMessages: Boolean)
}
```

三处差异全部变成**显式参数**，不再是两份代码的隐式分歧：

- `placeholderDraft`：regenerate 需要空草稿占位（`""`），send 不需要（`null`）。
  **这不是历史遗留**——UI 用 `streamDraft != null` 决定是否多渲染一行草稿气泡；
  send 时用户消息气泡本身就是「在生成」的占位，regenerate 时没有新气泡得自己造一个。
- `rebuildMessages`：send 需要重建（补变体游标元数据），regenerate 不需要。

`AgentRunService.stop()` 留在 send 的 `finally` 原位（它只属于 send），
`finish()` 从它之后开始，**执行顺序与改动前逐行一致**。

### 为什么不做成独立类

这些字段全是 ViewModel 的私有可变状态（`draftBuffer`、`runOwnerConvId`、
`_generatingConvId`…）。独立类要么暴露 8 个 setter，要么把状态搬出去再加一堆回调，
比 inner class 更绕。inner class 直接读写宿主字段，零额外耦合。

## 三、验证

| 项 | 结果 |
|---|---|
| `FallbackVerification` | **24 / 24**（新增 3 条 W-53 用例：proot 不可用 → 选中 adb、proot 可用时 adb 不抢、adb 是唯一可用） |
| `Spake2Verification` | 15 / 15（未回归） |
| `AdbDiscoveryVerification` | 11 / 11（未回归） |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |

顺带修掉两条被新档位打脸的旧断言：`shellCapabilitySnapshot` 现在是 4 个候选
（shizuku 诊断 → proot → adb → user），原断言写死 3 条。

## 四、没验的部分

1. **ADB 交互式 shell 未真机验**：需要配好对、然后让 agent 跑 `cd /tmp && pwd`
   这类跨命令依赖 cwd 的操作。`openInteractiveShell` 的协议路径与 W-51 的 `exec`
   共用同一套握手，但「持久 sh + 多帧读写」是首次跑。
2. **TurnRunner 只做了编译验证**：它动的是 UI 状态机，行为要靠真机看
   「发消息 → 停止 → 再发」「重新生成 → 切会话 → 切回」这几条路径。
   编译器能保证的只有「状态字段没漏清」，保证不了「清早了一步」。
3. **ADB 会话无 PTY**：`Ctrl-C` 不是真中断（与 `LocalUserBackend` 同限制）。
   池里的超时处置是「先发 `\u0003`，300ms 后关后端」——对 ADB 而言等价于直接关，
   代价是那条 shell 上正在跑的命令会变成孤儿，直到 `adbd` 回收。
