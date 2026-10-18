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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agentkit.core.workspace.CheckpointStore
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
    var newName by remember { mutableStateOf("") }
    var cpLabel by remember { mutableStateOf("") }

    fun refresh() {
        tree = WorkspaceStore.listTree(context)
        checkpoints = CheckpointStore.list(context)
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
