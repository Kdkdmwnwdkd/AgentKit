# W-67：外观从设置页拆成「壁纸与颜色」子页

## 问题

设置页第一组「外观」原本是整页最大的一块：

```
外观 ── [跟随系统][浅色][深色]
       ● ● ● ● ● (默认)
       自定义颜色 [________] 应用
       当前：#22406B（只改强调色，背景与文字不动）
       动态取色  [开关]
       背景图  换一张 / 清除
       浓度 ——●—————— 100%
```

一口气占掉一整屏，把通知、自动化、设备能力、工具入口全推到折叠线以下。用户原话：

> 这一层搞成一个小的，就比如说壁纸与颜色之类的，反正怎么叫取的话，你应该知道的，别搞得这一大块不好看啊。

## 判断

调外观是**低频动作**（设一次就不动），找设置是**每次都要干的事**。两者占同一层的面积，等于让偶尔一次的调节挤掉天天要用的入口。

所以不是「缩小这一块」，是把这一块整体挪走，设置页只留一行带状态的入口：

```
外观 ── 壁纸与颜色 / 跟随系统 · 天青
```

行里的摘要会跟着实际状态变：`跟随系统 · 天青`、`深色 · 星金 · 有背景图`、`浅色 · #B7862B · 动态取色`。

## 改动

### 新增 `ui/AppearanceScreen.kt`（约 330 行）

新子页，四组按「影响面从大到小」排：

| 组 | 内容 |
|---|---|
| 模式 | 跟随系统 / 浅色 / 深色 |
| 强调色 | 五预设圆点 + 默认 + HEX 输入框 |
| 动态取色 | Android 12+ 才有系统能力，低版本不摆开关 |
| 背景图 | 选择/换一张/清除 + 浓度滑杆 |

从 `SettingsScreen.kt` 整体迁入三个私有组件：`ThemeModeChip`、`AccentPicker`、`ChatBackgroundSection`（后者更名为 `BackgroundSection`）。

背景图区顺带改结构：原来标题挤在行里、状态写两行，现在标题放到组标题、状态进副标题，按钮和滑杆用 `paddedContent = true` 兜边距。

### `ui/SettingsScreen.kt`

- 删除外观大块，换成一行 `AkListItem("壁纸与颜色")`
- 签名收窄：去掉不再使用的 `onThemeModeChange` / `onDynamicColorChange`，只保留 `themeMode` / `dynamicColor` 用于摘要
- 清理迁移后失效的导入（`Slider`、`BorderStroke` 相关、`CircleShape`、`clip` 等）

### `ui/AppScaffold.kt`

- `AppDest` 新增 `APPEARANCE("appearance", "壁纸与颜色", …, Icons.Outlined.Palette)`
- `when (dest)` 增加分支，透传主题状态
- 设置页调用改命名参数

### QA 跟随

`scripts/qa/Hotfix11Verification.kt`：V7（动态取色门禁）、AA5（SAF 选图 + 浓度滑杆）、AB4（浓度上限 1.0）三条断言原来读 `SettingsScreen.kt`，改读 `ui/AppearanceScreen.kt`；AA5 的函数名同步为 `BackgroundSection`。
`scripts/qa/PermissionGuideVerification.kt`：长页面避让导航栏的清单加入 `AppearanceScreen.kt`。

## 顺带

设置页滚动位置由 W-66 的 `rememberPersistentScrollState()` 负责，新子页沿用同一机制——从壁纸页返回设置页，仍停在「外观」那一行。
