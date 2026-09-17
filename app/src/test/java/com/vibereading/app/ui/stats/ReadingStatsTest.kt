package com.vibereading.app.ui.stats

import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** 统计聚合纯函数：断言结构化结果（计数、比例、排序、截断），不涉及 UI。 */
class ReadingStatsTest {

    private fun item(
        id: Long,
        title: String = "书$id",
        totalChapters: Int = 10,
        translated: Int = 0,
        progress: Float = 0f,
        lastReadAt: Long = 0L,
        sourceLanguage: String = "zh",
        format: String = "txt"
    ) = BookShelfItem(
        book = Book(
            id = id,
            title = title,
            totalChapters = totalChapters,
            lastReadAt = lastReadAt,
            sourceLanguage = sourceLanguage,
            format = format
        ),
        translatedCount = translated,
        progress = progress
    )

    @Test
    fun `空书库全部为零且无最近阅读`() {
        val stats = readingStatsOf(emptyList())
        assertEquals(0, stats.bookCount)
        assertEquals(0, stats.totalChapters)
        assertEquals(0, stats.translatedChapters)
        assertEquals(0f, stats.translationRatio, 0f)
        assertEquals(0, stats.recent.size)
        assertEquals(0, stats.txtCount)
    }

    @Test
    fun `混合书库的计数与比例`() {
        val stats = readingStatsOf(
            listOf(
                item(id = 1, totalChapters = 10, translated = 0, progress = 0f),          // 未开始，中文 TXT
                item(id = 2, totalChapters = 100, translated = 50, progress = 0.5f,       // 在读，英文 EPUB
                    sourceLanguage = "en", format = "epub"),
                item(id = 3, totalChapters = 4, translated = 4, progress = 1f)            // 读到末章，中文 TXT
            )
        )
        assertEquals(3, stats.bookCount)
        assertEquals(1, stats.readingCount)
        assertEquals(1, stats.reachedEndCount)
        assertEquals(1, stats.untouchedCount)
        assertEquals(114, stats.totalChapters)
        assertEquals(54, stats.translatedChapters)
        assertEquals(54f / 114f, stats.translationRatio, 1e-6f)
        assertEquals(2, stats.chineseBookCount)
        assertEquals(1, stats.englishBookCount)
        assertEquals(1, stats.epubCount)
        assertEquals(2, stats.txtCount)
    }

    @Test
    fun `最近阅读按 lastReadAt 倒序并截断到上限`() {
        val items = (1..7).map { index ->
            item(id = index.toLong(), totalChapters = 20, translated = index, progress = 0.1f * index,
                lastReadAt = index * 100L)
        }
        val stats = readingStatsOf(items)
        assertEquals(ReadingStats.RECENT_LIMIT, stats.recent.size)
        // 最近的是 id 7..3；未读过的书（lastReadAt = 0）不进入列表
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), stats.recent.map { it.bookId })
        val top = stats.recent.first()
        assertEquals("书7", top.title)
        assertEquals(20, top.totalChapters)
        assertEquals(7, top.translatedCount)
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
