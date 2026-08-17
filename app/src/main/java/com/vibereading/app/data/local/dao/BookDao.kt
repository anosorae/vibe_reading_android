package com.vibereading.app.data.local.dao

import androidx.room.*
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书架条目：书 + 已翻译章节数（子查询派生）+ 关联的最后阅读章节。
 * translatedCount 由 chapters 表的 DONE 状态实时统计，避免手动维护缓存字段。
 */
data class BookWithProgress(
    @Embedded val book: BookEntity,
    val translatedCount: Int,
    @Relation(parentColumn = "lastReadChapterId", entityColumn = "id")
    val lastReadChapter: ChapterEntity?
)

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    /** 书架关联查询：含派生译文数与 lastReadChapter 关系，供书架列表/网格展示进度。 */
    @Transaction
    @Query("""
        SELECT books.*,
            (SELECT COUNT(*) FROM chapters WHERE chapters.bookId = books.id AND chapters.status = 2) AS translatedCount
        FROM books
    """)
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

    @Query("""
        UPDATE books
        SET lastReadChapterId = :chapterId, lastReadOffset = :offset, lastReadAt = :readAt
        WHERE id = :bookId
          AND EXISTS (
              SELECT 1 FROM chapters
              WHERE chapters.id = :chapterId AND chapters.bookId = :bookId
          )
    """)
    suspend fun updateLastReadProgress(bookId: Long, chapterId: Long, offset: Int, readAt: Long): Int
}
