# AgentKit 数据持久化与安全模型 · 只读评审

> 基线：HEAD `7dd0f88`，`app/src` 301 个 `.kt` / 75,207 行。全程只读源码，未执行 gradle（沙箱无 Android SDK），
> 未运行任何 harness。行号均以本 commit 为准。标注 ✔ = 逐行读过；○ = 由代码结构推断、未实测。

## 结论摘要

1. **落盘一致性靠「写序 + 单文件原子」而非事务。** 分片先写、index 后写（`ConversationStore.kt:930-953`），每个文件各自 tmp→fsync→`.bak`→rename。
   缺的是跨文件不变量：index 里没有被登记的分片永远不会被收编（`load` 只在 index 与 `.bak` 全坏时才扫目录，`:727-731`），
   index 里有 id 而分片读不出来时**静默跳过**（`:735`，`readShard` 返回 null）。两个方向都没有计数核对、没有日志。
2. **版本只有 index 有。** `ConversationIndex.schemaVersion=2`（`ConversationShard.kt:77-82`）是唯一版本字段；
   分片、`memory.json`、`prompts`、`keypool`、`profiles_json` 全都没有。读方向靠 `ignoreUnknownKeys` + 默认值能扛；
   **写方向必然丢字段**——任何一次「旧版本写回」都按老 schema 重新编码整个对象。降级更糟：R-10 之前的版本读新 index 得到空列表，
   随后新建会话并整库覆盖，全部分片变孤儿（已用 `git show 3d20004` 复核老代码）。
3. **凭据是明文，但不在日志里、不在系统云备份里。** API Key / 媒体 Key / 备用 Key 池 / GitHub PAT 都在
   SharedPreferences 明文（`agentkit_model_config.xml`、`agentkit_keypool.xml`），`allowBackup=false` 挡掉 Android 云备份。
   `AgentLogger` 有 5 条正则 + 7 个敏感工具白名单；`CrashLogger` **一条脱敏都没有**；`DiagnosticsExporter` 只遮主 Key 与当前档案池。
   **备份 ZIP 里是明文 Key**（用户已知悉：设置页写「导出含 API Key，请勿外传」）。
4. **一个坏包可以静默入库。** 校验只有白名单 + 单条 24MB + 累计 64MB + 条数（`BackupArchive.kt:101-159`），无哈希、无签名；
   条数对得上的语义垃圾照样通过。路径穿越不成立——解包全程在内存、不落盘（`:102`），落盘只经 `shardFile` 的 `SAFE_ID` hex 编码。
   但存在**自产备份自己恢复不了**的不对称：导出侧无上限，读入侧 24MB。
5. **`startsWith` 越界问题存在，但只在两处、影响有限。** `read_file/write_file/edit_file` 走 `FileTool.resolve`，带 `File.separator`，正确（`BuiltInTools.kt:120-124`）；
   `app_install`（`AppManageTools.kt:56`）与 OCR（`OcrTool.kt:134`）不带分隔符。shell/容器/ADB 零路径限制——文档 §7.3 标了「高」，
   但只给了风险等级，没给「为什么可接受」的论证，属于**已登记缺口**而非有据设计。
6. **规模上真正的成本不在落盘，在读取和展示。** 落盘已降为 O(单会话 + index)；但 `ConversationStore` 构造时同步解析**全部**分片（`ChatViewModel.kt:806`，属性初始化 = 主线程），
   `list()` 每次消息追加都做 O(Σ turns×variants) 求和 + 排序（`ConversationStore.kt:374-396`）。

## 关键实证

| 结论 | file:line | 复核 |
|---|---|---|
| index 格式 = `{schemaVersion, ids}`，版本号 `@EncodeDefault(ALWAYS)` 强制写出 | `ConversationShard.kt:72-84` | ✔ |
| 分片只存 `turns` 真源，`messages` 由 `flatten` 现算，双写确实消除 | `ConversationShard.kt:26-45`；`ConversationStore.kt:782-785` | ✔ |
| `persist` 先写脏分片、后写 index；index 每次落盘都刷新（不是只在会话集合变化时） | `ConversationStore.kt:930-953` | ✔ |
| 写脏判据 = `dirtyIds` / `removedIds` 两个集合，11 个写方法逐个标脏，A9 用结构对账守着 | `ConversationStore.kt:302-304, 405-670`；`r10_shard_guard.py:96-106` | ✔ |
| `.bak` 分两级：index 一级、每个分片各自一级；回退判定由调用方传 `isValid` 谓词 | `AtomicJsonFile.kt:85-125, 145-158`；`ConversationStore.kt:774-779` | ✔ |
| index+`.bak` 全坏 → 扫 `*.json` 重建 index，用**文件名 stem** 当 id（不是分片内容里的 id） | `ConversationStore.kt:801-817` | ✔ |
| index 好、分片缺 → `mapNotNull` 静默丢弃该会话，不触发重建、不打日志 | `ConversationStore.kt:735, 774-778` | ✔ |
| 分片写成功、index 未写成 → 该会话在下一次启动不可见，且**永远**不会被收编 | `ConversationStore.kt:723-741`（无孤儿扫描） | ✔ |
| 重建时 id 取自文件名，hex 编码的脏 id 会以 `h-<hex>` 形式进 index（读回自洽，但 index.id ≠ record.id） | `ConversationStore.kt:766-771, 802-815` | ✔ |
| 测试：B1–B14 行为 + A1–A13 结构 = 27 条，M1–M19 变异；B8 卡「窗口内 persistCount 不许涨」 | `R10ShardVerification.kt:218-240`；`r10-shard-verify/mutate.py:59-200` | ✔ |
| 测试**未覆盖**：index 完好但分片缺失、分片写成功 index 未写、`.bak` 回退到缺 id 的旧版 | 同上（B6/B7/B13 只覆盖「文件损坏」） | ✔ |
| `flush()` 由 `onActivityStopped` 前台计数归零触发 → 弱引用 hook → `ConversationStore.flush()` | `AgentKitApplication.kt:34-41`；`ConversationStore.kt:365, 901-903` | ✔ |
| `flush()` 只往单线程调度器 `execute` 一个任务并立即返回，**不 join**；`onStop` 刚结束就被杀仍会丢窗口内变更 | `ConversationStore.kt:901-903` | ✔ |
| 旧整库数组迁移由「格式探测」触发：读成对象走分片、读成数组走迁移；迁移成功后原文件变对象，故只跑一次 | `ConversationStore.kt:723-749, 788-794` | ✔ |
| 但 `migrate(rec)`（turns 归一/旧 messages 转轮次）**每次启动对每条会话都跑**，纯函数、幂等、不落盘 | `ConversationStore.kt:829-843` | ✔ |
| `schemaVersion` 只判下界（`>= 2`），无上界；未来 v3 的 index 会被当 v2 解出来并覆写回 v2 | `ConversationStore.kt:752-755` | ✔ |
| 备份读入会拒「版本过新」（`> VERSION`），比 index 严格——两套前向兼容策略不一致 | `BackupArchive.kt:130-132` | ✔ |
| 写入侧截断 4000 字符，作用于 user/assistant 正文、reasoning、toolArgs、工具结果；截断后的 turns 才是落盘真源 | `ConversationStore.kt:62, 165-197, 782-785` | ✔ |
| `MAX_MESSAGES=200` 实际卡的是 **turns 数**（`out.size`），variants 无上限，与注释「消息数封顶」不符 | `ConversationStore.kt:503, 974-975` | ✔ |
| 降级：老版本 `load` 解析对象失败即 items 为空，随后 `create`+整库写，主文件被数组覆盖 | `git show 3d20004:…/ConversationStore.kt:601-606`（老代码）；`ConversationStore.kt:420-435` | ✔ |
| 凭据落点：`agentkit_model_config.xml`（`profiles_json`/`api_key`/`media_key_*`/`gh_pat`）、`agentkit_keypool.xml`，全部明文 | `ConfigStore.kt:27-34, 107-120, 191-193, 216-223`；`KeyPoolStore.kt:16-20, 86-97` | ✔ |
| 系统云备份已关：`android:allowBackup="false"` | `AndroidManifest.xml:64` | ✔ |
| `AgentLogger` 5 条正则（`sk-`/`Bearer`/api_key/token/password）+ 7 个敏感工具只记长度 | `AgentLogger.kt:96-114, 130-138, 191-212` | ✔ |
| `CrashLogger` 报告原样写异常消息与堆栈，无任何脱敏 | `CrashLogger.kt:296-322` | ✔ |
| 诊断包：日志文本做了 Key 全串替换，但崩溃现场（`crashSnapshot`）原样粘入且不参与替换 | `DiagnosticsExporter.kt:59-97, 179-196` | ✔ |
| 诊断包替换表只含「主 Key + 当前档案的备用池」；媒体 Key、其它档案 Key、GitHub PAT 不在表内 | `DiagnosticsExporter.kt:59-73` | ✔ |
| `shell_run` 不在敏感工具白名单，非 `sk-` 前缀的 Key（`AIza…`/`ghp_…`/UUID 形态）会以明文进日志与诊断包 | `AgentLogger.kt:104-114` + 正则 `:96-102` | ✔ |
| 备份 6 成员：manifest/conversations/memories/config/prompts/keypool；config 内含明文 Key，PAT 置 null | `BackupArchive.kt:31-38`；`BackupUseCase.kt:97-126` | ✔ |
| 备份不含：workspace、skills、jobs、logs、media 产物、书签、工作流/定时任务、检查点、草稿（恢复时主动清空） | `BackupUseCase.kt:155-181`（唯一恢复入口）；`DraftStore` 无对应成员 | ✔ |
| 恢复时 `githubOwner/Repo/Workflow` 取自备份、PAT 沿用本机 → 敌意备份可改变推送目标 | `ConfigStore.kt:276-292` | ✔ |
| 路径沙箱正确实现：`canonicalPath + File.separator`，符号链接也被 canonical 解开 | `BuiltInTools.kt:120-124`；`FileBackupStore.kt:84, 148` | ✔ |
| 前缀越界：`app_install` 与 OCR 用不带分隔符的 `startsWith` | `AppManageTools.kt:55-57`；`OcrTool.kt:132-135` | ✔ |
| 技能包：id 白名单 + entry 名含 `..` 拒绝 + canonical 边界 + 条目数/累计字节边读边限 | `SkillImportRepository.kt:106-108, 213-262` | ✔ |
| `write_file` 用 `File.writeText`；`edit_file` 用原子写——同一目录下两种纪律 | `BuiltInTools.kt:295 vs 342` | ✔ |
| 编辑器保存、媒体任务库同样裸 `writeText`；`tasks.json` 解析失败按空处理，下一次 upsert 即覆盖全史 | `WorkspaceStore.kt:140-149`；`MediaTask.kt:65-71, 86-90` | ✔ |
| `ConversationStore` 在 ViewModel 属性初始化处构造 → 构造函数里的 `load()` 在主线程同步解析全部分片 | `ChatViewModel.kt:806`；`ConversationStore.kt:358-360` | ✔（是否引发可见 jank ○） |
| `appendMessage` 每次都 `flatten(turns)` 重建 `messages`，单会话写入 O(turns) → 会话内 O(n²)（被 200 上限兜住） | `ConversationStore.kt:452-464` | ✔ |
| `list()` 每次做全库 `flatMap{variants}` 求和 + 排序，被每条落库消息后调用一次 | `ConversationStore.kt:374-396`；`ConversationProjection.kt:122-125` | ✔ |
| `conversation_search` 先 `joinToString` 整段再 `take(6000)`，最坏临时字符串 ≈ 30×200×4KB | `ConversationSearchTool.kt:56-62, 90-91` | ✔ |
| `MemoryStore` 每次 add/remove 全量重写 ≤500 条并同步 fsync（调用线程） | `MemoryStore.kt:98-101, 254-259, 383-385` | ✔ |

## 结构性问题

**A 级（会导致错误或数据丢失）**

- **A1 跨文件不变量缺失。** index 与分片之间只有「写序」这一个弱保证。三类可复现窗口：① 分片已写、进程在 index 写之前死 → 新会话永久孤儿；
  ② index 写成功、某分片写失败（`writeShard` 返回 0 被忽略，`:938-942`）→ 该会话下次启动消失且无痕；③ index `.bak` 回退到缺 id 的旧版 → 同一后果。
  「index 与分片全坏才重建」这个条件太窄：真正需要重建的是「不一致」，而代码无法察觉不一致。
- **A2 降级不可逆且不设防。** 新格式对 R-10 之前版本是不可读对象，老版本会当作「没有会话」并从空库重建、覆盖主文件。
  这不是理论：老代码 `parseOrNull` 失败后 `items` 为空、`ensureNotEmpty()` 直接 `create()`（`git show 3d20004`）。分片仍在磁盘，但只有人工从 `.bak` 捞回 index 才能救。
  应用商店回滚、多设备互导（见 A3 备份）都会踩到。
- **A3 「原子写唯一实现」不成立。** `AtomicJsonFile` 的 KDoc 写「全仓唯一实现」，但 `write_file`、编辑器保存、`tasks.json` 三处仍裸 `writeText`。
  `tasks.json` 更严重：解析失败 → `emptyList()` → 下一次 `upsert` 把「1 条新任务」写回去，历史任务一次性消失，且日志里只有一行「解析失败，按空处理」。
- **A4 写入侧截断是静默且不可逆的。** 4000 字符上限对**用户粘贴的正文**同样生效；落盘真源是截断后的 `turns`，重载不可恢复，
  且此后 `flatten` 只会拿到截断版。UI 当期显示全文（内存态未截断），用户以为存住了——这是「看起来没问题，重启后内容少一半」的经典形态。

**B 级（阻碍演进、放大风险）**

- **B1 版本策略半套。** 只有 index 有版本、且无上界；分片无版本。`ignoreUnknownKeys` 只保证「读不崩」，不保证「写不丢」。
  在新版本给 `ConversationShard` 加字段之后，旧版本任何一次 `rename`/`setPinned` 都会把该字段抹掉，且用户与两端都收不到任何信号。
- **B2 备份完整性只有结构校验。** 无哈希/签名，条数对得上的坏包可直接 `replaceAll` 覆盖全库；导出无大小上限而读入限 24MB/64MB，
  长会话重度用户可能导出成功、恢复被拒（`备份成员过大`），且这条拒绝发生在用户已经选定文件之后。
- **B3 恢复会改写 GitHub 推送目标而保留本机 PAT。** 恶意/误拷备份把 `owner/repo` 指向他处，`restoreFrom` 沿用本机 PAT；
  确认框文案只说「覆盖会话、记忆与模型配置」，未提「会改推送仓库」。
- **B4 脱敏链路有两个空档。** ① `CrashLogger` 全程无脱敏，诊断包又把崩溃现场原文（4000 字符）拼进分享文本——崩溃消息里出现过的 Key 会完整外流；
  ② 替换表只覆盖主 Key + 当前档案池，媒体 Key / 其它档案 Key / PAT 不在内。
- **B5 前缀匹配的两处漏点。** `app_install`/OCR 的 `startsWith` 缺分隔符，`/…/agent_workspace` 能匹配 `/…/agent_workspace_evil`。
  实际影响受限（需要先存在同前缀兄弟目录，且 shell 本就能建；`app_install` 还要过系统安装器，OCR 只读图），但代码级结论是「有」。
- **B6 启动路径主线程化。** 构造即 `load()`，等于把「全部分片的读取 + JSON 解析 + `flatten`」压在 ViewModel 创建线程上；
  会话越多冷启动越慢，与文档「冷启动 ≤2.0s」的目标直接冲突。

**C 级（可读性/成本）**

- C1 index 每次落盘无条件重写（含只改标题的场景），O(会话数) 字节 + 两次 rename。
- C2 `MemoryStore` 每条记忆一次全量重写 + fsync；批量导入 N 条是 O(N×500)。
- C3 媒体产物无配额、无清理、不进备份（换机即丢），`outputs/<taskId>/` 只随任务删除。
- C4 恢复非事务：会话已替换、`config.json` 解码失败 → 半恢复状态；`BackupUseCase` 注释承认这点但未做顺序保护。

## 与 v1.0 文档的差异

1. **§6.2 表格已过期但未更新。** 表里仍写「写入单位 = 整库全量重写」「双写 messages/turns」「`flush()` 全仓零调用」，
   而 R-10 补记（`:269-277`）说的三条都已落地；补记是新增段落，表格没改，读者先看到的是错的那张表。
2. **「业务文件无 schemaVersion」仍成立，但需要精确化。** index 有（v2，且 `@EncodeDefault`），分片与其它业务文件没有。
   文档只给了「无 → 有」的二分，没有区分「载体有版本、内容无版本」这个真实切面。
3. **备份覆盖「5 类」实际是 6 类**（补了 prompts + keypool，文档 §6.2 与 BackupModels 注释仍写四/五类）。
   「不含 workspace/skills/jobs/logs/media」正确，但还漏记：书签、工作流/定时任务、检查点、草稿（恢复时主动清空）、媒体产物。
4. **§7.3「仅 read_file/write_file/edit_file 做 `startsWith`」不准确。** 实际有五处路径校验（含 `FileBackupStore` 两处、`app_install`、OCR），
   其中两处写法有缺陷；文档把「有校验」写成「只有三个工具」，掩盖了另外两处的质量问题。
5. **§7.3 对 shell 的表述停在「高风险」，没有设计判断。** 同一张表里对 shell 规则明确写了「设计如此」，
   对路径沙箱却只给等级。读者无法区分「有意不做」与「还没做」。
6. **§7.2「落盘失败 → 原子替换 + 保留 .bak」只对 `AtomicJsonFile` 的三条路径（会话/记忆/提示词）成立**，不覆盖 `write_file`/`tasks.json`。
7. 附录 B 度量（284 文件 / 73,006 行）落后于当前（301 / 75,207），差异来自 R-13/R-14 之后的新增，与本条评审无关但同类。

## 建议

| 落点 | 改什么 | 验收判据 | 量级 |
|---|---|---|---|
| `ConversationStore.load` | 加载后做一次**一致性对账**：index.ids vs 分片目录差集；多出的分片收编、缺失的分片记 WARN 并从 `.bak` 再试一次 | 新增 harness：分片在、index 无 → 重启后会话仍在且 index 被补写；分片缺、index 有 → 日志有 WARN 且不静默消失 | 40–60 行 + 2 条判据 |
| `ConversationStore.persist` | 写 index 前校验「本次 dirty∪removed 已全部落到预期状态」，任一 `writeShard` 返回 0 就**不写 index**并保留 dirty 重试 | 注入写失败（只读目录）后 index 不含失败 id；恢复可写后下一轮自动补齐 | 20 行 + 2 条判据 |
| 全部业务文件 | 给 `ConversationShard`、`MemoryItem` 容器、`profiles_json`、keypool 加 `schemaVersion`；读入时**拒收高于本版**的版本并在 UI 提示「数据来自更新版本」 | 造 v3 分片喂给当前版本 → 明确拒绝而非当 v2 覆写；结构守卫加 A14 卡「四个 DTO 字段集含 version」 | 30–50 行 + 1 组变异 |
| `BackupArchive.create` | 导出侧加与读入侧同口径的上限（单成员 24MB / 合计 64MB），超限时提示「改用分卷/导出会话」而不是产出一个自己恢复不了的包 | 造 100 个长会话导出 → 要么成功且可回读，要么在导出前明确拒绝并说明 | 15 行 + 1 条往返判据 |
| `BackupArchive.read` | manifest 增加 `sha256` 汇总（各成员内容哈希），读入时比对；不匹配 → `Invalid("备份内容损坏")` | 改一个字节仍保持条数 → 必须拒收；老包无该字段 → 放行（前向兼容） | 25 行 + 2 条判据 |
| `ConfigStore.restoreFrom` | 恢复 GitHub 配置时，若 `owner/repo` 与本机不同，返回「需用户确认」而不是直接写；确认框文案补上「会改变代码推送目标」 | 造 owner 不同的备份 → 恢复流程停下等确认；取消则本机配置不变 | 20 行 + 1 条判据 |
| `CrashLogger.formatReport` + `DiagnosticsExporter.crashSnapshot` | 崩溃文本在写盘前过 `AgentLogger.sanitize`；诊断包对崩溃段落也执行 Key 替换，并把替换表扩到「全部档案 + 媒体 Key + PAT」 | 造一个消息里含 `AIza…` 的崩溃 → crash 文件与诊断包都不出现完整串 | 15–25 行 + 2 条判据 |
| `AppManageTools` / `OcrTool` | 两处 `startsWith` 补 `+ File.separator`；顺手抽一个 `resolveUnder(root, rel)` 到 `core/io`，禁止再手写前缀比较 | 造 `/…/agent_workspace_evil/x.apk` → 必须 `bad_path`；结构守卫禁止 `startsWith(workspace.canonicalPath)` 无分隔符形态 | 10 行 + 1 条判据 |
| `BuiltInTools.WriteFileTool` / `WorkspaceStore.write` / `MediaTaskStore.writeAll` | 统一走 `AtomicJsonFile.write`（`write_file` 已有 `atomicWriteText` 却没用）；`tasks.json` 解析失败改为「拒绝写、保留原文件 + 提示」 | 断电模拟（截断主文件）后 `tasks.json` 从 `.bak` 恢复；`write_file` 落半截不可能 | 20 行 + 2 条判据 |
| `ChatViewModel` | 会话库改为异步预热（先返回空列表 + 加载态，IO 完成再推 `conversations`），`list()` 结果缓存并按 dirty 失效 | 100 会话 / 200 轮基准：主线程无 >16ms 的 `load`/`list` 段（需真机 Trace 佐证） | 结构改动，中等 |

优先级：A1/A3 + 三个 B 级脱敏与备份项属于「不改会静默丢数据/漏凭据」，建议下一批直接做；A2（降级）至少要在 README/发布说明里写明「不支持降级」。

## 证据边界

- 本文所有结论来自静态阅读 `app/src` 与 `scripts/qa`，**未编译、未跑 harness、未上真机**。沙箱无 Android SDK，`gradle` 全程未执行。
  因此「主线程 IO 是否造成可感知 jank」「B8 去抖在真机的写入次数」这类运行时判断，本文只给代码路径，不给数字。
- 行号基于 `7dd0f88`。`docs/architecture/AgentKit-架构方案-2026-11-05.md` 的 R-10 补记与 §6.2 表格自相矛盾，本文以代码为准。
- 降级行为（A2）是用 `git show 3d20004:…/ConversationStore.kt` 的老实现推演出的调用链，**未实际安装旧版本验证**；结论方向可靠，具体后果（是否会弹「新建对话」）未实测。
- 凭据结论以「文件里实际是什么」为准：SharedPreferences XML 中 `profiles_json` 的值形如
  `[{"id":"…","name":"OpenAI","config":{"baseUrl":"…","apiKey":"sk-…","model":"…","providerId":"openai"}}]`，
  备份 `config.json` 与 `keypool.json` 同形；`githubPat` 在导出侧被显式置 null（`BackupUseCase.kt:104-105`）。
  未在设备上导出真实文件核对，形态由序列化器与字段类型推出（✔ 代码层 / ○ 文件层）。
- 未覆盖：`core/container`、`automation/adb` 的落盘、`job/` 的任务持久化细节、`skill/market` 的下载缓存策略——
  这些不在本次六问范围内，若需要可另开一条评审线。
