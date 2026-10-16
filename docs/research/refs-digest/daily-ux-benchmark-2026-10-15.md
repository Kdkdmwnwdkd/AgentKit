# 四家参照仓库「全功能面」对标矩阵（2026-10-15 重扫版）

> 背景：用户指出此前调研「只吃透了大功能，小功能/日常功能没看」。本版**重新 clone 四家仓库**
> （`/root/.codebuddy/artifact/repos/`，main 分支，2026-10-15），以「用户每天打开 App 会用的
> 最基础操作」为纲做全功能面扫描，再逐项对照 AgentKit 源码现状（读码核实，非凭记忆）。
>
> 许可证纪律不变：**AGPL/LGPL/GPL 三家只读思路、禁止抄码；Apache-2.0（Coomi）深参仍净室重写**。
> 所有四家事实均带 `路径:行号`；AgentKit 现状均带文件路径。

## 〇、四家仓库与扫描面

| 家 | 地址 | 许可 | 源码文件 | 扫描面 |
|---|---|---|---|---|
| Coomi | TensorHub-ORG/Coomi | Apache-2.0 | 445 | Web UI 39 views + 30 components（功能地图最全） |
| RikkaHub | rikkahub/rikkahub | AGPL-3.0 | 922 | 聊天/历史/设置/消息组件/输入栏 |
| Operit | AAswordman/Operit | LGPL-3.0 | 1666 | 21 个 feature 模块 + 聊天组件 30+ 文件 |
| AiCode | jieapi/AiCode | GPL-3.0 | 591 | 17 个 Screen + README 功能清单 |

扫描分层：① 页面/功能域清单 → ② 每面交互入口（按钮/菜单/手势/设置项）→ ③ 日常触点细读
（聊天页、消息操作、会话管理、输入栏、提供商/模型、设置）→ ④ AgentKit 源码核对。

## 一、对标矩阵（日常功能面）

图例：✅ 有且等价 ｜ ◐ 部分有/形态不同 ｜ ❌ 无 ｜ — 不适用

### 1.1 聊天页日常触点

| # | 功能项 | Coomi | RikkaHub | Operit | AiCode | AgentKit 现状 | 定级 |
|---|---|---|---|---|---|---|---|
| 1 | 顶栏常驻「当前模型」显示 | ✅ ModelPickerSheet 常驻入口 | ✅ 顶栏标题第二行「助手/模型(提供商)」ChatPage.kt:607-615 | ◐ ChatHeader 有模型信息 | ✅ README「Settings → AI Vendors」 | ✅ hotfix11 指示条（独立行，v114） | **已追平** |
| 2 | 点顶栏标题改会话名 | ◐ | ✅ ChatPage.kt:598-602（titleState） | ◐ | — | ❌（只能抽屉长按重命名） | P1 |
| 3 | 聊天页菜单（导出/设置等） | ✅ | ✅ onClickMenu ChatPage.kt:634-636 | ✅ ChatHeader | — | ❌ | P1 |
| 4 | 「回到底部」悬浮按钮 | ✅ ChatView.vue:223 | ◐（滚动逻辑在 ChatList） | ◐ ChatScrollNavigator | — | ❌ | P2 |
| 5 | 消息流滚动位置记忆/跳转 | ◐ | ✅ ChatSizeChecker | ✅ ChatScrollNavigator/HeightMemory | — | ◐（LazyColumn 无记忆） | P2 |
| 6 | 语音会话模式 | ❌ | ✅ VoiceMode.kt | ◐ TTS 朗读为主 | ❌ | ❌ | P2 |
| 7 | 背景视觉效果（Mesh 渐变等） | ✅ ThemeSelect | ✅ MeshGradientBackground.kt | ◐ | ✅ 自定义背景图 | ◐（纯色主题） | P2 |
| 8 | 运行中「停止」按钮 | ✅ | ✅ | ✅ | ✅ | ✅ ChatStopButton | 已追平 |

### 1.2 消息操作（四家最重的日常面）

| # | 功能项 | Coomi | RikkaHub | Operit | AiCode | AgentKit 现状 | 定级 |
|---|---|---|---|---|---|---|---|
| 9 | **一键复制消息** | ✅ MessageBubble | ✅ ChatMessageActions.kt:99-100 + CopySheet（选择并复制/全部复制） | ✅ ChatArea.kt:838 onCopyMessage | ◐（编辑器复制） | ❌ **只有 SelectionContainer 手动选字** | **P0** |
| 10 | 重新生成 | ✅ | ✅ ChatMessageActions.kt:105 | ✅ ChatArea.kt:982 | ✅ | ✅ AssistantActions | 已追平 |
| 11 | 分支切换「◀ 2/3 ▶」 | ◐ | ✅ ChatMessageBranch.kt | ◐ | — | ✅ 变体切换器 | 已追平 |
| 12 | 编辑并重发 | ◐ | ✅ MessageSheetAction Edit | ✅ ChatArea.kt:931 编辑并重发 | ◐ | ✅ 「重新发问」对话框 | 已追平 |
| 13 | 朗读 TTS | ❌ | ✅ ChatMessageActions.kt:149 | ✅ ChatArea.kt:852 + 自动朗读 | ❌ | ❌ | P1 |
| 14 | 翻译消息 | ❌ | ✅ ChatMessageActions.kt:159 + translator 页 | ❌ | ❌ | ❌ | P2 |
| 15 | 分享消息 | ◐ | ✅ MessageSheetAction Share | ◐ | — | ❌ | P1 |
| 16 | 删除单条消息 | ◐ | ✅ MessageSheetAction Delete(destructive) | ◐ | — | ❌ | P1 |
| 17 | 收藏消息 | ❌ | ✅ MessageSheetAction favorite | ❌ | — | ❌ | P2 |
| 18 | 分叉成新会话 | ◐ | ✅ forkConversationAtMessage | ◐ | — | ❌ | P1 |
| 19 | 消息时间显示 | ◐ | ✅ ChatMessageActions.kt:178 | ◐ | — | ❌ | P2 |
| 20 | 推理过程折叠展示 | ✅ ReasoningBlock | ✅ ChatMessageReasoning.kt | ◐ | — | ❌（UI 层无 reasoning 展示；「回传历史思考过程」只是请求参数开关） | P1 |
| 21 | 消息 token/耗时统计行 | ✅ UsageView | ✅ ChatMessageNerdLine.kt | ✅ tokenstats 模块 | ✅ Token stats README | ✅ 助手气泡用量行（hotfix21） | P1 ✅ |
| 22 | WebView 渲染消息 | ❌ | ✅ render_with_webview | ❌ | ◐ | ❌ | P2 |

### 1.3 输入栏日常触点

| # | 功能项 | Coomi | RikkaHub | Operit | AiCode | AgentKit 现状 | 定级 |
|---|---|---|---|---|---|---|---|
| 23 | **图片附件入口** | ✅ AttachmentStrip | ✅ Add01 菜单 ChatInput.kt:339 + transferableContent 分享进图 :511 | ✅ AttachmentSelector/Chip/Preview 三件套 | ◐ | ✅ hotfix13（v119）：「＋」+ 缩略图行，多模态链路（UserMessage.images→image_url）接通 | 已追平 |
| 23b | **文件/文档附件（PDF/DOCX/文本）** | ◐ | ✅ README 明示 images/text/PDF/Docx 多模态输入 | ✅ 附件三件套 | ◐ | ✅ hotfix19（v122+）：文本类直读 / PDF 渲染成图 / DOCX 抽文本 / 其它明确拒绝 | 已追平 |
| 24 | 语音输入（ASR） | ❌ | ✅ AsrButton ChatInput.kt:344 | ◐ | ❌ | ❌ | P2 |
| 25 | 全屏输入对话框（长文本） | ◐ | ◐ | ✅ FullscreenInputDialog.kt | — | ❌ | P2 |
| 26 | 快捷指令/提示词库唤起 | ✅ PromptLibrary/QuickCommands | ◐ | ◐ PromptLibrary | ◐ | ❌（技能中心有但不在输入栏唤起） | P1 |
| 27 | 输入栏上方上下文工具条 | ✅ ContextTools | ◐ | ◐ | — | ❌ | P2 |
| 28 | 粘贴图片自动识别 | ◐ | ✅ transferableContent | ✅ AttachmentUtils | — | ❌ | P2 |
| 29 | 模型快捷切换（输入栏旁） | ✅ ModelPickerSheet 组件 | ◐（顶栏入口） | ◐ | — | ✅ hotfix11 指示条 | 已追平 |

### 1.4 会话管理

| # | 功能项 | Coomi | RikkaHub | Operit | AiCode | AgentKit 现状 | 定级 |
|---|---|---|---|---|---|---|---|
| 30 | 会话置顶/取消置顶 | ✅（按执行时间排序） | ✅ ConversationList.kt:303-311 | ◐ | — | ❌ | P1 |
| 31 | 会话搜索 | ✅ SessionsView | ✅ search 页 + FTS5 全文 | ◐ ChatHistorySelector | — | ❌ | P1 |
| 32 | 会话导出（Markdown/图片） | ✅ DataView | ✅ ConversationExport.kt | ✅ ExportDialogs | ✅ 备份 | ❌ | P1 |
| 33 | 重新生成会话标题 | ✅ | ✅ ConversationList.kt:321-325 | ◐ | — | ❌（仅首条消息自动命名） | P1 |
| 34 | 会话草稿独立保存 | ✅ README 明示 | ✅ | ◐ | — | ◐（输入框全局单草稿） | P1 |
| 35 | 删会话确认 | ◐ | ✅ | ◐ | ◐ | ✅ AppScaffold.kt:693-699 danger 确认「删除后不可恢复」 | 已追平 |
| 36 | 会话列表空态文案 | ✅ EmptyState | ✅ ConversationList.kt:123 | ◐ | ◐ | ❌（抽屉会话列表无空态） | P2 |
| 36b | 会话长按菜单（重命名/删除） | ◐ | ✅ DropdownMenu ConversationList.kt:299 | ◐ | — | ✅ AppScaffold.kt:645/668 长按菜单+确认 | 已追平 |

### 1.5 提供商/模型管理

| # | 功能项 | Coomi | RikkaHub | Operit | AiCode | AgentKit 现状 | 定级 |
|---|---|---|---|---|---|---|---|
| 37 | 提供商列表页（启用/模型数/管理） | ✅ ProvidersView | ✅ SettingPage | ✅ settings 模块 | ✅ ProviderEditor | ◐ hotfix11 前只有配置页；视频显示用户见过「提供商」列表在参照 App | **P0**（见下） |
| 38 | 测试连接 | ◐ | ◐ | ◐ | ◐ | ✅ TestConnectionButton（配置页内） | 已追平 |
| 39 | 模型列表拉取+搜索 | ✅ | ✅ listModels×3 | ◐ | ◐ | ✅ ModelPickerSheet+ModelListFetcher | 已追平 |
| 40 | 模型收藏夹 | ❌ | ✅ favoriteModels + ModelList 收藏区 | ❌ | ❌ | ❌ | P1 |
| 41 | 打开选择器定位当前项 | ◐ | ✅ ModelList.kt:411-458 | — | — | ❌ | P1 |
| 42 | Provider 分组折叠 | ◐ | ✅ ModelList.kt:377-388 | — | — | ❌ | P1 |
| 43 | 模型能力标签（视觉/工具/推理） | ◐ | ✅ ModelTypeTag/ModalityTag/AbilityTag :849-935 | ◐ | — | ❌ | P1 |
| 44 | 余额查询 | ◐ BalanceOption | ✅ ProviderBalanceText | ✅ token 模块 | ✅ Token stats | ❌ | P2 |
| 45 | 每提供商代理 | ◐ | ◐ | ◐ | ✅ README 明示 | ❌ | P2 |
| 46 | Key 多把轮询 | ❌ | ✅ KeyRoulette | ❌ | ❌ | ❌ | P2 |
| 47 | Provider 类型转换保留字段 | ✅ | ✅ convertTo + baseUrl 智能改写 | — | — | ◐（智能预填，无转换） | P2 |

### 1.6 设置/数据/全局

| # | 功能项 | Coomi | RikkaHub | Operit | AiCode | AgentKit 现状 | 定级 |
|---|---|---|---|---|---|---|---|
| 48 | 外观（明暗/动态取色/背景） | ✅ AppearanceView | ✅ themeId/dynamicColor/amoled | ✅ settings | ✅ Material You+背景图 | ◐ 五主题 token，无动态取色/背景 | P1 |
| 49 | Token 用量与成本统计 | ✅ UsageView | ✅ stats 页 | ✅ tokenstats | ✅ Token stats | ✅ 会话用量条+气泡行+抽屉 tokens（hotfix21） | P1 ✅ |
| 50 | 备份/恢复（含加密） | ✅ RestoreView | ✅ backup 页 | ◐ | ✅ 加密导出导入 | ❌ | P1 |
| 51 | 多语言 UI | ❌ | ✅ 10 语言 | ◐ | ✅ 中英 | ❌（仅中文） | P2 |
| 52 | 崩溃/日志可见性 | ◐ | ✅ log 页 | ◐ | ✅ CrashActivity | ✅ 日志页+崩溃横幅 | 已追平 |
| 53 | 断线恢复过程可见 | ✅ 继续重试/回撤 | ◐ | ◐ | ◐ | ✅ RetryBanner | 已追平 |
| 54 | 更新检查 | ✅ UpdatesView | ✅ UpdateChecker | ✅ update 模块 | ◐ | ✅（构建页链路） | 已追平 |
| 55 | 通知（完成提醒） | ◐ | ✅ 通知渠道 | ✅ | ◐ | ◐（前台服务通知，无完成提醒） | P1 |

## 二、差距清单（按日常使用频率 × 实现成本排序）

### P0（用户天天碰、四家全有、成本可控）——建议下一轮就做

| # | 项 | 依据 | 预估 |
|---|---|---|---|
| P0-1 | **消息一键复制**（助手+用户气泡操作区加「复制」；助手行：复制+重新生成并列） | 矩阵 #9，rikkahub/operit 都有；AgentKit 仅手动选字 | 0.5d |
| P0-2 | **输入栏图片附件**（选图入口+缩略图+随消息发送；多模态链路 `UserMessage.images→image_url` 已就绪，只缺 UI+VM 接线） | 矩阵 #23，三家都有；「截图问模型」是手机 Agent 高频场景 | 1.5d |
| P0-2b | **文件附件（PDF/DOCX/文本）**——用户验收时点名「别跟我说只能发照片，不能发文件」；第一版只做了图片是执行缩水 | 矩阵 #23b | 1d |
| P0-3 | **提供商/模型管理入口补齐**（对话页或设置里可直达「提供商列表」：启用/停用/模型数/测试，而不只深埋配置页） | 矩阵 #37；用户录屏里在参照 App 见过提供商页，AgentKit 无对应一级入口 | 1d |

### P1（日常会用到、价值明确）

P1-1 消息更多操作：分享/删除/分叉成新会话（#15/#16/#18）✅ hotfix17
P1-2 TTS 朗读 + 自动朗读开关（#13）✅ hotfix18（自动朗读开关未做）
P1-3 会话置顶 + 会话搜索 + 重新生成标题（#30/#31/#33）✅ hotfix16（搜索/置顶；重新生成标题未做）
P1-4 会话导出 Markdown + 全局备份/恢复（#32/#50）⏳ 下一项
P1-5 Token 用量统计（每模型/每会话）（#21/#49）✅ hotfix21
P1-6 模型选择器增强：收藏夹 + 打开定位当前项 + Provider 分组 + 能力标签（#40-43，rikkahub A4 的 P0 化）✅ hotfix20
P1-7 输入栏快捷指令/提示词库唤起（#26）⏳
P1-8 会话草稿独立保存（#34）⏳
P1-9 推理过程折叠 UI（#20）⏳
P1-10 外观增强：Material You 动态取色 + 完成提醒通知（#48/#55）⏳

### P2（增强，排期靠后）

语音输入（#24）/ 消息翻译（#14）/ 消息收藏（#17）/ WebView 渲染（#22）/ 全屏输入（#25）/
回到底部（#4）/ 循环进度条 / 自动压缩设置可见化（coomi AutoCompactionSettings）/
上下文工具条（#27）/ Key 轮询（#46）/ 每提供商代理（#45）/ 多语言（#51）/ 余额查询（#44）/
消息时间显示（#19）/ 会话删除确认（#35）/ Provider 类型转换（#47）

## 三、已追平项（证明逐项对过，不是只挑刺）

三家 listModels 端点同构（ModelListFetcher vs rikkahub 3.1.7）；自研 Markdown 渲染 +
代码块复制（coomi A5）；断网恢复横幅 + 退避（coomi A3 部分）；消息分支/重新生成/从中间
重问（rikkahub A2 节点游标的等价形态）；多模型配置档案（rikkahub Provider 体系简化版）；
会话持久化/重命名/删除；当前模型常驻显示+一键切换（hotfix11，v114，rikkahub ChatPage.kt
:607-615 的等价形态）；设置 13 组组织；日志/崩溃可见性；本地 llama 推理；终端/容器/编辑器/
MCP/创作工坊/构建推送全链路。

## 四、诚实声明

1. 本矩阵基于四家 **main 分支 2026-10-15 快照**；RikkaHub/Operit/AiCode 为 AGPL/LGPL/GPL，
   **只提取设计原理，落地必须净室重写**；Coomi 为 Apache-2.0，深参但仍重写。
2. 「AgentKit 现状」列均经本轮读码核实（ui/ChatScreen.kt、ui/ChatViewModel.kt、
   ui/ModelConfigScreen.kt、core/LLMClient.kt、ui/SettingsScreen.kt 等）；标 ◐ 处为
   「有组件但形态/入口不全」，标 ❌ 处为读码未发现（不排除极深路径存在，已按主要路径核）。
3. 预估工期为粗粒度；P0 三项互相独立，可分别提交验证。
