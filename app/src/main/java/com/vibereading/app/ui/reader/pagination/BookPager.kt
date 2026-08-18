package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.ReaderPageGeometry
import com.vibereading.app.ui.reader.components.BilingualParagraph
import com.vibereading.app.ui.reader.components.ParagraphKey
import com.vibereading.app.ui.reader.components.ReadingChapterTitle
import com.vibereading.app.ui.reader.components.SelectableParagraphText
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.theme.VibeColors
import java.util.Locale

/** 普通 HorizontalPager 的滑动开关；浮层不改变翻页手势本身。 */
fun readerPagerScrollEnabled(flipMode: String): Boolean =
    flipMode != ReadingSettings.FLIP_NO_ANIM &&
        flipMode != ReadingSettings.FLIP_SIMULATION

/** 手势开始时若有浮层，先关闭浮层，再继续处理本次手势。 */
fun readerShouldDismissOverlayOnGestureStart(overlayVisible: Boolean): Boolean = overlayVisible

/**
 * 仿真卷页状态机（对齐 Legado PageDelegate + HorizontalPageDelegate + SimulationPageDelegate）。
 *
 * 手势阶段：
 * - DOWN → 记录起点，reset 状态
 * - MOVE → slop 判定 → 确定方向 → setDirection(角落) → isCancel(回拖) → touchX/Y 跟手
 * - UP   → 启动自动动画（Scroller 式：cancel 回弹 / complete 完成）
 *
 * 动画阶段：animatable 驱动 touchX/Y 逐帧更新 → CurlOverlay recompose。
 * 动画结束：complete → scrollToPage + 清位图；cancel → 清位图。
 */
@Stable
class SimFlipState {
    val curl = PageCurl()

    // ── 覆盖层可见性 ──
    var animating by mutableStateOf(false)

    // ── 卷页几何 ──
    var direction by mutableStateOf(PageCurl.Direction.NEXT)
    var touchX by mutableFloatStateOf(0.1f)
    var touchY by mutableFloatStateOf(0.1f)
    var cornerX by mutableFloatStateOf(0f)
    var cornerY by mutableFloatStateOf(0f)

    // ── 位图（NEXT: cur=当前页, target=下一页; PREV: cur=当前页, target=上一页）──
    var curBitmap: Bitmap? by mutableStateOf(null)
    var targetBitmap: Bitmap? by mutableStateOf(null)
    var bgColor by mutableIntStateOf(0xFFFFFFFF.toInt())

    // 自动动画完成后将落地的目标页；-1 表示回弹/无翻页。
    // 新触摸打断动画时据此把翻页稳妥落地（snap），避免动画中途消失且翻页不生效（突兀）。
    var settleTarget by mutableIntStateOf(-1)

    // ── 手势状态（对齐 Legado PageDelegate）──
    var isMoved by mutableStateOf(false)
    var isCancel by mutableStateOf(false)
    var isRunning by mutableStateOf(false)
    var startX by mutableFloatStateOf(0f)
    var startY by mutableFloatStateOf(0f)
    var lastX by mutableFloatStateOf(0f)
    var lastY by mutableFloatStateOf(0f)

    /** DOWN 时重置状态（对齐 Legado PageDelegate.onDown） */
    fun onDown(x: Float, y: Float) {
        isMoved = false
        isCancel = false
        isRunning = false
        direction = PageCurl.Direction.NEXT
        startX = x
        startY = y
        lastX = x
        lastY = y
    }

    /** 计算角落（对齐 Legado SimulationPageDelegate.calcCornerXY） */
    fun calcCornerXY(x: Float, viewWidth: Float, viewHeight: Float) {
        cornerX = if (x <= viewWidth / 2) 0f else viewWidth
        cornerY = if (startY <= viewHeight / 2) 0f else viewHeight
    }

    /** 设置方向 + 角落调整（对齐 Legado SimulationPageDelegate.setDirection） */
    fun setDirection(dir: PageCurl.Direction, viewWidth: Float, viewHeight: Float) {
        direction = dir
        when (dir) {
            PageCurl.Direction.PREV -> {
                // 上一页：卷角固定右下角（对齐 Legado setDirection(PREV) → calcCornerXY(…, viewHeight)
                // 量化后恒为 viewWidth, viewHeight；也与 startSimFlip(PREV) 动画起点一致）。
                // 不要用 viewWidth - startX 的浮点卷角：拖拽越过该角 x 后，已揭示的上一页
                // 会在右侧被当前页重新盖回（渲染错位，与 NEXT 分支 9c33ec1 同类问题）。
                cornerX = viewWidth
                cornerY = viewHeight
            }
            PageCurl.Direction.NEXT -> {
                if (viewWidth / 2 > startX) {
                    // 左侧起手翻下一页仍从右侧卷起；必须把镜像起手点量化为右上/右下角。
                    // 直接保留 viewWidth - startX、startY 会把中部坐标当作卷角，
                    // 与 MOVE 阶段触点贴到底部时闭合出错误的三角裁剪区域。
                    calcCornerXY(viewWidth - startX, viewWidth, viewHeight)
                }
                // else: 已在 DOWN 时由 calcCornerXY 设置，不额外调整
            }
        }
    }

/** 垂直位置调整（对齐 Legado SimulationPageDelegate.onTouch MOVE）。
         *  PREV 方向卷页角固定右下，触摸点强制到底部；
         *  NEXT 方向按 startY 区间分段：上中段吸顶、中下段吸底、其余保持手势原值。 */
        fun adjustTouchY(viewHeight: Float) {
            when (direction) {
                PageCurl.Direction.PREV -> touchY = viewHeight
                PageCurl.Direction.NEXT -> {
                    when {
                        startY > viewHeight / 3 && startY < viewHeight / 2 -> touchY = 1f
                        startY >= viewHeight / 2 && startY < viewHeight * 2 / 3 -> touchY = viewHeight
                        // else：保持手势跟踪的原始 Y 值
                    }
                }
            }
        }

    /** 清除位图并停止动画 */
    fun cleanup() {
        animating = false
        curBitmap?.recycle()
        targetBitmap?.recycle()
        curBitmap = null
        targetBitmap = null
        isRunning = false
        isMoved = false
        settleTarget = -1
    }
}

/**
 * 打断自动卷页动画时应落地的目标页；-1 表示无需翻页（回弹进行中 / 已回到当前页 / 无动画在跑）。
 * 新触摸打断动画时,把「未完成的翻页」提交到动画本要到达的页，而不是让动画中途凭空消失。
 */
fun simFlipSettlePage(simFlip: SimFlipState, currentPage: Int, pageCount: Int): Int {
    val t = simFlip.settleTarget
    return if (t in 0 until pageCount && t != currentPage) t else -1
}

/**
 * 整页阅读视图：HorizontalPager + 五种翻页转场。
 *
 * - pager（平移）：默认滑动；
 * - cover（覆盖）：当前页静止，新页覆盖滑入（graphicsLayer 修正偏移）；
 * - noAnim（无动画）：瞬时 snap 切换；
 * - simulation（仿真）：Canvas 真卷页（PageCurl，从 Legado SimulationPageDelegate 移植）。
 *
 * 分页不留 contentPadding（否则上一页右缘会露出下一页内容），页内留白由
 * [paddingH]/[paddingV] 参数承担（与 BookWindow 排版内容区尺寸严格一致）。
 */
@Composable
fun ReaderPager(
    pagerState: PagerState,
    window: BookWindow,
    flipMode: String,
    palette: ReaderPalette,
    mode: String,
    pageStyle: PageStyle,
    paddingH: Int,
    paddingV: Int,
    statusBarPx: Int,
    navBarPx: Int,
    simFlip: SimFlipState,
    selectionState: TextSelectionState? = null
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = readerPagerScrollEnabled(flipMode),
            contentPadding = PaddingValues(0.dp)
        ) { page ->
            val units = window.pageUnits(page)
            // 该页相对当前页的偏移（-1..1）：向左滑（翻下一页）时 fraction ∈ [-1,0]
            val offset = page - pagerState.currentPage + pagerState.currentPageOffsetFraction
            val modifier = when (flipMode) {
                ReadingSettings.FLIP_COVER -> Modifier.coverPageEffect(offset)
                else -> Modifier
            }
            Box(modifier = modifier.fillMaxSize()) {
                PageRenderer(
                    units = units,
                    mode = mode,
                    palette = palette,
                    pageStyle = pageStyle,
                    paddingH = paddingH,
                    paddingV = paddingV,
                    statusBarPx = statusBarPx,
                    navBarPx = navBarPx,
                    selectionState = selectionState
                )
            }
        }

        // 仿真卷页覆盖层（对齐 Legado：位图=全屏，覆盖层也铺满全屏，边距区域参与卷页不割裂）
        if (simFlip.animating && simFlip.isRunning && flipMode == ReadingSettings.FLIP_SIMULATION) {
            CurlOverlay(simFlip = simFlip)
        }
    }
}

// ── 覆盖（cover）翻页：当前页静止，新页从右侧覆盖滑入 ──
// offset = (page - currentPage) + currentPageOffsetFraction（1.7.6 无官方 API，手工计算）。
// 向左滑（翻下一页）时 currentPageOffsetFraction ∈ [-1,0]：
//   当前页   offset ∈ [-1,0] → 静止
//   下一页   offset ∈ [0,1]  → 从右覆盖滑入
//   更远的页 offset 超出邻域 → 按 settled 距离归位
private fun Modifier.coverPageEffect(offset: Float): Modifier = graphicsLayer {
    when {
        offset < -1f || offset > 1f -> translationX = offset * size.width
        offset < 0f -> translationX = 0f          // 当前页静止（被覆盖）
        else -> {
            translationX = offset * size.width    // 下一页覆盖滑入
            alpha = 1f
        }
    }
}

// ── 仿真卷页覆盖层（对齐 Legado SimulationPageDelegate.onDraw） ──
// NEXT: base=当前页(curBitmap), sheet=下一页(targetBitmap)
// PREV: base=上一页(targetBitmap), sheet=当前页(curBitmap)
@Composable
private fun CurlOverlay(simFlip: SimFlipState) {
    val cur = simFlip.curBitmap
    val target = simFlip.targetBitmap
    if (cur == null || target == null) return
    val curl = simFlip.curl

    val base: Bitmap?
    val sheet: Bitmap?
    when (simFlip.direction) {
        PageCurl.Direction.NEXT -> { base = cur; sheet = target }
        PageCurl.Direction.PREV -> { base = target; sheet = cur }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        curl.start(simFlip.touchX, simFlip.touchY, simFlip.cornerX, simFlip.cornerY)
        drawIntoCanvas { c ->
            curl.draw(
                canvas = c.nativeCanvas,
                base = base,
                sheet = sheet,
                direction = simFlip.direction,
                bgColor = simFlip.bgColor
            )
        }
    }
}

// ── 单页渲染 ──
@Composable
fun PageRenderer(
    units: List<PageUnit>,
    mode: String,
    palette: ReaderPalette,
    pageStyle: PageStyle,
    paddingH: Int,
    paddingV: Int,
    statusBarPx: Int,
    navBarPx: Int,
    selectionState: TextSelectionState? = null
) {
    val density = LocalDensity.current

    // 页内留白（与排版内容区尺寸一致；原 contentPadding 移入页面内部，避免分页间露边）
    // 系统栏用缓存 px 值（不随沉浸式切换变化），与排版几何保持一致，防止切换菜单时重排
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = paddingH.dp)
            .padding(top = with(density) { statusBarPx.toDp() })
            .padding(bottom = with(density) { navBarPx.toDp() })
            .padding(vertical = paddingV.dp),
        contentAlignment = Alignment.TopStart
    ) {
        // 末段段距不渲染（对齐排版器 buildPage 的 realUsed = used - paragraphSpacingPx），
        // 否则渲染高度溢出内容区，底行被盒子裁剪
        val lastParaIdx = units.indexOfLast { it is PageUnit.Para }
        // 自定义 Layout：以无界高度测量子元素，再从上到下放置；
        // 排版高度因 lineHeight 修改 / dp→px 舍入可能微溢 contentHeightPx 几像素，
        // 普通 Column 会以剩余高度=0 戋断末子元素；此 Layout 允许内容微溢至 Box
        // padding 区域（Box 默认不 clip），底行完整可见
        androidx.compose.ui.layout.Layout(
            content = {
                units.forEachIndexed { idx, unit ->
                    when (unit) {
                        is PageUnit.Title -> ReadingChapterTitle(
                            section = unit.section,
                            title = unit.title,
                            palette = palette,
                            pageStyle = pageStyle
                        )
                        is PageUnit.Para -> {
                            val isLastPara = idx == lastParaIdx
                            val key = ParagraphKey(unit.chapterId, unit.paraIndex)
                            if (mode == "zh") {
                                // zh 模式：mainLayout 即中文排版，直接渲染 cnText（无气泡）
                                val bodyStyle = if (unit.lineHeightExtraPx > 0f) pageStyle.body.copy(
                                    lineHeight = (pageStyle.body.lineHeight.value +
                                        with(density) { unit.lineHeightExtraPx.toSp().value }).sp
                                ) else pageStyle.body
                                SelectableParagraphText(
                                    text = unit.cnText,
                                    style = bodyStyle,
                                    color = palette.bodyText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            bottom = if (unit.splitFirst || isLastPara) 0.dp
                                            else with(density) { pageStyle.paragraphSpacingPx.toDp() }
                                        ),
                                    selectionState = selectionState,
                                    paragraphKey = key,
                                    locale = Locale.CHINESE,
                                    highlightColor = palette.selectionHighlight
                                )
                            } else {
                                // en 模式
                                val hasTranslation = unit.enText != null && unit.enText.isNotBlank()
                                if (hasTranslation) {
                                    BilingualParagraph(
                                        englishText = unit.enText!!,
                                        chineseText = unit.cnText,
                                        pairHead = unit.pairHead,
                                        pageStyle = pageStyle,
                                        palette = palette,
                                        lineHeightExtraPx = unit.lineHeightExtraPx,
                                        showSpacer = !isLastPara,
                                        selectionState = selectionState,
                                        paragraphKey = key
                                    )
                                } else {
                                    // 未翻译：原文直接显示（无气泡，避免原文=气泡内容重复）
                                    val bodyStyle = if (unit.lineHeightExtraPx > 0f) pageStyle.body.copy(
                                        lineHeight = (pageStyle.body.lineHeight.value +
                                            with(density) { unit.lineHeightExtraPx.toSp().value }).sp
                                    ) else pageStyle.body
                                    SelectableParagraphText(
                                        text = unit.cnText,
                                        style = bodyStyle,
                                        color = palette.bodyText,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                bottom = if (unit.splitFirst || isLastPara) 0.dp
                                                else with(density) { pageStyle.paragraphSpacingPx.toDp() }
                                            ),
                                        selectionState = selectionState,
                                        paragraphKey = key,
                                        locale = Locale.CHINESE,
                                        highlightColor = palette.selectionHighlight
                                    )
                                }
                            }
                        }
                    }
                }
            },
            measurePolicy = { measurables, constraints ->
                // 以无界高度测量每个子元素，防止 Column 式截断
                val unboundedConstraints = constraints.copy(maxHeight = Int.MAX_VALUE)
                val placeables = measurables.map { it.measure(unboundedConstraints) }
                val width = constraints.maxWidth
                val contentHeight = placeables.sumOf { it.height }
                // 布局高度取 min(内容高度, 父约束最大高度)，溢出部分仍会被绘制
                val layoutHeight = contentHeight.coerceAtMost(constraints.maxHeight)
                layout(width, layoutHeight) {
                    var y = 0
                    placeables.forEach { placeable ->
                        placeable.place(0, y)
                        y += placeable.height
                    }
                }
            }
        )
    }
}

/**
 * 将一页内容离屏渲染为位图（仿真卷页的快照源）。
 *
 * 关键：**不用 StaticLayout 重排**，而是直接绘制分页器 `ChapterPaginator` 排版出的
 * `TextLayoutResult`（`MultiParagraph.paint`）。这样位图与底层 `HorizontalPager` 里
 * Compose `Text` 渲染的是**同一个排版结果**，像素级一致——彻底消除卷页结束
 * 「覆盖层清掉后露出另一套排版」造成的跳变（对齐 Legado 单渲染路径思路）。
 *
 * 位图 = 全屏（含页边距/系统栏），对齐 Legado：整个屏幕参与卷页，边距区域不割裂。
 * 先铺不透明背景覆盖全屏，文本内容从 (padH, statusBar+padV) 偏移开始绘制。
 */
fun renderPageBitmap(
    window: BookWindow,
    page: Int,
    mode: String,
    pageStyle: PageStyle,
    geometry: ReaderPageGeometry,
    palette: ReaderPalette,
    density: androidx.compose.ui.unit.Density,
    bgColorArgb: Int,
    sectionColorArgb: Int,
    measurer: TextMeasurer? = null
): Bitmap? {
    val units = window.pageUnits(page)
    if (units.isEmpty()) return null
    val viewWidthPx = geometry.screenWidthPx
    val viewHeightPx = geometry.screenHeightPx
    val contentWidthPx = geometry.contentWidthPx.toInt()
    val padHPx = geometry.padHPx
    val statusBarPx = geometry.statusBarPx
    val padVPx = geometry.padVPx
    val bitmap = try {
        // 软件位图 + Compose Canvas：直接画 TextLayoutResult（与真实页同源，逐像素一致）
        val image = androidx.compose.ui.graphics.ImageBitmap(
            width = viewWidthPx.coerceAtLeast(1),
            height = viewHeightPx.coerceAtLeast(1),
            hasAlpha = true
        )
        val canvas = androidx.compose.ui.graphics.Canvas(image)

        // 先铺不透明背景覆盖全屏（卷页位图不能透明，否则透出底下真实页叠字）
        val bgPaint = Paint().apply { color = Color(bgColorArgb) }
        canvas.drawRect(Rect(0f, 0f, viewWidthPx.toFloat(), viewHeightPx.toFloat()), bgPaint)

        // 文本偏移：内容区起点 = (padH, statusBar + padV)
        val offsetX = padHPx.toFloat()
        val offsetY = (statusBarPx + padVPx).toFloat()
        canvas.save()
        canvas.translate(offsetX, offsetY)

        // 文本 Paint（按真实页配色）
        val bodyPaint = textPaint(palette.bodyText)
        val titlePaint = textPaint(palette.titleText)
        val sectionPaint = textPaint(Color(sectionColorArgb))

        var cursorY = 0f

        // 末段段距不渲染（对齐排版器 buildPage 的 realUsed = used - paragraphSpacingPx）
        val lastParaIdx = units.indexOfLast { it is PageUnit.Para }
        // 与 Compose 布局保持同一整像素舍入口径：Modifier.padding 内部按 roundToPx(dp) 取整，
        // 位图若用浮点 dp*density 累加，每段（双语 padding + 段距）会比真实页少 1~1.5px，
        // 整页累积后正文逐段偏移（亚像素漂移）。间距统一先 round 成 Int 再累加。
        val padPx = { v: Int -> with(density) { v.dp.roundToPx() } }
        val paragraphSpacingInt = kotlin.math.round(pageStyle.paragraphSpacingPx).toInt()

        units.forEachIndexed { idx, unit ->
            when (unit) {
                is PageUnit.Title -> {
                    cursorY += padPx(ReaderMetrics.TITLE_TOP_DP)
                    unit.sectionLayout?.let { layout ->
                        drawLayout(canvas, layout, sectionPaint, cursorY)
                        cursorY += layout.size.height.toFloat()
                    }
                    // 只有卷名非空才有「卷名 → 章节名」间距（8dp）。必须与 Compose 页
                    // ReadingChapterTitle（section != null）和排版器 measureTitleHeight
                    // 的判定一致：无卷名章节的首页若无条件加这一段间距，位图标题会被
                    // 凭空顶低 ~8dp，触发仿真卷页的瞬间整页文字向下跳一下。
                    if (unit.sectionLayout != null) {
                        cursorY += padPx(ReaderMetrics.SECTION_TITLE_GAP_DP)
                    }
                    unit.titleLayout?.let { layout ->
                        drawLayout(canvas, layout, titlePaint, cursorY)
                        cursorY += layout.size.height.toFloat()
                    }
                    cursorY += padPx(ReaderMetrics.TITLE_BOTTOM_DP)
                }

                is PageUnit.Para -> {
                    val isLastPara = idx == lastParaIdx
                    val hasTranslation = mode == "en" && unit.enText?.isNotBlank() == true
                    // lineHeightExtraPx > 0 时用调整后的 lineHeight 重新测量，
                    // 与 PageRenderer 的 Text(style=bodyStyle) 排版一致，避免卷页时行距跳变
                    // 约束含 minWidth（对齐 Compose Text 的 Modifier.fillMaxWidth()），
                    // 保证 TextAlign.Justify 等对齐方式结果一致
                    val layout = if (unit.lineHeightExtraPx > 0f && measurer != null) {
                        val adjustedStyle = pageStyle.body.copy(
                            lineHeight = (pageStyle.body.lineHeight.value +
                                density.run { unit.lineHeightExtraPx.toSp().value }).sp
                        )
                        val text = if (mode == "zh") unit.cnText else (unit.enText ?: unit.cnText)
                        val cw = contentWidthPx.coerceAtLeast(1)
                        measurer.measure(
                            text = AnnotatedString(text),
                            style = adjustedStyle,
                            constraints = Constraints(minWidth = cw, maxWidth = cw)
                        )
                    } else unit.mainLayout
                    // en 模式双语对：对齐 BilingualParagraph 的 4dp top/bottom padding（roundToPx）
                    if (hasTranslation) {
                        cursorY += padPx(ReaderMetrics.BILINGUAL_PAD_DP)
                    }
                    layout?.let {
                        drawLayout(canvas, it, bodyPaint, cursorY)
                        cursorY += it.size.height.toFloat()
                    }
                    if (hasTranslation) {
                        cursorY += padPx(ReaderMetrics.BILINGUAL_PAD_DP)
                        // 气泡指示器（对齐 BilingualParagraph 的 18×6dp 小矩形），
                        // 仅首片段 pairHead 显示，续段不重复
                        if (unit.pairHead) {
                            val bubbleW = padPx(ReaderMetrics.BUBBLE_WIDTH_DP)
                            val bubbleH = padPx(ReaderMetrics.BUBBLE_HEIGHT_DP)
                            val bubbleX = contentWidthPx - bubbleW - padPx(ReaderMetrics.BUBBLE_END_DP)
                            val bubbleY = (cursorY - bubbleH - padPx(ReaderMetrics.BUBBLE_BOTTOM_DP)).toInt()
                            val bubblePaint = Paint().apply {
                                isAntiAlias = true
                                color = palette.sourceBubble
                            }
                            canvas.drawRect(
                                Rect(
                                    bubbleX.toFloat(), bubbleY.toFloat(),
                                    (bubbleX + bubbleW).toFloat(), (bubbleY + bubbleH).toFloat()
                                ),
                                bubblePaint
                            )
                        }
                    }
                    cursorY += if (unit.splitFirst || isLastPara) 0f else paragraphSpacingInt.toFloat()
                }
            }
        }

        canvas.restore()
        image.asAndroidBitmap()
    } catch (_: Exception) {
        null
    }
    return bitmap
}

/** 文本 Paint：抗锯齿 + 颜色。 */
private fun textPaint(color: Color): Paint = Paint().apply {
    isAntiAlias = true
    this.color = color
}

/**
 * 绘制一段 [androidx.compose.ui.text.TextLayoutResult]（底部对齐的 lineHeightExtra
 * 已在 layout 的行高里体现）。直接 `MultiParagraph.paint`，与 Compose Text 完全同源。
 */
private fun drawLayout(
    canvas: androidx.compose.ui.graphics.Canvas,
    layout: androidx.compose.ui.text.TextLayoutResult,
    paint: Paint,
    top: Float
) {
    canvas.save()
    canvas.translate(0f, top)
    layout.multiParagraph.paint(canvas = canvas, color = paint.color)
    canvas.restore()
}
