package com.example.agentkit.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agentkit.core.AgentLogger
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer

/**
 * UI 节点的**纯数据镜像**（脱离 [AccessibilityNodeInfo]，可在纯 JVM 下断言）。
 *
 * 为什么要有这层：真机第三轮 Bug B 的根因在「按文本定位」的决策逻辑上，而决策不该
 * 绑死在 Android 节点对象上（不可测）。故把「取数据 → 纯函数决策 → 执行动作」三段
 * 拆开：本文件只负责① 取数据（[AgentAccessibilityService.collectSummaries]）与
 * ③ 执行（点坐标），② 全部落在 [UiNodeMatcher] 的纯函数里。
 *
 * @param index 遍历序号（稳定标识，列表下标 == index）
 * @param text 可见文本（可能为脏：带换行/空格/附带其他字）
 * @param desc contentDescription（与 text 同等参与匹配）
 * @param resourceId 短 resource-id（去掉包名前缀）
 * @param depth 树深度（0 = 根）
 * @param clickable 自身是否可点击
 * @param parentIndex 父节点 index（-1 = 根，无父）
 * @param bounds 屏幕坐标 `l,t,r,b`
 */
internal data class UiNodeSummary(
    val index: Int,
    val text: String = "",
    val desc: String = "",
    val resourceId: String = "",
    val depth: Int = 0,
    val clickable: Boolean = false,
    val parentIndex: Int = -1,
    val bounds: String = ""
) {
    /** 参与匹配/展示的标签：优先可见文本，无文本时退回 contentDescription。 */
    val label: String get() = text.ifBlank { desc }
}

/**
 * 匹配结果（纯数据）。
 *
 * @param matchIndex 命中的节点（已是「最深/最具体」的那个）；null = 未命中
 * @param clickIndex 真正用来点坐标的节点：命中节点不可点击时，是**最近的可点击祖先**；
 *   找不到可点击祖先时退回命中节点自身（等价旧行为：按其 bounds 派发手势）
 * @param candidates 未命中时给模型的候选标签（≤5，按相似度降序），用于写进 hint 自纠
 * @param strategy 命中策略（exact/contains/prefix/none/empty_query），仅供日志与断言
 */
internal data class UiMatchResult(
    val matchIndex: Int?,
    val clickIndex: Int?,
    val candidates: List<String> = emptyList(),
    val strategy: String = "none"
) {
    /** 是否命中。 */
    val matched: Boolean get() = matchIndex != null
}

/**
 * 按文本 / resource-id 定位节点的**纯函数决策器**（零 Android 依赖，QA harness 直测）。
 *
 * 修真机第三轮 Bug B：旧实现用 `text.equals(query)` 精确相等，设置页里那一项的真实文本
 * 常常带空格/换行/附带其他字（「字体大小\n默认」），精确匹配必然落空 → 工具报
 * `click_not_found` 且**没有候选**，模型只能瞎试。故改为**分级匹配**：
 *  1. 归一化后精确（去所有空白 + 全角↔半角 NFKC 归一 + 忽略大小写）；
 *  2. 包含（contains）：query 是节点文本的一部分；
 *  3. 前缀（startsWith）。
 * text 与 contentDescription 同等参与，取两者中更好的等级。
 *
 * 多命中时取「最深/最具体」：等级优先 → 深度大者优先 → 文本短者优先 → 序号小者优先。
 * 命中节点 `clickable == false` 时上溯最近的可点击祖先（否则点了没反应）。
 * 未命中时给出 ≤5 条屏幕候选（按包含关系 / 公共前缀打分），帮模型自纠。
 */
internal object UiNodeMatcher {

    /** 候选提示上限（T02 统一契约：hint 不能太长）。 */
    const val MAX_CANDIDATES: Int = 5

    /**
     * 归一化：NFKC（全角→半角）→ 去掉所有空白字符 → 转小写。
     * 「字体 大小」「Ｆｏｎｔ」这类脏文本据此与干净 query 对齐。
     */
    internal fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            if (!ch.isWhitespace()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * 按可见文本 / contentDescription 定位节点。
     *
     * @param nodes 节点摘要（index 与列表下标一致）
     * @param query 目标文本（来自工具参数，可能带空格/全角）
     * @param maxCandidates 未命中时的候选条数上限
     */
    internal fun matchByText(
        nodes: List<UiNodeSummary>,
        query: String,
        maxCandidates: Int = MAX_CANDIDATES
    ): UiMatchResult {
        val q = normalize(query)
        if (q.isEmpty()) {
            // query 为空（工具侧已拦截，这里是防御）：不命中，但把屏幕上前几个标签兜底
            // 列出来，模型至少知道页面上有什么
            return UiMatchResult(null, null, fallbackCandidates(nodes, maxCandidates), "empty_query")
        }
        val best = pickBest(nodes) { levelOfText(it, q) }
        if (best == null) {
            return UiMatchResult(null, null, similarCandidates(nodes, q, maxCandidates), "none")
        }
        val (index, level) = best
        return UiMatchResult(index, clickableTarget(nodes, index), emptyList(), strategyName(level))
    }

    /**
     * 按 resource-id 定位节点（短 id，去掉包名前缀）。
     * 等级：0 = 相等或以 `id:` 后缀相等，1 = 包含；其余同 [matchByText] 的选取规则。
     */
    internal fun matchByResourceId(
        nodes: List<UiNodeSummary>,
        query: String,
        maxCandidates: Int = MAX_CANDIDATES
    ): UiMatchResult {
        val q = normalize(query)
        if (q.isEmpty()) {
            return UiMatchResult(null, null, fallbackIdCandidates(nodes, maxCandidates), "empty_query")
        }
        val best = pickBest(nodes) { levelOfId(it.resourceId, q) ?: -1 }
        if (best == null) {
            return UiMatchResult(null, null, fallbackIdCandidates(nodes, maxCandidates), "none")
        }
        val (index, level) = best
        return UiMatchResult(index, clickableTarget(nodes, index), emptyList(), strategyName(level))
    }

    /**
     * 上溯最近的可点击祖先：命中节点自身可点 → 自身；否则沿 [UiNodeSummary.parentIndex]
     * 向上找第一个 `clickable == true` 的祖先；一路到根仍不可点 → 退回自身（旧行为）。
     *
     * @return 用于取 bounds 派发点击的节点 index
     */
    internal fun clickableTarget(nodes: List<UiNodeSummary>, index: Int): Int {
        val byIndex = HashMap<Int, UiNodeSummary>(nodes.size)
        for (n in nodes) byIndex[n.index] = n
        var cursor = index
        var guard = 0
        while (guard++ <= nodes.size) {
            val node = byIndex[cursor] ?: return index
            if (node.clickable) return node.index
            val parent = node.parentIndex
            // 一路到根都不可点：退回**命中节点自身**（等价旧行为：按其 bounds 派发手势）
            if (parent < 0) return index
            cursor = parent
        }
        return index
    }

    /* ------------------------- 内部：打分与选取 ------------------------- */

    /** 节点文本 / desc 对 query 的匹配等级：0 精确 / 1 包含 / 2 前缀 / -1 不命中。 */
    private fun levelOfText(node: UiNodeSummary, q: String): Int {
        val t = normalize(node.text)
        val d = normalize(node.desc)
        val candidates = ArrayList<Int>(2)
        if (t.isNotEmpty()) levelOf(t, q)?.let { candidates += it }
        if (d.isNotEmpty()) levelOf(d, q)?.let { candidates += it }
        return candidates.minOrNull() ?: -1
    }

    /**
     * [text] 对 [q] 的匹配等级（null = 不命中）：
     *  0 归一化精确 / 1 包含（`text` 含 `q`，应对「字体大小\n默认」这类脏文本）/
     *  2 前缀（`q` 以 `text` 开头，即节点文本是 query 的前缀——query 带了尾巴，
     *     如 query「字体大小 默认」vs 节点「字体大小」）。
     * 注：等级 2 必须放在 1 之后判断（1 涵盖面更广），且取「节点更短」的那一侧，
     * 这样三级才各自可达、不互相遮蔽。
     */
    private fun levelOf(text: String, q: String): Int? = when {
        text == q -> 0
        text.contains(q) -> 1
        q.startsWith(text) -> 2
        else -> null
    }

    /** resource-id 的匹配等级：0 相等（含 `:id` 后缀相等）/ 1 包含 / null 不命中。 */
    private fun levelOfId(id: String, q: String): Int? {
        if (id.isEmpty()) return null
        val n = normalize(id)
        if (n.isEmpty()) return null
        return when {
            n == q -> 0
            n.endsWith(":$q") -> 0
            n.contains(q) -> 1
            q.startsWith(n) -> 2
            else -> null
        }
    }

    /**
     * 在所有节点里挑「最深/最具体」的命中：等级优先 → 深度大 → 文本短 → 序号小。
     *
     * @return (index, level)；无命中返回 null
     */
    private fun pickBest(nodes: List<UiNodeSummary>, level: (UiNodeSummary) -> Int): Pair<Int, Int>? {
        var bestIndex: Int? = null
        var bestLevel = Int.MAX_VALUE
        var bestDepth = Int.MIN_VALUE
        var bestLen = Int.MAX_VALUE
        for (node in nodes) {
            val lv = level(node)
            if (lv < 0) continue
            val len = normalize(node.label).length
            val better = bestIndex == null ||
                lv < bestLevel ||
                (lv == bestLevel && node.depth > bestDepth) ||
                (lv == bestLevel && node.depth == bestDepth && len < bestLen)
            if (better) {
                bestIndex = node.index
                bestLevel = lv
                bestDepth = node.depth
                bestLen = len
            }
        }
        return bestIndex?.let { it to bestLevel }
    }

    /** 未命中时的候选：按「包含关系 + 公共前缀」打分，取前 [max] 条（去重、非空）。 */
    private fun similarCandidates(nodes: List<UiNodeSummary>, q: String, max: Int): List<String> {
        val seen = LinkedHashSet<String>()
        val scored = ArrayList<Pair<String, Int>>()
        for (node in nodes) {
            val label = node.label.trim()
            if (label.isBlank()) continue
            if (!seen.add(label)) continue
            val n = normalize(label)
            if (n.isEmpty()) continue
            val score = when {
                n == q -> 1_000
                n.contains(q) -> 800 - n.length.coerceAtMost(200)
                q.contains(n) -> 600 - n.length.coerceAtMost(200)
                else -> commonPrefixLength(n, q) * 10 - n.length.coerceAtMost(200)
            }
            scored += label to score
        }
        scored.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        return scored.take(max.coerceAtLeast(0)).map { it.first }
    }

    /** query 为空的兜底候选：屏幕上前 [max] 个非空标签（按树序，短者优先）。 */
    private fun fallbackCandidates(nodes: List<UiNodeSummary>, max: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (node in nodes) {
            val label = node.label.trim()
            if (label.isNotBlank()) seen.add(label)
        }
        return seen.sortedWith(compareBy<String> { it.length }.thenBy { it }).take(max.coerceAtLeast(0))
    }

    /** resource-id 未命中时的候选：屏幕上出现过的短 id（去重）。 */
    private fun fallbackIdCandidates(nodes: List<UiNodeSummary>, max: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (node in nodes) {
            if (node.resourceId.isNotBlank()) seen.add(node.resourceId)
        }
        return seen.sortedWith(compareBy<String> { it.length }.thenBy { it }).take(max.coerceAtLeast(0))
    }

    /** 两个归一化串的公共前缀长度（相似度兜底打分用）。 */
    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        val max = minOf(a.length, b.length)
        while (i < max && a[i] == b[i]) i++
        return i
    }

    /** 等级 → 策略名（日志/断言可读）。 */
    private fun strategyName(level: Int): String = when (level) {
        0 -> "exact"
        1 -> "contains"
        2 -> "prefix"
        else -> "none"
    }
}

/**
 * AgentKit 无障碍自动化服务（标准 Android AccessibilityService，净室原创实现）。
 *
 * 能力范围（v1）：
 *  - 抓取当前窗口 UI 树（精简 JSON）
 *  - 通过 dispatchGesture 执行 点击 / 长按 / 滑动
 *  - 向焦点输入框写入文本（ACTION_SET_TEXT）
 *  - 系统级动作 home / back / recent
 *  - 按文本或 resource-id 定位元素并点按
 *
 * 所有对外方法均 try/catch 兜底，失败时返回 false / 空数组，绝不向上抛异常，
 * 因为调用方是后台 Agent 线程，异常必须被收敛成工具结果文本。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // L4 主动感知：仅在窗口切换/窗口集合变化时记录前台应用，避免高频噪声。
        // 其余操作仍为 Agent 主动查询模式（被动监听仅用于喂 DeviceContextStore）。
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg.isNotBlank()) DeviceContextStore.updateForeground(this, pkg)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        AutomationBridge.detach(this)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* UI 树抓取                                                           */
    /* ------------------------------------------------------------------ */

    /** 抓取当前窗口 UI 树，输出精简 JSON 数组（上限约 250 个节点）。 */
    fun getUiTreeJson(): String {
        val root = rootInActiveWindow ?: return "[]"
        return try {
            nodeCount = 0
            val arr = buildJsonArray { traverse(root, this) }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        } finally {
            root.recycle()
        }
    }

    private var nodeCount = 0

    private fun traverse(node: AccessibilityNodeInfo, arr: kotlinx.serialization.json.JsonArrayBuilder) {
        if (nodeCount >= MAX_NODES) return
        nodeCount++
        val b = Rect()
        node.getBoundsInScreen(b)
        arr.add(
            buildJsonObject {
                put("text", node.text?.toString().orEmpty())
                put("desc", node.contentDescription?.toString().orEmpty())
                put("id", node.viewIdResourceName?.substringAfter('/').orEmpty())
                put("cls", node.className?.toString()?.substringAfterLast('.').orEmpty())
                put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
                put("clickable", node.isClickable)
                put("scrollable", node.isScrollable)
                put("editable", node.isEditable)
                put("checked", node.isChecked)
            }
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverse(child, arr)
                child.recycle()
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 手势动作（dispatchGesture）                                         */
    /* ------------------------------------------------------------------ */

    private fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(x: Int, y: Int): Boolean = dispatchTap(x.toFloat(), y.toFloat(), 1)

    fun longPress(x: Int, y: Int): Boolean = dispatchTap(x.toFloat(), y.toFloat(), 600)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /* ------------------------------------------------------------------ */
    /* 文本输入                                                           */
    /* ------------------------------------------------------------------ */

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null) target = findFirstEditable(root)
            if (target == null) {
                false
            } else {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (target != root) target.recycle()
                ok
            }
        } catch (e: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocusable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /* ------------------------------------------------------------------ */
    /* 系统级动作                                                         */
    /* ------------------------------------------------------------------ */

    fun globalAction(action: String): Boolean = when (action.lowercase()) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recent", "recents", "overview" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    /* ------------------------------------------------------------------ */
    /* 按文本 / resource-id 点按                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 最近一次按文本 / resource-id 定位**失败**时给出的候选（≤5，按相似度降序）。
     *
     * 供 ui_click 工具写进 `hint` 帮助模型自纠（真机第三轮 Bug B：旧实现失败时只报
     * `click_not_found`，模型看不到屏幕上有什么，只能瞎换成 ui_tap 硬点坐标）。
     * 成功命中 / 新一次匹配开始前清空；只读给工具层，绝不参与匹配判据。
     */
    @Volatile
    var lastClickCandidates: List<String> = emptyList()
        private set

    /**
     * 按可见文本 / contentDescription 定位并点按（分级匹配，见 [UiNodeMatcher]）。
     *
     * @return true = 已按命中节点（或其最近可点击祖先）的坐标派发点击
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            lastClickCandidates = emptyList()
            return false
        }
        return try {
            val nodes = collectSummaries(root)
            val result = UiNodeMatcher.matchByText(nodes, text)
            lastClickCandidates = result.candidates
            if (!result.matched) {
                AgentLogger.w(
                    "UiAutomation",
                    "按文本定位失败：query=${text.take(40)}，候选=${result.candidates.joinToString("、")}"
                )
                false
            } else {
                AgentLogger.i("UiAutomation", "按文本定位命中：策略=${result.strategy}，节点=${result.matchIndex} → 点击 ${result.clickIndex}")
                tapNodeByIndex(nodes, result.clickIndex ?: result.matchIndex!!)
            }
        } catch (e: Exception) {
            lastClickCandidates = emptyList()
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * 按 resource-id 定位并点按；未命中时同样上溯可点击祖先，并把屏幕上的 id 候选
     * 写进 [lastClickCandidates]。
     */
    fun clickByResourceId(id: String): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            lastClickCandidates = emptyList()
            return false
        }
        return try {
            val nodes = collectSummaries(root)
            val result = UiNodeMatcher.matchByResourceId(nodes, id)
            lastClickCandidates = result.candidates
            if (!result.matched) {
                AgentLogger.w(
                    "UiAutomation",
                    "按 resource-id 定位失败：query=${id.take(40)}，候选=${result.candidates.joinToString("、")}"
                )
                false
            } else {
                AgentLogger.i("UiAutomation", "按 resource-id 定位命中：策略=${result.strategy}，节点=${result.matchIndex} → 点击 ${result.clickIndex}")
                tapNodeByIndex(nodes, result.clickIndex ?: result.matchIndex!!)
            }
        } catch (e: Exception) {
            lastClickCandidates = emptyList()
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * 取数据：把当前窗口的 UI 树镜像成 [UiNodeSummary] 列表（纯数据，供纯函数决策）。
     *
     * 深度优先前序；[UiNodeSummary.index] 与列表下标一致，父节点必先于子节点进列表，
     * 故上溯祖先只需按 index 查表。节点数封顶 [MAX_NODES]，避免大屏设置页爆内存。
     */
    private fun collectSummaries(root: AccessibilityNodeInfo, limit: Int = MAX_NODES): List<UiNodeSummary> {
        val out = ArrayList<UiNodeSummary>(limit.coerceAtMost(MAX_NODES))
        fun walk(node: AccessibilityNodeInfo, depth: Int, parentIndex: Int) {
            if (out.size >= limit) return
            val b = Rect()
            node.getBoundsInScreen(b)
            val index = out.size
            out.add(
                UiNodeSummary(
                    index = index,
                    text = node.text?.toString().orEmpty(),
                    desc = node.contentDescription?.toString().orEmpty(),
                    resourceId = node.viewIdResourceName?.substringAfterLast('/').orEmpty(),
                    depth = depth,
                    clickable = node.isClickable,
                    parentIndex = parentIndex,
                    bounds = "${b.left},${b.top},${b.right},${b.bottom}"
                )
            )
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1, index)
                child.recycle()
            }
        }
        walk(root, 0, -1)
        return out
    }

    /** 执行动作：按节点 bounds 的中心派发点击（bounds 非法时退回 false，绝不崩）。 */
    private fun tapNodeByIndex(nodes: List<UiNodeSummary>, index: Int): Boolean {
        val node = nodes.firstOrNull { it.index == index } ?: return false
        val parts = node.bounds.split(",")
        if (parts.size != 4) return false
        val l = parts[0].trim().toIntOrNull() ?: return false
        val t = parts[1].trim().toIntOrNull() ?: return false
        val r = parts[2].trim().toIntOrNull() ?: return false
        val b = parts[3].trim().toIntOrNull() ?: return false
        return dispatchTap(((l + r) / 2f), ((t + b) / 2f), 1)
    }

    companion object {
        private const val MAX_NODES = 250
    }
}
