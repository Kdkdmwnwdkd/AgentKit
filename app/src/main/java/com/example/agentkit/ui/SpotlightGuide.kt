package com.example.agentkit.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.composed

/*
 * 聚光灯引导（P2，2026-10-21）。
 *
 * ## 为什么不用「再来一个文字弹窗」
 * v6 的首启引导（ChatScreen.OnboardingDialog）是纯文字分步说明，用户看完仍然不知道
 * 界面上哪个是输入框、哪个是发送键。竞品（AiCode 的 SpotlightOverlay）的做法是
 * **在真实界面上开一个洞**：遮罩压暗，只有目标元素亮着，配一句话。
 *
 * ## 锚点怎么来
 * 不硬编码坐标（不同机型、字号、横竖屏全不一样），而是让目标组件自己上报：
 *  - [guideAnchor] 修饰符在布局完成后把 `boundsInRoot()` 写进 [GuideAnchorRegistry]；
 *  - 覆盖层读注册表画洞。
 * 注册表通过 [LocalGuideAnchors] 下发，没有 Provider 时修饰符是空操作——
 * 所以这些锚点常量可以安全地挂在任何页面上，不影响正常渲染。
 *
 * ## 为什么只有三步
 * 原计划五个锚点（输入框 → 发送 → 抽屉 → 权限红点 → 技能市场）。后两个位于抽屉内，
 * 抽屉关闭时整块内容虽然仍在组合树里，但被 offset 推到屏幕外——遮罩会开在屏幕左边外面，
 * 真机上就是「一片黑，什么都没高亮」。要做对必须在引导过程中自动展开抽屉并等动画结束，
 * 这会把一个纯展示层变成状态机，风险和收益不成比例。
 * 因此收敛为三个「永远在屏幕上」的锚点，抽屉里有什么写在第三步的正文里。
 */

/** 可被聚光灯指向的界面元素。新增锚点：加枚举值 + 在目标组件挂 [guideAnchor]。 */
enum class GuideAnchor {
    /** 对话页输入栏整块。 */
    INPUT_BAR,

    /** 发送按钮。 */
    SEND_BUTTON,

    /** 顶栏汉堡（抽屉入口）。 */
    DRAWER
}

/**
 * 锚点注册表：组件上报矩形，覆盖层按需读取。
 *
 * 用 `mutableStateMapOf` 而不是普通 Map：位置变化（旋转、键盘弹起、动画位移）要触发
 * 覆盖层重绘，否则洞会停在旧位置。
 */
class GuideAnchorRegistry {
    private val rects = mutableStateMapOf<GuideAnchor, Rect>()

    fun report(anchor: GuideAnchor, rect: Rect) {
        if (rects[anchor] != rect) rects[anchor] = rect
    }

    fun rectOf(anchor: GuideAnchor): Rect? = rects[anchor]

    /**
     * 组件离开组合时清掉它的矩形。
     *
     * 不清的后果：切页 / 旋转 / 宽屏隐藏汉堡之后，注册表里留着上一次的位置，
     * 覆盖层第一帧会照着旧坐标开洞——真机上表现为「洞开在空白处」。
     */
    fun forget(anchor: GuideAnchor) {
        rects.remove(anchor)
    }
}

/** 当前组合树里的锚点注册表；为 null 时 [guideAnchor] 不做事。 */
val LocalGuideAnchors = compositionLocalOf<GuideAnchorRegistry?> { null }

/**
 * 把组件标记为聚光灯锚点。
 *
 * `composed` 会为每个调用点生成独立的修饰符实例，这是必要的：修饰符内部要读
 * CompositionLocal，而普通 `Modifier.onGloballyPositioned` 在 `fun Modifier.x()` 里
 * 读不到（不是 @Composable 上下文）。
 */
fun Modifier.guideAnchor(anchor: GuideAnchor): Modifier = composed {
    val registry = LocalGuideAnchors.current ?: return@composed this
    // 离开组合即注销：锚点只在「它真的在屏幕上」时有效
    DisposableEffect(registry, anchor) {
        onDispose { registry.forget(anchor) }
    }
    onGloballyPositioned { registry.report(anchor, it.boundsInRoot()) }
}

/** 一步引导：指向哪个锚点 + 说什么。 */
data class SpotlightStep(
    val anchor: GuideAnchor,
    val title: String,
    val body: String
)

/** 默认三步（顺序即操作顺序：先说话、再发送、最后告诉你抽屉里有什么）。 */
val DefaultSpotlightSteps: List<SpotlightStep> = listOf(
    SpotlightStep(
        anchor = GuideAnchor.INPUT_BAR,
        title = "在这里说你要做什么",
        body = "像跟人说话一样下指令，比如「帮我把微信的消息读一下」「把这个链接存到笔记」。"
    ),
    SpotlightStep(
        anchor = GuideAnchor.SEND_BUTTON,
        title = "点这里发送",
        body = "发送后 Agent 会自己决定要不要调用工具、要不要问你确认。生成中可以随时点停止。"
    ),
    SpotlightStep(
        anchor = GuideAnchor.DRAWER,
        title = "其余入口都在抽屉里",
        body = "控制台（权限红点在这里）、技能中心、运行日志、记忆库、设置都在这一侧。"
    )
)

/**
 * 聚光灯覆盖层。
 *
 * 行为：压暗全屏 → 在目标元素处开一个圆角洞（描一圈 primary 边）→ 卡片说明 →
 * 点任意处或「下一步」推进；「跳过」直接结束。结束一律回调 [onFinish]，
 * 由调用方落 `spotlight_seen`（覆盖层自己不碰持久化，便于测试与「重看引导」）。
 *
 * @param steps 步骤表
 * @param registry 锚点注册表（由宿主建立并下发）
 * @param onFinish 完成或跳过
 */
@Composable
fun SpotlightGuide(
    registry: GuideAnchorRegistry,
    onFinish: () -> Unit,
    steps: List<SpotlightStep> = DefaultSpotlightSteps
) {
    if (steps.isEmpty()) {
        LaunchedEffect(Unit) { onFinish() }
        return
    }
    // rememberSaveable：旋转 / 分屏后不该从第一步重来。
    // 这里必须用 mutableStateOf 而不是 mutableIntStateOf——后者的类型不被
    // rememberSaveable 的 autoSaver 接受，进程恢复时会抛 "cannot be saved"。
    var index by rememberSaveable { mutableStateOf(0) }
    val step = steps.getOrNull(index)
    // 覆盖层自身的原点（相对 root）。boundsInRoot 给的是 root 坐标，而 Canvas 画的是本地坐标，
    // 两者差一个「内容区偏移」（窄屏 = 状态栏高度，宽屏 = 顶部 + 水平居中留白）。
    // 不减这一下，洞会整体上移 / 左移——这是靠读代码能算出来的错位，不是玄学。
    var originInRoot by remember { mutableStateOf(Offset.Zero) }

    // 锚点还没测量到（页面刚组合 / 该锚点在当前形态下不存在，如宽屏无汉堡）：
    // 等一帧再判，仍没有就跳过这一步。不能死等——否则宽屏用户会卡在第二步。
    val rect = step?.let { registry.rectOf(it.anchor) }
    LaunchedEffect(index, rect) {
        if (step == null) return@LaunchedEffect
        if (rect != null) return@LaunchedEffect
        delay(300)
        if (registry.rectOf(step.anchor) == null) {
            if (index < steps.lastIndex) index++ else onFinish()
        }
    }

    if (step == null || rect == null) return

    val advance: () -> Unit = {
        if (index < steps.lastIndex) index++ else onFinish()
    }

    val ak = LocalAkColors
    val scrim = ak.scrim
    // 颜色必须在 @Composable 上下文里读出来：Canvas 的 DrawScope 不是组合上下文
    val ringColor = MaterialTheme.colorScheme.primary

    BackHandler { onFinish() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { originInRoot = it.positionInRoot() }
            // 点任意处推进：遮罩本来就是模态的，点哪儿都该是「下一步」，而不是穿透到底层
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = advance
            )
    ) {
        val screenHeight = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val pad = 6.dp.toPx()
            val radius = 20.dp.toPx()
            val dx = originInRoot.x
            val dy = originInRoot.y
            val hole = Rect(
                left = (rect.left - dx - pad).coerceAtLeast(0f),
                top = (rect.top - dy - pad).coerceAtLeast(0f),
                right = (rect.right - dx + pad).coerceAtMost(size.width),
                bottom = (rect.bottom - dy + pad).coerceAtMost(size.height)
            )
            // EvenOdd：外框减去内洞，一次填充完成「压暗但目标透亮」
            val path = Path().apply {
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(hole, CornerRadius(radius)))
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, scrim)
            drawRoundRect(
                color = ringColor,
                topLeft = Offset(hole.left, hole.top),
                size = androidx.compose.ui.geometry.Size(hole.width, hole.height),
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // 卡片放在洞的另一侧：洞在上半屏就贴底，反之贴顶。不测量卡片高度，避免抖动。
        // 用局部坐标比较（rect 是 root 坐标），否则状态栏偏移会让判断偏向一侧。
        val holeInTopHalf = (rect.center.y - originInRoot.y) < screenHeight / 2f
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (holeInTopHalf) Arrangement.Bottom else Arrangement.Top
        ) {
            GuideCard(
                step = step,
                index = index,
                total = steps.size,
                onNext = advance,
                onSkip = onFinish
            )
        }
    }
}

/** 说明卡片：标题 + 正文 + 步骤计数 + 「跳过 / 下一步」。 */
@Composable
private fun GuideCard(
    step: SpotlightStep,
    index: Int,
    total: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = 360.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${index + 1} / $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AkTextButton(
                    text = "跳过",
                    onClick = onSkip,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                AkPrimaryButton(
                    text = if (index == total - 1) "开始使用" else "下一步",
                    onClick = onNext
                )
            }
        }
    }
}

// —— 持久化（与权限引导分开：两者触发条件不同，混用一个键会导致互相吞掉）——

internal const val SPOTLIGHT_PREFS = "agentkit_spotlight"
internal const val SPOTLIGHT_SEEN_KEY = "spotlight_seen"

internal fun spotlightSeen(ctx: Context): Boolean =
    ctx.getSharedPreferences(SPOTLIGHT_PREFS, Context.MODE_PRIVATE)
        .getBoolean(SPOTLIGHT_SEEN_KEY, false)

internal fun markSpotlightSeen(ctx: Context) {
    ctx.getSharedPreferences(SPOTLIGHT_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(SPOTLIGHT_SEEN_KEY, true).apply()
}

/** 设置页「重看引导」：清标记，回到对话页即会再出现一次。 */
internal fun resetSpotlight(ctx: Context) {
    ctx.getSharedPreferences(SPOTLIGHT_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(SPOTLIGHT_SEEN_KEY, false).apply()
}
