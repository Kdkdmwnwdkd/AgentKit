import com.example.agentkit.memory.MemoryAutoCapture
import com.example.agentkit.memory.MemoryStore
import java.io.File

/**
 * W-16 记忆检索 harness：BM25 排序 + 自动抽取 + 去重（纯 JVM，不加载 Android）。
 *
 * 断言口径：
 *  - BM25 必须让「查询词只出现在相关内容里」的条目排第一；
 *  - 高频词（两条都有的「我的」）不能把无关条目顶上来；
 *  - 完全不相关的查询返回空列表（记忆工具据此如实说「没找到」）；
 *  - 自动抽取只认显式信号、不认闲聊；近义说法视为重复。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        pass++
        println("  PASS  $name")
    } catch (t: Throwable) {
        fail++
        println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

fun main() {
    println("=== W-16 记忆检索（BM25 / 自动抽取 / 去重）===")

    // 临时目录建库（每次全新，避免污染）
    val dir = File(System.getProperty("java.io.tmpdir"), "w16-memory-qa-${System.nanoTime()}")
    val store = MemoryStore(dir)
    store.add("我对花生过敏，点外卖要避开", kind = "fact", salience = 9)
    store.add("我的手机是魅族 20", kind = "fact", salience = 6)
    store.add("我的常用邮箱是 work@example.com", kind = "fact", salience = 5)
    store.add("我喜欢深色模式", kind = "preference", salience = 7)

    check("BM25：查「花生」→ 过敏那条排第一") {
        val r = store.search("花生", topK = 3)
        assertTrue("应有结果", r.isNotEmpty())
        assertTrue("首条应含花生，实际：${r.first().content}", r.first().content.contains("花生"))
    }

    check("BM25：高频词「我的」不把无关条目顶上来") {
        val r = store.search("我的花生", topK = 4)
        assertTrue("应有结果", r.isNotEmpty())
        assertTrue("首条仍应是花生条目，实际：${r.first().content}", r.first().content.contains("花生"))
    }

    check("BM25：完全不相关 → 空列表（不硬凑）") {
        val r = store.search("量子力学 傅里叶变换", topK = 3)
        assertTrue("应无结果，实际 ${r.size} 条：${r.map { it.content }}", r.isEmpty())
    }

    check("BM25：标签命中加权（tag 含查询词时排前）") {
        store.add("每周三练腿", kind = "event", tags = listOf("健身"), salience = 5)
        val r = store.search("健身", topK = 2)
        assertTrue("应有结果", r.isNotEmpty())
        assertTrue("首条应为健身条目，实际：${r.first().content}", r.first().content.contains("练腿"))
    }

    check("自动抽取：记住… → fact") {
        val caps = MemoryAutoCapture.extract("记住：我对花生过敏")
        assertEquals("条数", 1, caps.size)
        assertEquals("类型", "fact", caps[0].kind)
        assertTrue("内容", caps[0].content.contains("花生"))
        assertTrue("重要度应 >= 8", caps[0].salience >= 8)
    }

    check("自动抽取：以后… → preference") {
        val caps = MemoryAutoCapture.extract("以后都用简体中文回答我")
        assertEquals("条数", 1, caps.size)
        assertEquals("类型", "preference", caps[0].kind)
    }

    check("自动抽取：闲聊不记") {
        val caps = MemoryAutoCapture.extract("今天天气不错，出去走走")
        assertTrue("不应有候选，实际：$caps", caps.isEmpty())
    }

    check("自动抽取：超长消息不处理（避免把整段文章记进去）") {
        val caps = MemoryAutoCapture.extract("记住：" + "很长".repeat(300))
        assertTrue("不应有候选", caps.isEmpty())
    }

    check("去重：换种说法视为同一条") {
        assertTrue(
            "「我不吃辣」应≈「我不太能吃辣」",
            MemoryAutoCapture.isDuplicate("我不吃辣", listOf("我不太能吃辣"))
        )
        assertTrue(
            "「我喜欢深色模式」与「我的邮箱」不应重复",
            !MemoryAutoCapture.isDuplicate("我喜欢深色模式", listOf("我的常用邮箱是 work@example.com"))
        )
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    dir.deleteRecursively()
    if (fail > 0) kotlin.system.exitProcess(1)
}
