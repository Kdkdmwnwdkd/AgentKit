# W-24 批次记录：OCR + 软件安装/卸载

> 依据：缺口清单 #69（OCR）、#65（软件管理），均为 P2 但手机场景高频。
> 承接 W-23「把差距补上」的路线。

## 一、OCR（#69）

### 为什么值得做
截图只能让**视觉模型**「看见」。本地小模型、或用户没挂视觉模型时，截图等于一张废图。
OCR 把画面变成文字直接喂给任何模型：验证码、聊天记录、票据、菜单、报错弹窗。

### 实现
| 项 | 选择 | 理由 |
|---|---|---|
| 引擎 | **ML Kit bundled 中文识别**（`text-recognition-chinese:16.0.1`） | 模型打进 APK，**不依赖 GMS**；国产 ROM 无 Google Play 也能用 |
| 来源 | 不传 path → 无障碍截当前屏；传 path → 读工作区图片 | 复用 W-12 唯一截图通道，不引入新权限 |
| 上传统 | 无（完全离线） | 图片不出设备 |
| 输出 | 纯文本（按行），≤6000 字符可调 | 任何模型都能读，不消耗视觉 token |

代价：APK 体积增加（中文模型约 20MB）。这是"离线 + 无 GMS"的必要成本。

### 工具契约
`ocr_image(path?, max_chars?)` → `ocr_ok` / `ocr_empty`（画面无文字）/ `ocr_failed`。
只读，可并行。

## 二、软件安装/卸载（#65）

### app_install
- 从工作区读 APK（限 .apk、拒 `..` 穿越），经 FileProvider 交给系统安装器；
- **不是静默安装**：Shizuku 的 `pm install` 读不到应用私有目录（UID 不同），
  要先复制到 `/data/local/tmp` 并清理——本批先做零特殊权限的 Intent 安装；
- 结果如实返回 `installer_opened`「已打开系统安装器，等待用户点安装」，不假装已装好。

配套：`file_paths.xml` 新增 `agent_workspace` 路径（工作区 APK 直接分享，不复制）。

### app_uninstall
- 优先 Shizuku `pm uninstall --user 0 <pkg>`（静默）；
- 通道不可用 → 系统卸载确认框（用户点确定）；
- **包名白名单 + 禁止卸载自己**。

### 关键坑：`pm uninstall` 的 exitCode 不可靠
系统应用卸载失败时 **exitCode 可能仍是 0**，只输出 `Failure [DELETE_FAILED_INTERNAL_ERROR]`。
所以判定必须看 stdout/stderr 关键词（[AppManageLogic.interpretUninstall]）：
- `Success` → 成功（即使 exit!=0）
- `DELETE_FAILED_INTERNAL_ERROR` → 系统应用/拒绝
- `DELETE_FAILED_DEVICE_POLICY_MANAGER` → 设备管理员，需先取消
- `not installed` → 未安装
- 输出为空且 exit=0 → **结果不明**（不冒充成功）

## 三、验证

- app_manage harness **9/9**（包名 2 + 路径 2 + 卸载判定 5）。
- 既有 harness 无回归（edit/websearch 27、keypool 25、load_skill 14）。
- CI：见当批 run。
- APK 反验：W24 组 11 条池条目 + 4 条文案。

## 四、已知边界

- `app_install` 需要用户点一次「安装」（系统安装器界面）；静默安装留待后续（需 tmp 目录流转）。
- `app_uninstall` 静默路径依赖 Shizuku；没有调试通道时会弹系统确认框。
- 部分 ROM 把 `pm uninstall --user 0` 对预装应用封死，只能停用（`pm disable-user`），
  本批未做停用——需要的话下批补 `app_disable`。
