package com.example.agentkit

import android.app.Application
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
    }
}
