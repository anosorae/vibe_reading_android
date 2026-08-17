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
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Update
    suspend fun update(chapter: ChapterEntity)

    // ── 翻译任务状态机（数据库级 stale 防护） ──
    // 每个任务持有自增 runId：开始翻译时写入，完成/失败/取消必须带同一 runId 才生效。
    // 旧任务即使绕过内存代际判断，也会被 WHERE translationRunId = :runId 拒绝写入。

    /** 开始翻译：标记 IN_PROGRESS 并登记 runId（归零旧 errorMessage）；返回更新行数。 */
    @Query("""
        UPDATE chapters
        SET status = :status, translationRunId = :runId, errorMessage = NULL
        WHERE id = :chapterId AND bookId = :bookId
    """)
    suspend fun startTranslationRun(bookId: Long, chapterId: Long, runId: Long, status: Int): Int

    /** 完成翻译：仅当 runId 匹配才写译文；返回更新行数（0=任务已失效）。 */
    @Query("""
        UPDATE chapters
        SET translatedContent = :content, status = :status, errorMessage = NULL, translationRunId = 0
        WHERE id = :chapterId AND bookId = :bookId AND translationRunId = :runId
    """)
    suspend fun completeTranslationRun(bookId: Long, chapterId: Long, runId: Long, content: String, status: Int): Int

    /** 翻译失败：仅当 runId 匹配才写错误；返回更新行数（0=任务已失效）。 */
    @Query("""
        UPDATE chapters
        SET status = :status, errorMessage = :errorMessage, translationRunId = 0
        WHERE id = :chapterId AND bookId = :bookId AND translationRunId = :runId
    """)
    suspend fun failTranslationRun(bookId: Long, chapterId: Long, runId: Long, status: Int, errorMessage: String?): Int

    /** 取消翻译：仅当 runId 匹配才恢复 PENDING；返回更新行数（0=任务已失效）。 */
    @Query("""
        UPDATE chapters
        SET status = :status, errorMessage = NULL, translationRunId = 0
        WHERE id = :chapterId AND bookId = :bookId AND translationRunId = :runId
    """)
    suspend fun cancelTranslationRun(bookId: Long, chapterId: Long, runId: Long, status: Int): Int

    /** 一般状态写入（不涉及翻译 run，如章节过长等一次性判定）。 */
    @Query("UPDATE chapters SET status = :status, errorMessage = :errorMessage WHERE id = :chapterId AND bookId = :bookId")
    suspend fun updateStatusWithError(bookId: Long, chapterId: Long, status: Int, errorMessage: String?): Int

    /** 用户重置翻译：无条件清译文、错误和 runId，恢复 PENDING。 */
    @Query("UPDATE chapters SET status = 0, translatedContent = NULL, errorMessage = NULL, translationRunId = 0 WHERE id = :chapterId AND bookId = :bookId")
    suspend fun resetChapter(bookId: Long, chapterId: Long): Int
}
