package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.vibereading.app.log.AppLog
import com.vibereading.app.ui.reader.pagination.CjkJustifier
import java.text.BreakIterator
import java.util.Locale

/**
 * 手柄类型：选区起点（START）或终点（END）。
 */
enum class HandleType { START, END }

/**
 * 选词交互状态（对齐 Legado ReadView.onLongPress 的「长按 → 定位词边界 → 高亮」，
 * 扩展支持双端手柄拖拽选区）。
 *
 * 选区是瞬时 UI 状态：翻页、滚动、模式/章节切换时必须 [clear]；
 * [popupPosition] 为选区几何中心在窗口坐标系中的位置，供工具栏/词典弹窗定位。
 */
@Stable
class TextSelectionState {
    var isSelecting by mutableStateOf(false)
        private set
    var selectedText by mutableStateOf("")
        private set
    var paragraphKey by mutableStateOf<Any?>(null)
        private set
    var popupPosition by mutableStateOf(Offset.Zero)
        private set
    /** 长按所在段落的全文，供「解释」按钮读取上下文。 */
    var paragraphText by mutableStateOf("")
        private set

    // ── 双端选区（替代原 selectionRange） ──
    /** 选区起始 UTF-16 偏移（inclusive）。 */
    var selectionStart by mutableStateOf(0)
        private set
    /** 选区结束 UTF-16 偏移（exclusive）。 */
    var selectionEnd by mutableStateOf(0)
        private set

    // ── 坐标映射所需（由 SelectableParagraphText 长按后上报） ──
    /** 选中段落的 TextLayoutResult，用于 getBoundingBox/getOffsetForPosition。 */
    var layoutResult by mutableStateOf<TextLayoutResult?>(null)
        private set
    /** 选中段落在窗口坐标系中的位置（onGloballyPositioned 获取）。 */
    var paragraphWindowOffset by mutableStateOf(Offset.Zero)
        private set

    // ── 手柄拖拽状态 ──
    /** 当前拖拽中的手柄类型，null 表示未拖拽。 */
    var draggingHandle by mutableStateOf<HandleType?>(null)
        private set

    /** 工具栏显隐控制：false 时工具栏隐藏但选区保留（手柄仍可交互）。 */
    var showToolbar by mutableStateOf(true)
        private set

    /**
     * 长按选中一个词（原 select 语义）。上报 layout 信息供手柄定位。
     */
    fun selectWord(
        key: Any?,
        text: String,
        range: IntRange,
        position: Offset,
        paragraphText: String = "",
        layout: TextLayoutResult? = null,
        windowOffset: Offset = Offset.Zero
    ) {
        paragraphKey = key
        selectedText = text
        selectionStart = range.first
        selectionEnd = range.last + 1
        popupPosition = position
        this.paragraphText = paragraphText
        layoutResult = layout
        paragraphWindowOffset = windowOffset
        isSelecting = true
        showToolbar = true
    }

    /** 开始拖拽指定手柄，同时隐藏工具栏。 */
    fun startDrag(handle: HandleType) {
        draggingHandle = handle
        showToolbar = false
    }

    /** 关闭工具栏但保留选区（供手柄继续交互）。 */
    fun dismissToolbar() {
        showToolbar = false
    }

    /**
     * 将活动端拖拽到指定字符偏移（含自动反转处理，对齐 Legado reverseStartCursor/reverseEndCursor）。
     * [charOffset] 由调用方从屏幕坐标转换后传入。
     *
     * 反转仅在严格超过对面时触发（D5）；恰好相等时贴对面保留单字符，不反转。
     */
    fun dragTo(charOffset: Int) {
        val handle = draggingHandle ?: return
        val clamped = charOffset.coerceIn(0, paragraphText.length)

        when (handle) {
            HandleType.START -> {
                if (clamped < selectionEnd) {
                    selectionStart = clamped
                } else if (clamped > selectionEnd) {
                    // 反转：START 拖过 END → 角色互换
                    draggingHandle = HandleType.END
                    selectionStart = selectionEnd
                    selectionEnd = (clamped + 1).coerceAtMost(paragraphText.length)
                } else {
                    // clamped == selectionEnd：贴着 END 保留单字符，不反转
                    selectionStart = (selectionEnd - 1).coerceAtLeast(0)
                }
            }
            HandleType.END -> {
                if (clamped > selectionStart) {
                    selectionEnd = (clamped + 1).coerceAtMost(paragraphText.length)
                } else if (clamped < selectionStart) {
                    // 反转：END 拖过 START → 角色互换
                    draggingHandle = HandleType.START
                    selectionEnd = selectionStart
                    selectionStart = clamped
                } else {
                    // clamped == selectionStart：贴着 START 保留单字符，不反转
                    selectionEnd = (selectionStart + 1).coerceAtMost(paragraphText.length)
                }
            }
        }
        selectedText = paragraphText.substring(selectionStart, selectionEnd)
    }

    /**
     * 结束拖拽：关闭拖拽状态，将弹出位置设为选区几何中心。
     * 调用方应在拖拽结束后调用此方法，以触发工具栏显示。
     */
    fun endDrag() {
        draggingHandle = null
        showToolbar = true
        // 计算选区几何中心作为工具栏锚点
        updatePopupPosition()
    }

    /** 计算选区几何中心并设置 popupPosition。 */
    private fun updatePopupPosition() {
        val layout = layoutResult ?: return
        if (selectionStart < 0 || selectionEnd > paragraphText.length || selectionStart >= paragraphText.length) {
            // 选区异常时保持原 popupPosition 不变（仍为长按点或上次拖拽结束位置）
            return
        }
        val startCursor = layout.cursorRectSafely(selectionStart) ?: return
        val endCursor = layout.cursorRectSafely(selectionEnd) ?: return
        val centerX = (startCursor.left + endCursor.left) / 2f
        val topY = minOf(startCursor.top, endCursor.top)
        val bottomY = maxOf(startCursor.bottom, endCursor.bottom)
        val centerY = (topY + bottomY) / 2f
        popupPosition = Offset(
            paragraphWindowOffset.x + centerX,
            paragraphWindowOffset.y + centerY
        )
    }

    fun clear() {
        isSelecting = false
        showToolbar = false
        selectedText = ""
        selectionStart = 0
        selectionEnd = 0
        paragraphKey = null
        paragraphText = ""
        layoutResult = null
        paragraphWindowOffset = Offset.Zero
        draggingHandle = null
    }

}

/**
 * 安全获取 [TextLayoutResult.getCursorRect]，失败时记录日志并返回 null。
 * 两端对齐时 getCursorRect 返回对齐后位置（优于 getBoundingBox 的 ink 边界）。
 */
internal fun TextLayoutResult.cursorRectSafely(offset: Int) =
    runCatching { getCursorRect(offset) }
        .onFailure { AppLog.put("getCursorRect(offset=$offset) 失败", it) }
        .getOrNull()

/**
 * 对两端对齐文本的命中测试补偿：在 [rawOffset] 所在行上逐字符遍历，
 * 用 [getBoundingBox] 的 ink 边界做命中测试，补偿 [getOffsetForPosition] 因齐行间距产生的水平偏差。
 * 供 [SelectableParagraphText] 和 [SelectionHandles] 共用。
 */
internal fun perCharHitTest(
    layout: TextLayoutResult,
    localPos: Offset,
    rawOffset: Int
): Int {
    val line = layout.getLineForOffset(rawOffset)
    val lineStart = layout.getLineStart(line)
    val lineEnd = layout.getLineEnd(line, visibleEnd = true)
    var found = rawOffset
    for (off in lineStart until lineEnd) {
        val bbox = layout.getBoundingBox(off)
        if (localPos.x >= bbox.left - 1f && localPos.x <= bbox.right + 1f) {
            found = off
            break
        }
    }
    return found
}

/** 段落唯一标识（章节 + 段落序号），用于选区高亮归属判断。 */
data class ParagraphKey(val chapterId: Long, val paraIndex: Int)

/**
 * 用 BreakIterator 找出包含 [offset]（UTF-16 索引）的词边界（对齐 Legado 选词逻辑）。
 * 返回半开区间 [start, end)；offset 落在空白/标点段时也返回该段，由调用方过滤。
 */
fun findWordBoundary(text: String, offset: Int, locale: Locale = Locale.ENGLISH): IntRange? {
    if (text.isEmpty() || offset < 0 || offset >= text.length) return null
    val boundary = BreakIterator.getWordInstance(locale)
    boundary.setText(text)
    var start = boundary.first()
    var end = boundary.next()
    while (end != BreakIterator.DONE) {
        if (offset in start until end) return start until end
        start = end
        end = boundary.next()
    }
    return null
}

/**
 * 可选中段落文本：普通 [Text] 的替换件（滚动与分页共用）。
 *
 * - 长按（系统 500ms）→ 命中测试字符 → BreakIterator 分词 → [TextSelectionState.selectWord]
 * - 长按触发后 consume 本次手势剩余事件，外层点按翻页逻辑自动跳过（`isConsumed` 判定）
 * - 同时上报 [TextLayoutResult] 和窗口位置，供 [SelectionHandles] 定位手柄
 * - 选区高亮用 AnnotatedString 背景色渲染：背景不参与测量，分页结果不受影响
 * - 中文两端对齐：[contentWidthPx] > 0 时经 [CjkJustifier] 生成逐行字距 span（对齐分页器
 *   测量口径），选区背景 span 与字距 span 共存于同一 AnnotatedString；span 参与测量，
 *   命中测试/手柄读取的即是真实几何，无需额外补偿
 * - 普通点击不消费任何事件，翻页/开关工具栏手势原样交给外层
 */
@Composable
fun SelectableParagraphText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    selectionState: TextSelectionState? = null,
    paragraphKey: Any? = null,
    locale: Locale = Locale.ENGLISH,
    highlightColor: Color = Color.Transparent,
    contentWidthPx: Int = 0,
    justifyLastLine: Boolean = false
) {
    // 中文两端对齐（CjkJustifier 内部门控：非 Justify/无 CJK 时原样返回纯文本）
    val justifyMeasurer = rememberTextMeasurer()
    val baseAnnotated = remember(text, style, contentWidthPx, justifyLastLine, justifyMeasurer) {
        if (contentWidthPx > 0) {
            CjkJustifier.annotate(text, style, contentWidthPx, justifyMeasurer, justifyLastLine)
        } else {
            AnnotatedString(text)
        }
    }

    if (selectionState == null) {
        Text(text = baseAnnotated, style = style, color = color, modifier = modifier)
        return
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var windowPosition by remember { mutableStateOf(Offset.Zero) }

    val isSelected = selectionState.isSelecting && selectionState.paragraphKey == paragraphKey
    val selStart = selectionState.selectionStart
    val selEnd = selectionState.selectionEnd
    val annotated = remember(baseAnnotated, isSelected, selStart, selEnd) {
        if (isSelected && selStart >= 0 && selEnd <= text.length && selStart < selEnd) {
            buildAnnotatedString {
                append(baseAnnotated)
                addStyle(
                    SpanStyle(background = highlightColor),
                    selStart,
                    selEnd
                )
            }
        } else {
            baseAnnotated
        }
    }

    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it },
        modifier = modifier
            .onGloballyPositioned { windowPosition = it.positionInWindow() }
            .pointerInput(selectionState, paragraphKey, text, locale) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) return@awaitEachGesture
                    // 长按命中：consume 本次手势剩余事件，防止外层把抬起当作点按翻页
                    down.consume()
                    longPress.consume()
                    val pressPosition = longPress.position
                    val layout = layoutResult
                    if (layout != null) {
                        val charOffset = runCatching {
                            layout.getOffsetForPosition(pressPosition)
                        }.getOrNull()
                        // 两端对齐时，getOffsetForPosition 返回的字符可能偏左（视觉位置因齐行间距偏右），
                        // 在该行上逐字符遍历，用 getBoundingBox 的 ink 边界做命中测试。
                        // 兼容两端对齐、居中等所有对齐方式，尽可能补偿 getOffsetForPosition 的映射偏差。
                        val adjustedOffset = if (charOffset != null) {
                            perCharHitTest(layout, pressPosition, charOffset)
                        } else null
                        val wordRange = adjustedOffset?.let {
                            findWordBoundary(text, it, locale)
                        }
                        val word = wordRange?.let { text.substring(it) }
                        if (wordRange != null && word != null &&
                            word.any { ch -> ch.isLetter() }
                        ) {
                            selectionState.selectWord(
                                key = paragraphKey,
                                text = word,
                                range = wordRange,
                                position = windowPosition + pressPosition,
                                paragraphText = text,
                                layout = layout,
                                windowOffset = windowPosition
                            )
                        }
                    }
                    // 消费直到抬起（长按后拖动不触发外层翻页/卷页）
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@awaitEachGesture
                        change.consume()
                        if (!change.pressed) return@awaitEachGesture
                    }
                }
            }
    )
}