# 08 · 五个同类应用的架构总剖析

> 本文是四竞品 + 一个最重叠同类的架构速查。目的不是复刻，而是**搞懂每个选择背后的取舍**，让 AgentKit 的九步实现"知道自己在学谁、避开谁的坑"。详细到模块级的实现方案在 01-07 号文档。

## 总览对比

| | Operit AI | RikkaHub | Coomi | AiCode | zafiro |
|---|---|---|---|---|---|
| 定位 | 全能手机 Agent | 多模型聊天客户端 | SKILL 生态助手 | 移动开发环境 | 轻量 AI 客户端 |
| ★ | 8591 | 8145 | 146 | 267 | 246 |
| 许可证 | LGPL-3.0 | AGPL-3.0 | Apache-2.0 | GPL-3.0 | **MIT** |
| 语言/UI | Kotlin/Compose | Kotlin/Compose | Kotlin/Compose | Kotlin/Compose | Kotlin/Compose |
| 本地模型 | ✅ | ✅ llama.cpp | ❌ | ❌ | ✅ |
| 终端 | ✅ 配套 App | ❌ | ✅ Termux 兼容 | ✅ 内置 | ✅ |
| 容器 | ✅ Ubuntu | ❌ | ⚠️ 借用 Termux | ✅ proot+rootfs | ⚠️ 轻量 |
| 编辑器 | ⚠️ 基础 | ❌ | ❌ | ✅ | ❌ |
| Git | ❌ | ❌ | ❌ | ✅ | ❌ |
| MCP | ✅ | ❌ | ✅ | ❌ | ✅ |
| 生图/视频 | ❌ | ✅ 最强 | ❌ | ❌ | ❌ |
| 构建 APK | ❌ | ❌ | ❌ | ⚠️ 本地 gradle | ❌ |
| 我们能学什么 | 架构全景 | Provider/媒体抽象 | 生态引导 | 执行环境 | 产品克制 |

## 1. Operit AI（AAswordman，LGPL-3.0，★8591）

**架构**：单 App + 配套"系统 App"双包模式。主 App = Agent 核心（对话/工具/技能），配套 App = Ubuntu 容器宿主（proot + rootfs + sshd）。主 App 通过包名检测发现配套 App，引导安装后通过本地 socket 通信。

**分层**（自底向上）：`core/`（LLM/AgentLoop/工具协议）→ `automation/`（无障碍/截图/UI 树）→ `mcp/`（MCP 客户端）→ `terminal/`（终端会话）→ `ui/`（Compose）。

**学什么**：
- 双包分离：主 App 不被容器体积拖累，rootfs 可以做得很大——**这是"应用大一点也没事"的最优解**（用户原话），但代价是两个 App 的心智成本
- 工具协议的权限分级（每个工具声明所需权限，Agent 调用前过闸）

**避什么**：
- 代码不可抄（LGPL-3.0），且它的 UI 是早期 Compose 大杂烩，信息架构反例居多
- 无编辑器/无 Git/无构建——它的"写代码"停留在 Agent 改文件

## 2. RikkaHub（rikkahub，AGPL-3.0，★8145）

**架构**：三层清晰得罕见。`ai/`（Provider 抽象 + 5 家文本 Provider + 5 家媒体 Provider）→ `data/`（DataStore 序列化一切）→ `ui/`（聊天 + 设置 + 媒体工坊）。

**学什么**：
- `ai/Provider<T: ProviderSetting>` 接口设计（01 号文档已详析）——**本次调研中架构质量最高的一块**
- `mediagen/` 的 Task 异步模型（06 号文档已详析）
- DataStore 驱动一切配置，Provider 无状态可随时重建

**避什么**：
- AGPL-3.0：架构学不得代码，且**将来 AgentKit 开源若选 Apache/MIT，与 RikkaHub 的衍生讨论要零关系**——净室重写是铁律
- 它是聊天客户端，**没有执行层**（终端/容器/编辑器全无）——恰好是我们的主战场

## 3. Coomi（TensorHub-ORG，Apache-2.0，★146）

**架构**：SKILL 优先。`skill/` 包是核心（SKILL 声明 → 安装 → 执行 → 权限分级），MCP 作为 skill 的传输方式之一，聊天是载体。

**学什么**：
- Apache-2.0 是四竞品中唯一宽松许可证——**代码可借鉴**（借鉴≠照抄，仍要净室意译）
- SKILL 生态的用户引导：安装来源、信任等级、更新检查的完整 UX
- 它与 Termux 的互操作设计（检测 Termux → 借用环境 → 失败兜底）

**避什么**：
- 体量小、完成度低（146★ 的代价）：终端体验残缺、无本地模型、无媒体生成——**学它的生态思路，别学它的功能边界**

## 4. AiCode（jieapi，GPL-3.0，★267）

**架构**：移动开发环境单 App。`_armAssets/alpine-rootfs.bin` 内置 Alpine rootfs，proot 起容器，容器内 node/python/git 齐全；自带编辑器 + 文件树 + 检查点；LLM 通过 OpenAI 兼容端点。

**学什么**：
- **检查点机制**（04 号文档已详析）：Agent 改文件前打快照，可一键回滚——Agent 时代编辑器最重要的能力
- 内置 rootfs 的分发方式（tarball 进 assets + 首启解压）
- 容器与工作区的目录 bind 设计

**避什么**：
- GPL-3.0 代码不可抄；且它尝试设备本地 gradle 构建（07 号文档的反面教材：慢且 OOM）
- 无 Provider 抽象（LLM 层薄）、无媒体生成——它的边界就是我们的机会

## 5. zafiro（niki914，MIT，★246）

**架构**：定位与 AgentKit 愿景最重叠的同类：本地模型 + 终端 + MCP + 轻工具。单 App 纯 Compose，模块最小化。

**学什么**：
- **MIT 许可证，全仓库可深度参考**——五家里唯一能正大光明读实现细节的
- 产品克制：功能不多但每个都打磨过，**证明"少而整"比"大而糙"更有体验**（这正是用户"体验感好"诉求的对标）
- 终端与本地模型的集成形态

**避什么**：
- 无容器/无编辑器/无 Git/无构建——深度不足，我们的九步正是补它没做的

## 架构决策汇总（AgentKit 的站位）

```
                执行层            创作层          交付层
                (终端/容器/编辑器)  (媒体)          (构建)
RikkaHub          -                 ★              -
Operit            ★(双包)           -              -
Coomi             △(借Termux)       -              -
AiCode            ★(内置)           -              △(本地)
zafiro            △                 -              -
─────────────────────────────────────────────────────────
AgentKit     自建(单App+内置rootfs)  ★           ★(云端CI)
```

**我们的站位**：五家各占一到两个格子，**AgentKit 是唯一填满四个格子的**。且四个格子的技术基础（Provider 抽象、容器、JGit、GitHub API）全部有 MIT/Apache 的干净来源——这就是"化为自己的实现"的可行性依据。
