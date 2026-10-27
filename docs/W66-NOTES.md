# W-66：返回不再跳回顶部

## 问题

用户点开「关于与帮助」，看完退出，设置页直接弹回最上面——「每次退出可能要点起来，但每次都给我跳回这上面，我每次都得重新翻」。

不是个别页面的 bug，是两件事叠在一起：

1. `AppScaffold` 用**手写 `when (dest)` 切页**，不是 `NavHost`。离开分支 = 离开组合 = 页面状态销毁。
2. 页面滚动用的是 `rememberScrollState()`，它内部只是 `remember`，不是 `rememberSaveable`——本来就不参与状态恢复。

所以不止设置页，日志、记忆、技能、模型配置、容器、编辑器、关于……**所有长页面返回都归零**。用户只是碰巧在设置页被绊到。

## 改动

### 1. `ui/Components.kt`：可恢复的滚动状态

```kotlin
@Composable
internal fun rememberPersistentScrollState(): ScrollState =
    rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
```

### 2. `ui/AppScaffold.kt`：按路由隔离保存

```kotlin
val pageStates = rememberSaveableStateHolder()
...
pageStates.SaveableStateProvider(dest.route) {
    when (dest) { ... }
}
```

没有这层 holder 时，`rememberSaveable` 的 key 由「组合位置」派生：所有页面共用 `when` 的同一个槽位，设置页存的值会被关于页顶掉，返回时拿到的是别人的状态。holder 按 route 各存一份，互不干扰。

### 3. 19 个文件：页面主滚动换成 `rememberPersistentScrollState()`

只换页面级/面板级的垂直滚动；`horizontalScroll` 的三处（代码块横滑、日志横滑）保持原样——横向位置不是「翻到哪」的语义，恢复反而怪。

## 为什么不是只修设置页

只修设置页，用户下一次会在「运行日志」「记忆库」「模型配置」上撞到同一个问题，再报一次。根因是切页机制缺状态保存，一次修全站。

## 自查

- `rememberSaveable` 在 Dialog 内没有 registry 时会退化成不保存，不抛异常（Compose 的既定行为），所以弹窗里的滚动状态改动无风险
- 同页面内多个滚动状态靠组合位置区分，不会互相覆盖
- 本地无 Android SDK，编译与 QA 交给 CI
