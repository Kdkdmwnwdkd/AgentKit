package com.example.agentkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.agentkit.ui.AgentKitTheme
import com.example.agentkit.ui.AppNav
import com.example.agentkit.ui.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AgentKitApp() }
    }
}

/** 应用根：套一层品牌主题，内容区由底部导航宿主 [AppNav] 承载。 */
@Composable
private fun AgentKitApp() {
    AgentKitTheme {
        Surface { AppNav(viewModel<ChatViewModel>()) }
    }
}
