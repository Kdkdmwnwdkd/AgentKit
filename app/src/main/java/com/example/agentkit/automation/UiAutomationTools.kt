package com.example.agentkit.automation

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自动化工具组：把 AgentAccessibilityService 的能力包装成标准 AgentTool，
 * 注册进 ToolRegistry 后，LLM 即可通过 function calling 直接操作手机。
 *
 * 全部基于 AutomationBridge 做状态校验——服务未连接或总开关未开时直接返回错误提示，
 * 不会触发任何真实动作（总开关即安全阀）。动作执行统一切到主线程，
 * 因为 Accessibility API 在主线程调用最稳定。
 *
 * T02：全部返回统一契约 ToolResult{ok, code, message, hint, data}，失败 hint 必填。
 * T03：readOnly 标注——ui_tree 只读；ui_tap/ui_long_press/ui_swipe/ui_input/ui_key/ui_click
 * 均为写操作（真实改变屏幕状态），含任一写操作的批次整批串行。
 */

private fun notReady(): ToolResult =
    ToolResult.fail(
        code = "service_not_ready",
        message = "自动化未启用：请在「设置 → 自动化」中开启总开关，并到系统设置授予 AgentKit 无障碍权限。",
        hint = "引导用户开启自动化总开关与无障碍权限后重试；勿重复调用同一自动化工具。"
    )

/** 在主线程调用服务方法，并把 Boolean 成败转成 ToolResult（统一契约）。 */
private suspend fun runOnService(
    okCode: String,
    okMsg: String,
    failCode: String,
    failMsg: String,
    hint: String,
    block: suspend (AgentAccessibilityService) -> Boolean
): ToolResult {
    val svc = AutomationBridge.service
    if (!AutomationBridge.isReady() || svc == null) return notReady()
    return try {
        val ok = withContext(Dispatchers.Main) { block(svc) }
        if (ok) ToolResult.ok(code = okCode, message = okMsg)
        else ToolResult.fail(code = failCode, message = failMsg, hint = hint)
    } catch (e: Exception) {
        ToolResult.fail(
            code = "exception",
            message = "执行异常：${e.message}",
            hint = "确认自动化总开关与无障碍权限仍然有效，然后重试；持续失败请告知用户。"
        )
    }
}

private fun intArg(args: JsonObject, key: String): Int? =
    args[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun strArg(args: JsonObject, key: String): String? =
    args[key]?.jsonPrimitive?.contentOrNull

/* ------------------------------------------------------------------ */
/* ui_tree                                                             */
/* ------------------------------------------------------------------ */
class UiTreeTool : AgentTool {
    override val name = "ui_tree"
    override val description =
        "读取当前屏幕的 UI 结构（精简 JSON 数组）。每个元素含 text/desc（内容描述）/id/cls（类名）/bounds（left,top,right,bottom 像素坐标）/clickable/scrollable/editable/checked。在分析屏幕或决定点哪里之前先调用它；ui_tap/ui_click 的坐标与目标都以它为准。每次操作后界面已变，需重新调用本工具；同一界面内容未变时不要重复调用。"
    override val systemPrompt =
        "你可以通过 ui_tree 读取当前屏幕结构，并用 ui_tap/ui_long_press/ui_swipe/ui_input/ui_key/ui_click 操作手机——这是操控界面的唯一可行通道。坐标取自 ui_tree 返回的 bounds（格式 left,top,right,bottom）。每次操作后重新 ui_tree 确认界面；界面内容未变则不要重复读取。"
    override val parameters = JsonSchema.objectSchema()

    /** 只读：仅读取界面结构，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = AutomationBridge.service
        if (!AutomationBridge.isReady() || svc == null) return notReady()
        return try {
            // W-7 P1-1：用带陈旧窗口防护的新版抓取——刚切换窗口时根节点可能没跟上，
            // 会自动轮询等待（≤1.5s），避免模型拿到 [] 或 AgentKit 自己的界面。
            val snap = withContext(Dispatchers.Main) { svc.getFreshUiTreeSnapshot() }
            val msg = buildString {
                append("已读取当前界面结构（${snap.json.length} 字符，保留 ${snap.kept} 个有效节点")
                if (snap.filtered > 0) append("，已过滤 ${snap.filtered} 个纯容器节点")
                append("）")
                if (snap.kept == 0) {
                    // kept=0 且没抛异常：要么界面真在切换中（启动应用/跳转后的过渡态），
                    // 要么当前前台是没有可读节点的界面。给模型明确的自纠指引。
                    append("。未读到可交互节点——可能界面正在切换（如刚打开新应用/页面），稍等片刻重新调用本工具；")
                    append("若持续为空，可先用 device_foreground_app 确认前台应用，或用 ui_screenshot 直接看屏幕。")
                }
            }
            ToolResult.ok(code = "ui_tree_ok", message = msg, data = snap.json)
        } catch (e: Exception) {
            ToolResult.fail(
                code = "ui_tree_error",
                message = "读取界面失败：${e.message}",
                hint = "确认无障碍服务在线；稍后重试或改用 ui_screenshot 看屏幕。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* ui_tap / ui_long_press                                             */
/* ------------------------------------------------------------------ */
class UiTapTool : AgentTool {
    override val name = "ui_tap"
    /** 危险操作：真实点击屏幕，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "在屏幕坐标 (x, y) 处点击一次。坐标必须取自 ui_tree 返回元素的 bounds 中心点：(left+right)/2, (top+bottom)/2。当 ui_click 按文本/ID 找不到元素时，用它按 ui_tree 的 bounds 坐标兜底点击。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("x", "integer", "点击的 X 坐标（像素）"),
        JsonSchema.prop("y", "integer", "点击的 Y 坐标（像素）")
    )

    /** 写操作：真实点击屏幕，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val x = intArg(args, "x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 x",
                hint = "ui_tap 需要整数坐标 x/y（取自 ui_tree 的 bounds，格式 left,top,right,bottom）。"
            )
        val y = intArg(args, "y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 y",
                hint = "ui_tap 需要整数坐标 x/y（取自 ui_tree 的 bounds，格式 left,top,right,bottom）。"
            )
        return runOnService(
            okCode = "tap_ok",
            okMsg = "已在 ($x, $y) 点击",
            failCode = "tap_failed",
            failMsg = "点击失败（服务可能未激活或坐标无效）",
            hint = "先调 ui_tree 确认界面结构与 bounds 坐标，再用有效坐标重试。"
        ) { it.tap(x, y) }
    }
}

class UiLongPressTool : AgentTool {
    override val name = "ui_long_press"
    /** 危险操作：真实长按屏幕，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description = "在屏幕坐标 (x, y) 处长按约 0.6 秒。坐标来自 ui_tree 的 bounds。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("x", "integer", "长按的 X 坐标（像素）"),
        JsonSchema.prop("y", "integer", "长按的 Y 坐标（像素）")
    )

    /** 写操作：真实长按屏幕，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val x = intArg(args, "x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 x",
                hint = "ui_long_press 需要整数坐标 x/y（取自 ui_tree 的 bounds）。"
            )
        val y = intArg(args, "y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 y",
                hint = "ui_long_press 需要整数坐标 x/y（取自 ui_tree 的 bounds）。"
            )
        return runOnService(
            okCode = "long_press_ok",
            okMsg = "已在 ($x, $y) 长按",
            failCode = "long_press_failed",
            failMsg = "长按失败",
            hint = "先调 ui_tree 确认 bounds 坐标后重试。"
        ) { it.longPress(x, y) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_swipe                                                           */
/* ------------------------------------------------------------------ */
class UiSwipeTool : AgentTool {
    override val name = "ui_swipe"
    /** 危险操作：真实滑动屏幕，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "从 (start_x, start_y) 滑动到 (end_x, end_y)。用于滚动列表、翻页、返回手势等。坐标来自 ui_tree 的 bounds。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("start_x", "integer", "起点 X 坐标"),
        JsonSchema.prop("start_y", "integer", "起点 Y 坐标"),
        JsonSchema.prop("end_x", "integer", "终点 X 坐标"),
        JsonSchema.prop("end_y", "integer", "终点 Y 坐标"),
        JsonSchema.prop("duration_ms", "integer", "滑动时长（毫秒，默认 300，越大越慢）")
    )

    /** 写操作：真实滑动屏幕，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val x1 = intArg(args, "start_x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 start_x",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val y1 = intArg(args, "start_y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 start_y",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val x2 = intArg(args, "end_x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 end_x",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val y2 = intArg(args, "end_y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 end_y",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val dur = intArg(args, "duration_ms") ?: 300
        return runOnService(
            okCode = "swipe_ok",
            okMsg = "已从 ($x1,$y1) 滑动到 ($x2,$y2)",
            failCode = "swipe_failed",
            failMsg = "滑动失败",
            hint = "确认坐标在屏幕范围内；可先 ui_tree 确认可滚动元素的 bounds。"
        ) { it.swipe(x1, y1, x2, y2, dur.toLong()) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_input                                                           */
/* ------------------------------------------------------------------ */
class UiInputTool : AgentTool {
    override val name = "ui_input"
    /** 危险操作：向当前焦点输入框写入文本，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "把文本写入当前获得焦点的输入框（如搜索框、聊天输入框）。调用前通常先 ui_tree 确认焦点在输入框，或先 ui_tap 点一下输入框。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("text", "string", "要输入的文本")
    )

    /** 写操作：向界面输入文本，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = strArg(args, "text")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 text",
                hint = "ui_input 必须提供 text（要写入输入框的文本）。"
            )
        return runOnService(
            okCode = "input_ok",
            okMsg = "已输入文本：${text.take(60)}",
            failCode = "input_failed",
            failMsg = "输入失败（当前可能没有聚焦的输入框）",
            hint = "先 ui_tree 确认 editable 元素，或先 ui_tap 点一下输入框获取焦点后重试。"
        ) { it.inputText(text) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_key                                                             */
/* ------------------------------------------------------------------ */
class UiKeyTool : AgentTool {
    override val name = "ui_key"
    /** 危险操作：执行 home/back/recent 系统按键，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description = "执行系统级按键动作。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("key", "string", "动作类型", enum = listOf("home", "back", "recent"))
    )

    /** 写操作：执行系统按键，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = strArg(args, "key")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 key",
                hint = "ui_key 必须提供 key，取值 home/back/recent 之一。"
            )
        return runOnService(
            okCode = "key_ok",
            okMsg = "已执行系统动作：$key",
            failCode = "key_failed",
            failMsg = "未知或失败的系统动作：$key",
            hint = "key 仅支持 home/back/recent；请用受支持的取值重试。"
        ) { it.globalAction(key) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_click                                                           */
/* ------------------------------------------------------------------ */
class UiClickTool : AgentTool {
    override val name = "ui_click"
    /** 危险操作：按文本/ID 定位并点按元素，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "按「可见文本」或「resource-id 后缀」定位界面元素并点按，比 ui_tap 更语义化。至少提供 text 或 resource_id 之一。定位失败时会返回屏幕上最接近的候选列表，请用最接近的候选重试一次；仍失败再改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("text", "string", "元素的可见文本或内容描述（可选）"),
        JsonSchema.prop("resource_id", "string", "元素的 resource-id 后缀，如 \"btn_submit\"（可选）")
    )

    /** 写操作：真实点按界面元素，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = strArg(args, "text")
        val rid = strArg(args, "resource_id")
        if (text.isNullOrBlank() && rid.isNullOrBlank()) {
            return ToolResult.fail(
                code = "missing_param",
                message = "ui_click 至少需要 text 或 resource_id 之一",
                hint = "提供元素的可见文本（text）或 resource-id 后缀（resource_id）；可先 ui_tree 查看可用元素。"
            )
        }
        val svc = AutomationBridge.service
        if (!AutomationBridge.isReady() || svc == null) return notReady()
        return try {
            val ok = withContext(Dispatchers.Main) {
                if (!rid.isNullOrBlank()) svc.clickByResourceId(rid)
                else svc.clickByText(text!!)
            }
            if (ok) {
                ToolResult.ok(code = "click_ok", message = "已点击「${text ?: rid}」")
            } else {
                // 真机第三轮 Bug B：失败必须带候选（归一化/包含/前缀都落空时，把屏幕上最相近
                // 的标签告诉模型），否则模型只能瞎换成 ui_tap 硬点坐标。
                // 真机第四轮 Bug（界面操作协议）：把「失败自纠三步」写进 hint——先用候选重试一次，
                // 再退到 ui_tap 按 bounds 坐标，避免模型转投 shell（dumpsys/am start 必因权限失败）。
                val candidates = svc.lastClickCandidates
                val extra = if (candidates.isEmpty()) "" else "屏幕上最相近的候选：${candidates.joinToString("、")}；"
                ToolResult.fail(
                    code = "click_not_found",
                    message = "未找到匹配元素或点击失败",
                    hint = extra + "请从候选里选最接近的「重试一次」；仍失败改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击。" +
                        "已做归一化（去空白/全角↔半角/忽略大小写）+ 包含 + 前缀匹配；也可先 ui_tree 确认元素文本/resource-id 的最新值。"
                )
            }
        } catch (e: Exception) {
            ToolResult.fail(
                code = "exception",
                message = "执行异常：${e.message}",
                hint = "确认自动化服务在线后重试；持续失败请告知用户手动操作。"
            )
        }
    }
}
