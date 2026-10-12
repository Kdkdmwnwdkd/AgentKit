# 03 · 执行层：终端组件与 Linux 容器

> 对应九步顺序的第 4、5 步。目标：App 内能敲命令（终端）、能跑完整 Linux 环境（容器）——这是「写代码/写游戏/跑 python/node」的执行底座。**本模块许可证最危险，全部决策先看 09 号文档。**

## 参考项目

| 项目 | 许可证 | 参考什么 | 可用度 |
|---|---|---|---|
| termux/termux-app | **GPLv3** | 终端仿真器（VT100 解析 + pty 会话 + 渲染）的行业标准架构 | ❌ 代码不可抄，架构可学 |
| ReTerminal | **MIT** | 现代终端组件，VT100 解析 + Compose/View 渲染 | ✅ 可深度参考/直接用 |
| AnLinux | Apache-2.0（脚本集合） | 「下载 rootfs + proot 启动」的分发脚本思路 | ✅ 思路照搬 |
| AAswordman/Operit | LGPL-3.0 | 配套 App 装 Ubuntu、主 App 检测包名引导下载的模式 | ❌ 架构参考 |
| jieapi/AiCode | GPL-3.0 | 内置 Alpine rootfs + proot 管理的实现路径 | ❌ 架构参考 |
| Coomi | Apache-2.0 | Termux 兼容环境的用户引导设计 | ⚠️ 交互思路可鉴 |
| proot | **GPLv3** | 无 root 的 chroot 替代（ptrace 重写路径） | ⚠️ 二进制随包分发，代码不抄 |
| tiny_container | 调研参考 | 极简容器封装（启动脚本层） | ✅ 思路参考 |

## 它怎么做的（架构思想）

### 终端仿真器（Termux 的四层解剖）

Termux 终端拆成四块，职责极其清晰，我们按同样的切法重建：

1. **TerminalEmulator（纯 Kotlin，无 Android 依赖）**：VT100/xterm 状态机——吃字节流，维护「屏幕缓冲区 + 光标 + 属性」文本模型。 CSI 序列解析、滚动区域、SGR 颜色全在这里。**这是整个终端最值钱也最独立的一块。**
2. **TerminalSession**：持有 pty 文件描述符，一个读线程把从进程来的字节喂给 Emulator，一个写线程把用户输入写给进程。生命周期 = 一条 shell。
3. **JNI 层（libtermux.so）**：`forkpty`/`openpty`/`waitpid`——Android 的 bionic 有 pty，但 Java 层拿不到，必须 native。
4. **TerminalView（渲染）**：把屏幕缓冲区画出来，处理手势（滑动滚历史、双指缩放字号、长按选择复制）。

### 容器（三条路线的架构本质）

- **AiCode 路线（内置 rootfs）**：`assets/_armAssets/alpine-rootfs.bin`（3MB 级 tar.gz 打包）随 APK 分发，首次启动解压到 filesDir，`proot -r <dir> -0 -w /` 起 shell。优点：离线可用、体验确定；缺点：APK 膨胀、系统老旧。
- **Operit 路线（配套 App）**：主 App 检测不到 Ubuntu 环境时，引导用户装一个独立"系统 App"（那个 App 里才是完整 proot + rootfs + sshd）。优点：主 App 干净、rootfs 可做得很大；缺点：多 App 心智成本。
- **AnLinux 路线（脚本编排）**：一切现场拼——下载官方 rootfs tar、写 `etc/resolv.conf`、`proot --link2symlink` 补缺失符号链接。优点：永远跟上游；缺点：每一步都可能被设备差异绊倒。

**共同点（真正要学的）**：rootfs 只是一个目录 + 一个 proot 启动参数模板 + 一份"设备兼容性兜底"（bind /proc /sys /dev， resolv.conf 注入， `--link2symlink`）。与 App 本身通过「会话句柄」交互，容器里跑的东西和 App 是**进程隔离**的。

## 我们的实现

### 第 4 步：终端组件（独立出包）

```
dev/terminal/
├── TerminalEmulator.kt    // VT100 状态机（纯 Kotlin，可单测）
├── TerminalSession.kt     // pty 会话：读线程→Emulator，写线程→pty
├── TerminalJni.kt         // external fun：openpty/read/write/close/kill
└── ui/TerminalScreen.kt   // Compose 渲染 + 手势 + 输入栏

app/src/main/cpp/termux-jni/   // 仅 openpty/forkpty 的薄封装，自写 200 行 C
```

**自写而非引用的决策**：Termux 的 Emulator 虽好但是 GPLv3，而 VT100 状态机是**有充分公开规范（xterm ctlseqs）可照做的确定性工程**——我们按规范重写，测试向量同样公开，不存在"抄不出来的"风险。渲染层直接用 Compose `Text` 逐行画（终端行数 24-50，性能足够），不做自定义 View。

**验收点**：App 内打开终端 Tab，`ls /data/data/com.example.agentkit` 有输出，`echo hi` 回显，Ctrl+C 能中断，上下滑看历史，长按复制。

**pty 封装自写 C 代码注意**：
- Android 9+ 对 `execve` 有 seccomp 限制，pty 本身可用（Termux 证明）
- 每次会话 = 一个独立进程组，`killProcessGroup` 清理，**别留僵尸 shell 耗电**
- 环境变量注入：`HOME`、`PATH=/data/data/<pkg>/files/usr/bin:...`、`LANG=C.UTF-8`

### 第 5 步：容器管理

```
dev/container/
├── RootfsManager.kt       // rootfs 下载/解压/校验/删除
├── ProotRunner.kt         // 启动参数模板 + 进程管理
├── ContainerSession.kt    // 一条容器 shell 会话（复用 TerminalSession）
└── ui/ContainerScreen.kt  // 发行版卡片（Alpine 内置/Ubuntu 下载）
```

**启动参数模板**（AiCode 路线，净室重写）：

```
proot --link2symlink -r <rootfsDir> -0 -w /root \
  -b /dev -b /proc -b /sys \
  -b <appFilesDir>/home:/home \
  /bin/sh
```

- `--link2symlink`：补 rootfs 里缺的符号链接（AnLinux 踩坑总结，必带）
- `-0`：模拟 root（proot 的 fake-root）
- bind /dev /proc /sys：不然连 `ls` 都报错
- 宿主 home 目录 bind 进去：**容器和宿主共享工作区**，这是后面编辑器（04）能直接改容器内文件的前提

**分发策略**：Alpine minirootfs（~3MB）打 tarball 进 assets 随包走；Ubuntu base rootfs（~30MB）走下载。**APK 增大用户明确说了没关系**，所以默认两个都内置，下载兜底。

**proot 二进制怎么合法地带**（详见 09）：
- 方案 A（推荐）：编译 proot 为独立可执行文件放 `assets/bin/arm64-v8a/proot`，App 运行时可执行——**进程隔离、动态加载、不静态链接**，我们的代码零 GPL 沾染；随包附 LICENSE 全文与源码获取说明
- 方案 B（备选）：用户设备已装 Termux 时，借用其 `libproot.so`（检测 `com.termux` 包名）——体验不稳定，仅作降级

**验收点**：容器内 `apk add nodejs` 成功（Alpine）、`python3 -c 'print(1)'` 成功、`node -e 'console.log(1)'` 成功，退出容器后宿主工作区文件还在。

### 与现有代码的衔接

- 现有 `ui/ConsoleScreen.kt`（423 行）是**设备自动化控制台**，与终端无关——新终端/容器作为**抽屉新目的地**（`AppDest` 枚举新增 `TERMINAL`、`CONTAINER`），走 v3.1 暖纸规范
- 终端会话句柄暴露给 Agent：新增 `AgentTool`——`runCommand(command, session)`，让 LLM 能直接在终端/容器里执行命令（这是「AI 写代码 → AI 自己跑」闭环的关键一跃）
- 工作区目录统一为 `context.filesDir/workspace/`（现有 FileTool 的 workspace 参数已存在，`BuiltInTools.kt:70`），终端默认 cd 到这里

## 注意事项

1. **许可证红线**：Termux 的 `TerminalEmulator`/`TerminalSession`/`libtermux.so` 全是 GPLv3，**一行都不抄**；proot 二进制随包要履行 GPL 义务（LICENSE + 源码书面要约）。开源发布前把 09 号文档的合规清单过一遍。
2. **seccomp 与厂商 ROM**：部分国产 ROM 禁 ptrace，proot 直接起不来。启动失败时给明确文案（"系统限制了 ptrace，可尝试 XX 设置"），而不是静默崩溃。
3. **耗电**：pty 读线程是阻塞 IO，会话不用时必须 kill 进程组；App 退后台 5 分钟自动回收空闲会话（现有 LifecycleEventObserver 模式可复用）。
4. **rootfs 解压位置**：`filesDir/containers/<distro>/`，卸载 App 即清理；sd卡 上不给用户选（scoped storage 会破坏 proot 的路径假设）。
5. **安全边界**：容器内进程 = 本 App uid 的沙箱内，**不宣传为"安全隔离"**；它是开发环境，不是安全沙箱。README 里写清楚。
