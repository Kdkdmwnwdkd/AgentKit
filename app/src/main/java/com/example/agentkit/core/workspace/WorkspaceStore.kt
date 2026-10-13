package com.example.agentkit.core.workspace

import android.content.Context
import com.example.agentkit.core.AgentLogger
import java.io.File

/**
 * 工作区文件存储：Agent 的可读写工作目录 `<filesDir>/workspace`。
 *
 * 这里放编辑器打开/保存的文本文件，也是 [CheckpointStore] 快照的目标。
 * 全部按「文件名（不含路径）」操作，杜绝 `../` 目录穿越（[safeName] 兜底）。
 */
object WorkspaceStore {

    private const val TAG = "WorkspaceStore"

    fun dir(context: Context): File =
        File(context.filesDir, "workspace").apply { runCatching { mkdirs() } }

    /** 工作区内文件列表（仅顶层文件，按名称排序）。 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun exists(context: Context, name: String): Boolean {
        val n = safeName(name) ?: return false
        return File(dir(context), n).isFile
    }

    /** 读取文件文本（UTF-8）；不存在返回 null。 */
    fun read(context: Context, name: String): String? {
        val n = safeName(name) ?: return null
        val f = File(dir(context), n)
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }
            .onFailure { AgentLogger.e(TAG, "读取 $n 失败：${it.message}") }
            .getOrNull()
    }

    /** 写入文件（覆盖），返回是否成功。 */
    fun write(context: Context, name: String, content: String): Boolean {
        val n = safeName(name) ?: return false
        val f = File(dir(context), n)
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }.onFailure { AgentLogger.e(TAG, "写入 $n 失败：${it.message}") }.isSuccess
    }

    /** 删除文件；不存在视为成功（幂等）。 */
    fun delete(context: Context, name: String): Boolean {
        val n = safeName(name) ?: return false
        return runCatching { File(dir(context), n).takeIf { it.isFile }?.delete() ?: true }
            .getOrDefault(false)
    }

    /** 新建（若已存在返回 false）。 */
    fun create(context: Context, name: String): Boolean {
        val n = safeName(name) ?: return false
        if (exists(context, n)) return false
        return write(context, n, "")
    }

    /**
     * 名称安全化：只允许单层文件名（字母/数字/中文/点/下划线/连字符/空格），
     * 拒绝路径分隔符与 `..`，防止穿越出 workspace。非法返回 null。
     */
    fun safeName(name: String): String? {
        val t = name.trim()
        if (t.isEmpty() || t.length > 120) return null
        if (t == "." || t == "..") return null
        if (t.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) return null
        // 过滤隐藏名与控制序列，保留常见可打印字符（含中文、点、连字符、空格）
        if (!t.all { it.isLetterOrDigit() || it in "._- ()[]#+=" }) return null
        return t
    }
}
