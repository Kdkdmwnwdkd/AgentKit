# AgentKit 信任层收口（A 批）交付与验证报告

- 仓库：`Kdkdmwnwdkd/AgentKit`
- 合并提交：`313d5bc`（main，merge commit）
- 修复提交：`45dfd98`（fix(trust): A 批信任层收口）
- CI：run #323「Build Debug APK」✅ success
- 报告时间：2026-10-22

---

## 一、本批解决的问题

上一轮审查（10-22 版）把核心短板定在 5 处。本批（A 批）全部落地为代码，不再停留在报告层面：

| 审查结论 | 本批处置 | 状态 |
|---|---|---|
| 能力表 fail-open，未登记工具默认放行 | `SkillSecurity.cap()` 兜底改为 `DANGEROUS` | ✅ |
| 技能脚本可绕过审批代调高危工具 | `SkillExecutor` 禁止脚本代调 `needsApproval` 工具 | ✅ |
| shell / 写文件 / 推仓库 无人工确认 | 5 个高危工具挂 `needsApproval` | ✅ |
| CI 同 ref 并发，latest 被旧包覆盖 | `concurrency` 串行 + 仅 main 发布 latest + 不再先删 Release | ✅ |
| 解压先读后验，可被 zip 炸弹拖爆 | 改为边写边累计字节，超限立即拒绝 | ✅ |

附带修掉 4 处 UI/UX 一致性缺陷（引导 effect 缺 route key、引导页返回不落"已看过"、模型测试结果不随输入失效、聚光灯空洞负尺寸）。

---

## 二、改动清单（15 个文件，+213 / −38）

### 信任层（核心）

**`skill/SkillSecurity.kt`** — 能力表补齐至全部 55 个工具，并改为 fail-closed：

```kotlin
fun cap(toolName: String): Capability =
    table[toolName] ?: Capability.DANGEROUS
```

同时新增白名单判定，白名单不再代表"全放行"：

```kotlin
fun isToolAllowedByWhitelist(
    declared: List<String>,
    toolName: String
): Boolean = declared.contains("*") || toolName in declared
```

**`skill/SkillExecutor.kt`** — 空白名单不再等于全放行；且 `needsApproval == true` 的工具禁止被技能脚本代调用。这一条同时规避了 Rhino 脚本线程与 suspend 审批通道之间的死锁——原实现下脚本会静默等到超时。

**`tools/ShellTool.kt` / `ShellJobTools.kt` / `BuiltInTools.kt` / `GitHubGitTools.kt`** — 为 `shell_run`、`shell_background`、`write_file`、`edit_file`、`github_commit` 补上人工审批位。

**`tools/BuiltInTools.kt`** — canonical 路径校验补目录分隔符判断，避免同前缀目录越界（原实现下 `/data/user/0/com.x/files_evil` 可绕过 `/data/user/0/com.x/files` 的前缀检查）。

**`skill/SkillImportRepository.kt`** — ZIP 解压改为边写边累计字节数，超过上限立即拒绝。

### CI

**`.github/workflows/build.yml`** — 三处：

1. 加 `concurrency`，同一 ref 串行构建，消除"旧 run 后完成覆盖 latest"的竞态；
2. 只有 `main` 分支才发布到 `latest` Release，推修复分支不再顶掉用户手上的包；
3. 不再先删 Release，改为 create 失败后 `upload --clobber`——原先"先删后建"存在发布窗口空档。

### UI 一致性

- `ui/AppScaffold.kt` — 自动权限引导的 `LaunchedEffect` 补 `route` 依赖，避免导航返回后引导不重跑。
- `ui/PermissionGuideScreen.kt`、`core/capability/PermissionGuide.kt` — 系统返回键也写入"已看过"标记；修正电池白名单的过度承诺文案。
- `ui/ChatViewModel.kt`、`ui/ModelConfigScreen.kt` — 新增 `clearTestResult()`，Base URL / Key / 模型 / Provider 任一变化即清除旧测试结果，避免"改了配置却显示旧的成功"。
- `ui/SpotlightGuide.kt` — 聚光灯 hole 四边统一 `coerceIn`，负尺寸时退化为仅压暗全屏而非崩溃。

### 测试

**`scripts/qa/W47W48Verification.kt`** — 新增 S6–S8 三条断言：能力表 fail-closed 且全仓工具 100% 登记、白名单 fail-closed、高危工具必须挂人工审批。该 harness 现为 **18 通过 / 0 失败**。

---

## 三、CI 与反验结果

### CI

| 项 | 结果 |
|---|---|
| Build Debug APK（run #323, sha `313d5bc`） | ✅ success |
| pages build and deployment（run #13） | ✅ success |
| 产出构件 | `app-debug` 34.74 MB、`qa-logs` 63.5 KB |

### QA harness 全量

下载 `qa-logs` 构件，48 个日志文件逐项统计：

- **PASS 总计 1282 条，FAIL 0 条**（各日志末尾 `FAIL=0`）
- `run-w47w48-verification.log` → 18 通过 / 0 失败，S6–S8 三条新断言全绿
- `run-apk-verification.log` → "反验汇总: 全部通过"

### APK 反验（1.323）

从 Actions artifact 取包反验（Release 直链与 API 直连在本沙箱被拦，改走 gh-proxy 镜像通道，见附录）：

| 项 | 值 |
|---|---|
| `versionName` | **1.323**（run_number 323，与 CI 一致） |
| `versionCode` | 323 |
| 包体积 | 45,980,011 B（约 43.9 MiB / 45.98 MB） |
| 声明权限 | 32 项 |
| v2 签名证书 SHA1 | `bdd2290debdb4b3c2b2a33b34d5154988ee73fe1` |
| 仓库 keystore 证书 SHA1 | `BD:D2:29:0D:EB:DB:4B:3C:2B:2A:33:B3:4D:51:54:98:8E:E7:3F:E1`（一致 ✅） |
| 新代码入包验证 | `isToolAllowedByWhitelist` 在 `classes15.dex` 中命中 ✅ |

签名指纹与仓库内 `app/debug.keystore` 完全一致，说明本地与 CI 产物同钥匙，可互相覆盖安装。

### 声明权限清单（32 项）

```
ACCESS_NETWORK_STATE          BIND_ACCESSIBILITY_SERVICE   BIND_JOB_SERVICE
BIND_NOTIFICATION_LISTENER_SERVICE   CAMERA              DUMP
FOREGROUND_SERVICE            FOREGROUND_SERVICE_DATA_SYNC FOREGROUND_SERVICE_MICROPHONE
FOREGROUND_SERVICE_SHORT_SERVICE      FOREGROUND_SERVICE_SPECIAL_USE
INTERACT_ACROSS_USERS_FULL   INTERNET                    KILL_BACKGROUND_PROCESSES
MANAGE_EXTERNAL_STORAGE      PACKAGE_USAGE_STATS         POST_NOTIFICATIONS
QUERY_ALL_PACKAGES            READ_EXTERNAL_STORAGE       READ_MEDIA_AUDIO
READ_MEDIA_IMAGES             READ_MEDIA_VIDEO            RECEIVE_BOOT_COMPLETED
RECEIVE_SMS                   RECORD_AUDIO                REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
REQUEST_INSTALL_PACKAGES      SCHEDULE_EXACT_ALARM        SYSTEM_ALERT_WINDOW
WAKE_LOCK                     WRITE_EXTERNAL_STORAGE      WRITE_SETTINGS
```

---

## 四、仍待处理（下一批 B 批）

本批只解决"信任层 fail-closed"。以下是审查中确认、但本批未动的结构性短板：

1. **长任务无前台服务 / 无进程恢复** — Agent 长任务被系统杀掉后无法续跑，这是对标 Operit / Coomi 的关键差距。
2. **Agent 写路径未接自动检查点** — 文件改动没有自动 checkpoint/回滚点。
3. **容器缺一键安装 node / python / java** — 容器化开发环境开箱即用度不足。
4. **设备操控授权分级** — 对标 Coomi 的信任等级模型，当前仅"全要 / 全不要"两档。

---

## 附录 · 沙箱 GitHub 通道实测

本轮验证了资料库《沙箱绕过网络连接 GitHub-通道说明》的结论，并补充两点：

| 通道 | 本轮实测 |
|---|---|
| `api.github.com` | ✅ 可读（Actions 运行记录、artifact 列表、release 元数据） |
| `github.com` git 协议 | ❌ 连接超时（`Failed to connect to github.com port 443`） |
| Release 直链下载 46 MB | ❌ 多次超时失败 |
| `gh-proxy.com` 镜像 artifact zip | ✅ 34.74 MB 秒下，无 Authorization 头 |
| `api.githubcopilot.com/mcp/` 写通道 | 可用（PAT 需 `repo` 权限） |

补充：文档提到 WebFetch 有 15 分钟缓存，复核 CI 状态时需加时间戳参数绕过——本轮用 API 直接取 `run_number`/`status`/`conclusion`，未依赖缓存。

---

## 附录 · 安全提醒

用户在会话中明文提供了 GitHub PAT。该令牌已用于本次仓库操作，**建议立即在 GitHub 设置中撤销并重新生成**（新令牌需勾选 `repo` 权限；如需触发 workflow_dispatch 还需 `workflow` 权限）。本次报告中未记录令牌明文。
