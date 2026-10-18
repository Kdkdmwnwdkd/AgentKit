# W-27 批次记录：工作区统一 + 文件树 + 语法高亮

> 依据：缺口 #76（文件树+语法高亮），以及排查中发现的**架构级 bug**（两个工作区）。

## 一、先修 Bug：两个工作区曾经分家

| 使用方 | 原目录 |
|---|---|
| Agent 工具（read_file / write_file / edit_file / ui_screenshot） | `<filesDir>/agent_workspace` |
| 编辑器 / 检查点 / 终端 / GitHub 构建 | `<filesDir>/workspace` |

后果：**Agent 写的文件编辑器里看不到；检查点拍不到 Agent 的产出；构建也打包不进去。**
这是"编程体验不对"的根因，比缺文件树严重得多。

修法：
- `WorkspaceStore.dir()` 统一到 `agent_workspace`；
- 首次访问**一次性迁移**老 `workspace`（整体改名优先；两边都在则逐项复制不覆盖；空目录删除）；
- `TerminalScreen` 的 cwd 同步改成同一目录。

## 二、文件树（#76）

- `WorkspaceStore.listTree()`：递归、目录在前、各自按名称排序，跳过隐藏文件与 `.part`/`.akitmp`；
- 编辑器列表态：目录可折叠（点击切换，图标 Folder/FolderOpen），缩进按层级 14dp；
- 文件行右侧显示语言标签（`kotlin` / `python`…），一眼看出类型；
- 新建支持子目录路径（`src/main.js`），校验走 `safeRelPath`。

## 三、语法高亮（#76）

- **不引库**：tree-sitter / Prism 会让 APK 再涨几 MB，选择 60 行单遍扫描；
- 识别：行注释（`//` 类 C、`#` 类 py/sh/yaml/toml）、字符串（含转义、未闭合到行尾）、
  数字、关键字（13 种语言）；
- 通过 `VisualTransformation` 实现：**只改显示，保存/复制仍是原文**；
- 性能护栏：文本 > 120KB 直接不着色（逐字符扫描会卡输入）；
- 语言识别在 `WorkspacePaths.languageOf`；词法器在 `SyntaxHighlighter`（均为纯函数）。

## 四、连带修复

- **检查点**：从"只快照顶层文件"改为**递归**（含 `screenshots/`、`downloads/`）；
  恢复时按原结构写回，并清空整个工作区（含子目录）。
- **GitHub 构建推送**：从 `agentkit-workspace/<文件名>` 改为保留相对路径
  （`agentkit-workspace/src/main.kt`），否则子目录代码会被拍平/丢失。

## 五、验证

- W-27 harness **13/13**（路径 5 + 语言 1 + 高亮 6 + 边界 1）。
- 既有 harness 无回归（27/25/14/9/12/12）。
- CI：见当批 run。
- APK 反验：W27 组 7 条池条目 + 3 条文案。

## 六、未做（下批）

- **@提及文件**：输入框打 `@` 选文件并注入上下文——与「工作区绑定会话」同属上下文管理，
  放 W-28 一起做更连贯。
- 高亮不识别块注释跨行、模板字符串插值、正则字面量——纯文本预览够用，不追。
