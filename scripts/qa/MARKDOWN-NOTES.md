# Phase 2 · T11 聊天消息 Markdown 渲染 — 实现说明

> 本文件为详细交付说明；回传主理人的摘要见会话回传（≤20 行）。

## 1. 需求与验收（Phase 2 DoD）

- 把聊天里 **Assistant** 的回复从纯文本升级为 Markdown 渲染；**用户气泡保持纯文本**。
- 必须支持 **9 类语法**：① 标题(#~###) ② 粗体 ③ 斜体 ④ 行内代码 ⑤ 围栏代码块(带语言标签/横向滚动/等宽)
  ⑥ 无序+有序列表(含嵌套一层) ⑦ 引用 ⑧ 链接 ⑨ 分割线。
- **安全硬要求**：`<script>` 等 HTML **原样显示为文本**，绝不执行、绝不吞掉。
- **工程**：解析与渲染分离；解析纯函数可被纯 JVM harness 断言；未闭合标记按字面渲染不抛异常；
  流式半截文本健壮；长代码块/长链接横向滚动不撑破气泡。

## 2. 文件清单

| 文件 | 行数 | 说明 |
|---|---|---|
| `app/src/main/java/com/example/agentkit/ui/MarkdownParser.kt` | 422 | **新增**。纯 Kotlin（零 Android/Compose 依赖）。AST + `MarkdownParser.parse / parseInline` |
| `app/src/main/java/com/example/agentkit/ui/MarkdownText.kt` | 250 | **新增**。Compose 渲染层 `MarkdownText(text, modifier, color)` |
| `app/src/main/java/com/example/agentkit/ui/ChatScreen.kt` | 1109 | **修改**。`MessageBubble` 里助手分支改用 `MarkdownText`（用户分支不动） |
| `scripts/qa/MarkdownVerification.kt` | 404 | **新增**。45 条纯 JVM 断言 |
| `scripts/qa/run-markdown-verification.sh` | 29 | **新增**。离线编译+运行（照 `run-verification.sh`） |

**零新增外部依赖 / 零 JUnit / 未改 minSdk·targetSdk·abiFilters / 未引任何 Markdown 库。**

## 3. 架构：解析 ⟂ 渲染

```
文本 ──(MarkdownParser.parse: 纯函数)──► List<MdBlock>（块级 AST）
                                        └─ 每块内容 = List<MdSpan>（行内 AST）
List<MdBlock> ──(MarkdownText: Compose)──► 视觉
```

- **块级 AST** `MdBlock`：`Heading(level,content)` / `Paragraph` / `CodeBlock(language,code)` /
  `BulletList(items)` / `OrderedList(items)` / `Quote` / `Divider`。
- **行内 AST** `MdSpan`：`Plain` / `Bold(children)` / `Italic(children)` / `Code(text)` / `Link(children,url)`。
- **列表项** `MdListItem(content, subList: MdBlock?)`：子列表挂在上一个子项上，天然支持嵌套（≥1 层）。
- 解析器是 `object` + `private class`（块解析器 / 行内解析器），**不引用任何 Android 类型**，
  编译产物可被纯 JVM 直接加载断言。

## 4. 关键裁决

1. **不解析 HTML = 无注入面**：解析器从不识别 `<...>`。`<script>alert(1)</script>` 落为单个
   `MdSpan.Plain`。AST 里**根本不存在 HTML/脚本节点类型**，从根上杜绝注入（比白名单消毒更彻底）。
2. **未闭合标记 → 字面**：`**粗` → `[Plain("**粗")]`；`` `code `` → `[Plain("`code")]`；
   孤立 ```` ``` ```` → `Paragraph([Plain("```")])`。配对失败即退化为字面，**不吞内容、不抛异常**。
3. **围栏代码块需配对闭合**：` ``` ` 无闭合围栏时**不成块**，整行按字面输出（未动行内解析，
   避免反引号被误当行内码）。闭合后正常成块。此为「流式半截不崩 + 未闭合按字面」的折中。
4. **代码块内容不做二次解析**：`CodeBlock.code` 为原文，内部 `#`/`-`/`**` 一律字面保留。
5. **行内优先级**：`` ` `` 行内码最高（其内容不再解析）；`**` 优先于单 `*`。可嵌套（粗体内含斜体）。
6. **链接非跳转**：`Link` 只保存 `url` 与文字，渲染层仅做「链接样式」（primary + 下划线），
   **不加点击跳转**，避免 `javascript:` 之流的协议注入面（Phase 2 未要求导航）。
7. **用户气泡不渲染**：仅助手气泡走 Markdown；用户输入按纯文本（避免用户输入被误解为指令标记）。
8. **横向滚动**：代码块用 `horizontalScroll` + `softWrap=false`，长行不换行、不撑破气泡；
   垂直方向交宿主 `LazyColumn`。长链接由气泡宽度 clamp + 文本软换行约束。

## 5. harness 覆盖（45 条，全 PASS）

- **A 块级(18)**：标题 1/2/3、非标题(#hashtag / 7 井号)、多行段落、代码块(带/无语言、
  内容不被二次解析)、无序(-/*)、有序(./))、嵌套列表、引用(单/多行)、分割线(---/***)、混合文档块序列。
- **B 行内(8)**：粗/斜/行内码/链接、混合分段、粗体内嵌斜体、行内码优先、标题内行内。
- **C 安全(4)**：`<script>` 落 Plain 且 AST 无非 Plain 节点、`**<b>x</b>**`、`<img onerror>`、HTML+MD 混排。
- **D 未闭合(6)**：`**粗`、`` `code ``、孤立 ```` ``` ````、`*ital`、`[t](u`、块级 `**未闭合`。
- **E 边界(3)**：空串、全空白、≈4 万字超长单行。
- **F 流式(6)**：半截加粗/闭合后、富文本逐前缀全量不崩、半截代码块、半截链接、半截引用。

## 6. 自检证据

- 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks` → **BUILD SUCCESSFUL**；
  新文件零告警（无 `MarkdownParser.kt` / `MarkdownText.kt` 的 `w:` / `e:`）。
- 新 harness：`bash scripts/qa/run-markdown-verification.sh` → **45 passed, 0 failed**。
- 七套回归（全绿，零回归）：
  `run-verification.sh`(32) `run-t01-verification.sh`(35) `run-t01-probe.sh`(11)
  `run-t01-probe2.sh`(7) `run-appcontrol-verification.sh`(31) `run-t07t08-verification.sh`(35)
  `run-t07t08-adversarial.sh`(17)。

## 7. 全局一致性自检

- **导入一致性**：`MarkdownText` 依赖 `MarkdownParser` 的 AST 类型（同包，无循环导入）；
  `ChatScreen` 同包调用 `MarkdownText`，无需 import。`machineText`/`LocalAkColors` 为同包 internal，复用。
- **接口契约**：`parse(text): List<MdBlock>`、`parseInline(text): List<MdSpan>` 均纯函数、无副作用、可重入。
- **数据流**：`ChatScreen.MessageBubble(assistant)` → `MarkdownText(text)` → `parse` → 渲染。用户分支未触达。
- **无重复实现**：解析逻辑只在 `MarkdownParser` 一处；渲染只在 `MarkdownText` 一处。
- **裁决**：`IS_PASS: YES`。

## 8. 遗留 / 未做（明确记录）

- 表格（GFM table）未支持——路线图原文含表格，但本批验收的 9 类语法不含表格，故按 9 类交付。
- 语法高亮（`SimpleHighlighter`）为 Phase 2 另一项（T13），本批代码块仅等宽、无着色。
- 链接不可点击跳转（见裁决 6）。
- 解析缓存：当前用 `remember(text)` 挡重复解析；路线图提到的 240 上限 `LinkedHashMap` 缓存未引入
  （流式下文本逐帧变化，缓存收益有限，避免过度工程）。
