package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.text.font.createFontFamilyResolver
import kotlinx.coroutines.test.runTest

/**
 * 复现：英文模式下点下一章到未翻译章节时跳到最后一页。
 * 假设：indexOf(chapterId, offset=0) 应返回该章第一页（pageInChapter=0 → 窗口首页），
 *      若返回最后一页则复现 bug。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NextChapterUntranslatedTest {

    private lateinit var measurer: TextMeasurer

    // 两章：第一章已翻译（有译文），第二章未翻译（PENDING，无译文）。
    // 模拟英文模式下从第一章翻到第二章。
    private val chapters = listOf(
        Chapter(
            id = 1, bookId = 1, title = "第一章", chapterIndex = 0,
            content = (0 until 10).joinToString("\n\n") { "第一章段落 $it 的中文内容，用于排版。" },
            translatedContent = (0 until 10).joinToString("\n\n") { "[${it + 1}] Chapter 1 paragraph $it English content." },
            status = Chapter.STATUS_DONE
        ),
        Chapter(
            id = 2, bookId = 1, title = "第二章", chapterIndex = 1,
            content = (0 until 10).joinToString("\n\n") { "第二章段落 $it 的中文内容，用于排版。" },
            translatedContent = null, // 未翻译
            status = Chapter.STATUS_PENDING
        )
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

    private fun window(mode: String = "en") = BookWindow(
        chapters = chapters,
        style = PageStyle(
            body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
            cn = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
            title = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp),
            paragraphSpacingPx = 10f
        ),
        mode = mode,
        contentWidthPx = 400f,
        contentHeightPx = 600f,
        measurer = measurer,
        backgroundMeasurer = { measurer }
    )

    @Test
    fun indexOf_offsetZero_onUntranslatedChapter_returnsFirstPage() = runTest {
        val w = window("en")
        // 以第一章为中心排版（带邻居第二章）
        w.recenterSync(1L, includeNeighbors = false)
        w.paginateNeighbors(1L)
        w.recenterSync(1L, includeNeighbors = true)

        val pageCount = w.pageCountInChapter(2L)
        // 翻到第二章 offset=0，应定位到第二章第一页
        val idx = w.indexOf(2L, 0L)
        assertNotNull("offset 0 必须能定位到页", idx)
        // 第二章第一页的 pageInChapter 应为 0
        val pageInChapter = w.pageInChapterOfPage(idx!!)
        assertEquals("offset 0 应定位到第二章第一页", 0, pageInChapter)
    }
}
