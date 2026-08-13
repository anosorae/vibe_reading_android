package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.theme.VibeColors

/**
 * 仿真卷页覆盖层状态：
 * - animating=true 时 CurlOverlay 显示；touchX/touchY 为当前触摸点（内容区坐标），
 *   拖拽手势实时更新（跟手），自动动画由插值协程逐帧写入；
 * - base/sheet 位图为动画期间的页面快照。
 */
@Stable
class SimFlipState {
    val curl = PageCurl()
    var animating by mutableStateOf(false)
    var touchX by mutableFloatStateOf(0f)
    var touchY by mutableFloatStateOf(0f)
    var cornerX by mutableFloatStateOf(0f)
    var cornerY by mutableFloatStateOf(0f)
    var direction by mutableStateOf(PageCurl.Direction.NEXT)
    var base: Bitmap? by mutableStateOf(null)
    var sheet: Bitmap? by mutableStateOf(null)
    var bgColor by mutableIntStateOf(0xFFFFFFFF.toInt())
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
    expandedPairs: Set<Pair<Long, Int>>,
    paddingH: Int,
    paddingV: Int,
    onToggleCn: (Pair<Long, Int>) -> Unit,
    simFlip: SimFlipState
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
                    expandedPairs = expandedPairs,
                    paddingH = paddingH,
                    paddingV = paddingV,
                    onToggleCn = onToggleCn
                )
            }
        }

        // 仿真卷页覆盖层：快照 base/sheet 页，按触摸点插值逐帧绘制真卷页。
        // 画布与页面内容区严格对齐（边距一致），位图尺寸一致。
        if (simFlip.animating && flipMode == ReadingSettings.FLIP_SIMULATION) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = paddingH.dp, vertical = paddingV.dp),
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

// ── 仿真卷页覆盖层：快照 base/sheet 页，按触摸点逐帧绘制真卷页（跟手：触摸点实时更新） ──
@Composable
private fun CurlOverlay(simFlip: SimFlipState) {
    val base = simFlip.base
    val sheet = simFlip.sheet
    val curl = simFlip.curl
    val touchX = simFlip.touchX
    val touchY = simFlip.touchY

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 更新卷页触摸点（拖拽每帧 / 动画插值每帧）
        curl.start(touchX, touchY, simFlip.cornerX, simFlip.cornerY)
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
    expandedPairs: Set<Pair<Long, Int>>,
    paddingH: Int,
    paddingV: Int,
    onToggleCn: (Pair<Long, Int>) -> Unit
) {
    val density = LocalDensity.current
    // 页内留白（与排版内容区尺寸一致；原 contentPadding 移入页面内部，避免分页间露边）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = paddingH.dp, vertical = paddingV.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            units.forEach { unit ->
                when (unit) {
                    is PageUnit.Title -> PageTitleBlock(
                        section = unit.section,
                        title = unit.title,
                        status = unit.status,
                        isDark = isDark,
                        pageStyle = pageStyle
                    )
                    is PageUnit.Para -> {
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
                                        bottom = if (unit.splitFirst) 0.dp
                                        else with(density) { pageStyle.paragraphSpacingPx.toDp() }
                                    )
                            )
                        } else {
                            val key = unit.chapterId to unit.paraIndex
                            val expanded = key in expandedPairs
                            PageBilingualParagraph(
                                englishText = unit.enText ?: unit.cnText,
                                chineseText = unit.cnText,
                                expanded = expanded,
                                onToggle = { onToggleCn(key) },
                                pageStyle = pageStyle,
                                isDark = isDark,
                                lineHeightExtraPx = unit.lineHeightExtraPx,
                                cnIndent = unit.cnLayout != null && pageStyle.cn.textIndent != null,
                                paragraphSpacingPx = pageStyle.paragraphSpacingPx
                            )
                        }
                    }
                }
            }
        }
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
        val cnPaint = textPaint(if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Charcoal)
        val titlePaint = textPaint(if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Ink)
        val sectionPaint = textPaint(Color(sectionColorArgb))

        var cursorY = 0f

        units.forEach { unit ->
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
                    if (mode == "zh") {
                        unit.mainLayout?.let { layout ->
                            drawLayout(canvas, layout, bodyPaint, cursorY)
                            cursorY += layout.size.height.toFloat()
                        }
                        cursorY += if (unit.splitFirst) 0f else pageStyle.paragraphSpacingPx
                    } else {
                        unit.mainLayout?.let { layout ->
                            drawLayout(canvas, layout, bodyPaint, cursorY)
                            cursorY += layout.size.height.toFloat()
                        }
                        if (unit.cnLayout != null) {
                            cursorY += with(density) { 6.dp.toPx() }
                            unit.cnLayout?.let { layout ->
                                drawLayout(canvas, layout, cnPaint, cursorY)
                                cursorY += layout.size.height.toFloat()
                            }
                        }
                        cursorY += if (unit.splitFirst) 0f else pageStyle.paragraphSpacingPx
                    }
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
    status: Int,
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
        Spacer(Modifier.height(12.dp))
        ReaderStatusBadge(status = status)
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

/** 双语对（分页版）：英文 + 可展开中文；展开状态由外部持有以便整章排版。 */
@Composable
fun PageBilingualParagraph(
    englishText: String,
    chineseText: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    pageStyle: PageStyle,
    isDark: Boolean,
    lineHeightExtraPx: Float,
    cnIndent: Boolean,
    paragraphSpacingPx: Float
) {
    val originalColor = if (isDark) VibeColors.Stone else VibeColors.WarmGray
    val density = LocalDensity.current
    fun extraLineHeight(base: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.TextUnit =
        if (lineHeightExtraPx > 0f) {
            (base.value + with(density) { lineHeightExtraPx.toSp().value }).sp
        } else base
    val enStyle = if (lineHeightExtraPx > 0f)
        pageStyle.body.copy(lineHeight = extraLineHeight(pageStyle.body.lineHeight)) else pageStyle.body
    val cnStyle = if (lineHeightExtraPx > 0f)
        pageStyle.cn.copy(lineHeight = extraLineHeight(pageStyle.cn.lineHeight)) else pageStyle.cn

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = englishText,
            style = enStyle,
            color = if (isDark) VibeColors.Cream.copy(alpha = 0.9f) else VibeColors.Charcoal,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(top = 4.dp, bottom = 4.dp)
        )
        if (expanded) {
            Text(
                text = chineseText,
                style = cnStyle,
                color = originalColor,
                modifier = Modifier
                    .fillMaxWidth()
                    // 排版已带首行缩进时不再叠加缩进 padding，否则中文会双重缩进
                    .then(
                        if (cnIndent) Modifier.padding(top = 2.dp, bottom = 8.dp)
                        else Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp)
                    )
            )
        }
        Spacer(Modifier.height(with(density) { paragraphSpacingPx.toDp() }))
    }
}
