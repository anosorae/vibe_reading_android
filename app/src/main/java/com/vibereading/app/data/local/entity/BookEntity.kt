package com.vibereading.app.data.local.entity

import androidx.room.*

@Entity(
    tableName = "books",
    indices = [Index("lastReadChapterId")]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String = "",
    val totalChapters: Int = 0,
    val translatedChapters: Int = 0,
    val lastReadChapterId: Long? = null,
    val lastReadPage: Int = 0,          // 分页模式：最后阅读的「章内页」索引（滚动模式恒 0）
    val lastReadAt: Long = 0,           // 最近阅读时间，书架「最近阅读」排序用
    val createdAt: Long = System.currentTimeMillis()
)
