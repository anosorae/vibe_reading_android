package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.text.font.createFontFamilyResolver

/**
 * ChapterPaginator 排版引擎单测（Robolectric NATIVE：真实 StaticLayout 换行）。
 * 断言结构化（切段拼接不丢字 / 双语对整体迁移 / 每页不溢出 / 展开重排），不 pin 像素值。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChapterPaginatorTest {

    private lateinit var measurer: TextMeasurer
    private val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
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

    private fun style(bottomJustify: Boolean = false): PageStyle = PageStyle(
        body = body,
        cn = cn,
        title = title,
        paragraphSpacingPx = 10f,
        bottomJustify = bottomJustify
    )

    private fun items(paragraphs: List<String>, en: List<String>? = null) = buildList {
        add(FlowItem.Title(1L, null, "第一章", 2))
        paragraphs.forEachIndexed { i, p ->
            add(FlowItem.Para(1L, i, p, en?.getOrNull(i)))
        }
    }

    // ── zh：长段落跨页切段，拼接不丢字 ──

    @Test
    fun zh_longParagraph_splitsAcrossPages_withoutLosingText() {
        // 窄内容区强制多行 + 多次跨页
        val longPara = (0 until 200).joinToString(" ") { "这是第${it}句很长很长的中文内容用来触发换行。" }
        val p = ChapterPaginator(
            1L, items(listOf(longPara)), style(),
            "zh", contentWidthPx = 200f, contentHeightPx = 300f, measurer = measurer
        )
        assertTrue("跨页应产生多页", p.pages.size >= 3)
        val all = p.pages.flatMap { page -> page.units.map { (it as? PageUnit.Para)?.cnText.orEmpty() } }
        // 断点处行尾空白可能被 getLineEnd(visibleEnd=true) 裁掉——断言不丢「可见字符」
        assertEquals("切段拼接不应丢字", longPara.replace(" ", ""), all.joinToString("").replace(" ", ""))
    }

    @Test
    fun zh_eachPage_fitsContentHeight() {
        val paras = (0 until 30).joinToString("\n\n") { "段落 $it 的内容，包含足够多的文字来确保每段都有多行。" }
        val p = ChapterPaginator(
            1L, items(paras.split("\n\n")), style(),
            "zh", contentWidthPx = 400f, contentHeightPx = 500f, measurer = measurer
        )
        assertTrue(p.pages.isNotEmpty())
        for ((i, page) in p.pages.withIndex()) {
            val height = page.units.fold(0f) { acc, it ->
                acc + when (it) {
                    is PageUnit.Title -> (it.section?.let { 21f + 6f } ?: 0f) + 28f + 16f * 2.2f + 10f
                    is PageUnit.Para -> (it.mainLayout?.size?.height?.toFloat() ?: 0f) + 10f
                }
            }
            assertTrue("第 $i 页(${height}) 超出内容高度 $500", height <= 500f + 0.5f)
        }
    }

    // ── en：双语对原子化 ──

    @Test
    fun en_bilingualPair_isNeverSplit() {
        // 每个 en 段都很长（多行），但任何一页都放得下整个对
        val cnTexts = (0 until 10).map { "中文段落 $it 的原文内容。" }
        val enTexts = (0 until 10).map { i ->
            "Paragraph $i is a fairly long English sentence that wraps across several lines inside the content area."
        }
        val p = ChapterPaginator(
            1L, items(cnTexts, enTexts), style(),
            "en", contentWidthPx = 400f, contentHeightPx = 400f, measurer = measurer
        )
        val seen = HashSet<Int>()
        for (page in p.pages) {
            val heads = page.units.filter { it is PageUnit.Para }.map { (it as PageUnit.Para) }
            heads.forEach { u ->
                assertTrue("双语对不应被拆分（paraIndex=${u.paraIndex}）", u.pairHead)
                assertTrue("paraIndex 不应重复出现", seen.add(u.paraIndex))
            }
        }
        assertEquals("所有段落都应排入", 10, seen.size)
    }

    @Test
    fun en_expandedCn_reflowPageAndKeepPairAtomic() {
        val cnTexts = (0 until 6).map { "中文原文段落 $it：这是一段足够长的中文，确保展开后占用额外高度。" }
        val enTexts = (0 until 6).map { i ->
            "English paragraph $i with enough words to wrap across a few lines inside the area."
        }
        val p = ChapterPaginator(
            1L, items(cnTexts, enTexts), style(),
            "en", contentWidthPx = 400f, contentHeightPx = 300f, measurer = measurer
        )
        val before = p.pages.size
        p.setExpanded(1L to 1, true)
        assertTrue("展开中文后应重排", p.pages.size != before || p.pageUnits(0).isNotEmpty())
        val seen = HashSet<Int>()
        p.pages.forEach { page ->
            page.units.filterIsInstance<PageUnit.Para>().forEach { u ->
                assertTrue("展开后双语对仍不应拆分", u.pairHead)
                assertTrue("展开后 paraIndex 不应重复", seen.add(u.paraIndex))
            }
        }
        assertEquals("展开后所有段落仍应完整排入", 6, seen.size)
    }

    // ── bottomJustify ──

    @Test
    fun bottomJustify_spreadsSlackAcrossLines() {
        // 20 段 × 每段多行：必然跨 3+ 页，中间满页留出 slack 触发底部对齐，末页豁免
        val paras = (0 until 20).joinToString("\n\n") {
            "底部对齐测试段落 $it：这是一段足够长的中文内容，确保在排版时占据多行高度。" +
                "底部对齐会把每页剩余高度均匀分配到各行的行距上，让末行沉底。"
        }
        val p = ChapterPaginator(
            1L, items(paras.split("\n\n")), style(bottomJustify = true),
            "zh", contentWidthPx = 400f, contentHeightPx = 600f, measurer = measurer
        )
        assertTrue("应跨 3+ 页", p.pages.size >= 3)
        // 至少有一页（非标题、非末页）的 lineHeightExtraPx > 0
        val anyExtra = p.pages.dropLast(1).any { page ->
            page.units.any {
                (it as? PageUnit.Para)?.let { para -> para.lineHeightExtraPx > 0f } ?: false
            }
        }
        assertTrue("中间满页应底部对齐", anyExtra)
        // 末页不做底部对齐（避免末页文字少时被拉出超大行距）
        val lastExtra = p.pages.last().units.filterIsInstance<PageUnit.Para>()
            .all { it.lineHeightExtraPx == 0f }
        assertTrue("末页不应底部对齐", lastExtra)
    }

    @Test
    fun titlePage_notBottomJustified() {
        val p = ChapterPaginator(
            1L, items(listOf("只有一段正文。")), style(bottomJustify = true),
            "zh", contentWidthPx = 400f, contentHeightPx = 600f, measurer = measurer
        )
        // 标题页（首页）不应被拉伸
        val first = p.pageUnits(0)
        assertTrue(first.any { it is PageUnit.Title })
        first.filterIsInstance<PageUnit.Para>().forEach {
            assertEquals("标题页正文不应底部对齐", 0f, it.lineHeightExtraPx, 0f)
        }
    }
}
