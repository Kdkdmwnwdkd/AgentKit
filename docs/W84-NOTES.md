# W-84：原生崩溃记忆 + 显存保护

真机 2026-10-29：`qwen2.5-3b-instruct-q4_k_m` + GPU「自动」+ 上下文 16384，
用户发「用三句话解释什么是光合作用」，**不出字，然后闪退**。运行日志里一条异常都没有。

## 1. 为什么日志里什么都没有

`CrashLogger` 挂的是 `Thread.UncaughtExceptionHandler`——它只能看见 **Java 异常**。
GPU / Vulkan / ggml 这层崩起来走的是**信号**（SIGSEGV / SIGABRT / SIGBUS），
进程当场消失，Java 那层连一句日志都来不及写。

所以「闪退 + 日志空白」不是日志系统坏了，而是**这类崩溃根本不在它的管辖范围内**。
在补上 native 捕获之前，任何关于「为什么崩」的结论都只能是猜。

## 2. native 崩溃记忆怎么做的

`app/src/main/cpp/native-crash.cpp`，三条铁律：

1. **只用 async-signal-safe 调用**（`open`/`write`/`fsync`/`close`/`raise`）。
   崩溃时堆可能已经碎了，`malloc`、`snprintf`、`std::string` 都可能再崩一次——
   handler 里二次崩溃会变成「信号处理中又触发信号」，现场照样丢。
   整数转字符串是手写的 `putUint`，位置见 `static_cast` 那几行。
2. **独立信号栈**（`sigaltstack` + `SA_ONSTACK`）。栈溢出型崩溃在原生栈上
   根本执行不了 handler，表现为「装了也抓不到」。
3. **写完之后必须让系统继续死**：恢复 `SIG_DFL` 再 `raise(sig)`，
   Android 才会照常生成 tombstone。吞掉信号等于假装没崩。

文件落在 `filesDir/crash/native-crash.txt`，固定名字、只保留最近一次：

```
kind=native-crash
signal=11 (SIGSEGV)
stage=MODEL_LOAD
gpu_layers=99
ctx=16384
threads=6
vulkan_built=1
model=qwen2.5-3b-instruct-q4_k_m.gguf
```

`stage` 由 `llama-jni.cpp` 在关键节点调 `ak_stage_set()` 更新：
`DEVICE_INIT`（建 Vulkan 实例）→ `MODEL_LOAD`（读权重）→ `CTX_CREATE`（分配 KV）
→ `PREFILL` / `DECODE`。**崩在哪一步，处理方式完全不同**，没有这一项只有「崩了」。

## 3. 下次启动自动关 GPU

`NativeCrashMemory.handleStartup()` 在 `Application.onCreate` 里跑：

- 读文件 → 解析 → **立刻删掉**（只消费一次）；
- `gpu_layers > 0` 才动手：`gpuMode = OFF`，并把原因写进 `lastGpuCrashNote`；
- 设置页 GPU 分组下面直接显示这条原因。

为什么删：留着的话，用户手动重开 GPU 后每次启动都会被「上次崩过」再关一次，
**永远开不回来**。

为什么不弹框问：闪退对用户来说是「App 坏了」，不是「一个选项需要权衡」。
先退回能用的状态、把原因写在设置页，比每次启动弹一次「是否继续用 GPU？」负责。

判据只有 `gpu_layers > 0` 一条，**不按阶段判**：纯 CPU 也会崩在 `DECODE`
（内存不足、模型损坏），那种情况关 GPU 没有任何意义，只会让用户少一个选项还查不出原因。

## 4. 显存保护：这次的直接原因

GPU 开启时上下文**硬限 4096**（`LocalDeviceRules.capCtxForGpu`）。

3B Q4 权重约 2GB，ctx=16384 的 KV 还要再吃六七百 MB。手机没有独立显存，
单次分配上限由驱动给（常见 256MB～1GB）。装不下时两种下场：
「装载很久 → 悄悄退回 CPU」，或者整个进程消失。

压上下文时会写一行日志，`nCtx` 是多少、设置里原本是多少、为什么——**不许静默改用户设置**。
确实要更长上下文就把 GPU 关掉，那时 KV 在系统内存里，能开多大由内存决定。

设置页「推理参数」的说明也跟着写了这条，避免用户以为 4096 是自己设的。

## 5. 三个坑

**① 半个文件不能当证据。**
写文件时进程正在死，`write` 可能只写了一半（`kind=native-crash` + `signal=1`）。
解析必须要求 `gpu_layers` 存在，否则 `signal=1` 会被当成 SIGHUP ——
而且 `gpu_layers` 缺失时按 0 处理会**放行**（不关 GPU），看起来「安全」，
但日志会写成「纯 CPU 崩溃」，把归因引到错误方向。

**② JNI 签名要跟 Kotlin 的形态对齐。**
`LlamaJni` 是 `object`，成员函数编译成**实例方法**，所以 C++ 第二个参数是 `jobject`
不是 `jclass`。两者 ABI 一样、写错也不崩，但代码里一处 `jobject` 一处 `jclass`
会让下一个人以为这里有区别。

**③ 处理器要在 `dlopen` 之后装，不是 `Application.onCreate`。**
崩溃只可能发生在 `.so` 已加载之后；把 `System.loadLibrary` 提前到 Application
等于给冷启动加 50MB 的映射开销。现在由 `ensureLoaded()` 在加载成功后立刻装，
路径由 Application 提前塞进 `LlamaJni.crashDir`（那里没有 `.so` 依赖，可以随便调）。

## 6. 验证

`scripts/qa/run-native-crash-memory-verification.sh`：**46 条**。

- 静态 22 条：编进同一个 `.so`、`SA_ONSTACK`、handler 里不碰 `malloc`、
  不吞信号、四个阶段标记、Application/LlamaJni/设置层的接线、上下文上限。
- 行为（C++）9 条：**真 fork 一个子进程 `raise(SIGSEGV)`**，核对
  ①进程仍按信号死亡（证明没吞）②文件真的写出来了 ③内容含 signal/stage/gpu_layers/ctx。
  只 grep 源码证明不了处理器装上了。
- 行为（Kotlin）14 条：真编真跑解析与判定，含「纯 CPU 崩溃不关 GPU」「半个文件当没有」
  「别家写的文件不认」。
- 行为（Kotlin）5 条：`capCtxForGpu` 的四个边界 + 常量值。

行为段跑不起来时**算失败**，不按 0 累加。理由见 W-83 笔记里那次假绿。
