package com.example.agentkit.core.agent

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Agent 长任务的运行态持久化（B 批）。
 *
 * ## 要解决什么
 * Agent 跑长任务时，用户可能切后台、被系统回收、或手机重启。此时：
 * - 任务**确实中断**了（进程都没了，不可能自己续跑）；
 * - 但用户回来看到的应该是「上次在做什么、到哪一步了、怎么继续」，
 *   而不是一片空白 + 重新开始。
 *
 * 这正是对标 Operit / Coomi 的「会话可恢复」形态：**中断可以，但不能失忆**。
 *
 * ## 与 JobStore 的分工
 * - [com.example.agentkit.core.job.JobStore] 管的是**后台 shell 命令**进程，
 *   有真正的 Process 对象，重启后进程已死，只能对账成 interrupted。
 * - 本文件管的是**Agent 对话回合**（LLM 多步工具调用），它跑在 App 进程内的协程里，
 *   进程死了同样没了——但我们可以把「进行到第几步、上下文是什么」记下来。
 *
 * ## 落盘格式
 * `<filesDir>/agent_run_state.json` —— 单文件，原子写（tmp + rename）。
 */
@Serializable
data class AgentRunState(
    /** 会话 id（对应 ConversationStore 的会话）。 */
    val sessionId: String,
    /** 最后一次用户输入的摘要（用于恢复界面提示「上次你让我做…」）。 */
    val prompt: String = "",
    /** 当前进行到第几步工具调用。 */
    val stepIndex: Int = 0,
    /** 状态：running（进行中）/ interrupted（被系统中断）/ done（已完成）。 */
    val status: String = STATUS_RUNNING,
    /** 开始时间戳。 */
    val startedAt: Long = 0L,
    /** 最后更新时间戳。 */
    val updatedAt: Long = 0L,
    /** 已完成的工具调用摘要（工具名列表，够用户看懂进度）。 */
    val doneTools: List<String> = emptyList(),
    /** 恢复提示：被中断时给用户的一句话。 */
    val note: String = ""
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_DONE = "done"
    }
}

/**
 * 运行态存储：读写 [AgentRunState]。
 *
 * 纯文件操作、无 Android 依赖，可被 JVM harness 直接断言。
 */
class AgentRunStateStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        runCatching { file.parentFile?.mkdirs() }
    }

    /** 读当前状态；没有或损坏返回 null。 */
    fun load(): AgentRunState? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString<AgentRunState>(file.readText())
    }.getOrNull()

    /** 写状态（原子：先写 tmp 再 rename，避免进程被杀时留下半个文件）。 */
    fun save(state: AgentRunState) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(state))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    /** 清空（任务正常结束后调用）。 */
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * 对账：进程启动时调用。
     *
     * 若磁盘上仍是 running，说明上次进程**没有正常结束**（被系统杀了 / 崩溃了），
     * 标记为 interrupted 并补一句恢复提示。
     *
     * @return 需要提示用户的中断状态；无中断返回 null
     */
    fun reconcile(): AgentRunState? {
        val state = load() ?: return null
        if (state.status != AgentRunState.STATUS_RUNNING) return null
        val updated = state.copy(
            status = AgentRunState.STATUS_INTERRUPTED,
            updatedAt = System.currentTimeMillis(),
            note = buildInterruptNote(state)
        )
        save(updated)
        return updated
    }

    /** 生成恢复提示文案。 */
    private fun buildInterruptNote(state: AgentRunState): String {
        val prompt = state.prompt.take(40)
        val tools = state.doneTools.takeLast(3).joinToString("、")
        return buildString {
            append("上次任务「")
            append(prompt.ifBlank { "未命名任务" })
            append("」被系统中断")
            if (state.stepIndex > 0) append("（已进行到第 ${state.stepIndex} 步")
            if (tools.isNotBlank()) append("，完成过 $tools")
            if (state.stepIndex > 0) append("）")
            append("。对话上下文仍在，可以直接说「继续」。")
        }
    }
}
