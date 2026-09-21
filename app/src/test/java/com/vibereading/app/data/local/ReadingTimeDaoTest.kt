package com.vibereading.app.data.local

import com.vibereading.app.data.local.dao.BookTimeTotal
import com.vibereading.app.data.local.dao.DailyTimeTotal
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 阅读时长 DAO：UPSERT 累加与两种聚合查询。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingTimeDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = newInMemoryDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `同一天多次增量累加`() = runBlocking {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 1)
        val dao = db.readingTimeDao()
        dao.addSeconds(1L, 100L, 60L)
        dao.addSeconds(1L, 100L, 45L)
        dao.addSeconds(1L, 100L, 15L)
        assertEquals(listOf(DailyTimeTotal(epochDay = 100L, seconds = 120L)), dao.observeDailyTotals().first())
        assertEquals(listOf(BookTimeTotal(bookId = 1L, seconds = 120L)), dao.observeBookTotals().first())
    }

    @Test
    fun `按天与按书聚合跨行合并`() = runBlocking {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 1)
        seedBookAndChapters(db, bookId = 2L, chapterCount = 1)
        val dao = db.readingTimeDao()
        dao.addSeconds(1L, 100L, 30L)
        dao.addSeconds(1L, 101L, 70L)
        dao.addSeconds(2L, 101L, 50L)
        val daily = dao.observeDailyTotals().first().associate { it.epochDay to it.seconds }
        assertEquals(mapOf(100L to 30L, 101L to 120L), daily)
        val byBook = dao.observeBookTotals().first().associate { it.bookId to it.seconds }
        assertEquals(mapOf(1L to 100L, 2L to 50L), byBook)
    }

    @Test
    fun `删书级联清掉该书时长`() = runBlocking {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 1)
        val dao = db.readingTimeDao()
        dao.addSeconds(1L, 100L, 60L)
        db.bookDao().deleteById(1L)
        assertEquals(emptyList<DailyTimeTotal>(), dao.observeDailyTotals().first())
        assertEquals(emptyList<BookTimeTotal>(), dao.observeBookTotals().first())
    }
}
