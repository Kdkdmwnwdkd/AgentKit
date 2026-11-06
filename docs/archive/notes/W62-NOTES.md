# W-62：装运行时「点了没反应」——安装任务被页面作用域带走了（2026-10-27）

真机 1.397，Alpine 装 Java 时的一段日志：

```
13:55:30 容器命令开始：distro=alpine cmd=ALP=$(sed ...)
13:56:44 终端会话已启动：/system/bin/sh cwd=.../alpine/rootfs 80x24
13:56:46 终端会话已主动结束（code=137，kill 进程组的预期结果）
13:56:54 拒绝重复命令：distro=alpine 已在执行 84s
13:56:56 拒绝重复命令：distro=alpine 已在执行 86s
13:57:08 拒绝重复命令：distro=alpine 已在执行 97s
13:57:09 拒绝重复命令：distro=alpine 已在执行 98s
13:57:09 拒绝重复命令：distro=alpine 已在执行 99s
13:57:10 拒绝重复命令：distro=alpine 已在执行 99s
```

用户的原话是「没跑通」。实际不是命令失败——是**按钮在撒谎**。

## 一、根因：安装跑在 `rememberCoroutineScope()` 里

旧代码：

```kotlin
val scope = rememberCoroutineScope()
var installing by remember { mutableStateOf<String?>(null) }
...
scope.launch(Dispatchers.IO) {
    val (res, verified) = installAndVerify(context, id, cmd, missing)
    withContext(Dispatchers.Main) { installing = null; ... }
}
```

`rememberCoroutineScope()` 绑定当前组合，**页面一销毁就取消**。于是：

1. 用户点「安装 Java」→ 协程在 IO 线程跑，proot 起来了；
2. 用户切走（或点进容器终端）→ 组合销毁 → 协程取消、`installing` 归 null；
3. 但 `ContainerCommandRunner.run` 是**阻塞调用**，不响应协程取消——proot 继续跑；
4. 用户回到容器页 → 按钮又是可点的「安装 Java」→ 点了被 `EXIT_BUSY` 拒；
5. 再点，再拒。20 秒内 6 次。

**第 3 步是本批的关键**：协程被取消 ≠ 进程被终止。Kotlin 的取消是协作式的，
`ProcessBuilder` + `waitFor` 全程没有挂起点，取消标记对它无效。所以「UI 忘了」
和「进程还在跑」同时成立——数据库锁被一个没人认领的 proot 占着，用户看到的是
「点一次失败一次」。

## 二、第二条线索：code=137 的终端

`13:56:46 终端会话已主动结束（code=137）` 是用户在安装进行中点进了容器终端，
页面又起了一个 proot。`ContainerCommandRunner` 的 KDoc 里早就写过：

> 真机上多个 proot 同时跑时内存压力会触发 LMK，pty 会话被 SIGKILL（退出码 137）。

这次撞上了。用户在终端里看到的是黑屏一闪——他多半以为是「终端也坏了」。

## 三、修法

### 1. 安装任务搬到应用级（`ui/container/ContainerInstallController.kt`）

```kotlin
object ContainerInstallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val running: StateFlow<Running?>
    val report: StateFlow<Report?>
    val finished: StateFlow<Int>
    fun start(context, distroId, label, command, targets): Boolean
}
```

- 进程级单例，**与页面生死无关**：切走、锁屏、切 tab 都不中断；
- 页面只观察；重建后立刻恢复成「正在安装 Java… 120s」，而不是恢复成「可以再点一次」；
- 已用时按 `startedAtMs` 算差，**不是**「每秒 +1」——否则切回来又从 0 数，
  用户会以为「刚开始装」，再一次选择重试；
- 结果留在 `report` 里跨页面可读（失败原因切走再回来还在）。

### 2. 进终端前先看有没有命令在飞

`ContainerCommandRunner.busyFor(distroId): Long?`（返回已运行毫秒）。
容器页在 `onClick` 里先问一句，有则 Toast 说明并**不进**：

> 正在安装运行时（已 84s）。现在进终端会起第二个 proot、把正在装的那个顶掉——等它装完再进。

## 四、顺带修的两条（同一批真机日志暴露的）

### 4.1 429 的原因被吞了

日志：

```
ERROR [HTTP] 请求失败 code=429 body={"error":{"message":"请登录platform进行实名认证以获取更高rpm", ...}}
WARN  [LLM] 第 1 次调用失败，准备重试：LLM 流式请求失败 429
```

服务端已经写清楚了「去实名认证」。但横幅只显示「请求过于频繁（429）」，
用户只能反复重试——重试一百次还是 429。

新增 `extractServerHint(body)`（`LLMClient.kt`，纯函数）：从 `error.message` /
`error` / `message` / `msg` 四种形态里取一句话，截 60 字。
`retryReason` 的 429 分支现在显示「请求过于频繁（429）· 请登录platform进行实名认证…」。

**只取 message 字段**，不回显整段 body——里面有 request id，有的网关还会回显 key 片段。

### 4.2 点「停止」不该打 ERROR

```
13:54:12 INFO  [Chat] 用户请求停止运行
13:54:12 ERROR [LLM] 调用失败
        kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled
```

取消是**正常路径**（用户自己点的），打 ERROR 会让人以为刚才那下把 App 搞崩了。
`RealAgentLoop` 的 `catch (e: Throwable)` 现在先判 `CancellationException`，
走 `INFO「本次调用已取消（用户点停止）」`，其余才打 ERROR。

## 五、真机验收（1.398 起）

1. 容器页 → Alpine → 点「安装 Java」→ **立刻切到聊天页再切回来**：
   按钮应该仍显示「正在安装 Java… Ns」，N 继续往上跳（不是从 0 开始）。
2. 安装中点一下发行版卡片（想进终端）：应该弹「正在安装运行时（已 Ns）…」且不进。
3. 装完后再进终端：应该正常，不再 code=137。
4. 用 agnes 连续发两句：429 时横幅应带「请登录platform进行实名认证…」，而不只是 429。
5. 停止运行后，日志里不该再出现 `ERROR [LLM] 调用失败 JobCancellationException`。

## 六、遗留

- **安装不能中途取消**：现在只有「等它结束」。真要取消必须连同 proot 子树一起杀
  （`terminateTree` 已有），但杀完 apk 的锁要 3~5 秒才释放，这段时间再点仍会 exit 99。
  没有实际需求前不做。
- **安装进度仍是黑盒**：`OutputSink` 只保留尾部 64 KB，不往运行日志吐。
  用户能看到的只有秒数在跳。要做得更好得解析 `apk` 的输出算百分比，跨发行版不可靠。
