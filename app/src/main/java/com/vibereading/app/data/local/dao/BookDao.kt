package com.vibereading.app.data.local.dao

import androidx.room.*
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

/** 书架条目：书 + 关联的最后阅读章节（用于展示进度/章节标题）。 */
data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "lastReadChapterId", entityColumn = "id")
    val lastReadChapter: ChapterEntity?
)

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    /** 书架关联查询，含 lastReadChapter 关系，供书架列表/网格展示进度。 */
    @Transaction
    @Query("SELECT * FROM books")
    fun getBooksWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookByIdFlow(id: Long): Flow<BookEntity?>

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE books SET lastReadChapterId = :chapterId, lastReadPage = :page, lastReadAt = :readAt WHERE id = :bookId")
    suspend fun updateLastReadProgress(bookId: Long, chapterId: Long, page: Int, readAt: Long)

    @Query("UPDATE books SET translatedChapters = :count WHERE id = :bookId")
    suspend fun updateTranslatedCount(bookId: Long, count: Int)
}
