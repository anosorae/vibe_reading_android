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

    /**
     * 插图尺寸非法（链接里声明为 0 或负）时的兜底宽高比（4:3）。
     * 分页适配（fitImage）用它给出一个可见的占位尺寸。
     */
    const val IMAGE_FALLBACK_ASPECT_RATIO = 0.75f

    /**
     * 滚动模式插图宽高比夹取区间：宽高比 = 宽/高，超出区间会让极端长图/宽图
     * 撑爆一屏或塌缩成不可见。分页模式不适用——那里是「整图缩进给定盒子」。
     */
    const val IMAGE_ASPECT_RATIO_MIN = 0.2f
    const val IMAGE_ASPECT_RATIO_MAX = 5f

    /** 原文气泡尺寸 / 定位 */
    const val BUBBLE_WIDTH_DP = 18
    const val BUBBLE_HEIGHT_DP = 6
    const val BUBBLE_END_DP = 4
    const val BUBBLE_BOTTOM_DP = 2

    /** 原文气泡圆角（BilingualParagraph 的 RoundedCornerShape 与卷页位图 drawRoundRect 共用） */
    const val BUBBLE_CORNER_RADIUS_DP = 3
    /** 原文气泡隐形触控区宽度（视觉气泡 18×6dp 太小，水平扩展至 44dp 保证易点按） */
    const val BUBBLE_TOUCH_TARGET_DP = 44

    /** 原文气泡隐形触控区高度（气泡下方部分）：小于通用 44/48dp 建议值，减少对下一段首行点按翻页的覆盖 */
    const val BUBBLE_TOUCH_HEIGHT_DP = 32

    /** 触控区在气泡上方的扩展量：盖住最后一行底部 24dp 便于点按；手势不吞 down，不影响该区域长按选词 */
    const val BUBBLE_TOUCH_ABOVE_DP = 24

    /** 触控区向下悬挂量：顶边对齐气泡顶部（气泡顶 = 段落底上方 BUBBLE_HEIGHT+BUBBLE_BOTTOM），向下延伸出段落底部 */
    const val BUBBLE_TOUCH_DROP_DP = BUBBLE_TOUCH_HEIGHT_DP - BUBBLE_HEIGHT_DP - BUBBLE_BOTTOM_DP

    /** 选择手柄尺寸 / 定位（对齐 ADR-002 D8） */
    const val HANDLE_SIZE_DP = 32       // 透明触控盒（大于视觉区，便于抓取且不压字）
    const val HANDLE_VISUAL_SIZE_DP = 24 // 竖线+圆点视觉绘制区（顶部锚定行底，余量为透明触控延伸）
    const val HANDLE_LINE_WIDTH_DP = 2  // 竖线宽度
    const val HANDLE_DOT_RADIUS_DP = 4 // 底部圆点半径
    const val HANDLE_DOT_PADDING_DP = 4 // 圆点距视觉区底部间距

    /** 双语对英文段上下 padding 合计（px）——排版器 en 模式额外占位用（density 为 display density）。 */
    fun bilingualPadPx(density: Float): Float =
        (2 * kotlin.math.round(BILINGUAL_PAD_DP * density)).toFloat()
}
