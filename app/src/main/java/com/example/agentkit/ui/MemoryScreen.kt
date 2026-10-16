package com.example.agentkit.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentkit.memory.MemoryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 记忆库页（UX v1 §1.7）：跨会话长期记忆的图谱浏览 / 沉淀 / 清理。
 *
 * 用户反馈记忆库平庸（旧版是纵向列表），参照 OPeritAI 的记忆库改图谱式：
 * Canvas 同心环节点网络 + 共享 tag 连边，点节点看详情，右侧竖排 FAB 做新增 / 清空。
 * 数据能力一件不减（检索 / 手动记一条 / 清空全部），只是换了一件衣服。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「记忆库」+ 右上「刷新」）。
 */
@Composable
fun MemoryScreen(vm: ChatViewModel) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // hotfix31：列表/图谱双模式，默认列表——真机反馈「图谱只学到布局的样子，
    // 人家可以点开可以看」：列表好扫好点（图谱节点 Canvas 命中难），图谱留着做视觉。
    var listMode by rememberSaveable { mutableStateOf(true) }
    // hotfix31：编辑对话框三件套草稿（content 多行 + salience 滑杆 + tags 逗号分隔）
    var showEdit by remember { mutableStateOf(false) }
    var editContent by rememberSaveable { mutableStateOf("") }
    var editSalience by rememberSaveable { mutableStateOf(5) }
    var editTags by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    // 选中项随结果集派生：搜索 / 刷新 / 遗忘后原选中可能已不在列表里，不强行续命
    val selected = memories.firstOrNull { it.id == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 详情卡封顶 45% 屏高：图谱/列表永远是主视觉，长内容在卡内滚动（OPeritAI 同款层级）
        val detailMaxHeight = maxHeight * 0.45f
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageTopBar(
                    title = AppDest.MEMORY.title,
                    actionIcon = Icons.Outlined.Refresh,
                    actionDescription = "刷新",
                    onAction = { vm.refreshMemories() }
                )

                // —— 顶栏下方一行：圆角搜索框 + 计数徽章；有筛选时再给一枚「全部」复位 —
                MemorySearchRow(
                    query = query,
                    resultCount = memories.size,
                    onQueryChange = { query = it },
                    onSearch = { vm.searchMemory(query) },
                    onReset = {
                        query = ""
                        vm.refreshMemories()
                    }
                )
                // hotfix31：模式切换 chip 行（列表 ⇄ 图谱）——「全部」复位仅在
                // 有筛选时出现，与模式 chip 同行不打架
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AgentSpacing.space5,
                            end = AgentSpacing.space5,
                            bottom = AgentSpacing.space2
                        ),
                    horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AkChip(
                        text = "列表",
                        selected = listMode,
                        onClick = { listMode = true }
                    )
                    AkChip(
                        text = "图谱",
                        selected = !listMode,
                        onClick = { listMode = false }
                    )
                    if (query.isNotBlank()) {
                        AkChip(
                            text = "全部",
                            selected = false,
                            onClick = {
                                query = ""
                                vm.refreshMemories()
                            }
                        )
                    }
                }

                if (memories.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AkEmptyState(
                            icon = Icons.Outlined.Storage,
                            title = if (query.isBlank()) "暂无记忆" else "没有匹配的记忆",
                            description = if (query.isBlank()) {
                                "Agent 每次成功回答会自动沉淀一条事件记忆；偏好和关键信息也可以点右侧「+」手动记录。"
                            } else {
                                "换个关键词试试，或点「全部」查看全部。"
                            }
                        )
                    }
                } else if (listMode) {
                    // hotfix31：列表模式——LazyColumn 记忆行，好扫好点；
                    // 行内直接显示 kind 徽章 + 摘要 + 重要度 + 时间，点选看详情
                    MemoryList(
                        memories = memories,
                        selectedId = selected?.id,
                        onSelect = { id -> selectedId = if (selectedId == id) null else id },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    // —— 图谱模式：核心视觉，吃满剩余空间；节点可拖动（hotfix31）——
                    MemoryGraph(
                        memories = memories,
                        selectedId = selected?.id,
                        onSelect = { id -> selectedId = if (selectedId == id) null else id },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
                if (selected != null) {
                    MemoryDetailCard(
                        item = selected,
                        maxHeight = detailMaxHeight,
                        onForget = {
                            vm.forgetMemory(selected.id)
                            selectedId = null
                        },
                        onEdit = {
                            // hotfix31：编辑入口——草稿三件套取自当前选中项
                            editContent = selected.content
                            editSalience = selected.salience
                            editTags = selected.tags.joinToString(", ")
                            showEdit = true
                        }
                    )
                }
            }

            // —— 右侧竖排 FAB（参照 OPeritAI）：primary 新增 / error 清空 ——
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = AgentSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
            ) {
                MemoryFab(
                    icon = Icons.Outlined.Add,
                    description = "手动记一条",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = { showAdd = true }
                )
                MemoryFab(
                    icon = Icons.Outlined.Delete,
                    description = "清空记忆",
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    onClick = { showClear = true }
                )
            }
        }
    }

    // —— 手动记一条：小对话框，提交 note 类记忆（kind 分类用户不敏感，默认 note）——
    AkModalHost(target = if (showAdd) Unit else null) { _, dialogVisible ->
        MemoryAddDialog(
            draft = draft,
            onDraftChange = { draft = it },
            visible = dialogVisible,
            onConfirm = {
                vm.addMemory(draft, "note", emptyList())
                draft = ""
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
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
                selectedId = null
                showClear = false
            },
            onDismiss = { showClear = false }
        )
    }

    // —— hotfix31：编辑记忆对话框（内容 / 重要度 / 标签三件套）——
    AkModalHost(target = if (showEdit) Unit else null) { _, dialogVisible ->
        MemoryEditDialog(
            content = editContent,
            salience = editSalience,
            tags = editTags,
            visible = dialogVisible,
            onContentChange = { editContent = it },
            onSalienceChange = { editSalience = it },
            onTagsChange = { editTags = it },
            onConfirm = {
                val target = selectedId
                // store 拒收（空白内容/目标不在）时保持对话框不关 + Toast 说明
                val ok = target != null && vm.updateMemory(
                    id = target,
                    content = editContent,
                    salience = editSalience,
                    tags = editTags.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
                )
                if (ok) {
                    showEdit = false
                } else {
                    Toast.makeText(context, "内容不能为空", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showEdit = false }
        )
    }
}

/**
 * 搜索行：圆角搜索面（填充式语汇，比 AkTextField 少一条 label，顶栏下方一行放下）
 * + 右侧计数徽章；框内「搜索」图标即触发（保留旧版显式触发语义，不改成输入即检索），
 * 有内容时出「×」= 清空并看全部。
 */
@Composable
private fun MemorySearchRow(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onReset: () -> Unit
) {
    val ak = LocalAkColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = AgentSpacing.space5,
                end = AgentSpacing.space5,
                top = AgentSpacing.space2,
                bottom = AgentSpacing.space2
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = AgentDimens.touchTarget)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = AgentSpacing.space2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AkCircleIconButton(
                icon = Icons.Outlined.Search,
                description = "搜索",
                iconSize = 20.dp,
                onClick = onSearch
            )
            Spacer(Modifier.width(AgentSpacing.space1))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索记忆...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                AkCircleIconButton(
                    icon = Icons.Outlined.Close,
                    description = "清空关键词并显示全部记忆",
                    iconSize = 18.dp,
                    onClick = onReset
                )
            }
        }
        Spacer(Modifier.width(AgentSpacing.space2))
        // 计数徽章（OPeritAI 右上「0/1」同款语义）：这里是「当前结果集共 N 条」
        AkBadge(
            text = "$resultCount 条记忆",
            container = ak.surface2,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 记忆图谱（用户反馈记忆库平庸，参照 OPeritAI 改图谱式）。
 *
 * 确定性布局（禁随机）：按 createdAt 排序放进 1~3 个同心环（内环 ≤6、中环 ≤12、其余外环），
 * 环上角度均匀分布、相邻环错开半个步长；画布按 min(宽,高) 适配，右侧预留标签位。
 * 连边：两条记忆共享任一 tag 即连线；全局无 tag 时同环相邻相连（保证始终是网状不是散点），
 * 有 tag 但仍悬空的孤点也接回同环邻居。节点着色按 kind（fact/preference/event/note →
 * primary/secondary/tertiary/outline，中性区分不用 error 红）。
 * 点选：命中最近节点（阈值 = 节点半径 + 8dp）→ 选中放大 1.3 倍 + 外圈高亮 + 连边加粗。
 */
@Composable
private fun MemoryGraph(
    memories: List<MemoryItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val nodes = remember(memories) { buildMemoryNodes(memories) }
    val edges = remember(nodes) { buildMemoryEdges(nodes) }
    // hotfix31：节点拖拽偏移（会话内有效，不落盘）——真机反馈「人家可以移动」。
    // key = 记忆 id；松手后位置停在新处（视图态），重启图谱回到力导布局。
    val dragOffsets: SnapshotStateMap<String, Offset> = remember { mutableStateMapOf() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme
    // kind → 节点色：Canvas 绘制 lambda 不可组合，语义色在此一次性读出
    val kindColor: (String) -> Color = { kind ->
        when (kind.lowercase()) {
            "fact" -> scheme.primary
            "preference" -> scheme.secondary
            "event" -> scheme.tertiary
            else -> scheme.outline
        }
    }

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "记忆图谱，共 ${nodes.size} 条；点按节点在下方查看详情，拖动可移动节点"
            }
            .pointerInput(nodes) {
                // PointerInputScope 自带 Density：dp → px 与绘制侧同源，命中阈值才不会漂移
                val ringRadiusPx = floatArrayOf(14.dp.toPx(), 12.dp.toPx(), 10.dp.toPx())
                val geo = memoryGeometry(
                    size.width.toFloat(),
                    size.height.toFloat(),
                    ringRadiusPx[0] + 6.dp.toPx()
                )
                detectTapGestures { tap ->
                    var bestId: String? = null
                    var bestDist = Float.MAX_VALUE
                    nodes.forEach { node ->
                        val center = memoryNodeCenter(node.ring, node.angle, geo) +
                            (dragOffsets[node.id] ?: Offset.Zero)
                        val dist = (center - tap).getDistance()
                        // 阈值 = 节点半径 + 8dp；选中态放大了 1.3 倍，一并算进取值范围
                        if (dist <= ringRadiusPx[node.ring] * 1.3f + 8.dp.toPx() && dist < bestDist) {
                            bestId = node.id
                            bestDist = dist
                        }
                    }
                    bestId?.let(onSelect)
                }
            }
            // hotfix31：拖动手势独立挂第二个 pointerInput——tap 不消费拖动事件，
            // 两个检测器各管各的（点击选中 / 拖动移位），不互相抢
            .pointerInput(nodes) {
                val ringRadiusPx = floatArrayOf(14.dp.toPx(), 12.dp.toPx(), 10.dp.toPx())
                val geo = memoryGeometry(
                    size.width.toFloat(),
                    size.height.toFloat(),
                    ringRadiusPx[0] + 6.dp.toPx()
                )
                detectDragGestures(
                    onDragStart = { start ->
                        // 抓取起始点最近的节点（同 tap 命中规则：半径 + 8dp）
                        var bestId: String? = null
                        var bestDist = Float.MAX_VALUE
                        nodes.forEach { node ->
                            val center = memoryNodeCenter(node.ring, node.angle, geo) +
                                (dragOffsets[node.id] ?: Offset.Zero)
                            val dist = (center - start).getDistance()
                            if (dist <= ringRadiusPx[node.ring] * 1.3f + 8.dp.toPx() && dist < bestDist) {
                                bestId = node.id
                                bestDist = dist
                            }
                        }
                        draggingId = bestId
                    },
                    onDragEnd = { draggingId = null },
                    onDragCancel = { draggingId = null },
                    onDrag = { _, dragAmount ->
                        draggingId?.let { id ->
                            dragOffsets[id] = (dragOffsets[id] ?: Offset.Zero) + dragAmount
                        }
                    }
                )
            }
    ) {
        val ringRadiusPx = floatArrayOf(14.dp.toPx(), 12.dp.toPx(), 10.dp.toPx())
        val geo = memoryGeometry(
            size.width.toFloat(),
            size.height.toFloat(),
            ringRadiusPx[0] + 6.dp.toPx()
        )
        fun centerOf(node: MemNode) =
            // hotfix31：节点位置 = 力导布局基准 + 拖拽偏移（拖动过的节点停在新处，
            // 会话内保持；edges 与标签都经此取位，边线随拖动实时重画）
            memoryNodeCenter(node.ring, node.angle, geo) + (dragOffsets[node.id] ?: Offset.Zero)

        // —— 连边：outline 低透明度；命中选中/拖动的节点边高亮加粗 ——
        edges.forEach { (from, to) ->
            val hot = from.id == selectedId || to.id == selectedId ||
                from.id == draggingId || to.id == draggingId
            drawLine(
                color = if (hot) primaryColor.copy(alpha = 0.85f)
                else outlineColor.copy(alpha = 0.28f),
                start = centerOf(from),
                end = centerOf(to),
                strokeWidth = if (hot) 2.5.dp.toPx() else 1.dp.toPx()
            )
        }

        // —— 节点 + 标签（标签截断到 8 字符放节点右侧）——
        nodes.forEach { node ->
            val center = centerOf(node)
            val selected = node.id == selectedId
            // hotfix31：拖动中的节点与选中态同倍放大（1.3x），手指下有个明确目标
            val dragging = node.id == draggingId
            val baseRadius = ringRadiusPx[node.ring]
            val radius = if (selected || dragging) baseRadius * 1.3f else baseRadius
            drawCircle(color = kindColor(node.item.kind), radius = radius, center = center)
            drawCircle(
                color = outlineColor.copy(alpha = 0.5f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            if (selected) {
                drawCircle(
                    color = primaryColor,
                    radius = radius + 4.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            val layout = measurer.measure(node.label, labelStyle)
            drawText(
                textLayoutResult = layout,
                color = if (selected) primaryColor else onSurfaceVariant,
                topLeft = Offset(
                    x = center.x + radius + 4.dp.toPx(),
                    y = center.y - layout.size.height / 2f
                )
            )
        }
    }
}

/** 选中节点详情卡：完整 content + kind 徽章 + tags / 重要度 / 时间 + 「忘记」+ hotfix31「编辑」。 */
@Composable
private fun MemoryDetailCard(
    item: MemoryItem,
    maxHeight: Dp,
    onForget: () -> Unit,
    onEdit: () -> Unit = {}
) {
    val ak = LocalAkColors
    AkGroupCard(
        Modifier.padding(
            start = AgentSpacing.space5,
            end = AgentSpacing.space5,
            bottom = AgentSpacing.space3
        )
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(AgentSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkBadge(
                    text = kindLabel(item.kind),
                    container = ak.surface2,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                // hotfix31：编辑入口（真机反馈「可以点开可以看还可以管」）
                AkTextButton(text = "编辑", onClick = onEdit)
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

/** 手动记一条对话框：多行 AkTextField + 确定 / 取消（旧版「手动记一条」卡的对话框化）。 */
@Composable
private fun MemoryAddDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 宽 = min(344dp, 100vw − 48dp)：与 AkConfirmDialog 同款宽度语汇
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "手动记一条",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        label = "要记住的内容",
                        placeholder = "例如：我喜欢喝美式咖啡，不加糖",
                        singleLine = false,
                        maxLines = 4
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "记住这条",
                            icon = Icons.Outlined.Bookmark,
                            modifier = Modifier.weight(1f),
                            enabled = draft.isNotBlank(),
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}

/**
 * hotfix31：列表模式——LazyColumn 记忆行。
 *
 * 真机反馈原话：「记忆库是说搞个和 OPeritAI 差不多的，但是你现在只学到了样子，
 * 人家可以点开，可以看，但是你这个不行，这只学到一个布局的样子而已」。
 * Canvas 图谱节点在真机上小而难戳、扫不动；列表是「可管理」的底色：行内直接给
 * kind 徽章 + 摘要两行 + 重要度 + 时间，点选与图谱同语义（选中看详情/编辑）。
 * 选中态左缘 3dp primary 竖条，与 DrawerConversationRow 同一套视觉语言。
 */
@Composable
private fun MemoryList(
    memories: List<MemoryItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ak = LocalAkColors
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = AgentSpacing.space5,
            vertical = AgentSpacing.space2
        ),
        verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        items(memories, key = { it.id }) { item ->
            val selected = item.id == selectedId
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                AkGroupCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (selected) 6.dp else 0.dp)
                        .clickable { onSelect(item.id) }
                ) {
                    Column(Modifier.padding(AgentSpacing.space3)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AkBadge(
                                text = kindLabel(item.kind),
                                container = ak.surface2,
                                content = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                text = "重要度 ${item.salience}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.tags.isNotEmpty()) {
                                Spacer(Modifier.width(AgentSpacing.space2))
                                Text(
                                    text = "#${item.tags.joinToString(" #")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(AgentSpacing.space1))
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatMemoryTime(item.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * hotfix31：编辑记忆对话框——content 多行 + salience 滑杆（1..10）+ tags 逗号分隔。
 * 骨架同 [MemoryAddDialog]；「保存」 enabled = content 非空白（store 还有一道拒收，
 * 双保险）。tags 拆分（中英文逗号）在调用方做，对话框只当纯文本编辑器。
 */
@Composable
private fun MemoryEditDialog(
    content: String,
    salience: Int,
    tags: String,
    visible: Boolean,
    onContentChange: (String) -> Unit,
    onSalienceChange: (Int) -> Unit,
    onTagsChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "编辑记忆",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkTextField(
                        value = content,
                        onValueChange = onContentChange,
                        label = "内容",
                        singleLine = false,
                        maxLines = 5
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    // salience 滑杆：1..10 离散（steps = 8），实时读数
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "重要度",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(AgentSpacing.space3))
                        Slider(
                            value = salience.toFloat(),
                            onValueChange = { onSalienceChange(it.toInt().coerceIn(1, 10)) },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(AgentSpacing.space2))
                        Text(
                            text = "$salience",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkTextField(
                        value = tags,
                        onValueChange = onTagsChange,
                        label = "标签",
                        placeholder = "用逗号分隔，如：饮食, 偏好"
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "保存",
                            icon = Icons.Outlined.Edit,
                            modifier = Modifier.weight(1f),
                            enabled = content.isNotBlank(),
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}

/**
 * 右侧竖排悬浮操作（参照 OPeritAI）：48dp 触控小圆面，颜色 primary / error 区分新增与清空。
 * 自绘而非 AkCircleIconButton：后者无底色槽，而 FAB 的语义恰恰要靠容器色承载（§3.1 红色只此一处）。
 */
@Composable
private fun MemoryFab(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .background(if (pressed) container.copy(alpha = 0.85f) else container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** 图谱节点：确定性布局产物（禁随机——同一份记忆每次画在同一位置）。 */
private data class MemNode(
    val id: String,
    /** 环号：0 内环 / 1 中环 / 2 外环。 */
    val ring: Int,
    /** 弧度；-π/2 起（正上方），相邻环错开半个步长避免径向对齐。 */
    val angle: Float,
    /** 截断到 8 字符的右侧标签。 */
    val label: String,
    val item: MemoryItem
)

/** 环容量：内环 ≤6、中环 ≤12、其余外环。 */
private const val MEMORY_INNER_CAP = 6
private const val MEMORY_MID_CAP = 12

/** 右侧预留标签宽度占比：外环右侧节点的 8 字符标签不越出画布。 */
private const val MEMORY_LABEL_RESERVE = 0.16f

/** 环半径占比（内 / 中 / 外）：外环贴适配圈，内环留白给标签呼吸。 */
private val MEMORY_RING_FRACTIONS = floatArrayOf(0.36f, 0.66f, 0.94f)

/** 图谱几何：圆心与最大环半径（画布按 min(宽,高) 适配，右侧预留标签位）。 */
private data class MemoryGeometry(val cx: Float, val cy: Float, val maxR: Float)

private fun memoryGeometry(
    canvasW: Float,
    canvasH: Float,
    insetPx: Float
): MemoryGeometry {
    val usableW = canvasW * (1f - MEMORY_LABEL_RESERVE)
    return MemoryGeometry(
        cx = usableW / 2f,
        cy = canvasH / 2f,
        // 外环节点 + 选中外圈都不能出画布：最大半径先扣掉内缩量
        maxR = (min(usableW, canvasH) / 2f - insetPx).coerceAtLeast(0f)
    )
}

private fun memoryNodeCenter(ring: Int, angle: Float, geo: MemoryGeometry): Offset {
    val r = geo.maxR * MEMORY_RING_FRACTIONS[ring]
    return Offset(geo.cx + r * cos(angle), geo.cy + r * sin(angle))
}

/**
 * 排序建环：按 createdAt 老的在内环（最稳定），新的外扩；
 * 环上角度均匀分布，相邻环错开半个步长（内外环节点不在同一径向上，连边不重叠）。
 */
private fun buildMemoryNodes(memories: List<MemoryItem>): List<MemNode> {
    val sorted = memories.sortedBy { it.createdAt }
    val innerCount = minOf(sorted.size, MEMORY_INNER_CAP)
    val midCount = minOf(maxOf(sorted.size - MEMORY_INNER_CAP, 0), MEMORY_MID_CAP)
    val outerCount = maxOf(sorted.size - MEMORY_INNER_CAP - MEMORY_MID_CAP, 0)
    return sorted.mapIndexed { index, item ->
        val ring = when {
            index < innerCount -> 0
            index < innerCount + midCount -> 1
            else -> 2
        }
        val ringStart = when (ring) {
            0 -> 0
            1 -> MEMORY_INNER_CAP
            else -> MEMORY_INNER_CAP + MEMORY_MID_CAP
        }
        val ringCount = when (ring) {
            0 -> innerCount
            1 -> midCount
            else -> outerCount
        }
        val step = (2.0 * PI / ringCount).toFloat()
        val angle = (-PI / 2f).toFloat() + (index - ringStart) * step + ring * step / 2f
        val text = item.content.trim().replace("\n", " ")
        MemNode(
            id = item.id,
            ring = ring,
            angle = angle,
            label = if (text.length > 8) text.take(8) + "…" else text,
            item = item
        )
    }
}

/**
 * 连边：优先「共享任一 tag」；全局一个 tag 都没有时同环相邻相连（保证始终是网状不是散点）；
 * 有 tag 但仍悬空的孤点也接回同环邻居——图谱不允许出现散点。
 */
private fun buildMemoryEdges(nodes: List<MemNode>): List<Pair<MemNode, MemNode>> {
    val edges = mutableListOf<Pair<MemNode, MemNode>>()
    val anyTag = nodes.any { it.item.tags.isNotEmpty() }
    if (anyTag) {
        for (i in nodes.indices) {
            val tagsI = nodes[i].item.tags.toSet()
            for (j in i + 1 until nodes.size) {
                if (nodes[j].item.tags.any { it in tagsI }) edges += nodes[i] to nodes[j]
            }
        }
    } else {
        nodes.groupBy { it.ring }.values.forEach { ringNodes ->
            for (i in ringNodes.indices) edges += ringNodes[i] to ringNodes[(i + 1) % ringNodes.size]
        }
        return edges
    }
    // 兜底：悬空孤点接回同环邻居（去重，避免两节点环互连出重复边）
    val linked = edges.flatMap { listOf(it.first.id, it.second.id) }.toSet()
    nodes.groupBy { it.ring }.values.forEach { ringNodes ->
        if (ringNodes.size <= 1) return@forEach
        ringNodes.forEachIndexed { i, node ->
            if (node.id !in linked) {
                val next = ringNodes[(i + 1) % ringNodes.size]
                val exists = edges.any {
                    (it.first.id == node.id && it.second.id == next.id) ||
                        (it.first.id == next.id && it.second.id == node.id)
                }
                if (!exists) edges += node to next
            }
        }
    }
    return edges
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
