package com.example.agentkit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 视觉规范（一次性沉淀，全 App 复用）：品牌色、排版、圆角。
 *
 * 设计取向：稳重的蓝紫主色 + 大面积留白 + 统一圆角，**不使用花哨渐变**，
 * 保证在 OLED 与 LCD 屏上都读得清、长时间看不累。
 */

// —— 品牌色：蓝紫（Indigo 系）——
private val BrandPrimary = Color(0xFF4F46E5)
private val BrandOnPrimary = Color(0xFFFFFFFF)
private val BrandPrimaryContainer = Color(0xFFE4E2FF)
private val BrandOnPrimaryContainer = Color(0xFF1B1A57)
private val BrandSecondary = Color(0xFF5B57D1)
private val BrandSecondaryContainer = Color(0xFFE7E5FF)
private val BrandOnSecondaryContainer = Color(0xFF1A1860)
private val BrandTertiary = Color(0xFF7B5EA7)
private val BrandTertiaryContainer = Color(0xFFF0E4FF)
private val BrandOnTertiaryContainer = Color(0xFF2C1442)

// 中性色（略偏冷灰，配合蓝紫主色）
private val NeutralSurface = Color(0xFFFAFAFC)
private val NeutralSurfaceVariant = Color(0xFFE9E9F2)
private val NeutralOnSurface = Color(0xFF1A1B25)
private val NeutralOnSurfaceVariant = Color(0xFF4A4C5E)
private val NeutralOutline = Color(0xFFC6C6D6)

private val SemanticError = Color(0xFFB3261E)
private val SemanticOnErrorContainer = Color(0xFF8C1009)
private val SemanticErrorContainer = Color(0xFFFCE4E4)
private val SemanticWarning = Color(0xFFB26A00)
private val SemanticSuccess = Color(0xFF2E7D54)

private val DarkSurface = Color(0xFF12131B)
private val DarkSurfaceVariant = Color(0xFF272839)
private val DarkPrimaryContainer = Color(0xFF37346E)

private val AgentKitLightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    onSecondary = BrandOnPrimary,
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,
    tertiary = BrandTertiary,
    onTertiary = BrandOnPrimary,
    tertiaryContainer = BrandTertiaryContainer,
    onTertiaryContainer = BrandOnTertiaryContainer,
    background = NeutralSurface,
    onBackground = NeutralOnSurface,
    surface = NeutralSurface,
    onSurface = NeutralOnSurface,
    surfaceVariant = NeutralSurfaceVariant,
    onSurfaceVariant = NeutralOnSurfaceVariant,
    outline = NeutralOutline,
    error = SemanticError,
    onErrorContainer = SemanticOnErrorContainer,
    errorContainer = SemanticErrorContainer
)

private val AgentKitDarkColors = darkColorScheme(
    primary = Color(0xFFB7B4FF),
    onPrimary = Color(0xFF221F5C),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color(0xFFE4E2FF),
    secondary = Color(0xFFC2BFFF),
    onSecondary = Color(0xFF2A2768),
    secondaryContainer = Color(0xFF3A3770),
    onSecondaryContainer = Color(0xFFE7E5FF),
    tertiary = Color(0xFFDCBEFF),
    onTertiary = Color(0xFF3C1F5C),
    tertiaryContainer = Color(0xFF4A2B69),
    onTertiaryContainer = Color(0xFFF0E4FF),
    background = DarkSurface,
    onBackground = Color(0xFFE6E6F0),
    surface = DarkSurface,
    onSurface = Color(0xFFE6E6F0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC6C6D6),
    outline = Color(0xFF8E8E9E),
    error = Color(0xFFFFB4AB),
    onErrorContainer = Color(0xFFFFDAD6),
    errorContainer = Color(0xFF93000A)
)

/** 排版：明确的层级（标题偏紧、正文行高宽松），正文字号不低于 14sp。 */
private val AgentKitTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

/** 圆角：小 8 / 中 12 / 大 16，卡片与大圆角构件统一走 large。 */
private val AgentKitShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

/**
 * AgentKit 主题入口：Material3 + 自定义品牌色 / 排版 / 圆角，明暗跟随系统。
 *
 * @param content 主题包裹的内容。
 */
@Composable
fun AgentKitTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) AgentKitDarkColors else AgentKitLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgentKitTypography,
        shapes = AgentKitShapes,
        content = content
    )
}

/**
 * 语义色：成功/警告/错误，用于状态徽章与工具卡片，避免各处硬编码同一或近似颜色。
 * 取 plain value 以保证深浅色主题下都有足够对比度。
 */
internal object AgentColors {
    val success: Color = SemanticSuccess
    val warning: Color = SemanticWarning
    val error: Color = SemanticError
}
