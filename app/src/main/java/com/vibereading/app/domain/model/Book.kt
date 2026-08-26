package com.vibereading.app.domain.model

data class Book(
    val id: Long = 0,
    val title: String,
    val filePath: String = "",
    val totalChapters: Int = 0,
    val lastReadChapterId: Long? = null,
    val lastReadOffset: Int = 0,
    val lastReadAt: Long = 0,
    val languageMode: String = "zh",  // 显示模式："zh" 或 "en"，默认=原文语言，按书绑定（ADR-003）
    val sourceLanguage: String = "zh",  // 书籍原文语言："zh" 或 "en"，决定翻译方向与段落插槽，可按书修正
    val format: String = "txt",       // 书籍格式："txt" / "epub"（ADR-002）
    val coverPath: String? = null,    // 封面文件相对路径（filesDir 下）；空回退渐变占位
    val createdAt: Long = System.currentTimeMillis()
)
