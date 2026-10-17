# W-13 调试通道：阶段一（协议层）已落地

方案见 `docs/w13-adb-channel-plan.md`。本文件记录已写进 App 的代码与验证结果。

## 已完成（阶段一，纯 JVM）

| 文件 | 内容 |
|---|---|
| `automation/adb/AdbPacket.kt` | adb 24 字节报文编解码（小端、magic=command 取反、CRC32）、命令枚举、AUTH 子类型、`headerPayloadLength` |
| `automation/adb/AdbCrypto.kt` | RSA-2048 生成、token `SHA1withRSA` 签名/验签（与 adbd `RSA_sign(NID_sha1)` 一致）、adb 公钥格式编码 |
| `automation/adb/AdbShellProtocol.kt` | `shell,v2` 增量解析：stdout/stderr/exit 分流、半包跨分片、退出码小端、未知流忽略 |
| `scripts/qa/AdbProtocolVerification.kt` | 14 条断言（报文 5 / 密钥 4 / shell v2 5） |
| `scripts/qa/run-adb-protocol-verification.sh` | 离线运行器（Gradle 缓存优先，沙箱回落 kotlinc） |

**验证：14/14 PASS**（`bash scripts/qa/run-adb-protocol-verification.sh`）。

## 阶段二 a：连接 / AUTH / shell 执行（已完成）

| 文件 | 内容 |
|---|---|
| `automation/adb/AdbClient.kt` | TCP 连接 + `CNXN → AUTH TOKEN → 签名 → CNXN` 握手；`OPEN(shell,v2,raw:cmd)` → `OKAY/WRTE/CLSE` 收流；stdout/stderr 分流、退出码、输出截断；未授权时抛 `AdbAuthException`（引导去配对，绝不假装成功） |
| `scripts/qa/AdbClientVerification.kt` | **回环假 adbd**（ServerSocket 按 AOSP 协议应答）端到端验证 5 个场景 |
| `scripts/qa/run-adb-client-verification.sh` | 离线运行器 |

**验证：5/5 PASS**——正常握手+`echo ok`、stderr/退出码 1、密钥被拒、50k 输出截断、
stdout 帧跨两个 WRTE 拼接。

**边界**：`AdbClient` 只负责「已信任密钥」之后的传输与执行；**首次配对仍是独立流程**。

## 阶段二 b：配对通道骨架（已完成，SPAKE2 待接）

| 文件 | 内容 |
|---|---|
| `automation/adb/AdbPairingPacket.kt` | 配对帧（version=1/type/length，大端）、PeerInfo（8192B）、AES/GCM、`Spake2` 接口 |
| `automation/adb/AdbPairingClient.kt` | TLS（自签不校验）→ `exportKeyingMaterial("adb-label\0", 64B)` → 口令=码+材料 → 帧交换 → 加密 PeerInfo |
| `scripts/qa/AdbPairingVerification.kt` | 帧/PeerInfo/AES/端到端（假 SPAKE2）**7/7 PASS** |

**协议实证**（对照 AOSP `pairing_connection` 与公开 Kotlin 实现）：AOSP **不用 TLS-PSK**，
而是普通 TLS + 导出密钥材料；Android 12+ 有公开 API（`SSLSockets.exportKeyingMaterial`），
所以配对层可纯 Kotlin 实现，无需 native。

**唯一未完成**：`Spake2` 真算法（BoringSSL `spake25519` 的移植，Apache-2.0）。
接口已就位，接上即可联调；在此之前配对流程不可用。

## 未完成（阶段二起，需真机）

1. **配对**：无线调试配对端口 + 6 位码 + SPAKE2/TLS-PSK（本阶段最大工作量），
   成功后设备保存本 App 公钥；
2. **连接与 AUTH 登录**：`CNXN → AUTH TOKEN → AUTH SIGNATURE → CNXN`；
3. **连接管理**：`AdbBridge` 状态机、断线重连、端口变化处理；
4. **工具路由**：`run_shell` / 文件 / 应用 / 设置走 shell；`ui_*` 仍走无障碍；
   Android 10 及以下的 `ui_screenshot` 用 `screencap` 兜底；
5. **UI**：权限中心「调试通道」项 + 无线调试引导 + 配对码输入。

## 边界

- 需要 Android 11+ 且用户在开发者选项开启「无线调试」并配对一次；
- 不在后台点其他应用界面（系统限制），但文件/应用/设置操作完全后台；
- 不读其他应用私有数据、不绕过锁屏。

## B 方案（Shizuku 兜底）已接线

| 位置 | 改动 |
|---|---|
| `tools/ShellTool.kt` | 新增 `privilegedExec` 注入 + 纯函数 `shouldUsePrivilegedShell`（带 session 不走、显式 user/su/ssh 不走、未指定且通道就绪则自动优先）；结果类型 `PrivilegedShellOut` |
| `ui/ChatViewModel.kt` | 注入 `ShizukuShell.exec` 适配（每次执行实时判态，不缓存）；`shizukuStatus` 状态 + `handleShizukuAction`（未运行→拉起/商店，未授权→申请，就绪→刷新）；能力链传 `shizukuAvailable` |
| `core/capability/CapabilityBridges.kt` | 候选顺序变为 **调试通道 → proot → 本机 user**，含标签/原因/可操作提示 |
| `ui/PermissionsScreen.kt` | 「调试通道（Shizuku）」授权项（三态文案）+ 授权结果回调即时刷新 |

**行为**：Shizuku 就绪时 `shell_run` 默认走 uid 2000（不跳屏、可 `settings put` / `pm` / 文件操作）；
未配置时静默回落原路径（不改变现有体验）。系统拒绝仍显式报 `permission_denied`，绝不假装成功。

### 验收（2026-10-17）

- 第三道门：CI run 170（`f07bf3f`）**Build debug APK success**（Shizuku API/反射/Compose 全部编译通过）。
- 第四道门：APK dex 反验 **全绿**（29 项判据 + 7 项反证）。对象：run 170 产物
  （28,272,297 B，sha256 `44b5ce20…2cfcb`）。
  新增判据：`ShizukuShell` 类描述符 / `shouldUsePrivilegedShell` / `PrivilegedShellOut` /
  `handleShizukuAction` / 「调试通道（Shizuku）」/「通过无线调试启动」/「shell uid 2000」。

### 四态修正（同日，用户反馈「Shizuku 已装却仍劝去商店」）

问题：`Status` 只有 NOT_RUNNING / NO_PERMISSION / READY，「已装未启动」被归成「未运行」，
文案一律「先安装 Shizuku」——用户明明装好了，点按钮却往没有该应用的应用商店跳。

修正：
- `ShizukuShell.isInstalled(context)` / `launch(context)`；
- `UiState` 四态（NOT_INSTALLED / INSTALLED_NOT_RUNNING / NO_PERMISSION / READY），
  由纯函数 `uiState(status, installed)` 推导；
- 动作与按钮文案随态变化：去安装 / **打开 Shizuku** / 去授权；
- 未安装时商店拉起失败退 GitHub releases；已装但无 launcher 入口退应用详情页。

验收：CI run 172（`be64827`）Build + Publish success；APK 反验全绿
（新增判据：`isInstalled` / `uiState` /「已安装未启动」/「打开 Shizuku」；
产物 28,272,297 B，sha256 `e01c584c…41e90`）。

### 包名修正（同日，真机录像）

现象：用户已装 Shizuku，权限页却显示「未安装」，点按钮跳到商店空白页「当前页面已失效」。

根因：`PACKAGE = "moe.shizuku.manager"` —— 这是 Shizuku **内部 manager 模块名**，
不是安装包名；官方 applicationId 为 **`moe.shizuku.privileged.api`**，`isInstalled` 恒 false。

修正：`PACKAGES` 候选（privileged.api / manager / com.rosan.sui）+ `installedPackage()`；
未安装时直接开官方 releases 页（不走 market://）；无障碍未连接文案补充「装新版本后需重开」。

验收：CI run 174（`10af272`）success；APK 反验全绿（新增「官方包名候选」「未安装走官方发布页」；
28,272,297 B，sha256 `be4522cf…463ff`）。
