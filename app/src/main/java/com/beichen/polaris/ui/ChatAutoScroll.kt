package com.beichen.polaris.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow

/**
 * 「跟随最新消息」的状态载体。
 *
 * 三个来源会改它：用户上翻 → [stop]；滑回底部 → [forceFollow]；
 * 发消息、点悬浮按钮这类明确意图 → [forceFollow]。
 *
 * 之所以做成对象而不是 `var followTail by remember { mutableStateOf(true) }`：
 * 状态和它的四条 effect 属于同一件事，放在 ChatScreen 里每修一轮就多十几行注释，
 * 主屏已经三千行。抽出来之后，「跟随」的全部规则只在这一个文件里。
 */
@Stable
internal class FollowTail {
    private val state = mutableStateOf(true)

    /** 当前是否跟随最新。只为读取：写入一律走 [forceFollow] / [stop]。 */
    val following: Boolean get() = state.value

    fun forceFollow() {
        state.value = true
    }

    fun stop() {
        state.value = false
    }
}

/**
 * 对话列表的自动贴底。
 *
 * 只在 [follow] 为真时动手；用户上翻即停，滑回底部才恢复。
 *
 * [itemCount] 是已落库的条目数，[draft] 是尚未落库的流式草稿
 * （草稿本身算一个额外的尾项，出现/消失都要归位一次）。
 *
 * 为什么单独处理「同一项内部长高」（第 4 条 effect）：一条回答超过一屏后，
 * 后续 token 只是让最后一项变高，item 数不变 —— 只按条目数跟随的话，
 * 新字会生成在视口下方，用户看到的是「卡住了」，得手动点回底部。
 */
@Composable
internal fun FollowTailEffect(
    listState: LazyListState,
    itemCount: Int,
    draft: String?,
    follow: FollowTail
) {
    // 1. 用户往「上」翻（可见起点前移）= 他在看历史，立刻停止跟随。
    //    只用公开 API：LazyListState 的 interactionSource 是 internal，拿不到拖拽事件。
    LaunchedEffect(listState) {
        var lastIdx = listState.firstVisibleItemIndex
        var lastOff = listState.firstVisibleItemScrollOffset
        // 视口高度：软键盘弹出/收起、横竖屏切换都会改它。
        //
        // 【真机第三轮修复】只比 idx/offset 会把「键盘弹出导致 LazyColumn 微调」误判成
        // 用户上翻：视口一矮，内容为保持可见项会往回让，offset 变小 → movedBack 成立 →
        // 跟随被永久关掉 → 之后发消息界面完全不动。每次发消息前都要点输入框，键盘必然
        // 弹一次，所以这个误判是**必然发生**的（实测 8/8 复现）。视口尺寸变化时只同步
        // 基准值、不判方向，把「布局变化」和「用户手势」分开。
        var lastViewportH = listState.layoutInfo.viewportSize.height
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.viewportSize.height
            )
        }.collect { (idx, off, vpH) ->
            if (vpH != lastViewportH) {
                // 视口变了：这一跳是布局重排造成的，不当作手势，只更新基准
                lastViewportH = vpH
                lastIdx = idx
                lastOff = off
                return@collect
            }
            val movedBack = idx < lastIdx || (idx == lastIdx && off < lastOff)
            if (movedBack) follow.stop()
            lastIdx = idx
            lastOff = off
        }
    }
    // 2. 回到底部（content 末尾可见）才恢复跟随
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            if (!canForward) follow.forceFollow()
        }
    }
    // 3. 条目数变化（含草稿出现/消失）：归位到内容**底部**。
    //    只传 index 会把最后一条的**顶部**对齐视口顶端——思考过程一条就有几百行，
    //    看起来就是「被拽回最开始思考的地方」；scrollOffset 传大值才是滚到末尾
    //    （LazyList 会自动钳制）。
    //
    //    不把 follow.following 放进 key：用户上翻的那一刻 effect 若重启，会立刻滚回底部，
    //    等于当场撤销「停止跟随」。跟随状态只在 lambda 里读，不参与重启。
    LaunchedEffect(itemCount, draft != null) {
        val count = itemCount + if (draft != null) 1 else 0
        if (count > 0 && follow.following) {
            listState.scrollToItem(count - 1, Int.MAX_VALUE)
        }
    }
    // 4. 同一条目内部长高（流式正文/推理过程追加）：最后一项的底边被顶出视口时补一次。
    //    判据取「最后一项已经是列表末尾，且露在可视区外」——内容没超一屏、或用户
    //    正停在中段看历史时都不成立，不会把人的手指顶掉。
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null &&
                last.index == info.totalItemsCount - 1 &&
                last.offset + last.size > info.viewportEndOffset - info.afterContentPadding
        }.collect { overflow ->
            if (overflow && follow.following) {
                // 每次只补到最新，不带动画：动效会跟手势抢，W-43 的教训。
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
            }
        }
    }
}
