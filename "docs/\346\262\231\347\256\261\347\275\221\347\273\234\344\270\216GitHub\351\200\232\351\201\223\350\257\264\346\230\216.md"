# 沙箱网络与 GitHub 通道说明（2026-10-30 合并版）

> 本文替代三份互相矛盾的旧记录：
> 资料库《沙箱 GitHub 通道与交付 SOP》、《AgentKit 网络通道与加速指南》，以及本文件 2026-10-22 版。
> 三者内容约八成重复，冲突全部来自同一个原因——**「通不通」是按天变的，被当成了结论写进去**。
> 所以这一版只固化**不随网络变的东西**：判据、选路规则、命令、坑。
> 状态数字挪进 §4，标明日期，**不许当结论抄**。

---

## 一、判据要三层，不能只看 DNS

旧文档的判据是「DNS 落在 `198.18.0.0/15` 就是被拦」。这条**只能排除**，**不能证明能传数据**。

2026-10-30 实测反例：

```
getent hosts github.com      → 20.205.243.166   （真实 IP，不是 198.18.x）
curl -m 20 https://github.com        → 200
curl -m 20 https://api.github.com    → 200
git clone（同一个域名、同一个 token） → 134 秒后 Failed to connect
git ls-remote origin                 → 秒回，能列出全部 refs
```

**解析正常、单请求 200、元数据能读，但对象传输挂死。** 所以判据必须是三层：

| 层 | 命令 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| 1 解析 | `getent hosts <域名>` | 有没有被劫持到保留网段 | 能不能建连 |
| 2 单请求 | `curl -m 20 -o /dev/null -w "%{http_code}"` | TLS 通不通、鉴权对不对 | 大响应能不能传完 |
| 3 传输 | 真跑一次 `git fetch` / 下一次 tarball | **这条通道今天能不能用** | 明天还能不能用 |

**只做第 1 层就下结论，是过去几轮反复白花时间的根因。**

---

## 二、选路：按「操作 × 体积」，不按域名状态

| 想做的事 | 走哪条 | 关键约束 |
|---|---|---|
| 推 1~20 个小文件（<100KB） | MCP `push_files` | 一次 commit，秒级；更新已有文件要 `sha` |
| 推单文件 >100KB | REST `PUT /contents/{path}` | MCP 会 `argument list too long`（`ARG_MAX`） |
| 推多文件且总量大 | 小文件 MCP 一批 + 大文件逐个 REST | 别硬凑一个 commit |
| 读文件 / 查 issue / 查 CI | `curl api.github.com`（带 PAT） | 匿名会撞限流 |
| 拉整仓源码 | `gh-proxy.com` + tarball | **不带** Authorization |
| **同步本地 git 历史** | `git fetch https://gh-proxy.com/https://github.com/...` | 镜像**只读**，push 会 `No anonymous write access` |
| 下 APK / artifact | `gh-proxy.com` 直链 | 不带 auth；支持 Range |
| `git clone` / `git push` 直连 | **不要用** | 见 §1 反例，重试是纯浪费 |

一句话：**读走镜像，写走 MCP，大文件走 REST，git 协议只用来 fetch，不用来 push。**

---

## 三、五条命令（可直接粘）

```bash
export GH_TOKEN=$(cat /tmp/.ghpat)          # classic PAT，勾 repo（要触发 workflow 再加 workflow）

# ① 探活（三层，10 秒内出结论）
getent hosts github.com api.github.com | awk '{print $1, $2}'
curl -s -m 10 -o /dev/null -w "api=%{http_code}\n" -H "Authorization: token $GH_TOKEN" https://api.github.com/user

# ② 同步本地历史（秒回，只读）
cd /workspace/AgentKit
git fetch "https://gh-proxy.com/https://github.com/Kdkdmwnwdkd/AgentKit.git" main
git reset --hard FETCH_HEAD                # 未跟踪文件不会被删

# ③ 推小文件（MCP，一次 commit）
mcporter call github.push_files owner=Kdkdmwnwdkd repo=AgentKit branch=main \
  message="docs: ..." files='[{"path":"docs/x.md","content":"..."}]'

# ④ 推大文件（REST，payload 走文件不进命令行）
SHA=$(curl -s -H "Authorization: token $GH_TOKEN" \
  "https://api.github.com/repos/Kdkdmwnwdkd/AgentKit/contents/<path>?ref=main" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['sha'])")
python3 -c "
import json,base64
open('/tmp/push.json','w').write(json.dumps({
  'message':'...','content':base64.b64encode(open('<local>','rb').read()).decode(),
  'sha':'$SHA','branch':'main'}))"
curl -s -X PUT -H "Authorization: token $GH_TOKEN" --data @/tmp/push.json \
  "https://api.github.com/repos/Kdkdmwnwdkd/AgentKit/contents/<path>"

# ⑤ 下产物（不带 auth，带了反而 403）
curl -sL --max-time 300 -o app-debug.apk \
  "https://gh-proxy.com/https://github.com/Kdkdmwnwdkd/AgentKit/releases/download/latest/app-debug.apk"
```

**MCP 注册（沙箱重置后必做，会清掉 `.mcp.json` 里的 github 条目）：**

```bash
mcporter config add github "https://api.githubcopilot.com/mcp/" \
  --transport http --header "Authorization=Bearer $(cat /tmp/.ghpat)" --scope home
mcporter list github | head
```

---

## 四、实测数字（会变，只看量级）

| 日期 | 通道 | 结果 |
|---|---|---|
| 10-19 | api.github.com | 被劫持到 198.18.0.x，`000` |
| 10-22 | 四条通道 | 全通；APK 34MB 走 gh-proxy 4 分钟（141KB/s） |
| 10-26 | MCP `push_files` | 7 文件一次提交，3 秒 |
| 10-28 | git 协议 | `ls-remote` 40 秒超时；改走 API 造 commit |
| **10-30** | gh-proxy tarball | Coomi 42MB / 5.0 秒（**8.3MB/s**） |
| **10-30** | codeload 直连 | Coomi 30MB 传 15 分钟未完成；RikkaHub 18.7MB / 10 分钟（**约 30KB/s**） |
| **10-30** | `git clone` 直连 | 134 秒 `Failed to connect`；同一时刻 `ls-remote` 秒回 |
| **10-30** | 镜像 `git fetch` | 秒回，拿到真实 HEAD `16b9731` |

**同一时刻，直连 30KB/s vs 镜像 8.3MB/s，差 250 倍。** 所以「慢」在多数情况下不是配额问题，是没换通道。

---

## 五、会复发的坑

| # | 坑 | 现象 | 处置 |
|---|---|---|---|
| 1 | `git clone` 直连挂起 | TCP 三次握手成功，之后 0 字节，134 秒报 `Failed to connect` | 别重试。改用镜像 tarball / 镜像 fetch |
| 2 | 镜像 push | `No anonymous write access` | 镜像只读，写走 MCP/REST |
| 3 | 镜像带 auth | 反而变慢或 403 | 下载/同步一律不带 Authorization |
| 4 | MCP 参数长度 | `argument list too long` | 单文件 >100KB 走 REST |
| 5 | MCP 提交后本地分叉 | `git push` 被拒、`git log` 对不上 | `git fetch <镜像> main && git reset --hard FETCH_HEAD` |
| 6 | 更新已有文件漏 `sha` | 422 | 先 `get_file_contents` 取 sha |
| 7 | MCP 响应是 SSE | JSON 解析失败 | 剥掉 `data: ` 前缀 |
| 8 | WebFetch 15 分钟缓存 | 拿旧状态误判「CI 卡住」 | URL 加 `?t=<时间戳>` |
| 9 | 401 `Bad credentials` | 偶发，且夹着 `rate limit exceeded for <IP>` | **九成是请求变成匿名了**，等 30~120 秒重试；先单独验 `/user` 再决定要不要换令牌 |
| 10 | 中文路径 | 全被判成「新增」 | `git -c core.quotepath=false`；API 侧用 UTF-8 原样 |
| 11 | 文件 mode | 脚本在 CI 里没有执行位 | `.sh` 用 `100755`，其余 `100644` |
| 12 | 分支名 | 404 | 参照仓库里 `rikkahub` 默认分支是 `master`，其余是 `main` |
| 13 | 只看 HTTP 200 就收工 | 以为推上去了 | 推完**读回 blob sha 逐个比对**，不看 200 看内容 |

**第 13 条是本项目最贵的一条**：ref 动了不等于 tree 对，文件数涨了不等于内容对。

---

## 六、旧结论裁定（明确作废，别再来回改）

| 旧说法 | 现在 | 证据 |
|---|---|---|
| 「DNS 落 198.18.x 就是被拦」 | 不完整。DNS 正常也可能传不动 | §1 的 10-30 反例 |
| 「`Authorization` 必须用 `token` 前缀」 | `token` / `Bearer` 都行 | 10-28、10-30 均 200 |
| 「本地 git 历史与远端是两套，rebase 不可用」 | **已解决**：镜像 fetch 可拿到完整历史，本地 HEAD 就是真实 commit | 10-30 `git log` 正常 |
| 「只能走 API 造 commit」 | 仍可用，但不必了：有真实历史后 `base_tree` 直接取本地 HEAD | 同上 |
| 「沙箱不能编 APK」 | 可以，需 JDK17 + cmdline-tools + 腾讯 gradle 镜像，首次全量约 18 分钟 | 10-19 补充节 |
| 「gh-proxy 只加速 artifact」 | 也加速 tarball 与 git fetch | 10-30 实测 |

保存「坑」那一列比保存「状态」那一列有用得多——前者一年后还成立，后者一天就过期。

---

## 七、安全

1. PAT 只放 `/tmp/.ghpat`（`chmod 600`）或密码管理器，**不进仓库、不进备份包、不进日志**。
2. **聊天里明文出现过的令牌一律视为已泄露**：用完立刻吊销重发。
3. 提交前扫一遍 diff 有没有 `api_key` / `token` / `Authorization` 字样。
4. 第三方镜像（gh-proxy / ghfast）**永不带可写凭据**。

---

## 八、维护约定

- **只改规则和命令，不改状态结论。** 状态只往 §4 加一行，带日期。
- 新踩的坑追加到 §5，「首次记录」写清日期。
- 旧结论改判写进 §6，注明证据——**不要直接删**，否则下一个人会把它重新写回来。
- 本文与资料库里那两份冲突时以本文为准；那两份建议归档，不再各自维护。
