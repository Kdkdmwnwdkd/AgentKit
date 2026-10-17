package com.example.agentkit.workflow

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Locale

/**
 * 定时工作流（W-17）：到点自动执行一条自然语言任务。
 *
 * 设计取舍（与 Operit 的 WorkflowScheduler 对比）：
 *  - Operit 是「可视化编排 + 节点图」，我们 v1 只做**单任务定时**：
 *    一句话描述要干什么 + 一个时间规则。80% 的真实需求（每天整理通知、每周清下载）就这个形态。
 *  - 触发不直接在后台跑完整 Agent：Android 上无障碍/截图/前台应用都依赖界面状态，
 *    后台跑得到的结果不可信。Worker 到期后**通知 + 待执行标记**，用户点开即执行，
 *    并把当时的上下文带过去（见 [WorkflowWorker]）。
 */
@Serializable
data class Workflow(
    val id: String,
    val name: String,
    /** 要执行的任务（自然语言，直接作为一次对话输入）。 */
    val prompt: String,
    val trigger: WorkflowTrigger,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val lastRunAt: Long? = null,
    val lastResult: String? = null
)

/**
 * 触发规则。
 *
 * @param type `daily`（每天某时刻）/ `weekly`（每周某几天某时刻）/ `interval`（每隔 N 分钟）
 * @param hour 0–23（daily/weekly）
 * @param minute 0–59
 * @param daysOfWeek 1=周一 … 7=周日（weekly；空 = 每天）
 * @param intervalMinutes interval 模式的间隔（下限 15，与 WorkManager 周期一致）
 */
@Serializable
data class WorkflowTrigger(
    val type: String = TYPE_DAILY,
    val hour: Int = 8,
    val minute: Int = 0,
    val daysOfWeek: List<Int> = emptyList(),
    val intervalMinutes: Int = 60
) {
    companion object {
        const val TYPE_DAILY = "daily"
        const val TYPE_WEEKLY = "weekly"
        const val TYPE_INTERVAL = "interval"

        const val MIN_INTERVAL_MINUTES = 15

        /** 校验并归一化：非法值夹到合法区间，未知类型退化为 daily。 */
        fun normalized(
            type: String,
            hour: Int,
            minute: Int,
            daysOfWeek: List<Int>,
            intervalMinutes: Int
        ): WorkflowTrigger {
            val t = when (type.lowercase(Locale.getDefault())) {
                TYPE_WEEKLY -> TYPE_WEEKLY
                TYPE_INTERVAL -> TYPE_INTERVAL
                else -> TYPE_DAILY
            }
            return WorkflowTrigger(
                type = t,
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                daysOfWeek = daysOfWeek.filter { it in 1..7 }.distinct().sorted(),
                intervalMinutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            )
        }
    }
}

/**
 * 到期判定（**纯函数**，W-17）。
 *
 * 语义：
 *  - interval：距上次执行 ≥ 间隔即可（从未执行过 → 立即到期）；
 *  - daily：今天的目标时刻已过，且上次执行早于「今天的目标时刻」；
 *  - weekly：今天的星期在列表内（空列表 = 每天），其余同 daily。
 *
 * 为什么要和「今天的目标时刻」比而不是和「上次执行 + 24h」比：
 * 手机可能关机/休眠几天，`上次 + 24h` 会在开机瞬间连续补跑多次；
 * 与「今天的目标时刻」比，最多补跑当天一次，符合用户直觉。
 *
 * @param lastRunAt 上次执行时间戳（null = 从未执行）
 * @param now 当前时间戳（测试注入，避免读系统时钟）
 * @param calendar 时区/日历实例（测试注入固定时区）
 */
fun isWorkflowDue(
    workflow: Workflow,
    lastRunAt: Long?,
    now: Long,
    calendar: Calendar = Calendar.getInstance()
): Boolean {
    if (!workflow.enabled) return false
    val trigger = workflow.trigger

    if (trigger.type == WorkflowTrigger.TYPE_INTERVAL) {
        val intervalMs = trigger.intervalMinutes.coerceAtLeast(WorkflowTrigger.MIN_INTERVAL_MINUTES) * 60_000L
        return lastRunAt == null || now - lastRunAt >= intervalMs
    }

    val c = calendar.clone() as Calendar
    c.timeInMillis = now
    // 今天的目标时刻
    val target = (calendar.clone() as Calendar).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, trigger.hour)
        set(Calendar.MINUTE, trigger.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    if (now < target) return false
    if (trigger.type == WorkflowTrigger.TYPE_WEEKLY && trigger.daysOfWeek.isNotEmpty()) {
        val isoDay = isoDayOfWeek(c)
        if (isoDay !in trigger.daysOfWeek) return false
    }
    return lastRunAt == null || lastRunAt < target
}

/** Calendar 的 DAY_OF_WEEK（1=周日）→ ISO（1=周一…7=周日）。纯函数。 */
internal fun isoDayOfWeek(calendar: Calendar): Int {
    val dow = calendar.get(Calendar.DAY_OF_WEEK)
    return if (dow == Calendar.SUNDAY) 7 else dow - 1
}

/** 触发规则的人类可读描述（界面与日志共用，纯函数）。 */
fun describeTrigger(trigger: WorkflowTrigger): String = when (trigger.type) {
    WorkflowTrigger.TYPE_INTERVAL ->
        "每 ${trigger.intervalMinutes} 分钟"
    WorkflowTrigger.TYPE_WEEKLY -> {
        val days = if (trigger.daysOfWeek.isEmpty()) "每天"
        else trigger.daysOfWeek.joinToString("、") { isoDayName(it) }
        "$days ${two(trigger.hour)}:${two(trigger.minute)}"
    }
    else -> "每天 ${two(trigger.hour)}:${two(trigger.minute)}"
}

private fun two(v: Int): String = if (v < 10) "0$v" else "$v"

private fun isoDayName(iso: Int): String = when (iso) {
    1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
    5 -> "周五"; 6 -> "周六"; 7 -> "周日"
    else -> "周$iso"
}
