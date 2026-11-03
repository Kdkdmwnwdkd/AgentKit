package com.example.agentkit.core.agent.journal

/**
 * 崩溃尾修复（规格 M4）：把一份"断在回合中间"的日志补成自洽的。
 *
 * 要解决的问题很具体：进程在「工具已派发、结果未落盘」的窗口被系统杀掉。
 * 重进时历史里有一条 assistant 声明了调用，却没有对应结果——
 *  - 直接丢弃 → 历史不完整，模型下一轮看到悬空的 tool_call，供应商会拒；
 *  - 当成失败重放 → **写操作被执行两次**，这是数据损坏，不是体验问题。
 *
 * 所以修复只做一件事：**按日志事实判断"越没越过执行边界"，然后补一条带明确语义的结果**。
 * 越过了但没结果 = 结局未知；没越过 = 肯定没跑。
 *
 * 三条硬约束：
 *  1. 只追加，不修改任何既有事件（改动历史等于伪造证据）；
 *  2. 幂等——对已修复的日志再跑一次必须返回空（否则每次启动都在长日志）；
 *  3. 时间戳复用最后一条真实事件，不用当前时间（否则同样的日志每次修复结果不同，没法断言）。
 *
 * @see RunEvent 事件语义
 * @see RepairResult 修复产物与诊断信息
 */
object RunJournalRepair {

    /**
     * 扫描并生成补全事件。
     *
     * 时间复杂度 O(n)，空间 O(未应答调用数)。可在启动主线程调用（千级事件 < 1ms），
     * 但调用方仍应在 IO 线程读文件。
     *
     * @param events 已按 seq 升序读出的日志，允许尾部截断（半条 JSON 由读取层丢弃）。
     * @return 需要追加的事件（可能为空）+ 诊断信息。调用方负责写入，本函数不碰 IO。
     */
    fun repair(events: List<RunEvent>): RepairResult {
        if (events.isEmpty()) return RepairResult(emptyList(), null, emptyList(), emptyList())

        var openTurnId: String? = null
        var openStep: Int? = null

        // LinkedHashMap 保序：补结果时必须按模型声明的顺序，供应商对乱序 tool_result 会报错。
        val pending = LinkedHashMap<String, PendingCall>()
        val anomalies = mutableListOf<String>()

        for (event in events) {
            when (event) {
                is RunEvent.TurnOpened -> {
                    if (openTurnId != null) {
                        anomalies.add("回合 ${openTurnId} 未关闭就开了新回合 ${event.turnId}（seq=${event.seq}）")
                    }
                    openTurnId = event.turnId
                    openStep = null
                    pending.clear()
                }

                is RunEvent.TurnClosed -> {
                    if (event.turnId != openTurnId) {
                        anomalies.add("回合结束事件与当前回合不匹配（seq=${event.seq}）")
                    }
                    openTurnId = null
                    openStep = null
                    pending.clear()
                }

                is RunEvent.StepClosed -> {
                    // 步已关闭却还有未应答调用，说明日志被截断/写坏过。
                    // 不在这里补——补了会写进一个已经结束的步，破坏"一调用一结果"的时序。
                    // 记下来，由上层决定是否上报诊断。
                    if (pending.isNotEmpty()) {
                        anomalies.add(
                            "步 ${event.step} 关闭时仍有 ${pending.size} 个未应答调用：" +
                                pending.keys.joinToString(",")
                        )
                    }
                    openStep = null
                    pending.clear()
                }

                is RunEvent.AssistantCommitted -> {
                    openStep = event.step
                    for (call in event.calls) {
                        pending[call.id] = PendingCall(call.id, call.name, event.step, dispatched = false)
                    }
                }

                is RunEvent.ToolDispatched -> {
                    // 只有"日志里出现过 ToolDispatched"才算越过边界。
                    // 注意：不能用 pending 里是否存在来判断——不存在说明调用声明都没记全，那是更早的损坏。
                    pending[event.callId]?.dispatched = true
                }

                is RunEvent.ToolSettled -> {
                    pending.remove(event.callId)
                }
            }
        }

        val turnId = openTurnId ?: return RepairResult(emptyList(), null, emptyList(), anomalies)
        val last = events.last()

        // 合成事件的 seq 接在最后一条之后；时间复用最后一条，任何输入都得到同一份输出。
        var nextSeq = last.seq + 1
        val closers = mutableListOf<RunEvent>()
        val orphans = mutableListOf<OrphanCall>()

        for (call in pending.values) {
            val status = if (call.dispatched) {
                ToolSettleStatus.OUTCOME_UNKNOWN
            } else {
                ToolSettleStatus.NOT_DISPATCHED
            }
            orphans.add(OrphanCall(call.callId, call.toolName, call.step, status))
            closers.add(
                RunEvent.ToolSettled(
                    seq = nextSeq++,
                    atMs = last.atMs,
                    turnId = turnId,
                    step = call.step,
                    callId = call.callId,
                    status = status,
                    errorCode = if (call.dispatched) CODE_OUTCOME_UNKNOWN else CODE_NOT_DISPATCHED
                )
            )
        }

        // 先关步再关回合：顺序反了会让读取方看到"回合已结束但步还开着"。
        if (openStep != null) {
            closers.add(RunEvent.StepClosed(nextSeq++, last.atMs, turnId, openStep))
        }
        closers.add(RunEvent.TurnClosed(nextSeq, last.atMs, turnId, REASON_INTERRUPTED))

        return RepairResult(closers, turnId, orphans, anomalies)
    }

    /** 日志里有声明、但恢复时发现没有结果。用于生成回喂模型的 ToolMessage。 */
    data class OrphanCall(
        val callId: String,
        val toolName: String,
        val step: Int,
        val status: ToolSettleStatus
    ) {
        /** 结局未知 = 可能已生效，必须让模型先核对；未派发 = 可以放心重试。 */
        val mayHaveTakenEffect: Boolean get() = status == ToolSettleStatus.OUTCOME_UNKNOWN
    }

    /**
     * @property closers 待追加事件，空表示日志本就平衡（幂等的判据）。
     * @property openTurnId 被修复的回合，null 表示无需修复。
     * @property orphans 未应答调用清单，供上层按工具元数据生成文案。
     * @property anomalies 日志自身的不一致，不影响修复，但应进诊断导出。
     */
    data class RepairResult(
        val closers: List<RunEvent>,
        val openTurnId: String?,
        val orphans: List<OrphanCall>,
        val anomalies: List<String>
    ) {
        val repaired: Boolean get() = closers.isNotEmpty()
    }

    private class PendingCall(
        val callId: String,
        val toolName: String,
        val step: Int,
        var dispatched: Boolean
    )

    const val CODE_OUTCOME_UNKNOWN = "journal_outcome_unknown"
    const val CODE_NOT_DISPATCHED = "journal_not_dispatched"
    const val REASON_INTERRUPTED = "interrupted"
}
