# W-16 批次记录：系统 TTS + 记忆自动写入 + BM25

> 依据：《四家对照-我们差什么》缺口 #4/#5（Operit Sherpa/自动保存、RikkaHub TTS/记忆防护）。

## 交付

| 文件 | 作用 |
|---|---|
| `audio/TextSpeaker.kt` | 系统 TTS 封装：初始化转 suspend（8s 超时）、语言/语速、≤3000 字、引擎缺失明确失败 |
| `tools/SpeakTool.kt` | `speak` 工具（text/language/rate） |
| `memory/MemoryAutoCapture.kt` | 显式信号抽取 + 归一化去重 |
| `memory/MemoryStore.kt` | 检索从 contains 计数换成 **BM25**（k1=1.2 / b=0.75） |
| `ui/ChatViewModel.kt` | 注册 speak；onCleared 释放引擎；send() 异步自动记忆 |
| `scripts/qa/MemoryRetrievalVerification.kt` + runner | 9 条纯函数断言 |

## 关键设计

- **只从用户消息抽取**（不抽模型输出）：模型输出可能含幻觉，写进长期记忆会自我强化。
- **归一化去重**：先去掉虚词/程度词（太/很/能/都/也…）再比相等/包含，Jaccard 兜底。
  **否定词（不/别/没）绝不进停用表**——否则「吃辣」与「不吃辣」会被合并。
- **BM25 而非向量**：零依赖、零下载；中文用二元组切分；标签命中 ×1.3。
- **不相关返回空**：不再「硬凑 topK」，记忆工具据此如实说「没找到」。

## 验证

- harness **9/9 PASS**（BM25 排序 4 + 自动抽取 4 + 去重 1）。
  **harness 抓出一个真实缺陷**：最初只靠 bigram Jaccard，短句加两个虚词就把相似度打到
  0.33（「我不吃辣」vs「我不太能吃辣」判为不同）→ 改为「归一化相等/包含」优先。
- CI run 186（`44095e2`）Build + Publish **success**。
- APK 反验全绿：`TextSpeaker` / `MemoryAutoCapture` / `SpeakTool` / `bm25Scores` 入池。
- 产物：30,143,577 B，sha256 `434ffff5…b368f`。

## 已知边界

- TTS 音色取决于系统语音包；无引擎设备会明确失败（提示改用文字）。
- 自动记忆只认四种显式句式；「随口提到的事实」仍不会自动入库（宁漏不滥）。
