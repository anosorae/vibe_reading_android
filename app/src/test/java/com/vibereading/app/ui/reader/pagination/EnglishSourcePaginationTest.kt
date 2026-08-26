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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 英文原版书排版单测（ADR-003 插槽互换）：
 * - buildChapterItems 按原文语言把中/英文侧填入 cnText/enText，offset 恒指原文范围；
 * - en 模式：英文原文正文 + 中文译文弹窗数据（双语对只在两侧都在时成立）；
 * - en 模式译文未就绪：按单语英文原文排版（无气泡）；
 * - zh 模式译文未就绪：回退英文原文（对称于中文书 en 模式回退中文原文）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EnglishSourcePaginationTest {

    private lateinit var measurer: TextMeasurer

    private val body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp)
    private val cn = body.copy(fontSize = 14.sp, lineHeight = 21.sp)
    private val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            androidx.compose.ui.unit.Density(1f),
            LayoutDirection.Ltr,
            64
        )
    }

    private fun style() = PageStyle(body = body, cn = cn, title = title, paragraphSpacingPx = 10f, bottomJustify = false)

    private fun paginator(items: List<FlowItem>, mode: String, width: Float = 500f, height: Float = 800f) =
        ChapterPaginator(1L, items, style(), mode, width, height, measurer)

    private fun englishChapter(translated: String? = null): Chapter = Chapter(
        id = 5L, bookId = 9L, title = "Chapter One", chapterIndex = 0,
        content = "Hello world.\n\nNice day.",
        translatedContent = translated,
        status = if (translated == null) Chapter.STATUS_PENDING else Chapter.STATUS_DONE
    )

    // ── buildChapterItems 插槽互换 ──

    @Test
    fun buildChapterItems_englishSource_swapsSlots() {
        val chapter = englishChapter("[1] 你好世界。\n[2] 天气不错。")
        val items = BookWindow.buildChapterItems(chapter, "en")

        val para0 = items[1] as FlowItem.Para
        val para1 = items[2] as FlowItem.Para
        // 中文侧=译文，英文侧=原文
        assertEquals("你好世界。", para0.cnText)
        assertEquals("Hello world.", para0.enText)
        assertEquals("天气不错。", para1.cnText)
        assertEquals("Nice day.", para1.enText)
        // offset 恒指章节原文范围，不随插槽方向变成译文长度
        val content = chapter.content
        assertEquals(content.indexOf("Hello world."), para0.sourceStartOffset)
        assertEquals(content.indexOf("Hello world.") + "Hello world.".length, para0.sourceEndOffset)
    }

    @Test
    fun buildChapterItems_englishSource_untranslated_keepsEnglishSide() {
        val chapter = englishChapter(translated = null)
        val items = BookWindow.buildChapterItems(chapter, "en")

        val para = items[1] as FlowItem.Para
        assertEquals("", para.cnText)           // 中文侧未就绪为空
        assertEquals("Hello world.", para.enText) // 英文侧恒为原文
    }

    // ── en 模式 ──

    @Test
    fun enMode_englishSource_translated_pairCarriesBothSides() {
        val chapter = englishChapter("[1] 你好世界。\n[2] 天气不错。")
        val p = paginator(BookWindow.buildChapterItems(chapter, "en"), mode = "en")

        val unit = p.pages.flatMap { it.units }.filterIsInstance<PageUnit.Para>()[0]
        assertEquals("你好世界。", unit.cnText)   // 弹窗数据源=中文译文
        assertEquals("Hello world.", unit.enText) // 正文=英文原文
        assertTrue("双语两侧都在时按双语对排版", unit.enText != null && unit.cnText.isNotBlank())
    }

    @Test
    fun enMode_englishSource_untranslated_singleEnglishNoBubble() {
        val chapter = englishChapter(translated = null)
        val p = paginator(BookWindow.buildChapterItems(chapter, "en"), mode = "en")

        val unit = p.pages.flatMap { it.units }.filterIsInstance<PageUnit.Para>()[0]
        assertEquals("", unit.cnText)                     // 无中文侧 → 非双语对
        assertEquals("Hello world.", unit.enText)         // 正文=英文原文
        assertTrue("译文未就绪不应按双语对处理（无气泡）", unit.cnText.isBlank())
    }

    // ── zh 模式 ──

    @Test
    fun zhMode_englishSource_translated_bodyIsChineseTranslation() {
        val chapter = englishChapter("[1] 你好世界。\n[2] 天气不错。")
        val p = paginator(BookWindow.buildChapterItems(chapter, "en"), mode = "zh")

        val unit = p.pages.flatMap { it.units }.filterIsInstance<PageUnit.Para>()[0]
        assertEquals("你好世界。", unit.cnText)
        assertNull(unit.enText)
    }

    @Test
    fun zhMode_englishSource_untranslated_fallsBackToEnglishOriginal() {
        val chapter = englishChapter(translated = null)
        val p = paginator(BookWindow.buildChapterItems(chapter, "en"), mode = "zh")

        val unit = p.pages.flatMap { it.units }.filterIsInstance<PageUnit.Para>()[0]
        assertEquals("Hello world.", unit.cnText)  // 中文侧未就绪 → 正文回退英文原文
    }

    // ── 中文书回归：插槽方向不变 ──

    @Test
    fun zhBookSlots_unchanged() {
        val chapter = Chapter(
            id = 5L, bookId = 9L, title = "第一章", chapterIndex = 0,
            content = "中文原文。\n\n第二段原文。",
            translatedContent = "[1] English first.\n[2] English second.",
            status = Chapter.STATUS_DONE
        )
        val items = BookWindow.buildChapterItems(chapter, "zh")

        val para0 = items[1] as FlowItem.Para
        assertEquals("中文原文。", para0.cnText)      // cnText=中文原文
        assertEquals("English first.", para0.enText) // enText=英文译文
    }
}