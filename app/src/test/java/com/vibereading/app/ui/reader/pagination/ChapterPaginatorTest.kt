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
import org.junit.Assert.assertFalse
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
 * 断言结构化（切段拼接不丢字 / 双语对整体迁移 / 每页不溢出），不 pin 像素值。
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
    fun zh_singleLineTooTall_keepsCompleteContinuation() {
        // 单行文本本身高于页面时无法按行切分，但正文仍必须完整保留。
        val completeText = "单行超高内容".repeat(40)
        val tallBody = body.copy(fontSize = 100.sp, lineHeight = 100.sp)
        val p = ChapterPaginator(
            chapterId = 1L,
            items = listOf(FlowItem.Para(1L, 0, completeText, null)),
            style = style().copy(body = tallBody, paragraphSpacingPx = 0f),
            mode = "zh",
            contentWidthPx = 100_000f,
            contentHeightPx = 50f,
            measurer = measurer
        )

        val rendered = p.pages.flatMap { page ->
            page.units.filterIsInstance<PageUnit.Para>().map { it.cnText }
        }.joinToString("")
        assertEquals("单行超高分支不应截断 continuation", completeText, rendered)
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
                    is PageUnit.Image -> it.displayHeightPx + 10f
                }
            }
            assertTrue("第 $i 页(${height}) 超出内容高度 $500", height <= 500f + 0.5f)
        }
    }

    // ── en：双语对按行切分填页（ADR-004），每个片段都带中文气泡数据源 ──

    @Test
    fun en_bilingualPair_splitsToFill_everyFragmentHasBubbleData() {
        // 每个 en 段占大半页（多行）：放不下剩余空间时按行切分填满当前页（ADR-004），
        // 每个带译文的片段都携带整段 cnText（点任一气泡弹出完整译文），
        // 英文切段拼接不丢字
        val cnTexts = (0 until 10).map { "中文段落 $it 的原文内容。" }
        val enTexts = (0 until 10).map { i ->
            ("Paragraph $i is a fairly long English sentence that wraps across several " +
                "lines inside the content area. ").repeat(4).trim()
        }
        val p = ChapterPaginator(
            1L, items(cnTexts, enTexts), style(),
            "en", contentWidthPx = 400f, contentHeightPx = 400f, measurer = measurer
        )
        assertTrue("应跨多页触发切分", p.pages.size >= 2)
        val enByPara = HashMap<Int, StringBuilder>()
        var fragments = 0
        for (page in p.pages) {
            page.units.filterIsInstance<PageUnit.Para>().forEach { u ->
                assertTrue(
                    "每个片段都应有气泡数据源（整段中文侧文本）paraIndex=${u.paraIndex}",
                    u.cnText.isNotBlank()
                )
                assertEquals("气泡数据源应为整段译文", cnTexts[u.paraIndex], u.cnText)
                fragments++
                enByPara.getOrPut(u.paraIndex) { StringBuilder() }.append(u.enText.orEmpty())
            }
        }
        assertTrue("双语对应被实际切分填页（片段数 $fragments）", fragments > 10)
        assertEquals("所有段落都应排入", 10, enByPara.size)
        enTexts.forEachIndexed { i, text ->
            assertEquals(
                "第 $i 段英文切段拼接不应丢字",
                text.replace(" ", ""),
                enByPara.getValue(i).toString().replace(" ", "")
            )
        }
    }

    // ── zh：切分填满页面（ADR-004）──

    @Test
    fun zh_paragraphs_fillPages_withoutLargeSlack() {
        // 多个中等长度段落：旧逻辑整段挪页会留下大片空白；新逻辑按行切分后，
        // 除末页外每页剩余高度应不足一行
        val paras = (0 until 20).joinToString("\n\n") {
            "填充测试段落 $it：这段中文内容足够长，在窄页面里会占据好几行的宽度，" +
                "确保段落本身超过单页剩余空间的概率足够高。"
        }
        val height = 500f
        val p = ChapterPaginator(
            1L, items(paras.split("\n\n")), style(),
            "zh", contentWidthPx = 400f, contentHeightPx = height, measurer = measurer
        )
        assertTrue("应跨多页", p.pages.size >= 3)
        for ((i, page) in p.pages.dropLast(1).withIndex()) {
            val slack = height - page.usedHeightPx
            assertTrue("第 $i 页剩余 ${slack}px 未按行切分填满（ADR-004）", slack <= 30f)
        }
    }

    @Test
    fun en_bilingualFragments_padCountedInMeasurement_pageHeightMatchesRender() {
        // 双语对上下 4dp padding 对每个带译文的片段（含续段）都必须计入测量：
        // 渲染端（BilingualParagraph/卷页位图）对所有片段加同一 padding，
        // 测量漏计会让实际渲染逐段超出 usedHeight，仿真卷页起手上下断层。
        // 段距置 0 排除干扰：usedHeight 应恰好等于 Σ(排版高 + 片段双语 padding)。
        val cnTexts = (0 until 10).map { "中文段落 $it 的原文内容。" }
        val enTexts = (0 until 10).map { i ->
            ("Paragraph $i is a fairly long English sentence that wraps across several " +
                "lines inside the content area. ").repeat(4).trim()
        }
        val p = ChapterPaginator(
            1L, items(cnTexts, enTexts), style().copy(paragraphSpacingPx = 0f),
            "en", contentWidthPx = 400f, contentHeightPx = 400f, measurer = measurer
        )
        assertTrue("应跨多页触发切分", p.pages.size >= 2)
        val pad = ReaderMetrics.bilingualPadPx(1f)
        var checkedPages = 0
        for ((i, page) in p.pages.withIndex()) {
            // 标题页高度单独构成，不在本断言范围
            if (page.units.any { it is PageUnit.Title }) continue
            checkedPages++
            // 复刻渲染端判定：cnText 与 enText 都非空的片段渲染双语 padding + 气泡
            val rendered = page.units.fold(0f) { acc, u ->
                val para = u as PageUnit.Para
                val bilingual = para.cnText.isNotBlank() && para.enText?.isNotBlank() == true
                acc + (para.mainLayout?.size?.height?.toFloat() ?: 0f) + (if (bilingual) pad else 0f)
            }
            assertEquals(
                "第 $i 页测量高度应含全部片段双语 padding（测量=渲染口径）",
                rendered, page.usedHeightPx, 0.5f
            )
            assertTrue(
                "第 $i 页渲染高度 ${rendered}px 不应超出内容区 $400px",
                rendered <= 400f + 0.5f
            )
        }
        assertTrue("应有不含标题的正文页参与断言", checkedPages > 0)
    }

    @Test
    fun splitFirstLastPage_realUsedNotReducedByPhantomSpacing() {
        // 以切分首片段结尾的页：排版与渲染都不带尾段距，
        // realUsed 不应虚减一个 paragraphSpacing（否则 slack 虚高、底部对齐过度拉伸）
        val longPara = (0 until 200).joinToString(" ") { "这是第${it}句很长很长的中文内容用来触发换行。" }
        val p = ChapterPaginator(
            1L, items(listOf(longPara)), style(),
            "zh", contentWidthPx = 400f, contentHeightPx = 500f, measurer = measurer
        )
        assertTrue("应跨多页", p.pages.size >= 3)
        var checked = 0
        val spacing = style().paragraphSpacingPx
        for (page in p.pages) {
            // 标题块高度单独构成，不在本断言范围
            if (page.units.any { it is PageUnit.Title }) continue
            // 复刻渲染端高度：每个单元排版高 + 段尾距（末单元与 splitFirst 不带）
            val lastIdx = page.units.indexOfLast { it is PageUnit.Para }
            val rendered = page.units.foldIndexed(0f) { idx, acc, u ->
                val para = u as? PageUnit.Para ?: return@foldIndexed acc
                val spacer = if (idx != lastIdx && !para.splitFirst) spacing else 0f
                acc + (para.mainLayout?.size?.height?.toFloat() ?: 0f) + spacer
            }
            val last = page.units.lastOrNull() as? PageUnit.Para ?: continue
            if (!last.splitFirst) continue
            checked++
            assertEquals(
                "splitFirst 结尾的页 realUsed 应等于渲染高度",
                rendered, page.usedHeightPx, 0.5f
            )
        }
        assertTrue("应存在以切分首片段结尾的页", checked > 0)
    }

    @Test
    fun zh_continuationChunk_hasNoFirstLineIndent() {
        // 跨页续排的续段顶格（无首行缩进），首片段保留缩进（ADR-004）
        val indented = style().copy(
            body = body.copy(textIndent = TextIndent(firstLine = 50.sp))
        )
        val longPara = (0 until 100).joinToString("") { "跨页续排缩进测试内容。" }
        val p = ChapterPaginator(
            1L, items(listOf(longPara)), indented,
            "zh", contentWidthPx = 300f, contentHeightPx = 200f, measurer = measurer
        )
        assertTrue("应跨页产生续段", p.pages.size >= 2)
        val firstPara = p.pages.first().units.filterIsInstance<PageUnit.Para>().first()
        assertFalse(firstPara.continuation)
        assertTrue(
            "首片段首行应带缩进（left=${firstPara.mainLayout!!.getBoundingBox(0).left}）",
            firstPara.mainLayout!!.getBoundingBox(0).left > 0f
        )
        val continuationUnits = p.pages.drop(1).flatMap { it.units }
            .filterIsInstance<PageUnit.Para>().filter { it.continuation }
        assertTrue("续页应存在顶格续段", continuationUnits.isNotEmpty())
        continuationUnits.forEach { u ->
            assertEquals("续段首行应顶格", 0f, u.mainLayout!!.getBoundingBox(0).left, 0.5f)
            // 续段文本不应以空白开头（否则渲染时出现伪缩进）
            assertFalse("续段文本不应以空白开头", u.cnText.first().isWhitespace())
        }
    }

    @Test
    fun en_continuationChunk_hasNoLeadingWhitespace() {
        // 英文行在词边界处换行，行尾空格不应留在续段开头造成伪缩进
        val cnTexts = (0 until 5).map { "中文段落 $it 原文。" }
        val enTexts = (0 until 5).map { i ->
            ("Paragraph $i contains enough English text to wrap across " +
                "multiple lines when the content area is narrow. ").repeat(6).trim()
        }
        val indented = style().copy(
            body = body.copy(textIndent = TextIndent(firstLine = 32.sp))
        )
        val p = ChapterPaginator(
            1L, items(cnTexts, enTexts), indented,
            "en", contentWidthPx = 300f, contentHeightPx = 300f, measurer = measurer
        )
        assertTrue("应跨页产生续段", p.pages.size >= 2)
        val continuationUnits = p.pages.drop(1).flatMap { it.units }
            .filterIsInstance<PageUnit.Para>().filter { it.continuation }
        assertTrue("续页应存在英文续段", continuationUnits.isNotEmpty())
        continuationUnits.forEach { u ->
            val enText = u.enText.orEmpty()
            if (enText.isNotBlank()) {
                assertFalse(
                    "英文续段不应以空白开头（实际='${enText.take(3)}'）",
                    enText.first().isWhitespace()
                )
            }
        }
    }

    // ── 拆分片段原文子区间：页区间互斥 + offset→页往返（进度保存/恢复的正确性前提） ──

    @Test
    fun zh_splitFragments_pageRangesDisjointAndRoundtrip() {
        // 回归：跨页拆分段落曾沿用整段 source 范围，续页与起始页区间完全相同 →
        // 保存的页首 offset 落在上一页，恢复时回退一页。修复后各页区间互斥且相接，
        // 每页页首 offset 必须映射回本页。
        val longPara = (0 until 200).joinToString("") { "跨页区间测试第${it}句，内容足够长以触发多次换行与跨页拆分。" }
        val p = ChapterPaginator(
            1L, listOf(FlowItem.Para(1L, 0, longPara, null)), style(),
            "zh", contentWidthPx = 200f, contentHeightPx = 300f, measurer = measurer
        )
        assertTrue("应跨 3+ 页", p.pages.size >= 3)
        var prevEnd: Int? = null
        for ((i, page) in p.pages.withIndex()) {
            val start = page.sourceStartOffset
            val end = page.sourceEndOffset
            assertTrue("第 $i 页应有原文范围", start != null && end != null)
            if (prevEnd != null) {
                assertEquals("相邻页区间应恰好相接（CJK 无行尾空白）", prevEnd, start)
            }
            prevEnd = end
            assertEquals("页首 offset=$start 应映射回第 $i 页", i, p.pageForOffset(start!!)!!)
        }
        assertEquals("段落范围应从段首起", 0, p.pages.first().sourceStartOffset)
        assertEquals("段落范围应覆盖到段尾", longPara.length, p.pages.last().sourceEndOffset)
        // 续段片段的 sourceStartOffset 应指向段内（非段首）——保存的是续页真实首字符
        val contStarts = p.pages.drop(1).flatMap { it.units }
            .filterIsInstance<PageUnit.Para>().filter { it.continuation }
            .map { it.sourceStartOffset }
        assertTrue("应存在续段片段", contStarts.isNotEmpty())
        contStarts.forEach { assertTrue("续段起点应大于段首（$it）", it > 0) }
    }

    @Test
    fun en_splitFragments_pageRangesDisjointAndRoundtrip() {
        // en 模式双语对跨页：片段 source 子区间互斥（英文行尾空白被裁掉时允许留出
        // 空白间隙，但不得重叠），页首 offset→页 往返不回退
        val enText = ("Bilingual offset mapping test paragraph with enough English words " +
            "to wrap across many lines in a narrow page. ").repeat(12).trim()
        val cnText = "中文译文段落。"
        val start = 100
        val p = ChapterPaginator(
            1L,
            listOf(
                FlowItem.Para(
                    1L, 0, cnText, enText,
                    sourceStartOffset = start, sourceEndOffset = start + enText.length
                )
            ),
            style(),
            "en", contentWidthPx = 300f, contentHeightPx = 300f, measurer = measurer
        )
        assertTrue("应跨 3+ 页", p.pages.size >= 3)
        var prevEnd: Int? = null
        for ((i, page) in p.pages.withIndex()) {
            val s = page.sourceStartOffset
            val e = page.sourceEndOffset
            assertTrue("第 $i 页应有原文范围", s != null && e != null)
            if (prevEnd != null) {
                assertTrue("页区间不得重叠（prevEnd=$prevEnd, start=$s）", s!! >= prevEnd!!)
            }
            prevEnd = e
            assertEquals("页首 offset=$s 应映射回第 $i 页", i, p.pageForOffset(s!!)!!)
        }
        assertEquals("首页区间从段首起", start, p.pages.first().sourceStartOffset)
        assertEquals("末页区间到段尾", start + enText.length, p.pages.last().sourceEndOffset)
        val contStarts = p.pages.drop(1).flatMap { it.units }
            .filterIsInstance<PageUnit.Para>().filter { it.continuation }
            .map { it.sourceStartOffset }
        assertTrue("应存在续段片段", contStarts.isNotEmpty())
        contStarts.forEach { assertTrue("续段起点应指向段内（$it）", it > start) }
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
