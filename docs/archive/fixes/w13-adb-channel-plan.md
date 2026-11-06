# W-13 方案：自授权调试通道（App 内 adb shell）

> 目标：不装第二个 App，用户开一次「无线调试」并配对，之后 App 自己以 shell（uid 2000）
> 身份执行命令。文件清理、应用管理、改设置、截图全部在后台完成，不跳屏、不弹授权。

## 一、为什么是它

| 能力 | 无障碍（现有） | adb shell（本批） |
|---|---|---|
| 点/滑/输入 | ✅ 但目标应用必须在前台 | ✅ 同样必须前台（Android 限制，OperitAI 也一样） |
| 文件清理/移动/删除 | ❌ | ✅ 纯后台 |
| 装/停/清应用、改设置 | ❌（shell_run 只有 app uid） | ✅ 纯后台 |
| 截图 | ✅ API 30+ | ✅ `screencap`（全版本） |
| 授权成本 | 一次无障碍授权 | 一次无线调试配对 |

结论：两者不是替代关系——**调试通道补的是「不需要界面」的那类任务**。

## 二、协议栈（Android 11+ 无线调试）

```
App ──TLS(pairing)──> 手机无线调试配对端口   [SPAKE2 + 6 位码]
App ──TCP──────────> 无线调试连接端口        [CNXN / AUTH / OPEN / WRTE / OKAY / CLSE]
                     └─ shell v2 子协议      [stdout/stderr 分流 + exit code]
```

### 1. 密钥
- App 内生成 RSA-2048（`KeyPairGenerator`），存 `filesDir/adb/adbkey`。
- 公钥按 adb 格式编码：`AAAB...`（`struct RSAPublicKey` 的 base64：modulus + exponent，小端字数）。

### 2. 配对（最难的一块）
- Android 11+ 的配对走 TLS + **SPAKE2**（Curve25519），6 位码作为共享密钥。
- 实现要点：
  - 从 `_adb-tls-pairing._tcp` mDNS 发现，或让用户手输 `IP:端口` + 6 位码（先做手输，稳）；
  - SPAKE2 的 P/W 常量、HKDF、HMAC 校验、TLS-PSK 导出密钥，全部按 AOSP `adbd/pairing_auth` 实现；
  - 成功后 `adb pair` 的密钥写入系统，后续 `adb connect` 直接用 RSA AUTH 登录。
- 风险：协议细节多、厂商 ROM 可能裁剪无线调试；需要真机联调。**先做手输配对，mDNS 后补。**

### 3. 连接与 AUTH
- 连无线调试端口 → 收 `AUTH TOKEN` → 用 RSA 私钥签名（PKCS#1 v1.5）→ 发 `AUTH SIGNATURE`。
- 成功收 `CNXN`（含 `shell_v2,cmd` 特性）。
- 失败（未配对）→ 引导用户重新配对，不静默重试。

### 4. shell v2
- `OPEN("shell,v2,raw:cmd")` → `WRTE` 传命令 → 读 `WRTE` 分片：
  - stream id 1=stdout、2=stderr、3=exit（4 字节小端返回码）；
- 输出按流拼装，超限沿用 `truncateToolResult`（W-11 已与预算对齐）。

## 三、接入点

| 层 | 改动 |
|---|---|
| 新包 `automation/adb/` | `AdbKeyPair` / `AdbPacket` / `AdbCrypto` / `AdbPairing` / `AdbConnection` / `AdbShell` |
| 连接管理 | `AdbBridge`（单例）：状态机 `DISCONNECTED → PAIRING → CONNECTING → READY`，断线自动重连（前台服务不需要） |
| 能力链 | `CapabilityChain` 新增「调试通道」候选，优先级高于「本机 user shell」；`run_shell` 自动选它 |
| 工具路由 | 文件/应用/设置类工具在调试通道就绪时走 `AdbShell`；`ui_*` 仍走无障碍；`ui_screenshot` 在 API<30 时用 `screencap` |
| UI | 权限中心新增「调试通道」一项：状态 + 「去开启无线调试」引导 + 配对码输入框 |

## 四、分阶段交付

| 阶段 | 内容 | 可验证 |
|---|---|---|
| 1 | 密钥生成 + adb 公钥编码 + 包编解码（CNXN/AUTH/OPEN/WRTE/OKAY/CLSE）+ shell v2 解析 | 纯 JVM harness（无需设备） |
| 2 | 配对（手输 IP/端口 + 6 位码，SPAKE2）+ AUTH 登录 | 需真机：配对一次后 `echo ok` 成功 |
| 3 | 连接管理 + 能力链 + `run_shell` 路由 | 真机：断网重连、shell 命令返回 |
| 4 | 工具路由（文件/应用/设置）+ `screencap` 截图兜底 | 真机：后台清理文件、不跳屏 |

## 五、前提与风险

- **Android 11+**；需用户在开发者选项开「无线调试」并配对一次（Flyme 可能还要「USB 调试（安全设置）」）。
- 无线调试端口在重启/切换网络后会变：需要重新发现或让用户重输（mDNS 可缓解）。
- 部分 ROM 会杀后台 socket：连接断开即重连，命令失败返回明确错误，不假装成功。
- 不做的事：不读其他应用私有数据、不绕过锁屏、不在后台点其他应用界面（系统不允许）。
