package com.vibereading.app.ui.reader.content

import com.vibereading.app.domain.model.Chapter

/**
 * 章节原文中的一个稳定段落范围。
 *
 * [sourceStartOffset] inclusive，[sourceEndOffset] exclusive，均以章节原文
 * [Chapter.content] 的 UTF-16 字符偏移计。范围保留原文中的真实位置，供滚动
 * 定位、分页恢复和将来的选区交互使用。
 */
data class ReadingParagraph(
    val index: Int,
    val sourceText: String,
    val translatedText: String? = null,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int
)

/** 统一的章节阅读内容模型；分页和滚动均从这里构造段落数据。 */
data class ReadingContent(
    val chapterId: Long,
    val section: String?,
    val title: String,
    val status: Int,
    val errorMessage: String?,
    val paragraphs: List<ReadingParagraph>
) {
    companion object {
        fun fromChapter(chapter: Chapter): ReadingContent {
            val source = sourceParagraphs(chapter.content)
            val translated = chapter.translatedContent
                ?.takeIf { it.isNotBlank() }
                ?.let { translatedParagraphs(it, source) }
                ?: emptyList()

            val paragraphs = if (translated.isEmpty()) {
                source.mapIndexed { index, paragraph ->
                    ReadingParagraph(
                        index = index,
                        sourceText = paragraph.text,
                        sourceStartOffset = paragraph.start,
                        sourceEndOffset = paragraph.end
                    )
                }
            } else {
                translated.mapIndexed { index, pair ->
                    val sourceParagraph = source.getOrNull(pair.sourceIndex)
                    ReadingParagraph(
                        index = index,
                        sourceText = sourceParagraph?.text.orEmpty(),
                        translatedText = pair.text.takeIf { it.isNotBlank() },
                        sourceStartOffset = sourceParagraph?.start ?: 0,
                        sourceEndOffset = sourceParagraph?.end ?: 0
                    )
                }
            }

            return ReadingContent(
                chapterId = chapter.id,
                section = chapter.section,
                title = chapter.title,
                status = chapter.status,
                errorMessage = chapter.errorMessage,
                paragraphs = paragraphs
            )
        }
    }
}

/** 原文段落的内部范围表示。 */
data class SourceParagraph(
    val text: String,
    val start: Int,
    val end: Int
)

private data class TranslatedParagraph(val text: String, val sourceIndex: Int)

/**
 * 与旧 splitParagraphs 保持完全相同的分段规则，同时保留原文范围。
 * 优先按空行分段；没有多个空行段时按单行分段。
 */
fun sourceParagraphs(content: String): List<SourceParagraph> {
    val blankSegments = ArrayList<SourceParagraph>()
    var segmentStart = 0
    content.split("\n\n").forEach { segment ->
        trimmedRange(content, segmentStart, segmentStart + segment.length)?.let(blankSegments::add)
        segmentStart += segment.length + 2
    }
    if (blankSegments.size > 1) return blankSegments

    val result = ArrayList<SourceParagraph>()
    var lineStart = 0
    while (lineStart <= content.length) {
        val lineEnd = content.indexOf('\n', lineStart).let { if (it < 0) content.length else it }
        trimmedRange(content, lineStart, lineEnd)?.let(result::add)
        if (lineEnd == content.length) break
        lineStart = lineEnd + 1
    }
    return result
}

private fun trimmedRange(content: String, rawStart: Int, rawEnd: Int): SourceParagraph? {
    var start = rawStart
    var end = rawEnd
    while (start < end && content[start].isWhitespace()) start++
    while (end > start && content[end - 1].isWhitespace()) end--
    return if (start < end) SourceParagraph(content.substring(start, end), start, end) else null
}

private fun translatedParagraphs(
    translatedContent: String,
    source: List<SourceParagraph>
): List<TranslatedParagraph> {
    val markerRegex = Regex("""\[(\d+)]\s*""")
    val parts = translatedContent.split(markerRegex).filter { it.isNotBlank() }
    val result = ArrayList<TranslatedParagraph>()
    var i = 0
    var fallbackIndex = 0
    while (i < parts.size) {
        val part = parts[i].trim()
        if (part.all(Char::isDigit) && part.isNotEmpty()) {
            val number = part.toIntOrNull()
            val text = parts.getOrNull(i + 1)?.trim()
            if (number != null && !text.isNullOrBlank()) {
                result += TranslatedParagraph(text, number - 1)
            }
            i += 2
        } else {
            if (part.isNotEmpty()) {
                result += TranslatedParagraph(part, fallbackIndex)
                fallbackIndex++
            }
            i++
        }
    }

    if (result.isEmpty()) {
        splitRawParagraphs(translatedContent).forEachIndexed { index, text ->
            result += TranslatedParagraph(text, index)
        }
    }

    // 兼容旧 prompt 将整章返回为一个未标记段落的情况。
    if (result.size == 1 && source.size > 1) {
        result.clear()
        splitRawParagraphs(translatedContent).take(source.size).forEachIndexed { index, text ->
            result += TranslatedParagraph(text, index)
        }
    }
    return result.filter { it.sourceIndex in source.indices }
}

private fun splitRawParagraphs(content: String): List<String> {
    val byBlank = content.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    return if (byBlank.size > 1) byBlank else content.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
