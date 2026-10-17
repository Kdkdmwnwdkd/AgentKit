package com.example.agentkit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.agentkit.browser.BrowserHost
import com.example.agentkit.ui.AgentKitTheme
import com.example.agentkit.ui.AppScaffold
import com.example.agentkit.ui.ChatViewModel
import com.example.agentkit.ui.ThemeMode
import com.example.agentkit.workflow.WorkflowNotifier

class MainActivity : ComponentActivity() {

    /**
     * 通知点击带来的待执行任务 id（W-17）。
     *
     * 用 Compose State 而不是「读一次就丢」：App 已在前台时点通知会走 [onNewIntent]，
     * 这时 Activity 不会重建，必须靠状态变化驱动 Compose 重新消费。
     */
    private val pendingWorkflowId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // W-15：后台 WebView 宿主（无界面），供 web_* 工具操作网页
        BrowserHost.attach(this)
        pendingWorkflowId.value = intent?.getStringExtra(WorkflowNotifier.EXTRA_WORKFLOW_ID)
        setContent {
            AgentKitApp(
                pendingWorkflowId = pendingWorkflowId.value,
                onWorkflowConsumed = { pendingWorkflowId.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 前台时点通知：不重建 Activity，靠这个状态触发执行
        pendingWorkflowId.value = intent.getStringExtra(WorkflowNotifier.EXTRA_WORKFLOW_ID)
    }

    override fun onDestroy() {
        // 释放 WebView，避免持有 Activity 造成泄漏
        BrowserHost.release()
        super.onDestroy()
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
private fun AgentKitApp(
    pendingWorkflowId: String? = null,
    onWorkflowConsumed: () -> Unit = {}
) {
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }
    var dynamicColor by rememberSaveable { mutableStateOf(false) }
    val vm = viewModel<ChatViewModel>()
    // W-17：点通知进入 → 自动把该任务的 prompt 作为一次对话发送
    LaunchedEffect(pendingWorkflowId) {
        if (pendingWorkflowId != null) {
            vm.consumeWorkflowIntent(pendingWorkflowId)
            onWorkflowConsumed()
        }
    }
    AgentKitTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
        AppScaffold(
            vm = vm,
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it },
            dynamicColor = dynamicColor,
            onDynamicColorChange = { dynamicColor = it }
        )
    }
}
