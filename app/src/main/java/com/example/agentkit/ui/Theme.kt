package com.example.agentkit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 主题模式（设置页三选）：跟随系统 / 浅色 / 深色。
 *
 * 浅色（暖纸）为主设计套，深色为配套平行套（规范 §0）；切换只影响本进程，
 * 不落盘——AgentKit 是「配置一次，天天对话」的日用助手，主题偏好不值得一次磁盘写。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * AgentKit 主题入口（规范 §8.1 组装层）。
 *
 * @param themeMode 明暗模式；默认跟随系统，保持 AgentKitTheme(content) 旧调用形态兼容。
 * @param content 主题包裹的内容。
 */
@Composable
fun AgentKitTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) AgentKitDarkColorScheme else AgentKitLightColorScheme,
        typography = AgentKitTypography,
        shapes = AgentKitShapes
    ) {
        // 语义色随主题注入；旧静态 AgentColors 对象废止（无法响应明暗切换），引用点改 LocalAgentKitColors.current
        CompositionLocalProvider(
            LocalAgentKitColors provides if (dark) DarkAgentKitColors else LightAgentKitColors
        ) {
            content()
        }
    }
}
