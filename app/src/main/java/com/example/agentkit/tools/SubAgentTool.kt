package com.example.agentkit.tools

import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import com.example.agentkit.core.agent.SubAgentLogic
import com.example.agentkit.core.agent.SubAgentRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `spawn_agents`（W-31）：把 1~3 个「只读子任务」派给独立上下文的子代理并行执行，
 * 只把结论汇总回来。
 *
 * ## 什么时候该用（工具描述里也写了，模型靠它判断）
 *  - 要在很多文件里找东西，但只需要"哪个文件定义了 X"；
 *  - 同时查几个互不相关的主题；
 *  - 分别看几个界面/应用的状态。
 *
 * ## 什么时候不该用
 *  - 需要**修改**任何东西（子代理只有只读工具，写了也不会生效）；
 *  - 只有一件事要做（直接自己调工具，省一层开销）。
 *
 * 子代理看不到主对话，所以每条 `instruction` 必须自包含——这一点在参数说明里强调，
 * 否则模型很容易写"按上面的要求去查"，子代理只会一脸茫然。
 */
class SubAgentTool(private val runner: SubAgentRunner) : AgentTool {

    override val name = "spawn_agents"

    override val description =
        "把 1~3 个只读子任务并行派给独立上下文的子代理，只拿结论回来（省主上下文）。" +
            "适合：在多个文件里找定义、同时搜几个主题、分别查看几个界面。" +
            "子代理**只能读**（读文件/搜索/看界面/查记忆），不会修改任何东西；" +
            "每条 instruction 必须自包含（子代理看不到当前对话）。"

    override val parameters = JsonSchema.objectSchema(
        // 结构化数组：严格实现要求 type=array 必须带 items，扁平占位写法会被拒
        "tasks" to JsonSchema.Property(
            type = "array",
            description = "子任务数组，1~3 项；每项必须自带完整指令（子代理看不到当前对话）",
            items = JsonSchema.Property(
                type = "object",
                properties = mapOf(
                    "title" to JsonSchema.Property(
                        type = "string",
                        description = "短标题（汇总时显示）；可留空，会自动截取指令前 20 字"
                    ),
                    "instruction" to JsonSchema.Property(
                        type = "string",
                        description = "完整、自包含的指令：要查什么、在哪个范围查、期望什么形式的结论"
                    )
                ),
                required = listOf("instruction")
            )
        )
    )

    /** 只读工具：可以与其他只读工具并行调度，不涉及用户数据修改。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val array = args["tasks"] as? JsonArray
            ?: return@withContext ToolResult.fail(
                code = "missing_param",
                message = "缺少 tasks",
                hint = "tasks 是数组，每项 {\"title\":\"…\",\"instruction\":\"…\"}，1~3 项。"
            )

        // 逐项解析：非对象元素直接拒绝，不静默跳过（跳过会让模型以为派了 3 个实际只跑 2 个）
        val raw = ArrayList<Pair<String, String>>(array.size)
        for ((index, element) in array.withIndex()) {
            val obj = element as? JsonObject
                ?: return@withContext ToolResult.fail(
                    code = "invalid_tasks",
                    message = "tasks 第 ${index + 1} 项不是对象",
                    hint = "每项必须是 {\"title\":\"…\",\"instruction\":\"…\"}。"
                )
            raw += (obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()) to
                (obj["instruction"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }

        when (val parsed = SubAgentLogic.parseTasks(raw)) {
            is SubAgentLogic.Parse.Err -> ToolResult.fail(
                code = parsed.code, message = parsed.message, hint = parsed.hint
            )

            is SubAgentLogic.Parse.Ok -> {
                val started = System.currentTimeMillis()
                val outcomes = runner.run(parsed.tasks)
                val elapsed = (System.currentTimeMillis() - started) / 1000
                val okCount = outcomes.count { it.ok }
                ToolResult.ok(
                    code = if (okCount == 0) "subagents_failed" else "subagents_done",
                    message = "${outcomes.size} 个子代理完成：成功 $okCount、失败 ${outcomes.size - okCount}（${elapsed}s）",
                    data = SubAgentLogic.summarize(outcomes)
                )
            }
        }
    }
}
