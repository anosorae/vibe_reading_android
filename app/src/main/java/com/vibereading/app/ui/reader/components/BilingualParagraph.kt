package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
import java.util.Locale

/**
 * 双语段落（滚动模式与分页模式共用，ADR-003 插槽模型）：英文侧正文 + 尾部中文气泡
 * （点击弹窗查看中文侧文本：中文书=中文原文，英文书=中文译文）。
 * 气泡与弹窗均为视觉叠加层，不影响排版测量，无需重排。
 *
 * - [lineHeightExtraPx]：分页模式底部对齐分配到每行的额外行高（px），滚动模式恒 0。
 * - [continuation]：是否为同段跨页续排片段——顶格无首行缩进；中文气泡在所有带译文的
 *   片段段尾都会显示（ADR-004），点任一气泡弹出整段中文侧文本。
 * - [showSpacer]：是否在段尾加段距（分页末段不加，对齐排版器 buildPage 的 realUsed）。
 * - [selectionState]/[paragraphKey]：长按选词状态与段落标识（英文侧正文参与选词）。
 * - [bubbleEnabled]：气泡是否可点击；菜单栏/浮层显示时传 false（对齐 selectionState 的禁用先例），
 *   禁用时手势不注册、不消费点击，点击落到外层翻页手势关闭菜单。
 */
@Composable
fun BilingualParagraph(
    englishText: String,
    chineseText: String,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    lineHeightExtraPx: Float = 0f,
    continuation: Boolean = false,
    showSpacer: Boolean = true,
    selectionState: TextSelectionState? = null,
    paragraphKey: Any? = null,
    bubbleEdgeExtendDp: Float = 0f,
    contentWidthPx: Int = 0,
    bubbleEnabled: Boolean = true
) {
    val density = LocalDensity.current
    // 续段顶格：与排版器测量口径一致，无首行缩进
    val baseStyle = if (continuation) pageStyle.body.copy(textIndent = null) else pageStyle.body
    // 分页模式 lineHeightExtraPx > 0 时调整行高，与 PageRenderer 的 Text 排版一致
    val enStyle = if (lineHeightExtraPx > 0f) baseStyle.copy(
        lineHeight = (pageStyle.body.lineHeight.value + with(density) { lineHeightExtraPx.toSp().value }).sp
    ) else baseStyle

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            SelectableParagraphText(
                text = englishText,
                style = enStyle,
                color = palette.bodyText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = ReaderMetrics.BILINGUAL_PAD_DP.dp,
                        bottom = ReaderMetrics.BILINGUAL_PAD_DP.dp
                    ),
                selectionState = selectionState,
                paragraphKey = paragraphKey,
                locale = Locale.ENGLISH,
                highlightColor = palette.selectionHighlight,
                // 译文残留中文（人名/地名/引语）时同样两端对齐，与分页器测量口径一致；纯英文被 CJK 门控跳过
                contentWidthPx = contentWidthPx
            )
            if (chineseText.isNotBlank()) {
                ChineseBubble(
                    chineseText = chineseText,
                    pageStyle = pageStyle,
                    palette = palette,
                    edgeExtendDp = bubbleEdgeExtendDp,
                    enabled = bubbleEnabled
                )
            }
        }
        if (showSpacer) {
            Spacer(Modifier.height(with(density) { pageStyle.paragraphSpacingPx.toDp() }))
        }
    }
}

/** 中文气泡 + 弹窗（视觉叠加层，不参与排版测量）。需在 Box 作用域内调用以使用 align 定位。
 *  触控区 44dp 高 ×（44+[edgeExtendDp])dp 宽：视觉气泡仅 18×6dp，左侧/上方扩展至 44dp 保证易点按，
 *  右侧按 [edgeExtendDp]（= 用户右边距 + BUBBLE_END_DP，由调用方按静态几何传入）延伸到屏幕右缘，
 *  避免点右侧误触发翻页；扩展区透明不干扰视觉。 */
@Composable
private fun BoxScope.ChineseBubble(
    chineseText: String,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    edgeExtendDp: Float,
    enabled: Boolean
) {
    var showPopup by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // 菜单栏/浮层显示时禁用气泡（enabled=false）：弹窗一并关闭，对齐打开浮层清选区的先例
    LaunchedEffect(enabled) {
        if (!enabled) showPopup = false
    }

    // 触控区容器用 matchParentSize：填满父 Box 但不参与尺寸决策，
    // 否则 44dp 触控区会比短段文本高，撑大 Box 导致位图与 Compose 页高度不一致。
    // 触控区在 matchParentSize 容器内通过 align(BottomEnd) 定位到右下角，视觉位置不变。
    // 触控区宽度比原来多出 edgeExtendDp 并用 offset 整体右移：左缘与原 44dp 区域重合、
    // 多出的宽度全部落在右缘之外直到屏幕边缘；offset 不影响布局测量，视觉气泡反向补偿保持原位。
    // 注意此处不得用 onGloballyPositioned 动态测量：翻页动画期间坐标每帧变化会引发
    // 每帧重组，扰动卷页位图与真实页的一致性（上下断层回归）。
    Box(
        modifier = Modifier.matchParentSize()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = edgeExtendDp.dp)
                .padding(
                    end = ReaderMetrics.BUBBLE_END_DP.dp,
                    bottom = ReaderMetrics.BUBBLE_BOTTOM_DP.dp
                )
                .size(
                    width = ReaderMetrics.BUBBLE_TOUCH_TARGET_DP.dp + edgeExtendDp.dp,
                    height = ReaderMetrics.BUBBLE_TOUCH_TARGET_DP.dp
                )
                // enabled=false 时不注册手势：点击不被消费，落到外层手势关闭菜单栏
                .pointerInput(enabled) {
                    if (enabled) detectTapGestures { showPopup = !showPopup }
                }
        ) {
            // 视觉气泡：在触控区内右下对齐并反向 offset，保持原视觉位置
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = -edgeExtendDp.dp)
                    .size(
                        width = ReaderMetrics.BUBBLE_WIDTH_DP.dp,
                        height = ReaderMetrics.BUBBLE_HEIGHT_DP.dp
                    )
            ) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = palette.bubble,
                    modifier = Modifier.fillMaxSize()
                ) {}
            }
        }
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
