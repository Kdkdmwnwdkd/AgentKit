package com.example.agentkit.skill

import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Skill 执行引擎（Phase 2 核心，原创实现，思路借鉴 RikkaHub 的 QuickJS 脚本 + Coomi 的 Skill 路由）：
 *
 * 一个 Skill 现在有两种运行形态：
 *  1. prompt 形态（无 entry）：只把指令正文作为 use_skill 的返回，由模型自行遵循（RikkaHub 的 use_skill 思路）。
 *  2. script 形态（有 entry + 可用 JsEngine）：执行 main.js，脚本通过 [SkillRuntimeBridge] 安全调用工具。
 *
 * 脚本与工具之间隔离在 [SkillRuntimeBridge] 里：脚本只能调用技能 manifest 中声明过的 tools 白名单，
 * 越权调用会被拦截；这对应你计划的「Skill 只提供工具白名单 + 权限」。
 */

/** JS 执行引擎抽象：把脚本与具体引擎（Rhino / 未来的 QuickJS）解耦。 */
interface JsEngine {
    suspend fun eval(source: String, bindings: Map<String, Any?>): Any?
    fun close()
}

/**
 * 基于 Mozilla Rhino 的 JS 引擎：纯 Java 实现，无需 NDK/原生库，可直接在 Android ART 上运行。
 * 选用它而非 QuickJS，是因为本工程可在无原生编译链的环境直接落地脚本能力；
 * 后续若需更小更快，可新增 QuickJsEngine 实现本接口、在 [SkillExecutor] 处替换即可。
 */
class RhinoJsEngine : JsEngine {
    override suspend fun eval(source: String, bindings: Map<String, Any?>): Any? = withContext(Dispatchers.Default) {
        val cx = org.mozilla.javascript.Context.enter()
        try {
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            for ((k, v) in bindings) scope.put(k, scope, org.mozilla.javascript.Context.javaToJS(v, scope))
            // 包成函数表达式：(source)；若求值结果是个函数，则注入 (input, agent) 调用
            val value = cx.evaluateString(scope, "($source)", "skill.js", 1, null)
            if (value is org.mozilla.javascript.Function) {
                val input = bindings["input"]
                val agent = bindings["agent"]
                return@withContext value.call(cx, scope, scope, arrayOf(input, agent))
            }
            return@withContext value
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    override fun close() {}
}

/**
 * 暴露给 Skill 脚本的安全运行时桥：脚本通过它调用工具与环境。
 * 所有工具调用都经过 [SkillManifest.tools] 白名单校验，越权即拦截。
 */
class SkillRuntimeBridge(
    private val registry: ToolRegistry,
    private val skill: SkillManifest,
    @Suppress("UNUSED_PARAMETER") private val workspace: File
) {
    private val logs = StringBuilder()

    /** 调用已注册工具。脚本中：agent.tool('http_request', {...}) */
    fun tool(name: String, args: Any?): String {
        if (skill.tools.isNotEmpty() && name !in skill.tools) {
            return "⛔ 技能「${skill.name}」无权使用工具 $name（白名单：${skill.tools.joinToString()}）"
        }
        val obj = toJsonObject(args) ?: return "⛔ 参数无法解析为对象：$args"
        val call = ToolCall("skill:${skill.id}:$name", name, obj)
        return registry.execute(call).content
    }

    /** 读取工作区文件。脚本中：agent.readFile('notes.txt') */
    fun readFile(path: String): String = tool("read_file", mapOf("path" to path))

    /** 写入工作区文件。脚本中：agent.writeFile('out.txt', 'hi') */
    fun writeFile(path: String, content: String): String = tool("write_file", mapOf("path" to path, "content" to content))

    /** 发起 HTTP 请求。脚本中：agent.http('GET', 'https://...') */
    fun http(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val args = mutableMapOf<String, Any?>("method" to method, "url" to url)
        if (body != null) args["body"] = body
        if (headers.isNotEmpty()) args["headers"] = headers
        return tool("http_request", args)
    }

    /** 发送通知。脚本中：agent.notify('标题', '内容') */
    fun notify(title: String, text: String): String = tool("send_notification", mapOf("title" to title, "text" to text))

    /** 记录脚本运行日志（会随结果一并返回给模型，便于调试）。 */
    fun log(msg: Any?): Unit = logs.appendLine(msg?.toString() ?: "null")

    fun getLog(): String = logs.toString()
}

/** Skill 执行结果。 */
data class SkillRunResult(val text: String, val scriptExecuted: Boolean)

/**
 * Skill 执行器：根据技能形态选择 prompt / script 运行方式。
 * @param jsEngine 可用的 JS 引擎；为 null 时所有技能回退为 prompt 形态。
 * @param readBody 读取技能指令正文（Android 侧从 assets 读取）。
 * @param readEntry 读取技能脚本源码（assets/skills/<id>/<entry>）。
 */
class SkillExecutor(
    private val registry: ToolRegistry,
    private val workspace: File,
    private val jsEngine: JsEngine? = null,
    private val readBody: (SkillManifest) -> String,
    private val readEntry: (SkillManifest) -> String?
) {
    suspend fun runSkill(skill: SkillManifest, inputJson: String): SkillRunResult {
        val entry = skill.entry?.let { readEntry(skill) }
        if (entry != null && jsEngine != null) {
            return runCatching {
                val bridge = SkillRuntimeBridge(registry, skill, workspace)
                val out = jsEngine.eval(entry, mapOf("input" to inputJson, "agent" to bridge))
                SkillRunResult(resultToString(out, bridge), true)
            }.getOrElse { e ->
                SkillRunResult("⚠️ 技能脚本执行失败：${e.message}\n\n${readBody(skill)}", false)
            }
        }
        return SkillRunResult(readBody(skill), false)
    }

    private fun resultToString(out: Any?, bridge: SkillRuntimeBridge): String {
        val s = when (out) {
            null -> ""
            is String -> out
            else -> out.toString()
        }
        val log = bridge.getLog().trim()
        return if (log.isNotEmpty()) "$s\n\n--- skill log ---\n$log" else s
    }
}

/** 把脚本传来的参数（Rhino NativeObject / JsonObject / String / Map）归一为 JsonObject。 */
private fun toJsonObject(args: Any?): JsonObject? = when (args) {
    null -> null
    is JsonObject -> args
    is String -> runCatching { Json.parseToJsonElement(args) }.getOrNull() as? JsonObject
    is Map<*, *> -> mapToJsonObject(args)
    else -> null
}

private fun mapToJsonObject(m: Map<*, *>): JsonObject = buildJsonObject {
    for ((k, v) in m) {
        val key = k?.toString() ?: continue
        when (v) {
            null -> put(key, JsonNull)
            is String -> put(key, v)
            is Number -> put(key, v.toDouble())
            is Boolean -> put(key, v)
            is Map<*, *> -> put(key, mapToJsonObject(v))
            is List<*> -> put(key, listToJsonArray(v))
            else -> put(key, v.toString())
        }
    }
}

private fun listToJsonArray(list: List<*>): JsonArray = JsonArray(list.map { item ->
    when (item) {
        null -> JsonNull
        is String -> JsonPrimitive(item)
        is Number -> JsonPrimitive(item.toDouble())
        is Boolean -> JsonPrimitive(item)
        is Map<*, *> -> mapToJsonObject(item)
        is List<*> -> listToJsonArray(item)
        else -> JsonPrimitive(item.toString())
    }
})
