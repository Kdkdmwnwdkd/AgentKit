package com.example.agentkit.core.workspace

import java.io.File

/**
 * 单文件版本备份（B 批）：Agent 每次写文件前，自动把**将要被覆盖的那一个文件**的旧内容留一份。
 *
 * ## 为什么不用 CheckpointStore
 * [CheckpointStore] 是整目录快照——语义是「我手动点了个检查点」，用于人工回滚。
 * 但 Agent 的写路径是自动的、连续的、高频的：`write_file` / `edit_file` 可能一秒钟
 * 调用十几次。整目录快照每次都要复制整个工作区，开销和磁盘占用都不可接受。
 *
 * 所以这里做**粒度相反**的版本：只备份「本次要动的那个文件」。
 * - 写新文件 → 记录「此前不存在」，回滚即删除该文件；
 * - 改旧文件 → 记录旧内容，回滚即写回旧内容。
 *
 * 这正是 AiCode / Coomi 那种「编辑自动 checkpoint」的产品形态：**每次改动都有一个
 * 可撤销的落点**，而不是让用户自己记得手动拍快照。
 *
 * ## 目录布局
 * ```
 * <filesDir>/file_backups/
 *   ├── <id>.meta            # id / relPath / 时间 / 是否原本存在 / 字节数
 *   └── <id>/                # 旧内容副本（文件名与 relPath 一致，保留子目录结构）
 * ```
 *
 * 纯文件操作、无 Android 依赖，可被 JVM harness 直接断言。
 */
class FileBackupStore(
    private val root: File,
    private val workspace: File,
    /** 错误回调（默认吞掉——备份是兜底，失败不该影响写入）。 */
    private val onError: (String) -> Unit = {}
) {

    /** 一次备份的元信息。 */
    data class Backup(
        val id: String,
        val relPath: String,
        val timeMs: Long,
        val existedBefore: Boolean,
        val sizeBytes: Long
    )

    init {
        runCatching { root.mkdirs() }
    }

    /**
     * 在写入 [relPath] 之前调用：备份该文件的当前内容。
     *
     * 幂等性：同一文件连续被写多次会产生多条备份（历史链条完整）。
     * 回滚时按时间倒序取最近一条即可回到「上一步」的状态。
     *
     * @param relPath 相对工作区的路径（与 write_file / edit_file 的 path 参数同源）
     * @return 新建的备份 id；失败返回 null（**不阻断写入**——备份是兜底，不是前置条件）
     */
    fun backupBeforeWrite(relPath: String): String? = runCatching {
        val safeRel = sanitizeRel(relPath) ?: return null
        val target = File(workspace, safeRel)
        // 目标逃出工作区 → 不备份（也不该发生，resolve() 已经拦过）
        if (!target.canonicalPath.startsWith(workspace.canonicalPath + File.separator)) return null

        val ts = System.currentTimeMillis()
        val id = "${ts}_${safeRel.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}"
        val existed = target.isFile
        val size = if (existed) target.length() else 0L

        if (existed) {
            // 旧内容存进 <id>/ 目录，保持相对路径结构，避免同名文件互相覆盖
            val backupFile = File(File(root, id), safeRel)
            backupFile.parentFile?.mkdirs()
            target.copyTo(backupFile, overwrite = true)
        } else {
            File(root, id).mkdirs()
        }

        File(root, "$id.meta").writeText(
            buildString {
                appendLine("relPath=$safeRel")
                appendLine("timeMs=$ts")
                appendLine("existedBefore=$existed")
                appendLine("sizeBytes=$size")
            }
        )
        id
    }.onFailure { onError("备份失败（不阻断写入）：${it.message}") }.getOrNull()

    /**
     * 回滚一次备份：把该备份记录的旧内容写回工作区。
     *
     * 备份时文件原本不存在 → 回滚即**删除**当前文件（撤销「新建」）。
     *
     * @return 成功返回 true
     */
    fun restore(id: String): Boolean = runCatching {
        val meta = File(root, "$id.meta")
        if (!meta.isFile) return false
        val props = meta.readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        val relPath = props["relPath"] ?: return false
        val existed = props["existedBefore"]?.toBooleanStrictOrNull() ?: return false
        val target = File(workspace, relPath)
        if (!target.canonicalPath.startsWith(workspace.canonicalPath + File.separator)) return false

        if (!existed) {
            // 原本不存在 → 撤销创建
            if (target.exists()) target.delete()
            return@runCatching true
        }
        val src = File(File(root, id), relPath)
        if (!src.isFile) return@runCatching false
        target.parentFile?.mkdirs()
        src.copyTo(target, overwrite = true)
        true
    }.getOrDefault(false)

    /** 列出全部备份，按时间倒序（最近的在最前）。 */
    fun list(): List<Backup> {
        val metas = root.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { mf ->
            runCatching {
                val id = mf.name.removeSuffix(".meta")
                val props = mf.readLines()
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0] to it[1] }
                Backup(
                    id = id,
                    relPath = props["relPath"] ?: "",
                    timeMs = props["timeMs"]?.toLongOrNull() ?: 0L,
                    existedBefore = props["existedBefore"]?.toBooleanStrictOrNull() ?: false,
                    sizeBytes = props["sizeBytes"]?.toLongOrNull() ?: 0L
                )
            }.getOrNull()
        }.filter { it.relPath.isNotEmpty() }
            .sortedByDescending { it.timeMs }
    }

    /** 某文件的最近一条备份（回滚「上一步」用）。 */
    fun latestFor(relPath: String): Backup? =
        list().firstOrNull { it.relPath == relPath }

    /** 删除一条备份。 */
    fun delete(id: String): Boolean = runCatching {
        File(root, id).deleteRecursively()
        File(root, "$id.meta").delete()
        true
    }.getOrDefault(false)

    /** 清理超出上限的旧备份（保留最近 [keep] 条）。 */
    fun prune(keep: Int = 50): Int {
        val all = list()
        if (all.size <= keep) return 0
        var removed = 0
        all.drop(keep).forEach {
            if (delete(it.id)) removed++
        }
        return removed
    }

    /** 相对路径安全化：拒绝绝对路径与 .. 穿越，返回 null 表示非法。 */
    private fun sanitizeRel(rel: String): String? {
        val t = rel.trim().replace('\\', '/')
        if (t.isEmpty()) return null
        if (t.startsWith("/")) return null
        val parts = t.split("/")
        if (parts.any { it == ".." }) return null
        return t
    }

    companion object {
        /** 默认目录名（备份仓落位）。 */
        const val DIR_NAME = "file_backups"
    }
}
