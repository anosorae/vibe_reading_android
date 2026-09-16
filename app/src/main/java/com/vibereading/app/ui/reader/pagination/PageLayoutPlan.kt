package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.parser.SourceLanguageDetector

/**
 * 一页的**版面计划**：把「每个内容块画在哪、多高、块后留多少间距、气泡在哪」算成纯数据。
 *
 * 背景与目的：同一份版面此前由两套渲染器各自推导——Compose 的 `PageRenderer`
 * 用 `Modifier.padding` 表达，仿真卷页的 `renderPageBitmap` 用手工 `cursorY` 累加，
 * 两者之间靠源码注释里 20+ 条「必须与…一致」维持不变量。本模块把渲染几何收敛到唯一实现，
 * 两个渲染器都消费它，分叉从「靠注释」变为「靠类型」。
 *
 * **口径是渲染口径，即 `roundToPx`**：`Modifier.padding(dp)` 内部就是先 roundToPx，
 * 计划必须与 Compose 逐像素对齐。注意这与 [ChapterPaginator] 的**测量口径**（`DP * density`
 * 浮点，见其 `measureTitleHeight`）刻意不同：测量期少留 <1px 只会让段落更早落页，
 * 不会漏排；若两侧都改成 roundToPx，反而会引入「排版说放得下、渲染溢出」的底行被裁风险。
 *
 * 块高 = 上间距 + 内容高 + 下间距；[PageBlockSpec.yPx] 是块顶（含上间距）。
 */
data class PageBubbleRect(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val cornerRadiusPx: Float
)

/** 一个内容块的几何。`unit` 供渲染器取文本/布局/图片路径，几何字段由 [PageLayoutPlanner] 决定。 */
data class PageBlockSpec(
    val unit: PageUnit,
    /** 块顶（相对内容区原点，含 [padTopPx]）。 */
    val yPx: Float,
    val padTopPx: Float,
    val padBottomPx: Float,
    /** 内容自身高度：标题块为标题块高，正文为文本布局高，插图为适配后高。 */
    val contentHeightPx: Float,
    /** 块后段距（整像素）；末个可间距块与拆分首片段为 0（对齐排版器 buildPage 的 realUsed）。 */
    val spacingBelowPx: Float,
    /** 带译文的双语片段段尾气泡（视觉叠加层，不参与块高）。 */
    val bubble: PageBubbleRect? = null
) {
    /** 块高（不含块后段距）。 */
    val heightPx: Float get() = padTopPx + contentHeightPx + padBottomPx

    /** 块底（不含块后段距）。 */
    val bottomPx: Float get() = yPx + heightPx

    /** 正文/插图的绘制起点（跳过上间距）。 */
    val contentTopPx: Float get() = yPx + padTopPx
}

object PageLayoutPlanner {

    /**
     * 计划一页的版面。纯函数：只读 [PageUnit] 自带的最终有效布局与 [style]/[density]，
     * 不做测量、不碰 Compose 运行时，可脱离渲染单测。bottomJustify 的行高已由分页器
     * 写入 [PageUnit.Para.mainLayout]，因此块高不会与 Compose/位图实际正文高度分叉。
     *
     * [mode] 决定双语插槽是否成立（en 模式下带译文的片段才有气泡与额外 padding）。
     */
    fun plan(
        units: List<PageUnit>,
        style: PageStyle,
        density: Density,
        contentWidthPx: Float,
        mode: String = SourceLanguageDetector.ZH
    ): List<PageBlockSpec> {
        // 末个参与段距的块之后不再加段距（保持与排版器 buildPage 的 realUsed 一致）
        val lastSpacedIdx = units.indexOfLast { it is PageUnit.Para || it is PageUnit.Image }
        // 段距与双语 padding 都先 round 成整像素再累加：Modifier.padding 内部即 roundToPx，
        // 若用浮点 dp*density 累加，整页会逐段累积亚像素漂移（历史回归）。
        val spacingPx = with(density) { style.paragraphSpacingPx.toDp().roundToPx() }.toFloat()
        val bilingualPadPx = bilingualPadSidePx(density)

        var cursor = 0f
        return units.mapIndexed { index, unit ->
            val spec = when (unit) {
                is PageUnit.Title -> PageBlockSpec(
                    unit = unit,
                    yPx = cursor,
                    padTopPx = 0f,
                    padBottomPx = 0f,
                    contentHeightPx = titleBlockHeightPx(unit.sectionLayout, unit.titleLayout, density),
                    // 标题块自身已含到底部正文的间距，不再另有段距
                    spacingBelowPx = 0f
                )

                is PageUnit.Image -> PageBlockSpec(
                    unit = unit,
                    yPx = cursor,
                    padTopPx = 0f,
                    padBottomPx = 0f,
                    contentHeightPx = unit.displayHeightPx,
                    spacingBelowPx = if (index == lastSpacedIdx) 0f else spacingPx
                )

                is PageUnit.Para -> {
                    val carriesBubble = mode == SourceLanguageDetector.EN &&
                        unit.enText?.isNotBlank() == true && unit.cnText.isNotBlank()
                    val pad = if (carriesBubble) bilingualPadPx else 0f
                    val textHeight = unit.mainLayout?.size?.height?.toFloat() ?: 0f
                    // 被拆开段落的首片段（splitFirst）与其续段视觉相连，不加段距
                    val spacing = if (unit.splitFirst || index == lastSpacedIdx) 0f else spacingPx
                    val blockBottom = cursor + pad + textHeight + pad
                    PageBlockSpec(
                        unit = unit,
                        yPx = cursor,
                        padTopPx = pad,
                        padBottomPx = pad,
                        contentHeightPx = textHeight,
                        spacingBelowPx = spacing,
                        bubble = if (carriesBubble) bubbleRect(blockBottom, contentWidthPx, density) else null
                    )
                }
            }
            cursor = spec.bottomPx + spec.spacingBelowPx
            spec
        }
    }

    /**
     * 标题块高度（**渲染口径**：与 `ReadingChapterTitle` 的 `Modifier.padding(dp)` 一致，
     * 每段间距先 roundToPx）：顶部留白 + 卷名（存在时才计入其后的 [ReaderMetrics.SECTION_TITLE_GAP_DP]）
     * + 章节名 + 底部间距。
     *
     * 卷名间距只在 [sectionLayout] 非空时累加——这是历史 bug 的根因：无卷名章节若无条件
     * 加上这 8dp，卷页位图的标题会被凭空顶低，卷页瞬间整页文字下跳。
     */
    fun titleBlockHeightPx(
        sectionLayout: TextLayoutResult?,
        titleLayout: TextLayoutResult?,
        density: Density
    ): Float {
        var h = with(density) { ReaderMetrics.TITLE_TOP_DP.dp.roundToPx() }.toFloat()
        sectionLayout?.let { layout ->
            h += layout.size.height.toFloat()
            h += with(density) { ReaderMetrics.SECTION_TITLE_GAP_DP.dp.roundToPx() }
        }
        titleLayout?.let { h += it.size.height.toFloat() }
        return h + with(density) { ReaderMetrics.TITLE_BOTTOM_DP.dp.roundToPx() }
    }

    /**
     * 双语对单边上下 padding（px），**渲染口径**：`Modifier.padding(4.dp)` 内部即 roundToPx
     * （四舍五入，10.5 → 11），必须与 `BilingualParagraph` 逐像素一致。
     *
     * 注意它**不等于** [ReaderMetrics.bilingualPadPx] 的一半：后者用 `kotlin.math.round`
     * （ties-to-even，10.5 → 10），是排版器的**测量口径**，刻意少留 2px。曾把两者当成同一个数，
     * 导致位图双语段每侧少 1px、相邻段间距比真实页差 2px（由 PageGeometryConsistencyTest
     * 与 RenderPageBitmapTitleOffsetTest 抓出）。
     */
    fun bilingualPadSidePx(density: Density): Float =
        with(density) { ReaderMetrics.BILINGUAL_PAD_DP.dp.roundToPx() }.toFloat()

    /**
     * 段尾气泡矩形：底边距块底 [ReaderMetrics.BUBBLE_BOTTOM_DP]，右缘距内容区右缘
     * [ReaderMetrics.BUBBLE_END_DP]——与 `BilingualParagraph` 的
     * `align(BottomEnd) + padding(end/bottom) + size(W,H)` 是同一几何。
     */
    private fun bubbleRect(blockBottomPx: Float, contentWidthPx: Float, density: Density): PageBubbleRect {
        val w = with(density) { ReaderMetrics.BUBBLE_WIDTH_DP.dp.roundToPx() }.toFloat()
        val h = with(density) { ReaderMetrics.BUBBLE_HEIGHT_DP.dp.roundToPx() }.toFloat()
        return PageBubbleRect(
            leftPx = contentWidthPx - w - with(density) { ReaderMetrics.BUBBLE_END_DP.dp.roundToPx() },
            topPx = blockBottomPx - with(density) { ReaderMetrics.BUBBLE_BOTTOM_DP.dp.roundToPx() } - h,
            widthPx = w,
            heightPx = h,
            cornerRadiusPx = with(density) { ReaderMetrics.BUBBLE_CORNER_RADIUS_DP.dp.roundToPx() }.toFloat()
        )
    }
}
