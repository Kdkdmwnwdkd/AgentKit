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
