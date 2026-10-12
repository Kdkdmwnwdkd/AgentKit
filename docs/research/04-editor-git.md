# 04 · 编辑器与 Git 集成

> 对应九步顺序的第 6 步。目标：App 内能浏览/编辑项目文件、看 diff、提交并推送——把「写代码」从事后看日志变成真正的开发工作面。与 03 的终端/容器共享同一工作区。

## 参考项目

| 项目 | 许可证 | 参考什么 | 可用度 |
|---|---|---|---|
| Rosemoe/sora-editor | **LGPL-2.1** | 功能最全的 Android 代码编辑器（高亮/补全/符号栏） | ⚠️ 库方式可用（见 09） |
| jieapi/AiCode | GPL-3.0 | 编辑器 + 检查点 + 文件树的集成形态 | ❌ 架构参考 |
| eclipse-jgit/jgit | EDL-1.0（≈BSD-3） | 纯 Java Git 实现 | ✅ 直接依赖 |
| AAswordman/Operit | LGPL-3.0 | 编辑器与 Agent 工具链的衔接 | ❌ 架构参考 |

## 它怎么做的（架构思想）

### sora-editor（LGPL-2.1，可库方式集成）

- **编辑器三层**：`Content`（文本模型，支持行/列操作与撤销栈）→ `EditorRenderer`（把内容画到 Canvas，自己实现行号、光标游走、自动换行）→ `Language` 插件（ TreeSitter/TextMate 语法高亮、自动补全）。
- **关键教训**：它是一个重型 Custom View，与 Compose 混用要靠 `AndroidView` 包一层；手势、IME、滚动全部要自己接管。**功能天花板高，但集成成本和维护负担真实存在。**

### AiCode（架构参考）

它的「AI 写代码」工作流值得学的是**检查点（checkpoint）机制**：每次 Agent 大改文件前，对整个工作区打一个轻量快照（文件哈希清单而非全量拷贝），出问题一键回滚。这让「AI 改代码」从不可逆变成可逆——**这是 Agent 场景下编辑器最重要的能力，比语法高亮重要得多。**

### JGit（直接依赖）

纯 Java 的 Git 实现：`Git.open(dir)` → `status()/add()/commit()/push()/pull()/log()/diff()` 全覆盖，无需 root、无需 git 二进制（容器里也没有 git，这点反而成了 JGit 的优势）。Android 上唯一注意：**大仓库 diff 计算放 IO 线程，别卡 UI**。

## 我们的实现

### 选型决策：自绘轻量编辑器 + JGit

**不引 sora-editor**，理由：

1. 它是 Custom View，塞进 Compose + v3.1 设计体系（暖纸色板、圆角、动效）要做大量适配，**设计一致性会被拖垮**——而设计体系不变是硬性原则。
2. LGPL-2.1 库方式可用但附义务（源码书面要约、替换权），自用阶段无感，**开源前要留合规工作量**。
3. 我们的场景 80% 是「看 Agent 写了什么 + 小改 + diff」，不是 IDE 级编码。

**自绘范围**（Compose 原生，天然融入 v3.1）：

```
dev/editor/
├── FileTreeScreen.kt      // 工作区文件树（折叠/新建/删除/重命名）
├── EditorScreen.kt        // 编辑页：代码渲染 + 行号 + 撤销重做
├── SyntaxHighlight.kt     // 正则分词高亮：kotlin/java/js/ts/python/json/md/xml
├── CheckpointStore.kt     // 检查点：文件树哈希快照 + 一键回滚
└── git/GitRepository.kt   // JGit 封装（status/diff/commit/push/log）
```

### 编辑器能力边界（够用就好）

| 能力 | 做 | 不做 |
|---|---|---|
| 高亮 | 正则分词，7 种语言 | LSP、语义分析 |
| 编辑 | 文本域 + 行号 + 撤销/重做 | 自动补全、多光标 |
| 大文件 | >500KB 只读打开 + 提示 | 流式加载 |
| diff | 行级对比视图（绿增红删，**遵守 A 股配色：增=红、删=绿**——不，diff 语义用国际惯例：增=绿删=红即可，但注意与金融涨跌区分开，代码 diff 属于技术语境） | 三方合并 |
| 检查点 | 改前自动打，列表可回滚 | 增量备份 |

> diff 配色说明：股票语境涨红跌绿（系统规范），但**代码 diff 是技术语境**，全球工具链（git/vscode）都是增绿删红，此处遵循技术惯例，在组件注释里写明避免后人"修正"错方向。

### Git 集成（JGit）

```kotlin
class GitRepository(private val dir: File) {
    fun status(): Status                       // 改动文件列表
    fun diff(file: String): String             // 统一 diff 文本
    fun commit(message: String, files: List<String>)
    fun push(remote: String = "origin", pat: String)   // HTTPS + PAT
    fun log(limit: Int): List<CommitInfo>
    fun init()                                 // 工作区 git init
}
```

- **工作区即仓库**：`filesDir/workspace/` 首次进入编辑器时提示 `git init`，之后每次 AI 改文件都能看 diff
- **推送凭据**：GitHub PAT 存 ConfigStore（与 07 号文档的构建按钮**共用同一个 PAT**，一份存储两处消费）
- **分支模型从简**：自用场景只有 main，不做分支管理 UI；log 列表足够回溯

### 检查点机制（学 AiCode 的核心）

```
CheckpointStore:
  - snapshot(name, label): 遍历 workspace 文件 → SHA-256 清单存 JSON（<100ms，不拷贝内容）
  - restore(name): 对比当前清单与快照 → 差异文件从内容库还原
  - 内容库: filesDir/checkpoints/<name>/ 只存被改过的文件（增量，省空间）
```

**接入点**：ChatViewModel 里 Agent 每轮工具调用写文件前（`WriteFileTool` 执行处，`BuiltInTools.kt:119`）自动 `snapshot("auto-<timestamp>")`；编辑器页面顶部显示「可回滚到 N 个检查点」。

### 与现有代码的衔接

- 复用现有 `FileTool` 的 `workspace: File` 概念（`BuiltInTools.kt:70`），所有文件操作统一锚定 `filesDir/workspace/`
- 抽屉新增目的地 `EDITOR`；终端/容器（03）的默认工作目录也是它——**三处一个工作区**
- 新页面全走 v3.1 暖纸规范：文件树用 `Surface` 卡片 + `Dimens.kt` token，代码区等宽字体（`Type.kt` 新增 mono 样式）

## 注意事项

1. **别做 IDE**。每加一个"IDE 功能"都是长期维护债；我们的编辑器是 **Agent 的副驾**，不是 VS Code。
2. **正则高亮的边界**：分词器对字符串内的关键字会误判（`val x = "fun"`），接受这个不完美——sora 的 TreeSitter 方案要带 .so 且语言包体积大，不值。
3. **JGit 与容器 git 不互通**：容器里若用户自己装了 git，容器内提交 vs App 内 JGit 提交是两套 .git 状态文件。**约定：.git 只归 JGit 管**，容器内的 git 命令提示会破坏 App 视图（在终端 banner 里提示）。
4. **检查点内容库要设上限**：保留最近 20 个 + 总大小超 500MB 时淘汰最旧——防止 Agent 死循环写文件把存储打满。
5. **大仓库警惕**：JGit `status()` 对几千文件的工作区是百毫秒级，可接受；但**别在每次工具调用时全量 status**，按需。
