# 待真机验证清单

> 规则：**服务器上能验的一律在服务器验掉**（源码断言 / JVM harness / 编译产物反验 / 静态扫描）。
> 只把「必须真实进程、真实生命周期」的项攒到这里，最后一次性验完，不逐项催。

## 一次验收怎么走（约 20 分钟）

1. arm64 手机连 adb（x86 模拟器只能做 P4、P5）。
2. 装包：`./gradlew :app:assembleDebug`（**不要**带 `-PnoNative`）→ `adb install -r`。
3. 按 P1 → P3 顺序点，P4/P5 任何设备都能做。
4. **任何一步不对，不要截图**：日志页 →「诊断包」→ 发我。R10 起这个包里带
   `turns.ndjson`（一行一个事件对象），比截图和 logcat 片段都好定位。

每项都写了「通过标准」，判据是**日志原文或界面文字**，不需要理解实现。

---

## P1 容器租约：成功路径确实借出了租约

| | |
|---|---|
| 阻塞原因 | 需要 arm64（assets 只有 arm64 版 proot，rootfs 里的 busybox 也是 aarch64） |
| 代码 | `ContainerScreen.ContainerSessionHolder.acquire` / `ContainerLease.borrow` |

**步骤**：侧栏 →「容器」→ 等 rootfs 解压完成、出现 shell 提示符。

**通过标准**
- `adb logcat -d \| grep 容器租约` 出现 `容器租约就绪：container:alpine`；
- 设置 → 资源租约，出现一行 `容器会话 · alpine`，`持有` 为 1，`空闲` 从 0 开始涨。

---

## P2 容器租约：退出归还 + 空闲回收

| | |
|---|---|
| 阻塞原因 | 同上；且要观察真实 proot 进程 |
| 代码 | `release()` 的 1 秒宽限期 / `ContainerLease.releaseIdle` |

**步骤**
1. 从容器页返回，等 2 秒：`adb shell ps -A \| grep -c proot` → 应为 **0**。
2. 再进容器页，1 秒内退回再进（模拟旋屏/误触）：`持有` 应回到 1，**不出现 2**。
3. 可选（要等 30 分钟，或临时把 `LeaseRegistry.idleTimeoutMs` 调成 60_000）：
   放着不动，日志出现 `空闲回收容器会话：container:alpine`，`ps` 里 proot 消失。

**通过标准**：第 1 步为 0；第 2 步恒为 1；第 3 步回收日志与进程消失**同时**成立
（只有日志没有进程消失 = 回收打空，这是 R9 之前那个 bug 的形态）。

---

## P3 容器租约：切换发行版不串会话

| | |
|---|---|
| 阻塞原因 | 需要能真的跑 proot + 两个 rootfs |
| 代码 | `acquire()` 里 `sessionDistroId != distro.id` 分支 |

**步骤**：alpine 里 `echo alpine > /root/who` → 切 debian → `cat /root/who`。

**通过标准**
- `cat` 报 `No such file or directory`（rootfs 独立）；
- 日志有 `切换发行版 alpine → debian，销毁旧容器会话`；
- 不会出现「标题写 debian、shell 还是 alpine」——这是修复前的老 bug。

---

## P4 容器租约：失败路径不借租约（任何设备都能做）

| | |
|---|---|
| 阻塞原因 | 无（x86 模拟器、无 proot 的真机都可） |
| 代码 | R3-15 静态断言对应的运行时行为 |

**步骤**：在没有 proot 的环境进容器页。

**通过标准**
- 页面提示错误（当前文案：`无法创建容器会话（proot 不可用？）`）；
- `adb logcat -d \| grep -E "容器租约就绪\|create 返回 null"` **只有**后者；
- 设置 → 资源租约**不出现**任何 `容器会话` 行（租约为空时整块不渲染）。

这条验的是「借了却拿不到会话」——若出现 `容器租约就绪`，`activeCount` 会永久卡在 1，
空闲回收再也不会触发。

---

## P5 诊断包含回合事件（任何设备都能做）

| | |
|---|---|
| 阻塞原因 | 需真实跑一轮对话产生回合日志 |
| 代码 | `DiagnosticsExporter.exportBundle` / `RunJournal.exportAllNdjson`（R10） |

**步骤**：跑一轮带工具调用的对话（比如让它读一个文件）→ 日志页 →「诊断包」→ 选任意应用发送。

**通过标准**
- 分享面板里是**两个**文件：`agentkit-diagnostics-*.txt` 和 `agentkit-turns-*.ndjson`；
- 回执文字是 `诊断包已生成（含回合事件）`；
- ndjson 每行是一个 JSON 对象，含 `seq` / `at` / `turn` / `scope` / `type`；
  `grep '"type":"settle"' | wc -l` 的数量与这一轮实际工具调用次数一致。

没有回合日志时（全新安装、还没聊过），应只分享一个 txt 且回执不说「含回合事件」。

---

## 观察到再说（不阻塞，先记着）

| # | 项 | 观察什么 | 判据 |
|---|---|---|---|
| O1 | 工具预算上限 | `shell_run` 跑超 10 分钟的命令（如 `gradle build`） | 是否在 600 秒被掐断。掐断属预期行为，但要确认提示里说了「改用 shell_background」 |
| O2 | 资源租约采样开销 | 设置页停留 5 分钟，其间操作终端 | 页面是否卡顿。`snapshot()` 每 2 秒在 IO 线程采一次，理论上不碰主线程 |
| O3 | 容器空闲策略 | 容器与终端同时开着，各自闲置 | 容器是否比终端先被回收（当前两者都是 30 分钟，无独立策略） |
| O4 | 聚合商换上游重试（R10） | 用 OpenRouter / 硅基流动连续请求，撞到 429 或 402 | 日志应有 `第 N 次调用失败，准备重试`，且 N 最多到 4（官方直连只到 3）。若 402 一次就失败，说明档位没生效 |
| O5 | 长对话不再「忘了前面」（R10） | 接 deepseek（64k）把上下文预算调到 100k，聊到 60k+ token | 实际预算应被压到 32k（窗口一半）；表现为压缩提前发生，而不是请求 400 或静默截断 |
| O6 | 第三层归一化有没有乱判（R10） | 任何失败后看日志 | `provider.misclassified` 若频繁出现且 evidence 不相干，说明关键词表需要收窄——这是唯一能发现误判的入口 |
| O7 | 长对话不再越聊越卡（R13） | 一次连续对话拉到 100+ 条消息，全程流式输出 | 滚动与输入应保持跟手。会话写入已改成 400ms 去抖（`ConversationStore.persistCount` 可查写盘次数）：100 条消息的写入次数应在个位数，而不是 100 次 |
| O8 | 工具数组参数在三家都能用（R11） | 用 OpenAI / Claude / Gemini 各发一次「并行查三个文件」触发 `spawn_agents` | 模型应正确产出 `tasks` 数组。修前 schema 里 `items` 是空的，严格实现（Gemini）会拒掉整个工具声明，表现为「这个工具像不存在」 |
| O11 | 升级后 MCP 工具默认不可见（P3-3） | 配一个 MCP 服务器 → 冷启动 → 看工具表；再问一句「用 mcp 工具查一下」 | 预期：默认不在工具表；模型应 `__use_tools("mcp")` 开组后可见。若模型反复报「没有这个工具」且从不试开组，说明组索引的措辞不够明确，需改 `noteOf("mcp")` |
| O10 | 能力清单零偏差（P3-1） | 冷启动后 `adb logcat -s AgentKit` 过滤 `能力清单` | 应**一条都没有**。若出现「能力清单有 N 处不同步」，说明有工具没登记组（会落进 misc），把日志里的工具名补进 `CapabilityManifest` 对应组的 `tools` |
| O12 | 遥测流水真的落了盘（P5-1） | 跑两个回合 → 看 `files/logs/` 下有没有 `turns-<stamp>.ndjson`（导出诊断包时它会被改名成 `agentkit-turns-<stamp>.ndjson`） | 流水文件里应有 `turn.start` / `prompt.plan` / `llm.call` / `llm.end` / `turn.end` / `store.write` 成对行。**若目录里只有 `agent-*.log` 没有 `turns-*.ndjson`**，说明 `TraceSink.init` 失败或队列没 flush——这是三项度量继续「未知」的原因。⚠️ 本行原先写的文件名 `agentkit-events-*.ndjson` **是错的**，实现叫 `turns-*.ndjson`，2026-11-04 更正 |
| O13 | 度量三项符合目标（P5-1） | 把 ndjson 拉回电脑，`python3 scripts/qa/trace-metrics.py <file>` | 期望：确定性错误白等 <1s、每回合会话写入 <1MB、重放 0 次。若白等仍有几十秒，看输出里「N 次还发生了重试」——那说明某类错误没被 `ErrorNormalizer` 判成确定性。⚠️ 「每回合会话写入」若显示 0.0KB **不是达标，是没量到**（2026-11-04 修过一次归属规则，见下节） |
| O9 | 断电不丢会话（R12） | 非必做：写入过程中强杀进程（`am force-stop`）后重进 | 会话要么是完整的一条，要么回到上一版；**不能**是空文件或半截 JSON。空文件说明 fsync 没生效 |
| O14 | 启动不再被判 ANR（2026-11-04 修） | 冷启动 App（**首次**装包或 WebView 被系统回收后再进） | 日志不应出现 `Completed ANR of com.example.agentkit` / `Force finishing activity ...MainActivity`。旧实现里后台 WebView 的创建排在首帧之前，冷启动要 20~30 秒占着主线程 → 首帧出不来 → 系统判 ANR（`does not have a focused window`）→ 弹回桌面。修后 WebView 改在首帧之后创建，构造耗时会打进日志（`后台 WebView 已就绪（构造 Nms，总计 Nms）`） |

---

## 已经在服务器验过的（不用再验）

| 层 | 手段 | 结果 |
|---|---|---|
| 租约语义 | `run-r3-verification.sh` R3-1~14 | 14/0 |
| 借还顺序 | 同脚本 R3-15（源码顺序断言） | 通过 |
| 回合事件 / 崩溃恢复 | `run-r7-verification.sh` | 20/0 |
| 技能脚本链路 + 能力表/清单对账 | `run-batchb-verification.sh` | 93/0 |
| 工具预算 / 作用域 | `run-runtime-capability-verification.sh` | 46/0 |
| 工具 schema 单点序列化 | `run-r11-verification.sh` | 11/0 |
| 原子写（真文件 / 真并发） | `run-r12-verification.sh` | 13/0 |
| 会话落盘去抖 | `run-r13-verification.sh` | 8/0 |
| 架构守卫（分层 / 行数 / 单点） | `run-arch-guard-verification.sh` | 5/0 |
| 能力清单四条双向断言 | `run-capability-manifest-verification.sh` | 30/0 |
| 生成运行态状态机（含取消/异常） | `run-generation-run-verification.sh` | 34/0 |
| 遥测流水字段契约与落盘 | `run-trace-telemetry-verification.sh` | 30/0 |
| 度量脚本自身的正确性 | `run-trace-metrics-verification.sh` | 14/0 |
| 工具表顺序 = 清单顺序（含乱序输入）+ 清单↔实现双向 | `run-kernel-exposure-verification.sh` 第 9~10 段 | 83/0 |
| 工具面 token 预算（默认 ≤525 / 满载 ≤3500） | `run-tool-surface-verification.sh` | 3/0 |
| 终端仿真器（打印/宽度/SGR/光标/擦除/备用屏/历史/畸形输入/并发/缩放） | `run-terminal-emulator-verification.sh` | 78/0 |
| 审批归组（同类操作同组 + 需审批工具都有中文说明） | `run-approval-grouping-verification.sh` | 9/0 |
| CI 门禁本身（含「全 SKIP 不算绿」） | `run-gate-verification.sh` | 20/0 |
| 启动顺序（首帧先于后台 WebView 创建） | `run-startup-order-verification.sh` | 5/0 |

真机验的是**这些断言覆盖不到的那一层**：真实 proot 进程的生死、真实 Compose 生命周期、
真实分享面板。断言通过不代表真机通过，反之亦然——两边都要，但不用重复。

---

## 2026-11-04 在验证机（x86_64 模拟器）上已验的项

环境：模拟器 API 30 / x86_64 + mock 模型（`http://10.0.2.2:8899/v1`，设备上已配好
「假模型」档位）。**不是 arm64 真机**，所以容器租约（需要 arm64 proot）那一类仍然挂账。

| 项 | 结果 | 依据 |
|---|---|---|
| O10 能力清单零偏差 | ✅ 通过 | 冷启动日志里**没有**「能力清单有 N 处不同步」；审计在 `ChatViewModel` 初始化时、MCP 注册前跑，零偏差即无输出 |
| O12 遥测真的落盘 | ✅ 通过 | `files/logs/turns-20261103.ndjson`（962 字节）出现，内容为 `store.write` / `turn.start` / `prompt.plan` / `llm.call` / `llm.end` / `turn.end` / `store.write` 七行 |
| O13 三项度量 | ✅ 通过 | 白等 0 次 0.0s（目标 <1s）；每回合会话写入 58.3KB（目标 <1MB）；重放 0 次 |

### 这台机器上发现的三个真问题（都已修）

1. **启动被判 ANR，App 闪退**：日志原文
   `Completed ANR of com.example.agentkit in 30580ms` + `Force finishing activity
   com.example.agentkit/.MainActivity`，原因 `does not have a focused window`。
   根因是后台 WebView 的创建排在首帧之前，冷启动 20~30 秒占着主线程。修法见
   `MainActivity.onCreate`（首帧的 `OnDrawListener` 里再 attach）。
2. **「每回合会话写入」度量恒为 0**：归属规则要求落盘时间戳落在回合窗口内，而落盘是
   去抖延迟写、真机上恰恰发生在 `turn.end` 之后 → 全被丢掉 → 目标 <1MB 恒成立。
   修法见 `scripts/qa/trace-metrics.py`（归到最后一个已开始的回合，回合外的写单独显示）。
3. **首帧修完之后，App 反而崩了**——这一条是**修 1 时自己引入的**：

   ```
   java.lang.IllegalStateException: Cannot call removeOnDrawListener inside of onDraw
       at android.view.ViewTreeObserver.removeOnDrawListener(ViewTreeObserver.java:745)
       at com.example.agentkit.MainActivity$onCreate$2.onDraw(MainActivity.kt:61)
   ```

   `ViewTreeObserver` 正在派发 `onDraw` 时不能摘监听。harness 全绿（它只读源码文本），
   真机一装就崩。这条记在这里，是想说明：**「服务器上能验的一律在服务器验掉」不等于
   「服务器上验过就等于对」**——真机项攒起来不是形式主义，是有些缺陷只能在设备上出现。

### 这台机器上**不能**做的事（省得下次再试）

- **触摸设备被禁用**：`dumpsys input` 里每个输入源都是
  `Touch Input Mapper (mode - disabled)`。所以 `adb shell input tap` **完全不生效**——
  点不亮输入框、键盘不弹、消息发不出去。`input text` / `keyevent` 也无效（没有焦点窗口）。
- **可行替代：用 workflow 通知入口触发回合**，不需要碰 UI：

  ```bash
  # 1. 往应用私有目录写一条 workflow（run-as 能写；目录要先 mkdir）
  adb shell 'run-as com.example.agentkit mkdir -p files/workflow'
  adb push wf.json /data/local/tmp/wf.json
  adb shell 'run-as com.example.agentkit sh -c "cat /data/local/tmp/wf.json > files/workflow/workflows.json"'
  # 2. 带 workflow_id 启动：MainActivity 的 LaunchedEffect 会自动执行它（prompt 直接当对话输入发出去）
  adb shell am start -n com.example.agentkit/.MainActivity --es workflow_id w-test
  # 3. 看结果
  adb shell 'run-as com.example.agentkit tail -20 files/logs/agent-*.log'
  adb shell 'run-as com.example.agentkit cat files/logs/turns-*.ndjson' > /tmp/turns.ndjson
  ```

  判定「回合真的跑了」的标志：`[Workflow] 从通知进入，准备执行 <id>` →
  `[Chat] 用户发送：…` → `[AgentLoop] 开始运行…` → `[Turn] … turn.end status=completed`。

---

## 2026-11-04 复测（构建机 native 编译 + 验证机干净复测）

这一轮补掉了两件之前一直挂着的事：**native arm64 首次编译通过**，以及
**三项度量在「不打断进程」的干净环境下复测通过**。

### 补掉 1：native arm64 首次真编译（构建机）

之前构建机上**从来没有 llama.cpp 源码**，所以历来的 APK 都是
`./gradlew :app:assembleDebug -PnoNative=true` 出的（28.2MB，**不含 lib/**）——
它能装进 x86 模拟器只是因为「没有 lib/ 目录 = 任何 ABI 都能装」，
代价是 **JNI 那条路一次都没被编译验证过**。

这次按 `scripts/fetch-llama.sh` 拉了固定 tag `v0.7.0`（commit 校验通过），
再跑不带 `-PnoNative` 的完整构建：

```
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a]
BUILD SUCCESSFUL
app-debug.apk  35,159,658 字节（29.8MB → 35.2MB）
```

APK 内实测含两个自编 .so：

| 文件 | 大小 |
|---|---|
| `lib/arm64-v8a/libagentkit-llama.so` | 4,471,456 |
| `lib/arm64-v8a/libagentkit-termux.so` | 331,928 |

（另有 `libmlkit_google_ocr_pipeline.so` 等第三方 .so。）

**结论**：llama.cpp v0.7.0 + JNI 的 arm64 交叉编译在构建机上成立。
两个产物各有用途，已留在构建机：

- `/data/home/app-debug-native-arm64.apk`（35.2MB）→ **真机用**（含 lib/）
- `/data/home/app-debug-emulator.apk`（28.2MB，无 lib/）→ **模拟器用**

### 补掉 2：验证机干净复测（三项度量全达标）

上一轮的 O13 是「跑到一半强杀进程」测出来的，`重放 3 次` 其实是**人为制造的
撕裂记录被修复**，不是产品缺陷。这轮改成：全新安装 → 只跑一个**自然结束**的回合
→ 不强杀进程 → 直接取流水。

触发回合的两处配置（模拟器触摸被禁用，只能走文件 + intent 入口）：

```bash
# ① 模型指向 mock LLM：adb reverse 把设备 127.0.0.1:8899 转到宿主机 mock
adb reverse tcp:8899 tcp:8899
adb shell "run-as com.example.agentkit sh -c 'cat > shared_prefs/agentkit_model_config.xml'" < cfg.xml
# ② 预置工具组：calculator 属于 sys 组，默认不激活，不置的话模型直接调会撞 unknown_tool
adb shell "run-as com.example.agentkit sh -c 'cat > shared_prefs/tool_activation.xml'" < act.xml
#   → <string name="active_groups">file,sys</string>
# ③ 工作流（prompt 里带 "write" 会触发 write_file，带 "calc" 触发 calculator）
adb shell "run-as com.example.agentkit sh -c 'cat > files/workflow/workflows.json'" < wf.json
adb shell am start -n com.example.agentkit/.MainActivity --es workflow_id w-calc
```

⚠️ 装完 App 立刻写 `shared_prefs/` 会失败（**目录还不存在**），
必须先 `adb shell run-as com.example.agentkit mkdir -p shared_prefs files/workflow`，
否则配置静默丢失，回合会以 `status=failed` 收场。

工具面因此从 5 个涨到 9 个，`calculator` 真跑起来了：

```
[ToolExposure] 工具面裁剪：provider=openai mode=GROUPED 暴露 9 个（含 2 个元工具），裁掉 49 个；已启用组=file,sys
[Turn] tool.start step=0 tool=calculator args={"expression":"(120+80)*0.85"}
[Turn] tool.end   step=0 tool=calculator code=calc_ok error=false dur=315ms
[Turn] turn.end status=completed steps=2
```

官方度量脚本（`scripts/qa/trace-metrics.py`）对这份流水的结果：

| 指标 | 实测 | 目标 | 判定 |
|---|---|---|---|
| 确定性错误白等 | 0 次，均值 0.0s，峰值 0.0s | <1s | ✅ |
| 每回合会话写入 | 均值 3.5KB / 峰值 3.5KB | <1MB | ✅ |
| 崩溃恢复重放 | **0 次** | 0 | ✅ |

退出码 0。事件流 15 行，类型齐全：
`store.write×4 / turn.start / prompt.plan×2 / llm.call×2 / llm.end×2 /
tool.start / tool.end / step.close / turn.end`。

同时确认：冷启动**无 FATAL EXCEPTION、无 ANR、无「能力清单有 N 处不同步」**。

### 本轮新发现的两件事

1. **模型直接调未激活组的工具，报的是「未知工具」**。
   `calculator` 登记在 `sys` 组，默认只激活 `file` 组，模型若不先调 `__find_tools`
   发现它就会拿到 `code=unknown_tool`。工具**确实存在**，但回执把它说成「不存在」。
   这是给模型看的错误文案，建议改成「该工具当前未启用，请先 `__find_tools` 查找」
   ——否则模型会以为是自己编了个不存在的工具，转而去猜别的名字。

2. **x86 软件模拟（`-accel off`）上首帧 37.3 秒**（`Displayed: +37s297ms`）。
   不是回归：`ChatViewModel` 初始化那约 9 秒主线程重活还在，只是没有 KVM 的
   模拟器把它放大了 3~4 倍。**这条仍然挂账**：arm64 真机上的首帧耗时没有数过。

### 仍然挂账（没变的）

- arm64 真机：O1~O9/O11、容器租约（需 arm64 版 proot）、无障碍、真实分享面板。
- **首帧耗时**（arm64 真机未测；`ChatViewModel` 初始化 9 秒主线程重活未处理）。
- P5-4 发布签名缺 secret。

---

## 2026-11-04 第二轮：三条挂账落地（启动可归因 + 工具回执分路 + Crashpad 静默）

上一轮列的三条「说不清」——`unknown_tool` 文案、`ChatViewModel` 9 秒、Crashpad 日志——
这一轮全部落地成**可验证的事实**。详细过程见
`AgentKit-启动可归因与工具回执分路-2026-11-04.md`。

### ✅ 挂账 1：未启用组的工具回执（已修 + 已验）

**改法**：新增失败码 `tool_not_enabled` 与专属文案，与 `unknown_tool` 严格分开；
执行器拿到全量注册表后做二分判定（存在但不在本回合工具视图 → 「未启用」）。

**验证机实测**（`active_groups=file`，`sys` 组不激活，mock LLM 调 `calculator`）：

```
23:50:08.788 INFO [ToolExposure] 工具面裁剪：provider=openai mode=GROUPED 暴露 5 个（含 2 个元工具），裁掉 53 个；已启用组=file
23:50:10.479 WARN  [Tool] 工具未启用：calculator（组 sys）——请先 __use_tools 启用该组
23:50:10.596 INFO  [Turn] tool.end step=0 tool=calculator code=tool_not_enabled error=true dur=130ms
23:50:11.784 INFO  [Turn] turn.end status=completed steps=2
```

回执现在说清三件事：**存在但未启用**、**属于哪个组**、**怎么开**（`groups="sys"`）。
真不存在的工具仍然回 `unknown_tool`（两条路径互不干扰，有断言守着）。

### ✅ 挂账 2：`ChatViewModel` 初始化 9 秒（已埋点，数字可归因）

新增纯 Kotlin 的启动分段计时器（`core/StartupTrace.kt`），在 ViewModel 初始化里打 6 个点。
**验证机实测（x86 软件模拟，两次冷启动）**：

```
23:45:07.699 INFO [Startup] ViewModel 初始化分段：skills=674ms, marketSources=13ms, registry=732ms,
                 reconcile=79ms, workflows=150ms, refresh=729ms, total=2377ms
23:47:04.759 INFO [Startup] ViewModel 初始化分段：skills=445ms, marketSources=9ms, registry=593ms,
                 reconcile=137ms, workflows=105ms, refresh=834ms, total=2123ms
```

「9 秒」这句话被数据取代了：这台无 KVM 的软件模拟器上总耗时 **2.1～2.4 秒**，
重活集中在 `refresh`（729~834ms）、`registry`（593~732ms）、`skills`（445~674ms）。
**下一轮要动的对象就是这三个数**（本轮只埋点，不优化）。

> arm64 真机首帧仍未测——但现在真机一装上，日志里就会直接给出这些数字。

### ✅ 挂账 3：chromium Crashpad E 级日志（已修 + 已验）

创建 WebView 之前先 `mkdirs()` 建出 `cache/WebView`（chromium 的 mkdir 不是递归的）。

**验证机实测（必须全新安装，`-r` 覆盖安装会保留 `cache/`，测不出东西）**：

```
23:55:04.415 INFO [BrowserHost] 开始创建后台 WebView
23:55:04.444 INFO [BrowserHost] 已预建 WebView 缓存目录：/data/user/0/com.example.agentkit/cache/WebView
23:55:06.971 INFO [BrowserHost] 后台 WebView 已就绪（构造 2509ms，总计 2555ms）
```

logcat 里 **0 条** crashpad 行、**0 条** chromium 的 E 级行。

### 本轮新发现的一件事（本轮未改，记为新挂账）

**App 已在前台时，`am start --es workflow_id w-calc` 不会触发工作流**——
系统提示 "intent has been delivered to currently running top-most instance"，
但 `onNewIntent` 里的 `pendingWorkflowId` 没有被 Compose 消费（无「从通知进入，准备执行」日志）。
冷启动带同一个 extra 则正常（`onCreate` 路径）。

影响：真机用户「App 开着的时候点定时任务通知」这条路径可能不执行。
本轮未定位根因、未改代码，**新挂账**。

### 这一轮的验证数字

| 项 | 结果 |
|---|---|
| 全量 harness | `PASS=90 SKIP=0 XFAIL=0 FAIL=0`（新增 2 个 harness） |
| 门禁 | 5/5 全过 |
| 文本断言 | 1373 → **1370**（新断言全改结构化比较，基线同步收紧） |
| 大文件行数 | `ChatViewModel` 3890 / `RealAgentLoop` 1557，**均未超冻结上限** |
| 反向变异 | 「回退注入点 → 必红 → 恢复 → 重新变绿」6/6 通过 |
| 三项度量 | 白等 0 次 / 写入 3.5KB / 重放 0 次 |


### 补掉 3：全量 harness 两处「第一次真跑」暴露的陈旧断言

这轮把 APK 路径传给 `run-all.sh`、并且 native 包编出来（AGP 产物
`app/build/tmp/kotlin-classes/debug` 就位）之后，**两个一直 SKIP 的 harness 第一次真跑**，
各暴露一条陈旧断言。两条都不是产品缺陷，是断言自己过期了：

| harness | 以前 | 现在暴露的问题 | 处理 |
|---|---|---|---|
| `run-apk-verification.sh` | SKIP（本地默认不传 APK 路径） | 查包内字符串 `READ_ONLY_TOOLS`，但 R4 已把它改名成 `NO_SIDE_EFFECT_TOOLS` | 反验脚本改查新名 |
| `run-localmodel-verification.sh` | SKIP（缺 AGP 编译产物） | 断言写死了**旧注册顺序**的工具表；P3-5 之后顺序由清单决定，数量对（25=25）但顺序不同 | 改成「数量相等 + 成员集合相等 + 顺序＝`CapabilityManifest.sort()`」 |

第二条值得多说一句：它原本断言「全量开关下模型收到**这 25 个、这个顺序**」。
把顺序写死，等于把「注册顺序」当成了不变量——而 P3-5 恰恰把这个不变量废掉了。
现在断言的是**真正的不变量**：「顺序由清单决定」，清单改了断言会跟着改，不会再过期。

> 这也印证了门禁第 5 条（PASS 数不得低于基线）的价值：
> 环境一变，两个 harness 从 SKIP 变 PASS，PASS 数从 86 变 88——
> 如果只看「FAIL=0」，这次会以为没变化；实际上**验得更多了**。
> 反过来，如果这次是环境变差导致大面积 SKIP，基线会立刻报红。

顺带补齐了 CI 的 **Fetch Shizuku APK** 步骤（本地构建默认不走它），
native 包因此与 CI 产物一致，反验里「assets/shizuku.apk 存在且体积合理」也真跑起来了：

```
$ curl -fL "$URL" -o app/src/main/assets/shizuku.apk    # RikkaApps/Shizuku v13.6.0
  2,571,773 bytes
```

### 最终产物

| 产物 | 大小 | 用途 |
|---|---|---|
| `/data/home/app-debug-native-arm64.apk` | 36,948,986 | **arm64 真机**（含 lib/ + shizuku.apk） |
| `/data/home/app-debug-emulator.apk` | 30,031,067 | **x86 模拟器**（无 lib/，含 shizuku.apk） |

用最终 APK 重做的验证机取证（全新安装 → 自然结束的一回合 → 不打断进程）：
冷启动 **0 崩溃 / 0 ANR / 0 能力清单不同步**，`Displayed: +34s760ms`，
`calculator` → `code=calc_ok error=false`，`turn.end status=completed steps=2`，
三项度量 **白等 0 次 / 写入 3.5KB / 重放 0 次**（`trace-metrics.py` 退出码 0）。
