package com.vibereading.app.ui.reader.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * 选词相关弹窗（工具栏 / 词典 / 解释）共用的定位策略：
 * 水平以锚点为中心并夹在屏幕边距内，垂直优先显示在锚点上方，上方空间不足时翻到下方。
 *
 * 锚点以 lambda 传入，因为工具栏跟随 [TextSelectionState.popupPosition] 实时变化，
 * 而词典/解释弹窗用一次性锚点。
 */
class SelectionPopupPositionProvider(
    density: Density,
    private val anchor: () -> Offset
) : PopupPositionProvider {

    private val gap = with(density) { GAP_DP.dp.roundToPx() }
    private val horizontalMargin = with(density) { MARGIN_DP.dp.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupSize: IntSize
    ): IntOffset {
        val a = anchor()
        val maxX = windowSize.width - popupSize.width - horizontalMargin
        // 弹窗宽于窗口时 coerceIn 会因下界大于上界抛异常，退化为靠左
        val x = (a.x - popupSize.width / 2f).roundToInt()
            .coerceIn(horizontalMargin, maxX.coerceAtLeast(horizontalMargin))
        // 上方优先，空间不足翻到下方
        val above = (a.y - popupSize.height - gap).toInt()
        val y = if (above < horizontalMargin) (a.y + gap).toInt() else above
        return IntOffset(x, y)
    }

    companion object {
        private const val GAP_DP = 8
        private const val MARGIN_DP = 8
    }
}
