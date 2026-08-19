package com.vibereading.app.ui.reader.pagination

/**
 * 排版 / 渲染共享的几何常量（dp）：标题顶距、卷名间距、双语对 padding、原文气泡尺寸。
 * 排版器（px）、卷页位图（px）、渲染组件（dp）三处引用同一来源，改一处到处同步。
 */
object ReaderMetrics {
    /** 章节标题块顶部留白（0 = 标题紧贴内容区顶部） */
    const val TITLE_TOP_DP = 0

    /** 卷名 → 章节名间距 */
    const val SECTION_TITLE_GAP_DP = 8

    /** 章节标题底部到第一行正文的间距 */
    const val TITLE_BOTTOM_DP = 44

    /** 双语对英文段上下 padding（单边） */
    const val BILINGUAL_PAD_DP = 4

    /** 原文气泡尺寸 / 定位 */
    const val BUBBLE_WIDTH_DP = 18
    const val BUBBLE_HEIGHT_DP = 6
    const val BUBBLE_END_DP = 4
    const val BUBBLE_BOTTOM_DP = 2
    /** 原文气泡隐形触控区（视觉气泡 18×6dp 太小，扩展至 44dp 保证易点按） */
    const val BUBBLE_TOUCH_TARGET_DP = 44

    /** 双语对英文段上下 padding 合计（px）——排版器 en 模式额外占位用（density 为 display density）。 */
    fun bilingualPadPx(density: Float): Float =
        (2 * kotlin.math.round(BILINGUAL_PAD_DP * density)).toFloat()
}
