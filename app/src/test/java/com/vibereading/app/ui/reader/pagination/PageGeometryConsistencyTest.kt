package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.newTextMeasurer
import com.vibereading.app.ui.reader.ReaderContentInteractions
import com.vibereading.app.ui.reader.ReaderLayoutSpec
import com.vibereading.app.ui.reader.ReaderPageGeometry
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.components.ReadingChapterTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 版面几何一致性测试网（M4「共享版面计划」抽取前的安全网）。
 *
 * 背景：同一份版面由两套渲染器产出——Compose 的 [PageRenderer] 与仿真卷页用的
 * [renderPageBitmap]——它们之间靠源码注释里 20+ 条「必须与…一致」维持不变量，
 * 没有编译期或测试期约束。抽取共享版面计划时最容易引入的回归正是「两套结果分叉」，
 * 因此本文件断言的是**两侧一致**，而不是某一个具体数值。
 *
 * 断言口径：
 * - 期望值从数据推导（`PageUnit.mainLayout`、`PageStyle`、`ReaderMetrics`、`ReaderPageGeometry`），
 *   不抄写渲染实现里的累加公式，这样 M4 改变「谁算这个数」时测试仍然成立；
 * - 只有两侧真的分叉才会变红。
 *
 * 密度 2.625 放大整像素舍入余量（与 RenderPageBitmapTitleOffsetTest 同口径）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class PageGeometryConsistencyTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = 2.625f
    private val densityObj = Density(density)

    private val screenW = 1080
    private val screenH = 2400
    private val statusBarPx = 105 // 40dp @2.625 → 返回 dp 再回 px 无舍入损失
    private val navBarPx = 105
    private val paddingH = 22
    private val paddingV = 20

    private val padHPx = with(densityObj) { paddingH.dp.roundToPx() }
    private val padVPx = with(densityObj) { paddingV.dp.roundToPx() }

    private val style = PageStyle(
        body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
        cn = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
        title = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold
        ),
        paragraphSpacingPx = 26.25f,
        bottomJustify = false // 关闭底部对齐：lineHeightExtra=0，位图直接画 mainLayout
    )

    private fun geometry() = ReaderPageGeometry.of(
        screenWidthPx = screenW,
        screenHeightPx = screenH,
        statusBarPx = statusBarPx,
        navBarPx = navBarPx,
        padHPx = padHPx,
        padVPx = padVPx
    )

    private fun newMeasurer(): TextMeasurer = newTextMeasurer(densityObj)

    // ── 1. 内容区几何：真实 PageRenderer 的落点必须与 ReaderPageGeometry 同口径 ──

    /**
     * 直接渲染真实的 [PageRenderer] 并读取首个正文节点的位置/宽度。
     * 此前这是「PageRenderer 的 Modifier 链」与「[ReaderPageGeometry] 公式」两份独立实现，
     * 只靠注释维持一致——本用例把两侧钉在一起（此处测真实链，不复刻 Modifier）。
     *
     * 取第 2 页（无章节标题块）：首行左缘应等于 paddingH，顶缘应等于 状态栏 + paddingV。
     */
    @Test
    fun pageRenderer_placesContentAtGeometryOrigin() {
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = null, chapterIndex = 0,
            content = (1..80).joinToString("\n\n") { "第${it}段正文内容，用于填满多个页面。" }
        )
        val w = window(chapter, mode = "zh")
        assertTrue("应排出多页", w.pageCount > 1)
        val units = w.pageUnits(1)
        assertTrue("第 2 页不应含标题块", units.none { it is PageUnit.Title })
        val firstPara = units.filterIsInstance<PageUnit.Para>().first()
        val probe = firstPara.cnText.take(6)

        compose.setContent {
            // 窗口尺寸来自 @Config qualifiers（≈1079×2399 px @2.625），
            // fillMaxSize 即内容区所在屏幕；不用 requiredSize，避免超出窗口被居中。
            Box(Modifier.fillMaxSize()) {
                    PageRenderer(
                        units = units,
                        mode = "zh",
                        layout = ReaderLayoutSpec(
                            pageStyle = style,
                            palette = ReaderPalette.of(isDark = false),
                            geometry = geometry(),
                            paddingH = paddingH,
                            paddingV = paddingV,
                            headerContentGap = 20,
                            footerContentGap = 20
                        ),
                        interactions = ReaderContentInteractions()
                    )
            }
        }
        compose.waitForIdle()

        val bounds = compose.onAllNodesWithText(probe, substring = true)[0].getUnclippedBoundsInRoot()
        assertEquals(
            "正文左缘应等于 paddingH（PageRenderer 的 Modifier 链 vs 用户边距）",
            paddingH.toFloat(),
            bounds.left.value,
            0.5f
        )
        assertEquals(
            "正文顶缘应等于 状态栏 + paddingV（PageRenderer 的 Modifier 链 vs 系统栏几何）",
            (statusBarPx / density) + paddingV,
            bounds.top.value,
            0.5f
        )
    }

    // ── 2. 标题块高度：Compose 渲染与排版/位图同口径 ──

    /**
     * 历史 bug 类：无卷名章节的首行若仍累加「卷名→章节名」间距，位图标题会凭空顶低 8dp。
     *
     * 断言两块高度各自等于「ReaderMetrics 推导值」：无卷名 == TOP + 标题文本高 + BOTTOM；
     * 有卷名 == 无卷名 + 卷名文本高 + SECTION_TITLE_GAP。后者精确锁定那个 8dp 只加在有卷名时。
     */
    @Test
    fun titleBlockHeight_differsOnlyBySectionGap() {
        val palette = ReaderPalette.of(isDark = false)

        // 用语义节点尺寸读块高（px）：不在布局期写 state，避免 onSizeChanged 触发
        // 额外布局往返——那在全量跑测试集时会退化成 Compose 空闲检测超时（AppNotIdle）。
        compose.setContent {
            // 窗口宽度来自 @Config qualifiers（≈1079px）：太窄标题会折行，块高就不再是
            // 「TOP + 单行标题 + BOTTOM」这个待验证的式子
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.testTag(TAG_NO_SECTION)) {
                    ReadingChapterTitle(section = null, title = "第一章 测试标题", palette = palette, pageStyle = style)
                }
                Box(Modifier.testTag(TAG_WITH_SECTION)) {
                    ReadingChapterTitle(section = "第一卷", title = "第一章 测试标题", palette = palette, pageStyle = style)
                }
            }
        }

        val withoutSection = compose.onNodeWithTag(TAG_NO_SECTION).fetchSemanticsNode().size.height
        val withSection = compose.onNodeWithTag(TAG_WITH_SECTION).fetchSemanticsNode().size.height

        val gapPx = with(densityObj) { ReaderMetrics.SECTION_TITLE_GAP_DP.dp.roundToPx() }
        val topPx = with(densityObj) { ReaderMetrics.TITLE_TOP_DP.dp.roundToPx() }
        val bottomPx = with(densityObj) { ReaderMetrics.TITLE_BOTTOM_DP.dp.roundToPx() }
        val width = (screenW - padHPx * 2)
        val measurer = newMeasurer()
        val titleH = measurer.measure(
            text = "第一章 测试标题",
            style = style.title,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)
        ).size.height
        val sectionH = measurer.measure(
            text = "第一卷",
            style = style.cn,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)
        ).size.height

        assertEquals(
            "无卷名标题块高度必须等于 TOP + 标题文本高 + BOTTOM（Compose 渲染 vs ReaderMetrics 推导）",
            topPx + titleH + bottomPx,
            withoutSection
        )
        assertEquals(
            "有卷名比无卷名恰好多出「卷名文本高 + SECTION_TITLE_GAP」（该间距只加在有卷名时）",
            sectionH + gapPx,
            withSection - withoutSection
        )
    }

    // ── 3. 末段段距：页高累加与实际使用高度一致 ──

    /**
     * `PageRenderer` 与 `renderPageBitmap` 都依赖「末个可间距单元不产生段距」这一规则
     * （对齐排版器 buildPage 的 realUsed）。此处从 PageUnit 的布局高度反推页高，
     * 逐一比对每一页，任一侧漏掉该规则都会变红。
     */
    @Test
    fun pageUsedHeight_excludesSpacingOfLastSpacedUnit() {
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = null, chapterIndex = 0,
            content = (1..80).joinToString("\n\n") { "第${it}段正文内容，用于填满多个页面，验证分页与页高口径。" }
        )
        // 直接用 ChapterPaginator：BookWindow 不暴露每页的 usedHeightPx，而页高口径正是本用例的对象
        val paginator = ChapterPaginator(
            chapterId = chapter.id,
            items = BookWindow.buildChapterItems(chapter, "zh"),
            style = style,
            mode = "zh",
            contentWidthPx = geometry().contentWidthPx,
            contentHeightPx = geometry().contentHeightPx,
            measurer = newMeasurer(),
            density = density
        )
        assertTrue("应排出多页", paginator.pages.size > 2)

        val spacingPx = style.paragraphSpacingPx
        paginator.pages.forEachIndexed { pageIndex, page ->
            val units = page.units
            // 最后一个参与段距的单元（Para/Image）之后不再加段距
            val lastSpacedIdx = units.indexOfLast { it is PageUnit.Para || it is PageUnit.Image }
            var expected = 0f
            units.forEachIndexed { idx, unit ->
                expected += unitHeightPx(unit)
                // 与生产同一规则：末个可间距单元不加；被拆开的段落首片段（splitFirst）
                // 与其续段视觉相连，也不加段距
                val isSpaced = when (unit) {
                    is PageUnit.Image -> true
                    is PageUnit.Para -> !unit.splitFirst
                    else -> false
                }
                if (isSpaced && idx != lastSpacedIdx) expected += spacingPx
            }
            // 允许 ±1px：排版器内部逐项 round 累加
            assertTrue(
                "第 $pageIndex 页 usedHeightPx=${page.usedHeightPx} 与单元高度推导值 $expected 不符" +
                    "（末段段距规则在两侧必须一致）",
                kotlin.math.abs(page.usedHeightPx - expected) <= 1f
            )
        }
    }

    // ── 3b. 版面计划自身的契约（纯函数，逐块断言） ──

    /**
     * [PageLayoutPlanner] 的段距规则与块序契约：段距为 0 的三种情形（标题块、末个可间距块、
     * 被拆开段落的首片段）必须精确，块顶必须紧接上一块底 + 段距。
     *
     * 这是「共享计划」这一新共享点的规格测试——只测渲染结果无法覆盖它：
     * 计划若给末块多留一个段距，两套渲染器会**一致地**多留，位图/Compose 对照仍通过，
     * 但页面底部会凭空多出间距（真实可见的回归）。
     */
    @Test
    fun planSpacingRules_pinZeroSpacingCasesAndBlockSequence() {
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = "第一卷", chapterIndex = 0,
            content = (1..40).joinToString(PARA_SEP) { "第${it}段正文，" + "内容".repeat(40) + "。" }
        )
        val spacingPx = with(densityObj) { style.paragraphSpacingPx.toDp().roundToPx() }.toFloat()
        val paginator = ChapterPaginator(
            chapterId = chapter.id,
            items = BookWindow.buildChapterItems(chapter, "zh"),
            style = style,
            mode = "zh",
            contentWidthPx = geometry().contentWidthPx,
            contentHeightPx = geometry().contentHeightPx,
            measurer = newMeasurer(),
            density = density
        )
        var sawSplitFragment = false

        paginator.pages.forEachIndexed { pageIndex, page ->
            val units = page.units
            val blocks = PageLayoutPlanner.plan(
                units = units,
                style = style,
                density = densityObj,
                contentWidthPx = geometry().contentWidthPx,
                mode = "zh"
            )
            assertEquals("一页一块，块数与单元数一致", units.size, blocks.size)

            // 块序：块顶 == 上一块底 + 上一块段距
            blocks.forEachIndexed { i, b ->
                if (i > 0) {
                    assertEquals(
                        "第 $pageIndex 页第 $i 块块顶必须紧接上一块底 + 段距",
                        blocks[i - 1].bottomPx + blocks[i - 1].spacingBelowPx,
                        b.yPx,
                        0.01f
                    )
                }
            }

            val lastSpacedIdx = units.indexOfLast { it is PageUnit.Para || it is PageUnit.Image }
            units.forEachIndexed { i, unit ->
                if (unit is PageUnit.Para && unit.splitFirst) sawSplitFragment = true
                val expected = when {
                    unit is PageUnit.Title -> 0f          // 标题块自带底部间距
                    i == lastSpacedIdx -> 0f              // 末个可间距块（realUsed 口径）
                    unit is PageUnit.Para && unit.splitFirst -> 0f // 与续段视觉相连
                    unit is PageUnit.Para || unit is PageUnit.Image -> spacingPx
                    else -> 0f
                }
                assertEquals(
                    "第 $pageIndex 页第 $i 块的段距（${unit::class.simpleName}）",
                    expected,
                    blocks[i].spacingBelowPx,
                    0.01f
                )
            }

            // 计划总高不得溢出内容区：溢出即底行被裁（PageRenderer 的无界高度 Layout
            // 允许微溢到 Box padding，但整页溢出内容区就是排版错误了）。
            // 不与排版器 usedHeightPx 比相等：两者口径刻意不同（测量期浮点 vs 渲染期
            // roundToPx），每处段距差最多 0.25px，差距随段数累积，比相等是错的断言。
            assertTrue(
                "第 $pageIndex 页计划总高 ${blocks.last().bottomPx} 不得溢出内容区 ${geometry().contentHeightPx}",
                blocks.last().bottomPx <= geometry().contentHeightPx + 1f
            )
        }
        assertTrue("语料应产生跨页拆分片段以覆盖 splitFirst 分支", sawSplitFragment)
    }

    // ── 4. bottomJustify：最终布局必须在分页期确定，计划器只消费 ──

    @Test
    fun bottomJustify_planUsesFinalLayoutForSecondParagraph() {
        val justifyStyle = style.copy(bottomJustify = true)
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = null, chapterIndex = 0,
            content = (1..60).joinToString(PARA_SEP) {
                "第${it}段正文内容，用于制造多段满页并验证第二块位置。" + "附加内容。".repeat(4)
            }
        )
        val paginator = paginator(chapter, mode = "zh", pageStyle = justifyStyle)
        val page = paginator.pages.dropLast(1).firstOrNull { candidate ->
            candidate.units.none { it is PageUnit.Title } &&
                candidate.units.filterIsInstance<PageUnit.Para>().size >= 2 &&
                candidate.units.filterIsInstance<PageUnit.Para>().any { it.lineHeightExtraPx > 0f }
        }
        requireNotNull(page) { "语料应产生包含至少两个正文块的 bottomJustify 中间页" }
        val paras = page.units.filterIsInstance<PageUnit.Para>()
        val first = paras[0]
        val second = paras[1]
        val blocks = PageLayoutPlanner.plan(
            units = page.units,
            style = justifyStyle,
            density = densityObj,
            contentWidthPx = geometry().contentWidthPx,
            mode = "zh"
        )
        val firstBlock = blocks.first { it.unit === first }
        val secondBlock = blocks.first { it.unit === second }

        assertTrue("首段应携带底部对齐额外行高", first.lineHeightExtraPx > 0f)
        assertEquals(
            "PageUnit.mainLayout 必须已是最终有效布局，不能仍保留自然高度",
            first.lineCount * first.lineHeightExtraPx,
            firstBlock.contentHeightPx - naturalLayoutHeight(first, justifyStyle, "zh"),
            first.lineCount + 1f
        )
        assertEquals(
            "第二正文块必须紧跟最终首块高度与实际段距，不能按自然 mainLayout 提前",
            firstBlock.bottomPx + firstBlock.spacingBelowPx,
            secondBlock.yPx,
            0.01f
        )
    }

    @Test
    fun bottomJustify_bilingualBubbleUsesFinalBlockBottom() {
        val justifyStyle = style.copy(bottomJustify = true)
        val original = (1..70).joinToString(PARA_SEP) {
            "English source paragraph $it contains enough words to occupy several wrapped lines on the page."
        }
        val translated = (1..70).joinToString(PARA_SEP) { "[$it] 第${it}段中文译文。" }
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "Chapter One", section = null, chapterIndex = 0,
            content = original,
            translatedContent = translated
        )
        val paginator = paginator(chapter, mode = "en", pageStyle = justifyStyle)
        val page = paginator.pages.dropLast(1).firstOrNull { candidate ->
            candidate.units.none { it is PageUnit.Title } &&
                candidate.units.filterIsInstance<PageUnit.Para>().size >= 2 &&
                candidate.units.filterIsInstance<PageUnit.Para>().any { it.lineHeightExtraPx > 0f }
        }
        requireNotNull(page) { "语料应产生双语 bottomJustify 中间页" }
        val blocks = PageLayoutPlanner.plan(
            units = page.units,
            style = justifyStyle,
            density = densityObj,
            contentWidthPx = geometry().contentWidthPx,
            mode = "en"
        )
        val lastParaBlock = blocks.last { it.unit is PageUnit.Para }
        val bubble = requireNotNull(lastParaBlock.bubble)
        val bubbleBottomPx = with(densityObj) { ReaderMetrics.BUBBLE_BOTTOM_DP.dp.roundToPx() }

        assertEquals(
            "最终双语块气泡必须锚定最终布局块底",
            lastParaBlock.bottomPx - bubbleBottomPx,
            bubble.topPx + bubble.heightPx,
            0.01f
        )
    }

    // ── 5. 原文气泡矩形：位图侧必须与 BilingualParagraph 使用同一组常量 ──

    @Test
    fun bubbleRect_bitmapHonoursSharedMetrics() {
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "Chapter One", section = null, chapterIndex = 0,
            content = "Hello world.",
            translatedContent = "[1] 你好，世界。"
        )
        val palette = ReaderPalette.of(isDark = false)
        val bgArgb = 0xFFFFF8F0.toInt()
        val bmp = requireNotNull(render(chapter, mode = "en", palette = palette, bgColorArgb = bgArgb)) {
            "位图渲染失败"
        }

        // 气泡是半透明色块，落到位图上已与背景做过 alpha 合成——按合成结果匹配
        val bubbleArgb = blendOver(palette.bubble.toArgb(), bgArgb)
        val box = boundingBoxOfColor(bmp, bubbleArgb, tolerance = 4)
        assertTrue("位图中应能定位到气泡色块（合成色 ${Integer.toHexString(bubbleArgb)}）", box != null)
        val (left, top, right, bottom) = box!!

        val wPx = with(densityObj) { ReaderMetrics.BUBBLE_WIDTH_DP.dp.roundToPx() }
        val hPx = with(densityObj) { ReaderMetrics.BUBBLE_HEIGHT_DP.dp.roundToPx() }
        val endPx = with(densityObj) { ReaderMetrics.BUBBLE_END_DP.dp.roundToPx() }

        assertTrue(
            "气泡宽度应约等于 BUBBLE_WIDTH_DP（实测 ${right - left}，期望 $wPx）",
            kotlin.math.abs((right - left + 1) - wPx) <= 2
        )
        assertTrue(
            "气泡高度应约等于 BUBBLE_HEIGHT_DP（实测 ${bottom - top + 1}，期望 $hPx）",
            kotlin.math.abs((bottom - top + 1) - hPx) <= 2
        )
        val contentRight = padHPx + geometry().contentWidthPx.toInt()
        assertTrue(
            "气泡右缘应距内容区右缘 BUBBLE_END_DP（实测 ${contentRight - right}，期望 $endPx）",
            kotlin.math.abs((contentRight - right) - endPx) <= 2
        )
    }

    // ── 6. 插图单元：位图必须按排版器适配后的尺寸绘制 ──

    @Test
    fun imageBlock_spacingBelowMatchesFollowingParagraph() {
        val link = IllustrationLink.build("1/inline.jpg", 800, 600)
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = null, chapterIndex = 0,
            content = "$link$PARA_SEP 插图后的正文段落。"
        )
        val page = paginator(chapter, mode = "zh", pageStyle = style).pages.first()
        val blocks = PageLayoutPlanner.plan(
            units = page.units,
            style = style,
            density = densityObj,
            contentWidthPx = geometry().contentWidthPx,
            mode = "zh"
        )
        val imageBlock = blocks.first { it.unit is PageUnit.Image }
        val paraBlock = blocks.first { it.unit is PageUnit.Para }
        val expectedSpacing = with(densityObj) { style.paragraphSpacingPx.toDp().roundToPx() }.toFloat()

        assertEquals("插图后的实际段距必须来自 PageStyle", expectedSpacing, imageBlock.spacingBelowPx, 0.01f)
        assertEquals(
            "插图后正文块顶必须等于插图块底加实际段距",
            imageBlock.bottomPx + expectedSpacing,
            paraBlock.yPx,
            0.01f
        )
    }

    @Test
    fun imageUnit_bitmapUsesPaginatorFittedSize() {
        val imgW = 1200
        val imgH = 900
        val link = IllustrationLink.build("1/cover.jpg", imgW, imgH)
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = null, chapterIndex = 0,
            content = "引言段落。\n\n$link\n\n后续段落。"
        )
        val imageUnit = window(chapter, mode = "zh").pageUnits(0)
            .filterIsInstance<PageUnit.Image>()
            .firstOrNull()
        assertTrue("首页应含插图单元", imageUnit != null)

        val solid = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        solid.eraseColor(IMAGE_MARKER)
        val bgArgb = 0xFFFFF8F0.toInt()
        val bmp = requireNotNull(
            render(chapter, mode = "zh", palette = ReaderPalette.of(isDark = false), bgColorArgb = bgArgb) { _, _ -> solid }
        ) { "位图渲染失败" }

        val box = boundingBoxOfColor(bmp, IMAGE_MARKER, tolerance = 0)
        assertTrue("位图中应能定位到插图（imageResolver 返回的纯色位图）", box != null)
        val (left, top, right, bottom) = box!!
        val drawnW = right - left + 1
        val drawnH = bottom - top + 1
        assertTrue(
            "插图绘制宽 ${drawnW} 应等于排版适配宽 ${imageUnit!!.displayWidthPx}",
            kotlin.math.abs(drawnW - imageUnit.displayWidthPx.toInt()) <= 2
        )
        assertTrue(
            "插图绘制高 ${drawnH} 应等于排版适配高 ${imageUnit.displayHeightPx}",
            kotlin.math.abs(drawnH - imageUnit.displayHeightPx.toInt()) <= 2
        )
        // 水平居中于内容区
        val expectedLeft = padHPx + (geometry().contentWidthPx - imageUnit.displayWidthPx) / 2f
        assertTrue(
            "插图应水平居中于内容区（实测左缘 $left，期望 $expectedLeft）",
            kotlin.math.abs(left - expectedLeft) <= 2f
        )
    }

    // ── 6. 跨页续段：片段区间互斥、flags 与后继片段一致 ──

    @Test
    fun splitParagraph_flagsAndRangesAreConsistentAcrossPages() {
        val longPara = "这是一段足够长的中文正文，".repeat(80)
        val chapter = Chapter(
            id = 1L, bookId = 1, title = "第一章", section = null, chapterIndex = 0,
            content = longPara
        )
        val w = window(chapter, mode = "zh")
        val fragments = (0 until w.pageCount).flatMap { w.pageUnits(it) }
            .filterIsInstance<PageUnit.Para>()
        assertTrue("长段应被拆成多个片段", fragments.size > 1)

        fragments.forEachIndexed { idx, unit ->
            // 片段区间必须落在段落范围内
            assertTrue("片段区间不得越界", unit.sourceStartOffset >= 0)
            assertTrue(
                "片段区间应互斥且递增（offset→页 唯一映射的前提）",
                idx == 0 || unit.sourceStartOffset >= fragments[idx - 1].sourceEndOffset
            )
            // 除末片段外都必须标记「段落在下一页延续」（决定末行是否拉伸）
            assertEquals(
                "第 $idx 个片段的 paragraphContinues 应与后继片段存在性一致",
                idx < fragments.size - 1,
                unit.paragraphContinues
            )
            // 续段必须顶格：无首行缩进 → 首行左缘贴内容区左缘
            if (unit.continuation) {
                assertEquals(
                    "续段首行必须顶格（无首行缩进）",
                    0f,
                    unit.mainLayout?.getLineLeft(0) ?: 0f,
                    0.5f
                )
            }
        }
    }

    // ── 辅助 ──

    private fun paginator(
        chapter: Chapter,
        mode: String,
        pageStyle: PageStyle = style
    ) = ChapterPaginator(
        chapterId = chapter.id,
        items = BookWindow.buildChapterItems(chapter, mode),
        style = pageStyle,
        mode = mode,
        contentWidthPx = geometry().contentWidthPx,
        contentHeightPx = geometry().contentHeightPx,
        measurer = newMeasurer(),
        density = density
    )

    private fun naturalLayoutHeight(unit: PageUnit.Para, pageStyle: PageStyle, mode: String): Float {
        val baseStyle = if (unit.continuation) pageStyle.body.copy(textIndent = null) else pageStyle.body
        val text = if (mode == "zh") unit.cnText.ifBlank { unit.enText.orEmpty() }
        else unit.enText ?: unit.cnText
        val width = geometry().contentWidthPx.toInt()
        val measurer = newMeasurer()
        val justified = CjkJustifier.annotateDetailed(
            text = text,
            style = baseStyle,
            contentWidthPx = width,
            measurer = measurer,
            justifyLastLine = unit.paragraphContinues
        )
        val effectiveStyle = if (justified.tookOver) {
            baseStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        } else {
            CjkJustifier.adjustLatinTextStyle(text, baseStyle)
        }
        return measurer.measure(
            text = justified.annotated,
            style = effectiveStyle,
            constraints = Constraints(minWidth = width, maxWidth = width)
        ).size.height.toFloat()
    }

    private fun window(chapter: Chapter, mode: String): BookWindow = BookWindow(
        chapters = listOf(chapter),
        style = style,
        mode = mode,
        contentWidthPx = geometry().contentWidthPx,
        contentHeightPx = geometry().contentHeightPx,
        measurer = newMeasurer(),
        backgroundMeasurer = { newMeasurer() },
        displayDensity = density
    ).also { it.recenterSync(chapter.id) }

    private fun render(
        chapter: Chapter,
        mode: String,
        palette: ReaderPalette,
        bgColorArgb: Int = 0xFFFFF8F0.toInt(),
        imageResolver: ((String, Int) -> Bitmap?)? = null
    ): Bitmap? = renderPageBitmap(
        window = window(chapter, mode),
        page = 0,
        mode = mode,
        pageStyle = style,
        geometry = geometry(),
        palette = palette,
        density = densityObj,
        bgColorArgb = bgColorArgb,
        sectionColorArgb = 0xFF8B5E3C.toInt(),
        measurer = newMeasurer(),
        imageResolver = imageResolver
    )

    /**
     * 单元在本页占用的高度，从单元自带数据推导（不抄渲染里的累加公式）：
     * 标题取卷名/标题布局，正文取 mainLayout，插图取适配尺寸，双语对额外加 2×padding。
     */
    private fun unitHeightPx(unit: PageUnit): Float = when (unit) {
        is PageUnit.Title -> {
            var h = with(densityObj) { ReaderMetrics.TITLE_TOP_DP.dp.roundToPx() }.toFloat()
            unit.sectionLayout?.let { h += it.size.height }
            if (unit.sectionLayout != null) {
                h += with(densityObj) { ReaderMetrics.SECTION_TITLE_GAP_DP.dp.roundToPx() }
            }
            unit.titleLayout?.let { h += it.size.height }
            h + with(densityObj) { ReaderMetrics.TITLE_BOTTOM_DP.dp.roundToPx() }
        }
        is PageUnit.Image -> unit.displayHeightPx
        is PageUnit.Para -> {
            val text = unit.mainLayout?.size?.height?.toFloat() ?: 0f
            if (unit.enText?.isNotBlank() == true && unit.cnText.isNotBlank()) {
                text + ReaderMetrics.bilingualPadPx(density)
            } else {
                text
            }
        }
    }

    /** 返回与 [argb] 在每通道 [tolerance] 内相近的像素包围盒（left, top, right, bottom），无命中返回 null。 */
    private fun boundingBoxOfColor(bitmap: Bitmap, argb: Int, tolerance: Int): IntArray? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val target = intArrayOf(
            argb shr 16 and 0xFF,
            argb shr 8 and 0xFF,
            argb and 0xFF
        )
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = px[y * w + x]
                val dr = kotlin.math.abs((v shr 16 and 0xFF) - target[0])
                val dg = kotlin.math.abs((v shr 8 and 0xFF) - target[1])
                val db = kotlin.math.abs((v and 0xFF) - target[2])
                if (dr <= tolerance && dg <= tolerance && db <= tolerance) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    private companion object {
        const val TAG_NO_SECTION = "title-no-section"
        const val TAG_WITH_SECTION = "title-with-section"

        /** 段落分隔符（章节字符串里的空行契约）。 */
        const val PARA_SEP = "\n\n"

        /** 插图测试用的纯色标记（不与页面背景/文字色冲突）。 */
        const val IMAGE_MARKER = 0xFF00FF00.toInt()

        /**
         * 半透明色 [src] 叠在不透明背景 [bg] 上的合成结果（与 Skia 的 SRC_OVER 一致）。
         * 位图里读到的像素已是合成值，不能用原色直接匹配。
         */
        fun blendOver(src: Int, bg: Int): Int {
            val a = (src ushr 24 and 0xFF) / 255f
            fun ch(shift: Int): Int {
                val s = (src shr shift and 0xFF).toFloat()
                val b = (bg shr shift and 0xFF).toFloat()
                return (a * s + (1f - a) * b + 0.5f).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Compose Color → ARGB int（位图按 ARGB_8888 读取）。 */
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f + 0.5f).toInt(),
    (red * 255f + 0.5f).toInt(),
    (green * 255f + 0.5f).toInt(),
    (blue * 255f + 0.5f).toInt()
)
