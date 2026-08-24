package com.vibereading.app.domain.model

data class Book(
    val id: Long = 0,
    val title: String,
    val filePath: String = "",
    val totalChapters: Int = 0,
    val lastReadChapterId: Long? = null,
    val lastReadOffset: Int = 0,
    val lastReadAt: Long = 0,
    val languageMode: String = "zh",  // "zh" 或 "en"，按书绑定，默认中文
    val createdAt: Long = System.currentTimeMillis()
)
