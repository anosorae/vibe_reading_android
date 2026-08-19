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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.BreakIterator
import java.util.Locale

/**
 * 选词交互状态（对齐 Legado ReadView.onLongPress 的「长按 → 定位词边界 → 高亮」）。
 *
 * 选区是瞬时 UI 状态：翻页、滚动、模式/章节切换时必须 [clear]；
 * [popupPosition] 为长按点在窗口坐标系中的位置，供工具栏/词典弹窗定位。
 */
@Stable
class TextSelectionState {
    var isSelecting by mutableStateOf(false)
        private set
    var selectedText by mutableStateOf("")
        private set
    var selectionRange by mutableStateOf<IntRange?>(null)
        private set
    var paragraphKey by mutableStateOf<Any?>(null)
        private set
    var popupPosition by mutableStateOf(Offset.Zero)
        private set
    /** 长按所在段落的全文，供「解释」按钮读取上下文。 */
    var paragraphText by mutableStateOf("")
        private set

    fun select(key: Any?, text: String, range: IntRange, position: Offset, paragraphText: String = "") {
        paragraphKey = key
        selectedText = text
        selectionRange = range
        popupPosition = position
        this.paragraphText = paragraphText
        isSelecting = true
    }

    fun clear() {
        isSelecting = false
        selectedText = ""
        selectionRange = null
        paragraphKey = null
        paragraphText = ""
    }
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
 * - 长按（系统 500ms）→ 命中测试字符 → BreakIterator 分词 → [TextSelectionState.select]
 * - 长按触发后 consume 本次手势的后续事件，外层点按翻页逻辑自动跳过（`isConsumed` 判定）
 * - 选区高亮用 AnnotatedString 背景色渲染：背景不参与测量，分页结果不受影响
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
    highlightColor: Color = Color.Transparent
) {
    if (selectionState == null) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var windowPosition by remember { mutableStateOf(Offset.Zero) }

    val isSelected = selectionState.isSelecting && selectionState.paragraphKey == paragraphKey
    val range = selectionState.selectionRange
    val annotated = remember(text, isSelected, range) {
        if (isSelected && range != null &&
            range.first >= 0 && range.last < text.length
        ) {
            buildAnnotatedString {
                append(text)
                addStyle(
                    SpanStyle(background = highlightColor),
                    range.first,
                    range.last + 1
                )
            }
        } else {
            buildAnnotatedString { append(text) }
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
                        // 用 getBoundingBox 校正：若触点在字符右边界右侧，则取下一个字符。
                        val adjustedOffset = if (charOffset != null && charOffset + 1 < text.length) {
                            val bbox = layout.getBoundingBox(charOffset)
                            if (pressPosition.x > bbox.right + 1f) {
                                charOffset + 1
                            } else {
                                charOffset
                            }
                        } else {
                            charOffset
                        }
                        val wordRange = adjustedOffset?.let {
                            findWordBoundary(text, it, locale)
                        }
                        val word = wordRange?.let { text.substring(it) }
                        if (wordRange != null && word != null &&
                            word.any { ch -> ch.isLetter() }
                        ) {
                            selectionState.select(
                                key = paragraphKey,
                                text = word,
                                range = wordRange,
                                position = windowPosition + pressPosition,
                                paragraphText = text
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
