package com.vibereading.app.data.repository

import com.vibereading.app.data.local.dao.BookDao
import com.vibereading.app.data.local.dao.BookWithProgress
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookRepository(private val bookDao: BookDao) {

    fun getAllBooks(): Flow<List<Book>> =
        bookDao.getAllBooks().map { list -> list.map { it.toDomain() } }

    /** 书架条目流（含最后阅读章节标题 + 进度比例）。排序在 ViewModel 内做。 */
    fun getShelfItems(): Flow<List<BookShelfItem>> =
        bookDao.getBooksWithProgress().map { list ->
            list.map { it.toShelfItem() }
        }

    fun getBookById(id: Long): Flow<Book?> =
        bookDao.getBookByIdFlow(id).map { it?.toDomain() }

    suspend fun getBookByIdOnce(id: Long): Book? =
        bookDao.getBookById(id)?.toDomain()

    suspend fun insert(book: Book): Long =
        bookDao.insert(book.toEntity())

    suspend fun update(book: Book) =
        bookDao.update(book.toEntity())

    suspend fun delete(id: Long) =
        bookDao.deleteById(id)

    /** 保存阅读进度：记录章节及原文字符 offset。 */
    suspend fun updateLastReadProgress(bookId: Long, chapterId: Long, offset: Int): Boolean =
        bookDao.updateLastReadProgress(bookId, chapterId, offset.coerceAtLeast(0), System.currentTimeMillis()) > 0

    suspend fun updateTranslatedCount(bookId: Long, count: Int) =
        bookDao.updateTranslatedCount(bookId, count)

    private fun BookEntity.toDomain() = Book(
        id = id, title = title, filePath = filePath,
        totalChapters = totalChapters, translatedChapters = translatedChapters,
        lastReadChapterId = lastReadChapterId, lastReadOffset = lastReadOffset,
        lastReadAt = lastReadAt, createdAt = createdAt
    )

    private fun Book.toEntity() = BookEntity(
        id = id, title = title, filePath = filePath,
        totalChapters = totalChapters, translatedChapters = translatedChapters,
        lastReadChapterId = lastReadChapterId, lastReadOffset = lastReadOffset,
        lastReadAt = lastReadAt, createdAt = createdAt
    )

    private fun BookWithProgress.toShelfItem() = BookShelfItem(
        book = book.toDomain(),
        lastReadChapterTitle = lastReadChapter?.title,
        progress = if (book.totalChapters > 0 && lastReadChapter != null) {
            (lastReadChapter.chapterIndex + 1).toFloat() / book.totalChapters
        } else 0f
    )
}
