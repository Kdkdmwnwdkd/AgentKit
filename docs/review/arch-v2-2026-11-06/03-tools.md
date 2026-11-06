# 03 · 工具 / 技能 / 能力面评审（只读）

评审对象：`/workspace/AgentKit` HEAD `7dd0f88`；范围 `core/tool/ToolCatalog.kt`（R-13 后唯一真源）、`tools/` 24 文件 5,061 行、`core/capability/` 6 文件 1,005 行、`skill/` 4 文件 1,397 行 + `skill/market/`。未改仓库、未跑 gradle。

## 结论摘要

1. **单一真源的主干确已落地，但"审批"这一列没有收口。** `CapabilityManifest`、`CapabilityRegistry`、`ApprovalGrouping` 三处都已是派生视图（无第二份名单），`ToolCatalog.tools` 是 56 条、16 个 `CatalogGroup`（含 `misc`；题干"57/17"是把 `grep` 匹配到的 `data class CatalogTool(` / `data class CatalogGroup(` 也算了一条）。但"要不要弹窗"的判据仍在工具类的 `needsApproval`，与目录的 `approval` 列构成**双写**；20 条带 `approval` 的条目里有 5 条是死元数据（`ui_tree`/`app_stop`/`speak`/`send_notification`/`github_status`），永远不会弹窗。
2. **新增一个工具今天要改 3 处，要审批的要改 4 处**：工具类（name/description/parameters/readOnly/needsApproval）→ `ToolCatalog` 一行 → `ChatViewModel.register(...)`；若涉密/敏感/子代理可调用，还要分别改 `LocalAgentPrompt.HINTS`、`AgentLogger.sensitiveTools`、`SubAgentLogic.NO_SIDE_EFFECT_TOOLS`，另有 3 张旁表（HINTS / sensitiveTools / 子代理白名单）同样靠人记。
3. **启动对账有一半是自证。** `CapabilityAuditReporter.report` 跑两条：`CapabilityManifest.audit(tools)` 是真的（注册表 ↔ 派生清单），`CapabilityRegistry.audit(CapabilityManifest.toolNames)` 两边同源，**恒为空**；`ToolCatalog.audit` 本身只在 harness 跑，生产启动不调用。
4. **工具面成本（实测脚本）**：全量 56 个 = 21,808 字符 / 9,761 token（另含元工具约 519 字符 / 250 token）；默认面只有 `file` 3 个 = 1,243 字符 / 525 token；粘性激活有硬上界——`MAX_ACTIVE_GROUPS=2` + 常驻 `file`/`misc`，最坏 18 个 / 6,821 字符 / 3,001 token。上界是**按组数**而不是按字符：`mcp` 组最多 60 个工具，开它一次就能把上界撑破。
5. **`shell_destructive` 可被包装命令绕过。** `busybox rm -rf /sdcard/x`、`env rm -rf`、`python -c "os.system('rm -rf ...')"` 的首词不在 `DESTRUCTIVE_HEADS`，被判为"普通 shell"，落进**可记忆**的 `shell` 组；用户此前为 `npm install` 勾过一次"不再询问"，之后这类命令不再弹窗。
6. **"MCP 每次调用都需确认"不属实。** `ApprovalScope.of` 对目录外工具（含 `mcp_*`）返回 `memorizable=true`，键 = 工具名；用户点一次"本次会话不再询问"后整会话放行。目录里给模型看的那句组说明（"每次调用都需确认"）与实际行为不符。
7. **R-07 回卷语义成立**（`registerOwned` 整体替换、`unregisterOwner` 按 owner 名册删、先撤后连），停用服务器后工具确实消失。但**同名冲突会永久偷走工具**：`mcpWireName` 把服务器名截断到 12 字符，撞名时后者覆盖并从前者的名册里摘除；赢家随后被停用时，输家的 `installed` 指纹仍记"已连"，不会重连，工具就此消失到进程重启。
8. **技能面的补偿控制是真的**：`load_skill` 虽判 SAFE 且无审批，但安装器**无条件**写 `trust=imported` 并撤销该 id 的脚本授权，下载来的 JS 不能执行，必须用户到技能中心显式开授权。残留风险是另外三条：https 校验只作用于**初始 URL**（OkHttp 默认跟随重定向到 http/内网）、可静默 `deleteRecursively` 覆盖同名已装技能、`skill.json` 双条目可让目录名与 manifest.id 分叉。
9. **脚本桥存在两处功能性死结**：`agent.writeFile` 走 `write_file`，必然被第三道闸（需审批工具不得代调用）拒绝；`agent.notify` 传 `text`，而 `NotificationTool` 读 `body`，通知正文恒为空。
10. **"同一份事实写多处"仍在工具描述层复发**：`container_toolchain` 的 `action` 有 4 份描述（description / 无 enum / `when` 三分支 / 错误提示只说两个取值），其中错误提示是**错**的；`R-20` 的 `schemaValues()` 只覆盖 `TerminalIdentity`，其余 8 处枚举仍是字面量。

## 关键实证

> 行号均相对仓库根；"✔"= 直接读源码确认，"○"= 由代码语义推断且未实测（验证方式见表后说明或建议列）。

### Q1 单一真源

| 结论 | file:line | 状态 |
|---|---|---|
| Q1 目录是唯一元数据表：56 条 `CatalogTool` / 16 个 `CatalogGroup`（含 misc、mcp 动态组） | `core/tool/ToolCatalog.kt:78-95`, `:102-173` | ✔ |
| Q1 `CapabilityManifest` 从目录派生，不再持有名单 | `core/capability/CapabilityManifest.kt:72-80` | ✔ |
| Q1 `CapabilityRegistry` 只剩查询（`cap`/`knownTools`），无手写分级表 | `skill/SkillSecurity.kt:40-50` | ✔ |
| Q1 `ApprovalGrouping.groupOf` 读 `approvalOf`，旧 when 表已删 | `core/agent/ApprovalGrouping.kt:47` | ✔ |
| Q1 启动对账只跑 Manifest 审计 + Registry 审计 | `ui/ChatViewModel.kt:1012`, `core/capability/CapabilityAuditReporter.kt:40-42` | ✔ |
| Q1 `CapabilityRegistry.audit` 入参与真源同源 → 恒空（自证） | `CapabilityAuditReporter.kt:42` + `SkillSecurity.kt:78-94` | ✔ |
| Q1 `CapabilityManifest.audit` 的"归组一致"在派生视图下也恒真（resolve 先查同一张 map） | `CapabilityManifest.kt:186-190`, `:223-229` | ✔ |
| Q1 `ToolCatalog.audit` 生产未调用，只出现在 harness | `scripts/qa/R13CatalogVerification.kt:146`（全仓仅此处） | ✔ |
| Q1 新增工具仍需手写 `register(...)`（45+ 处） | `ui/ChatViewModel.kt:835-946` | ✔ |
| Q1 目录外的工具名旁表：本地提示 HINTS（20 条） | `core/llm/local/LocalAgentPrompt.kt:45-66` | ✔ |
| Q1 子代理白名单（15 条，决定子代理能见哪些工具） | `core/agent/SubAgentLogic.kt:59-74` | ✔ |
| Q1 日志脱敏白名单（7 条，漏登记即明文落盘） | `core/AgentLogger.kt:109-118` | ✔ |
| Q1 `labelOf` 仍是 8 分支 when：新增一个审批归组要改目录 + 常量 + 文案三处 | `core/agent/ApprovalGrouping.kt:50-60` | ✔ |
| Q1 目录外工具的归组靠前缀回落、兜底 misc（动态工具不会消失） | `core/tool/ToolCatalog.kt:216-220` | ✔ |

### Q2 工具面成本

| 结论 | file:line | 状态 |
|---|---|---|
| Q2 全量 21,808 字符 / 9,761 token；默认 1,243 / 525；最坏 18 个 6,821 / 3,001 | `scripts/tool-surface-audit.py` 实跑输出 | ✔ |
| Q2 粘性上界 = 2 个可淘汰组 + `file`/`misc` 常驻 | `core/KernelExposure.kt:60`, `:105-106`, `:223-231` | ✔ |
| Q2 `misc` 常驻且无成员上限（新组数不涨、字符可涨） | `KernelExposure.kt:196` | ✔ |
| Q2 `__find_tools` 找不到时回吐全量 56 个工具名（一次性 ~1.5k 字符） | `KernelExposure.kt:270-277` | ○ 未量测具体字符 |
| Q2 工具自带 `systemPrompt` 片段（2,112 字符）随可见面一起裁剪，不额外常驻 | `core/agent/RealAgentLoop.kt:1299-1307` | ✔ |
| Q2 初始组在两处各自硬编码 `"file"`（DEFAULT_GROUPS / TINY_GROUPS） | `KernelExposure.kt:51`, `:79` | ✔ |

### Q3 审批与安全

| 结论 | file:line | 状态 |
|---|---|---|
| Q3 审批闸在 `needsApprovalFor(args)`，不看目录 | `core/agent/ToolExecutor.kt:342` | ✔ |
| Q3 `ApprovalScope` 只对 `shell_run`/`shell_background` 做入参分档 | `core/agent/ApprovalScope.kt:53`, `:55-68` | ✔ |
| Q3 `memorizable=false` 仅覆盖 shell 不可逆档；`allows` 对它永远 false | `ApprovalScope.kt:64-68`, `:72-73` | ✔ |
| Q3 破坏性首词表 + 子命令判定（不含 `busybox`/`env`/`nohup`/解释器） | `core/agent/ShellCommandRules.kt:136-162` | ✔ |
| Q3 5 条死审批：ui_tree（仅 readOnly）/app_stop/speak/send_notification/github_status | `automation/UiAutomationTools.kt:124-134`、`tools/AppControlTools.kt:631-643`、`tools/SpeakTool.kt:18-35`、`tools/BuiltInTools.kt:408-422`、`tools/GitHubGitTools.kt:105-121` | ✔ |
| Q3 B4 只校验 `needsApproval=true → 有 approval`，反向不校验（故死元数据能过门） | `scripts/qa/run-r13-catalog-verification.sh:105-129` | ✔ |
| Q3 MCP 每组必确认的注释，与 `memorizable=true` 实现相悖 | `core/tool/ToolCatalog.kt:93` vs `ApprovalScope.kt:58` | ✔ |
| Q3 截图额外享受"本回合缓存"，连"允许一次"都不再问 | `ui/ChatViewModel.kt:1636`, `:1651` | ✔ |
| Q3 审批等待有 30s 超时，超时按拒绝（文档 P0 已落地） | `core/agent/ApprovalDeadline.kt:33-36` | ✔ |
| Q3 "始终允许"只对 `memorizable` 落白名单（不可逆命令仅放行本次） | `ui/ChatViewModel.kt:1673-1676` | ✔ |

### Q4 注册回卷

| 结论 | file:line | 状态 |
|---|---|---|
| Q4 `registerOwned` 整体替换 + 幂等 + 内置优先（skipped） | `core/ToolRegistry.kt:65-102` | ✔ |
| Q4 `unregisterOwner` 按 owner 名册删，不靠前缀猜 | `ToolRegistry.kt:108-119` | ✔ |
| Q4 先撤后连：地址变更即撤，连不上就"没有工具"（不是旧工具） | `core/mcp/McpToolSync.kt:62-67`, `:128-132` | ✔ |
| Q4 撞名时后者覆盖并从前 owner 名册摘除（静默偷走） | `ToolRegistry.kt:88-98`（第 92 行） | ✔ |
| Q4 撞名根因：服务器名前缀截断 12 字符，用户可填任意名 | `core/mcp/McpToolAdapter.kt:107-108` | ✔ |
| Q4 一次 sync 失败不写 `installed`，但只在下次触发时重试；UI 只看到计数 | `McpToolSync.kt:161-163`, `ui/ChatViewModel.kt:1473-1483` | ○ 重试时点未实测 |
| Q4 配置读取在 binder 锁之外，两次快速启停可能反序应用 | `ChatViewModel.kt:1469-1472` | ○ 并发时序未实测 |
| Q4 `sync` 有 Mutex 串行化；但 `activeOwners()` 无锁读 `installed` | `McpToolSync.kt:123-125`, `:177` | ✔ |
| Q4 停用/删除服务器的回调确实存在（MCP 页变更后触发） | `ui/AppScaffold.kt:661`, `ChatViewModel.kt:1014` | ○ UI 回调时点未实测 |

### Q5 技能

| 结论 | file:line | 状态 |
|---|---|---|
| Q5 JS 沙箱：ClassShutter 屏蔽 java./javax./宿主类，解释执行 + 指令看门狗 | `skill/SkillExecutor.kt:70-77`, `:119-147` | ✔ |
| Q5 三档能力上限：packaged=DANGEROUS / imported=PRIVILEGED / remote=SAFE，未知按 SAFE | `skill/SkillSecurity.kt:141-147` | ✔ |
| Q5 脚本三道门：白名单 → 能力门 → 需审批工具禁止代调用 | `SkillExecutor.kt:310-351` | ✔ |
| Q5 `agent.writeFile` 撞上第三道门，必然 `script_approval_denied` | `SkillExecutor.kt:436` + `tools/BuiltInTools.kt:329` + `:344` | ✔ |
| Q5 `agent.notify` 传 `text`，工具读 `body` → 正文恒空 | `SkillExecutor.kt:447` vs `BuiltInTools.kt:422` | ✔ |
| Q5 安装强制降权 + 撤销授权（下载的 JS 不可执行） | `skill/SkillImportRepository.kt:123-127` | ✔ |
| Q5 Zip Slip / zip bomb 双保险（`..` + canonicalPath + 边写边计数） | `SkillImportRepository.kt:222-258` | ✔ |
| Q5 id 白名单 `[A-Za-z0-9._-]+` + 目录越界校验 | `SkillImportRepository.kt:105-110` | ✔ |
| Q5 落盘后只校验 name，不复查 id（双 `skill.json` 条目可分叉） | `SkillImportRepository.kt:99-101`, `:120-121` | ○ 未构造样本验证 |
| Q5 https/内网校验只做在初始 URL，下载用默认跟随重定向的 OkHttp | `tools/LoadSkillLogic.kt:20-31` + `ui/ChatViewModel.kt:3839` + `core/llm/LlmHttp.kt:61-68` | ✔ |
| Q5 `install_url` 可 `deleteRecursively` 覆盖同名已装技能，无审批 | `SkillImportRepository.kt:114` | ✔ |
| Q5 市场允许 http、sha256 可选（无签名） | `skill/market/MarketModels.kt:130-138`, `:170-171` | ✔ |
| Q5 非 packaged 来源即使有 entry 也强制回退提示词形态 | `SkillExecutor.kt:513-520` | ✔ |
| Q5 下载体积双限（header + 边读边计数 2 MB） | `ui/ChatViewModel.kt:3848-3862` | ✔ |
| Q5 市场索引下载与包下载同样只校验初始 URL、跟随重定向 | `skill/market/MarketClient.kt:63-76` | ✔ |

### Q6 描述与 schema

| 结论 | file:line | 状态 |
|---|---|---|
| Q6 `R-20` 只做了 terminal identity 一处 | `core/terminal/backend/TerminalIdentity.kt:141` | ✔ |
| Q6 `container_toolchain` 的 action 无 enum，错误提示缺 `versions` | `tools/ContainerToolchainTool.kt:44-51`, `:105` | ✔ |
| Q6 `ui_key` 实现接受 recents/overview，schema 与提示只列 3 个 | `automation/UiAutomationTools.kt:363`, `:378-380`, `:389` | ✔ |
| Q6 `load_skill` 的 action 在同一文件写 4 遍 | `tools/LoadSkillTool.kt:52-56`, `:74-89`, `:88` | ✔ |

## 结构性问题

**A 级（安全 / 授权语义，建议本轮修）**

- **A1 审批双写导致 25% 的审批声明是空文。** 目录说 `app_stop`/`ui_tree`"需确认"，闸门只问工具类。`ui_tree` 一次能吐出 1 万～4 万字符屏幕内容（`AgentAccessibilityService.kt:415` 有真机注记），却连一次弹窗都没有；`app_stop` 是 DANGEROUS + APP_MANAGE 档，模型可无提示强停任意应用。这不是"漏了一个 if"，是**两处声明谁都不同步**——B4 只查一个方向，所以永远绿。
- **A2 MCP 的"每次必确认"是文案承诺，不是实现。** 一次勾选即整会话放行，且弹窗文案会退回工具名（"本次会话不再询问 mcp_foo_bar"），正是 R-13 声称已经修掉的那种半句英文。风险在于 MCP 工具的参数常带用户数据（外发通道）。
- **A3 `shell_destructive` 的判定是"首词黑名单"，本质是可枚举的规避面。** 反例（可直接照抄）：`busybox rm -rf /sdcard/Download`、`env rm -rf ~`、`nohup rm -rf x`、`python -c "import os;os.system('rm -rf /sdcard')"`。四者 `segmentIsDestructive` 全 false → `memorizable=true` → 与 `npm install` 共用 `shell` 键 → 一次授权永久放行。注意它不会"绕过弹窗"，而是**把不可逆命令降级进可记忆档**，这比漏判更隐蔽。另 `cp -f`、`git checkout -- .`、`git branch -D` 也不在不可逆表内。

**B 级（结构 / 可靠性，可与 A 同批）**

- **B1 启动自检有一半在自证。** `CapabilityRegistry.audit` 恒空；`CapabilityManifest.audit` 的 ④ 在派生视图下恒真。真正有信息量的只剩 ①②③⑤⑥，而 `ToolCatalog.audit` 独有的"组断成两段""动态组混入静态成员"两条**生产从不跑**——偏偏前者直接决定工具表字节序与 KV 前缀。
- **B2 MCP 撞名会静默丢工具，且不自愈。** 服务器名截断 12 字符 + 用户可自定名 + `registerOwned` 直接覆盖 → 后者偷走前者；赢家停用后，输家的指纹仍是"已连"，`planMcpSync` 判 unchanged 不重连。诊断信息只有一行计数日志。
- **B3 上界是"组数上界"，不是"体量上界"。** `misc` 常驻无上限；`mcp` 单组上限 60 个工具（`McpToolSync.kt:115`）。开 `mcp + ui` 合法（2 个可淘汰组），字符量可远超实测的"最坏 6,821"。
- **B4 五张工具名旁表没有守卫。** 分别是 `LocalAgentPrompt.HINTS`（20 条）、`SubAgentLogic.NO_SIDE_EFFECT_TOOLS`（15 条）、`AgentLogger.sensitiveTools`（7 条）、`LocalToolCallParser` 的同义词表、`ApprovalScope.SHELL_TOOLS`（2 条）。新工具若属敏感类而漏进 `sensitiveTools`，日志明文落盘；漏进子代理白名单，子代理看不见它；漏进 HINTS，本地模型只能靠名字猜；漏进 `SHELL_TOOLS`，其命令内容永不参与破坏性判定。五处都不会红。

**C 级（一致性 / 可维护性）**

- **C1 枚举事实仍在 4 处写**（描述 / schema / 分支 / 错误提示），且已经漂移：`container_toolchain` 的错误提示少一个合法取值，`ui_key` 的 schema 少两个实现接受的别名。R-20 的 `schemaValues()` 只赢了 `identity` 一个战场。
- **C2 技能桥两处死结**（`writeFile` 永远被拒、`notify` 正文恒空）。前者更像是"第三道门上线时没回头改桥"，是设计演进的遗留，不是恶意行为。
- **C3 `load_skill` 的覆盖写无审批。** 模型可把用户已装的某技能整目录替换成任意内容（授权会被撤销，所以不会立刻变可执行，但内容/提示词已被替换，属于提示注入面）。

## 与 v1.0 文档的差异

1. §5 写"新增工具只剩两处：工具类 + 目录一行"——实测 **3 处**（还要 `register`），需审批时 4 处，安全相关旁表另计 3 处。文档把"元数据单一真源"说成了"工具接入单一真源"。
2. §5 说"漏改由 `run-r13-catalog-verification.sh` 判红"——该脚本 **B4 只查单向**（需审批 → 有归组），反方向的 5 条死元数据全绿。
3. §4.2 的七道闸顺序与实现一致（`ToolExecutor.kt:280-395`：可见性 → 熔断 → 审批 → 落盘 → 执行 → 截断），这一条 ✔。
4. §9 P1-5 "回卷已完成"成立；但文档未提"撞名偷工具"与"无重试"两个已知边界。
5. 附录 B "56 工具 / 15 组 / 22,327 字符 / 10,064 token"与实测吻合（21,808 + 元工具 519）。"15 组"是非 `misc` 组数；目录里 `CatalogGroup` 实为 16 个（含 `misc`），题干"17"多算了 `data class` 声明行。
6. 目录里给模型看的 `mcp` 组说明写着"每次调用都需确认"（`ToolCatalog.kt:93`）——与实现冲突，属于**文档与代码同时错**的一处。
7. R-20 的口径是"schemaValues() 覆盖全部后端枚举"——实际只覆盖 `TerminalIdentity`；工具层 8 处 enum 仍是字面量。

## 建议

| # | 落点（零新增依赖，全在现有骨架） | 验收判据 | 量级 |
|---|---|---|---|
| S1 | 审批收口：`RegistryToolExecutor` 的闸门改为 `tool.needsApprovalFor(args) \|\| ToolCatalog.approvalOf(tool.name) != null`（保留 shell 的入参细分），并把 5 条死元数据按意图二选一：补 `needsApproval` 或删 `approval` | B4 增加反方向断言"有 approval ⇒ 该工具会弹窗"；`ui_tree`/`app_stop` 真机各弹一次；R13 harness 全绿 | +25 行 +1 harness 断言 |
| S2 | MCP 不可记忆：`ApprovalScope.of` 对 `group == "mcp"` 或 `name.startsWith("mcp_")` 返回 `memorizable=false`，label 固定"外部 MCP 工具" | 连续两次调同一 MCP 工具，第二次仍弹窗；`memoLabel` 不含"不再询问" | +8 行 |
| S3 | shell 破坏性判定加"解包前缀"：`busybox/toybox/env/nohup/time/stdbuf/command` 递归取下一词；并在 `ApprovalScope.allows` 里对 `shell` 组**再跑一次** `isDestructive(command)`，命中即视为不可记忆（把"记忆"从键命中改为键命中+内容复检） | 用 `busybox rm -rf`/`env rm -rf`/`python -c` 三例断言 `memorizable=false`；已记忆 `shell` 组下仍弹窗 | +30 行 +3 断言 |
| S4 | 让审计说真话：`CapabilityAuditReporter.report` 增加 `ToolCatalog.audit(registry.names())`，删掉恒真的 `CapabilityRegistry.audit(CapabilityManifest.toolNames)`（或改为传注册表名） | 故意把一条目录记录的组改名，启动日志出现 1 条 WARN；harness 造一个"组断两段"的假表必红 | +6 行 −1 调用 |
| S5 | MCP 撞名可见化：`OwnerSync.skipped` 拆出 `collided`；`mcpWireName` 在撞名时追加 serverId 的短哈希；`planMcpSync` 以 `registry.ownerOf` 的实际持有为准而非 `installed` 指纹 | 两台同名服务器（名长 >12 字符）各暴露 `search`：两个工具名不同、均可调用；停用其一，另一个仍在 | +25 行 |
| S6 | `misc` 与 `mcp` 体量上界：`enforceCap` 之后对 `misc` 做成员数上限（如 20），`KernelExposure.plan` 对超限部分按清单 rank 取前 N；`McpToolBinder` 的 60 上限改为"字符预算"（复用 `ContextBudget.estimateTokens`） | 注入 40 个未知前缀工具，默认面字符不再增长；harness 断言 `plan` 结果字符 ≤ 阈值 | +30 行 |
| S7 | 技能安装两条硬约束：① 下载 `OkHttp` 关 `followRedirects/followSslRedirects`，或每次 `Location` 后重跑 `validateUrl`；② 落盘后按**目录名**复写 `skill.json.id`，消除 id 分叉 | 单测：302 到 `http://192.168.1.1/x.zip` 被拒；双 `skill.json` 样本安装后 `peekSkillId == 目录名` | +20 行 +2 断言 |
| S8 | 枚举收口（先把已漂移的两处修掉，再谈推广）：`container_toolchain.action`、`ui_key.key` 改成 enum + `schemaValues()`，description/提示全部由它拼接 | harness 断言"错误提示里的取值集合 == schema enum"；`container action=versions` 的提示不再说"只能是 status 或 install" | +40 行（每处 ~15 行） |

排序建议：S1/S2/S3 是授权语义（先做，改动小、可感知）；S4 是让后续所有守卫可信（紧随）；S5/S6 是 MCP 与成本的边界；S7/S8 可以并到下一批。

## 证据边界

- **✔ 已复核**：本文所有 file:line 均直接读源码确认；工具面字符/token 由 `python3 scripts/tool-surface-audit.py` 实跑一次（允许的只读脚本，输出未改仓库）；目录规模用脚本对 `ToolCatalog.kt` 正则计数并与 `override val name` 双向比对（56 ↔ 56，无缺无余）。
- **○ 推断（未实测）**：MCP 撞名的完整时序、`sync` 两次并发反序应用、双 `skill.json` 条目能否真的落成 id 分叉、`__find_tools` 回退文案的实际字符量。验证方式已在表中逐条写明（构造 zip / 两台同名服务器 / 并发调用 `refreshMcpTools`）。
- **未验证**：真机弹窗行为、OkHttp 重定向的实际跟随链、MCP 服务器真实返回。沙箱无 Android SDK，未跑 gradle、未跑任何 `*Verification.kt` harness；本报告中"实现行为"的结论均来自静态阅读，运行时语义需按上表复测一次。
- **口径说明**：`57 工具 / 17 组` 来自 `grep -c` 把 `data class` 声明行一并计数；真实为 **56 个工具、16 个 `CatalogGroup`（含 `misc`；其中 `mcp` 为动态组、`misc` 为空兜底）**，与 `tool-surface-audit.py` 的 56 一致。
