package com.vibereading.app.ui.bookshelf

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 书架版面的唯一度量来源（视觉基线见 `docs/ADR-006-bookshelf-visual-baseline.md`）。
 *
 * 设计稿按 **2.0 px/dp** 出图（用系统手势条 108×4dp 反推确认），画布宽 410dp，
 * 所以下面每个值都是「设计稿像素 ÷ 2」。卡片宽度不写死：列数按可用宽度推，
 * 410dp 屏上正好落到 3 列 × 118.7dp，与设计稿实测 119dp 吻合。
 */
internal object ShelfMetrics {
    /** 页面左右留白：网格卡片与悬浮底栏共用同一条对齐线（设计稿实测 29px ÷ 2） */
    val PagePadding = 15.dp

    /**
     * 顶栏与排序行的左右留白：设计稿里品牌名/圆钮/排序箭头比卡片再多缩进约 4dp
     * （品牌墨迹 x=40px、箭头 x=52px，而卡片左边缘 x=29px）。刻意分成两个常量而不是
     * 硬凑成一个，否则要么顶栏贴边、要么整片网格跟着内缩。
     */
    val HeaderPadding = 19.dp

    /** 网格列间距与行间距 */
    val GridSpacing = 12.dp

    /** 卡片宽度下限：再窄书名就只能显示一两个字 */
    val MinCardWidth = 118.dp

    /** 封面宽高比（宽/高）：设计稿实测 238×344 */
    const val CoverAspect = 238f / 344f

    /** 卡片与封面的圆角（设计稿实测 13px，两者同值，所以封面贴齐卡片顶） */
    val CardCorner = 7.dp

    /** 卡片正文区左右内边距，以及正文区的固定高度 */
    val CardPadding = 8.dp
    val CardBodyHeight = 69.dp

    /** 「已读/总章数」徽标：距封面右上角的内缩 + 胶囊内边距（胶囊实测 43.5×17dp） */
    val BadgeInset = 4.5.dp
    val BadgePaddingH = 6.dp
    val BadgePaddingV = 2.dp

    /** 进度条：轨道高 5.5dp；填充最窄 6dp（设计稿 1% 的进度也仍有一枚圆点） */
    val ProgressTrackHeight = 5.5.dp
    val ProgressMinFill = 6.dp

    /** 进度条与右侧百分比之间的间隙 */
    val ProgressPercentGap = 10.dp

    /** 顶栏圆钮直径与两钮间距 */
    val HeaderButtonSize = 40.dp
    val HeaderButtonGap = 12.dp

    /** 排序行上方/下方留白（实测：副标题行盒底到排序行盒顶 = 21dp，到网格顶 = 12dp） */
    val SortRowTopPadding = 19.dp
    val SortRowBottomPadding = 12.dp

    /** 液态玻璃悬浮底栏：条高 64dp、全胶囊圆角 32dp、距手势区 12dp、选中胶囊内缩 5dp */
    val NavBarHeight = 64.dp
    val NavBarCorner = 32.dp
    val NavBarBottomGap = 12.dp
    val NavItemInset = 5.dp
    /** 选中胶囊圆角 =（条高 - 内缩×2）÷ 2，维持全胶囊 */
    val NavItemCorner = 27.dp

    /** 滚动呼吸：列表最后一项完整滚出玻璃底栏所需的额外余量（玻璃栏下方有内容透出） */
    val NavBarScrollBreath = 16.dp

    /** 蓝色玻璃加号按钮：58dp 大圆角方形 */
    val NavFabSize = 58.dp
    val NavFabCorner = 19.dp

    /** 书架背景插画的宽高比：裁自设计稿 821×438（很扁的一张画，所以不能 cover 整屏） */
    const val BackdropAspect = 821f / 438f

    /**
     * 背景插画的高度范围。按宽度算，410dp 屏上正好 219dp；夹住上下限是为了横屏/矮屏：
     * 那里屏高只有 400dp 出头，不夹的话这张扁画会吃掉一半屏幕。
     */
    val BackdropMinHeight = 160.dp
    val BackdropMaxHeight = 260.dp
}

/**
 * 背景插画的高度：通栏铺满宽度，高度按原始比例推出，再夹进 [min]/[max]。
 *
 * 不夹的后果只出现在横屏/矮屏：这张画宽高比 1.87，410dp 竖屏上 219dp 刚好，
 * 但横屏屏高只有 400dp 出头，通栏后它要吃掉一半屏。
 */
internal fun backdropHeight(
    availableWidth: Dp,
    minHeight: Dp = ShelfMetrics.BackdropMinHeight,
    maxHeight: Dp = ShelfMetrics.BackdropMaxHeight
): Dp = (availableWidth / ShelfMetrics.BackdropAspect).coerceIn(minHeight, maxHeight)

/**
 * 网格列数：按可用宽度推，手机至少 3 列（视觉基线就是 3 列密排），
 * 平板/折叠屏展开后自动加列。
 *
 * 列数只决定「一行几本书」；封面高度再由列宽按书封比例推出。不要把封面高度写成
 * 屏高的比例 —— 那种算法在平板上一屏 3 行会把封面拉成方块。
 */
internal fun shelfColumns(
    availableWidth: Dp,
    pagePadding: Dp = ShelfMetrics.PagePadding,
    spacing: Dp = ShelfMetrics.GridSpacing,
    minCardWidth: Dp = ShelfMetrics.MinCardWidth
): Int = maxOf(
    3,
    ((availableWidth - pagePadding * 2 + spacing) / (minCardWidth + spacing)).toInt()
)

/** 列宽：可用宽度扣掉左右留白与列间距后均分。 */
internal fun shelfCardWidth(
    availableWidth: Dp,
    columns: Int,
    pagePadding: Dp = ShelfMetrics.PagePadding,
    spacing: Dp = ShelfMetrics.GridSpacing
): Dp = (availableWidth - pagePadding * 2 - spacing * (columns - 1)) / columns

/**
 * 已读章节数 —— 封面徽标的 `N` 与正文「已读 N / M 章」共用这一个算法。
 *
 * `BookShelfItem.progress` 是「最后阅读章节的 1-based 序号 ÷ 总章数」，所以乘回去
 * **四舍五入**就还原出章节序号（用截断会在 `(3f/110f)*110f` 这类浮点误差上少 1 章）。
 * 有进度即至少 1 章，并夹在总章数以内。
 */
internal fun readChapters(progress: Float, totalChapters: Int): Int {
    if (totalChapters <= 0 || progress <= 0f) return 0
    return (progress * totalChapters).roundToInt().coerceIn(1, totalChapters)
}

/**
 * 百分比标签。设计稿六张卡片的取值（2.7%→3、0.9%→1、0.2%→0、29.2%→29、3.8%→4、13.3%→13）
 * 全部与四舍五入吻合，所以这里用 round 而不是 ceil —— 用 ceil 会把「刚开始读」显示成 1%。
 */
internal fun percentLabel(progress: Float): String =
    "${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%"

/**
 * 进度条填充宽度（px）：名义比例之外还有一条「至少看得见」的下限，
 * 否则 1% 在 110dp 宽的轨道上不足 1px，等于没有反馈。
 * 进度为 0 时返回 0（未开始阅读不该有一枚假装读过的圆点）。
 */
internal fun progressFillWidth(trackWidthPx: Float, progress: Float, minFillPx: Float): Float {
    if (trackWidthPx <= 0f) return 0f
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction <= 0f) return 0f
    return maxOf(trackWidthPx * fraction, minFillPx).coerceAtMost(trackWidthPx)
}
