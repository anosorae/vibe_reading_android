package com.vibereading.app.data.repository

import com.vibereading.app.data.local.dao.ChapterDao
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.domain.model.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChapterRepository(private val chapterDao: ChapterDao) {

    fun getChaptersByBook(bookId: Long): Flow<List<Chapter>> =
        chapterDao.getChaptersByBook(bookId).map { list -> list.map { it.toDomain() } }

    suspend fun getChapterById(id: Long): Chapter? =
        chapterDao.getChapterById(id)?.toDomain()

    fun getChapterByIdFlow(id: Long): Flow<Chapter?> =
        chapterDao.getChapterByIdFlow(id).map { it?.toDomain() }

    suspend fun getChaptersByBookList(bookId: Long): List<Chapter> =
        chapterDao.getChaptersByBookList(bookId).map { it.toDomain() }

    suspend fun getRecentDoneChapters(bookId: Long, limit: Int): List<Chapter> =
        chapterDao.getRecentDoneChapters(bookId, limit).map { it.toDomain() }

    suspend fun insertAll(chapters: List<Chapter>) =
        chapterDao.insertAll(chapters.map { it.toEntity() })

    suspend fun update(chapter: Chapter) =
        chapterDao.update(chapter.toEntity())

    suspend fun updateStatus(id: Long, status: Int) =
        chapterDao.updateStatus(id, status)

    suspend fun updateTranslation(id: Long, content: String, status: Int) =
        chapterDao.updateTranslation(id, content, status)

    suspend fun resetChapter(id: Long) =
        chapterDao.resetChapter(id)

    suspend fun getDoneCount(bookId: Long): Int =
        chapterDao.getDoneCount(bookId)

    fun hasInProgress(bookId: Long): Flow<Boolean> =
        chapterDao.hasInProgress(bookId)

    private fun ChapterEntity.toDomain() = Chapter(
        id = id, bookId = bookId, title = title, section = section,
        chapterIndex = chapterIndex, content = content,
        translatedContent = translatedContent, status = status
    )

    private fun Chapter.toEntity() = ChapterEntity(
        id = id, bookId = bookId, title = title, section = section,
        chapterIndex = chapterIndex, content = content,
        translatedContent = translatedContent, status = status
    )
}
