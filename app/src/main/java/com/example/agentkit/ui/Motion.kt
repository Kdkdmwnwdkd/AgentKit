package com.example.agentkit.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 动效 token（视觉规范 v3.1 §7.1）：全 App 只此四个 + 流式光标 + 倒计时线，无 spring。
 *
 * motionPress 100ms 全局按压反馈（scale 0.97）· motionFast 150ms 颜色/透明度/chevron/新消息 fade ·
 * motionBase 250ms 展开收起 / 页面内 Tab 变色 / 抽屉滑入 · motionDialog 200ms 审批弹窗入场
 * （fade + scale 0.96→1），退场 150ms。抽屉复用 motionBase / motionDialog，不新增第 4 个动效 token。
 */
internal val AkMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal val AkMotionPress: AnimationSpec<Float> = tween(durationMillis = 100, easing = AkMotionEasing)
internal val AkMotionFast: AnimationSpec<Float> = tween(durationMillis = 150, easing = AkMotionEasing)
internal val AkMotionBase: AnimationSpec<Float> = tween(durationMillis = 250, easing = AkMotionEasing)
internal val AkMotionDialog: AnimationSpec<Float> = tween(durationMillis = 200, easing = AkMotionEasing)
internal val AkMotionExit: AnimationSpec<Float> = tween(durationMillis = 150, easing = AkMotionEasing)

/** 同 token 的 Dp / Color 具型变体：供 animateDpAsState / animateColorAsState 直接使用。 */
internal val AkMotionFastDp: AnimationSpec<Dp> = tween(durationMillis = 150, easing = AkMotionEasing)
internal val AkMotionFastColor: AnimationSpec<Color> = tween(durationMillis = 150, easing = AkMotionEasing)
