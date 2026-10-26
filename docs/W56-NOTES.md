# W-56 批次记录：Shizuku 成为会话后端（uid 2000 + 持久 shell）

> 批次时间：2026-10-27
> 起点：`179eebbd`（W-55，CI 绿：run #391）
> 触发：W-55 说的下一步——回落机制就位后，把 Shizuku 从「一次性通道」升级成「会话池后端」

---

## 一、这一步解决什么

W-54 已经说清：`shell_run` 在 Shizuku 就绪时默认走**一次性通道**，`cd` / `export` 不跨调用保持。
W-54 只是把话说清楚（返回文案里提示），没解决问题。根因是 Shizuku 从没被做成 `TerminalBackend`。

现在做了。`newProcess(arrayOf("sh"))` 返回的 `Process` 是**可以长期持有**的——
它有 stdin / stdout 流，跟 `ProcessBuilder` 起的一样。

> W-36 踩过的坑是**定时 `waitFor`**：Shizuku 的远端 `Process` 代理上它会立刻抛异常
> （真机日志 `echo hello` 6ms 报 timeout，模型被骗着重试 12 次）。那是 `waitFor` 的问题，
> 不是流的问题。新基类用 `exitValue()` 轮询，天然避开。

## 二、改了什么

### 1. `ProcessBackendBase`（新，161 行）—— 两条通道共用进程生命周期

`LocalUserBackend` 原来 133 行，其中 100 行是「起进程之后」的事：读流 → 投递 Channel、
写 stdin、close 时 destroy + 关 channel、退出码 await。Shizuku 后端这些东西**一模一样**，
复制一份就会变成「读循环两份、close 语义两份」，日后必然分叉。

抽成抽象基类后：

| | 改前 | 改后 |
|---|---|---|
| `LocalUserBackend` | 133 行（含全部读循环） | **46 行**（只回答「进程从哪来」） |
| `ShizukuTerminalBackend` | — | **58 行**（同上） |
| 读循环 / close / 退出码 | 1 份（在 LocalUserBackend 里） | 1 份（在基类里） |

### 2. `ShizukuProcess`（新）—— 反射收在一处

`Shizuku.newProcess` 从 API 13 起是 private，只能反射。原来这段反射写在
`ShizukuShell.exec` 里；现在 `ShizukuShell.exec`（一次性）与 `ShizukuTerminalBackend`（持久）
都调 `ShizukuProcess.open(argv)`，反射代码只有一份。

### 3. 候选链变成四档

```
proot（完整 Linux 工具链）
  → shizuku（uid 2000，binder，无 TCP 维持）
  → adb-wireless（uid 2000，不依赖第三方 App）
  → user（App 身份，最快）
```

同是 uid 2000，Shizuku 排前面是因为它走 binder：探测微秒级、不占 TCP 连接、不依赖
adbd 的端口（无线调试每次重开都会换端口，最容易失效）。

### 4. 能力快照去重

`shellCapabilitySnapshot` 原本手动把 shizuku 放在首位（作为「诊断条目」），现在它同时也在
`providersFor` 里——不去重就会出现两条 shizuku。加了个 `filter`：**展示仍用实时状态把它放第一，
provider 列表里跳过它**。

这样「展示顺序」与「执行顺序」不一致（展示：shizuku 第一；执行：proot 第一）。这是有意的：
展示关心「用户该去修哪条通道」，执行关心「哪条能力最强」。

## 三、验证

| 项 | 结果 |
|---|---|
| `T07T08Verification` | **39 / 39**（`LocalUserBackend` 重构后，真实的 `/bin/sh` 端到端用例全过：cwd 保持、环境变量保持、僵尸回收、并发不交织） |
| `FallbackVerification` | **27 / 27**（新增 3 条 W-56：候选顺序四档、proot 不可用时选 shizuku、快照里 shizuku 只出现一次） |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |

`LocalUserBackend` 的重构是被现有 harness **真正覆盖**的：T07T08 里有 8 个用例直接用它跑
真实 `/bin/sh`（`cd && pwd`、`export` 跨调用、并发写、僵尸会话）。抽基类后这些全过，
说明「进程生命周期搬走」没有改变行为。

## 四、没验的（真机才有的）

1. **Shizuku 的持久 shell 能不能起得来**。这是唯一的新风险点，且只能真机验：
   在 Shizuku 已启动的机器上跑 `shell_run`（不传 identity），看日志有没有
   `[ShizukuTerminalBackend] Shizuku 后端已启动（uid 2000，持久 shell）`。
   - 起得来 → `cd /tmp` 再 `pwd` 应该输出 `/tmp`（这才是 W-54 真正解决）
   - 起不来 → 回落机制接手（`[TerminalSessionPool] 后端启动失败，回落到下一档：shizuku`），
     用户只是权限降级，不会「用不了」
2. **默认路径仍是 privileged 一次性**。`shouldUsePrivilegedShell` 这次**没动**：
   `identity` 不传时仍优先走 `ShizukuShell.exec`（一次性）。所以本轮的收益要先满足
   「显式传 `identity=user` 开会话」才吃到。

   为什么不动：一次性通道现在**能用**，改成默认走池 = 把主路径押在未真机验证的新后端上。
   等第 1 条真机确认后，下一批再把默认切过去（那时才真正实现「两个世界都要」）。

## 五、下一步

真机确认 Shizuku 后端能起之后：

1. 改 `shouldUsePrivilegedShell`：`identity=null` 时返回 false（一律走池），
   保留 `identity=shizuku` 作为显式的一次性兜底；
2. 那时 `shell_run` 默认就是「uid 2000 + 持久 shell」，`cd` 跨调用生效，W-54 的提示语
   也就自然消失了（不再有那个坑）。
