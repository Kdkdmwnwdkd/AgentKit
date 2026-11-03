# device — 真机/模拟器验收脚本

这里放**必须在设备上跑**的验收，不能靠源码断言或单元测试替代。

前置条件：

- 一台能 `adb` 连上的设备或模拟器（脚本用 `ADB` 环境变量指定，默认 `adb`）
- 已安装被测包，`applicationId` 与 `PKG` 一致（默认 `com.example.agentkit`）
- **debug 包**：脚本用 `run-as` 读 `files/run-journal/`，非 debuggable 包读不到
- 模型端点可控：`scripts/../../` 之外的 mock 端点即可，只要能让模型下发一次
  `write_file`，并且这个 `write_file` 会**阻塞**（见下）

## r1-interrupt.sh —— 工具执行中被杀，重进不重放

这是 R1 唯一的最终判据。步骤：

1. 把工作区里的 `t.txt` 换成 FIFO（`mkfifo`）。`write_file` 写它会永久阻塞，
   于是「已越过执行边界、尚未产出结果」这个窗口可以被稳定制造出来。
2. 触发一次 `write_file`（手动发一句话，或让 mock 端点下发）。
3. 等到 `files/run-journal/*.rjl` 里出现 `DISP` 且**没有** `SETTLE`，立刻
   `am force-stop`。
4. 重新启动应用。
5. 断言：同一份日志被补成 `SETTLE … OUTCOME_UNKNOWN` + `STEP` + `CLOSE interrupted`；
   应用日志出现 `[Recovery] 上次回合被中止：N 个写操作结局未知`；
   下一轮请求里带上了「可能已经生效，先核对」的说明；**没有第二次执行同一个写操作**。

```bash
ADB=/path/to/adb PKG=com.example.agentkit bash r1-interrupt.sh prepare   # 造 FIFO 并清日志
# …在设备上手动触发 write_file，等 DISP 出现…
ADB=… bash r1-interrupt.sh kill       # 打印日志并 force-stop
ADB=… bash r1-interrupt.sh verify     # 重启后检查修复结果
```

## r1-approval-window.sh —— 审批弹窗期间被杀，**不该**判成「可能已生效」

判据：审批还没点，写操作一次都没跑，恢复后应判 `NOT_DISPATCHED`（可以安全重试），
提示语应是「N 个调用还没开始执行」，而不是「N 个写操作可能已经生效」。

这条测的是「记账点必须在审批之后」。早先的实现把 `ToolDispatched` 写在
`executeOne` 开头，审批弹窗的 30 秒倒计时也被算进「已越过执行边界」，
用户犹豫时进程被杀，重启后会被告知一个从未发生的写操作「可能已生效」。

## 环境说明（2026-11-03 实测）

在 4 vCPU / 8 GiB、`-accel off`、软件渲染的云端模拟器上：冷启动 50–60 秒，
`input tap` 偶发不生效，输入法偶发不聚焦，应用会因「输入事件投递超时」被系统判 ANR。
ANR 时应用主线程栈是空的（`Looper.pollOnce` 空转），是**输入进不来**，不是应用卡住；
同一台机器上未改动的 1.14 版本表现一致。自动化脚本要多留重试与等待，别把这类超时
当成被测代码的问题。
