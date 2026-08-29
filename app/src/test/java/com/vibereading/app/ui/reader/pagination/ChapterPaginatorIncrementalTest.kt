package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlinx.coroutines.test.runTest

/**
 * ChapterPaginator 增量排版单测：断点续排（layoutUntil）的产出必须与一次性
 * 整章排版完全一致（页结构、切段、offset 区间、底部对齐逐项对拍）；
 * 覆盖跨页切段（PendingChunk）中途挂起再续排的场景，以及 BookWindow 的
 * 前缀排版 → 续排补完 → pageCount 增长链路。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChapterPaginatorIncrementalTest {

    private lateinit var measurer: TextMeasurer

    // 超长段落：触发跨页按行切段（PendingChunk 断点续排路径）
    private val longParagraph = (0 until 60).joinToString("") { "跨页切段续排测试第${it}句，" }
    private val paraTexts = buildList {
        repeat(20) { add("段落 $it 的中文内容，用于排版验证。") }
        add(longParagraph)
        repeat(20) { add("尾段 $it 的中文内容，用于排版验证。") }
    }
    private val chapter = Chapter(
        id = 1L,
        bookId = 1,
        title = "第一章",
        chapterIndex = 0,
        content = paraTexts.joinToString("\n\n")
    )
    // 中文书的 en 模式：译文带 [N] 标记与原文段落数一致
    private val chapterEn = chapter.copy(
        id = 2L,
        translatedContent = paraTexts.mapIndexed { i, _ ->
            "[${i + 1}] English translation of paragraph $i with enough words to wrap across lines."
        }.joinToString("\n\n")
    )

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            androidx.compose.ui.unit.Density(1f),
            LayoutDirection.Ltr,
            64
        )
    }

    private fun style() = PageStyle(
        body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
        cn = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
        title = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp),
        paragraphSpacingPx = 10f
    )

    private fun paginator(mode: String, chapter: Chapter, lazy: Boolean) = ChapterPaginator(
        chapterId = chapter.id,
        items = BookWindow.buildChapterItems(chapter, "zh"),
        style = style(),
        mode = mode,
        contentWidthPx = 400f,
        contentHeightPx = 600f,
        measurer = measurer,
        lazyLayout = lazy
    )

    /** 页结构对拍：忽略 TextLayoutResult（引用语义），比较文本/切段/offset/行高分配。 */
    private fun structural(pages: List<TextPage>): List<Any> = pages.map { page ->
        Triple(
            page.indexInChapter,
            page.usedHeightPx,
            page.units.map { u ->
                when (u) {
                    is PageUnit.Para -> listOf(
                        "P", u.paraIndex, u.cnText, u.enText, u.splitFirst, u.continuation,
                        u.paragraphContinues, u.lineCount, u.lineHeightExtraPx,
                        u.sourceStartOffset, u.sourceEndOffset
                    )
                    is PageUnit.Title -> listOf("T", u.section, u.title, u.status, u.errorMessage)
                    is PageUnit.Image -> listOf("I", u.paraIndex, u.path, u.displayWidthPx, u.displayHeightPx)
                }
            }
        )
    }

    @Test
    fun incrementalFullLayout_equalsEagerLayout() {
        val eager = paginator("zh", chapter, lazy = false)
        val inc = paginator("zh", chapter, lazy = true).apply { layoutUntil(Int.MAX_VALUE) }
        assertTrue(eager.layoutComplete)
        assertTrue(inc.layoutComplete)
        assertEquals(structural(eager.pages), structural(inc.pages))
    }

    @Test
    fun enModeIncrementalFullLayout_equalsEagerLayout() {
        val eager = paginator("en", chapterEn, lazy = false)
        val inc = paginator("en", chapterEn, lazy = true).apply { layoutUntil(Int.MAX_VALUE) }
        assertEquals(structural(eager.pages), structural(inc.pages))
    }

    @Test
    fun prefixStopsAtCoveredOffset_thenResumeCompletes() {
        val eager = paginator("zh", chapter, lazy = false)
        val midOffset = chapter.content.length / 2

        val inc = paginator("zh", chapter, lazy = true)
        inc.layoutUntil(midOffset)
        assertTrue("前缀排版应覆盖目标 offset", inc.coversOffset(midOffset))
        assertFalse("前缀排版未完成整章", inc.layoutComplete)
        assertTrue("前缀页数应少于整章", inc.pages.size < eager.pages.size)

        inc.layoutUntil(Int.MAX_VALUE)
        assertTrue(inc.layoutComplete)
        assertEquals(structural(eager.pages), structural(inc.pages))
    }

    @Test
    fun multiStopResume_includingMidParagraphSplit_equalsEager() {
        val eager = paginator("zh", chapter, lazy = false)
        val longStart = chapter.content.indexOf(longParagraph)
        assertTrue(longStart > 0)
        val stops = listOf(
            0,
            longStart,
            longStart + longParagraph.length / 3,  // 长段切段进行中挂起
            longStart + longParagraph.length * 2 / 3,
            chapter.content.length - 1
        )
        val inc = paginator("zh", chapter, lazy = true)
        for (stop in stops) inc.layoutUntil(stop)
        assertTrue("多次断点续排后应已覆盖全文", inc.coversOffset(chapter.content.length - 1))
        inc.layoutUntil(Int.MAX_VALUE)
        assertTrue(inc.layoutComplete)
        assertEquals(structural(eager.pages), structural(inc.pages))
    }

    @Test
    fun prefixCoversPageBoundaryOffset_exactly() {
        val eager = paginator("zh", chapter, lazy = false)
        // 第 3 页（index 2）的起始 offset 恰为第 2 页的结束（页边界）：offset 属于第 3 页
        //（pageForOffset 起点闭区间），前缀必须把第 3 页排出，否则定位回退到错误页
        val boundary = eager.pages[2].sourceStartOffset ?: error("内容页应有来源范围")
        val inc = paginator("zh", chapter, lazy = true)
        inc.layoutUntil(boundary)
        assertTrue("页边界 offset 应覆盖到所在页（严格大于）", inc.coversOffset(boundary))
        assertEquals("offset 所在页应已排出且可定位", 2, inc.pageForOffset(boundary))
        assertTrue(inc.pages.size >= 3)

        inc.layoutUntil(Int.MAX_VALUE)
        assertTrue(inc.layoutComplete)
        assertEquals(structural(eager.pages), structural(inc.pages))
    }

    @Test
    fun coversOffset_semantics() {
        val inc = paginator("zh", chapter, lazy = true)
        assertFalse("未排版不覆盖任何 offset", inc.coversOffset(0))
        inc.layoutUntil(0)
        assertTrue("首页内容页即覆盖 offset 0", inc.coversOffset(0))
        assertFalse("MAX 仅整章排完才满足", inc.coversOffset(Int.MAX_VALUE))
        inc.layoutUntil(Int.MAX_VALUE)
        assertTrue(inc.coversOffset(Int.MAX_VALUE))
    }

    @Test
    fun windowPrefixRecenter_thenComplete_growsPageCount() = runTest {
        val eagerCenterPages = paginator("zh", chapter, lazy = false).pages.size
        val chapters = listOf(chapter)
        val w = BookWindow(
            chapters = chapters,
            style = style(),
            mode = "zh",
            contentWidthPx = 400f,
            contentHeightPx = 600f,
            measurer = measurer,
            backgroundMeasurer = { measurer }
        )
        val midOffset = chapter.content.length / 2
        w.recenterAsync(1L, midOffset)
        assertTrue("前缀排版应覆盖恢复 offset", w.pageCountInChapter(1L) > 0)
        assertTrue("前缀页数少于整章", w.pageCountInChapter(1L) < eagerCenterPages)
        assertFalse("前缀排版未完成，页脚总页数未定", w.isChapterLayoutComplete(1L))
        assertNotNull("恢复 offset 应可定位到页", w.indexOf(1L, midOffset.toLong()))

        w.ensurePaginatorComplete(1L)
        w.refreshWindow() // 续排完成后主线程刷新索引空间（与 ReaderScreen 同一调用序）
        assertTrue("续排完成后整章排版完成", w.isChapterLayoutComplete(1L))
        assertEquals("续排后页数补齐整章", eagerCenterPages, w.pageCountInChapter(1L))
        assertNotNull(w.indexOf(1L, midOffset.toLong()))
        assertEquals("索引空间随续排增长", eagerCenterPages, w.pageCount)
    }
}
