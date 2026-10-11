package com.example.agentkit.ui

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.ComponentName
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentkit.automation.AutomationBridge
import com.example.agentkit.automation.AutomationPrefs
import com.example.agentkit.automation.DeviceForegroundAppTool
import com.example.agentkit.automation.AgentNotificationListener
import com.example.agentkit.automation.DeviceContext
import com.example.agentkit.automation.DeviceContextStore
import com.example.agentkit.automation.DeviceNotificationsTool
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
import com.example.agentkit.core.AgentLogger
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
import com.example.agentkit.skill.ImportedSkillRepository
import com.example.agentkit.skill.SkillManifest
import com.example.agentkit.skill.createUseSkillTool
import com.example.agentkit.skill.CapabilityRegistry
import com.example.agentkit.skill.policyFor
import com.example.agentkit.skill.checkCapabilityGate
import com.example.agentkit.memory.MemoryItem
import com.example.agentkit.memory.MemoryStore
import com.example.agentkit.memory.MemoryForgetTool
import com.example.agentkit.memory.MemoryListTool
import com.example.agentkit.memory.MemorySearchTool
import com.example.agentkit.memory.MemoryWriteTool
import com.example.agentkit.tools.ClipboardTool
import com.example.agentkit.tools.HttpRequestTool
import com.example.agentkit.tools.NotificationTool
import com.example.agentkit.tools.ReadFileTool
import com.example.agentkit.tools.WriteFileTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** 单个技能向 UI 暴露的「工具-能力」明细。 */
data class SkillToolInfo(
    val name: String,
    val capability: String,
    val allowed: Boolean
)

/** UI 用技能信息（含 L5 安全策略摘要）：技能中心卡片直接消费。 */
data class SkillUiInfo(
    val id: String,
    val name: String,
    val description: String,
    val trust: String,
    /** 是否来自「导入」来源（filesDir/skills/imported），决定能否授权/卸载。 */
    val isImported: Boolean,
    val isScript: Boolean,
    val allowScript: Boolean,
    val maxCapability: String,
    val tools: List<SkillToolInfo>
)

/** UI 渲染用的消息项（与核心层 ChatMessage 解耦）。 */
sealed interface UiItem {
    data class User(val text: String) : UiItem
    data class Assistant(val text: String) : UiItem
    data class Tool(
        val call: ToolCall,
        val result: String,
        val isError: Boolean,
        /** 工具返回的图片（data URL 列表），如 ui_screenshot 截图，直接渲染在对话中。 */
        val images: List<String> = emptyList()
    ) : UiItem
}

/**
 * UI 与业务的唯一桥梁：持有注册表、Agent 循环与配置，对外只暴露 StateFlow。
 * 业务规则（工具注册、循环策略、Skill 接入）都在这里组装，Compose 只负责渲染。
 */
class ChatViewModel(private val app: Application) : AndroidViewModel(app) {

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

    /** 通知访问权限是否已授予（供感知层卡片展示）。 */
    private val _notificationGranted = MutableStateFlow(false)
    val notificationGranted: StateFlow<Boolean> = _notificationGranted.asStateFlow()

    /** 使用情况访问权限是否已授予（供感知层卡片展示）。 */
    private val _usageGranted = MutableStateFlow(false)
    val usageGranted: StateFlow<Boolean> = _usageGranted.asStateFlow()

    /** 当前设备上下文快照（L4 主动感知）：供自动化页展示 Agent 实时感知到的手机状态。 */
    private val _deviceContext = MutableStateFlow(DeviceContextStore.snapshot())
    val deviceContext: StateFlow<DeviceContext> = _deviceContext.asStateFlow()

    /** 导入技能仓库（L7）：从 URL / 本机 zip / 内置示例导入到 filesDir/skills/imported。 */
    private val importRepo = ImportedSkillRepository(app)

    /** 已导入技能 id 集合：用于按来源路由资源配置（imported 读 filesDir，否则读 assets）。 */
    private var importedIds: Set<String> = emptySet()

    /** 技能快照 = 内置（assets）+ 已导入（filesDir），随导入/卸载/授权动态刷新。 */
    private var skillsSnapshot: List<SkillManifest> = buildInitialSkills()

    /** 首次构建技能列表：合并 assets 内置技能与 filesDir 已导入技能。 */
    private fun buildInitialSkills(): List<SkillManifest> {
        val imported = importRepo.loadImported(app)
        importedIds = imported.map { it.id }.toSet()
        return SkillLoader.loadAll(app) + imported
    }

    /** UI 用技能信息（含安全策略摘要）：让 L5 全局能力门「看得见」。 */
    private val _skillInfos = MutableStateFlow(computeSkillInfos())
    val skillInfos: StateFlow<List<SkillUiInfo>> = _skillInfos.asStateFlow()

    /** 导入/卸载操作的状态提示（Toast 式文本）；reload 不改变它（保留上次提示）。 */
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    /** 由 skillsSnapshot 计算技能中心卡片数据（L5 安全策略摘要）。 */
    private fun computeSkillInfos(): List<SkillUiInfo> = skillsSnapshot.map { sk ->
        val policy = policyFor(sk.trust)
        SkillUiInfo(
            id = sk.id,
            name = sk.name,
            description = sk.description ?: "",
            trust = sk.trust,
            isImported = sk.id in importedIds,
            isScript = sk.entry != null,
            allowScript = policy.allowScript,
            maxCapability = policy.maxCapability.name,
            tools = sk.tools.map { t ->
                SkillToolInfo(
                    name = t,
                    capability = CapabilityRegistry.cap(t).name,
                    allowed = checkCapabilityGate(t, sk.trust) == null
                )
            }
        )
    }

    /** L8 运行日志落盘目录：filesDir/logs/agent-<yyyyMMdd>.log（core 内部异步写盘）。 */
    private val logDir = File(app.filesDir, "logs")

    /** L9 记忆库：跨会话长期记忆，持久化到 filesDir/memory/memory.json。 */
    private val memoryStore = MemoryStore(File(app.filesDir, "memory"))

    /** L8 最近日志（格式化后的文本，最新的在前），供自动化页「运行日志」卡片展示。 */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** L9 记忆列表快照，随写入/检索/遗忘/清空刷新。 */
    private val _memories = MutableStateFlow<List<MemoryItem>>(emptyList())
    val memories: StateFlow<List<MemoryItem>> = _memories.asStateFlow()

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
        // 设备感知（L3）：通知监听 + 当前前台应用
        register(DeviceNotificationsTool())
        register(DeviceForegroundAppTool(app))
        // 记忆库（L9）：让 Agent 拥有跨会话长期记忆
        register(MemoryWriteTool(memoryStore))
        register(MemorySearchTool(memoryStore))
        register(MemoryListTool(memoryStore))
        register(MemoryForgetTool(memoryStore))
    }

    /** JS 执行引擎（Rhino，纯 Java 无需 NDK）；初始化失败则降级为仅 prompt 形态。 */
    private val jsEngine: JsEngine? = runCatching { RhinoJsEngine() }.getOrNull()

    /** Skill 执行器：把工具注册表、工作目录、脚本引擎、资源读取接到一起。 */
    private val skillExecutor = SkillExecutor(
        registry = registry,
        workspace = workspace,
        jsEngine = jsEngine,
        readBody = { sk -> if (sk.id in importedIds) SkillLoader.readImportedBody(app, sk) else SkillLoader.readSkillBody(app, sk) },
        readEntry = { sk -> if (sk.id in importedIds) SkillLoader.readImportedEntry(app, sk) else SkillLoader.readEntry(app, sk) }
    )

    init {
        // L8：日志目录就绪后才能记录后续任何一行
        AgentLogger.init(logDir)
        if (skillsSnapshot.isNotEmpty()) {
            // use_skill 工具的 systemPrompt 会被 AgentLoop 自动拼进系统提示（利用工具自带提示特性）
            // 用 skillsProvider 动态感知导入/卸载/授权的技能变化
            registry.register(createUseSkillTool({ skillsSnapshot }, skillExecutor))
        }
        // 同步总开关到桥；并把服务连接变化回写到 UI 状态流
        AutomationBridge.enabled = automationPrefs.enabled
        AutomationBridge.onConnectionChanged = { connected -> _serviceConnected.value = connected }
        refreshGrants()
        // L8/L9：首屏即有内容可看
        refreshLogs()
        refreshMemories()
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

    /** 刷新权限授予状态（进入自动化页时调用，反映用户在系统设置里的变更）。 */
    fun refreshGrants() {
        // 通知监听权限：检查 enabled_notification_listeners 设置里是否包含本服务组件（免依赖、全版本可用）
        val cn = ComponentName(app, AgentNotificationListener::class.java)
        val enabledListeners = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
        _notificationGranted.value = enabledListeners
            ?.split(":")
            ?.any { it.equals(cn.flattenToString(), ignoreCase = true) }
            ?: false
        // 使用情况访问权限：通过 AppOps 判断 PACKAGE_USAGE_STATS 是否已授予
        _usageGranted.value = try {
            val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                app.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
        // L4 主动感知：刷新 UI 展示的设备上下文快照
        _deviceContext.value = DeviceContextStore.snapshot()
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
        registry = registry,
        // L9：每轮运行都把当前记忆摘要注入系统提示
        memoryProvider = { memoryStore.summaryForPrompt(12, 1200) }
    )

    fun updateConfig(cfg: ModelConfig) { _config.value = cfg }

    // —— L8 运行日志（日记）：出错可定位 ——

    /** 拉取最近 200 条日志文本（最新的在前）刷新 UI。 */
    fun refreshLogs() {
        _logs.value = AgentLogger.recent(200).map { it.format() }
    }

    /** 清空日志（内存缓冲 + 当日文件），随后刷新列表。 */
    fun clearLogs() {
        AgentLogger.clear()
        refreshLogs()
    }

    /** 导出日志全文，供用户复制到剪贴板排查问题。 */
    fun exportLogs(): String = AgentLogger.snapshotText(500)

    /** 当前日志文件绝对路径（卡片底部展示，方便 adb pull）。 */
    fun logFilePath(): String = AgentLogger.currentLogFile()?.absolutePath ?: "（日志文件未就绪）"

    // —— L9 记忆库：跨会话记忆 ——

    /** 刷新记忆列表（最近 50 条，按更新时间倒序）。 */
    fun refreshMemories() {
        _memories.value = memoryStore.list(50)
    }

    /** 手动新增一条记忆（UI 直接录入，默认 note / 重要度 5）。 */
    fun addMemory(content: String, kind: String, tags: List<String>) {
        if (content.isBlank()) return
        memoryStore.add(content.trim(), kind, tags, 5)
        refreshMemories()
    }

    /** 关键词检索记忆；结果直接替换列表，便于查看命中情况。 */
    fun searchMemory(query: String) {
        _memories.value = if (query.isBlank()) memoryStore.list(50) else memoryStore.search(query.trim(), 20)
    }

    /** 遗忘指定记忆并重刷新列表。 */
    fun forgetMemory(id: String) {
        memoryStore.forget(id)
        refreshMemories()
    }

    /** 清空全部记忆（UI 需二次确认）并重刷新列表。 */
    fun clearMemory() {
        memoryStore.clear()
        refreshMemories()
    }

    /** 复用 importStatus 展示一次性提示（如“已复制到剪贴板”）。 */
    fun setImportStatus(msg: String) { _importStatus.value = msg }

    fun send() {
        val text = input.value.trim()
        if (text.isBlank() || isRunning.value) return
        input.value = ""
        isRunning.value = true
        AgentLogger.i("Chat", "用户发送：${text.take(120)}")

        // L4 主动感知：把当前设备上下文自动拼进用户输入，让模型“知道”手机在干什么
        val ctxPrompt = DeviceContextStore.snapshot().toPrompt()
        val fullInput = if (ctxPrompt.isBlank()) text else "$ctxPrompt\n\n用户当前请求：$text"

        viewModelScope.launch {
            // 先拿到 Result，再在同一个挂起体里做后续 IO：Result.onSuccess 的 lambda 不是 suspend
            val outcome = runCatching { loop.run(fullInput) }
            outcome.onSuccess { history -> history.forEach { append(it) } }
            outcome.onFailure {
                AgentLogger.e("Chat", "运行失败", it)
                // 错误文案会直接渲染进聊天气泡（可被复制走），必须先脱敏再展示
                val safeMsg = AgentLogger.sanitize(it.message ?: "未知错误")
                _messages.value = _messages.value + UiItem.Assistant("⚠️ 出错了：$safeMsg")
            }
            // L9：把本轮对话沉淀成低权重 event 记忆，下次会话也能回忆起来
            val finalAnswer = outcome.getOrNull()
                ?.filterIsInstance<AssistantMessage>()
                ?.lastOrNull()?.content
                .orEmpty()
            if (finalAnswer.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        memoryStore.add(
                            "用户请求：${text.take(80)}｜结果：${finalAnswer.take(120)}",
                            "event",
                            emptyList(),
                            3
                        )
                    }
                }
                refreshMemories()
            }
            isRunning.value = false
            refreshLogs()
        }
    }

    private fun append(msg: ChatMessage) {
        val item = when (msg) {
            is UserMessage -> UiItem.User(msg.content)
            is AssistantMessage -> UiItem.Assistant(msg.content)
            is ToolMessage -> UiItem.Tool(
                ToolCall(msg.id, msg.name, msg.arguments),
                msg.content,
                msg.isError,
                msg.images
            )
            else -> return
        }
        _messages.value = _messages.value + item
    }

    // —— L7 技能导入 / 卸载 / 授权 ——

    /** 从 URL 下载 zip 并导入；网络 IO 放 Dispatchers.IO。 */
    fun importFromUrl(url: String) {
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    URL(url).openStream().use { it.readBytes() }
                }
                importRepo.installZip(bytes).getOrThrow()
            }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /** 载入内置示例技能（无网络演示）。 */
    fun importDemo() {
        viewModelScope.launch {
            val result = runCatching {
                importRepo.installZip(ImportedSkillRepository.demoZipBytes()).getOrThrow()
            }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /** 从本机选择的 zip 文件字节导入。 */
    fun importFromFile(bytes: ByteArray) {
        viewModelScope.launch {
            val result = runCatching {
                importRepo.installZip(bytes).getOrThrow()
            }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /**
     * 从 SAF 选择的 Uri 导入 zip：文件读取放 Dispatchers.IO，绝不在主线程读字节。
     * @param uri 来自 ActivityResultContracts.GetContent() 的内容 Uri。
     */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("无法读取所选文件")
                }
                importRepo.installZip(bytes).getOrThrow()
            }
            if (result.isFailure) {
                _importStatus.value = "⚠️ 导入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "✅ 已导入：${result.getOrThrow()}"
        }
    }

    /** 卸载指定技能（仅导入来源）；失败时给出明确提示，不谎报成功。 */
    fun uninstallSkill(id: String) {
        viewModelScope.launch {
            val ok = importRepo.uninstall(id)
            if (!ok) {
                _importStatus.value = "⚠️ 卸载失败：$id"
                return@launch
            }
            reloadSkills()
            _importStatus.value = "🗑️ 已卸载：$id"
        }
    }

    /**
     * 设置某技能的脚本授权（allow=true → 运行时 packaged，false → imported）。
     * 授权状态由仓库落 SharedPreferences，不再改写 skill.json。
     */
    fun setScriptAuthorization(id: String, allow: Boolean) {
        viewModelScope.launch {
            val ok = importRepo.setScriptAuthorization(id, allow)
            if (!ok) {
                _importStatus.value = "⚠️ 操作失败：$id"
                return@launch
            }
            reloadSkills()
            _importStatus.value = if (allow) "✅ 已授权脚本：$id" else "✅ 已撤销脚本授权：$id"
        }
    }

    /** 重新加载技能快照并刷新 skillInfos；不改变 importStatus。 */
    private fun reloadSkills() {
        skillsSnapshot = buildInitialSkills()
        _skillInfos.value = computeSkillInfos()
    }
}
