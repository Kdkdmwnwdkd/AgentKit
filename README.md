# AgentKit

Android 本地 AI Agent —— Kotlin / Jetpack Compose，原创实现，四层分离架构。

一个跑在手机上的 AI 助手：多轮工具调用、三家大模型协议原生适配、屏幕理解与操控、
终端与容器能力、跨会话长期记忆。**零第三方新增依赖**，视觉与交互按自研规范落地。

> 规模：Kotlin 源码 **252 文件 / 66,658 行** ｜ 源码级 QA **69 个 harness** ｜ 版本号 = CI 构建号（`1.N`）
>
> 上面这两个数字会随提交漂移，别当契约看：`find app/src/main -name '*.kt' | wc -l` 自己数一遍更快。

---

## 文档索引（先读这三个）

| 文档 | 内容 |
|---|---|
| **[现状 · 路线图 · 踩坑记录](docs/AgentKit-现状与路线图-2026-10-16.md)** | 项目全景：做了什么 / 正在做什么 / 后面要做什么 / **踩过的坑逐条（现象→根因→修法→防复发）** |
| [全程报告](docs/AgentKit-全程报告-2026-10-15.md) | 决策沿革：能力建设、真机反馈两轮闭环、架构取舍 |
| [UX 规范](docs/agentkit-ux-spec-v1.md) / [视觉规范 v3](docs/agentkit-visual-spec-v3.md) | 交互规范与视觉规范（全 App 唯一事实来源） |

研究材料见 `docs/research/`（四家参照仓库的能力面扫描，只读思路、净室重写）。

---

## 能力一览

- **模型接入**：30+ 家供应商预设（DeepSeek / Kimi / 智谱 / 通义 / 豆包 / 文心 / 混元 / 星火 / MiniMax / 阶跃 / 百川 / Yi / 商汤 / Grok / Mistral / Groq / OpenRouter / 硅基流动 / Ollama / LM Studio / vLLM …），协议三类（OpenAI 兼容 / Claude / Gemini）+ 本地 llama.cpp；未收录端点一律 OpenAI 兼容兜底
- **对话体验**：流式打字机（含**非 SSE 供应商自动降级**）、思考过程折叠（三家推理字段原生解析）、消息复制/朗读/分享/从此处删除、文件与图片附件、Token 用量统计、会话置顶/搜索/草稿/导出 `.md` 真文件
- **会话与记忆**：生成中可切会话（后台续跑）、记忆库（列表/图谱双模式、可编辑、节点可拖动）、跨会话事件记忆沉淀
- **外观**：主题三选 + Material You 动态取色 + **全局照片背景**（浓度 5%–100% 自调，全 App 生效）
- **系统能力**：屏幕捕捉（真机实证）、shell 终端、无障碍 App 控制、本地模型推理、全局备份/恢复（含提示词库）
- **运行成本**：56 个工具**按组加载**——默认只带 `file` 组 3 个，模型用 `__use_tools` 自己开组、开过就一直开着（粘性）；设置页「工具范围」可手动关闭。全量 22327 字符 ≈ 10064 token，默认面 1243 字符 ≈ 525 token（省 94%）
- **稳定性**：JSON 空值安全化、429 专用退避（尊重 `Retry-After`）、断网自动恢复、崩溃日志留存

---

## 构建与验证

```bash
# 编译（干净构建；沙箱/CI 环境建议带 --rerun-tasks 确保完整校验）
bash gradlew :app:compileDebugKotlin --rerun-tasks

# 源码级 QA（主 harness；断言条数会随代码增长，跑完自己看结果行）
bash scripts/qa/run-hotfix11-verification.sh

# 工具面成本审计（真实工具描述，不是 harness 里的假数据）
python3 scripts/tool-surface-audit.py --verbose

# 全套回归（69 个 harness）
for f in scripts/qa/run-*.sh; do bash "$f" || echo "FAIL $f"; done
```

> harness 是**纯源码扫描**（无 JUnit、无模拟器），随代码入库；产品变更时同步更新断言并在注释写明理由。

---

## 工程纪律

1. 每轮交付闭环：干净编译 → 主 harness + 全套回归 → 提交（现象/根因/修法/证据）→ 推送 → CI 绿 → 产物**二进制反验**（哈希 · versionName · dex 符号）
2. **零新增外部依赖**（背景图解码、通知、动态取色均自写）
3. **净室重写**：参照仓库只读思路、不抄代码
4. **失败永不崩**：外部数据入口一律降级（JSON 安全访问、分享降级、通知静默降级）
5. **前向兼容**：落盘格式新增字段一律带默认值

---

## 仓库

- 地址：`https://github.com/Kdkdmwnwdkd/AgentKit`（分支 `main`）
- APK 产物：Release tag `latest`（CI 自动维护 `app-debug.apk`）
- 加速直链：`https://gh-proxy.com/https://github.com/Kdkdmwnwdkd/AgentKit/releases/download/latest/app-debug.apk`
