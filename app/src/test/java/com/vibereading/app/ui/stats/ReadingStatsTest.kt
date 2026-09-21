package com.vibereading.app.ui.stats

import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 统计聚合纯函数：断言结构化结果（计数、时长口径、序列、格式化），不涉及 UI。 */
class ReadingStatsTest {

    private fun item(
        id: Long,
        title: String = "书$id",
        totalChapters: Int = 10,
        translated: Int = 0,
        progress: Float = 0f,
        lastReadAt: Long = 0L
    ) = BookShelfItem(
        book = Book(
            id = id,
            title = title,
            totalChapters = totalChapters,
            lastReadAt = lastReadAt
        ),
        translatedCount = translated,
        progress = progress
    )

    // ── 藏书/章节计数 ──

    @Test
    fun `空书库全部为零且无最近阅读`() {
        val stats = readingStatsOf(emptyList())
        assertEquals(0, stats.bookCount)
        assertEquals(0, stats.totalChapters)
        assertEquals(0, stats.recent.size)
        assertEquals(0L, stats.totalSeconds)
        assertEquals(0, stats.activeDays)
        assertNull(stats.firstRecordEpochDay)
    }

    @Test
    fun `混合书库的在读与读到末章计数`() {
        val stats = readingStatsOf(
            listOf(
                item(id = 1, totalChapters = 10, progress = 0f),    // 未开始
                item(id = 2, totalChapters = 100, progress = 0.5f), // 在读
                item(id = 3, totalChapters = 4, progress = 1f)      // 读到末章
            )
        )
        assertEquals(3, stats.bookCount)
        assertEquals(1, stats.readingCount)
        assertEquals(1, stats.reachedEndCount)
        assertEquals(114, stats.totalChapters)
    }

    // ── 阅读时长聚合口径 ──

    @Test
    fun `今日本周累计与活跃天均值`() {
        // today 取一个周一（2026-09-21 是周一，epochDay = 20686）
        val today = LocalDate.of(2026, 9, 21).toEpochDay()
        assertEquals(0, LocalDate.ofEpochDay(today).dayOfWeek.value - 1) // 自检：周一
        val snapshot = ReadingTimeSnapshot(
            dailyTotals = mapOf(
                today - 10L to 600L,  // 上周：只进累计
                today - 3L to 1800L,  // 上周五：本周从今天（周一）起算，不算本周
                today to 3600L        // 今天
            ),
            todayEpochDay = today
        )
        val stats = readingStatsOf(emptyList(), snapshot)
        assertEquals(3600L, stats.todaySeconds)
        assertEquals(3600L, stats.weekSeconds)
        assertEquals(6000L, stats.totalSeconds)
        assertEquals(3, stats.activeDays)
        assertEquals(2000L, stats.avgDailySeconds)
        assertEquals(today - 10L, stats.firstRecordEpochDay)
    }

    @Test
    fun `本周从周一起算且含上周日不混入`() {
        // 2026-09-20 是周日；以它为 today，本周起于 09-14（周一）
        val sunday = LocalDate.of(2026, 9, 20).toEpochDay()
        val snapshot = ReadingTimeSnapshot(
            dailyTotals = mapOf(
                sunday - 7L to 100L,   // 上周一：不算本周
                sunday - 6L to 200L,   // 本周一
                sunday to 300L         // 今天（周日）
            ),
            todayEpochDay = sunday
        )
        val stats = readingStatsOf(emptyList(), snapshot)
        assertEquals(500L, stats.weekSeconds)
        assertEquals(300L, stats.todaySeconds)
    }

    @Test
    fun `柱状图序列补零且旧到新排列`() {
        val today = 100L
        val series = dailySeries(
            dailyTotals = mapOf(98L to 60L, 100L to 120L),
            todayEpochDay = today,
            windowDays = 7
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 60L, 0L, 120L), series)
    }

    // ── 最近阅读 ──

    @Test
    fun `最近阅读按 lastReadAt 倒序并截断到上限，带本书时长`() {
        val items = (1..7).map { index ->
            item(id = index.toLong(), totalChapters = 20, translated = index, progress = 0.1f * index,
                lastReadAt = index * 100L)
        }
        val snapshot = ReadingTimeSnapshot(
            dailyTotals = emptyMap(),
            bookTotals = mapOf(7L to 3600L, 3L to 90L)
        )
        val stats = readingStatsOf(items, snapshot)
        assertEquals(ReadingStats.RECENT_LIMIT, stats.recent.size)
        // 最近的是 id 7..3；未读过的书（lastReadAt = 0）不进入列表
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), stats.recent.map { it.bookId })
        assertEquals(3600L, stats.recent.first().seconds)
        assertEquals(90L, stats.recent.last().seconds)
        assertEquals(0L, stats.recent[1].seconds) // 无记录的书为 0，展示层隐藏「累读」
        // 已读章节数与书架徽标同一口径（progress × 总章数四舍五入：0.3 × 20 = 6）
        assertEquals(6, stats.recent.last().readChapters)
    }

    @Test
    fun `lastReadAt 为零的书不进入最近阅读`() {
        val stats = readingStatsOf(
            listOf(
                item(id = 1, progress = 0.5f, lastReadAt = 0L),
                item(id = 2, progress = 0f, lastReadAt = 500L)
            )
        )
        assertEquals(listOf(2L), stats.recent.map { it.bookId })
    }

    // ── 格式化 ──

    @Test
    fun `时长格式化按中文习惯`() {
        assertEquals("不足 1 分钟", formatReadingDuration(0L))
        assertEquals("不足 1 分钟", formatReadingDuration(59L))
        assertEquals("1 分", formatReadingDuration(60L))
        assertEquals("42 分", formatReadingDuration(42L * 60L + 59L))
        assertEquals("1 小时", formatReadingDuration(3600L))
        assertEquals("3 小时 24 分", formatReadingDuration(3L * 3600L + 24L * 60L))
    }

    @Test
    fun `起始日期格式化`() {
        assertEquals("2026 年 9 月 21 日", formatStatsStartDate(LocalDate.of(2026, 9, 21).toEpochDay()))
        assertEquals("1970 年 1 月 1 日", formatStatsStartDate(0L))
    }

    @Test
    fun `数字格式化按中文习惯折算万`() {
        assertEquals("0", formatStatCount(0))
        assertEquals("9999", formatStatCount(9999))
        assertEquals("1万", formatStatCount(10000))
        assertEquals("1.2万", formatStatCount(12345))
        assertEquals("3万", formatStatCount(30000))
        assertEquals("12.3万", formatStatCount(123456))
    }
}
