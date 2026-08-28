package com.vibereading.app.ui.reader.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.ui.reader.pagination.CjkJustifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * perCharHitTest 单测（Robolectric NATIVE：真实 StaticLayout 测量与 bbox）。
 *
 * 实测语义（真机同源代码路径）：getBoundingBox = [primary(off), primary(off+1)) 平铺
 * 单元格（含尾随字距）；getOffsetForPosition = 最近光标边界（宽字形右半边取右邻字符）。
 * 两端对齐拉伸行上，可见字间隙留在左字符单元格右缘，命中必须按视觉间隙中点切分，
 * 否则间隙内点击系统性选中左字符（即两端对齐行选词错位 bug）。
 * 断言结构化（从实测单元格与 charStretchPx 推导边界），不 pin 易变像素值。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34, 35])
class TextSelectionHitTest {

    private lateinit var measurer: TextMeasurer
    private val style = TextStyle(fontSize = 32.sp, lineHeight = 48.sp)
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

    private fun lineMidY(layout: TextLayoutResult, line: Int): Float =
        (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f

    /**
     * 9 字中文段落：104px 内容宽每行 3 字（96px），余量 8px 均摊到逐字间隙。
     * 返回排版结果（Start 渲染口径）与逐字符拉伸量。
     */
    private fun stretchedLayout(): Pair<TextLayoutResult, FloatArray> {
        val text = "一二三四五六七八九"
        val justifiedText = CjkJustifier.annotateDetailed(text, justified, 104, measurer)
        assertTrue("应接管", justifiedText.tookOver)
        assertTrue("应生成字距 span", justifiedText.annotated.spanStyles.isNotEmpty())
        val layout = measure(justifiedText.annotated, justified.copy(textAlign = TextAlign.Start), 104)
        assertTrue("应产生多行", layout.lineCount >= 2)
        return layout to justifiedText.charStretchPx
    }

    @Test
    fun stretchedLine_stretchReported_perChar() {
        val (layout, stretch) = stretchedLayout()
        assertEquals("长度应等于文本长度", 9, stretch.size)
        // 行 0：3 字 2 间隙，期望每字拉伸 = (拉伸行右缘 − 自然行右缘) / 间隙数
        val natural = measure(AnnotatedString("一二三四五六七八九"), style.copy(textAlign = TextAlign.Start), 104)
        val expected = (layout.getLineRight(0) - natural.getLineRight(0)) / 2f
        assertTrue(expected > 1f)
        assertEquals(expected, stretch[0], 1.6f)
        assertEquals(expected, stretch[1], 1.6f)
        assertEquals("行末字符无尾随字距", 0f, stretch[2], 0f)
    }

    @Test
    fun stretchedLine_gapSplitAtVisualMidpoint() {
        val (layout, stretch) = stretchedLayout()
        val d = stretch[0]
        assertTrue("拉伸间隙应可分辨以暴露偏左问题：d=$d", d > 1f)
        val y = lineMidY(layout, 0)
        // 行 0 前 2 个字符的单元格右缘各带一个宽度 d 的可见拉伸间隙
        for (off in 0..1) {
            val cellRight = layout.getBoundingBox(off).right
            // 间隙偏左（距右字形更远）→ 左字符
            assertEquals(off, perCharHitTest(layout, Offset(cellRight - d * 0.75f, y), off, stretch))
            // 间隙偏右（紧贴右字形）→ 右字符（bug 复现断言：单元格语义会返回左字符）
            assertEquals(
                off + 1,
                perCharHitTest(layout, Offset(cellRight - d * 0.25f, y), off, stretch)
            )
        }
    }

    @Test
    fun stretchedLine_glyphCenter_hitsThatChar() {
        val (layout, stretch) = stretchedLayout()
        val y = lineMidY(layout, 0)
        val lineEnd = layout.getLineEnd(0, visibleEnd = true)
        for (off in layout.getLineStart(0) until lineEnd) {
            val bbox = layout.getBoundingBox(off)
            assertEquals(off, perCharHitTest(layout, Offset((bbox.left + bbox.right) / 2f, y), off, stretch))
        }
    }

    @Test
    fun stretchedLine_tapOutsideLineEdges_clampsToNearestEndChar() {
        val (layout, stretch) = stretchedLayout()
        val y = lineMidY(layout, 0)
        val lastOff = layout.getLineEnd(0, visibleEnd = true) - 1
        assertEquals(0, perCharHitTest(layout, Offset(-100f, y), 0, stretch))
        assertEquals(lastOff, perCharHitTest(layout, Offset(200f, y), lastOff, stretch))
    }

    @Test
    fun naturalLayout_exactCellBoundaries() {
        // 未拉伸行（无 justify）：d=0，边界即单元格右缘，行为与单元格语义一致
        val text = "自然排版不拉伸的中文段落，命中测试应保持精确的字符归属。"
        val natural = measure(AnnotatedString(text), style.copy(textAlign = TextAlign.Start), 104)
        val y = lineMidY(natural, 0)
        val lineEnd = natural.getLineEnd(0, visibleEnd = true)
        for (off in natural.getLineStart(0) until lineEnd - 1) {
            val cellRight = natural.getBoundingBox(off).right
            assertEquals(off, perCharHitTest(natural, Offset(cellRight - 0.5f, y), off))
            assertEquals(off + 1, perCharHitTest(natural, Offset(cellRight + 0.5f, y), off + 1))
            val bbox = natural.getBoundingBox(off)
            assertEquals(off, perCharHitTest(natural, Offset((bbox.left + bbox.right) / 2f, y), off))
        }
    }

    @Test
    fun englishSpaceLine_gapTap_splitsAtMidpoint() {
        // 英文空格行：拉伸落在空格字符之后，词间隙中点右侧点击应命中下一个词首字母
        val text = "some english words stretched across lines with word gaps here"
        val width = 200
        val result = CjkJustifier.annotateDetailed(text, justified, width, measurer)
        assertTrue("英文行应接管", result.tookOver)
        val layout = measure(result.annotated, justified.copy(textAlign = TextAlign.Start), width)
        assertTrue("应产生多行", layout.lineCount >= 2)
        val y = lineMidY(layout, 0)
        // 在行 0 找第一个空格字符，其单元格右缘之后有宽度 d 的拉伸间隙
        val lineEnd = layout.getLineEnd(0, visibleEnd = true)
        val spaceOff = (layout.getLineStart(0) until lineEnd - 1).first { text[it] == ' ' }
        val d = result.charStretchPx[spaceOff]
        assertTrue("空格间隙应有拉伸：d=$d", d > 1f)
        val cellRight = layout.getBoundingBox(spaceOff).right
        // 间隙偏左 → 仍是空格单元格（findWordBoundary 会过滤空白段）
        assertEquals(spaceOff, perCharHitTest(layout, Offset(cellRight - d * 0.75f, y), spaceOff, result.charStretchPx))
        // 间隙偏右（贴近下一词首字母）→ 下一个字符
        assertEquals(
            spaceOff + 1,
            perCharHitTest(layout, Offset(cellRight - d * 0.25f, y), spaceOff, result.charStretchPx)
        )
    }
}
