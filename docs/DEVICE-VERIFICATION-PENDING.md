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

---

## 已经在服务器验过的（不用再验）

| 层 | 手段 | 结果 |
|---|---|---|
| 租约语义 | `run-r3-verification.sh` R3-1~14 | 14/0 |
| 借还顺序 | 同脚本 R3-15（源码顺序断言） | 通过 |
| 回合事件 / 崩溃恢复 | `run-r7-verification.sh` | 20/0 |
| 技能脚本链路 | `run-batchb-verification.sh` | 89/0 |
| 工具预算 / 作用域 | `run-runtime-capability-verification.sh` | 46/0 |

真机验的是**这些断言覆盖不到的那一层**：真实 proot 进程的生死、真实 Compose 生命周期、
真实分享面板。断言通过不代表真机通过，反之亦然——两边都要，但不用重复。
