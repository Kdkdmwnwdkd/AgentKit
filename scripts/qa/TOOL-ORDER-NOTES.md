# P3-5 · 工具表顺序归清单（TOOL_ORDER 并入）

> 一句话：**顺序从「两张各自为政的表」变成「清单的一个属性」**，并且这次改动在所有默认路径上零字节位移。

## 修前到底有个什么问题

「工具按什么顺序发给模型」这件事，有**两个互不相干的答案**：

| 路径 | 顺序来自 | 有没有防线 |
|---|---|---|
| 云端（`plan().visible` → 请求体） | `ChatViewModel` 里 40 行 `register(...)` 的**书写顺序** | 没有。`list()` 是 `LinkedHashMap.values`，插入序即报文序 |
| 端侧小模型（`LocalAgentPrompt.build`） | `LocalAgentPrompt.ORDER` 手写表 | 有（`ORDER.indexOf` 排序，且与清单做过交叉比对） |

所以同一个 bug 在两条路径上的命运不同：

- 有人在 `register` 列表**中间**插一个工具 → 云端每轮请求体从那个位置开始整体位移 →
  KV 前缀失效（整段 prefill 重算，云端按量计费、本地几秒白等）→ **没有任何断言、日志、
  度量会响**。这类回归只会表现为「今天怎么变慢了」。
- 同样的改动对端侧没有任何影响（ORDER 兜住了）。

而 P3 的整个主张是「清单是唯一真源」——`CapabilityManifest.toolNames` 的注释甚至已经
写着「顺序即工具表顺序」，但那是**一句空话**：没有任何代码读它来排序。

## 修后

```kotlin
// CapabilityManifest
fun rankOf(name: String): Int = rankOfTool[name] ?: UNRANKED     // 未登记 → 排在最后
fun sort(tools: List<AgentTool>) = tools.sortedWith(compareBy({ rankOf(it.name) }, { it.name }))

// KernelExposure.planLocked
val business = CapabilityManifest.sort(all.filter { groupOf(it.name) in active })
val visible = business + metaTools(all, active)                   // 元工具仍固定在最后

// LocalAgentPrompt
private fun sorted(tools: List<AgentTool>) = CapabilityManifest.sort(tools)   // ORDER 已删
```

三条设计取舍：

1. **未登记排最后、彼此按名字排**。未登记的是 MCP 动态工具与插件——它们的位置不该由
   「谁先连上服务器」决定，否则同一台设备两次启动的工具表就可能不同，KV 前缀跟着抖。
2. **`LocalAgentPrompt` 自己再排一次**，不信任入参顺序。排序幂等，多一次没有副作用；
   而 `build()` 未来可能被新调用方直接喂未排序列表，那时提示词就逐轮抖了。
3. **元工具不参与排序**，仍然追加在末尾。它们是「查 / 开」的入口，排在业务工具后面符合
   阅读顺序；更重要的是它们**不该**因为在清单里没登记而被排到未登记工具那一堆里去。

## 位移评估（诚实数）

用真实注册顺序与清单顺序对齐，51 个已注册工具里 **50 个位置发生变化**：

```
旧前 6：http_request, read_file, write_file, edit_file, web_search, clipboard
新前 6：read_file, write_file, edit_file, ui_tree, ui_tap, ui_long_press
```

但**默认路径一个字节都没变**：

- 云端默认只激活 `file` 组（`DEFAULT_GROUPS`）→ 工具表 = `read_file, write_file, edit_file`
  + 元工具；这三个在旧实现里恰好也是注册顺序。
- 端侧 `TINY` 同样只给 `file` 组 → 提示词里同样只有这三行。

也就是说：**日常对话零位移，只有「全量模式（开发者开关）」和「多组同时激活」的场景才会变一次**。
那一次失效的代价 = 一次完整 prefill，之后顺序就固定下来了。这是本次改动唯一需要接受的成本，
而它换来的是「注册顺序再怎么洗牌，报文都不动」——`ChatViewModel` 那 40 行从此可以随便重排。

## 断言（每条都能红）

`KernelExposureVerification` 新增第 9 段，共 12 条；这一节把 `LocalAgentPrompt.kt`
也纳入编译单元——它此前**只有 grep，没有一次真的跑过 `build()`**。

| 断言 | 防的是什么 |
|---|---|
| 全量模式下已登记工具严格按清单顺序 | 排序没生效 / 排错表 |
| 确实重排了（不是照抄入参序） | 「排序」写成恒等映射也会绿的那种断言 |
| 打乱注册顺序后工具表逐字节相同 | 顺序又回到注册表手里 |
| 与基准全量表的顺序相同 | 两次 plan 之间顺序漂移 |
| 元工具在最后两位 | 元工具被排进业务工具中间，白白打断前缀 |
| 未登记工具排在已登记之后、元工具之前 | MCP 工具插到核心工具前面 |
| 默认云端路径零位移 | 顺手把日常路径也改了（本次最重要的回归线） |
| 提示词在乱序输入下逐字节相同 | 提示词层没走清单 |
| 提示词行序与清单顺序一致 | 两条路径各排各的（就是修前那个 bug） |
| 未登记工具排在提示词末尾 | 同 ④，提示词侧 |
| 提示词行数 = 工具数 | 渲染丢行 / 多行（小模型会照着不存在的行调用） |
| 单行格式稳定 | 改顺序时顺手改了行格式（影响 token 与解析） |

反向验证跑过一遍：把 `CapabilityManifest.sort` 改成 `tools`（恒等），**7 条立刻变红**
（全量顺序 / 确实重排 / 打乱相同 / 基准相同 / 提示词乱序相同 / 行序一致 / 未登记在末尾）。

## 顺带修掉的两处

1. **`run-local-agent-prompt-verification.sh` 的中文检查在扫整个源文件**。
   它用 `请|不要|如果|可以` 判断「提示词里不该有中文长句」，但扫的是文件而不是提示词——
   我在注释里写「每轮请求体」，「请求体」里的 **请** 就把这条断言打红了。
   现在只抽取 `build()` 函数体里的字符串字面量来判定：查的是提示词本身，不是注释。
2. **`CapabilityManifest.toolNames` 的注释从空话变成事实**，并写清了为什么顺序必须有
   唯一拥有者（上面那张表就是理由）。

## 附带的第三件事：`tool-surface-audit.py` 原来也在手抄清单

这个脚本（量「真实工具描述占多少 token」的那个）自带两张镜像表：`GROUP_PATCH`（补丁归组）
与 `GROUP_NAMES`（有哪些组）。P3-3 给清单加了 `mcp` 组之后，这两张表没跟上——**同一个病，
第二次**：同一份知识、两个副本、一个改一个不改。

现在它直接解析 `CapabilityManifest.kt`：组名（含 `MISC` 这种常量名）、成员、`pinned`、
`dynamic` 都以清单为准。解析器遇到读不懂的组声明会**当场退出并报出片段**，不再静默跳过
（兜底桶 `CapabilityGroup(MISC, ...)` 一开始就是被静默跳过的那一个：`misc` 因此从
`PINNED` 里消失，报表却看不出来）。

## 第四件事：工具面预算从「表里的数字」变成「会红的门」

架构方案「度量」表写着「工具面默认 token ≈525，目标 ≤525（不退化）」。在这之前**没有任何
断言读这个数**——把某个工具的描述从 30 字补到 120 字，每轮成本就涨了，而全套 harness 一片绿
（它们验分组，不验钱）。

`tool-surface-audit.py --gate` 现在卡三条：

| 门 | 阈值 | 现值 |
|---|---|---|
| 默认面（云端只开 file） | ≤ 525 token | **525**（零余量，涨一点就红） |
| 满载（file + 最重的 2 组 = ui/shell） | ≤ 3500 token | 3396 |
| 清单覆盖 | 每个工具都登记 | 56/56 |

`run-tool-surface-verification.sh` 除了跑门，还**反向跑一次**（把预算压到 1 token，必须 exit≠0）：
一道从没红过的门和装饰没有区别。门槛故意不留余量——留了就等于允许「每次一点点」地涨。
要涨可以，改常量，把代价写进提交信息。

## 断言基线

`run-local-agent-prompt-verification.sh` 37 → 38（+1）。新增的那条是
「本文件不得再自带 `private val ORDER`」——**反向断言没有行为可调**，只能看源码；
这正是 `assertion-baseline.txt` 头部写明的正当理由。其余全部是行为断言，
`KernelExposureVerification.kt` 仍是 10 条文本断言，未抬高。

## 验证

| 项 | 结果 |
|---|---|
| 暴露面 harness（含新第 9 段） | **80 / 0**（原 68） |
| 本地提示词静态契约 | 43 / 0（原 42） |
| 工具面预算门（含返向验证） | 3 / 0 |
| 断言普查 | 1373 条，只减不增（0 失败） |
| 全量 run-all | **PASS=85 SKIP=1 FAIL=0**（原 84） |
| 构建机 `compileDebugKotlin` / `assembleDebug` | 通过 / 28.3 MB |
