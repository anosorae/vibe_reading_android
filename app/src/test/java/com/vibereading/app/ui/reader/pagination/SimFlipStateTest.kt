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
}
