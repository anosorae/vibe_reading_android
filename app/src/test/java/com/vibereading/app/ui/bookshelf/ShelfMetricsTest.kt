package com.vibereading.app.ui.bookshelf

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架版面纯逻辑。断言的是**结构化结果**（列数、列宽守恒、章节数、填充宽度），
 * 不是易变的像素值。
 *
 * 夹具刻意取自视觉基线设计稿的六张卡片 —— 那份稿子上的「3/110」「29%」等数字
 * 就是这几个函数的期望输出，所以这组测试同时也是设计基线的回归网。
 */
class ShelfMetricsTest {

    /** 设计稿六张卡片：(已读章节序号, 总章数, 设计稿上的百分比文案) */
    private val designCards = listOf(
        Triple(3, 110, "3%"),
        Triple(13, 1501, "1%"),
        Triple(2, 1321, "0%"),
        Triple(28, 96, "29%"),
        Triple(17, 443, "4%"),
        Triple(51, 384, "13%"),
    )

    private fun progressOf(chapterIndex: Int, total: Int) = chapterIndex.toFloat() / total

    @Test
    fun `列宽守恒 - 列宽加间距加留白等于可用宽度`() {
        // 设计稿画布 410dp，以及常见手机/平板宽度
        listOf(360.dp, 393.dp, 410.dp, 411.dp, 600.dp, 800.dp).forEach { width ->
            val columns = shelfColumns(width)
            val card = shelfCardWidth(width, columns)
            val used = card * columns + ShelfMetrics.GridSpacing * (columns - 1) +
                ShelfMetrics.PagePadding * 2
            // Dp 是 Float 包装，均分后允许亚像素误差；关键是「不留空隙也不溢出」
            assertTrue(
                "宽度 $width：$columns 列 × $card + 间距 + 留白 = $used，与可用宽度不符",
                kotlin.math.abs(used.value - width.value) < 0.01f
            )
        }
    }

    @Test
    fun `410dp 画布落成设计稿的 3 列 119dp`() {
        val columns = shelfColumns(410.dp)
        assertEquals(3, columns)
        val card = shelfCardWidth(410.dp, columns)
        // 设计稿实测列宽 238px ÷ 2 = 119dp
        assertTrue("列宽 $card 应接近 119dp", kotlin.math.abs(card.value - 119f) < 1f)
        // 封面高 = 列宽 ÷ 0.6919，设计稿实测 172dp
        val coverHeight = card / ShelfMetrics.CoverAspect
        assertTrue("封面高 $coverHeight 应接近 172dp", kotlin.math.abs(coverHeight.value - 172f) < 2f)
    }

    @Test
    fun `窄屏不塌成 2 列 - 手机保底 3 列`() {
        // 视觉基线就是 3 列密排；再窄也只是卡片变小，不该退回 2 列
        assertEquals(3, shelfColumns(320.dp))
        assertEquals(3, shelfColumns(360.dp))
    }

    @Test
    fun `宽屏自动加列`() {
        assertTrue(shelfColumns(600.dp) >= 4)
        assertTrue(shelfColumns(900.dp) > shelfColumns(600.dp))
    }

    @Test
    fun `已读章节数与设计稿一致`() {
        designCards.forEach { (read, total, _) ->
            assertEquals(read, readChapters(progressOf(read, total), total))
        }
    }

    @Test
    fun `已读章节数还原浮点除法不丢一章`() {
        // progressOf 是 (index+1)/total 的浮点结果，乘回去若用截断会在
        // (3f/110f)*110f = 2.9999998 这类情况下少算一章。
        // 遍历所有合法章节序号，并额外覆盖若干会出现浮点误差的总章数。
        listOf(110, 96, 384, 443, 1321, 1501, 7, 3).forEach { total ->
            (1..total).forEach { index ->
                assertEquals(
                    "总 $total 章 / 第 $index 章",
                    index,
                    readChapters(progressOf(index, total), total)
                )
            }
        }
    }

    @Test
    fun `未开始读和空书都是 0 章`() {
        assertEquals(0, readChapters(0f, 110))
        assertEquals(0, readChapters(0f, 0))
        assertEquals(0, readChapters(0.5f, 0))
    }

    @Test
    fun `已读章节数不会超过总章数`() {
        assertEquals(110, readChapters(1f, 110))
        assertEquals(110, readChapters(1.5f, 110))
    }

    @Test
    fun `百分比文案与设计稿一致`() {
        designCards.forEach { (read, total, label) ->
            assertEquals(label, percentLabel(progressOf(read, total)))
        }
    }

    @Test
    fun `进度为 0 时不画填充`() {
        // 未开始阅读不该有一枚假装读过的圆点
        assertEquals(0f, progressFillWidth(trackWidthPx = 200f, progress = 0f, minFillPx = 12f), 0f)
    }

    @Test
    fun `极低进度至少画出最小宽度`() {
        // 1% 在 200px 轨道上只有 2px，必须被抬到最小宽度才看得见
        assertEquals(12f, progressFillWidth(trackWidthPx = 200f, progress = 0.01f, minFillPx = 12f), 0.001f)
    }

    @Test
    fun `进度足够大时按比例填充`() {
        assertEquals(100f, progressFillWidth(trackWidthPx = 200f, progress = 0.5f, minFillPx = 12f), 0.001f)
        assertEquals(200f, progressFillWidth(trackWidthPx = 200f, progress = 1f, minFillPx = 12f), 0.001f)
    }

    @Test
    fun `填充永不超出轨道`() {
        // 最小宽度比轨道还宽时（极窄卡片）也要夹住，否则圆头会溢出卡片
        val filled = progressFillWidth(trackWidthPx = 8f, progress = 0.01f, minFillPx = 12f)
        assertEquals(8f, filled, 0.001f)
        // 脏数据（进度 > 1）同样夹住
        assertEquals(200f, progressFillWidth(trackWidthPx = 200f, progress = 3f, minFillPx = 12f), 0.001f)
    }

    @Test
    fun `轨道宽度为零时不除零`() {
        assertEquals(0f, progressFillWidth(trackWidthPx = 0f, progress = 0.5f, minFillPx = 12f), 0f)
    }

    @Test
    fun `背景插画在手机竖屏上按原始比例`() {
        // 821×438 的资产通栏后，410dp 屏上应得 219dp —— 正好是设计稿里页脚那一带的高度
        val h = backdropHeight(410.dp)
        assertTrue("410dp 宽应得约 219dp 高，实际 $h", kotlin.math.abs(h.value - 219f) < 2f)
        // 常见手机宽度都不该撞到上下限
        listOf(360.dp, 393.dp, 411.dp, 430.dp).forEach {
            val height = backdropHeight(it)
            assertTrue("$it 宽不应被夹住，实际 $height", height.value > 160f && height.value < 260f)
        }
    }

    @Test
    fun `背景插画在矮屏上被夹住上限`() {
        // 横屏：可用宽度 900dp 通栏会推出 480dp，吃掉半屏，必须夹住
        assertEquals(ShelfMetrics.BackdropMaxHeight, backdropHeight(900.dp))
        assertEquals(ShelfMetrics.BackdropMaxHeight, backdropHeight(1200.dp))
    }

    @Test
    fun `背景插画在极窄屏上不低于下限`() {
        assertEquals(ShelfMetrics.BackdropMinHeight, backdropHeight(100.dp))
    }
}
