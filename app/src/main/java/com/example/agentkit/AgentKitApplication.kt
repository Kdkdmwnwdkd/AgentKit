package com.example.agentkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.agentkit.automation.adb.AdbSession
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.CrashLogger

/**
 * 应用入口（Application 子类，Manifest 已通过 `android:name` 声明）。
 *
 * **存在意义只有一个**：在**尽可能早**的时机装上崩溃捕获 [CrashLogger]。
 * 真机第四轮的核心矛盾是——「开启屏幕捕捉 → 立刻闪退」，而进程被杀时内存里的日志
 * 来不及落盘，日志页一条异常都抓不到，只能靠猜。崩溃捕获必须早于任何业务初始化，
 * 否则连初始化阶段的崩溃都会丢证据。
 *
 * 这里**不**做别的初始化（日志目录、配置、Store 等仍由 ChatViewModel 按需初始化），
 * 以保证 Application.onCreate 越轻越好——它自己出错就没有第二道防线了。
 */
class AgentKitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 必须是方法体第一条业务语句：之后任何未捕获异常都会被同步落盘到 filesDir/crash/
        CrashLogger.install(this)
        runCatching {
            AgentLogger.i(
                "CrashLogger",
                "崩溃捕获已安装（filesDir/crash/，保留最近 ${CrashLogger.MAX_KEEP} 份）"
            )
        }
        // 崩溃可见性增强（真机第四轮 B）：启动时检测**未查看**的崩溃文件并留痕。
        // 用户看不到「最近崩溃」等于白做——这里至少让日志/横幅两条路都能发现它。
        // 读取失败一律静默降级（CrashLogger 内部全程 runCatching），**绝不影响启动**。
        runCatching {
            val unseen = CrashLogger.unseenCrashRecords(this)
            if (unseen.isNotEmpty()) {
                AgentLogger.w(
                    "CrashLogger",
                    "检测到 ${unseen.size} 次未查看崩溃：${unseen.first().fileName}" +
                        "（可在日志页「最近崩溃」查看）"
                )
            }
        }
        // W-37：Shizuku UserService 需要 Context 构造 UserServiceArgs，且 binder 死亡监听
        // 要尽早挂上（否则服务重启后代理悬空，第一次调用必挂）。内部全程 runCatching，
        // 失败不影响启动，只是提权通道回落 newProcess。
        runCatching { ShizukuShell.init(this) }
        // W-51：无线调试通道的凭据存储（adb 密钥对 + 端点）。只读 SharedPreferences，
        // 不发网络包也不生成密钥——首次配对时才生成 RSA（见 AdbCredentials.keyPair）。
        // 失败（存储不可用）不影响启动，只是该通道会如实返回「密钥不可用」。
        runCatching { AdbSession.init(this) }
        // 功能五（P1-10）：前台 Activity 计数——「回答完成提醒」只在 App 退到后台时
        // 发通知（用户正盯着聊天界面时提醒是噪音）。用 ActivityLifecycleCallbacks
        // 而不是 ProcessLifecycleOwner：不引 lifecycle-process 新依赖，计数语义也够用。
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foregroundActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        /** 已 started 未 stopped 的 Activity 数；0 = App 在后台。多 Activity 形态也成立。 */
        @Volatile
        private var foregroundActivities: Int = 0

        /** App 是否在前台（有可见 Activity）。 CompletionNotifier 据此决定发不发通知。 */
        fun isAppInForeground(): Boolean = foregroundActivities > 0
    }
}
