package com.vibereading.app.ui.reader.pagination

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.em
import com.vibereading.app.log.AppLog

/**
 * 中文两端对齐（对齐 Legado `textFullJustify`）：Compose 的 `TextAlign.Justify` 委托平台
 * `JUSTIFICATION_MODE_INTER_WORD`，只在空格处分配余量，中文无空格导致右缘参差。
 *
 * 本对象是逐字拉伸的**唯一数据源**：按自然行宽计算每行余量，把余量均摊到行内字间隙，
 * 以 Em 单位的 `SpanStyle.letterSpacing` 表达（平台 `applySpanStyle` 写入
 * `paint.letterSpacing`，测量与绘制同源）。因此换行、页高、跨页切分不受影响，
 * 选词命中、手柄定位、仿真位图绘制读取的都是同一份真实几何。
 *
 * **自适应修正闭环**：不同 API 版本对 span 级字距的实际增量不同（sdk 34 精确；
 * Android 15 上增量被放大 ~2 倍并随覆盖字符数变化，措辞实测推翻 span 行引入重排）。
 * 因此不依赖「恰好填满」的先验数学，而是迭代测量反推每行增益并缩放字距：
 * 断行与自然排版一致且非末行行宽贴齐内容宽（±0.75px）即收敛；超过 6 轮不收敛则
 * 回退纯文本（视觉退化为现状，不留错误排版），并落日志便于诊断。
 *
 * 行级规则（对齐 Legado `addCharsToLineMiddle`）：
 * - 段末行不拉伸（CSS 惯例）；[justifyLastLine] 为 true 表示该片段末行在下一页延续，仍需拉伸
 * - 含 ASCII 空格的行交给平台 inter-word 对齐（现状行为，英文排版不变）
 */
object CjkJustifier {

    /** 每行右缘相对内容宽的目标偏差上限（px）：亚像素级即可视为贴齐（不同平台 span 增益的取整行为不同）。 */
    private const val FILL_TOLERANCE_PX = 1.25f

    /** 单字符间隙的拉伸预算上限（字宽比例）：超过则间隙肉眼可见，放弃该行。 */
    private const val MAX_GAP_BUDGET_EM = 0.15f

    /** 自适应迭代轮数上限。 */
    private const val MAX_ITERATIONS = 6

    /** 探针测量宽度上限（px），远大于任何正文字号下的两字符宽度。 */
    private const val PROBE_MAX_WIDTH = 4096

    /** 探针最小有效增量（px）：span 字距未生效（delta ≈ 0）时判定平台不支持，跳过拉伸。 */
    private const val PROBE_MIN_DELTA = 1.5f

    /** 进程级一次性标志：span 字距探针失效（平台/字体未应用 span）时只提醒一次。 */
    private var probeWarningLogged = false

    /** 单行的拉伸计划：span 覆盖 [start, 末簇起点)，目标增益 targetGainPx 均摊到 gapCount 个间隙。 */
    private class LinePlan(
        val line: Int,
        val start: Int,
        val end: Int,
        val gapCount: Int,
        val targetGainPx: Float,
        var scale: Float = 1f
    )

    /**
     * 为两端对齐段落生成逐行字距 span。
     * 门控（任一不满足即原样返回纯文本）：样式为 Justify、文本含 CJK、宽度有效、
     * 基准字间距为 Em 单位（PageStyle 口径）。
     */
    fun annotate(
        text: String,
        style: TextStyle,
        contentWidthPx: Int,
        measurer: TextMeasurer,
        justifyLastLine: Boolean = false
    ): AnnotatedString {
        if (text.length < 2 || contentWidthPx <= 1) return AnnotatedString(text)
        if (style.textAlign != TextAlign.Justify) return AnnotatedString(text)
        if (!containsCjk(text)) return AnnotatedString(text)
        val baseEm = when (style.letterSpacing.type) {
            TextUnitType.Em -> style.letterSpacing.value
            TextUnitType.Unspecified -> 0f
            // Sp 基准无法无损换算 Em（缺少测量端 density），回退自然排版
            else -> return AnnotatedString(text)
        }
        val cw = contentWidthPx.coerceAtLeast(1)
        val natural = measure(AnnotatedString(text), style.copy(textAlign = TextAlign.Start), cw, measurer)
        val plans = buildLinePlans(text, natural, cw, justifyLastLine)
        if (plans.isEmpty()) return AnnotatedString(text)
        val textSizePx = probeTextSizePx(style, measurer)
        if (textSizePx == 0f) return AnnotatedString(text) // 平台未应用 span 字距，整段跳过

        // 自适应修正闭环：每轮按实测增量重设字距，直至断行稳定且非末行填满
        for (iteration in 0 until MAX_ITERATIONS) {
            val annotated = buildAnnotatedString {
                append(text)
                plans.forEach { plan ->
                    if (plan.scale == 0f) return@forEach
                    val dPx = (plan.targetGainPx / plan.gapCount) * plan.scale
                    addStyle(
                        SpanStyle(letterSpacing = (baseEm + dPx / textSizePx).em),
                        plan.start, plan.end
                    )
                }
            }
            val verified = measure(annotated, style, cw, measurer)
            if (lineStartsDiffer(verified, natural)) {
                // 断行被撑破：span 增益大于预期（Android 15 上约为 2 倍），按 0.5 倍几何序列
                // 缩小字距直至断行恢复——不依赖单次观测，对任意平台增益都能收敛
                if (!shrinkScalesUntilLinesFit(text, style, cw, measurer, natural, plans, baseEm, textSizePx)) {
                    AppLog.put(
                        "CjkJustifier 中断：字距缩放序列未恢复断行，回退纯文本 " +
                            "natural=${natural.lineCount} 行 cw=$cw text=${text.take(16)}"
                    )
                    return AnnotatedString(text)
                }
                continue
            }
            // 断行稳定：逐行核对是否填满，未填满的行按残差比例微调
            var allFilled = true
            for (plan in plans) {
                if (plan.scale == 0f) continue // 该行平台未生效，放弃拉伸但不阻碍收敛
                val actualGain = verified.getLineRight(plan.line) - natural.getLineRight(plan.line)
                val slack = (cw - FILL_TOLERANCE_PX - natural.getLineRight(plan.line)) - actualGain
                if (kotlin.math.abs(slack) > FILL_TOLERANCE_PX) {
                    allFilled = false
                    if (actualGain > 0.01f) {
                        plan.scale *= (cw - FILL_TOLERANCE_PX - natural.getLineRight(plan.line)) / actualGain
                    } else {
                        plan.scale = 0f // 该行增益恒为 0（平台对该行不生效），放弃该行
                    }
                }
            }
            if (allFilled) return annotated
            if (iteration == MAX_ITERATIONS - 1) {
                // 未填满到亚像素级（个别行差 ≤ 1.25px 之外）：断行已稳定且大部分行已填满，
                // 保留当前结果优于回退纯文本（视觉部分对齐）
                return annotated
            }
        }
        return AnnotatedString(text)
    }

    /**
     * 断行被 span 撑破时的 bootstrap：把各行星距按 0.5 倍几何序列缩小并测量，
     * 首个「断行与自然一致」的倍率生效返回 true；序列耗尽仍未恢复返回 false。
     * 每次缩放在原 scale 上继续（先进先出，快于整体重来）。
     */
    private fun shrinkScalesUntilLinesFit(
        text: String,
        style: TextStyle,
        cw: Int,
        measurer: TextMeasurer,
        natural: TextLayoutResult,
        plans: List<LinePlan>,
        baseEm: Float,
        textSizePx: Float
    ): Boolean {
        val maxSteps = 5
        for (step in 0 until maxSteps) {
            // 等比序列 0.5, 0.25, 0.125…（进入此处时当前字距必然已撑破断行，先收缩再测）
            plans.forEach { if (it.scale != 0f) it.scale *= 0.5f }
            val annotated = buildAnnotatedString {
                append(text)
                plans.forEach { plan ->
                    if (plan.scale == 0f) return@forEach
                    val dPx = (plan.targetGainPx / plan.gapCount) * plan.scale
                    addStyle(
                        SpanStyle(letterSpacing = (baseEm + dPx / textSizePx).em),
                        plan.start, plan.end
                    )
                }
            }
            val verified = measure(annotated, style, cw, measurer)
            if (!lineStartsDiffer(verified, natural)) return true
        }
        return false
    }

    /** 生成可拉伸行的计划（末行/空格行/单簇行/余量过小或超过间隙预算的行不生成）。
     *  间隙预算：总余量 ≤ 每间隙 0.15 字宽 × 间隙数。真满行缺 1-2 字（如「…一道|离开。」）
     *  余量在预算内可拉；段内 `\n` 强制换行产生的短行（如「内容简介：」）余量达数百 px
     * 远超预算，拉伸会让每字间距骇人，保持自然。 */
    private fun buildLinePlans(
        text: String,
        natural: TextLayoutResult,
        cw: Int,
        justifyLastLine: Boolean
    ): List<LinePlan> {
        val plans = ArrayList<LinePlan>()
        val lastLine = natural.lineCount - 1
        for (line in 0..lastLine) {
            if (line == lastLine && !justifyLastLine) break
            val lineStart = natural.getLineStart(line)
            val visEnd = natural.getLineEnd(line, visibleEnd = true)
            if (visEnd - lineStart < 2) continue
            // 空格行交给平台 inter-word 对齐（英文排版与现状一致）
            var hasSpace = false
            for (i in lineStart until visEnd) {
                if (text[i] == ' ') { hasSpace = true; break }
            }
            if (hasSpace) continue
            val clusterStarts = clusterStarts(text, lineStart, visEnd)
            if (clusterStarts.size < 2) continue
            val naturalWidth = natural.getLineRight(line)
            val residual = cw - naturalWidth
            if (residual <= FILL_TOLERANCE_PX) continue
            // 间隙预算：residual ≤ MAX_GAP_BUDGET_EM × 字宽 × 间隙数，否则均摊后间隙肉眼可见
            val avgCharPx = naturalWidth / clusterStarts.size
            val gapBudget = MAX_GAP_BUDGET_EM * avgCharPx * (clusterStarts.size - 1)
            if (residual > gapBudget) continue
            plans += LinePlan(line, lineStart, clusterStarts.last(), clusterStarts.size - 1, residual)
        }
        return plans
    }

    private fun lineStartsDiffer(a: TextLayoutResult, b: TextLayoutResult): Boolean {
        if (a.lineCount != b.lineCount) return true
        for (line in 0 until a.lineCount) {
            if (a.getLineStart(line) != b.getLineStart(line)) return true
        }
        return false
    }

    private fun measure(
        text: AnnotatedString,
        style: TextStyle,
        width: Int,
        measurer: TextMeasurer
    ): TextLayoutResult = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(minWidth = width, maxWidth = width)
    )

    /**
     * 探针法测量正文字号（px）：对「一一」首字符施加 1em 字距 span 前后的行宽差
     * 恰为 textSize（与字形自然宽度、代理对、居中/尾随语义无关），无需测量端 density。
     * span 字距未生效（delta 过小）时返回 0，调用方跳过拉伸。
     */
    private fun probeTextSizePx(style: TextStyle, measurer: TextMeasurer): Float {
        val probeStyle = style.copy(textAlign = TextAlign.Start)
        val probe = "一一"
        val plain = measure(AnnotatedString(probe), probeStyle, PROBE_MAX_WIDTH, measurer).getLineRight(0)
        val spaced = measure(
            buildAnnotatedString {
                append(probe)
                addStyle(SpanStyle(letterSpacing = 1.em), 0, 1)
            },
            probeStyle, PROBE_MAX_WIDTH, measurer
        ).getLineRight(0)
        val delta = spaced - plain
        if (delta <= PROBE_MIN_DELTA) {
            if (!probeWarningLogged) {
                probeWarningLogged = true
                AppLog.put("CjkJustifier：span 字距探针无效（delta=$delta px），中文两端对齐跳过")
            }
            return 0f
        }
        return delta.coerceAtLeast(1f)
    }

    /** 列出 [start, end) 内每个字素簇的起始 UTF-16 偏移（低代理对与组合附标并入前簇）。 */
    private fun clusterStarts(text: String, start: Int, end: Int): List<Int> {
        val starts = ArrayList<Int>()
        var i = start
        while (i < end) {
            val c = text[i]
            if (!c.isLowSurrogate() && !isCombiningMark(c)) starts.add(i)
            i++
        }
        return starts
    }

    private val combiningMarkTypes = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt()
    )

    private fun isCombiningMark(c: Char): Boolean =
        Character.getType(c).toInt() in combiningMarkTypes

    private val cjkBlocks = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    )

    private fun containsCjk(text: String): Boolean {
        for (c in text) {
            if (c.isHighSurrogate()) return true
            if (Character.UnicodeBlock.of(c) in cjkBlocks) return true
        }
        return false
    }
}