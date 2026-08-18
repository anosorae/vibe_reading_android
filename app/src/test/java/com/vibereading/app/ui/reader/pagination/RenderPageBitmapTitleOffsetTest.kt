package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.ReaderPageGeometry
import com.vibereading.app.ui.reader.ReaderPalette
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
 * 回归测试：卷页位图标题块的起始偏移必须与 Compose 页一致。
 *
 * Bug 背景：renderPageBitmap 在无卷名（section == null）时仍无条件累加
 * `SECTION_TITLE_GAP_DP`（卷名→章节名的 8dp 间距），而 Compose 页
 * `ReadingChapterTitle` 与排版器 `ChapterPaginator.measureTitleHeight`
 * 都只在卷名存在时才计入该间距。结果：无卷名章节的首页位图标题被凭空
 * 顶低 ~8dp，触发仿真卷页的瞬间整页文字向下跳动。
 *
 * 断言的是结构事实：无卷名时位图首个文字行必须紧贴内容区顶
 * （距内容顶明显小于一个 8dp 间距），不依赖具体字体韵脚；密度 2.625 放大余量。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderPageBitmapTitleOffsetTest {

    private lateinit var measurer: TextMeasurer
    private val density = 2.625f
    private val densityObj = Density(density)

    private val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
    private val cn = body.copy(fontSize = 14.sp, lineHeight = 21.sp)
    private val title = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold
    )

    @Before
    fun setUp() {
        measurer = TextMeasurer(
            createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            densityObj,
            LayoutDirection.Ltr,
            64
        )
    }

    private fun chapter(section: String?) = Chapter(
        id = 1L,
        bookId = 1,
        title = "第一章 测试标题",
        section = section,
        chapterIndex = 0,
        content = "第一段。\n\n第二段。\n\n第三段。"
    )

    private fun renderFirstPageBitmap(chapter: Chapter): Bitmap {
        val style = PageStyle(
            body = body,
            cn = cn,
            title = title,
            paragraphSpacingPx = 26.25f
        )
        val window = BookWindow(
            chapters = listOf(chapter),
            style = style,
            mode = "zh",
            contentWidthPx = 800f,
            contentHeightPx = 1500f,
            measurer = measurer,
            backgroundMeasurer = { measurer },
            displayDensity = density
        )
        window.recenterSync(1L)
        val geometry = ReaderPageGeometry.of(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarPx = 100,
            navBarPx = 100,
            padHPx = 40,
            padVPx = 80
        )
        return checkNotNull(
            renderPageBitmap(
                window = window,
                page = 0, // 含章节标题的首页
                mode = "zh",
                pageStyle = style,
                geometry = geometry,
                palette = ReaderPalette.of(isDark = false),
                density = densityObj,
                bgColorArgb = 0xFFFFF8F0.toInt(),
                sectionColorArgb = 0xFF8B5E3C.toInt(),
                measurer = measurer
            )
        )
    }

    /** 返回位图中第一个「明显比背景深」的文字行（标题字形顶）。阈值取 10 以覆盖 14sp 小号卷名。 */
    private fun firstDarkRow(bitmap: Bitmap, threshold: Int = 10): Int? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            var sum = 0
            for (x in 0 until w) {
                val v = px[y * w + x]
                val luma = ((v shr 16 and 0xFF) + (v shr 8 and 0xFF) + (v and 0xFF)) / 3
                if (luma < 235) sum++
            }
            if (sum > threshold) return y
        }
        return null
    }

    @Test
    fun noSection_titleStartsAtContentTop_withoutPhantomGap() {
        val contentTop = 100 + 80 // statusBarPx + padVPx
        val gapPx = ReaderMetrics.SECTION_TITLE_GAP_DP * density
        val bmp = renderFirstPageBitmap(chapter(section = null))

        val firstDark = firstDarkRow(bmp)
        assertTrue("位图应有文字行", firstDark != null)
        // 无卷名时标题必须紧贴内容区顶：起始行距内容顶的距离必须明显小于一个 8dp 间距。
        // 若仍无条件累加卷名→章节名间距，起始行会落在 contentTop + gap 之后 → 断言变红。
        assertTrue(
            "无卷名时位图标题起始行距内容顶应 << 8dp 间距（不得凭空下移）",
            firstDark!! - contentTop < gapPx
        )
    }

    @Test
    fun withSection_sectionTextStartsAtContentTop() {
        val contentTop = 100 + 80
        val gapPx = ReaderMetrics.SECTION_TITLE_GAP_DP * density
        val bmp = renderFirstPageBitmap(chapter(section = "第一卷"))

        val firstDark = firstDarkRow(bmp)
        assertTrue("位图应有文字行", firstDark != null)
        // 有卷名时首行是卷名文本，同样紧贴内容顶（卷名前不应再有空档）
        assertTrue(
            "有卷名时首行卷名应紧贴内容顶",
            firstDark!! - contentTop < gapPx
        )
    }

    @Test
    fun enMode_bilingualPairSpacingMatchesComposeIntegerLayout() {
        // 回归：位图段间距必须与 Compose 布局逐项一致（roundToPx 整像素口径）。
        // 旧实现用浮点累加（4dp*2.625=10.5、段距 26.25），每段比真实页少 1~1.5px，
        // 整页累积后正文逐段向上偏移（亚像素漂移）。此处断言相邻两段首行墨迹的
        // 间距 == h1 + 2*round(4dp) + round(段距)，其中 h1 直接取排版器的布局高度。
        val enChapter = Chapter(
            id = 1L,
            bookId = 1,
            title = "第一章",
            section = null,
            chapterIndex = 0,
            content = "第一段。\n\n第二段。",
            translatedContent = "[1] Hello world.\n[2] Second line."
        )
        val style = PageStyle(
            body = body,
            cn = cn,
            title = title,
            paragraphSpacingPx = 26.25f,
            bottomJustify = false // 关闭底部对齐：lineHeightExtra=0，位图直接用 mainLayout
        )
        val window = BookWindow(
            chapters = listOf(enChapter),
            style = style,
            mode = "en",
            contentWidthPx = 800f,
            contentHeightPx = 1500f,
            measurer = measurer,
            backgroundMeasurer = { measurer },
            displayDensity = density
        )
        window.recenterSync(1L)
        val geometry = ReaderPageGeometry.of(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarPx = 100,
            navBarPx = 100,
            padHPx = 40,
            padVPx = 80
        )
        val bmp = checkNotNull(
            renderPageBitmap(
                window = window,
                page = 0,
                mode = "en",
                pageStyle = style,
                geometry = geometry,
                palette = ReaderPalette.of(isDark = false),
                density = densityObj,
                bgColorArgb = 0xFFFFF8F0.toInt(),
                sectionColorArgb = 0xFF8B5E3C.toInt(),
                measurer = measurer
            )
        )
        val paras = window.pageUnits(0).filterIsInstance<PageUnit.Para>()
        assertTrue("应有 2 个双语段", paras.size >= 2)
        val h1 = checkNotNull(paras[0].mainLayout).size.height

        val padPx = with(densityObj) { ReaderMetrics.BILINGUAL_PAD_DP.dp.roundToPx() }
        val spacingPx = kotlin.math.round(style.paragraphSpacingPx).toInt()
        val expectedDelta = h1 + 2 * padPx + spacingPx

        val bands = inkBandTops(bmp)
        assertTrue("应有标题+两段共 3 个文本带", bands.size >= 3)
        // bands[0]=章节标题，bands[1]=第一段，bands[2]=第二段
        val delta = bands[2] - bands[1]
        assertEquals(
            "相邻双语段首行间距必须等于排版高度 + 2*round(4dp) + round(段距)",
            expectedDelta,
            delta
        )
    }

    /** 返回墨迹带的起点行序列（带 = 连续若干行含深色像素）。 */
    private fun inkBandTops(bitmap: Bitmap, threshold: Int = 20): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val bands = mutableListOf<Int>()
        var inBand = false
        for (y in 0 until h) {
            var dark = 0
            for (x in 0 until w) {
                val v = px[y * w + x]
                val luma = ((v shr 16 and 0xFF) + (v shr 8 and 0xFF) + (v and 0xFF)) / 3
                if (luma < 235) dark++
            }
            if (dark > threshold && !inBand) {
                bands.add(y)
                inBand = true
            } else if (dark <= threshold && inBand) {
                inBand = false
            }
        }
        return bands
    }
}