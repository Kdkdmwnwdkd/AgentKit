import com.example.agentkit.core.agent.journal.RunEvent
import com.example.agentkit.core.agent.journal.RunJournal
import com.example.agentkit.core.agent.journal.ToolCallRef
import com.example.agentkit.core.agent.journal.ToolSettleStatus
import java.io.File

private var p = 0
private var f = 0
private fun ck(name: String, ok: Boolean, detail: String = "") {
    if (ok) { p++; println("  PASS  $name") } else { f++; println("  FAIL  $name  $detail") }
}

fun main() {
    val dir = File("/tmp/verify/journal-test").apply { deleteRecursively(); mkdirs() }
    val journal = RunJournal(dir)
    println("== RunJournal 落盘 ==")

    // J1 写入 + 读回
    val w = journal.open("turn-1")
    w.turnOpened(100)
    w.assistantCommitted(110, 1, listOf(ToolCallRef("c1", "write_file")))
    w.toolDispatched(120, 1, "c1")
    val loaded = journal.load("turn-1")
    ck("J1 写入顺序", loaded.map { it.seq } == listOf(1L, 2L, 3L), loaded.map { it.seq }.toString())
    ck("J1 类型正确", loaded[2] is RunEvent.ToolDispatched)

    // J2 崩溃产生半行：load 丢弃但不报错
    journal.fileOf("turn-1").appendText("v1\t4\t130\tturn-1\tSETT")  // 故意截断
    val afterTruncate = journal.load("turn-1")
    ck("J2 半行被丢弃", afterTruncate.size == 3, "size=${afterTruncate.size}")

    // J3 未闭合回合能被扫出
    val found = journal.scanUnclosed()
    ck("J3 扫出未闭合回合", found.size == 1, "size=${found.size}")
    ck("J3 识别为结局未知", found[0].orphans.single().status == ToolSettleStatus.OUTCOME_UNKNOWN)

    // J4 修复事件由 scanUnclosed 自己写回（R1 起），再扫应为空（幂等）
    val again = journal.scanUnclosed()
    ck("J4 修复后无未闭合", again.isEmpty(), again.size.toString())
    ck("J4 修复事件已落盘", journal.load("turn-1").last() is RunEvent.TurnClosed)

    // J5 重开写入句柄时 seq 续上，不重号
    val w3 = journal.open("turn-1")
    val next = w3.turnClosed(999, "completed")
    ck("J5 seq 续接", next.seq == 7L, "seq=${next.seq}")  // 原 3 条 + 修复 3 条 -> 下一条 7

    // J6 prune 只留最近 N 个
    repeat(5) { i ->
        val jw = journal.open("turn-prune-$i")
        jw.turnOpened(1000L + i); jw.turnClosed(1001L + i, "completed")
        Thread.sleep(5) // 依赖 mtime 排序，拉开时间
    }
    journal.prune(keep = 3)
    val remaining = dir.listFiles { it -> it.isFile && it.name.endsWith(".rjl") }!!.size
    ck("J6 保留 3 个", remaining == 3, "remaining=$remaining")

    println()
    println("结果：$p 通过 / $f 失败")
    if (f > 0) kotlin.system.exitProcess(1)
}
