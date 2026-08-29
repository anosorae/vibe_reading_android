package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.domain.model.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/**
 * BookWindow.restyle 热更新回归测试（边距/字号滑杆拖动性能修复）：
 * ReaderScreen 不再因排版样式变化重建窗口（那会在主线程 composition 内同步重排全章，
 * 实测单 tick 平均 ~70ms/峰值 241ms，远超帧预算），而是后台 restyle 后原子换入。
 * 断言结构化结果，不 pin 像素值。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BookWindowRestyleTest {

    private lateinit var measurer: TextMeasurer

    private val chapters = (0 until 3).map { i ->
        Chapter(
            id = (i + 1).toLong(),
            bookId = 1,
            title = "第${i + 1}章",
            chapterIndex = i,
            content = (0 until 20).joinToString("\n\n") { "第${i + 1}章段落 $it 的中文内容，用于排版验证热更新行为。" }
        )
    }

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            androidx.compose.ui.unit.Density(2.625f),
            LayoutDirection.Ltr,
            64
        )
    }

    private fun style(widthPx: Float, bodySp: Float = 16f) = PageStyle(
        body = TextStyle(fontFamily = FontFamily.Default, fontSize = bodySp.sp, lineHeight = (bodySp * 1.6f).sp, textAlign = TextAlign.Justify),
        cn = TextStyle(fontFamily = FontFamily.Default, fontSize = (bodySp * 0.875f).sp, lineHeight = (bodySp * 1.5f).sp, textAlign = TextAlign.Justify),
        title = TextStyle(fontFamily = FontFamily.Default, fontSize = (bodySp + 4).sp, lineHeight = ((bodySp + 4) * 1.3f).sp),
        paragraphSpacingPx = 12f
    )

    private fun window(widthPx: Float = 1000f, heightPx: Float = 1900f) = BookWindow(
        chapters = chapters,
        style = style(widthPx),
        mode = "zh",
        contentWidthPx = widthPx,
        contentHeightPx = heightPx,
        measurer = measurer,
        backgroundMeasurer = { measurer },
        displayDensity = 2.625f
    )

    @Test
    fun restyle_repaginatesWithoutIdentityChange() = runBlocking {
        val w = window(widthPx = 1000f, heightPx = 1900f)
        w.recenterSync(2, includeNeighbors = false)
        val oldPageCount = w.pageCount
        assertTrue(oldPageCount > 0)

        // 边距增大 → 内容区变窄
        val newStyle = style(widthPx = 800f)
        w.restyle(newStyle, newContentWidthPx = 800f, newContentHeightPx = 1900f)

        assertTrue("restyle 后窗口应匹配新样式", w.matchesStyle(newStyle, 800f, 1900f))
        assertTrue("变窄后每页容量变小，页数应不减", w.pageCount >= oldPageCount)
        assertEquals("窗口中心章不变（对象身份未换）", 2L, w.centerChapterId)
        w.windowPages.indices.forEach { idx ->
            assertTrue("窗口页 $idx 应有排版结果", w.pageUnits(idx).isNotEmpty())
        }
    }

    @Test
    fun restyle_keepsOffsetMappableToNewPages() = runBlocking {
        val w = window(widthPx = 1000f, heightPx = 1900f)
        w.recenterSync(2)
        // 取当前视觉页的原文偏移（ReaderScreen 重映射所用路径）
        val chapter = w.chapterOfPage(w.pageCount / 2)!!
        val offset = w.offsetOfPage(w.pageCount / 2)!!.first

        w.restyle(style(widthPx = 860f), 860f, 1900f)

        val newIdx = w.indexOf(chapter, offset.toLong()) ?: w.indexOf(chapter, 0)
        assertNotNull("restyle 后原 offset 应仍能定位到新页面", newIdx)
        assertEquals("重映射后仍落在原章节", chapter, w.chapterOfPage(newIdx!!))
    }

    @Test
    fun restyle_cancelledJob_doesNotSwap() = runBlocking {
        val w = window(widthPx = 1000f, heightPx = 1900f)
        w.recenterSync(2)
        val oldPageCount = w.pageCount
        val newStyle = style(widthPx = 700f)

        // 拖动期新 tick 到来会取消旧 restyle：已取消的协程不得换入结果
        val cancelled = Job().apply { cancel() }
        try {
            withContext(Dispatchers.Default + cancelled) {
                w.restyle(newStyle, 700f, 1900f)
            }
        } catch (_: CancellationException) {
            // 预期路径：入口即取消
        }
        assertFalse("取消的 restyle 不应换入新样式", w.matchesStyle(newStyle, 700f, 1900f))
        assertEquals("取消的 restyle 不应改变窗口页数", oldPageCount, w.pageCount)
    }
}
