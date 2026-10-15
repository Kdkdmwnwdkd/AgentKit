package com.example.agentkit.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.example.agentkit.core.AgentLogger
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 屏幕捕获前台服务（L2 截图视觉通道）。
 *
 * 通过 MediaProjection + ImageReader + VirtualDisplay 抓取屏幕帧，
 * 暴露 captureBitmap() 供 ui_screenshot 工具调用。
 *
 * 设计要点（净室原创，符合 Android 14 要求）：
 *  - 必须以后台前台服务（foregroundServiceType=mediaProjection）形式运行，
 *    MediaProjection 才能正常创建（API 34 强制）。
 *  - 服务实例以 companion 暴露，工具层只依赖 ScreenshotBridge，不直接持有服务。
 *
 * **启动顺序（Android 14 三条硬规则，务必保持——第六/七轮修复真机确证）**：
 *  - 规则 C（`createVirtualDisplay` 前置条件）：targetSdk ≥ 34 时**必须先
 *    `MediaProjection.registerCallback()` 再 `createVirtualDisplay()`**，否则系统抛
 *    `IllegalStateException("Must register a callback before starting capture, to manage
 *    resources in response to MediaProjection states.")`（官方文档 Android 14 行为变更
 *    「注册回调」条目 + 真机 2026-10-15 18:36:20 日志实证）。故 registerCallback 的失败
 *    在 Android 14 上是**致命**的，不再按「不致命」降级。
 *  - 规则 A（`getMediaProjection` 前置条件）：Android 14（targetSdk 34）上
 *    `getMediaProjection()` 要求本应用**已有以 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`
 *    类型运行的前台服务**，否则系统在投影 start 时抛 SecurityException
 *    （"Media projections require a foreground service of type ..."——hotfix6 把
 *    取 token 挪到服务生命周期之前，功能直接不可用，教训）。Operit 同款实证：
 *    先起 FGS 进前台，再取 token。
 *  - 规则 B（前台服务超时）：`startForegroundService()` 之后系统只给约 5s
 *    必须 `startForeground()`，否则抛 `ForegroundServiceDidNotStartInTimeException`
 *    ——**该异常不可捕获，进程直接被杀**（前几轮「反复开启崩 4 次」的根因）。
 *  - **唯一正确顺序**：`onStartCommand` ① **先进前台（带 mediaProjection 类型）**→
 *    ② `getMediaProjection`（规则 A 此时已满足）→ ③ 才建 ImageReader / VirtualDisplay。
 *    ① 在入口第一句完成，与后续成败无关（规则 B 稳如磐石）；②③ 的任何失败都发生在
 *    ① 之后，只走 [failSafe] 可见失败，**不可能**再触发超时崩溃。
 *  - Android 10～13（API 29-33）：同样的顺序没有副作用（类型已支持但无强制校验），
 *    <29：`startForeground` 无 type 参数，顺序无意义。**全版本共用一条路径**，不做分支。
 *  - 无论哪个版本，前台启动失败都**只允许**「记日志 + 上报 lastError + stopSelf」，
 *    绝不让异常逃出（老实现的 crash 就是异常从 onCreate 逃逸直接杀进程）。
 *  - 另设 2s 看门狗：万一 ① 链路异常拖长，赶在系统 5s 红线之前主动收摊并留现场，
 *    把「无声闪退」变成「日志里看得见的失败」。
 *
 * **全流程兜底（Bug「还是闪退」第二轮修复，本轮重点）**：
 *  顺序修好后仍然闪退 ⇒ 崩点在顺序之外。现在**每一个**可能抛异常的系统调用
 *  （`getMediaProjection` / `ImageReader.newInstance` / `setOnImageAvailableListener` /
 *  `createVirtualDisplay` / `registerCallback` / `acquireLatestImage` / `stopSelf`）
 *  都包在 [ScreenCaptureGuard.guardValue] / [ScreenCaptureGuard.guard] / `runCatching` 里；
 *  `displayMetrics` 尺寸先过 [ScreenCaptureGuard.sanitizeSize] 防御；
 *  任何异常一律走 [failSafe]：**日志带堆栈 + lastError + 按 [ScreenCaptureGuard.cleanupPlan]
 *  逐步安全停止**。**本类不向系统抛任何异常**——宁可「捕获失败可见」，也不要「闪退」。
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

/** 前台是否已成功启动（防闪退生死线的完成标志，看门狗据此放行/收摊）。 */
    @Volatile
    private var foregroundStarted: Boolean = false

/** 前台启动看门狗（startForegroundService 后 2s 未进前台则记日志 + stopSelf）。 */
    private var foregroundWatchdog: Handler? = null

    /** 投影回调：用户从控制中心「停止投屏」时系统会回调 onStop（零依赖，顺手加上）。 */
    private var projectionCallback: MediaProjection.Callback? = null

    /** 帧可用回调的 Handler（主线程 Looper；创建失败时退化为不注册回调，不影响捕获）。 */
    private var frameHandler: Handler? = null

    /** 是否已为「首次拿到帧」打过面包屑（只打一次，避免刷屏）。 */
    @Volatile
    private var firstFrameLogged: Boolean = false

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        // 只做「有帧了」的留痕；真正的取帧在 captureBitmap 里同步做（避免回调里做重活）
        runCatching {
            AgentLogger.d("ScreenCapture", "帧可用（reader=${reader.width}x${reader.height}）")
            if (!firstFrameLogged) {
                firstFrameLogged = true
                ScreenCaptureTrace.log(
                    ScreenCaptureTrace.FIRST_FRAME,
                    "已收到首帧 ${reader.width}x${reader.height}"
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 刻意不在这里启动前台：onCreate 里还没有授权数据（extras 随 onStartCommand
        // 才到），且前台启动统一放在 onStartCommand 第一句——这是 Android 14 两条
        // 硬规则（getMediaProjection 前置条件 + 5s 超时红线）的共同解，见类 KDoc。
        AgentLogger.i("ScreenCapture", "服务创建，等待 onStartCommand 第一句进前台")
        ScreenCaptureTrace.log(ScreenCaptureTrace.SERVICE_ON_CREATE, "onCreate 进入（尚未进前台）")
        // 看门狗：startForegroundService 后若 2s 仍未进前台，说明 onStartCommand 链路过长，
        // 记 ERROR 面包屑并自尽——赶在系统 5s 超时（ForegroundServiceDidNotStartInTimeException，
        // 不可捕获）之前留下可见日志，而不是无声闪退。
        foregroundWatchdog = Handler(Looper.getMainLooper()).apply {
            postDelayed({
                if (!foregroundStarted) {
                    ScreenCaptureTrace.logFailure(
                        ScreenCaptureTrace.START_FOREGROUND,
                        "看门狗触发：startForegroundService 后 2s 仍未进入前台，主动收摊",
                        null
                    )
                    AgentLogger.e("ScreenCapture", "看门狗：2s 未进前台，stopSelf 保命")
                    failSafe(
                        ScreenCaptureGuard.Stage.START_FOREGROUND,
                        "前台启动看门狗超时（2s 未进前台）",
                        null
                    )
                }
            }, WATCHDOG_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 第六轮 Bug 修复（hotfix6 把取 token 挪到服务生命周期之前 → getMediaProjection
        // 抛 SecurityException「Media projections require a foreground service of type
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION」，功能直接不可用——用户真机日志实证）。
        //
        // Android 14 真规则（Operit 源码实证：ScreenCaptureActivity 注释「Android 14
        // requirement: Start foreground service with mediaProjection type BEFORE calling
        // getMediaProjection」+ 轮询 isMediaProjectionForegroundReady 再取 token）：
        //   **getMediaProjection() 要求本应用已有以 mediaProjection 类型运行的前台服务**。
        // 唯一正确顺序：① **先进前台（带 mediaProjection 类型）**→ ② getMediaProjection
        //   → ③ 建 ImageReader/VirtualDisplay。两个红线同时满足：
        //   - 5s 超时红线（不可捕获）：① 在入口第一句完成，与后续成败无关；
        //   - getMediaProjection 前置条件：① 已满足；
        //   - ②③ 任何失败都只走 failSafe 可见失败，不可能再触发超时杀进程。
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        ScreenCaptureTrace.log(
            ScreenCaptureTrace.SERVICE_ON_START,
            "onStartCommand 进入 resultCode=$resultCode data=${if (data == null) "null" else "ok"}"
        )

        // ① **先进前台**（防闪退生死线 + getMediaProjection 前置条件：两条规则的共同解）
        startForegroundSafely()
        if (!foregroundStarted) {
            // startForegroundSafely 内部失败已 failSafe + stopSelf，这里只挡后续流程
            AgentLogger.w("ScreenCapture", "前台未就绪，放弃后续投影建立")
            return START_NOT_STICKY
        }

        // ② 取 token（此时 FGS 已以 mediaProjection 类型运行，Android 14 才允许）。
        //    extras 缺失 = 异常拉起；取失败 = 授权失效/已被消费——都走 failSafe，
        //    但 startForeground 已执行、5s 计时已满足，**不会崩进程**。
        if (resultCode == 0 || data == null) {
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "缺少屏幕捕获授权数据（请重新授予屏幕捕获权限）",
                null
            )
            return START_NOT_STICKY
        }
        val projection = obtainProjectionWithRetry(resultCode, data)
        if (projection == null) {
            // obtainProjectionWithRetry 内部已落失败面包屑；这里收摊并引导重新授权
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "获取屏幕捕获令牌失败（授权可能已失效，请重新授予屏幕捕获权限）",
                null
            )
            return START_NOT_STICKY
        }
        mediaProjection = projection
        ScreenCaptureTrace.log(ScreenCaptureTrace.OBTAIN_TOKEN, "getMediaProjection 成功")

        // ③ 才建 ImageReader + VirtualDisplay（重活放最后；失败走 failSafe，已有全兜底）
        setupCapture(projection)
        if (virtualDisplay == null) {
            AgentLogger.w("ScreenCapture", "投影未就绪，已安全停止")
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * 取 MediaProjection token（**仅限 SecurityException 有限重试**）。
     *
     * 为什么重试：`startForeground()` 返回后，个别 ROM 上前台状态传播有极小异步窗口，
     * `getMediaProjection` 可能先抛一次 SecurityException。Operit 的做法是轮询
     * `isMediaProjectionForegroundReady`（30ms 间隔、1.5s 超时）再取；本服务内等价做法
     * 就是「带类型 startForeground 返回后直接取，遇 SecurityException 稍候重试」。
     *
     * 重试边界：仅 SecurityException 可重试（最多 [MAX_TOKEN_RETRY] 次，每次间隔
     * [TOKEN_RETRY_DELAY_MS]）；其余异常 / 返回 null / 服务不可用 → 直接算失败，
     * 落失败面包屑返回 null，由调用方走 failSafe（前台已起，不崩）。
     */
    private fun obtainProjectionWithRetry(resultCode: Int, data: Intent): MediaProjection? {
        val manager = ScreenCaptureGuard.guardValue {
            getSystemService(MediaProjectionManager::class.java)
        }.value
        if (manager == null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.OBTAIN_TOKEN,
                "MediaProjectionManager 不可用",
                null
            )
            return null
        }
        var attempt = 0
        while (true) {
            attempt++
            val r = ScreenCaptureGuard.guardValue { manager.getMediaProjection(resultCode, data) }
            val projection = r.value
            if (projection != null) return projection
            val retriable = r.cause is SecurityException && attempt <= MAX_TOKEN_RETRY
            if (!retriable) {
                ScreenCaptureTrace.logFailure(
                    ScreenCaptureTrace.OBTAIN_TOKEN,
                    "getMediaProjection 失败（第 $attempt 次）：${r.failure}",
                    r.cause
                )
                AgentLogger.e("ScreenCapture", "getMediaProjection 失败：${r.failure}", r.cause)
                return null
            }
            // SecurityException：等前台状态传播后再试（仅主线程睡百毫级，无 ANR 风险）
            AgentLogger.w(
                "ScreenCapture",
                "getMediaProjection 第 $attempt 次遇 SecurityException，${TOKEN_RETRY_DELAY_MS}ms 后重试"
            )
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.OBTAIN_TOKEN,
                "第 $attempt 次遇 SecurityException（前台状态传播中），重试",
                r.cause
            )
            try {
                Thread.sleep(TOKEN_RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    /**
     * 进前台 + 失败兜底。**任何异常都不从这里逃出**（捕获 Throwable，含 Error）。
     *
     * 带 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 类型进前台是本服务的第一要务：
     *  - 满足 5s 超时红线（不可捕获，进程级）；
     *  - 更是随后 [obtainProjectionWithRetry]（`getMediaProjection`）的前置条件
     *    （Android 14 规则 A：无该类型 FGS 则投影 start 抛 SecurityException）。
     * manifest 已声明 `foregroundServiceType="mediaProjection"` +
     * `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限，正常路径必带类型。
     *
     * 万一带类型进前台在个别 ROM 上被拒：**退化无类型进前台保命**（5s 红线 B 优先于一切），
     * 随后 getMediaProjection 会因缺类型可见地失败（failSafe），可接受降级；
     * 绝不让异常逃逸，更绝不让 startForeground 从未执行（那才会触发不可捕获的超时崩溃）。
     */
    private fun startForegroundSafely() {
        val notification = ScreenCaptureGuard.guardValue { buildForegroundNotification() }.value
        if (notification == null) {
            ScreenCaptureTrace.logFailure(ScreenCaptureTrace.START_FOREGROUND, "前台通知构建失败", null)
            failSafe(ScreenCaptureGuard.Stage.START_FOREGROUND, "前台通知构建失败", null)
            return
        }
        val typed = ScreenCaptureStartPolicy.guard {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
        if (typed.failure != null) {
            AgentLogger.w("ScreenCapture", "带类型进前台失败，退化无类型保命：${typed.failure}")
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.START_FOREGROUND,
                "带类型失败（${typed.failure}），退化无类型保命",
                typed.cause
            )
            val plain = ScreenCaptureStartPolicy.guard { startForeground(NOTIF_ID, notification) }
            if (plain.failure != null) {
                val reason = "前台启动失败：${plain.failure}"
                ScreenCaptureTrace.logFailure(ScreenCaptureTrace.START_FOREGROUND, reason, plain.cause)
                failSafe(ScreenCaptureGuard.Stage.START_FOREGROUND, reason, plain.cause)
                return
            }
        }
        foregroundStarted = true
        foregroundWatchdog?.let { it.removeCallbacksAndMessages(null) }
        AgentLogger.i("ScreenCapture", "已进入前台（mediaProjection 类型）")
        ScreenCaptureTrace.log(ScreenCaptureTrace.START_FOREGROUND, "成功")
    }

    /** 构建前台通知（channel + Notification）；异常由调用方的 guardValue 兜底转换。 */
    private fun buildForegroundNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val chan = NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentKit 屏幕捕获中")
            .setContentText("正在为 Agent 捕捉屏幕画面")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    /**
     * 建立 ImageReader / VirtualDisplay（token 已由 [obtainProjectionWithRetry] 在
     * 进前台之后取好，随参数传入）。
     *
     * **每一步都单独兜底**（第四轮加固）：任何一步失败都走 [failSafe]，
     * 且释放范围精确等于「已经建出来的那些资源」——见 [heldResources]。
     * 本方法**不抛异常**；调用方以 `virtualDisplay != null` 判定是否就绪。
     */
    private fun setupCapture(projection: MediaProjection) {
        // ① 尺寸防御：0/负数直接放弃（拿 0 去 newInstance 必崩），超大分辨率等比缩放
        val metrics = runCatching { resources.displayMetrics }.getOrNull()
        val rawW = metrics?.widthPixels ?: 0
        val rawH = metrics?.heightPixels ?: 0
        val rawDensity = metrics?.densityDpi ?: 0
        val size = ScreenCaptureGuard.sanitizeSize(rawW, rawH, rawDensity)
        if (!size.ok) {
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                size.reason ?: "屏幕尺寸非法",
                null
            )
            return
        }
        AgentLogger.i(
            "ScreenCapture",
            "屏幕尺寸 ${size.width}x${size.height}@${size.density}" +
                if (size.clamped) "（原始 ${rawW}x${rawH} 已等比缩放）" else ""
        )
        ScreenCaptureTrace.log(
            ScreenCaptureTrace.SIZE,
            "实际使用 ${size.width}x${size.height}@${size.density}" +
                if (size.clamped) "（原始 ${rawW}x${rawH} 已等比缩放）" else ""
        )

        // ② ImageReader：分辨率/格式不合规会抛 IllegalArgumentException
        val readerResult = ScreenCaptureGuard.guardValue {
            ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        }
        val reader = readerResult.value
        if (reader == null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.IMAGE_READER,
                "newInstance 失败：${readerResult.failure}",
                readerResult.cause
            )
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "ImageReader.newInstance 失败：${readerResult.failure}",
                readerResult.cause
            )
            return
        }
        imageReader = reader
        ScreenCaptureTrace.log(ScreenCaptureTrace.IMAGE_READER, "newInstance 成功")

        // ③ 帧可用回调：非必需能力，注册失败只记 WARN，不因此放弃捕获
        val handlerResult = ScreenCaptureGuard.guardValue { Handler(Looper.getMainLooper()) }
        val handler = handlerResult.value
        if (handler != null) {
            frameHandler = handler
            val listenerResult = ScreenCaptureGuard.guard {
                reader.setOnImageAvailableListener(frameListener, handler)
            }
            if (listenerResult.failure != null) {
                AgentLogger.w(
                    "ScreenCapture",
                    "setOnImageAvailableListener 失败（不影响轮询取帧）：${listenerResult.failure}"
                )
            }
        } else {
            AgentLogger.w("ScreenCapture", "Handler 创建失败，跳过帧回调注册：${handlerResult.failure}")
        }

        // ④ **投影回调注册（Android 14 第三规则，必须先于 createVirtualDisplay）**：
        //    targetSdk ≥ 34 时未注册 Callback 就调 createVirtualDisplay，系统直接抛
        //    IllegalStateException("Must register a callback before starting capture...")。
        //    （官方文档 Android 14 行为变更「注册回调」条目 + 真机 18:36:20 日志实证。）
        //    因此这里的失败**不再是不致命**——后面必炸，直接走 failSafe 收摊。
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                AgentLogger.w("ScreenCapture", "系统回调 onStop：用户已停止投屏")
                failSafe(
                    ScreenCaptureGuard.Stage.PROJECTION_STOPPED,
                    "用户已从控制中心停止投屏",
                    null
                )
            }
        }
        val callbackResult = ScreenCaptureGuard.guard {
            projection.registerCallback(callback, frameHandler)
            projectionCallback = callback
        }
        if (callbackResult.failure != null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.REGISTER_CALLBACK,
                "失败（Android 14 上为致命，后续 createVirtualDisplay 必抛 IllegalStateException）：" +
                    callbackResult.failure,
                callbackResult.cause
            )
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "registerCallback 失败（Android 14 要求先注册回调）：${callbackResult.failure}",
                callbackResult.cause
            )
            return
        }
        ScreenCaptureTrace.log(ScreenCaptureTrace.REGISTER_CALLBACK, "成功")

        // ⑤ VirtualDisplay：这一步是闪退高发区（token 失效 / Surface 无效 / 分辨率超限）
        val displayResult = ScreenCaptureGuard.guardValue {
            projection.createVirtualDisplay(
                "AgentKitCapture", size.width, size.height, size.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        }
        val display = displayResult.value
        if (display == null) {
            ScreenCaptureTrace.logFailure(
                ScreenCaptureTrace.CREATE_VIRTUAL_DISPLAY,
                "失败：${displayResult.failure}",
                displayResult.cause
            )
            failSafe(
                ScreenCaptureGuard.Stage.INIT_PROJECTION,
                "createVirtualDisplay 失败：${displayResult.failure}",
                displayResult.cause
            )
            return
        }
        virtualDisplay = display
        ScreenCaptureTrace.log(ScreenCaptureTrace.CREATE_VIRTUAL_DISPLAY, "成功")

        // ⑥ 全部就绪：此时才对外声明可用（避免外部拿到半成品实例）
        activeInstance = this
        ScreenshotBridge.setCapturing(true)
        ScreenshotBridge.clearError()
        AgentLogger.i("ScreenCapture", "捕获链路就绪：VirtualDisplay + ImageReader 已建立")
    }

    /** 当前持有的、需要释放的资源（供 [failSafe] 生成精确的安全停止计划）。 */
    private fun heldResources(): Set<ScreenCaptureGuard.Resource> {
        val held = linkedSetOf<ScreenCaptureGuard.Resource>()
        if (virtualDisplay != null) held += ScreenCaptureGuard.Resource.VIRTUAL_DISPLAY
        if (imageReader != null) held += ScreenCaptureGuard.Resource.IMAGE_READER
        if (mediaProjection != null) held += ScreenCaptureGuard.Resource.PROJECTION
        return held
    }

    /**
     * **唯一**的失败出口：按 [ScreenCaptureGuard.cleanupPlan] 逐步安全停止。
     *
     * 每一步单独 `runCatching`——任何一步失败都**不能**阻止后续步骤执行
     * （尤其 [ScreenCaptureGuard.CleanupStep.STOP_SELF] 与 [ScreenCaptureGuard.CleanupStep.REPORT_LAST_ERROR]）。
     *
     * @param stage 出错阶段（决定日志文案）。
     * @param reason 人类可读原因（进 [ScreenshotBridge.lastError]，UI 可见）。
     * @param cause 原始异常（堆栈进日志）；可为 null。
     */
    private fun failSafe(stage: ScreenCaptureGuard.Stage, reason: String, cause: Throwable?) {
        val plan = ScreenCaptureGuard.cleanupPlan(stage, heldResources())
        AgentLogger.i("ScreenCapture", "安全停止序列（${ScreenCaptureGuard.stageLabel(stage)}）：$plan")
        for (step in plan) {
            when (step) {
                ScreenCaptureGuard.CleanupStep.RELEASE_VIRTUAL_DISPLAY -> runCatching {
                    virtualDisplay?.release()
                }
                ScreenCaptureGuard.CleanupStep.CLOSE_IMAGE_READER -> runCatching {
                    imageReader?.close()
                }
                ScreenCaptureGuard.CleanupStep.STOP_PROJECTION -> {
                    runCatching {
                        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
                    }
                    // token 一次性：停止即作废（重开必须重新授权，Android 硬限制）
                    runCatching { mediaProjection?.stop() }
                }
                ScreenCaptureGuard.CleanupStep.CLEAR_STATE -> {
                    // 只清自己：避免把另一个正在运行的实例状态抹掉
                    if (activeInstance === this) activeInstance = null
                    // 经桥置位 → 触发 onCapturingChanged → UI 开关回落（修「开了关不了」的假状态）
                    ScreenshotBridge.setCapturing(false)
                    // token 一次性且本次已失败：作废授权缓存，下次必须重新弹系统授权框
                    ScreenshotBridge.invalidatePermission()
                }
                ScreenCaptureGuard.CleanupStep.REPORT_LAST_ERROR ->
                    runCatching { ScreenshotBridge.reportFailure(reason) }
                ScreenCaptureGuard.CleanupStep.LOG_ERROR ->
                    AgentLogger.e(
                        "ScreenCapture",
                        "捕获失败（${ScreenCaptureGuard.stageLabel(stage)}）：$reason",
                        cause
                    )
                ScreenCaptureGuard.CleanupStep.STOP_SELF -> runCatching { stopSelf() }
            }
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        projectionCallback = null
        frameHandler = null
    }

    /** 抓取当前屏幕帧为 Bitmap（无帧时短暂重试）。调用方应在 IO 线程调用。**绝不抛异常**。 */
    fun captureBitmap(): Bitmap? {
        val reader = imageReader
        if (reader == null) {
            AgentLogger.w("ScreenCapture", "captureBitmap：ImageReader 未就绪（捕获未启动或已停止）")
            return null
        }
        return try {
            var image: Image? = null
            for (i in 0 until 10) {
                image = reader.acquireLatestImage()
                if (image != null) break
                try {
                    Thread.sleep(60)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (image == null) {
                AgentLogger.w("ScreenCapture", "captureBitmap：等待 10 次仍无可用帧")
                null
            } else {
                try {
                    imageToBitmap(image)
                } finally {
                    runCatching { image.close() }
                }
            }
        } catch (t: Throwable) {
            // 取帧/转码任何异常：按计划安全停止并上报，**绝不上抛**（上抛 = 进程崩）
            failSafe(ScreenCaptureGuard.Stage.CAPTURE, "acquireLatestImage/转码失败：${ScreenCaptureGuard.describe(t)}", t)
            null
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // stride 非法（0/负数）会让下面的除法与 createBitmap 直接抛 ArithmeticException /
        // IllegalArgumentException——显式拦下，交给 captureBitmap 的兜底走安全停止。
        if (pixelStride <= 0 || rowStride <= 0 || image.width <= 0 || image.height <= 0) {
            throw IllegalStateException(
                "非法 Image 参数：w=${image.width} h=${image.height} " +
                    "pixelStride=$pixelStride rowStride=$rowStride"
            )
        }
        val rowPadding = rowStride - pixelStride * image.width
        val raw = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        raw.copyPixelsFromBuffer(buffer)
        val realW = image.width - rowPadding / pixelStride
        return if (realW < raw.width) {
            val cropped = Bitmap.createBitmap(raw, 0, 0, realW, raw.height)
            raw.recycle()
            cropped
        } else raw
    }

    override fun onDestroy() {
        // 看门狗收摊：服务无论为何销毁，都不留悬挂回调（否则 2s 后误触发 + Handler 泄漏）
        foregroundWatchdog?.let { it.removeCallbacksAndMessages(null) }
        foregroundWatchdog = null
        // 销毁路径同样逐步 runCatching：释放顺序同 cleanupPlan（下游先、投影后）
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        projectionCallback = null
        frameHandler = null
        if (activeInstance === this) activeInstance = null
        ScreenshotBridge.setCapturing(false)
        // token 一次性：服务销毁即作废，重开必须重新授权（避免拿旧 token 反复失败）
        ScreenshotBridge.invalidatePermission()
        super.onDestroy()
        AgentLogger.i("ScreenCapture", "服务已销毁（资源全部释放）")
    }

    companion object {
        /**
         * 当前活跃的服务实例（**仅在捕获链路完全就绪后才赋值**，销毁/失败即刻清空，
         * 防止外部拿到半成品或已死实例 → NPE 与泄漏）。
         */
        var activeInstance: ScreenCaptureService? = null
            private set

        private const val CHANNEL_ID = "agentkit_screen_capture"
        private const val NOTIF_ID = 1001

        /** Intent extras 键：授权 resultCode（由桥层 setPermission 缓存后经 startService 传入）。 */
        private const val EXTRA_RESULT_CODE = "resultCode"

        /** Intent extras 键：授权 data（含投影 token 的 Intent，一次性）。 */
        private const val EXTRA_DATA = "data"

        /**
         * getMediaProjection 的 SecurityException 重试上限：仅用于「startForeground 返回后
         * 个别 ROM 前台状态传播的极小异步窗口」。初始 + 2 次重试 = 最多 3 次，
         * 总阻塞 ≤ ~200ms（主线程，远低于 ANR 阈值）。
         */
        private const val MAX_TOKEN_RETRY = 2

        /** SecurityException 重试间隔（ms）。 */
        private const val TOKEN_RETRY_DELAY_MS = 100L

        /**
         * 前台启动看门狗阈值：`startForegroundService()` 之后系统只给约 5s
         * （Android 12+ 实际更短，超时抛 `ForegroundServiceDidNotStartInTimeException`
         * 且**不可捕获**，进程直接被杀）。留 2s 余量：到点未进前台就主动收摊并留日志，
         * 赶在系统动手之前把现场留下来。
         */
        private const val WATCHDOG_MS = 2000L
    }
}
