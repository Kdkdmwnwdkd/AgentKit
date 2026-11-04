package com.example.agentkit.core

import com.example.agentkit.core.agent.ToolDescriptor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * 工具注册表：维护 name -> AgentTool 的映射，并统一负责「查找 + 调用 + 异常兜底」。
 * 之后的 Skill 插件系统只需把自己的工具 register 进来，Agent 循环无需改动。
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, AgentTool>()

    /**
     * W-14：MCP 工具在**运行时**异步注册（启动后连服务器、拉 tools/list），
     * 与主线程的 `list()/descriptors()` 并发。LinkedHashMap 非线程安全，
     * 不加锁会在扩容时抛 ConcurrentModificationException 或读到半写状态。
     */
    private val lock = Any()

    fun register(tool: AgentTool): ToolRegistry = apply {
        synchronized(lock) { tools[tool.name] = tool }
    }

    fun unregister(name: String): ToolRegistry = apply {
        synchronized(lock) { tools.remove(name) }
    }

    fun get(name: String): AgentTool? = synchronized(lock) { tools[name] }
    fun list(): List<AgentTool> = synchronized(lock) { tools.values.toList() }
    fun names(): List<String> = synchronized(lock) { tools.keys.toList() }

    /**
     * T01：工具描述符列表（纯映射，无行为）。
     *
     * name = 工具名；wireName 默认 = name（预留 MCP 前缀化，届时二者分离）；
     * inputSchemaJson = AgentTool.parameters 经 kotlinx.serialization 序列化的
     * JSON Schema 字符串（encodeDefaults 保 type/properties/required 字段齐全，
     * explicitNulls=false 省掉 enum:null 噪声）。纯函数：同 registry 两次调用结果相等。
     *
     * ⚠️ **契约占位声明（Round1 QA Minor 修复）**：首期本方法仅为 descriptor
     * 契约占位——wireName 预留 MCP 前缀化（当前恒等于 name），**不是稳定 API**；
     * 字段集合与语义可能随 MCP 接入调整，外部调用方不得对其做跨版本承诺依赖。
     */
    fun descriptors(): List<ToolDescriptor> = list().map { tool ->
        ToolDescriptor(
            name = tool.name,
            wireName = tool.name,
            inputSchemaJson = descriptorJson.encodeToString(JsonSchema.serializer(), tool.parameters)
        )
    }

    /**
     * T01：按工具名取描述符（[descriptors] 的单点版）；未知工具返回 null——
     * loop 据此走未知工具文案路径（T02 模糊匹配），见 RealAgentLoop.executeBatch。
     */
    fun descriptorOf(name: String): ToolDescriptor? = get(name)?.let { tool ->
        ToolDescriptor(
            name = tool.name,
            wireName = tool.name,
            inputSchemaJson = descriptorJson.encodeToString(JsonSchema.serializer(), tool.parameters)
        )
    }

    /** T01 descriptor 序列化配置：字段齐全（type/properties/required）+ 省 enum:null 噪声。 */
    @OptIn(ExperimentalSerializationApi::class)
    private val descriptorJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 本批是否含写操作（T03，coomi A2 has_mutation 思路）：
     * 任一工具非只读 → 整批串行；全批只读 → 才允许并行。
     * 未知工具返回 null，按只读处理（它只会得到一条错误结果，无副作用）。
     */
    fun batchHasMutation(calls: List<ToolCall>): Boolean =
        calls.any { get(it.name)?.readOnly == false }

    /**
     * 未知工具模糊匹配（T02，operit #11 思路）：编辑距离 ≤ [maxDistance] 的最佳候选。
     * 纯函数：同样入参两次调用结果相等。
     *
     * @return 候选工具名；无足够接近的候选时 null。
     */
    fun suggestToolName(name: String, maxDistance: Int = 2): String? {
        if (name.isBlank()) return null
        // 命中编辑距离 ≤2 的候选里取「最短 + 字典序最小」——最可能是模型笔误的目标
        return names()
            .filter { editDistanceWithin(it, name, maxDistance) }
            .sortedWith(compareBy({ it.length }, { it }))
            .firstOrNull()
    }

    /**
     * 未知工具回喂文案（T02）：含建议名（编辑距离 ≤2 命中时）+ 前 20 个可用工具，
     * 让模型能从错误里自纠（weather skill 思路：错误信息即修复指引）。
     */
    fun unknownToolMessage(name: String): String = buildString {
        append("未知工具 '").append(name).append("'。")
        val suggestion = suggestToolName(name)
        if (suggestion != null) {
            append("你是否想调用 '").append(suggestion).append("'？")
        }
        append("当前可用工具（前 20 个）：")
        append(names().take(20).joinToString(", "))
        append("。请从上述工具中选择正确名称重新调用，不要重复已失败的调用。")
    }

    /**
     * 工具在**全量注册表**里存在、但不在本注册表（回合级工具视图）里时的回喂文案。
     *
     * ## 为什么必须与 [unknownToolMessage] 分开
     *
     * 回合级工具视图（`RealAgentLoop.scopedRegistry`）只装本回合暴露的工具。被裁掉的
     * 工具在这个视图里「查无此工具」，与「这个工具根本不存在」走的是同一条分支——
     * 但两者的**正确反应完全相反**：
     *
     *  - 真不存在 → 模型是**记错了名字**，该做的是改名字或换工具；
     *  - 存在但未启用 → 模型记的**完全正确**，该做的是先 `__use_tools` 启用它所在的分组，
     *    下一轮就能直接调。
     *
     * 统一说「未知工具」会让模型以为自己凭空编了个工具，转而去猜别的名字——
     * 而真相是它**没有记错**，只是少开了一个组。这正是本方法存在的理由。
     *
     * @param name 模型调用的工具名。
     * @param group 该工具所属的分组名（用于告诉模型开哪个组）。
     */
    fun toolNotEnabledMessage(name: String, group: String): String = buildString {
        append("工具 '").append(name).append("' 存在，但当前未启用（它属于 '").append(group).append("' 组）。")
        append("请先调用 __use_tools 启用 '").append(group).append("' 组（参数 groups=\"").append(group).append("\"），")
        append("下一轮即可直接调用它；或调用 __find_tools 查找其他可用工具。")
        append("当前已启用工具：")
        append(names().take(20).joinToString(", "))
        append("。")
    }

    /** 执行一次工具调用，无论成功失败都返回一个可进入历史的 ToolMessage（统一契约文本）。 */
    suspend fun execute(call: ToolCall): ToolMessage {
        val tool = get(call.name)
            ?: return ToolMessage(
                call.id, call.name, call.arguments,
                formatToolResult(
                    ToolResult.fail(
                        code = "unknown_tool",
                        message = unknownToolMessage(call.name),
                        hint = "从当前可用工具列表中选择正确名称重新调用；若任务无法完成请直接告知用户。"
                    )
                ),
                isError = true
            )

        return runCatching { tool.execute(call.arguments) }
            .fold(
                onSuccess = {
                    ToolMessage(
                        call.id, tool.name, call.arguments,
                        truncateToolResult(formatToolResult(it)),
                        it.isError
                    )
                },
                onFailure = {
                    ToolMessage(
                        call.id, tool.name, call.arguments,
                        formatToolResult(
                            ToolResult.fail(
                                code = "exception",
                                message = "工具执行异常：${it.message}",
                                hint = "检查参数格式与取值；如持续失败请换用其他工具或告知用户。"
                            )
                        ),
                        isError = true
                    )
                }
            )
    }
}

/**
 * 有界编辑距离（Levenshtein）：判断 [a] 与 [b] 的编辑距离是否 ≤ [maxDistance]。
 *
 * 工具名都很短（<40 字符），经典 DP + 行最小值剪枝足够快；
 * 长度差超限直接 false。纯函数，供模糊匹配与纯函数级验证使用。
 */
internal fun editDistanceWithin(a: String, b: String, maxDistance: Int): Boolean {
    if (a == b) return true
    if (maxDistance < 0) return false
    if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
    if (a.isEmpty()) return b.length <= maxDistance
    if (b.isEmpty()) return a.length <= maxDistance

    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        var rowMin = curr[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            if (curr[j] < rowMin) rowMin = curr[j]
        }
        if (rowMin > maxDistance) return false // 整行超限，后续只增不减
        val swap = prev
        prev = curr
        curr = swap
    }
    return prev[b.length] <= maxDistance
}
