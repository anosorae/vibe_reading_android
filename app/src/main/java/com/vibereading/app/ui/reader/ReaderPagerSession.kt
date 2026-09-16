package com.vibereading.app.ui.reader

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.pagination.BookWindow
import com.vibereading.app.ui.reader.pagination.PageStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 分页窗口恢复、邻居扩展、索引重映射与进度同步。 */
@Stable
class ReaderPagerSession internal constructor(
    private val pagerState: PagerState,
    private val scope: CoroutineScope
) {
    var jumpTarget by mutableStateOf<Long?>(null)
        private set
    var jumpOffset by mutableIntStateOf(0)
        private set
    var initialSeekDone by mutableStateOf(false)
        internal set
    var windowSliding by mutableStateOf(false)
        internal set

    fun jumpTo(id: Long, offset: Int = 0, navigate: (Long, Int) -> Unit) {
        jumpTarget = id
        jumpOffset = offset
        navigate(id, offset)
    }

    fun goPage(
        next: Int,
        window: BookWindow,
        flipMode: String,
        chapters: List<Chapter>,
        activeChapterId: Long?,
        curlController: ReaderCurlController,
        navigate: (Long, Int) -> Unit
    ) {
        if (curlController.state.animating) return
        val current = pagerState.currentPage
        if (next !in 0 until window.pageCount) {
            val chapterIndex = chapters.indexOfFirst { it.id == activeChapterId }
            val targetId = when {
                next >= window.pageCount -> chapters.getOrNull(chapterIndex + 1)?.id
                else -> chapters.getOrNull(chapterIndex - 1)?.id
            } ?: return
            val targetOffset = if (next >= window.pageCount) 0
            else chapters.firstOrNull { it.id == targetId }?.content?.length ?: 0
            jumpTo(targetId, targetOffset, navigate)
            return
        }
        if (flipMode == ReadingSettings.FLIP_SIMULATION) {
            curlController.startSimFlip(current, next, next > current)
            return
        }
        scope.launch {
            if (flipMode == ReadingSettings.FLIP_NO_ANIM) pagerState.scrollToPage(next)
            else pagerState.animateScrollToPage(next)
        }
    }

    fun syncProgressBeforeFlush(
        isPagerMode: Boolean,
        window: BookWindow,
        updateProgress: (Long, Int) -> Unit
    ) {
        if (!isPagerMode || !initialSeekDone || windowSliding) return
        val chapterId = window.chapterOfPage(pagerState.currentPage) ?: return
        val offset = window.offsetOfPage(pagerState.currentPage)?.first ?: return
        updateProgress(chapterId, offset)
    }

    internal fun clearJumpIfReached(window: BookWindow, activeChapterId: Long?) {
        val target = jumpTarget ?: return
        val pageOffset = window.offsetOfPage(pagerState.currentPage)?.first
        if (activeChapterId == target && window.chapterOfPage(pagerState.currentPage) == target && pageOffset == jumpOffset) {
            jumpTarget = null
        }
    }
}

@Composable
fun rememberReaderPagerSession(
    pagerState: PagerState,
    scope: CoroutineScope,
    window: BookWindow,
    pageStyle: PageStyle,
    contentWidthPx: Float,
    contentHeightPx: Float,
    isPagerMode: Boolean,
    state: ReaderUiState,
    updateProgress: (Long, Int) -> Unit,
    navigate: (Long, Int) -> Unit
): ReaderPagerSession {
    val session = remember(pagerState, scope) { ReaderPagerSession(pagerState, scope) }

    LaunchedEffect(window, pageStyle, contentWidthPx, contentHeightPx) {
        if (!isPagerMode || window.matchesStyle(pageStyle, contentWidthPx, contentHeightPx)) return@LaunchedEffect
        val chapterId = window.chapterOfPage(pagerState.currentPage) ?: state.activeChapterId
        val offset = window.offsetOfPage(pagerState.currentPage)?.first ?: state.position?.offset ?: 0
        session.windowSliding = true
        try {
            window.restyle(pageStyle, contentWidthPx, contentHeightPx)
            val index = chapterId?.let { window.indexOf(it, offset.toLong()) ?: window.indexOf(it, 0) }
                ?: window.indexOf(window.centerChapterId ?: 0L, 0) ?: 0
            if (pagerState.currentPage != index) pagerState.scrollToPage(index)
        } finally {
            session.windowSliding = false
        }
    }

    LaunchedEffect(window, state.activeChapterId, session.jumpTarget) {
        if (!isPagerMode) return@LaunchedEffect
        val activeId = state.activeChapterId ?: return@LaunchedEffect
        val programmatic = session.jumpTarget != null
        val target = session.jumpTarget ?: activeId
        val sourceOffset = if (programmatic) session.jumpOffset else state.position?.offset ?: 0
        session.windowSliding = true
        val preChapter = window.chapterOfPage(pagerState.currentPage)
        val prePageInChapter = window.pageInChapterOfPage(pagerState.currentPage)
        try {
            if (programmatic || !session.initialSeekDone || window.centerChapterId != target) {
                window.recenterAsync(target, sourceOffset)
            }
            val alreadyOnTarget = !programmatic && session.initialSeekDone &&
                preChapter == target && window.chapterOfPage(pagerState.currentPage) == preChapter &&
                window.pageInChapterOfPage(pagerState.currentPage) == prePageInChapter
            val index = window.indexOf(target, sourceOffset.toLong())
                ?: window.indexOf(target, 0)
                ?: window.indexOf(window.centerChapterId ?: target, 0)
                ?: 0
            if (!alreadyOnTarget && pagerState.currentPage != index) pagerState.scrollToPage(index)
            session.initialSeekDone = true
        } finally {
            session.windowSliding = false
        }
        scope.launch {
            withFrameNanos { }
            window.ensurePaginatorComplete(target)
            val currentChapter = window.chapterOfPage(pagerState.currentPage)
            val currentPageInChapter = window.pageInChapterOfPage(pagerState.currentPage)
            window.refreshWindow()
            val remapped = currentChapter?.let { window.indexOf(it, currentPageInChapter) }
            if (remapped != null && pagerState.currentPage != remapped) pagerState.scrollToPage(remapped)
            window.preloadNeighbors(target)
        }
    }

    LaunchedEffect(window, state.activeChapterId, session.jumpTarget, pagerState.currentPage) {
        session.clearJumpIfReached(window, state.activeChapterId)
    }

    LaunchedEffect(window, state.activeChapterId, session.initialSeekDone, state.chaptersLoaded) {
        if (!isPagerMode || !session.initialSeekDone || !state.chaptersLoaded) return@LaunchedEffect
        val target = state.activeChapterId ?: return@LaunchedEffect
        if (!window.hasNeighbors(target)) window.paginateNeighbors(target)
        val currentChapter = session.jumpTarget ?: state.activeChapterId
        val currentOffset = if (session.jumpTarget != null) session.jumpOffset else state.position?.offset
        session.windowSliding = true
        try {
            window.recenterSync(target)
            val index = window.indexOf(currentChapter ?: target, currentOffset?.toLong() ?: 0L)
                ?: window.indexOf(target, 0) ?: 0
            if (pagerState.currentPage != index) pagerState.scrollToPage(index)
        } finally {
            session.windowSliding = false
        }
    }

    LaunchedEffect(pagerState.currentPage, isPagerMode) {
        if (!isPagerMode || !session.initialSeekDone || session.windowSliding) return@LaunchedEffect
        val chapterId = window.chapterOfPage(pagerState.currentPage) ?: return@LaunchedEffect
        val offset = window.offsetOfPage(pagerState.currentPage)?.first ?: return@LaunchedEffect
        updateProgress(chapterId, offset)
        if (chapterId != state.activeChapterId) navigate(chapterId, offset)
    }
    return session
}
