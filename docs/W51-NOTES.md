# W-51 批次记录：把无线调试接成第三条界面通道（SPAKE2 从 0 实现）

> 批次时间：2026-10-26
> 起点：`484c30d`（W-50，CI 绿：run #385）
> 触发：真机/用户反馈「无障碍和无线调试，两个必须开一个，不开就全都用不了」

---

## 一、问题：能用的通道有两条，界面操作只认其中一条半

W-50 已经把 `control_screen` 从「只有无障碍」扩成「无障碍 → Shizuku」，但用户手里
真正开着的往往是**无线调试**（开发者选项里点一下就有，不用装任何 App）。而这条通道
在代码里根本不存在：

| 事实 | 证据 |
|---|---|
| adb 协议实现完整，但**零引用** | `automation/adb/*` 6 个文件、710 行，W-38 起标注「已停用」 |
| 配对的密码学核心**只有接口没有实现** | `interface Spake2 { val message; fun process(...) }`，全仓无任何实现类 |
| 能力链只有两级 | `controlScreenSnapshot(serviceConnected, masterEnabled, shizukuAvailable)` |

所以「无线调试开着也没用」不是 bug，是**这条通道从未接线**。README 里那句
「要动这里之前，先确认需求真的无法用 Shizuku 满足」——现在正是那个需求。

## 二、这一批做了什么

### 1. `Spake2Ed25519.kt`：AOSP 版 SPAKE2（新，约 330 行）

AOSP 的 `adb pair` 用的不是 RFC 9382 的 SPAKE2，而是 boringssl 的
`crypto/curve25519/spake25519.cc`——三处细节走偏就会「配对码正确却提示解密失败」，
且无法从日志分辨错在哪：

| 细节 | 容易错成什么 |
|---|---|
| M / N 两个掩码点由固定 seed 哈希生成，**阶是 8L（带 8-torsion）** | 用标准 SPAKE2 的常量 → 掩码点不同 → 密钥必错 |
| 私钥与口令标量都**×8**（清 cofactor） | 不乘 → 低阶分量残留在 DH 里 |
| 口令标量按位补 L / 2L / 4L 使低 3 位为 0（boringssl 修历史 bug 的单边兼容） | 少了这一步 → 与 AOSP 侧掩码点不一致 |
| 名字是 `"adb pair client\0"` / `"adb pair server\0"`，**含结尾 NUL，16 字节** | 用 15 字节 → transcript 不同 → 密钥不同 |
| transcript 每段前有 **8 字节小端长度前缀**，顺序按角色排 | 漏前缀 / 顺序反 → 同上 |

实现用 `BigInteger` + 扩展扭曲爱德华坐标（add-2008-hwcd-3 / dbl-2008-hwcd），
一次配对 ~50ms，对「用户手点一次」的场景完全够用。**不做常数时间处理**是有意的：
输入是本端随机标量或对端掩码，没有可被远程测量的时序侧信道；若将来复用到批量签名
需改固定窗口 + 盲化（代码里已标注）。

### 2. `AdbCredentials.kt`：凭据与端点（新）

- RSA-2048 密钥对存 `SharedPreferences(MODE_PRIVATE)`，私钥 PKCS8 / 公钥 X509 base64；
  `commit()` 同步落盘（密钥没写进去就配对等于白做）。
- 只存 host + **连接端口**；配对端口是一次性的，不持久化。
- 提供「清端点」（换端口重配）与「彻底重置」（换设备/怀疑泄漏）两级。

### 3. `AdbSession.kt`：会话与重连（新）

- **单连接 + 串行化**：adb 连接是会话式的，反复建连在真机 100~300ms，adbd 侧还有 fd 上限。
- **闲置 60s 主动重建**：adbd 会回收空闲连接，否则「闲置后第一条命令必然失败」。
- **失败重连一次**（仅连接类错误）：超时与鉴权失败**不重试**——前者设备仍在执行
  （`input tap` 重放有副作用），后者重试无意义。
- 异常翻译成人话：`AdbAuthException` → 「设备拒绝了本机密钥，请重新配对」；
  `ConnectException` → 「无线调试每次开关都会换端口，请核对主页端口」。

### 4. `ShellChannel.kt`：shell 身份抽象（新）

```
ShellChannel { backendId, label, available(), exec(cmd, timeout) }
  ├─ ShizukuShellChannel   （binder，探测微秒级）
  └─ AdbShellChannel       （TCP，惰性建连）
ShellChannels.active()     → 按优先级取第一个可用
```

`ShellUiBackend` 从「硬编码 `ShizukuShell.exec`」改成「问 `ShellChannels.active()`」，
于是 `ui_tree` / `ui_click` / `input` 自动支持两条通道，**不需要再写第二份解析逻辑**。
返回文案会带上实际通道名（`ui_tree_ok_via_debug` 的 message 里写「已通过『无线调试（ADB）』读取…」）。

### 5. 能力链：三级

```
无障碍（实时、免 dump）
  → Shizuku（binder）
  → 无线调试（ADB，不依赖第三方 App）
```

`adbAvailable` 的判据是「**已配对**」而不是「当前连接活着」：端点在、连接可重建，
真正的失败（端口变了/配对失效）在执行时如实报错，比在探测阶段干等一次 TCP 超时更诚实。

### 6. 设置页「调试通道」分组 + 配对对话框

- 两行分别显示 Shizuku 与无线调试状态——两条通道会**独立失效**，不分开显示用户不知道该修哪条；
- 配对对话框四个字段：IP、配对端口、6 位码、连接端口，并明确写「两个端口不一样，
  填反了会提示解密失败」——这是配对失败最常见的原因；
- 配对成功后立刻 `echo` 验证一次，把问题留在这一屏而不是等用户去聊天里试。

## 三、验证

| 项 | 结果 |
|---|---|
| **SPAKE2 向量对拍** | 与一份独立实现（Python + `spake2.ed25519_basic` 群运算，同一份 spake25519.cc 流程）**逐字节一致**：alice/bob 的 `message` 与最终密钥 |
| `Spake2Verification.kt` | **15 / 15 通过**（群运算、向量、双端一致、错口令必不同、非法点拒绝、名字含 NUL） |
| `FallbackVerification.kt` | **22 / 22 通过**（新增 3 条 W-51 用例：降级到 ADB、未配对时的引导、三条都可用时仍首选无障碍） |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |

> 对拍为什么必要：自测「两端算出的密钥相等」只能证明实现自洽——两端同时错同一个
> 常量或顺序，测试依然全绿。只有跟**另一份独立实现**对上，才能证明它与 AOSP 一致。

## 四、没做的事（明确不假装完成）

1. **真机端到端配对未验**：需要一台开着无线调试的手机。SPAKE2 已与本实现外的独立
   实现对拍，但「TLS 导出密钥材料 + PeerInfo 加密 + 设备侧接受公钥」这一段只能在真机验。
   验证路径：设置 → 调试通道 → 配对，成功即 `echo` 通过；失败看 Toast 里的具体原因。
2. **`run_shell` 的 ADB 通道未接**：目前只有 `control_screen` 用上 ADB。`run_shell` 走
   `TerminalBackendFactory`，要新增 provider 并接终端流式 UI，工作量另算。
   影响面：agent 的 shell 工具在「无 Shizuku 但有无线调试」时仍不可用。
3. **mDNS 自动发现未做**：`_adb-tls-pairing._tcp` 能自动找到配对端口，省掉手输。
   当前让用户抄四个数字（其中两个容易混），是可用的下限，不是上限。
4. **配对失败的错误粒度**：`AdbPairingClient.pair()` 里 GCM 解密失败只抛「配对码错误或
   协议不匹配」。已按用户动作区分（配对码/端口填反/超时），但无法区分「端口填的是连接端口」
   与「配对码错」——两者在密码学上都表现为解密失败。

## 五、下一步建议

真机验一次配对（10 分钟）。若通过，再接 `run_shell` 的 ADB provider——那时「只开无线调试」
就能覆盖 shell + 界面全链路，Shizuku 从「必须」变成「可选加速」。
