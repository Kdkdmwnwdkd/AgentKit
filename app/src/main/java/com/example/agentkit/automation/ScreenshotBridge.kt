package com.example.agentkit.automation

import android.content.Context
import android.content.Intent
import com.example.agentkit.core.AgentLogger

/**
 * 屏幕捕获启动顺序策略（纯函数，零 Android 依赖——QA harness 可直接断言步骤序列）。
 *
 * **为什么需要它（机实测 Bug 2 修复）**：Android 14（API 34，本项目 targetSdk 34）
 * 对 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 有**强顺序约束**——
 * `startForeground(id, notification, type=MEDIA_PROJECTION)` 时，系统要求本次抓取的
 * MediaProjection token **已经被取出**（`MediaProjectionManager.getMediaProjection()`
 * 已带着用户同意的 resultCode/data 调用过且成功）；否则抛 `SecurityException`。
 * 老流程是「`startForegroundService` → 服务里 `startForeground(mediaProjection)` →
 * 之后才在 `onStartCommand` 里 `getMediaProjection()`」，顺序颠倒 → 授权返回后一进
 * App 就崩（用户看到的现象："给了权限，回到应用就闪退"）。
 *
 * 正确顺序：**先取 token，再以 mediaProjection 类型进前台**（Android 10～13 只是建议
 * 顺序，同样无副作用，故所有版本走同一条路径，避免分叉出第二套行为）。
 */
object ScreenCaptureStartPolicy {

    /** 启动过程中的一步（顺序敏感）。 */
    enum class Step {
        /** 由用户已同意的 resultCode + data 取出 MediaProjection token（必须先做）。 */
        OBTAIN_TOKEN,

        /** 以 mediaProjection 类型（Android 10+）启动前台服务。 */
        START_FOREGROUND,

        /** 放弃：没有可用来取 token 的授权数据，启动前台会立刻被系统拒绝。 */
        ABORT
    }

    /**
     * 依据「是否已有 MediaProjection token」给出应当执行的步骤序列。
     *
     * @param hasToken 是否拿到了用户同意的 resultCode + data（token 可取出）。
     * @param sdkInt 设备 API 级别；≥34（Android 14）该顺序是**强制**的，29～33 是建议，
     *   <29 无类型参数（直接用 [Step.START_FOREGROUND] 无类型版）——三者输出一致，
     *   参数留在这里是为了将来某天某版本放开约束时能在此一处调整。
     */
    fun plan(hasToken: Boolean, sdkInt: Int): List<Step> {
        check(sdkInt >= 0) { "sdkInt 非法：$sdkInt" }
        return if (hasToken) listOf(Step.OBTAIN_TOKEN, Step.START_FOREGROUND) else listOf(Step.ABORT)
    }

    /**
     * 失败原因摘要：异常的类名 + message（截断到 120 字符，防止日志爆掉）。
     * 纯函数，给 [failureMessage] 与 [guard] 共用。
     */
    fun describe(t: Throwable?): String {
        if (t == null) return "未知原因"
        val name = t.javaClass.simpleName.ifBlank { "Throwable" }
        val detail = t.message?.trim()?.take(120)?.ifBlank { null }
        return if (detail == null) name else "$name：$detail"
    }

    /**
     * 面向用户的失败文案：**必须带原因、必须可 actionable**（告诉用户能重试），
     * 杜绝「点了没反应 / 静默失败」。UI 层（Components.kt 的权限入口）与日志共用。
     */
    fun failureMessage(reason: String?): String {
        val cause = reason?.trim()?.takeIf { it.isNotBlank() } ?: "未知原因"
        return "屏幕捕获启动失败：$cause，可重试"
    }

    /**
     * 兜底执行：**任何** Throwable（含 Error / SecurityException / 厂商魔改 ROM 上的
     * 奇葩异常）都被吞在这里转换成 [GuardResult]，绝不上抛——这是 Bug 2 的底线修复：
     * 前台服务的启动失败必须「可见 + 可知」，但**绝不能让进程崩掉**。
     *
     * @param block 可能抛异常的启动动作（真实场景下是 startForeground / startService）。
     * @return 成功时 [GuardResult.failure] 为 null；失败时带上原因摘要与原始异常。
     */
    fun guard(block: () -> Unit): GuardResult =
        try {
            block()
            GuardResult(null, null)
        } catch (t: Throwable) {
            GuardResult(describe(t), t)
        }

    /** [guard] 的结果：[failure] 非空即失败；[cause] 供调用方把堆栈交给日志。 */
    data class GuardResult(val failure: String?, val cause: Throwable?) {
        /** 是否成功执行（无异常）。 */
        val succeeded: Boolean get() = failure == null
    }
}

/**
 * 屏幕捕获桥：持有 MediaProjection 授权结果（resultCode + data），
 * 供 UI 在用户授予屏幕捕获权限后启动前台服务，并对外暴露捕获状态。
 *
 * 与 AutomationBridge 同构——执行层（服务实例、授权结果）与 UI/工具层解耦，
 * 工具只依赖本桥，不直接持有 Android 服务实例。
 *
 * **Bug 2 修复后的两条新契约**：
 *  1. [startService] 返回成功/失败布尔，并通过 [lastError] 暴露可读原因——
 *     UI 必须据此给「屏幕捕获启动失败：<原因>，可重试」，不允许静默；
 *  2. 启动动作整体包在 [ScreenCaptureStartPolicy.guard] 里，`startForegroundService`
 *     自身（后台启动限制 / SecurityException）也不允许把进程带崩。
 */
object ScreenshotBridge {
    /** MediaProjection 授权结果码。 */
    var resultCode: Int = 0

    /** MediaProjection 授权返回的 Intent（含投影 token）。 */
    var permissionData: Intent? = null

    /** 当前是否正在捕获屏幕。 */
    var isCapturing: Boolean = false

    /**
     * 最近一次启动失败的可读原因（null = 无一次性失败）。
     * UI 在启动失败时读取它拼成「屏幕捕获启动失败：<原因>，可重试」，读完即由调用方清掉。
     */
    @Volatile
    var lastError: String? = null

    /**
     * 统一失败出口：把「启动失败」落成 [lastError]（供 UI 显示）并记 ERROR 日志。
     * 服务层与桥自身共用，保证文案唯一来源 = [ScreenCaptureStartPolicy.failureMessage]。
     */
    fun reportFailure(reason: String) {
        val msg = ScreenCaptureStartPolicy.failureMessage(reason)
        lastError = msg
        isCapturing = false
        AgentLogger.e("ScreenCapture", msg)
        logLastError(msg)
    }

    /**
     * 真机第三轮 Bug C（只做确认 + 统一留痕）：用户靠日志报现象，故每次 [lastError]
     * 落成非空值都补一条**统一关键字**的 WARN（原有各处日志前缀不一，不便 grep）。
     */
    private fun logLastError(value: String) =
        AgentLogger.w("ScreenCapture", "lastError 落库：${AgentLogger.sanitize(value).take(200)}")

    fun setPermission(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.permissionData = data
    }

    /**
     * 启动前台捕获服务（顺序见 [ScreenCaptureStartPolicy]：先取 token、后进前台）。
     *
     * @return true = 已把 Intent 交给系统且未出错；false = 未授权 / 系统拒绝，见 [lastError]。
     * **绝不抛异常**：任何失败都转成 false + [lastError]（Bug 2 底线）。
     */
    fun startService(context: Context): Boolean {
        val data = permissionData
        if (data == null || resultCode == 0) {
            lastError = ScreenCaptureStartPolicy.failureMessage("尚未取得屏幕捕获授权")
            AgentLogger.w("ScreenCapture", "未取得授权就启动捕获服务：$lastError")
            logLastError(lastError!!)
            return false
        }
        val failure = ScreenCaptureStartPolicy.guard {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            context.startForegroundService(intent)
        }
        val reason = failure.failure
        if (reason != null) {
            lastError = ScreenCaptureStartPolicy.failureMessage(reason)
            AgentLogger.e("ScreenCapture", "startForegroundService 失败：$lastError", failure.cause)
            logLastError(lastError!!)
            isCapturing = false
            return false
        }
        lastError = null
        return true
    }

    /** 停止前台捕获服务；同时清掉上一次的一次性失败文案（重开是干净起点）。 */
    fun stopService(context: Context) {
        AgentLogger.i("ScreenCapture", "停止屏幕捕获服务")
        ScreenCaptureStartPolicy.guard {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
        isCapturing = false
        lastError = null
    }
}
