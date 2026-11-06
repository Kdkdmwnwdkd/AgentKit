package com.example.agentkit.ui.contract

import android.content.Context
import androidx.compose.runtime.Stable
import com.example.agentkit.automation.DeviceContext
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.ConversationSummary
import com.example.agentkit.core.capability.CapabilityResolution
import com.example.agentkit.memory.MemoryItem
import com.example.agentkit.ui.SkillUiInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统状态型页面的契约（权限 / 外观 / 会话列表 / 控制台）。
 *
 * 与 [ContentContracts] 同一套理由，见那里的说明。这一组的特点是**跨页复用**：
 * 权限四项被「权限总览」「权限引导」「就绪卡片」「控制台」四处同时读，所以拆成
 * [PermissionReadout]（只读三态）与 [PermissionStatusContract]（+ 动作与更多探测），
 * 就绪卡片只拿前者——它本来就只负责「提示还缺什么」，不该有刷新和跳设置的权力。
 */
/**
 * 背景图**路径**这一项只读值。
 *
 * 设置页只在「外观」里显示当前背景名，连透明度都不读——透明度是背景层每帧
 * 用来合成图层的，跟设置页无关。所以 uri 单独切出来给 [ChatBackgroundUriRef] 的
 * 消费者，alpha 留在 [ChatBackgroundRef] 里：一个接口只装它真正被读到的东西。
 * 写入留给 [ChatAppearanceContract]。
 */
@Stable
interface ChatBackgroundUriRef {
    val chatBackgroundUri: StateFlow<String?>
}

/** 背景层合成用的两项只读值：路径 + 透明度。 */
@Stable
interface ChatBackgroundRef : ChatBackgroundUriRef {
    val chatBackgroundAlpha: StateFlow<Float>
}

@Stable
interface ChatAppearanceContract : ChatBackgroundRef {
    fun setChatBackground(uri: String?)
    fun setChatBackgroundAlpha(alpha: Float)
}

/**
 * 权限的**只读**三态 + 一个「去开无障碍」动作。
 *
 * 单独立出来是给 [ReadinessCard] 用的：它出现在对话页顶部，职责是「告诉你还差什么」。
 * 给它完整权限接口就等于让一张提示卡能改自动化开关——没必要，也不该。
 */
@Stable
interface PermissionReadout {
    val notificationGranted: StateFlow<Boolean>
    val usageGranted: StateFlow<Boolean>
    val serviceConnected: StateFlow<Boolean>
    fun openAccessibilitySettings(ctx: Context)
}

/** 权限总览页 / 引导页：在只读三态之上加电池、Shizuku、能力链与刷新。 */
@Stable
interface PermissionStatusContract : PermissionReadout {
    val batteryUnrestricted: StateFlow<Boolean>
    val shizukuUiState: StateFlow<ShizukuShell.UiState>
    val capabilityPlans: StateFlow<List<CapabilityResolution>>
    fun refreshGrants(): Job
    fun refreshShizukuStatus()
    fun handleShizukuAction(context: Context)
    fun openBatteryOptimizationSettings(ctx: Context)
}

/**
 * 「全部对话」页：列表态 + 改名/删除/置顶/导出。
 *
 * 刻意**不含** 新建 / 切换 / 绑定工作区：这个页面用 [AppScaffold] 传进来的
 * `onNew` / `onOpen` 回调完成跳转，工作区绑定也在抽屉里由组装根直接调 ViewModel。
 * 把这四个动作塞进契约，等于让一张列表页在语法上获得了「切走整个会话」的权力，
 * 而它一次也没调用过——R-14 的守卫会把这种「没人用的成员」判红。
 */
@Stable
interface ConversationListContract {
    val conversations: StateFlow<List<ConversationSummary>>
    val activeConversationId: StateFlow<String?>
    val generatingConvId: StateFlow<String?>

    /** 导出为 Markdown 正文（分享用）。 */
    fun conversationMarkdown(convId: String): String
    fun deleteConversation(id: String)
    fun renameConversation(id: String, title: String)
    fun setConversationPinned(id: String, pinned: Boolean)
}

/**
 * 控制台首页。
 *
 * 它是全 App 唯一一个「什么都看一眼」的聚合页（设备上下文、自动化、权限、技能、记忆），
 * 所以成员天然跨域。这里没有再往下切：控制台的职责就是汇总，硬拆只会让调用方
 * 传五个参数却仍在一个函数里用。
 */
@Stable
interface ConsoleContract {
    val deviceContext: StateFlow<DeviceContext>
    val automationEnabled: StateFlow<Boolean>
    val memories: StateFlow<List<MemoryItem>>
    val skillInfos: StateFlow<List<SkillUiInfo>>
    val notificationGranted: StateFlow<Boolean>
    val usageGranted: StateFlow<Boolean>
    val serviceConnected: StateFlow<Boolean>
    fun setAutomationEnabled(v: Boolean)
    fun refreshGrants(): Job
    fun openAccessibilitySettings(ctx: Context)
}
