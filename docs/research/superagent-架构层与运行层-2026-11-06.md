# Super-agent 原型：架构层与运行层对照

> 核对日期 2026-11-06 ｜ 对象 [`deepseek-ai/dsh-superagent-prototype`](https://github.com/deepseek-ai/dsh-superagent-prototype)
> 源码 110 文件 / 15,205 行 TypeScript，文档 1,973 行，MIT，20,005★，2026-10-31 创建
> 配套阅读：`docs/AgentKit-架构方案-v2-2026-11-06.md`、`docs/AgentKit-参照谱系-2026-11-05.md`

---

## 0. 先厘清「10 月开源的那个」是什么

三样东西经常被混成一件事，实际上不是：

| 东西 | 时间 | 是什么 | 与本项目的关系 |
|---|---|---|---|
| `deepseek-ai/deepseek-harness` | 10-02 公开预览 | 智能体宿主框架，Cordis「一切皆插件」，27.4 万★ | 谱系文档 §3 已按它对齐，结论仍成立 |
| **`deepseek-ai/dsh-superagent-prototype`** | **10-31** | **跑在 harness 上的一个 bundle：监听 GitHub/飞书，自己开工作项、开 worker 会话、提 PR** | **本文对象。这才是「10 月开源的那个架构」** |
| 《Super-agent 设计探索》技术文章 | 10-31 | Router/Worker 双层 + 五大模块的设计论述 | **文章 ≠ 原型**，见 §7 |

README 第一句就写了：*"Some designs discussed in the article are directions for future development and are not implemented in this prototype."*
这句话很重要——照文章去设计，会设计出一个手机端根本跑不动的东西（见 §6）。

---

## 1. 一句话结论

**该学的是它的「写者表」和「副作用队列」，不该学的是它的「Router/Worker 双层」。**

前者是两条零成本的纪律，正好打在 v2 认定的系统性缺陷（同一事实多个真源）上；
后者在代码里只是两个**提示词预设常量**（`src/app/sessions.ts:48`），不是两个进程、不是两层服务。
把它当架构层去实现，等于在手机上复刻一个服务端编排系统。

---

## 2. 架构层：五条硬规则

它的架构层不靠图，靠五条能被机械检查的规则。这是它最值钱的部分。

| # | 规则 | 落点 | 具体到什么程度 |
|---|---|---|---|
| 1 | **每个状态只有一个写者** | `docs/007-architecture.md:259-271` | 列了 10 类状态与唯一 writer：Trigger/Thread/Decision/Ticket/Assignment→Engine；设置→Settings；提示词版本→Prompts；effect 行→Effects；出站回执→Outbox；健康条目→认领者（`HealthBoard` 拒绝第二次认领） |
| 2 | **命令全同步，事务包住聚合** | `src/core/engine.ts` 全文 **0 处 `async`/`await`**；每个方法体是 `this.repo.atomic(() => ...)`，如 `engine.ts:48/100/135/160` | 端口在**事务内**入队外部工作，适配器在**提交后**执行（`effects.ts:61` 的 `enqueue` 被命令调用） |
| 3 | **角色化窄接口** | `docs/007-architecture.md:64` | 调用方只拿到自己那一片 `Pick`：`EngineForSources` / `ForMainSession` / `ForPeople` / `ForSettings` / `ForExecutor`。没有「全量 Engine」流出 |
| 4 | **命令用祈使，事实用过去式** | `docs/007-architecture.md:272` | `stopTicket` / `bindThread` 是请求，可被拒绝；`assignmentStarted` / `deliveryFailed` 是适配器报告的事实，不可拒绝。**动词时态即语义**，读代码时不会把「已发生」误当「想发生」 |
| 5 | **core 不 import 外部、不做 I/O** | `docs/007-architecture.md:258` | `src/core` 除 Node 的 hashing 外零外部依赖；`src/sources` 不 import `src/app`；面板只 import 类型 |

还有一条隐含的：**拒绝是枚举码，不是字符串**。`UserError(code, values)` 来自 `USER_ERRORS`，HTTP 层把 `missing` 映射 404、其余 400（`007:274`）。
这跟 v2 里「回执码枚举化」是同一件事，只是它做到了「连 UI 文案都由 code 派生」（`reason.<code>`）。

**一个细节值得单独记**：`Candidate` 的注释写着 *"bounded facts, never authority"*（`src/core/ports.ts:29`）。
评估者拿到的是**有界事实，永远不是权限**。这一句解释了为什么它的审批能做得那么干净——权限判定在 `Policy.gate`，评估者只判断「这事该不该做」，两者不共享对象。

---

## 3. 运行层：五个部件

它的「运行层」不是终端/容器那套，是「**让工作项在崩溃、重启、并发下都不丢**」的那套。

| 部件 | 规模 | 干什么 | 关键机制 |
|---|---|---|---|
| `Runtime` | `src/app/runtime.ts` 17KB | 组合根，无业务规则 | 构造时锁目录 → 开 Store → 建部件；构造失败则关 Store 并释放锁。启动顺序固定：装路由 → Setup → 装钩子 → 确保主会话 → Policy 同步 → **resume 被中断的 Assignment** → 启动 passes 与 effects |
| `Effects` | `src/app/effects.ts` 10KB | 持久副作用队列 | 表主键 `(kind, key)`（`store.ts:80-82`）；同键 `ON CONFLICT DO UPDATE` 且 `generation+1`、保留队列位置（`effects.ts:61-64`）；指数退避；`throw Later` **延迟但不计失败**（槽满时用） |
| `WorkerSlots` | `src/executor/dsh.ts:171-199` | 并发槽 | `used() >= max + (by==='person' ? 1 : 0)` 才拒绝——**人的消息可以超出一个槽**；`launches()` 先扣掉 wake 占用，**唤醒优先于启动** |
| `DshExecutor` | `src/executor/dsh.ts` 47KB | 一 Ticket 一会话一目录 | `start`/`deliver` 只入队 effect；`stop` 走 `afterCommit`，回滚就不会误杀 worker |
| `Outbox` | `src/app/outbox.ts` 3KB | 出站消息 | 回执与 `reply` effect **同一事务**写入；同 Ticket 同会话的前序消息未失败 3 次前，后面的排队；可 `withdraw` |

**阶段表**（`docs/003-execution.md:9-17`）是运行层的对外投影，7 个态、判据全是布尔谓词：

| 阶段 | 判据 |
|---|---|
| 输入未送达 | 投递失败，2s 起退避至 1min |
| 等待回答 | 有 Attention |
| 等待验收 | 最新 Report 是 `ready` |
| 阻塞 / 失败 | 最新 Report 是 `blocked`/`failed`，且此后无人发消息 |
| 排队 | 无 Assignment 或待启动 |
| 空闲 | 最新 Report 是 `waiting` |
| 工作 | 其余 |

这张表的价值不在 7 个态，在于**每个态都能从持久字段算出来**，不需要另存一个 `status` 字段。所以重启后不需要恢复 UI 状态——重算即可。这正是 AgentKit 现在缺的：状态散在 `StateFlow`，进程一死就没了。

**另一个细节**：主会话有 `noWait` 守卫（`sessions.ts:77`），拒绝 `sleep`、拒绝 bash 超过 30s、拒绝 job wait 超过 5s，理由是「等待的一步会挡住下一个候选」（`sessions.ts:64`）。
这就是「异步全双工」的真实落地方式——不是加线程，是**禁止等待**，让事件自己来。对照 AgentKit 的 `DEFAULT_COMMAND_TIMEOUT_MS` 与「一条写把整批拖到 600s」，这条几乎是直接可用的解药。

---

## 4. 与 AgentKit 的逐条对照

### 4.1 架构层

| 维度 | Super-agent | AgentKit 今天 | 差距性质 |
|---|---|---|---|
| 状态写者 | 10 类状态各一个 writer，健康条目拒绝二次认领 | 「是不是本地」4 个判据、「要不要审批」2 处、事件 3 套词汇 | **A 级**，正是 v2 的主轴 |
| 接口宽度 | 5 个角色化 `Pick` | `ChatViewModel` 一类的 72 条流全暴露，`ChatScreenContract` 51 成员 = 引用 51 成员 | A 级 |
| 事务边界 | 同步命令 + `atomic`，I/O 在提交后 | `RealAgentLoop` 全异步回调，状态回调里直接改 | B 级（Android 必须异步，但可借「事务内只改聚合」） |
| 命名法 | 祈使 / 过去式严格分离 | 混用 | **零成本可借** |
| 拒绝 | `UserError(code, values)` 枚举，UI 由 code 派生 | 回执码部分枚举化 | B 级 |
| 依赖规则 | core 零外部、零 I/O | `AtomicJsonFile` 等 I/O 在 `core/` | B 级 |
| 事件 | 12 个 `DomainEvent`，**只有 3 个有消费者**（`007:109`） | `AgentEvent` 未三分，一堆回调 lambda | 借「有消费者才发」这条纪律 |

最后一行值得展开：它发了 12 个事件，但明确写了「其余事件当前无消费者」。这不是浪费，是**留了扩展点又不假装有人用**。
对照 AgentKit 的「事件三分」提案——分三类的目的不是分而治之，是让**每一类都有明确的消费者**。没有消费者的事件不该存在。

### 4.2 运行层

| 维度 | Super-agent | AgentKit 今天 | 差距 |
|---|---|---|---|
| 持久副作用 | `effect` 表，`(kind,key)` 幂等，崩溃后重放 | `core/job/` 477 行，`JobStore.reconcile` 只把 `running` 标成 `interrupted` | **只差「重放」一步** |
| 恢复语义 | `resume` 重排 pending launch，最旧优先，带恢复备注 | `JobRunner.kt:24` 注释：「不假装它还在跑，也不假装它成功」 | 诚实但被动：中断即终止 |
| 并发槽 | `max` + 人消息超 1 槽 + 唤醒优先 | `LeaseRegistry` 只告警不夺资源；终端回收判据自相矛盾，**实际永不触发**（`LeaseRegistry.kt:197` vs `TerminalPoolLease.kt:66/87`） | **A 级，见 §5.3** |
| 阶段模型 | 7 态，全由持久字段算出 | 无，散在 StateFlow | 可直接借 |
| 每任务隔离 | 一 Ticket 一会话一目录 | `TerminalSessionPool` 按 `(backendKey, alias)` 分池 | 结构接近 |
| 出站 | Outbox：回执 + 顺序 + 撤销 | 直接回调 UI，无队列 | B 级 |
| 健康 | `HealthBoard` 每 kind 一条，唯一认领 | `AgentLogger` 有日志，无「谁负责」语义 | C 级 |

`JobRunner.kt:24` 那句注释其实是**对的工程判断**（不撒谎），但它停在「记录真相」，没走到「恢复工作」。
Super-agent 的答案是：真相记下来之后，**把还能做的重新排队**。这中间隔着的不是架构，是几十行代码。

---

## 5. 能拿的三件（零新增依赖）

### 5.1 写者表：先用文档钉住，再用脚本守住

**落点**：新增 `docs/state-writers.md`，一张表列全 AgentKit 的状态类与唯一 writer；
**验收**：表里每一行都能指到一个类，且全仓 `grep` 该类名只在预期文件出现；
**量级**：纯文档，0 行代码。守卫脚本可后置到 P1。

这是 v2「一个事实一个源」的最小可执行版本。**先声明，再检查**——顺序不能反，否则会写成一个讨好现有代码的脚本。

### 5.2 JobStore 从「标记中断」升级到「重放」

**落点**：`core/job/`（现有 5 个文件合计 477 行，不动结构）；
**做法**：`reconcile` 之后对 `interrupted` 且 `resumable=true` 的任务重新入队，最旧优先，附带一条恢复备注；
**验收**：新增 harness——构造一个 `running` 残留的 `jobs/` 目录，启动后断言该任务被重新执行且备注可见；
**量级**：约 60–100 行。直接解决 A 批报告承认的「长任务被杀无法续跑」。

注意一个坑：Super-agent 的重放是**幂等**的，因为 effect 有 `(kind,key)` 主键。
AgentKit 的 job 没有这个维度——重放前必须先回答「这个 job 重复执行会怎样」。答不上来的 job 类型，宁可继续标 `interrupted`。

### 5.3 并发槽的抢占语义

**落点**：`runtime/lease/LeaseRegistry.kt` + `core/terminal/TerminalSessionPool.kt`；
**借什么**：**人的操作可以超出一个槽**（`dsh.ts:194`）。用户点了发送，不该因为没有空闲租约而排队；
**同时必修**：终端的空闲回收实际上是**死代码**，两处判据互为前提：

```kotlin
// LeaseRegistry.kt:197 —— 进回收的前提
if (!lease.isSafeToRelease) continue
// TerminalPoolLease.kt:66 —— 该前提要求「没有存活会话」
override val isSafeToRelease get() = active.get() == 0 && liveSessions() == 0
// TerminalPoolLease.kt:85-87 —— 回收动作却要求「有存活会话」才干活
fun releaseIdle(reason: String) { val count = liveSessions(); if (count == 0) return ... }
```

三条合起来：**只有会话数为 0 才允许回收，而回收函数在会话数为 0 时直接 return**。
终端池因此永不释放，手机上的 shell 进程会一直挂着。这不是阈值问题，是判据自相矛盾。
修法是把 `isSafeToRelease` 的第二项去掉（会话本来就是要被释放的对象），改由 `activeCount` 单独把关。
**验收**：把 `idleTimeoutMs` 调到 0、开 3 个会话、调 `checkOnce()`，断言 `closeAll` 被调用且会话数归零——现有 harness 若有，大概率是断言了「不回收」。
**量级**：约 20 行改判据 + 一条断言。

---

## 6. 不能拿的四件

| 不拿 | 为什么 |
|---|---|
| **Router/Worker 双层** | 代码里是 `ROUTER_PRESET`/`WORKER_PRESET` 两个提示词常量（`sessions.ts:48`），不是一个常驻进程 + 一个 worker 池。按文章实现＝在手机上跑一个服务端编排器，功耗与内存都不允许 |
| **SQLite + WAL + `synchronous=FULL`** | 它敢这么写是因为跑在电脑上、单进程、有锁文件。AgentKit 的 JSON 分片方案（R-10）已经落地，换库是几千行迁移换一点性能，不划算。**但「事务内改聚合」的思想可以借，不必借 SQLite** |
| **Cordis 插件树** | 依赖 `@deepseek-ai/cordis` 全家桶（`package.json` 里 12 个 dev 依赖）。与「零新增依赖」直接冲突，且 Kotlin 侧没有对等物 |
| **常驻主会话 + 全双工长轮** | 它的主会话是**一直在线**的，靠 `noWait` 守卫防止阻塞。手机上前台服务常驻的代价是电量，v2 已经选了「事件驱动 + 前台服务仅在有任务时存活」 |

还有一件半拿不拿的：**Inline & Spill 上下文组织**——文章讲了，**原型里没有对应实现**（`grep spill` 只命中 `cards.ts` 的一个摘要函数）。
这条属于 §7 的「文章概念」，别当既有设计去对标。

---

## 7. 文章 vs 原型：哪些只是方向

| 文章说的 | 原型里是什么 | 能不能对标 |
|---|---|---|
| Router 层 + Worker 层 | 两个 agent preset 常量 | ✗ 不是架构分层 |
| 事件驱动 + 两级过滤（程序快筛 / 语义慢判） | `Policy.gate`（`src/policy.ts:191`）+ `MasterAssessor`（`src/app/assessment.ts:89`） | ✓ 真的实现了 |
| 订阅与直通 | `Subscriptions` + `attention` 直通（`docs/002-authorization.md`） | ✓ |
| Inline & Spill 上下文 | 无 | ✗ 未来方向 |
| 后台投机执行 | `src/executor/background.ts`：worker 的 shell jobs 与 subagents | △ 是「后台作业」，不是「静默复现并修复」的推理投机 |
| 存算分离「脑手分离」 | 会话（脑）与工作目录/进程（手）分离 | ✓ 结构上成立 |
| 外部 Watchdog | `RetryHealth` + `HealthBoard`（`src/app/retry-health.ts:29`） | △ 是健康看板 + 预算重试，不是独立看门狗进程 |

**实验数据也别直接引用**：12.4 分钟中位数、37% token 节省、6.4s→1.6s，都是「约 300 条消息、20 个工作请求、每种方案重复 5 次」的模拟环境。
它自己写了「实验基于简化环境」。这三个数字不能当 AgentKit 的目标值。

---

## 8. 对 v2 方案的三处修正

| v2 原判断 | 修正 | 依据 |
|---|---|---|
| P0 的 15 个 A 级缺陷按模块列 | **加一条横切项：缺持久副作用队列**。它不是某个模块的 bug，是「崩溃后工作丢失」这一类 | `Effects` 证明这是独立的一层，不是某个模块的附属 |
| 「事件三分」只写在 P1 | **提前到 P0 的收尾**：先按「有消费者才发」删掉无人消费的事件，再谈三分。它 12 个事件只留 3 个消费者，说明减法比分类更急 | `docs/007-architecture.md:109` |
| 运行层「租约只告警不夺资源是正确取舍」 | **部分撤回**：取舍本身对，但双闸互斥让「不夺」变成「永不回收」。Super-agent 的答案是「容量预留 + 优先级抢占」，比「告警」更有操作性 | `dsh.ts:171-199` vs `LeaseRegistry.kt` |

---

## 9. 证据边界

| 项 | 状态 | 说明 |
|---|---|---|
| 仓库元数据（★/许可/时间） | ✔ 已复核 | GitHub API 实测，2026-11-06 |
| 五条架构规则 | ✔ 已复核 | 逐条对到 `docs/007-architecture.md` 行号与 `engine.ts` 源码 |
| `engine.ts` 全同步 | ✔ 已复核 | `grep -c "async \|await "` = 0 |
| `WorkerSlots` 抢占语义 | ✔ 已复核 | `dsh.ts:171-199` 原文 |
| 文章 vs 原型的差异 | ✔ 已复核 | README 自述 + 逐条 grep |
| 实验数据可信度 | ○ 推断 | 只有二手报道，未找到原始实验脚本；按其自述「简化环境」，不作为目标值 |
| AgentKit 侧的差距 | ✔ 已复核 | 来自 `docs/review/arch-v2-2026-11-06/` 六份分线报告，均带 file:line |
| 手机端可行性 | ○ 推断 | 功耗/内存影响无实测，判断依据是「常驻会话 + 前台服务」的已知代价 |

**没做的事**：没有实际运行它的测试（需要 Node + Cordis 宿主），没有对比它的提示词全文（`prompts/` 10 个文件未逐字读），没有评估 MIT 许可下直接移植代码的可行性——按谱系文档的独立实现原则，本文只借结构，不借实现。
