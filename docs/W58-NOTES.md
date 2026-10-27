# W-58 · 失败指引跟上路由改动

**日期**：2026-10-27
**前置**：W-57（默认改走会话池，`prefer_proot=false` 才能拿 uid 2000）

---

## 1. W-57 制造的新缺口

W-57 把默认路径切到会话池之后，`identity` 不传会**先进 proot**。而容器里没有 Android 系统服务：

```
shell_run("pm list packages")        → 默认进 proot → Permission Denial / not found
```

此时撞上的是 W-13 写的旧 hint：

> 该命令需要比 user shell 更高的权限……**不要改用 shell 重试同一命令**——改走无障碍 UI 路径：
> ui_tree 读界面 → ui_click/ui_tap 点对应设置项。

**这条建议对 `pm` / `settings` / `appops` 是死路**：既没有等价的点击操作，模型照做也完不成任务。
更糟的是它明确说"不要用 shell 重试"，而正确解法恰恰是**换一个 shell 参数重试**。

一句话：W-57 改了路由，但失败指引还停在旧世界。

---

## 2. 分档：三种"权限不足"其实是三件事

| 当前后端 | 真实原因 | 正确下一步 |
|---|---|---|
| `proot` | 容器是 **fake root**，看不到 Android 系统服务，`pm` / `settings` 在里面根本不存在 | **`prefer_proot=false` 换 uid 2000** |
| `user` | App 身份只有 `u0_aXXX`，除自身声明的权限外什么系统权限都没有 | **`prefer_proot=false` 换 uid 2000**（若已配置调试通道） |
| `shizuku` / `adb` | 已经是 ADB 级（uid 2000），仍被拒 = **要真 root 或系统签名** | 别再重试，走 UI / 告知用户手动 |

新增纯函数 `permissionDeniedHint(poolKey)`，按当前后端分档输出。关键设计：

- **前两档必须指向 `prefer_proot=false`**，这是唯一能换成 uid 2000 且保持 cwd 的入口；
- **后两档绝不提 `prefer_proot=false`** —— 已经是 uid 2000 了，再传同一个参数只会回到同一档，
  变成死循环。harness 里有一条专门的**负向断言**钉住这点；
- 三档文案互不相同（也有断言），防止日后又被"统一简化"成一句。

`identity=shizuku`（一次性通道）的 denied 分支复用同一份函数，传 `KEY_SHIZUKU` 命中第三档。
两条路径不再各自演化——这正是 W-54 那类"两份逐行同构的代码"该避免的。

---

## 3. 顺带修掉的四处旧指引

W-57 改变了"怎么拿 uid 2000"，但全仓还有四处文案在教旧方法：

| 位置 | 原话 | 问题 |
|---|---|---|
| 一次性通道 `stateHint`（W-54 写的） | 「需要连续操作请显式传 **identity=user**」 | `identity=user` + `prefer_proot=true` 仍进容器，拿不到 uid 2000 |
| 通道不可用 hint ×2 | 「改用 **identity=user**」 | 同上 |
| `TerminalSessionPool` 的 `start_failed` | 「也可先只用 **identity=user**」 | W-57 之后**没有任何参数能表达「只要本机 shell」**——这句话已是空头承诺 |
| `identity_unsupported` | 「当前**仅支持** identity=user」 | W-56 把 Shizuku 做成后端后已半错：uid 2000 拿得到，只是入口不是 identity |

`identity_unsupported` 现在按身份分流：传 `shizuku` 进来说明是**一次性通道没起来**，
提示指向 `prefer_proot=false`；传 `su` / `ssh` 才说"首期无后端"。

`getHighestAvailable()` 的 KDoc 也从「proot → 本机 user」改成实际的四档，并注明
它**不做 start 试探**（要回落的场景走 `open()`，见 W-55）。

---

## 4. 验证

| 项 | 结果 |
|---|---|
| `ShellRouteVerification` | **14 / 14**（原 8 条路由 + 新 6 条分档指引） |
| `T07T08Verification` | 39 / 39 |
| `FallbackVerification` | 28 / 28 |
| `:app:compileDebugKotlin` | 通过 |
| 全量 `run-all.sh`（本次改动前一轮） | PASS=53 SKIP=1 FAIL=0 |

新增的 6 条里，两条是**负向**的：

- `shizuku 档不应出现 prefer_proot=false`（防死循环）
- `adb 档与 shizuku 档文案一致`（同为 uid 2000，不该给出不同建议）

---

## 5. 未做

1. **「只要本机 shell」的显式入口**：`prefer_proot=false` 现在会依次试 shizuku → adb → user，
   没有参数能说"别试调试通道，就要 App 身份"。加一个 `identity=user + prefer_proot=false` 的特例
   会破坏「identity 与后端选择解耦」的现状，而实际需求极少（多试两档只是慢几秒），故不做，
   只把误导性的文案删掉。
2. **`prefer_proot` 的命名**：它实际语义是"是否包含容器"，不是"是否**优先**容器"（候选链里
   proot 永远排第一，没有"次优先"这回事）。改名要动工具 schema，会破坏已有对话里的参数，
   留给下一批连同工具名一起收拾。
