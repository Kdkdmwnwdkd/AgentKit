package com.beichen.polaris.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * 导航返回栈的持久化策略（3.4）。
 *
 * 不用 `rememberSaveable` 的 autoSaver：它把 `List<AppDest>` 当 Serializable 塞进 Bundle，
 * 恢复时走 `Enum.valueOf`。只要某个版本删掉一个 `AppDest`，而系统还存着含该值的旧栈
 * （后台被回收 + 跨版本升级），恢复就会抛 `IllegalArgumentException`，进程直接起不来。
 *
 * 存 route 字符串则没有这个耦合：认不出的值直接丢弃——用户最多少一级返回栈，
 * 不会白屏。这也让路由表成了唯一的持久化契约（route 本身就是它的对外表示）。
 */
internal val BackStackSaver: Saver<List<AppDest>, Any> = listSaver(
    save = { stack -> stack.map { it.route } },
    restore = { routes ->
        routes.mapNotNull { r -> AppDest.values().firstOrNull { it.route == r } }
    }
)
