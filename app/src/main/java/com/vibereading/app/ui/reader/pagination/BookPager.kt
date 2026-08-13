package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.VibeDarkColors

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
                // 上一页：不出现对角
                if (startX > viewWidth / 2) {
                    cornerX = startX
                    cornerY = viewHeight
                } else {
                    cornerX = viewWidth - startX
                    cornerY = viewHeight
                }
            }
            PageCurl.Direction.NEXT -> {
                if (viewWidth / 2 > startX) {
                    cornerX = viewWidth - startX
                    cornerY = startY
                }
                // else: 已在 DOWN 时由 calcCornerXY 设置，不额外调整
            }
        }
    }

    /** 垂直位置调整（对齐 Legado SimulationPageDelegate.onTouch MOVE） */
    fun adjustTouchY(viewHeight: Float) {
        if ((startY > viewHeight / 3 && startY < viewHeight * 2 / 3)
            || direction == PageCurl.Direction.PREV
        ) {
            touchY = viewHeight
        }
        if (startY > viewHeight / 3 && startY < viewHeight / 2
            && direction == PageCurl.Direction.NEXT
        ) {
            touchY = 1f
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
    }
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
    isDark: Boolean,
    mode: String,
    pageStyle: PageStyle,
    paddingH: Int,
    paddingV: Int,
    simFlip: SimFlipState,
    isStreaming: Boolean = false,
    activeChapterId: Long? = null
) {
    val density = LocalDensity.current
    val padH = with(density) { paddingH.dp.toPx() }
    val padV = with(density) { paddingV.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = flipMode != ReadingSettings.FLIP_NO_ANIM &&
                flipMode != ReadingSettings.FLIP_SIMULATION,
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
                    isDark = isDark,
                    pageStyle = pageStyle,
                    textColor = if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Charcoal,
                    paddingH = paddingH,
                    paddingV = paddingV,
                    isStreaming = isStreaming,
                    activeChapterId = activeChapterId
                )
            }
        }

        // 仿真卷页覆盖层（对齐 Legado：画布与页面内容区严格对齐，位图尺寸一致）
        // 边到边模式：先扣除系统栏再留用户边距，与 PageRenderer 保持一致
        if (simFlip.animating && simFlip.isRunning && flipMode == ReadingSettings.FLIP_SIMULATION) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = paddingH.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = paddingV.dp),
                contentAlignment = Alignment.TopStart
            ) {
                CurlOverlay(simFlip = simFlip)
            }
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
    isDark: Boolean,
    pageStyle: PageStyle,
    textColor: Color,
    paddingH: Int,
    paddingV: Int,
    isStreaming: Boolean = false,
    activeChapterId: Long? = null
) {
    val density = LocalDensity.current
    // 检测本页所属章节是否已翻译（en 模式下未翻译章节不显示气泡）
    val titleStatus = units.filterIsInstance<PageUnit.Title>().firstOrNull()?.status
    val chapterId = units.filterIsInstance<PageUnit.Title>().firstOrNull()?.chapterId
    val chapterTranslated = titleStatus == Chapter.STATUS_DONE
    val showEnStatusHint = mode == "en" && titleStatus != null && !chapterTranslated

    // 页内留白（与排版内容区尺寸一致；原 contentPadding 移入页面内部，避免分页间露边）
    // 边到边模式：先扣除系统栏再留用户边距，保证内容不被状态栏/导航栏遮挡
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = paddingH.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
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
                        is PageUnit.Title -> PageTitleBlock(
                            section = unit.section,
                            title = unit.title,
                            isDark = isDark,
                            pageStyle = pageStyle
                        )
                        is PageUnit.Para -> {
                            val isLastPara = idx == lastParaIdx
                            if (mode == "zh") {
                                val enStyle = if (unit.lineHeightExtraPx > 0f) pageStyle.body.copy(
                                    lineHeight = (pageStyle.body.lineHeight.value +
                                        with(density) { unit.lineHeightExtraPx.toSp().value }).sp
                                ) else pageStyle.body
                                Text(
                                    text = unit.cnText,
                                    style = enStyle,
                                    color = textColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            bottom = if (unit.splitFirst || isLastPara) 0.dp
                                            else with(density) { pageStyle.paragraphSpacingPx.toDp() }
                                        )
                                )
                            } else {
                                // en 模式：未翻译章节(enText==null)只显示原文，不显示气泡
                                val hasTranslation = unit.enText != null && unit.enText.isNotBlank()
                                if (hasTranslation) {
                                    PageBilingualParagraph(
                                        englishText = unit.enText!!,
                                        chineseText = unit.cnText,
                                        pairHead = unit.pairHead,
                                        pageStyle = pageStyle,
                                        isDark = isDark,
                                        lineHeightExtraPx = unit.lineHeightExtraPx,
                                        paragraphSpacingPx = pageStyle.paragraphSpacingPx,
                                        isLastPara = isLastPara
                                    )
                                } else {
                                    // 未翻译：原文直接显示（无气泡，避免原文=气泡内容重复）
                                    val enStyle = if (unit.lineHeightExtraPx > 0f) pageStyle.body.copy(
                                        lineHeight = (pageStyle.body.lineHeight.value +
                                            with(density) { unit.lineHeightExtraPx.toSp().value }).sp
                                    ) else pageStyle.body
                                    Text(
                                        text = unit.cnText,
                                        style = enStyle,
                                        color = textColor,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                bottom = if (unit.splitFirst || isLastPara) 0.dp
                                                else with(density) { pageStyle.paragraphSpacingPx.toDp() }
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
                // en 模式下未翻译章节：标题下方显示状态提示
                if (showEnStatusHint) {
                    Spacer(Modifier.height(16.dp))
                    // 区分「翻译中」（正在流式翻译）和「翻译中断」（上次中断）
                    val isActiveStreaming = isStreaming && chapterId == activeChapterId
                    val hintText = when {
                        isActiveStreaming && titleStatus == Chapter.STATUS_IN_PROGRESS -> "翻译中…"
                        titleStatus == Chapter.STATUS_IN_PROGRESS -> "翻译中断"
                        titleStatus == Chapter.STATUS_FAILED -> "翻译失败"
                        titleStatus == Chapter.STATUS_PENDING -> "等待翻译"
                        titleStatus == Chapter.STATUS_TOO_LONG -> "章节过长"
                        else -> "未翻译"
                    }
                    val hintColor = when {
                        isActiveStreaming && titleStatus == Chapter.STATUS_IN_PROGRESS -> VibeColors.Sage
                        titleStatus == Chapter.STATUS_IN_PROGRESS -> VibeColors.BlueMuted
                        titleStatus == Chapter.STATUS_FAILED -> VibeColors.RedMuted
                        titleStatus == Chapter.STATUS_TOO_LONG -> VibeColors.Amber
                        else -> VibeColors.WarmGray
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(hintText, color = hintColor, fontSize = 13.sp)
                        // 正在流式翻译时显示进度指示器
                        if (isActiveStreaming && titleStatus == Chapter.STATUS_IN_PROGRESS) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = VibeColors.Sage
                            )
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
 * 位图 = 内容区（不含页边距），先铺不透明背景再绘制文本。
 */
fun renderPageBitmap(
    window: BookWindow,
    page: Int,
    mode: String,
    isDark: Boolean,
    pageStyle: PageStyle,
    density: androidx.compose.ui.unit.Density,
    pageWidthPx: Int,
    pageHeightPx: Int,
    bgColorArgb: Int,
    sectionColorArgb: Int
): Bitmap? {
    val units = window.pageUnits(page)
    if (units.isEmpty()) return null
    val bitmap = try {
        // 软件位图 + Compose Canvas：直接画 TextLayoutResult（与真实页同源，逐像素一致）
        val image = androidx.compose.ui.graphics.ImageBitmap(
            width = pageWidthPx.coerceAtLeast(1),
            height = pageHeightPx.coerceAtLeast(1),
            hasAlpha = true
        )
        val canvas = androidx.compose.ui.graphics.Canvas(image)

        // 先铺不透明背景（卷页位图不能透明，否则透出底下真实页叠字）
        val bgPaint = Paint().apply { color = Color(bgColorArgb) }
        canvas.drawRect(Rect(0f, 0f, pageWidthPx.toFloat(), pageHeightPx.toFloat()), bgPaint)

        // 文本 Paint（按真实页配色）
        val bodyPaint = textPaint(if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Charcoal)
        val titlePaint = textPaint(if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Ink)
        val sectionPaint = textPaint(Color(sectionColorArgb))

        var cursorY = 0f

        // 末段段距不渲染（对齐排版器 buildPage 的 realUsed = used - paragraphSpacingPx）
        val lastParaIdx = units.indexOfLast { it is PageUnit.Para }

        units.forEachIndexed { idx, unit ->
            when (unit) {
                is PageUnit.Title -> {
                    cursorY += with(density) { 24.dp.toPx() }
                    unit.sectionLayout?.let { layout ->
                        drawLayout(canvas, layout, sectionPaint, cursorY)
                        cursorY += layout.size.height.toFloat()
                    }
                    cursorY += with(density) { 8.dp.toPx() }
                    unit.titleLayout?.let { layout ->
                        drawLayout(canvas, layout, titlePaint, cursorY)
                        cursorY += layout.size.height.toFloat()
                    }
                    cursorY += with(density) { 12.dp.toPx() }
                    // 章节状态徽章（与 PageTitleBlock 的 ReaderStatusBadge 近似）
                    val badgePaint = Paint().apply {
                        isAntiAlias = true
                        color = VibeColors.Sage.copy(alpha = 0.15f)
                    }
                    canvas.drawRect(Rect(0f, cursorY, 90f, cursorY + 26f), badgePaint)
                    cursorY += 26f + 24f
                }

                is PageUnit.Para -> {
                    val isLastPara = idx == lastParaIdx
                    // en 模式只画英文正文（中文原文通过弹窗显示，不画入位图）
                    unit.mainLayout?.let { layout ->
                        drawLayout(canvas, layout, bodyPaint, cursorY)
                        cursorY += layout.size.height.toFloat()
                    }
                    cursorY += if (unit.splitFirst || isLastPara) 0f else pageStyle.paragraphSpacingPx
                }
            }
        }

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

@Composable
private fun PageTitleBlock(
    section: String?,
    title: String,
    isDark: Boolean,
    pageStyle: PageStyle
) {
    val titleAlign = when (pageStyle.titleMode) {
        ReadingSettings.TITLE_MODE_CENTER -> TextAlign.Center
        else -> TextAlign.Start
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        if (section != null) {
            Text(
                section,
                style = pageStyle.cn,
                color = MaterialTheme.colorScheme.primary,
                textAlign = titleAlign,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
        Text(
            title,
            style = pageStyle.title.copy(textAlign = titleAlign),
            color = if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Ink,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 章节状态徽章（与滚动模式共用语义）。 */
@Composable
fun ReaderStatusBadge(status: Int) {
    val (text, color) = when (status) {
        Chapter.STATUS_PENDING -> "待翻译" to VibeColors.Sand
        Chapter.STATUS_IN_PROGRESS -> "翻译中" to VibeColors.BlueMuted
        Chapter.STATUS_DONE -> "已翻译" to VibeColors.Sage
        Chapter.STATUS_FAILED -> "翻译失败" to VibeColors.RedMuted
        Chapter.STATUS_TOO_LONG -> "过长" to VibeColors.Amber
        else -> "未知" to VibeColors.Stone
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 双语对（分页版）：英文 + 尾部气泡（点击弹窗查看中文原文）。
 *  气泡与弹窗均为视觉叠加层，不影响排版测量，无需重排。 */
@Composable
fun PageBilingualParagraph(
    englishText: String,
    chineseText: String,
    pairHead: Boolean,
    pageStyle: PageStyle,
    isDark: Boolean,
    lineHeightExtraPx: Float,
    paragraphSpacingPx: Float,
    isLastPara: Boolean = false
) {
    var showPopup by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val bubbleColor = if (isDark) VibeColors.SiennaLight.copy(alpha = 0.25f)
    else VibeColors.Sienna.copy(alpha = 0.3f)
    val popupBgColor = if (isDark) VibeDarkColors.Surface else VibeColors.Parchment
    val cnTextColor = if (isDark) VibeColors.Stone else VibeColors.WarmGray
    val borderColor = if (isDark) VibeColors.Sand.copy(alpha = 0.3f) else VibeColors.Sand

    fun extraLineHeight(base: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.TextUnit =
        if (lineHeightExtraPx > 0f) {
            (base.value + with(density) { lineHeightExtraPx.toSp().value }).sp
        } else base
    val enStyle = if (lineHeightExtraPx > 0f)
        pageStyle.body.copy(lineHeight = extraLineHeight(pageStyle.body.lineHeight)) else pageStyle.body

    Column(modifier = Modifier.fillMaxWidth()) {
        // 英文段落 + 尾部气泡（仅首片段 pairHead 显示气泡，续段不重复）
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = englishText,
                style = enStyle,
                color = if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Charcoal,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp)
            )
            if (chineseText.isNotBlank() && pairHead) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 2.dp)
                        .size(width = 18.dp, height = 6.dp)
                        .clickable { showPopup = !showPopup }
                ) {
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = bubbleColor,
                        modifier = Modifier.fillMaxSize()
                    ) {}
                }
                if (showPopup) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, with(density) { (-4).dp.roundToPx() }),
                        onDismissRequest = { showPopup = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = popupBgColor,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = chineseText,
                                style = pageStyle.cn,
                                color = cnTextColor,
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawBehind {
                                        drawLine(
                                            color = borderColor,
                                            start = Offset(0f, 0f),
                                            end = Offset(0f, size.height),
                                            strokeWidth = 2.dp.toPx()
                                        )
                                    }
                                    .then(
                                        if (pageStyle.cn.textIndent != null)
                                            Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                                        else
                                            Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
        // 末段不加段距（对齐排版器 buildPage 的 realUsed = used - paragraphSpacingPx）
        if (!isLastPara) {
            Spacer(Modifier.height(with(density) { paragraphSpacingPx.toDp() }))
        }
    }
}
