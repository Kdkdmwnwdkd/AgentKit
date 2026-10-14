package com.example.agentkit.core

/**
 * 工具暴露策略（Phase 2 DoD④「本地模型工具暴露降级」）。
 *
 * ## 要解决的问题
 * 项目现有 20+ 个工具（http/read/write/clipboard/通知/ui_* 8 个/device_* 2 个/
 * memory_* 4 个/shell_* 2 个/app_* 3 个/use_skill）。云端大模型吃得下，但**手机上跑的
 * 本地小模型（llama.cpp）上下文小、指令遵循弱**，会被工具表挤爆——要么超上下文，
 * 要么在工具表里乱选。参照思路（Operit / AiCode 均按执行环境裁剪工具面，净室重写不抄码）：
 * 本地小模型只给「最小必要工具集」。
 *
 * ## 为什么用「中心化优先级表」而不是在 [AgentTool] 上加字段
 * 两种实现都在契约层面「可测」，本任务二选一。选中心化表的理由：
 *  1. **零侵入**：20+ 个工具实现一个字都不用改（不引入跨文件 API 变更、不增回归面）；
 *  2. **纯函数**：策略是完全确定性的纯函数，harness 可直接断言（可测性优先）；
 *  3. **可评审**：「哪些工具对本地小模型是核心」是产品决策，集中一处便于评审与调整；
 *  4. **向前兼容**：新增工具默认「非核心」（不进本地核心集），需显式列入才暴露——
 *     安全侧默认（新工具不会因实现者忘记标注而悄悄挤进本地工具面）。
 *
 * ## 裁剪只影响「暴露给 LLM 的工具面」，不改变注册表
 * [ToolExposure] 只描述「本次该给 LLM 看哪些工具」。真实执行侧由 loop 据本结果构造
 * **回合级工具视图 registry**（见 `RealAgentLoop.scopedRegistry`）——被裁掉的工具即便被
 * 本地模型瞎编调用，也走既有「未知工具」路径（T02 模糊匹配 + 可自纠 hint），绝不抛异常。
 */
object ToolExposurePolicy {

    /** 本地小模型 provider 标识（与 [LLMClient.id] 对齐）。 */
    const val LOCAL_PROVIDER_ID: String = "local-llama"

    /**
     * 本地小模型（`local-llama`）可见的**核心工具集**：**恰好 7 个**，按重要度降序。
     *
     * ## 取舍说明（详尽版本见 scripts/qa/LOCALMODEL-NOTES.md）
     *  1. `read_file`    —— 任务落地的基础信息通道；路线图 DoD ④ 显式要求「能完成一次 read_file」。
     *  2. `write_file`   —— 产出/落盘，与 read_file 构成「读写」最小闭环。
     *  3. `http_request` —— 联网取数：本地模型无内置知识，外部信息全靠它。
     *  4. `ui_tree`      —— 手机自动化的「感知」基础：先看见屏幕结构，才能决定下一步。
     *  5. `ui_tap`       —— 手机自动化的「动作」基础：把决策变成一次真实点按（最小可执行动作）。
     *  6. `clipboard`    —— 跨应用搬运文本的最短路径（复制/粘贴一站式）。
     *  7. `use_skill`    —— 技能扩展入口：不列它，本地模型无法触达任何技能能力。
     *
     * ## 被裁掉的例子（为什么）
     *  - `ui_swipe`/`ui_long_press`/`ui_input`/`ui_key`/`ui_click`：ui_tap 已覆盖「最小点按」，
     *    其余手势对本地小模型属「边际能力」，先裁（云端仍全量）；
     *  - `memory_write`/`memory_search`/`memory_list`/`memory_forget`：跨会话记忆依赖长上下文
     *    与稳定指令遵循，本地小模型装了也用不好，先裁；
     *  - `shell_run`/`shell_close`：高危且指令复杂（会话句柄/命令语法），先裁；
     *  - `app_list`/`app_launch`/`app_stop`：可由 ui_tree + ui_tap 间接达成，先裁；
     *  - `send_notification`、`ui_screenshot`：本地小模型多为纯文本（无视觉），先裁。
     *
     * ⚠️ 顺序即「重要度降序」，也是本地暴露列表的顺序（而非注册序）——让本地小模型先看到最该用的工具。
     */
    val LOCAL_CORE_TOOLS: List<String> = listOf(
        "read_file",
        "write_file",
        "http_request",
        "ui_tree",
        "ui_tap",
        "clipboard",
        "use_skill"
    )

    /** provider 是否为本地小模型（`local-llama`）。纯函数。 */
    fun isLocalModelProvider(providerId: String): Boolean = providerId == LOCAL_PROVIDER_ID

    /**
     * 计算某 provider 下应暴露给 LLM 的工具列表。**纯函数**：不读全局、不打日志、
     * 不改变入参，两次同参调用结果相等（日志由调用方 RealAgentLoop 在「裁剪发生」时打一条 INFO）。
     *
     * 规则（顺序即优先级）：
     *  1. [forceFullTools] = true → 强制全量（开发者/设置项开关），返回 [all] 原样（保注册序），不做裁剪；
     *  2. provider 非 `local-llama`（openai/claude/gemini/…）→ 返回 [all] 原样，**行为完全不变**；
     *  3. provider = `local-llama` → 只保留 [LOCAL_CORE_TOOLS] 中**确实已注册**的工具，
     *     顺序按核心重要度（而非注册序），个数 ≤ 7。
     *
     * @param all 当前注册表全量工具（注册序）。
     * @param providerId 当前 [LLMClient.id]。
     * @param forceFullTools 强制全量开关（默认 false = 按 provider 自动）。
     */
    fun exposureFor(
        all: List<AgentTool>,
        providerId: String,
        forceFullTools: Boolean = false
    ): ToolExposure {
        // 强制全量 或 非本地模型：原样返回全量（保注册序）。裁剪数量恒 0 → 上层不打日志。
        if (forceFullTools || !isLocalModelProvider(providerId)) {
            return ToolExposure(providerId, all, all.size, forcedFull = forceFullTools)
        }
        // 本地小模型：只保留核心集中确实存在的工具，顺序按核心重要度。
        val byName = all.associateBy { it.name }
        val core = LOCAL_CORE_TOOLS.mapNotNull { byName[it] }
        return ToolExposure(providerId, core, all.size, forcedFull = false)
    }
}

/**
 * 一次「工具暴露」的完整快照（Phase 2 DoD④）：[tools] 是本次要发给 LLM 的工具列表，
 * 另带诊断数字（全量数 / 暴露数 / 裁剪数）供日志与 harness 断言。
 *
 * @param providerId 触发本次裁剪的 provider id（诊断用）。
 * @param tools 实际暴露给 LLM 的工具（保序：裁剪时按核心重要度，否则为注册序）。
 * @param totalCount 注册表全量工具数（用于算裁剪掉多少）。
 * @param forcedFull 是否命中「强制全量」开关（true = 人为放行全量，非按 provider 自动）。
 */
data class ToolExposure(
    val providerId: String,
    val tools: List<AgentTool>,
    val totalCount: Int,
    val forcedFull: Boolean
) {
    /** 暴露给 LLM 的工具数。 */
    val exposedCount: Int get() = tools.size

    /** 被裁剪掉（未暴露）的工具数。 */
    val trimmedCount: Int get() = totalCount - exposedCount

    /** 是否真的发生了裁剪（暴露数 < 全量数）。 */
    val trimmed: Boolean get() = trimmedCount > 0

    /** 暴露工具名（按当前顺序）；harness 断言用。 */
    val exposedNames: List<String> get() = tools.map { it.name }
}
