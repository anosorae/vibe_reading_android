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
}
