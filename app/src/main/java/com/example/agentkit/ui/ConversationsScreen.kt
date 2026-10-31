package com.example.agentkit.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.core.ConversationSummary
import com.example.agentkit.core.text.ConversationFilter
import com.example.agentkit.core.text.ChatTime

/**
 * 全部对话页（W-69）。
 *
 * ## 为什么要有这一页
 * W-68 为了「设置不贴键盘」，把抽屉里那个搜索输入框删了——抽屉从此只有最近 8 条，
 * 第 9 条往后的会话按标题就找不到了，等于把旧的聊天记录关进了没有门的房间。
 * 补这一页把门装回去：**列表 + 标题搜索**都在独立页面里做，抽屉保持「无输入框、不弹键盘」。
 *
 * ## 搜索为什么放这儿而不是抽屉
 * 抽屉是覆盖层，键盘一弹就被 IME 压到 400 多 px，固定在底部的「设置」会贴到键盘上沿。
 * 独立页面没有固定底栏，键盘只把列表推窄，滚一滚照样能看。同一件事换个容器就成立了。
 *
 * ## 只搜标题，不搜正文
 * 正文检索在记忆库（BM25）——那边有索引、有召回排序；在这儿每次输入都要把所有会话的
 * 消息读一遍，输入三四个字就开始卡。标题搜索解决的是「我记得这个对话叫什么」，
 * 正文搜索解决的是「我记得我说过什么」，两件事各有各的家。
 */
@Composable
fun ConversationsScreen(
    vm: ChatViewModel,
    onOpen: (String) -> Unit,
    onNew: () -> Unit
) {
    val ctx = LocalContext.current
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeId by vm.activeConversationId.collectAsStateWithLifecycle()
    val generatingId by vm.generatingConvId.collectAsStateWithLifecycle()
    // 搜索词是一次性动作，不落盘；离开页面（组合销毁）就清空
    var query by remember { mutableStateOf("") }
    var menuConvId by remember { mutableStateOf<String?>(null) }
    var renameConvId by remember { mutableStateOf<String?>(null) }
    var deleteConvId by remember { mutableStateOf<String?>(null) }

    // conversations 已由 ConversationStore.list() 排好：置顶在前，其次按最近活动时间倒序。
    // 过滤交给纯函数：它保证不重排（重排会让「置顶」这个承诺失效）、空白词等于不过滤
    val visible = ConversationFilter.apply(conversations, query) { it.title }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = "全部对话",
            actionIcon = Icons.Outlined.Add,
            actionDescription = "新建对话",
            onAction = onNew
        )

        // 搜索框贴着顶栏，不固定、不悬浮：它就是列表的一部分，滚走也无所谓
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentSpacing.space4)
        ) {
            AkTextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索标题",
                placeholder = "输入关键词…",
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(AgentSpacing.space3))

        // 统计行：有关键词时显示「M / N」，让用户知道是被筛掉了而不是真没了
        val countText = ConversationFilter.countText(conversations.size, visible.size, query)
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AgentSpacing.space5)
        )

        Spacer(Modifier.height(AgentSpacing.space2))

        when {
            conversations.isEmpty() -> EmptyHint(
                title = "还没有对话",
                body = "回到对话页说一句话，会话就会出现在这里。"
            )

            visible.isEmpty() -> EmptyHint(
                title = "没有匹配的会话",
                body = "试试更短的关键词，或者去记忆库按内容找。"
            )

            else -> LazyColumn(
                // imePadding：键盘弹起时把列表底部抬上来，最后几条不会被 IME 盖住
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = AgentSpacing.space3,
                    end = AgentSpacing.space3,
                    top = AgentSpacing.space1,
                    // 末尾多留一截：最后一条不至于紧贴导航栏
                    bottom = AgentSpacing.space8
                ),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)
            ) {
                items(visible, key = { it.id }) { conv ->
                    ConversationRow(
                        conversation = conv,
                        isActive = conv.id == activeId,
                        isGenerating = conv.id == generatingId,
                        onClick = { onOpen(conv.id) },
                        onLongClick = { menuConvId = conv.id }
                    )
                }
            }
        }
    }

    // —— 长按菜单 / 重命名 / 删除：与抽屉同一套对话框，避免两份行为不一致 ——
    AkModalHost(target = menuConvId) { id, visible ->
        val conv = conversations.firstOrNull { it.id == id }
        ConversationMenuDialog(
            title = conv?.title ?: "对话",
            visible = visible,
            pinned = conv?.pinned == true,
            onTogglePin = {
                menuConvId = null
                vm.setConversationPinned(id, conv?.pinned != true)
            },
            onRename = { menuConvId = null; renameConvId = id },
            onExport = { menuConvId = null; exportConv(ctx, vm, conversations, id) },
            onDelete = { menuConvId = null; deleteConvId = id },
            onDismiss = { menuConvId = null }
        )
    }
    AkModalHost(target = renameConvId) { id, visible ->
        var text by remember(id) {
            mutableStateOf(conversations.firstOrNull { it.id == id }?.title ?: "")
        }
        RenameConversationDialog(
            name = text,
            onNameChange = { text = it },
            visible = visible,
            onConfirm = { vm.renameConversation(id, text); renameConvId = null },
            onDismiss = { renameConvId = null }
        )
    }
    AkModalHost(target = deleteConvId) { id, visible ->
        val title = conversations.firstOrNull { it.id == id }?.title ?: "该对话"
        AkConfirmDialog(
            title = "删除对话",
            message = "确定删除「$title」吗？消息记录将从本机移除，删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Chat,
            visible = visible,
            onConfirm = {
                // 与抽屉一致：在途会话 VM 会静默拒绝，UI 必须给一句人话
                if (id == generatingId) {
                    Toast.makeText(ctx, "该对话正在生成，请先停止或等待完成", Toast.LENGTH_SHORT).show()
                } else {
                    vm.deleteConversation(id)
                }
                deleteConvId = null
            },
            onDismiss = { deleteConvId = null }
        )
    }
}

/**
 * 导出为 .md 真文件（FileProvider content://）。
 *
 * 与抽屉里那段同源：文件名取会话标题，无匹配标题回退「会话」。
 * 会话为空时 [ChatViewModel.conversationMarkdown] 返空串，界面层不弹分享面板。
 */
private fun exportConv(
    ctx: android.content.Context,
    vm: ChatViewModel,
    conversations: List<ConversationSummary>,
    id: String
) {
    val title = conversations.firstOrNull { it.id == id }?.title ?: "会话"
    shareMarkdownFile(ctx, title, vm.conversationMarkdown(id), "导出会话")
}

/** 空态：一张图都没必要给，一句话说清「为什么空 / 下一步做什么」。 */
@Composable
private fun EmptyHint(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AgentSpacing.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(AgentSpacing.space1))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 一行会话：图标 + 标题 + 「时间 · N 条消息」+（置顶/生成中）
 *
 * 高度用 heightIn(min) 不用固定 height（规范 §5.4）：fontScale 拉到 1.3 时
 * 双行文字要能撑开，固定 64dp 会把副行裁掉半截。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    isActive: Boolean,
    isGenerating: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val ak = LocalAkColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val primary = MaterialTheme.colorScheme.primary
    val meta = buildString {
        append(ChatTime.format(conversation.updatedAt, System.currentTimeMillis()))
        append(" · ")
        append("${conversation.messageCount} 条消息")
        if (conversation.workspaceDir.isNotEmpty()) {
            append(" · ")
            append(conversation.workspaceDir.substringAfterLast('/'))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space1)
            .heightIn(min = 64.dp)
    ) {
        // 选中态：左缘 3dp 竖条（与抽屉会话行同一套视觉语言）
        if (isActive) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(primary)
            )
        }
        Row(
            // matchParentSize：父 Box 的高度由 heightIn(min) 给，不是无限高；
            // 用 fillMaxSize 会去争「父容器的最大高度」，在有限高的父里直接撑满（W-68 抽屉底栏那个 bug 的同源写法）
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = AgentSpacing.space1)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    when {
                        isActive -> primary.copy(alpha = 0.10f)
                        pressed -> ak.surface2
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Chat,
                contentDescription = null,
                tint = if (isActive) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = "已置顶",
                            tint = primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(AgentSpacing.space1))
                    }
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 2.dp,
                            color = primary
                        )
                        Spacer(Modifier.width(AgentSpacing.space1))
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isActive) primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
