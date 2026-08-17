package com.vibereading.app.domain.parser

/**
 * 一段原文在章节字符串中的精确范围。
 *
 * [startOffset] 包含，[endOffset] 不包含，偏移量使用 Kotlin String 的 UTF-16
 * code-unit 坐标，与 substring/indexOf 保持一致。范围只覆盖段落正文，不包含
 * 段落之间的空白分隔符。
 */
data class ReadingParagraph(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
) {
    init {
        require(startOffset >= 0) { "段落 startOffset 不能为负数" }
        require(endOffset >= startOffset) { "段落 endOffset 不能小于 startOffset" }
        require(endOffset - startOffset == text.length) {
            "段落范围必须与 text 的 UTF-16 长度一致"
        }
    }

    val range: IntRange
        get() = startOffset until endOffset

    /** 与原文的精确子串一致性，便于调用方在需要时校验输入。 */
    fun isFrom(content: String): Boolean =
        endOffset <= content.length && content.substring(startOffset, endOffset) == text
}

typealias OriginalParagraph = ReadingParagraph

/** 翻译段落与原文段落的稳定对应关系。 */
data class BilingualParagraph(
    val marker: Int?,
    val translatedText: String,
    val original: ReadingParagraph?
) {
    /** 与 UI 旧命名保持直观兼容。 */
    val englishText: String
        get() = translatedText

    val chineseText: String
        get() = original?.text.orEmpty()

    val originalParagraph: ReadingParagraph?
        get() = original
}

/**
 * 章节正文解析器。它是纯 Kotlin，不依赖 Android 或 Compose，可作为翻译提示词、
 * 阅读定位和双语展示的共同段落边界来源。
 */
object ReadingContentParser {

    private val blankLineSeparator = Regex("(?:\\r?\\n)[^\\S\\r\\n]*(?:\\r?\\n[^\\S\\r\\n]*)+")
    private val lineSeparator = Regex("\\r\\n|\\n|\\r")
    private val marker = Regex("\\[(\\d+)]\\s*")

    /**
     * 按旧阅读器约定拆分段落：有空行时按空行，否则每个非空行一段。
     * 返回值保留 trim 后正文的准确字符范围，不会把换行或空格误算进正文。
     */
    fun parseParagraphs(content: String): List<ReadingParagraph> {
        if (content.isEmpty()) return emptyList()

        val blankSeparated = blankLineSeparator.splitWithRanges(content)
        val candidates = if (blankSeparated.size > 1) {
            blankSeparated
        } else {
            lineSeparator.splitWithRanges(content)
        }
        return candidates.mapNotNull { (start, end) -> trimRange(content, start, end) }
            .map { (start, end) -> ReadingParagraph(content.substring(start, end), start, end) }
    }

    /** 原文段落的明确命名入口。 */
    fun parseOriginalParagraphs(content: String): List<ReadingParagraph> = parseParagraphs(content)

    /** 兼容原 splitParagraphs API 的纯文本视图。 */
    fun splitParagraphs(content: String): List<String> =
        parseParagraphs(content).map { it.text }

    /**
     * 解析带 [N] 标记的译文，并把每个标记绑定到原文段落及其 offset。
     * 没有标记时按兼容的段落顺序配对；无效/越界标记仍保留译文，但 original 为 null，
     * 避免静默把错误标记绑定到另一段原文。
     */
    fun parseBilingualParagraphs(
        translatedContent: String,
        originalContent: String
    ): List<BilingualParagraph> {
        val originals = parseParagraphs(originalContent)
        val matches = marker.findAll(translatedContent).toList()
        if (matches.isEmpty()) {
            return splitParagraphs(translatedContent).mapIndexed { index, text ->
                BilingualParagraph(
                    marker = null,
                    translatedText = text,
                    original = originals.getOrNull(index)
                )
            }
        }

        return matches.mapNotNullIndexed { index, match ->
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: translatedContent.length
            val text = translatedContent.substring(textStart, textEnd).trim()
            if (text.isEmpty()) return@mapNotNullIndexed null
            val number = match.groupValues[1].toIntOrNull()
            BilingualParagraph(
                marker = number,
                translatedText = text,
                original = number?.takeIf { it in 1..originals.size }?.let { originals[it - 1] }
            )
        }
    }

    /** 兼容旧 UI 的 Pair 结构，保留原文顺序和 [N] 对齐规则。 */
    fun parseBilingualPairs(
        translatedContent: String,
        originalContent: String
    ): List<Pair<String, String>> = parseBilingualParagraphs(translatedContent, originalContent)
        .map { it.translatedText to it.chineseText }

    private fun trimRange(content: String, start: Int, end: Int): Pair<Int, Int>? {
        var trimmedStart = start
        var trimmedEnd = end
        while (trimmedStart < trimmedEnd && content[trimmedStart].isWhitespace()) trimmedStart++
        while (trimmedEnd > trimmedStart && content[trimmedEnd - 1].isWhitespace()) trimmedEnd--
        return if (trimmedStart < trimmedEnd) trimmedStart to trimmedEnd else null
    }

    private fun Regex.splitWithRanges(content: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        findAll(content).forEach { match ->
            result += start to match.range.first
            start = match.range.last + 1
        }
        result += start to content.length
        return result
    }

    private inline fun <T, R> Iterable<T>.mapNotNullIndexed(transform: (Int, T) -> R?): List<R> {
        val result = ArrayList<R>()
        forEachIndexed { index, item -> transform(index, item)?.let(result::add) }
        return result
    }
}
