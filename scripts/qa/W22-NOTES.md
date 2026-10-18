# W-22 批次记录：全量 Bug 扫描（W-19~W-21 重点）

> 触发：用户要求「扫一遍看看有没有 Bug」。范围：近期三批新代码 + 关键交叉路径。
> 方法：机械扫描（强解包/空 catch/TODO）→ 人工过关键路径 → **真实网络验证**（不只跑 fixture）。

## 扫描结论：6 个真问题，已修 5 个

| # | 严重度 | 问题 | 状态 |
|---|---|---|---|
| 1 | **高** | `web_search` 只走 DuckDuckGo，**国内直连 20 秒超时**——工具在国内网络等于不可用 | 已修（Bing 主引擎 + DDG 兜底） |
| 2 | 中 | `edit_file` 回显偏移用 `replaced.indexOf(newString)`，新文本在文件别处已存在时**指错行** | 已修（用 `occurrences[0]`） |
| 3 | **高（隐私）** | 诊断包只脱敏主 Key，**备用 Key 明文可能进日志**并被打包分享 | 已修（主+备用全量替换，包里也列脱敏列表） |
| 4 | 中 | 主 Key 为空（只配了备用）时首请求 401 → 鉴权错误不触发切换 → **备用 Key 永远轮不到** | 已修（空主 Key 槽位剔除） |
| 5 | 低 | `KeyPoolStore.add` 用时间戳做 id，**同一毫秒加两把会互相覆盖** | 已修（UUID） |
| 6 | 中（数据完整性） | 备份/恢复**不含备用 Key 池**，恢复后全丢 | **未修**（见下） |

## 为什么第 6 项不在本批修

改备份格式 = 动用户数据路径：加 entry + manifest 字段 + 老/新备份交叉校验，
需要单独的迁移测试矩阵（老备份恢复、新备份恢复、半新半老）。
在「扫描批次」里顺手改，风险大于收益。下一批（W-23）单独做，配套 harness。

## 关键验证方式（这轮最重要的收获）

**不能只跑 fixture。** W-20 的 `web_search` harness 22/22 全绿，但 fixtures 是我按
假想结构写的——真实 DDG 在国内根本连不上，真实 Bing 的 DOM 也不是我假设的
`<h2><a>` 而是 `<a><h2>`。**harness 全绿 ≠ 功能可用。**

本轮改为：先 `curl` 真实页面 → 用真实 HTML 结构写解析 → Python 验证 → 再落 Kotlin。
Bing 实测：HTTP 200 / 61KB / 5 条结果 5/5 解析成功。

## 修复细节

### 1) web_search 双引擎（Bing 优先）
- `WebSearchParsing.parseBing`：按真实结构 `<li class="b_algo"><a href><h2>标题</h2></a>`
  + `<p class="b_lineclampN">摘要</p>` 解析；
- `realUrlBing`：还原 `/ck/a?…&u=a1<base64url>` 跳转包装（兼容 HTML 里的 `&amp;u=`）；
- `WebSearchTool`：Bing → DDG 串行兜底；两者都失败时报 `network_error` 并带各自原因，
  与「确实没搜到」(`no_results`) 区分开。

### 2) edit 回显偏移
`firstOffset = occurrences[0]`（第一处替换点之前的文本未变，偏移可直接用）。
新增回归断言：新文本在文件开头已存在时，偏移必须仍指向替换点。

### 3) 诊断包脱敏
主 Key + 备用池逐把替换；诊断包配置段新增「备用 Key（N 把）：label=masked…」。

### 4) 空主 Key 剔除
`allSlots()` 过滤空 Key；`chat()` 对空池用空 Key 走原请求（保持 401 报错，不伪造成功）。

### 5) 槽位 id
`k${UUID.take(8)}`；顺手把默认备注改成「备用 Key N」。

## 验证

- edit/websearch harness **27/27**（新增 Bing 4 条 + 偏移回归 1 条）。
- keypool harness **25/25**（未受改动影响）。
- CI：见当批 run。
- APK 反验：W22 组（parseBing / realUrlBing / cn.bing.com/search + 文案）。
