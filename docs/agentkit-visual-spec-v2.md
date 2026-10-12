# AgentKit 视觉风格规范 v2

> 版本：v2.1 · 适用平台：Android (Jetpack Compose + Material 3) · minSdk 26
> 替代范围：`ui/Theme.kt` 现有 Indigo 体系整体替换；`ChatScreen.kt` / `AutomationScreen.kt` 视觉层重做（功能逻辑不动）。
> 设计语言代号：**Signal Console · 信号控制台**

---

## v2.1 修订记录

> 依据：《AgentKit 体验规范 v1》§7.4（ux-ui-designer 反馈，共 2 处冲突 + 9 条补充/修正）+ 视觉层实测自查。
> 修订原则：不触碰 §1 设计 DNA 与 Signal Console 方向；所有色值变动以 WCAG 2.x 相对亮度公式实测为准（保留两位小数）；每条注明 Compose 承载方式。
> 版本号：v2.0 → **v2.1**（增量修订，未重订基线；v2.0 已发布内容除下表条目外全部有效）。

| # | 类型 | 修订内容 | 原因 | 提出者 | 实测数据 |
|---|---|---|---|---|---|
| R1 | **必修** | 新增语义 token `--ak-on-danger`（Dark `#00272B` / Light `#FFFFFF`）；§5.3「允许执行」按钮文字 `on-primary` → `on-danger`；同步 §2.2 / §2.3 / §2.4 / §2.7 / §7.1 | Dark 下 `#FF6B72` + 白字仅 **2.76:1**，违反本文 §2.4 自订铁律「正文 ≥4.5:1」；审批弹窗是全屏阻断式最高优先级交互，主按钮文字不允许不达标。深色墨迹方案同时保持「Dark 套所有填充按钮共用同一 on 色」的语汇统一 | ux-ui-designer | Dark `#00272B` on `#FF6B72` = **5.73:1**（原 `#FFFFFF` = 2.76:1）；Light `#FFFFFF` on `#CC2F2F` = **5.23:1** |
| R2 | 建议修 → **确认修** | §5.3「拒绝」OutlinedButton 描边 `outline` → `outlineStrong`；并推广为全 App 规则：**所有 OutlinedButton 描边一律 `outlineStrong`**（含 §5.6「取消」） | 属疏漏、非有意：本文 §7.3-7 已定「交互边界 ≥3:1 走强轨」，按钮描边即交互边界；1.4:1 描边会让安全关键的「拒绝」几乎看不见。加强描边不改变「填充=主 / 描边=次」的既定层级，也不破坏两按钮等高的「不偏袒」原则 | ux-ui-designer | Dark `#6B7688` on surface-3 `#222836` = **3.21:1**（原 `#39414F` = 1.43:1）；Light `#7E8797` on `#E9ECF1` = **3.06:1**（原 `#C9CFDA` = 1.32:1） |
| R3 | 补充 | §5.2 ToolCard 补第五态 **denied**：`outline`@35% 中性描边 + `outline-strong` 中性点 + 禁行图标 + 「已拒绝」12sp | 拒绝是用户的正常决策而非错误；用红色会把用户的选择污名为系统故障，违反「红色只留给危险动作」 | ux-ui-designer | 「已拒绝」`on-surface-var` on surface-2：Dark **7.45:1** / Light **7.01:1** |
| R4 | 补充 | §5.8 日志行补**级别缩写**（DEBUG / INFO / WARN / ERROR，mono 10sp 级别色）常驻，级别点降为 decorative | 色盲 / 读屏双编码：颜色之外必须有文字第二编码；WARN/ERROR 左缘色条保留，形成「色点 + 缩写 + 色条」三通道 | ux-ui-designer | 缩写 on surface-2（最坏底色）：Dark **5.46–9.77:1** / Light **4.66–5.35:1**，均 ≥4.5:1 |
| R5 | 修正 | §5.3「可选安全变体」10s 自动拒绝**升为默认开启**（设置项可关，不鼓励）；§6.2-1 / §7.3-9 同步 | 防 AgentLoop 无限挂起假死；用户被消息/电话打断是高频场景；倒计时本身是「紧迫但不恐慌」设计的组成部分 | ux-ui-designer | 无新增色值 |
| R6 | 修正 | §5.3 免询 checkbox：视觉 40dp + **触控容器 48×48dp**；§4.4 补同义注 | 40dp 低于本文 §4.4 自订 48dp 触控基准；与自定义 toggle「视觉 46×28dp / 触控 48×48dp」同处理 | ux-ui-designer | 无新增色值 |
| R7 | 修正 | §5.5 Tab label 10.5sp → **labelMedium 12/16 w500** | 10.5sp 非体系内值；与 §3.2 排版表统一，读屏更友好 | ux-ui-designer | 无新增色值 |
| R8 | 修正 | §3.3-1 行高策略补例外条款：fontScale ≤1.3 用绝对值；**>1.3 时行高系数 ×1.4**（machineSmall 16→22sp），容器一律 `heightIn(min=…)` | 绝对值策略服务跨设备一致性，不应以极端字号下裁切字形为代价 | ux-ui-designer | 无新增色值 |
| R9 | 补充 | §4.1 / §5.1 气泡 clamp 百分比基准由「屏幕宽 vw」改「**消息列容器宽**」（Compose `BoxWithConstraints`） | Expanded（>840dp）内容区限宽 680dp 后，88vw 会溢出容器；dp 上下限语义不变 | ux-ui-designer | 无新增色值 |
| R10 | 补充 | §5.5 Tab「自动化」更名「**控制台**」；「自动化」一词保留于功能语义（总开关 / 自动化流程） | 页面内容已重组为控制台仪表盘（route=console），命名与内容对齐；2 字在 12sp 下载荷优于 3 字 | ux-ui-designer | 无新增色值 |
| R11 | 补充 | §5.4 抽屉 Header 状态胶囊补第二态：底 `warning`@12% + 「● N 项待授权」（任一关键权限缺失时切换） | 全局信任状需反映「有事待办」，与红点角标体系汇总位对齐 | ux-ui-designer | Dark `#FFB224` on 混色底 `#312C23` = **7.69:1**；Light `#4A2E00` on 混色底 `#EDE7DE` = **10.16:1** |
| R12 | **自查必修** | Light 套「语义色@12–14% 淡 tint 底 + 语义色文字」全族不达 AA，统一改为「底 = 语义色淡 tint，**文字 = 对应 `on-*-container` 深色墨迹**」；Dark 套不变（已全部达标）。涉及 §5.2 权限徽章 / §5.4 状态胶囊 / §5.6 结果态文字 / §5.7 信任徽章 / §5.8 筛选 chips | R11 实测时发现：Light danger 徽章 **3.92:1**、success 胶囊 **4.41:1**、warning 徽章 4.40:1、primary 徽章 4.39:1——全族 3.92–4.41:1，违反本文 AA 铁律；属与 R1 同类的无障碍缺陷，不以「设计美感」豁免。仅改文字色，tint 底与视觉pattern 不变，DNA 不动 | 视觉设计架构师（实测自查） | 修正后 Light 全族 **7.18–14.09:1**（逐对明细见 §2.4） |

---

## 0. 文档使用说明

- 本文所有色值、字号、圆角、时长均为**最终值**，前端工程师可直接映射为 `Color(0xFF...)` / `xx.sp` / `xx.dp` / `tween(xxx)`。
- `--ak-*` 为设计 token 名（第 2.7 节给出 CSS 变量全集，方便与 Web 端/设计稿对齐）。
- 明暗双套全部给出；Dark 为**主设计套**（OLED + 夜间自动化场景），Light 为完整平行套，不是简单反色。
- 语义色（危险/警告/成功/信任等级）不属于品牌色，**只允许出现在状态语义场景**，禁止装饰性使用。

---

## 1. 设计 DNA

### 1.1 一句话设计主张

> **「把手机的每一次 AI 动作，变成可预审、可追踪、可回放的确定感。」**

AgentKit 与普通聊天 App 的本质差异是：它会真的操控你的手机。因此视觉的第一职责不是"好看"，而是让用户在每一次点击、每一次授权、每一次脚本执行前后都**心里有底**。所有视觉决策以此为准绳。

### 1.2 情绪板关键词（7 个）

| 关键词 | 视觉转译 |
|---|---|
| 深夜控制台 | 近黑石墨底（#0B0E14）+ 单一信号青高亮，OLED 友好 |
| 仪表盘 | 等宽字体承载一切机器输出；数字、时间戳、计数严格对齐 |
| 遥测遥测 | 级别色点（DEBUG/INFO/WARN/ERROR）+ 实时滚入的日志流 |
| 克制 | 全 App 只允许一个高亮色；状态色只用于状态 |
| 硬核工具感 | 线性格矢量图标（1.5dp stroke）、mono 微标签、无插画无吉祥物 |
| 可信赖 | 平面卡片 + 1dp 描边替代重阴影；危险操作全屏阻断式审批 |
| 确定性 | 进度可数（STEP n/m）、来源可溯（技能/指令回溯）、结果可读（等宽回显） |

### 1.3 与参考 App「玄枵」的刻意差异点（5 点）

| # | 玄枵的做法 | AgentKit 的做法 | 为什么 |
|---|---|---|---|
| 1 | Emoji 图标（☎️📬🖼️🧩） | 24dp 线性格矢量图标，1.5dp stroke，单色 tint | Emoji 在不同 OEM  ROM 上渲染不一致（三星/小米/原生三副面孔），且可爱气质会削弱"它能操作你手机"的严肃感。线性格图标是仪器语汇的基础 |
| 2 | 拼音小字注音（xuan xiao） | **不采纳拼音**。分组标题用「中文 + mono 英文微标签」双行结构（如 `设备感知 / DEVICE SENSE`） | 拼音是纯装饰、零信息增益、且快速过时；mono 英文微标签既保留"双行标签"的层次美感，又强化技术语汇 |
| 3 | 圆润可爱、毛玻璃聊天页 | 玻璃质感仅保留在 1 处（输入栏浮层），其余一律实色 + 1dp 描边 | 大面积毛玻璃会降低日志/代码可读性，中低端机有合成开销；"可读"是工具的生命线 |
| 4 | 多彩九宫格入口 | 单色九宫格 + 仅 >0 时显示的计数角标；审批类入口用脉冲红点而非数字 | 色彩即信息。当颜色只属于"状态"，用户扫一眼就知道哪里需要处理 |
| 5 | 右下角可爱 FAB / 轻动效 | 无装饰性 FAB；动效预算集中在三个"标志性动效"（见 §6） | 每一处动效都必须传达信息，否则就是噪音 |

### 1.4 不适合 AgentKit 的做法（5 项 + 理由）

1. **大面积渐变/彩虹渐变按钮** —— 渐变暗示"魔法与娱乐"；AgentKit 的危险操作需要"机械与确定"。实色 + 语义色边框才是审批场景的正确语汇。
2. **廉价重阴影（elevation ≥4dp 的卡片堆叠）** —— 重阴影在信息密集的工具界面里制造视觉噪音，且暗色模式下几乎不可见。改用 surfaceContainer 色阶层级 + 1dp 描边。
3. **可爱插画/吉祥物主导的空态与引导** —— 用户把手机控制权交给 Agent 的时刻，需要的是"专业审慎"，不是"萌"。
4. **无分组的无限卡片流** —— 工具界面需要可预测的结构：固定分区、稳定位置、层级清晰。每次进来东西都在乱动 = 不可信。
5. **全局弹跳动效（所有元素都 springy）** —— 当一切都 bounce，就没有东西重要了。弹跳只留给"成功确认"一种情绪，且参数收敛（damping 0.55）。

---

## 2. 色彩系统

### 2.1 色彩策略

- **单一信号色**：主色取磷光青（teal-cyan，hue ≈182）。理由：① 终端/自动化的文化母语是磷光屏，但传统"黑客绿"过于陈词滥调，青色是它的现代化身；② 与旧 Indigo（#4F46E5，SaaS 模板感）彻底切割；③ 在 #0B0E14 近黑底上天然高对比（11.3:1），OLED 上不晕不刺眼。
- **中性色带冷蓝灰底（hue ≈218，低彩度）**：避免纯灰的"脏"，与信号青同族。
- **状态色严格四组**：danger（审批/破坏性）、warning（警惕/导入来源）、success（就绪/内置）、trust-neutral（沙箱）。**红色 = 危险动作，不再用来标记"来源是远程"**（旧代码 SkillCard 的 remote→error 映射废弃，理由见 §7.2）。
- **涨红跌绿**：未来若加用量/统计图表，上涨=红（复用 danger 色系）、下跌=绿（复用 success 色系），遵循中文用户习惯；该规则仅限数据可视化语境，不影响状态语义（成功=绿/错误=红）。

### 2.2 Dark 主题（主设计套）

| Token | HEX | 用途 |
|---|---|---|
| `--ak-bg` | `#0B0E14` | 页面背景 |
| `--ak-surface` | `#12161E` | 最底层 surface（顶栏、输入栏） |
| `--ak-surface-1` | `#151A23` | surfaceContainerLow（抽屉、抽屉级容器） |
| `--ak-surface-2` | `#191E28` | surfaceContainer（**卡片/气泡默认底色**） |
| `--ak-surface-3` | `#222836` | surfaceContainerHigh（弹窗、浮层） |
| `--ak-surface-4` | `#2B3242` | surfaceContainerHighest（输入框、代码块、chip） |
| `--ak-surface-var` | `#232936` | surfaceVariant（填充式输入框） |
| `--ak-on-surface` | `#F0F3F8` | 主文字 |
| `--ak-on-surface-var` | `#A3AEC0` | 次文字/描述 |
| `--ak-on-surface-ter` | `#7A8494` | 三级文字/禁用（仍 ≥4.5:1） |
| `--ak-outline` | `#39414F` | 装饰分隔线（1.6:1，**仅分隔**） |
| `--ak-outline-strong` | `#6B7688` | 交互边界/焦点/选中边框（3.6:1） |
| `--ak-primary` | `#56D9D6` | 信号青：主按钮、选中态、进度、INFO 级 |
| `--ak-on-primary` | `#00272B` | primary 上的文字/图标 |
| `--ak-primary-container` | `#0E4A4C` | 用户气泡、选中药丸 |
| `--ak-on-primary-container` | `#A8F0EE` | 其上的文字 |
| `--ak-secondary` | `#9FB2C4` | 次要组件（弱化按钮、次级强调） |
| `--ak-secondary-container` | `#2A3644` | — |
| `--ak-on-secondary-container` | `#C7D6E4` | — |
| `--ak-tertiary` | `#B9A8FF` | AI/记忆语义紫（AI 头像、记忆徽章） |
| `--ak-tertiary-container` | `#372A6B` | — |
| `--ak-on-tertiary-container` | `#E2D9FF` | — |
| `--ak-danger` | `#FF6B72` | 审批、破坏性操作、ERROR 级 |
| `--ak-on-danger` | `#00272B` | **danger 填充上的文字/图标**（v2.1 新增；on `#FF6B72` = 5.73:1） |
| `--ak-danger-container` | `#451217` | 审批图标底、危险徽章底 |
| `--ak-on-danger-container` | `#FFDADD` | 其上的文字 |
| `--ak-warning` | `#FFB224` | 警惕、导入来源、WARN 级 |
| `--ak-warning-container` | `#4A3005` | 警告徽章底 |
| `--ak-on-warning-container` | `#FFE3A8` | 其上的文字 |
| `--ak-success` | `#45D68C` | 就绪、内置信任、完成态 |
| `--ak-success-container` | `#0E3D27` | 成功徽章底 |
| `--ak-on-success-container` | `#B2F2D0` | 其上的文字 |
| `--ak-trust-sandbox` | `#8A94A6` | 沙箱信任等级 |
| `--ak-log-debug` | `#8A94A6` | 日志 DEBUG |
| `--ak-log-info` | `#56D9D6` | 日志 INFO（= primary） |
| `--ak-inverse-surface` | `#E6EAF2` | Snackbar/Toast 底 |
| `--ak-inverse-on-surface` | `#191E28` | 其上的文字 |
| `--ak-scrim` | `#000000 @ 64%` | 弹窗遮罩 |

### 2.3 Light 主题

| Token | HEX | 用途 |
|---|---|---|
| `--ak-bg` | `#F5F6F8` | 页面背景 |
| `--ak-surface` | `#FFFFFF` | 顶栏/输入栏 |
| `--ak-surface-1` | `#FAFBFC` | surfaceContainerLow |
| `--ak-surface-2` | `#F0F2F5` | surfaceContainer（卡片/气泡） |
| `--ak-surface-3` | `#E9ECF1` | surfaceContainerHigh（弹窗） |
| `--ak-surface-4` | `#DFE3EA` | surfaceContainerHighest |
| `--ak-surface-var` | `#E9ECF1` | surfaceVariant |
| `--ak-on-surface` | `#14181F` | 主文字 |
| `--ak-on-surface-var` | `#4A5261` | 次文字 |
| `--ak-on-surface-ter` | `#656E7D` | 三级文字 |
| `--ak-outline` | `#C9CFDA` | 装饰分隔线 |
| `--ak-outline-strong` | `#7E8797` | 交互边界/焦点/选中（3.2:1） |
| `--ak-primary` | `#00707A` | 信号青（深一档，保正文对比） |
| `--ak-on-primary` | `#FFFFFF` | — |
| `--ak-primary-container` | `#B8ECEC` | — |
| `--ak-on-primary-container` | `#002A2E` | — |
| `--ak-secondary` | `#4E5D6C` | — |
| `--ak-secondary-container` | `#DCE5EE` | — |
| `--ak-on-secondary-container` | `#1B2836` | — |
| `--ak-tertiary` | `#5B4BC4` | AI/记忆紫 |
| `--ak-tertiary-container` | `#E4DEFF` | — |
| `--ak-on-tertiary-container` | `#241A52` | — |
| `--ak-danger` | `#CC2F2F` | — |
| `--ak-on-danger` | `#FFFFFF` | danger 填充上的文字/图标（v2.1 新增；on `#CC2F2F` = 5.23:1） |
| `--ak-danger-container` | `#FCE4E4` | — |
| `--ak-on-danger-container` | `#8C1009` | — |
| `--ak-warning` | `#8F5600` | 警告文字/图标（焦琥珀，不用亮黄） |
| `--ak-warning-container` | `#FFE1A6` | — |
| `--ak-on-warning-container` | `#4A2E00` | — |
| `--ak-success` | `#0E7A4D` | — |
| `--ak-success-container` | `#C9F2DD` | — |
| `--ak-on-success-container` | `#00210F` | — |
| `--ak-trust-sandbox` | `#5A6472` | — |
| `--ak-log-debug` | `#5A6472` | — |
| `--ak-log-info` | `#00707A` | — |
| `--ak-inverse-surface` | `#2B3242` | Snackbar 底 |
| `--ak-inverse-on-surface` | `#F0F3F8` | — |
| `--ak-scrim` | `#000000 @ 48%` | 浅色下用更轻遮罩 |

### 2.4 WCAG AA 对比度校验表（实测计算值）

| 套 | 前景 | 背景 | 比值 | 结论 |
|---|---|---|---|---|
| Dark | `#F0F3F8` onSurface | `#0B0E14` bg | **17.3:1** | ✅ AAA |
| Dark | `#A3AEC0` onSurfaceVariant | `#0B0E14` bg | **8.6:1** | ✅ AAA |
| Dark | `#7A8494` 三级文字 | `#0B0E14` bg | **5.1:1** | ✅ AA |
| Dark | `#56D9D6` primary | `#0B0E14` bg | **11.3:1** | ✅ AAA |
| Dark | `#00272B` onPrimary | `#56D9D6` primary | **9.3:1** | ✅ AAA |
| Dark | `#A8F0EE` onPrimaryContainer | `#0E4A4C` primaryContainer | **7.8:1** | ✅ AAA |
| Dark | `#FF6B72` danger | `#0B0E14` bg | **7.0:1** | ✅ AAA |
| Dark | `#FFB224` warning | `#0B0E14` bg | **10.7:1** | ✅ AAA |
| Dark | `#45D68C` success | `#0B0E14` bg | **10.3:1** | ✅ AAA |
| Dark | `#8A94A6` sandbox/DEBUG | `#0B0E14` bg | **6.3:1** | ✅ AA |
| Dark | `#B9A8FF` tertiary | `#0B0E14` bg | **9.3:1** | ✅ AAA |
| Dark | `#6B7688` outlineStrong | `#191E28` surface-2 | **3.6:1** | ✅ 交互边界 ≥3:1 |
| Light | `#14181F` onSurface | `#F5F6F8` bg | **16.5:1** | ✅ AAA |
| Light | `#4A5261` onSurfaceVariant | `#F5F6F8` bg | **7.3:1** | ✅ AAA |
| Light | `#656E7D` 三级文字 | `#F5F6F8` bg | **4.8:1** | ✅ AA |
| Light | `#00707A` primary | `#F5F6F8` bg | **5.4:1** | ✅ AA |
| Light | `#FFFFFF` onPrimary | `#00707A` primary | **5.8:1** | ✅ AA |
| Light | `#002A2E` onPrimaryContainer | `#B8ECEC` primaryContainer | **11.9:1** | ✅ AAA |
| Light | `#8F5600` warning | `#F5F6F8` bg | **5.6:1** | ✅ AA |
| Light | `#0E7A4D` success | `#F5F6F8` bg | **5.0:1** | ✅ AA |
| Light | `#CC2F2F` danger | `#F5F6F8` bg | **4.8:1** | ✅ AA |
| Light | `#5A6472` sandbox/DEBUG | `#F5F6F8` bg | **5.6:1** | ✅ AA |
| Light | `#7E8797` outlineStrong | `#F0F2F5` surface-2 | **3.2:1** | ✅ 交互边界 ≥3:1 |
| Dark | `#00272B` onDanger | `#FF6B72` danger | **5.73:1** | ✅ AA（v2.1 R1 修复：原 `#FFFFFF` 2.76:1） |
| Light | `#FFFFFF` onDanger | `#CC2F2F` danger | **5.23:1** | ✅ AA（v2.1 R1 新增 token） |
| Dark | `#6B7688` outlineStrong | `#222836` surface-3（Dialog 底） | **3.21:1** | ✅ 交互边界 ≥3:1（v2.1 R2） |
| Light | `#7E8797` outlineStrong | `#E9ECF1` surface-3（Dialog 底） | **3.06:1** | ✅ 交互边界 ≥3:1（v2.1 R2） |
| Dark | 日志缩写 DEBUG/INFO/WARN/ERROR | `#191E28` surface-2 | **5.46–9.77:1** | ✅ AA（v2.1 R4，取最坏底色实测） |
| Light | 日志缩写 DEBUG/INFO/WARN/ERROR | `#F0F2F5` surface-2 | **4.66–5.35:1** | ✅ AA（v2.1 R4；Light ERROR 4.66:1 偏紧，未来调浅 `--ak-danger` 必须复测此对） |
| Dark | `#A3AEC0`「已拒绝」 | `#191E28` surface-2 | **7.45:1** | ✅ AA（v2.1 R3 denied 态） |
| Light | `#4A5261`「已拒绝」 | `#F0F2F5` surface-2 | **7.01:1** | ✅ AA（v2.1 R3 denied 态） |
| Dark | 徽章/胶囊文字（语义色亮文 on 语义色@12–14% tint） | surface-1/2 混色底 | **5.11–7.69:1** | ✅ AA |
| Light | 徽章/胶囊文字（`on-*-container` on 语义色@12–14% tint） | surface-1/2 混色底 | **7.18–14.09:1** | ✅ AA（v2.1 R12 修复：原语义色亮文 3.92–4.41:1 全族不达标） |

> 说明：装饰线 `outline` 有意低于 3:1（1.5–1.7:1），它只承担分隔，不承担交互提示；所有需要被"看见"的边界一律走 `outlineStrong`。容器对（container / onContainer）比值全部 ≥7.8:1，此处不逐行罗列。
>
> **徽章/胶囊文字规则（v2.1，R12）**：「语义色@12–14% 淡 tint 底 + 小字（10–11sp）」组合中——**Dark 套**文字用语义色亮文（实测全部 ≥5.11:1）；**Light 套**淡 tint 会把语义色文字对比度压到 3.92–4.41:1（全族不达 AA），文字一律换对应 `on-*-container` 深色墨迹（实测全部 ≥7.18:1）。此规则覆盖 §5.2 权限徽章、§5.4 状态胶囊、§5.6 结果态文字、§5.7 信任徽章、§5.8 筛选 chips。tint 底与视觉 pattern 不变，只换文字色。

### 2.5 信任等级色板（Skills 专用）

| 等级 | Dark | Light | 语义 |
|---|---|---|---|
| packaged 内置 | success `#45D68C` | success `#0E7A4D` | 官方随包分发，默认可信 |
| imported 导入 | warning `#FFB224` | warning `#8F5600` | 外部来源，需警惕但可用 |
| sandbox 沙箱 | `#8A94A6` | `#5A6472` | 隔离运行，中性观察 |

### 2.6 日志级别色板

| 级别 | Dark | Light |
|---|---|---|
| DEBUG | `#8A94A6` | `#5A6472` |
| INFO | `#56D9D6` | `#00707A` |
| WARN | `#FFB224` | `#8F5600` |
| ERROR | `#FF6B72` | `#CC2F2F` |

### 2.7 CSS 变量全集（与设计稿/Web 对齐用）

```css
:root { /* Light */
  --ak-bg:#F5F6F8; --ak-surface:#FFFFFF; --ak-surface-1:#FAFBFC; --ak-surface-2:#F0F2F5;
  --ak-surface-3:#E9ECF1; --ak-surface-4:#DFE3EA; --ak-surface-var:#E9ECF1;
  --ak-on-surface:#14181F; --ak-on-surface-var:#4A5261; --ak-on-surface-ter:#656E7D;
  --ak-outline:#C9CFDA; --ak-outline-strong:#7E8797;
  --ak-primary:#00707A; --ak-on-primary:#FFFFFF; --ak-primary-container:#B8ECEC; --ak-on-primary-container:#002A2E;
  --ak-secondary:#4E5D6C; --ak-secondary-container:#DCE5EE; --ak-on-secondary-container:#1B2836;
  --ak-tertiary:#5B4BC4; --ak-tertiary-container:#E4DEFF; --ak-on-tertiary-container:#241A52;
  --ak-danger:#CC2F2F; --ak-on-danger:#FFFFFF; --ak-danger-container:#FCE4E4; --ak-on-danger-container:#8C1009;
  --ak-warning:#8F5600; --ak-warning-container:#FFE1A6; --ak-on-warning-container:#4A2E00;
  --ak-success:#0E7A4D; --ak-success-container:#C9F2DD; --ak-on-success-container:#00210F;
  --ak-trust-sandbox:#5A6472; --ak-log-debug:#5A6472; --ak-log-info:#00707A;
  --ak-inverse-surface:#2B3242; --ak-inverse-on-surface:#F0F3F8; --ak-scrim:rgba(0,0,0,.48);
}
[data-theme="dark"] {
  --ak-bg:#0B0E14; --ak-surface:#12161E; --ak-surface-1:#151A23; --ak-surface-2:#191E28;
  --ak-surface-3:#222836; --ak-surface-4:#2B3242; --ak-surface-var:#232936;
  --ak-on-surface:#F0F3F8; --ak-on-surface-var:#A3AEC0; --ak-on-surface-ter:#7A8494;
  --ak-outline:#39414F; --ak-outline-strong:#6B7688;
  --ak-primary:#56D9D6; --ak-on-primary:#00272B; --ak-primary-container:#0E4A4C; --ak-on-primary-container:#A8F0EE;
  --ak-secondary:#9FB2C4; --ak-secondary-container:#2A3644; --ak-on-secondary-container:#C7D6E4;
  --ak-tertiary:#B9A8FF; --ak-tertiary-container:#372A6B; --ak-on-tertiary-container:#E2D9FF;
  --ak-danger:#FF6B72; --ak-on-danger:#00272B; --ak-danger-container:#451217; --ak-on-danger-container:#FFDADD;
  --ak-warning:#FFB224; --ak-warning-container:#4A3005; --ak-on-warning-container:#FFE3A8;
  --ak-success:#45D68C; --ak-success-container:#0E3D27; --ak-on-success-container:#B2F2D0;
  --ak-trust-sandbox:#8A94A6; --ak-log-debug:#8A94A6; --ak-log-info:#56D9D6;
  --ak-inverse-surface:#E6EAF2; --ak-inverse-on-surface:#191E28; --ak-scrim:rgba(0,0,0,.64);
}
```

---

## 3. 字体与排版

### 3.1 字体组合

| 角色 | 字体 | 字重 | 理由 |
|---|---|---|---|
| 标题 / 正文（人类语言） | **Noto Sans SC** | 400 / 500 / 600 / 700 | 屏显清晰、字面率高；其拉丁字形与 Roboto 同源，与系统 UI 并置不跳字；开源可子集化。**不引入衬线体**——工具场景要屏显锐度，不要文学气质 |
| 机器语言（日志/命令/参数/URL/Key/时间戳/模型名/计数） | **JetBrains Mono** | 400 / 500 | 0/O、1/l/I 区分度高于 Roboto Mono；连字符与下划线清晰，适合长命令换行；等宽特性天然解决中英数字混排对齐 |
| 回退 | `FontFamily.SansSerif` / `FontFamily.Monospace` | — | Google Fonts 不可用时（无 Play 服务设备）自动回退 |

落地方式：`androidx.compose.ui:ui-text-google-fonts` + `GoogleFont("Noto Sans SC")` / `GoogleFont("JetBrains Mono")`，R.array 声明 provider；或按需打包常用 3500 字子集（下载字体优先，省包体）。

### 3.2 Type Scale（Compose Typography 直接映射）

| M3 slot | 字号/行高 (sp) | 字重 | 用途 |
|---|---|---|---|
| displayLarge | 36 / 44 | 400 | 大数字统计（日志条数等），少用 |
| displayMedium | 32 / 40 | 400 | 空态 hero 数字 |
| displaySmall | 28 / 36 | 400 | 空态 hero 数字 |
| headlineLarge | 26 / 34 | 600 | 页面大标题（空态主标题） |
| headlineMedium | 22 / 30 | 600 | 空态主标题 |
| headlineSmall | 20 / 28 | 600 | 弹窗大标题（备用） |
| titleLarge | 18 / 26 | 600 | 顶栏标题、分区标题、审批弹窗标题 |
| titleMedium | 16 / 24 | 600 | 卡片标题、技能名 |
| titleSmall | 14 / 20 | 600 | 列表项主文案 |
| bodyLarge | 16 / 28 | 400 | 长文本正文（中文行高 1.75） |
| **bodyMedium** | **15 / 26** | 400 | **默认正文：聊天气泡、描述**（较旧版 15/22 提高行高） |
| bodySmall | 13 / 20 | 400 | 辅助说明、两行截断描述 |
| labelLarge | 14 / 20 | 600 | 按钮文字 |
| labelMedium | 12 / 16 | 500 | 标签、底部 Tab 文字 |
| labelSmall | 11 / 14 | 500 | 徽章、角标、chip |

自定义样式（不在 Typography 14 slot 内，单独定义）：

| 名称 | 值 | 用途 |
|---|---|---|
| `machineText` | JetBrains Mono 12sp / 18 | 日志、命令、参数、响应预览 |
| `machineSmall` | JetBrains Mono 11sp / 16 | 时间戳、指纹、计数、角标 |
| `microLabel` | Noto Sans SC 10sp / 12，w500，letterSpacing 0.12em，全大写 | 分组微标签（AUTOMATION / RUNTIME / SYSTEM）、APPROVAL REQUIRED |
| `machineStrong` | JetBrains Mono 13sp / 20，w500 | 工具名、模型名、状态读数 |

### 3.3 中文排版专项

1. **行高**：中文正文行高 ≥1.7 倍（bodyMedium 15/26）；标签类紧行高（11/14）保证 chip 不臃肿。**行高一律用绝对值 sp，不用倍数**，避免不同 ROM 字体度量差异。**例外条款（v2.1，R8）**：fontScale ≤1.3 时绝对值行高成立；**fontScale >1.3 时绝对值会裁切字形，行高系数 ×1.4**（如 machineSmall 16→22sp、bodyMedium 26→36sp）；配套：容器一律用 `heightIn(min=…)` 不用固定 `height`，让文字可撑高。
2. **数字等宽化**：所有"机器数字"（时长 ms、STEP n/m、计数、时间戳、长度 N）走 mono；人类叙述中的数字跟随正文。**禁止**用 Noto Sans SC 的数字做表格/时间戳列——其数字为比例宽度，会错位。
3. **中英混排**：不在中英文之间手动插空格（Compose 无自动 spacing，手插 thin space 会在换行时断裂）；依靠 Noto Sans SC 自带的西文侧距。关键技术点：**全 App 只用 Noto Sans SC 一套拉丁字形来源**，不混搭 Roboto 数字，避免字重/字高跳动。
4. **标点悬挂**：Compose 不支持 CSS 式 hanging punctuation。替代方案：标题文案**避免行尾标点**；正文依靠 1.7 行高消化全角标点占据的视觉重量。设置 `includeFontPadding = false`（Compose 1.5+ 默认行为，显式声明以防回归）。
5. **拼音小字：不采纳**（理由见 §1.3）。其"双行标签"结构被 mono 微标签继承，只用于分组标题一处，不扩散到菜单项——菜单项保持单行，降低扫读成本。

---

## 4. 间距 / 圆角 / 高度体系

### 4.1 间距 token（8pt 网格）

| Token | 值 | 典型场景 |
|---|---|---|
| `space-1` | 4dp | 徽章内距、点与文字、chip 纵向 |
| `space-2` | 8dp | 相关元素间距、图标与文字、消息同发送者间距 |
| `space-3` | 12dp | 组内元素、卡片内上下分段、chip 间距 |
| `space-4` | 16dp | 卡片内边距、**页面左右边距（默认）** |
| `space-5` | 20dp | 卡片之间 |
| `space-6` | 24dp | 分区之间、弹窗内边距 |
| `space-8` | 32dp | 大分区、空态上下 |
| `space-10` | 40dp | 空态留白 |
| `space-12` | 48dp | 触控目标基准 |

- 页面边距响应式：`clamp(16dp, 4vw, 24dp)`（Compose 侧用 `WindowSizeClass` 或简单 `min(24.dp, max(16.dp, (config.screenWidthDp * 0.04f).dp))`）。
- 消息流气泡最大宽：用户 `clamp(240dp, 78vw, 420dp)`，AI `clamp(280dp, 88vw, 560dp)`。**clamp 百分比基准 = 消息列容器宽，不是屏幕宽（v2.1，R9）**：Expanded（>840dp）内容区限宽 680dp 后，88vw 会溢出容器；Compose 侧用 `BoxWithConstraints` 取容器 maxWidth 代入计算，dp 上下限语义不变。

### 4.2 圆角体系

| Token | 值 | 用于 |
|---|---|---|
| `radius-xs` | 6dp | 徽章、tag、代码块、checkbox |
| `radius-s` | 10dp | 按钮、输入框、小卡、图标底座 |
| `radius-m` | 14dp | **卡片、气泡、列表项、日志行** |
| `radius-l` | 20dp | Dialog、BottomSheet、大卡 |
| `radius-xl` | 28dp | 空态 hero 卡（唯一使用处） |
| `radius-full` | 999dp | 胶囊、开关、圆形按钮、药丸指示器 |

M3 `Shapes` 映射：`extraSmall=6dp / small=10dp / medium=14dp / large=20dp / extraLarge=28dp`。
聊天气泡"尾巴角"：靠头像一侧的对角收窄为 **4dp**（用户=右上、AI=左上）。

### 4.3 阴影 / 高度层级

Compose M3 双层 elevation（tonalElevation 用色阶层级表达，shadowElevation 只给真正"浮起"的组件）：

| 层级 | shadowElevation | 表达方式 |
|---|---|---|
| 页面背景 | 0dp | `--ak-bg` |
| 卡片 / 气泡 / 列表项 | **0dp + 1dp outline（outline@30-35%）** | `--ak-surface-2` 实色 + 描边。**禁重阴影** |
| 顶栏 / 输入栏 | 0dp | `--ak-surface` + 底部 1dp outline（滚动时也不抬 elevation，避免双线） |
| FAB（如使用） | 3dp | `--ak-primary-container` |
| Dialog / BottomSheet | 8dp | `--ak-surface-3` + scrim |
| 按下态 | 0dp | `graphicsLayer { scaleX/Y = 0.97f }` + 8% primary 叠加，**不用 elevation 变化** |

### 4.4 触控目标

- 所有可点元素 ≥ **48dp × 48dp**（IconButton 默认即 48dp；自定义 toggle 视觉 46×28dp，但触控容器 48×48dp）。
- 列表项高 ≥56dp；审批弹窗按钮高 48dp；底部 Tab 高 64dp。
- 免询 checkbox（§5.3）：**视觉 40dp + 触控容器 48×48dp**（v2.1，R6）——视觉收窄保持行内轻盈，触控不妥协。

---

## 5. 组件视觉规范（8 个关键组件）

> 每个组件给出：结构 / 尺寸 / 颜色引用 / 状态 / 动效。颜色引用用 token 名（对应 §2.7）。

### 5.1 聊天气泡（四种形态）

| 形态 | 结构 | 尺寸 | 颜色 | 状态 |
|---|---|---|---|---|
| **用户气泡** | 右对齐：文本气泡 + 右侧 32dp 圆头像 | maxW `clamp(240,78vw,420)`；padding h14 v10；radius 16dp，右上 4dp | bg `primary-container`，text `on-primary-container`；头像 bg `primary`，图标 `on-primary` 20dp | default / pressed(scale .98, 150ms) / 发送中(头像外圈 2dp primary 弧线旋转) |
| **AI 气泡** | 左对齐：32dp 圆头像 + 文本气泡 | maxW `clamp(280,88vw,560)`；同半径镜像（左上 4dp） | bg `surface-2`（无描边，靠底色差分层）；头像 bg `tertiary-container`，图标 `on-tertiary-container` | default / pressed / **streaming**（尾部 2×14sp primary 闪烁块，800ms loop）/ **failed**（1dp `danger`@60% 描边 + 气泡下"重试"TextButton） |
| **工具调用** | **不是气泡**，走 §5.2 ToolCard，左右 16dp 边距 | — | — | — |
| **系统提示** | 居中单行，无气泡；或 outline 胶囊 | 胶囊 padding h10 v4，radius full | text `on-surface-ter` 11sp；胶囊 1dp outline@30% | default |

通用规则：同发送者相邻消息间距 8dp，切换发送者 16dp；消息组之间插入居中时间戳（`machineSmall`，`on-surface-ter`）；新消息入场 `fadeIn + slideInVertically(8dp)`，200ms。气泡 maxW 的 clamp 基准为**消息列容器宽**（§4.1，v2.1）。

### 5.2 工具调用卡片

- **容器**：宽 = 屏宽 − 32dp；`surface-2`，radius 14dp，padding 12dp；1dp 状态描边：running `primary`@40% / done `outline`@35% / fail `danger`@60% / pending `outline`@35% / **denied `outline`@35%（中性，不用 danger）**。
- **Header（高 48dp）**：`[状态点 8dp] [工具名 machineStrong on-surface] [权限徽章] — [时长 machineSmall on-surface-ter] [chevron 20dp（触控 48dp）]`。
- **权限徽章**：mono 10sp，radius 6dp，padding h6 v2；高危工具（ui_tap/ui_input/adb.shell 等）→ `danger`@12% 底 + 「需审批」；普通 → `primary`@12% 底 + 「已授权」。**文字色（v2.1，R12）**：Dark 用语义色亮文（`danger` / `primary`，实测 5.11–7.59:1）；Light 的 tint 底会压低对比度，文字换 `on-danger-container` / `on-primary-container` 深色墨迹（实测 7.18 / 11.52:1）。
- **展开区**（animateContentSize 300ms）：「入参 ARGS」`microLabel` `on-surface-ter` + 代码块 `machineText` on `surface-4`，radius 10dp，padding 12dp，maxHeight 160dp 可滚动；「结果 RESULT」同；ERROR 时代码块换 `danger`@8% 底 + `danger` 文字。
- **多步进度**：Header 下 2dp 进度条（底 `outline`@20%，前景 `primary`）+ 右端 `STEP 3/7`（machineSmall，`on-surface-ter`）。
- **状态动效**：running 状态点 pulse（alpha .4↔1 + scale .8↔1.1，1200ms infinite）；done 绿色 pop（scale 0→1.25→1，spring 300ms）；fail 红点 shake（translateX ±2dp ×2，200ms）；chevron 旋转 180°，250ms standard。
- **denied 态（v2.1，R3 新增第五态）**：1dp `outline`@35% 中性描边；状态点 8dp `outline-strong`（中性灰，可见但不抢注意力，不用语义色）；Header 右侧「已拒绝」12sp `on-surface-var` + 禁行图标（1.5dp stroke）；展开区可展示拒绝原因（如有）。**不用红色**——拒绝是用户的正常决策、不是系统故障（红色只留给危险动作）；TalkBack 播报「已拒绝执行」。状态机定义（QUEUED→DENIED 终态）见体验规范 §3.2。

### 5.3 危险操作审批 Dialog（招牌组件）

**设计目标：让人警觉，但不恐慌。** 全屏阻断（scrim 64%）传达"此事重大"；信号青以外的语义红只出现在图标、左边线、主按钮三处，不做大面积红底。

- **容器**：宽 `min(340dp, 92vw)`；radius 20dp；bg `surface-3`；padding 24dp；shadow 8dp；scrim `#000@64%` fade 200ms。
- **顶部微标签**：`APPROVAL REQUIRED · 需要你的授权`，microLabel 10sp letterSpacing .12em，色 `danger`。
- **图标**：居中 56dp 圆，`danger-container` 底 + 1.5dp `danger`@50% 描边 + 28dp `danger` 盾牌线图标。**呼吸脉冲**：scale 1.0↔1.06 + alpha .55↔1.0，1600ms infiniteReverse。
- **标题/副文**：「确认执行危险操作」titleLarge 18/26 w600 `on-surface`；「Agent 正在请求操控手机，该操作执行后不可撤销。」bodyMedium `on-surface-var`。
- **「将执行」块**：bg `surface-4`，radius 10dp，**左侧 2dp `danger` 竖条**，padding 12dp；内容 `machineText` 12/20：工具名 + 参数（换行展示，包名/路径用 `tertiary` 强调），最多 6 行 + 滚动。
- **来源行**：「来源：技能「xx」· 触发指令「打开设置并点击 WLAN」」11sp `on-surface-ter`，可截断。
- **免询勾选行**：「本次会话不再询问同类操作」13sp `on-surface-var` + checkbox **视觉 40dp、触控容器 48×48dp**（v2.1，R6）（未选 2dp `outline-strong`；选中 `primary` 底 + 14dp `on-primary` 对勾）。
- **按钮**：Row，gap 12dp，高 48dp，radius 10dp。「拒绝」OutlinedButton flex 1，描边 **`outline-strong`**（v2.1，R2：交互边界走强轨，实测 Dark 3.21:1 / Light 3.06:1，原 `outline` 仅 1.43:1 / 1.32:1），文字 `on-surface` w600；「允许执行」Button flex 1.2，bg `danger`，文字 **`on-danger`** w600（v2.1，R1：Dark `#00272B` 实测 5.73:1 / Light `#FFFFFF` 实测 5.23:1，原 Dark 白字 2.76:1 不达 AA）。**两者等高——不制造"鼓励用户点允许"的视觉偏袒**（R2 只把「拒绝」的描边从 1.4:1 提到 3:1 档让其可被看见，不改变「填充=主 / 描边=次」的既定层级，也不改变等高约束）。
- **默认安全机制（v2.1，R5：由「可选安全变体」升为默认开启）**：底部 3dp `danger` 自动拒绝进度线 + `10s 后自动拒绝`（machineSmall，`danger`），10s 到点自动 deny；设置项可关闭（不鼓励）。倒计时是「紧迫但不恐慌」的组成部分（§6.2-1），也是防 AgentLoop 无限挂起的产品底线。
- **动效**：入场 scale .9→1 + fade，spring(damping .65, stiffness 500) 350ms；退场 fade + scale .96，200ms；允许时按钮内 Check 图标 pop（scale 0→1.2→1，300ms）。
- **与旧实现差异**：现有 `AlertDialog` 的「允许一次 / 始终允许」两个 Button 平级堆叠——废弃。改为单选决策（允许/拒绝）+ 免询勾选行，消灭"三个主按钮抢注意力"。

### 5.4 抽屉导航（侧边栏）

- **抽屉**：宽 `clamp(280dp, 82vw, 320dp)`；bg `surface-1`；右缘 1dp `outline`@30%；scrim `#000@40%` fade 250ms；入场 slideInHorizontally + fade，300ms emphasized。
- **Header**（padding 16dp）：40dp radius 10dp 应用标记（`primary-container` 底 + 24dp `primary` 盾牌线图标）+「AgentKit」18sp w600 + 微标签 `v1.0 · SANDBOX READY`（machineSmall `on-surface-ter`）；下方状态胶囊（radius full，**两态**）：① 就绪态——底 `success`@12% + 11sp「● 系统就绪」；② **待授权态（v2.1，R11 新增）**——任一关键权限缺失时切换为底 `warning`@12% + 11sp「● N 项待授权」，N = 缺失的关键权限数，全部就绪后回①。**胶囊文字色（v2.1，R12）**：Dark 用 `success` / `warning` 亮文（7.69:1）；Light 换 `on-success-container` / `on-warning-container` 深色墨迹（10.16–14.09:1，原 success 亮文仅 4.41:1 已修）。
- **分组标题**：双行——中文 12sp w500 `on-surface-var` + mono 微标签 10sp `on-surface-ter`（如 `设备感知 / DEVICE SENSE`）；padding top 20dp bottom 8dp。
- **菜单项**：高 56dp，radius 10dp（h8 边距），padding h8；图标 22dp 线图标 1.5dp stroke；标签 15sp `on-surface`；右侧角标：计数胶囊（mono 10sp，高 ≥18dp，radius full，`surface-4` 底 + `on-surface` 字）或**审批红点**（8dp `danger`，pulse）。
- **选中态**：bg `primary`@12%，图标+文字 `primary`，左缘 3dp `primary` 圆角竖条；pressed scale .98 + `primary`@20%。
- **角标规则**：仅 >0 时显示（0 不显示，减少噪音）；审批/未处理类一律红点不记数。

### 5.5 底部导航栏（3 Tab：对话 / 模型 / 控制台）

- **栏体**：高 64dp + `navigationBarsPadding`；bg `surface`；顶部 1dp `outline`@30%（不用 elevation）。
- **Item**：图标 24dp + label `labelMedium` 12/16 w500（v2.1，R7：统一为体系内字号，原 10.5sp 非体系值）；选中：图标与文字 `primary` + 药丸指示器（64×32dp，radius full，`primary-container` 底）；未选：`on-surface-var`。
- **命名（v2.1，R10）**：「自动化」Tab 更名「**控制台**」——页面内容已重组为控制台仪表盘（route=`console`），命名与内容对齐；「自动化」一词保留于功能语义（总开关、自动化流程、自动化触发），不废弃。fontScale >1.3 时栏高 64→72dp（§3.3-1 例外条款配套）。
- **角标**：仅对话 Tab 支持未读红点（8dp `danger`，图标右上 −2dp，pulse）。
- **动效**：药丸 scale .9→1 spring(damping .72, stiffness 450) 250ms；颜色 tween 200ms。

### 5.6 模型配置页

- **分区卡**（复用现有 SectionCard 结构）：`surface-2`，radius 14dp，padding 16dp；标题 14sp w600 + mono 微标签。
- **输入框**：OutlinedTextField；radius 10dp；默认描边 1dp `outline`，focused 2dp `primary`；label 12sp `on-surface-var`→focused `primary`；**Base URL / 模型名字体用 `machineStrong` 13sp**；Key 字段 masked（`••••`）+ trailing 眼睛 IconButton 48dp。
- **Key 自检行**（现有 KeySelfCheck 视觉升级）：mono 11sp；正常：6dp `success` 点 +「长度 56 · sk-a…7F3A · 校验通过」`on-surface-var`；异常：`danger` 点 +「检测到 3 个不可见字符，保存时自动清除」`danger`。
- **测试连接按钮**：高 48dp，radius 10dp，full width；默认 `primary` 实心；testing：左 18dp CircularProgressIndicator 2dp +「测试中… 1.2s」（mono 计时）；ok：`success-container` 底 + Check +「连接正常 · 214ms」；fail：`danger-container` 底 +「401 · Key 无效」+ 12sp 建议文字。**结果态文字色（v2.1，R12）**：Dark 用 `success` / `danger` 亮文（6.56 / 5.62:1）；Light 换 `on-success-container` / `on-danger-container`（14.07 / 7.91:1，原 Light `danger` 亮文仅 4.32:1 已修）。Check/感叹图标维持语义色（非文字图形，≥3:1 即合规：Light 4.41:1 通过）。
- **结果卡**：radius 14dp，1dp 状态描边（ok `success`@40% / fail `danger`@40%），padding 14dp；字段行 `machineSmall`（端点/指纹/响应预览），行距 4dp；右下「复制」TextButton。
- **保存/取消**：底部 Row；取消 Outlined flex 1（描边 `outlineStrong`，v2.1 R2 全 App 规则）、保存 `primary` flex 1；disabled 38% alpha。

### 5.7 技能卡片

- **容器**：`surface-2`，radius 14dp，padding 16dp，元素间距 10dp。
- **Header**：32dp radius 8dp 图标底座（信任色@12% 底 + 22dp 信任色线图标）+ 名称 15sp w600 + 版本 mono 10sp `on-surface-ter`；右侧 toggle。
- **信任徽章**（三种，替换旧 trust 映射）：

| 等级 | 样式 | 文案 |
|---|---|---|
| packaged | `success`@12% 底 + Dark `success` / Light `on-success-container` 文字 | ● 内置 |
| imported | `warning`@14% 底 + Dark `warning` / Light `on-warning-container` 文字 | ● 导入 |
| sandbox | `surface-4` 底 + `on-surface-var` 文字 | ● 沙箱 |

（mono 10sp，radius 6dp，padding h6 v3；文字色规则见 §2.4「徽章/胶囊文字规则」，v2.1 R12：Dark 语义色亮文 6.95:1 / Light 深色墨迹 9.16:1，原 Light `warning` 亮文 4.40:1 已修）
- **描述**：13sp/20 `on-surface-var`，maxLines 2 ellipsis。
- **权限 chips**（FlowRow，gap 6dp）：mono 10sp，radius 6dp，padding h6 v2，`surface-4` 底 + `on-surface-var` 字；高危工具 chip 换 `danger`@12% 底 + `danger` 字（如 `ui_input` `adb.shell`）；最多 3 个 + `+N` chip。
- **Footer**：左「脚本执行：已授权 / 未授权」12sp（`success` / `warning`）；右 TextButton：「授权脚本」（`primary`，仅 imported 时）/「撤销」「卸载」（`danger` 文字）。
- **Toggle**：track 46×28dp radius full；thumb 选中 22dp `on-primary` / 未选 16dp `outline-strong`；选中 track `primary` / 未选 `surface-4` + 1dp `outline`；触控 48×48dp；thumb 位移 spring(.7, 500) 250ms，颜色 tween 200ms；禁用 38% alpha。
- **展开明细**：点卡片展开（animateContentSize 300ms）：每个工具一行——14dp 图标（allowed `primary` 对勾 / blocked `danger` 禁行）+ mono 11sp 工具名 + capability tag +「放行/拦截」。

### 5.8 运行日志条目

- **行**：可点（min 48dp，auto height），padding h12 v8；条目间 1dp `outline`@20% 分隔。
- **组成**：`[级别点 8dp] [级别缩写 mono 10sp 级别色] [时间戳 machineSmall on-surface-ter] [来源 chip] [消息 13sp/20 on-surface]`；消息超 2 行 ellipsis。**级别缩写是文字双编码（v2.1，R4）**：色盲/读屏用户靠缩写辨识级别，级别点降为 decorative；缩写常驻不占额外空间（替换原行首空白），实测对比度见 §2.4（均 ≥4.66:1）。
- **来源 chip**：mono 10sp，radius 4dp，padding h4 v1，`surface-4` 底 + `on-surface-var`（ADB / LLM / SANDBOX / HTTP）。
- **级别点色**：DEBUG `log-debug` / INFO `log-info` / WARN `warning` / ERROR `danger`（点 decorative；级别语义由同色缩写文字承载，双编码）。
- **展开**：chevron；展开区 `machineSmall` on `surface-4`，radius 8dp，padding 10dp，maxHeight 160dp 滚动；ERROR 时换 `danger`@8% 底。
- **筛选 chips**（日志容器顶部）：ALL / INFO / WARN / ERROR，mono 10sp；选中 `primary`@12% 底 + Dark `primary` / Light `on-primary-container` 字（v2.1，R12；11.52:1，原 Light 4.39:1 已修），未选 `surface-4` 底 + `on-surface-var`。
- **滚入动效**：新条目 alpha 0→1 + translateY 12dp→0，250ms emphasized decel；级别点 scale 0→1.2→1 spring 300ms；WARN/ERROR 首次出现时左缘 2dp 色条 scaleX 0→1，300ms。

---

## 6. 动效规范

### 6.1 曲线与时长 token

| Token | 值（Compose 可直写） | 用途 |
|---|---|---|
| `motionInstant` | `tween(100, easing = CubicBezierEasing(0.2f,0f,0f,1f))` | 颜色/透明度微变 |
| `motionFast` | `tween(150, easing = CubicBezierEasing(0.2f,0f,0f,1f))` | pressed/release、toggle |
| `motionBase` | `tween(250, easing = CubicBezierEasing(0.2f,0f,0f,1f))` | 大多数过渡（chevron、Tab、fade） |
| `motionSlow` | `tween(400, easing = CubicBezierEasing(0.05f,0.7f,0.1f,1f))` | emphasized：Dialog、Sheet、页面切换 |
| `motionXSlow` | `tween(600, easing = CubicBezierEasing(0.05f,0.7f,0.1f,1f))` | 空态/hero（唯一允许 >400ms 的常规场景） |
| `springSnappy` | `spring(dampingRatio = 0.72f, stiffness = 450f)` | 药丸指示器、徽章 pop |
| `springSoft` | `spring(dampingRatio = 0.85f, stiffness = 300f)` | 气泡/卡片缩放回弹 |
| `pulseLoop` | `tween(1600, easing = LinearEasing)` + `RepeatMode.Reverse` infinite | 运行状态点、审批图标呼吸 |
| `shake` | `keyframes`：translateX 0→±2dp→0，总 200ms | 失败反馈 |

**全 App 常规过渡不超过 400ms**；超过 600ms 的动效禁止（会让人等待）。

### 6.2 三个标志性动效

1. **审批脉冲（Approval Pulse）** —— 弹窗入场 scale .9→1 spring 350ms + 盾牌图标 1600ms 呼吸 + 底部 10s 自动拒绝进度线。
   *传达什么*：决策权在你、系统在等待。紧迫（全屏阻断、脉冲、倒计时）但不恐慌（无闪烁、无全屏红、无双按钮诱导）。
2. **信号扫描（Signal Sweep）** —— 工具执行中，卡片顶部 2dp `primary` 光带从左至右循环扫过（1500ms），配合 `STEP n/m` 计数与状态点 pulse。
   *传达什么*：确定性的机器进度——不是魔法黑箱，是流水线。用户随时知道"走到第几步了"。
3. **遥测滚入（Telemetry Roll-in）** —— 新日志 alpha+12dp 上移 250ms，级别点 spring 弹入，WARN/ERROR 左缘色条 scaleX 划入 300ms。
   *传达什么*：系统在实时记录、每条可追溯。色条让"出问题了"在一屏日志里自己跳出来。

---

## 7. 落地映射（前端工程师接口）

### 7.1 Theme.kt 重构方案

建议拆为四个文件：`Theme.kt`（组装）+ `Color.kt`（色值）+ `Type.kt` + `Shape.kt` / `Motion.kt`（token）。

**ColorScheme 字段映射（旧 → 新）**

| ColorScheme 字段 | 旧 Light | 旧 Dark | **新 Light** | **新 Dark** |
|---|---|---|---|---|
| primary | #4F46E5 | #B7B4FF | `#00707A` | `#56D9D6` |
| onPrimary | #FFFFFF | #221F5C | `#FFFFFF` | `#00272B` |
| primaryContainer | #E4E2FF | #37346E | `#B8ECEC` | `#0E4A4C` |
| onPrimaryContainer | #1B1A57 | #E4E2FF | `#002A2E` | `#A8F0EE` |
| secondary | #5B57D1 | #C2BFFF | `#4E5D6C` | `#9FB2C4` |
| secondaryContainer | #E7E5FF | #3A3770 | `#DCE5EE` | `#2A3644` |
| onSecondaryContainer | #1A1860 | #E7E5FF | `#1B2836` | `#C7D6E4` |
| tertiary | #7B5EA7 | #DCBEFF | `#5B4BC4` | `#B9A8FF` |
| tertiaryContainer | #F0E4FF | #4A2B69 | `#E4DEFF` | `#372A6B` |
| onTertiaryContainer | #2C1442 | #F0E4FF | `#241A52` | `#E2D9FF` |
| background / surface | #FAFAFC | #12131B | `#F5F6F8` | `#0B0E14` |
| onBackground / onSurface | #1A1B25 | #E6E6F0 | `#14181F` | `#F0F3F8` |
| surfaceVariant | #E9E9F2 | #272839 | `#E9ECF1` | `#232936` |
| onSurfaceVariant | #4A4C5E | #C6C6D6 | `#4A5261` | `#A3AEC0` |
| outline | #C6C6D6 | #8E8E9E | `#C9CFDA` | `#39414F` |
| error | #B3261E | #FFB4AB | `#CC2F2F` | `#FF6B72` |
| **新增** onError（error 填充内容色） | — | — | `#FFFFFF` | `#00272B` |
| errorContainer | #FCE4E4 | #93000A | `#FCE4E4` | `#451217` |
| onErrorContainer | #8C1009 | #FFDAD6 | `#8C1009` | `#FFDADD` |
| **新增** surfaceContainerLow | — | — | `#FAFBFC` | `#151A23` |
| **新增** surfaceContainer | — | — | `#F0F2F5` | `#191E28` |
| **新增** surfaceContainerHigh | — | — | `#E9ECF1` | `#222836` |
| **新增** surfaceContainerHighest | — | — | `#DFE3EA` | `#2B3242` |
| **新增** inverseSurface / inverseOnSurface | — | — | `#2B3242` / `#F0F3F8` | `#E6EAF2` / `#191E28` |
| **新增** scrim | — | — | `#000000@48%` | `#000000@64%` |

> Material3 版本注意：`surfaceContainer*` 字段需 material3 ≥ 1.2.0。若当前版本低于此，用 `surface` + 一个 `LocalAgentKitColors`（staticCompositionLocalOf）扩展对象承载 surface-1/2/3/4、outlineStrong 与全部语义色，无需改库版本。

**语义色扩展对象**（替代现有 `AgentColors` 的单值 success/warning/error）：

```kotlin
data class AgentKitSemanticColors(
    val primaryFixed: Color,        // 不随明暗变的信号青（仅特殊场景）
    val danger: Color, val dangerContainer: Color, val onDanger: Color, val onDangerContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val trustPackaged: Color, val trustImported: Color, val trustSandbox: Color,
    val logDebug: Color, val logInfo: Color, val logWarn: Color, val logError: Color,
    val outlineDecorative: Color, val outlineStrong: Color, val textTertiary: Color,
)
val LocalAgentKitColors = staticCompositionLocalOf { LightSemantic }
```

明暗两套值，`AgentKitTheme` 内随 `isSystemInDarkTheme()` 切换。**`AgentColors` 旧对象删除**，所有引用点改为 `LocalAgentKitColors.current.xxx`。

**v2.1 承载说明（R1/R2）**：`on-danger` 双通道承载——① M3 原生槽位 `ColorScheme.onError`（Light `#FFFFFF` / Dark `#00272B`），供 `ButtonDefaults.buttonColors(contentColor = …)` 直接取用；② `AgentKitSemanticColors.onDanger`，供显式 token 引用。审批按钮落地示例：

```kotlin
// 「允许执行」：danger 填充 + on-danger 文字（R1，5.73:1 / 5.23:1）
Button(
    onClick = onAllow,
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,      // #FF6B72 / #CC2F2F
        contentColor = MaterialTheme.colorScheme.onError       // #00272B / #FFFFFF = --ak-on-danger
    )
) { Text("允许执行", fontWeight = FontWeight.SemiBold) }

// 「拒绝」：Outlined 描边走 outlineStrong 强轨（R2，3.21:1 / 3.06:1）
OutlinedButton(
    onClick = onDeny,
    border = BorderStroke(1.dp, LocalAgentKitColors.current.outlineStrong)
) { Text("拒绝", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) }
```

**Typography 替换点**：`bodyMedium 15/22 → 15/26`；`bodySmall 13/18 → 13/20`；`labelSmall 11/16 → 11/14`；`titleLarge 20/28 → 18/26`（原 titleLarge 20sp 偏大，顶栏用 18sp）；补齐 display/headline 各级；新增 `machineText / machineSmall / machineStrong / microLabel` 四个自定义 TextStyle。

**Shapes 替换点**：`8/12/16 → extraSmall 6 / small 10 / medium 14 / large 20 / extraLarge 28`。

**其他**：`AgentKitTheme` **不启用 dynamicColor**（保护信号青与语义色系统）；如未来要支持，加参数 `dynamicColor: Boolean = false`。字体走 google-fonts downloadable，`FontFamily(Font(googleFontProvider = provider, FontWeight.Normal), ...)`，回退 `FontFamily.SansSerif`。

### 7.2 组件重做优先级

| 优先级 | 组件 | 动作 |
|---|---|---|
| **P0** | Theme（Color.kt/Type.kt/Shape.kt/Motion.kt） | 全量替换；`AgentColors` → `LocalAgentKitColors` |
| **P0** | ApprovalDialog | 重做为招牌审批界面（§5.3）；废弃双 Button 平级 |
| **P0** | MessageBubble / ToolCard / StatusBadge | 新配色 + 状态点 + mono 工具名 + 权限徽章 |
| **P1** | 抽屉导航（新增） | 按 §5.4 新建，替代顶栏 settings/tune 双图标的隐藏入口 |
| **P1** | BottomNav | 药丸指示器 + 48dp 保障 |
| **P1** | SkillCard | 信任徽章三色化（**remote→error 的旧映射废弃**：红色留给危险动作；imported→warning；新增 sandbox）+ 自定义 toggle |
| **P1** | 日志区 | 级别色点 + mono + 可展开堆栈 + 筛选 chips（替代当前 surfaceVariant@55% 纯文本块） |
| **P2** | ModelConfigScreen | mono 字段、指纹自检行、测试连接按钮状态机 |
| **P2** | 空态 / SectionCard | radius 14 + surface-2 + mono 微标签；hero 卡 radius 28 |
| **P2** | 图标集 | emoji 与文本符号（✅⚠️❌↗）全部替换为线性格矢量图标 1.5dp stroke |

### 7.3 视觉回归检查清单（验收逐条核对）

- [ ] 1. 正文对比度：抽查 `onSurfaceVariant / surface-2`、`textTertiary / bg`，明暗双套均 ≥4.5:1
- [ ] 2. 所有可点区域 ≥48dp（IconButton、toggle、列表项、Tab）
- [ ] 3. 无 emoji 充当 UI 图标；无 ✅⚠️❌ 等文本 emoji
- [ ] 4. 机器输出（日志/命令/参数/URL/Key/时间戳/模型名/计数/时长）一律 mono 字体
- [ ] 5. 高亮色全 App 只有 `primary` 一个；语义色只出现在状态场景，无装饰性大红大绿
- [ ] 6. 圆角只取 6/10/14/20/28/full；阴影只取 0/3/8dp；卡片无重阴影
- [ ] 7. 装饰线 `outline`@30% 与交互边界 `outlineStrong`（≥3:1）分开使用，焦点/选中/错误走强轨
- [ ] 8. 明暗两套完整跑一遍：无对比度不足、无纯黑 #000 大面积、无纯白 #FFF 大面积
- [ ] 9. 审批弹窗：拒绝与允许按钮等高；允许为 `danger` 填充 + **`on-danger` 文字（≥4.5:1）**；拒绝描边 **`outlineStrong`（≥3:1）**；无第三个同级主按钮；有免询勾选行（触控 48dp）；**10s 自动拒绝进度线默认显示**
- [ ] 10. 数字列（时间戳、计数、STEP）等宽对齐，无比例数字混入
- [ ] 11. 常规动效 ≤400ms，且只取 §6.1 token 表内的曲线与时长
- [ ] 12. 深色模式下日志/代码块文字对比度仍 ≥4.5:1（mono 小字最易翻车）
- [ ] 13. 若含数据图表：上涨=红、下跌=绿，且图表内不混用状态语义色

---

## 8. 设计决策摘要

### 三个最大胆的取舍

1. **深色优先 + 单一信号青。** 全 App 只允许一个高亮色（#56D9D6 / #00707A），语义色严格限定在 danger/warning/success/trust 四个状态槽位。放弃了 Indigo 品牌色和"多彩徽章更活泼"的直觉——自动化工具的可信感来自色彩自律，不是色彩丰富。用户扫一眼界面，"哪里有颜色"就等于"哪里有状态"。
2. **机器文本强制等宽化。** 所有 AI/系统产出的文字（日志、命令、参数、指纹、时间戳、模型名、计数）走 JetBrains Mono，人类文字走 Noto Sans SC。"谁在说话"一眼可辨，这既是硬核工具感的根基，也顺带根治了中英数字混排的对齐问题。
3. **审批弹窗升格为"决策界面"。** 从普通 AlertDialog 重构为：全屏阻断 + 呼吸脉冲图标 + 「将执行」等宽回显 + 来源回溯 + 10s 自动拒绝进度线 + 免询勾选。把产品最差异化的能力（审批闸门）做成视觉招牌——它正是 AgentKit 与普通聊天 App 的本质分界线。

### 三个最容易被忽略但决定品质的细节

1. **outline 双轨制。** 装饰分隔线（outline@30%，1.5–1.7:1）与交互边界（outlineStrong，≥3:1）彻底分开。界面"廉价感"的最大来源是该弱的不弱、该强的不强；焦点/选中/错误边框一律走强轨，分隔线一律弱化。
2. **中文行高的绝对值策略。** bodyMedium 15/26、bodySmall 13/20、labelSmall 11/14，全部写死 sp 值 + `includeFontPadding=false`。不同 ROM 的字体度量差异会让倍数行高裁切descender 或挤成一团——写死绝对值是跨设备一致性的最低成本方案。
3. **角标克制与"零不显示"。** 九宫格/抽屉/技能的计数一律 >0 才显示，审批类用脉冲红点而非数字。当屏幕上只有"真正需要处理的事"在发光，用户的注意力才不会被训练成忽略红色。

---

*规范完。前端工程师请从 §7.1 开始落地；视觉验收按 §7.3 清单逐条打勾。*
