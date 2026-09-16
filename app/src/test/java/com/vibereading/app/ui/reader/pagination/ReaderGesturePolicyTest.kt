package com.vibereading.app.ui.reader.pagination

import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.ReaderTapAction
import com.vibereading.app.ui.reader.readerPagerScrollEnabled
import com.vibereading.app.ui.reader.readerShouldDismissOverlayOnGestureStart
import com.vibereading.app.ui.reader.resolveReaderTapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGesturePolicyTest {

    @Test
    fun visibleOverlay_isDismissedWhenGestureStarts() {
        assertTrue(readerShouldDismissOverlayOnGestureStart(overlayVisible = true))
    }

    @Test
    fun hiddenOverlay_doesNotNeedDismissal() {
        assertFalse(readerShouldDismissOverlayOnGestureStart(overlayVisible = false))
    }

    @Test
    fun pagerScrollRemainsEnabledForNormalPagingModes() {
        assertFalse(readerPagerScrollEnabled(ReadingSettings.FLIP_NO_ANIM))
        assertFalse(readerPagerScrollEnabled(ReadingSettings.FLIP_SIMULATION))
        assertTrue(readerPagerScrollEnabled(ReadingSettings.FLIP_PAGER))
        assertTrue(readerPagerScrollEnabled(ReadingSettings.FLIP_COVER))
    }

    @Test
    fun pagerTapZones_includeExactBoundaries() {
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, action(x = 99f))
        assertEquals(ReaderTapAction.TOGGLE_TOOLBAR, action(x = 100f))
        assertEquals(ReaderTapAction.TOGGLE_TOOLBAR, action(x = 199.9f))
        assertEquals(ReaderTapAction.NEXT_PAGE, action(x = 200f))
    }

    @Test
    fun oneHandMode_mapsLeftZoneToNextPage() {
        assertEquals(ReaderTapAction.NEXT_PAGE, action(x = 20f, oneHand = true))
    }

    @Test
    fun scrollMode_onlyUsesMiddleZone() {
        assertEquals(ReaderTapAction.NONE, action(x = 20f, mode = ReadingSettings.FLIP_SCROLL))
        assertEquals(ReaderTapAction.TOGGLE_TOOLBAR, action(x = 150f, mode = ReadingSettings.FLIP_SCROLL))
        assertEquals(ReaderTapAction.NONE, action(x = 280f, mode = ReadingSettings.FLIP_SCROLL))
    }

    @Test
    fun popupSelectionAndOverlay_havePriorityOverPageActions() {
        assertEquals(ReaderTapAction.DISMISS_POPUP, action(x = 280f, popup = true, overlay = true))
        assertEquals(ReaderTapAction.NONE, action(x = 280f, selection = true, overlay = true))
        assertEquals(ReaderTapAction.DISMISS_OVERLAY, action(x = 280f, overlay = true))
    }

    @Test
    fun settledSimulationFlip_blocksSecondSideFlipButKeepsMiddleToolbar() {
        assertEquals(
            ReaderTapAction.NONE,
            action(x = 20f, mode = ReadingSettings.FLIP_SIMULATION, settled = true)
        )
        assertEquals(
            ReaderTapAction.TOGGLE_TOOLBAR,
            action(x = 150f, mode = ReadingSettings.FLIP_SIMULATION, settled = true)
        )
        assertEquals(
            ReaderTapAction.NONE,
            action(x = 280f, mode = ReadingSettings.FLIP_SIMULATION, settled = true)
        )
    }

    private fun action(
        x: Float,
        mode: String = ReadingSettings.FLIP_PAGER,
        oneHand: Boolean = false,
        overlay: Boolean = false,
        popup: Boolean = false,
        selection: Boolean = false,
        settled: Boolean = false
    ): ReaderTapAction = resolveReaderTapAction(
        x = x,
        width = 300f,
        flipMode = mode,
        oneHandMode = oneHand,
        overlayVisible = overlay,
        popupVisible = popup,
        hadSelectionAtDown = selection,
        settledSimulationFlip = settled
    )
}
