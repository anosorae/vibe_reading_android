package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.domain.parser.ReadingContentParser
import com.vibereading.app.ui.reader.content.ReadingContent
import com.vibereading.app.ui.reader.content.ReadingParagraph
import com.vibereading.app.ui.reader.pagination.PageStyle
import com.vibereading.app.ui.reader.pagination.ReaderMetrics

/**
 * 双语段落（滚动模式与分页模式共用）：英文 + 尾部气泡（点击弹窗查看中文原文）。
 * 气泡与弹窗均为视觉叠加层，不影响排版测量，无需重排。
 *
 * - [lineHeightExtraPx]：分页模式底部对齐分配到每行的额外行高（px），滚动模式恒 0。
 * - [pairHead]：是否为双语对首片段——仅首片段显示气泡，续段不重复。
 * - [showSpacer]：是否在段尾加段距（分页末段不加，对齐排版器 buildPage 的 realUsed）。
 */
@Composable
fun BilingualParagraph(
    englishText: String,
    chineseText: String,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    lineHeightExtraPx: Float = 0f,
    pairHead: Boolean = true,
    showSpacer: Boolean = true
) {
    val density = LocalDensity.current
    // 分页模式 lineHeightExtraPx > 0 时调整行高，与 PageRenderer 的 Text 排版一致
    val enStyle = if (lineHeightExtraPx > 0f) pageStyle.body.copy(
        lineHeight = (pageStyle.body.lineHeight.value + with(density) { lineHeightExtraPx.toSp().value }).sp
    ) else pageStyle.body

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = englishText,
                style = enStyle,
                color = palette.bodyText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = ReaderMetrics.BILINGUAL_PAD_DP.dp,
                        bottom = ReaderMetrics.BILINGUAL_PAD_DP.dp
                    )
            )
            if (chineseText.isNotBlank() && pairHead) {
                SourceBubble(chineseText = chineseText, pageStyle = pageStyle, palette = palette)
            }
        }
        if (showSpacer) {
            Spacer(Modifier.height(with(density) { pageStyle.paragraphSpacingPx.toDp() }))
        }
    }
}

/** 原文气泡 + 弹窗（视觉叠加层，不参与排版测量）。需在 Box 作用域内调用以使用 align 定位。 */
@Composable
private fun BoxScope.SourceBubble(
    chineseText: String,
    pageStyle: PageStyle,
    palette: ReaderPalette
) {
    var showPopup by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(
                end = ReaderMetrics.BUBBLE_END_DP.dp,
                bottom = ReaderMetrics.BUBBLE_BOTTOM_DP.dp
            )
            .size(
                width = ReaderMetrics.BUBBLE_WIDTH_DP.dp,
                height = ReaderMetrics.BUBBLE_HEIGHT_DP.dp
            )
            .clickable { showPopup = !showPopup }
    ) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = palette.sourceBubble,
            modifier = Modifier.fillMaxSize()
        ) {}
    }

    if (showPopup) {
        Popup(
            popupPositionProvider = object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupSize: IntSize
                ): IntOffset {
                    // 弹窗右上角对齐段落右下角（气泡位置），向左下展开，不遮挡译文
                    val gap = with(density) { 4.dp.roundToPx() }
                    val x = anchorBounds.right - popupSize.width
                    val y = anchorBounds.bottom + gap
                    return IntOffset(x, y)
                }
            },
            onDismissRequest = { showPopup = false },
            properties = PopupProperties(focusable = true)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = palette.popupBg,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .padding(4.dp)
            ) {
                Text(
                    text = chineseText,
                    style = pageStyle.cn,
                    color = palette.cnText,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(
                                color = palette.popupBorder,
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

/**
 * 旧 UI parser 的兼容入口。真正的分段与 marker 配对只由 domain parser 完成，
 * 这样 LLM prompt、滚动内容和分页内容共享完全相同的 CRLF/CR/空白规则。
 */
fun splitParagraphs(content: String): List<String> = ReadingContentParser.splitParagraphs(content)

/** 统一章节内容构造入口；分页和滚动可共享同一段落范围数据。 */
fun readingContent(chapter: Chapter): ReadingContent = ReadingContent.fromChapter(chapter)

/** 带原文范围的滚动项，供 ReaderScreen 后续接线而不改变当前滚动渲染。 */
data class ReadingScrollItem(
    val paragraph: ReadingParagraph,
    val sourceStartOffset: Int = paragraph.sourceStartOffset,
    val sourceEndOffset: Int = paragraph.sourceEndOffset
)

fun ReadingContent.scrollItems(): List<ReadingScrollItem> =
    paragraphs.map { ReadingScrollItem(it) }

/** 保持旧 UI Pair API 可编译，但不再维护第二套解析实现。 */
fun parseBilingualParagraphs(
    translatedContent: String,
    originalContent: String
): List<Pair<String, String>> = ReadingContentParser.parseBilingualPairs(
    translatedContent,
    originalContent
)
