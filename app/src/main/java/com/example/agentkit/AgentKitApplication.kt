package com.example.agentkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.agentkit.automation.adb.AdbSession
import com.example.agentkit.automation.shizuku.ShizukuShell
import com.example.agentkit.core.AgentLogger
import com.example.agentkit.core.CrashLogger
import com.example.agentkit.core.llm.local.AndroidDeviceProbe
import com.example.agentkit.core.llm.local.LlamaJni
import com.example.agentkit.core.llm.local.NativeCrashMemory
import java.io.File

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
        // 原生崩溃记忆（W-84）：把上一次「进程被信号打死」的现场读出来。
        //
        // 与上面 CrashLogger 的分工：CrashLogger 只管 Java 异常；GPU / Vulkan / ggml
        // 崩起来走的是信号，进程当场消失，Java 那层一句日志都留不下——真机上表现就是
        // 「发消息 → 转一会儿 → 直接回桌面，日志里什么都没有」。
        // 这里做两件事：① 判断上次是不是开 GPU 崩的；② 是的话**自动关掉 GPU**，
        // 保证这次至少能用起来，并把原因写进设置页。
        runCatching {
            val crashDir = File(filesDir, CrashLogger.DIR_NAME)
            crashDir.mkdirs()   // 信号处理器要往里写文件，目录必须先存在
            LlamaJni.crashDir = crashDir.absolutePath
            NativeCrashMemory.handleStartup(this)
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
        // W-82：设备画像（芯片 / GPU / 内存 → 这台机器该走 NPU / GPU / CPU 哪条路）。
        //
        // 为什么放后台线程：画像要起一个 1x1 的 EGL 上下文问 GL_RENDERER（几毫秒到几十毫秒），
        // 而 onCreate 是启动最敏感的路径——这里等它等于给冷启动加一截。
        // 为什么失败可以接受：画像只用于「把判断写进日志 + 后续在设置页展示」，
        // 探测失败就退到 CPU 路线，不影响任何功能。内部全程 runCatching。
        //
        // 这一行让真机日志直接带上设备判定，省掉「你这台是什么芯片」的往返：
        //   DeviceProbe: 设备画像：高通骁龙 · Adreno 660 · 中高端（8GB 级） → 首选 GPU，建议 1.5B~3B
        Thread {
            runCatching { AndroidDeviceProbe.profile(this) }
        }.apply { isDaemon = true; name = "device-probe" }.start()
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
