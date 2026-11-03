import com.example.agentkit.core.agent.journal.RunEvent
import com.example.agentkit.core.agent.journal.RunEventCodec
import com.example.agentkit.core.agent.journal.RunJournalRepair
import com.example.agentkit.core.agent.journal.RunScope
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
        // R7：v2 在版本头后多一列 scope，列数由 6 变 7
        check("U9 净化后仍单行", line.count { it == '\n' } == 0 && line.split('\t').size == 7)
        check("U9 可解回", RunEventCodec.decode(line) != null)
    }

    // R7：v1 老文件仍可读。崩溃恢复的依据是历史日志，读不懂等于让旧设备上的
    // 未闭合回合永远补不上尾。v1 没有 scope 列，读出时归 main。
    run {
        val legacy = RunEventCodec.decode("v1\t1\t100\tt1\tOPEN")
        check("R7-1 v1 仍可读", legacy != null)
        check("R7-1 v1 scope 归 main", legacy?.scope == RunScope.MAIN)
        val legacyTool = RunEventCodec.decode("v1\t2\t100\tt1\tDISP\t3\tcall-1")
        check("R7-1 v1 工具事件可读", legacyTool is RunEvent.ToolDispatched &&
            legacyTool.callId == "call-1" && legacyTool.scope == RunScope.MAIN)
        check("R7-1 未知版本仍拒绝", RunEventCodec.decode("v9\t1\t100\tt1\tmain\tOPEN") == null)
    }

    // R7：v2 的 scope 列真的落盘并读回
    run {
        val scoped = RunEvent.ToolDispatched(3, 100, "t1", 2, "call-9", RunScope.skill("demo"))
        val line = RunEventCodec.encode(scoped)
        val back = RunEventCodec.decode(line)
        check("R7-1 scope 往返一致", back == scoped)
        check("R7-1 scope 类别可判", RunScope.kindOf(RunScope.skill("demo")) == RunScope.KIND_SKILL)
    }

    // R7：有 DISP 无 ASSIST 也算「越过了执行边界」。
    //
    // 技能脚本的调用不经过「模型声明调用」这一步，所以日志里只有 DISP 没有 ASSIST。
    // 改造前修复器写的是 `pending[callId]?.dispatched = true`——遇到没有声明的 DISP
    // 就整个忽略，于是「脚本写完文件、进程被杀」被判成「什么都没发生」→ 重放 → 数据损坏。
    run {
        val events = listOf(
            RunEvent.TurnOpened(1, 100, "t1"),
            RunEvent.ToolDispatched(2, 110, "t1", 0, "skill:demo:write_file#1"),
        )
        val r = RunJournalRepair.repair(events)
        val orphan = r.orphans.firstOrNull { it.callId == "skill:demo:write_file#1" }
        check("R7-2 无 ASSIST 的 DISP 被登记", orphan != null)
        check("R7-2 判为结局未知", orphan?.status == ToolSettleStatus.OUTCOME_UNKNOWN)
        check("R7-2 工具名从 callId 反推", orphan?.toolName == "write_file")
        check("R7-2 补了一条 SETTLE", r.closers.any { it is RunEvent.ToolSettled })
    }

    // R7：派生句柄不写回合级/步级事件（否则子代理的一步收口会清空主代理的待应答表）
    run {
        val events = listOf(
            RunEvent.TurnOpened(1, 100, "t1"),
            RunEvent.AssistantCommitted(2, 110, "t1", 0, listOf(ToolCallRef("main-1", "read_file"))),
            RunEvent.ToolDispatched(3, 120, "t1", 0, "main-1"),
            // 子代理的一步收口（scope=sub:x）——若修复器把它当全局的步收口，
            // pending 会被清空，主代理未应答的调用就此漏判
            RunEvent.StepClosed(4, 130, "t1", 0, RunScope.subagent("查资料")),
            RunEvent.ToolDispatched(5, 140, "t1", 0, "sub-1", RunScope.subagent("查资料")),
        )
        val r = RunJournalRepair.repair(events)
        val mainOrphan = r.orphans.firstOrNull { it.callId == "main-1" }
        check("R7-2 主代理的调用未被子代理的步收口清空", mainOrphan != null)
        check("R7-2 主代理调用判为结局未知", mainOrphan?.status == ToolSettleStatus.OUTCOME_UNKNOWN)
    }

    println()
    println("结果：$pass 通过 / $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}
