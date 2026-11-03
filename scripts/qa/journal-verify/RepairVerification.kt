import com.example.agentkit.core.agent.journal.RunEvent
import com.example.agentkit.core.agent.journal.RunEventCodec
import com.example.agentkit.core.agent.journal.RunJournalRepair
import com.example.agentkit.core.agent.journal.ToolCallRef
import com.example.agentkit.core.agent.journal.ToolSettleStatus

private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("  PASS  $name") }
    else { fail++; println("  FAIL  $name  $detail") }
}

private fun open(seq: Long, id: String = "t1", t: Long = 100) = RunEvent.TurnOpened(seq, t, id)
private fun assist(seq: Long, step: Int = 1, calls: List<ToolCallRef>, t: Long = 110) =
    RunEvent.AssistantCommitted(seq, t, "t1", step, calls)
private fun disp(seq: Long, callId: String, step: Int = 1, t: Long = 120) =
    RunEvent.ToolDispatched(seq, t, "t1", step, callId)
private fun settle(seq: Long, callId: String, s: ToolSettleStatus, step: Int = 1, t: Long = 130) =
    RunEvent.ToolSettled(seq, t, "t1", step, callId, s)
private fun stepEnd(seq: Long, step: Int = 1, t: Long = 140) = RunEvent.StepClosed(seq, t, "t1", step)
private fun close(seq: Long, t: Long = 150) = RunEvent.TurnClosed(seq, t, "t1", "completed")

fun main() {
    println("== M4 中断尾修复 ==")

    // U1 平衡日志：什么都不做
    run {
        val events = listOf(
            open(1), assist(2, 1, listOf(ToolCallRef("a", "read_file"))),
            disp(3, "a"), settle(4, "a", ToolSettleStatus.SUCCEEDED), stepEnd(5), close(6)
        )
        val r = RunJournalRepair.repair(events)
        check("U1 平衡日志不修补", !r.repaired && r.orphans.isEmpty() && r.anomalies.isEmpty(),
            "closers=${r.closers.size}")
    }

    // U2 崩在「已派发未落结果」：a 未知、b 未派发
    val u2 = run {
        val events = listOf(
            open(1),
            assist(2, 1, listOf(ToolCallRef("a", "read_file"), ToolCallRef("b", "write_file"))),
            disp(3, "a")
        )
        val r = RunJournalRepair.repair(events)
        check("U2 补两条结果", r.orphans.size == 2, "orphans=${r.orphans.size}")
        check("U2 已派发=结局未知", r.orphans[0].status == ToolSettleStatus.OUTCOME_UNKNOWN, r.orphans[0].status.name)
        check("U2 未派发=尚未开始", r.orphans[1].status == ToolSettleStatus.NOT_DISPATCHED, r.orphans[1].status.name)
        check("U2 顺序保持声明序", r.orphans.map { it.callId } == listOf("a", "b"), r.orphans.map { it.callId }.toString())
        check("U2 补步+回合", r.closers.size == 4 &&
            r.closers[2] is RunEvent.StepClosed && r.closers[3] is RunEvent.TurnClosed)
        check("U2 序号接续", r.closers.first().seq == 4L, "seq=${r.closers.first().seq}")
        check("U2 时间复用末条", r.closers.all { it.atMs == 120L })
        events + r.closers
    }

    // U3 幂等：修过的再修必须空
    run {
        val r2 = RunJournalRepair.repair(u2)
        check("U3 修复后幂等", !r2.repaired, "closers=${r2.closers.size}")
    }

    // U4 步已关、回合没关：只补回合结束
    run {
        val events = listOf(
            open(1), assist(2, 1, listOf(ToolCallRef("a", "read_file"))),
            disp(3, "a"), settle(4, "a", ToolSettleStatus.SUCCEEDED), stepEnd(5)
        )
        val r = RunJournalRepair.repair(events)
        check("U4 只补回合结束", r.closers.size == 1 && r.closers[0] is RunEvent.TurnClosed)
        check("U4 无孤儿", r.orphans.isEmpty())
    }

    // U5 日志不一致：步关闭时还有未应答（应报告但不修补）
    run {
        val events = listOf(
            open(1), assist(2, 1, listOf(ToolCallRef("a", "write_file"))),
            stepEnd(3), close(4)
        )
        val r = RunJournalRepair.repair(events)
        check("U5 不修补已关闭的步", !r.repaired, "closers=${r.closers.size}")
        check("U5 记录异常", r.anomalies.size == 1, r.anomalies.toString())
    }

    // U6 空日志
    run {
        val r = RunJournalRepair.repair(emptyList())
        check("U6 空日志安全", !r.repaired && r.orphans.isEmpty())
    }

    println("== M2 日志编解码 ==")

    val samples = listOf(
        open(1),
        assist(2, 3, listOf(ToolCallRef("id-1", "read_file"), ToolCallRef("id-2", "shell_run"))),
        disp(3, "id-1", step = 3),
        settle(4, "id-1", ToolSettleStatus.OUTCOME_UNKNOWN, step = 3),
        stepEnd(5, step = 3),
        close(6)
    )
    for (e in samples) {
        val line = RunEventCodec.encode(e)
        val back = RunEventCodec.decode(line)
        check("U7 往返 ${e::class.simpleName}", back == e, "line=$line back=$back")
    }

    // U8 坏行不抛
    check("U8 半行返回 null", RunEventCodec.decode("v1\t1\t100") == null)
    check("U8 未知版本返回 null", RunEventCodec.decode("v9\t1\t100\tt1\tOPEN") == null)

    // U9 字段含分隔符被净化，不破坏行结构
    run {
        val dirty = RunEvent.TurnClosed(1, 100, "t\turn", "clo\nsed")
        val line = RunEventCodec.encode(dirty)
        check("U9 净化后仍单行", line.count { it == '\n' } == 0 && line.split('\t').size == 6)
        check("U9 可解回", RunEventCodec.decode(line) != null)
    }

    println()
    println("结果：$pass 通过 / $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}
