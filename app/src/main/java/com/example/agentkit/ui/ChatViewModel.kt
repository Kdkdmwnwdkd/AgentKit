package com.example.agentkit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import com.example.agentkit.automation.AutomationBridge
import com.example.agentkit.automation.AutomationPrefs
import com.example.agentkit.automation.ScreenshotBridge
import com.example.agentkit.automation.UiClickTool
import com.example.agentkit.automation.UiInputTool
import com.example.agentkit.automation.UiKeyTool
import com.example.agentkit.automation.UiLongPressTool
import com.example.agentkit.automation.UiScreenshotTool
import com.example.agentkit.automation.UiSwipeTool
import com.example.agentkit.automation.UiTapTool
import com.example.agentkit.automation.UiTreeTool
import com.example.agentkit.core.AgentLoop
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.OpenAIClient
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.UserMessage
import com.example.agentkit.skill.JsEngine
import com.example.agentkit.skill.ModelConfig
import com.example.agentkit.skill.RhinoJsEngine
import com.example.agentkit.skill.SkillExecutor
import com.example.agentkit.skill.SkillLoader
import com.example.agentkit.skill.createUseSkillTool
import com.example.agentkit.tools.ClipboardTool
import com.example.agentkit.tools.HttpRequestTool
import com.example.agentkit.tools.NotificationTool
import com.example.agentkit.tools.ReadFileTool
import com.example.agentkit.tools.WriteFileTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** UI 渲染用的消息项（与核心层 ChatMessage 解耦）。 */
sealed interface UiItem {
    data class User(val text: String) : UiItem
    data class Assistant(val text: String) : UiItem
    data class Tool(val call: ToolCall, val result: String, val isError: Boolean) : UiItem
}

/**
 * UI 与业务的唯一桥梁：持有注册表、Agent 循环与配置，对外只暴露 StateFlow。
 * 业务规则（工具注册、循环策略、Skill 接入）都在这里组装，Compose 只负责渲染。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val workspace = File(app.filesDir, "agent_workspace")

    /** 自动化总开关持久化。 */
    private val automationPrefs = AutomationPrefs(app)

    /** 自动化状态流：供设置页实时反映开关与服务连接情况。 */
    private val _automationEnabled = MutableStateFlow(automationPrefs.enabled)
    val automationEnabled: StateFlow<Boolean> = _automationEnabled.asStateFlow()

    private val _serviceConnected = MutableStateFlow(AutomationBridge.service != null)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    /** 屏幕捕获状态：供自动化页反映是否正在捕获。 */
    private val _capturing = MutableStateFlow(ScreenshotBridge.isCapturing)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    /** 扫描已安装 Skill（assets/skills）；为空则不打 use_skill 工具。 */
    private val skills = SkillLoader.loadAll(app)

    private val registry = ToolRegistry().apply {
        register(HttpRequestTool())
        register(ReadFileTool(workspace))
        register(WriteFileTool(workspace))
        register(ClipboardTool(app))
        register(NotificationTool(app))
        // 手机自动化工具组（受总开关 + 无障碍服务连接双重保护）
        register(UiTreeTool())
        register(UiTapTool())
        register(UiLongPressTool())
        register(UiSwipeTool())
        register(UiInputTool())
        register(UiKeyTool())
        register(UiClickTool())
        // 截图视觉（L2）：喂给视觉模型“看”屏幕
        register(UiScreenshotTool(workspace))
    }

    /** JS 执行引擎（Rhino，纯 Java 无需 NDK）；初始化失败则降级为仅 prompt 形态。 */
    private val jsEngine: JsEngine? = runCatching { RhinoJsEngine() }.getOrNull()

    /** Skill 执行器：把工具注册表、工作目录、脚本引擎、资源读取接到一起。 */
    private val skillExecutor = SkillExecutor(
        registry = registry,
        workspace = workspace,
        jsEngine = jsEngine,
        readBody = { SkillLoader.readSkillBody(app, it) },
        readEntry = { SkillLoader.readEntry(app, it) }
    )

    init {
        if (skills.isNotEmpty()) {
            // use_skill 工具的 systemPrompt 会被 AgentLoop 自动拼进系统提示（利用工具自带提示特性）
            registry.register(createUseSkillTool(skills, skillExecutor))
        }
        // 同步总开关到桥；并把服务连接变化回写到 UI 状态流
        AutomationBridge.enabled = automationPrefs.enabled
        AutomationBridge.onConnectionChanged = { connected -> _serviceConnected.value = connected }
    }

    /** 设置页：切换自动化总开关（同时持久化并同步到桥）。 */
    fun setAutomationEnabled(v: Boolean) {
        automationPrefs.enabled = v
        AutomationBridge.enabled = v
        _automationEnabled.value = v
    }

    /** 设置页：跳转系统无障碍设置页，引导用户开启本应用的服务。 */
    fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** 屏幕捕获：缓存 MediaProjection 授权结果（来自系统授权回调）。 */
    fun setScreenCapturePermission(resultCode: Int, data: android.content.Intent?) {
        ScreenshotBridge.setPermission(resultCode, data)
    }

    /** 屏幕捕获：启动前台服务开始截屏（需先 setScreenCapturePermission）。 */
    fun startScreenCapture(ctx: Context) {
        ScreenshotBridge.startService(ctx)
        _capturing.value = true
    }

    /** 屏幕捕获：停止前台服务。 */
    fun stopScreenCapture(ctx: Context) {
        ScreenshotBridge.stopService(ctx)
        _capturing.value = false
    }

    private val _messages = MutableStateFlow<List<UiItem>>(emptyList())
    val messages: StateFlow<List<UiItem>> = _messages.asStateFlow()

    private val _config = MutableStateFlow(ModelConfig())
    val config: StateFlow<ModelConfig> = _config.asStateFlow()

    val input = MutableStateFlow("")
    val isRunning = MutableStateFlow(false)

    // llmProvider 每次都按最新配置构造，支持运行时切换模型/Key
    private val loop = AgentLoop(
        llmProvider = {
            OpenAIClient(LLMConfig(_config.value.baseUrl, _config.value.apiKey, _config.value.model))
        },
        registry = registry
    )

    fun updateConfig(cfg: ModelConfig) { _config.value = cfg }

    fun send() {
        val text = input.value.trim()
        if (text.isBlank() || isRunning.value) return
        input.value = ""
        isRunning.value = true

        viewModelScope.launch {
            runCatching { loop.run(text) }
                .onSuccess { history -> history.forEach { append(it) } }
                .onFailure { _messages.value = _messages.value + UiItem.Assistant("⚠️ 出错了：${it.message}") }
            isRunning.value = false
        }
    }

    private fun append(msg: ChatMessage) {
        val item = when (msg) {
            is UserMessage -> UiItem.User(msg.content)
            is AssistantMessage -> UiItem.Assistant(msg.content)
            is ToolMessage -> UiItem.Tool(ToolCall(msg.id, msg.name, msg.arguments), msg.content, msg.isError)
            else -> return
        }
        _messages.value = _messages.value + item
    }
}
