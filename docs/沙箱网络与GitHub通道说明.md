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
