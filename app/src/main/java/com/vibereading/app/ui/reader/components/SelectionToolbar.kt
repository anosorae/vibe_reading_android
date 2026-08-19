package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vibereading.app.ui.reader.ReaderPalette
import kotlin.math.roundToInt

/**
 * 选词工具栏（对齐 Legado TextActionMenu）：显示在选区中心上方，提供「查词 / 解释 / 复制」。
 * 弹窗为独立窗口（focusable），点击外部时调用 [onDismissRequest] 决定是否清除选区。
 * 拖拽手柄模式下，[onDismissRequest] 应仅关闭工具栏而保留选区，便于手柄继续交互。
 */
@Composable
fun SelectionToolbar(
    selectionState: TextSelectionState,
    palette: ReaderPalette,
    onLookup: (String) -> Unit,
    onExplain: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismissRequest: () -> Unit = { selectionState.clear() }
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val horizontalMargin = with(density) { 8.dp.roundToPx() }

    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupSize: IntSize
            ): IntOffset {
                val anchor = selectionState.popupPosition
                var x = (anchor.x - popupSize.width / 2f).roundToInt()
                x = x.coerceIn(horizontalMargin, windowSize.width - popupSize.width - horizontalMargin)
                // 上方优先，空间不足翻到下方
                var y = (anchor.y - popupSize.height - gap).toInt()
                if (y < horizontalMargin) y = (anchor.y + gap).toInt()
                return IntOffset(x, y)
            }
        },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Row(
            modifier = Modifier
                .background(color = palette.popupBg, shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ToolbarButton("查词", palette, Modifier.clickable { onLookup(selectionState.selectedText) })
            ToolbarButton("解释", palette, Modifier.clickable { onExplain(selectionState.selectedText) })
            ToolbarButton("复制", palette, Modifier.clickable { onCopy(selectionState.selectedText) })
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = palette.bodyText,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp)
    )
}