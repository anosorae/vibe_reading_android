package com.vibereading.app.data.local.entity

import androidx.room.*
import com.vibereading.app.domain.model.Chapter

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val section: String? = null,
    val chapterIndex: Int,
    val content: String = "",
    val translatedContent: String? = null,
    val status: Int = Chapter.STATUS_PENDING,
    val errorMessage: String? = null,
    // 翻译任务代际标识：写入译文/错误/取消时按 runId 匹配，旧任务不能污染新任务
    val translationRunId: Long = 0
)
