# W-87：让推理库自己说话

日期：2026-10-29
上一批：W-86（崩溃后日志不再空白）
真机版本：1.466 → 本批出 1.468

## 现象

三次真机闪退，形态不同但结果一样：进程消失、日志一片空白。

| 时间 | 组合 | 阶段 | 信号 |
|---|---|---|---|
| 10:22 | 纯 CPU / ctx 4096 | prefill | SIGABRT |
| 10:36 | GPU 99 层 / ctx 2048 | 生成文字 | SIGABRT |
| 10:44 | GPU 99 层 / ctx 2048 | 生成文字 | SIGABRT |
| 10:50 | 纯 CPU / ctx 2048 | prefill | SIGABRT |

W-84 的崩溃记忆认出了「第 1、4 次与 GPU 无关」，W-85 把 stderr 和内存快照落了盘，
W-86 让日志在崩溃后还能看见。但**都还没回答「谁 abort 的」**。

## 根因（两层，都是「话没被听见」）

### 1. ggml 的日志没人接

`llama.cpp` / `ggml` 通过 `ggml_log_callback` 输出，默认实现是写 stderr。
Android 的 App 进程在 zygote 里就把 fd 0/1/2 接到 `/dev/null` 了，
所以 `GGML_ASSERT(...) failed`、`failed to allocate ...` 这类**直接点名死因**的行，
用户、logcat、我们的 Java 日志，三方都看不到。

W-85 把 stderr 重定向到 `native-stderr.txt` 解决了「事后能查到」，
但用户仍然要会翻应用私有目录——大部分情况下他不会翻，也不会想到要翻。

### 2. 未捕获的 C++ 异常 = 静默 abort

`llama.cpp` 内部大量 `throw std::runtime_error(...)`（分配失败、架构不认、形状不合法）。
这些异常穿过 JNI 边界没有人接，就是 `std::terminate` → `abort()` → `SIGABRT`。

关键点：**这条路径上什么都不会留下**。没有 Java 堆栈（不是 Java 异常），
没有我们自己的日志（abort 前没人写），只有一个 `signal=6`。
它和「被 OOM killer 杀掉」在用户侧完全同形——而两者处理方式完全相反。

## 修法

### ① `llama_log_set` → 运行日志

```cpp
void akLlamaLog(ggml_log_level level, const char* text, void*) {
    const bool isErr = (level == GGML_LOG_LEVEL_ERROR);
    const bool isWarn = (level == GGML_LOG_LEVEL_WARN);
    const bool isCont = (level == GGML_LOG_LEVEL_CONT);
    const bool emit = isErr || isWarn || (isCont && prevNotable);
    ...
}
```

分级刻意收窄：ERROR/WARN 全收；CONT（续行）只跟上一条；INFO/DEBUG 一律丢。

为什么丢 INFO：decode 期间每个 batch 都有一行图构建信息，全转进「运行日志」会把
80 行的环形缓冲冲干净，反而埋掉真正有用的那几行。细节仍在 `native-stderr.txt`。

**一个容易踩的坑**：`GGML_LOG_LEVEL_CONT = 5` 比 `ERROR = 4` 大。
用 `level >= GGML_LOG_LEVEL_ERROR` 过滤会把所有续行当成错误收进来。
必须用 `==` 逐个比较。

### ② `std::set_terminate` → 现场带 `reason`

```cpp
static void akTerminate() {
    if (auto e = std::current_exception()) {
        try { std::rethrow_exception(e); }
        catch (const std::exception& ex) { snprintf(msg, ..., "%s", ex.what()); }
    }
    fprintf(stderr, "[terminate] %s\n", what);   // 绕开 LOGE，避免 diagMu 死锁
    ak_crash_reason(what);
    ak_crash_dump_now();
    abort();
}
```

现场里写 `signal=0 (TERMINATE)` + `reason=<what()>`。

两个设计决定：

- **信号写 0 而不是编一个假信号号**。Kotlin 侧据此走「C++ 异常」分支，
  不去分析 `si_code`（那条路对这个来源没有意义）。
- **reason 存固定数组 + 写完再置标志位**。信号处理器只认标志位，
  单写者单读者，不加锁也不会读到写了一半的字符串。

`abort()` 之前先 `ak_crash_dump_now()` 写一遍，是因为随后的 SIGABRT 会再写一遍
（覆盖同一路径）。所以信号处理器里也要把 `reason=` 带上，否则 terminate 写的那份
会被信号那份吃掉——**同一个进程走两条死亡路径，不能互相擦除证据**。

### ③ prefill 每块打 RSS

```cpp
const long rssKb = ak_rss_kb();
if (rssKb > 0) snprintf(rssBuf, sizeof(rssBuf), "，RSS %ld MB", rssKb / 1024);
LOGI("prefill 进度：%zu / %zu token，%lld ms（%.1f tok/s）%s", ...);
```

W-85 已经有「崩溃瞬间的 VmRSS」，但那是一个孤立数字：分不清「稳定在 800MB」
和「一路涨到 2.4GB 然后死」。一串数字可以——涨的曲线本身就是判据。

## 为什么这次不是「再加一层日志」

因为前三次修的都是「事后追查」的能力（写文件、留现场、回读日志），
而缺的一直是**当场说出来**的能力。ggml 一直在说话，只是没人接电话。

这一批接上了两条线：库自己的日志，和异常被吞掉的那条路径。

## QA

`run-native-crash-memory-verification.sh` 101 → 116 条，全绿。

新增的关键几条（都是真跑，不是 grep 源码）：

```
PASS  terminate 现场写成 signal=0 (TERMINATE)
PASS  reason 行原样落盘（推理库自己说的话）
PASS  terminate 现场也带阶段
PASS  ak_rss_kb 读到了真实数字（1300 KB）
PASS  [ggml] 日志接进运行日志 / 按级别过滤 / 只装一次
```

### 一个差点写错的断言

第一版验证 `[terminate]` 是否进包，用了：

```bash
strings "$SO" | grep -c "[ggml]"     # 30855
```

`[ggml]` 在正则里是**字符集**（匹配 g 或 g 或 m 或 l 单个字符），
几乎每行都命中。`signal=0 (TERMINATE)` 里的括号同理，被当成分组，结果 0 命中。
正确写法是 `grep -cF`。这两条都是「断言自己写错了」而不是代码错——
和 W-83 的 `strings` 不认 UTF-8、W-84 的 `pipefail` + `grep -q` 是同一类毛病。

## 交付

- 1.468 APK（versionCode 468，Vulkan 已编入，`libagentkit-llama.so` 29 MB）
- md5 `41a83c2f6fa01fcdf755ae804882e4d1`
- 签名与 CI 同证书，可覆盖安装

## 下一步

装上之后发一句话。三种结果都有明确含义：

| 日志里出现 | 结论 | 下一版做什么 |
|---|---|---|
| `[ggml] GGML_ASSERT ...` | 断言类 bug，与内存无关 | 按那行断言改代码 |
| `[ggml] failed to allocate ...` + RSS 逼近上限 | 内存顶爆 | 降批/降 ctx/换 1.5B |
| `uncaught C++ exception: ...` | 库抛异常没人接 | 在 JNI 边界加 catch 并转成 Java 异常 |
| 什么都不崩，正常出字 | 安全模式（ctx 2048/批 64）已经够用 | 再谈吞吐与 GPU 部分卸载 |
