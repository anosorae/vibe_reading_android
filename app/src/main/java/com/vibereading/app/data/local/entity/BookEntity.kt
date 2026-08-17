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
    val lastReadOffset: Int = 0,        // 原文字符偏移量；旧页码不转换
    val lastReadAt: Long = 0,           // 最近阅读时间，书架「最近阅读」排序用
    val createdAt: Long = System.currentTimeMillis()
)
