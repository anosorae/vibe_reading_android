package com.vibereading.app.ui.reader.pagination

import com.vibereading.app.domain.model.ReadingSettings
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
}
