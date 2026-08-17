package com.vibereading.app.data.repository

import com.vibereading.app.data.local.dao.ChapterDao
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.domain.model.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChapterRepository(private val chapterDao: ChapterDao) {

    fun getChaptersByBook(bookId: Long): Flow<List<Chapter>> =
        chapterDao.getChaptersByBook(bookId).map { list -> list.map { it.toDomain() } }

    suspend fun getChapterById(bookId: Long, chapterId: Long): Chapter? =
        chapterDao.getChapterById(bookId, chapterId)?.toDomain()

    fun getChapterByIdFlow(bookId: Long, chapterId: Long): Flow<Chapter?> =
        chapterDao.getChapterByIdFlow(bookId, chapterId).map { it?.toDomain() }

    suspend fun getChaptersByBookList(bookId: Long): List<Chapter> =
        chapterDao.getChaptersByBookList(bookId).map { it.toDomain() }

    suspend fun getRecentDoneChapters(bookId: Long, limit: Int): List<Chapter> =
        chapterDao.getRecentDoneChapters(bookId, Chapter.STATUS_DONE, limit).map { it.toDomain() }

    suspend fun insertAll(chapters: List<Chapter>) =
        chapterDao.insertAll(chapters.map { it.toEntity() })

    suspend fun update(chapter: Chapter) =
        chapterDao.update(chapter.toEntity())

    /** 开始翻译任务并登记 runId；返回是否成功开始。 */
    suspend fun startTranslation(bookId: Long, chapterId: Long, runId: Long): Boolean =
        chapterDao.startTranslationRun(bookId, chapterId, runId, Chapter.STATUS_IN_PROGRESS) > 0

    /** 完成翻译（runId 匹配才生效）；返回 false 表示任务已失效。 */
    suspend fun completeTranslation(bookId: Long, chapterId: Long, runId: Long, content: String): Boolean =
        chapterDao.completeTranslationRun(bookId, chapterId, runId, content, Chapter.STATUS_DONE) > 0

    /** 翻译失败（runId 匹配才生效）；返回 false 表示任务已失效。 */
    suspend fun failTranslation(bookId: Long, chapterId: Long, runId: Long, errorMessage: String?): Boolean =
        chapterDao.failTranslationRun(bookId, chapterId, runId, Chapter.STATUS_FAILED, errorMessage) > 0

    /** 取消翻译并恢复 PENDING（runId 匹配才生效）；返回 false 表示任务已失效。 */
    suspend fun cancelTranslation(bookId: Long, chapterId: Long, runId: Long): Boolean =
        chapterDao.cancelTranslationRun(bookId, chapterId, runId, Chapter.STATUS_PENDING) > 0

    /** 标记章节过长（一次性判定，不涉及 run）。 */
    suspend fun markTooLong(bookId: Long, chapterId: Long, errorMessage: String): Int =
        chapterDao.updateStatusWithError(bookId, chapterId, Chapter.STATUS_TOO_LONG, errorMessage)

    /** 用户重置翻译：无条件清译文、错误和 runId。 */
    suspend fun resetChapter(bookId: Long, chapterId: Long): Int =
        chapterDao.resetChapter(bookId, chapterId)

    private fun ChapterEntity.toDomain() = Chapter(
        id = id, bookId = bookId, title = title, section = section,
        chapterIndex = chapterIndex, content = content,
        translatedContent = translatedContent, status = status,
        errorMessage = errorMessage
    )

    private fun Chapter.toEntity() = ChapterEntity(
        id = id, bookId = bookId, title = title, section = section,
        chapterIndex = chapterIndex, content = content,
        translatedContent = translatedContent, status = status,
        errorMessage = errorMessage
    )
}
