import java.io.File

/**
 * 启动顺序 harness：**首帧必须先于重活**。
 *
 * 真机事故（模拟器 x86_64 + 首次 WebView 冷启动，日志原文）：
 *   E ActivityManager: Completed ANR of com.example.agentkit in 30580ms
 *   W ActivityTaskManager: Force finishing activity com.example.agentkit/.MainActivity
 *   I ActivityManager: Killing 11969:com.example.agentkit (adj 0): user request after error
 *
 * 根因：后台 WebView 的 `attach` 被排在 `setContent` **之前**，而它内部用 `main.post`
 * 把「创建 WebView」排进主线程队列 —— 正好排在 Compose 首帧之前。WebView 构造必须在
 * 主线程，冷启动要拉起 chromium provider，可能 20~30 秒。主线程被占满，界面一帧都出不来，
 * 系统等不到 focused window，5 秒后判 ANR：用户点开 App 直接被弹回桌面，只会当成闪退。
 *
 * 这些断言锁住修复后的形状，防止有人把 attach 挪回 `setContent` 前面。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  PASS  $name")
    } else {
        fail++
        println("  FAIL  $name${if (detail.isNotBlank()) " → $detail" else ""}")
    }
}

fun main() {
    val main = File("app/src/main/java/com/example/agentkit/MainActivity.kt").readText()
    val host = File("app/src/main/java/com/example/agentkit/browser/BrowserHost.kt").readText()

    // 1. attach 必须排在 setContent 之后
    val setContentAt = main.indexOf("setContent {")
    val attachAt = main.indexOf("BrowserHost.attach(this@MainActivity)")
    check(
        "WebView 的 attach 排在 setContent 之后",
        setContentAt > 0 && attachAt > setContentAt,
        "setContent@$setContentAt attach@$attachAt"
    )

    // 2. attach 必须等首帧画完再排队，不能直接在 onCreate 里 post
    //    （post 会排在 Choreographer 的 doFrame 之前，等于仍挡在首帧前面）
    check("attach 挂在首帧回调上（addOnDrawListener）", "addOnDrawListener" in main)
    check("首帧回调用完即摘（否则 OnDrawListener 泄漏 Activity）", "removeOnDrawListener" in main)

    // 3. WebView 本身仍在主线程创建（Android 硬要求，不能挪到后台线程）
    check("WebView 仍在主线程创建（main.post + WebView(activity)）",
        "main.post" in host && "WebView(activity)" in host)

    // 4. 构造耗时必须进日志：这段卡顿是可见的，日志里查不到就等于没观测
    check("WebView 构造耗时进日志", "elapsedRealtime" in host && "构造" in host)

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
