package com.example.agentkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.agentkit.ui.AgentKitTheme
import com.example.agentkit.ui.AppScaffold
import com.example.agentkit.ui.ChatViewModel
import com.example.agentkit.ui.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgentKitApp() }
    }
}

/**
 * 应用根：品牌主题 + 抽屉导航宿主 [AppScaffold]。
 *
 * 主题模式用 rememberSaveable 持有在** Activity 作用域**（而非各页内），
 * 设置页改选项、全 App 立刻跟着变；旋转屏幕 / 进程被系统回收后重建时不丢选择。
 * 偏好不落盘——日用助手天天对话，主题不值得一次磁盘写（见 [ThemeMode] 注释）。
 *
 * 功能五（P1-10）：动态取色开关与 [themeMode] 同纪律（进程内有效不落盘），
 * 默认关——「暖纸」品牌色是 deliberate 设计，壁纸取色是 opt-in 覆盖。
 */
@Composable
private fun AgentKitApp() {
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }
    var dynamicColor by rememberSaveable { mutableStateOf(false) }
    AgentKitTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
        AppScaffold(
            vm = viewModel<ChatViewModel>(),
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it },
            dynamicColor = dynamicColor,
            onDynamicColorChange = { dynamicColor = it }
        )
    }
}
