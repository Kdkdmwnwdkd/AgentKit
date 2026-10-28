# W-72：本地推理「一个字都不出」的真正原因 —— tokenize 返回值读反了

真机 1.436 日志（`qwen2.5-3b-instruct-q4_k_m.gguf`，ctx=4096，threads=4）：

```
11:45:52.134  模型就绪，耗时 5816 ms
11:45:52.237  ERROR 本地生成返回空文本（prompt 2947 字符）。native 诊断：
              tokenize 失败（输入 5707 字节，开头：<|im_start|>system ...）
```

三次请求全部命中同一行，每次 73~98 ms 返回。这个耗时本身就说明**根本没有进入
推理**：3B Q4_K 在手机 CPU 上跑 2000 token 的 prefill 需要数秒，不可能 80 ms 结束。

## 根因

`tokenizeAll()` 用两趟法分词，第一趟传 `nullptr / 0` 只为问「需要多大」，然后按
llama.cpp 的约定写成了：

```cpp
int n = llama_tokenize(vocab, text.c_str(), len, nullptr, 0, true, true);
if (n <= 0) return false;          // ← 错在这
```

`llama_tokenize` 的返回值约定是：

| 情况 | 返回 |
|---|---|
| 装得下 | 实际 token 数（正数） |
| 装不下 | **`-所需数`（负数）** |

第一趟 `n_tokens_max = 0`，任何非空输入都「装不下」，必然返回负数。于是**每一个
非空 prompt 都在分词这一步被判死**，never reached `prompt 长度` 这条日志。

用户侧看到的就是：模型能下载、能加载、测试连接是绿的，一发消息就弹
「本地模型读不懂这段输入（分词失败）」——像是词表不兼容，其实是自己的判断写反了。

## 修复

`app/src/main/cpp/llama-jni.cpp`

```cpp
int32_t n = llama_tokenize(vocab, text.c_str(), len, nullptr, 0, true, true);
int32_t need = n < 0 ? -n : n;   // 负数是「需要多少」，不是错误
if (need <= 0) return false;

for (int attempt = 0; attempt < 3; attempt++) {
    out.resize(need);
    const int32_t got = llama_tokenize(vocab, text.c_str(), len, out.data(), need, true, true);
    if (got > 0) { out.resize(got); return true; }
    if (got == 0) return false;
    need = -got;    // 仍不够：按新需求再来一趟
}
```

留一趟重试而不是只调两次：个别版本在 `parse_special` 合并相邻特殊 token 时会
「第二趟还差一点」，退化成失败会让这个 bug 以另一种形态复现。

## 顺带说清的三件事

1. **W-69/W-70/W-71 的改动都不是白做的，但都不是这次的病根。** 增量 prefill、
   编译档位、关 FlashAttention、显式 pos、`llama_vocab_is_eog` 全在 tokenize
   之后，这一版之前一次都没被执行到过。这一版之后它们才第一次真正跑起来。
2. **报错文案误导了排查方向。** 「分词失败」让人往「词表不兼容 / 模型文件坏」上
   想，实际是返回值语义。文案保留（现在它只在真失败时出现），但诊断行里补了
   所需 token 数，下次一眼能分辨。
3. **80 ms 返回是「未进入推理」的指纹。** 后续再遇到「很快失败 + 0 字」，先看
   耗时落在哪个量级：<300 ms 基本是入口处就失败，不必怀疑模型或量化档。

## 防回归

`scripts/qa/run-local-inference-perf-verification.sh` 新增第 6 组三条契约：

- `need = n < 0 ? -n : n` 必须存在（负数按「需要多少」转换）
- `need = -got` 必须存在（第二趟不够时按新需求重试）
- `if (n <= 0) return false;` 必须不存在（不再把负返回值当失败）

契约条数 19 → 22，全部通过。
