package com.example.agentkit.memory

import java.util.Locale

/**
 * 会话中的「自动记忆抽取」（W-16）。
 *
 * 此前只有模型主动调 `memory_write` 才会记忆——实际用起来几乎不会主动写，
 * 于是记忆库永远是空的（对照 Operit 的 `MemoryAutoSaveScheduler`）。
 *
 * 这里用**保守的规则**从用户消息里抽取「明确值得长期记住」的内容：
 *  - 只处理用户在对话里说的话（不处理模型输出，避免自我强化/幻觉入库）；
 *  - 只认显式信号（「记住…」「以后都…」「我喜欢…」「我叫…」），
 *    宁可漏记，也不要把闲聊塞进记忆库（噪音会污染检索）；
 *  - 每条截断到 [MAX_CONTENT] 字，去重交给 [isDuplicate]（bigram Jaccard）。
 *
 * 纯函数（不读磁盘、不读时间），便于 JVM harness 断言。
 */
object MemoryAutoCapture {

    data class Captured(
        val content: String,
        val kind: String,
        val salience: Int
    )

    private const val MAX_CONTENT = 120
    private const val MIN_INPUT = 6
    private const val MAX_INPUT = 500

    /** 显式信号 → 记忆类型与重要度。顺序即优先级（先匹配到的先记）。 */
    private val RULES: List<Triple<Regex, String, Int>> = listOf(
        // 「记住：我对花生过敏」→ 事实，重要度高
        Triple(Regex("记住[：:，, ]?\\s*(.{3,$MAX_CONTENT})"), "fact", 8),
        // 「以后都用简体中文回我」「以后别自动更新」
        Triple(Regex("以后(?:都|就|请)?(?:要|别|不要|用|按|叫我)\\s*(.{2,$MAX_CONTENT})"), "preference", 7),
        // 「我喜欢用深色模式」「我不喜欢吃香菜」
        Triple(Regex("我(?:喜欢|不喜欢|习惯|偏好|讨厌)\\s*(.{2,$MAX_CONTENT})"), "preference", 7),
        // 「我叫小王」「我的名字是…」
        Triple(Regex("我(?:叫|的名字是)\\s*(.{1,40})"), "fact", 6)
    )

    /**
     * 从一条用户消息里抽取候选记忆。
     *
     * @return 0~3 条；同一条消息里重复命中的规则只保留第一条（避免同一句话记成三条）
     */
    fun extract(userText: String): List<Captured> {
        val text = userText.trim()
        if (text.length < MIN_INPUT || text.length > MAX_INPUT) return emptyList()
        // 纯问题/纯指令不带「记住」类信号时直接跳过（省掉正则开销）
        if (text.none { it in "记住以后我习惯偏好喜欢讨厌叫名字" }) return emptyList()

        val out = LinkedHashSet<Captured>()
        RULES.forEach { (regex, kind, salience) ->
            val m = regex.find(text) ?: return@forEach
            val body = clean(m.groupValues.getOrNull(1).orEmpty())
            if (body.length < 2) return@forEach
            val content = when (kind) {
                "preference" -> body
                else -> body
            }
            out.add(Captured(content = content, kind = kind, salience = salience))
            if (out.size >= 3) return out.toList()
        }
        return out.toList()
    }

    /** 去掉包裹的标点与多余空白，并截断。 */
    private fun clean(raw: String): String = raw
        .trim()
        .trim('。', '，', ',', '.', '！', '!', '？', '?', '；', ';', '：', ':')
        .replace(Regex("\\s+"), " ")
        .take(MAX_CONTENT)

    /**
     * 是否与已有记忆重复。
     *
     * 两级判定（顺序即优先级）：
     *  1. **归一化后相等/包含**：先去掉程度副词与语气词（太/很/能/都/也/非常…），
     *     「我不吃辣」与「我不太能吃辣」归一后都是「我不吃辣」→ 直接判重；
     *  2. **bigram Jaccard ≥ [threshold]**：兜底处理语序微调的长句。
     *
     * 为什么不能只用 Jaccard：短句插两个虚词就会把相似度打到 0.33 以下（分母小），
     * 而「我不吃辣 / 我不吃香菜」这类**关键宾语不同**的必须判为不同——
     * 归一化去掉虚词后它们的尾部差异（辣 vs 香菜）仍在，正好区分开。
     */
    fun isDuplicate(candidate: String, existing: List<String>, threshold: Double = 0.8): Boolean {
        val na = normalizeForCompare(candidate)
        if (na.isEmpty()) return true
        return existing.any { item ->
            val nb = normalizeForCompare(item)
            if (nb.length >= 3 && (na == nb || na.contains(nb) || nb.contains(na))) return@any true

            val a = bigrams(candidate)
            val b = bigrams(item)
            if (a.isEmpty() || b.isEmpty()) return@any false
            val inter = a.count { b.contains(it) }
            val union = a.size + b.size - inter
            union > 0 && inter.toDouble() / union >= threshold
        }
    }

    /** 比对用的归一化：去掉标点、空白与程度副词/语气词（只影响判重，不影响入库原文）。 */
    internal fun normalizeForCompare(s: String): String {
        var t = s.lowercase(Locale.getDefault())
        PADDING_WORDS.forEach { t = t.replace(it, "") }
        return t.filter { it.isLetterOrDigit() }
    }

    /**
     * 判重时忽略的虚词/程度词。
     *
     * 只收「不改变语义主体」的词：「太/很/能/都/也/就/还/挺」这类；
     * 否定词（不/别/没）**绝不能**进这张表——去掉会让「吃辣」与「不吃辣」变成同一条。
     */
    private val PADDING_WORDS = listOf(
        "非常", "特别", "比较", "有点", "一点", "超级",
        "太", "很", "能", "都", "也", "就", "还", "挺", "蛮",
        "的", "了", "啊", "吧", "呢", "呀", "嘛", "哈", "啦", "哦", "嗯",
        " ", "，", ",", "。", ".", "！", "!", "？", "?", "、", "：", ":", "；", ";"
    )

    /** 中英混排的二元组集合（英文按小写整词，中文按相邻两字）。纯函数。 */
    internal fun bigrams(s: String): Set<String> {
        val lower = s.lowercase(Locale.getDefault())
        val out = LinkedHashSet<String>()
        // 中文二元组
        val cjk = lower.filter { it in '\u4e00'..'\u9fff' }
        for (i in 0 until cjk.length - 1) out.add(cjk.substring(i, i + 2))
        // 英文/数字整词
        Regex("[a-z0-9]{2,}").findAll(lower).forEach { out.add(it.value) }
        return out
    }
}
