# HOTFIX11-NOTES：对话页模型快速切换（hotfix11）

## 用户反馈（真机录屏，20:03）

「保存了好多个模型的调度口，但是我要换的时候你都不给我换，我都不知道我现在用哪个模型」。
录屏实证：侧边栏「模型」→「提供商」页（OpenAI / Gemini / AiHubMix / APIMart / 蛙蛙流动 /
DeepSeek / 月之暗面 / OpenRouter）→ 进某提供商配置页，**只有保存 / 清除，没有测试连接，
没有更换模型的按键**；参照 App 的聊天页顶栏直接显示当前模型（deepseek-v3.1）。

## 根因（源码实证）

- `ChatScreen` 顶栏只有会话名 + 「新对话」，**对话页零当前模型信息、零切换入口**——
  「不知道现在用哪个模型」字面成立；
- 唯一切换途径是「抽屉 → 模型配置 → 顶部横向档案 chip 条」（ProfileSwitcher）：
  深埋三层、档案多时被横向滚动挤出屏（该类注释自己承认过）、切换后无任何可见反馈
  （静默生效）——「要换的时候不给换」字面成立；
- VM 侧 `switchProfile` 其实一直是即时生效 + 落盘的（`_config.value = target.config` +
  `saveProfiles`），坏的纯粹是 UI 入口与可发现性。

## 修复（hotfix11，仅 UI 层，VM / core 零改动）

`ui/ChatScreen.kt`：

1. **当前模型指示条**（`ModelIndicatorBar`）：PageTopBar 下方常驻——图标底座 + 配置名
   （titleMedium w600）+「模型名 · 域名」（bodySmall）+ 右侧 ExpandMore；整条 52dp 触控，
   AkPressSurface 同款按压语言；
2. **未配置引导态**：`modelReady=false` 时文案「模型未配置 · 点此前往配置模型」，
   点击走 `onOpenModel()`——与空态「去配置模型」同语义，多一个常驻入口；
3. **切换弹窗**（`ModelSwitchDialog`）：AkModalHost 承载退场 + 344dp 白卡 + scale/fade
   （对齐 RenameProfileDialog 语言）；LazyColumn 封顶 360dp 列全部档案（配置名 +
   模型名 · 域名），当前项 selected 高亮 + Check 对勾；点选 `vm.switchProfile(id)`
   即时生效 + Toast「已切换到「X」」；底部「管理模型配置」文字按钮进模型配置页
   （先关弹窗再跳页，防退场动画期间跳页闪断）；
4. `hostOf()`：Base URL → 域名（去 scheme / 去路径）纯函数，指示条与弹窗共用。

「测试连接」按钮在模型配置页本就存在（TestConnectionButton），本次不重复造；
用户说的「没有」实为深埋三层未发现——指示条 + 弹窗把「看当前模型 / 换模型」降到一步。

## 验证

- 构建：`bash gradlew :app:assembleDebug` BUILD SUCCESSFUL（腾讯镜像临时注入，
  构建后 settings.gradle.kts 已还原，`git diff` 干净）；
- harness：hotfix 17/0、hotfix3 26/0、capture-path 58/0（零回归）；
- 新增 `scripts/qa/Hotfix11Verification.kt`（G1-G12 源码扫描，13 断言）：13/0 ALL PASS；
- 真机行为待用户装机复测（源码级验证不能证明真机行为）。

## 已知边界

- 切换对「下一轮请求」生效：运行中切换不中断进行中的流式请求（loop 每轮读 _config，
  语义正确，但与「立刻换」的直觉有差异，弹窗描述已写「点选即时生效」——指配置态即时，
  进行中的那一轮不受影响）；
- 本地模型（local-llama）模式 model 为空，指示条显示「未设置模型名」，切换弹窗
  description 回落域名/「未填写」——本地模型的实际切换在「本地模型」页签，不归本弹窗。
