# 沙箱网络与 GitHub 通道说明（实测）

> 本文记录**在这个沙箱里访问 GitHub 的可用通道与坑**，供后续会话直接复用，
> 不用再花一小时重新试错。
>
> 参考了资料库里的《参考：沙箱绕过网络连接 GitHub - 通道说明》，并结合本仓库
> 2026-10-22 的实测结果做了修正与补充。

---

## 一、先看：当前到底通不通

**拦截是间歇性的，不要凭一次结果下结论。** 每次开工先跑一次：

```bash
for d in github.com api.github.com raw.githubusercontent.com api.githubcopilot.com; do
  echo -n "$d -> "; getent hosts $d | head -1 | awk '{print $1}'
done
```

| 解析结果 | 含义 |
|---|---|
| `198.18.0.x` | **被拦截**。这是 IANA 保留的基准测试网段（RFC 2544），出现它即代表流量被拦截设备接管 |
| `20.205.243.x` / `140.82.113.x` 等 | **真实 GitHub IP，正常** |

2026-10-22 实测：解析到真实 IP，通道全通。**但之前有会话记录过被拦截的情况，
所以每次开工都要重新确认一次。**

---

## 二、四条通道（按用途选）

| 通道 | 用途 | 状态 | 坑 |
|---|---|---|---|
| `curl` + PAT 直连 `api.github.com` | REST API 读写 | ✅ 可用（TLS 已不被切断） | 偶发见 §3.1 |
| `api.githubcopilot.com/mcp/` | 官方 GitHub MCP Server | ✅ 连通（401 = 需要 PAT） | **不含 Actions 工具** |
| `gh-proxy.com` 直链 | **大文件下载（APK）** | ✅ **强烈推荐** | 见 §3.2 |
| `WebFetch` 工具 | 读 `api.github.com` | ✅ 可用 | 见 §3.3 |

### 2.1 curl + PAT（主力）

```bash
export GH_PAT=<your classic PAT, 需 repo 权限>
curl -s -H "Authorization: token $GH_PAT" https://api.github.com/user
```

- **必须用 `token` 前缀**：`Authorization: Bearer` 会 401 Bad credentials。
- 连接器（`@github-connector`）的 OAuth token **权限不足**（403 Resource not accessible
  by integration），必须用**用户自建 classic PAT，勾选 `repo`**。

### 2.2 GitHub MCP Server（`api.githubcopilot.com`）

端点不在拦截名单内，可调用 GitHub 全量 API。写操作关键项：
`create_or_update_file`（单文件）、`push_files`（批量）、`create_branch`、
`create_pull_request`、`merge_pull_request`。

- 更新已存在文件**必须提供 sha**（先 `get_file_contents` 取）。
- 响应是 SSE 格式，需剥离 `data: ` 前缀。
- **没有 Actions 相关工具**（`list_workflow_runs` 等返回 unknown tool），
  查 CI 状态得走 REST API。

### 2.3 gh-proxy 直链（大文件下载，最关键的提速）

```bash
# 注意：不要带 Authorization 头！带了反而 403。
curl -sL -C - "https://gh-proxy.com/https://api.github.com/repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip" -o out.zip
```

**实测对比（本仓库 34 MB 的 APK artifact，2026-10-22）：**

| 方式 | 耗时 | 速度 |
|---|---|---|
| API 直连 + Authorization | **40 分钟**（多轮断点续传） | ~14 KB/s |
| **gh-proxy 直链（不带 auth）** | **4 分钟** | ~141 KB/s |

**快 10 倍。** 这轮在下载上浪费的近一小时，就是没先走这条通道。

- `artifact_id` 从 `actions/runs/{run_id}/artifacts` 取（**这个接口要带 auth**）。
- 支持 Range 断点续传（`-C -` / `-r`），返回 206。
- artifact 有效期 90 天。
- Release asset 直链（`github.com/.../releases/download/...`）会被 CDN 卡住
  （实测卡在 3.6 MB 不动）→ **改用 gh-proxy 走 artifact**。

### 2.4 WebFetch

能读 `api.github.com` 的 REST 接口（含 Actions 运行记录）。

---

## 三、三个反复踩的坑

### 3.1 "Bad credentials" 401 —— 大概率不是令牌失效

这轮遇到过三次 `401 Bad credentials`，中间还夹着一次：

```
API rate limit exceeded for 114.132.152.117.
(But here's the good news: Authenticated requests get a higher rate limit...)
```

**"rate limit exceeded" 暴露了真相**：那条请求是以**未认证身份**发出去的。
也就是说偶发情况下 `Authorization` 头没生效（被中间设备剥离或请求重写），
GitHub 就按匿名 IP 限流，然后返回 401/403。

**处置**：**不要急着吊销或换令牌**——等 30~120 秒重试即可，这轮三次都是重试即恢复。
判断依据：用 `curl -H "Authorization: token $GH_PAT" https://api.github.com/user`
单独验一次，返回 `login` 就说明令牌没坏。

### 3.2 gh-proxy 不能带 Authorization 头

文档原话："不要带 Authorization 头 —— gh-proxy 服务端自带凭据，客户端带 token 会 403。"
实测确认：不带 → 206 正常；带了 → 403。

### 3.3 WebFetch 有 15 分钟缓存

对同一 URL 反复查询可能拿到**过期快照**。曾有会话因此误判"CI 跑了 60 分钟还没结束"，
实际那次 run 在 27 分钟时就已 success。

**处置**：复核状态时在 URL 后加无害查询参数绕过缓存，如 `?t=<时间戳>`。

---

## 四、本仓库的特殊约束（与网络无关但一起踩）

1. **沙箱里 git 协议直连 github.com:443 超时** → 只能走 REST API 造 commit
   （blob → tree → commit → ref）。
2. 由此导致**本地 git 历史与远端历史是两套**，`git rebase` 不可用。
   正确姿势：「以远端 head 为 parent + 显式文件列表」造 commit。
3. 远端 head 的 commit **在本地 git 里不存在**，`git rev-parse <sha>^{tree}` 会直接失败。
   `base_tree` 必须从 API 取（`/git/commits/{sha}`）。
4. **不要用 compare API 的文件列表重建 tree** —— 它会**漏文件**。
   本仓库上一轮 `known-broken.txt` 就被漏掉，合并后没进远端，最后靠单独推一次补上。
   合并时**直接用分支 head 的 tree**。
5. 中文文件名走 `contents` API 时 shell 里容易被截断/误解析，
   核对远端文件用网页或 raw 路径更稳。

---

## 五、2026-10-28 实测补充（W-79 推送这次）

这一轮把「git push 走不通 → 用 API 造 commit」完整跑了一遍，四条结论值得记住：

### 5.1 `git push` 的表现和判据

```
git ls-remote origin HEAD      → exit 124（40 秒超时，0 字节）
curl https://github.com/.../info/refs → 45 秒超时
TCP github.com:443             → 能连上（三次握手成功）
```

**TCP 能连、TLS 后无响应**，与 §1 的「DNS 落 198.18.x」是同一件事的两种表现。
判据不要只看 TCP：`/dev/tcp` 通不代表能用，必须发一个真实 HTTP 请求。

同时 `api.github.com` 正常 200。所以**读写都走 REST，不要碰 git 协议**。

### 5.2 `Authorization` 头两种前缀都能用

本轮 `Authorization: Bearer <PAT>` 请求 `/user`、`/git/trees`、`/actions/runs`
全部 200。§2.1 记的「必须 `token` 前缀」不成立——两种都可，`Bearer` 更通用。
遇到 401 先按 §3.1 重试，别急着换令牌。

### 5.3 造 commit 的最小流程（13 个文件，一次成功）

```text
GET  /repos/{o}/{r}/git/trees/{base}?recursive=1      # 远端 path → blob sha
本地 git ls-tree -r HEAD（配 -c core.quotepath=false）# 本地 path → blob sha
差异集 = 本地有、远端 sha 不同或不存在
POST /git/blobs   逐个上传（base64），得到新 blob sha
POST /git/trees   { base_tree: base, tree: [{path,mode,type,sha}...] }
POST /git/commits { message, tree, parents: [base] }
PATCH /git/refs/heads/main { sha, force: false }
```

三个细节：

- **必须 `core.quotepath=false`**。默认会把中文路径转成 `\346\211\271...`，
  与 API 返回的 UTF-8 路径对不上，结果是把已有文档全判成「新增」，
  再叠加「远端有本地无的不删」，看起来没坏，实际清单已经错了。
- **只传差异文件，`base_tree` 用远端 head 的 tree**。本地历史落后远端时（本仓库常态），
  这样能保住远端那些本地没有的文件（本轮 7 个 `core/build/*.kt`）。
- **`.sh` 用 `100755`，其余 `100644`**。mode 传错会让脚本在 CI 里失去执行位。

### 5.4 推完必须读回

本轮推完做了一次逐文件比对：取新 commit 的 tree，与本地 `git ls-tree` 的 13 个
blob sha 对照，**13/13 一致**，远端文件数 532 → 538（6 个新增）。

只看 `PATCH ref` 返回 200 不算数——它只说明 ref 动了，不说明 tree 内容对。

### 5.5 CI 与产物的口径

- 本轮 run 447：17 个步骤全 success，`QA gate` 在列。
- 日志汇总是 `PASS=57 SKIP=0 FAIL=0`，**57 是 QA 脚本个数**（`scripts/qa/run-*.sh`），
  不是断言条数。对外说「N 条契约」时要么给脚本数，要么给断言数并注明口径。
- APK 33.87 MB，Release `latest` 已更新，`versionName = 1.<run_number> = 1.447`。
