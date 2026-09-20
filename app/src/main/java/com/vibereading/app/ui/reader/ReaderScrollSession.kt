package com.vibereading.app.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 滚动模式 chunks 构建、恢复定位、可见项跟踪和程序化跳章。 */
@Stable
class ReaderScrollSession internal constructor(val listState: LazyListState) {
    var chunks by mutableStateOf<List<ScrollItem>>(emptyList())
        internal set
    var suppressTracking by mutableStateOf(false)
        internal set
    var pendingJumpChapter by mutableStateOf<Long?>(null)
        private set

    fun jumpTo(id: Long, navigate: (Long, Int) -> Unit) {
        pendingJumpChapter = id
        navigate(id, 0)
    }

    internal fun finishJump() {
        pendingJumpChapter = null
    }
}

@Composable
fun rememberReaderScrollSession(
    isPagerMode: Boolean,
    state: ReaderUiState,
    titleMode: Int,
    updateProgress: (Long, Int) -> Unit
): ReaderScrollSession {
    // 滚动位置同样以 DB 的 chapterId+offset 为唯一事实源（见 readerProcessGeneration 注释），
    // 跨进程恢复的列表索引指向已销毁的 chunk 列表，交给定位 effect 按保存 offset 重锚。
    val listState = androidx.compose.runtime.key(readerProcessGeneration) { rememberLazyListState() }
    val session = remember(listState) { ReaderScrollSession(listState) }

    LaunchedEffect(titleMode) {
        session.chunks = emptyList()
    }
    LaunchedEffect(!isPagerMode, state.chapters, titleMode) {
        if (!isPagerMode && state.chapters.isNotEmpty()) {
            val updated = withContext(Dispatchers.Default) { buildScrollChunks(state.chapters, titleMode) }
            if (updated != session.chunks) {
                session.suppressTracking = true
                session.chunks = updated
            }
        }
    }
    LaunchedEffect(session.chunks, !isPagerMode, state.activeChapterId, state.position?.offset) {
        if (isPagerMode || session.chunks.isEmpty() || !state.restoreReady) return@LaunchedEffect
        val index = session.chunks.indexInChunks(state.activeChapterId, state.position?.offset ?: 0)
            ?: return@LaunchedEffect
        session.suppressTracking = true
        listState.scrollToItem(index)
        delay(300)
        session.suppressTracking = false
    }
    LaunchedEffect(listState, session.chunks, isPagerMode) {
        if (isPagerMode) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index?.let { session.chunks.getOrNull(it) }
        }.collect { visibleItem ->
            if (session.suppressTracking || session.pendingJumpChapter != null || visibleItem == null) return@collect
            updateProgress(visibleItem.chapterId, visibleItem.sourceStartOffset)
        }
    }
    LaunchedEffect(state.activeChapterId, session.chunks.size) {
        val pending = session.pendingJumpChapter
        if (pending != null && pending == state.activeChapterId) {
            session.chunks.indexInChunks(state.activeChapterId)?.let { index ->
                session.suppressTracking = true
                listState.scrollToItem(index)
                delay(300)
                session.suppressTracking = false
            }
            session.finishJump()
        }
    }
    return session
}
