package com.vibereading.app.ui.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.components.ReadingChapterTitle
import com.vibereading.app.ui.reader.components.ReadingIllustrationBlock
import com.vibereading.app.ui.reader.components.ReadingParagraphItem
import com.vibereading.app.ui.reader.components.ParagraphKey
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.reader.content.ReadingContent
import com.vibereading.app.ui.reader.content.ReadingParagraph
import com.vibereading.app.ui.reader.pagination.PageStyle

// ── 滚动模式：与分页共用 ReadingContent 的扁平内容项 ──
sealed interface ScrollItem {
    val chapterId: Long
    val sourceStartOffset: Int

    data class Title(
        override val chapterId: Long,
        val section: String?,
        val title: String,
        val status: Int,
        val errorMessage: String?
    ) : ScrollItem {
        override val sourceStartOffset: Int = 0
    }

    data class Paragraph(
        override val chapterId: Long,
        val paragraph: ReadingParagraph
    ) : ScrollItem {
        override val sourceStartOffset: Int = paragraph.sourceStartOffset
    }
}

fun buildScrollChunks(
    chapters: List<Chapter>,
    titleMode: Int
): List<ScrollItem> = buildList {
    chapters.forEach { chapter ->
        val content = ReadingContent.fromChapter(chapter)
        if (titleMode != ReadingSettings.TITLE_MODE_HIDDEN) {
            add(ScrollItem.Title(content.chapterId, content.section, content.title, content.status, content.errorMessage))
        }
        content.paragraphs.forEach { add(ScrollItem.Paragraph(content.chapterId, it)) }
    }
}

fun chapterIdOfChunkKey(key: String?): Long? =
    key?.substringAfter('-', "")?.substringBefore('-')?.toLongOrNull()

fun List<ScrollItem>.indexInChunks(chapterId: Long?, offset: Int = 0): Int? {
    if (chapterId == null) return null
    val candidates = indices.filter { get(it).chapterId == chapterId }
    if (candidates.isEmpty()) return null
    val containing = candidates.firstOrNull { index ->
        val item = get(index)
        item is ScrollItem.Paragraph &&
            item.paragraph.sourceStartOffset <= offset &&
            offset < item.paragraph.sourceEndOffset
    }
    return containing
        ?: candidates.firstOrNull { get(it).sourceStartOffset > offset }
        ?: candidates.lastOrNull { get(it) is ScrollItem.Paragraph }
        ?: candidates.last()
}

@Composable
fun ScrollReader(
    chapters: List<Chapter>,
    chunks: List<ScrollItem>,
    scrollState: LazyListState,
    state: ReaderUiState,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    paddingH: Int,
    paddingV: Int,
    statusBarPx: Int,
    navBarPx: Int,
    onJumpChapter: (Long) -> Unit,
    selectionState: TextSelectionState? = null,
    onIllustrationClick: ((String) -> Unit)? = null
) {
    val density = LocalDensity.current
    // 内容区顶部/底部扣除系统栏高度（用缓存值，沉浸式切换不触发滚动内容跳动）
    val insetTopDp = with(density) { statusBarPx.toDp() }
    val insetBottomDp = with(density) { navBarPx.toDp() }
    LazyColumn(
        state = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = paddingH.dp),
        contentPadding = PaddingValues(
            top = insetTopDp + paddingV.dp,
            bottom = insetBottomDp + paddingV.dp
        )
    ) {
        itemsIndexed(chunks, key = { _, item ->
            when (item) {
                is ScrollItem.Title -> "title-${item.chapterId}"
                is ScrollItem.Paragraph -> "para-${item.chapterId}-${item.paragraph.index}"
            }
        }) { _, item ->
            when {
                item is ScrollItem.Title -> ReadingChapterTitle(
                    section = item.section,
                    title = item.title,
                    palette = palette,
                    pageStyle = pageStyle
                )
                // 插图段（ADR-002）：双语两侧共用同一张图，无气泡不参与选词
                item is ScrollItem.Paragraph && item.paragraph.illustration != null ->
                    ReadingIllustrationBlock(
                        link = item.paragraph.illustration!!,
                        showSpacer = true,
                        onClick = if (onIllustrationClick != null) {
                            { onIllustrationClick(item.paragraph.illustration!!.path) }
                        } else null
                    )
                item is ScrollItem.Paragraph -> ReadingParagraphItem(
                    paragraph = item.paragraph,
                    mode = state.mode,
                    sourceLanguage = state.sourceLanguage,
                    pageStyle = pageStyle,
                    palette = palette,
                    selectionState = selectionState,
                    paragraphKey = ParagraphKey(item.chapterId, item.paragraph.index)
                )
            }
        }
    }
}
