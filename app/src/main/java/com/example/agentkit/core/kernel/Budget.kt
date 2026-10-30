package com.example.agentkit.core.kernel

/**
 * 上下文预算：决定「历史留多少、老的怎么处理」。
 *
 * ## 和现有 `ContextBudget`（281 行）的差别
 * 那边做的是「按比例砍」——超了就从头丢消息。问题有两个：
 * 1. 丢掉的是**最早的用户意图**，模型会突然不知道自己为什么在做这件事；
 * 2. 直接丢，中间那几轮的工具结果（往往是关键数据）无声消失，模型开始重复干活。
 *
 * 这里换成「分块折叠」：以用户消息为界切块，最近 [Budget.keepRecent] 块原样保留，
 * 更早的块折叠成**一条摘要**（由调用方给的 [Summarizer] 生成，通常是本地小模型跑一遍，
 * 失败就退化为「只保留首句 + 工具名列表」）。折叠是有损的，但方向对：保留意图和结论，
 * 丢弃过程。
 *
 * ## 为什么用字符数而不是 token 数
 * 内核不引 tokenizer（不同 provider 的分词器不一样，装了也不准）。字符数是可靠的上界
 * 代理：中英混排下 1 token ≈ 1.6–2.2 字符，预算按 2 字符/token 折算即偏保守。
 * 真要精确，就在适配层用服务端返回的 usage 做二次校正。
 */
data class Budget(
    /** 输入侧字符上限（含系统提示与工具表）。 */
    val maxPromptChars: Int,
    /** 最近保留的对话块数（一块 = 一条用户消息 + 之后的助手/工具消息）。 */
    val keepRecent: Int = 6,
    /** 至少保留最近这么多块，即使已经超预算（避免把「刚发生的事」也折掉）。 */
    val minKeep: Int = 2
) {
    companion object {
        /** 每 token 折算的字符数。取 2.0 偏保守（中文更接近 1.2，英文 3.5）。 */
        const val CHARS_PER_TOKEN: Double = 2.0

        /**
         * 由模型上下文窗口推算预算。
         *
         * @param reserveForReply 留给输出与工具结果的 token，默认 1/4 窗口（不少于 1024）。
         */
        fun fromContext(contextWindow: Int, reserveForReply: Int = 0): Budget {
            val reserve = if (reserveForReply > 0) reserveForReply else maxOf(1024, contextWindow / 4)
            val usable = maxOf(1024, contextWindow - reserve)
            return Budget(maxPromptChars = (usable * CHARS_PER_TOKEN).toInt())
        }
    }
}

/** 折叠后的结果。 */
data class Fitted(
    /** 可以直接发给模型的消息序列（首部可能多出一条折叠说明）。 */
    val messages: List<Msg>,
    /** 被折叠掉的原始消息条数。 */
    val foldedCount: Int,
    /** 折叠摘要（null = 没有折叠）。 */
    val foldedNote: String?,
    /** 是否因为单块本身就超预算而硬截断了最近对话（正常不该发生）。 */
    val truncated: Boolean,
    /** 估算字符数。 */
    val chars: Int
) {
    val ok: Boolean get() = !truncated
}

/**
 * 折叠摘要生成器：把一段早期对话压成几句话。
 *
 * 允许失败（返回 null），此时退回结构化摘要（首句 + 用过的工具名）。这个回调是挂载点：
 * 想用本地小模型跑摘要就在这里接，不想花这个算力就一直返回 null。
 */
fun interface Summarizer {
    suspend fun summarize(chunks: List<List<Msg>>): String?
}

object ContextFitter {

    /**
     * 裁剪。
     *
     * @param messages 完整历史（含系统消息）。
     * @param budget 预算。
     * @param summarize 折叠摘要生成器；默认不生成，走结构化兜底。
     */
    suspend fun fit(
        messages: List<Msg>,
        budget: Budget,
        summarize: Summarizer = Summarizer { null }
    ): Fitted {
        val systems = messages.filter { it.role == Role.SYSTEM }
        val dialogue = messages.filter { it.role != Role.SYSTEM }

        // 以用户消息为界切块；开头若有孤立的助手/工具消息，并入第一块。
        val chunks = mutableListOf<MutableList<Msg>>()
        for (m in dialogue) {
            if (m.role == Role.USER || chunks.isEmpty()) chunks.add(mutableListOf(m))
            else chunks.last().add(m)
        }

        val systemChars = systems.sumOf { it.text.length }
        var used = systemChars
        val kept = ArrayDeque<List<Msg>>()
        var truncated = false

        // 从最新往回取块，装满为止。
        for (i in chunks.indices.reversed()) {
            val chunk = chunks[i]
            val c = chunk.sumOf { it.text.length }
            val reachedLimit = kept.size >= budget.keepRecent
            if (!reachedLimit && used + c <= budget.maxPromptChars) {
                kept.addFirst(chunk)
                used += c
            } else if (kept.size < budget.minKeep) {
                // 单块自身就超预算：仍然保留，但标记截断，由调用方决定要不要压工具输出。
                kept.addFirst(chunk)
                used += c
                truncated = true
            } else {
                break
            }
        }

        val folded = chunks.take(chunks.size - kept.size).flatten()
        val note = if (folded.isEmpty()) null else buildNote(folded, summarize)

        val out = ArrayList<Msg>(systems.size + kept.size + 1)
        out.addAll(systems)
        if (note != null) out.add(Msg.system(note))
        kept.forEach { out.addAll(it) }

        return Fitted(
            messages = out,
            foldedCount = folded.size,
            foldedNote = note,
            truncated = truncated,
            chars = out.sumOf { it.text.length }
        )
    }

    /**
     * 生成折叠说明。优先用调用方给的摘要器；它返回 null 或抛异常时用结构化兜底。
     *
     * 结构化兜底刻意保留「第一条用户消息的开头」——那通常是整段任务的原始意图，
     * 是丢了之后模型最容易跑偏的东西。
     */
    private suspend fun buildNote(folded: List<Msg>, summarize: Summarizer): String {
        val first = folded.firstOrNull { it.role == Role.USER }?.text?.take(120)?.replace('\n', ' ')
        val tools = folded.filter { it.role == Role.TOOL }.mapNotNull { it.name }.distinct()
        val head = "[早期对话已折叠 ${folded.size} 条]"
        val intent = if (first != null) "最初的任务：$first" else ""
        val toolLine = if (tools.isEmpty()) "" else "已用过的工具：${tools.joinToString(", ")}"

        val custom = runCatching { summarize.summarize(listOf(folded)) }.getOrNull()
        return listOf(head, custom ?: "", intent, toolLine)
            .filter { it.isNotBlank() }
            .joinToString("；")
    }
}
