package com.example.agentkit.core.workspace

import android.content.Context
import java.io.File

/**
 * [FileBackupStore] 的 Android 侧构造入口。
 *
 * ## 为什么要单独一个文件
 * [FileBackupStore] 本身是**纯文件操作**（无 Android 依赖），可以直接被 JVM harness
 * 编译断言；而「工作区目录在哪」这件事依赖 [WorkspaceStore]——后者会触发老目录
 * 迁移，硬编码路径会绕过迁移，导致备份指向一个空目录。
 *
 * 把这层依赖拆到独立文件，纯逻辑部分就能脱离 android.jar 编译。
 */
object FileBackupStoreFactory {

    /** 按约定路径构造：备份仓 `<filesDir>/file_backups`，工作区取 [WorkspaceStore.dir]。 */
    fun default(context: Context): FileBackupStore =
        FileBackupStore(
            File(context.filesDir, FileBackupStore.DIR_NAME),
            WorkspaceStore.dir(context)
        ) { msg -> com.example.agentkit.core.AgentLogger.w("FileBackupStore", msg) }
}
