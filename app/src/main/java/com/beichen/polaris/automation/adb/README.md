# automation/adb —— 无线调试通道（W-51 起部分启用）

> 状态：**已启用**。`AdbSession` + `Spake2Ed25519` 支撑「设置 → 调试通道 → 无线调试配对」，
> 并以 `AdbShellChannel` 的形式接进 `ShellChannels`，供 `ui_tree` / `ui_click` / `input` 兜底。
>
> `run_shell` 的第三级后端也已接上（W-53）：`AdbTerminalBackend` 用
> `AdbClient.openInteractiveShell()` 起一条常驻 `sh`，语义与 `LocalUserBackend` 一致
> （只是 uid 从 App 变成 2000）。候选顺序 `proot → adb → user`。

## 与 Shizuku 的关系

两者都是 **uid 2000（shell）**，能做同一批事（`input` / `uiautomator dump` / `screencap` /
`pm` / `settings`）。区别只在前置条件与失效模式：

| | Shizuku | 无线调试直连 |
|---|---|---|
| 前置 | 装 Shizuku App 并授权本应用 | 开发者选项里开「无线调试」并配对一次 |
| 依赖 | 第三方 App 常驻进程 | 无（系统 adbd） |
| 失效模式 | Shizuku 进程被杀 → binder 失效 | 端口变化 / 配对失效 → 需重配 |
| 探测成本 | `pingBinder()`，微秒级 | 只看「是否配对过」，不发包 |

所以候选链是 `无障碍 → Shizuku → 无线调试`：Shizuku 更轻，ADB 更独立。

## 文件

| 文件 | 内容 |
|---|---|
| `AdbPacket.kt` | 消息头编解码（CNXN/AUTH/OPEN/WRTE/OKAY/CLSE） |
| `AdbCrypto.kt` | RSA-2048 生成、adb 公钥格式、TOKEN 签名 |
| `AdbClient.kt` | 连接 + AUTH + shell v2 |
| `AdbShellProtocol.kt` | shell v2 子协议（stdout/stderr/exit 分流） |
| `AdbPairingClient.kt` / `AdbPairingPacket.kt` | TLS + SPAKE2 配对、PeerInfo 加解密 |
| **`Spake2Ed25519.kt`** | SPAKE2（boringssl/AOSP 版）：Ed25519 群运算 + 协议流程 |
| **`AdbCredentials.kt`** | 密钥对与端点持久化（SharedPreferences，私有目录） |
| **`AdbSession.kt`** | 会话管理：配对、连接复用、闲置重建、失败重连一次 |
| **`AdbTerminalBackend.kt`** | `run_shell` 的第三级后端：交互式 shell（持久、保 cwd） |
| **`AdbDiscovery.kt`** / `AdbEndpoint.kt` | 无线调试端点自动发现（mDNS）+ 合并规则 |
| **`Spake2Ed25519.kt`** | 见下；配对全流程 |

## 复用时最容易踩的三个坑

1. **SPAKE2 的 M/N 是 8L 阶（带 8-torsion）**，不是标准 SPAKE2 的 L 阶点。所以私钥与
   口令标量都必须 ×8，否则低阶分量残留，密钥必错。
2. **名字常量含结尾 NUL**（`"adb pair client\0"`，16 字节）——AOSP 用 `sizeof()` 传长度。
3. **配对端口 ≠ 连接端口**：前者在「使用配对码配对设备」弹窗里，后者在无线调试主页
   的 `IP:端口` 那行。填反的表现是「配对码正确却解密失败」，极易误判。

harness（可独立跑，不连设备）：
`scripts/qa/run-spake2-verification.sh`（向量对拍 + 群运算 + 协议行为）、
`run-adb-protocol-verification.sh`、`run-adb-client-verification.sh`、`run-adb-pairing-verification.sh`。
