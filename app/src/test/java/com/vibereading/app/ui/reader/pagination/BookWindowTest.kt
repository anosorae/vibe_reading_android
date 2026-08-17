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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.text.font.createFontFamilyResolver

/**
 * BookWindow 章窗口模型单测：窗口 = 当前章 ±1，远跳 O(1)、跨章连续索引、
 * 预载不破坏窗口。断言结构化，不 pin 像素值。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BookWindowTest {

    private lateinit var measurer: TextMeasurer
    private val chapters = (0 until 5).map { i ->
        Chapter(
            id = (i + 1).toLong(),
            bookId = 1,
            title = "第${i + 1}章",
            chapterIndex = i,
            content = (0 until 20).joinToString("\n\n") { "第${i + 1}章段落 $it 的中文内容，用于排版。" }
        )
    }

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            androidx.compose.ui.unit.Density(1f),
            LayoutDirection.Ltr,
            64
        )
    }

    private fun window(mode: String = "zh") = BookWindow(
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
    fun recenter_buildsWindowOfThreeChapters() {
        val w = window()
        w.recenterSync(3)
        assertEquals("窗口应含 3 章", listOf(2L, 3L, 4L), w.windowChapterIds)
        assertTrue(w.pageCount >= 3)
        // 每章页数 = 该章 paginator 页数
        val c2 = w.pageCountInChapter(2)
        val c3 = w.pageCountInChapter(3)
        val c4 = w.pageCountInChapter(4)
        assertEquals("窗口扁平页数 = 三章页数之和", c2 + c3 + c4, w.pageCount)
    }

    @Test
    fun recenter_crossChapterIndexIsContinuous() {
        val w = window()
        w.recenterSync(2)
        val c2 = w.pageCountInChapter(2)
        val c1 = w.pageCountInChapter(1)
        // 窗口 [1,2,3]：扁平索引空间从第1章页开始，第3章首页 = 第1章页数 + 第2章页数
        val c3First = w.indexOf(3, 0)
        assertNotNull("第3章首页应在窗口内", c3First)
        assertEquals(c1 + c2, c3First)
        // 窗口滑动：center 移到 3 后，索引空间重映射；第3章首页 = 前导章(第2章)页数
        w.recenterSync(3)
        assertEquals("第3章首页 = 窗口前导第2章页数", w.pageCountInChapter(2), w.indexOf(3, 0))
    }

    @Test
    fun recenter_preservesVisualPositionAfterSlide() {
        val w = window()
        w.recenterSync(3)
        val idx = w.indexOf(3, 2) ?: return // 第3章第3页在窗口 [2,3,4] 内必可定位
        // 滑动窗口后，第3章第3页仍在窗口内（center=3 的 ±1 含第3章）
        w.recenterSync(4)
        assertNotNull("滑动后原视觉页仍可定位", w.indexOf(3, 2))
        assertTrue(idx >= 0)
    }

    @Test
    fun farJump_buildsWindowAtTargetOnly() {
        val w = window()
        w.recenterSync(1)
        assertTrue("第1章窗口至少 2 页", w.pageCountInChapter(1) >= 2)
        // 远跳第 5 章：O(1)，不经过中间章节
        w.recenterSync(5)
        assertEquals("边界章窗口应为 [4,5]", listOf(4L, 5L), w.windowChapterIds)
        // 第 1、2 章被驱逐（内存有界）
        assertEquals("第1章应被驱逐", 0, w.pageCountInChapter(1))
        assertEquals("第2章应被驱逐", 0, w.pageCountInChapter(2))
    }

    @Test
    fun offsetOfPage_returnsHalfOpenSourceRange() {
        val content = "唯一段落"
        val chapter = Chapter(
            id = 99L,
            bookId = 1,
            title = "偏移测试",
            chapterIndex = 0,
            content = content
        )
        val w = BookWindow(
            chapters = listOf(chapter),
            style = PageStyle(
                body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
                cn = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
                title = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp),
                paragraphSpacingPx = 10f
            ),
            mode = "zh",
            contentWidthPx = 400f,
            contentHeightPx = 600f,
            measurer = measurer,
            backgroundMeasurer = { measurer }
        )
        w.recenterSync(99L)

        val firstContentPage = w.indexOf(99L, 0)
        assertNotNull("正文页应在窗口内", firstContentPage)
        val range = w.offsetOfPage(firstContentPage!!)
        assertNotNull("正文页应有原文范围", range)
        assertEquals("范围起点应包含", 0, range!!.first)
        assertEquals("半开范围末端不应包含", content.length - 1, range.last)
        assertTrue("半开范围不应包含 end", content.length !in range)
    }

    @Test
    fun enWindow_bilingualAtomicHoldsAcrossWindow() {
        val cnChapters = chapters.mapIndexed { i, ch ->
            ch.copy(
                translatedContent = (0 until 20).joinToString("\n") { j ->
                    "[${j + 1}] English translation of paragraph $j in chapter ${i + 1}."
                }
            )
        }
        val w = BookWindow(
            chapters = cnChapters,
            style = PageStyle(
                body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
                cn = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
                title = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp),
                paragraphSpacingPx = 10f
            ),
            mode = "en",
            contentWidthPx = 300f,
            contentHeightPx = 500f,
            measurer = measurer,
            backgroundMeasurer = { measurer }
        )
        w.recenterSync(3)
        // 窗口 [2,3,4]：第3章 20 段应完整、原子；第2/4章也可能部分入窗
        val chapter3Seen = HashSet<Int>()
        for (i in 0 until w.pageCount) {
            w.pageUnits(i).filterIsInstance<PageUnit.Para>().forEach { u ->
                assertTrue("双语对不应拆分（chapter=${u.chapterId} para=${u.paraIndex}）", u.pairHead)
                if (u.chapterId == 3L) {
                    assertTrue("第3章 paraIndex 不应重复", chapter3Seen.add(u.paraIndex))
                }
            }
        }
        assertEquals("第3章所有 20 段完整且不重", 20, chapter3Seen.size)
    }
}
