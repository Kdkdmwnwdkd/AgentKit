# 06 · 生图 / 视频 Provider 与异步任务模型

> 对应九步顺序的第 8 步。目标：App 内「创作工坊」——文生图、图生图、文生视频。复用 01 号文档的 Provider 抽象模式，新开 `media/` 模块，**不动模型层一行代码**。

## 参考项目

| 项目 | 许可证 | 参考什么 | 可用度 |
|---|---|---|---|
| rikkahub/rikkahub `mediagen/` | AGPL-3.0 | 媒体 Provider 抽象 + 异步任务模型的完整蓝本 | ❌ 架构参考（最值得学的一份） |
| nightmare-mobile（本地 SD 推理） | 调研参考 | 端侧 Stable Diffusion 的可能性与代价 | ⚠️ 远期备选 |

## 它怎么做的（架构思想）

RikkaHub 的 `mediagen/` 把「媒体生成」从 LLM 对话里干净地剥出来，四条设计：

1. **MediaGenerationProvider 接口与文本 Provider 并列同层**：
   `generateImage(prompt, ratio, refImages) / editImage(...) / generateVideo(prompt, ...)`——返回的不是图片而是**任务句柄**。
2. **Task 异步任务模型**（核心）：媒体生成是分钟级长任务，绝不能挂起等。提交 → 拿 `taskId` → 轮询/回调进度 → 完成拿 URL → 下载落盘。任务有状态机：`SUBMITTED → RUNNING → SUCCEEDED/FAILED`，**持久化**（杀进程重进 App 能看到历史任务续查）。
3. **Provider 各管各的协议**：阿里云 DashScope（通义万相，提交+轮询两段式）、火山方舟（异步任务）、MiniMax（轮询）、OpenAI images（同步返回，包成瞬时任务）。协议差异全在实现类里。
4. **参数模型独立于 Provider**：尺寸比例（1:1/16:9/9:16）、风格、参考图数量是 UI 概念，Provider 负责翻译成各家 API 参数。

**给我们的启发**：任务状态机 + 持久化是灵魂；Provider 翻译层与 01 的 `LLMClient` 实现类是同一个套路——**会写一个就会写另一个**。

## 我们的实现

### 模块位置

```
media/
├── MediaProvider.kt        // 接口 + 参数模型（UI 概念）
├── MediaTask.kt            // 任务状态机 + 持久化
├── providers/
│   ├── DashScopeMediaProvider.kt   // 阿里云通义万相（文/图/视频）
│   ├── VolcMediaProvider.kt        // 火山方舟即梦
│   ├── MiniMaxMediaProvider.kt     // MiniMax 海螺（视频强项）
│   └── OpenAIMediaProvider.kt      // DALL·E / gpt-image（同步包异步）
└── ui/MediaScreen.kt       // 抽屉新目的地「创作」：图片/视频 Tab + 任务进度 + 作品库
```

### 接口设计

```kotlin
interface MediaProvider {
    val id: String                                   // "dashscope" / "volc" / "minimax" / "openai"
    suspend fun submit(request: MediaRequest): MediaTaskHandle
    suspend fun poll(taskId: String): MediaTaskState  // 进度/完成/失败
}

data class MediaRequest(
    val kind: MediaKind,              // TEXT_TO_IMAGE / IMAGE_TO_IMAGE / TEXT_TO_VIDEO
    val prompt: String,
    val ratio: Ratio = Ratio.SQUARE,  // UI 概念，Provider 翻译成各家 size 参数
    val refImageUrls: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap()      // 逃生舱（n/seed/style 等）
)

sealed interface MediaTaskState {
    data class Running(val progress: Float?) : MediaTaskState
    data class Succeeded(val localPaths: List<String>) : MediaTaskState
    data class Failed(val message: String) : MediaTaskState
}
```

### 任务模型与持久化

```kotlin
enum class TaskStatus { SUBMITTED, RUNNING, SUCCEEDED, FAILED }

data class MediaTask(
    val id: String,                  // uuid
    val providerId: String,
    val providerTaskId: String?,     // 各家侧的任务 id
    val request: MediaRequest,
    val status: TaskStatus,
    val progress: Float?,
    val resultPaths: List<String> = emptyList(),
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
```

- 存储：JSON 文件 `filesDir/media/tasks.json`（与 MemoryStore 同款朴素方案，不自建数据库）
- 轮询器：`MediaTaskPoller`（WorkManager 或简单协程 + App 前台轮询、退后台 15 分钟一次）——**自用优先，不上 WorkManager 的复杂约束**，一个 `CoroutineScope` + 生命周期内轮询足够，杀进程就停，重进 App 按 tasks.json 续查
- 下载：完成的 URL 下载到 `filesDir/media/outputs/<taskId>/`，**本地路径才是资产的 truth**（URL 会过期）

### UI：创作工坊（走 v3.1 暖纸规范）

- 顶部 Tab：`图片` / `视频` / `作品库`
- 表单：prompt 输入（多行）、比例选择（四个胶囊按钮：1:1 / 16:9 / 9:16 / 4:3）、参考图（从相册选，转 base64 data URI）、Provider 下拉（按 kind 过滤可用的）
- 任务卡：提交后任务卡进「进行中」列表，进度条 + 状态文字；完成的通知**复用现有 NotificationTool 思路**（`automation/` 的通知通道），作品入库并弹「已保存到作品库」
- 作品库：网格布局，点击看大图，长按删除/分享（`mobile_share_files` 同款系统分享）

### 与 Agent 的联动（体验加分项）

新增 `AgentTool`：`generateImage(prompt)` —— Agent 对话里说"画只猫"直接出图，工具返回本地路径，ChatScreen 的 `UserMessage.images` 通道渲染。**这是 RikkaHub 没有做透的**（它的媒体生成是独立工坊，没接进 Agent 对话），我们把工坊和对话打通：工坊管精细参数，对话管随手生成。

### 国内 Provider 的现实约束

| Provider | 需要 | 备注 |
|---|---|---|
| 阿里云 DashScope | API Key（阿里云账号） | 通义万相，图/视频都有，文档全 |
| 火山方舟 | API Key + 开通推理 | 即梦，视频质量第一梯队 |
| MiniMax | API Key | 海螺视频，API 简单 |
| OpenAI images | Key + 海外网络 | gpt-image-1 同步返回 |

Key 管理沿用现有 ConfigStore（与 LLM Key 同库不同键），**不引入统一密钥库**（等开源前再统一做加密）。

## 注意事项

1. **本步排第 8 位的原因**：媒体生成是纯消费云端 API 的旁路，不依赖终端/容器/编辑器——**但它依赖 01 的 Provider 模式先立住**。前面步骤全部独立可验收，本步同样：只有 media/ + 一个新页面。
2. **AGPL 红线**：RikkaHub 的 mediagen 代码一行不抄，协议参数以各家官方文档为准自己写。
3. **视频生成的等待心理学**：视频任务 2-10 分钟。任务列表要有「后台跑，切页面不打断」的明确反馈，进度文案用百分比不用"生成中"三个字干等。
4. **存储配额**：图片单张 1-4MB、视频 10-50MB。作品库显示总占用，超 1GB 提示清理——**别等系统存储满才爆**。
5. **内容合规**：自用不管，但图片 prompt 不过三方审核是平台义务；开源前在 README 写清"生成内容责任由使用者承担"。
6. **本地 SD（nightmare-mobile 路线）远期备选**：端侧 SD 需要 vulkan 推理链 + 2GB 模型，体验与速度目前不如云端 API 十分之一——**先云端，本地留到 02 的本地 LLM 生态成熟后再说**。
