package com.vibereading.app.ui.stats

import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.ui.bookshelf.readChapters

/** 「最近阅读」卡片的行数据：进度展示所需的最小字段集。 */
data class RecentReading(
    val bookId: Long,
    val title: String,
    val progress: Float,
    val readChapters: Int,
    val totalChapters: Int,
    val translatedCount: Int
)

/**
 * 统计页聚合数据。全部由书架条目（Room chapters 派生）计算，是**本地真实数据**：
 * 阅读时长、连续打卡等未采集的口径一律不做，避免「看起来有数据其实在编」。
 */
data class ReadingStats(
    val bookCount: Int,
    /** 已开始且未读到末章 */
    val readingCount: Int,
    /** 读到最后一章（progress ≥ 1，指到达末章，不承诺逐字读完） */
    val reachedEndCount: Int,
    val totalChapters: Int,
    val translatedChapters: Int,
    val chineseBookCount: Int,
    val englishBookCount: Int,
    val epubCount: Int,
    val recent: List<RecentReading>
) {
    /** 双语覆盖率：已译章节 / 章节总数；无章节时为 0。 */
    val translationRatio: Float
        get() = if (totalChapters > 0) translatedChapters.toFloat() / totalChapters else 0f

    val txtCount: Int get() = bookCount - epubCount
    val untouchedCount: Int get() = bookCount - readingCount - reachedEndCount

    companion object {
        const val RECENT_LIMIT = 5
    }
}

/**
 * 统计聚合的唯一计算入口（纯函数）。
 * 「已读章节数」复用书架 [readChapters]（四舍五入还原章节序号），保证统计页与
 * 书架徽标对同一本书永远给出同一个数字。
 */
fun readingStatsOf(items: List<BookShelfItem>): ReadingStats {
    var reading = 0
    var reachedEnd = 0
    var totalChapters = 0
    var translated = 0
    var chinese = 0
    var english = 0
    var epub = 0
    for (item in items) {
        val progress = item.progress
        when {
            progress >= 1f -> reachedEnd++
            progress > 0f -> reading++
        }
        totalChapters += item.book.totalChapters
        translated += item.translatedCount
        when (item.book.sourceLanguage) {
            "en" -> english++
            else -> chinese++
        }
        if (item.book.format == "epub") epub++
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
                translatedCount = it.translatedCount
            )
        }
        .toList()
    return ReadingStats(
        bookCount = items.size,
        readingCount = reading,
        reachedEndCount = reachedEnd,
        totalChapters = totalChapters,
        translatedChapters = translated,
        chineseBookCount = chinese,
        englishBookCount = english,
        epubCount = epub,
        recent = recent
    )
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
