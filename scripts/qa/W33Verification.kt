import com.example.agentkit.core.job.JobInfo
import com.example.agentkit.core.job.JobLogic
import com.example.agentkit.core.job.JobStatus

/**
 * W-33 harness：后台命令的纯逻辑。
 *
 * 覆盖：命令清洗、日志尾行（含省略标记）、耗时文案、状态文案、重启对账、列表摘要。
 * 这几项直接决定用户看到的是「命令完成了」还是「命令挂了」——错一个字都会误导人。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun job(
    id: String = "j1",
    command: String = "echo hi",
    status: String = JobStatus.RUNNING,
    startedAt: Long = 1_000_000L,
    finishedAt: Long = 0L,
    exitCode: Int? = null
) = JobInfo(
    id = id,
    command = command,
    startedAt = startedAt,
    finishedAt = finishedAt,
    exitCode = exitCode,
    status = status
)

fun main() {
    println("== W-33 harness：后台命令 ==")

    // ——————————————— 命令清洗 ———————————————
    check("首尾空白被清掉") {
        assertTrue("应清洗", JobLogic.normalizeCommand("  echo hi  ") == "echo hi")
    }
    check("结尾换行被去掉（拼接命令时多一个换行会让 shell 提前结束）") {
        assertTrue("应去换行", JobLogic.normalizeCommand("echo hi\n\n") == "echo hi")
    }
    check("空命令 → null") {
        assertTrue("应 null", JobLogic.normalizeCommand("   \n  ") == null)
    }
    check("超长命令 → null") {
        assertTrue("应 null", JobLogic.normalizeCommand("x".repeat(JobLogic.MAX_COMMAND + 1)) == null)
    }
    check("多行命令保留内部换行（脚本片段需要）") {
        val cmd = JobLogic.normalizeCommand("cd /tmp\necho hi")
        assertTrue("应保留内部换行，实际 $cmd", cmd == "cd /tmp\necho hi")
    }

    // ——————————————— 日志尾行 ———————————————
    check("空日志 → 明确占位而不是空串") {
        assertTrue("应占位", JobLogic.tailLines("").contains("暂无输出"))
    }
    check("行数不超限时原样返回") {
        val text = "a\nb\nc"
        assertTrue("应原样", JobLogic.tailLines(text, 10) == text)
    }
    check("末尾换行不会多出一个空行") {
        assertTrue("不应有尾部空行", JobLogic.tailLines("a\nb\n", 10) == "a\nb")
    }
    check("超限时只留最后 N 行并标注省略数") {
        val text = (1..10).joinToString("\n")
        val got = JobLogic.tailLines(text, 3)
        assertTrue("应含省略提示，实际 $got", got.contains("前 7 行已省略"))
        assertTrue("应保留最后一行", got.endsWith("10"))
        assertTrue("不应包含第一行", !got.contains("\n1\n"))
    }
    check("lines 参数被夹取（0 不会返回空）") {
        val got = JobLogic.tailLines("a\nb", 0)
        assertTrue("应至少一行", got.contains("b"))
    }

    // ——————————————— 耗时文案 ———————————————
    check("耗时：秒 / 分秒 / 时分") {
        assertTrue("8s", JobLogic.durationText(8_000) == "8s")
        assertTrue("3m20s", JobLogic.durationText(200_000) == "3m20s")
        assertTrue("3m（整分不带 0s）", JobLogic.durationText(180_000) == "3m")
        assertTrue("1h05m", JobLogic.durationText(3_900_000) == "1h05m")
    }
    check("耗时：0 或负数兜底 0s") {
        assertTrue("0", JobLogic.durationText(0) == "0s")
        assertTrue("负", JobLogic.durationText(-5) == "0s")
    }

    // ——————————————— 状态文案 ———————————————
    check("运行中：显示已运行时长（按 now 计算）") {
        val text = JobLogic.statusText(job(startedAt = 1_000_000L), nowMs = 1_012_000L)
        assertTrue("应含运行中与 12s，实际 $text", text.contains("运行中") && text.contains("12s"))
    }
    check("完成：exit 0 + 耗时") {
        val text = JobLogic.statusText(
            job(status = JobStatus.DONE, startedAt = 1_000L, finishedAt = 6_000L, exitCode = 0),
            nowMs = 9_999_999L
        )
        assertTrue("应含完成与 5s，实际 $text", text.contains("完成") && text.contains("5s"))
    }
    check("失败：带非零退出码") {
        val text = JobLogic.statusText(
            job(status = JobStatus.FAILED, finishedAt = 2_000L, exitCode = 127),
            nowMs = 9_999_999L
        )
        assertTrue("应含 127，实际 $text", text.contains("失败") && text.contains("127"))
    }
    check("取消 / 超时 / 中断各有明确文案（不混成「失败」）") {
        val cancelled = JobLogic.statusText(job(status = JobStatus.CANCELLED, finishedAt = 2_000L), 9_999_999L)
        val timeout = JobLogic.statusText(job(status = JobStatus.TIMEOUT, finishedAt = 2_000L), 9_999_999L)
        val interrupted = JobLogic.statusText(job(status = JobStatus.INTERRUPTED), 9_999_999L)
        assertTrue("取消文案", cancelled.contains("已取消"))
        assertTrue("超时文案", timeout.contains("超时"))
        assertTrue("中断文案要说明结果未知", interrupted.contains("结果未知"))
    }

    // ——————————————— 重启对账 ———————————————
    check("残留的 running 且开始很早 → 标为 interrupted") {
        val updated = JobLogic.reconcileStatus(job(startedAt = 1_000L), staleBeforeMs = 10_000L)
        assertTrue("应改为 interrupted", updated.status == JobStatus.INTERRUPTED)
    }
    check("刚刚启动的 running 不动（避免误杀正在跑的）") {
        val original = job(startedAt = 20_000L)
        assertTrue("应保持", JobLogic.reconcileStatus(original, staleBeforeMs = 10_000L) == original)
    }
    check("已是终态的任务不动") {
        val done = job(status = JobStatus.DONE, finishedAt = 5_000L, exitCode = 0)
        assertTrue("应保持", JobLogic.reconcileStatus(done, staleBeforeMs = 10_000L) == done)
    }

    // ——————————————— 列表摘要 ———————————————
    check("摘要含 id + 状态 + 命令") {
        val line = JobLogic.summaryLine(job(command = "find . -name '*.log'"), nowMs = 1_001_000L)
        assertTrue("应含 id", line.contains("j1"))
        assertTrue("应含命令", line.contains("find ."))
        assertTrue("应含状态", line.contains("运行中"))
    }
    check("摘要把多行命令压成一行、超长截断") {
        val line = JobLogic.summaryLine(job(command = "echo a\necho b\n" + "x".repeat(80)), nowMs = 1_001_000L)
        assertTrue("不应含换行（除缩进那行外）", line.lines().size == 2)
        assertTrue("应截断", line.contains("…"))
    }

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
