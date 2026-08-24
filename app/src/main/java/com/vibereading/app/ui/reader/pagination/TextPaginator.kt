package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.content.ReadingParagraph

/**
 * 行级排版模型（对齐 Legado TextPageFactory / TextLine 思路，ADR-001）：
 *
 * - **每章一个 [ChapterPaginator]**：该章全部内容同步排成 [TextPage] 列表常驻，
 *   由 `BookWindow`（章窗口模型）持有 ±1 窗口；
 * - **真实页宽测量**：`measureLayout` 传 `Constraints(maxWidth = contentWidth)`，
 *   行高/切段基于真实换行（修复旧实现无约束测量导致「所见≠所排」）；
 * - **mode 感知排版**：zh 模式测量 cnText 分页，en 模式测量 enText 分页（无则回退 cnText），
 *   双语对原子化不可拆，放不下整段移下一页；单段超高退化为按行切分（首片段可显示气泡，
 *   续段无气泡）；zh/en 模式页面布局独立，切换模式需重建窗口重新排版；
 * - 每页排版后按 `bottomJustify` 把剩余高度均匀分到各行（末行沉底，对齐 Legado
 *   `TextPage.upLinesPosition`）；
 * - [TextLayoutResult] 挂在 [PageUnit] 上：渲染层 `Text` 以同文本同样式渲染
 *   （同一测量引擎天然一致），仿真卷页位图直接 `layout.draw()` 绘制（逐像素一致）。
 */

/** 排版样式（TextStyle 由调用方在 remember 中持有，保证引用稳定）。 */
data class PageStyle(
    val body: TextStyle,        // 正文（en 英文 / zh 中文）
    val cn: TextStyle,          // 双语对中的中文原文（小一号）
    val title: TextStyle,       // 章节标题（大号粗体）
    val paragraphSpacingPx: Float,
    val bottomJustify: Boolean = true, // 底部对齐：页内行距重分布使末行沉底
    val titleMode: Int = ReadingSettings.TITLE_MODE_LEFT // 0 左 / 1 居中 / 2 隐藏
) {
    companion object {
        /** 由 ReadingSettings 构造排版样式（分页与滚动共用同一口径，对齐 Legado 微信读书预设）。
         *  cnFont/enFont 为中英分体字体；正文 body 在 en 模式显示英文（用 enFont）、zh 模式显示中文（用 cnFont），
         *  中文气泡 cnFont 与标题恒用中文字体。 */
        fun of(settings: ReadingSettings, density: Density, mode: String = "zh", cnFont: FontFamily? = null, enFont: FontFamily? = null): PageStyle {
            // 中文字体优先自定义/内置，否则系统字体（fontFamilyOf 按名字符串）；英文字体跟随中文或独立
            val cnFamily = cnFont ?: fontFamilyOf(settings.fontFamily)
            val enFamily = enFont ?: cnFamily
            // 首行缩进：以正文字号换算绝对 sp 值，卷标/标题字号不同时仍视觉对齐
            // （em 单位相对于当前字号，标题大字号 2em > 正文 2em > 卷标小字号 2em，不对齐）
            val indentSp = settings.indentEm * settings.fontSize
            val bodyIndent = if (indentSp > 0f) TextIndent(
                firstLine = indentSp.sp
            ) else null
            // 卷标/标题缩进与正文视觉对齐（用正文字号换算的绝对值，非各自字号的 em）
            val cnIndent = if (indentSp > 0f) TextIndent(
                firstLine = indentSp.sp
            ) else null
            val align = if (settings.justify) TextAlign.Justify else TextAlign.Start
            return PageStyle(
                body = TextStyle(
                    fontFamily = if (mode == "en") enFamily else cnFamily,
                    fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * 1.6 + settings.lineSpacing).sp,
                    letterSpacing = settings.letterSpacing.em,
                textIndent = bodyIndent,
                textAlign = align
            ),
            cn = TextStyle(
                    fontFamily = cnFamily,
                    fontSize = (settings.fontSize * 0.875).sp,
                    lineHeight = (settings.fontSize * 1.5 + settings.lineSpacing).sp,
                    letterSpacing = settings.letterSpacing.em,
                    textIndent = cnIndent,
                    textAlign = align
                ),
                title = TextStyle(
                    fontFamily = cnFamily,
                    fontSize = (settings.fontSize + 4).sp,
                    lineHeight = ((settings.fontSize + 4) * 1.3f).sp,
                    fontWeight = FontWeight.Bold,
                    textIndent = cnIndent  // 与卷标/正文首行对齐
                ),
                paragraphSpacingPx = with(density) { settings.paragraphSpacing.dp.toPx() },
                bottomJustify = settings.bottomJustify,
                titleMode = settings.titleMode
            )
        }
    }
}

/** 章节内排版条目：章节标题 或 段落（en 模式为双语对）。 */
sealed class FlowItem {
    abstract val chapterId: Long

    data class Title(
        override val chapterId: Long,
        val section: String?,
        val title: String,
        val status: Int,
        val errorMessage: String? = null
    ) : FlowItem()

    data class Para(
        override val chapterId: Long,
        val paraIndex: Int,
        val cnText: String,
        val enText: String?,     // en 模式下英文译文；未翻译为 null（渲染回退原文）
        val sourceStartOffset: Int = 0,
        val sourceEndOffset: Int = sourceStartOffset + cnText.length
    ) : FlowItem() {
        fun toReadingParagraph(): ReadingParagraph = ReadingParagraph(
            index = paraIndex,
            sourceText = cnText,
            translatedText = enText,
            sourceStartOffset = sourceStartOffset,
            sourceEndOffset = sourceEndOffset
        )
    }
}

/** 单页显示单元（排版完成后的结果）。 */
sealed class PageUnit {
    abstract val chapterId: Long

    data class Title(
        override val chapterId: Long,
        val section: String?,
        val title: String,
        val status: Int,
        val errorMessage: String? = null,
        val sectionLayout: TextLayoutResult? = null, // 卷名布局（仿真位图用）
        val titleLayout: TextLayoutResult? = null    // 章节名布局（仿真位图用）
    ) : PageUnit()

    data class Para(
        override val chapterId: Long,
        val paraIndex: Int,
        val cnText: String,            // zh：正文；en：中文原文（弹窗数据源）
        val enText: String?,           // en 模式下译文（拆分的续段为 null）
        val splitFirst: Boolean = false,   // 拆分子段的第一段（不加段距，视觉上与续段相连）
        val pairHead: Boolean = true,      // en 首片段可显示原文气泡；续段无气泡
        val lineCount: Int = 0,            // 本单元本页实际行数（底部对齐用）
        val lineHeightExtraPx: Float = 0f, // 底部对齐分配给每行的额外高度
        val mainLayout: TextLayoutResult? = null,  // zh=正文布局 / en=英文布局
        val sourceStartOffset: Int = 0,
        val sourceEndOffset: Int = sourceStartOffset + cnText.length
    ) : PageUnit()
}

/** 单页排版结果。 */
data class TextPage(
    val chapterId: Long,
    val indexInChapter: Int,
    val units: List<PageUnit>,
    val usedHeightPx: Float,
    /** 本页覆盖的原文范围（标题页或空页为 null）。 */
    val sourceStartOffset: Int? = units
        .filterIsInstance<PageUnit.Para>()
        .filter { it.sourceStartOffset >= 0 && it.sourceEndOffset >= it.sourceStartOffset }
        .minOfOrNull { it.sourceStartOffset },
    val sourceEndOffset: Int? = units
        .filterIsInstance<PageUnit.Para>()
        .filter { it.sourceStartOffset >= 0 && it.sourceEndOffset >= it.sourceStartOffset }
        .maxOfOrNull { it.sourceEndOffset }
)

/** 切段续排状态：超长段的第一段已排完，剩余文本随 [para] 进入下一页。 */
private data class PendingChunk(
    val para: FlowItem.Para,
    val text: String,
    val isHead: Boolean = true
)

/**
 * 单章排版器：把「章节标题 + 段落」按内容区尺寸（真实页宽）全量排成 [TextPage]。
 * 构造即全量排版（章节受 STATUS_TOO_LONG 上限约束，排版有界）。
 */
class ChapterPaginator(
    val chapterId: Long,
    val items: List<FlowItem>,
    private val style: PageStyle,
    private val mode: String,            // "zh" | "en"
    private val contentWidthPx: Float,
    private val contentHeightPx: Float,
    private val measurer: TextMeasurer,
    private val density: Float = 1f          // display density，用于 dp→px 转换
) {

    var pages: List<TextPage> = emptyList()
        private set

    /** en 模式双语对额外占位：PageBilingualParagraph 的 4.dp top + 4.dp bottom padding（px） */
    private val bilingualPadPx = ReaderMetrics.bilingualPadPx(density)

    init {
        pages = layoutAll()
    }

    fun pageUnits(page: Int): List<PageUnit> = pages.getOrNull(page)?.units ?: emptyList()

    /** 返回包含原文偏移的页；边界偏移归入覆盖它的第一项。 */
    fun pageForOffset(sourceOffset: Int): Int? {
        val offset = sourceOffset.coerceAtLeast(0)
        return pages.indexOfFirst { page ->
            page.sourceStartOffset?.let { start ->
                val end = page.sourceEndOffset ?: start
                offset in start until end || (offset == start && start == end)
            } == true
        }.takeIf { it >= 0 }
            ?: pages.indexOfLast { it.sourceStartOffset != null }
                .takeIf { it >= 0 && offset >= (pages[it].sourceStartOffset ?: 0) }
    }

    // ── 排版核心 ──

    private fun layoutAll(): List<TextPage> {
        val result = ArrayList<TextPage>()
        var units = ArrayList<PageUnit>()
        var used = 0f
        var pending: PendingChunk? = null
        var pos = 0

        fun pageDone() {
            if (units.isEmpty()) return
            result.add(buildPage(units, used, result.size))
            units = ArrayList()
            used = 0f
        }

        while (true) {
            val chunk = pending
            pending = null
            val isChunk = chunk != null
            val item = if (isChunk) chunk!!.para else items.getOrNull(pos) ?: break

            when (item) {
                is FlowItem.Title -> {
                    if (style.titleMode == ReadingSettings.TITLE_MODE_HIDDEN) {
                        pos++
                        continue
                    }
                    val sectionLayout = item.section?.let { measureLayout(it, style.cn) }
                    val titleLayout = measureLayout(item.title, style.title)
                    val h = measureTitleHeight(sectionLayout, titleLayout)
                    if (units.isNotEmpty() && used + h > contentHeightPx) pageDone()
                    units += PageUnit.Title(item.chapterId, item.section, item.title, item.status, item.errorMessage, sectionLayout, titleLayout)
                    used += h
                    pos++
                }

                is FlowItem.Para -> {
                    if (mode == "zh") {
                        // zh 模式：以中文原文排版分页
                        val text = if (isChunk) chunk!!.text else item.cnText
                        val layout = measureLayout(text, style.body)
                        val h = layout.size.height.toFloat()
                        if (h > contentHeightPx && layout.lineCount > 1) {
                            // 长段落：本页剩余放得下首行就在剩余高度内切段，否则先翻页用整页高
                            if (units.isNotEmpty() && contentHeightPx - used < layout.getLineBottom(0)) pageDone()
                            val bound = (contentHeightPx - used).coerceAtLeast(layout.getLineBottom(0))
                            val (c1, c2) = splitLayout(text, layout, bound)
                            val l1 = if (c1 != text) measureLayout(c1, style.body) else layout
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, c1, null,
                                splitFirst = true, lineCount = l1.lineCount, mainLayout = l1,
                                sourceStartOffset = item.sourceStartOffset,
                                sourceEndOffset = item.sourceEndOffset
                            )
                            used += l1.size.height.toFloat()
                            if (c2.isNotBlank()) {
                                pending = PendingChunk(item, c2)
                                pageDone() // 续段放下一页
                            } else {
                                pos++
                            }
                        } else if (h > contentHeightPx) {
                            // 极端：单行超高（防御）。单行无法按行切分时允许本页溢出，
                            // 但必须保留完整 continuation，不能用固定长度截断正文。
                            val continuation = text
                            val tl = measureLayout(continuation, style.body)
                            if (units.isNotEmpty()) pageDone()
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, continuation, null,
                                lineCount = tl.lineCount, mainLayout = tl,
                                sourceStartOffset = item.sourceStartOffset,
                                sourceEndOffset = item.sourceEndOffset
                            )
                            used += tl.size.height.toFloat()
                            pos++
                        } else {
                            if (units.isNotEmpty() && h > contentHeightPx - used) pageDone()
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, text, null,
                                lineCount = layout.lineCount, mainLayout = layout,
                                sourceStartOffset = item.sourceStartOffset,
                                sourceEndOffset = item.sourceEndOffset
                            )
                            used += h + style.paragraphSpacingPx
                            pos++
                        }
                    } else {
                        // en 模式：以英文译文排版分页（中文原文通过弹窗显示，不参与排版测量）
                        val en = if (isChunk) chunk!!.text
                            else item.enText?.takeIf { it.isNotBlank() } ?: item.cnText
                        val hasTranslation = !isChunk && item.enText?.isNotBlank() == true
                        val head = if (isChunk) chunk!!.isHead else true
                        val enLayout = measureLayout(en, style.body)
                        val h = enLayout.size.height.toFloat()
                        // 双语对额外占位：PageBilingualParagraph 的 4dp top + 4dp bottom padding
                        val padPx = if (hasTranslation) bilingualPadPx else 0f
                        val remaining = contentHeightPx - used
                        if (h + padPx > contentHeightPx) {
                            // 单段超高：按行切分英文，不丢内容
                            if (units.isNotEmpty() && remaining < enLayout.getLineBottom(0) + padPx) pageDone()
                            val bound = (contentHeightPx - used - padPx)
                                .coerceAtLeast(enLayout.getLineBottom(0))
                            val (c1, c2) = splitLayout(en, enLayout, bound)
                            val l1 = if (c1 != en) measureLayout(c1, style.body) else enLayout
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, item.cnText, c1,
                                splitFirst = true, pairHead = head,
                                lineCount = l1.lineCount,
                                mainLayout = l1,
                                sourceStartOffset = item.sourceStartOffset,
                                sourceEndOffset = item.sourceEndOffset
                            )
                            used += l1.size.height.toFloat() + padPx
                            if (c2.isNotBlank()) {
                                pending = PendingChunk(item, c2, isHead = false)
                                pageDone()
                            } else {
                                pos++
                            }
                        } else {
                            if (units.isNotEmpty() && h + padPx > remaining) {
                                pageDone() // 整段移到下一页
                                continue
                            }
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, item.cnText, en,
                                pairHead = head,
                                lineCount = enLayout.lineCount,
                                mainLayout = enLayout,
                                sourceStartOffset = item.sourceStartOffset,
                                sourceEndOffset = item.sourceEndOffset
                            )
                            used += h + padPx + style.paragraphSpacingPx
                            pos++
                        }
                    }
                }
            }
        }

        pageDone()
        // 末页不做底部对齐：文字按自然密度排，多余留白留在页底
        // （底部对齐只对「满页」沉底有意义；末页文字少时均匀拉伸会拉出超大行距）
        if (style.bottomJustify && result.isNotEmpty() && result.last().units.any { it is PageUnit.Para }) {
            val last = result.last()
            val fixed = last.units.map { u ->
                if (u is PageUnit.Para && u.lineHeightExtraPx > 0f) u.copy(lineHeightExtraPx = 0f) else u
            }
            result[result.size - 1] = last.copy(units = fixed)
        }
        return result
    }

    /**
     * 页完成：按 bottomJustify 把剩余高度均匀分到各行（末行沉底）。
     * 含章节标题的页不底部对齐（标题块本身留有留白，拉伸正文会突兀）。
     */
    private fun buildPage(units: List<PageUnit>, used: Float, indexInChapter: Int): TextPage {
        // 末段段距不占页高（渲染时页尾无段距），否则短页会假性溢出/无 slack
        val realUsed = if (units.lastOrNull() is PageUnit.Para) used - style.paragraphSpacingPx else used
        val page = TextPage(chapterId, indexInChapter, units, realUsed)
        if (!style.bottomJustify) return page
        if (units.any { it is PageUnit.Title }) return page
        val totalLines = units.sumOf { (it as? PageUnit.Para)?.lineCount ?: 0 }
        val slack = contentHeightPx - realUsed
        if (totalLines <= 1 || slack <= 0f) return page
        val extra = slack / totalLines
        val adjusted = units.map { u ->
            if (u is PageUnit.Para && u.lineCount > 0) u.copy(lineHeightExtraPx = extra) else u
        }
        return page.copy(units = adjusted)
    }

    /** zh 长段落/en 超高超长段按行切分：返回 (本页子段, 续段)。 */
    private fun splitLayout(text: String, layout: TextLayoutResult, maxHeightPx: Float): Pair<String, String> {
        if (maxHeightPx <= 0f || layout.lineCount <= 1) return text to ""
        var lastFit = -1
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) <= maxHeightPx) lastFit = line else break
        }
        if (lastFit < 0) lastFit = 0
        if (lastFit >= layout.lineCount - 1) return text to ""
        val splitIndex = layout.getLineEnd(lastFit, visibleEnd = true)
        val c1 = text.substring(0, splitIndex)
        // 续段不做 trimStart：行尾空白可能被 getLineEnd 裁在断点前后，trim 会吞掉原文字符
        val c2 = text.substring(splitIndex).trimStart('\n', '\r')
        return if (c1.isBlank()) text to "" else c1 to c2
    }

    // ── 测量（真实页宽约束，minWidth=maxWidth 对齐 Compose Text 的 fillMaxWidth） ──

    private fun measureLayout(text: String, textStyle: TextStyle): TextLayoutResult {
        val cw = contentWidthPx.toInt().coerceAtLeast(1)
        return measurer.measure(
            text = AnnotatedString(text),
            style = textStyle,
            constraints = Constraints(minWidth = cw, maxWidth = cw)
        )
    }

    /** 标题块高度：顶部留白 + 卷名 + 章节名（与 PageTitleBlock 一致，无徽章）。 */
    private fun measureTitleHeight(sectionLayout: TextLayoutResult?, titleLayout: TextLayoutResult?): Float {
        val sectionH = sectionLayout?.size?.height?.toFloat()
            ?.plus(ReaderMetrics.SECTION_TITLE_GAP_DP * density) ?: 0f  // section→title 间距 = 8dp
        val titleH = titleLayout?.size?.height?.toFloat() ?: 0f
        return ReaderMetrics.TITLE_TOP_DP * density + sectionH + titleH + ReaderMetrics.TITLE_BOTTOM_DP * density  // 顶部无留白，底部间距 = 44dp
    }

    companion object {
    }
}

/** 根据 ReadingSettings 的字体名字符串解析 FontFamily。
 *  "default"（及未知值）跟随设备系统 UI 字体（Typeface.DEFAULT，体现厂商/系统设置的字体）；
 *  仅 "sans-serif/monospace/serif" 等显式族名映射到对应风格。 */
fun fontFamilyOf(name: String): FontFamily = when (name) {
    "sans-serif" -> FontFamily.SansSerif
    "monospace" -> FontFamily.Monospace
    "serif" -> FontFamily.Serif
    else -> FontFamily.Default
}
