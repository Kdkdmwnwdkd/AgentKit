package com.example.agentkit.skill

import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.FailureGuard
import com.example.agentkit.core.ToolCall
import com.example.agentkit.core.ToolRegistry
import com.example.agentkit.core.agent.AgentScope
import com.example.agentkit.core.agent.DEFAULT_READ_ONLY_BUDGET_MS
import com.example.agentkit.core.agent.DEFAULT_WRITE_BUDGET_MS
import com.example.agentkit.core.agent.NoopHooks
import com.example.agentkit.core.agent.RegistryToolExecutor
import com.example.agentkit.core.agent.ToolCallOutcome
import com.example.agentkit.core.agent.ToolDescriptor
import com.example.agentkit.core.agent.ToolExecutor
import com.example.agentkit.core.agent.currentAgentScope
import com.example.agentkit.core.agent.journal.RunScope
import com.example.agentkit.core.agent.journal.ToolSettleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.javascript.ClassShutter
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

/** 脚本单次求值超时上限（毫秒）：超过即放弃脚本结果、回退提示词形态。 */
private const val SCRIPT_TIMEOUT_MS = 10_000L

/** 脚本一律禁止触碰的 Java 包名前缀：JDK（反射/IO/网络）、Rhino 自身、宿主 App 内部类。 */
private val BLOCKED_CLASS_PREFIXES = arrayOf(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "org.mozilla.javascript.",
    "com.example.agentkit."
)

/**
 * 基于 Mozilla Rhino 的 JS 引擎：纯 Java 实现，无需 NDK/原生库，可直接在 Android ART 上运行。
 * 选用它而非 QuickJS，是因为本工程可在无原生编译链的环境直接落地脚本能力；
 * 后续若需更小更快，可新增 QuickJsEngine 实现本接口、在 [SkillExecutor] 处替换即可。
 *
 * 沙箱加固（L11）：
 *  1. ClassShutter 屏蔽上表中的包：脚本拿不到 Packages.java.net.Socket / Class.forName / 宿主内部类，
 *     唯一出口是注入的 agent 桥（其工具调用受 manifest 白名单 + L5 全局能力门双重约束）。
 *  2. optimizationLevel = -1（解释执行），为后续接入指令计数看门狗留口子。
 *  3. withTimeoutOrNull(SCRIPT_TIMEOUT_MS) 超时看门狗。
 *
 * ⚠️ 超时语义说明：Rhino 1.7.14 **无法强制中断一个已经在跑的死循环求值线程**——
 * 超时只会取消协程、让本函数立刻返回 null 放弃结果，Rhino 那个线程仍会继续空转（可能永不结束，
 * 线程与 Context 都会泄漏）。所以这里的策略是「不等它」：由 [SkillExecutor.runSkill]
 * 记 ERROR 日志并回退提示词形态，保证用户侧不会被卡死。
 * 真正可强 kill 的中断需要后续换成 QuickJS（有原生中断钩子）才能做到。
 */
class RhinoJsEngine : JsEngine {
    override suspend fun eval(source: String, bindings: Map<String, Any?>): Any? =
        withTimeoutOrNull(SCRIPT_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                val cx = org.mozilla.javascript.Context.enter()
                try {
                    cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
                    cx.optimizationLevel = -1
                    // 注入给脚本的宿主对象（agent 桥）必须放行：Rhino 在把 Java 对象包装进 JS 作用域时
                    // 就会对它的类做 shutter 校验，一刀切屏蔽 com.example.agentkit.* 会让脚本形态彻底失效
                    // （连 agent.http / agent.tool 都用不了）。因此这里只放行真正注入进来的那些类。
                    val injectedClasses = bindings.values
                        .filterNotNull()
                        .map { it.javaClass.name }
                        .filterNot { name -> BLOCKED_CLASS_PREFIXES.any { p -> name.startsWith(p) } }
                        .toSet() + SkillRuntimeBridge::class.java.name
                    cx.setClassShutter(ClassShutter { className ->
                        className in injectedClasses ||
                            !BLOCKED_CLASS_PREFIXES.any { p -> className.startsWith(p) }
                    })
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
        }

    override fun close() {}
}

/**
 * 暴露给 Skill 脚本的安全运行时桥：脚本通过它调用工具与环境。
 * 所有工具调用都经过 [SkillManifest.tools] 白名单校验，越权即拦截。
 *
 * ## R7：脚本的工具调用现在与主路径同规格
 *
 * 改造前这里直接 `registry.execute(call)`——绕开了执行器，于是：
 *  - **没有超时预算**（R2 第二层完全失效）：脚本里一次 `agent.http()` 挂死，
 *    唯一的兜底是外层 10 秒脚本看门狗，而那只是「放弃结果」，Rhino 线程照样卡住；
 *  - **没有熔断**（T04）：脚本可以反复调同一个必失败的工具，把预算耗光；
 *  - **没有回合日志**：脚本调 `agent.writeFile()` 真实改了文件，日志里却一片空白——
 *    崩溃恢复因此会漏判「技能是否已经写过文件」。
 *
 * 现在脚本走 [ToolExecutor]（与主路径同一套预算与熔断），
 * 并在执行域里成对写 `ToolDispatched` / `ToolSettled`（scope=`skill:<id>`）。
 */
class SkillRuntimeBridge(
    private val registry: ToolRegistry,
    private val skill: SkillManifest,
    @Suppress("UNUSED_PARAMETER") private val workspace: File,
    /** R7：工具执行器（超时预算 + 熔断 + DISP 落盘）。由 [SkillExecutor] 构造。 */
    private val executor: ToolExecutor,
    /** R7：本次脚本执行的域（日志句柄 + 作用域 + 步号）。null = 不留日志。 */
    private val agentScope: AgentScope? = null,
    /**
     * R7：最近一次 DISP 写入时的步号（由执行器的 `beforeEffect` 写入）。
     *
     * DISP 与 SETTLE 必须落在同一步：DISP 写在执行前、SETTLE 写在执行后，
     * 中间主回合可能已推进到下一步。用这个共享计数器把步号对齐，
     * 否则恢复器按 (step, callId) 配对时会对不上。
     */
    private val dispatchedStep: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0)
) {
    private val logs = StringBuilder()

    /**
     * R7：脚本内工具调用的序号。
     *
     * 改造前 callId 是 `skill:<id>:<工具名>`——**确定性**的。同一脚本连调两次
     * `agent.readFile()` 会得到**同一个 id**，于是：
     *  - 回喂模型的 ToolMessage 出现重复 id，模型侧配对混乱；
     *  - 回合日志里两条 DISP 共用一个 id，恢复器按 id 配对时会把它们错配
     *    （第一条的 SETTLE 被算到第二条头上），崩溃时判定出的「哪个调用已生效」就是错的。
     *
     * 加序号后每次调用唯一，代价只是 id 变长。
     */
    private val callSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 调用已注册工具。脚本中：agent.tool('http_request', {...}) */
    fun tool(name: String, args: Any?): String {
        // 第一道：manifest 白名单（fail-closed；空表 = 不允许任何工具，要全量必须显式 "*"）
        if (!isToolAllowedByWhitelist(skill.tools, name)) {
            return "⛔ 技能「${skill.name}」无权使用工具 $name（白名单：" +
                (skill.tools.joinToString().ifBlank { "空" }) + "）"
        }
        // 第二道：全局能力门（应用内置真相源，防「自证白名单」越权拿高危权限）
        checkCapabilityGate(name, skill.trust)?.let { return it }
        val obj = toJsonObject(args) ?: return "⛔ 参数无法解析为对象：$args"
        // 第三道：需要人工确认的工具，脚本**不得代调用**。
        //
        // 不做「脚本内弹审批」的原因：JS 求值在非挂起的 Rhino 线程里，审批是 suspend 的；
        // runBlocking 会卡住这条线程，且 SCRIPT_TIMEOUT 的取消无法中断它——用户不点弹窗
        // 就会永久占住一个线程。这里直接拒绝，让模型回到主对话里走正常审批链路，
        // 是当前架构下唯一不会引入死锁的 fail-closed 做法。
        //
        // R4：判据从工具级 `needsApproval` 换成参数级 `needsApprovalFor(args)`。
        // 差别在 shell_run：`ls` 无副作用、`rm -rf` 有——工具级布尔会把两者一起拒掉，
        // 技能脚本于是连「看一眼目录」都做不到。参数级判据与主对话走同一套规则
        // （[com.example.agentkit.core.agent.ShellCommandRules]），不会出现两处口径不一。
        val target = registry.get(name)
        if (target?.needsApprovalFor(obj) == true) {
            return "⛔ 工具 $name 需要用户逐次确认，脚本不能代为调用。" +
                "请在主对话中直接调用该工具（Agent 会弹出确认框）。"
        }
        val call = ToolCall(nextCallId(name), name, obj)
        return runBlocking { executeWithJournal(call, obj) }
    }

    /**
     * R7：执行一次脚本内的工具调用，并补上回合日志的终态。
     *
     * **DISP 不在这里写**——由执行器的 `beforeEffect` 写（见 [buildToolExecutor]）。
     *
     * 这一点很要紧：`DISP` 的语义是「**越过了执行边界**」，而熔断拦截、审批拒绝、
     * 未知工具这些路径都**没有**越过。若在这里先写 DISP、再交给执行器判定，
     * 就会给「根本没执行」的调用留下 DISP——崩溃恢复看到「有 DISP 无 SETTLE」
     * 就会判成「结局未知」，让用户去核对一个从未发生过的写操作。
     *
     * 与主路径保持同一套时序：熔断判定 → 审批 → 写 DISP → 真正执行 → 写 SETTLE。
     */
    private suspend fun executeWithJournal(call: ToolCall, args: JsonObject): String {
        val scope = agentScope
        val turn = scope?.turn
        val step = dispatchedStep.get()
        val descriptor = registry.descriptorOf(call.name)
            ?: ToolDescriptor(call.name, call.name, "{}")
        val outcome = try {
            executor.execute(call, descriptor)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // 取消不是失败：原样上抛，由上层闭合处理。
            throw cancel
        } catch (e: Throwable) {
            turn?.toolSettled(step, call.id, ToolSettleStatus.FAILED)
            return "⛔ 工具 ${call.name} 执行异常：${e.message}"
        }

        val status = if (outcome is ToolCallOutcome.Completed && outcome.message.isError) {
            ToolSettleStatus.FAILED
        } else {
            ToolSettleStatus.fromOutcome(outcome)
        }
        turn?.toolSettled(step, call.id, status)

        return when (outcome) {
            is ToolCallOutcome.Completed -> outcome.message.content
            ToolCallOutcome.Interrupted ->
                "⛔ 工具 ${call.name} 被中断（只读调用，确定未完成，可以直接重试）。"
            ToolCallOutcome.Unknown ->
                "⛔ 工具 ${call.name} 被中断（写操作，状态未知——请先用只读方式核对结果，" +
                    "确认没有生效再决定是否重做；无法确认时告知用户）。"
            ToolCallOutcome.NotDispatched ->
                "⛔ 工具 ${call.name} 未被派发（未执行，可以重试）。"
        }
    }

    /** 唯一 callId：技能 id + 工具名 + 序号（见 [callSeq] 的说明）。 */
    private fun nextCallId(toolName: String): String =
        "skill:${skill.id}:$toolName#${callSeq.incrementAndGet()}"

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
    fun log(msg: Any?) { logs.appendLine(msg?.toString() ?: "null") }

    fun getLog(): String = logs.toString()
}

/** Skill 执行结果。 */
data class SkillRunResult(val text: String, val scriptExecuted: Boolean)

/**
 * Skill 执行器：根据技能形态选择 prompt / script 运行方式。
 * @param jsEngine 可用的 JS 引擎；为 null 时所有技能回退为 prompt 形态。
 * @param readBody 读取技能指令正文（Android 侧从 assets 读取）。
 * @param readEntry 读取技能脚本源码（assets/skills/<id>/<entry>）。
 * @param failureGuard 失败熔断器（R7）。脚本内反复调同一个必失败的工具要被熔断，
 *   否则脚本能把整轮预算耗光。默认新建一个，语义上「技能内自洽」。
 */
class SkillExecutor(
    private val registry: ToolRegistry,
    private val workspace: File,
    private val jsEngine: JsEngine? = null,
    private val readBody: (SkillManifest) -> String,
    private val readEntry: (SkillManifest) -> String?,
    private val failureGuard: FailureGuard = FailureGuard()
) {

    /**
     * R7：脚本专用的工具执行器。
     *
     * - `approval = null`：脚本不得代弹审批。需确认的工具在 [SkillRuntimeBridge]
     *   第三道门就已被拒绝（按参数级判据），执行器这一层不需要再问。
     * - `beforeEffect`：**在这里**写 DISP，而不是在脚本侧写。
     *   执行器的时序是「熔断判定 → 审批 → 写 DISP → 真正执行」——
     *   被熔断或被审批拒绝的调用**没有越过执行边界**，就不该留下 DISP。
     *   若在脚本侧先写 DISP 再交给执行器判定，崩溃恢复会把一个从未执行的写操作
     *   判成「结局未知」，让用户去核对一件没发生过的事。
     *
     * @param scope 本次技能执行的域（日志句柄 + 作用域 + 步号）。
     * @param dispatchedStep 回调：DISP 写入时的步号，供 SETTLE 使用（两者必须同一步）。
     */
    internal fun buildToolExecutor(
        scope: AgentScope?,
        dispatchedStep: java.util.concurrent.atomic.AtomicInteger,
        readOnlyBudgetMs: Long = DEFAULT_READ_ONLY_BUDGET_MS,
        writeBudgetMs: Long = DEFAULT_WRITE_BUDGET_MS
    ): ToolExecutor = RegistryToolExecutor(
        registry = registry,
        failureGuard = failureGuard,
        approval = null,
        hooks = NoopHooks,
        beforeEffect = { call ->
            val step = scope?.currentStep?.get() ?: 0
            dispatchedStep.set(step)
            scope?.turn?.toolDispatched(step, call.id) ?: true
        },
        readOnlyBudgetMs = readOnlyBudgetMs,
        writeBudgetMs = writeBudgetMs
    )

    suspend fun runSkill(skill: SkillManifest, inputJson: String): SkillRunResult {
        val entry = skill.entry?.let { readEntry(skill) }
        if (entry != null && jsEngine != null) {
            // 非 PACKAGED 来源禁止执行脚本，强制回退提示词形态（契约化只读的核心约束）
            if (!policyFor(skill.trust).allowScript) {
                return SkillRunResult(
                    "⚠️ 技能「${skill.name}」来源「${skill.trust}」不允许执行脚本，已回退为提示词模式。\n\n${readBody(skill)}",
                    false
                )
            }
            // R7：派生技能作用域。日志句柄与主回合**同一个文件**（派生句柄），
            // 事件带上 skill:<id> 标签——崩溃恢复时能说清「这次写操作是技能干的」。
            val scope = skillScope(skill)
            // DISP 与 SETTLE 必须落在同一步：DISP 由执行器写（执行前），
            // SETTLE 由脚本侧写（执行后），中间主回合可能已经推进到下一步。
            // 用这个共享计数器把 DISP 的步号传给 SETTLE。
            val dispatchedStep = java.util.concurrent.atomic.AtomicInteger(0)
            val toolExecutor = buildToolExecutor(scope, dispatchedStep)
            return runCatching {
                val bridge = SkillRuntimeBridge(
                    registry, skill, workspace, toolExecutor, scope, dispatchedStep
                )
                val engine = jsEngine!!
                val out = engine.eval(entry, mapOf("input" to inputJson, "agent" to bridge))
                // eval 返回 null 即超时（看门狗放弃了这个脚本的结果）；
                // 脚本显式 return null 也走同一分支，代价可接受——宁可回退提示词也不让用户干等。
                if (out == null) {
                    AgentLogger.e("Skill", "脚本执行超时：${skill.name}")
                    return@runCatching SkillRunResult(
                        "⚠️ 脚本执行超时（>10s），已回退为提示词模式。\n\n${readBody(skill)}",
                        false
                    )
                }
                SkillRunResult(resultToString(out, bridge), true)
            }.getOrElse { e ->
                SkillRunResult("⚠️ 技能脚本执行失败：${e.message}\n\n${readBody(skill)}", false)
            }
        }
        return SkillRunResult(readBody(skill), false)
    }

    /**
     * R7：派生技能的执行域。
     *
     * 拿不到当前域（不在回合里，或本回合无日志）时返回 null——
     * 脚本照常执行，只是不留日志，与改造前一致。
     */
    private suspend fun skillScope(skill: SkillManifest): AgentScope? {
        val parent = currentAgentScope() ?: return null
        val name = RunScope.skill(skill.id)
        return AgentScope(
            turn = parent.turn?.scoped(name),
            scope = name,
            currentStep = parent.currentStep
        )
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
