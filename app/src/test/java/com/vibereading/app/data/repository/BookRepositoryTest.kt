package com.vibereading.app.data.repository

import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BookRepository 单测：真实 Room 内存库验证包装层契约——
 * 域映射字段完整性、进度 offset 下界钳制、更新返回语义与书架条目派生。
 * SQL 级 stale 防护已在 BookChapterDaoTest 覆盖，此处不重复。
 */
@RunWith(RobolectricTestRunner::class)
class BookRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BookRepository

    @Before
    fun setUp() {
        db = newInMemoryDb()
        repo = BookRepository(db.bookDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and read back preserves all domain fields`() = runBlocking {
        val book = Book(
            title = "测试书", filePath = "/tmp/a.epub", totalChapters = 7,
            lastReadChapterId = 3L, lastReadOffset = 42, lastReadAt = 123L,
            languageMode = "en", sourceLanguage = "en",
            format = "epub", coverPath = "covers/9_abc.jpg", createdAt = 456L
        )
        val id = repo.insert(book)
        assertEquals(book.copy(id = id), repo.getBookByIdOnce(id))
    }

    @Test
    fun `updateLastReadProgress clamps negative offset and reports unknown book`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, bookId = 1L, chapterCount = 2)

        assertTrue(repo.updateLastReadProgress(1L, chapterIds[0], -5))
        val book = repo.getBookByIdOnce(1L)!!
        assertEquals(chapterIds[0], book.lastReadChapterId)
        assertEquals(0, book.lastReadOffset)

        assertFalse(repo.updateLastReadProgress(999L, chapterIds[0], 0))
    }

    @Test
    fun `updateLanguageMode and updateSourceLanguage report unknown book`() = runBlocking {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 1)

        assertTrue(repo.updateLanguageMode(1L, "en"))
        assertEquals("en", repo.getBookByIdOnce(1L)!!.languageMode)
        assertTrue(repo.updateSourceLanguage(1L, "en"))
        assertEquals("en", repo.getBookByIdOnce(1L)!!.sourceLanguage)

        assertFalse(repo.updateLanguageMode(999L, "zh"))
        assertFalse(repo.updateSourceLanguage(999L, "zh"))
    }

    @Test
    fun `shelf item derives translated count chapter title and progress`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, bookId = 1L, chapterCount = 4)
        val chapterDao = db.chapterDao()
        // 两章走完 start→complete 生命周期落 DONE，阅读位置停在第 3 章
        chapterDao.startTranslationRun(1L, chapterIds[0], 10L, Chapter.STATUS_IN_PROGRESS)
        chapterDao.completeTranslationRun(1L, chapterIds[0], 10L, "译文一", Chapter.STATUS_DONE)
        chapterDao.startTranslationRun(1L, chapterIds[1], 11L, Chapter.STATUS_IN_PROGRESS)
        chapterDao.completeTranslationRun(1L, chapterIds[1], 11L, "译文二", Chapter.STATUS_DONE)
        repo.updateLastReadProgress(1L, chapterIds[2], 0)

        val shelf = repo.getShelfItems().first().single()
        assertEquals(2, shelf.translatedCount)
        assertEquals("第3章", shelf.lastReadChapterTitle)
        assertEquals(0.75f, shelf.progress, 0.0001f) // (chapterIndex 2 + 1) / 4
    }

    @Test
    fun `delete removes the book from shelf`() = runBlocking {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 1)
        repo.delete(1L)
        assertNull(repo.getBookByIdOnce(1L))
        assertTrue(repo.getShelfItems().first().isEmpty())
    }
}
