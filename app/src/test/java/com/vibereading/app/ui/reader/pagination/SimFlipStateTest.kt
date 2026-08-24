package com.vibereading.app.ui.reader.pagination

import org.junit.Assert.assertEquals
import org.junit.Test

class SimFlipStateTest {

    @Test
    fun nextFromLeftUpperHalf_snapsCornerToTopRight() {
        val state = SimFlipState()
        state.onDown(x = 240f, y = 720f)
        state.calcCornerXY(x = 240f, viewWidth = 1080f, viewHeight = 2400f)

        state.setDirection(PageCurl.Direction.NEXT, viewWidth = 1080f, viewHeight = 2400f)

        assertEquals(1080f, state.cornerX)
        assertEquals(0f, state.cornerY)
    }

    @Test
    fun nextFromLeftLowerHalf_snapsCornerToBottomRight() {
        val state = SimFlipState()
        state.onDown(x = 240f, y = 1680f)
        state.calcCornerXY(x = 240f, viewWidth = 1080f, viewHeight = 2400f)

        state.setDirection(PageCurl.Direction.NEXT, viewWidth = 1080f, viewHeight = 2400f)

        assertEquals(1080f, state.cornerX)
        assertEquals(2400f, state.cornerY)
    }

    @Test
    fun prev_snapsCornerToBottomRight_regardlessOfStartX() {
        // PREV 起手位置无论在哪，卷角都必须量化到右下角（对齐 Legado setDirection(PREV)）。
        // 回归背景：旧实现用 viewWidth - startX 浮点卷角，拖过角 x 后当前页在右侧
        // 重新盖回已揭示的上一页（渲染错位）。
        for (startX in listOf(120f, 300f, 540f, 780f, 900f, 1080f)) {
            val state = SimFlipState()
            state.onDown(x = startX, y = 1200f)
            state.calcCornerXY(x = startX, viewWidth = 1080f, viewHeight = 2400f)

            state.setDirection(PageCurl.Direction.PREV, viewWidth = 1080f, viewHeight = 2400f)

            assertEquals("startX=$startX 卷角必须为右下角", 1080f, state.cornerX)
            assertEquals("startX=$startX 卷角必须为右下角", 2400f, state.cornerY)
        }
    }

    @Test
    fun cleanup_resetsSettleTarget() {
        val state = SimFlipState()
        state.settleTarget = 7
        state.cleanup()
        assertEquals(-1, state.settleTarget)
    }

    @Test
    fun onDown_resetsDownSettledFlip() {
        // 打断标记只属于发起打断的那个手势自身：新手势 DOWN 必须重置，
        // 否则上一次打断的 flag 会误吞下一次正常点按的翻页。
        val state = SimFlipState()
        state.downSettledFlip = true
        state.onDown(x = 10f, y = 10f)
        assertEquals(false, state.downSettledFlip)
    }

    @Test
    fun settlePage_commitsRunningAnimationTarget() {
        val state = SimFlipState()
        // 动画进行中被打断：应提交到动画本要落地的页
        state.settleTarget = 5
        assertEquals(5, simFlipSettlePage(state, currentPage = 3, pageCount = 10))
        // 已在该页：无需再翻
        assertEquals(-1, simFlipSettlePage(state, currentPage = 5, pageCount = 10))
        // 回弹/无动画（settleTarget = -1）：不翻页
        state.settleTarget = -1
        assertEquals(-1, simFlipSettlePage(state, currentPage = 3, pageCount = 10))
        // 越界目标页：按停留不翻处理
        state.settleTarget = 12
        assertEquals(-1, simFlipSettlePage(state, currentPage = 3, pageCount = 10))
    }

    @Test
    fun commitPage_cancelOrOutOfRange_returnsMinusOne() {
        // 正常完成：提交目标页；末页边界 pageCount-1 合法
        assertEquals(5, simFlipCommitPage(isCancel = false, target = 5, pageCount = 10))
        assertEquals(9, simFlipCommitPage(isCancel = false, target = 9, pageCount = 10))
        // 回弹：不落地
        assertEquals(-1, simFlipCommitPage(isCancel = true, target = 5, pageCount = 10))
        // 目标越界（含负值）：不落地
        assertEquals(-1, simFlipCommitPage(isCancel = false, target = 10, pageCount = 10))
        assertEquals(-1, simFlipCommitPage(isCancel = false, target = -1, pageCount = 10))
    }

    @Test
    fun duration_scalesWithDistance_andClampsToSharedRange() {
        val wf = 1080f
        val hf = 2400f
        // 全屏位移 → 恰为速度基准值
        assertEquals(400L, simFlipDurationMs(dx = wf, dy = 0f, viewWidth = wf, viewHeight = hf))
        // 微小位移 → 钳到共享区间下限
        assertEquals(100L, simFlipDurationMs(dx = 1f, dy = 0f, viewWidth = wf, viewHeight = hf))
        // 越屏大位移（NEXT 全程 ≈ 1.9×宽）→ 钳到共享区间上限
        assertEquals(600L, simFlipDurationMs(dx = wf * 2f, dy = 0f, viewWidth = wf, viewHeight = hf))
        // 纯纵向回弹（dx==0）走 dy 分支，按全屏高缩放
        assertEquals(400L, simFlipDurationMs(dx = 0f, dy = hf, viewWidth = wf, viewHeight = hf))
    }
}
