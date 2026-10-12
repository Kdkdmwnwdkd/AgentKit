# AgentKit 调研知识库 · 总索引

> 本目录是「手机全功能 AI 开发创作台」方向的调研沉淀。原则：**不照搬代码，吃透架构后重写为 AgentKit 自己的实现**。每份文档末尾都有「我们的实现」章节，供分步实现时直接作为任务书依据。

## 调研范围（2026-10-12）

| 对象 | 仓库 | ★ | 许可证 | 角色 |
|---|---|---|---|---|
| Operit AI | AAswordman/Operit | 8591 | LGPL-3.0 | 最全能的同类，架构参考（代码不可抄） |
| RikkaHub | rikkahub/rikkahub | 8145 | AGPL-3.0 | Provider 抽象与媒体生成模块蓝本（架构参考） |
| Coomi | TensorHub-ORG/Coomi | 146 | Apache-2.0 | SKILL/MCP 生态思路（代码可借鉴） |
| AiCode | jieapi/AiCode | 267 | GPL-3.0 | 终端/容器/编辑器/检查点架构（架构参考） |
| zafiro | niki914/zafiro | 246 | **MIT** | **定位最重叠的同类，可深度参考** |
| iris_android | nerve-sparks/iris_android | 295 | Apache-2.0 | 本地 GGUF 推理的 Android 实现样板 |
| Termux | termux/termux-app | 62444 | GPLv3 | 终端组件行业标准（许可证雷区） |
| MCP Kotlin SDK | modelcontextprotocol/kotlin-sdk | 1467 | MIT | MCP 客户端直接集成 |

## 四层架构（自底向上，禁止反向依赖）

```
┌─ 第4层 交付层 build/       CI 打包链路产品化（AgentKit 独有优势）
├─ 第3层 创作层 media/       生图/视频 Provider 抽象（复用第1层模式）
├─ 第2层 执行层 dev/ + mcp/  终端/容器/编辑器/Git/MCP——Agent 的手脚
└─ 第1层 模型层 llm/         Provider 抽象 + Claude/Gemini/本地 llama.cpp
```

## 九步集成顺序（每步可独立编译验收）

| 步 | 动作 | 依据文档 | 验收点 |
|---|---|---|---|
| 1 | LLMClient 拆 Provider 接口（纯 Kotlin） | 01 | 现有对话不回归 |
| 2 | Claude / Gemini Provider | 01 | 三家切换对话 |
| 3 | llama.cpp JNI + GGUF 管理 | 02 | 本地离线对话 |
| 4 | 终端组件（ReTerminal MIT 或自绘） | 03 | App 内敲命令 |
| 5 | 容器管理（Alpine 内置 + Ubuntu 按需） | 03 | 容器内跑 node/python |
| 6 | 代码编辑器 + Git | 04 | 改文件/看 diff/提交 |
| 7 | MCP 客户端 | 05 | 接入 stdio MCP server |
| 8 | 生图/视频工坊 | 06 | 异步任务出图 |
| 9 | 「构建」按钮（推 GitHub→CI→拉回→安装） | 07 | 手机出 APK |

## 文档清单

| 文档 | 内容 |
|---|---|
| [01-provider-abstraction.md](01-provider-abstraction.md) | 模型层：Provider 接口设计与三家协议适配 |
| [02-local-llm.md](02-local-llm.md) | 本地模型：llama.cpp JNI 集成与 GGUF 管理 |
| [03-terminal-container.md](03-terminal-container.md) | 执行层：终端组件选型与 proot 容器管理 |
| [04-editor-git.md](04-editor-git.md) | 编辑器与 Git 集成 |
| [05-mcp.md](05-mcp.md) | MCP 客户端架构 |
| [06-media-generation.md](06-media-generation.md) | 生图/视频 Provider 与异步任务模型 |
| [07-build-pipeline.md](07-build-pipeline.md) | 构建交付链路（自有 CI 产品化） |
| [08-competitor-architecture.md](08-competitor-architecture.md) | 五个同类应用的架构总剖析 |
| [09-licenses.md](09-licenses.md) | 许可证决策记录（开源路线保障） |

## 硬性原则

1. **许可证**：只用 MIT/Apache-2.0/LGPL（库方式）代码；RikkaHub/Operit/AiCode/Termux 只吸收架构思想，实现全部重写
2. **不憋大招**：九步每步独立出可安装包，用户验收后再进下一步
3. **ViewModel 接口冻结**：新能力通过新模块+新页面接入，不改 ChatViewModel 已有公开接口
4. **设计体系不变**：所有新页面走 v3.1 暖纸规范（Color.kt/Dimens.kt/Shape.kt/Motion.kt）
