# AgentKit 体验规范 v1

> 版本：v1.0 · 依赖：《AgentKit 视觉风格规范 v2》（Signal Console · 信号控制台，`docs/agentkit-visual-spec-v2.md`）
> 定位：本文只做**体验层设计**——信息架构 / 交互流程 / 状态机 / 响应式 / 无障碍 / 文案。视觉规范已定的色值、字号、圆角、动效参数一律不推翻，冲突处在 §7.4 显式提出。
> 读者：前端工程师（Compose 落地）、视觉设计师（对齐）、产品（决策记录）。
> 设计主张（继承自视觉规范 §1.1）：**「把手机的每一次 AI 动作，变成可预审、可追踪、可回放的确定感。」**

---

## 0. 体验设计原则

### 0.1 从产品现实推导的三条体验原则

AgentKit 与普通聊天 App 的分界线是：**它真的操控用户的手机**（AccessibilityService + MediaProjection）。用户让渡的不只是注意力，是**设备控制权**。因此一切体验决策从这条公理出发：

| # | 原则 | 含义 | 对应设计主张 |
|---|---|---|---|
| 1 | **可预审 Foreseeable** | 任何动作发生前，用户能看到"将要执行什么"；任何降权发生时，用户能看到"什么被拦截了" | 审批弹窗 / 信任降级可视化 |
| 2 | **可追踪 Traceable** | 任何动作进行中，用户知道走到第几步；任何状态变化，都有视觉与日志双通道 | STEP n/m / 状态点 / 运行日志 |
| 3 | **可回放 Replayable** | 任何动作完成后，会话内可回看、跨会话可导出、错误可定位 | 工具卡常驻 / 日志筛选展开 / 导出 |

### 0.2 控制权让渡的四个心理阶段（体验设计的检查框架）

用户把手机交给 Agent，心理上经历四个阶段。每个阶段必须有对应的界面机制，缺一个就会在信任上断裂：

```
告知 INFOM → 授权 AUTHORIZE → 监视 MONITOR → 追溯 AUDIT
 │              │                │              │
 ▼              ▼                ▼              ▼
空态起飞前检查   审批弹窗/技能授权   STEP进度/状态点   运行日志/导出
权限中心        免询勾选          信号扫描          ERROR色条
```

### 0.3 本文不做的事（视觉规范已定，直接引用）

- 色值/对比度/字体/圆角/间距/动效参数 → 引用视觉规范 token（`--ak-*`）与章节号
- 组件视觉结构（审批弹窗布局、ToolCard 结构、抽屉样式）→ 引用视觉规范 §5.x
- 本文新增的只有：**状态定义、状态转移、信息架构、流程、文案模板、无障碍语义、响应式行为**

---

## 1. 信息架构（IA）

### 1.1 现状诊断（基于现有代码）

| 问题 | 现状 | 体验后果 |
|---|---|---|
| 自动化页是"巨无霸"单页 | `AutomationScreen.kt` 一个滚动页塞了 6 个一级主题：状态总览/总开关/屏幕捕获/设备感知/技能中心/运行日志/记忆库 | 用户找一个功能要滚三屏；日志和记忆被埋在自动化设置里，定位问题路径极长 |
| 全局入口隐藏 | 顶栏 settings/tune 两个无标签图标（`ChatScreen.kt` L149-155） | 不可发现；图标语义含糊（settings ≠ 技能/日志/记忆） |
| 一级导航负载不均 | 底部 Tab「对话/模型/自动化」中，自动化一个 Tab 承载 6 个主题 | 违反"固定分区、稳定位置"（视觉规范 §1.4-4） |
| 无角标/待办概念 | 权限缺失、新 ERROR 全靠用户主动翻 | "需要处理的事"不发光，注意力被训练成忽略 |

### 1.2 IA 设计决策（5 条）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **底部 Tab 保持 3 个不变**（对话/模型/自动化，视觉规范 §5.5 已定），但「自动化」Tab 重组为**控制台仪表盘**：三宫格状态 + 总开关 + 设备感知实时上下文 + 4 个二级页快捷入口 | 不推翻视觉已定结构；把"巨无霸"拆成"仪表盘 + 二级页"，可寻性质变。Tab 语义从"自动化设置"变为"控制台" |
| D2 | **抽屉作为全局导航**（视觉规范 §7.2 P1 已定要建），承载 3 个分组：主要（镜像 Tab）/ 控制台（4 个二级页）/ 系统 | 审批、日志、记忆、技能信任是 AgentKit 的一等公民，必须有一级可达性，不能再藏在某个 Tab 的深处 |
| D3 | **4 个二级页独立成页**：权限中心 / 技能中心 / 运行日志 / 记忆库；二级页压在三 Tab 之上，底部 Tab 全局常驻 | 每个主题有稳定 URL（Compose route）和稳定位置；用户在日志里定位到问题后，一步跳到模型页改配置，不迷路 |
| D4 | **审批不是导航项** | 审批是对话流中实时发生的模态事件（`pendingApproval`），不是"一个页面"。给它做入口等于鼓励用户预先浏览审批——错误心智。审批的"历史"由运行日志承载 |
| D5 | **零新建"使用手册/插件"类玄枵结构** | AgentKit 没有插件体系（技能是能力扩展不是功能页）；帮助信息收敛到「关于」页，不占一级导航 |

### 1.3 完整导航结构图

```
AgentKit（AppNav：底部 3 Tab 常驻 + 抽屉 overlay/permanent）
│
├── 底部 Tab（BottomNav，视觉 §5.5）
│   ├── 对话      / CHAT       route: chat       ← 默认落地页
│   ├── 模型      / MODEL      route: model
│   └── 自动化    / AUTOMATION route: console    ← 控制台仪表盘（D1 重组）
│
├── 抽屉（Drawer，视觉 §5.4；窄屏 overlay，宽屏 permanent，见 §4.2）
│   ├── Header：应用标记 + AgentKit + v1.0 · SANDBOX READY
│   │          + 全局就绪胶囊（success「● 系统就绪」/ warning「● N 项待授权」，见 1.6）
│   ├── 分组：主要 / PRIMARY
│   │   ├── 对话    / CHAT        → chat
│   │   ├── 模型    / MODEL       → model
│   │   └── 自动化  / AUTOMATION  → console
│   ├── 分组：控制台 / CONSOLE
│   │   ├── 权限中心  / PERMISSIONS → permissions   [红点：有关键权限缺失]
│   │   ├── 技能中心  / SKILLS      → skills        [计数：已安装技能数]
│   │   ├── 运行日志  / LOGS        → logs          [红点：有未读 ERROR]
│   │   └── 记忆库    / MEMORY      → memory        [计数：记忆条数]
│   └── 分组：系统 / SYSTEM
│       ├── 关于        / ABOUT    → about
│       └── 开源许可    / LICENSES → licenses
│
└── 二级页（各自 Scaffold + 返回；底部 Tab 常驻）
    ├── 控制台 console（重组后的「自动化」Tab 本体）
    │   ├── 状态总览三宫格（无障碍服务 / 总开关 / 屏幕捕获）
    │   ├── 总开关 + 「前往系统设置开启无障碍服务」
    │   ├── 设备感知实时上下文（前台应用 + 最近通知）
    │   └── 快捷入口 2×2：权限中心 / 技能中心 / 运行日志 / 记忆库（各带角标）
    │
    ├── 权限中心 permissions（从 console 或抽屉进入）
    │   ├── 权限列表（每项：状态点 + 说明 + 主按钮「去开启」/「已就绪」）
    │   │   ├── 无障碍服务 ACCESSIBILITY（关键·阻断级）
    │   │   ├── 屏幕捕获 SCREEN CAPTURE（关键·截图视觉）
    │   │   ├── 通知访问 NOTIFICATIONS（增强）
    │   │   └── 使用情况访问 USAGE ACCESS（增强）
    │   └── 全部就绪态：success 三宫格 + 「所有权限已就绪」
    │
    ├── 技能中心 skills
    │   ├── 「导入技能」→ URL / 本机 zip / 内置示例（三条路径）
    │   └── 技能卡片列表（信任徽章 + 权限 chips + toggle + 授权/撤销/卸载）
    │
    ├── 运行日志 logs
    │   ├── 筛选 chips：ALL / INFO / WARN / ERROR
    │   ├── 日志条目（级别点 + 级别缩写 + 时间戳 + 来源 chip + 消息 + 展开堆栈）
    │   └── 操作：刷新 / 导出（复制到剪贴板）/ 清空（二次确认）
    │
    └── 记忆库 memory
        ├── 搜索框 + 「搜索」
        ├── 记忆列表（kind 徽章 + 内容 + tags + salience + 「忘记」）
        └── 「手动记住一条」+ 全部记忆 / 清空记忆（二次确认）
```

**迁移路径（前端可分阶段落地，不必一次性拆完）**：
- 阶段 1（P1 前段）：抽屉 + 角标 + 4 个入口先做"锚点跳转"——进 `console` 后 `scrollTo` 到对应 SectionCard；
- 阶段 2（P1 后段）：SectionCard 抽成独立 route，console 只留仪表盘与快捷入口；
- 阶段 3（P2）：日志筛选/展开、记忆搜索精修。

### 1.4 与参考 App「玄枵」IA 的差异及理由

| # | 玄枵 | AgentKit | 理由 |
|---|---|---|---|
| 1 | 抽屉有「插件」分组（AI 绘图/小说写作台/番外编辑） | 无插件分组；技能归「控制台」组 | AgentKit 的技能是**能力扩展**（工具+脚本），不是独立功能页；把技能和权限/日志/记忆放在一起，强化"控制台"心智 |
| 2 | 首页 = 三宫格角标入口 + 技能卡片列表 | 首页（对话 Tab）= 对话流 + 空态起飞前检查 | AgentKit 的核心任务是"发指令→看执行"，不是"浏览功能入口"。功能入口收进抽屉，首屏留给任务本身 |
| 3 | 底部 Tab = 聊天/使用手册/设置 | 底部 Tab = 对话/模型/自动化 | 「模型配置」是使用前提（没 Key 不能对话）、「自动化控制台」是信任核心——两者必须是一级，不能和"使用手册"同级 |
| 4 | 权限管理 = 独立一级页，三段 Tab + 九宫格 | 权限中心 = 二级页，四项列表 | 权限是**手段不是目的**。但可达性不能丢：抽屉一级入口 + 控制台红点 + 快捷入口，三处可达 |
| 5 | 无审批/日志/记忆概念 | 审批（对话内模态）+ 日志 + 记忆均为一等公民 | 这是 AgentKit 的差异化：玄枵的 AI 不操控手机，所以不需要"执行留痕"体系 |
| 6 | 抽屉菜单项带拼音注音 | 分组标题中文 + mono 英文微标签双行；菜单项单行中文 | 继承视觉规范 §1.3-2：拼音零信息增益，mono 微标签保留层次感 |

### 1.5 导航项命名表（中文 + mono 英文微标签双行约定）

> 规则（继承视觉规范 §3.3-5）：**分组标题**用双行（中文 12sp w500 + mono 10sp 微标签）；**菜单项保持单行中文**，不加微标签（降低扫读成本）。微标签仅分组标题一处，不扩散。

| 层级 | 中文名 | mono 微标签（分组标题用） | 图标（24dp 线性格，1.5dp stroke） |
|---|---|---|---|
| 分组 | 主要 | `PRIMARY` | — |
| 分组 | 控制台 | `CONSOLE` | — |
| 分组 | 系统 | `SYSTEM` | — |
| Tab/菜单 | 对话 | — | `chat` 对话气泡 |
| Tab/菜单 | 模型 | — | `tune` 调参 |
| Tab/菜单 | 自动化 | — | `dashboard` 仪表盘 |
| 菜单/二级页 | 权限中心 | — | `shield` 盾牌 |
| 菜单/二级页 | 技能中心 | — | `extension` 拼图 |
| 菜单/二级页 | 运行日志 | — | `description` 文档 |
| 菜单/二级页 | 记忆库 | — | `bookmark` 书签 |
| 菜单 | 关于 | — | `info` 信息 |
| 菜单 | 开源许可 | — | `policy` 许可 |
| 分区标题 | 状态总览 | `STATUS` | — |
| 分区标题 | 设备感知 | `DEVICE SENSE` | — |
| 分区标题 | 信任等级 | `TRUST LEVEL` | — |

### 1.6 角标设计规则

**总规则（继承并扩展视觉规范 §5.4「角标规则」）**：
1. **零不显示**：所有角标在计数值/待处理数为 0 时一律不渲染，减少噪音；
2. **红点 vs 计数分工**：红点 = "有事需要你处理"（待办/未读，pulse 8dp `--ak-danger`）；计数胶囊 = "系统存量"（中性 `surface-4` 底 + `on-surface` 字，mono 10sp，高 ≥18dp，radius full，**不脉冲**）；
3. **同一状态多处显示是允许的**（抽屉项 + Tab + 快捷入口），用户从任一处都能发现问题；清除条件一致；
4. 红点只在用户**看过**之后清除（见清除条件），不用定时消失——待办不能自己假装被处理了。

| 入口 | 角标类型 | 语义 | 显示条件 | 清除条件 |
|---|---|---|---|---|
| 对话 Tab + 抽屉项 | 红点 pulse | 运行完成时 App 不在前台，有未读回复 | 回复完成 ∧ 页面不可见 | 进入对话页（滚动到底部即已读） |
| 自动化 Tab + 抽屉项 | 红点 pulse | 关键权限缺失（无障碍服务未开 ∧ 总开关相关） | 任一关键权限未就绪 | 全部关键权限就绪 |
| 权限中心（抽屉+快捷） | 红点 pulse | 同「自动化」（同一状态源，冗余可达） | 同上 | 同上 |
| 技能中心 | 计数胶囊 | 已安装技能总数（含内置） | 数量 > 0（常驻，中性不抢注意力） | 不清除 |
| 运行日志 | 红点 pulse | 有未读 ERROR 级条目 | 新 ERROR 写入 ∧ 日志页不可见 | 进入日志页（ERROR 筛选视图渲染过即已读） |
| 记忆库 | 计数胶囊 | 记忆条数 | 数量 > 0 | 不清除 |
| 模型 | 无 | — | — | — |

**抽屉 Header 全局就绪胶囊**（UX 对视觉 §5.4 组件的状态定义）：`success`@12% 底 + `success` 11sp「● 系统就绪」；任一关键权限缺失时切 `warning`@12% + 「● N 项待授权」。这是全局信任状的唯一固定位置，常驻不消失。

### 1.7 路由表（Compose Navigation 直译）

```kotlin
// 底部 Tab（一级）
"chat"       // 对话
"model"      // 模型配置
"console"    // 自动化控制台（仪表盘）
// 二级页（压在三 Tab 之上，底部 Tab 常驻）
"permissions" // 权限中心
"skills"      // 技能中心
"logs"        // 运行日志
"memory"      // 记忆库
"about"       // 关于
"licenses"    // 开源许可
// 全局模态（非路由，是状态）
pendingApproval: ApprovalRequest?   // 审批弹窗，见 §3.1
```

---

## 2. 核心用户流程（6 条）

> 每条流程给出：步骤流 / 用户决策点 / 系统反馈 / 失败分支 / 视觉状态引用（token 名，对应视觉规范 §2.7）。
> 流程编号 F1–F6；状态机引用见第 3 章。

### F1 首次启动 → 配置模型 → 发出第一条指令

**目标**：零经验用户在 90 秒内完成模型配置并发出一条真实指令。核心是「Key 自检 + 测试连接」把"填错 Key"这个最高频挫败点前置消灭。

```
S0 冷启动落地 chat 空态
   └─ 空态 hero 卡（radius-xl 28，surface-2）：
      · 56dp 圆图标底（primary@14% + 30dp primary 盾牌线图标）
      · headline「你好，我是 AgentKit」
      · 「起飞前检查」三行清单（可预审 DNA 的延伸）：
          ① 模型配置     ⚠ 未配置 → 点击跳 model
          ② 无障碍服务   ⚠ 未开启 → 点击跳 permissions
          ③ 屏幕捕获     ○ 可选   → 点击跳 permissions
      · 主按钮「去配置模型」（48dp，radius-s 10，primary 实心）
      · 三条示例建议卡（surface-2，点击填入输入框）
S1 model 页：Base URL / API Key / 模型名（OutlinedTextField，machineStrong 13sp）
   └─ Key 自检行实时刷新（每次输入）：mono 11sp「长度 56 · sk-a…7F3A」
      · 正常：6dp success 点 + 「校验通过」on-surface-var
      · 异常：danger 点 + 「检测到 3 个不可见字符，保存时自动清除」danger
S2 测试连接（按钮状态机，视觉 §5.6）：
   idle → testing（18dp 圆环 2dp + 「测试中… 1.2s」mono 计时）
        → ok：success-container 底 + Check + 「连接正常 · 214ms」
        → fail：danger-container 底 + 「401 · Key 无效」+ 12sp danger 建议文字
   ※ 最小请求 ping，不污染对话历史，几乎零 token 消耗
S3 保存（ConfigSanitizer 清洗 → DataStore 落盘）→ Snackbar「已保存」→ 回 chat
   └─ 空态检查行①变 success（6dp 点 + 文案「已配置 · sk-a…7F3A」）
S4 输入指令 → 发送 → 用户气泡（primary-container）+ 头像外圈 2dp primary 弧线旋转（发送中）
   └─ AI 气泡 streaming（尾部 2×14sp primary 闪烁块 800ms loop）
S5 若指令触发危险工具 → 进入 F2；若权限未开 → 系统提示胶囊 + 引导跳转（**绝不静默失败**）
```

| 步骤 | 用户决策点 | 系统反馈 | 失败分支 | 视觉状态引用 |
|---|---|---|---|---|
| S0 | 点「去配置模型」或检查行 | 空态 hero 渲染就绪状态 | — | radius-xl / primary@14% / headlineMedium |
| S1 | 是否粘贴 Key | 自检行实时（success/danger 点） | Key 过短/含不可见字符 → danger 行 + 自动清除承诺 | machineSmall / danger / success |
| S2 | 是否测试 | 按钮四态 + 结果卡（端点/指纹/响应预览） | 401→重新粘贴；429→稍后再试；code=-1→检查网络与 URL；5xx→稍后重试 | primary / success-container / danger-container / machineStrong |
| S3 | 保存或取消 | Snackbar + 空态①变绿 | 磁盘异常 → Snackbar「保存失败，请重试」 | inverse-surface |
| S4 | 发什么 | 发送中弧线 / streaming 闪烁 | 模型未配置 → 输入框上方 warning 条「先完成模型配置」+ 跳转按钮 | primary / primary-container |

---

### F2 一次危险操作的完整生命周期（招牌流程）

**目标**：这是 AgentKit 与普通聊天 App 的分界线。每个状态都有明确的视觉对应，用户全程"心里有底"：事前能看、事中能数、事后能查。

```
用户：「打开设置并点击 WLAN」
 │
 S1 对话页：用户气泡（primary-container，右上 4dp 尾角）
 │   └─ 系统提示胶囊：「正在规划操作…」（on-surface-ter 11sp，outline 胶囊）
 │
 S2 AgentLoop 推理 → 判定 ui_click 为危险工具 → approvalHandler 拦截
 │   └─ AI 气泡 streaming（尾部 primary 闪烁块）
 │
 S3 审批弹窗全屏阻断（视觉 §5.3，状态 PENDING）
 │   └─ scrim #000@64% fade 200ms；Dialog 入场 scale .9→1 spring 350ms
 │   └─ 顶部 microLabel「APPROVAL REQUIRED · 需要你的授权」danger
 │   └─ 56dp 圆盾牌图标（danger-container 底 + danger 描边）呼吸脉冲 1600ms
 │   └─ 「将执行」块：surface-4 + 左缘 2dp danger 竖条 + mono 回显（工具名+参数，包名 tertiary 强调）
 │   └─ 来源行：「来源：技能「设置导航」· 触发指令「打开设置并点击 WLAN」」
 │   └─ 免询勾选：「本次会话不再询问同类操作」（checkbox 40dp 视觉 + 48dp 触控）
 │   └─ 底部：3dp danger 进度线 + 「10s 后自动拒绝」machineSmall
 │
 S4 用户决策（五条路径，见 §3.1 状态机）
 │   ├─ 允许执行（未勾免询）→ APPROVED_ONCE
 │   ├─ 允许执行（勾免询）   → APPROVED_SESSION（sessionAllowed 记入，日志 WARN）
 │   ├─ 拒绝                 → DENIED
 │   ├─ 点外部/返回键        → DISMISSED ≡ DENIED
 │   └─ 10s 无操作           → EXPIRED → 自动 DENIED + 系统胶囊「审批超时，已自动拒绝」
 │   ※ 允许时按钮内 Check pop（scale 0→1.2→1，300ms）；退场 fade + scale .96，200ms
 │
 S5 执行（仅 APPROVED 分支）：ToolCard 状态机 QUEUED → RUNNING
 │   └─ QUEUED：outline@35% 描边 + pending 状态点
 │   └─ RUNNING：顶部 2dp primary 光带循环扫描 1500ms + 状态点 pulse 1200ms + STEP n/m
 │
 S6 结果回显
 │   ├─ DONE：success 绿点 pop（spring 300ms）+ mono 结果块 surface-4 + 时长 machineSmall
 │   ├─ FAILED：danger 红点 shake 200ms + danger@60% 描边 + ERROR 块 danger@8% 底 + 「重试」TextButton
 │   └─ DENIED：outline 中性描边 + 禁行图标 + 「已拒绝」（**不用红色**：拒绝是用户的正常决策，不是错误）
 │
 S7 日志留痕（AgentLogger）
 │   └─ WARN「Approval: ui_click 允许」+ INFO「ui_click 完成 214ms」
 │   └─ 遥测滚入：alpha 0→1 + translateY 12dp→0 250ms + 级别点 spring + WARN/ERROR 左缘色条 300ms
 │
 S8 记忆沉淀：本轮 event 记忆（salience 3），下次会话可回忆
```

| 状态 | 视觉状态（token） | TalkBack 播报 | 日志级别 |
|---|---|---|---|
| PENDING | scrim 64% + 呼吸盾牌 + 10s 进度线 | 模态聚焦；剩余 3s 时 polite 提示一次「即将自动拒绝」 | — |
| APPROVED_ONCE | Check pop 300ms | 「已允许一次」 | INFO |
| APPROVED_SESSION | Check pop + 系统胶囊「同类操作本会话不再询问」 | 「已允许，本会话不再询问同类操作」 | WARN |
| DENIED / DISMISSED | 退场 fade；对话流出现「已拒绝」ToolCard | 「已拒绝，Agent 将换方案或停止」 | WARN |
| EXPIRED | 进度线走完 + 系统胶囊 | 「审批超时，已自动拒绝」 | WARN |
| QUEUED | outline@35% 描边 | 「排队中」 | — |
| RUNNING | 2dp 信号扫描 + 状态点 pulse + STEP n/m | 「正在执行，第 n 步共 m 步」 | — |
| DONE | success 点 pop + mono 结果块 | 「执行成功，耗时 N 毫秒」 | INFO |
| FAILED | danger 点 shake + danger@60% 描边 + 重试 | 「执行失败，可重试」 | ERROR |
| DENIED（卡） | outline 中性 + 禁行 + 「已拒绝」 | 「已拒绝执行」 | — |

**失败分支与防骚扰规则**：
- 审批请求发出后 AgentLoop 挂起等待（`approvalMutex` 串行）；**超时 10s 自动拒绝**（当前实现无超时，会无限挂起——需 `withTimeoutOrNull`，见 §7.2 对齐点）；
- 同一指令内，同一工具+同一参数被用户拒绝后，AgentLoop **不得重复发起同一审批**（防骚扰规则，需循环层配合）；
- 用户在弹窗打开期间按 Home 离开 → 倒计时继续，到点 EXPIRED，不因离开而放宽（高风险不妥协）；
- 队列深度 >1 时，弹窗内右下角显示「+N 待处理」machineSmall（增强项，P2）。

---

### F3 技能导入 → 信任降级 → 显式授权 → 使用

**目标**：让"强制降权"这件事**看得懂、不误解**。核心设计：**信任等级（来源）与授权状态（权限）是两个正交维度**，徽章永远显示来源，footer 显示授权——防止用户误以为"授权后它就变成内置技能了"。

```
S1 skills 页 → 「导入技能」→ 三路径：URL / 本机 zip（SAF）/ 内置示例
S2 导入成功 → inline 状态「已导入：xx」+ 新卡片插入列表顶部
   └─ 同时降权立即生效（视觉上立即可见）：
      · 信任徽章「● 导入」warning@14% 底 + warning 字
      · allowScript = false（script 形态不能执行 main.js）
      · maxCapability 收紧：高危工具 chip 换 danger@12% 底 + danger 字（ui_input / adb.shell）
      · 展开明细：每个工具一行——allowed=primary 对勾「放行」/ blocked=danger 禁行「拦截」
S3 用户查看卡片（降权三处可见：徽章 + chips + footer）
   └─ footer：「脚本执行：未授权」warning + 「授权脚本」TextButton（primary）
S4 点「授权脚本」→ 授权确认 Dialog（**复用审批弹窗结构，warning 系变体**）
   └─ 标题「授权脚本执行」+ 盾牌图标换 warning 色系（警惕而非危险）
   └─ 正文：为什么降权（外部来源）+ 授权后果（获得与内置同等的脚本执行权限，可随时撤销）
   └─ 按钮：取消（Outlined，outlineStrong 描边）/ 确认授权（primary 实心）
S5 授权后：徽章不变（仍 warning「● 导入」——来源不可变）+ footer 变「脚本执行：已授权」success + 「撤销授权」
S6 可逆性：撤销授权 → 回降权态；卸载 → 二次确认 Dialog → 删目录 + 授权一并撤销
```

| 步骤 | 用户决策点 | 系统反馈 | 失败分支 | 视觉状态引用 |
|---|---|---|---|---|
| S1 | 选哪条导入路径 | 导入 Dialog | — | surface-3 / radius-l |
| S2 | 无（自动降权） | 卡片 warning 徽章 + blocked 工具行 | zip 损坏 → warning「导入失败：xx」；URL 不可达 → 同左，卡片不出现 | warning@14% / danger@12% / primary 对勾 / danger 禁行 |
| S3 | 是否展开明细 | 每个工具放行/拦截可见 | — | machineText / surface-4 |
| S4 | 授权 or 取消 | Dialog（warning 系） | — | warning / warning-container |
| S5 | 是否撤销 | footer 状态切换 + Snackbar「已撤销」 | — | success / warning |
| S6 | 卸载确认 | 二次确认 Dialog（破坏性按钮 danger 描边，不用填充——卸载不如"允许执行"重大） | 删除失败 → warning「卸载失败」 | danger / on-danger-container |

**反误解设计（本流程的灵魂）**：
1. 徽章（来源）与 footer（授权）**视觉分离**：徽章在 header，footer 在底部，永不同框；
2. 授权确认后徽章**不变色**——用户任何时候扫一眼，都知道这个技能仍是"导入来源"；
3. 每个高危工具在 chips 和展开明细中**双重列出**（danger chip + blocked 行），降权不靠用户"注意到"，靠"藏不住"。

---

### F4 出错排查：对话报错 → 通过日志/记忆定位问题

**目标**：对应"日记"诉求。技术异常在气泡里就翻译成人话 + 下一步；要深挖时，日志是结构化可筛选的，不是一盆纯文本。

```
S1 运行失败 → AI 气泡 failed 态（视觉 §5.1）
   └─ 1dp danger@60% 描边 + 气泡下「重试」TextButton + 「查看日志」TextButton
   └─ 文案三段式：结论 → 定位信息 → 下一步（见 §6.3）
S2 用户点「查看日志」→ logs 页（或抽屉进）
   └─ 筛选 chips：ALL / INFO / WARN / ERROR（选中 primary@12% 底 + primary 字）
S3 选 ERROR → ERROR 条目自己跳出来：左缘 2dp danger 色条 + danger 级别点 + ERROR 缩写
S4 展开条目 → 堆栈/响应体（surface-4 mono 块，danger@8% 底，maxHeight 160dp 滚动）+ 「复制」
S5 定位后行动：
   · 401/403 → 去 model 页重新粘贴 Key（自检行先排不可见字符）
   · 429/5xx/超时 → 点气泡「重试」
   · 工具失败 → 重试或检查权限中心
   · 技能脚本异常 → skills 页卸载该技能
S6 仍未解决 → 「导出」复制 500 条快照到剪贴板 → 贴进 issue/群
   ※ 辅助：memory 页搜索历史记忆，看是否踩过同样的坑
```

| 步骤 | 用户决策点 | 系统反馈 | 失败分支 | 视觉状态引用 |
|---|---|---|---|---|
| S1 | 重试 or 看日志 | failed 气泡（danger@60% 描边）+ 人话文案 | — | danger / danger@8% |
| S2 | 选哪个筛选 | chips 选中态 | 空日志 → 空态 hero | primary@12% / surface-4 |
| S3–S4 | 展开哪条 | 遥测滚入 + 色条 + mono 堆栈 | 堆栈过长 → maxHeight 160dp 滚动 | machineSmall / danger 色条 |
| S5 | 去哪修 | 各页可达（路由直跳） | — | 各页主按钮 |
| S6 | 导出 | Snackbar「已复制到剪贴板（N 字符）」 | 剪贴板不可用 → warning Snackbar | inverse-surface |

---

### F5 自动化开启流程：无障碍授权 → 屏幕捕获授权 → 总开关 → 状态可见

**目标**：四个授权动作有明确的依赖顺序和状态可见性；用户在系统设置里操作后回到 App，**状态立即刷新**（不靠猜）。

```
S1 console 页（或 permissions 页）查看三宫格：无障碍服务 / 总开关 / 屏幕捕获
   └─ 每格：8dp 状态点（success=已开启 / on-surface-ter=未开启）+ 名称 + 状态文字
S2 开总开关（SwitchRow）→ AutomationPrefs 持久化 + AutomationBridge.enabled 同步
   └─ 但无障碍服务未开时：开关旁 inline warning「还需开启无障碍服务才能操作手机」+ 主按钮「去开启」
S3 点「前往系统设置开启无障碍服务」→ 跳系统 ACTION_ACCESSIBILITY_SETTINGS
   └─ 用户在系统设置里开启本应用服务 → 返回 App
   └─ **onResume 自动 refreshGrants()**（当前实现仅进入页面时刷新——见 §7.2 对齐点）
   └─ 三宫格①变 success + 抽屉 Header 胶囊「● N 项待授权」数字减 1
S4 屏幕捕获：点「开始屏幕捕获（授权录屏）」→ 系统 MediaProjection 弹窗
   └─ 用户允许 → 前台服务启动 → 三宫格③变 success「捕获中」
   └─ 用户拒绝 → 卡片保持「未开启」+ inline 说明「拒绝后 Agent 无法"看见"屏幕，UI 树工具仍可用」
S5 通知访问 / 使用情况访问（增强项）：同样跳系统设置 + 返回刷新
S6 全部就绪：三宫格全 success + 抽屉胶囊「● 系统就绪」+ 权限中心全绿态
S7 设备感知实时上下文卡片：前台应用 + 最近通知（Agent 主动感知的可见化——"它现在知道什么"）
```

| 步骤 | 用户决策点 | 系统反馈 | 失败分支 | 视觉状态引用 |
|---|---|---|---|---|
| S1 | 无 | 三宫格状态点 | — | success / on-surface-ter |
| S2 | 开/关总开关 | Switch thumb spring 250ms + warning 提示（缺服务时） | — | primary / warning |
| S3 | 去开启/放弃 | 跳系统设置；返回自动刷新 | 用户没开就返回 → 仍 warning，红点不消失（待办不自己消失） | warning@12% |
| S4 | 允许/拒绝录屏 | 前台服务 + 三宫格刷新 | 拒绝 → 说明降级能力（UI 树仍可用） | success-container |
| S5 | 去开启/跳过 | 同 S3 | 同 S3 | warning |
| S6 | 无 | 全绿 + 就绪胶囊 | — | success |
| S7 | 无 | 实时上下文（每次发消息自动注入的可见化） | 无感知数据 → 空态文案「开启无障碍服务后切换 App，这里会实时显示」 | machineSmall / primary 徽章 |

**依赖顺序**：总开关 → 无障碍服务（关键）→ 屏幕捕获（关键）→ 通知/使用情况（增强）。权限中心列表按此顺序排列，未就绪项自动置顶。

---

### F6 回放与追溯：一次运行的完整回看（"可回放"的落地）

**目标**：把设计主张的第三个词"可回放"变成具体能力。三层回放深度，按优先级分期。

| 层级 | 能力 | 现状 | 落地阶段 |
|---|---|---|---|
| L1 会话内回放 | 一次 AgentLoop 的所有工具卡 + 审批结果**永久留在对话流**，不回滚、不折叠丢失 | 已有（UiItem 列表） | 保持；UX 要求：审批结果卡（含「已拒绝」）必须入流 |
| L2 跨会话回放 | 运行日志按天分文件 + 筛选 + 展开堆栈 + 导出快照 | 已有（AgentLogger） | UX 增强：级别缩写文字、筛选 chips、ERROR 色条（视觉 P1 日志条目） |
| L3 运行时间轴 | 「最近一次运行」时间轴视图：LLM 请求 → 工具调用 → 审批 → 结果，按序一排，可逐步点开 | 无 | **P2 增强**：日志页顶部「运行时间轴」入口，数据来自同一次 runId 的日志聚合 |

---

## 3. 状态机与空状态

### 3.1 审批弹窗状态机（招牌状态机）

```
                    ┌──────────────────────────────────────────────┐
                    │                 AgentLoop                    │
                    │          requestApproval(tool, args)         │
                    └──────────────────────┬───────────────────────┘
                                           ▼
                                       ┌───────┐
              ┌────────────────────────│ IDLE  │────────────────────────┐
              │                        └───┬───┘                        │
              │      弹出（scrim 64% + spring 350ms 入场）               │ 终态清理
              │                            ▼                            │ pendingApproval=null
              │                       ┌─────────┐                       │
              │                       │ PENDING │◄── 10s 倒计时（3dp     │
              │                       │  (展示)  │    danger 进度线）    │
              │                       └────┬────┘                       │
              │                            │                            │
     ┌────────┴────────┬─────────────┬─────┴──────┬────────────────┐   │
     ▼                 ▼            ▼            ▼                ▼   ▼
┌──────────┐   ┌─────────────┐  ┌────────┐  ┌───────────┐   ┌──────────┐
│APPROVED_│   │ APPROVED_   │  │ DENIED │  │ DISMISSED │   │ EXPIRED  │
│  ONCE   │   │  SESSION    │  │        │  │ (≡DENIED) │   │          │
└────┬─────┘   └──────┬──────┘  └───┬────┘  └─────┬─────┘   └────┬─────┘
     │                │             │              │               │
  放行本次        记入sessionAllowed  回填false      回填false     自动DENIED
  Check pop 300ms  + 系统胶囊提示     Agent换方案    同左          + 系统胶囊
     │                │             │              │               │
     └────────────────┴─────────────┴──────────────┴───────────────┘
                                    ▼
                          ToolCard 进入执行/拒绝分支（§3.2）
```

| 当前状态 | 事件 | 下一状态 | 副作用 | 视觉/反馈 |
|---|---|---|---|---|
| IDLE | Agent 请求审批 | PENDING | 弹窗入场 | scrim fade 200ms + scale .9→1 spring 350ms + 盾牌呼吸 1600ms |
| PENDING | 点「允许执行」未勾免询 | APPROVED_ONCE | 放行 | Check pop 300ms + 退场 fade+scale .96 200ms |
| PENDING | 点「允许执行」勾了免询 | APPROVED_SESSION | `sessionAllowed += toolName`；后续同类不再弹 | Check pop + 系统胶囊「同类操作本会话不再询问」 |
| PENDING | 点「拒绝」 | DENIED | 回填 false，AgentLoop 作为工具结果继续推理 | 退场 + 对话流「已拒绝」卡 |
| PENDING | 点 scrim / 返回键 | DISMISSED | **等同 DENIED**（不允许"忽略"危险操作） | 同 DENIED |
| PENDING | 10s 倒计时到点 | EXPIRED | 自动回填 false + 系统胶囊「审批超时，已自动拒绝」 | 进度线走满 + 退场 |
| 任一终态 | — | IDLE | `pendingApproval = null`（finally 保证，防卡死下一个工具） | — |

**设计说明**：
1. **超时自动拒绝是默认行为，不是可选变体**（视觉规范 §5.3 将其列为"可选安全变体"——UX 层升为默认，理由：① 用户被消息/电话打断是高频场景，无限挂起会让 Agent 假死；② 10s 是"看一眼并决策"的合理下限，倒计时同时是紧迫感来源；③ 需要更长时间的审阅可以随时重发指令。提供设置项可关闭，但不鼓励）。这是对视觉规范的显式修正建议，见 §7.4-5。
2. **免询勾选取代"始终允许"独立按钮**（落实视觉规范 §5.3 废弃三按钮的决策）：勾选 = 会话级白名单；不勾 = 单次。消灭"三个主按钮抢注意力"。
3. **DISMISSED ≡ DENIED**：点击外部关闭不允许被理解为"稍后处理"——危险操作没有"忽略"这个选项。
4. TalkBack：倒计时文本 `aria-live=off`（避免读屏用户被每秒播报轰炸），仅在剩余 3s 时 polite 播报一次。

### 3.2 工具调用卡状态机

```
        ┌─────────┐  开始执行   ┌─────────┐  成功   ┌──────┐
        │ QUEUED  │──────────▶│ RUNNING │───────▶│ DONE │
        └────┬────┘           └────┬────┘        └──────┘
             │                     │ 异常
             │ 审批被拒             ▼
             │                ┌─────────┐  用户点「重试」
             └───────────────▶│ FAILED  │──────────────┐
                              └─────────┘              │
             ┌─────────────────────────────────────────┘
             ▼ (回到 QUEUED)
   另：QUEUED 阶段审批被拒 → DENIED（终态，本卡不再变化）
```

| 状态 | 描边 | 状态点 | 动效 | STEP 语义 | 视觉引用 |
|---|---|---|---|---|---|
| QUEUED | `outline`@35% | outline 中性 8dp | 无 | —（未开始） | surface-2 / outline |
| RUNNING | `primary`@40% | primary pulse（alpha .4↔1 + scale .8↔1.1，1200ms） | 顶部 2dp primary 光带循环扫描 1500ms | `STEP 3/7` machineSmall 显示 | primary / on-surface-ter |
| DONE | `outline`@35% | success pop（scale 0→1.25→1，spring 300ms） | 无 | STEP 走满后隐藏 | success |
| FAILED | `danger`@60% | danger shake（±2dp ×2，200ms） | 无 | 停留在失败步 | danger / danger@8% 结果块 |
| DENIED | `outline`@35%（中性） | outline 中性 + 禁行图标 | 无 | — | on-surface-ter「已拒绝」 |

**STEP n/m 语义定义**（新增能力，当前代码无此概念，需 §7.2 对齐）：
- 触发条件：一次 AgentLoop 内计划/执行 ≥2 个工具调用时显示；
- `n` = 当前第几个工具，`m` = 本循环工具调用总数；m 未知时（流式追加）显示 `STEP n`；
- 视觉：mono 等宽（视觉规范 §3.3-2 数字等宽化），`on-surface-ter`；
- TalkBack：状态变化 polite 播报「第 n 步，共 m 步」。

### 3.3 网络/LLM 错误分级与文案策略

> 分级逻辑：**HTTP 语义 → 日志级别 → 视觉级别 → 文案策略 → 用户动作**。日志级别与视觉级别严格同源（ERROR→danger，WARN→warning），保证"日志里是什么级，界面上就是什么色"。

| 异常 | 日志级别 | 视觉级别 | 文案策略（结论→定位→行动） | 主行动按钮 | 可重试 |
|---|---|---|---|---|---|
| 401 / 403 | ERROR | danger | 「API Key 无效（401）」+ 端点 + Key 指纹（len/head/tail，绝不全显）+ 「到模型页重新粘贴 Key」 | 去模型配置 | 否（修好再试） |
| 400 / 404 | WARN | warning | 「请求被拒绝（400）」+ 「检查 Base URL 与模型名」 | 去模型配置 | 否 |
| 429 | WARN | warning | 「请求过于频繁（429）」+「稍后再试」 | 重试（退避） | 是 |
| 5xx | WARN | warning | 「模型服务暂时不可用（500）」+「稍后重试」 | 重试 | 是 |
| 超时 | WARN | warning | 「网络超时」+「检查网络连接」 | 重试 | 是 |
| 无网络 | WARN | warning | 「网络不可用」+「检查网络连接」 | 重试 | 是 |
| 工具执行失败 | ERROR | danger | 具体工具名 + 原因摘要 + 「重试或查看日志」 | 重试 / 查看日志 | 视工具 |
| JS 脚本异常 | ERROR | danger | 「技能「xx」执行出错」+「查看日志或卸载该技能」 | 查看日志 | 否 |
| 权限缺失 | WARN | warning | 「需要无障碍服务」+「去开启」 | 去权限中心 | — |

**文案铁律**（继承视觉规范 DNA"确定性"）：
1. 气泡文案必须包含**可定位信息**（端点/指纹/状态码），不让用户去翻日志才知道发生了什么；
2. 每条错误文案必须带**下一步动作**（按钮或明确指令），禁止只报错不指路；
3. Key 永不全文回显，只 `len / head / tail` 指纹；
4. 脱敏在离开日志系统前完成（`AgentLogger.sanitize`，现有实现已做，保持）。

### 3.4 空状态设计（5 种）

> 统一规格：hero 卡 `radius-xl 28dp`（视觉规范规定的唯一使用处）+ `surface-2` 底 + padding 24dp + 56dp 圆图标底（语义色@14%）+ headlineMedium 22/30 主标题 + bodyMedium 描述 + 主按钮 48dp `radius-s`。

| 空态 | hero 主标题 | hero 描述 | 主行动按钮 | 图标/色 |
|---|---|---|---|---|
| 空对话 | 你好，我是 AgentKit | 我可以看懂你的屏幕、读取通知，并替你完成手机上的操作。先完成「起飞前检查」，再发出第一条指令。 | 去配置模型 | primary 盾牌；附三行就绪检查清单 + 三条示例建议 |
| 空日志 | 还没有任何运行记录 | Agent 的每一步都会记在这里：LLM 请求与响应、工具调用与结果、审批决定、HTTP 错误。 | 去发第一条指令 | `log-debug` 色文档图标 |
| 空记忆 | Agent 还没有长期记忆 | 每次成功回答会自动沉淀一条记忆；你也可以手动记录偏好或关键信息，跨会话生效。 | 手动记一条 | tertiary 记忆徽章紫 |
| 空技能 | 当前只有内置技能 | 导入的技能默认降权运行：脚本执行需显式授权，高危工具默认拦截。 | 导入技能 | warning 色（警惕但不恐吓） |
| 无权限 | （非 hero，为引导态） | 权限中心：缺失项卡片自动置顶，每项带「去开启」主按钮；全部就绪后显示 success 三宫格 + 「所有权限已就绪」 | 去开启（每项） | danger 状态点（缺失）/ success（就绪） |

---

## 4. 响应式与适配

### 4.1 WindowSizeClass 策略

| 断点 | 宽度 | 设备 | 底部 Tab | 抽屉 | 内容区 |
|---|---|---|---|---|---|
| Compact | <600dp | 手机竖屏（主场景） | 常驻 64dp | **overlay**（clamp(280,82vw,320)）+ scrim 40% | 单栏，全宽 |
| Medium | 600–840dp | 折叠屏展开 / 小平板 | 常驻 64dp | overlay（同 Compact） | 单栏，页面边距 clamp(16,4vw,24) |
| Expanded | >840dp | 平板 / 折叠横屏 | 常驻 64dp | **permanent**（常驻左侧，无 scrim） | 限宽居中 max 680dp |

**决策说明**：
- Medium 不切 permanent 抽屉：折叠展开约 600–700dp，300dp 抽屉会吃掉一半宽度，对话气泡被压扁。overlay 保留"需要时才让位"；
- Expanded 保留底部 Tab（与 permanent 抽屉共存）：Tab 是"域切换"（对话/模型/控制台），抽屉是"全局入口+系统项"，两者职责不同，共存不冗余——工具类产品（IDE、控制台）的成熟范式；
- 单会话产品不做平板"列表-详情"双栏（避免为空架构增加复杂度）； Expanded 的增益体现在：气泡上限展开（clamp 上限 420/560dp 真正可用）、审批 Dialog 两侧留白呼吸、日志/记忆双列。

### 4.2 抽屉 overlay / permanent 断点

| | Compact / Medium | Expanded |
|---|---|---|
| 呈现方式 | overlay，滑入 + scrim | permanent，常驻 |
| 打开方式 | 顶栏汉堡按钮（各页 Scaffold navigationIcon） | 无需打开 |
| 关闭方式 | 点 scrim / 选中一项 / 返回键 | 不可关闭 |
| 选中态 | 跟随当前 route 精确高亮 | 同左 |
| 汉堡按钮 | 显示 | **隐藏**（无抽屉可开，避免死按钮） |

### 4.3 触控与安全区

| 区域 | 规则 |
|---|---|
| 触控目标 | 全部 ≥48×48dp（继承视觉规范 §4.4）；自定义 toggle 视觉 46×28dp 但触控容器 48×48dp；免询 checkbox 视觉 40dp + 触控 48dp（对视觉规范的修正，见 §7.4-6） |
| 底部 Tab | 64dp + `navigationBarsPadding`（edge-to-edge） |
| 输入栏 | `imePadding()`（现有实现已有，保持）+ `navigationBarsPadding` |
| 抽屉 | `statusBarsPadding`（overlay 模式从状态栏下滑入） |
| 审批 Dialog | 不贴边（min(340,92vw) 居中），无需 inset；横屏高度不足时 Dialog 内部 `verticalScroll` 兜底，「将执行」块 maxHeight 160dp |
| 横屏 | 避让 display cutout（`WindowInsets.displayCutout`）；对话页横屏时输入栏与 Tab 高度不变，消息区压缩 |
| 系统返回 | 二级页返回优先关闭抽屉/弹窗，再退出页面（与系统返回栈语义一致） |

### 4.4 气泡宽度的容器化修正

视觉规范 §4.1 规定气泡 maxW 用 `clamp(240,78vw,420)` / `clamp(280,88vw,560)`——`vw` 是全屏宽。**Expanded 下内容区限宽 680dp 后，88vw 会溢出内容区**。修正：clamp 的百分比基准从「屏幕宽」改为「消息列容器宽」（Compose 中用 `BoxWithConstraints` 取容器 maxWidth 计算）。这是实现细节修正，不改变 clamp 的 dp 上下限语义。

---

## 5. 无障碍（A11y）

### 5.1 TalkBack 语义规范

| 组件 | 语义策略 | contentDescription / stateDescription 模板 |
|---|---|---|
| 审批弹窗 | 系统 Dialog 自带模态聚焦；标题+副文+将执行块+来源行按顺序聚焦；**倒计时 liveRegion=off**，剩余 3s polite 播报一次 | 标题：「确认执行危险操作」；副文：「Agent 正在请求操控手机，该操作执行后不可撤销」；将执行块：「将执行 ui_click，参数 target 等于 WLAN」；来源行：「来源：技能 设置导航，触发指令 打开设置并点击 WLAN」 |
| 审批按钮 | role=Button，文字自解释 | 「拒绝」「允许执行」 |
| 免询 checkbox | `Role.Checkbox` + stateDescription | 「本次会话不再询问同类操作，已勾选/未勾选」 |
| Toggle（总开关/技能） | `Modifier.toggleable` + `Role.Switch` + stateDescription | 「自动化总开关，已开启/已关闭」 |
| 工具卡 | 整卡合并语义（一次聚焦读全），状态点/chevron decorative；展开态变化时 polite 重播 | 「ui_click，执行成功，耗时 214 毫秒，双击展开详情」/「ui_input，执行失败，双击展开详情，可重试」/「ui_tap，已拒绝执行」 |
| STEP 进度 | 进度条 `semantics { progressBar }` + 文字 | 「进度，第 3 步，共 7 步」 |
| 日志条目 | 级别缩写是文字（非装饰）；色点 decorative；展开区独立聚焦 | 「ERROR，14:32:07，来源 ADB，连接超时，双击展开堆栈」 |
| 红点角标 | 红点 decorative，语义进父项 description | 「运行日志，有 3 条未读错误」 |
| 计数角标 | 语义进父项 | 「技能中心，已安装 12 个技能」 |
| streaming AI 气泡 | `liveRegion=Polite`，节流：每完成一个文本段播报一次，禁止逐字播报 | — |
| 空态检查行 | 整行 clickable + 状态文字 | 「模型配置，未配置，双击前往配置」 |

### 5.2 动态字号（fontScale 1.0–2.0）保护

> 视觉规范 §3.3 定「行高一律绝对值 sp」——该策略在 fontScale ≤1.3 时正确；**fontScale >1.3 时绝对值行高会裁切字形**。UX 层补一条显式例外（对视觉规范的修正，见 §7.4-8）。

| 元素类型 | 单位策略 | fontScale 2.0 保护 |
|---|---|---|
| 所有文字 | sp（视觉已定） | 容器用 `heightIn(min=…)` 不用 `height`，文字可撑高 |
| 图标/间距/圆角/描边 | dp 锁死 | 不随字号缩放，防止布局崩坏 |
| 底部 Tab | 栏高 64dp | fontScale >1.3 时栏高升至 72dp；label maxLines=1，保栏稳定优先 |
| 按钮（含审批两按钮） | 高 48dp | 按钮文字 maxLines=2 + 高度自适应（`heightIn(min=48)`）；「允许执行」4 字在 28sp 下换行不裁切 |
| hero 空态卡 | headlineMedium 22/30 | 允许撑高（空态有垂直余量）；描述 maxLines=4 |
| 日志条目 | machineSmall 11/16 | **行高 clamp 例外**：fontScale ≤1.3 用 16sp 绝对值；>1.3 时行高 ×1.4（16→22sp），防 mono 小字裁切 |
| 「将执行」mono 块 | machineText 12/18 | maxHeight 160dp 滚动兜底 |
| 徽章/chip | labelSmall 11/14 | chip 高度 `heightIn(min=18)`，文字溢出优先撑高 |

### 5.3 色盲友好：状态双编码

> 铁律：**任何状态不得只靠颜色区分**。颜色之外必须有文字或图标第二编码。

| 状态 | 颜色编码 | 第二编码（必须） |
|---|---|---|
| 运行中 | primary 脉冲点 | 「RUNNING」/「执行中」文字徽章 + STEP n/m |
| 成功 | success 绿点 pop | 「成功」文字 + 对勾图标 |
| 失败 | danger 红点 shake | 「失败」文字 + 感叹图标 |
| 已拒绝 | outline 中性点 | 「已拒绝」文字 + 禁行图标 |
| 需审批 | danger 徽章 | 「需审批」文字（视觉规范 §5.2 已有） |
| WARN 日志 | warning 左缘色条 | 「WARN」缩写文字（对视觉规范的修正，见 §7.4-4） |
| ERROR 日志 | danger 左缘色条 | 「ERROR」缩写文字 |
| 信任 imported | warning 徽章 | 「● 导入」文字 |
| 权限未授权 | danger 徽章 | 「未授权」文字 |
| 三宫格就绪 | success 点 | 「已开启」文字（现有实现已有） |

### 5.4 减少动态效果（prefers-reduced-motion）降级

> 原则：**装饰性动效全部移除，信息性动效（进度、计数、色条）全部保留**。用户关的是"晕车"，不是"确定性"。

| 标志性动效 | 正常态（视觉规范 §6.2） | 降级态 |
|---|---|---|
| 审批脉冲 | scale .9→1 spring 入场 + 盾牌 1600ms 呼吸 + 10s 进度线 | 入场直接出现（无 scale）；盾牌静态；**倒计时进度线保留**（信息）；退场直接消失 |
| 信号扫描 | 2dp 光带 1500ms 循环 + 状态点 pulse + STEP n/m | 光带改为静态 100% 宽 2dp primary 条；状态点静态；**STEP n/m 文字保留** |
| 遥测滚入 | alpha+12dp 上移 250ms + 级别点 spring + 左缘色条 scaleX 划入 | 直接渲染无位移；级别点直接显示；**左缘色条直接显示**（信息） |
| 通用 | pressed scale .97 / spring / shake / pop | 全部换为 100ms 颜色/透明度变化；pressed 的 8% primary 叠加保留（触觉反馈仍在） |

Compose 落地：`LocalAccessibilityManager` 无直接 reduce-motion 开关，需读 `Settings.Global.ANIMATOR_DURATION_SCALE`（为 0 时用户关闭了动画）或提供 App 内「减少动态效果」开关存 DataStore，二者取或。

---

## 6. 文案与微文案规范

### 6.1 语气总则

| 要 | 不要 |
|---|---|
| 专业、确定、短句 | 卖萌（「人家等不及啦」）、拟物撒娇 |
| 陈述事实 + 指明下一步 | 恐吓（「危险！！将摧毁你的手机！」） |
| 审批场景"警觉但不恐慌"（视觉规范 §5.3 目标） | 用红色恐吓性大字、全屏红底 |
| 技术异常 → 人话 + 可操作 | 裸抛异常（`SocketTimeoutException`）、甩堆栈给用户 |
| 错误文案带定位信息（端点/指纹/状态码） | 「出错了，请重试」然后什么都不知道 |
| 「已拒绝」「已超时」中性陈述 | 「你拒绝了 Agent 的好意」情感绑架 |

### 6.2 审批弹窗文案模板（含来源回溯行）

```
┌────────────────────────────────────────┐
│ APPROVAL REQUIRED · 需要你的授权          │ microLabel 10sp danger
│                                         │
│ 确认执行危险操作                          │ titleLarge on-surface
│ Agent 正在请求操控手机，该操作执行后       │ bodyMedium on-surface-var
│ 不可撤销。                               │
│                                         │
│ ┌ 将执行 ──────────────────────────┐    │
│ │ ui_click                         │    │ machineText（surface-4 底
│ │ target: "WLAN"                   │    │ + 左缘 2dp danger 竖条）
│ │ package: com.android.settings    │    │ 包名/路径 tertiary 强调
│ └──────────────────────────────────┘    │
│                                         │
│ 来源：技能「设置导航」· 触发指令            │ 11sp on-surface-ter，可截断
│ 「打开设置并点击 WLAN」                    │
│                                         │
│ ☐ 本次会话不再询问同类操作                 │ 13sp on-surface-var
│                                         │
│ [ 拒绝 ]        [ 允许执行 ]              │ 等高 48dp；拒绝 Outlined /
│                         10s 后自动拒绝     │ 允许 danger 填充
└────────────────────────────────────────┘
```

**来源回溯行的写法规则**：
- 结构：`来源：{来源类型}「{来源名}」· 触发指令「{用户原话}」`；
- 来源类型三选一：`技能`（use_skill 调用）/ `系统`（内置工具直接调用）/ `自动化`（定时触发，预留）；
- 触发指令截断到 40 字（`take(40) + "…"`），保留用户原话不改写；
- 无来源信息时显示 `来源：本次对话直接调用`，**不留空**（空行会让用户疑心）；
- 数据依赖：当前 `ApprovalRequest` 只有 `toolName/argsPreview`，需扩展 `sourceSkill`/`sourceInstruction` 字段（见 §7.2）。

### 6.3 错误文案模板（三段式）

**结构：结论行（是什么）→ 定位行（mono 技术细节）→ 行动行（怎么办）**

```
401 实例：
API Key 无效或已过期（401）                        ← 结论
端点 https://api.example.com/v1/chat/completions   ← 定位（mono）
Key 指纹 len=35 head=sk-a tail=d38d
下一步：到「模型」页重新粘贴 Key，保存时会自动清除不可见字符   ← 行动

429 实例：
请求过于频繁（429）                     ← 结论
稍后再试；频繁出现时检查是否有其他客户端在用同一个 Key   ← 行动

超实例：
网络超时                                   ← 结论
端点 xxx · 等待 30s 无响应                  ← 定位
下一步：检查网络连接后重试                   ← 行动
```

### 6.4 中英混排规则

| 场景 | 语言/字体 | 例 |
|---|---|---|
| 人类叙述、标题、按钮、菜单项 | Noto Sans SC 中文 | 「允许执行」「运行日志」 |
| 工具名/参数/URL/Key/时间戳/计数/STEP/状态码/模型名/时长 | JetBrains Mono | `ui_click`、`sk-a…7F3A`、`214ms`、`STEP 1/3` |
| 分组标题 | 中文 + mono 英文微标签双行 | `设备感知 / DEVICE SENSE` |
| 徽章/chip | mono 10sp | `● 导入`、`ui_input` |
| 人类叙述中的数字 | 跟随正文 | 「3 条记忆」 |
| 机器语境中的数字 | mono | `len=35`、`STEP 3/7` |
| 中英文之间 | **不手动插空格**（视觉规范 §3.3-3，遵守） | 「到模型 Model 页」× → 「到模型页」✓ |

### 6.5 术语表（全 App 统一用词，防一义多词）

| 统一术语 | 禁用/避免 | 说明 |
|---|---|---|
| 审批 / 授权 | 「确认」「同意」「批准」混用 | 危险操作=审批；技能脚本=授权 |
| 拒绝 | 「取消」 | 弹窗里「取消」指放弃操作，「拒绝」指否决 Agent 请求 |
| 放行 / 拦截 | 「允许/禁止」（工具行内） | 工具行用放行/拦截，与徽章的授权体系区分 |
| 信任等级 | 「安全级别」「风险等级」 | 徽章专用：内置/导入/沙箱 |
| 运行日志 | 「日记」「记录」 | UI 标题统一「运行日志」，「日记」只出现在描述性文案里 |
| 本次会话 | 「永久」「总是」 | 会话白名单重启即失效，文案不得暗示持久 |

---

## 7. 落地映射与验收

### 7.1 UX 优先级与视觉 P0/P1/P2 对齐

| 视觉优先级 | UX 配套工作 | 对齐结论 |
|---|---|---|
| P0：Theme tokens / ApprovalDialog / 气泡+ToolCard+StatusBadge | 审批状态机（含 10s 超时默认开启）+ 错误分级文案 + ToolCard 五状态（**含新增 denied**）+ 空态 hero 文案 | **对齐，无调整**；唯一追加：超时自动拒绝从视觉"可选"升为默认（§7.4-5） |
| P1：抽屉 / BottomNav 药丸化 / SkillCard 信任徽章 / 日志条目 | 抽屉 IA（分组+角标规则）+ 4 个二级页拆分（分阶段）+ 角标状态源 + 日志级别缩写 + 筛选 chips | **对齐**；建议把「技能降权的显式授权流程」（F3）与 SkillCard 绑定验收——徽章必须承载"来源不可变"语义 |
| P2：模型配置页 / 空态 hero / emoji→图标迁移 | 起飞前检查清单 + 测试连接四态按钮 + 自检行 + fontScale 保护 + reduce-motion 降级 | **对齐**；「减少动效降级」建议从 P2 提前到 P1 末（实现成本低，a11y 价值高） |

**新增 UX 工作项（视觉规范未覆盖，需排期）**：
- 审批超时（`withTimeoutOrNull`）+ AgentLoop 防重复审批（P0 边缘，依赖 core 层配合）；
- `ApprovalRequest` 扩展来源字段（P0 边缘）；
- `onResume` 权限刷新（P1）；
- 运行时间轴 L3 回放（P2 增强）。

### 7.2 前后端对齐点（体验层对数据模型/事件的新增需求）

| # | 需求 | 现状 | 影响流程 |
|---|---|---|---|
| A1 | `ApprovalRequest` 增加 `sourceSkill: String?` / `sourceInstruction: String?` | 只有 toolName/argsPreview | F2 来源行 |
| A2 | 审批 `deferred.await()` 包 `withTimeoutOrNull(10_000)`，超时记 WARN 并回填 false | 无超时，会无限挂起 | F2 EXPIRED |
| A3 | AgentLoop 暴露步骤事件（第 n 步/共 m 步）供 ToolCard 显示 STEP n/m | 无此概念 | F2 信号扫描 |
| A4 | 同一指令内同工具+同参数被拒后不得重复申请审批 | 无此规则 | F2 防骚扰 |
| A5 | `refreshGrants()` 挂到 Activity `ON_RESUME`（现为进入页面时一次） | LaunchedEffect(Unit) | F5 |
| A6 | 日志条目结构化（level/time/source/message/stack 字段）供筛选与双编码 | 纯文本行 `format()` | F4/F6 |
| A7 | 「未读」状态源：回复完成 ∧ 页面不可见；新 ERROR ∧ 日志页不可见 | 无 | 角标规则 |

### 7.3 体验验收清单（14 条，逐条打勾）

- [ ] 1. 抽屉三个 Tab 页均可进入；4 个二级页各有返回；抽屉选中态精确到当前 route，不出现"打开了技能中心却高亮自动化"的错位
- [ ] 2. 角标全部遵守「零不显示」；红点类不显示数字且为 pulse；计数类为中性色不脉冲；红点只在用户看过对应页后清除
- [ ] 3. 审批弹窗五条路径（允许一次/免询允许/拒绝/外部点击/10s 超时）全部走通且全部写日志；超时后 Agent 不死锁、下一个工具可正常申请
- [ ] 4. 审批弹窗明暗双套：「允许执行」按钮文字对比度 ≥4.5:1；「拒绝」按钮描边 ≥3:1（双轨制强轨）
- [ ] 5. 工具卡五状态（QUEUED/RUNNING/DONE/FAILED/DENIED）视觉可辨；DENIED 用中性色不用红色；多步任务显示 STEP n/m 且等宽对齐
- [ ] 6. 401/429/5xx/超时/无网络五类错误各有差异化文案，均含定位信息与下一步动作；Key 只以指纹形式出现
- [ ] 7. 五个空态（对话/日志/记忆/技能/权限）全部为 radius-28 hero 卡 + 主行动按钮；空对话带三行起飞前检查
- [ ] 8. TalkBack 全流程走查：审批弹窗模态可读且倒计时不轰炸；toggle 有 stateDescription；工具卡合并语义一条读全；红点角标有文字描述
- [ ] 9. fontScale 2.0：底部 Tab 不溢出（栏高 72dp）、审批按钮文字不裁切、日志条目行高已放大、hero 卡不截断
- [ ] 10. 色盲模拟（ Deuteranopia / Protanopia ）：所有状态有非颜色第二编码（文字/图标），无"只能靠红绿分辨"的界面元素
- [ ] 11. reduce-motion（开发者选项动画关闭）下：三个标志性动效全部降级；倒计时、STEP、左缘色条信息不丢失
- [ ] 12. Compact/Medium/Expanded 三档：抽屉 overlay/permanent 行为正确；气泡 clamp 基于容器宽不溢出；审批 Dialog 三档均居中
- [ ] 13. edge-to-edge：底部 Tab 与输入栏避让 navigationBars；抽屉避让 statusBars；横屏避让 display cutout
- [ ] 14. 技能降权"看得懂"：导入即 warning 徽章 + 高危工具 danger chip + 展开明细 blocked 行；授权后徽章不变（来源不可变），footer 显示授权状态

### 7.4 对视觉规范的补充/修正（显式提出，不默默妥协）

| # | 类型 | 位置 | 问题 | 建议修正 | 理由 |
|---|---|---|---|---|---|
| 1 | **冲突（必修）** | 视觉 §5.3 审批「允许执行」按钮 | Dark 主题 `#FF6B72` 底 + 白字（onPrimary）= **2.76:1**，不达 WCAG AA 4.5:1（Light `#CC2F2F`+白字=5.7:1 达标） | 新增 token `--ak-on-danger`：Dark `#00272B`（≈5.7:1）/ Light `#FFFFFF`；按钮文字改用它 | 视觉规范 §2.4 对比度表未覆盖 danger 底+前景这一对，但审批是招牌界面，不允许暗色下主按钮文字不达标 |
| 2 | **冲突（建议修）** | 视觉 §5.3「拒绝」OutlinedButton | 描边用 `outline`（1.7:1），低于视觉规范自己的 §7.3-7「交互边界 ≥3:1 走强轨」 | Dialog 内按钮描边改 `outlineStrong`（3.6:1） | 遵守视觉规范自己的双轨制规则 |
| 3 | 补充 | 视觉 §5.2 ToolCard | 只有 running/done/fail/pending，**缺 denied 状态** | 补 DENIED：outline@35% 中性描边 + 禁行图标 + 「已拒绝」文字，不用红色 | 用户拒绝是正常决策不是错误；用红色会把"用户的选择"污名为"系统故障" |
| 4 | 补充 | 视觉 §5.8 日志条目 | 级别只有 8dp 色点，无色盲/读屏第二编码 | 级别缩写（DEBUG/INFO/WARN/ERROR）mono 10sp 常驻，色点降为 decorative | 遵守 §5.3 色盲双编码；WARN/ERROR 左缘色条已有，补文字闭环 |
| 5 | 修正（升级） | 视觉 §5.3「可选安全变体」10s 自动拒绝 | 列为可选，默认关闭 | **升为默认开启**，设置项可关（不鼓励） | 防 Agent 无限挂起假死；用户离开场景高频；倒计时本身就是"紧迫但不恐慌"设计的一部分 |
| 6 | 修正 | 视觉 §5.3 免询 checkbox 40dp | 40dp < 视觉规范 §4.4 的 48dp 触控基准 | 视觉 40dp + 触控容器 48×48dp（与自定义 toggle 同处理） | 遵守自己的触控规范 |
| 7 | 修正（小） | 视觉 §5.5 Tab label 10.5sp vs §3.2 labelMedium 12sp | 两处不一致 | Tab label 统一 labelMedium 12sp | 10.5sp 非体系内值；12sp 读屏友好 |
| 8 | 修正（例外） | 视觉 §3.3-1「行高一律绝对值 sp」 | fontScale >1.3 时绝对值行高裁切字形 | 例外条款：fontScale ≤1.3 用绝对值；>1.3 时正文/日志行高 ×1.4 | 绝对值策略服务跨设备一致性，不应以极端字号下裁切文字为代价 |
| 9 | 补充 | 视觉 §4.1 气泡 clamp 用 vw | Expanded 限宽 680dp 后 88vw 溢出内容区 | clamp 百分比基准改为消息列容器宽（BoxWithConstraints） | 实现细节，保 dp 上下限语义不变 |
| 10 | 补充（命名） | 视觉 §5.5 底部 Tab「自动化」 | 页面内容重组为控制台语义后，「自动化」一名稍抽象（另两个 Tab 是名词） | 可选：改「控制台 / CONSOLE」；不改则页面内标题用「自动化控制台」 | 命名与内容对齐；非强制，尊重视觉已定 |
| 11 | 补充 | 视觉 §5.4 抽屉 Header 状态胶囊 | 只定义了「● 沙箱就绪」一种 | 补第二态：warning「● N 项待授权」（关键权限缺失时） | 全局信任状需要反映"有事待办"，否则红点体系缺一个汇总位 |

---

*规范完。前端工程师从 §7.2 对齐点开始落地；体验验收按 §7.3 清单逐条打勾；与视觉规范的冲突以 §7.4 显式记录为准。*
