# W-76：158 token 也跑不动 → 问题不在 prompt 长度

## 一、这次日志真正说明了什么

```
14:24:51.839  开始生成：轻聊模式，prompt 352 字符，上限 512 token，线程 6
14:27:53.505  180s 没出首字（中止）
native 诊断：
  后端能力：CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | DOTPROD = 1 | AARCH64_REPACK = 1 |
  nativeInit 成功: ... (n_ctx=4096, n_batch=256, threads=6/8, ...)
  prompt 长度：686 字节 → 158 token      ← 分词已通
```

三件事同时成立：

1. **分词通了**（`prompt 长度` 这行出现，158 token），W-72 的修复有效；
2. **轻聊档生效**（`tools=0`，352 字符而不是 1086 token），W-74 的修复有效；
3. **但 158 token 的 prefill 仍然 180 秒算不完。**

第 3 条把前面所有「prompt 太长」的假设全部推翻。158 token 在 8 核手机上，正常
prefill 是 **1~3 秒**，慢 60 倍以上，这不是参数问题，是引擎路径问题。

而且日志里**没有 `prefill 进度` 这一行**。158 token 小于分块 256，说明它卡在
「第一块 llama_decode 内部」，一次都没返回过。

## 二、两个让 decode 卡死的实现问题

### 1. `AARCH64_REPACK`（主嫌）

`后端能力` 里写着 `AARCH64_REPACK = 1`。这是 `GGML_CPU_AARCH64` 打开后启用的
权重布局重排：Q4_K 会被转成 8x8 交错布局，理论上内积更快。

问题是重排是**懒执行**的，落在第一次 `llama_decode` 里。b4600 这条路径在部分
ARM 设备上会反复重排（没有正确命中缓存），一次 forward 就够把预算烧光。
这正好解释「加载 5 秒正常、第一次 decode 永不返回」。

处理：CMake 里显式 `GGML_CPU_AARCH64 OFF`，回到标准 NEON + dotprod 内核。
IRIDIS 在同款设备上跑 4.8 tok/s 走的也是标准路径，说明绝对速度够用。

### 2. prefill 线程被上浮到全部核心

```cpp
cparams.n_threads_batch = std::max(nThreads, std::min(hwCores, 8));  // 旧
```

用户设 6 线程，prefill 实际跑 8 线程（日志里的 `threads=6/8` 就是证据）。
手机 SoC 是 4 大 + 4 小的异构结构，8 线程意味着每一步都等小核；而 ggml 的线程池
在「每线程只分到几十个 token」时几乎全花在同步上。

处理：`n_threads_batch = nThreads`，不再上浮。

## 三、把「算得慢」和「卡死」彻底分开

前面几版最耗人的地方是：**一份日志看不出卡在哪**。这一版补了三层证据：

| 新增 | 作用 |
|---|---|
| `预热 decode：1 token / N ms` | 单独算 1 个 token。3B 正常 100~400ms，超过 8 秒就是引擎废了，与 prompt 长度无关 |
| `prefill 开始：从第 N 个起，共 M token，线程 T，分块 C` | 进门先留痕。有「开始」没「进度」= 卡在 decode 内部；两者都没有 = 卡在 KV 回退 |
| 分块 256 → 64 | 第一行进度几秒内就出现，不再被大块吞掉 |

预热还带**自愈**：1 token 超过 8 秒且线程 > 4 时，自动把线程减半、重建上下文
再试一次（手机小 batch 上线程争用常比并行收益大）。两次都慢就认命，但结论写进日志。

## 四、看门狗不再一律等 180 秒

旧逻辑：没出首字就等 180 秒。新逻辑按预热结果给预算：

```kotlin
fun firstTokenBudgetMs(warmupMs: Long): Long = when {
    warmupMs in 1..2_000 -> 45_000     // 引擎正常 → prefill 拖过 45 秒就是卡住
    warmupMs > 2_000     -> 150_000    // 引擎本身慢 → 别误杀
    else                 -> 90_000     // 还没探到
}
```

另外补了**生成阶段的看门狗**：已经出字之后，90 秒没有新字也中止（之前只管首字，
生成中途原地循环会一直挂着）。

报错文案按预热耗时分支：
- 引擎慢 → 明确建议换 `qwen2.5-0.5b`；
- 引擎正常 → 让用户回传日志（此时能定位到具体哪一步）。

## 五、契约

QA 42 → **50 条**，新增第 10 组「引擎自检」7 条，第 2 组补「关掉 repack」1 条。

```
[10/10] 引擎自检（把「算得慢」和「卡死」分开）
  PASS  有单 token 预热探针
  PASS  预热超阈值自动降线程重试
  PASS  prefill 前留下「开始」痕迹
  PASS  prefill 分块小于逻辑批（进度不被大块吞掉）
  PASS  prefill 线程不再上浮到全部核心
  PASS  首字预算由预热耗时决定
  PASS  生成中途卡住也有上限
```

## 六、这次要看的三行

```
预热 decode：1 token / xxx ms（正常/偏慢/异常慢）
prefill 开始：从第 1 个起，共 158 token，线程 6，分块 64
prefill 进度：64 / 158 token，xxxx ms（xx.x tok/s）
```

- 预热 **< 400ms** 且 prefill 有进度 → 通了，接着看 `生成完成` 的 tok/s；
- 预热 **> 8000ms** → 这台设备跑 3B 确实吃力（repack 之外的因素），换 0.5B；
- 有 `prefill 开始`、无任何进度 → decode 仍卡死，但这次能确定是 decode 内部，
  下一步直接换 llama.cpp 版本（b4600 → b5xxx），不再在参数上打转。
