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
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.AssistantMessage
import com.example.agentkit.core.ChatMessage
import com.example.agentkit.core.LlmHttpException
import com.example.agentkit.core.LLMConfig
import com.example.agentkit.core.ProviderManager
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolMessage
import com.example.agentkit.core.ToolApproval
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.UserMessage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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

    private val _messages = MutableStateFlow<List<UiItem>>(emptyList())
    val messages: StateFlow<List<UiItem>> = _messages.asStateFlow()

    /** 模型配置持久化：进程重启也能还原，避免「明明填过却退回默认值」的不一致。 */
    private val configStore = ConfigStore(app)

    /** 当前模型配置（已落盘，UI 直接消费）；初始化即从 ConfigStore 读取，而不是凭空默认值。 */
    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<ModelConfig> = _config.asStateFlow()

    /** 「测试连接」结果流：配置页卡片消费，null 表示尚未测试。 */
    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    /** 是否正在测试连接：UI 用它给按钮显示加载态，避免重复点击。 */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    val input = MutableStateFlow("")
    val isRunning = MutableStateFlow(false)

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
        memoryProvider = { memoryStore.summaryForPrompt(12, 1200) }
    )

    /**
     * 更新模型配置：先过 [ConfigSanitizer] 清洗（剔除不可见字符/规范化 URL），
     * 再落盘 + 更新内存状态流，保证「保存即生效、重启不丢失」。
     */
    fun updateConfig(cfg: ModelConfig) {
        val clean = ConfigSanitizer.sanitized(cfg)
        configStore.save(clean)
        _config.value = clean
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
                // 气泡面向用户：技术异常在此映射成可读文案（完整堆栈已在日志里落盘）
                _messages.value = _messages.value + UiItem.Assistant(friendlyMessage(it))
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
