# W-32 GitHub 提交三件套（改完能提交）

## 缺口

AiCode 唯一还明显领先的地方：它的 Agent 改完文件能 `git commit`。我们的 Agent 能改工作区，
但改完就停在那儿——用户还得自己去电脑上提交。

## 为什么不用本地 git / JGit

| 方案 | 问题 |
|---|---|
| 调 `git` 命令 | 手机端没有 git 可执行文件（除非 proot 里装，等于要求用户先装环境） |
| 引 JGit | +3MB，`java.nio.file` 需要 desugar，SSH 私钥格式支持有限 |
| **Git Data API**（本批） | 纯 HTTP，blobs→tree→commit→ref 四步完整表达"提交"；复用已有的 PAT 配置 |

代价：只能提交到 GitHub（不支持 GitLab/Gitea）。如实记。

## 三个工具

| 工具 | 作用 | 读/写 |
|---|---|---|
| `github_status` | 比对工作区与远端分支，列出待提交的新增/修改 | 只读 |
| `github_commit` | 提交变更（message 必填，可指定 branch / paths） | 写 |
| `github_log` | 最近提交（sha / 标题 / 作者 / 时间） | 只读 |

配置复用「构建」页的 owner/repo/PAT，不让用户配第二遍。未配置时明确报
`github_not_configured` 并指路，不假装成功。

## 关键实现点

**blob sha 必须本地算。** `sha1("blob {len}\0" + bytes)` 是 git 的对象哈希公式。
先算再比对，只有真正变了的文件才上传 blob——否则每次提交都要把所有文件传一遍。
harness 用 `git hash-object` 的独立参考值断言（空文件 `e69de29b…`、`hello\n` `ce013625…`、
中文 UTF-8 `f3ca6104…`）。

**不支持删除。** 远端有、本地没有的文件保持不动。工作区是用户随手放文件的地方，
把「本地没有」当成「要删」会误删仓库文件——这个错代价太大，宁可不做。

**更新分支用 `force=false`。** 远端在此期间有新提交会失败（422），而不是覆盖别人的改动。
失败文案直接说"重试即可"。

**只传变更，但 tree 带 `base_tree`。** 这样未变更的文件沿用旧 tree 条目，
一次提交只描述差异，不会把整个仓库重写。

**commit message 规范化**：首行 ≤72 字（超出截断加省略号）、空行分隔正文、总长 500。
harness 抓到一个真 bug：`drop(1)` 保留了标题后的空行，不 trim 会拼出三个换行。

## 上传边界

- 单文件 ≤ 2MB（超过跳过并在结果里列出）
- 单次 ≤ 200 个文件
- 路径规范化：拒 `..`、绝对路径、连续斜杠、控制字符

## 验证

- harness **24/24**（blob sha 4 + 路径 6 + 差异 5 + 消息 4 + 摘要/边界 5）
- 既有 harness：27/25/14/9/12/13/18/16/34/18 全无回归
- 反验断言：13 条池条目 + 5 条文案
- **真实仓库对拍**（用 AgentKit 自己的仓库跑只读接口）：
  - `/git/ref/heads/main` 的 `object.sha`、`/git/commits/{sha}` 的 `tree.sha`、
    `/git/trees/{tree}?recursive=1` 的 `tree[].path/sha/type` 字段假设全部成立；
  - 递归 tree 返回 364 个 blob 条目；
  - 取 `scripts/qa/W32Verification.kt` 的真实内容按本实现公式算 sha1，
    与 GitHub 返回的 blob sha **完全一致**（`11f5e6e85113…`）——证明 status 的
    「未改也判成改了」这类误判不会发生。

## 本批未做（下一批）

**后台命令完成通知**：`shell_run` 目前是同步的，`npm install` / `gradle build` 这类长命令
会一直占着轮次。要做需要动终端会话池（异步提交 + 输出落文件 + 完成通知 + 状态查询），
风险比这批大，单独开一批做，避免和提交功能混在一起出问题。
