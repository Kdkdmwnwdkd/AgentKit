package com.example.agentkit.core.workspace

import android.content.Context
import com.example.agentkit.core.AgentLogger
import java.io.File

/**
 * 检查点（快照）存储：把整个 workspace 目录快照到 `<filesDir>/checkpoints/<id>/`，
 * 支持随时恢复到任意检查点。语义等价「git 检查点」，但用目录级快照实现——
 * 零第三方依赖、离线安全、Android 全兼容（[File.copyRecursively]）。
 *
 * 检查点 id = `<epochMs>_<label>`（label 经安全化）。meta 记录在 `checkpoints/<id>.meta`。
 */
object CheckpointStore {

    private const val TAG = "CheckpointStore"

    data class Checkpoint(
        val id: String,
        val label: String,
        val timeMs: Long,
        val fileCount: Int,
        val sizeBytes: Long
    )

    private fun root(context: Context): File =
        File(context.filesDir, "checkpoints").apply { runCatching { mkdirs() } }

    /** 创建检查点：快照当前 workspace。 */
    fun create(context: Context, label: String): Checkpoint? {
        val ws = WorkspaceStore.dir(context)
        val files = WorkspaceStore.list(context)
        val size = files.sumOf { it.length() }
        val ts = System.currentTimeMillis()
        val safeLabel = label.ifBlank { "checkpoint" }
            .filter { it.isLetterOrDigit() || it in "._- " }.take(40).trim().ifBlank { "checkpoint" }
        val id = "${ts}_$safeLabel"
        val snapDir = File(root(context), id)
        val ok = runCatching {
            // 逐个复制文件，避免把空目录/异常也带进去；保持结构可预期
            files.forEach { src ->
                src.copyTo(File(snapDir, src.name), overwrite = true)
            }
            // 写入 meta
            File(root(context), "$id.meta").writeText(
                "label=$safeLabel\ntimeMs=$ts\nfileCount=${files.size}\nsizeBytes=$size\n"
            )
        }.onFailure { AgentLogger.e(TAG, "创建检查点失败：${it.message}") }.isSuccess
        if (!ok) return null
        return Checkpoint(id, safeLabel, ts, files.size, size)
    }

    /** 列出所有检查点（按时间倒序）。 */
    fun list(context: Context): List<Checkpoint> {
        val r = root(context)
        val metas = r.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { mf ->
            runCatching {
                val id = mf.name.removeSuffix(".meta")
                val props = mf.readLines()
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0] to it[1] }
                Checkpoint(
                    id = id,
                    label = props["label"] ?: id,
                    timeMs = props["timeMs"]?.toLongOrNull() ?: 0L,
                    fileCount = props["fileCount"]?.toIntOrNull() ?: 0,
                    sizeBytes = props["sizeBytes"]?.toLongOrNull() ?: 0L
                )
            }.getOrNull()
        }.sortedByDescending { it.timeMs }
    }

    /** 恢复检查点：清空当前 workspace 后写回快照文件。 */
    fun restore(context: Context, id: String): Boolean {
        val snapDir = File(root(context), id)
        if (!snapDir.isDirectory) {
            AgentLogger.e(TAG, "检查点不存在：$id")
            return false
        }
        return runCatching {
            val ws = WorkspaceStore.dir(context)
            // 清空 workspace 现有文件
            ws.listFiles()?.forEach { runCatching { if (it.isFile) it.delete() } }
            // 写回快照
            snapDir.listFiles()?.filter { it.isFile }?.forEach { src ->
                src.copyTo(File(ws, src.name), overwrite = true)
            }
        }.onFailure { AgentLogger.e(TAG, "恢复检查点失败：${it.message}") }.isSuccess
    }

    /** 删除检查点。 */
    fun delete(context: Context, id: String): Boolean =
        runCatching {
            File(root(context), id).deleteRecursively()
            File(root(context), "$id.meta").delete()
        }.getOrDefault(false)

    /** 人类可读时间。 */
    fun formatTime(timeMs: Long): String {
        if (timeMs <= 0L) return "—"
        val df = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        return df.format(java.util.Date(timeMs))
    }
}
