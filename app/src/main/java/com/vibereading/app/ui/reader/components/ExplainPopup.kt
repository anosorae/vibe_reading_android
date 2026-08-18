package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vibereading.app.domain.model.WordExplanation
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.pagination.PageStyle
import kotlin.math.roundToInt

/**
 * LLM 词语解释弹窗：显示在长按点附近，展示词条 / 音标 / 词性 / 释义 / 词形变化 / 近义词 / 反义词 / 搭配 / 难度。
 * 复用词典弹窗的 Popup 模式（focusable 独立窗口，点外部或返回键关闭），
 * 视觉叠加层，不参与排版。
 */
@Composable
fun ExplainPopup(
    queryWord: String,
    result: WordExplanation?,
    loading: Boolean,
    error: String?,
    anchor: Offset,
    palette: ReaderPalette,
    pageStyle: PageStyle,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val horizontalMargin = with(density) { 8.dp.roundToPx() }
    // 弹窗内样式不继承段落的缩进/两端对齐
    val popupBody = pageStyle.body.copy(textIndent = null, textAlign = TextAlign.Start)
    val popupCn = pageStyle.cn.copy(textIndent = null, textAlign = TextAlign.Start)

    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupSize: IntSize
            ): IntOffset {
                var x = (anchor.x - popupSize.width / 2f).roundToInt()
                x = x.coerceIn(horizontalMargin, windowSize.width - popupSize.width - horizontalMargin)
                // 上方优先，空间不足翻到下方
                var y = (anchor.y - popupSize.height - gap).toInt()
                if (y < horizontalMargin) y = (anchor.y + gap).toInt()
                return IntOffset(x, y)
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .background(color = palette.popupBg, shape = RoundedCornerShape(8.dp))
                .widthIn(max = 320.dp)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when {
                loading -> Text(
                    text = "解释中…",
                    style = popupCn,
                    color = palette.cnText
                )

                error != null -> Text(
                    text = error,
                    style = popupCn,
                    color = palette.cnText
                )

                result != null -> {
                    // 词条 + 音标 + CEFR 难度
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = result.lemma.ifBlank { queryWord },
                            style = popupBody.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = palette.titleText
                        )
                        if (result.phonetic.isNotBlank()) {
                            Text(
                                text = "/${result.phonetic}/",
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                                color = palette.cnText,
                                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                            )
                        }
                        if (result.difficulty.isNotBlank()) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = result.difficulty,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.popupBg,
                                modifier = Modifier
                                    .background(palette.cnText, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .align(Alignment.CenterVertically)
                            )
                        }
                    }
                    // 词性
                    if (result.pos.isNotBlank()) {
                        Text(
                            text = result.pos,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // 释义
                    if (result.definition.isNotBlank()) {
                        Text(
                            text = result.definition,
                            style = popupCn,
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    // 词形变化
                    if (result.inflections.isNotBlank()) {
                        Text(
                            text = "词形变化: ${result.inflections}",
                            style = popupCn.copy(fontSize = 13.sp),
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    // 近义词
                    if (result.synonyms.isNotBlank()) {
                        Text(
                            text = "近义: ${result.synonyms}",
                            style = popupCn.copy(fontSize = 13.sp),
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // 反义词
                    if (result.antonyms.isNotBlank()) {
                        Text(
                            text = "反义: ${result.antonyms}",
                            style = popupCn.copy(fontSize = 13.sp),
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    // 常见搭配
                    if (result.collocations.isNotBlank()) {
                        Text(
                            text = "搭配: ${result.collocations}",
                            style = popupCn.copy(fontSize = 13.sp),
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                else -> Text(
                    text = "无解释结果",
                    style = popupCn,
                    color = palette.cnText
                )
            }
        }
    }
}
