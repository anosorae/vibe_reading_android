package com.vibereading.app.web

import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.content.ReadingContent
import com.vibereading.app.ui.reader.content.ReadingParagraph

/**
 * Web 伴读服务的 JSON DTO（Gson 序列化，ADR-005）。
 *
 * 字段名即 JSON 键，全部小驼峰；Web 前端按这些键消费。
 * offset 语义与 App 一致：原文字符串的 UTF-16 半开区间坐标。
 */

/** 书架条目（含进度摘要与封面 URL）。 */
data class CompanionBook(
    val id: Long,
    val title: String,
    val totalChapters: Int,
    val translatedCount: Int,
    val lastReadChapterId: Long?,
    val lastReadOffset: Int,
    val lastReadAt: Long,
    val lastReadChapterTitle: String?,
    val languageMode: String,
    val sourceLanguage: String,
    val format: String,
    val hasCover: Boolean,
    val progress: Float
) {
    companion object {
        fun from(item: BookShelfItem) = CompanionBook(
            id = item.book.id,
            title = item.book.title,
            totalChapters = item.book.totalChapters,
            translatedCount = item.translatedCount,
            lastReadChapterId = item.book.lastReadChapterId,
            lastReadOffset = item.book.lastReadOffset,
            lastReadAt = item.book.lastReadAt,
            lastReadChapterTitle = item.lastReadChapterTitle,
            languageMode = item.book.languageMode,
            sourceLanguage = item.book.sourceLanguage,
            format = item.book.format,
            hasCover = item.book.coverPath != null,
            progress = item.progress
        )
    }
}

/** 章节列表项（轻量，用于目录与状态轮询）。 */
data class CompanionChapter(
    val id: Long,
    val title: String,
    val section: String?,
    val chapterIndex: Int,
    val status: Int,
    val errorMessage: String?
) {
    companion object {
        fun from(chapter: Chapter) = CompanionChapter(
            id = chapter.id, title = chapter.title, section = chapter.section,
            chapterIndex = chapter.chapterIndex, status = chapter.status,
            errorMessage = chapter.errorMessage
        )
    }
}

/** 章节列表响应：书信息 + 全部章节。 */
data class CompanionChapterList(
    val book: CompanionBook,
    val chapters: List<CompanionChapter>
)

/**
 * 正文段落：src=原文侧文本，trans=译文侧文本（null=未翻译）。
 * main/expand（正文主侧/展开侧）由前端按 languageMode + sourceLanguage 派生，
 * 与 App 的 `ReadingParagraph.chineseSide/englishSide` 插槽语义一致。
 */
data class CompanionParagraph(
    val type: String,       // "p" 文本段 / "img" 插图段
    val src: String?,       // 原文侧（插图段为 null）
    val trans: String?,     // 译文侧
    val start: Int,         // 原文范围起点（半开区间；无原文范围 = -1）
    val end: Int,           // 原文范围终点（不含）
    val imgUrl: String?,    // 插图段：HTTP 路径
    val imgW: Int?,         // 插图段：像素宽（导入期解码）
    val imgH: Int?
) {
    companion object {
        fun from(paragraph: ReadingParagraph): CompanionParagraph {
            val ill = paragraph.illustration
            return CompanionParagraph(
                type = if (ill != null) "img" else "p",
                src = if (ill != null) null else paragraph.sourceText,
                trans = paragraph.translatedText,
                start = paragraph.sourceStartOffset,
                end = paragraph.sourceEndOffset,
                imgUrl = ill?.let { "/img/${it.path}" },
                imgW = ill?.widthPx,
                imgH = ill?.heightPx
            )
        }
    }
}

/** 章节正文响应。 */
data class CompanionChapterContent(
    val chapterId: Long,
    val title: String,
    val section: String?,
    val status: Int,
    val errorMessage: String?,
    val paragraphs: List<CompanionParagraph>
) {
    companion object {
        fun from(content: ReadingContent) = CompanionChapterContent(
            chapterId = content.chapterId,
            title = content.title,
            section = content.section,
            status = content.status,
            errorMessage = content.errorMessage,
            paragraphs = content.paragraphs.map { CompanionParagraph.from(it) }
        )
    }
}

/** 统一响应封装（对齐 legado ReturnData 的极简版）。 */
data class CompanionResult(val ok: Boolean, val error: String? = null, val data: Any? = null) {
    companion object {
        fun success(data: Any?) = CompanionResult(ok = true, data = data)
        fun failure(error: String) = CompanionResult(ok = false, error = error)
    }
}

/**
 * 把客户端上报的阅读 offset 规范化到章节原文长度内（半开区间）：
 * 负值取 0，超过章节长度按内容长度收敛。与 App 的 offset 规范化语义一致。
 */
fun normalizeCompanionOffset(offset: Int, contentLength: Int): Int =
    offset.coerceIn(0, contentLength.coerceAtLeast(0))
