package com.example.agentkit.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        // 被动查询模式：Agent 主动调方法读取/操作，不监听事件。
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

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val node = findNode(root) { n ->
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                t.equals(text, ignoreCase = true) || d.equals(text, ignoreCase = true)
            }
            if (node == null) {
                false
            } else {
                val b = Rect()
                node.getBoundsInScreen(b)
                node.recycle()
                dispatchTap(((b.left + b.right) / 2f), ((b.top + b.bottom) / 2f), 1)
            }
        } catch (e: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    fun clickByResourceId(id: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val node = findNode(root) { n -> n.viewIdResourceName?.endsWith(id) == true }
            if (node == null) {
                false
            } else {
                val b = Rect()
                node.getBoundsInScreen(b)
                node.recycle()
                dispatchTap(((b.left + b.right) / 2f), ((b.top + b.bottom) / 2f), 1)
            }
        } catch (e: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, pred)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    companion object {
        private const val MAX_NODES = 250
    }
}
