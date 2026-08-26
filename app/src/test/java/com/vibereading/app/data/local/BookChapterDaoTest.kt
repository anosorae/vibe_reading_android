package com.vibereading.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibereading.app.data.local.dao.BookWithProgress
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room DAO/Repository 集成回归测试：
 * - translationRunId 数据库级 stale 防护（旧任务不能污染新任务/重译后的章节）；
 * - translatedCount 从 chapters 表实时派生，无冗余缓存字段；
 * - 阅读进度与章节归属校验。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BookChapterDaoTest {

    private lateinit var db: AppDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBookAndChapters(
        bookId: Long = 1L,
        chapterCount: Int = 3
    ): List<Long> {
        db.bookDao().insert(
            BookEntity(
                id = bookId, title = "测试书", totalChapters = chapterCount,
                lastReadAt = 1000L, createdAt = 1000L
            )
        )
        val ids = db.chapterDao().insertAll(
            (0 until chapterCount).map { index ->
                ChapterEntity(
                    bookId = bookId,
                    title = "第${index + 1}章",
                    chapterIndex = index,
                    content = "正文${index + 1}"
                )
            }
        )
        return ids
    }

    @Test
    fun `translatedCount derives from done chapters`() = runBlocking {
        val bookId = 1L
        val ids = seedBookAndChapters(bookId)
        // 初始：无 DONE 章节
        val items0 = db.bookDao().getBooksWithProgress().first()
        assertEquals(0, items0[0].translatedCount)

        // 两章完成
        db.chapterDao().startTranslationRun(bookId, ids[0], 1L, 1)
        db.chapterDao().completeTranslationRun(bookId, ids[0], 1L, "EN 1", 2)
        db.chapterDao().startTranslationRun(bookId, ids[1], 2L, 1)
        db.chapterDao().completeTranslationRun(bookId, ids[1], 2L, "EN 2", 2)
        val items1 = db.bookDao().getBooksWithProgress().first()
        assertEquals(2, items1[0].translatedCount)

        // 重置一章后计数回落
        db.chapterDao().resetChapter(bookId, ids[1])
        val items2 = db.bookDao().getBooksWithProgress().first()
        assertEquals(1, items2[0].translatedCount)
    }

    @Test
    fun `stale run cannot complete after chapter reset`() = runBlocking {
        val bookId = 1L
        val ids = seedBookAndChapters(bookId)
        val chapterId = ids[0]

        // 任务 A 开始（runId=1），随后用户重译：取消 A 恢复 PENDING，任务 B 开始（runId=2）
        assertTrue(db.chapterDao().startTranslationRun(bookId, chapterId, 1L, 1) > 0)
        assertTrue(db.chapterDao().cancelTranslationRun(bookId, chapterId, 1L, 0) > 0)
        assertTrue(db.chapterDao().startTranslationRun(bookId, chapterId, 2L, 1) > 0)

        // 任务 A 迟到完成：runId 不匹配，必须被拒绝
        assertFalse(db.chapterDao().completeTranslationRun(bookId, chapterId, 1L, "STALE", 2) > 0)
        val chapter = db.chapterDao().getChapterById(bookId, chapterId)!!
        assertEquals(1, chapter.status)
        assertEquals(2L, chapter.translationRunId)
        assertNull(chapter.translatedContent)

        // 任务 B 正常完成
        assertTrue(db.chapterDao().completeTranslationRun(bookId, chapterId, 2L, "EN B", 2) > 0)
        val done = db.chapterDao().getChapterById(bookId, chapterId)!!
        assertEquals(2, done.status)
        assertEquals("EN B", done.translatedContent)
        assertEquals(0L, done.translationRunId)
    }

    @Test
    fun `stale error and cancel are rejected`() = runBlocking {
        val bookId = 1L
        val ids = seedBookAndChapters(bookId)
        val chapterId = ids[0]

        assertTrue(db.chapterDao().startTranslationRun(bookId, chapterId, 1L, 1) > 0)
        assertTrue(db.chapterDao().startTranslationRun(bookId, chapterId, 2L, 1) > 0)

        // 旧任务失败/取消：不生效
        assertFalse(db.chapterDao().failTranslationRun(bookId, chapterId, 1L, -1, "old error") > 0)
        assertFalse(db.chapterDao().cancelTranslationRun(bookId, chapterId, 1L, 0) > 0)
        val chapter = db.chapterDao().getChapterById(bookId, chapterId)!!
        assertEquals(1, chapter.status)
        assertEquals(2L, chapter.translationRunId)
        assertNull(chapter.errorMessage)

        // 当前任务失败：生效并清除 runId
        assertTrue(db.chapterDao().failTranslationRun(bookId, chapterId, 2L, -1, "real error") > 0)
        val failed = db.chapterDao().getChapterById(bookId, chapterId)!!
        assertEquals(-1, failed.status)
        assertEquals("real error", failed.errorMessage)
        assertEquals(0L, failed.translationRunId)
    }

    @Test
    fun `translation run is scoped to book`() = runBlocking {
        val ids = seedBookAndChapters(1L, 2)
        val otherBookId = 2L
        db.bookDao().insert(BookEntity(id = otherBookId, title = "另一本书", totalChapters = 1))
        db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = otherBookId, title = "他人章", chapterIndex = 0, content = "x"))
        )
        val otherChapterId = db.chapterDao().getChaptersByBookList(otherBookId)[0].id

        // 用错误 bookId 操作：不生效
        assertFalse(db.chapterDao().startTranslationRun(otherBookId, ids[0], 1L, 1) > 0)
        assertFalse(db.chapterDao().completeTranslationRun(otherBookId, ids[0], 1L, "EN", 2) > 0)
        // 正确 bookId 生效
        assertTrue(db.chapterDao().startTranslationRun(otherBookId, otherChapterId, 1L, 1) > 0)
        assertTrue(db.chapterDao().completeTranslationRun(otherBookId, otherChapterId, 1L, "EN OTHER", 2) > 0)
    }

    @Test
    fun `progress update only applies to chapters of the book`() = runBlocking {
        val bookId = 1L
        val ids = seedBookAndChapters(bookId)
        val otherBookId = 2L
        db.bookDao().insert(BookEntity(id = otherBookId, title = "另一本书", totalChapters = 1))
        db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = otherBookId, title = "他人章", chapterIndex = 0, content = "x"))
        )
        val otherChapterId = db.chapterDao().getChaptersByBookList(otherBookId)[0].id

        // 跨书章节 ID：拒绝
        val rejected = db.bookDao().updateLastReadProgress(bookId, otherChapterId, 10, System.currentTimeMillis())
        assertEquals(0, rejected)
        // 本书章节：接受
        val accepted = db.bookDao().updateLastReadProgress(bookId, ids[1], 10, System.currentTimeMillis())
        assertEquals(1, accepted)
        val book = db.bookDao().getBookById(bookId)!!
        assertEquals(ids[1], book.lastReadChapterId)
        assertEquals(10, book.lastReadOffset)
    }

    @Test
    fun `shelf item carries derived translated count`() = runBlocking {
        val bookId = 1L
        val ids = seedBookAndChapters(bookId)
        db.chapterDao().startTranslationRun(bookId, ids[0], 1L, 1)
        db.chapterDao().completeTranslationRun(bookId, ids[0], 1L, "EN 1", 2)
        db.bookDao().updateLastReadProgress(bookId, ids[1], 5, System.currentTimeMillis())

        val items: List<BookWithProgress> = db.bookDao().getBooksWithProgress().first()
        assertEquals(1, items.size)
        assertEquals(1, items[0].translatedCount)
        assertEquals(ids[1], items[0].lastReadChapter?.id)
        assertEquals("第2章", items[0].lastReadChapter?.title)
    }

    @Test
    fun `source language correction clears all translations and resets display mode`() = runBlocking {
        val bookId = 1L
        val ids = seedBookAndChapters(bookId)
        // 两章已完成翻译（旧方向）
        db.chapterDao().startTranslationRun(bookId, ids[0], 1L, 1)
        db.chapterDao().completeTranslationRun(bookId, ids[0], 1L, "EN 1", 2)
        db.chapterDao().startTranslationRun(bookId, ids[1], 2L, 1)
        db.chapterDao().completeTranslationRun(bookId, ids[1], 2L, "EN 2", 2)
        db.bookDao().updateLanguageMode(bookId, "en")
        assertEquals("en", db.bookDao().getBookById(bookId)!!.languageMode)

        // 修正为英文原版：清空全部译文 + 重置显示模式为新原文语言
        db.chapterDao().resetAllChaptersForBook(bookId)
        db.bookDao().updateSourceLanguage(bookId, "en")
        db.bookDao().updateLanguageMode(bookId, "en")

        val book = db.bookDao().getBookById(bookId)!!
        assertEquals("en", book.sourceLanguage)
        assertEquals("en", book.languageMode)
        val chapters = db.chapterDao().getChaptersByBookList(bookId)
        assertTrue(chapters.all { it.translatedContent == null })
        assertTrue(chapters.all { it.status == 0 }) // PENDING
        assertTrue(chapters.all { it.errorMessage == null })
        assertTrue(chapters.all { it.translationRunId == 0L })
        // 书架已译章节数回落为 0
        assertEquals(0, db.bookDao().getBooksWithProgress().first()[0].translatedCount)
    }

    @Test
    fun `source language defaults to zh for existing books`() = runBlocking {
        val bookId = 1L
        seedBookAndChapters(bookId)
        val book = db.bookDao().getBookById(bookId)!!
        assertEquals("zh", book.sourceLanguage)
        assertEquals("zh", book.languageMode)
    }
}
