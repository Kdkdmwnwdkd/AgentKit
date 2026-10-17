package com.example.agentkit.browser

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.agentkit.core.AgentLogger
import java.lang.ref.WeakReference

/**
 * 后台 WebView 宿主（W-15）。
 *
 * 为什么需要一个「宿主」：
 *  - Android 的 WebView **必须在主线程创建**，且首次创建要绑定一个 Activity 上下文；
 *  - 我们不想给用户弹一个浏览器界面（Agent 应静默操作网页），所以把一个不可见的
 *    WebView 挂在 MainActivity 上，页面加载与 JS 执行都在它里面完成。
 *
 * 生命周期：MainActivity.onCreate → [attach]；onDestroy → [release]。
 * 用 [WeakReference] 持有 Activity 派生的 WebView，避免配置变更（旋转）时泄漏。
 * App 不在前台时 WebView 仍可执行 JS（进程存活即可），但若系统回收了 Activity，
 * 工具会明确报「浏览器不可用，请回到 App」。
 */
object BrowserHost {

    private const val TAG = "BrowserHost"

    private val main = Handler(Looper.getMainLooper())
    private var webRef: WeakReference<WebView>? = null

    /**
     * WebView 没有 `isDestroyed`（那是 Fragment/Activity 的 API），
     * 自己记一个「是否已销毁」标志，避免 destroyed 之后继续 loadUrl 崩溃。
     */
    @Volatile
    private var destroyed = true

    /** 页面加载完成的等待句柄（由 [WebViewBrowser] 设置/消费）。 */
    internal var onPageFinished: (() -> Unit)? = null

    /** 在主线程创建（或复用）WebView。重复调用是幂等的。 */
    fun attach(activity: Activity) {
        main.post {
            val existing = webRef?.get()
            if (existing != null && !destroyed) return@post
            runCatching {
                val wv = WebView(activity)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // 去掉 "wv" 标记：部分站点会给 WebView UA 返回阉割版页面，影响 snapshot 质量
                    userAgentString = userAgentString.replace("; wv", "")
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        AgentLogger.d(TAG, "页面加载完成：${url?.take(120)}")
                        onPageFinished?.invoke()
                    }
                }
                webRef = WeakReference(wv)
                destroyed = false
                AgentLogger.i(TAG, "后台 WebView 已就绪")
            }.onFailure {
                AgentLogger.w(TAG, "创建 WebView 失败：${it.message}")
            }
        }
    }

    /** 取当前 WebView（必须在主线程访问）。未就绪返回 null。 */
    fun webView(): WebView? = if (destroyed) null else webRef?.get()

    /** 释放（Activity 销毁时调用）。 */
    fun release() {
        main.post {
            runCatching {
                webRef?.get()?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            }
            webRef = null
            destroyed = true
            onPageFinished = null
            AgentLogger.i(TAG, "后台 WebView 已释放")
        }
    }

    /** 供工具层判断可用性（无需主线程）。 */
    fun isReady(): Boolean = !destroyed && webRef?.get() != null

    /** 给需要 Context 的调用方（如错误提示）。 */
    fun contextOrNull(): Context? = webRef?.get()?.context
}
