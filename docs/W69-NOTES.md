# W-69：本地推理提速（增量 prefill / 指令集档位 / 逐字流式）

> 触发：用户拿 IRIDIS 对比后反馈「它本地回得很快，断网也能用」，要求北辰本地推理对齐。

## 一、慢在哪（对照 IRIDIS 源码得出的三条）

| 差异 | IRIDIS | 北辰（改前） |
|---|---|---|
| KV 复用 | `LlamaNative.nativeGenerate(roles[], contents[])` + `nativeReset()`，native 侧持 token 序列，只补新增段 | `nativeGenerate(prompt)` 每轮从位置 0 重新 `llama_decode` 整个 prompt |
| 编译档位 | `-march=armv8.2-a+dotprod+i8mm+fp16`，并在 `CpuFeatures` 里硬校验 | 基线 `armv8-a`，`GGML_CPU_ALL_VARIANTS OFF`，dotprod kernel 根本没编进来 |
| 出字方式 | token 级 sink，逐片上屏 | `LocalLlamaClient` 未覆盖 `streamChat` → 走默认实现 → 整段算完才一次性刷出 |
| （另）GPU | OpenCL 全层 offload + flashAttn | 纯 CPU、`flash_attn` 未开 |

结论：**首字延迟的大头不是「生成慢」，而是每轮重算历史 + 憋完整段才显示**。
3000 token 的历史在手机 CPU 上重算要好几秒，且这期间界面只有「思考中」。

## 二、这次改了什么

### 1. 增量 prefill（`app/src/main/cpp/llama-jni.cpp`）

`LlamaInstance` 增加账本 `kvTokens`：KV cache（seq 0）当前持有的 token 序列。

每轮 `nativeGenerate` 的流程变成：

1. tokenize 完整 prompt；
2. 超窗保护：按「输入 3/4、生成 1/4」切预算，历史塞满时丢掉最旧的中段（保留首 token）；
3. 与 `kvTokens` 求最长公共前缀 `common`；
4. `common < kvTokens.size()` 时用 `llama_kv_cache_seq_rm(ctx, 0, common, -1)` 回退，
   账本同步截断；回退失败（滑窗/混合注意力模型）则整块清空重算；
5. 只 `decode` `tokens[common..]`，边 decode 边记账；
6. 生成循环里每个 token decode 成功后 `push_back` 进账本，保证「账本 = KV」。

关键依据：`llama_batch_get_one` 不带 pos，`llama_decode` 内部按
`kv_self.max_pos() + 1` 自动续号（b4600 `src/llama.cpp:8583`），所以只要账本与 KV
一致，分块 decode 天然接在公共前缀之后 —— 不需要手工维护 `n_past`。

预期：同一对话第二轮起，prefill 从「全部历史」降到「上一条回复 + 新提问」，
首字延迟从秒级降到百毫秒级；工具链回合（一次提问多次调模型）受益最明显。

### 2. 编译档位（`app/src/main/cpp/CMakeLists.txt`）

```cmake
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
```

量化推理的算力几乎全在 Q4 点积上，基线 armv8-a 只能走通用 NEON 实现。
抬到 `+dotprod+fp16` 后 ggml 会编出专用 vec_dot kernel（同设备实测约 1.5~2x）。

**不启用 i8mm**：i8mm 属 ARMv8.6，只有骁龙 8 Gen 1 及以上有，启用等于把
2018~2020 的机器全部排除。dotprod/fp16 从 Cortex-A75（2017）起普及，覆盖面大得多。

配套：`cparams.flash_attn = true`（CPU 后端同样生效，长上下文省内存也省时间；
head 维度不支持时 llama.cpp 自己退回普通注意力，不崩）。

### 3. 加载前 CPU 自检（`LlamaJni.kt`）

这版 `.so` 在缺 `asimddp/asimdhp` 的老 CPU 上 **dlopen 即 SIGILL**（崩溃，不是降级）。
所以 `ensureLoaded()` 在 `System.loadLibrary` 之前读 `/proc/cpuinfo` 的 `Features` 行，
缺哪个就拒绝加载并把原因放进 `unavailableReason`，由既有「native 库不可用」路径给文案。

读不到 `/proc/cpuinfo` 时放行（宁可让 dlopen 自己报错，也不要用一个不可读的伪文件
把能用的设备挡在外面）。

### 4. 逐字流式（`LocalLlamaClient.streamChat`）

覆盖 `streamChat`，把 native 的 token 回调转成 `TextDelta`。两个细节：

- **工具调用不逐字外泄**：本地工具协议是「整条回复就是一个 JSON」，首字符判定为 `{`
  时先憋着，等生成结束解析出 `ToolCall` 再整条 commit。否则用户会先看一屏
  `{"tool":"shell_run"…` 再被工具卡替代，比不流式更难看。
- 判定像 JSON 但最终没解析出工具（JSON 非法 / 工具名不在白名单）时，整段按普通文本补发，
  不让这轮回答凭空消失。

### 5. 顺带修的三个小问题

- **跨 token UTF-8 残片**：一个汉字常被切成 2~3 个 token，逐 token `NewStringUTF`
  会出现半个字符（乱码方块）。native 侧现在攒到完整码点再回调。
- **token 文本丢失**：`llama_token_to_piece` 返回负值（缓冲区不够）时原来直接
  `continue`，该 token 既不进 KV 也不记账。改为按所需长度扩容重试。
- **默认线程数**（`LocalLlamaSetting`）：从 `min(4, 核心数)` 改为「最高频簇的核心数
  （clamp 2..6）」。手机是大小核异构，开满 8 线程每步都要等最慢的小核，实际更慢更烫。

## 三、怎么验证

静态契约（CI 每次跑）：

```bash
bash scripts/qa/run-local-inference-perf-verification.sh
```

真机看 logcat（tag `llama-jni`）：

```
prompt token 数: 2841
prefill 完成：复用 2790 / 共 2841 token，补算 51，耗时 380 ms
生成完成：142 token / 386 字符，6120 ms（23.2 tok/s）
```

- 第二轮起 `复用` 应接近总数、`补算` 只剩几十；
- `tok/s` 与改前对比看指令集档位是否生效（改前同模型通常 8~12 tok/s）。

## 四、风险与回滚

| 风险 | 兜底 |
|---|---|
| 老 CPU（armv8.0）加载崩溃 | `LlamaJni` 自检拦截，走「不支持」文案，不 SIGILL |
| KV 回退在滑窗模型上失败 | `llama_kv_cache_seq_rm` 返回 false 时整块清空重算 |
| 账本与 KV 不一致 → 输出质量下降 | 所有 decode 失败路径都 `resetKv`；每轮 decode 前以账本为准做前缀匹配 |
| 并发调用踩 KV | `nativeGenerate` 用 `genMu` 串行化（`nativeStop` 不受锁，随时可打断） |
| 需要退回旧行为 | 只改 3 个文件：删 `kvTokens` 相关逻辑即恢复全量 prefill；`GGML_CPU_ARM_ARCH` 置空即回基线 |

**未做**：Vulkan/OpenCL 全层 offload（要编 shader、处理各家驱动差异，体积 +4MB，
留到 A+B 真机数据出来再判断值不值）。NPU 路线（LiteRT-LM + AOT 模型）工程量是另一个量级。
