package com.example.agentkit.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import com.example.agentkit.core.workspace.CheckpointStore
import com.example.agentkit.core.workspace.FileBackupStore
import com.example.agentkit.core.workspace.FileBackupStoreFactory
import com.example.agentkit.core.workspace.TextDiff
import com.example.agentkit.core.workspace.WorkspaceStore

/**
 * 编辑器页（调研文档「第 6 步：编辑器 + Git 检查点」直译）。
 *
 * 两态：列表态（工作区文件 + 检查点）↔ 编辑态（多行文本编辑 + 保存 / 检查点）。
 * 检查点用 [CheckpointStore] 目录快照实现（零依赖、离线安全，语义等价 git 检查点）。
 */
@Composable
fun EditorScreen() {
    val context = LocalContext.current

    var current by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var tree by remember { mutableStateOf(WorkspaceStore.listTree(context)) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var checkpoints by remember { mutableStateOf(CheckpointStore.list(context)) }
    var backupGroups by remember { mutableStateOf(FileBackupStoreFactory.default(context).grouped()) }
    // D 批：正在查看差异的备份（非 null 时弹层展示）
    var diffTarget by remember { mutableStateOf<FileBackupStore.BackupDiff?>(null) }
    var newName by remember { mutableStateOf("") }
    var cpLabel by remember { mutableStateOf("") }

    fun refresh() {
        tree = WorkspaceStore.listTree(context)
        checkpoints = CheckpointStore.list(context)
        backupGroups = FileBackupStoreFactory.default(context).grouped()
    }

    // D 批：差异弹层放在最外层，与备份列表的嵌套层级解耦
    diffTarget?.let { d ->
        BackupDiffDialog(diff = d, onDismiss = { diffTarget = null })
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = AppDest.EDITOR.title,
            actionIcon = if (current != null) Icons.AutoMirrored.Outlined.ArrowBack else null,
            actionDescription = "返回列表",
            onAction = { current = null; dirty = false }
        )

        val open = current
        if (open == null) {
            // ——— 列表态：文件 + 检查点 ———
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AgentSpacing.space5)
            ) {
                AkSectionCard(
                    title = "工作区",
                    icon = Icons.Outlined.Description,
                    description = "与 Agent 工具同一个目录（agent_workspace），支持子目录；点目录折叠/展开。"
                ) {
                    if (tree.isEmpty()) {
                        Text(
                            "暂无文件，在下方新建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // W-27：文件树（目录可折叠）。折叠目录下的子项直接跳过。
                        tree.forEach { entry ->
                            val hidden = collapsed.any { entry.path.startsWith("$it/") }
                            if (!hidden) {
                                TreeRow(
                                    entry = entry,
                                    collapsed = entry.path in collapsed,
                                    onToggle = {
                                        collapsed = if (entry.path in collapsed) {
                                            collapsed - entry.path
                                        } else collapsed + entry.path
                                    },
                                    onOpen = {
                                        val t = WorkspaceStore.read(context, entry.path)
                                        if (t != null) {
                                            current = entry.path; editorText = t; dirty = false
                                        } else {
                                            Toast.makeText(context, "无法读取 ${entry.path}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                Spacer(Modifier.height(AgentSpacing.space1))
                            }
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoInput(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = "新文件，如 src/main.js",
                            imeSend = Icons.Outlined.Add,
                            onSend = {
                                val n = newName.trim()
                                when {
                                    n.isEmpty() -> Unit
                                    com.example.agentkit.core.workspace.WorkspacePaths.safeRelPath(n) == null ->
                                        Toast.makeText(context, "路径含非法字符（不允许 ..）", Toast.LENGTH_SHORT).show()
                                    !WorkspaceStore.create(context, n) ->
                                        Toast.makeText(context, "文件已存在", Toast.LENGTH_SHORT).show()
                                    else -> { newName = ""; refresh(); Toast.makeText(context, "已创建 $n", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(AgentSpacing.space4))

                AkSectionCard(
                    title = "检查点",
                    icon = Icons.Outlined.Storage,
                    description = "快照整个工作区，可随时恢复（目录快照，等价 git 检查点）。"
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoInput(
                            value = cpLabel,
                            onValueChange = { cpLabel = it },
                            placeholder = "检查点备注，如 完成登录脚本",
                            imeSend = Icons.Outlined.Save,
                            onSend = {
                                val cp = CheckpointStore.create(context, cpLabel.ifBlank { "checkpoint" })
                                if (cp != null) {
                                    cpLabel = ""; refresh()
                                    Toast.makeText(context, "已创建检查点 ${cp.id}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "创建检查点失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    if (checkpoints.isEmpty()) {
                        Text(
                            "暂无检查点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        checkpoints.forEach { cp ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cp.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${CheckpointStore.formatTime(cp.timeMs)} · ${cp.fileCount} 文件 · ${cp.sizeBytes}B",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                AkTextButton(text = "恢复", onClick = {
                                    if (CheckpointStore.restore(context, cp.id)) {
                                        refresh(); Toast.makeText(context, "已恢复到「${cp.label}」", Toast.LENGTH_SHORT).show()
                                    } else Toast.makeText(context, "恢复失败", Toast.LENGTH_SHORT).show()
                                })
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            CheckpointStore.delete(context, cp.id); refresh()
                                        }
                                        .padding(6.dp)
                                )
                            }
                            Spacer(Modifier.height(AgentSpacing.space2))
                        }
                    }
                }

                Spacer(Modifier.height(AgentSpacing.space4))

                // B 批：Agent 写路径的自动备份。Agent 每次 write_file / edit_file
                // 之前都会自动留一份旧内容，这里列出这些落点，可一键回滚到上一步。
                //
                // C 批：按**文件分组**展示（同一文件被改了几次一目了然），
                // 并提供「回滚本文件」（退回到该文件被首次改动前）与「全部回滚」。
                AkSectionCard(
                    title = "自动备份",
                    icon = Icons.Outlined.History,
                    description = "Agent 每次写入文件前自动留下的旧版本。按文件分组，可单文件回滚或一次性全部回滚（新建的文件回滚即删除）。"
                ) {
                    if (backupGroups.isEmpty()) {
                        Text(
                            "暂无自动备份。Agent 修改工作区文件后会在这里出现。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // 顶部：一次性全部回滚
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "共 ${backupGroups.size} 个文件、${backupGroups.sumOf { it.changeCount }} 次改动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            AkTextButton(text = "全部回滚", onClick = {
                                val store = FileBackupStoreFactory.default(context)
                                val n = store.restoreAll()
                                refresh()
                                Toast.makeText(context, "已回滚 $n 处改动", Toast.LENGTH_SHORT).show()
                            })
                        }
                        Spacer(Modifier.height(AgentSpacing.space3))

                        backupGroups.forEach { g ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            g.relPath,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            buildString {
                                                append(CheckpointStore.formatTime(g.latestTimeMs))
                                                append(" · ")
                                                append("被改动 ${g.changeCount} 次")
                                                append(" · ")
                                                append(if (g.backups.last().existedBefore) "可退回最初内容" else "原本不存在（回滚将删除）")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    AkTextButton(text = "回滚本文件", onClick = {
                                        val store = FileBackupStoreFactory.default(context)
                                        val n = store.restoreFile(g.relPath)
                                        refresh()
                                        Toast.makeText(
                                            context,
                                            if (n > 0) "已回滚 ${g.relPath}（$n 处）" else "回滚失败",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    })
                                }
                                // 该文件的历史落点（倒序 = 最近在前），可单条回滚
                                g.backups.forEach { b ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            buildString {
                                                append(CheckpointStore.formatTime(b.timeMs))
                                                append(" · ")
                                                append(if (b.existedBefore) "旧内容 ${b.sizeBytes}B" else "新建文件（回滚将删除）")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        AkTextButton(text = "看改动", onClick = {
                                            val d = FileBackupStoreFactory.default(context).diffToCurrent(b.id)
                                            if (d == null) {
                                                Toast.makeText(context, "读取备份内容失败", Toast.LENGTH_SHORT).show()
                                            } else {
                                                diffTarget = d
                                            }
                                        })
                                        AkTextButton(text = "回滚", onClick = {
                                            val ok = FileBackupStoreFactory.default(context).restore(b.id)
                                            if (ok) {
                                                refresh()
                                                Toast.makeText(context, "已回滚 ${b.relPath}", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "回滚失败", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                    }
                                }
                            }
                            Spacer(Modifier.height(AgentSpacing.space3))
                        }
                    }
                }
            }
        } else {
            // ——— 编辑态：多行编辑 + 保存 / 删除 / 检查点 ———
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space3)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = open + if (dirty) " *" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(AgentSpacing.space3))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(AgentSpacing.space3)
                ) {
                    val language = com.example.agentkit.core.workspace.WorkspacePaths.languageOf(open)
                    BasicTextField(
                        value = editorText,
                        onValueChange = { editorText = it; dirty = true },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = EditorMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // W-27：语法高亮只改显示，写进文件的仍是原文
                        visualTransformation = SyntaxHighlightTransformation(
                            language = language,
                            keywordColor = MaterialTheme.colorScheme.primary,
                            stringColor = MaterialTheme.colorScheme.tertiary,
                            commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            numberColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
                Spacer(Modifier.height(AgentSpacing.space3))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkPrimaryButton(
                        text = "保存",
                        onClick = {
                            if (WorkspaceStore.write(context, open, editorText)) {
                                dirty = false; refresh()
                                Toast.makeText(context, "已保存 $open", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AkOutlinedButton(
                        text = "检查点",
                        onClick = {
                            if (WorkspaceStore.write(context, open, editorText)) dirty = false
                            val cp = CheckpointStore.create(context, "编辑 $open")
                            refresh()
                            if (cp != null) Toast.makeText(context, "已创建检查点 ${cp.id}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(AgentSpacing.space2))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkTextButton(text = "删除此文件", tint = MaterialTheme.colorScheme.error, onClick = {
                        WorkspaceStore.delete(context, open)
                        current = null; dirty = false; refresh()
                        Toast.makeText(context, "已删除 $open", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

/**
 * 工作区树节点（W-27）：目录可折叠（点击切换），文件点击打开。
 * 缩进按 [WorkspaceStore.TreeEntry.depth]，每层 14dp——文件多也看得清层级。
 */
@Composable
private fun TreeRow(
    entry: WorkspaceStore.TreeEntry,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (entry.isDir) onToggle() else onOpen() }
            .padding(
                start = AgentSpacing.space2 + (entry.depth * 14).dp,
                end = AgentSpacing.space3,
                top = AgentSpacing.space2,
                bottom = AgentSpacing.space2
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDir && collapsed -> Icons.Outlined.Folder
                entry.isDir -> Icons.Outlined.FolderOpen
                else -> Icons.Outlined.Description
            },
            contentDescription = if (entry.isDir) (if (collapsed) "展开" else "折叠") else "打开",
            tint = if (entry.isDir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(AgentSpacing.space2))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!entry.isDir) {
            val lang = com.example.agentkit.core.workspace.WorkspacePaths.languageOf(entry.path)
            if (lang != null) {
                Text(
                    lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 等宽单行输入 + 右侧动作（复用输入栏 chip 语汇的紧凑 36dp 圆形动作）。 */
@Composable
private fun MonoInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeSend: androidx.compose.ui.graphics.vector.ImageVector,
    onSend: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = EditorMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = EditorMonoStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                },
                // onSend 由外层通过 IME action 不便暴露，改由右侧按钮触发；这里保留 singleLine
            )
        }
        Spacer(Modifier.width(AgentSpacing.space2))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onSend)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = imeSend,
                    contentDescription = "执行",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 编辑器等宽文本样式（noFontPadding）。 */
private val EditorMonoStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    textAlign = TextAlign.Start
)

/**
 * D 批：备份差异弹层。
 *
 * 为什么需要它：备份页原本只有「回滚」——那是个**不可逆**动作，用户盲点很危险。
 * 先看一眼「这次到底改了什么」（+N −M 行），再决定要不要退，才是安全的交互。
 *
 * 视觉：等宽字体、逐行着色（新增 primary 底、删除 error 底），前缀 `+` / `−` / 空格
 * 让色弱用户也能分辨（不只靠颜色）。
 */
@Composable
private fun BackupDiffDialog(
    diff: FileBackupStore.BackupDiff,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AgentSpacing.space5)) {
                Text(
                    text = "改动对比",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(diff.relPath)
                        append(" · ")
                        append(diff.stats.label)
                        if (diff.currentMissing) append(" · 当前文件已删除")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space3))

                if (diff.stats.isEmpty && !diff.currentMissing) {
                    Text(
                        "这条备份与当前内容完全一致。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 只渲染「有变化」的上下文：纯 SAME 的中间段折叠成「… 未变 N 行 …」，
                    // 否则一个 500 行文件里改 2 行，用户要滚 500 行才看得到改动点。
                    val shown = remember(diff.id) { compactDiff(diff.lines) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAkColors.surface1)
                            .verticalScroll(rememberScrollState())
                            .padding(AgentSpacing.space3)
                    ) {
                        shown.forEach { row ->
                            when (row) {
                                is DiffRow.Gap -> Text(
                                    text = "… 未变 ${row.count} 行 …",
                                    style = machineText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                is DiffRow.Item -> DiffLineView(row.line)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(AgentSpacing.space4))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AkTextButton(text = "关闭", onClick = onDismiss)
                }
            }
        }
    }
}

/** 折叠后的 diff 行：普通行 / 被折叠的未变段。 */
private sealed interface DiffRow {
    data class Item(val line: TextDiff.Line) : DiffRow
    data class Gap(val count: Int) : DiffRow
}

/** 保留改动行 + 前后各 2 行上下文，其余 SAME 段折叠。 */
private fun compactDiff(lines: List<TextDiff.Line>, context: Int = 2): List<DiffRow> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { i, l ->
        if (l.kind != TextDiff.Kind.SAME) {
            for (k in (i - context)..(i + context)) {
                if (k in lines.indices) keep[k] = true
            }
        }
    }
    val out = ArrayList<DiffRow>()
    var i = 0
    while (i < lines.size) {
        if (keep[i]) {
            out += DiffRow.Item(lines[i])
            i++
        } else {
            var j = i
            while (j < lines.size && !keep[j]) j++
            out += DiffRow.Gap(j - i)
            i = j
        }
    }
    return out
}

/** 单行：前缀 + 行号 + 内容；新增/删除各有一层淡底。 */
@Composable
private fun DiffLineView(line: TextDiff.Line) {
    val (prefix, bg, fg) = when (line.kind) {
        TextDiff.Kind.ADD -> Triple(
            "+",
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.onSurface
        )
        TextDiff.Kind.DEL -> Triple(
            "−",
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.onSurface
        )
        TextDiff.Kind.SAME -> Triple(" ", Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val no = (line.newNo ?: line.oldNo)?.toString().orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = prefix,
            style = machineText,
            color = fg,
            modifier = Modifier.width(14.dp)
        )
        Text(
            text = no.padStart(4),
            style = machineText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp)
        )
        Text(
            text = line.text.ifEmpty { " " },
            style = machineText,
            color = fg
        )
    }
}
