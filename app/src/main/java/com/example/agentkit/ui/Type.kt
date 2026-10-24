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

// 真机反馈（2026-10-24 录屏）：设置页 / 容器页出现「标题压住副标题、同一行字上下错位」。
// 布局本身是 heightIn(min) + Column 自适应，不存在裁切；问题在字体度量——
// 部分厂商 ROM 的中文字体 ascent/descent 与 Compose 按 lineHeight 计算的 line box 对不上，
// 关掉 font padding 后字形会溢出到相邻行。
// 恢复系统默认（true）：行高多 2~4dp，换「任何字体下都不会叠字」。
private val fontPaddingStyle = PlatformTextStyle(includeFontPadding = true)

internal val AgentKitTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        platformStyle = fontPaddingStyle
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        platformStyle = fontPaddingStyle
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        platformStyle = fontPaddingStyle
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 26.sp,
        platformStyle = fontPaddingStyle
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        platformStyle = fontPaddingStyle
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        platformStyle = fontPaddingStyle
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        platformStyle = fontPaddingStyle
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        platformStyle = fontPaddingStyle
    )
)

/** 工具名 / URL / 模型名 / Key / 入参结果块（mono 13sp/20）。 */
internal val machineText: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    platformStyle = fontPaddingStyle
)

/** 时间戳 / STEP n/m / 时长 / 指纹 / 版本（mono 12sp/16）。 */
internal val machineSmall: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    platformStyle = fontPaddingStyle
)
