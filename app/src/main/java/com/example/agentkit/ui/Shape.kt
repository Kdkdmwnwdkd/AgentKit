package com.example.agentkit.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角体系（视觉规范 v3.1 §5.2）：4 档，v2 的 6 档废止。
 *
 * extraSmall=8（checkbox / 小代码角）· small=medium=14（按钮 / 输入框 / 内嵌块 / 抽屉项）·
 * large=extraLarge=20（卡片 / 列表项 / Dialog / 底栏）。聊天气泡尾角的 4dp 收窄在 ChatScreen 内按角设置。
 */
internal val AgentKitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp)
)
