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
import com.example.agentkit.core.ConfigSanitizer
import com.example.agentkit.core.ConfigStore
import com.example.agentkit.core.ConversationStore
import com.example.agentkit.core.ConversationSummary
import com.example.agentkit.core.ModelProfile
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LlmHttpException
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.ModelListFetcher
import com.example.agentkit.core.ProviderManager
import com.example.agentkit.core.AgentEvent
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolApproval
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.UserMessage
import com.example.agentkit.core.toMsgRecord
import com.example.agentkit.core.toUiItems
import com.example.agentkit.core.llm.local.DownloadedModel
import com.example.agentkit.core.llm.local.GgufCatalogEntry
import com.example.agentkit.core.llm.local.GgufModelStore
import com.example.agentkit.core.llm.local.LocalLlamaClient
import com.example.agentkit.core.llm.local.LocalLlamaEngine
import com.example.agentkit.core.llm.local.LocalLlamaSetting
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URL
import java.util.UUID

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
        val images: List<String> = emptyList(),
        /** Hotfix2：执行中态（AgentEvent.ToolStarted 已上报、ToolFinished 未到）。 */
        val running: Boolean = false
    ) : UiItem
}

/**
 * 一次待确认的危险工具调用（L10）：由 AgentLoop 发起、UI 弹窗、用户决策后回填。
 *
 * @param toolName 待执行的工具名（如 ui_tap）。
 * @param argsPreview 入参预览（截断到 500 字符，避免超长参数撑爆 Dialog）。
 * @param deferred UI 用它回复 AgentLoop：complete(true) 放行、complete(false) 拒绝。
 */
data class ApprovalRequest(
    val toolName: String,
    val argsPreview: String,
    val deferred: CompletableDeferred<Boolean>
)

/** 「测试连接」的诊断结果：配置页卡片直接消费。 */
data class TestResult(
    /** 是否连通（HTTP 2xx）。 */
    val ok: Boolean,
    /** HTTP 状态码；网络异常时为 -1。 */
    val code: Int,
    /** 实际发起请求的完整端点。 */
    val endpoint: String,
    /** Key 指纹：len=35 head=sk-a tail=d38d。 */
    val fingerprint: String,
    /** 响应体前 200 字（已脱敏）；异常时为异常文案。 */
    val bodyPreview: String,
    /** 针对状态码的修复建议（失败时有值）。 */
    val suggestion: String = ""
)

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

    // —— 本地 llama.cpp 推理（九步第 3 步）————————————————————————

    /** 本地推理参数（active 模型 / ctx / 线程数）持久化。 */
    private val localSetting = LocalLlamaSetting(app)

    /** GGUF 模型文件管理（files/models/）：目录、下载、删除。 */
    private val modelStore = GgufModelStore(app)

    /** 本地模型清单快照（页签打开时刷新）。 */
    private val _localModels = MutableStateFlow(modelStore.listDownloaded())
    val localModels: StateFlow<List<DownloadedModel>> =
        _localModels.asStateFlow()

    /** 当前选中的本地模型文件名（空 = 未选）。 */
    private val _localActive = MutableStateFlow(localSetting.activeModel)
    val localActive: StateFlow<String> = _localActive.asStateFlow()

    /** native 引擎是否已加载模型（真机才可能为 true）。 */
    private val _localLoaded = MutableStateFlow(false)
    val localLoaded: StateFlow<Boolean> = _localLoaded.asStateFlow()

    /** 下载中的文件名 → 进度（0f~1f，total 未知时为 null 持续刷新）。 */
    private val _localDownloading = MutableStateFlow<Pair<String, Float?>?>(null)
    val localDownloading: StateFlow<Pair<String, Float?>?> = _localDownloading.asStateFlow()

    /** 本地推理参数（页签内编辑，保存即落盘）。 */
    private val _localNCtx = MutableStateFlow(localSetting.nCtx)
    val localNCtx: StateFlow<Int> = _localNCtx.asStateFlow()

    private val _localThreads = MutableStateFlow(localSetting.nThreads)
    val localThreads: StateFlow<Int> = _localThreads.asStateFlow()

    private val _localMaxTokens = MutableStateFlow(localSetting.maxTokens)
    val localMaxTokens: StateFlow<Int> = _localMaxTokens.asStateFlow()

    /** 刷新本地模型清单（页签 onResume / 下载完成后调用）。 */
    fun refreshLocalModels() {
        _localModels.value = modelStore.listDownloaded()
        _localLoaded.value = LocalLlamaEngineIsLoaded()
    }

    private fun LocalLlamaEngineIsLoaded(): Boolean =
        LocalLlamaEngine.isLoaded

    /** 选用某个已下载模型（立即生效；下次对话时引擎按参数加载/复用）。 */
    fun setActiveLocalModel(fileName: String) {
        localSetting.activeModel = fileName
        _localActive.value = fileName
    }

    /** 删除本地模型文件（正在加载的会先卸载）。 */
    fun deleteLocalModel(fileName: String) {
        modelStore.delete(fileName)
        _localModels.value = modelStore.listDownloaded()
        if (_localActive.value == fileName) _localActive.value = localSetting.activeModel
    }

    /** 卸载常驻内存的本地模型（换模型或内存吃紧时用）。 */
    fun unloadLocalModel() {
        LocalLlamaEngine.unload()
        _localLoaded.value = false
    }

    /** 下载内置目录中的模型（断点续传；重复调用会复用已完成文件）。 */
    fun downloadLocalModel(entry: GgufCatalogEntry) {
        if (_localDownloading.value != null) return
        viewModelScope.launch {
            _localDownloading.value = entry.fileName to null
            val outcome = runCatching {
                modelStore.download(entry) { downloaded, total ->
                    _localDownloading.value = entry.fileName to
                        if (total > 0) downloaded.toFloat() / total else null
                }
            }
            _localDownloading.value = null
            _localModels.value = modelStore.listDownloaded()
            _importStatus.value = when {
                outcome.isFailure -> "下载失败：${outcome.exceptionOrNull()?.message}"
                // 下完自动选用，省一次点击
                else -> {
                    setActiveLocalModel(entry.fileName)
                    "已下载并选用 ${entry.displayName}"
                }
            }
        }
    }

    fun setLocalNCtx(v: Int) {
        localSetting.nCtx = v
        _localNCtx.value = localSetting.nCtx
    }

    fun setLocalThreads(v: Int) {
        localSetting.nThreads = v
        _localThreads.value = localSetting.nThreads
    }

    fun setLocalMaxTokens(v: Int) {
        localSetting.maxTokens = v
        _localMaxTokens.value = localSetting.maxTokens
    }

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

    // —— 功能三：聊天记录（用户真机录屏反馈：「人家都有聊天记录对话记录，你这都没有」）——
    // 会话持久化：conversations.json 放 filesDir，原子写 + .bak（形态学 MemoryStore）
    private val conversationStore = ConversationStore(File(app.filesDir, "conversations.json"))

    /** 当前会话 id；_messages 的语义即「当前会话的消息」。 */
    private val _activeConversationId = MutableStateFlow(conversationStore.ensureNotEmpty())
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    /** 会话摘要列表（updatedAt 倒序；抽屉「最近对话」与对话页顶栏标题消费）。 */
    private val _conversations = MutableStateFlow(conversationStore.list())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    /**
     * 当前会话的消息；启动时从会话存储载入（功能三：重启 App 后对话还在）。
     * 声明序保证 [conversationStore] / [_activeConversationId] 已就绪。
     */
    private val _messages = MutableStateFlow(
        conversationStore.load(_activeConversationId.value)?.messages?.toUiItems() ?: emptyList()
    )
    val messages: StateFlow<List<UiItem>> = _messages.asStateFlow()

    /** Hotfix2：过程事件回调来自工具并行执行线程，与主线程的 send() 竞争同一列表——加锁防丢更新。 */
    private val msgLock = Any()

    /** 模型配置持久化：进程重启也能还原，避免「明明填过却退回默认值」的不一致。 */
    private val configStore = ConfigStore(app)

    // —— 功能二：多模型配置（用户真机录屏反馈：「别人的模型可以搞好几个，你就只能搞一个」）——
    // _profiles = 全部档案，_activeProfileId = 当前生效档案；config 同名同型保留
    // （= active profile 的 config），现有消费者（对话页 / 401 气泡 / 测试连接）零改动
    private val _profiles = MutableStateFlow(configStore.loadProfiles())
    private val _activeProfileId = MutableStateFlow(configStore.loadActiveId().orEmpty())

    /**
     * 取出当前生效配置；顺带做两道兜底（profiles 为空 / activeId 失效），
     * 保证 [config] 永远有值——老消费者零改动。
     */
    private fun ensureActiveConfig(): ModelConfig {
        if (_profiles.value.isEmpty()) {
            // 极端：profiles_json 损坏成空数组——建默认配置落盘，App 继续可用
            val id = UUID.randomUUID().toString()
            val profile = ModelProfile(id, "默认配置", ModelConfig())
            _profiles.value = listOf(profile)
            _activeProfileId.value = id
            configStore.saveProfiles(_profiles.value, id)
            return profile.config
        }
        val active = _profiles.value.firstOrNull { it.id == _activeProfileId.value }
            ?: _profiles.value.first()
        // activeId 指向不存在的档案（数据漂移）：回退第一个并纠正内存态
        if (active.id != _activeProfileId.value) _activeProfileId.value = active.id
        return active.config
    }

    /** 当前模型配置（已落盘，UI 直接消费）；= 当前生效 profile 的 config。 */
    private val _config = MutableStateFlow(ensureActiveConfig())
    val config: StateFlow<ModelConfig> = _config.asStateFlow()

    /** 全部模型配置档案（配置页切换条消费）。 */
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    /** 当前生效档案 id（配置页高亮 + 编辑器语义锚点）。 */
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /** 「测试连接」结果流：配置页卡片消费，null 表示尚未测试。 */
    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    /** 是否正在测试连接：UI 用它给按钮显示加载态，避免重复点击。 */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    val input = MutableStateFlow("")
    val isRunning = MutableStateFlow(false)

    /**
     * T05：当前运行任务的 Job 句柄（send() 内置，stop() 取消）。
     * 取消后 AgentLoop 会在 ≤1 轮内退出，并在 NonCancellable 内补全所有待决
     * ToolResult（树尾闭合）——历史里不会留下未闭合 tool_call。
     */
    private var runJob: Job? = null

    /**
     * T05：停止当前运行（流式中点「停止」）。
     * 取消 runJob → AgentLoop 每轮边界 ensureActive 退出 + 树尾闭合补结果。
     * 未在运行时调用为 no-op。
     */
    fun stop() {
        val job = runJob ?: return
        AgentLogger.i("Chat", "用户请求停止运行")
        job.cancel()
        runJob = null
    }

    // —— L10 危险操作审批：Agent 想操控手机必须先过人 ——

    /** 当前待用户确认的危险工具调用；非 null 时 UI 弹出审批 Dialog。 */
    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    /** 本次会话内「始终允许」的工具名集合：仅存进程内存，App 重启即失效，避免长期授权漂移。 */
    private val sessionAllowed = mutableSetOf<String>()

    /** 审批互斥锁：并发工具调用时串行排队，保证同一时刻只有一个审批弹窗。 */
    private val approvalMutex = Mutex()

    /**
     * 向用户征求一次危险操作授权，挂起直到用户决策。
     * 已在本次会话「始终允许」过的工具直接放行，不再打扰用户。
     */
    private suspend fun requestApproval(tool: AgentTool, args: JsonObject): Boolean = approvalMutex.withLock {
        if (tool.name in sessionAllowed) return@withLock true
        val deferred = CompletableDeferred<Boolean>()
        _pendingApproval.value = ApprovalRequest(tool.name, args.toString().take(500), deferred)
        // finally 保证无论用户怎么关掉弹窗，pending 都会清空，不会残留卡住下一个工具
        val ok = try { deferred.await() } finally { _pendingApproval.value = null }
        AgentLogger.w("Approval", "${tool.name} ${if (ok) "允许" else "拒绝"}")
        ok
    }

    /** 用户选择「允许一次」：仅放行当前这一次调用。 */
    fun approveOnce() {
        _pendingApproval.value?.deferred?.complete(true)
    }

    /** 用户选择「本次会话始终允许」：记入会话白名单后放行，后续同类调用不再弹窗。 */
    fun approveAlways() {
        val pending = _pendingApproval.value ?: return
        sessionAllowed += pending.toolName
        pending.deferred.complete(true)
    }

    /** 用户选择「拒绝」：AgentLoop 会把「用户拒绝了该危险操作」作为工具结果回填。 */
    fun denyApproval() {
        _pendingApproval.value?.deferred?.complete(false)
    }

    // llmProvider 每次都按最新配置构造，支持运行时切换模型/Key/供应商
    // L10：ui_tap / ui_input / ui_swipe / ui_key / ui_click / ui_screenshot 等危险工具执行前先问用户
    private val approvalHandler: ToolApproval = { tool, args -> requestApproval(tool, args) }

    /**
     * 供应商工厂：openai / claude / gemini 由 ProviderManager 内置注册；
     * local-llama 在此补注册——工厂签名只有 LLMConfig，而本地客户端还需要
     * setting（模型文件/参数）与 modelStore（文件定位），这些是 ViewModel 的常驻成员，
     * 在注册处闭包捕获即可，不必改 ProviderManager 的接口。
     */
    private val providerManager = ProviderManager().apply {
        registerProvider("local-llama") { cfg ->
            LocalLlamaClient(LocalLlamaEngine, localSetting, modelStore, cfg)
        }
    }

    private val loop = AgentLoop(
        llmProvider = {
            val c = _config.value
            providerManager.create(c.providerId, LLMConfig(c.baseUrl, c.apiKey, c.model))
        },
        registry = registry,
        approval = approvalHandler,
        // L9：每轮运行都把当前记忆摘要注入系统提示
        memoryProvider = { memoryStore.summaryForPrompt(12, 1200) },
        // Hotfix2：工具调用过程实时上屏（执行中卡 → 终态卡），
        // 修掉「AI 干完什么都在黑盒里、跑完才甩结果」的体验缺陷
        onEvent = ::handleAgentEvent
    )

    /** 过程事件 → UI 实时更新（Hotfix2）。工具并行执行时多线程回调，读写列表加锁防丢更新。 */
    private fun handleAgentEvent(ev: AgentEvent) = synchronized(msgLock) {
        when (ev) {
            is AgentEvent.ToolStarted -> {
                val item = UiItem.Tool(
                    call = ev.call,
                    result = "",
                    isError = false,
                    running = true
                )
                _messages.value = _messages.value + item
                // 功能三：执行中态先落一条占位（终态到达后按 call.id 覆盖，见 ConversationStore.appendMessage）
                appendToStore(item)
            }

            is AgentEvent.ToolFinished -> {
                val cur = _messages.value
                val idx = cur.indexOfFirst {
                    it is UiItem.Tool && it.call.id == ev.message.id && it.running
                }
                if (idx >= 0) {
                    val done = UiItem.Tool(
                        call = ToolCall(ev.message.id, ev.message.name, ev.message.arguments),
                        result = ev.message.content,
                        isError = ev.message.isError,
                        images = ev.message.images,
                        running = false
                    )
                    _messages.value = cur.toMutableList().also { it[idx] = done }
                    // 功能三：终态落盘（store 按 call.id 覆盖执行中占位）
                    appendToStore(done)
                }
                // idx < 0（理论上不该发生）：终态由 send() 的 history 兜底补上
            }
        }
    }

    /**
     * 更新模型配置：语义不变——改的是「当前生效档案」的 config（功能二）。
     * 先过 [ConfigSanitizer] 清洗（剔除不可见字符/规范化 URL），再落盘 + 更新内存状态流，
     * 保证「保存即生效、重启不丢失」。
     */
    fun updateConfig(cfg: ModelConfig) {
        val clean = ConfigSanitizer.sanitized(cfg)
        val id = _activeProfileId.value
        val updated = _profiles.value.map { if (it.id == id) it.copy(config = clean) else it }
        _profiles.value = updated
        _config.value = clean
        // saveProfiles 内部同步镜像旧四键（媒体 Key 回退链不断，见 ConfigStore）
        configStore.saveProfiles(updated, id)
    }

    /**
     * 新增一个配置档案（功能二；配置页切换条「＋」→「新建模型配置」对话框确认后调用）。
     * 空白起步、字段自填、对标参照 App：API Key / Base URL / 模型名三个文本字段全部置空，
     * 由用户从头填写，不再继承复制当前生效配置的值；providerId 保留当前供应商
     * （它是下拉选择器而非「已填数据」，配置页的智能预填与默认值提示会引导用户补全）。
     * 命名并立即切换过去（编辑器同步显示空白配置）。
     *
     * @param name 用户在对话框里起的档案名；null / 空白回退自动命名「配置 N」
     *   （用户清空输入框时也走这条——绝不创建空白名档案）。
     * @return 新档案 id。
     */
    fun addProfile(name: String? = null): String {
        val blank = _config.value.let {
            ModelConfig(baseUrl = "", apiKey = "", model = "", providerId = it.providerId)
        }
        val id = UUID.randomUUID().toString()
        val trimmed = name?.trim()
        val profile = ModelProfile(
            id,
            if (trimmed.isNullOrBlank()) "配置 ${_profiles.value.size + 1}" else trimmed,
            blank
        )
        val updated = _profiles.value + profile
        _profiles.value = updated
        _activeProfileId.value = id
        _config.value = blank
        configStore.saveProfiles(updated, id)
        return id
    }

    /**
     * 切换生效档案（配置页切换条点击）。
     * 运行中切换对下一轮请求生效——[loop] 的 llmProvider lambda 每轮读 _config.value，天然支持。
     */
    fun switchProfile(id: String) {
        val target = _profiles.value.firstOrNull { it.id == id } ?: return
        _activeProfileId.value = id
        _config.value = target.config
        configStore.saveProfiles(_profiles.value, id)
    }

    /** 重命名档案（空名忽略，防用户清空文本框后按确定）。 */
    fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val updated = _profiles.value.map { if (it.id == id) it.copy(name = trimmed) else it }
        _profiles.value = updated
        configStore.saveProfiles(updated, _activeProfileId.value)
    }

    /**
     * 删除档案；只剩 1 个时拒绝（至少保留一个，否则当前生效配置无处安放）。
     * 删的是当前生效档案则切换到剩下第一个。
     */
    fun deleteProfile(id: String) {
        if (_profiles.value.size <= 1) return
        val updated = _profiles.value.filter { it.id != id }
        var activeId = _activeProfileId.value
        if (activeId == id) {
            activeId = updated.first().id
            _config.value = updated.first().config
        }
        _activeProfileId.value = activeId
        _profiles.value = updated
        configStore.saveProfiles(updated, activeId)
    }

    /** 由当前保存的 Key 生成「len=35 head=sk-a tail=d38d」指纹，供 401 气泡与测试卡片共用。 */
    private fun keyFingerprint(apiKey: String): String {
        val insp = ConfigSanitizer.inspectKey(apiKey)
        return "len=${insp.length} head=${insp.head} tail=${insp.tail}"
    }

    /**
     * 按供应商拼「测试连接」卡片展示的端点——与各 Provider 实现实际请求路径一致
     * （openai: /chat/completions；claude: /v1/messages；gemini: /v1beta/models/{m}:generateContent；
     * local-llama: 本地文件，展示「本地推理 + 文件名」）。
     */
    private fun endpointFor(cfg: ModelConfig): String {
        if (cfg.providerId == "local-llama") {
            val name = localSetting.activeModel.ifBlank { "未选择模型" }
            return "本地推理 · llama.cpp · $name"
        }
        val base = ConfigSanitizer.normalizeBaseUrl(cfg.baseUrl).trimEnd('/')
        return when (cfg.providerId) {
            "claude" -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            "gemini" -> "$base/v1beta/models/${ConfigSanitizer.cleanModel(cfg.model)}:generateContent"
            else -> "$base/chat/completions"
        }
    }

    /**
     * 「测试连接」：用配置页当前输入框的值（先实时清洗）发起一次最小请求，
     * 把连通性结果写入 [_testResult] 供卡片渲染。网络 IO 全部走 Dispatchers.IO，不阻塞主线程。
     *
     * 最小请求体：`messages=[{role:user,content:"ping"}]`、`max_tokens=1`、不带 tools，
     * 既不污染对话历史，也几乎不产生 token 消耗。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param model 输入框当前的模型名（原始值）。
     */
    /** 三参重载：兼容旧调用点（默认 openai 供应商）。 */
    fun testConnection(baseUrl: String, apiKey: String, model: String) =
        testConnection(baseUrl, apiKey, model, "openai")

    /**
     * 「测试连接」：用配置页当前输入框的值（先实时清洗）发起一次最小请求，
     * 把连通性结果写入 [_testResult] 供卡片渲染。网络 IO 全部走 Dispatchers.IO，不阻塞主线程。
     *
     * 最小请求体按供应商各自协议构造（Provider 实现内聚），
     * 既不污染对话历史，也几乎不产生 token 消耗。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param model 输入框当前的模型名（原始值）。
     * @param providerId 当前选中的供应商（"openai" / "claude" / "gemini"）。
     */
    fun testConnection(baseUrl: String, apiKey: String, model: String, providerId: String) {
        if (_testing.value) return
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            val cfg = ConfigSanitizer.sanitized(baseUrl, apiKey, model, providerId)
            val endpoint = endpointFor(cfg)
            // 本地模式没有 Key：指纹位展示模型文件名，比 "len=0" 更有信息量
            val finger = if (cfg.providerId == "local-llama") {
                "模型文件=${localSetting.activeModel.ifBlank { "未选择" }}"
            } else {
                keyFingerprint(cfg.apiKey)
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    providerManager
                        .create(cfg.providerId, LLMConfig(cfg.baseUrl, cfg.apiKey, cfg.model))
                        .testPing()
                }
            }
            _testResult.value = when {
                outcome.isFailure -> {
                    val t = outcome.exceptionOrNull()!!
                    // 异常文案离开日志系统前先脱敏（防御：异常里可能夹带 url 中的 key 片段）
                    val msg = (t.message?.let { AgentLogger.sanitize(it) } ?: "未知错误").take(200)
                    val localTip = if (providerId == "local-llama") {
                        "本地推理失败：确认模型文件已下载完整、设备为 arm64；" +
                            "加载日志可在「日志」页查看（标签 llama-jni）"
                    } else {
                        "无法连接：请检查网络、Base URL 是否可达（错误类型：${t::class.java.simpleName}）"
                    }
                    TestResult(
                        ok = false,
                        code = -1,
                        endpoint = endpoint,
                        fingerprint = finger,
                        bodyPreview = msg,
                        suggestion = localTip
                    )
                }

                else -> {
                    val ping = outcome.getOrThrow()
                    if (ping.code in 200..299) {
                        TestResult(
                            ok = true,
                            code = ping.code,
                            endpoint = endpoint,
                            fingerprint = finger,
                            bodyPreview = ping.bodyPreview
                        )
                    } else {
                        // 失败分支给出针对性建议，让用户不必再猜是 Key 问题还是端点问题
                        val suggestion = when (ping.code) {
                            401, 403 -> "Key 无效/无权限：确认未夹带空格或零宽字符，且 Key 仍有额度与调用权限"
                            429 -> "请求过快（429）：稍后再试"
                            in 400..499 -> "客户端错误（${ping.code}）：检查模型名 / Base URL 是否正确"
                            else -> "服务端错误（${ping.code}）：稍后重试"
                        }
                        TestResult(
                            ok = false,
                            code = ping.code,
                            endpoint = endpoint,
                            fingerprint = finger,
                            bodyPreview = ping.bodyPreview,
                            suggestion = suggestion
                        )
                    }
                }
            }
            _testing.value = false
        }
    }

    // —— 模型列表拉取（模型配置页「选择模型」弹窗）————————————————————————

    /** 「选择模型」弹窗的模型 id 列表；emptyList = 未拉取或拉到空。 */
    private val _modelList = MutableStateFlow<List<String>>(emptyList())
    val modelList: StateFlow<List<String>> = _modelList.asStateFlow()

    /** 是否正在拉取模型列表：UI 显示 loading，并作并发守卫（拉取中忽略重复触发）。 */
    private val _modelListLoading = MutableStateFlow(false)
    val modelListLoading: StateFlow<Boolean> = _modelListLoading.asStateFlow()

    /** 拉取失败文案（已脱敏后截断）；null = 无错误。 */
    private val _modelListError = MutableStateFlow<String?>(null)
    val modelListError: StateFlow<String?> = _modelListError.asStateFlow()

    /** 拉取任务句柄：重试 / 关闭时先 cancel 旧 job，避免旧结果覆盖新结果。 */
    private var modelListJob: Job? = null

    /**
     * 拉取模型列表（模型配置页「选择模型」弹窗打开即调用）。
     * 与 [testConnection] 同模式：并发守卫 + Dispatchers.IO + 失败脱敏。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值，fetcher 内部先过 ConfigSanitizer）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param providerId 当前选中的供应商（"openai" / "claude" / "gemini" / "local-llama"）。
     */
    fun fetchModelList(baseUrl: String, apiKey: String, providerId: String) {
        // 并发守卫：正在拉取时直接忽略重复点击（与 _testing 同一思路）
        if (_modelListLoading.value) return
        // 本地模型不做网络拉取：请在「本地模型」页签的状态卡中选择
        // （isLocal 时弹窗入口根本不显示，这里是双保险）
        if (providerId == "local-llama") {
            _modelListError.value = "本地模型请在下方状态卡中选择"
            return
        }
        modelListJob?.cancel()
        _modelListLoading.value = true
        _modelListError.value = null
        modelListJob = viewModelScope.launch {
            try {
                // 先过 ConfigSanitizer（与 testConnection 同模式；fetcher 内部还会再洗一遍做防御双保险）
                val base = ConfigSanitizer.normalizeBaseUrl(baseUrl)
                val key = ConfigSanitizer.cleanApiKey(apiKey)
                val models = withContext(Dispatchers.IO) {
                    ModelListFetcher().fetch(base, key, providerId)
                }
                // OkHttp 的 execute() 是阻塞调用，withContext(IO) 里的挂起不会因
                // 外层 job cancel 而中断：旧任务可能跑完阻塞调用后走到这里。
                // isActive 守卫保证旧 job 晚到时不写脏状态（旧配置的列表 / 错误都不覆盖新拉取）。
                if (!isActive) return@launch
                _modelList.value = models
            } catch (e: CancellationException) {
                // 取消不是错误：向上传播，绝不吞（否则协程树语义被破坏）
                throw e
            } catch (e: Throwable) {
                if (!isActive) return@launch
                // 异常文案离开 UI 前先脱敏再截断（防御：异常里可能夹带 url 中的 key 片段）
                _modelListError.value =
                    (e.message?.let { AgentLogger.sanitize(it) } ?: "未知错误").take(200)
            } finally {
                // 只有当期 job 能收 loading：旧 job 的 finally 晚到时 isActive=false，
                // 不会把新任务刚置的 _modelListLoading=true 误收成 false
                if (isActive) _modelListLoading.value = false
            }
        }
    }

    /** 清空模型列表三状态（弹窗关闭时调用：cancel 在途任务 + 三态归零，下次打开重新拉）。 */
    fun clearModelList() {
        modelListJob?.cancel()
        modelListJob = null
        _modelList.value = emptyList()
        _modelListLoading.value = false
        _modelListError.value = null
    }

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

        // T05：句柄置入 runJob，供 stop() 取消；finally 保证 isRunning/日志必收
        runJob = viewModelScope.launch {
            try {
                // Hotfix2：用户消息立即上屏；工具调用过程由 handleAgentEvent 实时渲染
                // （执行中卡 → 终态卡）；最终历史只补文本回答，工具卡按 call.id 去重
                val userItem = UiItem.User(text)
                _messages.value = _messages.value + userItem
                appendToStore(userItem) // 功能三：聊天记录同步落盘
                // 先拿到 Result，再在同一个挂起体里做后续 IO：Result.onSuccess 的 lambda 不是 suspend
                val outcome = runCatching { loop.run(fullInput) }
                outcome.onSuccess { history ->
                    history.forEach { msg ->
                        when (msg) {
                            // 发送时已实时上屏，跳过防重复
                            is UserMessage -> Unit
                            // 过程事件已渲染过同 id 的工具卡；未渲染的才兜底补上
                            is ToolMessage -> {
                                val shown = _messages.value.any { it is UiItem.Tool && it.call.id == msg.id }
                                if (!shown) append(msg)
                            }
                            // 中间轮 Assistant 可能只有 toolCalls 无文本，空内容不出气泡
                            is AssistantMessage -> if (msg.content.isNotBlank()) append(msg)
                            else -> Unit
                        }
                    }
                }
                outcome.onFailure {
                    // T05：取消是用户主动停止，不是错误——树尾闭合后历史已完整，给停止提示
                    if (it is CancellationException) {
                        AgentLogger.i("Chat", "运行已停止（历史已闭合，可继续提问）")
                        val stopItem = UiItem.Assistant("⏹ 已停止。已完成的历史保留，可基于它继续提问。")
                        _messages.value = _messages.value + stopItem
                        appendToStore(stopItem)
                        return@onFailure
                    }
                    AgentLogger.e("Chat", "运行失败", it)
                    // 气泡面向用户：技术异常在此映射成可读文案（完整堆栈已在日志里落盘）
                    val errItem = UiItem.Assistant(friendlyMessage(it))
                    _messages.value = _messages.value + errItem
                    appendToStore(errItem) // 功能三：失败气泡也进聊天记录
                }
                // L9：把本轮对话沉淀成低权重 event 记忆，下次会话也能回忆起来
                // （取消时 finalAnswer 为空，自然跳过）
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
            } finally {
                // finally 保证取消路径（withContext 直接抛 CancellationException）也能复位
                runJob = null
                isRunning.value = false
                refreshLogs()
            }
        }
    }

    /**
     * 把底层技术异常翻译成面向用户的中文提示。
     *
     * 分级依据：[LlmHttpException] 的 HTTP 状态码决定（鉴权/参数类不可重试，直接给修复建议）；
     * 其余 IOException 归为网络问题；兜底才回显脱敏后的原始消息，避免泄露 Key 与堆栈。
     *
     * @param e AgentLoop / LLMClient 抛出的原始异常。
     * @return 可直接渲染进助手气泡的文案。
     */
    private fun friendlyMessage(e: Throwable): String {
        // LlmHttpException 也是 IOException，必须先于网络分支判断
        val http = e as? LlmHttpException
        if (http != null) {
            return when {
                http.code == 401 || http.code == 403 -> {
                    // 401/403：在原有文案基础上，附上能直接定位的信息——服务端原始 message、
                    // 实际请求端点、Key 指纹，让用户凭气泡就能判断是「Key 粘贴不全」还是「端点错了」，
                    // 而不是再去翻日志。指纹只取 head/tail，绝不回显完整 Key。
                    val endpoint = "${ConfigSanitizer.normalizeBaseUrl(_config.value.baseUrl)}/chat/completions"
                    val finger = keyFingerprint(_config.value.apiKey)
                    val serverMsg = http.serverBody.take(200)
                    val extra = buildString {
                        append("\n端点：$endpoint")
                        append("\nKey 指纹：$finger")
                        if (serverMsg.isNotBlank()) {
                            append("\n服务端：${AgentLogger.sanitize(serverMsg)}")
                        }
                    }
                    "🔑 API Key 无效或已过期（${http.code}）：请到「模型」页检查 Key 是否正确、是否还有额度$extra"
                }
                http.code == 429 ->
                    "⏳ 请求过于频繁（429）：请稍后再试"
                http.code >= 500 ->
                    "🔧 模型服务暂时不可用（服务端错误 ${http.code}）：请稍后重试"
                http.code in 400..499 ->
                    "⚠️ 请求被拒绝（${http.code}）：请检查 Base URL 与模型名是否填写正确"
                else ->
                    "⚠️ 请求失败（${http.code}）：${AgentLogger.sanitize(e.message ?: "未知错误")}"
            }
        }
        if (e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.io.IOException
        ) {
            return "📶 网络不可用或超时：请检查网络连接"
        }
        val raw = e.message?.let { AgentLogger.sanitize(it) }?.trim().orEmpty()
        return if (raw.isBlank()) "⚠️ 出错了：未知错误" else "⚠️ 出错了：$raw"
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
        appendToStore(item) // 功能三：history 兜底补的消息同样落盘
    }

    // —— 功能三：会话切换 / 增删改（聊天记录） ——

    /**
     * 同步一条消息到会话存储。与 _messages 写入点同处调用、同在 [msgLock] 内，保顺序；
     * activeConversationId 运行中不变（[switchConversation] 有 isRunning 守卫），直接读 current。
     *
     * 每条消息后都刷摘要流：工具事件频次高（每轮多个 ToolStarted/ToolFinished），不刷的话
     * 抽屉「最近对话」的标题/条数/updatedAt 在整个会话期间全陈旧，首条 user 触发的自动
     * 命名也不反映到顶栏（QA 抓到的体验缺陷）。store.list() 是内存 items 快照 map+sort
     * （synchronized 内、无磁盘 IO），每事件刷新是 O(n) 内存操作，可负担。
     */
    private fun appendToStore(item: UiItem) {
        // _activeConversationId 内部是非空 String（ensureNotEmpty 兜底）；对外 StateFlow 声明为
        // String? 是给 UI 层「可能还没初始化」的余地，这里直接读内部值即可
        val convId = _activeConversationId.value
        conversationStore.appendMessage(convId, item.toMsgRecord())
        refreshConversations()
    }

    /** 刷新会话摘要列表（每次增删改后调用；抽屉最近对话区与顶栏标题消费）。 */
    private fun refreshConversations() {
        _conversations.value = conversationStore.list()
    }

    /** 载入指定会话为当前会话（设 id + 读消息进 _messages + 刷新摘要）。 */
    private fun loadConversation(id: String) {
        _activeConversationId.value = id
        _messages.value = conversationStore.load(id)?.messages?.toUiItems() ?: emptyList()
        refreshConversations()
    }

    /**
     * 新建对话并切过去（顶栏 / 抽屉「＋ 新对话」）。
     * 运行中拒绝（VM 兜底，UI 禁用只是双保险）：新建即切 activeConversationId，
     * 在途事件（ToolStarted/ToolFinished、history 兜底、onFailure）会按新 id 落盘，
     * 把旧会话的尾巴写进新会话——与 [switchConversation] 同一理由。
     */
    fun newConversation() {
        if (isRunning.value) return
        loadConversation(conversationStore.create())
    }

    /**
     * 切换到指定会话（抽屉「最近对话」点击）。
     * 运行中直接拒绝：Agent 事件按当前会话写消息，中途切换会写串（UI 层也会禁用，双保险）。
     */
    fun switchConversation(id: String) {
        if (isRunning.value) return
        if (id == _activeConversationId.value) return
        loadConversation(id)
    }

    /**
     * 删除会话；删的是当前会话则顺手切到剩下的最近一个（_messages 不空指）。
     * 删 active 会话 = 变相切 active（ensureNotEmpty 会载入另一个会话），运行中同样拒绝；
     * 删非 active 会话不碰 activeConversationId，在途事件不受影响，不拦。
     */
    fun deleteConversation(id: String) {
        if (id == _activeConversationId.value && isRunning.value) return
        conversationStore.delete(id)
        if (id == _activeConversationId.value) {
            loadConversation(conversationStore.ensureNotEmpty())
        } else {
            refreshConversations()
        }
    }

    /**
     * 重命名会话（抽屉长按菜单 → 对话框）。
     * 不需要 isRunning 守卫：只改标题、不切 activeConversationId、不动 _messages，
     * 在途事件仍按原 id 正常落盘（rename 同步更新该会话的 title 字段，无写串风险）。
     */
    fun renameConversation(id: String, title: String) {
        conversationStore.rename(id, title)
        refreshConversations()
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
