package com.example.agentkit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.core.Bookmark
import com.example.agentkit.core.text.ChatTime

/**
 * 收藏页（W-30）。
 *
 * 定位：**精选存档**，不是消息备份。因此：
 *  - 删会话不影响这里的条目（存的是正文快照，不是引用）；
 *  - 只提供「复制 / 删除 / 清空」，不做搜索——收藏条数上限 500，滚动即可；
 *  - 每条标明来源会话与原始时间，隔久了也知道它是哪来的。
 */
@Composable
fun BookmarkScreen(vm: ChatViewModel) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    var showClear by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = "收藏",
            actionIcon = if (bookmarks.isEmpty()) null else Icons.Outlined.Delete,
            actionDescription = "清空收藏",
            onAction = { showClear = true }
        )

        if (bookmarks.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AgentSpacing.space6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Text(
                    text = "还没有收藏",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AgentSpacing.space1))
                Text(
                    text = "在对话里点消息下方的「⋯」→「收藏」，内容会存到这里；删掉会话也不会丢。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AgentSpacing.space4,
                    end = AgentSpacing.space4,
                    top = AgentSpacing.space2,
                    bottom = AgentSpacing.space6
                ),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
            ) {
                items(bookmarks, key = { it.id }) { item ->
                    BookmarkCard(
                        item = item,
                        onCopy = { copyBookmark(ctx, item.text) },
                        onDelete = { vm.removeBookmark(item.id) }
                    )
                }
            }
        }
    }

    if (showClear) {
        AkConfirmDialog(
            title = "清空收藏",
            message = "将删除全部 ${bookmarks.size} 条收藏，不可恢复。会话本身不受影响。",
            confirmText = "清空",
            danger = true,
            onConfirm = {
                vm.clearBookmarks()
                showClear = false
            },
            onDismiss = { showClear = false }
        )
    }
}

/** 单条收藏卡片：来源行 + 正文（Markdown）+ 操作行。 */
@Composable
private fun BookmarkCard(item: Bookmark, onCopy: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAkColors.surface1)
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space3)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (item.role == "user") Icons.Outlined.Person else Icons.Outlined.AutoAwesome,
                contentDescription = if (item.role == "user") "我的提问" else "AI 回答",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = item.convTitle.ifBlank { "未命名对话" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val time = ChatTime.precise(item.timeMs)
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space2))
        MarkdownText(text = item.text, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(AgentSpacing.space2))
        Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)) {
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** 复制正文（收藏页独立实现：不依赖 ChatScreen 的私有 copyMessage）。 */
private fun copyBookmark(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("收藏", text))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    }
}
