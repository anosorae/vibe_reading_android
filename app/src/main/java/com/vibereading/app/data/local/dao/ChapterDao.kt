package com.vibereading.app.data.local.dao

import androidx.room.*
import com.vibereading.app.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun getChaptersByBook(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :chapterId AND bookId = :bookId")
    suspend fun getChapterById(bookId: Long, chapterId: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :chapterId AND bookId = :bookId")
    fun getChapterByIdFlow(bookId: Long, chapterId: Long): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChaptersByBookList(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND status = :doneStatus ORDER BY chapterIndex DESC LIMIT :limit")
    suspend fun getRecentDoneChapters(bookId: Long, doneStatus: Int, limit: Int): List<ChapterEntity>

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query("UPDATE chapters SET status = :status, errorMessage = NULL WHERE id = :chapterId AND bookId = :bookId")
    suspend fun updateStatus(bookId: Long, chapterId: Long, status: Int): Int

    @Query("UPDATE chapters SET translatedContent = :content, status = :status, errorMessage = NULL WHERE id = :chapterId AND bookId = :bookId")
    suspend fun updateTranslation(bookId: Long, chapterId: Long, content: String, status: Int): Int

    @Query("UPDATE chapters SET status = :status, errorMessage = :errorMessage WHERE id = :chapterId AND bookId = :bookId")
    suspend fun updateStatusWithError(bookId: Long, chapterId: Long, status: Int, errorMessage: String?): Int

    @Query("UPDATE chapters SET status = 0, translatedContent = NULL, errorMessage = NULL WHERE id = :chapterId AND bookId = :bookId")
    suspend fun resetChapter(bookId: Long, chapterId: Long): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND status = 2")
    suspend fun getDoneCount(bookId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM chapters WHERE bookId = :bookId AND status = :inProgressStatus)")
    fun hasInProgress(bookId: Long, inProgressStatus: Int): Flow<Boolean>
}
