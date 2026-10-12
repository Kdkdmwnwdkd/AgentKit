package com.example.agentkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.memory.MemoryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记忆库页（UX v1 §1.7）：跨会话长期记忆的检索 / 沉淀 / 清理。
 *
 * 从旧自动化页拆出：记忆是「Agent 记住了什么」的资产管理页面，
 * 搜索、手动记录、清空三件事在一页内闭环。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「记忆库」+ 右上「刷新」）。
 */
@Composable
fun MemoryScreen(vm: ChatViewModel) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var showClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.MEMORY.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = { vm.refreshMemories() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // —— 检索 ——
            AkSectionCard(
                title = "检索",
                icon = Icons.Outlined.Search,
                description = "按关键词搜索记忆；点「全部记忆」回到完整列表。"
            ) {
                AkTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "关键词",
                    placeholder = "例如：美式咖啡"
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkPrimaryButton(
                        text = "搜索",
                        modifier = Modifier.weight(1f),
                        enabled = query.isNotBlank(),
                        onClick = { vm.searchMemory(query) }
                    )
                    AkOutlinedButton(
                        text = "全部记忆",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            query = ""
                            vm.refreshMemories()
                        }
                    )
                }
            }

            // —— 记忆列表 / 空态 ——
            if (memories.isEmpty()) {
                AkEmptyState(
                    icon = Icons.Outlined.Storage,
                    title = if (query.isBlank()) "暂无记忆" else "没有匹配的记忆",
                    description = if (query.isBlank()) {
                        "Agent 每次成功回答会自动沉淀一条事件记忆；偏好和关键信息也可以在下面手动记录。"
                    } else {
                        "换个关键词试试，或点「全部记忆」查看全部。"
                    }
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)) {
                    memories.forEach { item ->
                        MemoryCard(item = item, onForget = { vm.forgetMemory(item.id) })
                    }
                }
            }

            // —— 手动记录 + 清空 ——
            AkSectionCard(
                title = "手动记一条",
                icon = Icons.Outlined.Bookmark,
                description = "偏好、常用信息手动沉淀后，Agent 后续回答会参考这些记忆。"
            ) {
                AkTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = "要记住的内容",
                    placeholder = "例如：我喜欢喝美式咖啡，不加糖",
                    singleLine = false,
                    maxLines = 3
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                AkPrimaryButton(
                    text = "记住这条",
                    icon = Icons.Outlined.Bookmark,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = draft.isNotBlank(),
                    onClick = {
                        vm.addMemory(draft, "note", emptyList())
                        draft = ""
                    }
                )
                Spacer(Modifier.height(AgentSpacing.space4))
                AkDivider()
                Spacer(Modifier.height(AgentSpacing.space4))
                AkOutlinedButton(
                    text = "清空记忆",
                    icon = Icons.Outlined.Delete,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showClear = true }
                )
            }
        }
    }

    // —— 清空二次确认：Agent 会忘掉全部偏好与过往，删前必须拦一道 ----
    AkModalHost(target = if (showClear) Unit else null) { _, dialogVisible ->
        AkConfirmDialog(
            title = "清空记忆库",
            message = "确认清空全部 ${memories.size} 条记忆？Agent 将忘掉所有偏好与过往，且无法恢复。",
            confirmText = "确认清空",
            icon = Icons.Outlined.Delete,
            danger = true,
            visible = dialogVisible,
            onConfirm = {
                vm.clearMemory()
                showClear = false
            },
            onDismiss = { showClear = false }
        )
    }
}

/**
 * 单条记忆卡：kind 徽章 + 内容 + 元信息（标签 / 重要度 / 时间）+ 「忘记」。
 * kind 是分类不是状态，徽章一律中性底，不用语义色（§3.5）。
 */
@Composable
private fun MemoryCard(item: MemoryItem, onForget: () -> Unit) {
    val ak = LocalAkColors
    AkGroupCard {
        Column(
            Modifier.padding(AgentSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkBadge(
                    text = kindLabel(item.kind),
                    container = ak.surface2,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                AkTextButton(text = "忘记", onClick = onForget, tint = MaterialTheme.colorScheme.error)
            }
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            val meta = buildList {
                if (item.tags.isNotEmpty()) add("#${item.tags.joinToString(" #")}")
                add("重要度 ${item.salience}")
                add(formatMemoryTime(item.createdAt))
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 记忆 kind（英文枚举）→ 中文展示名（规范 §4.1 纯中文；未知值原样透传，不吞数据）。 */
private fun kindLabel(kind: String): String = when (kind.lowercase()) {
    "fact" -> "事实"
    "preference" -> "偏好"
    "event" -> "事件"
    "note" -> "随手记"
    else -> kind
}

/**  epoch 毫秒 → 「MM-dd HH:mm」本地时间（minSdk 26 起 java.time 可用，无需脱糖）。 */
private val memoryTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatMemoryTime(epochMilli: Long): String =
    memoryTimeFormatter.format(
        Instant.ofEpochMilli(epochMilli).atZone(ZoneId.systemDefault())
    )
