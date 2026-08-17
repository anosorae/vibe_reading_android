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

    suspend fun updateStatus(bookId: Long, chapterId: Long, status: Int): Int =
        chapterDao.updateStatus(bookId, chapterId, status)

    suspend fun updateStatusWithError(bookId: Long, chapterId: Long, status: Int, errorMessage: String?): Int =
        chapterDao.updateStatusWithError(bookId, chapterId, status, errorMessage)

    suspend fun updateTranslation(bookId: Long, chapterId: Long, content: String, status: Int): Int =
        chapterDao.updateTranslation(bookId, chapterId, content, status)

    suspend fun resetChapter(bookId: Long, chapterId: Long): Int =
        chapterDao.resetChapter(bookId, chapterId)

    suspend fun getDoneCount(bookId: Long): Int =
        chapterDao.getDoneCount(bookId)

    fun hasInProgress(bookId: Long): Flow<Boolean> =
        chapterDao.hasInProgress(bookId, Chapter.STATUS_IN_PROGRESS)

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
