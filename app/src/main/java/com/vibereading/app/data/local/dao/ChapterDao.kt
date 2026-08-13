package com.vibereading.app.data.local.dao

import androidx.room.*
import com.vibereading.app.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun getChaptersByBook(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapterById(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun getChapterByIdFlow(id: Long): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChaptersByBookList(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND status = 2 ORDER BY chapterIndex DESC LIMIT :limit")
    suspend fun getRecentDoneChapters(bookId: Long, limit: Int): List<ChapterEntity>

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query("UPDATE chapters SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("UPDATE chapters SET translatedContent = :content, status = :status WHERE id = :id")
    suspend fun updateTranslation(id: Long, content: String, status: Int)

    @Query("UPDATE chapters SET status = 0, translatedContent = NULL WHERE id = :id")
    suspend fun resetChapter(id: Long)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND status = 2")
    suspend fun getDoneCount(bookId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM chapters WHERE bookId = :bookId AND status = 1)")
    fun hasInProgress(bookId: Long): Flow<Boolean>
}
