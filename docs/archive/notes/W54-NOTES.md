# W-54 批次记录：一次性通道的语义误导（`shell_run` 的 cd 不生效）

> 批次时间：2026-10-26
> 起点：`06642667`（W-53，CI 绿：run #388）
> 触发：W-53 收尾复核时发现——「`shell_run` 默认走 Shizuku 一次性通道」与「工具描述写的
> 会话复用语义」不一致

---

## 一、发现

`ShellTool.shouldUsePrivilegedShell()` 的规则是：

| 条件 | 走哪 |
|---|---|
| 带 session 句柄 | 走会话池（proot / adb / user） |
| `identity=shizuku` | 走 Shizuku 一次性 `exec` |
| **`identity` 未指定 + Shizuku 就绪** | **自动走 Shizuku 一次性 `exec`** |
| `identity=user` | 走会话池 |

问题在于一次性通道是「一条命令一个远端进程」：

```
shell_run("cd /sdcard")   → 新进程里 cd，进程退出即丢
shell_run("ls")           → 又一个新进程，还在默认目录
```

而工具描述写的是「复用同一 session 句柄可保持工作目录与环境变量（多轮 cd/export 生效）」。
**在 Shizuku 就绪的机器上（也就是绝大多数用户），默认路径根本拿不到 session 句柄**——
模型按描述以为目录已经切好，后续命令全跑错位置，还以为是路径写错。

这与 W-36 那个坑同类：不是执行错，是**语义被误读**。

### 顺带纠正 W-53 的一处说法

上一轮说「`run_shell` 接上了无线调试」。准确范围是：**只有会话池路径**（传 session、
或 Shizuku 不可用、或 `identity=user`）才会走到 `proot → adb → user` 这条链。
Shizuku 就绪时默认不经过池，ADB 后端不会被自动选中。

## 二、这一轮改了什么（低风险部分）

1. **工具描述改准确**：`shell_run` 的 description / systemPrompt 明说
   「identity=shizuku 是一次性执行，不返回会话句柄，cd/export 不跨调用保持」。
2. **命中状态变更命令时，在返回文案里补一句**（`mutatesShellState`）：

   ```
   通道 Shizuku（uid 2000）退出码 0
   注意：本通道为一次性执行，本次的 cd / export 只在本次生效，
   下一次调用回到默认工作目录。需要连续操作请不带 identity 开一个会话再复用句柄。
   ```

   只对 `cd` / `pushd` / `popd` / `export` / `unset` / 变量赋值前缀生效——
   普通命令（`ls` / `cat` / `pm list`）不加，否则每条都啰嗦。

3. **修掉我自己写出来的一个同类误导**：初版文案写的是「需要连续操作请**不带 identity**
   建立会话」——可不带 identity 且 Shizuku 就绪时，恰恰又走一次性通道，根本拿不到句柄。
   已改成「显式传 `identity=user`」：那是唯一保证进会话池的写法
   （`shouldUsePrivilegedShell` 对非 null 且非 shizuku 的 identity 返回 false）。

## 三、为什么没直接「把 Shizuku 做成会话后端」

技术上可行（`Shizuku.newProcess(["sh"])` 拿到的 Process 有流），W-36 踩的坑是
`waitFor(timeout)` 抛异常，不影响流读写。但有个**回落缺口**：

`TerminalSessionPool.open()` 的流程是「`source.obtain()` 选定一个后端 → `start()`」，
**`start()` 失败就直接报错，不会再试下一个候选**。所以如果新写的 `ShizukuTerminalBackend`
在真机上起不来，默认 shell 会从「现在能用的一次性通道」变成「完全不可用」。

在真机验证之前做这个改动，是用「确定的可用」换「不确定的更好」——不做。

### 更彻底的方案（待决策）

要让 Shizuku 用户同时拿到 uid 2000 + session 复用，需要两步：

1. `ShizukuTerminalBackend`（持久 `sh`，走 pool）
2. `TerminalSessionPool.open()` 支持「候选逐个 start 试」，失败回落下一个

第 2 步是通用改进（对 ADB 后端同样有价值：配对失效时自动落到 user），
但改的是所有 shell 调用的主路径，需要真机验。

## 四、验证

| 项 | 结果 |
|---|---|
| `ShellStateHintVerification`（新增） | **9 / 9 通过**：cd / 单独 cd / export / 变量赋值 / 组合命令 判为 true；`ls` / `cat` / `pm` / `echo cd` / `echo A=1` / `CD` / `find -name cd` 判为 false |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |

## 五、没验的

- 提示语出现在真实 `shell_run` 返回里的样子——需要真机跑一条 `cd /data/local/tmp` 看返回文案。
- 描述改动对模型行为的影响——只能靠观察：下一次 agent 要做多步操作时，
  是否改成「先开 session 再复用」，而不是连续发单条 `cd`。
