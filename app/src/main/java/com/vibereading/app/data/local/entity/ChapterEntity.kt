package com.vibereading.app.data.local.entity

import androidx.room.*

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
    val status: Int = 0  // 0=pending, 1=in_progress, 2=done, -1=failed, 3=too_long
)
