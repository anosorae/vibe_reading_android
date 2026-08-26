package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.parser.IllustrationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 插图排版单测（ADR-002 D5）：插图作为固定高度单元参与分页——放不下整图移下一页、
 * 超高整图缩到单页内、offset 范围包含插图段；BookWindow.buildChapterItems 正确发射 Image。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IllustrationPaginationTest {

    private lateinit var measurer: TextMeasurer

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            androidx.compose.ui.unit.Density(1f),
            LayoutDirection.Ltr,
            64
        )
    }

    private val style = PageStyle(
        body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
        cn = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        paragraphSpacingPx = 10f,
        bottomJustify = false
    )

    private fun paginator(items: List<FlowItem>, contentWidthPx: Float, contentHeightPx: Float) =
        ChapterPaginator(1L, items, style, "zh", contentWidthPx, contentHeightPx, measurer)

    // ── 整图适配：超高图缩到单页内 ──

    @Test
    fun oversizedImage_fitsSinglePage() {
        // 2000x2000 的图放进 300x400 内容区：等比缩小后高不超过页高
        val p = paginator(
            listOf(FlowItem.Image(1L, 0, "1/a.jpg", 2000, 2000)),
            contentWidthPx = 300f, contentHeightPx = 400f,
        )
        assertEquals(1, p.pages.size)
        val unit = p.pages[0].units.single() as PageUnit.Image
        assertTrue("宽不超内容区", unit.displayWidthPx <= 300f + 0.5f)
        assertTrue("高不超内容区（整图适配单页，不跨页拆条带）", unit.displayHeightPx <= 400f + 0.5f)
        assertEquals("等比缩放保持宽高一致", 300f, unit.displayWidthPx, 0.01f)
        assertEquals(300f, unit.displayHeightPx, 0.01f)
    }

    // ── 放不下：整图移下一页，绝不拆分 ──

    @Test
    fun imageNotFittingRemaining_movesToNextPage_whole() {
        val para = (0 until 40).joinToString("") { "字" } // 一行文字占 ~24px 高
        val items = listOf(
            FlowItem.Para(1L, 0, para, null),
            FlowItem.Para(1L, 1, para, null),
            FlowItem.Para(1L, 2, para, null),
            FlowItem.Image(1L, 3, "1/b.jpg", 300, 300)
        )
        val p = paginator(items, contentWidthPx = 300f, contentHeightPx = 100f)
        val imagePages = p.pages.filter { page -> page.units.any { it is PageUnit.Image } }
        assertEquals("插图独占一页", 1, imagePages.size)
        val imagePageUnits = imagePages[0].units
        assertEquals("插图页上不应混排被截断的文字", 1, imagePageUnits.size)
        assertTrue(imagePageUnits[0] is PageUnit.Image)
        // 插图完整保留（显示尺寸即适配结果，无裁剪）
        val img = imagePageUnits[0] as PageUnit.Image
        // fitImage(300x300, maxW=300, maxH=100)：scale=min(1,1/3) → 显示 100x100
        assertEquals(100f, img.displayHeightPx, 0.01f)
        assertEquals(100f, img.displayWidthPx, 0.01f)
    }

    // ── offset 范围：插图段参与页范围计算（进度恢复定位）──

    @Test
    fun imageUnit_offsetsIncludedInPageRange() {
        val linkText = IllustrationLink.build("1/c.jpg", 600, 400)
        val items = listOf(
            FlowItem.Title(1L, null, "章", 2),
            FlowItem.Image(1L, 0, "1/c.jpg", 600, 400, sourceStartOffset = 10, sourceEndOffset = 46)
        )
        val p = paginator(items, contentWidthPx = 500f, contentHeightPx = 800f)
        val page = p.pages[0]
        assertEquals(10, page.sourceStartOffset)
        assertEquals(46, page.sourceEndOffset)
        // pageForOffset 能定位到插图所在页
        assertEquals(0, p.pageForOffset(20))
    }

    // ── BookWindow.buildChapterItems 发射 FlowItem.Image ──

    @Test
    fun buildChapterItems_emitsImageFlowItem() {
        val link = IllustrationLink.build("9/x.png", 800, 600)
        val chapter = Chapter(
            id = 5L, bookId = 9L, title = "第一章", chapterIndex = 0,
            content = "正文段落。\n\n$link"
        )
        val items = BookWindow.buildChapterItems(chapter)
        assertEquals(3, items.size) // Title + 正文 Para + 插图 Image
        assertTrue(items[1] is FlowItem.Para)
        val image = items[2] as FlowItem.Image
        assertEquals("9/x.png", image.path)
        assertEquals(800, image.imageWidthPx)
        assertEquals(600, image.imageHeightPx)
        assertTrue("插图段 offset 指向链接文本本身", image.sourceEndOffset > image.sourceStartOffset)
    }
}
