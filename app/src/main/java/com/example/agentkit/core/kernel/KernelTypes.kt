package com.example.agentkit.core.kernel

/**
 * 内核基础类型：只依赖 Kotlin 标准库，不含任何 Android / 序列化库 / 协程库之外的类型。
 *
 * ## 为什么单独一层
 * 现在这些角色由 `core/AgentContract.kt` 兼任：它同时承担「给 LLM 的消息模型」「给 UI 的
 * 渲染模型」「工具契约」三种职责，还被 52 个工具和 4110 行的 ChatViewModel 直接依赖。
 * 内核另起一套更小的类型，是为了让「每轮必经」的代码不被迫拖上 UI 和工具实现的依赖树。
 *
 * 净室声明：全部为本项目自行设计，只借鉴过外部项目「消息分角色、工具调用带 id、
 * 结果单独回填」这一公开的协议结构，未复制其类型、命名或实现。
 */

/** 消息角色。与主流 Chat Completions 协议一一对应，便于适配层直译。 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * 一轮对话中的一条消息（不可变）。
 *
 * @param text 正文。TOOL 角色时是工具输出的文本化结果。
 * @param calls 仅 ASSISTANT 使用：本次回复里模型请求调用的工具。
 * @param callId 仅 TOOL 使用：对应哪一次调用（回填关联键，缺了模型会认不出结果属于谁）。
 * @param name 仅 TOOL 使用：工具名，方便模型和日志定位。
 */
data class Msg(
    val role: Role,
    val text: String = "",
    val calls: List<Call> = emptyList(),
    val callId: String? = null,
    val name: String? = null
) {
    init {
        require(role != Role.TOOL || callId != null) { "TOOL 消息必须带 callId" }
    }

    companion object {
        fun system(text: String) = Msg(Role.SYSTEM, text)
        fun user(text: String) = Msg(Role.USER, text)
        fun assistant(text: String, calls: List<Call> = emptyList()) =
            Msg(Role.ASSISTANT, text, calls = calls)
        fun tool(callId: String, name: String, text: String) =
            Msg(Role.TOOL, text, callId = callId, name = name)
    }
}

/**
 * 模型请求的一次工具调用。
 *
 * @param args 参数（见 [Args]）。内核不解析 JSON，只透传与读单个键。
 */
data class Call(val id: String, val name: String, val args: Args)

/**
 * 工具入参抽象。
 *
 * ## 为什么不直接用 JSON 对象
 * 内核若依赖某个 JSON 库，工具契约就绑死在那个库的版本上；而审批判定只需要「读一个键」。
 * 所以这里只留两件事：原始串（给日志和落盘）+ 按键取值（给审批规则）。适配层负责
 * 把自家 JsonObject 包成这个接口，内核不感知。
 */
interface Args {
    /** 原始 JSON 串（原样透传，不做规范化，避免影响模型缓存与回放）。 */
    val raw: String

    /** 取一个标量参数；不存在或不是标量时返回 null。 */
    fun opt(key: String): String?

    /**
     * 是否「读不出参数」（只有原始串、按键取值一律 null）。
     *
     * 这个标记不是为了省事，是为了**安全**：参数级审批规则（如「`ls` 放行、`rm` 要问」）
     * 在读到 null 时会误判成「不危险」而放行。所以读不到参数的入参必须显式标出来，
     * 让 [ToolSpec.approvalFor] 强制走询问。参见 [RawArgs]。
     */
    val opaque: Boolean get() = false

    companion object {
        val EMPTY: Args = MapArgs(emptyMap(), "{}")
    }
}

/** [Args] 的默认实现：键值皆字符串（数组/对象参数由适配层自行处理，内核不用）。 */
class MapArgs(
    private val values: Map<String, String?>,
    override val raw: String
) : Args {
    override fun opt(key: String): String? = values[key]

    override fun toString(): String = "Args($raw)"

    companion object {
        fun of(vararg pairs: Pair<String, String?>): Args =
            MapArgs(pairs.toMap(), pairs.joinToString(",", "{", "}") { "\"${it.first}\":\"${it.second}\"" })
    }
}

/**
 * 只有原始串、读不出键的 [Args]。
 *
 * 用于「元工具转发」场景：外层参数是 `name` + `args`（一段没解析的 JSON 子串），
 * 内核拿不到子参数，于是审批规则里的参数级放行不会生效——这是刻意的保守设计，
 * 让 `rm -rf` 不可能因为「外层没写参数」而被当成安全调用放行。
 */
class RawArgs(override val raw: String) : Args {
    override fun opt(key: String): String? = null

    override val opaque: Boolean get() = true

    override fun toString(): String = "RawArgs($raw)"
}

/**
 * 模型档位。**这是暴露策略的唯一输入**，不再按 provider 名硬编码白名单。
 *
 * 为什么按「能力」而不是按「provider 字符串」：provider 名会新增（openai/claude/gemini/
 * ollama/本地 llama…），每加一个就要回来改白名单，是当前 `ToolExposurePolicy` 的根本毛病。
 * 能力档位是稳定的三档，新 provider 只需声明自己属于哪档，暴露策略不用动。
 */
enum class Strength {
    /** 端侧小模型：上下文小、指令遵循弱，工具表要压到接近零。 */
    TINY,

    /** 端侧较强或小参数云端模型：能读分组索引，按需激活。 */
    SMALL,

    /** 云端大模型：全量工具也不至于选错。 */
    LARGE
}

/**
 * 本次请求面向的模型。
 *
 * @param contextWindow 上下文窗口（token）。预算裁剪据此换算，不写死数字。
 * @param strength 能力档位，决定工具暴露模式。
 */
data class ModelRef(
    val provider: String,
    val model: String,
    val contextWindow: Int = 32_000,
    val strength: Strength = Strength.LARGE
)

/** 一次模型往返的用量。字段含义与主流协议一致，便于直接累加统计成本。 */
data class Usage(val prompt: Long = 0, val completion: Long = 0) {
    val total: Long get() = prompt + completion

    operator fun plus(other: Usage) = Usage(prompt + other.prompt, completion + other.completion)

    companion object {
        val ZERO = Usage()
    }
}

/**
 * 工具执行结果。
 *
 * 失败必须带 [hint]：模型下一步要靠它自纠，而不是把同一个错再犯一次。
 * 这条约束沿用现有 `ToolResult` 的经验（T02），但类型独立、字段更少。
 */
data class Outcome(
    val ok: Boolean,
    val text: String,
    val hint: String? = null
) {
    init {
        require(ok || hint != null) { "失败结果必须给 hint，否则模型无法自纠" }
    }

    companion object {
        fun ok(text: String) = Outcome(true, text)
        fun fail(text: String, hint: String) = Outcome(false, text, hint)
    }
}
