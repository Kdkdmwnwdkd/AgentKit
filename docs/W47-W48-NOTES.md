# W-47 / W-48 批次记录：安全一致性 + 插件市场

> 批次时间：2026-10-21
> 起点：`f623806`（W-46，CI 绿）
> 依据：全仓体检报告里未处理的安全/健壮性条目（W-47），以及「能力面向 Operit 对齐」的第一块（W-48）

---

## 一、W-47：安全与一致性

### 1. 备份不再打包 GitHub PAT（P1）

| 项 | 内容 |
|---|---|
| 现象 | 「导出备份」生成的 ZIP 里含 `githubPat` 明文，用户常见的存放位置是网盘 / 微信文件传输助手 |
| 风险 | 拿到备份件 = 拿到仓库读写权；PAT 还能绕开本机 |
| 修法 | 导出时 `githubPat = null`（owner/repo/workflow 保留，省去重填）；恢复时若备份不带 PAT，**沿用本机已存的 PAT**，本机也没有则整段跳过（不写半截配置）；备份页文案明确「含模型 API Key，勿外传；不含 PAT」 |
| 边界 | 老备份件仍带 PAT → 恢复照旧覆盖（兼容优先）；模型/媒体 Key 仍随备份走（恢复配置的刚需，属已知取舍） |

### 2. 备份解包按单条目限流（P1）

- 旧实现 `zip.readBytes()` 先整条读进内存，再累加总量判断 → 一个声明 500MB 的条目在检查前就把内存吃满。
- 现在：先看 `entry.size`（声明值）预拒，再用 `readEntryLimited()` **边读边计数**，单条上限 24MB、总量 64MB。
- 读异常与超限**分开处理**：读异常上报「不是有效的 ZIP」，超限上报「成员过大」——两者排查方向完全不同，不能混成一句话。
- 单条上限取 24MB 的依据：`conversations.json` 在 200 条/会话 × 多会话、含图片 data URL 时可到十几 MB；24MB 覆盖真实用量，又远低于常见 256MB 堆。

### 3. 消息列表读改写统一加锁（P2）

- `_messages.value = _messages.value + x` 有 5 处，其中 3 处（send / stop / 失败）**无锁**；并行工具回调（最多 5 路）持锁写。
- `MutableStateFlow.value` 赋值原子，但「读-拼-写」不原子：两线程读到同一旧列表，后写覆盖先写 → UI 少一张工具卡（落盘不丢，切回又出现，极难复现）。
- 统一收口到 `appendUiItem()` / `replaceUiItems()`，两处都在 `msgLock` 内；`synchronized` 可重入，事件回调内再调用不会死锁。

### 4. 全仓 JSON 硬转换清零（技术债 E1）

- 体检时 247 处 `jsonObject/jsonArray/jsonPrimitive` 硬转换，W-46 只安全化了 LLM 链路 93 处。
- 本批全量替换为 `objOrNull()/arrOrNull()/primOrNull()`：**空安全调用 173 处机械替换 + 剩余 74 处逐个人工改写**（列表元素跳过、根解析退化、MCP 协议错误）。
- 断言守护：`W47W48Verification` 的 S4 扫描全仓，出现任何硬转换即失败。

### 5. `primOrNull()` 对 `JsonNull` 的漏洞（自查发现）

`JsonNull` 在 kotlinx 里**继承 `JsonPrimitive`**，仅用 `as? JsonPrimitive` 会放行：
`?.primOrNull()?.long` 会走到 `"null".toLong()` 抛 `NumberFormatException`；`.content` 还会把字符串 `"null"` 当真实取值（工具调用 id 变 `"null"`）。
已改为 `(this as? JsonPrimitive)?.takeIf { it !is JsonNull }`，语义上「字段为 null」与「字段不存在」同解。

---

## 二、W-48：插件市场

### 目标

对齐 Operit 的插件市场体验（浏览 → 安装 → 更新 → 卸载），但不引新依赖、不降低既有安全约束。

### 结构

| 文件 | 职责 |
|---|---|
| `skill/market/MarketModels.kt` | 数据模型 + **纯函数规则**（id 白名单、URL 校验与相对解析、索引清洗、版本比较、安装态判定） |
| `skill/market/MarketClient.kt` | 受限下载（协议白名单、体积上限、SHA-256）+ 索引解析 |
| `skill/market/MarketStore.kt` | 源列表持久化（SharedPreferences，解码失败退空表） |
| `ui/SkillMarketScreen.kt` | 市场页（源管理、插件卡片、安装/更新/卸载、状态行） |
| `ui/ChatViewModel.kt` | 市场状态流与动作（刷新、增删源、装/卸） |

### 索引格式（可托管在任意静态位置）

```json
{
  "schema": 1,
  "name": "AgentKit 官方市场",
  "plugins": [
    {
      "id": "weather",
      "name": "天气查询",
      "version": "1.2.0",
      "description": "查询实时天气与未来三天预报",
      "downloadUrl": "plugins/weather-1.2.0.zip",
      "sha256": "……64 位十六进制……",
      "sizeBytes": 20480
    }
  ]
}
```

`downloadUrl` 支持相对路径（按索引地址 `URI.resolve`），市场可以整体搬家。

### 安全边界（本批的重点，共 6 道）

1. **协议白名单**：只允许 http/https，挡掉 `file:` / `content:`（否则恶意索引可让 App 读本机文件当技能装）。
2. **体积上限**：索引 512KB、包 8MB；先看 `Content-Length`，再边读边计数（分块传输没有长度，只信 header 不够）。
3. **完整性**：索引声明了 `sha256` 就必须匹配。**自查修掉一个致命 bug**：`"%02x".format(byte)` 对负数字节会输出 8 位（`ffffff80`），哈希永远对不上——已改为 `and 0xff`。
4. **id 白名单**：`[A-Za-z0-9._-]{1,64}`，与 `installZip` 的目录名校验一致。
5. **包内 id 必须等于市场 id**（自查新增）：否则「挂 A 的名、装 B 的包」会覆盖已装的 B，而市场还以为装的是 A。安装前用 `peekSkillId()` 只读探测，不一致直接拒绝。
6. **复用既有安装链路**：解压走 `installZip`（Zip Slip 校验、条目数/解压体积上限、失败回滚、导入即降权），市场只负责「取包 + 校验 + 交给它」。

### 交互细节

- 进入页面自动刷新一次（否则用户看到空列表还要自己找刷新按钮）；
- 刷新中再次请求（例如删源）→ 标记 pending，结束后**补刷一次**，避免「源删了、插件还在」；
- 添加源失败不清空输入框（失败还清空，用户得重打长地址）；
- 卸载二次确认（删目录 + 撤授权，误点一次就要重装重授权）；
- 一次只允许一个安装/卸载，按钮按 id 置灰，用户能看清正在处理哪个包。

---

## 三、三轮审查记录（每一轮都改出了东西）

| 轮次 | 发现 | 处置 |
|---|---|---|
| 第 1 轮 | ① 下载器 `ByteArrayOutputStream(-1)`（分块传输无 Content-Length 时必崩）② SHA-256 字节符号 bug ③ 安装 `onSuccess` 内抛异常会让 busy 永久卡死 ④ `sizeBytes` 把 100MB 夹成 8MB（界面撒谎）⑤ 备份读异常被误报成「过大」 | 全部修 |
| 第 2 轮 | ① 市场条目 id 与包内 id 无校验（可覆盖同名技能）② 进入页面不自动刷新 ③ 删源后旧条目残留 ④ 安装完成路径 `refreshMarketInstallState`/`reloadSkills` 在主线程读目录 ⑤ 卸载无二次确认 ⑥ harness 的 9MB 用例低于新上限，会误判通过 | 全部修 |
| 第 3 轮 | ① 刷新期间删源，结果仍是旧快照（补刷 pending）② 添加失败仍清空输入框 ③ 长 URL 未省略号 ④ 备份单条上限 8MB 可能误伤真实会话（改 24MB） | 全部修 |

---

## 四、验证

```bash
gradle :app:compileDebugKotlin --no-daemon          # BUILD SUCCESSFUL
bash scripts/qa/run-w47w48-verification.sh          # 14/14
bash scripts/qa/run-all.sh                          # 见汇总（FAIL=0）
```

行为测试覆盖：id/URL/版本/清洗规则、`JsonNull` 软转换、备份正常件与 zip 炸弹、条数校验。
结构断言覆盖：PAT 不导出、单条目限流存在、消息写入在锁内、全仓无硬转换、市场路由已接。

---

## 五、未做（明确留给后续）

| 项 | 原因 |
|---|---|
| 备份整体加密 | 涉及格式版本与口令交互，宜独立一批；当前已止血（不导出 PAT + UI 警示） |
| 模型/媒体 Key 不落备份 | 会破坏「换机恢复配置」的主场景，需产品决策（可选：口令加密） |
| Coomi 三档审批（read_only/write/destructive） | 要改 `AgentTool` 契约 + 全量重标 + 弹层改造，会改变每次操作的交互节奏，等真机验收 |
| 市场索引签名 / 官方源 | 当前只有哈希校验，没有发布者签名；等有真实发布流程再设计 |
| 市场包内版本与索引版本不一致的提示 | 只做 id 校验；版本号由作者维护，强行拒绝会误伤「忘记改版本号」的正常包 |
