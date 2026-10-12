# 07 · 构建交付链路：把自有 CI 产品化

> 对应九步顺序的第 9 步。目标：App 内一个「构建」按钮——推代码到 GitHub → CI 编译 → 拉回 APK → 直接安装。**这是 AgentKit 对全部竞品的独有优势**：Coomi/RikkaHub/Operit/AiCode 都没有把「手机改代码 → 出安装包」做成闭环。

## 参考项目

| 项目 | 参考什么 |
|---|---|
| 本仓库现有 CI（`.github/workflows/`） | 已验证的构建链路（versionName 用 `GITHUB_RUN_NUMBER` 递增，见下） |
| jieapi/AiCode | 内置构建脚本调 gradle 的尝试（本地构建路线，反面教材） |
| termux packages | 移动端交叉编译的复杂度参照（说明为什么本地构建不现实） |

## 它怎么做的 / 现状盘点

### 已有资产（本仓库 CI 的现状）

```
push → GitHub Actions（ubuntu-latest）
     → ./gradlew assembleDebug
     → 上传 artifact + 发布到 release（latest tag 覆盖）
     → versionName = GITHUB_RUN_NUMBER → versionCode = RUN_NUMBER
```

这条链路已经稳定跑了十几轮：**L 系列 12 个里程碑版本 + v3.1 UI 版全部由此产出**，APK 同签名可覆盖安装。也就是说「构建」按钮缺的不是 CI，而是**把 CI 的触发与结果回流传进 App 的产品化封装**。

### AiCode 的本地构建路线为何不走

它尝试在设备上直接跑 gradle——实测：完整 Android 构建在旗舰机上 20-60 分钟且频繁 OOM（Gradle daemon + kotlinc 的内存需求是手机扛不住的）。**结论：云端 CI 是唯一现实路线**，也正好是 AgentKit 已经跑通的。

## 我们的实现

### 流程设计（App 内的「构建」按钮）

```
用户在编辑器改代码
   ↓ 点「构建」
① 本地 git add/commit（JGit，04 号文档的 GitRepository）
   ↓
② push 到 GitHub（PAT，HTTPS）
   ↓
③ 触发 workflow_dispatch（或 push 自动触发）
   ↓
④ 轮询 run 状态（queued → in_progress → completed）
   ↓
⑤ 成功：下载 artifact / release APK（OkHttp，支持 Range 续传）
   ↓
⑥ 校验 SHA-256 + 签名一致性
   ↓
⑦ 系统安装器拉起安装（FileProvider + REQUEST_INSTALL_PACKAGES）
```

### 模块位置

```
build/
├── BuildConfig.kt           // GitHub 配置：owner/repo/PAT/workflow 文件名
├── GitHubBuildService.kt    // ②-⑥ 全链路（GitHub REST API v3）
├── BuildTask.kt             // 构建任务状态机（复用 06 的任务模型思路）
└── ui/BuildScreen.kt        // 抽屉新目的地「构建」：历史构建列表 + 进度 + 安装
```

### GitHub REST API 调用点（OkHttp，与 LLM 层同款 HTTP 栈）

| 步骤 | API |
|---|---|
| 创建/推送 commit | `POST /repos/{o}/{r}/git/refs` 或 JGit push（04 已有） |
| 触发 workflow | `POST /repos/{o}/{r}/actions/workflows/{file}/dispatches` |
| 查 run 状态 | `GET /repos/{o}/{r}/actions/runs?event=workflow_dispatch` |
| 下 artifact | `GET /repos/{o}/{r}/actions/artifacts/{id}/zip` |
| 下 release | `GET /repos/{o}/{r}/releases/latest` → `assets[].url`（`Accept: application/octet-stream`） |

**PAT 权限**（fine-grained，最小化）：`Contents: R&W` + `Actions: R&W` + `Metadata: R`。存 ConfigStore（与 04 的 Git push、05 的 Key 同库）。**首次使用弹引导**：贴 PAT → 存 → 测连通（`GET /user`）。

### 版本与签名

- 版本号继续吃 `GITHUB_RUN_NUMBER`（现有 CI 已实现）——App 内显示「构建 #N」与 CI run number 一一对应，**出问题能精确定位是哪次提交**
- 签名：现阶段 debug keystore 由 CI 自动生成，同机覆盖安装没问题；**将来上 release 签名**：keystore 存 GitHub Secrets，App 只回传 APK——**私钥永远不进手机**（这是架构上的正确边界，写进设计文档防后人"图方便"）

### UI：构建页（v3.1 暖纸规范）

- 顶部：当前仓库/分支/未提交改动数（JGit status）+「构建」主按钮（有未提交改动时按钮文案变「提交并构建」）
- 构建历史列表：每次一行——`#56 · 成功 · 1.56 · 12分钟前 · [安装]`；失败行红点 + 「查看日志」跳 LogsScreen
- 进行中卡片：进度阶段文字（排队中 → 编译中 → 打包中 → 下载中）+ 已完成百分比（轮询 run 的 `run_attempt` 与 artifact 下载进度合成）
- 「安装」：下载完本地 APK，点安装走系统 Intent；**已安装同签名时提示覆盖升级**

### 与现有资产的衔接

- `AgentLogger`：构建全链路记日志（脱敏 PAT），失败时 LogsScreen 直接看 HTTP 状态码
- 通知通道：构建完成推送通知（复用 06 的任务完成通知模式）
- 工作区：构建的 git 操作锚定 `filesDir/workspace/`（04 号文档的工作区约定）

## 注意事项

1. **轮询频率**：run 状态 30s 一次、artifact 下载走 OkHttp 断点续传。**CI 队列可能排几分钟**，文案要说清"云端排队中"而非卡死感。
2. **PAT 泄露面**：ConfigStore 是明文 JSON（现状）。自用可接受；**开源前必须迁移加密存储**（09 号文档合规清单里记一笔）。日志脱敏走 `AgentLogger.sanitize`（现有 401 脱敏机制同款）。
3. **私有仓库**：PAT 访问私有仓库 OK，但**开源时别把 PAT 提交进仓库**——`.gitignore` 已有机制，构建配置单独存 ConfigStore 不进 git。
4. **artifact 过期**：GitHub artifact 默认 90 天过期，release 不过期。下载策略：**优先 release/latest**，artifact 仅当 release 未更新时用。
5. **APK 大小**：现在 17MB，加了 llama.cpp .so（02）后会到 ~50MB+，容器 rootfs 走运行时下载不进 APK。CI 下载给 20 次重试的续传（这个坑在 L 系列踩过：代理对大文件断流）。
6. **本步放最后的原因**：它依赖 04 的 Git 集成（commit/push）与全部前置能力（构建出来的东西才有意义）。**但它本身不依赖任何新引擎**，纯 HTTP 协议封装，是九步里实现量最小的一步——放在最后是产品逻辑，不是技术依赖。
