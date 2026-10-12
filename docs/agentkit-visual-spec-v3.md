# AgentKit 视觉风格规范 v3

> 版本：v3.1（v3.1 = 导航架构修订：底部 Tab 废止，抽屉为唯一 App 级导航；色彩/字体/圆角/间距/动效 token 全部沿用 v3.0，修订记录见 §1.4）· 适用平台：Android (Jetpack Compose + Material 3) · minSdk 26
> 替代范围：`ui/Theme.kt` 整体替换；`ChatScreen.kt` / `AutomationScreen.kt` 视觉层重做（功能逻辑不动）。
> 设计语言代号：**暖纸 · Warm Paper**
> 与 v2 的关系：v2（Signal Console 深色控制台）**保留作历史存档，不删除**；本文是对 v2 的方向性重订，不是增量修订。v2.1 的 12 条修订（R1–R12）中经过实测验证的规则全部继承，逐条列于 §1.3。

---

## 0. 文档使用说明

- 本文所有色值、字号、圆角、时长均为**最终值**，前端工程师可直接映射为 `Color(0xFF...)` / `xx.sp` / `xx.dp` / `tween(xxx)`。
- `--ak-*` 为设计 token 名（§3.7 给出 CSS 变量全集，方便与设计稿/Web 端对齐）。
- **Light（浅色暖调）为主设计套**；Dark 为配套平行套，允许比 Light 简化（少数仅 Light 精致的调和不在 Dark 1:1 复刻，§3.3 注明）。
- 所有对比度均为 WCAG 2.x 相对亮度公式**实测计算值**（保留两位小数），铁律：**正文 ≥4.5:1、交互边界 ≥3:1**。改任何一个色值必须回到 §3.4 复测。
- 简洁是第一原则：每个视觉决策都必须能回答"它服务哪个用户任务"。答不上来，就删。

---

## 1. 勘误与方向变更说明

### 1.1 勘误：v2 §1.3-2 的差异点作废（前提错误）

| # | v2 的记载 | 事实核对 | 处置 |
|---|---|---|---|
| 1 | v2 §1.3-2 差异点 2：「玄枵用拼音小字注音（xuan xiao）」→「我们不采纳拼音，改用中文 + mono 英文微标签双行」 | 录屏/截图中汉字上方的小字拼音，是**用户自己手机字体自动渲染的注音效果**（部分 OEM 字体/第三方字体应用的全局能力），**不是 App 本身的设计**。玄枵原本就是纯中文界面，无任何拼音 | 该差异点**前提不成立，整条作废**。我们并没有在"反对一个不存在的设计" |
| 2 | 连带：v2 用「中文 + mono 英文微标签」双行结构（`设备感知 / DEVICE SENSE`、`APPROVAL REQUIRED`、`STATUS / TRUST LEVEL` 等）来"保留双行标签的层次美感" | 保留双行的理由随前提一起消失 | **英文微标签全量移除**，界面回归纯中文 |

仍然成立、且比以前更重要的一条结论：**AgentKit 不用拼音，也不用英文微标签——纯中文。** 它从"针对玄枵的刻意差异"变成"RikkaHub 式简洁的共识"。

流程修正（写给以后的竞品分析）：以录屏/截图做参考时，必须先排除用户设备环境（字体、输入法、第三方主题、壁纸、无障碍设置）的干扰，再提取产品设计结论。逐帧核对，以产品实际设计为准。

### 1.2 本次为何转向

产品负责人提供 RikkaHub 录屏与截图，明确要求「按它那样做，简洁一点的」。逐帧核对（剔除拼音干扰后）确认其设计语言为：**淡暖粉底 + 白色大圆角卡片（≈20dp，0 阴影）+ 左侧图标两行列表项 + 大号纯中文标题 + 左上圆形返回/右上 `+` + 左上圆形按钮 + 侧边抽屉导航（无 App 级底部 Tab）+ 低密度大留白 + 零英文术语**。（v3.1 勘误：初版此栏曾误记「底部简单 Tab」——经产品负责人确认，RikkaHub 无 App 级底部 Tab，其页面内 Tab 是内容分区，不是 App 导航，见 §1.4。）

对 AgentKit 的含义：

1. **浅色为主套。** AgentKit 是日用助手（配置一次，天天对话），不是夜间值班的运维控制台。暖色浅底更贴近"放在桌上天天用"的气质。
2. **密度降下来。** v2 的一屏密排是为"监控"服务的；AgentKit 的主场景是"发一条指令、看它执行、必要时批一下"，一屏几物足够。
3. **去掉仪器语汇。** mono 英文微标签、状态描边、光带、脉冲是 v2 的"控制台人格"，与"简便"直接冲突，全部删除或降级。
4. **但差异化组件不砍。** 审批 Dialog / 工具调用卡 / 运行日志 / 技能信任徽章 / 模型 Key 自检是 AgentKit 的立身之本（RikkaHub 没有，因为它的 AI 不操控手机）。保留，但用轻的方式呈现——一张普通卡片、一个克制弹窗，而不是仪表盘。

### 1.3 v3 与 v2：作废清单 / 保留清单

**作废（含 v2.1 中被连带作废的部分）**

| v2 内容 | 处置与理由 |
|---|---|
| Dark 为主设计套（OLED + 夜间场景优先） | **作废** → Light 为主套；Dark 改配套（§3.3） |
| Signal Console DNA：深夜控制台 / 仪表盘 / 遥测 / 硬核工具感 | **作废** → 暖纸风（§2） |
| mono 英文微标签体系（microLabel、DEVICE SENSE、APPROVAL REQUIRED、STATUS/TRUST LEVEL 双行） | **作废** → 纯中文；等宽字体降为纯工具用途（§4.1） |
| 14 个 Typography slot + 4 个自定义样式 | **作废** → 9 slot + 2 个 mono 样式（§4.2） |
| 6 档圆角（6/10/14/20/28/full，28 仅 hero 卡） | **作废** → 4 档：8/14/20/full（§5.2） |
| 卡片 1dp 描边 + Dialog 8dp 阴影 + FAB 3dp | **作废** → **全 App 0 阴影、卡片 0 描边**，分层只靠底色与 scrim（§5.3） |
| 仪器动效全家：信号扫描光带、状态点 pulse、盾牌呼吸 1600ms、shake、pop spring、日志滚入位移、Tab 药丸 spring、气泡 slide-in | **作废** → 只保留 3 个动效（§7） |
| hero 空态卡（radius-xl 28dp + 56dp 图标底） | **作废** → 居中排版空态（§6.9） |
| 抽屉三分组 + 双行分组标题 + 版本号技术行 + 状态胶囊两态容器 | **作废** → 两分组 + 单行中文分组标题 + 一行就绪文字（§6.5） |
| 计数胶囊角标、九宫格计数 | **作废** → 静态小点，全 App 只留 2 处（§6.5） |
| §1.3 整节"与玄枵的差异点" | **作废**（前提错误，§1.1） |
| 底部 Tab 整体（3 Tab 栏体 64dp + 图标文字变色方案） | **v3.1 作废** → 抽屉为唯一 App 级导航（§6.5、§1.4）；药丸指示器早在 v3.0 即废止 |

**保留（v2/v2.1 中被证明正确的规则，原样继承）**

| 保留内容 | 出处 | v3 中的状态 |
|---|---|---|
| 可预审 / 可追踪 / 回放的精神 | v2 §1.1 | 保留，语汇温和化（§2.1） |
| WCAG 实测铁律与全部对比度方法论 | v2 §2.4 | 保留并扩充到新色系（§3.4） |
| 双轨描边：`outline` 装饰（<2:1）/ `outlineStrong` 交互边界（≥3:1） | v2 §2.4/§7.3 | 保留（§3.2、§6.0） |
| 48dp 触控基准；checkbox/toggle 视觉收窄 + 触控容器 48×48 | v2 §4.4 / R6 | 保留（§5.4） |
| 语义色只用于状态，禁止装饰性使用 | v2 §0/§2.1 | 保留（§3.1） |
| DENIED 用中性色不用红（拒绝是用户决策，不是系统故障） | v2.1 R3 | 保留（§6.3） |
| 淡 tint 徽章文字用 `on-*-container` 深色墨迹（Light 套） | v2.1 R12 | 保留并重新实测（§3.5） |
| 审批 10s 自动拒绝默认开启 | v2.1 R5 | 保留（§6.4） |
| 气泡 maxW 的 clamp 百分比基于消息列容器宽，非屏幕宽 | v2.1 R9 | 保留（§6.2） |
| 「控制台」Tab 命名（自动化一词保留于功能语义） | v2.1 R10 | 保留（§6.5） |
| 「● N 项待授权」待办态 | v2.1 R11 | 保留，样式从简（§6.5） |
| 信任三级 packaged / imported / sandbox | v2 §2.5 | 保留，徽章从简（§6.6） |
| 日志级别双编码（色 + 文字） | v2.1 R4 | 保留，文字中文化（§6.7） |
| Key 指纹自检 + 测试连接四态 | v2 §5.6 | 保留，样式从简（§6.8） |
| fontScale >1.3 行高 ×1.4 例外条款 | v2.1 R8 | 保留（§4.3） |
| 抽屉作为全局导航的 IA 决策 | UX v1 D2 | 保留，抽屉重做（§6.5） |
| 不启用 dynamicColor | v2 §7.1 | 保留（§8.1） |

### 1.4 v3.1 修订记录：导航架构变更（底部 Tab 废止，抽屉为唯一 App 级导航）

**触发**：产品负责人审 v3.0 稿后明确反馈：「底部 Tab 完全没必要做，全部做到侧边栏。」参照对象 RikkaHub 本身即**左上角圆形按钮 + 侧边抽屉导航**，无 App 级底部 Tab；v3.0 保留的「底部 3 Tab + 轻抽屉」偏离了参考对象，且常驻 Tab 占 64dp + 手势区空间，不如抽屉按需展开简洁（§1.2 竞品描述已同步勘误）。

**变更一句话**：删掉底部 3 Tab，侧边抽屉升格为唯一 App 级导航；页面内 Tab（模型页「模型/提示词」）保留——它是内容分区，不是 App 导航。

**为什么这是更简（三条，均可验证）**：

| # | 收益 | 量化 |
|---|---|---|
| 1 | 少一个常驻 UI 元素 | 每屏固定元素 −1：Tab 栏体、3 项图标文字、Tab 红点逻辑、`navigationBarsPadding` 容器全删 |
| 2 | 内容区空间 | 每页净增 **64dp**（Tab 栏高）；≈ 2.5 行正文（bodyLarge 26dp 行高）或 1 条两行列表项（72dp）；在 915dp 高机型上 ≈7% 可视高 |
| 3 | 与 RikkaHub 一致 | 左上圆钮 + 侧抽屉即参照对象的导航原型；不再自造「Tab + 抽屉」混合态 |

**页面内 Tab 为什么不冲突**：RikkaHub 自己也有页面内 Tab（内容分区）。「App 级导航」回答"我在哪个功能区"（抽屉承担，全局唯一）；「内容分区」回答"这个页面的两类平级内容看哪类"（页面内 Tab 承担）。两者层级不同，不构成双导航。v3.1 全 App 页面内 Tab **仅模型页 1 处**（§6.5.3）。

**受影响章节**：

| 章节 | 变更 |
|---|---|
| §1.2 / §1.3 | 竞品描述勘误（RikkaHub 无底部 Tab）；作废清单增加「底部 Tab 整体废止」一行 |
| §3.4 | 新增 7 行对比度实测（Light 3 行 + Dark 4 行，均为 v3.1 新增场景：抽屉就绪文字、抽屉选中项文字） |
| §4.2 | labelMedium 用途改为「页面内 Tab」；headlineSmall 的「页面大标题」职责移交顶栏 |
| §5.3 / §5.4 | 层级表「底部 Tab」行替换为「顶栏 / 输入栏」两行；触控节新增顶栏 56dp、抽屉项 52dp 规格 |
| §6.5 | **整节重写**（本修订主体）：抽屉 9 入口 + 顶栏标题化 + 页面内 Tab + 角标/就绪文字/选中态/手势细则 |
| §6.8 | 页面标题移交顶栏；保存/取消行保留在内容区底部 |
| §6.10 | 新增：对话输入栏贴底规范（含玻璃拟态唯一性确认） |
| §7.1 / §7.2 | token 用途文案更新；**不新增动效 token**（抽屉滑入复用 `motionBase`） |
| §8.2 / §8.4 | 组件优先级表更新（含删除旧 Tab 代码）；新增 Mockup 更新清单 |
| §9 | 设计决策摘要第 3 条改写 |

**两处「部分回滚」（v3.0 废止、v3.1 在抽屉内恢复，均有理由）**：

1. **选中态左缘 3dp 竖条**（v3.0 曾以「少一个元素」废止）：抽屉升格为唯一 App 级导航后，选中态从「次级指示」升级为「当前方位」的唯一视觉锚点，9 项列表需要比一块浅底色更强的扫描性；3dp 竖条复用 §6.4 审批竖条 / §6.7 日志色条已有的左缘色条语汇，不引入新元素类型。
2. **运行日志入口的计数角标**（v3.0 废止计数胶囊）：以「纯数字、无底无胶囊」的轻形态恢复 1 处，承载「未读 ERROR 存量」；技能/记忆/模型的计数**不复活**——全 App 角标维持 2 处，防红点/计数通胀。

**动效合规**：抽屉滑入**不新增第 4 个动效 token**——入场复用 `motionBase`（250ms standard 曲线），滑出与 scrim fade 复用 `motionDialog`（200ms），吸附过渡复用 `motionFast`（150ms）。§7.2「3 个有意义的动效」不变，抽屉滑入属于 M2 同 token 家族的结构位移复用。

---

## 2. 设计 DNA

### 2.1 一句话设计主张

> **「每一次替你动手，都先给你看、再让你定、事后查得到。」**

v2 的主张是「可预审、可追踪、可回放的确定感」——精神不变（事前能看到、事中能数步、事后可查），语汇从"确定感"降温为"放心"：AgentKit 是天天放在桌上的助手，不是值班室里的控制台。视觉的第一职责从"监控"变为"陪伴式把关"。

### 2.2 情绪板关键词（5 个）

| 关键词 | 视觉转译 |
|---|---|
| **暖纸** | 淡暖粉底 `#F8EFE9` + 白卡 + 暖灰褐文字（`#2E2620`/`#6B5B4E`/`#756450`）。全 App 无冷灰——v2 的冷蓝灰中性色族整体废弃 |
| **低鸣** | 一屏几物：页面边距 20dp、卡片内边距 20dp、组间距 24dp；纯中文标题，零术语装饰 |
| **清亮** | 浅色为主；层级靠底色分层（粉底 → 白卡 → 更暖的内嵌块），不靠阴影、不靠描边 |
| **稳当** | 全 App 唯一的"重"给审批闸门：红色只出现在图标、左竖条、允许按钮、倒计时线四处，靠色彩 + 排版 + 留白传达警觉，不靠仪表盘语汇 |
| **轻响** | 动效只剩 3 个（§7）；按压即反馈；流式光标是唯一循环动画 |

### 2.3 不适合 AgentKit 的做法（5 项 + 理由）

1. **大面积渐变 / 彩虹渐变按钮** —— 渐变暗示"魔法与娱乐"；审批场景需要"机械与审慎"。实色 + 语义色点缀才是正确语汇。（对齐 ui-design-system「绝对避免」）
2. **廉价重阴影（≥4dp 的卡片堆叠）** —— 暖纸体系里重阴影会立刻制造"廉价感"，且暗色下几乎不可见。本规范更进一步：**全 App shadowElevation = 0**，分层只靠底色。（严于「绝对避免」）
3. **英文术语 / mono 微标签当装饰** —— `DEVICE SENSE`、`APPROVAL REQUIRED` 这类双行结构是 v2 的仪器人格，与"简便"直接冲突，且把阅读成本转嫁给中文用户。等宽字体只服务机器文本对齐，不再是"语汇"。
4. **高密度密排 + 一屏堆满** —— RikkaHub 的"简便"本质是低密度：留白大、一屏几个条目。任何"再加一个信息"的提议，先问能否删掉别的。
5. **全屏跳动效（springy everything）** —— 当一切都 bounce，就没有东西重要了。v3 只保留 3 个动效，且审批弹窗的入场都是克制的 fade + 微缩放，无弹跳。

---

## 3. 色彩系统

### 3.1 色彩策略

- **暖底白卡**：页面底色 `#F8EFE9`（淡暖粉/肉粉），卡片 `#FFFFFF`。分层逻辑：粉底（页面）→ 白卡（内容）→ 暖灰内嵌块 `#F4E9E2`（入参/结果/chip）→ 暖灰按压态 `#EFE2D9`。零阴影零描边，纯靠明度阶层。
- **暖中性色族**：文字与线条全部带暖褐底调（`#2E2620` 暖黑 / `#6B5B4E` 暖灰褐 / `#756450` 暖灰），不用冷灰——冷灰落在暖粉底上会"发脏"。
- **单一主色「青碧」`#0E5D6B`**（选择理由见下）。
- **语义色严格四槽**：danger（审批/破坏性/ERROR）、warning（警惕/导入来源/WARN）、success（就绪/内置/完成）、trust-sandbox（沙箱，中性暖灰）。**红色 = 危险动作**，不用来标记"来源是远程"或"用户拒绝了"。
- **主色选择理由（青碧 #0E5D6B，深青碧/ petrol teal，hue ≈192°）**：
  1. **补色对位**：暖粉底 hue ≈20°，青碧 hue ≈192°，近似互补。粉底越暖，青碧越沉静——"被设计过"的辨识度来自这组对位，而不是再多加一个颜色。
  2. **安全距离**：与四个语义色色相距离 ≥45°（红 hue≈4°、琥珀 hue≈36°、绿 hue≈140°），徽章、状态点、主按钮、日志级别不会互相含糊。
  3. **深而低彩**：白字对比 7.51:1（§3.4 实测），在浅色暖底上柔和却有足够存在感；不刺眼、不模板。
  4. **告别两个旧答案**：不是常见 AI 应用的蓝紫（SaaS 模板感，v2 已切割）；也不是 v2 的亮信号青 `#56D9D6`/`#00707A`（为 OLED 近黑底而生，放在暖纸浅底上会刺眼，且"终端荧光"语汇过强）。
  5. **命名即语汇**：青碧是传统色名，与纯中文界面一致；内部代号「暖纸」，对外不用解释色号。
- **同时出现的色相 ≤5 族**：暖底/白卡/暖灰（同族）、青碧、红、琥珀、绿。其中红/琥珀/绿仅出现在状态语义场景。token 总数 27 个，但全部隶属于上述 5 族（满足 ui-design-system「≤16 种颜色同时出现」且更严格）。

### 3.2 Light 主题（主设计套）

| Token | HEX | 用途 |
|---|---|---|
| `--ak-bg` | `#F8EFE9` | 页面背景（淡暖粉） |
| `--ak-surface` | `#FFFFFF` | 卡片、Dialog、底栏、输入栏 |
| `--ak-surface-1` | `#F4E9E2` | 内嵌块：入参/结果、chip 底、图标底座 |
| `--ak-surface-2` | `#EFE2D9` | 按压态、更深的内嵌 |
| `--ak-surface-var` | `#F6EDE7` | 填充式输入框底 |
| `--ak-on-surface` | `#2E2620` | 主文字（暖黑） |
| `--ak-on-surface-var` | `#6B5B4E` | 次文字/描述（暖灰褐） |
| `--ak-on-surface-ter` | `#756450` | 三级文字/时间戳/禁用 |
| `--ak-outline` | `#E5D6CC` | 装饰分隔线（1.42:1，仅分隔） |
| `--ak-outline-strong` | `#8A7563` | 交互边界/焦点/选中边框（≥3:1） |
| `--ak-primary` | `#0E5D6B` | 青碧：主按钮、选中态、链接、INFO 级 |
| `--ak-on-primary` | `#FFFFFF` | primary 上的文字/图标 |
| `--ak-primary-container` | `#D2E7E9` | 选中底、AI 头像底 |
| `--ak-on-primary-container` | `#093F49` | 其上的文字 |
| `--ak-danger` | `#B4322A` | 审批、破坏性、ERROR 级 |
| `--ak-on-danger` | `#FFFFFF` | danger 填充上的文字（允许执行按钮） |
| `--ak-danger-container` | `#F9E2DF` | 审批图标底、危险徽章底 |
| `--ak-on-danger-container` | `#7E1F16` | 其上的文字 |
| `--ak-warning` | `#8A5A12` | 警惕、导入来源、WARN 级 |
| `--ak-warning-container` | `#F7E8D2` | 警告徽章底 |
| `--ak-on-warning-container` | `#5C3A05` | 其上的文字 |
| `--ak-success` | `#177245` | 就绪、内置信任、完成态 |
| `--ak-success-container` | `#DCF0E3` | 成功徽章底 |
| `--ak-on-success-container` | `#0F4A2A` | 其上的文字 |
| `--ak-trust-sandbox` | `#6B5B4E` | 沙箱信任等级（= on-surface-var） |
| `--ak-log-debug` | `#756450` | 日志「调试」 |
| `--ak-log-info` | `#0E5D6B` | 日志「信息」（= primary） |
| `--ak-inverse-surface` | `#2E2620` | Snackbar/Toast 底 |
| `--ak-inverse-on-surface` | `#F8EFE9` | 其上的文字 |
| `--ak-scrim` | `#1A120D @ 40%` | 弹窗遮罩（暖黑，不用纯黑） |

### 3.3 Dark 主题（配套平行套，可精简）

暖黑体系（非 v2 的冷蓝黑）：底色带咖啡底调，与暖纸同族。允许比 Light 简化的点：内嵌块与按压态的阶层差不必 1:1 复刻 Light 的微妙过渡；徽章 tint 直接用 container 色（不叠 12% alpha）。

| Token | HEX | 用途 |
|---|---|---|
| `--ak-bg` | `#17110E` | 页面背景（暖黑） |
| `--ak-surface` | `#1F1815` | Dialog、底栏 |
| `--ak-surface-1` | `#241C18` | 抽屉 |
| `--ak-surface-2` | `#2A211C` | 卡片/气泡 |
| `--ak-surface-var` | `#372C25` | 内嵌块、输入框、chip 底 |
| `--ak-on-surface` | `#F2EAE3` | 主文字（暖白） |
| `--ak-on-surface-var` | `#BBA99B` | 次文字/描述 |
| `--ak-on-surface-ter` | `#A89A89` | 三级文字/时间戳 |
| `--ak-outline` | `#3E332C` | 装饰分隔线（1.29:1，仅分隔） |
| `--ak-outline-strong` | `#9C8672` | 交互边界（≥3:1） |
| `--ak-primary` | `#7FC8CE` | 青碧亮档 |
| `--ak-on-primary` | `#05292E` | primary 上的深墨迹 |
| `--ak-primary-container` | `#14494F` | 选中底、AI 头像底 |
| `--ak-on-primary-container` | `#B9E6EA` | 其上的文字 |
| `--ak-danger` | `#FF8D82` | 文字/图标/点（亮珊瑚） |
| `--ak-danger-fill` | `#C2483A` | **允许执行按钮填充**（Dark 专用，见 §3.4 注） |
| `--ak-on-danger-fill` | `#FFFFFF` | 其上的文字 |
| `--ak-danger-container` | `#3D1A14` | 徽章底 |
| `--ak-on-danger-container` | `#FFC9C0` | 其上的文字 |
| `--ak-warning` | `#F2B950` | 警告文字/图标 |
| `--ak-warning-container` | `#3A2A12` | 徽章底 |
| `--ak-on-warning-container` | `#F5D9A0` | 其上的文字 |
| `--ak-success` | `#62D996` | 成功文字/图标 |
| `--ak-success-container` | `#123828` | 徽章底 |
| `--ak-on-success-container` | `#BFF0D4` | 其上的文字 |
| `--ak-trust-sandbox` | `#A8988A` | 沙箱信任等级 |
| `--ak-log-debug` | `#A89A89` | 日志「调试」 |
| `--ak-log-info` | `#7FC8CE` | 日志「信息」 |
| `--ak-inverse-surface` | `#F2EAE3` | Snackbar 底（深色下用暖白） |
| `--ak-inverse-on-surface` | `#17110E` | 其上的文字 |
| `--ak-scrim` | `#000000 @ 56%` | 弹窗遮罩 |

### 3.4 WCAG AA 对比度校验表（实测计算值）

> 铁律：正文 ≥4.5:1；交互边界 ≥3:1；装饰分隔线有意 <2:1（只分隔，不提示）。以下为 WCAG 2.x 相对亮度公式实测，保留两位小数。

**Light（主套）**

| 前景 | 背景 | 比值 | 结论 |
|---|---|---|---|
| `#2E2620` 主文字 | `#F8EFE9` bg | **13.09:1** | AAA |
| `#2E2620` 主文字 | `#FFFFFF` 白卡 | **14.85:1** | AAA |
| `#6B5B4E` 次文字 | `#F8EFE9` bg | **5.73:1** | AA |
| `#6B5B4E` 次文字 | `#FFFFFF` 白卡 | **6.50:1** | AA |
| `#6B5B4E` 次文字 | `#F4E9E2` 内嵌块 | **5.45:1** | AA |
| `#756450` 三级文字 | `#F8EFE9` bg | **5.01:1** | AA |
| `#756450` 三级文字 | `#FFFFFF` 白卡 | **5.68:1** | AA |
| `#756450` 三级文字 | `#F4E9E2` 内嵌块 | **4.76:1** | AA |
| `#0E5D6B` primary | `#F8EFE9` bg | **6.62:1** | AA |
| `#0E5D6B` primary | `#FFFFFF` 白卡 | **7.51:1** | AA |
| `#FFFFFF` onPrimary | `#0E5D6B` primary（按钮） | **7.51:1** | AAA |
| `#093F49` onPrimaryContainer | `#D2E7E9` primaryContainer | **8.99:1** | AAA |
| `#0E5D6B` primary | `#D2E7E9` primaryContainer（选中文字） | **5.85:1** | AA |
| `#093F49` onPrimaryContainer | `#E2ECED` primary@12% tint（chip） | **9.60:1** | AAA |
| `#8A7563` outlineStrong | `#FFFFFF` 白卡 | **4.37:1** | 交互边界 ≥3:1 |
| `#8A7563` outlineStrong | `#F8EFE9` bg | **3.85:1** | 交互边界 ≥3:1 |
| `#8A7563` outlineStrong | `#F4E9E2` 内嵌块 | **3.66:1** | 交互边界 ≥3:1 |
| `#E5D6CC` outline | `#FFFFFF` 白卡 | **1.42:1** | 装饰线，仅分隔 |
| `#B4322A` danger | `#F8EFE9` bg | **5.40:1** | AA |
| `#B4322A` danger | `#FFFFFF` 白卡 | **6.12:1** | AA |
| `#FFFFFF` onDanger | `#B4322A` danger（允许按钮） | **6.12:1** | AA |
| `#7E1F16` onDangerContainer | `#F9E2DF` dangerContainer | **8.10:1** | AAA |
| `#7E1F16` onDangerContainer | `#F6E6E5` danger@12% tint（徽章） | **8.29:1** | AAA |
| `#8A5A12` warning | `#F8EFE9` bg | **5.21:1** | AA |
| `#8A5A12` warning | `#FFFFFF` 白卡 | **5.91:1** | AA |
| `#5C3A05` onWarningContainer | `#F7E8D2` warningContainer | **8.45:1** | AAA |
| `#5C3A05` onWarningContainer | `#F1EBE3` warning@12% tint（徽章） | **8.60:1** | AAA |
| `#177245` success | `#F8EFE9` bg | **5.25:1** | AA |
| `#177245` success | `#FFFFFF` 白卡 | **5.95:1** | AA |
| `#177245` success | `#F4E9E2` 内嵌块 | **4.99:1** | AA |
| `#0F4A2A` onSuccessContainer | `#DCF0E3` successContainer | **8.66:1** | AAA |
| `#0F4A2A` onSuccessContainer | `#E3EEE9` success@12% tint（徽章） | **8.69:1** | AAA |
| `#6B5B4E` sandbox | `#EFE2D9` 徽章底 | **5.12:1** | AA |
| `#8A5A12` warning | `#F4E9E2` 抽屉底（就绪文字，v3.1） | **4.96:1** | AA |
| `#B4322A` danger | `#F4E9E2` 抽屉底（就绪文字/红点，v3.1） | **5.13:1** | AA（红点为非文本，≥3:1 即可） |
| `#0E5D6B` primary | `#D8D8D4` primary@12% over 抽屉底（选中项文字，v3.1） | **5.25:1** | AA |
| `#F8EFE9` inverseOnSurface | `#2E2620` inverseSurface（Snackbar） | **13.09:1** | AAA |
| 日志四级（调试/信息/警告/错误） | `#FFFFFF` 白卡 | **5.03–7.51:1** | AA（最坏为调试 5.03:1） |

**Dark（配套套）**

| 前景 | 背景 | 比值 | 结论 |
|---|---|---|---|
| `#F2EAE3` 主文字 | `#17110E` bg | **15.72:1** | AAA |
| `#F2EAE3` 主文字 | `#2A211C` 卡片 | **13.25:1** | AAA |
| `#BBA99B` 次文字 | `#17110E` bg | **8.25:1** | AAA |
| `#BBA99B` 次文字 | `#2A211C` 卡片 | **6.95:1** | AA |
| `#A89A89` 三级文字 | `#17110E` bg | **6.81:1** | AA |
| `#A89A89` 三级文字 | `#2A211C` 卡片 | **5.74:1** | AA |
| `#A89A89` 三级文字 | `#372C25` 内嵌块 | **4.94:1** | AA |
| `#7FC8CE` primary | `#17110E` bg | **9.84:1** | AAA |
| `#7FC8CE` primary | `#2A211C` 卡片 | **8.30:1** | AAA |
| `#05292E` onPrimary | `#7FC8CE` primary（按钮） | **8.11:1** | AAA |
| `#B9E6EA` onPrimaryContainer | `#14494F` primaryContainer | **7.43:1** | AAA |
| `#7FC8CE` primary | `#14494F` primaryContainer（选中文字） | **5.28:1** | AA |
| `#7FC8CE` primary | `#343531` primary@12% tint（chip） | **6.50:1** | AA |
| `#9C8672` outlineStrong | `#2A211C` 卡片 | **4.55:1** | 交互边界 ≥3:1 |
| `#9C8672` outlineStrong | `#1F1815` Dialog 底 | **5.06:1** | 交互边界 ≥3:1 |
| `#3E332C` outline | `#2A211C` 卡片 | **1.29:1** | 装饰线，仅分隔 |
| `#FF8D82` danger | `#17110E` bg | **8.36:1** | AAA |
| `#FF8D82` danger | `#2A211C` 卡片 | **7.04:1** | AA |
| `#FFFFFF` onDangerFill | `#C2483A` dangerFill（允许按钮） | **4.90:1** | AA |
| `#C2483A` dangerFill | `#1F1815` Dialog 底（按钮形体可辨） | **3.57:1** | ≥3:1 |
| `#FFC9C0` onDangerContainer | `#3D1A14` dangerContainer | **10.59:1** | AAA |
| `#FF8D82` danger | `#442E28` danger@12% tint（徽章） | **5.63:1** | AA |
| `#F2B950` warning | `#17110E` bg | **10.54:1** | AAA |
| `#F2B950` warning | `#2A211C` 卡片 | **8.88:1** | AAA |
| `#F5D9A0` onWarningContainer | `#3A2A12` warningContainer | **10.08:1** | AAA |
| `#F2B950` warning | `#423322` warning@12% tint（徽章） | **6.85:1** | AA |
| `#62D996` success | `#17110E` bg | **10.58:1** | AAA |
| `#62D996` success | `#2A211C` 卡片 | **8.92:1** | AAA |
| `#BFF0D4` onSuccessContainer | `#123828` successContainer | **10.25:1** | AAA |
| `#62D996` success | `#31372B` success@12% tint（徽章） | **6.94:1** | AA |
| `#A8988A` sandbox | `#372C25` 徽章底 | **4.86:1** | AA |
| `#17110E` inverseOnSurface | `#F2EAE3` inverseSurface（Snackbar） | **15.72:1** | AAA |
| `#62D996` success | `#241C18` 抽屉底（就绪文字，v3.1） | **9.48:1** | AAA |
| `#F2B950` warning | `#241C18` 抽屉底（就绪文字，v3.1） | **9.44:1** | AAA |
| `#FF8D82` danger | `#241C18` 抽屉底（就绪文字/红点，v3.1） | **7.48:1** | AAA（红点为非文本，≥3:1 即可） |
| `#7FC8CE` primary | `#2F312E` primary@12% over 抽屉底（选中项文字，v3.1） | **6.91:1** | AA |
| 日志四级（调试/信息/警告/错误） | `#2A211C` 卡片 | **5.22–8.88:1** | AA（最坏为调试 5.22:1） |

> **注 1（dangerFill 双形态）**：审批「允许执行」按钮的红色填充在明暗两套用不同 token——Light 直接复用 `--ak-danger` `#B4322A`；Dark 若复用亮珊瑚 `#FF8D82` 做填充会变成"霓虹块"，与暖纸气质冲突，故 Dark 专用 `--ak-danger-fill` `#C2483A`（白字 4.90:1、按钮对 Dialog 底 3.57:1，双达标）。danger 的**文字/图标/点**仍用亮珊瑚 `#FF8D82`（暗底上要亮才读得清）。
> **注 2（tint 底色）**：表内 `语义色@12% tint` 为语义色以 12% alpha 叠在白卡/暗卡上的实测混色值（Light 混色算法：`primary@12% over #FFFFFF = #E2ECED`；Dark：`over #2A211C = #343531`）。落地时用 `Color.copy(alpha = 0.12f)` 由 Compose 合成，无需手写混色 hex。
> **注 3（v3.1 抽屉场景）**：抽屉底 = `surface-1`（Light `#F4E9E2` / Dark `#241C18`）；抽屉选中项 tint = `primary@12% over surface-1`（Light 混色 `#D8D8D4`，Dark 混色 `#2F312E`）。就绪文字三态对比度 Light 4.96–5.13:1 / Dark 7.48–9.48:1，选中项文字 Light 5.25:1 / Dark 6.91:1，均达标。

### 3.5 徽章 / 淡 tint 文字规则（继承 v2.1 R12，重新实测）

「语义色@12–14% 淡 tint 底 + 小字（11–12sp）」组合中：

- **Light 套**：淡 tint 会把语义色文字对比度压到 4.5:1 以下（v2.1 实测 3.92–4.41:1 全族不达标），文字**一律换对应 `on-*-container` 深色墨迹**（v3 重新实测全部 ≥8.29:1）。
- **Dark 套**：文字用语义色亮文（v3 重新实测全部 ≥5.63:1）。
- 此规则覆盖：信任徽章（§6.6）、日志筛选 chip（§6.7）、测试连接结果态（§6.8）、抽屉就绪文字（§6.5）。tint 底与视觉 pattern 不变，只换文字色。

### 3.6 信任等级色板（Skills 专用）

| 等级 | Light 徽章底 / 文字 | Dark 徽章底 / 文字 | 语义 |
|---|---|---|---|
| packaged 内置 | `#DCF0E3` / `#0F4A2A` | `#123828` / `#BFF0D4` | 官方随包分发，默认可信 |
| imported 导入 | `#F7E8D2` / `#5C3A05` | `#3A2A12` / `#F5D9A0` | 外部来源，需警惕但可用 |
| sandbox 沙箱 | `#EFE2D9` / `#6B5B4E` | `#372C25` / `#A8988A` | 隔离运行，中性观察 |

### 3.7 日志级别色板

| 级别 | 中文标签 | Light | Dark |
|---|---|---|---|
| DEBUG | 调试 | `#756450` | `#A89A89` |
| INFO | 信息 | `#0E5D6B` | `#7FC8CE` |
| WARN | 警告 | `#8A5A12` | `#F2B950` |
| ERROR | 错误 | `#B4322A` | `#FF8D82` |

> 级别色只用于**文字与 8dp 点**（点 decorative，文字承载双编码，§6.7）。WARN/ERROR 另有左缘 3dp 色条（§6.7）。

### 3.8 CSS 变量全集（与设计稿/Web 对齐用）

```css
:root { /* Light（主套） */
  --ak-bg:#F8EFE9; --ak-surface:#FFFFFF; --ak-surface-1:#F4E9E2; --ak-surface-2:#EFE2D9;
  --ak-surface-var:#F6EDE7;
  --ak-on-surface:#2E2620; --ak-on-surface-var:#6B5B4E; --ak-on-surface-ter:#756450;
  --ak-outline:#E5D6CC; --ak-outline-strong:#8A7563;
  --ak-primary:#0E5D6B; --ak-on-primary:#FFFFFF; --ak-primary-container:#D2E7E9; --ak-on-primary-container:#093F49;
  --ak-danger:#B4322A; --ak-on-danger:#FFFFFF; --ak-danger-container:#F9E2DF; --ak-on-danger-container:#7E1F16;
  --ak-warning:#8A5A12; --ak-warning-container:#F7E8D2; --ak-on-warning-container:#5C3A05;
  --ak-success:#177245; --ak-success-container:#DCF0E3; --ak-on-success-container:#0F4A2A;
  --ak-trust-sandbox:#6B5B4E; --ak-log-debug:#756450; --ak-log-info:#0E5D6B;
  --ak-inverse-surface:#2E2620; --ak-inverse-on-surface:#F8EFE9; --ak-scrim:rgba(26,18,13,.40);
}
[data-theme="dark"] { /* Dark（配套套） */
  --ak-bg:#17110E; --ak-surface:#1F1815; --ak-surface-1:#241C18; --ak-surface-2:#2A211C;
  --ak-surface-var:#372C25;
  --ak-on-surface:#F2EAE3; --ak-on-surface-var:#BBA99B; --ak-on-surface-ter:#A89A89;
  --ak-outline:#3E332C; --ak-outline-strong:#9C8672;
  --ak-primary:#7FC8CE; --ak-on-primary:#05292E; --ak-primary-container:#14494F; --ak-on-primary-container:#B9E6EA;
  --ak-danger:#FF8D82; --ak-danger-fill:#C2483A; --ak-on-danger-fill:#FFFFFF;
  --ak-danger-container:#3D1A14; --ak-on-danger-container:#FFC9C0;
  --ak-warning:#F2B950; --ak-warning-container:#3A2A12; --ak-on-warning-container:#F5D9A0;
  --ak-success:#62D996; --ak-success-container:#123828; --ak-on-success-container:#BFF0D4;
  --ak-trust-sandbox:#A8988A; --ak-log-debug:#A89A89; --ak-log-info:#7FC8CE;
  --ak-inverse-surface:#F2EAE3; --ak-inverse-on-surface:#17110E; --ak-scrim:rgba(0,0,0,.56);
}
```

---

## 4. 字体与排版

### 4.1 字体组合

| 角色 | 字体 | 字重 | 理由 |
|---|---|---|---|
| 全部人类语言（标题/正文/按钮/菜单/徽章） | **Noto Sans SC** | 400 / 500 / 600 | 屏显清晰、字面率高；拉丁字形与 Roboto 同源，与系统 UI 并置不跳字；开源可子集化。不引入衬线体 |
| 机器文本（日志/时间戳/工具名/URL/Key/入参/STEP/时长/指纹） | **JetBrains Mono** | 400 / 500 | 0/O、1/l/I 区分度高；等宽特性解决数字列对齐。**注意：这是工具用途，不再是"语汇"**——v3 没有任何英文微标签，mono 只出现在"机器自己说出来的字符串"里 |
| 回退 | `FontFamily.SansSerif` / `FontFamily.Monospace` | — | 无 Play 服务设备自动回退 |

落地：`androidx.compose.ui:ui-text-google-fonts` + `GoogleFont("Noto Sans SC")` / `GoogleFont("JetBrains Mono")`，下载字体优先（省包体），失败回退系统族。

### 4.2 Type Scale（Compose Typography 直接映射，比 v2 少 9 级）

| M3 slot | 字号/行高 (sp) | 字重 | 用途 |
|---|---|---|---|
| headlineSmall | **22 / 30** | 600 | 内容区内的大号强调（**非页面标题**——页面标题 v3.1 起在顶栏，§6.5.1）；默认不使用，仅内容区需要 22sp 级强调的个别场景 |
| titleLarge | 18 / 26 | 600 | 弹窗标题、分组卡标题 |
| titleMedium | 16 / 24 | 500 | 列表项主文案、技能名、气泡标题 |
| bodyLarge | **15 / 26** | 400 | **默认正文**：聊天气泡、列表描述、说明文字 |
| bodySmall | 13 / 20 | 400 | 辅助说明、来源行、二级描述 |
| labelLarge | 14 / 20 | 600 | 按钮文字 |
| labelMedium | 12 / 16 | 500 | 页面内 Tab、筛选 chip、状态文字（执行中/已完成） |
| labelSmall | 11 / 14 | 500 | 徽章、角标、非等宽小字 |

自定义样式（2 个，均为 mono 工具用途）：

| 名称 | 值 | 用途 |
|---|---|---|
| `machineText` | JetBrains Mono 13sp / 20，w400 | 工具名、URL、模型名、Key、入参/结果块 |
| `machineSmall` | JetBrains Mono 12sp / 16，w400 | 时间戳、STEP n/m、时长、指纹 |

> 已废除：v2 的 `microLabel`（英文全大写微标签）、`machineStrong`、display 三档、headlineLarge/Medium、titleSmall。**页面标题 v3.1 起统一为顶栏居中 titleLarge 18/26 w600（§6.5.1），不再使用内容区 headlineSmall 大标题**；空态主标题也用 titleLarge，不再有 display 级大字。

### 4.3 中文排版专项

1. **行高绝对值策略**：中文正文行高 ≥1.7（bodyLarge 15/26 = 1.73）；标签类紧行高（labelSmall 11/14）。一律绝对值 sp 不用倍数，避免 ROM 字体度量差异。`includeFontPadding = false`（显式声明防回归）。
2. **fontScale 例外条款（继承 v2.1 R8）**：fontScale ≤1.3 用绝对值；**>1.3 时行高系数 ×1.4**（如 machineSmall 16→22sp、bodyLarge 26→36sp）；容器一律 `heightIn(min=…)` 不用固定 `height`。
3. **数字等宽化**：机器语境数字（时间戳、STEP n/m、时长、指纹、Key、计数）走 mono；人类叙述中的数字跟随正文（「3 条记忆」用正文数字）。**禁止**用 Noto Sans SC 的比例数字做时间戳列。
4. **中英混排**：不手动插空格（手插 thin space 换行会断）；全 App 只用 Noto Sans SC 一套拉丁字形来源。界面文案以中文为主，英文只出现在"机器输出的字符串"里（工具名、URL、Key、模型名）——那是内容，不是装饰。
5. **标点**：标题文案避免行尾标点；正文靠 1.7 行高消化全角标点。Compose 不支持 hanging punctuation，不强行模拟。
6. **废除**：v2 §3.3-5「拼音小字不采纳」及其双行标签结构——前提已勘误（§1.1），且双行结构本身随英文微标签一起废除。

---

## 5. 间距 / 圆角 / 高度体系

### 5.1 间距 token（RikkaHub 式大留白，比 v2 更宽松）

| Token | 值 | 典型场景 |
|---|---|---|
| `space-1` | 4dp | 徽章内距、点与文字 |
| `space-2` | 8dp | 图标与文字、同发送者消息间距、chip 间距 |
| `space-3` | 12dp | 卡片内块间距、按钮行 gap、卡片之间 |
| `space-4` | 16dp | 列表项左右内边距、分隔线缩进、气泡内上下距 |
| `space-5` | **20dp** | **卡片内边距、页面左右边距（默认）** |
| `space-6` | 24dp | Dialog 内边距、分区之间 |
| `space-8` | 32dp | 空态上下留白 |
| `space-10` | 40dp | 空态图标区留白 |
| `space-12` | 48dp | 触控目标基准 |

- 页面边距 **20dp 固定**（不做 clamp——少一个变量少一处不一致；Expanded >840dp 内容区限宽 680dp 居中）。
- 消息流气泡最大宽：用户 `clamp(240dp, 76%容器宽, 400dp)`，AI `clamp(272dp, 84%容器宽, 520dp)`；**百分比基准 = 消息列容器宽**（`BoxWithConstraints`，继承 v2.1 R9），dp 上下限语义不变。
- 同发送者相邻消息 8dp，切换发送者 16dp。

### 5.2 圆角体系（4 档，v2 的 6 档废止）

| Token | 值 | 用于 |
|---|---|---|
| `radius-s` | 8dp | checkbox、小代码角 |
| `radius-m` | 14dp | 按钮、输入框、内嵌块（入参/结果/chip 底） |
| `radius-l` | **20dp** | **卡片、列表项、Dialog、底栏** |
| `radius-full` | 999dp | 徽章、筛选 chip、开关、圆形图标按钮 |

M3 `Shapes` 映射：`extraSmall=8dp / small=14dp / medium=14dp / large=20dp / extraLarge=20dp`。
聊天气泡尾角：靠头像一侧的对角收窄为 **4dp**（用户=右上、AI=左上）。

### 5.3 阴影 / 高度层级：**全 App 0 阴影**

| 层级 | 表达方式 |
|---|---|
| 页面背景 | `--ak-bg` 暖粉 |
| 卡片 / 气泡 / 列表项 / Dialog | `--ak-surface` 白 + **0 阴影 + 0 描边**，靠底色差分层 |
| 内嵌块（入参/结果/chip/输入框） | `--ak-surface-1` / `--ak-surface-var` 暖灰 |
| 按压态 | `--ak-surface-2`（不用 elevation 变化） |
| Dialog 浮起 | scrim（`#1A120D@40%` / `#000@56%`）+ 0 阴影 |
| 顶栏 | 透明无底色，overlay 于内容之上；内容区预留顶栏等高 padding，滚动不叠实色（§6.5.1） |
| 输入栏（贴底） | `--ak-surface` @ 92% 玻璃拟态（全 App 唯一一处）+ 顶部 1dp `outline` 装饰线（§6.10） |

> 这是对 v2「卡片 1dp 描边 + Dialog 8dp 阴影」的整体废止，也是对 ui-design-system「禁止廉价重阴影」的加码执行：暖粉底 + 白卡 + 暖灰内嵌的三层明度差已经足够分层，任何阴影都是噪音。

### 5.4 触控目标

- 所有可点元素 ≥ **48dp × 48dp**（严于 44dp 基准）。
- 列表项：两行结构 ≥72dp，单行 ≥56dp；审批弹窗按钮高 48dp。
- **抽屉菜单项 52dp**（v3.1）：≥48dp 触控基准达标；上文「单行列表项 ≥56dp」不适用于导航抽屉——抽屉项无描述行、无右侧控件，52dp 已容纳 16sp/24 文字的舒适行高。
- 顶栏：高 **56dp** + `statusBarsPadding`（overlay 形态，内容区预留等高 padding，§6.5.1）；输入栏贴屏幕底 + `navigationBarsPadding`（§6.10）。
- 自定义 toggle：视觉 46×28dp + 触控容器 48×48dp；checkbox：视觉 40dp + 触控容器 48×48dp（继承 v2.1 R6）。
- 顶栏圆形按钮：视觉 40dp + 触控 48×48dp。

---

## 6. 组件规范（10 个关键组件 + 基础控件）

> 每个组件给出：结构 / 尺寸 / 颜色 / 状态。颜色引用 token 名（对应 §3.8）。**所有卡片类组件一律 0 阴影 0 描边**，不再逐条重复。

### 6.0 基础控件（按钮 / 输入 / 勾选 / 开关）

**按钮（三形态，高 48dp，radius 14dp）**

| 形态 | 默认 | 按压 | 禁用 |
|---|---|---|---|
| Filled 主按钮 | bg `primary`，文字 `on-primary` 14sp w600，padding h24 | scale 0.97（100ms，全局按压语言） | 整体 38% alpha |
| Outlined 次按钮 | 1.5dp `outline-strong` 描边，文字 `on-surface` w600，透明底 | bg `surface-2` | 描边与文字 38% alpha |
| Text 文字按钮 | 无底无描边，文字 `primary`（破坏性用 `danger`） | bg `surface-1` | 38% alpha |
| Danger Filled（仅审批「允许执行」） | bg `danger`（Dark=`danger-fill`），文字 `on-danger` w600 | scale 0.97 | 38% alpha |

- 按钮行：Row + gap 12dp；「取消/拒绝」与「确认/允许」**等高**（不制造视觉偏袒，继承 v2 决策）。
- Loading（测试连接）：左侧 18dp CircularProgressIndicator（2dp stroke，`on-primary`）+ 文字「测试中…」。

**输入框（填充式，RikkaHub 语言）**

- 结构：label 在上（13sp `on-surface-var`），field 在下：bg `surface-var`，radius 14dp，高 56dp，内边距 h16，**默认无描边**。
- 聚焦：1.5dp `primary` 描边 + bg 升为 `surface`。
- 错误：1.5dp `danger` 描边 + 下方 12sp `danger` 辅助文字。
- 机器内容（Base URL / 模型名 / Key）用 `machineText` 13sp；Key  masked（`••••`）+ 尾部眼睛 IconButton（视觉 40dp / 触控 48dp）。

**Checkbox**：视觉 40dp 方框 radius 8dp；未选 = 1.5dp `outline-strong` 描边；选中 = `primary` 底 + 20dp `on-primary` 对勾；触控容器 48×48dp；状态切换 150ms 颜色过渡。

**Switch**：track 46×28dp radius full；thumb 选中 22dp `on-primary` / 未选 16dp `outline-strong`；选中 track `primary` / 未选 `surface-2`；触控 48×48dp；thumb 位移与颜色 150ms。

### 6.1 列表项（RikkaHub 两行结构，全 App 列表基件）

| 维度 | 规格 |
|---|---|
| 容器 | 白色分组卡（`surface`，radius 20dp）内纵向排列；项与项之间 1dp `outline` 分隔线，**左侧缩进 16dp**（自图标后起） |
| 结构 | 左：40dp 圆角图标底座（radius 12dp，`surface-1` 底 + 22dp `on-surface-var` 线图标）→ 中：主文案 16sp/24 w500 `on-surface`（1 行）+ 描述 13sp/20 `on-surface-var`（1 行，ellipsis）→ 右：控件（Switch/Checkbox）或 20dp `>`  Chevron（`on-surface-ter`） |
| 高度 | 两行结构 ≥72dp；单行 ≥56dp；左右内边距 16dp |
| 按压 | bg `surface-2` + scale 0.97（100ms） |
| 选中态（导航场景） | 图标底座升 `primary-container`、图标与主文案 `primary` |
| 服务任务 | 设置/技能/权限/记忆等所有"浏览 + 单项操作"场景——一屏几物，靠留白分组，不靠分隔线堆叠 |

### 6.2 聊天气泡（用户 / AI / 系统提示）

| 形态 | 规格 |
|---|---|
| 用户气泡 | 右对齐：28dp 圆头像（`surface-1` 底 + 20dp `on-surface-var` 人形图标）+ 气泡；气泡 bg `primary`，文字 `on-primary` 15sp/26；radius 18dp，右上 4dp 尾角；padding h14 v10；maxW `clamp(240,76%容器,400)` |
| AI 气泡 | 左对齐：28dp 圆头像（`primary-container` 底 + 20dp `on-primary-container` 星点图标）+ 气泡；气泡 bg `surface` 白，文字 `on-surface`；radius 18dp，左上 4dp 尾角；padding h14 v10；maxW `clamp(272,84%容器,520)` |
| 失败态 | AI 气泡加 1dp `danger`@50% 描边；气泡下方「重试」TextButton（`primary`）+「查看日志」TextButton（`on-surface-var`）；文案三段式：结论 → 定位 → 下一步（UX v1 §6.3） |
| 流式中 | 尾部 2×14sp `primary` 方块闪烁（800ms）——**全 App 唯一循环动画**，承载"正在生成"状态信息 |
| 系统提示 | 居中单行纯文字 12sp `on-surface-ter`（无气泡无描边），maxW 80% 容器；如「正在规划操作…」 |
| 分组时间戳 | 居中 `machineSmall` 12sp `on-surface-ter` |
| 通用 | 同发送者间距 8dp、切换 16dp；新消息入场 **fade 150ms**（无位移，v2 的 slide-in 废止） |

### 6.3 工具调用卡（轻量：一张普通卡片）

| 维度 | 规格 |
|---|---|
| 容器 | 消息列内白卡（`surface`，radius 20dp），左右边距 16dp，padding 16dp；**无状态描边、无色带、无扫描光带**（v2 的仪器三件套全废止） |
| Header（高 48dp） | `[状态点 8dp] [工具名 machineText 13sp on-surface w500] — [状态文字 12sp] [chevron 20dp]` |
| 状态文字（双编码） | 执行中=`primary` / 已完成=`success` / 已失败=`danger` / **已拒绝=`on-surface-var`（中性，不用红——拒绝是用户决策不是故障，继承 v2.1 R3）**；状态点同色但 decorative |
| 第二行 | `第 3 步，共 7 步` + `214ms`（`machineSmall` 12sp `on-surface-ter`，数字等宽对齐）；单步任务只显时长 |
| 展开区 | `animateContentSize 250ms`（§7-M2）；「入参」「结果」两个内嵌块：`surface-1` 底，radius 14dp，padding 12dp，`machineText` 13sp/20，maxHeight 140dp 可滚动；ERROR 时结果块文字 `danger` |
| 服务任务 | 执行中能数步（STEP n/m）、完成后能回看（入参/结果常驻可展开）、拒绝能溯源——三件事一张轻卡说完，不加描边不加光 |

### 6.4 危险操作审批 Dialog（招牌组件：简洁但警觉）

**设计目标：让人警觉，但不恐慌。** 靠三件事传达，零仪器语汇：
**色彩**——红只出现在 4 处（图标、将执行左竖条、允许按钮、倒计时线），合计面积 <8% 弹窗面；**排版**——标题 18sp w600 正常字重，文案陈述事实（不加大、不加粗、不变红、不全大写）；**留白**——padding 24dp、块间距 16dp，"重大但从容"。**无扫描线、无遥测读数、无脉冲呼吸、无全屏红底。**

| 维度 | 规格 |
|---|---|
| 容器 | 宽 `min(344dp, 100vw−48dp)`；radius 20dp；bg `surface` 白；padding 24dp；**0 阴影**；scrim `#1A120D@40%`（Dark `#000@56%`）fade 200ms |
| 图标行 | 36dp 圆（`danger-container` 底）+ 20dp `danger` 警示线图标；**静态，不呼吸**（v2 的 1600ms 脉冲废止） |
| 标题 | 「确认执行危险操作」titleLarge 18/26 w600 `on-surface` |
| 副文 | 「Agent 正在请求操控你的手机，执行后不可撤销。」bodyLarge 15/26 `on-surface-var` |
| 「将执行」块 | `surface-1` 底，radius 14dp，padding 14dp，**左缘 3dp `danger` 圆角竖条**；内容 `machineText` 13sp/20（工具名 + 参数换行，包名/路径 w500 强调，**不另用颜色**）；最多 5 行 + maxHeight 140dp 滚动 |
| 来源行 | 「来源：技能「设置导航」· 指令「打开设置并点击 WLAN」」bodySmall 13/20 `on-surface-ter`，maxLines 2；无来源时显示「来源：本次对话直接调用」，不留空 |
| 免询行 | 「本次会话不再询问同类操作」15sp `on-surface-var` + checkbox（视觉 40dp / 触控 48dp） |
| 按钮行 | gap 12dp，高 48dp，radius 14dp，**两者 flex 1 等高**：「拒绝」Outlined（1.5dp `outline-strong` 描边，文字 `on-surface` w600）；「允许执行」Danger Filled（bg `danger`/Dark `danger-fill`，文字 `on-danger` w600） |
| 倒计时 | 按钮行下方：居中「10 秒后自动拒绝」11sp `on-surface-ter` + 2dp `danger` 进度线（内容宽，100%→0，10s linear）；**功能性进度，非装饰动效**，reduce-motion 下保留（继承 v2.1 R5 默认开启） |
| 动效 | 入场 fade + scale 0.96→1，200ms standard；退场 fade 150ms。**无弹跳、无脉冲**（§7-M1 克制版） |
| TalkBack | 模态聚焦；标题→副文→将执行块→来源行顺序聚焦；倒计时剩余 3s 时 polite 播报一次（UX v1 §3.1） |

### 6.5 导航：抽屉为唯一 App 级导航 + 透明顶栏（v3.1 重写）

**结构决策：无底部 Tab，抽屉是唯一 App 级导航。** 理由三条：① 目的地 9 个（对话/模型/控制台/权限中心/技能中心/运行日志/记忆库/设置/关于），任何 Tab 方案都装不下，硬装只会变成「3 Tab + 更多」的双层导航；② 「可追踪」DNA 要求出错时从任何页面一步进日志/权限——抽屉是一级入口，overflow 菜单是隐藏入口；③ 参照对象 RikkaHub 即「左上圆钮 + 侧抽屉」，无 App 级底部 Tab；常驻 Tab 占 64dp + 手势区空间，不如抽屉按需展开简洁（§1.4）。**页面内 Tab 保留**（模型页「模型/提示词」，§6.5.3）：它是内容分区不是 App 导航，RikkaHub 自己也有页面内 Tab——两者层级不同，不构成双导航。

#### 6.5.1 顶栏（透明，每页同一骨架）

| 维度 | 规格 |
|---|---|
| 高度 | **56dp**（内容高）+ `statusBarsPadding`；顶栏 overlay 于内容区之上，**内容区预留「状态栏 + 56dp」顶部 padding**——滚动内容永不穿过标题，零监听零状态 |
| 底色 | **常驻透明**（与页面同色 `--ak-bg`），无底色、无分割线；**滚动时不变实色**。理由：暖纸分层靠三层底色（§5.3），实色/毛玻璃顶栏会引入「悬浮感」，与 0 阴影语言冲突；内容区从顶栏下方起排，标题与内容永不打架 |
| 左 | 圆形汉堡按钮：触控 48×48dp（radius-full 圆形点击区），视觉 40dp；菜单线图标 24dp（1.5dp stroke）`on-surface`；按压 bg `surface-2` + scale 0.97（100ms）；TalkBack「打开导航抽屉」 |
| 中 | 页面标题：titleLarge 18/26 w600 `on-surface`，居中，单行 ellipsis；一律用页面中文名 |
| 右 | 页面级动作按钮 0–1 个（规格同汉堡：48dp 触控 / 40dp 视觉 / 24dp 线性图标）；逐页清单见 §6.5.4 |
| 二级页 | 左 = 圆形返回按钮（`←` 24dp，规格同上）；中 = 标题；右 = 按需 0–1 个动作 |

> 一级页 = 抽屉 9 个入口对应的页面；其余页面为二级页（返回按钮替代汉堡）。
> 图标尺寸：v3.0 顶栏图标为 22dp，v3.1 统一 **24dp**——「汉堡 ↔ 抽屉项」是同一菜单语汇，应同尺。

#### 6.5.2 抽屉（窄屏 overlay / 宽屏常驻，继承 UX v1 §4.2 断点）

| 维度 | 规格 |
|---|---|
| 宽度 | `clamp(280dp, 82vw, 320dp)`（沿用 v3.0）：280dp 保触控与可读下限，320dp 保「抽屉感」上限（不变第二内容区），82vw 中间档照顾小屏 |
| 容器 | bg `surface-1`（Light `#F4E9E2` / Dark `#241C18`）；右缘 1dp `outline` 装饰线；顶部 `statusBarsPadding`、底部 `navigationBarsPadding` |
| 滑入 | 左侧 `slideInHorizontally` **250ms = `motionBase`**（standard 曲线，无 spring）+ fade；**复用现有 token，不新增第 4 个动效**（§7.2 注） |
| 滑出 | `slideOutHorizontally` 200ms = `motionDialog` + fade；返回键同样关闭（`BackHandler`） |
| scrim | `#1A120D@40%`（Dark `#000@56%`），fade 200ms = `motionDialog`；**点击 scrim 关闭**；背后页面内容不位移（省算力，与 RikkaHub 一致） |
| 手势 | 一级页左缘 **24dp 热区**内右滑开抽屉：支持跟手拖拽（抽屉位移与 scrim 透明度随手势进度），松手按「超过抽屉宽 1/3」阈值吸附（吸附过渡 150ms = `motionFast`）——缓解汉堡居左、右手单手难够到（§6.5.5） |
| 高度预算 | 头部 ≈120dp + 2×分组标题 30dp + 9 项 ×52dp + 间距 ≈ **716dp** + 状态栏 inset；主流机型（如 412×915dp，可用 891dp）一屏放得下，余量 >170dp；内容高 <740dp 的老旧小屏允许抽屉内部滚动，**不加折叠、不加「查看更多」** |

**结构（两分组单行中文 + 底部位；入口比 v3.0 多 1 个：设置）**

```
┌────────────────────────────┐
│ [标记] AgentKit              │  ← 18sp w600
│ ● 无障碍已连接 · 3 项待授权   │  ← 就绪文字 12sp（三态见下）
├────────────────────────────┤
│ 主要                         │  ← 分组标题 13sp w500
│ [图标] 对话                  │
│ [图标] 模型                  │
│ [图标] 控制台                │
├────────────────────────────┤
│ 管理                         │
│ [图标] 权限中心           ●  │  ← 红点（待办）
│ [图标] 技能中心              │
│ [图标] 运行日志            3  │  ← 计数（存量）
│ [图标] 记忆库                │
├────────────────────────────┤
│ [图标] 设置                  │  ← 底部位，无分组标题
│ [图标] 关于                  │
└────────────────────────────┘
```

（示意仅表结构；图标全部为 24dp 线性矢量、1.5dp stroke，禁 emoji。）

| 维度 | 规格 |
|---|---|
| 头部 | padding 20dp：40dp 圆角应用标记（`radius-m` 14dp，`primary-container` 底 + 24dp `primary` 盾牌线图标）+「AgentKit」titleLarge 18/26 w600 + 就绪文字行 |
| 就绪文字 | 一行 12sp/16：6dp 状态点 + 文字（状态词着色，其余 `on-surface-var`），**三态**：① `success` 点 +「无障碍已连接」（0 待授权）；② `warning` 点 +「无障碍已连接 · N 项待授权」（N 用正文数字，§4.3-3，与权限中心红点同源）；③ `danger` 点 +「无障碍未连接」，整行可点跳权限中心。对比度 Light 4.96–5.13:1 / Dark 7.48–9.48:1（§3.4 注 3） |
| 分组 | **2 组**：`主要`（对话/模型/控制台——天天用）、`管理`（权限中心/技能中心/运行日志/记忆库——出事时用）；分组标题 = 单行中文 13sp w500 `on-surface-var`，无英文微标签、无双行。`设置`/`关于` 为**底部位**（装一次看一次，语义自知，不配分组标题），与上方间距 20dp，不加分隔线 |
| 菜单项 | 高 **52dp**，radius-m 14dp，项间距 4dp，左右边距 8dp；24dp 线图标（`on-surface-var`）+ 中文名单行 16sp/24 w500 `on-surface`（与 §6.1 列表项主文案对齐——抽屉项本质是单行列表项）；**右缘不设 chevron**（9×20dp 箭头是噪音；整行可点是常识）；整行为触控目标 |
| 选中态 | bg `primary`@12% + 图标与文字 `primary` + **左缘 3dp `primary` 圆角竖条**（贴抽屉左缘，与项等高；v3.1 恢复该元素，理由见 §1.4）。文字对比度 Light 5.25:1 / Dark 6.91:1（§3.4 注 3） |
| 角标 | 全 App 仅 **2 处**，规则 = **红点：待办（静态，不脉冲）/ 计数：存量 / 零不显示**：① 权限中心 → 8dp `danger` 静态红点，条件 = 有待授权审批（N≥1），处理完消失；② 运行日志 → 12sp w500 `on-surface-var` 纯数字（无底无胶囊），条件 = 未读 ERROR 条数 >0，读后归零即隐藏。技能/记忆/模型**不设角标**（v3.0 计数胶囊废止不复活）。红点/计数语义并入父项 TalkBack（「权限中心，3 项待授权」） |
| 交互 | 点当前所在页项 = 仅关闭抽屉（不重复导航）；跳转后抽屉 200ms 滑出，**页面切换无转场动画**（v3 无此 token）；宽屏 ≥840dp 时抽屉常驻为侧栏（280dp，无 scrim 无滑入），内容区宽 = min(屏宽 − 280dp, 680dp) 居中 |

**入口清单（9 项完整覆盖）**

| # | 区位 | 入口 | 图标语义（24dp 线性） | 角标与条件 |
|---|---|---|---|---|
| 1 | 主要 | 对话 | 对话气泡 | 无 |
| 2 | 主要 | 模型 | 立方体 / 芯片 | 无 |
| 3 | 主要 | 控制台 | 仪表盘 / 四方格 | 无 |
| 4 | 管理 | 权限中心 | 盾牌 | 红点 = 有待授权审批（N≥1） |
| 5 | 管理 | 技能中心 | 拼图 / 插件 | 无 |
| 6 | 管理 | 运行日志 | 文档 / 列表 | 计数 = 未读 ERROR 条数（>0 才显示） |
| 7 | 管理 | 记忆库 | 数据库 / 分层 | 无 |
| 8 | 底部 | 设置 | 齿轮 | 无 |
| 9 | 底部 | 关于 | 信息圆 | 无 |

> 「控制台」= 自动化仪表盘（v2.1 R10 命名保留）；开源许可并入关于页（v2 独立项废止不变）。v3.0 的「对话未读红点」随底部 Tab 一并废止——无 Tab 即无常驻载体，会话未读改由系统通知承担，抽屉内不再放红点（防红点通胀）。

#### 6.5.3 页面内 Tab（全 App 仅模型页 1 处）

| 维度 | 规格 |
|---|---|
| 位置 | 顶栏标题下方、内容区顶部；单行两项「模型 / 提示词」 |
| 样式 | 文字 labelLarge 14/20；未选 = `on-surface-var` w500；选中 = `primary` w600 + 底部 2dp `primary` 下划线（宽随文字）；**无药丸、无背景、无 spring**（v3.0 Tab 药丸废止不变） |
| 切换 | 仅点选：变色 + 下划线位移 150ms = `motionFast`；**不支持滑动切换**——避免与抽屉右滑手势争抢，且少一个 ViewPager 依赖 |
| 语义 | 内容分区，非 App 导航；未来新增必须满足「同一页面的两类平级内容」 |

#### 6.5.4 各页面顶栏差异（左 = 汉堡恒定；中 = 标题；右 = 动作）

| 页面 | 标题 | 右上动作 | 说明 |
|---|---|---|---|
| 对话 | 对话 | 新对话（`+`） | 当前会话已为空时禁用（38% alpha）；TalkBack「新对话」 |
| 模型配置 | 模型配置 | 无 | 保存/取消在内容区底部 Row（§6.8）——保存有毁损性，不进顶栏防误触 |
| 控制台 | 控制台 | 刷新（圆形箭头） | 拉取最新自动化状态 |
| 权限中心 | 权限中心 | 无 | 允许/拒绝操作在列表项内 |
| 技能中心 | 技能中心 | 导入（`+`） | 走导入来源警示流程（§6.6） |
| 运行日志 | 运行日志 | 导出（下载线图标） | 筛选 chips 在内容区顶部（§6.7） |
| 记忆库 | 记忆库 | 新建记忆（`+`） | 手动添加一条记忆 |
| 设置 | 设置 | 无 | — |
| 关于 | 关于 | 无 | — |
| 二级页 | 页面标题 | 按需 0–1 个 | 左侧为返回按钮（替换汉堡） |

#### 6.5.5 无障碍与单手操作

- **右手单手**：汉堡居左上、右手拇指热区在右——缓解 = 一级页左缘 24dp 热区右滑开抽屉（§6.5.2）+ 返回键关闭；**不新增右下角浮动按钮**（会把「少一个常驻元素」的收益还回去）。
- **TalkBack**：汉堡 `contentDescription = "打开导航抽屉"`；抽屉打开时焦点移入抽屉第一项并 announce「导航抽屉已打开」；关闭时焦点返回汉堡并 announce「导航抽屉已关闭」；当前页菜单项 `selected = true`（读作「对话，已选中」）；红点/计数语义并入父项文本，不单独聚焦；就绪文字未连接态合并播报「无障碍未连接，双击进入权限中心」。

### 6.6 技能卡（信任徽章 + 从简的降权可见性）

| 维度 | 规格 |
|---|---|
| 容器 | 白卡 radius 20dp padding 16dp，元素间距 10dp |
| Header | 40dp 图标底座（radius 12dp，`surface-1` 底 + 22dp `on-surface-var` 图标）+ 名称 16sp/24 w600 + 版本 `machineSmall` 11sp `on-surface-ter` + 右侧 Switch（§6.0） |
| 信任徽章 | 胶囊 radius full，padding h8 v3，11sp w500：`● 内置`（`success-container`/`on-success-container`）、`● 导入`（`warning-container`/`on-warning-container`）、`● 沙箱`（`surface-2`/`on-surface-var`）。**文字色规则见 §3.5**；徽章永不变色——来源不可变（UX v1 F3 反误解设计） |
| 高危摘要行 | 替代 v2 的权限 chips 矩阵（用户明确"不搞矩阵"）：有高危工具被拦截时显示一行 12sp `danger` 文字「已拦截 2 个高危工具」；无则不显示 |
| 描述 | 13sp/20 `on-surface-var`，maxLines 2 |
| 展开明细 | 点卡展开（250ms，§7-M2）：每个工具一行——14dp 图标（放行=`primary` 对勾 / 拦截=`danger` 禁行）+ `machineText` 12sp 工具名 + 「放行」/「拦截」12sp。降权不靠用户"注意到"，靠"藏不住"（UX v1 F3 保留） |
| Footer | 左：「脚本执行：已授权」12sp `success` /「未授权」`warning`；右 TextButton：「授权脚本」（`primary`，仅 imported）/「撤销」/「卸载」（`danger` 文字，卸载走二次确认 Dialog：破坏性按钮用 **danger 描边 Outlined**，不用填充——卸载不如"允许执行"重大） |

### 6.7 运行日志条目（简单：级别 + 时间 + 消息）

| 维度 | 规格 |
|---|---|
| 容器 | 日志页：顶部筛选 chip 行 + 白色分组卡（radius 20dp）内条目列表，条目间 1dp `outline` 分隔线（左缩进 16dp） |
| 条目结构（RikkaHub 两行式） | 第一行：8dp 级别点（decorative）+ 级别中文 12sp w500 级别色（**调试/信息/警告/错误**，双编码文字）+ spacer + 时间戳 `machineSmall` 12sp `on-surface-ter`；第二行：消息 bodyLarge 15sp/24 `on-surface`，maxLines 2 |
| WARN/ERROR | 左缘 3dp 级别色圆角条（让"出问题了"在一屏日志里自己跳出来；文字双编码已承载语义，色条是第三层冗余，成本 3dp） |
| 展开 | chevron + 250ms（§7-M2）；展开区内嵌块 `surface-1`，`machineSmall` 12sp/16，maxHeight 140dp 滚动；ERROR 时文字 `danger` |
| 筛选 chips | 全部/信息/警告/错误（中文标签）；胶囊 radius full 高 32dp；选中 = `primary`@12% 底 + `on-primary-container` 文字（§3.5 规则）；未选 = `surface-1` 底 + `on-surface-var` 文字 |
| 滚入 | 新条目 fade 200ms（v2 的位移 + spring + 色条划入废止） |

### 6.8 模型配置页（Key 指纹自检 + 测试连接，样式从简）

| 维度 | 规格 |
|---|---|
| 页面 | 标题在顶栏（「模型配置」，§6.5.4）；内容区顶部为页面内 Tab「模型/提示词」（§6.5.3），其下为分区卡（白卡 radius 20dp padding 20dp，卡标题 titleLarge 18sp w600，**无英文微标签**） |
| 字段 | Base URL / 模型名：`machineText` 13sp；API Key：masked + 眼睛 IconButton（48dp 触控）；填充式输入框（§6.0） |
| Key 自检行 | 每次输入实时刷新：6dp 点 + 「长度 56 · sk-a…7F3A · 校验通过」12sp `on-surface-var`（指纹部分 `machineSmall`）；异常：`danger` 点 + 「检测到 3 个不可见字符，保存时自动清除」`danger` |
| 测试连接 | 按钮四态（§6.0 Loading）：idle = Filled 主按钮全宽 48dp；testing = 左 18dp 圆环 + 「测试中…」；ok = `success-container` 底 + 对勾 + 「连接正常 · 214ms」；fail = `danger-container` 底 + 「401 · Key 无效」+ 12sp 建议文字（结果态文字色规则 §3.5） |
| 结果卡 | radius 20dp，padding 14dp；字段行（端点/指纹/响应预览）`machineSmall` 12sp/16，行距 4dp；右下「复制」TextButton |
| 保存/取消 | 底部 Row gap 12dp：取消 Outlined（`outline-strong` 描边）flex 1 + 保存 Filled（`primary`）flex 1；disabled 38% alpha |

### 6.9 空状态（5 个，从简：无 hero 卡）

- 统一规格：居中排版，上下 padding 32dp：56dp 圆图标底（`surface-1` + 28dp 语义色线图标）+ 标题 titleLarge 18/26 w600 + 描述 bodySmall 13/20 `on-surface-var`（居中，maxW 280dp）+ 主按钮（Filled，高 48dp，padding h24，自动宽度）。
- **v2 的 radius-xl 28dp hero 卡废止**——空态是"暂时没有内容"，不是"英雄榜"。
- 文案沿用 UX v1 §3.4（空对话带三行起飞前检查清单 + 三条示例建议卡；示例卡 = 白卡 radius 14dp padding 14dp，13sp `on-surface-var`）。

### 6.10 对话输入栏（贴底 · 玻璃拟态唯一处）

| 维度 | 规格 |
|---|---|
| 位置 | **直接贴屏幕底边缘**：容器背景延伸至屏幕底（含手势区），交互元素位于 `navigationBarsPadding` 之上。v3.0 时输入栏在底部 Tab 上方；Tab 废止后下移 64dp，内容区净增 64dp（§1.4） |
| 栏体 | 内容高 **68dp**（输入框 48dp + 上下内边距各 8dp + 底部 4dp）+ `navigationBarsPadding`；bg `--ak-surface` @ **92%** + 顶部 1dp `outline` 装饰线 |
| 输入框 | bg `surface-var`，radius 20dp（radius-l），最小高 48dp，多行自增高（max 120dp 内滚动）；placeholder「说点什么…」15sp `on-surface-ter`；内边距 h16 |
| 发送按钮 | 右侧圆形：触控 48dp / 视觉 40dp；`primary` 底 + 24dp `on-primary` 发送线图标；输入为空时 38% alpha 禁用 |
| 玻璃拟态 | **全 App 唯一一处**：`surface` @ 92% 半透明，消息从栏底穿过时呈「磨砂透光」感。**不做真实背景模糊**——API 26 无低成本方案，且暖纸体系里 blur 显脏。Dialog / 抽屉 / Snackbar / 顶栏全部实色。位置变化后玻璃反而更成立：v3.0 下方是实色 Tab（无可透对象），v3.1 直接贴在滚动内容末端（有真对象可透） |

---

## 7. 动效规范（3 个，其余全废）

### 7.1 时长 token（Compose 直写）

| Token | 值 | 用途 |
|---|---|---|
| `motionPress` | `tween(100, easing = CubicBezierEasing(0.2f,0f,0f,1f))` + scale 0.97 | 全局按压反馈（M3） |
| `motionFast` | `tween(150, easing = CubicBezierEasing(0.2f,0f,0f,1f))` | 颜色/透明度/chevron/新消息 fade |
| `motionBase` | `tween(250, easing = CubicBezierEasing(0.2f,0f,0f,1f))` | 展开收起、页面内 Tab 变色、抽屉滑入 |
| `motionDialog` | `tween(200, easing = CubicBezierEasing(0.2f,0f,0f,1f))` | Dialog 入场（fade + scale 0.96→1）；退场 fade 150ms |

**全 App 无 spring、无 keyframes、无 infiniteRepeat（流式光标除外）。**

### 7.2 三个有意义的动效

| # | 动效 | 参数 | 传达什么 |
|---|---|---|---|
| M1 | **审批弹窗克制入场** | fade + scale 0.96→1，200ms；退场 fade 150ms | "这是重大决定，但我很冷静"——不弹跳、不脉冲，紧迫感由倒计时线承担 |
| M2 | **展开收起** | `animateContentSize` 250ms（工具卡/日志/技能明细共用同一 token） | 信息按需展开，层级不跳 |
| M3 | **按压反馈** | scale 0.97 + 100ms，所有可点元素 | "我收到你的触摸了"——最轻的触觉语言 |

> **v3.1 补充**：抽屉滑入（250ms `motionBase`）、滑出与 scrim fade（200ms `motionDialog`）、手势吸附（150ms `motionFast`）均为上表同 token 家族的结构位移复用——**不新增第 4 个动效 token**，「3 个有意义的动效」不变。

### 7.3 废止清单（v2 的仪器动效，一个不留）

信号扫描光带（1500ms）、状态点 pulse（1200ms）、盾牌呼吸（1600ms infiniteReverse）、失败 shake、成功 pop spring、日志滚入位移（12dp）+ 级别点 spring + 色条 scaleX 划入、Tab 药丸 scale spring、消息 slideInVertically、气泡 pressed scale 0.98 spring。

### 7.4 两个功能性例外（不算装饰动效）

1. **流式光标**：AI 气泡尾部 2×14sp `primary` 块 800ms 闪烁——全 App 唯一循环，承载"正在生成"状态，reduce-motion 下改为静态块 + 「正在回复」文字。
2. **审批倒计时进度线**：2dp `danger` 线 100%→0，10s linear——信息性进度，reduce-motion 下保留（倒计时的信息价值高于防晕动效）。

---

## 8. 落地映射（前端工程师接口）

### 8.1 Theme.kt 组织方案

拆为五个文件（替代现有单文件 Theme.kt）：

| 文件 | 内容 |
|---|---|
| `Color.kt` | 两套 `lightColorScheme()` / `darkColorScheme()` + `AgentKitSemanticColors` data class + `LocalAgentKitColors`（staticCompositionLocalOf） |
| `Type.kt` | 9 个 M3 slot + `machineText` / `machineSmall` 两个自定义 TextStyle |
| `Shape.kt` | `extraSmall=8 / small=14 / medium=14 / large=20 / extraLarge=20` |
| `Motion.kt` | `motionPress / motionFast / motionBase / motionDialog` 四个 token |
| `Theme.kt` | 组装；`AgentKitTheme(dynamicColor: Boolean = false, content)` |

**ColorScheme 字段映射（现有代码 → v3）**

| ColorScheme 字段 | 旧 Light | 旧 Dark | **新 Light** | **新 Dark** |
|---|---|---|---|---|
| primary | #4F46E5 | #B7B4FF | `#0E5D6B` | `#7FC8CE` |
| onPrimary | #FFFFFF | #221F5C | `#FFFFFF` | `#05292E` |
| primaryContainer | #E4E2FF | #37346E | `#D2E7E9` | `#14494F` |
| onPrimaryContainer | #1B1A57 | #E4E2FF | `#093F49` | `#B9E6EA` |
| background / surface | #FAFAFC | #12131B | `#F8EFE9` | `#17110E` |
| onBackground / onSurface | #1A1B25 | #E6E6F0 | `#2E2620` | `#F2EAE3` |
| surfaceVariant | #E9E9F2 | #272839 | `#F6EDE7` | `#372C25` |
| onSurfaceVariant | #4A4C5E | #C6C6D6 | `#6B5B4E` | `#BBA99B` |
| outline | #C6C6D6 | #8E8E9E | `#E5D6CC` | `#3E332C` |
| error | #B3261E | #FFB4AB | `#B4322A` | `#FF8D82` |
| onError | — | — | `#FFFFFF` | `#FFFFFF` |
| errorContainer | #FCE4E4 | #93000A | `#F9E2DF` | `#3D1A14` |
| onErrorContainer | #8C1009 | #FFDAD6 | `#7E1F16` | `#FFC9C0` |
| surfaceContainerLow | — | — | `#F4E9E2` | `#241C18` |
| surfaceContainer | — | — | `#F4E9E2` | `#2A211C` |
| surfaceContainerHigh | — | — | `#EFE2D9` | `#372C25` |
| inverseSurface / inverseOnSurface | — | — | `#2E2620` / `#F8EFE9` | `#F2EAE3` / `#17110E` |
| scrim | — | — | `#1A120D@40%` | `#000000@56%` |

> material3 ≥1.2.0 才有 `surfaceContainer*`；低于此版本时用 `surface` + `LocalAgentKitColors` 扩展对象承载全部 surface-1/2、outlineStrong、语义色（含 `dangerFill`、`textTertiary`、`logDebug/logInfo/logWarn/logError`、`trustSandbox`），无需改库版本。旧 `AgentColors` 对象删除，引用点改 `LocalAgentKitColors.current.xxx`。

**审批「允许执行」按钮落地示例**（Dark 套必须走 `dangerFill` 而非 `error`——亮珊瑚 `#FF8D82` 只做文字/图标，不做大填充，见 §3.4 注 1）：

```kotlin
// Light：dangerFill #B4322A / onDangerFill #FFFFFF = 6.12:1
// Dark ：dangerFill #C2483A / onDangerFill #FFFFFF = 4.90:1（按钮对 Dialog 底 3.57:1）
val semantic = LocalAgentKitColors.current
Button(
    onClick = onAllow,
    colors = ButtonDefaults.buttonColors(
        containerColor = semantic.dangerFill,
        contentColor = semantic.onDangerFill
    )
) { Text("允许执行", fontWeight = FontWeight.SemiBold) }

// 「拒绝」：Outlined 描边走 outlineStrong 强轨（≥3:1，Light 4.37:1 / Dark 5.06:1 on Dialog 底）
OutlinedButton(
    onClick = onDeny,
    border = BorderStroke(1.5.dp, LocalAgentKitColors.current.outlineStrong)
) { Text("拒绝", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) }
```

**Typography / Shapes 替换点**：`bodyMedium 15/22 → bodyLarge 15/26`；`titleLarge 20/28 → 18/26`；`bodySmall 13/18 → 13/20`；`labelSmall 11/16 → 11/14`；补齐 headlineSmall 22/30、titleMedium 16/24；新增 `machineText` / `machineSmall`。Shapes `8/12/16 → 8/14/20`。

### 8.2 组件重做优先级

| 优先级 | 组件 | 动作 |
|---|---|---|
| **P0** | Theme（Color/Type/Shape/Motion 五文件） | 全量替换；`AgentColors` → `LocalAgentKitColors` |
| **P0** | ApprovalDialog | 重做（§6.4）：双按钮等高 + 免询勾选 + 10s 倒计时；废弃旧「允许一次/始终允许」平级堆叠 |
| **P0** | 列表项基件 + 聊天气泡 + ToolCard | 白卡 20dp、0 阴影、状态文字双编码、STEP n/m 中文化 |
| **P1** | 抽屉（唯一 App 级导航）+ 透明顶栏 | 按 §6.5 新建：9 入口两分组 + 就绪文字三态 + 2 处角标 + 右滑手势；**删除**旧底部 Tab（NavigationBar/TabRow）全部代码与状态 |
| **P1** | 日志条目 + 筛选 chips | 两行结构 + 中文级别 + 左缘色条 |
| **P1** | 技能卡 | 信任徽章三色 + 高危摘要行 + 展开明细；权限 chips 矩阵删除 |
| **P1** | 空态 5 个 | 居中排版，hero 卡废止 |
| **P2** | 模型配置页 | Key 自检行 + 测试连接四态 + 结果卡 |
| **P2** | 图标迁移 | emoji 与文本符号（✅⚠️❌↗）→ 线性格矢量图标（1.5dp stroke） |

### 8.3 视觉回归检查清单（8 条，验收逐条打勾）

- [ ] 1. 正文/次要/三级文字在**暖粉底、白卡、内嵌块**三种底色上，明暗双套均 ≥4.5:1（对照 §3.4 表）
- [ ] 2. 交互边界一律 `outlineStrong`（按钮描边/输入聚焦/checkbox 未选 ≥3:1）；装饰分隔线只走 `outline`（<2:1）
- [ ] 3. 全 App `shadowElevation = 0`、卡片 0 描边；分层只靠底色与 scrim；无渐变（对齐 ui-design-system 绝对避免）
- [ ] 4. 触控 ≥48dp；审批两按钮等高 48dp；「允许执行」文字 ≥4.5:1（Light 6.12:1 / Dark 4.90:1）
- [ ] 5. 纯中文语汇：无英文微标签、无 mono 双语汇；mono 只出现在机器文本（时间戳/Key/工具名/入参/STEP）
- [ ] 6. 状态双编码：日志级别、工具状态、信任徽章均有文字第二编码；「已拒绝」用中性色不用红；红色只出现在状态语义场景
- [ ] 7. 动效只取 §7.1 四个 token + 流式光标 + 倒计时线；无 spring / pulse / 光带 / shake；审批弹窗无脉冲呼吸
- [ ] 8. Light 为主套逐页走查 + Dark 配套抽验：暖粉底无冷灰文字、白卡无纯黑、暗色为暖黑（`#17110E`）不是 v2 的冷黑（`#0B0E14`）

### 8.4 v3.1 设计稿 / Mockup 更新清单（前端对照）

**删除**

1. 底部 Tab 全部状态稿（3 项选中/未选/红点、栏体条）
2. 「输入栏 + 底部 Tab」组合稿

**更新（顶栏换骨架：左上汉堡 + 居中标题 + 右上动作；输入栏贴底）**

3. 对话页：顶栏「对话」+ 新对话按钮；输入栏贴底 + 玻璃栏；标注内容区 +64dp
4. 模型配置页：顶栏「模型配置」无右上动作；**新增**页面内 Tab「模型/提示词」选中/未选两态；保存/取消行在内容区底部
5. 控制台页：顶栏 + 刷新按钮
6. 权限中心页：顶栏（无动作）
7. 技能中心页：顶栏 + 「+ 导入」
8. 运行日志页：顶栏 + 导出
9. 记忆库页：顶栏 + 「+ 新建」
10. 设置页 / 关于页：顶栏（无动作）
11. 二级页（如技能详情）：顶栏左侧返回按钮

**新增**

12. 抽屉展开态完整稿：9 项两分组 + 底部位 + 就绪文字 + 2 处角标 + 选中态（tint + 左缘 3dp 竖条）+ scrim
13. 就绪文字三态稿：已连接 / N 项待授权 / 未连接（整行可点）
14. 右滑开抽屉手势示意（交互说明稿，可与 12 合并）
15. 宽屏 ≥840dp 常驻抽屉态稿（280dp 侧栏 + 内容区限宽居中）
16. 输入栏贴底前后对比稿（内容区 +64dp 增益标注）

---

## 9. 设计决策摘要（三个最关键的"简洁"决策）

1. **0 阴影、0 描边、三层底色。** 粉底 → 白卡 → 暖灰内嵌，明度差已经说完所有分层；阴影和描边是 v2 控制台人格的残留，在暖纸上只会显廉价。省下的视觉噪音全部转成留白（页面边距 16→20dp）。
2. **审批弹窗做减法。** 红色从"大面积警示"收缩到 4 个小面积元素（图标/竖条/按钮/倒计时线），图标从 1600ms 呼吸改为静态，入场从 spring 弹跳改为 200ms fade——警觉性一点没少（全屏阻断 + 倒计时 + 红色按钮仍在），恐慌感全部拿掉。
3. **v3.1 起导航只剩一个元素：侧边抽屉。** 底部 3 Tab 整体废止（每屏 −1 个常驻元素、内容区 +64dp、与 RikkaHub 原型一致），抽屉升格为唯一 App 级导航，同时保持最薄：双行分组标题、英文微标签、状态胶囊、脉冲红点、chevron 全部没有，只剩：应用标记 + 一行就绪文字 + 两组单行菜单 + 底部位两行 + 2 处静态角标（红点=待办 / 计数=存量 / 零不显示）。页面内 Tab（模型页 1 处）是内容分区，不是第二导航。可达性（日志/权限一步进）与简洁（一个普通列表）不再互斥。

---

*规范完。前端工程师从 §8.1 开始落地；视觉验收按 §8.3 清单逐条打勾。改色值必须先回 §3.4 复测对比度。*
