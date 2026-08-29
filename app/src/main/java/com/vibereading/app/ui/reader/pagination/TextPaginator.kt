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
 * - **mode 感知排版**：zh 模式测量 cnText 分页，en 模式测量 enText 分页（无则回退 cnText）；
 *   段落放不下本页剩余空间时按行边界切开填满当前页（ADR-004），续段顶格续排到下一页
 *   （无首行缩进，双语对每个带译文的片段都显示中文气泡）；zh/en 模式页面布局独立，
 *   切换模式需重建窗口重新排版；
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
        val cnText: String,          // 中文侧文本（ADR-003 插槽）：zh 正文 / en 弹窗数据源；英文书译文未就绪为空
        val enText: String?,         // 英文侧文本（ADR-003 插槽）：英文书恒为原文；未翻译段落为 null（渲染回退中文侧）
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

    /**
     * 插图段（ADR-002 D3/D5）：固定高度内容单元，排版按链接内尺寸整图适配
     * 内容区（超高整体缩小到单页内，不跨页拆条带）。
     */
    data class Image(
        override val chapterId: Long,
        val paraIndex: Int,
        /** 插图链接键 `{bookId}/{fileName}`，渲染端解析到私有目录。 */
        val path: String,
        /** 链接内声明的原始像素尺寸。 */
        val imageWidthPx: Int,
        val imageHeightPx: Int,
        val sourceStartOffset: Int = 0,
        val sourceEndOffset: Int = sourceStartOffset
    ) : FlowItem()
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
        val cnText: String,            // 中文侧文本（ADR-003 插槽）：zh 正文 / en 弹窗数据源
        val enText: String?,           // 英文侧文本（ADR-003 插槽）：拆分的续段为 null
        val splitFirst: Boolean = false,   // 拆分子段的第一段（不加段距，视觉上与续段相连）
        val continuation: Boolean = false, // 同段跨页续排片段：顶格无首行缩进（渲染端同口径）
        val paragraphContinues: Boolean = false, // 段落在下一页延续：本片段末行仍需两端对齐（CjkJustify 用）
        val lineCount: Int = 0,            // 本单元本页实际行数（底部对齐用）
        val lineHeightExtraPx: Float = 0f, // 底部对齐分配给每行的额外高度
        val mainLayout: TextLayoutResult? = null,  // zh=正文布局 / en=英文布局
        val sourceStartOffset: Int = 0,
        val sourceEndOffset: Int = sourceStartOffset + cnText.length
    ) : PageUnit()

    /** 插图单元：排版期已按内容区整图适配好的显示尺寸。 */
    data class Image(
        override val chapterId: Long,
        val paraIndex: Int,
        val path: String,
        /** 适配后的显示像素尺寸（宽 ≤ 内容宽，高 ≤ 内容高）。 */
        val displayWidthPx: Float,
        val displayHeightPx: Float,
        val sourceStartOffset: Int = 0,
        val sourceEndOffset: Int = sourceStartOffset
    ) : PageUnit()
}

/** 单页排版结果。 */
data class TextPage(
    val chapterId: Long,
    val indexInChapter: Int,
    val units: List<PageUnit>,
    val usedHeightPx: Float,
    /** 本页覆盖的原文范围（标题页或空页为 null）；段落与插图单元都参与范围计算。 */
    val sourceStartOffset: Int? = pageSourceRange(units).first,
    val sourceEndOffset: Int? = pageSourceRange(units).second
)

/** 页内带原文范围的单元（Para/Image）的 [start, end) 汇总。 */
private fun pageSourceRange(units: List<PageUnit>): Pair<Int?, Int?> {
    var start: Int? = null
    var end: Int? = null
    units.forEach { unit ->
        when (unit) {
            is PageUnit.Para -> {
                if (unit.sourceStartOffset >= 0 && unit.sourceEndOffset >= unit.sourceStartOffset) {
                    start = minOf(start ?: unit.sourceStartOffset, unit.sourceStartOffset)
                    end = maxOf(end ?: unit.sourceEndOffset, unit.sourceEndOffset)
                }
            }
            is PageUnit.Image -> {
                if (unit.sourceStartOffset >= 0 && unit.sourceEndOffset >= unit.sourceStartOffset) {
                    start = minOf(start ?: unit.sourceStartOffset, unit.sourceStartOffset)
                    end = maxOf(end ?: unit.sourceEndOffset, unit.sourceEndOffset)
                }
            }
            else -> {}
        }
    }
    return start to end
}

/** 切段续排状态：段落按行切分填满当前页后的剩余文本，随 [para] 续排到下一页（顶格）。
 *  [sourceBase] 是 [text] 首字符对应的原文 offset，续排片段据此记录真实子区间
 *  （页区间互斥是 offset→页 唯一映射的前提，拆分片段不得沿用整段范围）。 */
private data class PendingChunk(
    val para: FlowItem.Para,
    val text: String,
    val sourceBase: Int
)

/**
 * 单章排版器：把「章节标题 + 段落」按内容区尺寸（真实页宽）排成 [TextPage] 列表。
 * 支持增量排版（[lazyLayout]=true + [layoutUntil]）：先只排到恢复 offset 所在页即出
 * 首帧（EPUB 长章打开提速），剩余内容由后续调用从断点续排；断点续排的产出与
 * 一次性整章排版完全一致（有单测对拍保证）。
 */
class ChapterPaginator(
    val chapterId: Long,
    val items: List<FlowItem>,
    private val style: PageStyle,
    private val mode: String,            // "zh" | "en"
    private val contentWidthPx: Float,
    private val contentHeightPx: Float,
    private val measurer: TextMeasurer,
    private val density: Float = 1f,         // display density，用于 dp→px 转换
    lazyLayout: Boolean = false              // true = 不在构造期排版，由 layoutUntil 增量推进
) {

    /** 已排版页快照（后台排版推进时原子换入，读侧免锁）。 */
    @Volatile
    var pages: List<TextPage> = emptyList()
        private set

    /** 整章是否已排完（前缀排版后由续排补齐置 true）。 */
    @Volatile
    var layoutComplete = false
        private set

    // ── 增量排版断点状态（同章串行访问：BookWindow 的 buildingIds 单飞行保证） ──
    private val typedPages = ArrayList<TextPage>()
    private var pos = 0
    private var pending: PendingChunk? = null
    private var units = ArrayList<PageUnit>()
    private var used = 0f

    /** en 模式双语对额外占位：PageBilingualParagraph 的 4.dp top + 4.dp bottom padding（px） */
    private val bilingualPadPx = ReaderMetrics.bilingualPadPx(density)

    init {
        if (!lazyLayout) layoutUntil(Int.MAX_VALUE)
    }

    fun pageUnits(page: Int): List<PageUnit> = pages.getOrNull(page)?.units ?: emptyList()

    /**
     * 已排版范围是否覆盖原文 offset [offset]（[Int.MAX_VALUE] 仅整章排完时满足）。
     * 必须严格大于：offset 恰为页边界时（第 k 页起始 = 第 k-1 页结束），offset 属于
     * 第 k 页（pageForOffset 起点闭区间），第 k-1 页排完不等于覆盖，必须把第 k 页排出。
     */
    fun coversOffset(offset: Int): Boolean {
        if (layoutComplete) return true
        if (offset >= Int.MAX_VALUE) return false
        val lastEnd = pages.lastOrNull { it.sourceEndOffset != null }?.sourceEndOffset ?: Int.MIN_VALUE
        return lastEnd > offset
    }

    /**
     * 增量排版：从断点继续，至少排到覆盖原文 offset [targetOffset] 所在页即停
     * （首帧按需排版：恢复位置在第 k 页时只需排前 k 页，剩余由后续调用续排补齐）；
     * [Int.MAX_VALUE] 表示排完整章。多次调用幂等续排。
     */
    fun layoutUntil(targetOffset: Int) {
        if (layoutComplete) return
        while (true) {
            val chunk = pending
            pending = null
            val isChunk = chunk != null
            val item = if (isChunk) chunk!!.para else items.getOrNull(pos)
            if (item == null) {
                finishLayout()
                return
            }

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

                is FlowItem.Image -> {
                    // 插图（ADR-002 D5）：整图适配内容区，超高整体缩小，绝不跨页拆条带；
                    // 剩余高度放不下则整图移下一页（适配后高 ≤ contentHeightPx，必能放下）
                    val (dw, dh) = fitImage(item.imageWidthPx, item.imageHeightPx, contentWidthPx, contentHeightPx)
                    if (units.isNotEmpty() && dh > contentHeightPx - used) pageDone()
                    units += PageUnit.Image(
                        item.chapterId, item.paraIndex, item.path, dw, dh,
                        sourceStartOffset = item.sourceStartOffset,
                        sourceEndOffset = item.sourceEndOffset
                    )
                    used += dh + style.paragraphSpacingPx
                    pos++
                }

                is FlowItem.Para -> {
                    if (mode == "zh") {
                        // zh 模式：以中文侧文本排版分页；英文书译文未就绪回退英文原文（ADR-003）
                        val cont = isChunk
                        val text = if (isChunk) chunk!!.text
                            else item.cnText.ifBlank { item.enText.orEmpty() }
                        // 拆分片段的原文子区间基准：新段落从段首起，续段从切分点起
                        // （页区间互斥是 offset→页 唯一映射的前提，见 pageForOffset）
                        val base = if (isChunk) chunk!!.sourceBase else item.sourceStartOffset
                        // 排版文本长度与原文区间长度可能不一致（译文文本按原文 offset 近似定位），
                        // 子区间统一收口在段落原文范围内，保证互斥且不越界
                        fun sourceEnd(len: Int) = (base + len).coerceIn(item.sourceStartOffset, item.sourceEndOffset)
                        // 续段顶格：同段跨页的延续文本不带首行缩进（渲染端同口径）
                        val paraStyle = if (cont) style.body.copy(textIndent = null) else style.body
                        val layout = measureLayout(text, paraStyle)
                        val h = layout.size.height.toFloat()
                        val remaining = contentHeightPx - used
                        if (h > contentHeightPx || (units.isNotEmpty() && h > remaining)) {
                            // 放不下整段：本页剩余放得下首行就在剩余高度内按行切段填满当前页
                            //（ADR-004），否则先翻页再用整页高切段续排；翻页后整段可能直接放下
                            if (units.isNotEmpty() && remaining < layout.getLineBottom(0)) {
                                pageDone()
                                continue
                            }
                            val bound = if (units.isNotEmpty()) remaining else contentHeightPx
                            val (c1, c2, c2Index) = splitLayout(text, layout, bound)
                            val l1 = if (c1 != text) measureLayout(c1, paraStyle, justifyLastLine = c2.isNotBlank()) else layout
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, c1, null,
                                splitFirst = true, continuation = cont,
                                paragraphContinues = c2.isNotBlank(),
                                lineCount = l1.lineCount, mainLayout = l1,
                                sourceStartOffset = base,
                                sourceEndOffset = sourceEnd(c1.length)
                            )
                            used += l1.size.height.toFloat()
                            if (c2.isNotBlank()) {
                                pending = PendingChunk(item, c2, sourceEnd(c2Index))
                                pageDone() // 续段放下一页
                            } else {
                                pos++
                            }
                        } else {
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, text, null,
                                continuation = cont,
                                lineCount = layout.lineCount, mainLayout = layout,
                                sourceStartOffset = base,
                                sourceEndOffset = if (cont) sourceEnd(text.length) else item.sourceEndOffset
                            )
                            used += h + style.paragraphSpacingPx
                            pos++
                        }
                    } else {
                        // en 模式：以英文侧文本排版分页（中文侧通过弹窗显示，不参与排版测量）；
                        // 双语对只有两侧都在才成立（英文书译文未就绪时按单语英文原文排版，无气泡）
                        val cont = isChunk
                        val en = if (isChunk) chunk!!.text
                            else item.enText?.takeIf { it.isNotBlank() } ?: item.cnText
                        // 拆分片段的原文子区间基准：新段落从段首起，续段从切分点起
                        // （页区间互斥是 offset→页 唯一映射的前提，见 pageForOffset）
                        val base = if (isChunk) chunk!!.sourceBase else item.sourceStartOffset
                        // 排版文本长度与原文区间长度可能不一致（译文文本按原文 offset 近似定位），
                        // 子区间统一收口在段落原文范围内，保证互斥且不越界
                        fun sourceEnd(len: Int) = (base + len).coerceIn(item.sourceStartOffset, item.sourceEndOffset)
                        // 切分片段与整段同口径：双语对的每个片段（含续段）渲染时都加
                        // 4dp top/bottom padding 和中文气泡（ADR-004），测量必须同样计入，
                        // 否则每片段凭空多出 8dp，页面内容逐段下移、仿真卷页起手上下断层
                        val hasTranslation = item.cnText.isNotBlank() && item.enText?.isNotBlank() == true
                        // 续段顶格：同段跨页的延续文本不带首行缩进（渲染端同口径）
                        val paraStyle = if (cont) style.body.copy(textIndent = null) else style.body
                        val enLayout = measureLayout(en, paraStyle)
                        val h = enLayout.size.height.toFloat()
                        // 双语对额外占位：PageBilingualParagraph 的 4dp top + 4dp bottom padding
                        val padPx = if (hasTranslation) bilingualPadPx else 0f
                        val totalH = h + padPx
                        val remaining = contentHeightPx - used
                        if (totalH > contentHeightPx || (units.isNotEmpty() && totalH > remaining)) {
                            // 放不下整段（ADR-004）：本页剩余放得下首行就在剩余高度内按行切分
                            // 填满当前页（每个带译文的片段都可显示中文气泡），否则先翻页再切
                            if (units.isNotEmpty() && remaining < enLayout.getLineBottom(0) + padPx) {
                                pageDone()
                                continue
                            }
                            val bound = ((if (units.isNotEmpty()) remaining else contentHeightPx) - padPx)
                                .coerceAtLeast(enLayout.getLineBottom(0))
                            val (c1, c2, c2Index) = splitLayout(en, enLayout, bound)
                            val l1 = if (c1 != en) measureLayout(c1, paraStyle, justifyLastLine = c2.isNotBlank()) else enLayout
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, item.cnText, c1,
                                splitFirst = true, continuation = cont,
                                paragraphContinues = c2.isNotBlank(),
                                lineCount = l1.lineCount,
                                mainLayout = l1,
                                sourceStartOffset = base,
                                sourceEndOffset = sourceEnd(c1.length)
                            )
                            used += l1.size.height.toFloat() + padPx
                            if (c2.isNotBlank()) {
                                pending = PendingChunk(item, c2, sourceEnd(c2Index))
                                pageDone()
                            } else {
                                pos++
                            }
                        } else {
                            units += PageUnit.Para(
                                item.chapterId, item.paraIndex, item.cnText, en,
                                continuation = cont,
                                lineCount = enLayout.lineCount,
                                mainLayout = enLayout,
                                sourceStartOffset = base,
                                sourceEndOffset = if (cont) sourceEnd(en.length) else item.sourceEndOffset
                            )
                            used += h + padPx + style.paragraphSpacingPx
                            pos++
                        }
                    }
                }
            }

            // 每落一页检查是否已覆盖目标 offset（严格大于：offset 所在页必须已排出，
            // 页边界 offset 属于下一页）——覆盖即挂起，剩余内容保留断点由续排补齐
            val lastEnd = typedPages.lastOrNull { it.sourceEndOffset != null }?.sourceEndOffset ?: Int.MIN_VALUE
            if (lastEnd > targetOffset) {
                pages = typedPages.toList()
                return
            }
        }
    }

    /** 章节排完：末页落盘 + 末页不做底部对齐（末页文字按自然密度排，多余留白留在页底；
     *  底部对齐只对「满页」沉底有意义，末页文字少时均匀拉伸会拉出超大行距）。 */
    private fun finishLayout() {
        pageDone()
        if (style.bottomJustify && typedPages.isNotEmpty() && typedPages.last().units.any { it is PageUnit.Para }) {
            val last = typedPages.last()
            val fixed = last.units.map { u ->
                if (u is PageUnit.Para && u.lineHeightExtraPx > 0f) u.copy(lineHeightExtraPx = 0f) else u
            }
            typedPages[typedPages.size - 1] = last.copy(units = fixed)
            pages = typedPages.toList()
        }
        layoutComplete = true
    }

    private fun pageDone() {
        if (units.isEmpty()) return
        typedPages += buildPage(units, used, typedPages.size)
        units = ArrayList()
        used = 0f
        pages = typedPages.toList()
    }

    /** 返回包含原文偏移的页；边界偏移归入覆盖它的第一项（即起点等于该 offset 的页，
     *  前一页区间为 [start, end) 不含 end）。前提：同段跨页拆分的各页 source 区间
     *  互斥（拆分片段记录真实子区间），否则重叠区间的映射不唯一。 */
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

    /**
     * 页完成：按 bottomJustify 把剩余高度均匀分到各行（末行沉底）。
     * 含章节标题的页不底部对齐（标题块本身留有留白，拉伸正文会突兀）。
     */
    private fun buildPage(units: List<PageUnit>, used: Float, indexInChapter: Int): TextPage {        // 末段段距不占页高（渲染时页尾无段距），否则短页会假性溢出/无 slack；
        // 插图单元同样带尾距。切分产生的首片段（splitFirst）排版与渲染都不带
        // 尾距（渲染 bottom padding = 0），不适用该豁免，否则 realUsed 虚低、slack 虚高
        val lastHasSpacing = when (units.lastOrNull()) {
            is PageUnit.Para -> (units.lastOrNull() as PageUnit.Para).let { !it.splitFirst }
            is PageUnit.Image -> true
            else -> false
        }
        val realUsed = if (lastHasSpacing) used - style.paragraphSpacingPx else used
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

    /** zh 长段落/en 超高超长段按行切分：返回 (本页子段, 续段, 续段首字符在 text 中的索引)。
     *  第三项供续段记录真实原文子区间（sourceBase = 段内基准 + 此索引），未切分时为 0。 */
    private fun splitLayout(text: String, layout: TextLayoutResult, maxHeightPx: Float): Triple<String, String, Int> {
        if (maxHeightPx <= 0f || layout.lineCount <= 1) return Triple(text, "", 0)
        var lastFit = -1
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) <= maxHeightPx) lastFit = line else break
        }
        if (lastFit < 0) lastFit = 0
        if (lastFit >= layout.lineCount - 1) return Triple(text, "", 0)
        val splitIndex = layout.getLineEnd(lastFit, visibleEnd = true)
        val c1 = text.substring(0, splitIndex)
        // 续段从下一行首字符开始：getLineStart 跳过行尾空白（英文词间空格、
        // 中文句间空格等），避免续段首字符为空白造成伪缩进。
        // 仍保留 trimStart('\n','\r') 兜底，处理跨行空白中可能残留的换行。
        val nextLineStart = layout.getLineStart(lastFit + 1).coerceIn(splitIndex, text.length)
        val c2Raw = text.substring(nextLineStart)
        val c2 = c2Raw.trimStart('\n', '\r')
        val c2Index = nextLineStart + (c2Raw.length - c2.length)
        return if (c1.isBlank()) Triple(text, "", 0) else Triple(c1, c2, c2Index)
    }

    // ── 测量（真实页宽约束，minWidth=maxWidth 对齐 Compose Text 的 fillMaxWidth） ──

    private fun measureLayout(
        text: String,
        textStyle: TextStyle,
        justifyLastLine: Boolean = false
    ): TextLayoutResult {
        val cw = contentWidthPx.toInt().coerceAtLeast(1)
        // 与渲染端 SelectableParagraphText 同口径：span 接管时以 Start 测量（避免平台
        // justify 二次拉伸空格），未接管时回退平台 justify（无 CJK 文本剥离非零字间距，
        // Android 15 平台回归），保证排版测量与屏幕渲染使用同一份 style（换行/页高一致）
        val justified = CjkJustifier.annotateDetailed(text, textStyle, cw, measurer, justifyLastLine)
        val effectiveStyle = if (justified.tookOver) {
            textStyle.copy(textAlign = TextAlign.Start)
        } else {
            CjkJustifier.adjustLatinTextStyle(text, textStyle)
        }
        return measurer.measure(
            text = justified.annotated,
            style = effectiveStyle,
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
        /**
         * 整图适配：等比缩放到 maxW×maxH 内（只缩小不放大），返回显示尺寸。
         * 尺寸非法时按 4:3 占位，保证链接语法完整但内容异常的插图仍可渲染。
         */
        fun fitImage(imageW: Int, imageH: Int, maxW: Float, maxH: Float): Pair<Float, Float> {
            if (imageW <= 0 || imageH <= 0 || maxW <= 0f || maxH <= 0f) {
                return maxW.coerceAtLeast(1f) to (maxW.coerceAtLeast(1f) * 0.75f).coerceAtMost(maxH.coerceAtLeast(1f))
            }
            val scale = minOf(maxW / imageW, maxH / imageH)
            return (imageW * scale).coerceAtLeast(1f) to (imageH * scale).coerceAtLeast(1f)
        }
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
