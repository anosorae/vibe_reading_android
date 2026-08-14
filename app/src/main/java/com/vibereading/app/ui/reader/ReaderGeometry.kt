package com.vibereading.app.ui.reader

/**
 * 阅读页几何（px）：集中「内容区 = 屏幕 − 系统栏 − 用户边距」公式，
 * 排版（BookWindow）、渲染（PageRenderer / 卷页位图）、手势三处共用，保证扣除口径一致。
 */
data class ReaderPageGeometry(
    val contentWidthPx: Float,   // 排版内容区宽度（BookWindow / ChapterPaginator 用）
    val contentHeightPx: Float,  // 排版内容区高度
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val padHPx: Int,             // 左右用户边距（px）
    val padVPx: Int,             // 上下用户边距（px）
    val statusBarPx: Int,
    val navBarPx: Int
) {
    companion object {
        /**
         * 由屏幕/系统栏/用户边距整像素构造几何（对齐 Compose 布局系统：
         * 每个 padding 值先 roundToPx 后相减，再 coerceAtLeast(0)）。
         */
        fun of(
            screenWidthPx: Int,
            screenHeightPx: Int,
            statusBarPx: Int,
            navBarPx: Int,
            padHPx: Int,
            padVPx: Int
        ): ReaderPageGeometry = ReaderPageGeometry(
            contentWidthPx = (screenWidthPx - padHPx * 2).coerceAtLeast(0).toFloat(),
            contentHeightPx = (screenHeightPx - statusBarPx - navBarPx - padVPx * 2)
                .coerceAtLeast(0).toFloat(),
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            padHPx = padHPx,
            padVPx = padVPx,
            statusBarPx = statusBarPx,
            navBarPx = navBarPx
        )
    }
}
