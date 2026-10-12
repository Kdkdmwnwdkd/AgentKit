package com.example.agentkit.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 暖纸 Warm Paper 色板（视觉规范 v3.1 §3.2 / §3.3）。
 *
 * 设计取向：暖粉底 + 白卡 + 暖灰内嵌的三层明度分层，全 App 0 阴影 0 描边；
 * 单一主色「青碧」与暖粉底近似互补；语义色严格四槽（danger / warning / success / trust-sandbox）。
 * 本文件是全 App 唯一允许出现硬编码 Color(0x..) 的地方，改色值必须先回规范 §3.4 复测对比度。
 */

// —— Light（主设计套）§3.2 ——
private val LightBg = Color(0xFFF8EFE9)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurface1 = Color(0xFFF4E9E2)
private val LightSurface2 = Color(0xFFEFE2D9)
private val LightSurfaceVar = Color(0xFFF6EDE7)
private val LightOnSurface = Color(0xFF2E2620)
private val LightOnSurfaceVar = Color(0xFF6B5B4E)
private val LightOnSurfaceTer = Color(0xFF756450)
private val LightOutline = Color(0xFFE5D6CC)
private val LightOutlineStrong = Color(0xFF8A7563)
private val LightPrimary = Color(0xFF0E5D6B)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD2E7E9)
private val LightOnPrimaryContainer = Color(0xFF093F49)
private val LightDanger = Color(0xFFB4322A)
private val LightOnDanger = Color(0xFFFFFFFF)
private val LightDangerContainer = Color(0xFFF9E2DF)
private val LightOnDangerContainer = Color(0xFF7E1F16)
private val LightWarning = Color(0xFF8A5A12)
private val LightWarningContainer = Color(0xFFF7E8D2)
private val LightOnWarningContainer = Color(0xFF5C3A05)
private val LightSuccess = Color(0xFF177245)
private val LightSuccessContainer = Color(0xFFDCF0E3)
private val LightOnSuccessContainer = Color(0xFF0F4A2A)
private val LightTrustSandbox = Color(0xFF6B5B4E)
private val LightLogDebug = Color(0xFF756450)
private val LightLogInfo = Color(0xFF0E5D6B)
private val LightInverseSurface = Color(0xFF2E2620)
private val LightInverseOnSurface = Color(0xFFF8EFE9)
/** 弹窗遮罩：暖黑 #1A120D @ 40%（不用纯黑）。 */
private val LightScrim = Color(0x661A120D)

// —— Dark（配套平行套，暖黑非冷黑）§3.3 ——
private val DarkBg = Color(0xFF17110E)
private val DarkSurface = Color(0xFF1F1815)
private val DarkSurface1 = Color(0xFF241C18)
private val DarkSurface2 = Color(0xFF2A211C)
private val DarkSurfaceVar = Color(0xFF372C25)
private val DarkOnSurface = Color(0xFFF2EAE3)
private val DarkOnSurfaceVar = Color(0xFFBBA99B)
private val DarkOnSurfaceTer = Color(0xFFA89A89)
private val DarkOutline = Color(0xFF3E332C)
private val DarkOutlineStrong = Color(0xFF9C8672)
private val DarkPrimary = Color(0xFF7FC8CE)
private val DarkOnPrimary = Color(0xFF05292E)
private val DarkPrimaryContainer = Color(0xFF14494F)
private val DarkOnPrimaryContainer = Color(0xFFB9E6EA)
private val DarkDanger = Color(0xFFFF8D82)
/** Dark 专用「允许执行」按钮填充：亮珊瑚只做文字/图标，不做大填充（§3.4 注 1）。 */
private val DarkDangerFill = Color(0xFFC2483A)
private val DarkOnDangerFill = Color(0xFFFFFFFF)
private val DarkDangerContainer = Color(0xFF3D1A14)
private val DarkOnDangerContainer = Color(0xFFFFC9C0)
private val DarkWarning = Color(0xFFF2B950)
private val DarkWarningContainer = Color(0xFF3A2A12)
private val DarkOnWarningContainer = Color(0xFFF5D9A0)
private val DarkSuccess = Color(0xFF62D996)
private val DarkSuccessContainer = Color(0xFF123828)
private val DarkOnSuccessContainer = Color(0xFFBFF0D4)
private val DarkTrustSandbox = Color(0xFFA8988A)
private val DarkLogDebug = Color(0xFFA89A89)
private val DarkLogInfo = Color(0xFF7FC8CE)
private val DarkInverseSurface = Color(0xFFF2EAE3)
private val DarkInverseOnSurface = Color(0xFF17110E)
/** 弹窗遮罩：纯黑 @ 56%。 */
private val DarkScrim = Color(0x8F000000)

/**
 * Material3 ColorScheme 映射（规范 §8.1）。
 *
 * secondary / tertiary 无规范独立色值，直接复用 primary 同族（暖纸全 App 同时出现色相 ≤5 族，
 * 不引入规范外色相）；surfaceContainer* 承载 surface-1 / surface-2 阶层。
 */
internal val AgentKitLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightPrimaryContainer,
    onSecondaryContainer = LightOnPrimaryContainer,
    tertiary = LightPrimary,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightPrimaryContainer,
    onTertiaryContainer = LightOnPrimaryContainer,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = LightOnSurfaceVar,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface1,
    surfaceContainer = LightSurface1,
    surfaceContainerHigh = LightSurface2,
    surfaceContainerHighest = LightSurface2,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    outline = LightOutline,
    outlineVariant = LightOutline,
    scrim = LightScrim,
    error = LightDanger,
    onError = LightOnDanger,
    errorContainer = LightDangerContainer,
    onErrorContainer = LightOnDangerContainer
)

internal val AgentKitDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
    tertiary = DarkPrimary,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkPrimaryContainer,
    onTertiaryContainer = DarkOnPrimaryContainer,
    background = DarkBg,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVar,
    onSurfaceVariant = DarkOnSurfaceVar,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurface1,
    surfaceContainer = DarkSurface2,
    surfaceContainerHigh = DarkSurfaceVar,
    surfaceContainerHighest = DarkSurfaceVar,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    scrim = DarkScrim,
    error = DarkDanger,
    onError = DarkOnDangerFill,
    errorContainer = DarkDangerContainer,
    onErrorContainer = DarkOnDangerContainer
)

/**
 * 规范 §3.8 中 ColorScheme 承载不下的语义 token（surface-1/2 阶层、强交互边界、
 * 语义四槽、日志级别、信任等级、scrim）。淡 tint 徽章的底由语义色 12% alpha 现场合成
 * （§3.4 注 2），文字一律取对应 on-*-container 深色墨迹（§3.5）。
 */
internal data class AgentKitColors(
    /** 内嵌块 / 图标底座 / chip 底 / 抽屉底（Light #F4E9E2 / Dark #241C18）。 */
    val surface1: Color,
    /** 按压态 / 更深内嵌（Light #EFE2D9 / Dark #2A211C）。 */
    val surface2: Color,
    /** 交互边界强轨 ≥3:1（Light #8A7563 / Dark #9C8672）。 */
    val outlineStrong: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** 「允许执行」按钮填充：Light 复用 danger，Dark 用专用暗红（§3.4 注 1）。 */
    val dangerFill: Color,
    val onDangerFill: Color,
    /** 沙箱信任等级（中性暖灰，不是语义色）。 */
    val trustSandbox: Color,
    val logDebug: Color,
    val logInfo: Color,
    val logWarn: Color,
    val logError: Color,
    /** 抽屉 / 弹窗遮罩（Light #1A120D@40% / Dark #000@56%）。 */
    val scrim: Color
)

internal val LightAgentKitColors = AgentKitColors(
    surface1 = LightSurface1,
    surface2 = LightSurface2,
    outlineStrong = LightOutlineStrong,
    success = LightSuccess,
    successContainer = LightSuccessContainer,
    onSuccessContainer = LightOnSuccessContainer,
    warning = LightWarning,
    warningContainer = LightWarningContainer,
    onWarningContainer = LightOnWarningContainer,
    dangerFill = LightDanger,
    onDangerFill = LightOnDanger,
    trustSandbox = LightTrustSandbox,
    logDebug = LightLogDebug,
    logInfo = LightLogInfo,
    logWarn = LightWarning,
    logError = LightDanger,
    scrim = LightScrim
)

internal val DarkAgentKitColors = AgentKitColors(
    surface1 = DarkSurface1,
    surface2 = DarkSurface2,
    outlineStrong = DarkOutlineStrong,
    success = DarkSuccess,
    successContainer = DarkSuccessContainer,
    onSuccessContainer = DarkOnSuccessContainer,
    warning = DarkWarning,
    warningContainer = DarkWarningContainer,
    onWarningContainer = DarkOnWarningContainer,
    dangerFill = DarkDangerFill,
    onDangerFill = DarkOnDangerFill,
    trustSandbox = DarkTrustSandbox,
    logDebug = DarkLogDebug,
    logInfo = DarkLogInfo,
    logWarn = DarkWarning,
    logError = DarkDanger,
    scrim = DarkScrim
)

/** 语义色 CompositionLocal：随明暗主题切换，替代旧静态 AgentColors 对象（规范 §8.1）。 */
internal val LocalAgentKitColors = staticCompositionLocalOf { LightAgentKitColors }
