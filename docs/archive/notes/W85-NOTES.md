# W-85：把 SIGABRT 的真凶抓出来（stderr 落盘 + 内存快照 + 安全模式）

紧接 W-84。1.466 装上后，用户第二次闪退，这次的日志长这样：

```
10:22:36.343 WARN [LocalLlama] 上次运行被 SIGABRT 终止（读题（prefill）），当时是纯 CPU 运行——与 GPU 无关
```

W-84 的崩溃记忆**生效了**：读到了上次的现场，认出了信号名、认出了阶段（prefill）、
认出了当时 `gpu_layers=0`，因此没有误关 GPU。但也就到此为止——它只告诉我们
「这不是显卡的事」，**没有告诉我们是什么事**。

## 1. 这次为什么还是查不出

SIGABRT 在 ggml / llama.cpp 里几乎只有两个来源：

| 来源 | 现场特征 | 对症做法 |
|---|---|---|
| 分配失败（`GGML_ABORT("alloc fail")`、KV/中间张量顶爆） | VmRSS 已贴到进程上限 | 降批、降 ctx |
| `GGML_ASSERT(...)` 形状/位置断言 | VmRSS 远低于上限 | 改代码，调参没用 |

这两种在 W-84 的文件里**长得一模一样**：都只有 `signal=6`。
而区分它们的两个关键证据，当时都被丢掉了：

1. **ggml 在 abort 之前会 `fprintf(stderr, "GGML_ASSERT(...) failed")` 打一行。**
   那行字就是答案。但 Android 的 App 进程在 zygote 里就把 fd 0/1/2 接到了
   `/dev/null`——logcat 看不到、我们的日志看不到、tombstone 里只剩信号号。
2. **崩溃瞬间的内存占用没人记。**

## 2. 这一版做的三件事

### ① stderr 落盘：`native-stderr.txt`

`ak_crash_install()` 一进门就把 fd 2 `dup2` 到 `filesDir/crash/native-stderr.txt`
（`O_TRUNC`，只留最近一次），并 `setvbuf(_IONBF)`——

> 有些 ROM 的 libc 会把 stderr 设成行缓冲。行缓冲意味着「没有换行就还在用户态缓冲里」，
> 而 abort 走的是 `_exit` 语义，缓冲区不会 flush。显式设无缓冲，才能保证崩溃前那行字落了盘。

下次再崩，这个文件里会有 ggml 自己打的那句断言，一字不改。

### ② 内存快照：`mem=` 行

`crashHandler` 里读 `/proc/self/status`（`open`+`read`，都在 async-signal-safe 列表里），
抄出 `VmPeak` / `VmSize` / `VmRSS` 三行，拼成：

```
mem= VmPeak: 2153888 kB VmSize: 3120488 kB VmRSS: 1820444 kB
```

顺带加了 `sigcode=`（`-6` = SI_TKILL，自己 abort 自己；`1/2` = SEGV_MAPERR/ACCERR，野指针）。

`putMemSnapshot` 用了 `static char sbuf[4096]`：独立信号栈只有 64KB，
handler 里再放 4KB 局部数组要跟别的局部量抢空间；handler 已先把自身重置为 `SIG_DFL`，
不会重入，static 是安全的。

### ③ 安全模式：纯 CPU 崩在推理阶段时，压参数而不是只写一行日志

W-84 在纯 CPU 崩溃时只写了一句「与 GPU 无关」就收工——用户下次进来还是同样的参数、
同样的崩法。这一版加了 `safeMode`：

| | 正常 | 安全模式 |
|---|---|---|
| 上下文 | 用户设置（≤16384） | `coerceAtMost(2048)` |
| 逻辑批 `n_batch` | `min(nCtx, 256)` | `min(nCtx, 64)` |
| 物理批 `n_ubatch` | 跟随 `n_batch` | 跟随 `n_batch` |

触发条件三条同时满足：**纯 CPU** + **崩在 PREFILL/DECODE** + 有完整现场。
（`MODEL_LOAD`/`CTX_CREATE` 不触发：那是模型文件或内存对不上，压批救不了。）

> `n_ubatch` 原来写死 `min(n_batch, 512)`。看着等价（`n_batch` 恒 ≤256），
> 但一旦逻辑批降到 64、物理批还是 512，ggml 建图仍按 512 算，等于没降。
> 这次两处改成同一个 `batchCap`。

安全模式**用户可以手动关**，设置页「推理参数」组最上方有开关和一句解释。
它不是终态，是「先让你能用」的临时档位。

## 3. 为什么开关不在启动时推给 native

`NativeCrashMemory.handleStartup` 只写 SharedPreferences，`LlamaJni.applySafeMode`
由 `LocalLlamaClient.ensureModelLoaded` 在 `acquire` 之前调。两个原因：

1. 启动时调会触发 `dlopen`，把「Application.onCreate 不碰 native」这条约定打破；
2. 用户在设置页开关后，「下一条消息」就生效，不用重启（`n_batch` 是建 context 时定死的，
   所以必须在 `nativeInit` 之前推）。

## 4. 断言

`scripts/qa/run-native-crash-memory-verification.sh` 从 46 条加到 **90 条**。新增的真跑部分：

- 复刻这次的真实形态：`fork` → `PREFILL` + `gpu_layers=0` + `ctx=4096` + `raise(SIGABRT)`
  （W-84 只测了 SIGSEGV + MODEL_LOAD，**两次崩溃形态完全不同，只测一次等于没测**）；
- 子进程 `fprintf(stderr, "GGML_ASSERT(...) failed")` 后再 abort，父进程读
  `native-stderr.txt` 核对那行原样落盘；
- 读崩溃文件里的 `VmRSS` 必须是个**真实数字**，不是占位符。

跑的过程中踩了两个自己的坑，都记在脚本注释里：

- `atoi` 忘了 `<cstdlib>`，C++ 编不过（断言直接红，不是静默跳过）；
- `sed 's/.*VmRSS: *\([0-9]*\).*/\1/p'` 匹配不到——procfs 里是
  `VmRSS:\t   504 kB`（制表符），只写 `[ ]*` 一位数字都取不到，**假红**。
  改成 `[[:space:]]*`。

另外 `ulimit -c 0`：这些断言会 fork + abort，不开的话每跑一次就在仓库根目录
掉几个几十 MB 的 `core.<pid>`。

## 5. 还没做的事（等这版数据）

- 如果 `native-stderr.txt` 里的断言是「alloc fail」→ 内存账算错了，下一步做
  prefill 前的可用内存护栏（低于阈值直接返回中文错误，而不是让 ggml abort）。
- 如果断言是形状类（`GGML_ASSERT(...)` + 具体表达式）→ 是位置/批的 bug，
  调参无用，要按那行表达式改代码。
- VmRSS 明显低于设备可用量 → 同上，不是内存问题。

**这一版的目标不是「修好」，是「下次崩了能一句话定性」。** 同时用安全模式给一个
大概率有效的绕行方案（3B Q4 在 8GB 机器上本来就贴着边）。
