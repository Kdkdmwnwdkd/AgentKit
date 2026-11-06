package com.example.agentkit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.agentkit.core.lifecycle.AppBackgroundHooks
import com.example.agentkit.startup.BootSequence

/**
 * 应用入口（Application 子类，Manifest 已通过 `android:name` 声明）。
 *
 * **这里只做两件事**：跑启动顺序 [BootSequence]，装前台 Activity 计数。
 * 十步初始化的顺序与失败语义都在 `startup/BootSequence.kt` 里，不再平铺在
 * `onCreate`——平铺时顺序只存在于语句次序，重排一次不会有任何东西报错。
 *
 * 历史教训（保留，因为它解释了这个文件为什么这么薄）：真机第四轮的核心矛盾是
 * 「开启屏幕捕捉 → 立刻闪退」，进程被杀时内存日志来不及落盘，日志页一条异常都
 * 抓不到。所以崩溃捕获必须是启动的第一步，且 `onCreate` 自身越轻越好——
 * 它自己出错就没有第二道防线了。
 */
class AgentKitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        BootSequence.run(this)
        // 功能五（P1-10）：前台 Activity 计数——「回答完成提醒」只在 App 退到后台时
        // 发通知（用户正盯着聊天界面时提醒是噪音）。用 ActivityLifecycleCallbacks
        // 而不是 ProcessLifecycleOwner：不引 lifecycle-process 新依赖，计数语义也够用。
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foregroundActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                val now = (foregroundActivities - 1).coerceAtLeast(0)
                foregroundActivities = now
                // R-19：前台计数归零 = 进程进后台，这是**最可能被系统回收**的时刻。
                // 会话落盘是 400ms 去抖的，不在这里补一次，窗口内的最后一次变更就随
                // 进程一起消失（丢的是用户刚打的字，不是流式增量，代价不同）。
                // 旋转屏幕/Activity 重建也会走这里，多触发一次无害：flush 自己有脏检查。
                if (now == 0) runCatching { AppBackgroundHooks.fireAll("app_background") }
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
