# 源码级深挖：四家参照仓库「吃透」报告 —— 应用启动/跨应用操控 + 路线图新增发现

> 撰写人：产品经理 许清楚（Xu）｜委托：主理人 齐活林（Qi）
> 输入：主理人已核实源码笔记（含路径:行号）+ 本轮亲自抽验（下文所有引用均经 Read/grep 复核）
> 第一优先级：回答「参照项目到底怎么启动应用/跨应用操控」（对应 W-7）
> 第二优先级：四家对 AgentKit 路线图（`00-roadmap.md`）有高价值的新发现
> 路径口径：四家源码根为 `/root/.codebuddy/artifact/repos/{operit,coomi,rikkahub,aicode}`；AgentKit 路径默认前缀 `app/src/main/java/com/example/agentkit/`
> **路径勘误**：笔记中 AiCode/RikkaHub 的相对路径与实际仓库布局不符，本报告一律使用实测路径（AiCode 实际根为 `app/src/main/java/com/aicode/`，RikkaHub 为 `app/src/main/java/me/rerere/rikkahub/`），关键行号已逐条复核属实。

---

## TL;DR（10 句）

1. 四家里只有 Operit 有真正的「启动应用」工具链：五件套（install/uninstall/list/start/stop）注册于 `ToolRegistration.kt:2315-2370`，核心机制与 AgentKit `app_launch` **逐行同构**（getLaunchIntentForPackage + FLAG_ACTIVITY_NEW_TASK），Coomi/RikkaHub/AiCode 均无应用启动工具。
2. Operit 的领先不在「启动」本身，而在**周边四件套**：~100 条静态中文应用字典（含快手 com.smile.gifmaker，`StandardUITools.kt:68`）+ 全机扫描补齐（`:259-296`）+ 启动后 1000ms 沉降（`PhoneAgent.kt:788`）+ 提示词纪律（`FunctionalPrompts.kt:720-721`）。
3. AgentKit `app_launch` 的 Intent 机制没有错——W-7 断点最可能在**「启动后」而不在「启动」**：无沉降等待、无前台确认（`AppControlTools.kt:379-383` startActivity 后立即返回成功），`ui_tree` 走 `rootInActiveWindow`（`AgentAccessibilityService.kt:344`），窗口切换未完成时读到的是 AgentKit 自己的旧窗口 → 模型误判「打开失败」。
4. 次要断点：后台 Activity 启动限制（Android 10+ 静默拦截，不抛异常 → 假成功）；名称解析无静态字典兜底（小模型给错名即 NotFound/Ambiguous）。
5. AgentKit 已有一张被低估的好牌：`DeviceContextStore` 事件驱动前台跟踪（`AgentAccessibilityService.kt:319-329` → `DeviceContextStore.kt:27-35`，TYPE_WINDOW_STATE_CHANGED 触发），比 UsageStats 桶更快——**「启动后前台确认」应当复用它，而不是新造轮子**。
6. 修复优先级 P0 四件：静态字典（~52 条，快手必收）+ 包名兜底；启动后沉降 + 前台确认组合；`device_foreground_app` 未授权自动打开设置页 + UsageEvents 配对法升级（学 RikkaHub `ScreenTimeTool.kt:81-83,136-138`）；**H2 修复（`core/AgentLoop.kt:112` 适配层 maxSteps 默认 16 未随 hotfix5 改 40，`ChatViewModel.kt:879` 构造未传参 → 生产实际 16，跨应用多步任务易提前截断）**。
7. AiCode 是第二优先级金矿：三模式（BUILD/PLAN/AUTO，`ChatSession.kt:6-10`）、权限引擎（Shizuku 高危不可记忆，`ToolPermissionPolicyEngine.kt:24-40,147,357`）、按会话隔离的 Checkpoint（`CheckpointManager.kt:47`）、子代理系统、提示词资产化——前三项路线图有对应条目且本轮验证属实，**三模式与子代理是路线图缺口（🆕）**。
8. Coomi 的跨应用操控 = 无障碍文件队列主路径 + adb 兜底，纪律是「**仅当输出含『无响应』才回退 adb，其它错误如实返回**」（`lib.rs:1400-1401`）——这是「不静默盲试」的工程美德，AgentKit 的 app_stop 已具备同款品德（`AppControlTools.kt:446-469`）。
9. RikkaHub 贡献两个可复用范本：AskUserTool（回合内向用户提问）与 ScreenTimeTool（UsageEvents 配对 + 自动跳设置页 + launcher 排除），后者正是 `device_foreground_app` 的升级模板。
10. 合规红线不变：LGPL/AGPL/GPL 三家只读思路净室重写（字典类事实数据除外，仍需自行整理），Apache-2.0 可深参仍净室；本报告所有建议**零新增外部依赖**。

---

## 第一部分 · 参照项目怎么启动应用

### 1.1 四家能力矩阵

| 能力 | Operit | Coomi | RikkaHub | AiCode | AgentKit 现状 |
|---|---|---|---|---|---|
| 启动应用工具 | ✅ `start_app`（包名必填 + Activity 选填） | ❌ 无（仅自身 Launcher） | ❌ 无（仅自身 Activity 路由） | ❌ 无（getLaunchIntent 仅用于拉 Shizuku 管理器自身） | ✅ `app_launch`（`AppControlTools.kt:313-391`） |
| 列出已安装应用 | ✅ `list_installed_apps`（getInstalledApplications 全量口径） | ❌ | ❌ | ❌ | ✅ `app_list`（queryIntentActivities 只列有 launcher 入口，`:215-230`） |
| 停止应用 | ✅ `stop_app`（killBackgroundProcesses） | ❌ | ❌ | ❌ | ✅ `app_stop`（shell am force-stop，更彻底） |
| 安装/卸载 | ✅ `install_app` / `uninstall_app` | ❌ | ❌ | ❌ | ❌ |
| 名称→包名解析 | ✅ 静态字典 ~100 条 + 全机扫描 + 原样兜底 | — | — | — | ⚠️ 仅依赖 app_list 候选匹配（`:107-133`） |
| 跨应用操控主通道 | 无障碍 + Shower 虚拟屏 + shell（am start） | 无障碍文件队列 + adb input/screencap 兜底 | —（纯聊天客户端） | 无障碍 + Shizuku shell | 无障碍 ui_*（system prompt 禁 shell 操控界面，`core/AgentLoop.kt:26`） |
| shell 启动通道 | ✅ Debugger 级 `am start -n` 覆写 | ✅ shell 工具可 `am start`（安全策略无专项禁令） | — | ✅ Shizuku（高危管控） | ⚠️ `shell_run` 存在但被提示词禁用（`core/AgentLoop.kt:26`） |

> 结论先行：**「怎么启动应用」这个问题上，四家能给 AgentKit 的参考答案几乎全部在 Operit 一家**；Coomi 的贡献在「操控纪律」，RikkaHub/AiCode 的贡献在「启动之后」的周边设施。

### 1.2 Operit 全链路解剖（W-7 主答案）

#### 1.2.1 工具注册（五件套，executor 全走 systemOperationTools）

`core/tools/ToolRegistration.kt:2315-2370`：install_app / uninstall_app / list_installed_apps / start_app / stop_app，executor 统一 `runBlocking(Dispatchers.IO) { systemOperationTools.* }`（standard 族）。**关键设计：startApp/stopApp 是 `open` 方法（`StandardSystemOperationTools.kt:514`、`:568`），Debugger 族子类覆写——同一工具名、按权限级别换实现。**

#### 1.2.2 标准级 startApp（与 AgentKit app_launch 逐行同构）

`core/tools/defaultTool/standard/StandardSystemOperationTools.kt:514-566`：

- 参数仅 `package_name`（必填）+ `activity`（选填）——**要包名，不要应用名；名称解析推给模型自己做**；
- 无 activity：`context.packageManager.getLaunchIntentForPackage(packageName)`；有 activity：`Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, activity))`；
- `intent.addFlags(FLAG_ACTIVITY_NEW_TASK)` → `context.startActivity(intent)`；
- intent == null → 失败，文案 "Failed to start app: Cannot find app launch Intent. Please check package name or Activity name."

与 AgentKit `AppControlTools.kt:363-391`（launchResolved）对比：**机制完全相同**（同为 getLaunchIntentForPackage / ComponentName / NEW_TASK / startActivity / null 判失败）。AgentKit 多做了「名称解析 + 歧义不启动」（`resolveAppTarget:107-133`），这一点反而优于 Operit 标准级。

#### 1.2.3 Debugger 级覆写（高权限身份的 shell 启动）

`core/tools/defaultTool/debugger/DebuggerSystemOperationTools.kt:277-355`：

- 无 activity 时先 `cmd package resolve-activity --brief <pkg> 2>/dev/null | tail -n 1` 解析主 Activity（兼容 "pkg/act" 与纯 act 两种返回，`:300-310`），再 `am start -n <pkg>/<act>`；
- 代码注释原话：「**使用 am start 命令而不是 monkey，避免修改系统设置（如屏幕旋转）**」（`:293`）——明确点名 monkey 的副作用（monkey 会改系统设置，是真机自动化经典坑）；
- 解析失败 → 直接报错 "Cannot resolve app main Activity..."，**不盲启**（`:313-317`）。

#### 1.2.4 PhoneAgent Launch 动作（三级回退 + 权限前置）

`core/tools/agent/PhoneAgent.kt:966-1030`：

1. **权限前置**：`resolvePrivilegedExecutionState` 判定 isAdbOrHigher 但无 Debugger/Shizuku 访问 → 直接失败并给 `phone_agent_shizuku_unavailable` 提示（`:978-980`）——对应路线图 M-12「执行用严格、获取用降级」；
2. **三级回退**：① Shower 虚拟屏 `ensureDisplay` + `ShowerController.launchApp`（先 `getLaunchIntentForPackage(pkg) != null` 预检 hasLaunchableTarget，`:985`）→ ② 失败退桌面包 `com.ai.assistance.operit.desktop`（`:1003`）→ ③ 再失败走 `start_app` 工具本体（`:1015-1019`）；
3. **启动成功后 `delay(POST_LAUNCH_DELAY_MS = 1000L)`**（`:788`、`:999/:1009/:1020`），其他非等待动作后 500ms。

#### 1.2.5 名称→包名解析（字典 + 扫描 + 兜底）

- 静态字典 `StandardUITools.kt:61-252` 的 `APP_PACKAGES`：约 100 条，中文热门应用全覆盖（微信/QQ/微博/淘宝/京东/拼多多/小红书/高德/美团/饿了么/携程/12306/滴滴/bilibili/抖音/**快手 com.smile.gifmaker**（`:68`）/腾讯视频/爱奇艺/优酷/芒果TV/红果短剧/网易云音乐/QQ音乐/汽水音乐/喜马拉雅/番茄小说/七猫/飞书/QQ邮箱/豆包 com.larus.nova/keep/美柚/腾讯新闻/今日头条/贝壳找房/安居客/同花顺/星穹铁道/恋与深空）+ 英文常用（Settings/Chrome/VLC/WeChat/WhatsApp/X/Twitter…含大小写变体）；
- `scanAndAddInstalledApps(context)`（`:259-296`）：一次性全机扫描 `getInstalledApplications(GET_META_DATA)` 补充字典（label→package，**不覆盖已有键** `:281`，appsScanned 标志 + synchronized 幂等 `:265-267`）；
- PhoneAgent 懒同步一次 list_installed_apps 工具结果补字典（`syncAppPackagesFromToolIfNeeded`，`PhoneAgent.kt:1240`）；
- 查找是**精确匹配**（原样/trim/lowercase 三次 lookup，`PhoneAgent.kt:1232`），未命中 → **把输入原样当包名用**（resolveAppPackageName 末尾 `?: trimmed`，`:1228-1242`）。

#### 1.2.6 list_installed_apps 与 stop_app（口径差异）

- `listInstalledApps`（`StandardSystemOperationTools.kt:467-511`）：`getInstalledApplications(GET_META_DATA)`，include_system_apps 默认 false 按 FLAG_SYSTEM 过滤，输出 `"$appName ($packageName)"` 全量排序。**口径注意：这会包含没有 launcher 入口的应用**——与 AgentKit 用 queryIntentActivities(MAIN/LAUNCHER, MATCH_ALL) 只列可启动应用不同（`AppControlTools.kt:218`）。AgentKit 口径对「列给模型看」更优，Operit 口径对「查任意包名」更全。
- `stopApp`（`:568-600`）：`ActivityManager.killBackgroundProcesses(pkg)` + KILL_BACKGROUND_PROCESSES 权限，SecurityException 时明确提示缺权限。AgentKit 走 shell `am force-stop`（`AppControlTools.kt:531`）更彻底，且已有诚实降级（`:446-469`）。

#### 1.2.7 提示词纪律 + Manifest

- `core/config/FunctionalPrompts.kt:720-721`（UI 子代理 JSON 动作协议）："start_app: Use this to launch an app directly. This is often more reliable than tapping icons on the home screen." / "list_installed_apps: Use this to find an app's package name if you don't know it."——**把工具的正确用法写进提示词，而不是指望模型悟**；
- `app/src/main/AndroidManifest.xml:59`：`QUERY_ALL_PACKAGES`（tools:ignore="QueryAllPackagesPermission"）。AgentKit 同样已声明（`AndroidManifest.xml:42`），✅ 无缺口。

### 1.3 Coomi 路线：无启动工具，双路径 UI 自动化

- **无 start_app 类工具**（全仓 grep 仅自身 Launcher Activity）。跨应用操控 = 无障碍 + shell，且其 shell 工具**完全可以 `am start`**（安全策略是路径黑名单式，`security/src/lib.rs`，无 am start 专项禁令）——即 Coomi 的答案是「不给你专用工具，但不禁你 shell 开应用」。
- `ui_automation` 工具（`apps/coomi-rs/tools/src/lib.rs:1352-1435`）：动作集 tap/swipe/long_press/text/send/fill_and_send/click_text/global(back 等)/foreground/read_screen/input_text；**主路径 = 无障碍文件队列**（control_queue_dir 下写 `*.cmd.json`，`run_control_command(command, Duration::from_secs(6))` 等结果，`:1394-1396`）；**仅当输出含「无响应」（服务没开/队列没消费）才回退** adb `input tap/swipe/text/keyevent` + `screencap`（经 `shizuku::shell`，`:1429-1434`）；其它错误**如实返回**——注释原话（`:1400`）：「避免在用户毫无感知的情况下反复用 adb 盲点」；
- Android shell 需 config_home 已配置（无障碍或 Shizuku 授权），否则报 "Android shell not configured; enable Accessibility or authorize Shizuku"（`:1432-1434`）；
- shell 工具 environment 参数 `auto/host/termux/proot/shizuku` 五选一（`lib.rs:532-550`）；session_id 以 `shizuku:` 开头或 environment=shizuku 走 shizuku 本地 shell（`:538-548`）；
- 无障碍服务（`apps/coomi-app/app/src/main/java/app/coomi/CoomiAccessibilityService.java`，656 行）：describeScreen() 遍历 AccessibilityNodeInfo 输出可点击元素（带 int[] budget 预算）；指令通道是文件队列（queueLoop 轮询 → handleCommandFile → writeResult）。

### 1.4 RikkaHub / AiCode：无启动能力，贡献在别处

- **RikkaHub**：纯聊天客户端，getLaunchIntent/startActivity 仅自身 Activity 路由。本报告相关价值在 ScreenTimeTool（见 §3.3）与消息分支实体验证（`data/db/entity/MessageNodeEntity.kt`：message_node 表 = id + conversation_id(FK CASCADE) + node_index + messages(JSON List<UIMessage>) + select_index——路线图 M-06「节点+游标扁平 List」论断本轮再次属实）。
- **AiCode**：无通用应用启动工具；`getLaunchIntentForPackage` 仅用于拉起 Shizuku 管理器自身（`feature/agent/domain/shizuku/ShizukuManager.kt:185-191`，据主理人笔记）。其价值在三模式/权限/检查点/子代理/提示词（见第三部分）。

### 1.5 AgentKit 现状自剖（W-7 断点结论）

**机制层无bug**：`app_launch`（`AppControlTools.kt:313-391`）与 Operit 标准级同构；工具已注册（`ui/ChatViewModel.kt:482`，注入真实 shell 通道给 app_stop）；QUERY_ALL_PACKAGES 已声明（`AndroidManifest.xml:42`）；无障碍 ui_* 全家桶在位。工单描述「需查 Intent 启动/前台应用读取/无障碍联动哪一环断」（`docs/AgentKit-全程报告-2026-10-15.md:151`）——**逐环排查后，断点按概率排序如下**：

| 排序 | 断点 | 证据 | 性质 |
|---|---|---|---|
| **①** | **启动后无沉降 + 无前台确认 → 假成功 + 陈旧 ui_tree** | `AppControlTools.kt:379-383`：startActivity 后**立即**返回 ok，无 delay、无验证；ui_tree 走 `rootInActiveWindow`（`AgentAccessibilityService.kt:344`），窗口切换未完成时仍返回 AgentKit 自身窗口或 null（`:348` 返回 "[]"）→ 模型拿着旧树/空树判断「没打开快手」→ 放弃。Operit 对策：POST_LAUNCH_DELAY_MS=1000（`PhoneAgent.kt:788`）+ hasLaunchableTarget 预检（`:985`） | 体验/时序断点（最可能） |
| **②** | 后台 Activity 启动限制 | `app_launch` 在 Dispatchers.IO 工具协程里 startActivity（`:272,328,379`）；Android 10+ 对后台应用启动 Activity 有限制，**被拦时不抛异常**（静默失败）→ 工具返回「已启动」但实际没启，且无任何验证手段能发现 | 权限/场景断点（需实测覆盖） |
| **③** | 名称解析无静态字典兜底 | `resolveAppTarget`（`:107-133`）完全依赖 launchable 列表 label；小模型给错名（如「快手极速版」）→ NotFound/Ambiguous，多耗 1-2 轮；若同时命中 H2（maxSteps=16 实际值），多步跨应用任务被提前截断 | 鲁棒性断点 |
| **④** | device_foreground_app 未授权时无引导 + 读取法粗糙 | `DeviceForegroundAppTool.kt:37-44`：queryUsageStats(INTERVAL_DAILY, now-5min) 桶法 + maxByOrNull(lastTimeUsed)，在「刚启动」场景可能拿不到最新前台；`:46-52` 未授权时只返回失败 hint，**不自动打开设置页**（RikkaHub 会，见 §3.3） | 感知断点 |
| ⑤ | app_list 口径差 | AgentKit 只列有 launcher 入口的（`:218`），Operit 列全量（`StandardSystemOperationTools.kt:470`）——大部分场景 AgentKit 更优；仅「无入口应用的 Activity 直启」场景需注意 | 口径差异（低危） |

> **W-7 一句话结论**：Intent 机制没错，错在「启动即成功」的语义和「读完旧树就下结论」的时序。修复方向 = 沉降 + 前台确认 + 字典兜底（见第二部分），而不是重写启动机制。

---

## 第二部分 · W-7 修复/加固建议（P0/P1/P2）

> 每条给出：改哪个文件 / 做什么 / 验收什么。全部零新增外部依赖（只用 Kotlin 标准库 + 协程 + 已有 PackageManager/无障碍 API）。

### P0-1 · 静态中文应用名字典 + 包名兜底

- **文件**：新增 `tools/AppNameDictionary.kt`（纯 Kotlin object，无 Android 依赖，JVM harness 可断言）；修改 `tools/AppControlTools.kt` 的 `resolveAppTarget` 调用链（`:107-133`、`:339`）。
- **做什么**：内置 ~52 条中文热门应用「名称→包名」表（**快手 com.smile.gifmaker 必收**，其余据 Operit 公开字典整理，见下表）；`app_launch` 解析顺序改为：① 静态字典精确命中（中文名/常见别名）→ ② app_list 候选（现有 resolveAppTarget 三级策略）→ ③ **包名兜底**：仍 NotFound 且 target 形如包名（含 '.' 且无空格）时，直接 `getLaunchIntentForPackage(target)` 试启（学 Operit `PhoneAgent.kt:1228-1242` 末尾 `?: trimmed`）。字典同时供 app_list 的 query 预匹配提示。
- **字典表（主表据 Operit `StandardUITools.kt:61-252` 整理；包名为事实数据）**：

| 名称 | 包名 | 名称 | 包名 |
|---|---|---|---|
| 微信 | com.tencent.mm | 网易云音乐 | com.netease.cloudmusic |
| QQ | com.tencent.mobileqq | QQ音乐 | com.tencent.qqmusic |
| 微博 | com.sina.weibo | 汽水音乐 | com.luna.music |
| 小红书 | com.xingin.xhs | 喜马拉雅 | com.ximalaya.ting.android |
| 豆瓣 | com.douban.frodo | 番茄小说 | com.dragon.read |
| 知乎 | com.zhihu.android | 七猫免费小说 | com.kmxs.reader |
| 淘宝 | com.taobao.taobao | 飞书 | com.ss.android.lark |
| 京东 | com.jingdong.app.mall | QQ邮箱 | com.tencent.androidqqmail |
| 拼多多 | com.xunmeng.pinduoduo | 豆包 | com.larus.nova |
| 高德地图 | com.autonavi.minimap | keep | com.gotokeep.keep |
| 百度地图 | com.baidu.BaiduMap | 美柚 | com.lingan.seeyou |
| 美团 | com.sankuai.meituan | 腾讯新闻 | com.tencent.news |
| 大众点评 | com.dianping.v1 | 今日头条 | com.ss.android.article.news |
| 饿了么 | me.ele | 贝壳找房 | com.lianjia.beike |
| 携程 | ctrip.android.view | 安居客 | com.anjuke.android.app |
| 铁路12306 | com.MobileTicket | 同花顺 | com.hexin.plat.android |
| 去哪儿 | com.Qunar | 星穹铁道 | com.miHoYo.hkrpg |
| 滴滴出行 | com.sdu.did.psnger | 恋与深空 | com.papegames.lysk.cn |
| bilibili | tv.danmaku.bili | 设置 | com.android.settings |
| 抖音 | com.ss.android.ugc.aweme | **快手** | **com.smile.gifmaker** |
| 腾讯视频 | com.tencent.qqlive | 芒果TV | com.hunantv.imgo.activity |
| 爱奇艺 | com.qiyi.video | 优酷视频 | com.youku.phone |
| 红果短剧 | com.phoenix.read | 肯德基 | com.yek.android.kfc.activitys |

  别名同包策略（学 Operit）：B站/哔哩哔哩→tv.danmaku.bili；淘宝闪购→com.taobao.taobao；京东秒送→com.jingdong.app.mall；12306→com.MobileTicket；番茄免费小说→com.dragon.read；崩坏：星穹铁道→com.miHoYo.hkrpg。
  **建议自补（待真机核对，不在 Operit 字典内）**：快手极速版、闲鱼、钉钉、企业微信——先用 `adb shell pm list packages \| grep` 核对后再入表。
- **验收**：① JVM harness 断言「快手」→ com.smile.gifmaker、「B站」→ tv.danmaku.bili；② 模型传 com.smile.gifmaker 直接启动不经过名称匹配；③ 传「快手极速版」且未安装时返回 not_found 且 hint 含 app_list 引导；④ 字典查不到的「xx.yy.zz」形态输入走 getLaunchIntentForPackage 兜底。

### P0-2 · 启动后沉降 + 前台确认组合（W-7 核心修复）

- **文件**：修改 `tools/AppControlTools.kt`（`launchResolved`，`:363-391`）；修改 `automation/DeviceContextStore.kt`（增加等待 API，`:27-35` 区域）。
- **做什么**（学 Operit `PhoneAgent.kt:788,985` + 复用 AgentKit 自有事件驱动优势）：
  1. `startActivity` 成功后先 `delay(800~1000ms)`（取 Operit POST_LAUNCH_DELAY_MS=1000L 同值，常量 `POST_LAUNCH_SETTLE_MS`）；
  2. 轮询等待前台切换：以 250ms 间隔、最多 3s，读 `DeviceContextStore.foregroundPackage`（该 Store 由 `AgentAccessibilityService.kt:319-329` 在 TYPE_WINDOW_STATE_CHANGED/TYPE_WINDOWS_CHANGED 时更新，`DeviceContextStore.kt:27-35`，@Volatile 读取无锁）== 目标包名；
  3. 三分支返回：确认成功 → ok「已启动且已确认前台」；超时未确认 → **ok + warning**「已发出启动意图，但未在前台确认到（可能被系统拦截或应用异常），建议 device_foreground_app 复核」——**绝不分不清「发了」和「成了」**；startActivity 抛异常 → 现有 launch_failed。
- **验收**：① 真机「打开快手」：app_launch 返回后 1s 内 ui_tree 能读到快手窗口（不再返回 AgentKit 自身窗口/空数组）；② 杀快手进程后再启动同样成立；③ 目标包无 launcher 入口时仍走现有 no_launch_intent 分支；④ 日志可见 settle 与确认耗时。

### P0-3 · device_foreground_app 升级：未授权自动打开设置页 + UsageEvents 配对法

- **文件**：修改 `automation/DeviceForegroundAppTool.kt`（全文 66 行）。
- **做什么**（学 RikkaHub `data/ai/tools/local/ScreenTimeTool.kt:81-83,136-138,186`）：
  1. 权限未授 → **自动打开系统设置页**（`Intent.ACTION_USAGE_ACCESS_SETTINGS` + FLAG_ACTIVITY_NEW_TASK）+ 返回结构化错误 `{"error":"NO_PERMISSION","message":"...please ask the user to enable 'Usage access'..."}` 让模型转告用户（RikkaHub 同款双动作，`ScreenTimeTool.kt:81-83`）；
  2. 当前前台读取从 `queryUsageStats` 桶法（`:37-44`）升级为 **UsageEvents 逐个 ACTIVITY_RESUMED/PAUSED 事件配对**（`:136-138`：注释原话「比 queryAndAggregateUsageStats 更接近系统『屏幕使用时间』，避免统计桶溢出导致的范围偏差」；`SCREEN_NON_INTERACTIVE` 参与配对；`:186` MOVE_TO_FOREGROUND/BACKGROUND 与 API29 的 ACTIVITY_RESUMED/PAUSED 同值做兼容）；排除 launcher 包（resolveLauncherPackages，`:138,249`）；
  3. 保留 queryUsageStats 作为事件法无数据时的降级。
- **验收**：① 未授权时调用一次，设置页自动弹出且工具返回 NO_PERMISSION 结构化错误；② 授权后「刚切到微信」场景 1s 内返回 com.tencent.mm（桶法在此场景已知不可靠）；③ 回到桌面（launcher）不计为前台应用；④ 时间入参加 INVALID_TIME/INVALID_RANGE 结构化校验（RikkaHub 四格式入参可作 P2 参照）。

### P0-4 · H2 修复：适配层 maxSteps 默认值未随 hotfix5 生效

- **文件**：修改 `core/AgentLoop.kt:112`（适配层构造参数 `maxSteps: Int = 16`）；核对 `ui/ChatViewModel.kt:879-896`。
- **做什么**：hotfix5 只把 `core/agent/RealAgentLoop.kt:117` 的默认值改为 40，但适配层 `core/AgentLoop.kt:112` 仍默认 16 并**显式透传**（`:149`）给 impl；`ChatViewModel.kt:879` 构造适配层时未传 maxSteps → **生产实际生效值 = 16，hotfix5 白改**。两种修法（推荐 a）：a. 适配层默认值同步改 40（与 RealAgentLoop 一致，默认值单一真源）；b. ChatViewModel 显式传 `maxSteps = 40`。跨应用操控任务（launch→settle→ui_tree→tap→ui_tree→…）很容易在 16 步截断，与 W-7 直接相关。
- **验收**：① grep 确认 `core/AgentLoop.kt` 与 `RealAgentLoop.kt` 默认值一致（或 ChatViewModel 显式传参）；② 构造一次 AgentLoop 打印 effective maxSteps = 40；③ 一条 20 步的跨应用任务不再触发「已达到最大步数」文案（`RealAgentLoop.kt:270-272`）。

### P1-1 · ui_tree 陈旧窗口防护

- **文件**：修改 `automation/UiAutomationTools.kt`（`UiTreeTool`，`:65` 起）或 `automation/AgentAccessibilityService.kt`（`getUiTreeJson`，`:344-357`）。
- **做什么**：ui_tree 检测到「当前活动窗口包名 == AgentKit 自身包名」且距上次窗口切换事件 <1.5s 时，自动等待一个窗口切换事件再抓取（窗口事件源已在 `AgentAccessibilityService.kt:324-325` 监听，可复用）；`rootInActiveWindow` 返回 null 时重试一次再返回 "[]"（当前 null 直接返回 "[]" 会被模型误读成「空白界面」）。
- **验收**：① app_launch 后立即 ui_tree，不再返回 AgentKit 自身窗口节点；② 快速切换应用时 ui_tree 不出现空数组误报。

### P1-2 · 提示词纪律补强（学 Operit FunctionalPrompts.kt:720-721）

- **文件**：修改 `core/AgentLoop.kt` 的 `DEFAULT_SYSTEM_PROMPT`（`:20-30`，预算 ≤ SYSTEM_PROMPT_MAX_CHARS=4000，`:33`）。
- **做什么**：在界面操作协议段补两句：① 「启动应用优先用 app_launch（传中文名或包名均可），比在桌面上找图标点按更可靠」；② 「不知道包名时先用 app_list 查」。与 Operit 对模型的纪律同款（「start_app often more reliable than tapping icons」+「list_installed_apps to find package name」）。
- **验收**：① 提示词长度仍 ≤4000（QA harness 断言）；② 小模型任务「打开快手」不再出现「先回桌面找图标」路径。

### P1-3 · W-7 实测 Checklist（防修完不复测）

- **文件**：新增 `docs/research/refs-digest/T09-device-checklist.md` 同级或追加章节（现有 `T09-device-checklist.md` 可参考其格式）。
- **做什么**：固化复现矩阵，每条记录「期望/实际」：① 前台聊天态启动快手；② 切到后台 5s 后经通知/自动化触发启动（覆盖后台启动限制）；③ 锁屏态启动；④ 目标已在前台时重复 app_launch（应提示不要重复启动， Operit 纪律同款）；⑤ 目标未安装；⑥ 目标无 launcher 入口；⑦ 启动后 0/0.5/1/2s 四个时点各抓一次 ui_tree；⑧ 未授权 usage stats 时调 device_foreground_app。
- **验收**：8 条矩阵全部有真机记录；②若复现后台静默失败，P0-2 的 warning 分支能被触发并可见。

### P1-4 · app_stop shell 通道真机验证

- **文件**：核对 `tools/AppControlTools.kt:530-533` + `tools/ShellTool.terminalShellCommandRunner` 注册链（`ui/ChatViewModel.kt:482`）。
- **做什么**：验证注入的 terminalPool shell 通道确能执行 `am force-stop com.smile.gifmaker`（user shell 身份在多数 ROM 可 force-stop 三方应用）；不能则确认诚实降级文案（`:446-469` 已具备）在真机可见。
- **验收**：① 停止快手后 ui_tree 不再返回快手窗口；② 通道不可用时报 stop_needs_manual 且 hint 可见。

### P2-1 · Coomi「仅超时才回退」纪律落地

- **文件**：`automation/UiAutomationTools.kt` 的失败分支（与 `core/AgentLoop.kt:26-29` 的自纠三步协议对齐）。
- **做什么**：ui_* 失败时区分「服务未连接/超时」（可提示引导开无障碍）与「元素未找到/点击无效」（走自纠三步），**不把两类错误混为同一句重试提示**；任何回退路径（如未来的 shell 兜底）只在主路径明确不可用时触发，注释学 Coomi `lib.rs:1400` 原话精神。
- **验收**：① 关无障碍后 ui_tap 返回「服务未连接」类错误而非「元素未找到」；② 自纠三步文案两类错误各自准确。

### P2-2 · app_launch 的 shell 兜底通道预留（Debugger 级思路）

- **文件**：`tools/AppControlTools.kt`（`AppLaunchTool` 增加可选 `shellRunner`，对齐 app_stop 的注入模式 `:415-428`）。
- **做什么**：当标准 Intent 路径返回 no_launch_intent 或 P0-2 的 warning 分支触发、且 shell 通道可用时，按 Operit Debugger 级思路回退：`cmd package resolve-activity --brief <pkg>` 解析主 Activity → `am start -n <pkg>/<act>`；**明确不用 monkey**（`DebuggerSystemOperationTools.kt:293` 注释：monkey 会改系统设置如屏幕旋转）；解析失败不盲启。注意当前 system prompt 禁止 shell 操控界面（`core/AgentLoop.kt:26`），此兜底仅限「启动应用」不碰界面操作，需同步改提示词措辞。
- **验收**：① 无 launcher 入口但有 MAIN Activity 的应用可经 shell 兜底启动；② 解析失败返回可读错误；③ 提示词禁令与兜底不互相矛盾。

### P2-3 · RikkaHub AskUserTool 引入评估

- **文件**：新增 `tools/AskUserTool.kt`（若评审通过）。
- **做什么**：回合内 AI 向用户提问的工具（RikkaHub `LocalTools.kt:23` askUserTool），用于登录/验证码/歧义选择场景——与 T28 UI 子代理的 take_over 互补（take_over 是子代理退出，AskUser 是主代理提问）。**先评估后做**：W-7 修复后若仍频繁需要人工介入（如快手登录态），再排期。
- **验收**：（评审通过后）① 模型在需要用户选择时调用 ask_user 并暂停等待；② 用户回答后回合继续。

---

## 第三部分 · 四家新增源码级收获（对照 00-roadmap.md）

> 标注：✅ 已验证（本轮亲自读码确认行号）｜⚠️ 部分验证（核心结论有行号，细节据笔记）｜🆕 路线图缺口（现有 50 条改造项未覆盖）

### 3.1 AiCode —— 五块金矿

#### 3.1.1 三模式 BUILD/PLAN/AUTO（🆕 路线图缺口，建议补入 T23/T31 设计）

`feature/agent/domain/model/ChatSession.kt:6-10` ✅：`BUILD`（默认，允许所有授权操作）/ `PLAN`（拦截修改类操作，只读探索；工具被拦返回错误码 PLAN_MODE_REJECTED）/ `AUTO`（放行所有权限不弹窗，**仅用户手动可切，AI 无法通过 planMode 进入**）。`modeBeforePlan` 记忆进入 PLAN 前的模式（如 AUTO），退出时恢复。AI 通过 `planMode` 工具进出 PLAN（StatefulAgentWorkflow.kt:1364 ⚠️：退出方向直接放行不弹窗，因为有计划审查面板兜底）。
**对 AgentKit 的意义**：路线图 M-12/T23/T31 做的是**工具级权限**（单次 ASK/记忆 ALWAYS），没有**会话级模式**这一层。三模式解决的是「这一整轮我只想让你看，不想让你动」——对「让 AI 先调研再动手」的产品体验是结构性增强。建议：T23 设计时把「会话模式」作为权限评估的第一输入（PLAN 下所有写工具直接 DENY + PLAN_MODE_REJECTED 错误码），成本增量 <0.5d。

#### 3.1.2 权限策略引擎（✅ 验证核心，增强 M-12/T23/T31）

`feature/agent/domain/permission/ToolPermissionPolicyEngine.kt`（406 行）⚠️核心行号已验证（`:24-40,147,357`）：
- Verdict = ALLOW / DENY / ASK；Bash 评估顺序（⚠️细节据笔记）：① 用户 DENY 规则（可覆盖内置白名单）→ ② 命令不可静态判定（命令替换/分组/绝对路径重定向）→ ASK 且**不可记忆** → ③ 命中内置只读白名单（ls/git status 等）→ ALLOW → ④ 命中已记忆 ALLOW 规则 → ALLOW → ⑤ 否则 ASK，可记忆 pattern 为「程序名」或「程序名+子命令」（对 git/npm 这类子命令分发器记 `git pull` 而非 `git`，不同子命令独立授权）；
- **Shizuku 工具按高危**：无论白名单/记忆规则一律 ASK 且不可记忆，AUTO 模式也不自动放行（`:147` 注释；`:24-40` reason 原话：「Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆」；`:357`）；
- 破坏性 rm 多层防护（⚠️据笔记）：根目录删除/相对通配（`*` `.*` `..` `../*`）/根级通配（`^/[^/*?]*[*?]`）/工作区根/系统关键目录（/bin /boot /dev /etc /home /lib* /proc /root /run /sbin /sys /usr /var…）/不可判定构造+疑似破坏性程序（rm|dd|shred|truncate|mkfs|wipefs|fdisk|parted|mkswap|blkdiscard）；
- `NON_REMEMBERABLE_CAPABILITIES`：MODIFY_AGENT_CONFIG / MODIFY_CONTAINER_ENV 不可记忆（`:48`）；
- 用户选择 REJECT/ONCE/ALWAYS，ALWAYS 按 PermissionScope.PROJECT 记忆（StatefulAgentWorkflow.kt:1380-1400 ⚠️）。
**对 AgentKit 的意义**：M-12 已裁决「身份严格/获取降级」双轨 + ExecutionRules 四模式（ALLOW/LOCKED_ONLY/CONFIRM/DISABLED）。AiCode 的三条可净室吸收：① 「程序名+子命令」级记忆（比整命令精确、比程序名粒度细）；② **高危能力不可记忆清单**（Shizuku/MODIFY_CONFIG 类——AgentKit 的 ShizukuBackend 与 ConfigStore 正对应）；③ rm 的多层静态判据可直接进 T31 DangerousOpHook。

#### 3.1.3 Checkpoint（✅ 验证，T29 路线图条目坐实）

`feature/agent/domain/checkpoint/CheckpointManager.kt` ✅：
- `activeCheckpointIds: ConcurrentHashMap<sessionId, checkpointId>`（`:47`）——**按会话隔离**（注释：多会话含父子代理并行跑 workflow，单一字段会被后创建的会话覆盖，导致快照挂错会话）；
- 只快照「将被修改的文件」，同一 checkpoint 内同一文件只保留**最原始一份**（countSnapshot 查重，⚠️据笔记）；
- 快照时记内容指纹；**回滚判据用 FileVersionRegistry「本会话最后看到的版本」**（每次 write/readFile 都刷新），而非快照时指纹——注释详解：拿快照指纹比对会把本会话自己的正常改动误判成外部改动，回滚永远拒绝（`:146` 区域 ✅）；
- 外部（其他会话/用户）叠加的改动 → **跳过并记入 skipped**（`:24-29` 注释原话：「静默跳过等于把数据丢失换成了无声的失败」——调用方必须展示 skipped 清单）；
- 回滚 = 目标 checkpoint 及其之后所有 checkpoint 的快照**倒序**还原；CREATE 类型 → 删除文件（已知局限：无指纹无法判断后续写入）；写路径共用 `WorkspaceFileLock`。
**对照路线图**：T29（`00-roadmap.md` §4 Phase 3，来源 aicode.md A6）✅ 完全属实，且「FileVersionRegistry 判据优于快照指纹」这一细节是 digest 之外的新增量——T29 落地时**必须**按「本会话最后看到的版本」实现，否则回滚会永远拒绝。

#### 3.1.4 子代理系统（🆕 路线图缺口，T28 的潜在增强）

`feature/agent/domain/subagent/`（⚠️据笔记）：定义文件 `agents/<name>.md`（frontmatter + 正文）：name / description（何时派它）/ providerId / model / reasoningEffort / mode / allowedTools（空=继承全量）/ disallowedTools（先于白名单生效）/ inject / prompt；null = 继承父会话。作用域 GLOBAL / PROJECT（项目级可 git 追踪）。`InjectPart` 五种注入片段：BASE（agent/subagent-base.md 子代理精简基线）/ MAIN_RULES（00~70 全量规则）/ SKILLS / MEMORY / PROJECT_RULES。主代理通过 `task(agent="<name>")` 派发。
**对 AgentKit 的意义**：T28（UI 子代理）目前设计为单一专用循环；AiCode 给出的是**通用子代理框架**（markdown 定义 + 工具黑白名单 + 提示词注入片段组合）。对 AgentKit 的落地建议：T28 先按专用循环做（不变），但把「子代理 = 独立会话 + 工具子集 + 注入片段」的定义方式记录下来，作为 T36 元工具之后的备选架构；「allowedTools 空=继承全量 / disallowedTools 先于白名单」的语义可直接借给 T28 的工具裁剪。

#### 3.1.5 提示词资产化（✅ 验证，T18 路线图条目坐实 + 新增可抄文本）

`app/src/main/assets/prompts/`（⚠️据笔记，目录结构经主理人核实）：`00-identity.md` / `10-communication.md` / `15-project-rules.md` / `20-coding-discipline.md` / `30-comments.md` / `40-approach.md` / `50-safety.md` / `60-tools-and-paths.md` / `70-skills-and-mcp.md` / `99-runtime-context.md`（**编号即注入顺序**）+ `agent/` 专用提示词（auto-mode / commit-generator / compact-summary / init / plan-mode / subagent-base / subagent-context / title-generator）。
**对照路线图**：T18（M-08）✅ 架构论断属实（编号片段 = 路线图原设计）。新增增量：`20-coding-discipline` 的六条核心原则（只做任务要求的事不夹带 / 以核实过的代码为准 / 遵循既有约定 / 完整实现不写半成品 / 只在系统边界做校验不为不可能场景加兜底 / 不留向后兼容临时手段）——**这是可直接净室吸收进 T18 的 `20-coding-discipline.md` 的现成文本资产**（原则性表述，非代码）。

#### 3.1.6 editFile 精确字符串匹配 + 双层写保护（✅ digest 已述，本轮无新发现）

EditFileTool.kt:37-56 精确匹配 + WorkspaceFileLock + 写前指纹校验返 STALE_FILE——已入路线图 T20/T21，本轮无增量，从略。

### 3.2 Coomi —— 「仅超时才回退」的操控纪律（增强 P2-1，已验证）

`apps/coomi-rs/tools/src/lib.rs:1400-1401` ✅注释原话：「其它错误如实返回，避免在用户毫无感知的情况下反复用 adb 盲点」。主路径（无障碍文件队列，6s 超时）与回退路径（adb input/screencap）的切换判据是**输出含「无响应」**——即「服务没开」这一类可判定的基础设施故障，而非笼统的「失败了就换条路」。
**对 AgentKit 的意义**：路线图 M-04/T04 管「同指纹打转熔断」，Coomi 这条管「**不同通道之间的回退判据**」——AgentKit 的 app_stop 已有同款品德（`AppControlTools.kt:446-469` 三分支不假装成功），ui_* 通道尚缺（见 P2-1）。另：Coomi shell 工具 environment 五选一（`lib.rs:532-550`）可作为 M-02 TerminalIdentity 枚举的佐证（auto/host/termux/proot/shizuku ≈ AgentKit 的 User/Su/Shizuku/Ssh + proot）。

### 3.3 RikkaHub —— AskUser 与 ScreenTime 两个可复用范本（✅已验证）

- **本地工具 9 个按需装配**：`data/ai/tools/local/LocalTools.kt:23-53` ✅：javascript / timeInfo / clipboard / tts / **askUser** / screenTime / calendarQuery / calendarCreate / chartDisplay，按 `LocalToolOption` 开关装配。对 AgentKit 的启示：工具可按开关装配（M-11 工具暴露降级的另一形态——按场景裁剪而非只按 provider 裁剪）。
- **AskUserTool** ✅（`:23`）：回合中 AI 向用户提问。AgentKit 无对应工具（见 P2-3 评估）。
- **ScreenTimeTool** ✅（276 行，`:81-83,136-138,186,249`）：① 权限未授自动打开系统设置页 + 结构化 NO_PERMISSION 错误让模型转告用户；② UsageEvents 逐个 ACTIVITY_RESUMED/PAUSED 配对计算真实前台时长（比 queryAndAggregateUsageStats 更接近系统「屏幕使用时间」，避免统计桶溢出的范围偏差）；③ 排除 launcher 包；④ 时间入参四格式（ISO-8601 日期/本地日期时间/偏移日期时间/epoch 毫秒）+ INVALID_TIME/INVALID_RANGE 结构化校验；top 钳制 1~50 默认 10。**这是 device_foreground_app 的升级范本（P0-3 已吸收 ①②③）。**
- **消息分支实体验证** ✅：`data/db/entity/MessageNodeEntity.kt`：message_node 表 = id + conversation_id(FK CASCADE) + node_index + messages(JSON List<UIMessage>) + select_index——M-06/T12 论断再次属实。

---

## 合规声明

1. **许可证纪律（不变）**：Operit（LGPL-3.0）/ RikkaHub（AGPL-3.0）/ AiCode（GPL-3.0）三家**只读思路、净室重写**——本报告及其派生的所有改造项（含字典表、沉降确认、权限判据、Checkpoint 语义）均为设计原理吸收，不复制其代码结构、命名与注释原文（引用注释原话仅作论据，属合理使用）；Coomi（Apache-2.0）可深参，仍净室重写。`00-roadmap.md` §6.4 的落地引用规范继续有效：**代码注释里禁止粘贴参照仓库源码片段**。
2. **字典表特别说明**：本报告 P0-1 的「名称→包名」表是**互操作性事实数据**（应用包名是客观标识符，如同电话簿），据 Operit 公开字典整理并标注来源；「建议自补」4 条未经核实，入表前必须真机核对。
3. **零新增外部依赖红线（不变）**：本报告全部建议只用 Kotlin 标准库 + 协程 + Android SDK（PackageManager / UsageStatsManager / AccessibilityService / ActivityManager）+ 已有依赖（okhttp / kotlinx-serialization / rhino），无任何新增 Gradle 依赖、无 JNI、无 assets 二进制。
4. **推测标注**：文中「待验证」处（AiCode 权限引擎五步顺序细节、Checkpoint countSnapshot、子代理 frontmatter 七字段、Operit Shower 回退分支外的边角）来自主理人笔记的核心结论，行号级细节建议下一轮按需抽验；W-7 断点排序为基于代码的推断，需 P1-3 实测矩阵最终确认。

---

*报告完 · 撰写人：产品经理 许清楚（Xu）· 来源：四家源码实测 + 主理人核实笔记 · 落盘：`docs/research/refs-digest/deepdive-appcontrol-and-beyond.md`*
