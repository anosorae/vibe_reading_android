package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.log.AppLog
import com.vibereading.app.ui.reader.ReaderContentInteractions
import com.vibereading.app.ui.reader.ReaderLayoutSpec
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.ReaderPageGeometry
import com.vibereading.app.ui.reader.readerPagerScrollEnabled
import com.vibereading.app.ui.reader.components.BilingualParagraph
import com.vibereading.app.ui.reader.components.ParagraphKey
import com.vibereading.app.ui.reader.components.ReadingChapterTitle
import com.vibereading.app.ui.reader.components.ReadingIllustrationBlock
import com.vibereading.app.ui.reader.components.SelectableParagraphText
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.ui.theme.VibeColors
import java.util.Locale

/** 卷页位图延迟回收时长：两帧（60fps），确保渲染线程重放完最后一帧。 */
private const val SIM_FLIP_BITMAP_RECYCLE_DELAY_MS = 34L

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

    // 本手势 DOWN 是否刚打断并提交了一次翻页：打断后该手势的左右点按视为「打断确认」，
    // 不再重复翻页（否则 PREV 右滑被打断后又被点按翻回原页，右下角反复卷页乱闪、反直觉）。
    var downSettledFlip by mutableStateOf(false)

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
        downSettledFlip = false
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

    /** 清除位图引用并停止动画；位图本体延迟两帧回收（见 [deferRecycle]）。 */
    fun cleanup() {
        animating = false
        deferRecycle(curBitmap)
        deferRecycle(targetBitmap)
        curBitmap = null
        targetBitmap = null
        isRunning = false
        isMoved = false
        settleTarget = -1
    }

    // 惰性创建：纯 JVM 单测构造 SimFlipState 不触碰 android.os.Handler
    private val recycleHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // 立即摘除位图引用，recycle 推迟两帧：硬件加速下 UI 线程录制完成后，渲染线程仍可能
    // 重放引用该位图的上一帧 DisplayList，立即 recycle 会以 "Canvas: trying to use a
    // recycled bitmap" 崩溃；两帧后该帧必已上屏。
    private fun deferRecycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        recycleHandler.postDelayed({ bitmap.recycle() }, SIM_FLIP_BITMAP_RECYCLE_DELAY_MS)
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

/** 卷页自动动画速度基准：时长按位移占全屏宽/高的比例缩放。 */
internal const val SIM_FLIP_ANIMATION_SPEED_MS = 200

// 点按翻页与拖拽抬手共用同一手感区间（此前两处 coerceIn 区间不一致导致手感漂移）
private const val SIM_FLIP_MIN_DURATION_MS = 60L
private const val SIM_FLIP_MAX_DURATION_MS = 300L

/** 卷页自动动画时长：按位移比例缩放并钳制到统一手感区间。 */
internal fun simFlipDurationMs(dx: Float, dy: Float, viewWidth: Float, viewHeight: Float): Long {
    val raw = if (dx != 0f) SIM_FLIP_ANIMATION_SPEED_MS * kotlin.math.abs(dx) / viewWidth
    else SIM_FLIP_ANIMATION_SPEED_MS * kotlin.math.abs(dy) / viewHeight
    return raw.toLong().coerceIn(SIM_FLIP_MIN_DURATION_MS, SIM_FLIP_MAX_DURATION_MS)
}

/** 抬手动画落地页决策：回弹（isCancel）或目标越界返回 -1 不翻页，否则返回目标页。 */
internal fun simFlipCommitPage(isCancel: Boolean, target: Int, pageCount: Int): Int =
    if (!isCancel && target in 0 until pageCount) target else -1

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
    mode: String,
    layout: ReaderLayoutSpec,
    interactions: ReaderContentInteractions,
    simFlip: SimFlipState
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
                    layout = layout,
                    interactions = interactions
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
    layout: ReaderLayoutSpec,
    interactions: ReaderContentInteractions
) {
    val density = LocalDensity.current
    val pageStyle = layout.pageStyle
    val palette = layout.palette
    val contentWidthPx = layout.geometry.contentWidthPx.toInt()

    // 页内留白（与排版内容区尺寸一致；原 contentPadding 移入页面内部，避免分页间露边）
    // 系统栏用缓存 px 值（不随沉浸式切换变化），与排版几何保持一致，防止切换菜单时重排
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = layout.paddingH.dp)
            .padding(top = with(density) { layout.geometry.statusBarPx.toDp() })
            .padding(bottom = with(density) { layout.geometry.navBarPx.toDp() })
            .padding(vertical = layout.paddingV.dp),
        contentAlignment = Alignment.TopStart
    ) {
        // 版面计划：与卷页位图 renderPageBitmap 共用同一个 PageLayoutPlanner，段距与块序一致。
        // 段距为 0 的块（末个可间距块、被拆开段落的首片段）由计划统一判定，
        // 本处不再自行推导——此前两侧各写一份，只能靠注释维持一致。
        val blocks = PageLayoutPlanner.plan(
            units = units,
            style = pageStyle,
            density = density,
            contentWidthPx = contentWidthPx.toFloat(),
            mode = mode
        )
        // 自定义 Layout：以无界高度测量子元素，再从上到下放置；
        // 排版高度因 lineHeight 修改 / dp→px 舍入可能微溢 contentHeightPx 几像素，
        // 普通 Column 会以剩余高度=0 戋断末子元素；此 Layout 允许内容微溢至 Box
        // padding 区域（Box 默认不 clip），底行完整可见
        androidx.compose.ui.layout.Layout(
            content = {
                units.forEachIndexed { idx, unit ->
                    val block = blocks[idx]
                    when (unit) {
                        is PageUnit.Title -> ReadingChapterTitle(
                            section = unit.section,
                            title = unit.title,
                            palette = palette,
                            pageStyle = pageStyle
                        )
                        is PageUnit.Image -> {
                            ReadingIllustrationBlock(
                                link = IllustrationLink(
                                    path = unit.path,
                                    widthPx = unit.displayWidthPx.toInt().coerceAtLeast(1),
                                    heightPx = unit.displayHeightPx.toInt().coerceAtLeast(1)
                                ),
                                bottomSpacing = with(density) { block.spacingBelowPx.toDp() },
                                fixedDisplayHeightPx = unit.displayHeightPx,
                                onClick = interactions.onIllustrationClick?.let { cb -> { cb(unit.path) } }
                            )
                        }
                        is PageUnit.Para -> {
                            val key = ParagraphKey(unit.chapterId, unit.paraIndex)
                            if (mode == "zh") {
                                // zh 模式：mainLayout 即中文侧排版，直接渲染 cnText（无气泡）；
                                // 英文书译文未就绪回退英文原文（ADR-003）；
                                // 续段顶格与排版器测量口径一致（同段跨页延续无首行缩进）
                                val bodyText = unit.cnText.ifBlank { unit.enText.orEmpty() }
                                val baseStyle = if (unit.continuation) pageStyle.body.copy(textIndent = null) else pageStyle.body
                                val bodyStyle = if (unit.lineHeightExtraPx > 0f) baseStyle.copy(
                                    lineHeight = (pageStyle.body.lineHeight.value +
                                        with(density) { unit.lineHeightExtraPx.toSp().value }).sp
                                ) else baseStyle
                                SelectableParagraphText(
                                    text = bodyText,
                                    style = bodyStyle,
                                    color = palette.bodyText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = with(density) { block.spacingBelowPx.toDp() }),
                                    selectionState = interactions.selectionState,
                                    paragraphKey = key,
                                    locale = Locale.CHINESE,
                                    highlightColor = palette.selectionHighlight,
                                    // 中文两端对齐：段落在下一页延续时本片段末行仍需拉伸
                                    contentWidthPx = contentWidthPx,
                                    justifyLastLine = unit.paragraphContinues
                                )
                            } else {
                                // en 模式
                                val hasTranslation = unit.enText?.isNotBlank() == true && unit.cnText.isNotBlank()
                                if (hasTranslation) {
                                    BilingualParagraph(
                                        englishText = unit.enText!!,
                                        chineseText = unit.cnText,
                                        continuation = unit.continuation,
                                        pageStyle = pageStyle,
                                        palette = palette,
                                        lineHeightExtraPx = unit.lineHeightExtraPx,
                                        showSpacer = block.spacingBelowPx > 0f,
                                        selectionState = interactions.selectionState,
                                        paragraphKey = key,
                                        // 气泡触控区右向延伸到屏幕右缘（与滚动模式同口径）
                                        bubbleEdgeExtendDp = (layout.paddingH + ReaderMetrics.BUBBLE_END_DP).toFloat(),
                                        contentWidthPx = contentWidthPx,
                                        bubbleEnabled = interactions.bubbleEnabled,
                                        // 段落在下一页延续时本片段末行仍需拉伸（与分页测量/仿真位图同口径）
                                        justifyLastLine = unit.paragraphContinues
                                    )
                                } else {
                                    // 双语未就绪：显示存在的一侧（中文书=中文原文回退，英文书=英文原文），
                                    // 无气泡，避免气泡内容与正文重复；续段顶格与排版器测量口径一致
                                    val baseStyle = if (unit.continuation) pageStyle.body.copy(textIndent = null) else pageStyle.body
                                    val bodyStyle = if (unit.lineHeightExtraPx > 0f) baseStyle.copy(
                                        lineHeight = (pageStyle.body.lineHeight.value +
                                            with(density) { unit.lineHeightExtraPx.toSp().value }).sp
                                    ) else baseStyle
                                    SelectableParagraphText(
                                        text = unit.enText ?: unit.cnText,
                                        style = bodyStyle,
                                        color = palette.bodyText,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = with(density) { block.spacingBelowPx.toDp() }),
                                        selectionState = interactions.selectionState,
                                        paragraphKey = key,
                                        locale = Locale.CHINESE,
                                        highlightColor = palette.selectionHighlight,
                                        // 中文书未译回退中文原文时同样两端对齐（英文文本被 CjkJustify 门控跳过）
                                        contentWidthPx = contentWidthPx,
                                        justifyLastLine = unit.paragraphContinues
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
    measurer: TextMeasurer? = null,
    imageResolver: ((path: String, targetWidth: Int) -> Bitmap?)? = null
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

        // 文本 Paint（按真实页配色）；卷名色与 ReadingChapterTitle 同源取 palette.accent，
        // 不接受外部传入——历史上位图侧接过独立的 accent 参数，导致仿真翻页卷名变色跳变
        val bodyPaint = textPaint(palette.bodyText)
        val titlePaint = textPaint(palette.titleText)
        val sectionPaint = textPaint(palette.accent)

        // 版面几何由共享计划决定（与 Compose 页同一个 PageLayoutPlanner）：
        // 本函数只负责「按计划绘制」，不再自行累加间距、不再各自推导气泡矩形。
        val blocks = PageLayoutPlanner.plan(
            units = units,
            style = pageStyle,
            density = density,
            contentWidthPx = contentWidthPx.toFloat(),
            mode = mode
        )

        blocks.forEach { block ->
            when (val unit = block.unit) {
                is PageUnit.Title -> {
                    val gapPx = with(density) { ReaderMetrics.SECTION_TITLE_GAP_DP.dp.roundToPx() }
                    val topPx = with(density) { ReaderMetrics.TITLE_TOP_DP.dp.roundToPx() }.toFloat()
                    var y = block.yPx + topPx
                    unit.sectionLayout?.let { layout ->
                        drawLayout(canvas, layout, sectionPaint, y)
                        y += layout.size.height.toFloat() + gapPx
                    }
                    unit.titleLayout?.let { layout -> drawLayout(canvas, layout, titlePaint, y) }
                }

                is PageUnit.Image -> {
                    // 卷页位图同步解码（BookImageStore 内存缓存命中时零开销）；失败画占位框
                    val bmp = imageResolver?.invoke(
                        unit.path,
                        unit.displayWidthPx.toInt().coerceAtLeast(1)
                    )
                    val left = (contentWidthPx - unit.displayWidthPx) / 2f
                    val wInt = unit.displayWidthPx.toInt().coerceAtLeast(1)
                    val hInt = unit.displayHeightPx.toInt().coerceAtLeast(1)
                    // createScaledBitmap 尺寸相同时返回原对象，不额外分配
                    val scaled = bmp?.let {
                        try {
                            android.graphics.Bitmap.createScaledBitmap(it, wInt, hInt, true)
                                .asImageBitmap()
                        } catch (e: Exception) {
                            AppLog.put("卷页位图缩放插图失败: ${unit.path}", e)
                            null
                        }
                    }
                    if (scaled != null) {
                        canvas.drawImage(scaled, Offset(left, block.yPx), Paint())
                    } else {
                        val placeholder = Paint().apply {
                            isAntiAlias = true
                            color = palette.bodyText.copy(alpha = 0.10f)
                        }
                        canvas.drawRect(
                            Rect(left, block.yPx, left + unit.displayWidthPx, block.yPx + unit.displayHeightPx),
                            placeholder
                        )
                    }
                }

                is PageUnit.Para -> {
                    // 分页器已生成包含 bottomJustify、续段顶格与 CjkJustifier 的最终布局；
                    // 位图只消费该布局，不在渲染期重新测量，避免计划块高仍是自然高度而后续块错位。
                    unit.mainLayout?.let { drawLayout(canvas, it, bodyPaint, block.contentTopPx) }
                    // 气泡指示器（18×6dp 小矩形，圆角 3dp）：每个带译文的片段段尾都显示
                    // （ADR-004），几何来自计划，与 Compose 页同一组常量
                    block.bubble?.let { bubble ->
                        val bubblePaint = Paint().apply {
                            isAntiAlias = true
                            color = palette.bubble
                        }
                        canvas.drawRoundRect(
                            bubble.leftPx, bubble.topPx,
                            bubble.leftPx + bubble.widthPx, bubble.topPx + bubble.heightPx,
                            bubble.cornerRadiusPx, bubble.cornerRadiusPx,
                            bubblePaint
                        )
                    }
                }
            }
        }

        canvas.restore()
        image.asAndroidBitmap()
    } catch (e: Exception) {
        AppLog.put("仿真卷页位图渲染失败 page=$page mode=$mode", e)
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
