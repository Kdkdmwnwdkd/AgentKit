package com.example.agentkit.memory

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 记忆星图的力导向布局引擎。
 *
 * 为什么单独抽一个文件：它是纯数值计算（FloatArray 进、FloatArray 出），不碰 Android / Compose，
 * 于是 `scripts/qa/run-starmap-layout-verification.sh` 能用 kotlinc 直接把它拉进 JVM 跑断言——
 * 「力导向图会不会飘出去 / 会不会抖 / 会不会永远不收敛」这类问题，靠肉眼看截图是看不出来的。
 *
 * 模型是三股力的叠加（Fruchterman–Reingold 的思路，参数按手机屏幕的尺度调过）：
 *  1. 斥力：任意两点互相推开，强度 k²/d —— 保证节点不糊成一团；
 *  2. 弹簧：有连边的两点按理想长度 L 收放，F = (d − L) —— 让「共享 tag」的簇靠在一起；
 *  3. 向心：极轻地拉回画布中心，防止孤立子图被斥力顶到边界外。
 *
 * 收敛靠「温度」alpha：每步衰减，位移按温度缩放，低到阈值就停（[isSettled]），
 * 由调用方切断帧循环——图谱静止时不该继续烧 60fps。用户拖动 / 点选 / 数据变化时
 * 由 [reheat] 重新加温，再跑一小段。
 *
 * 位置用**世界坐标 px**表示（不含缩放平移），视口变化时按比例映射，见 [setViewport]。
 */
class MemoryForceLayout(
    /** 节点数。0 或 1 时不做模拟（单点没有可解的力平衡）。 */
    val count: Int,
    /** 连边，扁平存放的索引对：[a0, b0, a1, b1, ...]。 */
    edges: IntArray,
    /** 每个节点的绘制半径（px），用于碰撞与命中测试。 */
    val radii: FloatArray,
    /** 初始散布的确定性种子：同一份记忆每次进场从同一个姿态展开，不会「每次刷新都换一张脸」。 */
    private val seed: Long = 0x5EED_1701L
) {

    init {
        require(edges.size % 2 == 0) { "edges 必须是成对的索引：实际 ${edges.size} 个" }
        require(radii.size == count) { "radii 长度 ${radii.size} 与 count $count 不一致" }
        edges.forEach { require(it in 0 until count) { "边索引越界：$it（count=$count）" } }
    }

    /** 节点中心的世界坐标。 */
    val x = FloatArray(count)
    val y = FloatArray(count)
    /** 每步的合力累加器：成员字段复用，避免每帧给 50 个节点分配两个新数组。 */
    private val dispX = FloatArray(count)
    private val dispY = FloatArray(count)
    private val edges: IntArray = edges.copyOf()

    private var viewW = 0f
    private var viewH = 0f

    /** 温度：1 = 刚炸开，0 = 完全静止。 */
    var alpha = 1f
        private set

    /** 连续「几乎没动」的帧数，用于提前收敛（比死等温度衰减快得多）。 */
    private var stillFrames = 0

    /** 本轮收敛是否已经做过收拢（reheat 后重置）。 */
    private var fitted = false

    /**
     * 是否已经静止。
     *
     * count <= 1 直接视为静止：单节点的力平衡是它自己，没有可模拟的东西，
     * 让它跑帧循环只会白烧电。
     */
    val isSettled: Boolean
        get() = count <= 1 || alpha <= SETTLE_ALPHA

    /**
     * 视口尺寸变化（首帧 / 旋转 / 分屏）。
     *
     * 首次调用做种子散布；之后按比例映射既有坐标，并轻微加温——
     * 不然旋转屏幕后节点会挤在角落里不动。
     */
    fun setViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (viewW <= 0f || viewH <= 0f) {
            viewW = width
            viewH = height
            seedPositions()
            return
        }
        val sx = width / viewW
        val sy = height / viewH
        for (i in 0 until count) {
            x[i] *= sx
            y[i] *= sy
        }
        viewW = width
        viewH = height
        reheat(0.5f)
    }

    /** 重新加温：用户拖动 / 点选 / 数据变化后叫醒模拟。 */
    fun reheat(temperature: Float = 0.75f) {
        if (count <= 1) return
        alpha = max(alpha, temperature.coerceIn(0f, 1f))
        stillFrames = 0
        fitted = false
    }

    /**
     * 把第 [index] 个节点钉在 (px, py)（拖拽跟随手指）。
     *
     * 只改位置、清速度，并保持一个中等温度：邻居会被弹簧拽着跟过来，
     * 「拖动一个点，整张网跟着动」——这是图谱看起来「活」的关键，而不是把点单独搬走。
     */
    fun dragTo(index: Int, px: Float, py: Float) {
        if (index !in 0 until count) return
        x[index] = px
        y[index] = py
        alpha = max(alpha, DRAG_TEMPERATURE)
        stillFrames = 0
        fitted = false
    }

    /** 命中测试：返回离 (px, py) 最近且在容差内的节点，没有则 -1。坐标为世界坐标。 */
    fun nearest(px: Float, py: Float, tolerance: Float = 0f): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until count) {
            val dx = x[i] - px
            val dy = y[i] - py
            val d = sqrt(dx * dx + dy * dy)
            val reach = radii[i] + tolerance
            if (d <= reach && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    /** 把全部节点摆回中心附近并重新展开（「归位」按钮）。 */
    fun reset() {
        seedPositions()
        reheat(1f)
    }

    /**
     * 跑一步模拟，返回本步最大位移（px）。
     *
     * 返回值是给调用方做自适应收敛判断用的：连续若干步位移都小于 0.35px，
     * 说明布局已经稳了，可以提前把温度归零。
     */
    fun step(): Float {
        if (count <= 1 || isSettled) return 0f
        val w = viewW
        val h = viewH
        if (w <= 0f || h <= 0f) return 0f

        // 理想间距 k：节点越多、画布越大，间距越大；上下限防止极端屏幕把参数带飞。
        // 乘 0.72 是有意的：按 sqrt(area/n) 算出来的 k 会让整张图精确铺满画布，
        // 在手机上就是「贴着四边」——看起来像被裁过。收一点，留出呼吸的空间。
        val k = (sqrt(w * h / max(count, 1)) * IDEAL_SPACING_RATIO)
            .coerceIn(MIN_IDEAL, MAX_IDEAL)
        val idealEdge = k * EDGE_LENGTH_RATIO
        val cx = w / 2f
        val cy = h / 2f
        // 温度 = 本步允许的最大位移（Fruchterman–Reingold 的做法）。
        // 用「位移上限」而不是「力 × 系数」的好处：力在近距离会趋于无穷，
        // 乘以系数照样会炸；封顶则永远稳，而且温度下降天然对应「慢慢停手」。
        val temperature = max(MIN_TEMPERATURE, k * TEMPERATURE_RATIO * alpha)

        for (i in 0 until count) {
            dispX[i] = 0f
            dispY[i] = 0f
        }

        // —— 斥力：任意两点互推，k²/d ——
        // 同时把碰撞并进来：两球靠得比「半径和 + 间隙」还近时额外再加一份推力，
        // 不然节点大小不一时会互相压住（视觉上是「糊」，不是「挤」）。
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                var dx = x[i] - x[j]
                var dy = y[i] - y[j]
                var d2 = dx * dx + dy * dy
                if (d2 < 0.01f) {
                    // 完全重合：按索引给确定性的小扰动，避免除零与「叠死再也不动」
                    val jitter = 0.35f + (hash(i * 131 + j) % 100) / 220f
                    dx = jitter
                    dy = -jitter
                    d2 = dx * dx + dy * dy
                }
                val d = sqrt(d2)
                var force = k * k / d
                val minGap = radii[i] + radii[j] + k * COLLISION_PADDING
                if (d < minGap) force += (minGap - d) * COLLISION_STRENGTH
                val ux = dx / d * force
                val uy = dy / d * force
                dispX[i] += ux
                dispY[i] += uy
                dispX[j] -= ux
                dispY[j] -= uy
            }
        }

        // —— 弹簧：有边的两点按 d²/L 收拢（FR 原式）——
        // d 远大于 L 时吸引远强于斥力，长边会迅速收回来；d 接近 L 时与斥力打平。
        // 这比「(d − L) × 常数」收敛快得多，也不会在远处拉不动。
        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            e += 2
            val dx = x[a] - x[b]
            val dy = y[a] - y[b]
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
            val force = d * d / idealEdge * SPRING_SCALE
            val ux = dx / d * force
            val uy = dy / d * force
            dispX[a] -= ux
            dispY[a] -= uy
            dispX[b] += ux
            dispY[b] += uy
        }

        // —— 向心：把整张图拢在画布中部，否则无边的节点会被斥力推到墙角 ——
        for (i in 0 until count) {
            dispX[i] += (cx - x[i]) * GRAVITY
            dispY[i] += (cy - y[i]) * GRAVITY
        }

        var maxDisp = 0f
        for (i in 0 until count) {
            val len = sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i])
            if (len <= 0.0001f) continue
            // 只走温度允许的那一小步：方向取合力方向，长度封顶
            val move = min(len, temperature)
            val movX = dispX[i] / len * move
            val movY = dispY[i] / len * move

            val oldX = x[i]
            val oldY = y[i]
            var nx = oldX + movX
            var ny = oldY + movY

            // 硬边界（含半径）：撞墙就贴住，不反弹——反弹会和弹簧叠成抖动
            val marginX = radii[i] + k * WALL_PADDING
            val marginY = radii[i] + k * WALL_PADDING
            nx = nx.coerceIn(marginX, max(marginX, w - marginX))
            ny = ny.coerceIn(marginY, max(marginY, h - marginY))

            x[i] = nx
            y[i] = ny
            // 实际位移（可能被墙吃掉一部分）——用它判断收敛比看温度准
            maxDisp = max(maxDisp, sqrt((nx - oldX) * (nx - oldX) + (ny - oldY) * (ny - oldY)))
        }

        alpha *= COOLING
        if (maxDisp < STILL_EPSILON) {
            stillFrames++
        } else {
            stillFrames = 0
        }
        // 收敛的判定有两条：温度到底，或者连续多帧几乎没动（后者更快）。
        // 两条都收拢一次——力平衡会把图撑到四角，贴边在手机上看就是被裁掉一半。
        if ((alpha <= SETTLE_ALPHA || stillFrames >= STILL_FRAMES) && !fitted) {
            alpha = 0f
            fitToViewport()
            fitted = true
        }
        return maxDisp
    }

    /**
     * 把整张图等比收进视口（留 [FIT_PADDING] 边距），并居中。
     *
     * 只缩放几何，不改力模型：力平衡天然会让图「铺满并贴边」，而手机上贴边等于被裁；
     * 收敛后一次性缩放最省事，也不会像「每帧都归一化」那样把力反馈搞乱。
     */
    private fun fitToViewport() {
        if (count <= 1) return
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            if (x[i] < minX) minX = x[i]
            if (x[i] > maxX) maxX = x[i]
            if (y[i] < minY) minY = y[i]
            if (y[i] > maxY) maxY = y[i]
        }
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return

        val pad = min(viewW, viewH) * FIT_PADDING
        val availW = (viewW - 2f * pad).coerceAtLeast(1f)
        val availH = (viewH - 2f * pad).coerceAtLeast(1f)
        // 加了节点半径再算缩放，保证最大的圆也不出界
        val maxRadius = radii.maxOrNull() ?: 0f
        val spanX = (maxX - minX) + 2f * maxRadius
        val spanY = (maxY - minY) + 2f * maxRadius
        val scale = min(availW / spanX.coerceAtLeast(1f), availH / spanY.coerceAtLeast(1f))
            .coerceAtMost(1f)

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        for (i in 0 until count) {
            x[i] = viewW / 2f + (x[i] - cx) * scale
            y[i] = viewH / 2f + (y[i] - cy) * scale
        }
    }

    /**
     * 种子散布：黄金角螺旋 + 确定性抖动，落在中心 8% 半径内。
     *
     * 不用 Random：同一个 seed 必须得到同一张初始图，否则「进页面—退出来」两次
     * 形状完全不同，用户会以为记忆被改了。
     */
    private fun seedPositions() {
        if (count == 0) return
        val cx = viewW / 2f
        val cy = viewH / 2f
        val r0 = min(viewW, viewH) * SEED_RADIUS_RATIO
        for (i in 0 until count) {
            val h = hash(i)
            val angle = (GOLDEN_ANGLE_DEG * i + (h % 360).toDouble()).toFloat() * (PI / 180f).toFloat()
            val spread = 0.3f + 0.7f * ((h ushr 9) % 1000) / 1000f
            x[i] = cx + r0 * spread * cos(angle)
            y[i] = cy + r0 * spread * sin(angle)
        }
        alpha = 1f
        stillFrames = 0
        fitted = false
    }

    /** 确定性散列（SplitMix 变体）：同样的 i 永远得到同样的值。 */
    private fun hash(i: Int): Long {
        // 这几个常量最高位是 1，写成 Long 字面量会「out of range」，用 ULong 再转回 Long：
        // 位模式不变，溢出的环绕正是散列想要的
        var z = seed + i * 0x9E37_79B9_7F4A_7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58_476D_1CE4_E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D0_49BB_1331_11EBuL.toLong()
        return z xor (z ushr 31)
    }

    private companion object {
        /** 温度低于此值即视为静止（0.02 时每步位移已在亚像素级，肉眼不可见）。 */
        const val SETTLE_ALPHA = 0.02f

        /** 每步降温系数：0.97 在 60fps 下约 2 秒到阈值（再慢用户会盯着「一直在抖」的图）。 */
        const val COOLING = 0.97f

        /** 拖动时的保温温度：够邻居跟过来，又不至于整张图重排。 */
        const val DRAG_TEMPERATURE = 0.55f

        /** 初始步长（相对 k）：0.30 表示第一帧最多挪 0.3 个理想间距。 */
        const val TEMPERATURE_RATIO = 0.30f

        /** 步长下限：温度再低也留 0.35px，否则最后一点形变永远走不完。 */
        const val MIN_TEMPERATURE = 0.35f

        /** 理想间距的收缩系数：不乘它，力平衡会精确铺满画布（四边贴死）。 */
        const val IDEAL_SPACING_RATIO = 0.72f

        /** 相邻节点的理想边长为 k 的比例。 */
        const val EDGE_LENGTH_RATIO = 1.0f

        /** 弹簧的整体强度：FR 原式的 d²/L 在多个邻居叠加时会把簇压成一个点。 */
        const val SPRING_SCALE = 0.6f

        /** 向心引力系数：无边的节点全靠它拢回中间；太小整张图会被斥力推到四角。 */
        const val GRAVITY = 1.60f

        /** 碰撞判定的额外间隙（相对 k）。 */
        const val COLLISION_PADDING = 0.06f

        /** 碰撞推开的强度。 */
        const val COLLISION_STRENGTH = 1.2f

        /** 边界留白（相对 k）：节点不贴边，贴边会被视觉裁掉一半。 */
        const val WALL_PADDING = 0.10f

        /** 理想间距的上下限。 */
        const val MIN_IDEAL = 42f
        const val MAX_IDEAL = 220f

        /** 初始散布半径占比。 */
        const val SEED_RADIUS_RATIO = 0.09f

        /** 黄金角（度）。 */
        const val GOLDEN_ANGLE_DEG = 137.50776405

        /** 收敛后收拢时四周留白（相对短边）。 */
        const val FIT_PADDING = 0.06f

        /** 判定「几乎没动」的位移阈值与连续帧数。 */
        const val STILL_EPSILON = 0.35f
        const val STILL_FRAMES = 30
    }
}
