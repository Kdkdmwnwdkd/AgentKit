# 容器租约：验证边界与真机验收清单

R9 引入 `ContainerLease`（`container:<distro>`）之后，这条链路一直挂在「未真机验证」。
本文件把**已经验过什么、还差什么、差的那部分怎么验**写死，避免每轮重新推理一遍。

## 1. 三层验证矩阵

| 层 | 内容 | 状态 | 手段 |
|---|---|---|---|
| L1 租约语义 | 计数、回收判定、幂等销毁、快照字段 | **已验** | `run-r3-verification.sh` R3-1~14（JVM，假 alive/destroy） |
| L2 借还时序 | 只在会话 start 成功后 borrow；失败回滚 refs；切发行版先销毁再借 | **已验（静态）** | `run-r3-verification.sh` R3-15（源码顺序断言） |
| L2 运行时 | 真实 Compose 生命周期（旋屏宽限期、快速进出、切发行版） | **未验** | 需设备，见 §4 |
| L3 proot 真进程 | 容器能启动、能执行命令、空闲回收真的杀掉它 | **未验** | 需 arm64 真机，见 §4 |

分层的意义：L3 失败不代表租约错，L1/L2 通过也不代表容器能跑。R9 之前把三件事混成
一句「容器租约未验证」，导致无法判断风险到底在哪一层。

## 2. 为什么 x86_64 模拟器验不了 L3

三个缺口**同时**存在，缺一个都跑不通：

1. `assets/bin/` 只有 arm64-v8a 的 proot（`ProotRunner.assetDir()` 在 x86 上返回
   `bin/x86_64`，该目录不存在 → `ensureBinary` 返回 null）；
2. `assets/containers/alpine.tar` 里的 busybox 是 **aarch64** ELF，x86 内核 execve 直接失败；
3. `-PnoNative` 构建排掉全部 `.so`，终端 JNI（`agentkit-termux`）缺失，
   `TerminalSession.start()` 必失败——即使前两项补齐也走不到 `borrow()`。

`scripts/fetch-x86-testkit.sh` 能补第 1、2 项（Termux 官方 x86_64 proot + Alpine x86_64
rootfs，装进 `src/debugX86/assets`），但第 3 项要改 CMake 的 ABI 与 llama 目标，属于
为验证而扩大改动面。该脚本头部注释的结论仍然成立：

> 即使套件齐全，proot 在 x86_64 模拟器上仍可能 execve 失败；
> 这套件主要用于验证 UI 与「发行版识别 / 版本判定 / 解压」链路，容器真正跑命令仍需 arm64 真机。

## 3. 顺带修掉的脚本 bug

`scripts/build-proot.sh` 的 `x86_64` 分支此前**从未跑通过**，两处错误：

| 位置 | 问题 | 修法 |
|---|---|---|
| `build_abi` 的 case 表 | `fmt="elf64-little"`，llvm-objcopy 不认，报 `invalid output format` | 改为 `elf64-x86-64`（GNU 与 LLVM 的 ELF 机器名不同） |
| `loader-info.c` 生成段 | 无条件用 readelf 找 `pokedata_workaround`，而该符号只在 `#if defined(__aarch64__)` 下定义 | x86_64 分支直接写 `offset=0`，并注明它不会被引用 |

修复后 `./scripts/build-proot.sh x86_64` 端到端通过，产出 309400 字节
（`ELF 64-bit LSB pie executable, x86-64, for Android 26`）。

> 注意：编出来的 x86_64 proot **不要**提交进仓库——正式包只带 arm64，
> 多一份 300KB 资产只是白占体积。本地验证请走 `fetch-x86-testkit.sh` 的 `src/debugX86/`。

## 4. arm64 真机验收清单

拿到设备后按顺序做，全程约 15 分钟。

### 4.1 前置

```bash
./gradlew :app:assembleDebug            # 不要带 -PnoNative
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
```

### 4.2 断言 A：成功路径确实借出了租约

进容器页（侧栏「容器」），等 rootfs 解压完成、shell 出现提示符。

```bash
adb logcat -d | grep -E "ContainerLeases|ContainerLease"
```

期望**两条**：

```
容器租约就绪：container:alpine
```

再进「设置 → 资源租约」，期望出现一行 `容器会话 · alpine`，`持有` 为 1，
`空闲` 从 0 开始增长。

### 4.3 断言 B：退出后宽限期结束归还，且空闲回收能真的杀进程

```bash
# 返回上一页，等 2 秒（ROTATION_GRACE_MS 宽限期）
adb shell ps -A | grep -c proot          # 期望 0
```

若 `ROTATION_GRACE_MS` 内重新进入容器页，`ps` 应仍有 proot 且设置页持有数回到 1
（不出现 2——出现 2 说明 `acquire` 在复用会话时又 borrow 了一次，是 L2 回归）。

空闲回收（可选，需要等 30 分钟或临时把 `LeaseRegistry.idleTimeoutMs` 调小）：

```bash
adb logcat -d | grep "空闲回收容器会话"
adb shell ps -A | grep -c proot          # 期望 0
```

### 4.4 断言 C：切换发行版不串会话

在 alpine 里留下可识别的痕迹（`echo alpine > /root/who`），切到 debian，再 `cat /root/who`。

- 期望：文件不存在（rootfs 独立）；
- `adb logcat -d | grep "切换发行版"` 应有一条 `alpine → debian，销毁旧容器会话`；
- 设置页应同时/先后出现 `容器会话 · alpine` 与 `容器会话 · debian`，**不会**出现
  「标题 debian 但仍是 alpine 的 shell」。

### 4.5 断言 D（失败路径，任何设备都能做）

在没有 proot 的环境（x86 模拟器，或把 assets 里 `bin/arm64-v8a/proot` 临时改名重打包）：
进容器页应看到明确错误，且：

```bash
adb logcat -d | grep -E "容器租约就绪|ContainerSession.create 返回 null"
```

期望**只有** `ContainerSession.create 返回 null`，**没有** `容器租约就绪`。
设置页不应出现任何 `容器会话` 行（`LeaseRegistry.isEmpty()` 为空时整块不渲染）。

这一条是 R3-15 的运行时对应物：它证明失败路径没有偷偷借出租约。

## 5. 什么时候该重新审视本文件

- 容器页改走会话池（`TerminalSessionPool`）——那时 `ContainerLease` 应整体删除，
  改回 `terminal:proot`，本文件作废；
- 加入第二个容器后端（如 chroot / QEMU）——`LeaseKind` 与 `ContainerLeases` 的
  key 需要重新设计；
- 决定正式支持 x86_64（如 ChromeOS / 部分平板）——§2 的三个缺口要一次性补齐，
  否则容器页在这些设备上永远是「proot 不可用」。
