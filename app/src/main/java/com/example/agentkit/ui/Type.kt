package com.example.agentkit.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版（视觉规范 v3.1 §4.2）：9 个 M3 slot + 2 个 mono 工具样式。
 *
 * 人类语言统一 Noto Sans SC——工程落地按规范 §4.1 回退方案用系统 FontFamily.SansSerif
 * （不引入 ui-text-google-fonts 依赖，无 Play 服务设备自动回退，拉丁字形与 Roboto 同源不跳字）。
 * 机器文本（时间戳 / Key / 工具名 / URL / 入参 / STEP）走 JetBrains Mono 的回退 FontFamily.Monospace，
 * 等宽特性服务数字列对齐；这是工具用途，不再是 v2 的英文微标语汇。
 *
 * 行高一律绝对值 sp（中文正文 ≥1.7：bodyLarge 15/26），includeFontPadding = false 显式声明防回归；
 * fontScale >1.3 的行高 ×1.4 例外由各容器 heightIn(min) 兜底（UX v1 §5.2），不在样式层写死。
 */

private val noFontPadding = PlatformTextStyle(includeFontPadding = false)

internal val AgentKitTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        platformStyle = noFontPadding
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        platformStyle = noFontPadding
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        platformStyle = noFontPadding
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 26.sp,
        platformStyle = noFontPadding
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        platformStyle = noFontPadding
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        platformStyle = noFontPadding
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        platformStyle = noFontPadding
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        platformStyle = noFontPadding
    )
)

/** 工具名 / URL / 模型名 / Key / 入参结果块（mono 13sp/20）。 */
internal val machineText: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    platformStyle = noFontPadding
)

/** 时间戳 / STEP n/m / 时长 / 指纹 / 版本（mono 12sp/16）。 */
internal val machineSmall: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    platformStyle = noFontPadding
)
