# W-23 批次记录：load_skill 动态装技能 + 本地模型三处修复

> 依据：用户视频反馈（本地模型不该要 API、内置下面空白）+ 缺口清单 #59（load_skill，P1）。

## 一、本地模型三处修复（用户视频指出）

### 1) 本地服务不该显示 API Key 字段
`ProviderCatalog` 里 Ollama / LM Studio / vLLM 都标了 `requiresApiKey = false`，
但 `ModelConfigScreen` **无条件渲染 Key 输入框**（`needsKey` 只用于判断"能否测试连接"）。
用户看到"本地模式 + Ollama"下面还有 `sk-…` 输入框，自然以为本地也要 Key。

→ 现在：`needsKey == false` 时不渲染 Key 字段，改成一行说明
「本地服务不需要 API Key（Ollama / LM Studio / vLLM 直接连本机端口）。填了也会被忽略。」

### 2) 一键切到内置模型
用户档案名为「本地模式」，但供应商选的是 Ollama——两者不是一个东西：
- **本地模型（内置）**：App 内 llama.cpp 进程内推理，无端点无 Key；
- **Ollama 等**：外部本地服务，要 Base URL（不需要 Key）。

→ 「模型接入」卡底部新增按钮「改用 App 内置模型（无需 API / 不联网）」，
点一下直接切 `local-llama`，并清空残留的 baseUrl / apiKey。

### 3) 内置模型下载源国内不可达（关键）
`GgufModelStore` 的 7 个内置模型 URL 全是 `huggingface.co`——**国内直连超时**，
用户点下载必然失败，于是「啥都没有」。

→ 下载改为**双源**：先 `hf-mirror.com`（实测 302 → 200，1.1GB 文件头正常），
失败回退官方源；`.part` 断点在多源间保留（取消才清理）。
另外「本地模型」页签本身是完整的（运行状态/已下载/模型目录/推理参数），
只是藏在第二个 tab，用户找不到——本地状态卡里加了直达按钮。

## 二、load_skill：Agent 自己装技能（#59）

四家里 Coomi / Operit / AiCode 都有。我们此前只有 `use_skill`（用已装的），
装技能只能人去技能中心手动导入。

| 文件 | 作用 |
|---|---|
| `tools/LoadSkillLogic.kt` | **纯函数**：URL 安全 / 内网判定 / 体积上限 / base64 容错 / id 白名单 |
| `tools/LoadSkillTool.kt` | `load_skill`：install_url / install_base64 / uninstall / list |
| `ui/ChatViewModel.kt` | 注册工具 + 流式下载（2MB 上限随时中止，不信 Content-Length） |

### 安全边界（两层独立）
1. **工具层**：只允许 https 公网（拒 file/ftp/内网/环回/链路本地/CGNAT）、体积 ≤ 2MB、
   id 白名单；base64 解码失败/空内容拒绝。
2. **安装器层**（既有 `ImportedSkillRepository`）：Zip Slip、zip bomb、目录越界、
   **强制降权为 imported**、重新导入撤销既有授权。

装进来的脚本**默认不可执行**，需用户在技能中心显式授权——工具返回文案里明确说了这一点。

### 热生效
安装/卸载后调 `reloadSkills()`：`skillsSnapshot` 重建 → `use_skill` 的 systemPrompt
与技能中心列表立刻更新，无需重启。

## 三、验证

- load_skill harness **14/14**（URL 8 + 体积 1 + base64 4 + id 1）。
- 既有 harness 未回归：edit/websearch 27/27、keypool 25/25。
- CI：见当批 run。
- APK 反验：W23 组 8 条池条目 + 4 条文案。
