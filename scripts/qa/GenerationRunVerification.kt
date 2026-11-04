#!/usr/bin/env kotlin
/**
 * 生成运行态 harness：验证 [com.example.agentkit.ui.GenerationRunController]。
 *
 * ## 为什么这段逻辑终于能被单独验了
 *
 * 改动前它是 `ChatViewModel` 的 7 个私有字段 + 一个 `private inner class TurnRunner`：
 * 想验「停止后运行态有没有复位」得先把整个 ViewModel 建起来（Application、Compose、
 * 一堆 Android 单例）。于是它从来没被单独验过——真机上「点了停止，发送键永久灰着」
 * 就是这么漏出去的：`finish()` 的某条分支没走到，而 UI 只看 `isRunning`。
 *
 * 现在状态有唯一所有者，可以在这里把四条路径逐一跑一遍：
 * 正常完成 / 异常退出 / 用户取消 / 切换会话时草稿的保留与恢复。
 *
 * 运行：bash scripts/qa/run-generation-run-verification.sh
 */

import com.example.agentkit.ui.GenerationRunController
import com.example.agentkit.ui.GenerationRunHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private var pass = 0
private val fails = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  ✅ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        fails += name
        println("  ❌ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "实际=$actual 期望=$expected")

/** 记录钩子调用顺序——顺序本身就是契约（见 GenerationRunHooks 的注释）。 */
private class Recorder : GenerationRunHooks {
    val events = mutableListOf<String>()
    override fun onBegin(owner: String, placeholderDraft: Boolean) {
        events += "begin:$owner:placeholder=$placeholderDraft"
    }

    override fun onDraftTaken(chars: Int) {
        events += "draft:$chars"
    }

    override fun onReset() {
        events += "reset"
    }

    override fun onFinish(rebuildMessages: Boolean) {
        events += "finish:rebuild=$rebuildMessages"
    }
}

private class Harness(
    /** 作用域的异常出口：body 抛出的异常会交到这里，而不是静默消失。 */
    private val onError: (Throwable) -> Unit = {},
) {
    var active: String? = "A"
    val recorder = Recorder()
    val finished = mutableListOf<Boolean>()
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> onError(e) }
    )
    val controller = GenerationRunController(
        scope = scope,
        activeId = { active },
        onFinished = { finished += it },
        hooks = recorder,
    )
}

fun main() {
    println("== 生成运行态（P4-2）==")

    // —— 1. 开场 ——
    println("\n[1] 开场：owner / 生成中 / 运行态 / 草稿占位")
    runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.controller.start("A", placeholderDraft = false, rebuildMessages = true) { gate.await() }
        eq("owner 置位", h.controller.owner, "A")
        eq("生成中标识", h.controller.generating.value, "A")
        eq("运行态打开", h.controller.running.value, true)
        eq("send 不带空草稿占位", h.controller.draft.value, null)
        eq("钩子顺序（开场）", h.recorder.events.last(), "begin:A:placeholder=false")
        gate.complete(Unit)
        h.controller.job?.join()

        val h2 = Harness()
        val gate2 = CompletableDeferred<Unit>()
        h2.controller.start("A", placeholderDraft = true, rebuildMessages = false) { gate2.await() }
        eq("regenerate 立即出空草稿", h2.controller.draft.value, "")
        gate2.complete(Unit)
        h2.controller.job?.join()
    }

    // —— 2. 草稿与投影 ——
    println("\n[2] 草稿缓冲：权威态 + 条件投影")
    runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.controller.start("A", placeholderDraft = false, rebuildMessages = true) {
            h.controller.appendText("你好")
            h.controller.appendText("，世界")
            h.controller.appendReasoning("想一想")
            gate.await()
        }
        eq("正文累积", h.controller.draft.value, "你好，世界")
        eq("推理累积", h.controller.reasoning.value, "想一想")

        // 用户切到别的会话：显示态清空，缓冲继续攒（切回能续看）
        h.active = "B"
        h.controller.syncDisplay()
        eq("切走后不显示草稿", h.controller.draft.value, null)
        h.controller.appendText("（后台继续）")
        eq("切走后仍不显示", h.controller.draft.value, null)
        h.active = "A"
        h.controller.syncDisplay()
        eq("切回后原样恢复", h.controller.draft.value, "你好，世界（后台继续）")

        gate.complete(Unit)
        h.controller.job?.join()
    }

    // —— 3. 取草稿（停止前抢救） ——
    println("\n[3] takeDraft：抢救已生成部分并清空")
    runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.controller.start("A", placeholderDraft = false, rebuildMessages = true) {
            h.controller.appendText("半截回答")
            gate.await()
        }
        val partial = h.controller.takeDraft()
        eq("取到内容", partial, "半截回答")
        eq("取后清空显示", h.controller.draft.value, null)
        eq("取后清空缓冲", h.controller.takeDraft(), null)
        check("钩子记录字符数", h.recorder.events.contains("draft:4") && h.recorder.events.contains("draft:0"),
            h.recorder.events.filter { it.startsWith("draft") }.toString())
        gate.complete(Unit)
        h.controller.job?.join()
    }

    // —— 4. 正常完成：复位彻底 ——
    println("\n[4] 正常完成：状态全复位 + 钩子顺序")
    runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.controller.start("A", placeholderDraft = true, rebuildMessages = true) { gate.await() }
        gate.complete(Unit)
        h.controller.job?.join()
        eq("owner 清空", h.controller.owner, null)
        eq("生成中清空", h.controller.generating.value, null)
        eq("草稿清空", h.controller.draft.value, null)
        eq("运行态关闭", h.controller.running.value, false)
        eq("宿主收尾收到 rebuild=true", h.finished, listOf(true))
        eq("钩子顺序", h.recorder.events.takeLast(2), listOf("reset", "finish:rebuild=true"))
        eq("复位在宿主收尾之前", h.recorder.events.indexOf("reset") < h.recorder.events.indexOf("finish:rebuild=true"), true)
    }

    // —— 5. 异常退出：finally 必须走到 ——
    println("\n[5] 异常退出：finally 仍然收尾（否则 UI 永久停在「运行中」）")
    runBlocking {
        var caught: Throwable? = null
        val h = Harness { caught = it }
        h.controller.start("A", placeholderDraft = false, rebuildMessages = true) {
            throw IllegalStateException("模型炸了")
        }
        // start 内部 launch：异常不回到调用方，而是交给作用域处理器（线上表现为崩溃上报）
        h.controller.job?.join()
        check("异常交给作用域处理器（不静默吞）", caught?.message == "模型炸了", caught?.message ?: "null")
        eq("运行态已关闭", h.controller.running.value, false)
        eq("owner 已清", h.controller.owner, null)
        eq("宿主收尾只调一次", h.finished.size, 1)
    }

    // —— 6. 用户取消 ——
    println("\n[6] 用户取消：cancel 能打断，收尾照旧")
    runBlocking {
        val h = Harness()
        var bodyCancelled = false
        h.controller.start("A", placeholderDraft = false, rebuildMessages = true) {
            try {
                delay(60_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                bodyCancelled = true
                throw e
            }
        }
        val job: Job? = h.controller.job
        check("取消返回 true", h.controller.cancel())
        job?.join()
        eq("body 收到取消", bodyCancelled, true)
        eq("运行态已关闭", h.controller.running.value, false)
        eq("取消后再取消是 no-op", h.controller.cancel(), false)
        eq("宿主收尾仍被调用", h.finished.size, 1)
    }

    // —— 7. 落库目标 ——
    println("\n[7] 落库目标：在途按 owner，空闲按当前会话")
    runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.active = "B"
        h.controller.start("A", placeholderDraft = false, rebuildMessages = true) { gate.await() }
        eq("在途写回原会话", h.controller.targetConversation(), "A")
        eq("在途时 B 不是 owner", h.controller.isOwnerActive(), false)
        gate.complete(Unit)
        h.controller.job?.join()
        eq("空闲时落当前会话", h.controller.targetConversation(), "B")
    }

    println("\n==== 结果：PASS=$pass FAIL=${fails.size} ====")
    if (fails.isNotEmpty()) {
        println("失败项：")
        fails.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
