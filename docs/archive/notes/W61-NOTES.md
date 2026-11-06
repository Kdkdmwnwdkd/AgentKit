# W-61 · 首次真机验收反馈的处理

**日期**：2026-10-27
**输入**：用户按 `1.396` 验收清单跑了 A/B/C，附日志 + 5 张截图

---

## 1. 逐条判定

| 条 | 结论 | 依据 |
|---|---|---|
| **A** 停止按钮 | **判定不了**（取消生效，但没走到工具阶段） | 12:25:56 点停止，1.7s 内 `运行已停止（历史已闭合）`；但模型当时还在 LLM 阶段，压根没调工具 |
| **B** `cd` 保持 | **验不了**（我的清单设计错了，两处） | 见 §2 |
| **C** uid 2000 | **失败**，且伴随**两次 App 崩溃** | 见 §3 |

---

## 2. B 验不了：清单自身的两个错误

### 2.1 句柄示例被当成了真值

我在清单里写「把 `a1b2` 换成你的句柄」，但那四个字符是**我编的示例**，看起来像真值。
用户直接照抄，于是：

```
12:33:40 shell_run {"command":"cd /data/local/tmp","session":"a1b2"} → no_such_session
12:38:18 shell_run {"command":"pwd","session":"a1b2"}            → no_such_session
```

真实句柄是 `08f3`（12:29:56 开辟的）。

**教训**：给用户的操作手册里，占位符不能长得像真实数据。要么用 `你的句柄` 这种明显不是值的写法，
要么直接给「抄下屏幕上那四位」的明确指令 + 醒目提示。

### 2.2 路径选错了后端

我让用户 `cd /data/local/tmp`，而日志显示当时走的是 **proot 容器**：

```
12:35:28 /bin/sh: cd: can't cd to /data/local/tmp: No such file or directory
```

容器 rootfs 是 Alpine，没有 Android 的 `/data/local/tmp`。**即使句柄对了，这条也必然失败。**

改法：用 `/sdcard` —— proot 启动参数里有 `-b /sdcard`，宿主机也有 `/sdcard`，换哪个后端都成立。

### 2.3 顺带发现：模型自己"修"了命令

12:35:28 模型把三步合并成了一次 `cd /data/local/tmp && pwd`（`session=""`）。
这恰好落回「一条命令一个进程」的假阳性——即使用户没抄错句柄，模型也可能这么合并。
所以清单里"必须分三次发"的提示要更硬：**每步单独一轮，不要在同一条消息里说两件事**。

---

## 3. C 失败的直接原因：崩溃（已修）

日志里两次：

```
[CrashLogger] 崩溃记录 crash-20261027-125257.txt：java.lang.IllegalArgumentException: process hasn't exited
[Crash] 堆栈首行：at android.os.Parcel.createExceptionOrNull(Parcel.java:3343)
[CrashLogger] 崩溃记录 crash-20261027-124116.txt：java.lang.IllegalArgumentException: process hasn't exited
```

### 根因

`ShizukuRemoteProcess` 在**进程还在跑**时，内部 `exitValue()` 抛的是
`IllegalArgumentException: process hasn't exited`（经 Parcel 回来），
而标准 `java.lang.Process` 抛的是 `IllegalThreadStateException`。
它的 `isAlive()` 只捕获后者 → **前一种异常直接穿出**。

而我在 W-56 写的 `ProcessBackendBase` 里有两处**裸调**：

```kotlin
override val isAlive: Boolean
    get() = process?.isAlive == true          // ← 正常运行时就会抛

if (!p.isAlive && runCatching { p.exitValue() }.isFailure) return -1   // ← 同样裸调
```

第二处跑在 `scope.launch { awaitExitCode(p) }` 里，**没有 try/catch** →
未捕获异常 → 直接崩进程。

**讽刺的是**，老代码 `ShizukuShell.exec` 里早就写清楚了这件事：

```kotlin
// IllegalThreadStateException = 进程还在跑，正常；其余（RemoteException 等）= 通道异常
if (e != null && e !is IllegalThreadStateException) { waitError = ...; break }
```

W-56 我把一次性通道的逻辑搬到持久后端时，**把这条知识丢了**——只留了"用 exitValue 轮询"，
没留"哪些异常算还在跑"。

### 修法（四处）

1. `isAlive` 包 `runCatching`，拿不到状态时返回 **true**（异常在这个上下文恰好等于"还在跑"；
   真死了 `send` 会失败并由池如实报错）
2. `awaitExitCode` 把 `IllegalThreadStateException` **和** `IllegalArgumentException("hasn't exited")`
   都当"还在跑"；只有真正拿不到时才放弃
3. 放弃时返回 **null** 而不是 `-1` —— `-1` 会被池当成"进程已退出"，把仍可用的会话判死
4. `readerJob` 的 `finally` 补 `exit.complete(-1)`：**输出流关闭 ≈ 进程结束**，
   这是 `exitValue()` 不可用时唯一可靠的退出信号

另外补一条：`readerJob` 的 `catch (Throwable)` 会把 `CancellationException` 记成"读取异常"，
加前置重抛（与 W-60 同一类问题）。

---

## 4. 顺带修的一个日志错误：降级级数 off-by-one

真机日志：

```
[run_shell] 候选「调试通道（Shizuku）」可用=true
[run_shell] 选中「调试通道（Shizuku）」（降级 1 级）      ← 命中的是首选，却写"降级 1 级"
[control_screen] 选中「调试通道（Shizuku）」（降级 2 级）  ← 第 2 档，应写"降级 1 级"
```

代码是 `降级 ${trace.size} 级`。trace 从首候选列到选中项，选中首选时 `size` 已经是 1。

改为 `trace.size - 1`，且 0 时显示「首选」。

**这个错误值得单独修**：读日志的人（包括我）会据此判断"有更优候选但没选它"，
而实际它就是首选——排障方向会被整个带偏。

---

## 5. 不是 bug 但影响测试的两件事

### 5.1 429 限流让每轮要等 1~2 分钟

```
[HTTP] 请求失败 code=429 body={"error":{"message":"请登录platform进行实名认证以获取更高rpm"}}
[NetworkRecovery] 退避开始时网络即通（非断网失败），按序列等满 10000ms
... 30000ms ... 60000ms
```

单次对话 121 秒，其中 100 秒在退避。**这不是代码问题**，是 agnes-ai 免费额度限流。
但验收时每步都等两分钟，体验上会让人以为"卡住了"。

### 5.2 模型在"自言自语"

截图 3 里模型连续输出 4 遍「当前还在 Flyme 体验改进计划页，先回到设置首页」，
`toolCalls=0` —— 它没有调工具，只是在描述自己的意图。这是**模型能力问题**
（agnes-3.0-flash），不是 App 的问题。

> 验收 A 时如果换成能稳定调工具的模型（如 claude/gpt），A 才有意义；
> 否则测的是模型，不是取消逻辑。

---

## 6. 验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| Fallback / T07T08 / ShellRoute / AdbShellFrame | 28/28、39/39、14/14、3/3 |
| 崩溃修复 | **未验证**——需要真机再跑一次 C（这正是下一轮的目的） |

## 7. 待用户确认

1. `files/crash/crash-20261027-125257.txt` 的完整堆栈（日志里只有首行）——
   确认崩溃点确实是 `isAlive`，而不是我漏掉的第三处
2. 换模型后重测 A（当前模型不调工具，A 测不出来）
3. B 用修正后的步骤重测（`/sdcard` + 你自己的句柄）

---

## 8. 一个自我批评

W-59 我审自己的代码时，专门检查了「`close()` 有没有关流」「`start()` 有没有超时」，
**但没检查"对 Shizuku 代理的方法调用会不会抛非常规异常"**。

而老代码里有现成的注释写着这件事。我的审查方法是"扫自己写的代码找可疑模式"，
漏掉了"对照同类老代码看它踩过什么坑"——这次是后者才救回来的。

**下次审新增后端时，第一步应该先把同类的老实现读一遍，把它的注释当成检查清单。**
