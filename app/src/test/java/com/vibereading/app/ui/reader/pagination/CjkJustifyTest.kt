package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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

/**
 * CjkJustifier 单测（Robolectric NATIVE：真实 StaticLayout 换行与 span 测量）。
 * 在 sdk 34 与 35 双跑：34 语义精确、35 语义被自适应修正闭环吸收（与真机 Android 15 一致）。
 * 断言结构化（行边界、首末字符 bbox、span 范围、分页不变量），不 pin 易变像素值。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34, 35])
class CjkJustifyTest {

    private lateinit var measurer: TextMeasurer
    private val style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    private val justified = style.copy(textAlign = TextAlign.Justify)

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            Density(1f),
            LayoutDirection.Ltr,
            64
        )
    }

    private fun measure(text: AnnotatedString, style: TextStyle, width: Int) =
        measurer.measure(
            text = text,
            style = style,
            constraints = Constraints(minWidth = width, maxWidth = width)
        )

    /** 多行中文段落：16sp 字号、100px 内容宽（每行约 6 个汉字，余量明显）。 */
    private fun annotate(text: String, width: Int = 100, justifyLastLine: Boolean = false) =
        CjkJustifier.annotate(text, justified, width, measurer, justifyLastLine)

    // ── spike：span 级 Em letterSpacing 必须真实参与测量（整个方案的平台前提） ──

    @Test
    fun spike_spanLetterSpacing_affectsMeasuredWidth() {
        val text = "汉字汉字"
        val width = 1000 // 远大于自然行宽，不触发换行
        val plain = measure(AnnotatedString(text), style, width).getLineRight(0)
        val spaced = measure(
            buildAnnotatedString {
                append(text)
                addStyle(SpanStyle(letterSpacing = 0.25.em), 0, text.length - 1)
            },
            style,
            width
        ).getLineRight(0)
        assertTrue(
            "span 级字距应加宽测量结果：plain=$plain spaced=$spaced",
            spaced > plain + 1f
        )
    }

    // ── 拉伸几何：非末行首尾贴齐内容区，末行保持自然 ──

    @Test
    fun zh_justifiedLines_flushToContentWidth() {
        val text = "这是一段用来验证中文两端对齐效果的测试文本，需要足够长以触发多行换行排版效果。"
        val annotated = annotate(text)
        val layout = measure(annotated, justified, 100)
        assertTrue("应产生多行", layout.lineCount >= 3)
        assertTrue("应生成字距 span", annotated.spanStyles.isNotEmpty())
        for (line in 0 until layout.lineCount - 1) {
            val right = layout.getLineRight(line)
            val left = layout.getBoundingBox(layout.getLineStart(line)).left
            assertTrue("第 $line 行右缘应贴齐内容宽：right=$right cw=100", kotlin.math.abs(right - 100f) < 1.5f)
            assertTrue("第 $line 行左缘应贴齐左边距：left=$left", kotlin.math.abs(left) < 0.5f)
        }
    }

    @Test
    fun zh_lastLine_notStretched() {
        val text = "这是一段用来验证中文两端对齐效果的测试文本，末行较短保持自然宽度不被拉伸。"
        val layout = measure(annotate(text), justified, 100)
        val last = layout.lineCount - 1
        assertTrue(
            "末行不应拉伸到内容宽：right=${layout.getLineRight(last)}",
            layout.getLineRight(last) < 100f - 10f
        )
    }

    @Test
    fun zh_justifyLastLine_stretchesChunkLastLine() {
        // 模拟跨页切分片段 c1：取原文前 N-1 行，末行 = 原倒数第 2 行（满行，残差 ≤1 字）
        val full = "这是一段用来验证中文两端对齐效果的测试文本，需要足够长以触发多行换行排版，这段内容在本页之后还会在下一页继续出现。"
        val natural = measure(AnnotatedString(full), justified.copy(textAlign = TextAlign.Start), 100)
        assertTrue("原文应产生多行", natural.lineCount >= 3)
        val splitAt = natural.getLineEnd(natural.lineCount - 2, visibleEnd = true)
        val chunk = full.substring(0, splitAt)
        // 片段末行在下一页延续，仍需拉伸
        val layout = measure(annotate(chunk, justifyLastLine = true), justified, 100)
        val last = layout.lineCount - 1
        assertTrue(
            "延续片段末行应拉伸到内容宽：right=${layout.getLineRight(last)}",
            kotlin.math.abs(layout.getLineRight(last) - 100f) < 1.5f
        )
    }

    // ── 不变量：换行、页高、文本、偏移完全不受影响 ──

    @Test
    fun lineBreaks_identicalToNaturalLayout() {
        val text = "两端对齐不允许改变断行位置，任何一行的起点偏移都必须与自然排版完全一致，否则分页与渲染会错位。"
        val annotated = annotate(text)
        val natural = measure(AnnotatedString(text), style.copy(textAlign = TextAlign.Start), 100)
        val justifiedLayout = measure(annotated, justified, 100)
        assertEquals("行数不应变化", natural.lineCount, justifiedLayout.lineCount)
        for (line in 0 until natural.lineCount) {
            assertEquals(
                "第 $line 行起点偏移不应变化",
                natural.getLineStart(line),
                justifiedLayout.getLineStart(line)
            )
            assertEquals(
                "第 $line 行高度不应变化",
                natural.getLineBottom(line),
                justifiedLayout.getLineBottom(line),
                0f
            )
        }
    }

    @Test
    fun plainText_unchanged() {
        val text = "选词、查词与解释依赖 UTF-16 偏移，字距 span 不允许改变任何字符。"
        assertEquals(text, annotate(text).text)
    }

    // ── 门控：不应生成 span 的场景 ──

    @Test
    fun singleLineParagraph_noSpans() {
        val text = "单行短段。" // 5 字 × 16px = 80px < 100px，单行
        assertTrue(annotate(text).spanStyles.isEmpty())
    }

    @Test
    fun englishText_noSpans() {
        val text = "English words are justified by the platform inter-word mode already."
        assertTrue(annotate(text, width = 150).spanStyles.isEmpty())
    }

    @Test
    fun spaceContainingLines_noSpans() {
        // 含空格的行交给平台 inter-word 对齐，不生成字距 span
        val text = "汉字 汉字 汉字 汉字 汉字 汉字 汉字 汉字 汉字 汉字 汉字 汉字 汉字"
        val annotated = annotate(text, width = 120)
        val natural = measure(AnnotatedString(text), style.copy(textAlign = TextAlign.Start), 120)
        annotated.spanStyles.forEach { span ->
            val line = natural.getLineForOffset(span.start)
            val lineText = text.substring(natural.getLineStart(line), natural.getLineEnd(line, visibleEnd = true))
            assertTrue("含空格的行不应生成字距 span：$lineText", !lineText.contains(' '))
        }
    }

    @Test
    fun styleNotJustify_returnsPlain() {
        val text = "这段文本样式不是两端对齐，不应有任何字距 span 生成。"
        val annotated = CjkJustifier.annotate(text, style, 100, measurer)
        assertTrue(annotated.spanStyles.isEmpty())
        assertEquals(text, annotated.text)
    }

    // ── en 模式未译回退（中文书）：中文原文用 en-body 样式渲染，同样两端对齐 ──

    @Test
    fun enFallback_chineseText_justified() {
        val text = "en 模式译文未就绪时回退中文原文，这段文本需要足够长以触发多行换行排版验证两端对齐。"
        val bodyEn = justified.copy(fontFamily = FontFamily.Default)
        val annotated = CjkJustifier.annotate(text, bodyEn, 100, measurer)
        val layout = measure(annotated, bodyEn, 100)
        assertTrue("应产生多行", layout.lineCount >= 3)
        assertTrue("应生成字距 span", annotated.spanStyles.isNotEmpty())
        for (line in 0 until layout.lineCount - 1) {
            assertTrue(
                "第 $line 行右缘应贴齐内容宽：right=${layout.getLineRight(line)}",
                kotlin.math.abs(layout.getLineRight(line) - 100f) < 1.5f
            )
        }
    }

    // ── 段内换行短行（如「内容简介：」）：余量超过一字宽，不得拉伸，保持自然 ──

    @Test
    fun inlineNewlineShortLine_notStretched() {
        val text = "内容简介：\n一个武者主宰的地球，人类和野兽都开始了新的进化，残酷的生死战斗从此揭开。"
        val annotated = annotate(text)
        val layout = measure(annotated, justified, 100)
        assertTrue("应产生多行", layout.lineCount >= 2)
        assertTrue("满行应生成字距 span", annotated.spanStyles.isNotEmpty())
        // 行 0（「内容简介：」）余量远超一字宽：不应被拉满到内容宽
        assertTrue(
            "段内换行的短行不应拉伸：right=${layout.getLineRight(0)}",
            layout.getLineRight(0) < 100f - 8f
        )
        // 其余非末行应贴齐
        for (line in 1 until layout.lineCount - 1) {
            assertTrue(
                "第 $line 行右缘应贴齐内容宽：right=${layout.getLineRight(line)}",
                kotlin.math.abs(layout.getLineRight(line) - 100f) < 1.5f
            )
        }
    }

    // ── 真满行缺 1-2 字（如行尾孤字断行「…一道|离开。」）：余量在间隙预算内，应拉满 ──

    @Test
    fun nearFullLine_twoCharShortfall_justified() {
        val text = "罗峰笑了笑，就和自己的同学一道离开。" // 18 字 = 288px
        val width = 280 // 行 0 放得下前 17 字（272px），缺最后一个字（16px ≈ 1 字），属真满行
        val annotated = annotate(text, width = width)
        val layout = measure(annotated, justified, width)
        assertTrue("应产生多行", layout.lineCount >= 2)
        assertTrue("真满行应生成字距 span", annotated.spanStyles.isNotEmpty())
        assertTrue(
            "缺 1 字的真满行应拉满：right=${layout.getLineRight(0)}",
            kotlin.math.abs(layout.getLineRight(0) - width.toFloat()) < 1.5f
        )
    }

    // ── 分页不变量：justify 开启（带 span）与关闭（自然排版）页数与页高一致 ──

    @Test
    fun pagination_unchangedByJustifySpans() {
        val longPara = (0 until 60).joinToString("") { "第${it}段中文内容需要足够长以触发跨页切分排版。" }
        fun paginate(align: TextAlign): List<TextPage> {
            val pageStyle = PageStyle(
                body = style.copy(textAlign = align),
                cn = style, title = style, paragraphSpacingPx = 10f
            )
            val items = listOf(
                FlowItem.Title(1L, null, "第一章", 2),
                FlowItem.Para(1L, 0, longPara, null)
            )
            return ChapterPaginator(
                1L, items, pageStyle, "zh",
                contentWidthPx = 100f, contentHeightPx = 200f, measurer = measurer
            ).pages
        }
        val justifiedPages = paginate(TextAlign.Justify)
        val naturalPages = paginate(TextAlign.Start)
        assertEquals("页数不应变化", naturalPages.size, justifiedPages.size)
        justifiedPages.forEachIndexed { i, page ->
            assertEquals("第 $i 页内容高度不应变化", naturalPages[i].usedHeightPx, page.usedHeightPx, 0.01f)
        }
        // 切段拼接不丢字（回归 ADR-004）
        val all = justifiedPages.flatMap { p -> p.units.map { (it as? PageUnit.Para)?.cnText.orEmpty() } }
        assertEquals(longPara, all.joinToString(""))
    }
}