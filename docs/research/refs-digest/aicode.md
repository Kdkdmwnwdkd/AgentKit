# AiCode 参照仓库吃透报告（第 4 路）

> 研究对象：`/root/.codebuddy/artifact/repos/aicode`（main 分支，922 文件，app 模块 440 个 `.kt`，assets 3.7 MB）
> 许可证：**GPL-3.0** —— 本报告只提取**设计原理与工程思路**，不含任何移植/摘录代码；所有落地必须**净室重写**
> 撰写：产品经理 许清楚 ｜ 目的：为 AgentKit 提供「理解其设计 → 我们怎么适配」的改造依据

---

## ① TL;DR

AiCode 是**目前四个参照 App 里，唯一一个「AI + 完整 Linux 容器 + 真实代码编辑器 + 内置提示词/场景工程体系」四件套齐全的产物**。它不是聊天 App 外挂终端，而是**把「一台能跑 node/python/java/Android-SDK 的 Linux 开发机」塞进手机，再让 AI 在这台机器上干活**。

**八条最值得抄的思路（不是代码）：**

1. **提示词是「分层配置资产」，不是代码里的字符串**
   `assets/prompts/` 用 `<两位数字>-<名称>.md` 做**编号身份**决定注入顺序，`prompts.custom/` 按编号覆盖；动态内容全部走 `{{AICODE_*}}` 变量注入。**四层优先级：项目 > 全局 > 本地 > 内置**（`PromptFragmentCatalog.kt:13`）。
   → **这是 AgentKit 最大的结构性差距**：我们的提示词硬编码在 `AgentLoop.kt` 里，改一次要发版。

2. **「场景库」把「装开发环境」这件事产品化了**
   `assets/aicode/lib/scenarios/{node,python,java,go,rust,php,android,flutter,common,base}.sh`，每个脚本只暴露两个函数 `scenario_name_<id>` / `install_scenario_<id>`，由 `SCENARIO_IDS` 列表驱动菜单（`env-common.sh:613`，分发在 `:622-634`）。
   → AgentKit 想要「能写代码 / 写游戏 / 做安装包」，**必须**有这套东西，否则容器里连 `node` 都没有。

3. **`editFile` 用「精确字符串匹配」而不是行号**
   `EditFileTool.kt:37-56` 的注释写死了理由：**行号在连续编辑时会漂移，字符串匹配不会**。`old_string → new_string` 一个工具覆盖替换/删除/插入三种语义；`edits` 数组**整批原子**（任一失败全回滚）。

4. **双层并发保护：路径锁 + 写前版本校验（STALE_FILE）**
   `WorkspaceFileLock`（互斥）+ `FileVersionRegistry`（内容指纹比对）。关键点：**先 readFile 再 editFile 不只是好习惯，是拿到保护的前提**（`FileTools.kt:39-42`）——因为版本 registry 由 readFile 的副作用登记。

5. **`terminal` 工具用「notify=true」反转了轮询模型**
   后台命令跑完不是 AI 去轮询，而是**系统推事件唤醒 AI**（`BackgroundTerminalTools.kt:100` 描述、`TerminalSessionManager.kt:414-425` 发 `TabFinishedEvent`）。提示词里明确写「不要轮询或 sleep 等待」。
   → AgentKit 的 `AgentExecutor` 现在应该是同步盲等，这是体验分水岭。

6. **ARGV 第一位必须是 proot 二进制本身**
   `LinuxContainerEngine.kt:46-52` 花了一大段注释说明：Termux `TerminalSession` 的 `native execvp(cmd, argv)` **不会自动补 argv[0]**，写成习惯性的「cmd=客户机程序、args=参数」会让选项整体错位一位。
   → AgentKit `ProotRunner.buildArgs()`（`core/container/ProotRunner.kt:73`）暂时只服务 `ProcessBuilder`，一旦接 PTY **必踩此坑**，建议提前对齐。

7. **Checkpoint 只快照「将被修改的文件」，且回滚必须报告 skip 清单**
   `CheckpointManager.kt:23-29` 注释直说：**静默跳过等于把数据丢失换成了无声的失败**。对比 AgentKit `CheckpointStore` 现在是整目录 `copyRecursively`，大 workspace 下会卡。

8. **`targetSdk` 锁 28 的真正原因不是 proot，而是 jniLibs 路径可执行**
   `app/build.gradle.kts:107-114` 注释推翻了旧认知：proot 全套改走 **jniLibs → nativeLibraryDir**（唯一允许 `execve` 的 SELinux 标签位置），客户机二进制由 proot loader 用 `mmap(PROT_EXEC)` 加载，不受 W^X 约束。
   → AgentKit 现在是 `assets/bin/arm64-v8a/proot` 提取到 `filesDir`（`ProotRunner.kt:27,30`），**这条路径在 Android 10+ 有风险**，建议直接对齐 jniLibs 方案。

**一句话结论**：AiCode 真正值钱的是**上下文工程（提示词/场景/技能/记忆四层解耦）** 和 **「文件编辑的安全性工程」（原子批 + 版本校验 + 检查点）**。这两块是 AgentKit 从「能跑 Demo」走到「用户敢让它改真实项目」的必经之路。

---

## ② 架构分层地图

### 2.1 模块划分

三模块 Gradle 工程（`settings.gradle.kts:21-23`）：

| 模块 | 语言 | 内容 | 说明 |
|---|---|---|---|
| `:app` | Kotlin | **440 个 `.kt`** | 业务主体，feature-based 分层 |
| `:terminal-emulator` | **Java**（15 文件） | `com.termux.terminal.*` | VT100/ANSI 解析 + PTY + 屏幕缓冲区 |
| `:terminal-view` | **Java**（7 文件） | `com.termux.view.*` | Android View 渲染 + 手势 + 文本选择 |

> ⚠️ 两个终端模块是 **Java** 不是 Kotlin，且经 **JitPack** 分发（`settings.gradle.kts:12`，注释说明腾讯镜像没有）。AgentKit 若想引入，受**「零新增外部依赖」红线**约束，只能净室重写 —— 好在 AgentKit 已有 `terminal/TerminalEmulator.kt`（自己实现的 VT100 状态机），这条不用动。

### 2.2 技术栈

来自 `app/build.gradle.kts`：

| 层面 | 选型 | 位置 |
|---|---|---|
| 语言/编译 | Kotlin 2.2.21，JVM 17 | `build.gradle.kts:19`（根） |
| AGP | 8.9.3，`compileSdk 36` | 根 `:18`，`app/:91` |
| UI | Compose BOM **2026.01.00** + Material3 | `app/:365-370` |
| DI | **Hilt 2.56.1** + KSP | `app/:377-378` |
| 持久化 | **Room 2.7.1**（schema v55）+ DataStore | `app/:381-386` |
| 网络 | Retrofit + Gson + OkHttp 4.12.0 | `app/:389-391` |
| 序列化 | kotlinx-serialization-json **1.8.1** | `app/:399` |
| YAML | snakeyaml 2.2（技能/子代理 frontmatter） | `app/:402` |
| SVG/远程 | sshj 0.38.0 + BouncyCastle + Apache FtpServer | `app/:405-410` |
| 容器解压 | commons-compress 1.26.2 + xz | `app/:413-415` |
| Markdown | multiplatform-markdown-renderer-m3 0.41.0 + `-code` | `app/:374-376` |
| 代码高亮 | highlights-jvm 1.1.0（diff 用）/ TextMate（编辑器用） | `app/:378` |
| **代码编辑器** | **sora-editor 0.24.6** + `language-textmate` | `app/:382-386` |
| 图表/耗时 | Vico 2.4.4（Token 趋势） | `app/:372` |
| Shizuku | rikka api/provider 13.1.5 | `app/:349-350` |

**关键 KMP/constraint 结论**：AiCode **大量依赖**（Hilt / Room / sora-editor / Retrofit / markdown-renderer / shirtato / Vico / jlatexmath）。AgentKit 红线是「零新增外部依赖（仅 okhttp + serialization + rhino）」，所以：
- **不能直接引入 sora-editor** → TextMate 高亮必须自己用 Kotlin 重写一个极简引擎（可行性见 §4.1）
- **不能引入 markdown-renderer** → 聊天 Markdown 需自研（或继续用轻量方案）
- **可以放心借鉴** 的是**不依赖任何库的纯 Kotlin/算法部分**：`LineDiff`（LCS）、`FileVersionRegistry`、`WorkspaceFileLock`、`PromptFragmentResolver`、`BoundedOutput`、`ToolPermissionPolicyEngine` 的规则表。

### 2.3 包结构（feature-based + DDD）

```
com.aicode/
├── AIEditorApp.kt          # Application：FileLogger / TerminalKeepaliveService / McpManager
├── MainActivity.kt         # 1073 行：NavHost + ModalNavigationDrawer + 双栏布局
├── WorkbenchPane.kt        # 大屏右栏容器（92 行）
├── core/
│   ├── db/     MigrationLoader.kt       # 文件式 SQL 迁移 + SqlScriptSplitter
│   ├── net/    (5)                      # 快照崩溃/bug trader
│   ├── security/ (1)
│   ├── theme/  AIEditorTheme.kt         # Spacing / Radius / semanticColors
│   ├── ui/     (14)                     # AppTextField / AppSwitch / AdaptiveModalBottomSheet /
│   │                                    # SwipeToDeleteRow / SplitHandle / FloatingTabBar /
│   │                                    # SegmentedTabs / ImageViewer / PageMotion / WindowSize
│   └── util/   (11)                     # **LineDiff** / FileLogger / LanguageRegistry /
│                                        # GitIgnoreMatcher / BoundedLineReader / ApkInstaller
├── di/
└── feature/
    ├── agent/       (188 kt)  ★核心
    ├── settings/    (95 kt)
    ├── workspace/   (31 kt)
    ├── credentials/ (16 kt)
    ├── backup/      (16 kt)
    ├── terminal/    (15 kt)
    ├── git/         (12 kt)
    ├── editor/      (9 kt)
    ├── onboarding/  (7 kt)
    ├── disclaimer/  (3 kt)
    └── browser/     (2 kt)
```

`feature/agent` 子结构（**这是重点，188 个文件**）：

```
domain/
├── prompt/        SystemPromptProvider / PromptFragmentCatalog / PromptFragmentResolver
├── tool/          AgentTool.kt（抽象基类）+ ToolRegistry + ToolPermissionManager
│   ├── container/ ExecuteCommandTool(Bash) / BackgroundTerminalTools(terminal)
│   ├── editor/    EditFileTool
│   ├── file/      FileTools(readFile/writeFile) / ImageTools / GenerateImageTool / SendFileTool
│   ├── explorer/  ListFilesTool(list) / SearchCodeTool(search) / ShellArgs
│   ├── subagent/  TaskTool(task) / MessageParentTool
│   ├── skill/     LoadSkillTool
│   ├── memory/    MemoryTool
│   ├── todo/      TodoTool
│   ├── mode/      PlanModeTool / PlanApprovalManager
│   ├── question/  AskUserQuestionTool
│   ├── search/    WebSearchTool / WebFetchTool
│   ├── mcp/       ManageMcpTool
│   ├── browser/   BrowserTool
│   └── shizuku/   ShizukuTool
├── permission/    ToolPermissionPolicyEngine / ShellCommandParser / BuiltInSafeCommands
├── subagent/      AgentDefinition / AgentDefinitionParser / *DirectoryAgentSource
├── skill/         Skill / SkillParser / *DirectorySkillSource
├── memory/        Memory / MemoryParser / GlobalMemorySource / ProjectMemorySource
├── mcp/           McpClient / McpManager / McpTool / StdioTransport / StreamableHttpTransport
├── container/     LinuxContainerEngine / ContainerInstaller / CommandEngine / RemoteSsh*
├── provider/      AIProvider / OpenAIAdapter / AnthropicAdapter / GeminiAdapter / RetryPolicy
├── workflow/      StatefulAgentWorkflow(1406行) / ContextCompactor(385) / AgentWorkflow
├── checkpoint/    CheckpointManager
├── session/       SessionUseCase / MessagePersistenceUseCase
└── notification/  AgentNotificationCenter / AgentEventInjector
data/
├── local/   database/AgentDatabase.kt + 6 DAO + 6 Entity
└── remote/  anthropic/ openai/ gemini/（各含 Models 定义）
presentation/
├── AIAgentViewModel.kt
└── component/ (41 个 Compose 组件)  MarkdownContent / MessageBubbles /
                                     ToolMessageComponents / AIChatPanel / ChatInputBar /
                                     TodoDashboardBar / DiffViewer...
```

### 2.4 导航结构

`MainActivity.kt`（1073 行）采用 **「抽屉 + NavHost + 大屏双栏」三合一**：

- `ModalNavigationDrawer` + `ModalDrawerSheet` → 侧边会话抽屉（聊天会话切换）
- `NavHost` + `composable` → 全屏路由
- **`WorkbenchPane.kt:21`** 定义大屏右栏种类：
  ```kotlin
  internal enum class WorkbenchPaneKind { NONE, EDITOR, TERMINAL, GIT, BROWSER }
  ```
- 关键设计（`WorkbenchPane.kt:23-31` 注释）：**同一个页面组件在窄屏走全屏路由、宽屏走右栏**，靠 `embedded: Boolean` 参数区分，两条路径共用一份 UI 代码。ViewModel 挂在聊天页的 NavBackStackEntry 上，**切换右栏内容不销毁 VM**。

> AgentKit 现状：`ui/AppScaffold.kt` 是单栏抽屉导航，无双栏。**这是「平板/折叠屏体验」的直接借鉴点**，且改动成本低（只加一个 `WorkbenchPane` 容器 + `WindowSizeClass` 判断）。

---

## ③ 核心功能逐项剖析（按与 AgentKit 相关性排序）

### 3.1 【核心重点】代码能力

#### 3.1.1 代码编辑器：sora-editor + TextMate

**它怎么做：**

- 依赖 `io.github.rosemoe:editor-bom:0.24.6` + `language-textmate`（`app/build.gradle.kts:382-386`）
- **刻意不引入** `language-treesitter` 与 `oniguruma-native` —— 注释说明这两个含 `.so`，会与 ABI flavor 拆分和 F-Droid 可复现构建冲突（`app/build.gradle.kts:344-345`）
- API 33 以下必须开**核心库脱糖**（`app/build.gradle.kts:206, 387`）

**初始化是一次性且必须在 IO 线程**（`feature/editor/domain/TextMateSetup.kt`）：
```
TextMateSetup.kt:15      注释：语法包体积近 1 MB，loadGrammars 全量解析，不能放 Application.onCreate
                         否则拖慢冷启动 / 卡首帧
TextMateSetup.kt:27      fun ensureInitialized(context)  ← 幂等，synchronized 双重检查锁
TextMateSetup.kt:33      AsyncIncrementalAnalyzeManager.setUpdateStylesDuringAnalysis(true)
                         ← **增量渲染：每分析 1000 行就把已得着色推给编辑器**，
                           首屏不必等全文分析结束
TextMateSetup.kt:37      GrammarRegistry.getInstance().loadGrammars(ASSET_LANGUAGES)
TextMateSetup.kt:98      const val ASSET_LANGUAGES = "textmate/languages.json"
TextMateSetup.kt:42-53   loadThemes()：assets/textmate/{dark_plus,light_plus}.json
TextMateSetup.kt:56-58   applyTheme(dark)：**必须与 Compose 深浅主题一致**，否则 token 配色与背景对不上
TextMateSetup.kt:68-96   scopeNameFor(path)：扩展名 → scopeName，**且必须校验 grammar 已注册**
                         否则 TextMateLanguage.create 会抛 IllegalArgumentException 直接崩
```

覆盖 24 种扩展名（`TextMateSetup.kt:74-96`）：kt/kts, java, py, js/ts/tsx, json, yaml, sh/bash/zsh, go, rs, c/cpp, css, php, lua, xml, html, md。

**屏幕：`CodeEditorScreen.kt`（837 行）**
- `AndroidView` 承载 `io.github.rosemoe.sora.widget.CodeEditor`
- 支持：撤销/重做（`UndoManager`）、脏标记（与 `baselineText` 比对，撤销回原样时自行复位）、保存确认对话框、底部符号快捷栏、Markdown 预览切换（Tab：`Code` / `Eye`）
- **`CodeEditorScreen.kt:27` 注释坦白**：「保存直接覆盖写回，**不做外部并发写检测**——AI 工具或终端可能同时改同一文件」。
  → **这是一个已知缺陷**，正好是我们做 `STALE_FILE` 的切入点（§4.2）

**assets/textmate 目录：2.4 MB**，含 `languages.json` + `dark_plus.json` / `light_plus.json` + 各语言子目录（cpp/css/go/html/java/javascript/json/kotlin…）

#### 3.1.2 文件树：**AiCode 没有独立文件树组件**（重要发现）

全仓 `grep -rl 'FileTree|文件树|FileBrowser'` 只命中 6 个文件，且都不是树组件实现。**替代方案是三层**：

1. **`list` 工具**（`ListFilesTool.kt`）→ ls 风格输出，`MAX_ENTRIES=500`（`:33`），支持 `-a -A -l -R -d -1 -h -r -t -S -v -f --` 与 `| head [-n N]`
   - 走 `WorkspacePathMapper` **映射容器路径到宿主文件系统直接遍历**，不进容器（`:22-23` 注释），两种模式（本地/SSH）均可用
2. **`@` 文件提及**（`FileMentionRepository.kt`）：用容器内 `rg --files` 拉清单 + 内存模糊匹配
   - **默认遵守 .gitignore**，自动剪掉 `.git` / `build`，避免大仓库全量遍历（`:18-19`）
   - 清单**不跨会话缓存**，每次打开菜单拉最新（`:12-13`）
3. **`search` 工具**（`SearchCodeTool.kt`）→ 容器内 ripgrep，**只允许 `| head` 管道**，其它管道（grep/sort/wc）一律拒绝（`buildSearchCommand`）

> **这对 AgentKit 是好消息**：AgentKit 若先不做重型文件树，只在聊天里渲染工具结果，体验差距并不大。**真要做树，也是 `rg --files` + 增量展开，而不是一次性遍历。**

#### 3.1.3 Diff：`LineDiff`（**纯 Kotlin，零依赖，可直接重写**）

`core/util/LineDiff.kt`（60 行）：

```
LineDiff.kt:11-16   统一文本格式：每行第 0 列为标记位（+/-/空格），其余为原文
                    → **即便代码本身以 +/- 开头也不会歧义**，解析时只取首列
LineDiff.kt:19-49   diff(oldText, newText)：**经典 LCS 动态规划**
                    - dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度
                    - 倒序填表 → 正序回溯走 случаи
                    - 输出 List<DiffLine>(type, text)
LineDiff.kt:51-58   toUnified()：渲染为 `+xxx` / `-xxx` / ` xxx` 文本
```

渲染侧在 Git 页：`feature/git/presentation/component/DiffViewer.kt`
- `DiffData` / `DiffRow` 数据模型
- **行号在 ViewModel 算 diff 时一并算好**（注释解释：避免 `LazyColumn` 懒加载下 UI 层维护可变计数）
- 着色用 `dev.snipme:highlights-jvm`（`Highlights` / `SyntaxLanguage` / `SyntaxThemes`）
- 防护：`MAX_DIFF_FILE_BYTES = 2 MB`（`GitRepository.kt:44`），超过降级为「文件过大」

> **移植价值极高**：LCS 是纯算法，不受 GPL/依赖约束，AgentKit 可在 `core/workspace/` 下净室实现一个 `LineDiff.kt`（约 60 行）。**唯一要注意**：`(n+1)×(m+1)` 的 `IntArray` 表在大文件上会 OOM，净室版应加「文件超过 N 行则退化为按段落 diff」的保护。

#### 3.1.4 Markdown 渲染：**双路径**

| 场景 | 方案 | 位置 |
|---|---|---|
| **聊天消息** | `multiplatform-markdown-renderer-m3:0.41.0` + `-code`（Compose 原生渲染） | `app/build.gradle.kts:374-376` |
| **编辑器预览页** | **WebView + marked.js + highlight.js + DOMPurify** | `MarkdownPreviewWebView.kt` + `assets/markdown/` |

`assets/markdown/`：`preview.html`、`marked.min.js`、`highlight.min.js`、`purify.min.js`、`highlight-github{,-dark}.min.css`、`github-markdown-{light,dark}.css`，外加 `scripts/eruda.min.js`（远程调试）。许可证集中在 `assets/licenses/`。

`MarkdownPreviewWebView.kt` 安全配置（`:47-56`）：
```
settings.javaScriptEnabled = true           // 必须：marked.js
settings.allowFileAccess = false            // ← 关键：页面只经 assetLoader 取资源
settings.allowContentAccess = false         //   不暴露本地文件系统
settings.domStorageEnabled = false
webViewClient.shouldInterceptRequest → viewModel.assetLoader（自定义 WebViewAssetLoader）
```
预览页返回键优先回退 WebView 历史，到头再退出；**外部链接不跳转**。

> 第三方 license 清单完整性值得学：`assets/licenses/{DOMPurify-Apache-2.0, github-markdown-css-MIT, highlight.js-BSD-3-Clause, JetBrainsMono-OFL-1.1, marked-MIT}.txt` —— AgentKit 已有 `assets/3rdparty/proot/COPYING` + `SOURCE_OFFER.txt`，这套做法已经在做，可以对齐扩展。

#### 3.1.5 代码执行：**「一次性」vs「常驻」双通道**

这是 AiCode 最精巧的一处设计，提示词里明写（`prompts/60-tools-and-paths.md`）：

> 命令：**一次性命令用 `Bash`**（内置 `git`、`rg`、`py`/`python`、`node`，不要先问是否安装）；**常驻或交互式会话用 `terminal`**。

| | `Bash`（`ExecuteCommandTool.kt`） | `terminal`（`BackgroundTerminalTools.kt`） |
|---|---|---|
| 语义 | 一次性的单次命令，同步返回 | 常驻/交互式会话，返回 `tab_id` 后续 send/read/key/close |
| 默认超时 | 120s，上限 1800s | 无（任务持续运行） |
| 流式 | `executeStream` 逐行 emit `ToolStreamEvent.Progress` | 同 |
| 典型用途 | `npm install`、`git status` | `npm run dev`、`tail -f`、python REPL、编辑器 |
| 权限 | `ToolPermissionPolicy.ASK` + 按**命令前缀**记忆 | `ASK`，`start` 按命令前缀、`read` 按整工具 |
| **唤醒 AI** | 无 | **`notify=true`**：命令退出时系统推 `TabFinishedEvent`（含退出码 + 末尾 N 行），主动唤醒 AI |

输出限幅：`BoundedOutput`（`container/BoundedOutput.kt`，90 行）—— **超大输出只保留开头+结尾**，保证喂回模型的内容一致且不撑爆上下文。

> `ExecuteCommandTool.kt` 注释里有个细节值得记：**异常兜底时必须把已 emit 的输出作为结果保留**，否则模型只看到「执行失败」，之前展示给用户的内容全丢（`:168-176`）。

### 3.2 【核心重点】内置 Agent 体系

#### 3.2.1 提示词资产组织：`assets/prompts/`

```
prompts/                          ← 静态基线，**编号即身份**，决定注入顺序
├── 00-identity.md           (6 行)
├── 10-communication.md     (15)
├── 15-project-rules.md     (10)
├── 20-coding-discipline.md (10)
├── 30-comments.md           (5)
├── 40-approach.md           (9)
├── 50-safety.md            (21)
├── 60-tools-and-paths.md   (34)   ★ 工具选择纪律 + 路径约定 + 子代理用法
├── 70-skills-and-mcp.md    (34)
└── 99-runtime-context.md   (30)   ← 动态内容集中在这（`{{AICODE_*}}` 变量）

prompts/agent/                    ← 按需叶子片段，**无数字身份**，按精确同名覆盖
├── auto-mode.md           (14)   PLAN/AUTO 模式约束，**随用户消息注入，不进 system**
├── plan-mode.md           (25)   同上
├── subagent-base.md       (49)   子代理精简基线
├── subagent-context.md     (8)
├── commit-generator.md    (11)
├── compact-summary.md     (11)
├── init.md                (47)
└── title-generator.md     (38)

agents/
└── explore.md             (31)   ★ 唯一的**内置子代理**（YAML frontmatter + 正文）
```

**注入规则**（`SystemPromptProvider.kt`）：
```
:22-31   注释：**稳定基线放最前（享受 KV Cache）**，仅日期为低频变化
:47-51   StaticRuleSource：**每次重新读盘** —— 未编辑时字符串一致 KV Cache 照常命中，
         编辑后立即生效无需重启
:243-275 build() 主流程：
           1. 取各 Source 快照（static / skills / subAgents / memories / projectRules /
              workspace / environment）
           2. renderVariables → 展开 {{AICODE_*}}
           3. collapseBlankLines → 3 个以上连续换行压成 2 个
:53-78   ActiveSkillsSource / :95-125 SubAgentListSource / :194-220 MemoryListSource
         → **会话级缓存** (sessionId, projectRoot)，避免每轮扫盘导致 system prompt
           抖动打断 KV 缓存；**空内容用 "" 占位区分「未缓存」**
:127-172 ProjectRuleSource：**AGENTS.md 优先、CLAUDE.md 兜底**；全局(~/.aicode) 与
         工作区两级；按 (路径+mtime) 签名判定缓存；MAX_AGENTS_CHARS = 32_000
:379-405 renderVariables：10 个占位符
         {{AICODE_SKILLS}} / {{AICODE_MEMORY_GLOBAL}} / {{AICODE_MEMORY_PROJECT}} /
         {{AICODE_SUBAGENTS}} / {{AICODE_PROJECT_RULES_*}} / {{AICODE_WORKSPACE}} /
         {{AICODE_ENVIRONMENT}} / {{AICODE_DATE}} / {{AICODE_SUBAGENT_NAME}}
         → **未写变量的片段原样保留**，不影响 {{INSTRUCTION}} 等其它占位符
:422-428 resolvePrompt 优先级（静态数字片段）：
           1. prompts.custom/ 顶层同编号文件
           2. prompts.custom/<name> 精确同名
           3. <aicodeDir>/prompts/<name>（启动时从 assets 释放的本地副本）
           4. assets/prompts/<name>（内置兜底）
```

**四层来源**（`PromptFragmentCatalog.kt`）：
```
:12-13   enum PromptFragmentSource { PROJECT, GLOBAL, LOCAL, BUILTIN }  优先级递减
:46-53   项目 = 工作区 .aicode/prompts.custom/
         全局 = <aicodeDir>/prompts.custom/
         本地 = <aicodeDir>/prompts/
         内置 = assets/prompts/
:247-254 builtinFiles()：直接读 assets.list("prompts")，靠 Regex 解析编号
:23-27   description = **正文开头的 <!-- ... --> 注释**，作为列表摘要
:39-42   editable：PROJECT/GLOBAL 可写，LOCAL/BUILTIN 只读
:123-153 saveOverride：写入前先清各可写层同编号旧文件，保证「同一编号只保留一份」
:159-180 reorder：**拖拽重排 = 按新顺序重新映射到原编号集合**，不改变编号集合本身
:183-186 deleteOverride：删后自动回退到下一层
:189-203 setBuiltinDisabled：.no-builtin 标记文件
```

`PromptFragmentResolver.kt`（40 行，**纯 Kotlin，零依赖**）：
```
:17      NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")   ← 两位数字即身份
:27-37   numberedFragments：同编号多个文件只留字典序首个（避免排序不稳定）
:15      DISABLE_BUILTIN_FILE = ".no-builtin"
```

**提取时机**：`ContainerInstaller.extractPrompts()`（`:63`，「每次启动全量覆盖，使 App 升级后提示词随之更新；**用户自定义放 prompts.custom/，本方法不触碰**」）
配套 `extractDocs()`（`:44`，「提取前先整目录删除，避免旧版本同名文件残留在别的路径下被 AI 读到」）、`extractAssetsRecursive`（`:74`，靠 `assets.open` 对目录抛 `IOException` 来递归）。

#### 3.2.2 内置子代理：`agents/explore.md`

只有一个，但结构完整：
```yaml
---
name: Explore
description: 只读代码库探索：搞清某个功能/模块/调用链怎么实现的…
tools: [readFile, list, search, websearch, webfetch, loadSkill]
inject: [base, projectRules]
---
你是代码库探索专家。你的产出是**结论**，不是改动——你没有写文件与执行命令的工具…
```

提示词正文里有几条**极高质量的约束**（值得抽象成我们的写法）：
- 「你看不到派你的那个会话的任何历史…**你的最后一条回复是主代理唯一能读到的内容**，所以结论必须自成一体」
- 「每个事实都带 `文件路径:行号` 引用，让主代理能直接跳过去核对」
- 「明确区分『读到的』与『推断的』…**绝不编造文件路径、函数名或行号**」
- 「发现与任务前提相矛盾的事实…**一定要指出来，这往往比原问题更重要**」

解析：`AgentDefinitionParser.kt`（90 行，snakeyaml）
```
:29-31   正文为空 → 返回 null（agent 必须有提示词）
:36-37   name 取 frontmatter，缺省回退到文件名
:46-49   stringList()：**同时接受 YAML 列表与逗号分隔字符串**两种写法
:52-58   parseMode()：忽略大小写与连字符/下划线差异，非法值回退 null（继承父会话）
:60-68   parseInject()：**缺省或全非法时回退 DEFAULT_INJECT**，避免定义写错就丢掉全部上下文
:70-88   splitAndParseFrontmatter：YAML load 失败降级 emptyMap，不抛
```

`AgentDefinition.kt`（数据模型，关键字段）：
```
:9-38    enum InjectPart { BASE("base"), MAIN_RULES("mainRules"), SKILLS("skills"),
                           MEMORY("memory"), PROJECT_RULES("projectRules") }
:24-36   fromToken()：**宽松映射**，忽略大小写、连字符与下划线差异
                       "mainrules"→MAIN_RULES, "skill"/"skills"→SKILLS…
:56-98   AgentDefinition(name, description, providerId?, model?, reasoningEffort?,
                         mode?, allowedTools, disallowedTools, inject, prompt, filePath?)
:82      DEFAULT_INJECT = { BASE, SKILLS, MEMORY, PROJECT_RULES }
:86-108  filterToolNames(all)：
           **黑名单先生效，再与白名单取交集**；支持 `mcp__server__*` 通配
           **一次禁掉某个 MCP server 的全部工具**
:101-107 matches()：`p.endsWith("*")` → startsWith 前缀匹配
:110-115 NESTED_TOOL = "task"（子代理不可嵌套派发，工具集永远剔除）
         PARENT_MESSAGE_TOOL = "messageParent"（仅子代理可用）
```

子代理提示词组装：`SystemPromptProvider.buildForSubAgent()`（`:310-376`）
```
- InjectPart.MAIN_RULES → 拼 staticRuleSource（完整 00~70 基线）
- InjectPart.BASE       → 拼 subagent-base.md（去掉前导 <!-- --> 注释）
- 然后 append 定义正文 prompt（同样展开 {{AICODE_*}}）
- SKILLS / MEMORY / PROJECT_RULES 按需追加
- 最后 append subagent-context.md（带 {{AICODE_SUBAGENT_NAME}}）
- **不注入可用子代理清单**（子代理不能嵌套派发）
```

#### 3.2.3 技能系统：与子代理共享 frontmatter 约定

`Skill.kt`（33 行）、`SkillParser.kt`（70 行）：
```
SkillParser.kt:11-12   SKILL_FILE = "SKILL.md"，CLAUDE_FILE = "CLAUDE.md"
:24-28   **优先 SKILL.md，其次 CLAUDE.md**（兼容只用 CLAUDE.md 的技能），名称忽略大小写
:41-45   name 取 frontmatter，缺省回退到目录名
:50-58   required_toolsfrontmatter 字段
来源：GlobalDirectorySkillSource / ProjectDirectorySkillSource（项目级可 git 追踪）
工具：LoadSkillTool —— system prompt 里只注入**清单**（name: description），
      真正正文按需用 loadSkill 加载（省 token）
```

> **AgentKit 照搬度极高**：已有 `skill/`（SkillExecutor / SkillProtocol / SkillSecurity / SkillImportRepository）+ `assets/skills/{weather,wordcount}`。缺的就是：① 双来源（全局/项目）② **清单-正文分离**（我们现在大概是把正文全塞进 system）③ `required_tools` 声明。

#### 3.2.4 工具如何暴露给模型

`AgentTool.kt`（146 行，**整个体系的核心抽象**）：
```kotlin
abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolParameter>
    open val permissionPolicy = AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()
    open fun effectiveCapabilities(args): Set<ToolCapability> = capabilities   // 按参数动态降级
    abstract suspend fun execute(args): ToolResult
    open suspend fun executeWithContext(args, AgentContext): ToolResult         // 需要 sessionId 的工具走这条
    open fun buildPermissionRequest(...): PendingToolPermission                 // 自定义弹窗文案
    open fun toJsonSchema(): Map<String, Any>                                   // → OpenAI/Anthropic schema
}
```
- `ToolParameter(name, type, description, required, enum?, itemsSchema?)` —— `itemsSchema` 用于描述**对象数组**（如 `edits`），原样并入 JSON Schema `items`
- `ToolCapability` 12 个值：READ_WORKSPACE / WRITE_WORKSPACE / EXECUTE_COMMANDS / NETWORK_READ / NETWORK_WRITE / READ_AGENT_CONFIG / MODIFY_AGENT_CONFIG / MODIFY_CONTAINER_ENV / USER_INTERACTION / MODIFY_SESSION_STATE / MODIFY_TODO_STATE / EXTERNAL_TOOL
- `ToolResult` sealed：`Success(data, images)` / `Error(message, code)` / `Partial(data, message)`，`classDiscriminator = "status"`
- `StreamingAgentTool` 接口 + `ToolStreamEvent.{Progress, Completed}` —— **过程输出流式回 UI，但最终聚合结果才喂模型**

`ToolRegistry.kt`（**只有 41 行，极简**）：
```
:10     Collections.synchronizedMap(LinkedHashMap())  ← LinkedHashMap 保序，
        注释：**避免工具增删后前缀顺序漂移打断隐式前缀缓存**
:28-36  getAvailableTools()：PLAN 模式下仍返回全部工具定义
        （让 AI 知道这些工具存在，可在计划中引用），
        写操作由 ToolPermissionPolicyEngine 在**运行时拦截**返回 PLAN_MODE_REJECTED
        ← 这个设计很聪明：**不靠删 schema 来限制模式，而是运行时拦**，避免 plan↔auto 切换时
          system prompt 变更打断 KV Cache
```

MCP 接入：domain/mcp 下 19 个文件，`McpClient` / `McpManager` / `McpTool` / `StdioTransport` / `StreamableHttpTransport` / 完整 OAuth（PKCE + 动态注册 + Loopback 服务端 + WWW-Authenticate 解析）。`McpTool` **覆写 `toJsonSchema()` 直接透传服务端原始 schema**（`AgentTool.kt:126-128` 注释解释原因）。

#### 3.2.5 权限与安全

`ToolPermissionPolicyEngine.kt`（406 行），评估顺序写在头部注释（`:15-24`）：
```
Bash 类工具：
  1) 任一段命中 DENY 规则 → DENY（即便命令不可静态判定，可覆盖内置白名单）
  2) 命令不可静态判定（命令替换/分组/绝对路径重定向等）→ ASK，**不可记忆**
  3) 所有段命中内置安全白名单（BuiltInSafeCommands）→ ALLOW
  4) 所有段命中已记忆 ALLOW 规则 → ALLOW
  5) 否则 → ASK，**记忆前缀为「程序名」或「程序名+子命令」**
     ← git/npm 这类**子命令分发器**记 `git pull` 而非 `git`，使不同子命令各自独立授权
非 shell 的 ASK 工具按整工具匹配（pattern=`*`）
特殊：Shizuku 一律 ASK 且不可记忆（以 adb shell 身份操作宿主系统），AUTO 模式也不放行
      terminal 工具按 action 路由：start 走命令前缀，send/read 按整工具
      task 工具 read/list/send 自动放行，create/stop 走正常评估
      browser 工具区分只读/写 action
```
配套：`ShellCommandParser.kt`（223 行，静态判定命令是否可解析）、`BuiltInSafeCommands.kt`（54 行）、`PermissionRulesRepository.kt`（272 行，记忆规则持久化）。

### 3.3 终端执行

#### 3.3.1 自研 vs 复用

**AiCode 没有自研终端**，而是**复用 Termux 的两个模块**（JitPack）：
- `terminal-emulator`（15 个 **Java** 文件）：`TerminalEmulator`（VT100 状态机）、`TerminalSession`、`TerminalBuffer`、`TerminalRow`、`KeyHandler`、`WcWidth`、`JNI`、`SubprocessBackend`、`SessionBackend`、`TerminalColorScheme`、`TextStyle`…
- `terminal-view`（7 个 **Java** 文件）：`TerminalView`、`TerminalRenderer`、`GestureAndScaleRecognizer`、`textselection/{CursorController, TextSelectionCursorController, TextSelectionHandleView}`

> AgentKit **已经有自己等价的实现**：`terminal/TerminalEmulator.kt`（纯 Kotlin VT100 状态机）+ `TerminalJni.kt` + `TerminalSession.kt`。**这条不需要改，也不用引入 Termux 代码**。差距主要在渲染层（我们是 Compose 自绘 vs 他们 GPU 渲染的 View）。

#### 3.3.2 App 侧：`feature/terminal`（15 kt）

```
domain/TerminalSessionManager.kt      ★ 单例，拥有所有标签
domain/TerminalTab.kt                 标签模型（id/title/session/view/runState/isBackground/notifyOnExit）
domain/TerminalSessionProvider.kt     接口（给 AI 工具用），DelegatingTerminalSessionProvider 分发本地/远程
domain/RemoteTerminalSessionManager.kt + SshShellBackend.kt   远程 SSH 终端
domain/TerminalKeepaliveService.kt    前台服务保活（dataSync 类型）
domain/KeepaliveWorker.kt             WorkManager 兜底：周期检查存活并拉起
domain/font/TerminalFontManager.kt    字体管理
data/repository/TerminalSettingsRepository.kt
presentation/component/TerminalScreen.kt / TerminalClients.kt / TerminalSettingsSheet.kt
```

#### 3.3.3 PTY 与 proot 的结合（**最有技术含量的一段**）

`TerminalSessionManager.kt:400-439 buildSession()`：
```kotlin
val invocation = containerEngine.buildProotInvocation(shellCommand, workspace)
val client = AppTerminalSessionClient(viewProvider = { _tabs.firstOrNull { it.session === session }?.view },
                                      onFinished = { ... TabFinishedEvent ... })
session = TerminalSession(
    invocation.executable,                    // ← argv[0]，即 proot 二进制本身
    appContext.filesDir.absolutePath,          // cwd
    invocation.argv.toTypedArray(),            // ← **完整含 argv[0] 的 argv**
    invocation.ptyEnvArray,                    // ← **System.getenv() + env 合并**
    TRANSCRIPT_ROWS, client
)
```

**两个致命细节，写在 `LinuxContainerEngine.kt:46-71` 的注释里：**

1. **`argv[0]` 必须是 proot 二进制**（`:46-52`）
   > Termux `TerminalSession` 的 cmd 仅用于 `execvp` 查找可执行文件，真正的 argv 由它的 args 参数原样构成（`native execvp(cmd, argv)`，**不会自动补 argv[0]**），故 args 必须也是「含 argv[0]=proot 二进制」的完整 argv，否则**选项整体错位一位、proot 会把 rootfs 路径误当客户机程序**（"is not a regular file"）。

2. **环境必须合并父进程环境**（`:60-71`）
   > `ProcessBuilder.environment()` 初始即为父进程副本；但 `TerminalSession` 接收的是「完整、不继承父进程」的环境数组，**只喂自定义 env 会让 proot 因缺 ANDROID_ROOT/LD_LIBRARY_PATH 等而 exec 失败、瞬间退出**（终端表现为「会话已结束」而日志无其他报错）。故 `ptyEnvArray = (System.getenv() + env).map { "k=v" }`。

#### 3.3.4 与 Agent 的联动：`terminal` 工具 + notify 机制

`BackgroundTerminalTools.kt`（511 行）→ `TerminalSessionTool`，`name = "terminal"`：
- **一个工具 + `action` 参数**取代原三个工具（`:38-44` 注释：原 `run_background_command` / `send_terminal_input` / `read_terminal_output`）
- actions：`start`（后台启动，立即返回 tab_id，`START_CAPTURE_MS = 5s` 初始输出捕获）/ `send` / `key`（`SUPPORTED_KEYS` 13 个：ctrl+c/d/z/l/u/w、esc、tab、enter、方向键）/ `read`（省略 tab_id 则**列出所有标签**）/ `close`
- **`notify` 参数**（`:100`）：命令结束后系统**主动唤醒 AI** 并送达退出码与末尾输出，**不要轮询或 sleep 等待**
- `effectiveCapabilities`（`:83-88`）：`read` action 降级为 `READ_WORKSPACE`（PLAN 模式可用），其余保持 `EXECUTE_COMMANDS`
- `asPlainString()`（`:29-33`）教训：**不能用 `toString().removeSurrounding("\"")`** —— `toString()` 返回的是 JSON 序列化形式，`echo "*.vue"` 会被变成 `echo \"*.vue\"`；必须用 `jsonPrimitive.contentOrNull`

唤醒链路：`TerminalSessionManager.kt:414-425` 会话结束 → 若 `notifyOnExit && !finishedNotified` → `_tabFinishedEvents.tryEmit(TabFinishedEvent(tabId, title, command, exitCode, sourceSessionId, tailOutput = getTabOutput(id)?.takeTailLines(TAIL_LINES)))` → `AgentEventInjector` 注入到 Agent 下一轮。

### 3.4 rootfs / 容器

#### 3.4.1 打包：flavor 拆分 + jniLibs（**关键工程决策**）

`app/build.gradle.kts`：
```
:131-142   flavorDimensions += "container"
           universal (arm64-v8a + x86_64) / armsolo (arm64-v8a) / x86solo (x86_64)
:145-162   sourceSets 共享镜像（_armAssets / _x86Assets 各只有一份物理副本）
           _armJniLibs / _x86JniLibs → jniLibs.srcDirs
:145-150   **proot 全套（proot + loader + loader32 + libtalloc + libandroid-shmem）
           走 jniLibs 而非 assets** —— 安装器只把 APK 内 lib/<abi>/lib*.so 解压到
           nativeLibraryDir，而那是 App 目录里**唯一允许 execve 的位置**
           （SELinux 标签 apk_data_file）。rootfs 仍在 assets → filesDir，
           因为客户机二进制**不由内核 execve**，而是 proot loader 用 mmap(PROT_EXEC)
           映射进内存，不受 W^X 约束。
           → 故命名必须是 lib*.so
:226       useLegacyPackaging = true  ← 必须！默认 false 时 .so 不解压、直接从 APK 映射，
           nativeLibraryDir 会是空目录，execve libproot.so 得到 ENOENT
:229-236   keepDebugSymbols：proot 全套是外部预编译产物，不得被 NDK strip 后处理
```

> ⚠️ **这对 AgentKit 是一个待确认的风险点**。AgentKit 现在 `assets/bin/arm64-v8a/proot` → 提取到 `filesDir/bin/proot` + `chmod 0700`（`core/container/ProotRunner.kt:27,30,52`）。AiCode 的注释明确说 filesDir **不是**允许 execve 的位置。鉴于 AgentKit `targetSdk` 未在给定信息中锁定，建议**优先验证**这一点（见 §5 P0）。

#### 3.4.2 版本与幂等安装

```
ContainerInstaller.kt:233   INSTALL_VERSION = "alpine-3.21.3-v6"（含脚本后缀，改 rootfs 就 +1）
ContainerInstaller.kt:259   ASSET_ROOTFS = "$ASSET_DIR/alpine-rootfs.bin"
ContainerInstaller.kt:318-323  marker 文件比对 → 已是当前版本则跳过解压
ContainerInstaller.kt:341-353  实际上}=\- 解压写 marker
LinuxContainerEngine.kt:162-170  PROVISION_VERSION = "provision-script-v9"
         **独立于 INSTALL_VERSION**：rootfs 版本升级会删 rootfs，但脚本版本独立演进
LinuxContainerEngine.kt:483     marker 存在且内容 == PROVISION_VERSION
```

rootfs 解压：`commons-compress` 的 `TarArchiveInputStream` + `GZIPInputStream` / `XZCompressorInputStream`（注释：正确处理 symlink/hardlink/权限位）。支持 tar.gz / tar.xz。

#### 3.4.3 proot 调用构造（核心）

`LinuxContainerEngine.kt:718-764 buildBaseProotArgv()`：
```
prootBin
  -r <rootfs>
  -b /dev  -b /proc  -b /sys  -b /system      ← /system 让宿主动态库可用
  -0                                           ← 伪 root（apk 等需要）
  -b <projectPath>:/root/workspace
  -w /root/workspace                           ← 工作目录
  -b <aicodeDir>:/root/.aicode                 ← AI 配置目录（skills/ mcp.json prompts/）
  [-b ...profile.extraBindings]
  [...profile.extraArgs]
```

`LinuxContainerEngine.kt:767-800+ buildContainerEnv()`：
```
PROOT_TMP_DIR        = <rootfs>/tmp（Android 没有 /tmp；proot 要求已存在）
PROOT_LOADER         = <nativeLibDir>/libproot-loader.so
PROOT_LOADER_32      = <nativeLibDir>/libproot-loader32.so
LD_LIBRARY_PATH      = <nativeLibDir>:/system/lib64:/system/lib
PATH                 = /root/.aicode/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
HOME                 = /root
TMPDIR               = /tmp        ← 覆盖宿主 TMPDIR（指向 App cache，容器内 /data 未挂载）
GIT_CONFIG_GLOBAL    = /root/.aicode/.gitconfig
```

**一个反直觉的注释（`:776-781`）**：
> **刻意不设 `PROOT_NO_SECCOMP`** —— 旧 proot 5.1.0 的 seccomp 过滤表没有 `statx`，Node 用 statx 解析模块路径会拿到未翻译的 `~/workspace/xxx` → ENOENT「Cannot find module」。Termux proot (5.1.107.x) 已包含 statx。强制全量 ptrace 反而在本设备触发过 `ptrace(PEEKDATA) I/O error`。

这条对我们**可能是踩过的或即将踩的坑**，值得对照 AgentKit 的 proot 版本确认。

#### 3.4.4 资产提取器清单（启动时执行）

`ContainerInstaller.kt` companion object 里的静态方法，**全部不含 proot/rootfs 解压**：

| 方法 | 行号 | 目标 | 策略 |
|---|---|---|---|
| `extractDocs` | `:44` | `~/.aicode/docs/` | **整目录先删后建**（避免旧版残留被 AI 读到） |
| `extractPrompts` | `:63` | `~/.aicode/prompts/` | 每次全量覆盖（**不触碰 prompts.custom/**） |
| `extractAssetsRecursive` | `:74` | — | 靠 `assets.open` 对目录抛 IOException 递归 |
| `extractAgents` | `:95` | `~/.aicode/agents/` | **文件已存在则不覆盖**（保留用户修改） |
| `extractScripts` | `:117` | `~/.aicode/scripts/` | 同上 + `setExecutable(true)` |
| `extractCredentialHelper` | `:147` | `~/.aicode/git-credential-aicode` | 每次覆盖 + 可执行位 |
| `extractProvisionScript` | `:172` | `~/.aicode/provision.sh` | 每次覆盖 + 可执行位 |
| `extractEnvTool` | `:195` | `~/.aicode/{bin,lib}` | 每次覆盖 |

> **覆盖策略的差异是有意的**：提示词/文档要跟随版本更新（覆盖），用户可编辑的（agents/scripts）保护一次（不覆盖）。这个「三层策略」值得直接照抄。

#### 3.4.5 `git-credential-aicode`：文件 IPC 回源 App

`assets/aicode/git-credential-aicode`（4204 字节 shell）：
- 凭据真源 `~/.aicode/git-credentials`，内容是「**store 明文 base64 后整体反转**」——注释坦白是**混淆而非加密**（「可挡住随手 cat、grep 扫描与 AI 读取进上下文」），安全边界定位明确
- 未命中凭据时：**本地** → 写 `cred-req-<id>` 文件 → App 侧 `FileObserver` 监听 `filesDir/aicode` 的创建事件 → **弹窗让用户填** → 轮询 `cred-resp-<id>`
- **远程**（`AICODE_CRED_NO_PROMPT` 非空，服务器上没 App）→ 直接空退出
- **依赖 PRoot `-b` 绑定**：容器内 `/root/.aicode` ⇔ 宿主 `filesDir/aicode` **同一 inode**，所以 FileObserver 才能监听到
- shebang 用 `/bin/sh`（busybox ash 永在，provisioning 失败时也得能跑）
- **仅 HTTPS 生效**：SSH 走 ssh-agent 不触发 credential helper

> 这是一个非常巧妙的「容器内进程反向请求宿主 UI」的模式。**比我们如果要做 git 推拉认证时的任何方案都简单**，且零依赖。可用在：容器内需要宿主能力的任何场景（不只是凭据）。

### 3.5 模型配置

`AIProviderConfig.kt`（settings/domain/model）：

**三种 ProviderType**：`OPENAI` / `ANTHROPIC` / `GEMINI`，适配器在 `domain/provider/{OpenAIAdapter, AnthropicAdapter, GeminiAdapter}.kt`。

亮点字段：
```
:9-14     multiKeyEnabled + apiKeys: List<ProviderKey>  ← **多 Key 轮询/故障转移**
:12       keyRotationStrategy: SEQUENTIAL（失败才切）/ 轮询（新会话轮流起步）
:15-16    keyCooldownMinutes：切走的 Key 冷却多久重新纳入候选
:19       keySwitchStatusCodes：命中即触发切换的 HTTP 状态码
:29       useResponseApi：OpenAI Responses API 开关
:31       anthropicCacheBreakpoints：**显式缓存断点**（cache_control），默认开
:33       openaiChatCacheKey：Chat Completion 路径发 prompt_cache_key（shard 路由）
:36-39    dashboardScriptPath + dashboardRefreshInterval  ← **自定义余额/用量面板（DIY 脚本）**
:43-45    **customHeaders: Map<String, String>（请求头名 -> 值）**，值支持占位符
          `{{SESSION_ID}}` / `{{API_KEY}}`，发送前替换
:47       sortOrder：提供商排序
:48-53    每提供商独立代理（proxyEnabled/type/host/port/user/pass）
:56-59    scriptParams：执行面板脚本时注入为 `AICODE_KEY_<KEY>` 环境变量
:62-64    effectiveModel / :67-74 effectiveApiKeys
:96-101   ProviderKey(value, weight)：**加权轮询**，>0 参与调度
:105-124  **sanitized()**：读与写两侧各清洗一次
          - apiKey 去**所有空白**（全部 strip，"_{}| " 去除后再移除空格，注释：粘贴带来的
            换行会被 OkHttp 拒绝 "Unexpected char 0x0a in Authorization value"）
          - name/model 去换行 tab 再 trim（允许内部空格）
          → **AgentKit 的 `core/ConfigSanitizer.kt` 已经在做同样的事，思路一致 ✅**
```

**模型数据来源三处**：
1. `assets/providers.json`（内置兜底快照，**随发版固化**）—— 12+ 家预设（openai/anthropic/deepseek/google/xai/alibaba…），含 `id/name/type/baseUrl/isRecommended/badge/websiteUrl/tags`
2. `data/providers.json`（**仓库动态数据源**，App 启动时拉取）—— CLAUDE.md:77 明确：「新增或调整内置 AI 供应商，**只改 data/providers.json**」
3. `assets/api.official.json`（109 KB，12 家官方 provider 的**模型 + 单价**）—— 由 `scripts/update-models-dev-assets.py` 从 models.dev 刷新，**只在打 Tag 发版前手动执行**（CLAUDE.md:148）

> AgentKit 已有 `ModelListFetcher` + `ConfigSanitizer` + 三云端 Provider + local-llama，**这一块是最接近的**，差距在：多 Key 轮换加权、每 provider 独立代理、自定义 header 占位符、Models.dev 式单价表。

### 3.6 其他

#### 3.6.1 Checkpoint

`CheckpointManager.kt`（255 行）：
```
:23-29    CheckpointRestoreResult(restored: Int, skipped: List<String>)
          注释：**调用方必须把 skipped 展示给用户——静默跳过等于把数据丢失换成了无声的失败**
:37-40    baseDir = <filesDir>/checkpoints/<sessionId>/<checkpointId>/
:42-45    activeCheckpointIds = ConcurrentHashMap<sessionId, checkpointId>
          ← **必须按会话隔离**：多会话（含父会话与子代理）并行时，单一字段会被后创建的
            会话覆盖，导致文件快照挂到别的会话名下，「那个会话一撤销就会把本会话刚写好的
            文件还原/删除」
:53-71    createCheckpoint(sessionId, userMessageId, prompt)
          ← **在用户发送新消息时调用**（每轮一个检查点）
:82-96    beforeFileModified(sessionId, filePath)
          ← 工具（editFile/writeFile）改文件前调用；**同一 checkpoint 内对同一文件只保留
            最原始的一次快照**
```

对比 AgentKit `CheckpointStore.kt`：**整目录 `copyRecursively` 快照**，且有fileCount/sizeBytes 统计。差异：
- AiCode 是**「懒快照」**（只快照将被修改的文件，且只留最原始一次）→ 空间/时间开销小得多
- AiCode 是**按 sessionId 隔离**的活动检查点 → 支持并行会话
- AiCode 回滚有 `skipped` 清单（因为 AI 可能在快照后被别处改过）

#### 3.6.2 Git

`GitRepository.kt`（12 个文件）：
- **直接复用 `CommandEngine.runCommandSync`（cwd = 工作区），不经 agent 工具链 / 权限引擎** —— 注释：「Git 页是用户主动操作」
- 所有命令带 **`-c core.quotepath=false`** —— 否则 git 把非 ASCII 转成八进制转义（中文乱码）
- 只读命令（`git(...)`）靠输出解析容错；**写命令（commit/push/merge）据退出码判成败**，非零抛 `GitCommandFailureException` 携带 git 输出，UI 如实显示失败原因
- `GRAPH_PAGE_SIZE = 100`（分批拉提交图）
- `MAX_DIFF_FILE_BYTES = 2 MB`
- 参数经 `shellQuote` 逐参数单引号转义

#### 3.6.3 构建/打包（APK）

**AiCode 不在 App 内直接构建 APK**，而是：
- 容器内装 Android SDK（`lib/android-sdk.sh` + `scenarios/android.sh`），用户在终端跑 `gradlew assembleDebug`
- App 侧只做**安装**（`core/util/ApkInstaller.kt`）：`canRequestPackageInstalls` 判定 → `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 引导 → FileProvider 授权 URI → 拉起系统安装器。
  **注释强调必带 `FLAG_ACTIVITY_NEW_TASK`**（ViewModel 的 ApplicationContext 起 Activity 会直接抛异常）
- `syncAiDocs` Gradle task（`app/build.gradle.kts:76-88`）：把 `docs-site/docs` 复制进 `assets/docs`，保证「站上看到的」与「AI 在容器里读到的」永远一致。

> **AgentKit 已有 `core/build/`（BuildEngine / GitHubBuildService —— 推 GitHub → CI → 拉回 → 安装），这条路比 AiCode 更"云原生"，应保持并强化，不要退回容器内编译**（容器内 Gradle 在手机上编译极慢且耗电）。

---

## ④ 「它怎么做 → 我们怎么适配」对照表

> 通则：**只读思路，净室重写**。AiCode 是 GPL-3.0；下列所有落地方案都必须在不看其源码的情况下，
> 由**理解了原理的工程师**从零实现。本表的价值是「指出坑在哪、为什么要这样做」，不是提供代码。

### A. 代码能力

| # | AiCode 做法（含位置） | AgentKit 现状 | 我们怎么适配 | 依赖 | 优先级 |
|---|---|---|---|---|---|
| A1 | **TextMate 定向高亮**：`assets/textmate/languages.json` + 各语言 grammar + `dark_plus/light_plus` 主题；`ensureInitialized` 必须在 IO 线程，**每分析 1000 行就推一次着色**（`TextMateSetup.kt:15,33`） | `ui/EditorScreen.kt`（369 行）用 `BasicTextField` 自绘，**无语法高亮** | 净室写一个 **`core/editor/SimpleHighlighter.kt`**：不做完整 TextMate（需要 oniguruma），改做**正则 + 状态机的极简着色器**（~400 行）。覆盖 10 种高频语言（kt/java/py/js/ts/json/yaml/sh/md/html），词法用「关键词表 + 字符串/注释转义状态」。**必须异步分块**：在 `Dispatchers.Default` 按 500 行分块产出 `AnnotatedString`，用 `LaunchedEffect` 增量更新 UI | **零依赖** ✅ | **P0** |
| A2 | 编辑器用 sora-editor（`app/build.gradle.kts:382-386`） | `BasicTextField` + 自己写的行号 | **保留 Compose 自绘**，只补：① 行号 gutter ② undo/redo（自己维护 `undoStack: ArrayDeque<TextSnapshot>`）③ dirty 判定用 baseline 比对（`CodeEditorScreen.kt:114` 的思路）④ 保存前用下面 D2 的 STALE_FILE 校验 | 零依赖 | **P0** |
| A3 | **不在 APK 里做 UI 文件树**；靠 `list` 工具 ls 输出 + `@` 提及（`rg --files`，遵守 .gitignore）+ `search`（rg） | 几乎没有文件探索能力（`tools/BuiltInTools.kt` 只有 http_request/read_file/write_file/clipboard/send_notification） | ① 新增 `list_dir` 工具（宿主 `File.walk` 有限深度 + `.gitignore` 简易匹配，`MAX_ENTRIES=500` 保护）② 新增 `search_code` 工具（容器内 rg；rg 缺失时降级为 Kotlin 实现的子串搜索）③ **新增 `@` 文件提及**：`rg --files` 拉清单 + 内存模糊匹配，清单每次打开刷新 | 零依赖（rg 走已有容器） | **P0** |
| A4 | **`editFile` 精确字符串匹配**（而非行号）——注释写死理由：行号连续编辑会漂移（`EditFileTool.kt:37-56`） | `write_file` 整文件覆盖（197 行 BuiltInTools） | 新增 `edit_file` 工具：`old_string`/`new_string`/`replace_all` + `edits` 数组**整批原子**（先在内存里全算完，任一不匹配则整体不写盘）。一只语义覆盖替换/删除/插入 | 零依赖 | **P0** |
| A5 | **双层并发保护**：`WorkspaceFileLock`（路径互斥）+ `FileVersionRegistry`（写前内容指纹比对，不一致返 `STALE_FILE`）。**校验在锁内、以锁内重读为准**（`EditFileTool.kt:60-70`） | 无 | 净室写 `core/workspace/FileVersionRegistry.kt`（`ConcurrentHashMap<path, sha256>`）+ `WorkspaceFileLock.kt`（`ConcurrentHashMap<path, Mutex>`）。**关键**：`read_file` 必须登记指纹，这是拿到保护的前提；错误码 `STALE_FILE`，错误消息明确告诉模型「先重新 read_file」 | 零依赖 | **P0** |
| A6 | **Checkpoint 懒快照**：只快照将被修改的文件、只留最原始一次、按 sessionId 隔离；回滚返回 `skipped` 清单（`CheckpointManager.kt:42-96`） | `CheckpointStore.kt` 整目录 `copyRecursively` | 改造为：**在 `write_file`/`edit_file` 执行前调 `CheckpointStore.snapshotFile(path)`**（同一轮次同文件只快照一次）；回滚时逐个比对 `lastModified + size`，变更则跳过并收集清单返给 UI | 零依赖 | **P1** |
| A7 | **LineDiff LCS** 行级 diff，统一格式「首列标记位」避免代码以 +/- 开头时歧义（`LineDiff.kt:11-58`） | 无 diff 展示 | 净室写 `core/workspace/LineDiff.kt`（~70 行 LCS）。**必须加保护**：行数 > 4000 时退化为「按首尾各 50 行」，避免 `(n+1)×(m+1)` IntArray OOM。聊天里把 `edit_file` 的结果渲染成 +/- 行（`ToolMessageComponents` 式卡片） | 零依赖 | **P1** |
| A8 | 聊天 Markdown 用 `markdown-renderer-m3`；编辑器预览用 WebView + marked.js + highlight.js + DOMPurify（`MarkdownPreviewWebView.kt:47-56`，`allowFileAccess=false`） | ChatScreen 的 Markdown 渲染情况待验证（**待验证**） | **不引外部库**。两条路：① 聊天维持现状/自绘增强（代码块用 A1 的同一套 Highlighter 复用）② 预览页用已有 `WebView` + assets 里放 marked/highlight（MIT/BSD 许可，安全）。**必须照抄三条安全配置：`allowFileAccess=false` + DOMPurify + 外部链接不跳转** | marked/highlight 是 assets 非 Gradle 依赖 ✅ | **P2** |

### B. 提示词 / 场景体系

| # | AiCode 做法（含位置） | AgentKit 现状 | 我们怎么适配 | 依赖 | 优先级 |
|---|---|---|---|---|---|
| B1 | **编号片段 + 四层优先级**：`assets/prompts/<NN>-<名>.md`（编号=身份=顺序），PROJECT > GLOBAL > LOCAL > BUILTIN（`PromptFragmentCatalog.kt:12-13, 247-254`） | 提示词硬编码在 `AgentLoop.kt` | **这是最值得抄的一件事**。新增 `assets/prompts/` + `core/prompt/{PromptCatalog,PromptResolver,SystemPromptBuilder}.kt`：① 启动时把 assets/prompts 释放到 `filesDir/prompts`（每次覆盖）② 目录扫描用两位数字前缀 Regex ③ 用户覆盖写在 `filesDir/prompts.custom/`（**不删**）④ 先做「项目/全局」两层即可，LOCAL/BUILTIN 可合并 | 零依赖（自己写 ~150 行）✅ | **P0** |
| B2 | **`{{VAR}}` 变量注入**：10 个 `{{AICODE_*}}` 占位符，动态内容全部走到末尾的 `99-runtime-context.md`；注释明说这是为了 **KV Cache**（`SystemPromptProvider.kt:22-31, 379-405`） | 无 | 照搬思路：**稳定内容在前、易变内容在最后**。至少实现 4 个变量：`{{SKILLS}}` `{{MEMORY}}` `{{WORKSPACE}}` `{{DATE}}`。技能/记忆清单的注入顺序放在 prompt 最后 | 零依赖 | **P0** |
| B3 | **会话级 Source 缓存** `(sessionId, projectRoot)`，空内容用 `""` 占位区分「未缓存」（`SystemPromptProvider.kt:53-78`） | 无 | 照搬：每次构造 system prompt 时若重扫磁盘会导致 prompt 抖动、打断云端 KV Cache。缓存 key 用 `(sessionId, projectRoot)`，`SOURCE_CACHE_LIMIT=32` 超了整体清 | 零依赖 | **P1** |
| B4 | **场景库**：`lib/scenarios/<id>.sh` 只暴露 `scenario_name_<id>` / `install_scenario_<id>`，由 `SCENARIO_IDS` 列表驱动菜单 + 函数前缀分发（`env-common.sh:613, 622-634`） | `assets/containers/alpine.tar`，容器内有什么全靠手动 | **这是「能写代码/写游戏/做安装包」的前提**。新增 `assets/env/scenarios/{base,node,python,java,go,rust,php}.sh` + `env-common.sh`（包管理器探测 apk/apt/dnf/pacman、国内镜像源替换、运行时版本探测）。每个场景只写两个 shell 函数。**同步新增 ContainerScreen 里的「环境安装」UI**，1 键装 node/python/java | 零依赖（纯 shell asset）✅ | **P0** |
| B5 | **`aicode` 命令 = `provision.sh --env`**：同一份脚本，两个入口（首次进终端自动弹 / AI 或用户随时手动调）（`provision.sh:1-13`, `bin/aicode`） | 无 | 照搬：**一个 `provision.sh` + 一个 `bin/agentkit` 入口**。脚本每改动一次就要 `PROVISION_VERSION += 1` 才能触发存量设备重跑 —— 这个「版本号常量 + marker 文件比对」机制务必照做（`LinuxContainerEngine.kt:162-170`） | 零依赖 | **P1** |
| B6 | **脚本版本与镜像版本解耦**：`PROVISION_VERSION` 独立于 rootfs `INSTALL_VERSION`（`LinuxContainerEngine.kt:162-170`） | `RootfsManager.kt` 单一版本号 | 拆成两个常量：镜像版本改了才重解压 rootfs，脚本改了只重跑 provision。避免「改一行 shell 就要重解压 40MB rootfs」 | 零依赖 | **P2** |
| B7 | **内置子代理 `agents/explore.md`**：YAML frontmatter（name/description/tools/inject）+ 正文；`inject` 控制基线注入、**工具白/黑名单（含 `mcp__x__*` 通配）**、可覆写 provider/model/mode（`AgentDefinition.kt:56-115`） | 无子代理 | **P1 阶段先做只读版**：新增 `assets/agents/explore.md`，只用 `tools/inject/description` 三个字段；解析器容错（YAML 失败降级 emptyMap、非法值回退默认）。**工具的 capability/黑白名单裁剪留到 P2** | YAML 解析要注意：AgentKit 无 snakeyaml。用 json 或自己手写一个「只支持 `key: value` 与 `key: [a, b]`」的极简 frontmatter 解析器（~80 行） | **P1** |
| B8 | **技能三层来源**（全局/项目）+ **清单与正文分离**（system 只注入 `name: description`，正文靠 `loadSkill` 按需加载）+ `required_tools` | `skill/` 四件套 + `assets/skills/{weather,wordcount}` | ① 拆分：system 注入只放清单 ② 新增 `load_skill` 工具 ③ 支持项目级 `.agentkit/skills/`（可 git 追踪）④ frontmatter 里的 `tools` 字段用于跨技能的工具可用性校验 | 已有 skill 体系，改造量小 | **P1** |
| B9 | **项目规则自动读取**：`AGENTS.md` 优先、`CLAUDE.md` 兜底，全局+工作区两级，按 (path+mtime) 签名缓存，**32K 字符截断**（`SystemPromptProvider.kt:127-172`） | 无 | 照搬，但改成 `AGENTS.md` 优先、`.cursorrules` 兜底（更贴近我们的用户群）。**截断上限建议 16K**（手机上下文宝贵） | 零依赖 | **P1** |
| B10 | **模式提醒不进 system**：`plan-mode.md` / `auto-mode.md` **随用户消息注入**（文件头注释明写「随用户消息注入，不进 system」） | 无模式概念 | 这个技巧很省 token 且**避免切模式时 system prompt 变更打断 KV Cache**。若后续加「审批模式」，务必沿用此方式 | 零依赖 | **P2** |

### C. 终端联动

| # | AiCode 做法（含位置） | AgentKit 现状 | 我们怎么适配 | 依赖 | 优先级 |
|---|---|---|---|---|---|
| C1 | **`Bash`（一次性）vs `terminal`（常驻）双通道**，提示词里明写分工（`prompts/60-tools-and-paths.md`） | 只有一个同步执行路径 | 新增两个工具：① `bash`（同步，默认 120s 超时）② `terminal`（`action=start/send/key/read/close`）。**提示词必须明确写「不要轮询或 sleep 等待」** | 零依赖 | **P0** |
| C2 | **`notify=true` 反转轮询**：后台命令跑完系统推事件唤醒 AI（`BackgroundTerminalTools.kt:100`；`TerminalSessionManager.kt:414-425` 发 `TabFinishedEvent`） | `AgentExecutor` 同步盲等 | **体验分水岭**。改造 `core/AgentLoop.kt`：会话结束回调查 `notifyOnExit` → 构造 notification 消息 → 走 `AgentEventInjector` 式注入触发下一轮 LLM 调用。`tailOutput` 取末尾 ~40 行即可 | 零依赖 | **P0** |
| C3 | **`BoundedOutput` 限幅**：超大输出只保留开头+结尾，喂回模型与展示给用户的保持一致 | 待验证 | 写 `core/util/BoundedOutput.kt`（~60 行）：环形缓冲 + head/tail 双窗口。同时对「工具输出落盘 + 只回填 preview + `output_path` 供分段读」做配套（AiCode 用 `~/.aicode/tool-output/`） | 零依赖 | **P1** |
| C4 | **`argv[0]` 必须是 proot 二进制本身**，否则选项错位一位（`LinuxContainerEngine.kt:46-52`） | `ProotRunner.buildArgs()` 返回「不含 proot 自身的参数列表」（`:64-73`） | **提前对齐**：改造 `buildArgs()` → `buildInvocation()` 返回 `{ executable, argv（含 argv[0]）, env }`。现在改成本几乎为零；等接了 PTY 再改会连带一堆调用点 | 零依赖 | **P0**（低成本高价值） |
| C5 | **PTY 环境必须合并 `System.getenv()`**，否则 proot exec 失败且日志无报错（`LinuxContainerEngine.kt:60-71`） | 尚未接 PTY | 落到 C4 的同一个 `buildInvocation()` 里，提供两个出口：`envMap`（ProcessBuilder）与 `envArray`（PTY，含父进程环境）。**这个坑如果不预知，排查要花几天** | 零依赖 | **P1** |
| C6 | `terminal` 工具用**一个工具 + action 参数**取代三个（`BackgroundTerminalTools.kt:38-44`） | — | 照搬：少两个工具 = 少两份 schema = 少 token 占用 | — | **P1** |
| C7 | `effectiveCapabilities(args)`：按参数**动态降级** capability（`read` → READ_WORKSPACE，PLAN 下放行） | 无 capability 概念 | 给 `AgentTool` 加 `capabilities: Set<ToolCapability>` + `effectiveCapabilities(args)`。这是「计划模式下只拦写、放行读」的干净实现 | 零依赖 | **P2** |
| C8 | **PLAN 模式下仍返回全部工具 schema**，写操作在**运行时拦截**（`ToolRegistry.kt:28-36`） | — | 照搬：比「动态删 tool schema」更好，因为切换模式时 system prompt 不变，不打断 KV Cache | — | **P2** |

### D. 容器 / 安全

| # | AiCode 做法（含位置） | AgentKit 现状 | 我们怎么适配 | 依赖 | 优先级 |
|---|---|---|---|---|---|
| D1 | **proot 走 jniLibs → nativeLibraryDir**（唯一允许 execve 的 SELinux 位置），`.so` 必须 `useLegacyPackaging=true` 才会解压（`app/build.gradle.kts:145-150, 226`） | `assets/bin/arm64-v8a/proot` → 提取到 `filesDir/bin/proot` + `chmod 0700`（`ProotRunner.kt:27,30,52`） | **最高优先级的待验证项**。必须有真机验证：Android 10+ 上 `filesDir` 执行 proot 是否被 W^X/SELinux 拦。若拦，改造 proot 为 `libproot.so` 放 `jniLibs/`，运行时从 `applicationInfo.nativeLibraryDir` 取。**改之前先在 targetSdk 与 minSdk 上确认我们的取值**（AiCode 为绕过这问题把 targetSdk 锁 28，代价是上不了 Google Play） | 需真机验证 | **P0** |
| D2 | proot 环境变量：`PROOT_TMP_DIR` / `PROOT_LOADER(_32)` / `LD_LIBRARY_PATH` / `PATH` 含 `/root/.aicode/bin` / `TMPDIR=/tmp` / `GIT_CONFIG_GLOBAL`（`LinuxContainerEngine.kt:767-800`） | `ProotRunner.guestEnv()`（91 行文件） | 逐项比对是否有缺漏，特别是 **① `PROOT_TMP_DIR`（Android 无 /tmp）② `TMPDIR` 覆盖宿主值（宿主 TMPDIR 指向 App cache，容器内 /data 未挂载 → mktemp/dpkg 失败）③ PATH 要显式列出 `/usr/local/bin`**（非登录 shell 不加载 /etc/profile） | 零依赖 | **P0** |
| D3 | **刻意不设 `PROOT_NO_SECCOMP`**，因 Termux proot 5.1.107.x 的 seccomp 表已含 statx；旧版会导致 Node ENOENT / 强制 ptrace 会 `PEEKDATA I/O error`（`LinuxContainerEngine.kt:776-781`） | 未处理 | 确认 AgentKit 打包的 proot 版本。若 < 5.1.107（或不知道版本），**在 Node 相关场景验证 `#include <asm/unistd>`/`statx` 的行为**。这是「AI 写的 node 游戏跑不起来」的潜在根因 | 待验证 proot 版本 | **P1** |
| D4 | **`git-credential-aicode` 文件 IPC**：容器内写 `cred-req-<id>` → 宿主 FileObserver 监听 → 弹窗 → 轮询 `cred-resp-<id>`；依赖 `-b` 绑定让两端同 inode | 无 | **强烈推荐照搬思路**（自己写 shell）。这是「AI 推 GitHub / 拉私有仓库」闭环的最短路径，且零依赖。同时学它的**凭据混淆**：明文 → base64 → 整体反转，注释坦白「是混淆而非加密」，目的是**挡住 AI 把凭据读进上下文** —— 这个安全意识值得强调 | 零依赖 | **P1** |
| D5 | **资产提取三策略**：提示词/文档/脚本每次覆盖；agents/scripts **已存在则不覆盖**（保护用户修改）；文档**先整目录删后建**（防旧版残留被 AI 读到）（`ContainerInstaller.kt:44,63,95,117`） | `RootfsManager` 单一策略 | 照搬三层策略。特别是「agent/skill/script 用户可能已改」与「提示词必须跟随版本更新」的区分 | 零依赖 | **P1** |
| D6 | **Git 走 sandbox 内核 careful**: 所有 git 命令带 `-c core.quotepath=false`（否则中文路径八进制转义）；**写子命令据退出码判成败**并如实显示 git 原文（`GitRepository.kt:29-31, 60-70`） | `core/build/GitHubBuildService` | 每一行 git 调用都加上 `-c core.quotepath=false`；写命令失败时**把 git 原始 stderr 返给用户和模型**，不要吞掉 | 零依赖 | **P1** |
| D7 | **`ToolPermissionPolicyEngine` 五步评估**；记忆粒度是「**程序名+子命令**」（`git pull` 而非 `git`）；命令不可静态判定则 ASK 且禁「始终允许」（`:15-24`） | 有 `SkillSecurity` + 审批（`L10/L11`） | 借鉴**记忆粒度**设计：现在是整工具粒度还是命令粒度？建议升级到「git 命令按子命令记忆」 + 「含命令替换/子 shell 的一律不可记忆」 | 零依赖 | **P2** |
| D8 | **`ApkInstaller`**：`canRequestPackageInstalls` → 引导授权 → FileProvider URI → `FLAG_ACTIVITY_NEW_TASK` 拉起安装器（`core/util/ApkInstaller.kt:14-60`） | `core/build/BuildEngine` 已闭环 | 确认是否已有 `canRequestPackageInstalls` 预检。**这行没有会导致「点了安装没反应」** | — | **P2** |

### E. 模型配置

| # | AiCode 做法（含位置） | AgentKit 现状 | 我们怎么适配 | 依赖 | 优先级 |
|---|---|---|---|---|---|
| E1 | **多 Key 加权轮询 + 冷却 + 命中状态码即切**（`AIProviderConfig.kt:9-19, 96-101`） | `ConfigStore.kt` 单 Key | 我们 AIR 面向的模式是在国内多 Key 薅免费额度的用户，价值高。至少做**顺序切换 + 冷却分钟数** | 零依赖 | **P2** |
| E2 | **sanitized()：写入与读取两侧各清洗一次**，已存脏数据不靠迁移也能恢复（`AIProviderConfig.kt:105-124`） | `ConfigSanitizer.kt` ✅ 已在做 | **已对齐**，只需在读取侧也调一次 | — | — |
| E3 | **models.dev 式单价表**（`assets/api.official.json` 109KB，12 家），由脚本在打 Tag 前刷新 | `ModelListFetcher` 拉模型列表 | 加一个 model→price 的 map 即可支撑「本次会话花了多少钱」。`CostFormat.kt` / `ModelCostCalculator.kt` 是纯逻辑 | 零依赖 | **P2** |
| E4 | **每 provider 独立代理** + `customHeaders` 占位符 `{{SESSION_ID}}`/`{{API_KEY}}` | 单一代理设置 | 面向中转/网关用户的刚需 | 零依赖 | **P2** |

---

## ⑤ 优先行动清单

### P0 —— 决定「像不像 AiCode」的最小集（建议 2 个迭代，约 13~18 人日）

| # | 行动 | 对标条目 | 工作量 | 备注 |
|---|---|---|---|---|
| P0-1 | **验证 `filesDir` 执行 proot 在 Android 10+ 是否可行**；若被 W^X/SELinux 拦，改造为 jniLibs + `nativeLibraryDir` + `libproot.so` 命名 + `useLegacyPackaging=true` | D1 | **1.5 人日** | **阻塞项，必须最先做**。不做这一步，后面所有容器能力在低版本设备上都可能是坏的 |
| P0-2 | **对齐 proot 环境变量**：`PROOT_TMP_DIR` / `TMPDIR=/tmp` / `PATH` 显式含 `/usr/local/bin` / `GIT_CONFIG_GLOBAL` | D2 | **0.5 人日** | 顺手修，收益立竿见影（mktemp/dpkg/自定义命令） |
| P0-3 | **`ProotRunner.buildArgs()` → `buildInvocation()`**：返回 `{executable, argv（含 argv[0]）, envMap, envArray}`，`envArray` = `System.getenv() + env` | C4 / C5 | **0.5 人日** | **成本最低、价值最高的一条**。现在改几乎零成本；等接了 PTY 再改是重构 |
| P0-4 | **提示词资产化**：`assets/prompts/<NN>-<名>.md` + `core/prompt/{PromptResolver, PromptCatalog, SystemPromptBuilder}.kt` + 启动时释放到 `filesDir/prompts` + `prompts.custom/` 用户覆盖层 | B1 | **3 人日** | 把 `AgentLoop.kt` 里硬编码的 system prompt 拆成 6~8 个片段。同时加 4 个 `{{VAR}}` |
| P0-5 | **场景库 v1**：`assets/env/scenarios/{base,node,python,java,go}.sh` + `env-common.sh`（包管理器探测 + 国内镜像源）+ `provision.sh` 菜单 + ContainerScreen「环境安装」入口 | B4 / B5 | **3 人日** | 「能写代码 / 写游戏」的前提。5 个场景足够覆盖 80% |
| P0-6 | **`edit_file` 工具**：old_string/new_string/replace_all + `edits` 数组整批原子 | A4 | **1.5 人日** | 这是「AI 改真实项目」的基础能力，没有它只能整文件覆盖 |
| P0-7 | **双层写保护**：`FileVersionRegistry`（sha256 写前校验，返 `STALE_FILE`）+ `WorkspaceFileLock`；`read_file` 登记指纹 | A5 | **1.5 人日** | 与 P0-6 配套。锁内校验、以锁内重读为准 |
| P0-8 | **`bash` + `terminal` 双工具 + `notify` 唤醒机制** | C1 / C2 | **3 人日** | 含改造 `AgentLoop` 支持外部事件注入触发下一轮。这是「体验好」的分水岭 |
| P0-9 | **文件探索三件套**：`list_dir` / `search_code` / `@` 文件提及 | A3 | **2 人日** | 与 P0-6 配套，构成最小可用的代码 Agent 闭环 |
| P0-10 | **极简语法高亮**：`core/editor/SimpleHighlighter.kt`（10 语言，异步 500 行分块）+ 编辑器补行号/undo/dirty | A1 / A2 | **3 人日** | 不用 TextMate/oniguruma。视觉观感提升最大的单项 |

### P1 —— 补齐工程完整度（约 12~16 人日）

| # | 行动 | 对标 | 工作量 |
|---|---|---|---|
| P1-1 | `LineDiff` LCS + 聊天内 +/- 渲染 + `edit_file` 结果 diff 卡片 | A7 | 2 人日（含大文件保护） |
| P1-2 | Checkpoint 改造：懒快照（只快照将被改的文件）+ 按 sessionId 隔离 + `skipped` 清单 | A6 | 1.5 人日 |
| P1-3 | `BoundedOutput` 限幅 + 工具输出落盘 + `output_path` 分段续读 | C3 | 1 人日 |
| P1-4 | 提示词语序优化：稳定内容前移到最后片段、`{{*}}` 变量集中到末尾（KV Cache 优化） | B2 / B3 | 1 人日 |
| P1-5 | 技能三层来源 + 清单/正文分离 + `load_skill` 工具 | B8 | 2 人日 |
| P1-6 | 项目规则自动读取（`AGENTS.md` > `.cursorrules`，两级，16K 截断） | B9 | 1 人日 |
| P1-7 | 内置子代理 `explore`（只读版：tools/inject/description 三字段 + 极简 frontmatter 解析） | B7 | 2 人日（含自己写 frontmatter 解析器） |
| P1-8 | `git-credential-agentkit` 文件 IPC 回源 + 凭据 base64+反转混淆 | D4 | 1.5 人日 |
| P1-9 | Git 全线加 `-c core.quotepath=false`；写命令失败回传 git 原文 | D6 | 0.5 人日 |
| P1-10 | 资产提取三策略（覆盖 / 保护用户已改 / 整目录先删后建） | D5 | 0.5 人日 |
| P1-11 | `PROVISION_VERSION` 与镜像版本解耦 | B6 | 0.5 人日 |
| P1-12 | proot 版本确认 + `statx`/`PROOT_NO_SECCOMP` 实测（Node 跑不起来的根因排查） | D3 | 1 人日（含验证） |

### P2 —— 体验与差异化（约 8~11 人日）

| # | 行动 | 对标 | 工作量 |
|---|---|---|---|
| P2-1 | 大屏双栏 `WorkbenchPane`（EDITOR / TERMINAL / GIT 右栏，`embedded` 参数复用） | §2.4 | 2 人日 |
| P2-2 | 能力标记 `ToolCapability` + `effectiveCapabilities(args)` + PLAN 模式运行时拦截 | C7 / C8 | 2 人日 |
| P2-3 | 权限记忆粒度升级到「程序名+子命令」，不可静态判定的命令禁「始终允许」 | D7 | 1.5 人日 |
| P2-4 | 子代理进阶：工具白/黑名单（含 `mcp__x__*` 通配）+ provider/model/mode 覆写 | B7 | 2 人日 |
| P2-5 | Markdown 预览 WebView（assets 内置 marked/highlight/DOMPurify + 三条安全配置） | A8 | 1.5 人日 |
| P2-6 | 多 Key 轮换 + 冷却 + 状态码切换 | E1 | 1.5 人日 |
| P2-7 | 每 provider 独立代理 + `customHeaders` 占位符 | E4 | 1 人日 |
| P2-8 | models.dev 式单价表 + Token/费用统计 UI | E3 | 1 人日 |

**合计估算**：P0 ≈ **17.5 人日**，P1 ≈ **14.5 人日**，P2 ≈ **12.5 人日**。

> 建议排期：**先做 P0-1（1.5 人日）**，它是唯一的技术阻塞项，结果会影响我们对 targetSdk/minSdk 的决策。

---

## ⑥ 代码位置索引

### 构建与工程配置
| 内容 | 位置 |
|---|---|
| 三模块定义 | `settings.gradle.kts:21-23` |
| JitPack 源（Termux 组件） | `settings.gradle.kts:12` |
| AGP/Kotlin/Hilt 版本 | `build.gradle.kts:17-21`（根） |
| `minSdk 26` / `targetSdk 28` 锁定原因 | `app/build.gradle.kts:106-114` |
| flavor 按 ABI 拆分 | `app/build.gradle.kts:131-142` |
| **proot 走 jniLibs 的原理注释** | `app/build.gradle.kts:145-150` |
| sourceSets 镜像共享 | `app/build.gradle.kts:152-163` |
| `useLegacyPackaging = true` | `app/build.gradle.kts:226` |
| sora-editor 依赖 + 不引 tree-sitter 的原因 | `app/build.gradle.kts:382-386` |
| 核心库脱糖（textmate 需 API 33 以下） | `app/build.gradle.kts:206, 387` |
| Markdown 渲染依赖 | `app/build.gradle.kts:374-378` |
| `syncAiDocs` Gradle task | `app/build.gradle.kts:76-88` |
| gitVersionName / gitCommitCount（Tag 驱动发版） | `app/build.gradle.kts:27-74` |

### 核心业务
| 内容 | 位置 |
|---|---|
| **容器 proot 调用构造** | `feature/agent/domain/container/LinuxContainerEngine.kt:718-764` |
| **容器环境变量** | `feature/agent/domain/container/LinuxContainerEngine.kt:767-800+` |
| **argv[0] 必须是 proot 的完整注释** | `feature/agent/domain/container/LinuxContainerEngine.kt:46-52` |
| **PTY 环境必须合并 getenv 的注释** | `feature/agent/domain/container/LinuxContainerEngine.kt:60-71` |
| 不设 `PROOT_NO_SECCOMP` 的原因 | `feature/agent/domain/container/LinuxContainerEngine.kt:776-781` |
| `PROVISION_VERSION` | `feature/agent/domain/container/LinuxContainerEngine.kt:162-170, 483` |
| rootfs 版本 / asset 路径 | `feature/agent/domain/container/ContainerInstaller.kt:233, 259` |
| 资产提取器全家 | `feature/agent/domain/container/ContainerInstaller.kt:44/63/74/95/117/147/172/195` |
| PTY 会话构造 | `feature/terminal/domain/TerminalSessionManager.kt:400-439` |
| TabFinishedEvent（唤醒 AI） | `feature/terminal/domain/TerminalSessionManager.kt:414-425` |
| `terminal` 工具（5 action + notify） | `feature/agent/domain/tool/container/BackgroundTerminalTools.kt:38-120` |
| `Bash` 工具（含流式 + 异常保留输出） | `feature/agent/domain/tool/container/ExecuteCommandTool.kt:1-190` |
| `editFile`（字符串匹配 + 原子批） | `feature/agent/domain/tool/editor/EditFileTool.kt:29-130` |
| `readFile`（窗口化读取 + 指纹登记） | `feature/agent/domain/tool/file/FileTools.kt:29-115` |
| `list` 工具 | `feature/agent/domain/tool/explorer/ListFilesTool.kt:22-60` |
| `search` 工具（rg） | `feature/agent/domain/tool/explorer/SearchCodeTool.kt:23-60` |
| `@` 文件提及（rg --files） | `feature/agent/domain/mention/FileMentionRepository.kt:1-40` |

### 提示词与 Agent 体系
| 内容 | 位置 |
|---|---|
| **提示词组装主流程** | `feature/agent/domain/prompt/SystemPromptProvider.kt:243-275` |
| **`{{AICODE_*}}` 变量展开** | `feature/agent/domain/prompt/SystemPromptProvider.kt:379-405` |
| 片段解析优先级 | `feature/agent/domain/prompt/SystemPromptProvider.kt:422-428` |
| 会话级 Source 缓存 | `feature/agent/domain/prompt/SystemPromptProvider.kt:53-78, 194-220` |
| 项目规则（AGENTS.md > CLAUDE.md） | `feature/agent/domain/prompt/SystemPromptProvider.kt:127-172` |
| 子代理提示词组装 | `feature/agent/domain/prompt/SystemPromptProvider.kt:310-376` |
| **四层来源枚举** | `feature/agent/domain/prompt/PromptFragmentCatalog.kt:12-13` |
| 片段 CRUD（save/reorder/delete） | `feature/agent/domain/prompt/PromptFragmentCatalog.kt:100-204` |
| 内置片段扫描 | `feature/agent/domain/prompt/PromptFragmentCatalog.kt:247-254` |
| **编号 Regex / `.no-builtin`** | `feature/agent/domain/prompt/PromptFragmentResolver.kt:15-40` |
| `AgentTool` 抽象基类 | `feature/agent/domain/tool/AgentTool.kt:70-146` |
| `ToolRegistry`（41 行） | `feature/agent/domain/tool/ToolRegistry.kt:1-41` |
| 权限五步评估注释 | `feature/agent/domain/permission/ToolPermissionPolicyEngine.kt:15-24` |
| 子代理定义模型 + 工具裁剪 | `feature/agent/domain/subagent/AgentDefinition.kt:56-115` |
| frontmatter 解析（容错） | `feature/agent/domain/subagent/AgentDefinitionParser.kt:29-88` |
| 技能解析 | `feature/agent/domain/skill/SkillParser.kt:11-70` |
| Checkpoint | `feature/agent/domain/checkpoint/CheckpointManager.kt:23-96` |
| 主工作流（1406 行） | `feature/agent/domain/workflow/StatefulAgentWorkflow.kt` |

### UI / 渲染 / Diff
| 内容 | 位置 |
|---|---|
| TextMate 初始化 | `feature/editor/domain/TextMateSetup.kt:15-58` |
| 扩展名 → scopeName | `feature/editor/domain/TextMateSetup.kt:68-96` |
| 编辑器屏幕（837 行） | `feature/editor/presentation/CodeEditorScreen.kt:27-120` |
| Markdown 预览 WebView | `feature/editor/presentation/component/MarkdownPreviewWebView.kt:31-70` |
| **LineDiff LCS** | `core/util/LineDiff.kt:11-58` |
| Diff 渲染 + Highlights 着色 | `feature/git/presentation/component/DiffViewer.kt:59-90` |
| Git 命令封装 | `feature/git/domain/GitRepository.kt:29-70` |
| APK 安装器 | `core/util/ApkInstaller.kt:14-60` |
| **大屏双栏容器** | `WorkbenchPane.kt:21-92` |
| 主 Activity（导航） | `MainActivity.kt`（1073 行） |

### 资产（assets）
| 内容 | 位置 |
|---|---|
| 内置提示词（静态基线） | `assets/prompts/{00,10,15,20,30,40,50,60,70,99}-*.md` |
| 按需叶子片段 | `assets/prompts/agent/*.md`（8 个） |
| 内置子代理 | `assets/agents/explore.md`（31 行） |
| **场景库** | `assets/aicode/lib/scenarios/*.sh`（10 个） |
| 场景分发 + SCENARIO_IDS | `assets/aicode/lib/env-common.sh:613, 622-634` |
| 轻场景安装器 | `assets/aicode/lib/env-common.sh:636-651` |
| 包管理器探测 / 换源 | `assets/aicode/lib/env-common.sh:42, 433-570` |
| Android SDK 安装 | `assets/aicode/lib/android-sdk.sh:1-60` |
| 容器初始化脚本 | `assets/aicode/provision.sh:1-70` |
| `aicode` 命令入口 | `assets/aicode/bin/aicode:1-8` |
| **git 凭据 helper（文件 IPC）** | `assets/aicode/git-credential-aicode:1-40` |
| TextMate 语法/主题（2.4 MB） | `assets/textmate/` |
| Markdown 预览 bundle | `assets/markdown/`（preview.html + marked + highlight + purify） |
| Provider 预设 | `assets/providers.json` |
| 官方模型单价表 | `assets/api.official.json`（109 KB，12 家） |
| 容器镜像源 | `assets/container-images.json` |
| 第三方许可证清单 | `assets/licenses/`（5 个 .txt） |

### AiCode 侧**未被采纳 / 已知缺陷**（供我们避坑）
| 问题 | 位置 |
|---|---|
| 编辑器保存**不做并发写检测**（明示的已知缺陷） | `feature/editor/presentation/CodeEditorScreen.kt:27` |
| 没有独立文件树组件 | 全仓 `grep FileTree` 无实现 |
| `targetSdk` 锁 28，代价是**不能上 Google Play** | `app/build.gradle.kts:107-114, 256` |
| git 凭据是**混淆（base64+反转）而非加密**，脚本自身可还原 | `assets/aicode/git-credential-aicode:3-9` |
| `terminal-emulator` / `terminal-view` 依赖 JitPack | `settings.gradle.kts:12` |

---

## 附：待验证事项清单

1. **`filesDir` 执行 proot 在 Android 10+ 的行为**（P0-1）—— AiCode 注释明确说 filesDir 不允许 execve，但 AgentKit 目前就是这么做的。**需真机/多版本实测确认**。
2. **AgentKit 打包的 proot 版本**（P1-12）—— 决定是否受 `statx` / seccomp 问题影响。
3. **AgentKit `targetSdk` / `minSdk` 取值** —— 决定是否需要像 AiCode 一样为 PRoot 做妥协。
4. **AgentKit ChatScreen 的 Markdown 渲染现状**（A8）—— 未在本次范围内读码确认。
5. **AgentKit `terminal` 是否已接 PTY** —— 影响 C4/C5 的紧迫程度。
6. **` defaultModel vs selectedModel` 模型选择逻辑**在 AiCode 是 `AIProviderConfig.effectiveModel`（`:62-64`），不确定 AgentKit `ConfigStore` 是否已等价实现（**待验证**）。

---

*报告完 · 事实均来自实际读码并标注 `路径:行号`；不确定的已标注「待验证」；无编造内容。*