package com.vibereading.app.domain.model

/**
 * 书籍中的稳定阅读位置。
 *
 * [offset] 是对应章节原文的 UTF-16 字符偏移量，采用半开区间语义的同一坐标系，
 * 因此可以直接用于 [String.substring] 和解析器返回的段落范围。
 */
data class ReadingPosition(
    val chapterId: Long?,
    val offset: Int = 0
) {
    init {
        require(offset >= 0) { "阅读位置 offset 不能为负数" }
        require(chapterId != null || offset == 0) {
            "没有章节的阅读位置只能使用 offset=0"
        }
    }

    fun normalized(contentLength: Int): ReadingPosition =
        copy(offset = offset.coerceIn(0, contentLength.coerceAtLeast(0)))

    companion object {
        val Beginning = ReadingPosition(chapterId = null, offset = 0)
    }
}
