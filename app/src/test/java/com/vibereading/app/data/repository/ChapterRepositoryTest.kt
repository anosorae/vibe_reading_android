package com.vibereading.app.data.repository

import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
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
 * ChapterRepository 单测：真实 Room 内存库验证包装层契约——
 * 域映射字段完整性、开书回退逻辑（Repository 层职责）、翻译终态的 Boolean 返回语义
 * 与 reset 的按书作用域。SQL 级 stale 防护已在 BookChapterDaoTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class ChapterRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChapterRepository

    @Before
    fun setUp() {
        db = newInMemoryDb()
        repo = ChapterRepository(db.chapterDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insertAll and read back preserves domain fields`() = runBlocking {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 0) // 只建书，满足外键
        val ids = repo.insertAll(
            listOf(
                Chapter(
                    bookId = 1L, title = "卷一 第一章", section = "卷一",
                    chapterIndex = 0, content = "正文内容"
                )
            )
        )
        val back = repo.getChapterById(1L, ids[0])!!
        assertEquals("卷一", back.section)
        assertEquals(0, back.chapterIndex)
        assertEquals("正文内容", back.content)
        assertEquals(Chapter.STATUS_PENDING, back.status)
    }

    @Test
    fun `getOpeningChapter falls back to first chapter`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, bookId = 1L, chapterCount = 3)

        assertEquals(chapterIds[0], repo.getOpeningChapter(1L, null)!!.id)
        assertEquals(chapterIds[1], repo.getOpeningChapter(1L, chapterIds[1])!!.id)
        // 保存的章节已不存在（重译/数据异常）时回退首章，不开空书
        assertEquals(chapterIds[0], repo.getOpeningChapter(1L, 999L)!!.id)
        assertNull(repo.getOpeningChapter(999L, null))
    }

    @Test
    fun `translation lifecycle honors runId in both directions`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, bookId = 1L, chapterCount = 1)
        val chapterId = chapterIds[0]

        assertFalse(repo.startTranslation(1L, 999L, 1L))
        assertTrue(repo.startTranslation(1L, chapterId, 1L))
        assertEquals(Chapter.STATUS_IN_PROGRESS, repo.getChapterById(1L, chapterId)!!.status)

        // 旧代际不能完成也不能取消，章节状态不被污染
        assertFalse(repo.completeTranslation(1L, chapterId, 0L, "旧译文"))
        assertFalse(repo.cancelTranslation(1L, chapterId, 0L))
        assertEquals(Chapter.STATUS_IN_PROGRESS, repo.getChapterById(1L, chapterId)!!.status)

        assertTrue(repo.completeTranslation(1L, chapterId, 1L, "[1] 译文"))
        val done = repo.getChapterById(1L, chapterId)!!
        assertEquals(Chapter.STATUS_DONE, done.status)
        assertEquals("[1] 译文", done.translatedContent)

        // 终态写入把 translationRunId 归零：同 runId 的迟到失败同样被拒
        assertFalse(repo.failTranslation(1L, chapterId, 1L, "迟到的失败"))
        assertEquals(Chapter.STATUS_DONE, repo.getChapterById(1L, chapterId)!!.status)
    }

    @Test
    fun `cancelTranslation restores pending without translation`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, bookId = 1L, chapterCount = 1)
        repo.startTranslation(1L, chapterIds[0], 5L)

        assertTrue(repo.cancelTranslation(1L, chapterIds[0], 5L))
        val chapter = repo.getChapterById(1L, chapterIds[0])!!
        assertEquals(Chapter.STATUS_PENDING, chapter.status)
        assertNull(chapter.translatedContent)
        assertNull(chapter.errorMessage)
    }

    @Test
    fun `markTooLong persists status and error message`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, bookId = 1L, chapterCount = 1)

        assertEquals(1, repo.markTooLong(1L, chapterIds[0], "章节超过单章字符上限"))
        val chapter = repo.getChapterById(1L, chapterIds[0])!!
        assertEquals(Chapter.STATUS_TOO_LONG, chapter.status)
        assertEquals("章节超过单章字符上限", chapter.errorMessage)
    }

    @Test
    fun `resetChapter and resetAllChapters are scoped to the book`() = runBlocking {
        val book1Chapters = seedBookAndChapters(db, bookId = 1L, chapterCount = 2)
        val book2Chapters = seedBookAndChapters(db, bookId = 2L, chapterCount = 1)
        suspend fun complete(bookId: Long, chapterId: Long, runId: Long) {
            assertTrue(repo.startTranslation(bookId, chapterId, runId))
            assertTrue(repo.completeTranslation(bookId, chapterId, runId, "译文"))
        }
        complete(1L, book1Chapters[0], 100L)
        complete(1L, book1Chapters[1], 101L)
        complete(2L, book2Chapters[0], 200L)

        // 单章重置只影响指定章节
        assertEquals(1, repo.resetChapter(1L, book1Chapters[0]))
        val resetOne = repo.getChapterById(1L, book1Chapters[0])!!
        assertEquals(Chapter.STATUS_PENDING, resetOne.status)
        assertNull(resetOne.translatedContent)
        assertEquals(Chapter.STATUS_DONE, repo.getChapterById(1L, book1Chapters[1])!!.status)

        // 整书重置清空本书全部译文（返回重置行数=本书章节数），另一本书不受影响（ADR-003 原文语言更正路径）
        assertEquals(2, repo.resetAllChapters(1L))
        assertTrue(
            repo.getChaptersByBookList(1L).all {
                it.status == Chapter.STATUS_PENDING && it.translatedContent == null
            }
        )
        assertEquals(Chapter.STATUS_DONE, repo.getChapterById(2L, book2Chapters[0])!!.status)
    }
}
