package com.vibereading.app.ui.reader

import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.reader.pagination.PageStyle

/** 阅读页面的共享排版与覆盖层几何；可派生的 px 值统一从 [geometry] 读取。 */
data class ReaderLayoutSpec(
    val pageStyle: PageStyle,
    val palette: ReaderPalette,
    val geometry: ReaderPageGeometry,
    val paddingH: Int,
    val paddingV: Int,
    val headerContentGap: Int,
    val footerContentGap: Int,
    val cutoutLeftPx: Int = 0,
    val cutoutRightPx: Int = 0
)

/** 分页与滚动正文共享的瞬时交互参数。 */
data class ReaderContentInteractions(
    val selectionState: TextSelectionState? = null,
    val bubbleEnabled: Boolean = true,
    val onIllustrationClick: ((String) -> Unit)? = null
)
