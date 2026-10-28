# W-80：llama.cpp b4600 → v0.7.0

> 起因：对账「IRIDIS / Operit 的底座是不是最新」，顺出来的结论是——我们自己落后最多。

## 1. 版本对账（2026-10-28 实测）

| 项目 | 推理底座 | 版本 | 落后上游 | 备注 |
|---|---|---|---|---|
| 上游 | llama.cpp | v0.7.0（2026-10-15，内部 build b11669）<br>nightly b11946（2026-10-28） | — | Android 官方 prebuilt 已含 CPU / Adreno GPU / Hexagon NPU |
| **北辰** | llama.cpp | **b4600（2025-01-30）** | **约 21 个月 / 7300+ 个 build** | 只编 CPU，纯自建 JNI |
| IRIDIS | llama.cpp | v0.6.0（2026-10-05） | 10 天 / 1 个 minor | 同时挂 MNN 3.6.1（最新）、LiteRT-LM 0.18（最新是 0.18.1，落后一个 patch） |
| Operit | llama.cpp | `720d7fa4`（2026-07-25） | 约 3 个月 | 另挂 MNN-LLM |

结论分两句：

- **「最新」**：IRIDIS 基本贴着上游（差 10 天），Operit 落后一个季度，我们落后近两年。
- **「最好」**：这三家不是同一条路线，谈不上谁最好。llama.cpp 在 GGUF + k-quant + CPU 上生态最厚；MNN 对移动端 ARM 算子更细（尤其国产 SoC 的 GPU/NPU 通路）；LiteRT-LM 走 NPU delegate 最省事但要专有格式。IRIDIS 三家全挂，本质是在**对冲**——按设备能力选路，不押注单一引擎。

北辰的取舍没变，仍是「只上 llama.cpp、只跑 CPU」，但要把它升到当前版本。

## 2. 升级实际要改什么（不是估的，是逐个头文件对出来的）

拿我们 `llama-jni.cpp` 里用到的 **47 个 llama_\* API** 逐个比对 b4600 与 v0.7.0 的声明，真正的破坏性变更只有 **3 个**：

| b4600 | v0.7.0 | 性质 |
|---|---|---|
| `llama_kv_cache_clear(ctx)` | `llama_memory_clear(llama_get_memory(ctx), true)` | KV 提升为通用 memory 抽象 |
| `llama_kv_cache_seq_rm(ctx, …)` | `llama_memory_seq_rm(llama_get_memory(ctx), …)` | 同上 |
| `llama_sampler_init_penalties(last_n, rep, freq, present)` | 首参多一个 `n_vocab` | 内部按词表大小预分配惩罚环 |

另外三处是**结构体字段**改名，编译器会直接报，不会静默：

| b4600 | v0.7.0 |
|---|---|
| `cparams.flash_attn = false` | `cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED`（三态：AUTO/DISABLED/ENABLED） |
| `mparams.use_mmap = true` | `mparams.load_mode = LLAMA_LOAD_MODE_MMAP` |
| （无） | 新增 `mparams.lazy_mode`（OFF / AUTO / ON） |

CMake 侧一处**会静默出事**的改名：

> `GGML_CPU_AARCH64` → `GGML_CPU_REPACK`，**默认值仍是 ON**。

我们在 b4600 上踩过这个坑：权重重排是懒执行的，正好落在第一次 decode 里，部分 ARM 设备上会反复重排不命中缓存，表现为「加载 5 秒正常，第一次 decode 卡满 180 秒不出字」。如果只留着老变量名，v0.7.0 会**默默把重排打开**，编译日志里一个字都不会提。所以两个名字都显式设 OFF。

## 3. 做法：双向兼容层，而不是硬改

`llama-jni.cpp` 顶部加了一层 `akKvClear` / `akKvSeqRm` / `akSamplerPenalties`，用 `LLAMA_SESSION_VERSION`（b4600 = 9，v0.7.0 = 11）分支：

```cpp
#if LLAMA_SESSION_VERSION >= 10
#define AK_LLAMA_MEMORY_API 1
#else
#define AK_LLAMA_MEMORY_API 0
#endif
```

理由只有一个：**回退成本**。万一新版在真机上出现定位不了的回归，把 `fetch-llama.sh` 的 tag 改回 b4600 就能立刻编出可用包，不用再改一遍 C++。多出来的是二十来行 `#else`，比「出问题先花一小时回滚代码」便宜。

## 4. 这次**故意没开**的两个新能力

升级本身已经是一个变量，再叠两个就分不清回归是谁的锅。

| 能力 | 为什么想要 | 为什么这次不开 |
|---|---|---|
| `lazy_mode`（按需读 tensor 行） | 直接对口我们的「冷启动 13.4 秒」——不再整块缺页，只读要用到的行 | 它把缺页成本从加载阶段推到首次 decode，首字延迟反而变难看。要配合预读策略调，单独一轮验证 |
| `GGML_CPU_KLEIDIAI`（ARM 手写 matmul 内核） | ggml v0.27.0 里 CPU 侧最大的提速项（SME2 k-quant matmul） | 构建时要额外从 GitHub Release 拉 ARM 的源码包，CI 多一处网络依赖。先让版本本身跑稳 |

两项都留了注释在原位，下一轮单独开关、单独测。

## 5. 验证做到哪一步

1. **API 形状**：把我们的调用形式抽成一个独立编译单元，对着 v0.7.0 的 `llama.h` 真编（`g++ -std=c++17 -c`），通过。抓的是签名/字段名/枚举名这类只有编译器能确认的东西。
2. **静态契约**：新增 `scripts/qa/run-llama-v070-verification.sh`，26 条断言，锁四类事——版本钉定（tag + commit + CI 缓存 key）、兼容层双分支、调用点是否全部走封装、编译开关是否按新名字设了。
3. **回归**：`run-local-inference-perf-verification.sh` 由 61 条扩到 64 条（repack 断言改成新旧名各一条），全过。
4. **还没做**：NDK 真编译 + 真机跑。这两步只能在 CI 和手机上完成。

## 6. 真机要看的三行日志

- `nativeInit 成功: …` —— 能不能加载、模型描述是否正常
- `prefill 完成：… 耗时 XXXX ms（XX tok/s）` —— 与升级前同 prompt 对比
- `生成 XXX tok / XXX ms（X.X tok/s）` —— 稳态生成速度有没有变化

判定口径：**先看能不能出字，再看快了多少**。新版万一 prefill 反而慢，先怀疑是不是 repack 被悄悄打开了（看编译日志里的 `GGML_CPU_REPACK`）。
