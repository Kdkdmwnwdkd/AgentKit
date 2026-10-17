import com.example.agentkit.workflow.Workflow
import com.example.agentkit.workflow.WorkflowTrigger
import com.example.agentkit.workflow.describeTrigger
import com.example.agentkit.workflow.isWorkflowDue
import java.util.Calendar
import java.util.TimeZone

/**
 * W-17 定时任务到期判定 harness（纯函数，不加载 Android）。
 *
 * 口径：
 *  - daily：今天目标时刻已过 + 上次执行早于该时刻 → 到期；
 *  - weekly：还要今天星期命中；
 *  - interval：距上次 ≥ 间隔（从未执行 → 立即到期）；
 *  - 停用一律不到期。
 *
 * 时间全部注入，不读系统时钟——同一用例在任何时区/任何日期跑结果一致。
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

private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int): Calendar =
    Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        set(y, m - 1, d, h, min, 0)
        set(Calendar.MILLISECOND, 0)
    }

private fun wf(
    trigger: WorkflowTrigger,
    enabled: Boolean = true
) = Workflow(id = "t1", name = "测试", prompt = "做点事", trigger = trigger, enabled = enabled)

fun main() {
    println("=== W-17 定时任务到期判定 ===")
    val daily8 = WorkflowTrigger(TYPE_DAILY_VAL, 8, 0)
    val tz = TimeZone.getTimeZone("Asia/Shanghai")

    check("daily：07:59 未到点 → 不到期") {
        val now = cal(2026, 10, 17, 7, 59).timeInMillis
        assertTrue("应不到期", !isWorkflowDue(wf(daily8), null, now, Calendar.getInstance(tz)))
    }

    check("daily：08:00 到点且从未执行 → 到期") {
        val now = cal(2026, 10, 17, 8, 0).timeInMillis
        assertTrue("应到期", isWorkflowDue(wf(daily8), null, now, Calendar.getInstance(tz)))
    }

    check("daily：今天 08:00 已执行 → 不到期（不重复提醒）") {
        val now = cal(2026, 10, 17, 12, 0).timeInMillis
        val ran = cal(2026, 10, 17, 8, 0).timeInMillis
        assertTrue("应不到期", !isWorkflowDue(wf(daily8), ran, now, Calendar.getInstance(tz)))
    }

    check("daily：昨天执行过、今天到点 → 到期") {
        val now = cal(2026, 10, 17, 9, 0).timeInMillis
        val ran = cal(2026, 10, 16, 8, 0).timeInMillis
        assertTrue("应到期", isWorkflowDue(wf(daily8), ran, now, Calendar.getInstance(tz)))
    }

    check("daily：停用 → 一律不到期") {
        val now = cal(2026, 10, 17, 9, 0).timeInMillis
        assertTrue("应不到期", !isWorkflowDue(wf(daily8, enabled = false), null, now, Calendar.getInstance(tz)))
    }

    check("weekly：只在周一（17 日是周六）→ 不到期") {
        val now = cal(2026, 10, 17, 9, 0).timeInMillis // 2026-10-17 星期六
        val trigger = WorkflowTrigger(TYPE_WEEKLY_VAL, 8, 0, daysOfWeek = listOf(1))
        assertTrue("周六不该到期", !isWorkflowDue(wf(trigger), null, now, Calendar.getInstance(tz)))
    }

    check("weekly：周六在列表内 + 到点 → 到期") {
        val now = cal(2026, 10, 17, 9, 0).timeInMillis
        val trigger = WorkflowTrigger(TYPE_WEEKLY_VAL, 8, 0, daysOfWeek = listOf(6))
        assertTrue("周六应到期", isWorkflowDue(wf(trigger), null, now, Calendar.getInstance(tz)))
    }

    check("interval：30 分钟间隔，已过 20 分钟 → 不到期") {
        val now = cal(2026, 10, 17, 9, 0).timeInMillis
        val trigger = WorkflowTrigger(TYPE_INTERVAL_VAL, intervalMinutes = 30)
        assertTrue("应不到期", !isWorkflowDue(wf(trigger), now - 20 * 60_000L, now, Calendar.getInstance(tz)))
    }

    check("interval：30 分钟间隔，已过 30 分钟 → 到期") {
        val now = cal(2026, 10, 17, 9, 0).timeInMillis
        val trigger = WorkflowTrigger(TYPE_INTERVAL_VAL, intervalMinutes = 30)
        assertTrue("应到期", isWorkflowDue(wf(trigger), now - 30 * 60_000L, now, Calendar.getInstance(tz)))
    }

    check("归一化：非法值被夹到合法区间") {
        val t = WorkflowTrigger.normalized("weekly", 25, -3, listOf(0, 1, 1, 8), 3)
        assertTrue("hour", t.hour == 23)
        assertTrue("minute", t.minute == 0)
        assertTrue("days", t.daysOfWeek == listOf(1))
        assertTrue("interval 下限 15", t.intervalMinutes == 15)
    }

    check("文案：每天 / 每周 / 间隔") {
        assertTrue("daily", describeTrigger(WorkflowTrigger(TYPE_DAILY_VAL, 8, 5)).contains("08:05"))
        assertTrue("weekly", describeTrigger(
            WorkflowTrigger(TYPE_WEEKLY_VAL, 21, 0, daysOfWeek = listOf(7))
        ).contains("周日"))
        assertTrue("interval", describeTrigger(
            WorkflowTrigger(TYPE_INTERVAL_VAL, intervalMinutes = 45)
        ).contains("45"))
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}

// 常量别名：避免与 Kotlin 关键字/局部变量混淆
private const val TYPE_DAILY_VAL = "daily"
private const val TYPE_WEEKLY_VAL = "weekly"
private const val TYPE_INTERVAL_VAL = "interval"
