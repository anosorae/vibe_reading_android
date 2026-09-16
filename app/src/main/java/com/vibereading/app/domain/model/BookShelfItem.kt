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
) {
    companion object {
        /**
         * 阅读进度比例：最后阅读章节的序号（1-based）占全书章节数之比，无最后阅读章节时为 0。
         * 唯一计算入口——书架与 Web 伴读共用同一公式。
         */
        fun progressOf(totalChapters: Int, lastReadChapterIndex: Int?): Float =
            if (totalChapters > 0 && lastReadChapterIndex != null) {
                (lastReadChapterIndex + 1).toFloat() / totalChapters
            } else 0f
    }
}
