package com.vibereading.app.ui.reader

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.vibereading.app.ui.reader.pagination.BookWindow
import com.vibereading.app.ui.reader.pagination.ReaderPager
import com.vibereading.app.ui.reader.pagination.SimFlipState
import androidx.compose.foundation.pager.PagerState

/** Pager/Scroll 正文表面；chrome 与覆盖层由 ReaderOverlays 按原 z-order 叠加。 */
@Composable
fun BoxScope.ReaderContentSurface(
    state: ReaderUiState,
    isPagerMode: Boolean,
    window: BookWindow,
    pagerState: PagerState,
    scrollSession: ReaderScrollSession,
    layout: ReaderLayoutSpec,
    interactions: ReaderContentInteractions,
    simFlip: SimFlipState,
    background: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    isDark: Boolean
) {
    if (isPagerMode) {
        when {
            window.pageCount > 0 -> ReaderPager(
                pagerState, window, state.readingSettings.pageFlipMode, state.mode,
                layout, interactions, simFlip
            )
            state.chaptersLoaded -> EmptyReaderHint(isDark)
        }
    } else {
        when {
            state.chapters.isEmpty() -> EmptyReaderHint(isDark)
            scrollSession.chunks.isEmpty() -> ReaderOpeningShade(
                state.bookTitle.ifEmpty { "正在打开" }, background,
                layout.palette.titleText, accent
            )
            else -> ScrollReader(
                scrollSession.chunks, scrollSession.listState, state.mode,
                state.sourceLanguage, layout, interactions
            )
        }
    }
}
