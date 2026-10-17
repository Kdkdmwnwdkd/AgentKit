package com.example.agentkit.workflow

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 工作流持久化（W-17）：`filesDir/workflows.json`，原子写 + `.bak` 回退（同 MemoryStore 纪律）。
 *
 * 全部 synchronized + runCatching：存储损坏最多「这次没读到」，绝不能让 App 崩。
 */
class WorkflowStore(private val dir: File) {

    private val file = File(dir, "workflows.json")
    private val bak = File(dir, "workflows.json.bak")
    private val tmp = File(dir, "workflows.json.tmp")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    init {
        runCatching { if (!dir.exists()) dir.mkdirs() }
    }

    fun list(): List<Workflow> = synchronized(lock) { load() }

    fun get(id: String): Workflow? = list().firstOrNull { it.id == id }

    /** 新建（id/创建时间由本方法生成）。 */
    fun create(
        name: String,
        prompt: String,
        trigger: WorkflowTrigger
    ): Workflow = synchronized(lock) {
        val now = System.currentTimeMillis()
        val wf = Workflow(
            id = UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { "未命名任务" }.take(60),
            prompt = prompt.trim().take(MAX_PROMPT_CHARS),
            trigger = trigger,
            enabled = true,
            createdAt = now
        )
        val all = load().toMutableList()
        all.add(wf)
        persist(all)
        wf
    }

    fun update(workflow: Workflow): Boolean = synchronized(lock) {
        val all = load().toMutableList()
        val idx = all.indexOfFirst { it.id == workflow.id }
        if (idx < 0) return@synchronized false
        all[idx] = workflow
        persist(all)
        true
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        val all = load().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return@synchronized false
        all[idx] = all[idx].copy(enabled = enabled)
        persist(all)
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val all = load().toMutableList()
        val removed = all.removeAll { it.id == id }
        if (removed) persist(all)
        removed
    }

    /** 记录一次执行结果（Worker / 前台执行完都调用）。 */
    fun markRun(id: String, at: Long, result: String?) {
        synchronized(lock) {
            val all = load().toMutableList()
            val idx = all.indexOfFirst { it.id == id }
            if (idx < 0) return
            all[idx] = all[idx].copy(lastRunAt = at, lastResult = result?.take(500))
            persist(all)
        }
    }

    /* ------------------------------ 内部 ------------------------------ */

    private fun load(): MutableList<Workflow> = runCatching {
        val text = when {
            file.exists() -> file.readText()
            bak.exists() -> bak.readText()
            else -> return mutableListOf()
        }
        json.decodeFromString<List<Workflow>>(text).toMutableList()
    }.getOrDefault(mutableListOf())

    private fun persist(all: List<Workflow>) {
        runCatching {
            val text = json.encodeToString(all)
            tmp.writeText(text)
            if (file.exists()) runCatching { file.copyTo(bak, overwrite = true) }
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX_PROMPT_CHARS = 2000
    }
}
