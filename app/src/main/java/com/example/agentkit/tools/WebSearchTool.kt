package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网页搜索（W-20，缺口清单 #53：Coomi/RikkaHub/Operit/AiCode 四家均有，我们此前的空白）。
 *
 * 走 DuckDuckGo HTML 端点（无需 API Key、无需登录）：
 *  - `https://html.duckduckgo.com/html/` 返回服务端渲染的完整 HTML；
 *  - 解析逻辑在 [WebSearchParsing]（纯函数，harness 可测），本类只负责网络 IO。
 *
 * 为什么不让模型自己用 http_request 抓 DDG：HTML 里 95% 是导航/脚本噪音，
 * 回灌上下文既浪费又容易让模型读错；本工具直接返回「标题 / 链接 / 摘要」结构化清单。
 *
 * 只读：GET 查询无副作用，可与其他只读工具并行。
 */
class WebSearchTool(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : AgentTool {

    override val name = "web_search"
    override val description =
        "联网搜索公开网页，返回最多 8 条结果（标题 + 链接 + 摘要）。" +
            "需要查实时信息、新闻、价格、文档、陌生概念时使用；" +
            "搜索不到时换关键词再试，或改用 web_open 打开具体网页。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("query", "string", "搜索关键词（中文或英文均可，尽量具体）"),
        JsonSchema.prop("max_results", "integer", "返回结果条数，默认 5，最多 8")
    )

    /** 只读：搜索查询无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 query",
                    hint = "web_search 必须提供 query（搜索关键词），请补充后重试。"
                )
            if (query.isEmpty()) {
                return@withContext ToolResult.fail(
                    code = "empty_query",
                    message = "query 不能为空",
                    hint = "请给出具体关键词，如「深圳天气」或「kotlin coroutines 教程」。"
                )
            }
            val max = (args["max_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5)
                .coerceIn(1, 8)

            val form = "q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .post(form)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.code !in 200..299) {
                    return@withContext ToolResult.fail(
                        code = "http_error",
                        message = "搜索服务返回 HTTP ${resp.code}",
                        hint = "搜索引擎暂时不可用；可稍后重试，或改用 http_request 直接抓取已知网页。"
                    )
                }
                val items = WebSearchParsing.parse(resp.body?.string().orEmpty(), max)
                if (items.isEmpty()) {
                    return@withContext ToolResult.fail(
                        code = "no_results",
                        message = "没有搜到「$query」的相关结果",
                        hint = "换更短或更常用的关键词再试；查具体站点内容可用 web_open 直接打开网址。"
                    )
                }
                val out = buildString {
                    items.forEachIndexed { i, r ->
                        append("${i + 1}. ${r.title}\n")
                        append("   ${r.url}\n")
                        if (r.snippet.isNotBlank()) append("   ${r.snippet}\n")
                        append("\n")
                    }
                }.trim()
                ToolResult.ok(
                    code = "search_ok",
                    message = "「$query」搜到 ${items.size} 条结果",
                    data = out
                )
            }
        }.getOrElse {
            ToolResult.fail(
                code = "network_error",
                message = "搜索请求异常：${it.message}",
                hint = "检查网络连接；持续失败可改用 http_request 抓取具体网页。"
            )
        }
    }

    companion object {
        private const val ENDPOINT = "https://html.duckduckgo.com/html/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
