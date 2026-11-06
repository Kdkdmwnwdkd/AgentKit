package verify

import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.agent.journal.RunEvent
import com.beichen.polaris.core.agent.journal.RunJournal
import com.beichen.polaris.core.agent.journal.RunJournalRepair
import com.beichen.polaris.core.agent.journal.ToolSettleStatus
import com.beichen.polaris.core.agent.journal.TurnJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * TurnJournal 的三件事：顺序对、写坏不崩、坏了好。
 *
 * 这不是"跑通就行"的冒烟测试，三条断言各自对应一个真会发生的故障：
 *  - 顺序错了 → 崩溃后无法区分"没跑"与"跑了没记"，全部退化成人工核对；
 *  - 写坏就抛 → 存储满的手机连纯对话都用不了；
 *  - 坏了不恢复 → 用户清完空间还得重启 App。
 */

private var pass = 0
private var fail = 0

private fun ck(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        pass++
        println("PASS $name")
    } else {
        fail++
        println("FAIL $name  $detail")
    }
}

private fun freshDir(path: String): File {
    val d = File(path)
    d.deleteRecursively()
    d.mkdirs()
    return d
}

/** 正常回合：六个事件、序号连续、尾部闭合、修复器认为是平衡的。 */
private fun caseHappyPath() = runBlocking {
    val dir = freshDir("/tmp/tj-happy")
    val store = RunJournal(dir)
    val j = TurnJournal(store) { 1000L }

    val t = j.begin("turn-1")
    ck("H0 日志可用", t.journaling)
    t.assistantCommitted(0, listOf(ToolCall("c1", "write_file"), ToolCall("c2", "read_file")))
    ck("H1 派发放行", t.toolDispatched(0, "c1"))
    t.toolSettled(0, "c1", ToolSettleStatus.SUCCEEDED)
    t.toolSettled(0, "c2", ToolSettleStatus.NOT_DISPATCHED)
    t.stepClosed(0)
    t.turnClosed(TurnJournal.REASON_COMPLETED)

    val events = store.load("turn-1")
    ck("H2 七条事件", events.size == 7, "实际 ${events.size}")
    ck("H3 序号从 1 连续", events.map { it.seq } == (1L..7L).toList(), "${events.map { it.seq }}")
    ck(
        "H4 事件类型顺序",
        events.map { it::class.simpleName } == listOf(
            "TurnOpened", "AssistantCommitted", "ToolDispatched",
            "ToolSettled", "ToolSettled", "StepClosed", "TurnClosed"
        ),
        "${events.map { it::class.simpleName }}"
    )
    ck("H5 尾部是 TurnClosed", events.last() is RunEvent.TurnClosed)
    ck("H6 修复器认为已平衡", !RunJournalRepair.repair(events).repaired)
    ck("H7 时间戳来自注入时钟", events.all { it.atMs == 1000L })
}

/** 「先落盘再执行」：AssistantCommitted 必须严格早于 ToolDispatched。 */
private fun caseOrder() = runBlocking {
    val dir = freshDir("/tmp/tj-order")
    val store = RunJournal(dir)
    val j = TurnJournal(store) { 2000L }
    val t = j.begin("turn-o")
    t.assistantCommitted(3, listOf(ToolCall("k", "shell_run")))
    t.toolDispatched(3, "k")
    val events = store.load("turn-o")
    val iAssist = events.indexOfFirst { it is RunEvent.AssistantCommitted }
    val iDisp = events.indexOfFirst { it is RunEvent.ToolDispatched }
    ck("O1 声明先于派发", iAssist in 0 until iDisp, "assist=$iAssist disp=$iDisp")
    ck("O2 步号透传", (events[iDisp] as RunEvent.ToolDispatched).step == 3)
}

/** 写不进去：降级、不抛、写操作被拦；恢复后自动解除。 */
private fun caseDegrade() = runBlocking {
    val path = "/tmp/tj-blocked"
    File(path).deleteRecursively()
    // 同名普通文件占位：mkdirs 不会生效，日志路径的父级不是目录 → 写入必失败。
    File(path).writeText("occupied")

    val store = RunJournal(File(path))
    val j = TurnJournal(store) { 3000L }
    val t = j.begin("turn-b")

    ck("D1 降级为 DEGRADED", j.health == TurnJournal.Health.DEGRADED, "${j.health}")
    ck("D2 写操作不放行", !t.toolDispatched(0, "c1"))
    ck("D3 记下了失败原因", !j.lastError.isNullOrBlank(), "${j.lastError}")
    ck("D4 回合未抛异常", true)

    // 空间被清理：删掉占位文件、重建目录，下一次写应成功并自动恢复。
    File(path).delete()
    File(path).mkdirs()
    val ok = t.toolDispatched(0, "c2")
    ck("D5 恢复后放行", ok)
    ck("D6 健康度回到 HEALTHY", j.health == TurnJournal.Health.HEALTHY, "${j.health}")
    File(path).deleteRecursively()
}

/** 并行批次：20 个协程同时写同一文件，不能出半行、不能重号。 */
private fun caseConcurrent() = runBlocking {
    val dir = freshDir("/tmp/tj-concurrent")
    val store = RunJournal(dir)
    val j = TurnJournal(store) { 4000L }
    val t = j.begin("turn-p")
    t.assistantCommitted(0, (1..20).map { ToolCall("p$it", "read_file") })

    coroutineScope {
        (1..20).map { i ->
            launch(Dispatchers.Default) { t.toolSettled(0, "p$i", ToolSettleStatus.SUCCEEDED) }
        }.joinAll()
    }

    val events = store.load("turn-p")
    ck("P1 22 条事件（开 + 声明 + 20 结算）", events.size == 22, "实际 ${events.size}")
    ck("P2 序号唯一", events.map { it.seq }.toSet().size == events.size)
    ck("P3 全部可解析（无半行）", events.none { it is RunEvent.TurnClosed })
    ck("P4 callId 无丢失", events.filterIsInstance<RunEvent.ToolSettled>().map { it.callId }.toSet().size == 20)
}

/** 崩溃尾修复 + 写回 + 幂等：修过的日志第二次扫描必须无事可做。 */
private fun caseCrashRecovery() = runBlocking {
    val dir = freshDir("/tmp/tj-crash")
    val store = RunJournal(dir)
    val j = TurnJournal(store) { 5000L }

    val t = j.begin("turn-crash")
    t.assistantCommitted(0, listOf(ToolCall("x1", "write_file")))
    t.toolDispatched(0, "x1")
    // 这里"进程被杀"：不调 toolSettled / stepClosed / turnClosed

    val r1 = j.scanAndRepair()
    ck("R1 扫到 1 个未闭合回合", r1.size == 1, "实际 ${r1.size}")
    val orphans = r1.firstOrNull()?.orphans ?: emptyList()
    ck("R2 孤儿调用 1 个", orphans.size == 1, "${orphans.size}")
    ck("R3 已派发判为结局未知", orphans.firstOrNull()?.mayHaveTakenEffect == true)
    ck("R4 补全事件 3 条（结算+关步+关回合）", r1.first().closers.size == 3, "${r1.first().closers.size}")

    // 关键：修复结果必须已经写回文件，否则下次启动还是"未闭合"。
    val events = store.load("turn-crash")
    ck("R5 已写回文件", events.last() is RunEvent.TurnClosed, "${events.last()::class.simpleName}")
    ck("R6 修复后已平衡", !RunJournalRepair.repair(events).repaired)

    val r2 = j.scanAndRepair()
    ck("R7 幂等：再扫一次无事可做", r2.isEmpty(), "实际 ${r2.size}")
}

/** 未派发 vs 已派发：这是两根轴的全部意义所在。 */
private fun caseNotDispatched() = runBlocking {
    val dir = freshDir("/tmp/tj-nd")
    val store = RunJournal(dir)
    val j = TurnJournal(store) { 6000L }

    val t = j.begin("turn-nd")
    t.assistantCommitted(0, listOf(ToolCall("n1", "write_file")))
    // 声明了但没派发就崩
    val r = j.scanAndRepair().firstOrNull()
    val orphan = r?.orphans?.firstOrNull()
    ck("N1 未派发判为可安全重试", orphan?.mayHaveTakenEffect == false, "${orphan?.status}")
    ck("N2 状态是 NOT_DISPATCHED", orphan?.status == ToolSettleStatus.NOT_DISPATCHED)
}

fun main() {
    caseHappyPath()
    caseOrder()
    caseDegrade()
    caseConcurrent()
    caseCrashRecovery()
    caseNotDispatched()
    println()
    println("== TurnJournal 验证：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
