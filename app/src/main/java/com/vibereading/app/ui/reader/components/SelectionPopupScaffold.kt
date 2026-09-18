package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.vibereading.app.ui.reader.ReaderPalette

/**
 * 选词系弹窗（词典/解释）共用的 Popup 外壳：focusable 独立窗口（点外部或返回键关闭）
 * + 圆角滚动容器，定位复用 [SelectionPopupPositionProvider]。
 * 视觉叠加层，不参与排版。
 */
@Composable
fun SelectionPopupScaffold(
    anchor: Offset,
    palette: ReaderPalette,
    maxHeight: Dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Popup(
        popupPositionProvider = SelectionPopupPositionProvider(LocalDensity.current) { anchor },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .background(color = palette.popupBg, shape = RoundedCornerShape(8.dp))
                .widthIn(max = 320.dp)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content
        )
    }
}
