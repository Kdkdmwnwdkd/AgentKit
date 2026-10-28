# W-71：本地推理「一个字都不出」——定位与修复

## 现场（真机日志，1.435）

```
11:13:42.665 [LocalLlama] 模型就绪: qwen2.5-3b-instruct-q4_k_m.gguf，耗时 5008 ms
11:13:42.671 [LLM] → 请求 model=qwen2.5-3b... messages=4 tools=7
11:13:42.770 [LLM] ← 响应 ... FAIL 98ms 流式生成结束（文本）首字 — / 0 字
```

界面表现：发什么消息都没有新回复，屏幕上一直是**同一条旧回答**，用户感受是「一直重复一句」。

## 那句话不是模型生成的

代码里没有任何「你好！请问需要我帮你做什么？」的文案（全仓 grep 无命中），
本地模式也不走云端。结合 `0 字 / 98ms` 可以确定：

> 屏幕上是**历史记录**——旧版本本地推理正常时留下的回答。
> 本轮生成失败、没有新增可见气泡，所以看起来像卡在同一句话上。

这条判断很关键：它说明 **3B 模型在这台设备上曾经能跑**，问题是一次**回归**，
不是「模型太大跑不动」。回归点指向 W-69（1.429）的三处改动。

## 为什么 98ms 就失败

500 token 的 prefill 在手机 CPU 上要数秒。98ms 结束只有两种可能：
tokenize 失败，或 `llama_decode` 立刻返回非 0。日志时间戳落在
`→ 请求` 之后 99ms，`buildPrompt` 只花了 6ms，所以是 decode 失败。

W-69 引入的三处都有嫌疑，按可能性排序：

| 嫌疑 | 机理 | 处理 |
|---|---|---|
| **FlashAttention 开启**（最可能） | b4600 的 CPU FA 按 head_dim × 量化类型分派，个别组合直接让 `llama_decode` 返回非 0。Qwen2.5 head_dim=128 + Q4_K_M 正好落在边界上 | 关掉，先保证出字 |
| `llama_batch_get_one` 的隐式位置续号 | 它依赖「KV 里该序列 max_pos + 1」。上一轮若删过 KV，max_pos 是否回落取决于版本实现；不回落就写到错误位置 | 改显式 pos，不再向库猜 |
| 结束符判定只比 `llama_vocab_eos` | Qwen2.5 有 `<\|endoftext\|>` 与 `<\|im_end\|>` 两个，漏一个就一直往下吐 | 改 `llama_vocab_is_eog` |

## 改了什么

### native（llama-jni.cpp）

1. **显式构造 batch**：`decodeExplicit()` 手工填 `pos / n_seq_id / seq_id / logits`，
   `pos0` 由调用方（KV 账本长度）给出。不再用 `llama_batch_get_one` 的 `pos=nullptr`
   隐式续号——那条规则是我们上一轮自己删 KV 之后最不可靠的一环。
2. **关闭 flash_attn**：先隔离变量。等真机数据证明生成速度确实是瓶颈，再按机型开。
3. **结束符判定改 `llama_vocab_is_eog`**：覆盖 Qwen 系的多结束符。
4. **生成为空时必留原因**：`produced / 首个 token / 预算 / promptTokens / prefill 耗时`。
5. **三级自愈**（不需要重启 App）：
   prefill 失败 → 分块缩到 512 重试 → 仍失败则重建上下文重试 → 仍失败才放弃。
   前两级能救回「账本脏」「单批太大被拒」这两类真机常见情况。
6. **生成预算下限从 16 改 1**：窗口吃满时原来硬生成 16 个 token，反而必然 decode 失败。
7. **诊断回路**：`LOGI/LOGE` 同时写入进程内 ring buffer（最近 80 行），
   由 `nativeDiagnostics()` 回传给 Kotlin。

### Kotlin

8. **`LlamaJni.nativeDiagnostics()`**：把 native 现场搬进 App 内「运行日志」。
   用户没电脑也能把原因带回来——这是这次最实质的改进，之前所有线索都只在 logcat 里。
9. **空输出不再静默**：`chat / streamText / streamChat` 三条路径，生成 0 字一律
   抛 `LocalInferenceException`，并先记一条含完整 native 诊断的 ERROR。
   原来会静默落一条空助手消息，用户侧就是「没反应」。
10. **`LocalInferenceException` 不继承 `IOException`**：`friendlyMessage` 把所有
    IOException 翻译成「📶 网络不可用或超时」。本地模型根本不出网，这句提示会把
    用户带到完全错误的方向。现在显示「🧠 <真实原因>」。
11. **不可重试**：本地失败是确定性的，走 3s + 10s 退避只会让用户干等 13 秒，
    所以 `isRetryable` 直接判 false（快速失败、马上显示原因）。

## 验收

装上后发一句「你好」：

- **成功**：正常出字。运行日志多一行
  `[LocalLlama] native 摘要｜nativeInit 成功… ｜prompt 长度：… ｜prefill 完成：复用 0 / 共 N token，补算 N，耗时 XXXms ｜生成完成：…`
- **仍失败**：对话里出现一条 `🧠 <原因>` 气泡，运行日志里有完整 native 诊断
  （tokenize 长度、decode 返回码、首个 token、prefill 耗时）。

把这条气泡或那段日志发回来，就能直接定位到具体哪一步。

## 教训

「加一个新特性（FA / 增量 prefill）」和「保住能出字」是两件事。W-69 一次改了三处
互相耦合的东西，真机失败时无法二分。以后动 native 生成路径，**一次只动一处**，
且必须带「失败原因可见」的通道——否则用户在手机上看到的现象，和真实原因之间
隔着一条 logcat。
