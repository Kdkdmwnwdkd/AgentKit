# W-57 · `shell_run` 默认改走会话池

**日期**：2026-10-27
**前置**：W-54（把「一次性通道不保 cd」说清楚）、W-55（后端 start 失败回落）、W-56（Shizuku 成为持久会话后端）

---

## 1. 为什么改

W-54 解决了「模型被文案骗」，没解决「默认路径确实不保 cwd」。W-56 把 Shizuku 做成会话后端之后，
「uid 2000」和「持久 shell」不再是二选一——那默认就没有理由继续走一次性通道了。

改之前有**两条互相矛盾的声明**：

| 声明 | 实际行为 |
|---|---|
| 工具参数 `prefer_proot` 默认 `true`，文档写「优先容器」 | `identity` 不传时**根本不看**这个参数：直接走一次性 Shizuku `sh -c` |
| 文档写「复用同一 session 可保持 cwd / 环境变量」 | `identity` 不传时压根不返回句柄 |

也就是说：**模型最常走的默认路径，同时无视了 `prefer_proot`，还给不了句柄**。

---

## 2. 改了什么

### 2.1 路由（`ShellTool.shouldUsePrivilegedShell`）

```kotlin
// 改前
return when (identity) {
    null -> true        // ← 默认就进一次性通道
    "shizuku" -> true
    else -> false
}
// 改后
return identity == "shizuku"
```

`identity=shizuku` **仍然走一次性通道**，理由有两条：① 调用方明确表示「跑完就走、不想留会话」；
② 池的 `TerminalIdentity.Shizuku` 首期无后端（传它 `providersFor` 返回空），这条通道是拿到该语义的唯一路径。

### 2.2 `prefer_proot=false` 的候选链

```kotlin
// 改前：等价于「唯一选项」——那时还没有 shizuku / adb 后端
else -> listOf(KEY_USER)
// 改后
else -> listOf(KEY_SHIZUKU, KEY_ADB, KEY_USER)
```

传 `false` 的语义是「**别用容器**」，不是「只能用 App 身份」。
`pm` / `settings` / `appops` 在容器里永远做不了，跳过容器后仍该先试 uid 2000。

这一条是本批**对用户价值最大**的：以前模型想跑系统命令只能 `identity=shizuku`（一次性，cd 丢），
现在 `prefer_proot=false` 就能拿到 uid 2000 **且** cwd 保持。

### 2.3 文案

- `description`：删掉「需要连续操作请显式传 identity=user」（那是在教模型绕远路），
  改成「默认就进会话池 + 要系统权限传 `prefer_proot=false` + `identity=shizuku` 是例外」。
- `systemPrompt`：同样把「要系统权限」的正确姿势从 `identity=shizuku` 改成 `prefer_proot=false`。
- `identity` 参数说明：从「默认 user（首期仅 user 有后端）」改成「默认走会话池；仅 shizuku 是特殊值」。

### 2.4 顺手清掉三处过期注释

| 位置 | 原话 | 现状 |
|---|---|---|
| `TerminalBackend.KEY_SHIZUKU` | 「仍未实现为 TerminalBackend」 | W-56 已实现 |
| `TerminalIdentity.Shizuku` | 「首期只留接口不实现」 | 仍未作为**身份**实现（靠候选链拿 uid 2000），但说法要精确 |
| `TerminalIdentity.Su/Ssh` | —— | 仍是未实现，不动 |

`KEY_SHIZUKU` 那段尤其值得改：它把 W-36 的坑记成「远端代理的流语义不可用」，实际踩的是**定时 `waitFor`**。
留着会让人下次又得出「Shizuku 不能做持久 shell」的错误结论。

---

## 3. 影响面

| 场景 | 改前 | 改后 |
|---|---|---|
| `identity` 不传（最常见） | 一次性 Shizuku（uid 2000，cd 丢） | 会话池：proot → shizuku → adb → user（cd 保） |
| `identity=user` | 会话池（proot 优先） | 不变 |
| `identity=shizuku` | 一次性（uid 2000） | 不变 |
| `prefer_proot=false` | 只有本机 user（App 身份） | shizuku → adb → user（uid 2000 优先） |
| 带 `session` | 池 | 不变 |

**唯一的净损失**：`proot` 已解压 + Shizuku 就绪的机器上，`identity` 不传时从 uid 2000 变成容器内 fake root，
`pm` / `settings` 会失败（但会如实报 `permission_denied` 并提示改 `prefer_proot=false`）。
这是「尊重 `prefer_proot=true` 的既有声明」的必然代价——原来那种「声明 true、实际不生效」才是 bug。

**回退方式**：如果真机上发现问题，把 `shouldUsePrivilegedShell` 的 `null -> true` 加回去即可，
只影响默认路由，不牵连任何后端实现。

---

## 4. 验证

| 项 | 结果 |
|---|---|
| `ShellRouteVerification`（新增） | **8 / 8**：真值表（null / shizuku / 带 session / 通道不可用 / user / su / ssh / 空串 / 大小写） |
| `FallbackVerification` | **28 / 28**（新增 1 条「`prefer_proot=false` → shizuku → adb → user」） |
| `ShellStateHintVerification` | 9 / 9（未回归） |
| `T07T08Verification` | 39 / 39（未回归） |
| `:app:compileDebugKotlin` | 通过 |
| **全量 `run-all.sh`** | **PASS=53 SKIP=1 FAIL=0**（首次在本地跑满，见下） |

### 顺带：本地全量第一次真的跑满

此前本地一直是 `PASS=40~41 / FAIL=12`，那 12 个全是 `FATAL: 找不到 kotlinc（/tmp/kotlinc/bin/kotlinc）`。
查 CI workflow 才发现它自己就有一段「Prepare kotlinc shim」——用 Gradle 缓存里的
`kotlin-compiler-embeddable` 包一层同名命令，喂给只认 `$KOTLINC_HOME/bin/kotlinc` 的老 harness。

本地按同样思路补了一份（外加 `kotlinx-serialization-compiler-plugin`，w26~w30 需要），
12 个立刻全绿，且没有一个失败——**说明那些脚本此前从未在这台机器上真正执行过，
"12 个环境失败"里没有藏着真 bug，但也没有任何东西被验证**。现在本地与 CI 的覆盖面一致了。

### 需要在真机上确认的

1. **`identity` 不传时真的进了池**：日志应出现 `会话已开辟 … pool=proot`（或 `pool=shizuku`，
   取决于 rootfs 与 Shizuku 状态），返回文案里带「会话 xxxx（身份 user，池 proot）」。
2. **`prefer_proot=false` 落到 uid 2000**：`pwd` 返回句柄 → 再传 session 跑 `id`，应看到 `uid=2000(shell)`。
   若看到 `uid=10xxx(u0_a…)`，说明 shizuku / adb 都没起来、落到了本机 user。
3. **系统命令真的能跑**：`settings get system font_scale` 应返回值而不是 `permission_denied`。

---

## 5. 未做

1. **`TerminalIdentity.Shizuku` 作为身份实现**：现在拿 uid 2000 靠「identity=User + 候选链选到 shizuku 后端」。
   语义上够用，但日志里 identity 字段会显示 `user`，容易让人以为没提权。要彻底干净需要给
   `providersFor` 加「首选后端键」参数，改动面比收益大，暂不做。
2. **池路径的 `mutatesShellState` 提示**：不需要——池里 cd 真的保持，加了反而误导。
   该函数只在 `identity=shizuku` 的一次性分支生效。
3. **默认路径切到池之后，一次性通道只剩一个入口**：`identity=shizuku`。
   是否要保留它（或者干脆删掉、让池完全接管）需要真机跑一段时间后再判断。
