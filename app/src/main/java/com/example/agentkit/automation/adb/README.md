# automation/adb —— **未启用的实验代码，不要当成现有能力**

> 状态：**未接入**（零引用）。保留是因为协议实现（CNXN/AUTH/OPEN/WRTE、RSA 签名、SPAKE2 配对包）
> 本身是对的，将来若真要做 App 内 adb 可以复用；但它**现在不提供任何用户可见功能**。

## 为什么停在这里

W-13 最初的设想是「App 自己连无线调试」：用户开一次无线调试、配对一次，之后 App 以 uid 2000
执行命令，不依赖 Shizuku。做完协议层后发现这条路投入产出比极差：

1. **四家参照都不做**：Operit、AiCode、Coomi 全部依赖 Shizuku，RikkaHub 根本没有 shell。
   全仓搜 `adb pair` / `SPAKE2` / `wireless` 零命中。
2. **配对协议重**：Android 11+ 走 TLS + SPAKE2（Curve25519）+ 6 位码，细节全部要对齐 AOSP，
   还要处理厂商 ROM 裁剪无线调试。
3. **Shizuku 已经把这件事做完了**：用户在 Shizuku 里点「通过无线调试启动」，Shizuku 自己
   完成配对并维持服务，App 只需要 `requestPermission` + 执行命令。

结论：**提权走 Shizuku，不要再往 ADB 配对投入**。要动这里之前，先读
`docs/w13-adb-channel-plan.md` 与 `docs/reference/参考仓库-索引.md`，确认需求真的无法用
Shizuku 满足。

## 现有文件

| 文件 | 内容 | 引用情况 |
|---|---|---|
| `AdbPacket.kt` | 消息头编解码（CNXN/AUTH/OPEN/WRTE/OKAY/CLSE） | 无 |
| `AdbCrypto.kt` | RSA-2048 生成、adb 公钥格式、TOKEN 签名 | 无 |
| `AdbClient.kt` | 连接 + AUTH + shell v2 | 无 |
| `AdbShellProtocol.kt` | shell v2 子协议（stdout/stderr/exit 分流） | 无 |
| `AdbPairingClient.kt` / `AdbPairingPacket.kt` | TLS + SPAKE2 配对 | 无 |

harness（可独立跑，仅验证协议编解码与签名，不连设备）：
`scripts/qa/AdbProtocolVerification.kt`、`AdbClientVerification.kt`、`AdbPairingVerification.kt`。

## 如果真的要用

第一步不是写代码，是**先接一个入口**（设置页「调试通道」→ 选择 Shizuku / ADB），
并把 `AdbClient` 接进 `ShellRunTool` 的 `privilegedExec` 三级降级链末端。
在那之前，这里任何类都不该被当作可用能力引用。
