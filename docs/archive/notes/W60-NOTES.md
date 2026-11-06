# W-60 · 全仓静态扫描（补 W-59 没覆盖的部分）

**日期**：2026-10-27
**起因**：W-59 只查了「我自己改过的 6 个文件」，被追问「全部查过啊」。

先把话说清楚：**这不是逐行读完 59,253 行。** 全仓 229 个 `.kt`、59k 行，逐行读既不可行也不可靠。
这轮做的是**按已知 bug 类型做模式扫描 + 逐命中判断**——能覆盖"已知类"，覆盖不了"逻辑写错"。

---

## 1. 扫描清单与结论

| # | 类别 | 命中 | 判定 | 处理 |
|---|---|---|---|---|
| 1 | `catch (Throwable/Exception)` 吞协程取消 | 45 处 | **11 处正确守卫，34 处无守卫**；其中 4 处包住挂起点 | 修 4 处 |
| 2 | `runCatching` 包住挂起点 | 20 处候选 | 7 处真吞挂起点，13 处误报/收尾路径良性 | 修 3 处 |
| 3 | 空 catch | 0 处 | — | — |
| 4 | `GlobalScope` | 0 处 | — | — |
| 5 | 资源未关（流/Socket/Cursor） | 21 个流 + 3 个 Socket | **全部有 `use` 或 `finally close`** | 无 |
| 6 | 报文长度未校验导致 OOM | 1 处 | **已有 16MB 上限**（`headerPayloadLength`） | 无 |
| 7 | 阻塞调用（`runBlocking`） | 5 处 | 4 处是注释/文档，1 处在非主线程且已声明取舍 | 无 |
| 8 | `Thread.sleep` | 3 处 | 1 处注释、1 处在 Shell 协议 25ms 轮询、1 处在抢锁重试 | 无 |
| 9 | `!!` 强解包 | 16 处 | **逐个看过，全部安全**（前置判空 / 在 `isFailure` 分支内 / 被 runCatching 兜住） | 无 |
| 10 | 硬编码凭据 / 未脱敏日志 / 明文端点 | 0 | — | — |

---

## 2. 修掉的 7 处

### 2.1 `UiAutomationTools`（3 处，影响面最大）

`runOnService`、`ui_tree`、`ui_click` 都是这个形状：

```kotlin
return try {
    val ok = withContext(Dispatchers.Main) { block(svc) }   // ← 挂起点
    ...
} catch (e: Exception) {
    shizukuFallback(...) ?: ToolResult.fail(code = "exception", ...)
}
```

`CancellationException` 继承自 `Exception`，用户点「停止」时抛的就是它 —— 被吞成 `code="exception"` 的
普通失败。**`runOnService` 这条更糟**：`catch` 里还会**再发一次 `shizukuFallback`**，
等于"取消之后还去动设备"。

而 `AppControlTools` 里 W-7 审核 F1 早就写过这个坑：

> `CancellationException` 继承自 Exception，一旦包进 `catch (e: Exception)` 会被吞成假失败，
> 破坏「取消绝不是失败」

**同一个知识，只在那个文件里落地了，没推广到 `UiAutomationTools`。** 现在补齐（三处都加
`catch (c: CancellationException) { throw c }` 前置）。

### 2.2 `ChatViewModel` 的两个导入函数

```kotlin
val result = runCatching {
    val bytes = withContext(Dispatchers.IO) { ... }   // ← 挂起点
    importRepo.installZip(bytes).getOrThrow()
}
if (result.isFailure) { _importStatus.value = "⚠️ 导入失败：..." }
```

退出页面 / ViewModel 销毁时，取消被写成「⚠️ 导入失败」，且协程以**成功态**结束（结构上不算取消）。
改为 `.onFailure { if (it is CancellationException) throw it }`。

### 2.3 `TerminalSessionPool.execute`

```kotlin
val sentOk = runCatching { send(...) && send(...) }.getOrDefault(false)
if (!sentOk) return ExecResult.failure(code = "send_failed", ...)
```

`send` 是挂起点，取消会被报成「命令写入失败」。同上处理。

### 2.4 `ShizukuShell.ensureBound`

```kotlin
runCatching { withContext(Dispatchers.Main) { Shizuku.bindUserService(...) } }
    .onFailure { pendingBind = null; return@withLock null }
```

取消被吞成"绑定失败"→ `ensureBound` 返回 null → 上层误判成"通道不可用"并降级。
改为清完 `pendingBind` 后重抛取消。

---

## 3. 看过但**没改**的

| 位置 | 情况 | 为什么不改 |
|---|---|---|
| `TerminalSessionPool` 的 collector | `runCatching { output.collect { ... } }` 吞取消 | **故意**：collector 是被 `cancelAndJoin` 主动取消的，吞掉反而避免抛到父作用域。属良性 |
| `ProcessBackendBase` / `AdbTerminalBackend` 的 `join()` | `runCatching { job.join() }` | 收尾路径，吞掉只影响一句日志 |
| `JobRunner` 的 `runCatching { withTimeoutOrNull { reader.join() } }` | 同上 | 同上 |
| `UiScreenshotTool` / `OcrTool` 的 `catch (e: Exception)` | 包的是**同步**方法 `takeScreenshotViaA11y()`，无挂起点 | 不能在这里抛取消，改了是噪音 |
| `AgentAccessibilityService` 的 7 处 | Service 回调（binder 线程），不是协程 | 无取消语义 |
| `SkillExecutor` 的 `runBlocking` | 在 Rhino 脚本线程，且已显式拒绝 `needsApproval` 工具避免死锁 | 注释里已交代取舍，属已知设计 |
| `AdbClient` 的 `ByteArray(len)` | `len` 来自报文头，但有 `0..16MB` 上限 | 已有防护（W-11 时期加的） |
| 16 处 `!!` | 逐个核过前置条件 | 全部可达性成立 |

---

## 4. 上一轮说错的一句话

W-59 里我写「40 处 `catch Throwable`，0 处重抛 `CancellationException`」——**统计口径错了**。
实际是：项目用的是更规范的写法，`catch (c: CancellationException) { throw c }` **前置**，
我的 grep 只往后看 3 行，所以一个都没算进去。

正确数字是 **11 / 45 有守卫**。`RealAgentLoop`、`ToolExecutor`、`AppControlTools` 都做得很好——
尤其 `RealAgentLoop.callWithRetry` 里那段：

```kotlin
catch (cancel: CancellationException) {
    // 取消立即上抛，绝不重试；finally 会立刻清掉「正在恢复」
    throw cancel
}
```

这是**正确示范**。问题不在"没人知道"，而在"知道的人写了，没推广到所有同类位置"。

---

## 5. 这套扫描覆盖不了什么

必须说清楚，否则「查过了」会被误解成「没问题了」：

| 覆盖得了 | 覆盖不了 |
|---|---|
| 已知类型的资源/并发/取消 bug | **逻辑错误**（算错、条件写反、边界差一） |
| 危险 API 的用法 | **状态机漏洞**（时序竞态，需要运行才暴露） |
| 泄漏、OOM、阻塞 | **产品语义错误**（功能实现了但不是用户要的） |
| 明显的安全疏漏 | **Compose 重组性能**（要看实际渲染） |

第 2 类（逻辑错误）只能靠读具体文件或跑起来。如果要把全仓的"逻辑正确性"也过一遍，
那需要按模块分专题读——比如「上下文预算的裁剪公式是否真的不会超」「审批分组的分组键是否可能撞」，
每个专题都值得单独一批。

---

## 6. 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| 全量 `run-all.sh` | 见 CI（本地 PASS=54 基线 + 本次仅加 catch 分支） |
| 行为影响 | 仅影响**取消路径**：ui_* 工具、技能导入、shell 写入、Shizuku 绑定 |

改动本身没有新增用例——这几处的正确行为是「取消时抛 CancellationException」，
在 JVM harness 里要模拟「协程取消 + 挂起点」成本不低，而 `AppControlTools` 已有同类先例。
CI 的 55 个 harness 能保证不回归，取消语义靠真机（点停止后工具不再多发一次操作）。
