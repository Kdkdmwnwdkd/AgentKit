# W-75：读 IRIDIS 的本地推理实现，按原理重写北辰这一层

> 方法：把 `WWRJ3321/IRIDIS` 完整拉下来读（7 MB 源码），**只提炼原理，不复制代码**。
> 北辰的 `llama-jni.cpp` 仍是净室实现：类结构、命名、错误处理、日志措辞都是北辰自己的，
> 只在「怎么做才对」这一点上参考了它的工程结论。

## 一、IRIDIS 的本地推理是怎么搭的

```
模型文件 → EngineManager（单例，同一时刻只驻留一个模型）
             ├─ llama.cpp 引擎（GGUF）  → 默认
             ├─ MNN 引擎（taobao-mnn 目录）
             └─ LiteRT-LM 引擎（.litertlm）
```

关键机制（读 `app/src/main/cpp/iris_engine.cpp`、`engine/llama/LlamaEngine.kt` 得出）：

1. **KV 账本 + 最长公共前缀**，多轮只算新增部分。
2. **`abort_callback` 注册进 context**，`llama_decode` 内部每算完一个 ubatch 就查一次，
   取消在 prefill 中途也能立刻生效。
3. **超窗时按「整轮对话」丢弃最旧消息**（连它对应的回答一起丢），而不是截断中间的 token
   ——很多模板（Gemma/Mistral）不接受「以 assistant 开头」的历史，会整段退化成手写 ChatML。
4. **物理批 `n_ubatch` 固定 ≤512，逻辑批可以更大**，吞吐不变但计算缓冲更小。
5. **采样链顺序固定**：penalties → top-k → top-p → min-p → temp → dist。
6. **线程 = 核数 − 2（上限 8）**，并留 2 个核给 UI 与系统。
7. **系统提示默认为空/由用户填**，工具协议单独拼在 agent 模式下——这是它 prompt 只有
   7 token、首字 4.8 秒的直接原因。
8. **没有 Claude/GPT 那种「人设」写死**；persona 是用户可选项。

## 二、北辰原来差在哪（逐项对照）

| 能力 | IRIDIS | 北辰 1.441 之前 | 后果 |
|---|---|---|---|
| 中断粒度 | decode 内部（ubatch） | 每 n_batch（512 token）查一次 | 慢机上点停止要等半分钟 |
| 中止后的 KV | 对齐账本、保留 | 直接清空 | 中途停止 = 几千 token 白算 |
| 整段命中 | 回退一个 token | 不回退 | 重发同一句时采样读旧 logits，答案原样重播 |
| 采样 | top-k + min-p 双截断 | 只有 top-p | 3B 模型容易在长尾翻车 |
| 线程 | 核数−2 | 大核数（8 核机=4） | 真机 prefill 只有 2~3 tok/s |
| 后端可观测 | 打印 system_info | 没有 | 「慢」永远只能靠猜 |

**注意**：北辰已经有的东西不要丢——增量 prefill、显式 batch（不依赖隐式位置续号）、
三级自愈、UTF-8 安全流式、进度回传运行日志，这几项是对着真机问题一条条打出来的，
比 IRIDIS 的实现更保守也更啰嗦（好事，出问题时日志能直接定位）。

## 三、这一轮改了什么

### native（`llama-jni.cpp`）

1. **`abortCb` 注册进 context**（`cparams.abort_callback` / `rebuildContext` 同样）。
   `llama.h` 明确「returns true = aborted」，实现就是读 `inst->stop`。
2. **中止不再清 KV**：`decodeExplicit` 在 `rc != 0 && stop` 时把 KV 回退到账本长度
   （`llama_kv_cache_seq_rm(pos = kvTokens.size(), -1)`），`decodeFrom` 也不再 `resetKv`。
   账本没动过，所以「回退到账本长度」就是最精确的对齐点。
3. **整段命中回退一个 token**：`common == tokens.size()` 时 `common--` 并同步账本/KV。
   否则一个 token 都不 decode，采样器读到上一轮的旧 logits，界面把上一句再吐一遍。
4. **采样链补齐**：penalties(1.15) → top-k 40 → top-p 0.9 → min-p 0.05 → temp 0.7 → dist。
   惩罚必须先于截断，温度必须最后。
5. **`n_batch` 512 → 256，`n_ubatch` 显式 ≤512，`no_perf = true`**：进度更细、停止更快，
   吞吐不变。
6. **启动打印 `llama_print_system_info()`**：CPU 特性、向量宽度、kernel 家族全进运行日志。
   这行是「prefill 慢是算法问题还是编译问题」的唯一证据。

### Kotlin

7. **默认线程数：大核数 → `clamp(核数−2, 4, 6)`**（8 核机 4 → 6）。
   并加一次性迁移 `threads_default_version`，否则老用户存在 SharedPreferences 里的旧值
   会让新默认永远不生效——现象就是「更新了但没变快」。

## 四、依赖处理

新增 `docs/DEPENDENCIES.md`：运行时 Maven 坐标 + 构建期工具链 + llama.cpp 的 pin 方式 +
随包二进制（proot 三件套）的来源与许可 + **明确列出没引入哪些**（MNN / LiteRT /
sherpa-onnx / QNN / GenieX）及理由。

北辰的 native 总量约 3 MB，IRIDIS 约 100 MB——不是能力差距，是「支持多少种模型格式」的
差距。北辰只押 GGUF，所以不需要那 97 MB。

## 五、待验证（下次真机日志看这三行）

```
后端能力：...（有没有 dotprod / NEON，向量宽度多少）
prefill 进度：256 / 1086 token，xxxx ms（xx.x tok/s）
生成完成：xx token / xx 字符，xxxx ms（x.x tok/s）
```

- 如果 `后端能力` 里**没有** dotprod → 问题在编译参数，调线程没用；
- 如果 prefill 只有个位数 tok/s 而 dotprod 正常 → 设备本身弱，换 0.5B；
- 如果 prefill 正常（≥15 tok/s）而生成慢 → 模型太大，也是换小模型。
