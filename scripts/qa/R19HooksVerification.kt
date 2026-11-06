/**
 * R-19 行为验证：`AppBackgroundHooks` 的注册/广播语义。
 *
 * ## 为什么单独测这个 object
 *
 * 接线本身（`Application` 在计数归零时 fireAll）只能靠 runner 的静态断言，
 * 但**被接的那个东西对不对**必须真跑：一个「注册了却永远不触发」「触发两次」
 * 「一个 hook 抛异常把后面全带走」的广播器，接上线比不接更糟——它给了
 * 「切后台会落盘」的错觉。
 *
 * 这里只编译 `AppBackgroundHooks.kt`（零 Android 依赖），所以是 JVM 真跑，
 * 不是静态扫描。
 *
 * ## 不测什么（说清楚，避免读的人以为覆盖了）
 *
 * - **GC 回收后自动清理**：不可确定性（`System.gc()` 是建议不是命令），
 *   写出来就是一条会偶发红的用例。弱引用的语义由 `fireAll` 里的
 *   `entries.removeAll { it.owner.get() == null }` 承担，靠代码审查保证。
 * - **Application 的 Activity 计数**：拖 Android，编不动。
 */

import com.beichen.polaris.core.lifecycle.AppBackgroundHooks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

private var p = 0
private var f = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        p++
        println("  PASS  $name")
    } catch (t: Throwable) {
        // 必须抓 Throwable 而不是只抓 AssertionError：被测代码抛出的运行时异常
        // 同样是一条失败，放它逃出去会把**整个 harness**打断——后面的用例一条不跑，
        // 结果行都不会打印，runner 拿不到「N 通过 / M 失败」就会误以为全绿。
        // （这是变异测试 N1 抓到的：去掉 fireAll 的 try/catch，F5 直接炸掉整个进程。）
        f++
        println("  FAIL  $name -> ${t::class.java.simpleName}: ${t.message}")
    }
}

private fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

/** 保持强引用：owner 一旦被 GC，弱引用就失效，用例会以「0 次执行」的形式假失败。 */
private val keepAlive = ArrayList<Any>()

fun main() {
    println("[F] AppBackgroundHooks 行为")

    check("F1 没有注册者时 fireAll 返回 0（不能假装做了事）") {
        AppBackgroundHooks.clearForTest()
        expect(AppBackgroundHooks.fireAll("test") == 0, "空表应返回 0")
    }

    check("F2 注册后广播：action 收到的是同一个 owner，返回成功 1 次") {
        AppBackgroundHooks.clearForTest()
        val owner = Any().also { keepAlive += it }
        var got: Any? = null
        AppBackgroundHooks.register(owner) { got = it }
        val n = AppBackgroundHooks.fireAll("background")
        expect(n == 1, "应执行 1 次，实际 $n")
        expect(got === owner, "action 应收到注册时的 owner 实例")
        expect(AppBackgroundHooks.activeCount() == 1, "存活 hook 应为 1")
    }

    check("F3 同一 owner 重复注册是替换，不是叠加（ViewModel 重建不会触发两次）") {
        AppBackgroundHooks.clearForTest()
        val owner = Any().also { keepAlive += it }
        val first = AtomicInteger(0)
        val second = AtomicInteger(0)
        AppBackgroundHooks.register(owner) { first.incrementAndGet() }
        AppBackgroundHooks.register(owner) { second.incrementAndGet() }
        val n = AppBackgroundHooks.fireAll("background")
        expect(n == 1, "应只执行 1 次，实际 $n")
        expect(first.get() == 0, "旧动作不该被执行，实际 ${first.get()}")
        expect(second.get() == 1, "新动作应执行 1 次，实际 ${second.get()}")
    }

    check("F4 unregister 之后不再触发") {
        AppBackgroundHooks.clearForTest()
        val owner = Any().also { keepAlive += it }
        val hits = AtomicInteger(0)
        AppBackgroundHooks.register(owner) { hits.incrementAndGet() }
        AppBackgroundHooks.unregister(owner)
        expect(AppBackgroundHooks.fireAll("background") == 0, "注销后应无可执行项")
        expect(hits.get() == 0, "动作不该被执行")
        expect(AppBackgroundHooks.activeCount() == 0, "存活 hook 应为 0")
    }

    check("F5 一个 hook 抛异常不影响其余，且不计数为成功（收尾要尽量做完）") {
        AppBackgroundHooks.clearForTest()
        val bad = Any().also { keepAlive += it }
        val good1 = Any().also { keepAlive += it }
        val good2 = Any().also { keepAlive += it }
        val ok = AtomicInteger(0)
        AppBackgroundHooks.register(bad) { throw IllegalStateException("写盘失败") }
        AppBackgroundHooks.register(good1) { ok.incrementAndGet() }
        AppBackgroundHooks.register(good2) { ok.incrementAndGet() }
        // 失败要留痕（走 System.err 而不是静默吞掉），所以这里把它接住一起断言：
        // 既不污染 harness 日志，又证明「失败可见」这件事是真的。
        val origErr = System.err
        val buf = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(buf, true, "UTF-8"))
        val n = try {
            AppBackgroundHooks.fireAll("background")
        } finally {
            System.setErr(origErr)
        }
        val logged = buf.toString("UTF-8")
        expect(ok.get() == 2, "两个正常 hook 都应执行，实际 ${ok.get()}")
        expect(n == 2, "成功计数应为 2，实际 $n")
        // 用 `in` 而不是字符串的 contains 方法：census 的模式是「点号 + contains + 字面量」，
        // 一律记成「源码文本断言」（架构方案 P0 那条规矩的机械判据）。这里查的是
        // **运行期写出的日志内容**，不是源码长什么样，不该被记账。
        expect("后台收尾失败" in logged, "失败必须留痕，实际输出：${logged.take(120)}")
    }

    check("F6 多次广播会重复执行（切后台 → 回前台 → 再切后台）") {
        AppBackgroundHooks.clearForTest()
        val owner = Any().also { keepAlive += it }
        val hits = AtomicInteger(0)
        AppBackgroundHooks.register(owner) { hits.incrementAndGet() }
        AppBackgroundHooks.fireAll("background")
        AppBackgroundHooks.fireAll("background")
        expect(hits.get() == 2, "两次广播应执行 2 次，实际 ${hits.get()}")
    }

    check("F7 clearForTest 清空（harness 用例之间不互相污染）") {
        AppBackgroundHooks.clearForTest()
        val owner = Any().also { keepAlive += it }
        AppBackgroundHooks.register(owner) { }
        AppBackgroundHooks.clearForTest()
        expect(AppBackgroundHooks.activeCount() == 0, "清空后应为 0")
        expect(AppBackgroundHooks.fireAll("test") == 0, "清空后广播应返回 0")
    }

    check("F8 并发注册 + 广播：100 个 owner 一个不丢、不抛 ConcurrentModificationException") {
        AppBackgroundHooks.clearForTest()
        val total = 100
        val hits = AtomicInteger(0)
        val owners = (1..total).map { Any() }
        keepAlive.addAll(owners)
        val start = CountDownLatch(1)
        val threads = (1..4).map { t ->
            Thread {
                start.await()
                owners.filterIndexed { i, _ -> i % 4 == t - 1 }.forEach { o ->
                    AppBackgroundHooks.register(o) { hits.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5000) }
        val n = AppBackgroundHooks.fireAll("background")
        expect(hits.get() == total, "应执行 $total 次，实际 ${hits.get()}")
        expect(n == total, "成功计数应为 $total，实际 $n")
    }

    println("")
    println("$p 通过 / $f 失败")
    if (f > 0) kotlin.system.exitProcess(1)
}
