import com.example.agentkit.memory.MemoryForceLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * W-68 记忆星图 harness：力导向布局的**可证伪**断言（纯 JVM，不加载 Android / Compose）。
 *
 * 为什么要它：星图这类东西，「看起来能动」很容易，但真正的失败模式都是肉眼看不出来的——
 * 节点飘出画布、两个点叠死、力平衡震荡永远不收敛（于是 60fps 一直烧电）、
 * 每次进页面形状都不一样（用户以为记忆被改了）。这些只能靠数值断言锁住。
 *
 * 断言口径：
 *  - 收敛：有限步内必须静止，且静止后 step() 不再产生位移；
 *  - 边界：任何时刻所有节点（含半径）都在画布内；
 *  - 不叠死：无连边的散点之间也必须保持最小间距；
 *  - 聚类：相连节点的平均距离必须显著小于无关节点；
 *  - 拖拽：钉住的节点精确跟手，邻居要被弹簧带着动；
 *  - 确定性：同 seed + 同步数 → 同一张图（同一份记忆不该每次换个形状）。
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

private fun assertNear(msg: String, expected: Float, actual: Float, tol: Float) {
    if (kotlin.math.abs(expected - actual) > tol) {
        throw AssertionError("$msg：期望 $expected ±$tol，实际 $actual")
    }
}

/** 建一张 n 节点的链式图（0-1-2-…），便于同时验证「有边要收拢」和「无边要散开」。 */
private fun chain(n: Int): IntArray {
    val e = IntArray(max(0, (n - 1) * 2))
    for (i in 0 until n - 1) {
        e[i * 2] = i
        e[i * 2 + 1] = i + 1
    }
    return e
}

/** 跑到静止，返回实际步数（超过上限仍不静止则返回 -1）。 */
private fun settle(layout: MemoryForceLayout, limit: Int = 900): Int {
    for (i in 1..limit) {
        layout.step()
        if (layout.isSettled) return i
    }
    return -1
}

private fun distance(l: MemoryForceLayout, a: Int, b: Int): Float {
    val dx = l.x[a] - l.x[b]
    val dy = l.y[a] - l.y[b]
    return sqrt(dx * dx + dy * dy)
}

fun main() {
    println("=== W-68 记忆星图布局（力导向 / 收敛 / 边界 / 拖拽）===")

    val w = 1080f
    val h = 1600f

    check("空图与单点：不崩、直接静止、step 无位移") {
        val empty = MemoryForceLayout(0, IntArray(0), FloatArray(0))
        empty.setViewport(w, h)
        assertTrue("空图应静止", empty.isSettled)
        assertTrue("空图 step 应为 0", empty.step() == 0f)

        val one = MemoryForceLayout(1, IntArray(0), floatArrayOf(12f))
        one.setViewport(w, h)
        assertTrue("单点应静止", one.isSettled)
        assertTrue("单点 step 应为 0", one.step() == 0f)
    }

    check("确定性：同 seed 同步数 → 坐标完全一致") {
        val a = MemoryForceLayout(24, chain(24), FloatArray(24) { 10f }, seed = 7L)
        val b = MemoryForceLayout(24, chain(24), FloatArray(24) { 10f }, seed = 7L)
        a.setViewport(w, h)
        b.setViewport(w, h)
        repeat(60) { a.step(); b.step() }
        var maxDiff = 0f
        for (i in 0 until 24) {
            maxDiff = max(maxDiff, kotlin.math.abs(a.x[i] - b.x[i]))
            maxDiff = max(maxDiff, kotlin.math.abs(a.y[i] - b.y[i]))
        }
        assertTrue("两次模拟最大坐标差应 < 0.01px，实际 $maxDiff", maxDiff < 0.01f)
    }

    check("不同 seed：初始形状确实不同（不是写死一个圆）") {
        val a = MemoryForceLayout(20, chain(20), FloatArray(20) { 10f }, seed = 1L)
        val b = MemoryForceLayout(20, chain(20), FloatArray(20) { 10f }, seed = 2L)
        a.setViewport(w, h)
        b.setViewport(w, h)
        var diff = 0f
        for (i in 0 until 20) diff += kotlin.math.abs(a.x[i] - b.x[i])
        assertTrue("不同种子的初始分布应不同，实际差异 $diff", diff > 1f)
    }

    check("收敛：30 节点链式图在 900 步内静止") {
        val l = MemoryForceLayout(30, chain(30), FloatArray(30) { 10f })
        l.setViewport(w, h)
        val steps = settle(l)
        assertTrue("900 步内应静止（实际 $steps）", steps in 1..900)
        println("        · 收敛步数 $steps（约 ${steps / 60.0} 秒 @60fps）")
    }

    check("静止后不再动：step() 位移恒为 0（不白烧帧）") {
        val l = MemoryForceLayout(30, chain(30), FloatArray(30) { 10f })
        l.setViewport(w, h)
        settle(l)
        val before = l.x.copyOf()
        var moved = 0f
        repeat(30) { l.step() }
        for (i in 0 until 30) moved = max(moved, kotlin.math.abs(l.x[i] - before[i]))
        assertTrue("静止后位移应恒为 0，实际最大 $moved", moved == 0f)
    }

    check("边界：全过程所有节点（含半径）都在画布内") {
        val l = MemoryForceLayout(40, chain(40), FloatArray(40) { 12f })
        l.setViewport(w, h)
        var out = 0
        repeat(600) {
            l.step()
            for (i in 0 until 40) {
                if (l.x[i] < 12f - 0.5f || l.x[i] > w - 12f + 0.5f ||
                    l.y[i] < 12f - 0.5f || l.y[i] > h - 12f + 0.5f
                ) out++
            }
        }
        assertTrue("越界采样次数应为 0，实际 $out", out == 0)
    }

    check("不叠死：40 个无连边节点，中心距不小于半径和的一半") {
        val l = MemoryForceLayout(40, IntArray(0), FloatArray(40) { 14f })
        l.setViewport(w, h)
        settle(l)
        var worst = Float.MAX_VALUE
        for (i in 0 until 40) for (j in i + 1 until 40) worst = min(worst, distance(l, i, j))
        assertTrue("最小中心距应 >= 14px（半径 14 的一半），实际 $worst", worst >= 14f)
        println("        · 最小中心距 ${"%.1f".format(worst)}px（半径 14px）")
    }

    check("聚类：相连节点平均距离明显小于无关节点") {
        // 三条互不相连的链（0-1-2-3 / 4-5-6-7 / 8-9-10-11），链内应各自抱团
        val edges = IntArray(9 * 2)
        var p = 0
        for (base in intArrayOf(0, 4, 8)) {
            for (k in 0 until 3) {
                edges[p++] = base + k
                edges[p++] = base + k + 1
            }
        }
        val l = MemoryForceLayout(12, edges, FloatArray(12) { 10f })
        l.setViewport(w, h)
        settle(l)

        fun isLinked(a: Int, b: Int): Boolean {
            var i = 0
            while (i < edges.size) {
                val x = edges[i]; val y = edges[i + 1]; i += 2
                if ((x == a && y == b) || (x == b && y == a)) return true
            }
            return false
        }

        var linkedSum = 0f; var linkedN = 0
        var otherSum = 0f; var otherN = 0
        for (i in 0 until 12) for (j in i + 1 until 12) {
            val d = distance(l, i, j)
            if (isLinked(i, j)) { linkedSum += d; linkedN++ } else { otherSum += d; otherN++ }
        }
        val linkedAvg = linkedSum / linkedN
        val otherAvg = otherSum / otherN
        assertTrue(
            "链内平均距离应小于无关节点（linked=$linkedAvg, other=$otherAvg）",
            linkedAvg < otherAvg * 0.9f
        )
        println("        · 链内 ${"%.0f".format(linkedAvg)}px vs 链间 ${"%.0f".format(otherAvg)}px")
    }

    check("拖拽：钉住的节点精确跟手，邻居被带着动") {
        val l = MemoryForceLayout(16, chain(16), FloatArray(16) { 10f })
        l.setViewport(w, h)
        settle(l)
        val targetX = 80f
        val targetY = 90f
        l.dragTo(0, targetX, targetY)
        assertNear("钉住的 x", targetX, l.x[0], 0.001f)
        assertNear("钉住的 y", targetY, l.y[0], 0.001f)

        val before = FloatArray(16) { l.y[it] }
        repeat(20) { l.step() }
        // 0 虽然被钉住，但下一帧模拟会把它推走（dragTo 只是设定位置，不是硬约束）——
        // 手指还在按着时每帧都会重新 dragTo，所以这里验证的是「邻居有反应」
        val neighborMoved = kotlin.math.abs(l.y[1] - before[1])
        assertTrue("邻居 1 应被拉动（实际移动 $neighborMoved px）", neighborMoved > 0.5f)
    }

    check("重置：reset() 把节点收回中心区域并重新加温") {
        val l = MemoryForceLayout(20, chain(20), FloatArray(20) { 10f })
        l.setViewport(w, h)
        settle(l)
        l.dragTo(0, w - 30f, h - 30f)
        l.reset()
        assertTrue("reset 后应重新加温（alpha > 0.5）", l.alpha > 0.5f)
        val cx = w / 2f
        val cy = h / 2f
        val far = max(
            kotlin.math.abs(l.x[0] - cx),
            kotlin.math.abs(l.y[0] - cy)
        )
        assertTrue("reset 后 0 号应回到中心附近（距中心 $far px）", far < min(w, h) * 0.2f)
    }

    check("视口变化：按比例映射，不把节点留在旧坐标的角落") {
        val l = MemoryForceLayout(20, chain(20), FloatArray(20) { 10f })
        l.setViewport(w, h)
        settle(l)
        l.setViewport(w / 2f, h / 2f)
        var out = 0
        for (i in 0 until 20) {
            if (l.x[i] > w / 2f || l.y[i] > h / 2f) out++
        }
        assertTrue("缩半后不应有节点越界，实际 $out 个", out == 0)
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
