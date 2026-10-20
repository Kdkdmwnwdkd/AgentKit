# W-46 批次记录：手机端可用性修复（历史 / 端点 / 超时 / 会话）

> 批次时间：2026-10-20
> 起点：`15b4cf4`（CI 绿）
> 触发：一次全仓静态体检（18 条，P0×1 / P1×6 / P2×7 / P3×4）后，先做「用户能直接感知 + 一行级风险」的 7 项

---

## 一、这一批修了什么（现象 → 根因 → 修法）

### 1. 多轮对话失忆（P0）

| 项 | 内容 |
|---|---|
| 现象 | 第二句说「继续」「把它改成红色」「第二个方案再展开」，模型完全接不上；而 UI 文案还写着「回复『继续』即可接着做」 |
| 根因 | `RealAgentLoop.runTurn()` 每回合只造 `SystemMessage + UserMessage` 两条；会话历史虽完整落盘，但**从未转成 `ChatMessage` 进请求**（全仓 `.turns` 只有 regenerate 取最后一句时读过一次） |
| 修法 | ① 契约 `AgentLoop.send` 增加 `priorMessages`；② `runTurn` 把历史插在系统提示之后、当前输入之前，并用 `prefixCount` 把返回值裁成「本回合新增」（否则调用方会把历史重复落盘）；③ `ChatViewModel` 新增 `buildPriorMessages()`，按轮取 user 正文 + **选中变体**的 assistant 正文，最多回看 20 轮，体积交给 `ContextBudget` 裁；④ regenerate 用 `excludeLastTurn = true`，避免把要重做的旧答案当历史 |
| 取舍 | 历史只带正文、**不带 tool trace**：跨回合重建 `assistant.tool_calls ↔ tool` 配对极易让严格供应商 400，而工具过程对指代没有帮助 |
| 证据 | `RealAgentLoop.kt` `prefixCount` / `turnResult()`；`ChatViewModel.buildPriorMessages`；W-46 harness H1–H5 |

### 2. 局域网 `http://` 端点被系统拦死（P1）

| 项 | 内容 |
|---|---|
| 现象 | 填 `http://192.168.x.x:11434`（Ollama）、`http://10.0.2.2:1234`（LM Studio）、局域网 one-api 中转，一律「调用失败」 |
| 根因 | `targetSdk=34` 默认禁明文；工程里既无 `usesCleartextTraffic` 也无 `networkSecurityConfig`，而 `requireUsableEndpoint()` 明确允许 http |
| 修法 | 新增 `res/xml/network_security_config.xml`（`cleartextTrafficPermitted=true`）+ Manifest 引用；注释写明为何不能按域白名单（Android 的 `<domain>` 不支持 CIDR）与收紧路径 |

### 3. LLM 通道 10 秒读超时（P1）

| 项 | 内容 |
|---|---|
| 现象 | 推理模型（R1/QwQ 类）或长系统提示下，等 10 秒报网络错误，3s/10s/25s 重试三次后整轮失败 |
| 根因 | 三家客户端都是裸 `OkHttpClient()`（默认 `readTimeout=10s`）；`isRetryable` 又把 `SocketTimeoutException` 当网络抖动 |
| 修法 | 新增 `core/llm/LlmHttp.kt`：连接 15s / 读 180s / 写 60s / **callTimeout=0**（长回答可流几分钟，靠协程取消收口），三家共用同一实例（连接池与 Dispatcher 按实例持有） |

参考口径：AiCode 默认超时 120s、上限 1800s（`docs/research/refs-digest/aicode.md:281`）。

### 4. `http_request` 谎称只读（P1）

| 项 | 内容 |
|---|---|
| 现象 | 模型同一批发 `[POST /order, GET /order]` 时两路真并发，读到写前/写后随机 |
| 根因 | `readOnly=true` 是 `batchHasMutation` 的唯一判据，却被写成「POST 由调用方自负」；Coomi 的 `has_mutation`（全批只读才并发）因此被架空 |
| 修法 | 改 `readOnly=false`，含 http_request 的批次一律串行；注释写清代价与理由 |

### 5. 重试次数两套默认值（P2）

适配层 `providerRetry=2`、实现层 `=3`，生产走适配层 → hotfix10 设计的 429 退避第三档（60s）**永不生效**。已对齐为 3。

### 6. `runOwnerConvId` 缺 `@Volatile`（P2）

写点在主线程、读点在工具回调/落盘协程。缺可见性时后台线程可能读到 stale null，
`runOwnerConvId ?: active` 会把后台结果写进**当前**会话——正是 hotfix30 要消灭的「写串」。
同文件 `screenshotApprovedThisTurn` 早已按此标注，属于漏改，本次补齐。

### 7. 终端会话超时不收尸 + 输出无上限（P1）

| 项 | 内容 |
|---|---|
| 现象 A | `shell_run("cat 大文件")` 直接闪退（OOM） |
| 现象 B | 某条命令 30s 超时后，下一条命令返回的是**上一条的退出码与输出**（静默错位） |
| 根因 A | 输出全量堆进 `StringBuilder`，`truncate` 只在命令结束后执行 |
| 根因 B | 超时只返回 failure，shell 仍停在半条命令上，下一个哨兵匹配到旧输出 |
| 修法 | 收流时按 `MAX_BUFFER_CHARS=256K` 丢头保尾（哨兵在尾部，不影响完成判定），并在结果前标注丢弃字符数；超时先发 `Ctrl-C`，300ms 后仍存活则关后端并摘除会话，否则标 `desynced`，后续 `run` 返回 `session_desynced` 逼调用方重开 |

参考口径：Operit 的 `executeCommand(commandId, timeoutMs)` **超时可中断**，验收明确要求
「`sleep 100` 在 5s 超时后被中断并返回已产出输出」（`operit.md:698/732`）。

---

## 二、验证

```bash
# 干净编译（沙箱：Gradle 8.9 + Android SDK 34 + 阿里云 Maven 镜像）
gradle :app:compileDebugKotlin --no-daemon        # BUILD SUCCESSFUL（仅既有 warning）

# 新增 harness：12 条断言，0 失败
bash scripts/qa/run-w46-verification.sh
```

W-46 harness 断言的是**结构不变量**（源码级），因为历史注入的端到端行为要跑通 Android VM 链才能验：
`messages.addAll(priorMessages)` 与 `messages.drop(prefixCount)` 必须同时存在——少一个就分别退化为
「历史根本没发」与「历史被重复落盘」。

---

## 三、这一批**没做**的（按风险/成本排序，留给后续批次）

| 项 | 为什么先不做 |
|---|---|
| **Coomi 三档审批**（`read_only / write / destructive`） | 要改 `AgentTool` 契约 + 逐个工具标级 + UI 弹层，属接口变更；且会改变交互节奏，需真机验收 |
| 备份 ZIP 加密 | 牵涉备份格式版本与前向兼容；可先做「不备份 GitHub PAT」的止血版 |
| 全仓 247 处 JSON 硬转换安全化 | 与 hotfix33 同源，但已被 `ToolExecutor` catch 成「该步失败」，不崩 App；机械替换宜单独一批 |
| `_messages` 读改写统一加锁 | 需要在 5 个写入点抽公共方法，改动面横跨发送/停止/失败/事件四条路径 |
| 每条消息全量序列化落盘 | 要改成增量日志 + 压缩，属于存储层重构 |

---

## 四、复盘：为什么会漏

1. **端到端没人串过**：模块各自自洽（loop 只管一回合、store 只管落盘），接口之间「谁负责把历史接上」没有归属。
2. **注释当规格书用，但没人核对**：`回复「继续」即可接着做`、`下一次请求的历史` 两处文案都在描述一个不存在的实现。
3. **配置类缺陷不进编译门**：cleartext、超时、默认值不一致，编译器全都不管；只能靠真机或体检发现。
