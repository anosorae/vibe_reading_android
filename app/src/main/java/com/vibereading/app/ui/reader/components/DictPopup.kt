package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.vibereading.app.domain.model.DictEntry
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.pagination.PageStyle
import kotlin.math.roundToInt

/**
 * 词典查询结果弹窗（离线 ECDICT）：显示在长按点附近，展示音标 / 词性 / 中文释义。
 * 复用原文弹窗的 Popup 模式（focusable 独立窗口，点外部或返回键关闭），
 * 视觉叠加层，不参与排版。
 */
@Composable
fun DictPopup(
    queryWord: String,
    entry: DictEntry?,
    loading: Boolean,
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
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when {
                loading -> Text(
                    text = "查询中…",
                    style = popupCn,
                    color = palette.cnText
                )

                entry == null -> Text(
                    text = if (queryWord.any { it.isCjk() }) "仅支持英文查词" else "未收录该词",
                    style = popupCn,
                    color = palette.cnText
                )

                else -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = entry.word,
                            style = popupBody.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = palette.titleText
                        )
                        if (!entry.phonetic.isNullOrBlank()) {
                            Text(
                                text = "/${entry.phonetic}/",
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                                color = palette.cnText,
                                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                            )
                        }
                    }
                    if (!entry.pos.isNullOrBlank()) {
                        Text(
                            text = entry.pos,
                            fontSize = 13.sp,
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (!entry.translation.isNullOrBlank()) {
                        Text(
                            text = entry.translation,
                            style = popupCn,
                            color = palette.cnText,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 是否 CJK 字符（用于「仅支持英文查词」提示）。 */
private fun Char.isCjk(): Boolean =
    Character.UnicodeBlock.of(this) in setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    )
