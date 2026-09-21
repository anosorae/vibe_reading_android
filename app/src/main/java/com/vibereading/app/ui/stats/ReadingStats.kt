package com.vibereading.app.ui.stats

import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.ui.bookshelf.readChapters
import java.time.LocalDate

/** 「最近阅读」卡片的行数据：进度展示所需的最小字段集。 */
data class RecentReading(
    val bookId: Long,
    val title: String,
    val progress: Float,
    val readChapters: Int,
    val totalChapters: Int,
    val translatedCount: Int,
    /** 本书累计阅读秒数；时长采集（2026-09）之前的阅读历史为 0，展示层据此隐藏「累读」。 */
    val seconds: Long = 0L
)

/**
 * 时长统计的一次输入快照：`reading_time_daily` 表的两种聚合视图 + 聚合当天的 epochDay。
 * 纯函数以本结构为输入，today 由调用方传入以保证可测。
 */
data class ReadingTimeSnapshot(
    /** epochDay → 当天总秒数（跨书合并）。 */
    val dailyTotals: Map<Long, Long> = emptyMap(),
    /** bookId → 本书累计秒数。 */
    val bookTotals: Map<Long, Long> = emptyMap(),
    val todayEpochDay: Long = 0L
)

/**
 * 统计页聚合数据。藏书/章节计数由书架条目（Room chapters 派生）计算；
 * 阅读时长来自 `reading_time_daily` 按天聚合——2026-09 起采集，此前的阅读没有
 * 时长记录、不估算补造，展示层以 firstRecordEpochDay 标注起始日期。全部是本地真实数据。
 */
data class ReadingStats(
    val bookCount: Int,
    /** 已开始且未读到末章 */
    val readingCount: Int,
    /** 读到最后一章（progress ≥ 1，指到达末章，不承诺逐字读完） */
    val reachedEndCount: Int,
    val totalChapters: Int,
    val todaySeconds: Long,
    /** 本周 = 一起（含）至今天。 */
    val weekSeconds: Long,
    val totalSeconds: Long,
    /** 日均 = 累计 ÷ 有记录天数（活跃天均值，不被闲置期稀释）。 */
    val avgDailySeconds: Long,
    /** 有时长记录的天数（活跃天）。 */
    val activeDays: Int,
    /** 首条时长记录所在天；无任何记录为 null（口径说明与空态判断用）。 */
    val firstRecordEpochDay: Long?,
    /** 每日秒数明细（epochDay → 秒），柱状图序列的数据源。 */
    val dailyTotals: Map<Long, Long> = emptyMap(),
    /** 聚合当天的 epochDay（图表横轴定位用）。 */
    val todayEpochDay: Long = 0L,
    val recent: List<RecentReading>
) {
    companion object {
        const val RECENT_LIMIT = 5
    }
}

/**
 * 统计聚合的唯一计算入口（纯函数）。
 * 「已读章节数」复用书架 [readChapters]（四舍五入还原章节序号），保证统计页与
 * 书架徽标对同一本书永远给出同一个数字；时长聚合见 [ReadingTimeSnapshot]。
 */
fun readingStatsOf(
    items: List<BookShelfItem>,
    time: ReadingTimeSnapshot = ReadingTimeSnapshot()
): ReadingStats {
    var reading = 0
    var reachedEnd = 0
    var totalChapters = 0
    for (item in items) {
        val progress = item.progress
        when {
            progress >= 1f -> reachedEnd++
            progress > 0f -> reading++
        }
        totalChapters += item.book.totalChapters
    }
    // 周一起算：回退到本周一（含）
    val weekStartEpochDay = LocalDate.ofEpochDay(time.todayEpochDay)
        .minusDays((LocalDate.ofEpochDay(time.todayEpochDay).dayOfWeek.value - 1).toLong())
        .toEpochDay()
    var todaySeconds = 0L
    var weekSeconds = 0L
    var totalSeconds = 0L
    var activeDays = 0
    var firstDay: Long? = null
    for ((day, seconds) in time.dailyTotals) {
        totalSeconds += seconds
        if (day == time.todayEpochDay) todaySeconds += seconds
        if (day >= weekStartEpochDay) weekSeconds += seconds
        activeDays++
        if (firstDay == null || day < firstDay) firstDay = day
    }
    val recent = items.asSequence()
        .filter { it.book.lastReadAt > 0 }
        .sortedByDescending { it.book.lastReadAt }
        .take(ReadingStats.RECENT_LIMIT)
        .map {
            RecentReading(
                bookId = it.book.id,
                title = it.book.title,
                progress = it.progress,
                readChapters = readChapters(it.progress, it.book.totalChapters),
                totalChapters = it.book.totalChapters,
                translatedCount = it.translatedCount,
                seconds = time.bookTotals[it.book.id] ?: 0L
            )
        }
        .toList()
    return ReadingStats(
        bookCount = items.size,
        readingCount = reading,
        reachedEndCount = reachedEnd,
        totalChapters = totalChapters,
        todaySeconds = todaySeconds,
        weekSeconds = weekSeconds,
        totalSeconds = totalSeconds,
        avgDailySeconds = if (activeDays > 0) totalSeconds / activeDays else 0L,
        activeDays = activeDays,
        firstRecordEpochDay = firstDay,
        dailyTotals = time.dailyTotals,
        todayEpochDay = time.todayEpochDay,
        recent = recent
    )
}

/**
 * 柱状图序列：截至 today（含）连续 [windowDays] 天的每日秒数，旧 → 新。
 * 缺记录的天补零，图表横轴恒定宽度。
 */
fun dailySeries(dailyTotals: Map<Long, Long>, todayEpochDay: Long, windowDays: Int): List<Long> {
    return (todayEpochDay - windowDays + 1..todayEpochDay).map { dailyTotals[it] ?: 0L }
}

/**
 * 阅读时长的展示格式（中文习惯）：「3 小时 24 分」；不足 1 小时只显分钟（「42 分」）；
 * 不足 1 分钟显「不足 1 分钟」。纯函数，供单测固定行为。
 */
fun formatReadingDuration(seconds: Long): String = when {
    seconds < 60L -> "不足 1 分钟"
    seconds < 3600L -> "${seconds / 60} 分"
    else -> buildString {
        append(seconds / 3600).append(" 小时")
        val minutes = seconds % 3600 / 60
        if (minutes > 0) append(" ").append(minutes).append(" 分")
    }
}

/** 统计口径说明里的起始日期：「2026 年 9 月 21 日」。 */
fun formatStatsStartDate(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
}

/**
 * 统计数字的展示格式：≥1 万按中文习惯折算成「x.x万」（小数尾零省略），否则原样输出。
 * 纯函数，供单测固定行为。
 */
fun formatStatCount(value: Int): String {
    if (value < 10000) return value.toString()
    val wan = value / 10000.0
    val text = if (wan >= 100) wan.toInt().toString() else String.format("%.1f", wan).trimEnd('0').trimEnd('.')
    return "${text}万"
}
