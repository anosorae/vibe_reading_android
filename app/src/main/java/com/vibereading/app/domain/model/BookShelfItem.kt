package com.vibereading.app.domain.model

/**
 * 书架行/卡片数据：书 + 阅读进度展示所需信息。
 * 由 BookDao 关联查询组装（对齐 Legado BooksAdapter 的进度/最新章节展示）。
 */
data class BookShelfItem(
    val book: Book,
    val translatedCount: Int = 0,   // 已翻译章节数（由 chapters 表派生，不落库）
    val lastReadChapterTitle: String? = null,
    val progress: Float = 0f // 0..1，基于 totalChapters
)
