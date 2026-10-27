package com.example.agentkit.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
 * @param dynamicColor 功能五（P1-10）：Material You 动态取色开关（默认关）。
 *   Android 12+ 开启后用系统壁纸取色覆盖 [MaterialTheme.colorScheme]——是**覆盖**而非
 *   混排：只换 M3 标准色角色，语义色（LocalAgentKitColors 的暖纸 surface-1/2 等品牌色）
 *   不跟随，否则「暖纸」设计套的辨识度会被壁纸色稀释。与 [themeMode] 同纪律：
 *   进程内有效不落盘，重启回默认。
 * @param content 主题包裹的内容。
 */
@Composable
fun AgentKitTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    /**
     * W-64：用户自选强调色（null = 品牌默认）。
     *
     * 只覆盖 primary 族，背景/表面/语义色不动——见 [accentColorScheme] 的说明。
     * 深色主题下会先提亮 30%：`#B7862B` 这种中调色直接放到 `#17110E` 上会糊成一片。
     */
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    // 动态取色：Android 12+ 且用户开启才生效；低版本静默回退品牌色
    val base = if (dark) AgentKitDarkColorScheme else AgentKitLightColorScheme
    val colorScheme = when {
        // 动态取色优先级最高：它本来就是「交给系统决定」，用户再选色会互相打架
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        accent != null ->
            accentColorScheme(base, if (dark) accent.blendTo(Color.White, 0.30f) else accent, dark)
        else -> base
    }
    MaterialTheme(
        colorScheme = colorScheme,
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
