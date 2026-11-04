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

    // 2b. 摘监听必须**延后**到派发结束之后。
    //
    // 真机崩溃栈（本轮在验证机上抓到的）：
    //   java.lang.IllegalStateException: Cannot call removeOnDrawListener inside of onDraw
    //       at android.view.ViewTreeObserver.removeOnDrawListener(ViewTreeObserver.java:745)
    //       at com.example.agentkit.MainActivity$onCreate$2.onDraw(MainActivity.kt:61)
    // 也就是：ViewTreeObserver 正在派发 onDraw 时直接 remove 会抛异常，首帧一画就崩。
    // 这条断言锁的是「remove 之前先 post」——写错了它会红，但它是文本断言，
    // 真正的护栏是真机：装包冷启动，看有没有 FATAL。
    // 位置查找必须**剥掉注释**再找：这段代码的注释里就写着 "removeOnDrawListener"，
    // 直接在原文件里 indexOf 会先撞上注释里的那个，断言就成了文本游戏。
    val code = main.lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("//")) "" else line
    }
    val onDrawAt = code.indexOf("override fun onDraw()")
    val removeAt = code.indexOf("removeOnDrawListener", onDrawAt)
    val postAt = code.indexOf("post {", onDrawAt)
    check(
        "摘监听前先 post（不能在 onDraw 里直接 removeOnDrawListener）",
        onDrawAt > 0 && removeAt > onDrawAt && postAt > onDrawAt && postAt < removeAt,
        "onDraw@$onDrawAt post@$postAt remove@$removeAt"
    )

    // 3. WebView 本身仍在主线程创建（Android 硬要求，不能挪到后台线程）
    check("WebView 仍在主线程创建（main.post + WebView(activity)）",
        "main.post" in host && "WebView(activity)" in host)

    // 4. 构造耗时必须进日志：这段卡顿是可见的，日志里查不到就等于没观测
    check("WebView 构造耗时进日志", "elapsedRealtime" in host && "构造" in host)

    // ---------- 5. WebView 缓存目录必须先建（chromium 的 Crashpad 报错） ----------
    //
    // 真机日志原文（每次冷启动都有）：
    //   E chromium: [ERROR:filesystem_posix.cc(62)]
    //     mkdir /data/user/0/<pkg>/cache/WebView/Crashpad: No such file or directory (2)
    //
    // 根因：chromium 建 Crashpad 子目录时用**非递归** mkdir，父目录 `cache/WebView`
    // 不存在就直接失败（那个目录是 WebView provider 首次运行时才懒创建的）。
    // 影响：功能无害，但每次冷启动刷一条 E 级日志——启动日志里挂着 E 级错误，
    // 排障时会让人怀疑是不是出了真问题，白白分神。
    //
    // 修法：创建 WebView 之前先 mkdirs 建出 `cache/WebView`。
    //
    // 断言要点：建目录的调用**必须在 WebView(activity) 之前**——顺序反了等于没修
    // （chromium 在构造 WebView 时就报错了，事后建目录来不及）。
    val ensureCall = host.indexOf("ensureWebViewCacheDir(activity)")
    val webViewCtor = host.indexOf("WebView(activity)")
    check(
        "预建 WebView 缓存目录的调用排在 WebView 构造之前（顺序反了等于没修）",
        ensureCall > 0 && webViewCtor > 0 && ensureCall < webViewCtor,
        "ensure@$ensureCall webView@$webViewCtor"
    )
    check(
        "建的是 cache/WebView（chromium 要在它下面建 Crashpad 子目录）",
        "cacheDir" in host && "WebView" in host
    )
    check(
        "用 mkdirs 递归建（chromium 的 mkdir 不是递归的，父目录必须齐）",
        "mkdirs()" in host
    )
    // 建目录失败**不能**阻断 WebView 创建：它是「消除一条 E 级日志」，
    // 不是 WebView 的必要条件。失败就退回原行为。
    check(
        "建目录失败不阻断 WebView 创建（runCatching 兜底）",
        "runCatching" in host && "ensureWebViewCacheDir" in host
    )

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
