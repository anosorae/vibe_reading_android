package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
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
 * Android 15 回归单测：非零字间距会使平台废弃英文 inter-word 两端对齐（右缘缺一个词宽）。
 * [CjkJustifier.adjustLatinTextStyle] 对无 CJK 的 Justify 文本剥离字间距后应恢复贴齐；
 * 含 CJK 的文本必须保留字间距（逐字拉伸由 CjkJustifier 负责，不受平台回归影响）。
 * 镜像真机参数（17sp、密度 2.625、cw=932）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LatinJustifyLetterSpacingTest {

    private lateinit var measurer: TextMeasurer

    private val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 17.sp,
        lineHeight = 27.2.sp,
        textAlign = TextAlign.Justify
    )

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            Density(2.625f),
            LayoutDirection.Ltr,
            64
        )
    }

    private val english = "The quick brown fox jumps over the lazy dog. " +
        "Pack my box with five dozen liquor jugs. " +
        "How vexingly quick daft zebras jump. " +
        "Sphinx of black quartz judge my vow. " +
        "Waltz, bad nymph, for quick jigs vex. " +
        "Glib jocks quiz nymph to vex dwarf."

    private fun measure(text: String, style: TextStyle, width: Int = 932): TextLayoutResult =
        measurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(minWidth = width, maxWidth = width)
        )

    private fun shortLineCount(layout: TextLayoutResult, cw: Int): Int {
        var short = 0
        for (line in 0 until layout.lineCount - 1) {
            if (cw - layout.getLineRight(line) > 1.25f) short++
        }
        return short
    }

    // ── adjustLatinTextStyle 契约（纯函数） ──

    @Test
    fun adjustLatin_stripsLetterSpacing_onlyForLatinJustify() {
        // 无 CJK + Justify + 非零字间距 → 剥离为 0
        val stripped = CjkJustifier.adjustLatinTextStyle(english, body.copy(letterSpacing = 0.02f.em))
        assertEquals(0f, stripped.letterSpacing.value, 0.0001f)

        // 含 CJK → 保留字间距（逐字拉伸路径不受平台回归影响）
        val cjk = "中文段落 The quick brown fox 混排"
        val kept = CjkJustifier.adjustLatinTextStyle(cjk, body.copy(letterSpacing = 0.02f.em))
        assertEquals(0.02f, kept.letterSpacing.value, 0.0001f)

        // 非 Justify（Start/Center）→ 不动
        val start = body.copy(textAlign = TextAlign.Start, letterSpacing = 0.02f.em)
        assertTrue(CjkJustifier.adjustLatinTextStyle(english, start) === start)

        // 字间距已为 0 / Unspecified → 原对象引用不动（避免无效 copy）
        val zero = body.copy(letterSpacing = 0f.em)
        assertTrue(CjkJustifier.adjustLatinTextStyle(english, zero) === zero)
    }

    // ── 集成：剥离后英文 Justify 行右缘贴齐（Android 15 上修复平台回归） ──

    @Test
    fun sdk35_latinJustify_withLetterSpacing_adjustedStyle_edgesFlush() {
        val withLs = body.copy(letterSpacing = 0.02f.em)
        // 未剥离：平台 inter-word 失效，右缘不贴齐（复现真机 ≈30px 缺口）
        val broken = measure(english, withLs)
        assertTrue("剥离前应存在不贴齐的短行", shortLineCount(broken, 932) > 0)

        // 剥离字间距后：右缘贴齐内容宽
        val adjusted = CjkJustifier.adjustLatinTextStyle(english, withLs)
        val fixed = measure(english, adjusted)
        assertEquals(0, shortLineCount(fixed, 932))
        // 断行数保持一致（剥离只移除字间距，不改变换行决策的确定性由分页/渲染同源保证）
        assertEquals(broken.lineCount, fixed.lineCount)
    }
}