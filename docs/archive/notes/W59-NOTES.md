# W-59 · 自查：W-50~W-58 这九批改动的代码审查

**日期**：2026-10-27
**范围**：这两天动过、且**没有被真机验证过**的路径——ADB 通道、会话池、进程后端基类

不是泛泛的代码风格审查：只查「我自己写/改的地方，有没有真会出问题的」。八个问题，七个已修。

---

## 1. 高危：ADB 读帧会破坏帧同步（已修）

### 现象

`ShellStream.read(timeoutMs=200)` 的设计是「200ms 没输出就当暂时空闲，继续轮询」。
但 `readPacket()` 是在 `input.read` 循环里读 header + payload 的：

```kotlin
// 旧实现
while (read < header.size) {
    val n = input.read(header, read, header.size - read)   // ← 超时抛在这里
    read += n
}
```

**一旦中途超时，已消费的字节就丢了。** 调用方按约定把 `SocketTimeoutException` 当"暂时没输出"，
下一次 `readPacket()` 从**半帧**开始读 → magic 校验失败 → 读循环 break → **整条 shell 报废**。

### 触发条件并不苛刻

单帧 payload 上限 256KB。`dumpsys`、`cat` 大文件、`logcat` 都是大块输出，
慢速链路或设备繁忙时读不完 200ms 很正常。而 ADB 通道本来就是"手机算力吃紧时才走的那条"。

### 修法：两阶段超时

```kotlin
// 阶段 1：只等「下一帧是否存在」，此时未消费任何字节，超时安全
socket.soTimeout = firstByteTimeoutMs
first = input.read()
if (first < 0) return null

// 阶段 2：帧已开始，必须读完 —— 用 readTimeoutMs（30s）
socket.soTimeout = readTimeoutMs
```

阶段 2 再超时就说明连接真坏了，包成 `IOException` 让调用方**停止**而不是继续轮询。
另加一个判断：header 阶段 `read == 0 && first < 0` 时原样抛 `SocketTimeoutException`
（一个字节都没读，流状态未变，安全重试）——否则一次性 `exec` 路径会被误判成连接损坏。

### 证据：先复现再修

新增 `AdbShellFrameVerification`：起本地 TCP 服务端扮演 adbd，完整走
CNXN → AUTH → OPEN → WRTE → CLSE，然后把一个 WRTE 报文**拆成两半、中间停 350ms**（> 200ms）。

| 版本 | 帧头分片 | 载荷分片 | 完整帧 |
|---|---|---|---|
| 旧实现（临时回滚验证） | **FAIL** | **FAIL** | PASS |
| 修复后 | PASS | PASS | PASS |

旧实现跑出的日志正是 `IOException: adb 帧读取超时（已开始接收但未收完），连接帧同步已损坏`。
完整帧仍 PASS 说明正常路径没被拖慢——这正是"测试真的在测东西"的证据。

> 写这个 harness 时我自己栽了两次：① 少写 shell v2 载荷的 4 字节长度字段，
> `ShellV2Parser` 把文本前 4 字节读成长度、超 `MAX_FRAME` 后 break；
> ② mock 端在 OKAY 后等 stdin，而客户端没写过 `writeStdin`，两边互相干等 6 秒。
> 两次都是"全 FAIL 但连完整帧也 FAIL"——**当基线用例也红时，先怀疑脚手架，不要怀疑被测代码**。

---

## 2. 中危：配对的失效端口不会被摘掉（已修）

`AdbDiscovery.onServiceLost` 的注释写着「从候选里摘掉，等新广播」，实际只打了一行日志：

```kotlin
override fun onServiceLost(service: NsdServiceInfo) {
    if (stopped) return
    val name = service.serviceName
    AgentLogger.i(TAG, "服务消失：$type / $name")   // ← 就这么结束了
}
```

**后果**：配对弹窗关闭后，旧配对端口仍留在 `pairing` 列表里，`merge` 继续把它当配对端口。
用户下次配对就会得到 **「配对码正确却解密失败」**——那正是 W-51/W-52 花了整批力气想避免的错误。
`merge` 的"同类取端口最大"只是碰巧能缓解，端口更小就错。

修法：加 `byName` 索引（`onServiceLost` 拿到的 `serviceInfo.port` 通常是 0，只能靠名字找），
移除该条后重算端点并回调。名字对不上时按端口兜底再找一次。

---

## 3. 中危：mDNS 回调与 UI 线程无同步（已修）

NsdManager 的回调跑在 **binder 线程**，`stop()` 由 UI（主线程）在 `onDispose` 里调：

```
binder 线程: pending.addLast(...)
主线程:     pending.clear()
```

`ArrayDeque` 不是线程安全的 → `ConcurrentModificationException`。表现为**关掉配对对话框时偶发崩溃**。
`resolving` 标志同样没有 `@Volatile`，跨线程可见性无保证。

修法：加 `stateLock` 保护所有可变状态（`pending` / `pairing` / `connect` / `listeners` / `resolving` / `byName`）。
`onUpdate` 调用放在锁外——它切主线程，锁内回调等于埋死锁。

---

## 4. 中危：ADB 报文写入无锁（已修）

`write(p)` 是 `output.write(...)` + `flush()`，`BufferedOutputStream` **不是线程安全的**。而交互式 shell 下：

- `writeStdin`（写命令）在池的 IO 协程
- `ShellStream.close()` 或 `read` 里回 CLSE 可能在另一条协程

两次写交错会让报文头与载荷错位，对端直接判协议错。加 `synchronized(writeLock)`。

---

## 5. 中危：后端 `start()` 没有超时（已修）

```kotlin
val started = runCatching { candidate.start() }.getOrDefault(false)   // 旧
```

Shizuku 的 `newProcess` 走 binder——**binder 卡住时不会抛异常也不会返回**，
整条工具调用就挂在那里。用户看到的是「点了没反应」，而不是「启动失败」，这比报错难查得多。

修法：`withTimeoutOrNull(30_000)`（proot 冷启动 <5s、Shizuku 秒级、ADB 是 5s connect + 8s 握手，
30s 留足余量），超时后照常 `close()` + 回落下一档，日志区分「启动超时」与「启动失败」。

---

## 6. 中危：`close()` 不关流，reader 可能永久阻塞（已修）

```kotlin
if (p != null) runCatching { p.destroy() }        // 旧：只 destroy
runCatching { p?.outputStream?.close() }
```

只 `destroy()` 不够：Shizuku 的远端进程代理对 `destroy` 不一定及时生效（W-36 已知它的
`waitFor` 语义不靠谱），reader 会一直阻塞在 `reader.read()` 上——那是 `Dispatchers.IO` 的一条线程。

修法：`destroy()` 之后**主动关 `outputStream` / `inputStream` / `errorStream`**，
让阻塞中的 read 立刻抛异常 → `finally` 里 `channel.close()` → reader 收工。

---

## 7. 中危：ADB 启动失败会漏 TCP 连接（已修）

```kotlin
val c = AdbClient(host, port, keyPair)
c.connect()
val s = c.openInteractiveShell()   // ← 这里失败，c 还没赋给字段
client = c
```

`getOrElse` 里只调 `release()`，而 `release()` 关的是**字段** `client`（此时仍是 null）——
局部变量 `c` 的 socket 就漏了。配错端口/配对失效时这条路径会反复走，漏的是真 TCP 连接。

修法：局部变量 `pending` 接住，失败分支也 `pending?.close()`。

---

## 8. 低危：`start()` 失败时一条日志都没有（已修）

```kotlin
val started = runCatching {
    val p = openProcess() ?: return false    // 非局部返回，直接跳出 start()
```

`return false` 跳过了后面整段 `if (started) ... else ...` 日志，排查时只能靠猜。
把 `openProcess()` 移到 `runCatching` 外面单独判空并打日志。

---

## 9. 发现但**没修**的（如实列出）

| 问题 | 为什么没修 |
|---|---|
| 超时后 `send("\u0003")` 对无 PTY 的 shell **无效** | 注释说"尽力中断"，实际 0x03 只是个普通字节。真要中断得改协议（发信号 / 关后端），会影响"超时后仍能继续用会话"的现有语义。已在注释里改成"尽力"，但没改行为 |
| `AdbTerminalBackend.isAlive` 只看 `stream != null` | socket 断了但 readLoop 还没 break 时会误判存活。影响仅是僵尸检测晚一个命令，`send` 失败会兜住 |
| `ProcessBackendBase.awaitExitCode` 每 200ms 一次 `exitValue()` | 对 Shizuku 是每秒 5 次 binder 调用，一条会话挂几小时就是上万次。但改成事件驱动需要它支持回调（没有），只能靠调大间隔缓解 |
| `TerminalIdentity.Shizuku` / schema 里的 `su`、`ssh` 是死值 | 传了会报"无后端"，比静默降权好。要彻底清理得动 schema，会破坏已有对话里的参数 |
| `prefer_proot` 名不副实（实际是"是否包含容器"） | 改名要动工具 schema，同上 |

---

## 10. 验证

| 项 | 结果 |
|---|---|
| `AdbShellFrameVerification`（新增） | **3 / 3**，且**旧实现下 2/3 FAIL**（反向验证） |
| `ShellRoute` / `StateHint` | 14/14、9/9 |
| `T07T08` / `Fallback` | 39/39、28/28 |
| `AdbDiscovery` / `Spake2` | 11/11、15/15 |
| `:app:compileDebugKotlin` | 通过 |
| 全量 `run-all.sh` | PASS=54（新增 1 个 harness） |

## 11. 这批的教训

1. **「超时当作可重试」这条约定，只在"没消费任何字节"时成立。** 一旦进了帧就退化成
   "状态未知"，必须判连接损坏——把两种情况混在一个 catch 里是这类 bug 的共同来源。
2. **注释承诺 ≠ 实现。** 第 2 条（"从候选里摘掉"）和第 6 条（"关掉后端"）都是注释写得对、
   代码没做。审查时对照注释逐句核实现，比通读代码更快抓到东西。
3. **基线用例也红时，先怀疑脚手架。** 我自己在写 harness 时栽了两次，都是"全 FAIL"，
   而全 FAIL 通常意味着测试自己错了。
