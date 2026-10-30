package com.example.agentkit.core.kernel

/**
 * 内核主循环。
 *
 * ## 它做什么
 * 只有一件事：**请求 → 有工具调用就执行 → 把结果塞回去 → 再请求**，直到模型不再调工具
 * 或步数用尽。全部逻辑约 100 行，不含任何「主题」「记忆」「技能」「计划」的概念。
 *
 * ## 为什么这么薄
 * 现有 `RealAgentLoop` 974 行，把「系统提示拼装、上下文裁剪、工具暴露、审批、费用记账、
 * 记忆注入、标题生成」都塞在循环里。结果是：**每加一个功能，循环就长一截，而且所有功能
 * 都被迫在每一轮执行**——这正是「用不上的东西每次发消息都要经过一遍」的来源。
 *
 * 这里把它们全部挪到循环外面：
 * - 提示怎么拼 → [Prompt]（循环只收到一个字符串）
 * - 历史留多少 → [Budget]（循环只收到已经裁好的消息列表）
 * - 工具给谁看 → [Surface]（循环只收到一张已经渲染好的工具表）
 * - 要不要问用户 → [Hooks.approve]
 * - 想记什么账 → 订阅 [Event]
 *
 * 循环本身不认识这些概念，所以新增功能不需要改循环。
 */
data class Turn(
    /** 本次面向的模型。 */
    val model: ModelRef,
    /** 已组装好的系统提示。 */
    val prompt: Prompt,
    /** 对话消息（不含系统提示；系统提示可能由多条 system 消息组成，此处可含）。 */
    val history: List<Msg>,
    /** 全量工具。是否暴露由 [policy] 决定，注册表本身就是全量。 */
    val tools: List<ToolSpec>,
    /** 预算。 */
    val budget: Budget = Budget.fromContext(model.contextWindow),
    /** 暴露策略。默认按模型档位选。 */
    val policy: ExposurePolicy = ExposurePolicy.forStrength(model.strength),
    /** 组名 → 一句话说明（渲染组索引用）。 */
    val groupNotes: Map<String, String> = emptyMap()
)

/**
 * 一次模型请求。
 *
 * @param toolTable 已渲染的工具表文本——**原样进请求**。循环不再做任何格式化，
 *        这样「工具表有多长」是一个可测的数字（[Event.Step] 里带出来）。
 */
data class Request(
    val model: ModelRef,
    val prompt: Prompt,
    val messages: List<Msg>,
    val toolTable: String,
    val surface: Surface
)

/** 模型回复。 */
data class Reply(
    val text: String = "",
    val calls: List<Call> = emptyList(),
    val usage: Usage = Usage.ZERO
)

/** 模型调用适配点。每个 provider 实现一个，内核不认识任何协议细节。 */
fun interface ModelCaller {
    suspend fun call(req: Request): Reply
}

/** 循环对外事件。UI 与统计都从这里拿数据，循环不直接碰 UI。 */
sealed interface Event {
    /** 一步开始。`tableChars` 是这轮工具表的长度——省钱效果的观察点。 */
    data class Step(val index: Int, val visibleTools: Int, val tableChars: Int, val historyChars: Int) : Event

    /** 模型产出的正文（非流式时一整段；流式由适配层切片后多次发）。 */
    data class Text(val chunk: String) : Event

    data class ToolStart(val name: String, val args: String) : Event

    data class ToolEnd(val name: String, val ok: Boolean, val chars: Int) : Event
}

/** 一轮的终局。 */
sealed interface Result {
    /** 模型不再调工具，正常结束。 */
    data class Done(
        val text: String,
        val steps: Int,
        val usage: Usage,
        val foldedMessages: Int
    ) : Result

    /** 步数用尽或外部中止。 */
    data class Aborted(val reason: String, val steps: Int, val usage: Usage) : Result
}

/**
 * 扩展点。默认实现**全部走安全侧**：需要审批就拒绝，不记账。
 *
 * 之所以默认拒绝而不是默认放行：内核可能被用在无人值守的场景（自动化、定时任务），
 * 那时「默认放行」等于把「删文件」交给一个没人看着的模型。接入方必须显式实现
 * [approve] 才能获得放行能力。
 */
interface Hooks {
    /** 审批。level 恒为 [Approval.ASK]（ALLOW/DENY 在循环内已处理）。 */
    suspend fun approve(tool: ToolSpec, args: Args, level: Approval): Boolean = false

    /** 每次工具执行完的回调（记账、审计、UI 刷新）。 */
    suspend fun toolFinished(tool: ToolSpec, outcome: Outcome) {}
}

/**
 * 主循环。
 *
 * @param maxSteps 最多几轮模型往返。超过就中止并把已产出的文本交回——不做「静默继续」。
 * @param approvalMode 全局审批模式。
 */
class Kernel(
    private val caller: ModelCaller,
    private val hooks: Hooks = object : Hooks {},
    private val maxSteps: Int = 8,
    private val approvalMode: ApprovalMode = ApprovalMode.AUTO,
    private val summarize: Summarizer = Summarizer { null }
) {

    /**
     * 跑完一轮对话。
     *
     * @param onEvent 事件回调（可在里面推进 UI 状态）。
     */
    suspend fun run(turn: Turn, onEvent: (Event) -> Unit = {}): Result {
        val history = turn.history.toMutableList()
        var surface = Surface.of(turn.tools, turn.policy, turn.groupNotes)
        var usage = Usage.ZERO
        var text = ""
        var folded = 0

        for (step in 1..maxSteps) {
            val fitted = ContextFitter.fit(history, turn.budget, summarize)
            folded = fitted.foldedCount

            val req = Request(
                model = turn.model,
                prompt = turn.prompt,
                messages = fitted.messages,
                toolTable = surface.render(turn.model),
                surface = surface
            )
            onEvent(Event.Step(step, surface.visible().size, req.toolTable.length, fitted.chars))

            val reply = caller.call(req)
            usage += reply.usage
            if (reply.text.isNotBlank()) {
                text = reply.text
                onEvent(Event.Text(reply.text))
            }
            history.add(Msg.assistant(reply.text, reply.calls))

            if (reply.calls.isEmpty()) {
                return Result.Done(text, step, usage, folded)
            }

            for (call in reply.calls) {
                val (outcome, newSurface) = dispatch(call, surface, onEvent)
                surface = newSurface
                history.add(Msg.tool(call.id, call.name, renderOutcome(outcome)))
            }
        }

        return Result.Aborted("步数用尽（$maxSteps 步）", maxSteps, usage)
    }

    /**
     * 执行一次调用。返回（结果，可能被激活的 surface）。
     *
     * 元工具的语义在这里解释，而不是做成 `ToolSpec`：它们的参数需要访问 [Surface] 本身，
     * 做成工具会和暴露层形成环依赖，也会让「元工具」混进普通工具注册表被误暴露。
     */
    private suspend fun dispatch(
        call: Call,
        surface: Surface,
        onEvent: (Event) -> Unit
    ): Pair<Outcome, Surface> {
        onEvent(Event.ToolStart(call.name, call.args.raw))

        val result: Pair<Outcome, Surface> = when (call.name) {
            MetaTool.FIND -> findTools(call.args, surface) to surface
            MetaTool.ACTIVATE -> activateGroup(call.args, surface)
            MetaTool.CALL -> callTool(call.args, surface) to surface
            else -> callToolByName(call.name, call.args, surface) to surface
        }

        onEvent(Event.ToolEnd(call.name, result.first.ok, result.first.text.length))
        return result
    }

    /** 元工具：按关键词在**全量**工具里检索（不激活，只报候选）。 */
    private fun findTools(args: Args, surface: Surface): Outcome {
        val q = args.opt("query")?.trim().orEmpty()
        if (q.isEmpty()) {
            return Outcome.fail("缺少 query", "给出要做什么的一句话，例如 query=\"读取文件\"")
        }
        val hits = surface.all.filter { t ->
            t.name.contains(q, ignoreCase = true) || t.group.contains(q, ignoreCase = true) ||
                t.brief(ModelRef("", "")).contains(q, ignoreCase = true)
        }.take(8)
        if (hits.isEmpty()) {
            val groups = surface.groups().joinToString("、") { it.first }
            return Outcome.fail("没有匹配「$q」的工具", "可用分组：$groups；换更宽的关键词，或先激活分组")
        }
        return Outcome.ok(hits.joinToString("\n") { "${it.name}（组 ${it.group}）— ${it.brief(ModelRef("", ""))}" })
    }

    private fun activateGroup(args: Args, surface: Surface): Pair<Outcome, Surface> {
        val g = args.opt("group")?.trim().orEmpty()
        if (g.isEmpty()) {
            return Outcome.fail("缺少 group", "传入组名：" + surface.groups().joinToString("、") { it.first }) to surface
        }
        val next = surface.activate(g)
        if (next === surface) {
            val groups = surface.groups().joinToString("、") { it.first }
            return Outcome.fail("组「$g」不存在或已激活", "现有分组：$groups") to surface
        }
        val names = next.visible().filter { it.group == g }.joinToString(", ") { it.name }
        return Outcome.ok("已激活 $g：$names") to next
    }

    /**
     * 元工具：调用真实工具。参数是 `name` + `args`（子参数串）。
     *
     * ⚠️ 这里的审批只能看到「外层」参数，看不到子参数，因此危险工具一律按 ASK 处理
     * （[Args] 实现为 [RawArgs]，`opt` 恒为 null，工具自述的参数级放行不会生效）。
     * 这是保守的失败方向：宁可多问一次，不可静默执行一条 `rm -rf`。
     */
    private suspend fun callTool(args: Args, surface: Surface): Outcome {
        val name = args.opt("name")?.trim().orEmpty()
        if (name.isEmpty()) return Outcome.fail("缺少 name", "传入要调用的工具名")
        val inner = RawArgs(args.opt("args") ?: "{}")
        return execute(name, inner, surface)
    }

    private suspend fun callToolByName(name: String, args: Args, surface: Surface): Outcome =
        execute(name, args, surface)

    private suspend fun execute(name: String, args: Args, surface: Surface): Outcome {
        val tool = surface.find(name)
            ?: return Outcome.fail(
                "没有名为 $name 的工具",
                "不要编造工具名；先 __find_tools 检索，或从组索引里选一个"
            )

        // 分组模式下，没激活的组不允许被调用：否则模型会学到「激活这步可以跳过」，
        // 组索引就白设计了。
        if (surface.policy.mode == ExposureMode.GROUPED &&
            tool.group !in surface.activeGroups &&
            tool.name !in surface.policy.pinned
        ) {
            return Outcome.fail(
                "工具 $name 属于未激活的组 ${tool.group}",
                "先调 ${MetaTool.ACTIVATE} 激活组 ${tool.group}，再调用 $name"
            )
        }

        when (tool.approvalFor(args, approvalMode)) {
            Approval.DENY -> return Outcome.fail("已拒绝：$name 的这次调用不被允许", "换一种做法，或说明为什么需要它")
            Approval.ASK -> {
                val allowed = hooks.approve(tool, args, Approval.ASK)
                if (!allowed) {
                    return Outcome.fail("用户拒绝了 $name", "不要重复请求；换方法或直接问用户要什么")
                }
            }
            Approval.ALLOW, Approval.INHERIT -> Unit
        }

        val outcome = tool.invoke(args)
        hooks.toolFinished(tool, outcome)
        return outcome
    }

    /** 工具结果回填成文本。失败时把 hint 拼在后面——模型下一轮靠它自纠。 */
    private fun renderOutcome(o: Outcome): String = if (o.ok) {
        o.text
    } else {
        buildString {
            appendLine("失败：${o.text}")
            append("建议：${o.hint}")
        }
    }
}
