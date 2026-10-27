# W-65：气泡降重 + 关于与权限合并

## 背景

W-64 把配色和图标定下来之后，用户看完预览提了两条：

1. 「自己的气泡颜色淡一点嘛，可以淡一点，或者说渐变色，或者玻璃那种的，透一点，但是要能看出是气泡」
2. 「那个关于的就是介绍整个商品的，和那个教你怎么搞权限的，合成一行放在最下面」

第一条是 W-64 留下的问题：用户气泡用的是 `primary` 实色块 + `onPrimary` 白字，一屏七八条自己的话就是七八块深色补丁，比 AI 的长段回复还抢眼。第二条是信息架构问题：「关于」在「数据与关于」组、「权限引导」在「帮助」组，两行说的其实是同一件事——有问题去哪看。

## 改动

### 1. 我方气泡：淡主题色渐变 + 玻璃描边

`ui/Color.kt` 新增 `UserBubbleColors` 与 `userBubbleColors(scheme)`：

| 槽位 | 浅色 | 深色 | 作用 |
|---|---|---|---|
| `start` | `surface` 掺 9% primary | 掺 13% | 渐变左下，几乎融进底色 |
| `end` | 掺 28% primary | 掺 34% | 渐变右上，透出一点色相 |
| `text` | `onSurface` 掺 30% primary | `onSurface` 原值 | 偏主题色的深字，不是纯黑 |
| `stroke` | 掺 32% primary | 掺 46% | 0.5dp 玻璃边缘 |

为什么用 `surface` 当底而不是 `background`：浅色下聊天页底色比卡片灰，用 surface 才亮得出来；深色同理，气泡要浮在页面底之上。

`ui/ChatScreen.kt` 的 `MessageBubble`：用户侧 `Surface` 改透明底，渐变与描边画在 Surface 之前（`Modifier.background(Brush.linearGradient(...), shape).border(0.5.dp, stroke, shape)`）。渐变本身构成边界，所以哪怕只掺 9% 也看得出这是个气泡。

没有用 `RenderEffect` 做真模糊：气泡背后是纯色页面，模糊没有可模糊的东西，只会多一次离屏渲染。真玻璃留到有背景图/滚动内容透出的地方（顶栏、输入栏）再做。

### 2. 关于与帮助合并成一行，钉在最底部

`ui/SettingsScreen.kt`：

- 「数据与关于」组改名「数据」，只留技能 / 日志 / 记忆
- 「帮助」组只留「重看上手引导」，删掉单占一行的「权限引导」
- 末尾新增一组「关于」，一行「关于与帮助 / 产品介绍 · 权限怎么给 · 版本与开源许可」

`ui/AboutScreen.kt`：加 `onOpenPermissionGuide` 参数（默认空，保持旧调用兼容），在应用标识卡后插入「帮助」组，一行「权限怎么给」进入 `PERMISSION_GUIDE`。这样点一次看到两件事：先知道这是什么，再知道要给它什么权限。

`ui/AppScaffold.kt`：`AboutScreen` 调用点传入导航。

## 自查

- 未使用导入：`outlined.Security` 从 SettingsScreen 删除
- QA 无颜色/文案断言依赖这两处（已 grep `scripts/qa`）
- 本地无 Android SDK，编译验证交给 CI（`.github/workflows` 上 build → QA → 发布）
