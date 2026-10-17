package com.example.agentkit.tools

import com.example.agentkit.browser.WebViewBrowser
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * W-15 浏览器工具五件套（对齐 Operit 的 browser_* / AiCode BrowserTool）：
 *   web_open → web_snapshot → web_click / web_type → web_snapshot 循环。
 *
 * 与 http_request 的分工：
 *   http_request 只拿 HTML 文本（无登录态、无 JS）；本组工具跑真实 WebView，
 *   有 Cookie/登录态、能点按钮、能填表单——`http_request` 拿不到的页面用它。
 *
 * 执行面：WebView 由 [com.example.agentkit.browser.BrowserHost] 挂在 MainActivity 上，
 * 全程无界面弹出；App 被系统回收后工具会明确报「后台浏览器不可用」。
 */

/** 共享的浏览器实例（无状态，可复用）。 */
private val browser = WebViewBrowser()

private fun notReadyHint(): String =
    "后台浏览器未就绪：先回到 AgentKit 主界面（App 在前台时 WebView 才可用），再重试。"

class WebOpenTool : AgentTool {
    override val name = "web_open"
    override val description =
        "用内置浏览器打开网页并等待加载（保留 Cookie/登录态，可后续点击、填表）。" +
            "只读网页正文请优先 http_request；需要登录、JS 渲染或点击时才用本工具。"

    override val systemPrompt =
        "网页任务流程：web_open 打开 → web_snapshot 看结构和可点击元素 → web_click/web_type 操作 → " +
            "再 web_snapshot 确认结果。不要用 web_open 反复刷新同一页面。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("url", "string", "要打开的完整 URL（含 https://）"),
        JsonSchema.prop("timeout_ms", "integer", "等待加载的超时毫秒数，默认 20000")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isBlank()) {
            return ToolResult.fail("missing_param", "缺少 url", "传入完整地址，例如 https://example.com")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.fail(
                "bad_url", "url 必须是以 http:// 或 https:// 开头的完整地址。",
                "补全协议后重试，例如 https://example.com。"
            )
        }
        val timeout = args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 20_000L
        return browser.open(url, timeout.coerceIn(3_000L, 60_000L)).fold(
            onSuccess = { finalUrl ->
                ToolResult.ok("web_opened", "已打开：$finalUrl", data = "下一步可用 web_snapshot 查看页面结构。")
            },
            onFailure = { e ->
                ToolResult.fail("web_open_failed", "打开失败：${e.message}", notReadyHint())
            }
        )
    }
}

class WebSnapshotTool : AgentTool {
    override val name = "web_snapshot"
    override val description =
        "读取当前网页的结构摘要：标题、URL、可交互元素编号列表、正文摘要。" +
            "操作页面前先调用它，确认要点哪个元素。"

    override val systemPrompt =
        "web_snapshot 返回的是可交互元素清单（序号 + 标签 + 文本 + id/name）。" +
            "点击时优先用「文本」或「#id」，比 nth-child 选择器稳定。"

    override val parameters = JsonSchema.objectSchema(required = emptyList())

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult =
        browser.snapshot().fold(
            onSuccess = { text ->
                if (text.isBlank()) {
                    ToolResult.fail("web_empty", "页面没有可读内容（可能是空白页或未加载完成）。", "先 web_open，再重试。")
                } else {
                    ToolResult.ok("web_snapshot_ok", "页面结构如下：", data = text.take(6_000))
                }
            },
            onFailure = { e -> ToolResult.fail("web_snapshot_failed", "读取页面失败：${e.message}", notReadyHint()) }
        )
}

class WebClickTool : AgentTool {
    override val name = "web_click"
    override val description =
        "点击网页元素：传 CSS 选择器（#id / .class）或元素的可见文本（如「登录」）。"

    override val systemPrompt =
        "点击优先用元素文本（来自 web_snapshot 的列表），或用 #id；点击后再 web_snapshot 确认页面变化。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("target", "string", "CSS 选择器或元素可见文本")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val target = args["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (target.isBlank()) return ToolResult.fail("missing_param", "缺少 target", "传 CSS 选择器或可见文本。")
        return browser.click(target).fold(
            onSuccess = { r ->
                when {
                    r == "NOT_FOUND" -> ToolResult.fail(
                        "web_not_found",
                        "页面上没有找到「$target」。",
                        "先 web_snapshot 看当前可交互元素，确认文本或改用 #id。"
                    )
                    else -> ToolResult.ok("web_clicked", "已点击：${r.removePrefix("CLICKED:")}", data = "可用 web_snapshot 确认结果。")
                }
            },
            onFailure = { e -> ToolResult.fail("web_click_failed", "点击失败：${e.message}", notReadyHint()) }
        )
    }
}

class WebTypeTool : AgentTool {
    override val name = "web_type"
    override val description = "在网页输入框填写内容（支持 React/Vue 受控组件），selector 可为 #id 或 input[name=xx]。"

    override val systemPrompt = "填表顺序：web_snapshot 找到输入框的 #id 或 name → web_type → 最后 web_click 提交按钮。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("selector", "string", "输入框的 CSS 选择器，如 #search 或 input[name=q]"),
        JsonSchema.prop("text", "string", "要填入的文本")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val text = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (selector.isBlank()) return ToolResult.fail("missing_param", "缺少 selector", "传输入框的 CSS 选择器。")
        return browser.type(selector, text).fold(
            onSuccess = { r ->
                if (r == "NOT_FOUND") {
                    ToolResult.fail(
                        "web_not_found",
                        "没有找到输入框「$selector」。",
                        "先 web_snapshot；可用 #id 或 input[name=xxx] 精确定位。"
                    )
                } else {
                    ToolResult.ok("web_typed", "已填入 ${text.length} 个字符。", data = "如已填完，可 web_click 提交。")
                }
            },
            onFailure = { e -> ToolResult.fail("web_type_failed", "输入失败：${e.message}", notReadyHint()) }
        )
    }
}

class WebEvalTool : AgentTool {
    override val name = "web_eval"
    override val description =
        "在当前网页执行一段 JavaScript 并返回结果（用于点击/输入覆盖不到的场景，如滚动、读取特定数据）。"

    override val systemPrompt =
        "web_eval 是兜底手段：优先用 web_snapshot/web_click/web_type。脚本必须返回字符串或简单值，不要返回 DOM 对象。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("script", "string", "要执行的 JS 表达式或 IIFE，最后 return 一个字符串")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val script = args["script"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (script.isBlank()) return ToolResult.fail("missing_param", "缺少 script", "传入一段返回字符串的 JS。")
        return browser.eval(script).fold(
            onSuccess = { r ->
                ToolResult.ok("web_eval_ok", "执行完成", data = r.take(6_000).ifBlank { "(无返回值)" })
            },
            onFailure = { e -> ToolResult.fail("web_eval_failed", "执行失败：${e.message}", notReadyHint()) }
        )
    }
}
