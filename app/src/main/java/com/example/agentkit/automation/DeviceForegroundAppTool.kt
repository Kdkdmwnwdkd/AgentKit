package com.example.agentkit.automation

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.agentkit.core.AgentTool
import com.example.agentkit.core.JsonSchema
import com.example.agentkit.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * W-7 P0-3：device_foreground_app 升级版。
 *
 * 相对初版（queryUsageStats 桶法）的三处升级，全部学自 RikkaHub
 * `data/ai/tools/local/ScreenTimeTool.kt`（AGPL-3.0，只读思路、净室重写）：
 *
 *  1. **未授权自动打开系统设置页**（`ACTION_USAGE_ACCESS_SETTINGS`）+ 返回结构化
 *     失败——模型拿到失败结果后会把「需要用户去开权限」如实转告用户，而不是
 *     反复重试同一调用。双动作（打开设置 + 结构化错误）与 RikkaHub 同款语义。
 *  2. **前台读取从桶法升级为 UsageEvents 事件配对法**：逐个扫描
 *     ACTIVITY_RESUMED/MOVE_TO_FOREGROUND 事件，取时间序最后一个作为当前前台。
 *     RikkaHub 原版注释：「比 queryAndAggregateUsageStats 更接近系统『屏幕使用
 *     时间』，避免统计桶溢出导致的范围偏差」——「刚切到微信」这类场景桶法已知
 *     不可靠（桶按整时间段聚合，5 分钟窗口常拿不到最新前台）。
 *  3. **launcher 包排除**：最后一个 RESUMED 若是桌面（HOME 意图解析者），
 *     判定为「在桌面」而非某个前台应用——否则模型会把「桌面」当成一个可操作
 *     的 App。
 *
 * 降级链：事件法无数据 → 回落桶法（既有行为）；两者都无 → permission_missing
 * 引导。**绝不静默返回空白**。
 */

/** 事件类型常量（与 `android.app.usage.UsageEvents.Event` 同值；抽出常量使纯函数可测）。 */
internal const val EVENT_ACTIVITY_RESUMED: Int = 1 // API29+；与 MOVE_TO_FOREGROUND 同值
internal const val EVENT_SCREEN_NON_INTERACTIVE: Int = 15 // 息屏（不参与配对，仅备查）

/**
 * 一条 UsageEvents 事件（纯数据，JVM harness 可直接构造，无需 Android）。
 *
 * @param type 事件类型（见 [EVENT_ACTIVITY_RESUMED] 等常量）
 * @param packageName 事件包名
 * @param timeStamp 事件时间戳（毫秒）
 */
internal data class UsageEventRecord(
    val type: Int,
    val packageName: String,
    val timeStamp: Long
)

/**
 * 事件流 → 当前前台包名（**纯函数**，W-7 P0-3 核心可测点）。
 *
 * 规则：取时间序上**最后一个** RESUMED/MOVE_TO_FOREGROUND 事件的包名；
 * PAUSED/BACKGROUND 不参与（它们表达「刚离开」，当前实现不需要维护 Activity
 * 栈）；时间戳相同后者覆盖前者（与系统事件追加顺序一致）。
 *
 * 调用方负责两件事：① 把 launcher 包名解读为「在桌面」；② 事件法无数据时
 * 决定是否回落桶法。
 *
 * @param events 事件列表（无需预排序，函数内部按时间戳取最大）
 * @return 前台包名；无任何 RESUMED 事件返回 null
 */
internal fun pickForegroundPackageFromEvents(events: List<UsageEventRecord>): String? {
    var best: String? = null
    var bestTs = Long.MIN_VALUE
    for (e in events) {
        if (e.type != EVENT_ACTIVITY_RESUMED) continue
        if (e.packageName.isBlank()) continue
        if (e.timeStamp >= bestTs) {
            bestTs = e.timeStamp
            best = e.packageName
        }
    }
    return best
}

/**
 * 前台探测的事件窗口（毫秒）：只扫最近 60 秒的 RESUMED 事件。
 *
 * 取 60s 而非 5min：事件法是「最后一个 RESUMED」语义，窗口只需覆盖最近一次
 * 切换；太短（如 5s）在息屏/久置后会漏事件被迫回落桶法，太长无收益。
 */
private const val FOREGROUND_EVENT_WINDOW_MS = 60_000L

class DeviceForegroundAppTool(private val context: Context) : AgentTool {
    override val name = "device_foreground_app"
    override val description =
        "获取当前正在前台运行的应用包名与名称。用于感知用户此刻正在用哪个 App，判断操作上下文。" +
            "需要 PACKAGE_USAGE_STATS 权限（未授权时会自动打开系统设置页引导用户开启）。"
    override val systemPrompt =
        "你可以用 device_foreground_app 获取当前前台应用，感知用户此刻在哪个 App 里。当任务需要判断当前界面归属、" +
            "或「用户在用什么 App」时调用它。若返回权限未授权，请让用户去刚弹出的设置页开启「使用情况访问权限」后再重试。"
    override val parameters = JsonSchema.objectSchema()

    /** 只读：仅查询使用统计，无副作用（未授权时打开设置页是引导动作，不改用户数据）。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext ToolResult.fail(
                code = "service_unavailable",
                message = "无法获取 UsageStatsManager。",
                hint = "当前设备不支持使用情况统计；请改用 ui_tree 判断当前界面。"
            )

        val now = System.currentTimeMillis()

        // 1. 权限探测：24 小时窗口桶为空 ≈ 未授权（任何用过的手机都有统计）。
        //    不用 queryEvents 探测——事件法在「有权限但最近 60s 无切换」时同样
        //    可能为空，会把已授权设备误判成未授权。
        val dayStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 24L * 60 * 60 * 1000,
            now
        )
        if (dayStats.isNullOrEmpty()) {
            openUsageAccessSettings()
            return@withContext ToolResult.fail(
                code = "permission_missing",
                message = "未能读取前台应用：尚未授予「使用情况访问权限」（PACKAGE_USAGE_STATS）。",
                hint = "已尝试打开系统设置页，请引导用户找到本应用并开启「使用情况访问权限」后重试；" +
                    "用户不便授权时，改用 ui_tree 读取当前界面。"
            )
        }

        // 2. 事件配对法（主路径）：扫最近 60s 的 RESUMED 事件取最后一个。
        val events = usm.queryEvents(now - FOREGROUND_EVENT_WINDOW_MS, now)
        val records = ArrayList<UsageEventRecord>(16)
        while (events.hasNextEvent()) {
            val e = UsageEvents.Event()
            if (events.getNextEvent(e)) {
                records.add(
                    UsageEventRecord(
                        type = e.eventType,
                        packageName = e.packageName ?: "",
                        timeStamp = e.timeStamp
                    )
                )
            }
        }
        val pkg = pickForegroundPackageFromEvents(records)
        if (pkg != null) {
            // launcher 判定：桌面不是「前台应用」，如实说明，防止模型把桌面当 App 操作
            if (pkg in loadLauncherPackages()) {
                return@withContext ToolResult.ok(
                    code = "foreground_home",
                    message = "当前在桌面（主屏幕），没有运行中的前台应用。"
                )
            }
            return@withContext ToolResult.ok(
                code = "foreground_app_ok",
                message = "当前前台应用：${labelOf(pkg)}（包名：$pkg）"
            )
        }

        // 3. 降级：事件法无数据（最近 60s 无切换/息屏）→ 桶法（既有行为）。
        val recent = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 1000L * 60 * 5,
            now
        ).filter { it.lastTimeUsed > 0 }.maxByOrNull { it.lastTimeUsed }

        if (recent == null) {
            return@withContext ToolResult.fail(
                code = "permission_missing",
                message = "未能确定前台应用：使用情况数据为空。",
                hint = "引导用户授予「使用情况访问权限」后重试；或改用 ui_tree 读取界面。"
            )
        }
        ToolResult.ok(
            code = "foreground_app_ok",
            message = "当前前台应用：${labelOf(recent.packageName)}（包名：${recent.packageName}）"
        )
    }

    /** 打开系统「使用情况访问」设置页（失败静默——部分 ROM 可能无此 Activity）。 */
    private fun openUsageAccessSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 解析「桌面」包名集合（HOME 意图解析者；多桌面/多启动器全收录）。 */
    private fun loadLauncherPackages(): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            pm.queryIntentActivities(home, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** 包名 → 显示名（失败回落包名本身）。 */
    private fun labelOf(pkg: String): String = try {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
            .toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }
}
