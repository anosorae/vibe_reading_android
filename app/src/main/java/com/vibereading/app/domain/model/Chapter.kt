package com.vibereading.app.domain.model

data class Chapter(
    val id: Long = 0,
    val bookId: Long,
    val title: String,
    val section: String? = null,
    val chapterIndex: Int,
    val content: String = "",
    val translatedContent: String? = null,
    val status: Int = 0
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_IN_PROGRESS = 1
        const val STATUS_DONE = 2
        const val STATUS_FAILED = -1
        const val STATUS_TOO_LONG = 3
    }
}
