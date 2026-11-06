# W-55 批次记录：会话池的后端回落（补上 W-54 说的那一步）

> 批次时间：2026-10-26
> 起点：`604e0ba5`（W-54，CI 绿：run #390）
> 触发：W-54 里写的「彻底方案」第 2 步——`TerminalSessionPool.open()` 在 `start()` 失败时
> 不回落，直接报错

---

## 一、为什么先做这一步，而不是先做 Shizuku 骨架

要「两个世界都要」（Shizuku 的 uid 2000 + 会话复用的 cwd 保持），需要两件事：

1. `ShizukuTerminalBackend`（用 `Shizuku.newProcess(["sh"])` 起持久 shell）
2. `open()` 支持「候选逐个 start，失败回落」

第 1 件必须真机验证：W-36 踩过 `waitFor(timeout)` 在 Shizuku 的远端 Process 代理上直接抛异常
（真机日志里 `echo hello` 6ms 报 timeout，模型被骗着重试 12 次）。流读写看起来没事，但
「看起来没事」和「真机跑得住」是两回事。

而第 2 件是**纯错误处理改进**：不引入新后端、不改成功路径，只在 `start()` 失败时多试一档。
它还能独立产生价值——rootfs 被删、无线调试端口变了，这些情况下首选后端「能构造、起不来」，
改动前用户看到的是 `start_failed`，而本机 shell 明明可用。

所以顺序是：先做回落（可测、零新风险），真机确认后再加 Shizuku 后端。

## 二、改了什么

### `TerminalBackendSource.obtainCandidates()`（新）

```kotlin
fun obtainCandidates(identity, preferProot): List<TerminalBackend> =
    listOfNotNull(obtain(identity, preferProot))   // 默认实现 = 单候选
```

默认实现让既有实现（含 harness 里的 lambda 假 source）不用改——这点很重要：
改接口最容易顺手打碎一堆测试。

`TerminalBackendFactory` 覆写它：按 `providersFor` 的顺序把所有 `isAvailable` 的候选都
`create()` 出来。「能构造」不等于「能启动」，所以要把整个列表交给池层。

### `TerminalSessionPool.open()`（重写 1~4 段）

改动前：

```
obtain() 取一个 → 查槽位 → start() → 失败就 return start_failed
```

改动后：

```
取全部候选 → 对每一档：查它的槽位（有存活会话就复用）→ start()
            起不来就 close() 并 continue，起来了就用它
            全部失败 → start_failed，消息里列出「试过哪几档」
```

三个细节：

1. **复用检查放在循环内**：回落到第二档时，要按第二档自己的 `(backendKey, alias)` 查槽位，
   不能沿用第一档的 poolKey——否则会把 `byAlias` 指错，留下悬空 entry。
2. **未选中的后端要 `close()`**：`create()` 出来的对象即使没 start 也持有资源（ADB 的实例
   持有配置、proot 实例持有路径），不关就是泄漏。
3. **失败消息列出实际尝试过的档位**：`已尝试：proot → adb-wireless` 比「启动失败」有用得多——
   用户能直接看出是哪一档的环境坏了。

## 三、验证

| 项 | 结果 |
|---|---|
| `T07T08Verification` | **39 / 39 通过**（新增 4 条：首选失败→落 user、三档连挂两档→第三档接手、全挂→`start_failed` 且列出两档、空候选→`no_backend`） |
| 全量 `run-all.sh` | 见下（CI 为准） |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |

新增用例里第一条顺带断言了「回落后的会话**真的能跑命令**」——不只是 open 成功，
而是 `pool.run(handle, "echo hi")` 也 ok，避免「返回了句柄但后端是死的」。

## 四、下一步（真机后）

真机确认以下两条后，再上 `ShizukuTerminalBackend`：

1. 断开无线调试（设置里「断开并清除凭据」）后，`shell_run` 应**自动落到本机 shell**，
   而不是报「所有后端都启动失败」——这是本轮改动的直接收益，可观测点是
   `[TerminalSessionPool] 后端启动失败，回落到下一档：adb-wireless` 这行日志。
2. 删掉 rootfs（容器页卸载）后再跑 `shell_run`，同样应回落到 adb 或 user。

如果这两条都正常，加 Shizuku 后端就只剩「它自己能不能起得来」一个风险点，
而那时即使起不来，也会被回落机制兜住。
