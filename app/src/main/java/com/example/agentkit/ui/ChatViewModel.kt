package com.example.agentkit.ui

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import android.content.Intent
import android.content.ComponentName
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentkit.automation.AutomationBridge
import com.example.agentkit.automation.AutomationPrefs
import com.example.agentkit.automation.CaptureFlag
import com.example.agentkit.automation.DeviceForegroundAppTool
import com.example.agentkit.automation.AgentNotificationListener
import com.example.agentkit.automation.DeviceContext
import com.example.agentkit.automation.DeviceContextStore
import com.example.agentkit.automation.DeviceNotificationsTool
import com.example.agentkit.automation.ScreenshotBridge
import com.example.agentkit.automation.ScreenCaptureStartPolicy
import com.example.agentkit.automation.ScreenCaptureTrace
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
import com.example.agentkit.core.agent.ConnectivityNetworkRecoveryWaiter
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
import com.example.agentkit.core.TokenUsage
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
import com.example.agentkit.core.container.RootfsManager
import com.example.agentkit.core.capability.Capability
import com.example.agentkit.core.capability.CapabilityRequest
import com.example.agentkit.core.capability.CapabilityResolution
import com.example.agentkit.core.capability.logTrace
import com.example.agentkit.core.capability.resolveCapability
import com.example.agentkit.core.capability.shellCapabilitySnapshot
import com.example.agentkit.automation.screenCapabilitySnapshot
import com.example.agentkit.core.terminal.TerminalSessionPool
import com.example.agentkit.core.terminal.backend.TerminalBackendFactory
import com.example.agentkit.core.terminal.backend.createTerminalBackendFactory
import com.example.agentkit.tools.ClipboardTool
import com.example.agentkit.tools.HttpRequestTool
import com.example.agentkit.tools.NotificationTool
import com.example.agentkit.tools.ReadFileTool
import com.example.agentkit.tools.ShellCloseTool
import com.example.agentkit.tools.ShellRunTool
import com.example.agentkit.tools.WriteFileTool
import com.example.agentkit.tools.registerAppControlTools
import com.example.agentkit.tools.terminalShellCommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    /**
     * 用户气泡。
     * @param turnIndex 该提问所属轮次下标；重新发问（截断其后轮次）按它定位；< 0 表示未归属（实时上屏的草稿）。
     */
    data class User(val text: String, val turnIndex: Int = -1) : UiItem

    /**
     * 助手气泡。
     *
     * Phase 2 DoD②：额外携带「变体游标」元数据，供气泡旁切换器 `← 2/3 →` 消费。
     * 默认值保证旧构造点（只有 text）零改动即兼容——单变体、无切换器。
     *
     * @param variantIndex 当前选中变体下标（0-based）；< 0 表示非分叉消息（旧数据/中间助手）。
     * @param variantCount 该轮变体总数；<= 1 时不显示切换器。
     * @param turnIndex 该轮在会话 [com.example.agentkit.core.TurnRecord] 列表中的下标；
     *   重新生成 / 切换 / 重新发问都按它定位轮次；< 0 表示未归属任何轮。
     * @param usage 功能五（P1-5）：本条回答的 token 用量（一个回合多次 LLM 往返的合计；
     *   null = 服务端没给 usage，UI 不显示而非猜一个数）。
     */
    data class Assistant(
        val text: String,
        val variantIndex: Int = -1,
        val variantCount: Int = 1,
        val turnIndex: Int = -1,
        val usage: TokenUsage? = null
    ) : UiItem

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

/**
 * 供应商瞬时失败的「自动恢复」进行态（Phase 2 DoD③）。
 *
 * 由 [ChatViewModel.retryState] 暴露：非 null 时 UI 显示「正在恢复（n/N）· 原因」横幅，
 * 恢复成功 / 最终失败 / 用户取消时清回 null。**取消不算失败，但必须立即清状态**
 * （T05 不变量：点停止后不得残留「正在恢复」）。
 *
 * @param attempt 1-based 重试序号（第几次重试）。
 * @param max 最大重试次数（= providerRetry）。
 * @param reason 面向用户的可读原因（已脱敏，不含原始异常 message）。
 */
data class RetryState(
    val attempt: Int,
    val max: Int,
    val reason: String
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
/**
 * 功能五（P1-4）：待发文件——文件名 + 已提取的正文文本。
 *
 * 文本在读取时就抽好（读文件是 IO 重活），发送时只是字符串拼接；
 * UI 只负责展示文件名与移除，不碰解析细节。
 */
data class PendingFile(val name: String, val text: String)

/**
 * 功能五（P1-4）：文件附件处理结果——三条成功路径 + 一条失败路径。
 *
 * 之所以要区分 [Text] 与 [Images]：用户需要知道「这份 PDF 是被当图片读的」
 * （影响模型能否引用原文精确字句），而不是笼统一句「已添加」。
 */
sealed interface FileAttachResult {
    /** 文本类文件已直读（[chars] = 实际取用字符数）。 */
    data class Text(val name: String, val chars: Int) : FileAttachResult

    /** PDF 已渲染为 [pages] 张图片并入图片链路。 */
    data class Images(val name: String, val pages: Int) : FileAttachResult

    /** 不支持的类型 / 读取失败——[reason] 直接展示给用户。 */
    data class Failed(val reason: String) : FileAttachResult
}

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

    /**
     * 终端后端工厂（T07）：proot 优先、不可用回落本机 user。
     * 单独持有引用，供能力链（DoD⑤）复用其带缓存的可用性判断，避免重复实现降级顺序与探测。
     */
    private val terminalBackendFactory: TerminalBackendFactory =
        createTerminalBackendFactory(app) { RootfsManager.listAvailable(app).firstOrNull() }

    /**
     * 终端会话池（T08）：shell 工具的运行时底座。后端工厂 proot 优先、不可用回落本机 user；
     * 上层（shell 工具）只认 4-hex 会话句柄，不存在「是容器还是本机」的分支。
     */
    private val terminalPool = TerminalSessionPool(terminalBackendFactory)

    /**
     * 能力就绪度快照（DoD⑤）：逐条能力给出「选中的执行路径 + 降级轨迹 + 最终引导」。
     * 供权限中心页「一次走完」地告诉用户需要做什么，而不是让用户逐条工具试错。
     */
    private val _capabilityPlans = MutableStateFlow<List<CapabilityResolution>>(emptyList())
    val capabilityPlans: StateFlow<List<CapabilityResolution>> = _capabilityPlans.asStateFlow()

    /**
     * 重算全部能力的就绪度（进入权限页 / 权限或自动化状态变化时调用）。
     *
     * 纯函数 [resolveCapability] 只做「快照 → 裁决」，Android 侧探测（proot/rootfs 是否有、
     * 无障碍是否就绪）由 [shellCapabilitySnapshot] / [screenCapabilitySnapshot] 先行完成；
     * 裁决后把降级轨迹逐级落日志（可观测），**不静默**。
     */
    fun refreshCapabilityPlans() {
        _capabilityPlans.value = Capability.entries.map { capability ->
            val snapshot = when (capability) {
                Capability.RUN_SHELL -> terminalBackendFactory.shellCapabilitySnapshot()
                Capability.CONTROL_SCREEN -> screenCapabilitySnapshot()
            }
            resolveCapability(CapabilityRequest(capability, snapshot)).also { it.logTrace() }
        }
    }

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
        // 终端 shell（T08）：会话池 + 短句柄；shell_run 执行命令、shell_close 释放会话
        register(ShellRunTool(terminalPool))
        register(ShellCloseTool(terminalPool))
        // 应用操控（Phase 1 补口）：列出/启动/停止任意已安装应用——「叫它打开快手就能打开」。
        // app_launch 走 Intent（零权限）；app_stop 接**真实 shell 通道**（会话池适配，见下），
        // 通道不可用（池无可用后端）时仍走「诚实降级为需用户手动停止」，绝不静默假装成功。
        // apply 内传 this（不能传 registry 自身，会触发类型推断递归）
        registerAppControlTools(this, app, terminalShellCommandRunner(terminalPool))
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
        AutomationBridge.onConnectionChanged = { connected ->
            _serviceConnected.value = connected
            refreshCapabilityPlans() // 无障碍连接变化 → 能力就绪度随之刷新
        }
        // 屏幕捕获开关以「服务真实状态」为唯一真源：服务链路失败/停止 → 开关自动回落，
        // 杜绝「UI 显示捕获中、其实早就失败」的假状态（真机第七轮「开了之后关不了」）。
        ScreenshotBridge.onCapturingChanged = { capturing ->
            _capturing.value = capturing
        }
        refreshGrants()
        // L8/L9：首屏即有内容可看
        refreshLogs()
        refreshMemories()
    }

    /**
     * 资源生命周期收口（QA 定级的 Major：资源泄漏修复）。
     *
     * ## 为什么必须释放
     * [TerminalSessionPool] 持有一条条**真实子进程**（本机 `/system/bin/sh`、proot 会话）；
     * 子进程**不随 GC 回收**，只有显式关闭才释放。Activity `finish()` 清理 ViewModel 时若不收口，
     * 反复进出会累积泄漏 shell/proot 进程。
     *
     * ## 为什么用非挂起的 [TerminalSessionPool.releaseAllAsync]
     * [onCleared] 是**同步**生命周期钩子（主线程执行）：挂起版 `closeAll()` 需要协程 `await`，
     * 在这里 `runBlocking` 会卡主线程且关闭子进程可能耗时。`releaseAllAsync()` 做了「最小惊讶」
     * 取舍——**同步**清空会话表（旧句柄立即失效）+ **异步**关闭后端进程 + 取消输出收集协程，
     * 既非阻塞又达成防泄漏目标（详见该方法 KDoc）。
     *
     * ## 异常安全
     * `releaseAllAsync()` 内部已对空池 / 后端 `close()` 抛错做 `runCatching`；此处再包一层，
     * 保证生命周期回调绝不抛异常（回调抛错会拖垮进程收尾）。
     */
    override fun onCleared() {
        runCatching { terminalPool.releaseAllAsync() }
        super.onCleared()
    }

    /** 设置页：切换自动化总开关（同时持久化并同步到桥）。 */
    fun setAutomationEnabled(v: Boolean) {
        automationPrefs.enabled = v
        AutomationBridge.enabled = v
        _automationEnabled.value = v
        refreshCapabilityPlans() // 总开关变化 → 刷新「操作手机界面」能力就绪度
    }

    /** 设置页：跳转系统无障碍设置页，引导用户开启本应用的服务。 */
    fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * 屏幕捕获：缓存 MediaProjection 授权结果（来自系统授权回调）。失败 → lastError + ERROR 日志。
     *
     * **只缓存，不取 token**（第六轮修复）：Android 14 规则 A 要求 FGS 先以 mediaProjection
     * 类型进前台才允许 `getMediaProjection`，故 token 由服务在 onStartCommand 第一句进前台
     * 之后自取；桥只缓存授权 extras，随后 [startScreenCapture] 把它随 intent 传给服务。
     */
    fun setScreenCapturePermission(resultCode: Int, data: android.content.Intent?) {
        try {
            ScreenshotBridge.setPermission(resultCode, data)
            ScreenCaptureTrace.log(
                ScreenCaptureTrace.CACHE_PERMISSION,
                "resultCode=$resultCode data=${if (data == null) "null" else "ok"}"
            )
        } catch (t: Throwable) {
            // 缓存授权结果本身不该失败，但厂商 ROM 可能在此抛——吞掉并上报，绝不逃逸到主线程。
            ScreenshotBridge.reportFailure(
                "缓存屏幕捕获授权失败：${ScreenCaptureStartPolicy.describe(t)}"
            )
            AgentLogger.e("ScreenCapture", "setScreenCapturePermission 异常", t)
        }
    }

    /**
     * 屏幕捕获：启动前台服务开始截屏（需先 setScreenCapturePermission）。
     *
     * **语义修正（真机第四轮 A3）**：仅当 [ScreenshotBridge.startService] 返回 true 才把
     * `_capturing` 置 true——失败时保持 false 并在 UI 可见，避免「显示捕获中但服务其实没起来」。
     */
    fun startScreenCapture(ctx: Context) {
        try {
            val ok = ScreenshotBridge.startService(ctx)
            val decision = CaptureFlag.decide(ok, ScreenshotBridge.lastError)
            _capturing.value = decision.capturing
            if (!ok) {
                AgentLogger.e("ScreenCapture", "屏幕捕获启动失败：${decision.errorMessage}")
            }
            ScreenCaptureTrace.log(
                ScreenCaptureTrace.START_SERVICE,
                "startService=$ok lastError=${ScreenshotBridge.lastError}"
            )
        } catch (t: Throwable) {
            ScreenshotBridge.reportFailure(ScreenCaptureStartPolicy.describe(t))
            _capturing.value = false
            AgentLogger.e("ScreenCapture", "startScreenCapture 异常", t)
        }
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
        // DoD⑤：权限/环境变化后重算能力就绪度（能力链一次走完），并逐级落降级轨迹日志
        refreshCapabilityPlans()
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
     * Phase 2 DoD②：改由 [ConversationRecord.toUiItems] 逐轮渲染（带变体游标元数据），
     * 使变体切换后助手气泡能正确反映当前选中变体。
     * 声明序保证 [conversationStore] / [_activeConversationId] 已就绪。
     */
    private val _messages = MutableStateFlow(
        conversationStore.load(_activeConversationId.value)?.toUiItems() ?: emptyList()
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
        // Phase 2 DoD③/T05：取消立即清「正在恢复」——不等协程收到取消再清，UI 即刻复原
        _retryState.value = null
    }

    // —— Phase 2 DoD②：消息分支（重新生成 / 左右切换 / 从中间轮重新发问）——

    /**
     * 重新生成**最后一轮**的助手回答：走 T01 流式路径（[com.example.agentkit.core.LLMClient.streamChat]，
     * 经 [AgentLoop] 的 onTextDelta 打字机上屏到 [_streamDraft]），完成后**追加**一个新变体并选中
     * （旧变体保留、可左右切回）——覆盖 DoD②「重新生成 3 次可左右切换」。
     *
     * 运行中忽略（UI 禁用只是双保险）。持久化经 [ConversationStore.appendVariant]，杀进程后仍在。
     */
    fun regenerate() {
        if (isRunning.value) return
        val rec = conversationStore.load(_activeConversationId.value) ?: return
        val turns = rec.turns
        val turnIndex = turns.lastIndex
        if (turnIndex < 0) return
        val userText = turns[turnIndex].user.content
        if (userText.isBlank()) return

        isRunning.value = true
        _streamDraft.value = ""
        AgentLogger.i("Chat", "重新生成：第 ${turnIndex + 1} 轮")
        runJob = viewModelScope.launch {
            try {
                val outcome = runCatching { loop.run(userText) }
                // 流式草稿使命结束：最终回答以整条变体落库，先清草稿避免与整条同时显示
                _streamDraft.value = null
                outcome.onSuccess { history ->
                    // 过程事件已实时渲染；未渲染的工具卡兜底补上（与 send 同策略）
                    history.forEach { msg ->
                        if (msg is ToolMessage) {
                            val shown = _messages.value.any { it is UiItem.Tool && it.call.id == msg.id }
                            if (!shown) append(msg)
                        }
                    }
                    val final = history.filterIsInstance<AssistantMessage>()
                        .lastOrNull()?.content.orEmpty()
                    // 功能五（P1-5）：新变体带本回合全部 LLM 往返的合计用量
                    val variant = UiItem.Assistant(
                        text = if (final.isBlank()) "（模型未返回内容）" else final,
                        usage = TokenUsage.sum(
                            history.filterIsInstance<AssistantMessage>().map { it.usage }
                        )
                    )
                    conversationStore.appendVariant(
                        _activeConversationId.value, turnIndex, variant.toMsgRecord()
                    )
                    reloadActiveMessages()
                    refreshConversations()
                }
                outcome.onFailure {
                    if (it is CancellationException) {
                        // 用户主动停止：不算失败，也不追加空变体（草稿已清）
                        AgentLogger.i("Chat", "重新生成已停止")
                    } else {
                        AgentLogger.e("Chat", "重新生成失败", it)
                        val err = UiItem.Assistant(friendlyMessage(it))
                        conversationStore.appendVariant(
                            _activeConversationId.value, turnIndex, err.toMsgRecord()
                        )
                        reloadActiveMessages()
                        refreshConversations()
                    }
                }
            } finally {
                runJob = null
                isRunning.value = false
                _streamDraft.value = null
                // Phase 2 DoD③：兜底清「正在恢复」（与 send 同一保险）
                _retryState.value = null
                refreshLogs()
            }
        }
    }

    /**
     * 左右切换第 [turnIndex] 轮的选中变体（[delta] = -1 左 / +1 右）。
     * 委托 [ConversationStore.selectVariant]（边界 clamp：首个左移/末个右移原地不动）；
     * 切换后重建 [_messages]，保证 UI 与「下一次请求的历史」都用选中变体。运行中忽略。
     */
    fun switchVariant(turnIndex: Int, delta: Int) {
        if (isRunning.value) return
        conversationStore.selectVariant(_activeConversationId.value, turnIndex, delta)
        reloadActiveMessages()
        refreshConversations()
    }

    /**
     * 从第 [turnIndex] 轮重新发问（UI：用户气泡长按 → 编辑对话框）。
     * 先截断该轮**之后**的所有轮次（[ConversationStore.truncateAfter]，保留该轮），再把新问题
     * 填入输入框走 [send]（与正常发问同一路径，新开一轮）——轮次下标天然连续。运行中忽略。
     */
    fun reaskFromTurn(turnIndex: Int, text: String) {
        if (isRunning.value) return
        val t = text.trim()
        if (t.isBlank()) return
        conversationStore.truncateAfter(_activeConversationId.value, turnIndex)
        reloadActiveMessages()
        refreshConversations()
        input.value = t
        send()
    }

    /**
     * 功能五（P1-1）：从指定轮起删除（含该轮）——「清理从这里开始的内容」。
     *
     * 为什么不提供「只删这一条」：助手消息可能携带 tool_calls，删单条会让
     * 后续 tool 结果成为孤儿（协议层直接 400）。截断语义既安全，又正好命中
     * 用户真实需求（试错几轮后把尾巴一次性清掉重来）。
     *
     * [turnIndex] = 0 表示清空全部轮次（保留会话壳，标题不动）。
     */
    fun deleteFromTurn(turnIndex: Int) {
        if (isRunning.value) return
        conversationStore.truncateAfter(_activeConversationId.value, turnIndex - 1)
        reloadActiveMessages()
        refreshConversations()
        AgentLogger.i("Chat", "已从第 ${turnIndex + 1} 轮起删除")
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
        onEvent = ::handleAgentEvent,
        // Phase 2 DoD②：T01 流式文本增量 → 打字机草稿气泡（重新生成的新变体流式填入）
        onTextDelta = ::handleTextDelta,
        // 真机第三轮 Bug A2：退避期间监听网络恢复——关掉飞行模式后「秒续」而不是干等满额
        networkRecovery = ConnectivityNetworkRecoveryWaiter(app)
    )

    /**
     * T01 流式文本增量 → [_streamDraft] 草稿（打字机）。TextDelta 不进历史，
     * 仅用于「正在生成」的实时气泡；[send] / [regenerate] 结束时清空并落库最终整条。
     * 回调来自 LLM 收集协程，单独用 [streamDraftLock] 串行化（与 [msgLock] 解耦）。
     */
    private val streamDraftLock = Any()

    private fun handleTextDelta(delta: String) = synchronized(streamDraftLock) {
        _streamDraft.value = (_streamDraft.value ?: "") + delta
    }

    /** 流式草稿文本：非 null 时 UI 在消息末尾渲染一个「正在生成」的助手气泡（尚未落库）。 */
    private val _streamDraft = MutableStateFlow<String?>(null)
    val streamDraft: StateFlow<String?> = _streamDraft.asStateFlow()

    /**
     * 功能五（P1-5）：当前会话累计 token 用量（对所有助手气泡的 usage 求和）。
     * 切会话时随 [_messages] 重载自动切换；UI 在对话页顶部常驻显示。
     */
    val sessionUsage: StateFlow<TokenUsage> = _messages
        .map { items ->
            TokenUsage.sum(items.filterIsInstance<UiItem.Assistant>().mapNotNull { it.usage })
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TokenUsage.EMPTY)

    /**
     * 供应商瞬时失败的「自动恢复」进行态（Phase 2 DoD③）：非 null 时 UI 显示
     * 「正在恢复（n/N）· 原因」横幅。由 [handleAgentEvent] 的 RetryStarted/RetryFinished
     * 驱动置位/清除；[send] / [regenerate] / [stop] 收尾处再兜底清空，杜绝残留。
     */
    private val _retryState = MutableStateFlow<RetryState?>(null)
    val retryState: StateFlow<RetryState?> = _retryState.asStateFlow()

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

            // Phase 2 DoD③：准备重试 → 置位「正在恢复」横幅（U 显示 n/N + 原因）
            is AgentEvent.RetryStarted -> {
                _retryState.value = RetryState(ev.attempt, ev.max, ev.reason)
            }

            // Phase 2 DoD③：重试收尾（成功 / 最终失败 / 取消）→ 清除横幅
            is AgentEvent.RetryFinished -> {
                _retryState.value = null
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

    /**
     * 功能五（P1-6）：收藏的模型名（对标 RikkaHub `favoriteModels`）。
     *
     * 收藏区恒在列表最前，跨端点共用——目的是让「每天用的那两三个模型」
     * 不用每次在几十个列表项里翻。
     */
    private val _favoriteModels = MutableStateFlow(configStore.loadFavoriteModels())
    val favoriteModels: StateFlow<List<String>> = _favoriteModels.asStateFlow()

    /** 功能五（P1-6）：收藏 / 取消收藏（即时落盘，重启不丢）。 */
    fun toggleFavoriteModel(name: String) {
        val cur = _favoriteModels.value
        val next = if (name in cur) cur - name else cur + name
        _favoriteModels.value = next
        configStore.saveFavoriteModels(next)
    }

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

    /**
     * 功能四（P0-2）：等待发送的图片（data URL 列表）。
     *
     * 选图在 UI 线程拿 Uri，转 base64 是重活——放 IO 线程由 [attachImageFromUri] 处理，
     * 完成后统一落到这里，发送时一次性带走。UI 只读这个流渲染缩略图，不碰编码细节。
     */
    private val _pendingImages = MutableStateFlow<List<String>>(emptyList())
    val pendingImages: StateFlow<List<String>> = _pendingImages.asStateFlow()

    /**
     * 功能四（P0-2）：把相册选中的图片读成 data URL 挂到待发列表。
     *
     * 压缩到长边 ≤ [MAX_EDGE]、JPEG 质量 80——1080×2214 原始截图 base64 约好几 MB，
     * 直接塞进请求会超上下文且慢。失败（权限撤销/文件被删）返回 false，由调用方提示。
     */
    suspend fun attachImageFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = app.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@runCatching false
            val scaled = scaleDown(bitmap)
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            _pendingImages.value = _pendingImages.value + "data:image/jpeg;base64,$encoded"
            true
        }.getOrDefault(false)
    }

    /** 功能四（P0-2）：附件图片长边上限——兼顾视觉模型识别效果与请求体积。 */
    private companion object {
        const val MAX_EDGE = 1024

        /** 功能五（P1-4）：PDF 最多渲染页数（首页几页通常已够模型读懂主旨）。 */
        const val MAX_PDF_PAGES = 5

        /** 功能五（P1-4）：单个文本附件最多取用的字符数（防超长文件撑爆上下文）。 */
        const val MAX_FILE_CHARS = 60_000

        /** 功能五（P1-4）：按扩展名识别的文本类文件（直读文本路径）。 */
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm",
            "yaml", "yml", "ini", "conf", "cfg", "properties", "toml", "sql",
            "kt", "java", "py", "js", "ts", "sh", "gradle", "c", "cpp", "h", "rs", "go",
            "rb", "php", "bat"
        )
    }

    /** 功能四（P0-2）：长边超过 [MAX_EDGE] 等比缩小（两阶段解码在调用方已完成）。 */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / max
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true
        )
    }

    /** 功能四（P0-2）：移除待发图片。 */
    fun removePendingImage(url: String) {
        _pendingImages.value = _pendingImages.value.filter { it != url }
    }

    /**
     * 功能五（P1-4）：待发**文件**——「＋」里除了图片还能发文档。
     *
     * 三种处理路径（不新增任何依赖，全走系统 API）：
     *  ① 文本类（txt/md/json/csv/代码…）→ 直读文本，发送时作为附件块拼进消息；
     *  ② PDF → `PdfRenderer` 渲染前 ≤[MAX_PDF_PAGES] 页为图片，并入图片链路（视觉得看）；
     *  ③ DOCX → 解 zip 抽 `word/document.xml` 里的 `<w:t>` 文本（纯系统 zip + 正则）。
     * 其它二进制格式明确拒绝并给出可读原因——**不做「静默忽略」**，
     * 用户点了「＋」却什么都没发生是最差的反馈。
     */
    private val _pendingFiles = MutableStateFlow<List<PendingFile>>(emptyList())
    val pendingFiles: StateFlow<List<PendingFile>> = _pendingFiles.asStateFlow()

    /** 功能五（P1-4）：读取文件并按其类型挂到待发清单；返回精确结果供 UI 反馈。 */
    suspend fun attachFileFromUri(uri: Uri): FileAttachResult = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri) ?: "未命名文件"
            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                // ② PDF：渲染成图片走多模态（视觉模型读页面）
                ext == "pdf" -> {
                    val images = renderPdfToImages(uri, MAX_PDF_PAGES)
                    if (images.isEmpty()) {
                        FileAttachResult.Failed("PDF 打开失败或没有可渲染页面")
                    } else {
                        _pendingImages.value = _pendingImages.value + images
                        FileAttachResult.Images(name, images.size)
                    }
                }

                // ③ DOCX：解包抽文本
                ext == "docx" -> {
                    val text = extractDocxText(uri)
                    if (text.isBlank()) {
                        FileAttachResult.Failed("DOCX 里没有提取到文字（可能是纯图片文档）")
                    } else {
                        _pendingFiles.value = _pendingFiles.value + PendingFile(name, text.take(MAX_FILE_CHARS))
                        FileAttachResult.Text(name, text.length)
                    }
                }

                // ① 文本类：直读
                ext in TEXT_EXTENSIONS -> {
                    val text = readTextFile(uri)?.take(MAX_FILE_CHARS)
                    if (text.isNullOrBlank()) {
                        FileAttachResult.Failed("文件为空或无法读取")
                    } else {
                        _pendingFiles.value = _pendingFiles.value + PendingFile(name, text)
                        FileAttachResult.Text(name, text.length)
                    }
                }

                // 其它：明确拒绝（含建议），不静默吞掉
                else -> FileAttachResult.Failed(
                    "暂不支持 .$ext 文件；当前可发：图片、PDF、DOCX、" +
                        "以及 txt/md/json/csv 等文本类文件"
                )
            }
        }.getOrElse { FileAttachResult.Failed("读取失败：${it.message ?: "未知错误"}") }
    }

    /** 功能五（P1-4）：移除待发文件。 */
    fun removePendingFile(name: String) {
        _pendingFiles.value = _pendingFiles.value.filterNot { it.name == name }
    }

    /** ContentResolver 查显示名（拿不到就回落 null，由调用方兜底命名）。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 读文本文件（UTF-8；二进制误判为文本时由上层长度/空白判定兜底）。 */
    private fun readTextFile(uri: Uri): String? = runCatching {
        app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /**
     * PDF → 图片 data URL（前 [maxPages] 页，长边 ≤1024 与图片附件同一规格）。
     * 渲染前铺白底：PDF 页面本身透明，不铺白会得到黑底图（视觉模型也读不清）。
     */
    private fun renderPdfToImages(uri: Uri, maxPages: Int): List<String> {
        val images = mutableListOf<String>()
        runCatching {
            val fd = app.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            fd.use { parcel ->
                android.graphics.pdf.PdfRenderer(parcel).use { renderer ->
                    val pages = minOf(renderer.pageCount, maxPages)
                    for (i in 0 until pages) {
                        renderer.openPage(i).use { page ->
                            val scale = MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                            val w = (page.width * scale).toInt().coerceIn(1, MAX_EDGE)
                            val h = (page.height * scale).toInt().coerceIn(1, MAX_EDGE)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(0xFFFFFFFF.toInt())
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val bytes = ByteArrayOutputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                out.toByteArray()
                            }
                            images += "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                    }
                }
            }
        }
        return images
    }

    /**
     * DOCX → 文本：`word/document.xml` 里逐个 `<w:t>` 取文字，`</w:p>` 视为换行，
     * 最后解 XML 实体（&amp; 等）。纯系统 `ZipInputStream` + 正则，不引第三方库。
     */
    private fun extractDocxText(uri: Uri): String {
        val sb = StringBuilder()
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val xml = zip.readBytes().toString(Charsets.UTF_8)
                            val normalized = xml.replace("</w:p>", "\n")
                            Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                                .findAll(normalized)
                                .forEach { sb.append(decodeXmlEntities(it.groupValues[1])) }
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        return sb.toString()
    }

    /** 最小 XML 实体解码（DOCX 文本里常见五种）。 */
    private fun decodeXmlEntities(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    fun send() {
        val text = input.value.trim()
        if (text.isBlank() || isRunning.value) return
        // 功能四（P0-2）/ 功能五（P1-4）：先摘走待发附件并清空，避免本轮发出后仍留在输入区
        val images = _pendingImages.value
        _pendingImages.value = emptyList()
        val files = _pendingFiles.value
        _pendingFiles.value = emptyList()
        input.value = ""
        isRunning.value = true
        _streamDraft.value = null
        AgentLogger.i(
            "Chat",
            "用户发送：${text.take(120)}" +
                (if (images.isNotEmpty()) "（含 ${images.size} 张图片）" else "") +
                (if (files.isNotEmpty()) "（含 ${files.size} 个文件）" else "")
        )

        // L4 主动感知：把当前设备上下文自动拼进用户输入，让模型“知道”手机在干什么
        val ctxPrompt = DeviceContextStore.snapshot().toPrompt()
        // 功能五（P1-4）：文件正文以带文件名的附件块拼在「当前请求」之前——
        // 模型先看到材料、再看问题，符合人的阅读顺序；文件名保留，
        // 模型回答时能引用「在 xx.txt 里...」
        val fileBlock = files.joinToString("\n\n") { "[附件：${it.name}]\n${it.text}" }
        val prefix = listOfNotNull(
            ctxPrompt.takeIf { it.isNotBlank() },
            fileBlock.takeIf { it.isNotBlank() }
        ).joinToString("\n\n")
        val fullInput = if (prefix.isBlank()) text else "$prefix\n\n用户当前请求：$text"

        // T05：句柄置入 runJob，供 stop() 取消；finally 保证 isRunning/日志必收
        runJob = viewModelScope.launch {
            try {
                // Hotfix2：用户消息立即上屏；工具调用过程由 handleAgentEvent 实时渲染
                // （执行中卡 → 终态卡）；最终历史只补文本回答，工具卡按 call.id 去重
                val userItem = UiItem.User(text)
                _messages.value = _messages.value + userItem
                appendToStore(userItem) // 功能三：聊天记录同步落盘
                // 先拿到 Result，再在同一个挂起体里做后续 IO：Result.onSuccess 的 lambda 不是 suspend
                val outcome = runCatching { loop.run(fullInput, images) }
                // 流式草稿使命结束：最终回答以整条消息落库（防草稿与整条同时显示）
                _streamDraft.value = null
                outcome.onSuccess { history ->
                    // 功能五（P1-5）：一个回合可能多次 LLM 往返（每次工具调用后都要再问一次模型），
                    // 每条 AssistantMessage 各带自己那次的 usage——合计后挂到最终回答气泡上，
                    // 中间轮（只有 toolCalls）不出气泡，其用量不至于丢
                    val turnUsage = TokenUsage.sum(
                        history.filterIsInstance<AssistantMessage>().map { it.usage }
                    )
                    val lastAnswer = history.filterIsInstance<AssistantMessage>()
                        .lastOrNull { it.content.isNotBlank() }
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
                            is AssistantMessage -> if (msg.content.isNotBlank()) {
                                append(msg, if (msg === lastAnswer) turnUsage else null)
                            }
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
                _streamDraft.value = null
                // Phase 2 DoD③：兜底清「正在恢复」——重试收尾事件之外的最后一道保险
                _retryState.value = null
                // Phase 2 DoD②：用轮次变体结构重建 _messages——补上助手气泡的变体游标元数据
                // （首条回答变体数=1，切换器不显示；重新生成后才会 >1）
                reloadActiveMessages()
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

    private fun append(msg: ChatMessage, usage: TokenUsage? = null) {
        val item = when (msg) {
            is UserMessage -> UiItem.User(msg.content)
            // 功能五（P1-5）：usage 仅在 send() 汇总整轮后传给最终回答（中间轮不传）
            is AssistantMessage -> UiItem.Assistant(msg.content, usage = usage)
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
        _messages.value = conversationStore.load(id)?.toUiItems() ?: emptyList()
        refreshConversations()
    }

    /** 用当前会话的轮次变体结构重建 [_messages]（变体切换/重新生成/重新发问后调用）。 */
    private fun reloadActiveMessages() {
        _messages.value = conversationStore.load(_activeConversationId.value)?.toUiItems() ?: emptyList()
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

    /**
     * 功能五（P1-3）：置顶 / 取消置顶会话（抽屉长按菜单）。
     *
     * 与 rename 一样不需要 isRunning 守卫：只改排序键，不切 activeConversationId、
     * 不动 _messages，在途事件仍按原 id 落盘。
     */
    fun setConversationPinned(id: String, pinned: Boolean) {
        conversationStore.setPinned(id, pinned)
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
