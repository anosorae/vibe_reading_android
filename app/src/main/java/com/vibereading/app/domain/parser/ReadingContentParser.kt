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

    private val marker = Regex("""\[(\d+)]""")

    /**
     * 按阅读器约定拆分段落，并保留正文在原始章节中的准确范围。
     *
     * 换行统一按 LF、CRLF、CR 识别。存在空白行时，连续的非空行属于同一段；
     * 没有空白行时，每个非空行是一段。段首尾的空白（包括换行）不属于正文，
     * 段内的换行和空格则原样保留。
     */
    fun parseParagraphs(content: String): List<ReadingParagraph> {
        if (content.isEmpty()) return emptyList()

        val lines = linesWithRanges(content)
        val hasBlankLine = lines.any { (start, end) -> isBlank(content, start, end) }
        val rawRanges = if (hasBlankLine) {
            groupedByBlankLines(content, lines)
        } else {
            lines.map { (start, end) -> start to end }
        }

        return rawRanges.mapNotNull { (start, end) ->
            trimRange(content, start, end)
        }.map { (start, end) ->
            ReadingParagraph(content.substring(start, end), start, end)
        }
    }

    /** 原文段落的明确命名入口。 */
    fun parseOriginalParagraphs(content: String): List<ReadingParagraph> = parseParagraphs(content)

    /** 兼容原 splitParagraphs API 的纯文本视图。 */
    fun splitParagraphs(content: String): List<String> = parseParagraphs(content).map { it.text }

    /**
     * 解析带 [N] 标记的译文，并把每个标记绑定到原文段落及其 offset。
     *
     * 合法标记按原文顺序返回；缺失的合法标记也返回一个空译文段落，以保留原文。
     * 非法标记不会绑定到其他原文，也不会被丢弃，而是作为 original=null 的译文
     * 返回给调用方。没有任何标记时，两边按共同的段落规则顺序配对。
     */
    fun parseBilingualParagraphs(
        translatedContent: String,
        originalContent: String
    ): List<BilingualParagraph> {
        val originals = parseParagraphs(originalContent)
        val matches = marker.findAll(translatedContent).toList()

        if (matches.isEmpty()) {
            return pairUnmarked(translatedContent, originals)
        }

        val marked = matches.mapIndexed { index, match ->
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: translatedContent.length
            MarkedTranslation(
                marker = match.groupValues[1].toIntOrNull(),
                text = translatedContent.substring(textStart, textEnd).trim()
            )
        }.toMutableList()
        // marker 之前的正文也必须保留，不能因为模型漏写/错写标记而静默丢失。
        val prefix = translatedContent.substring(0, matches.first().range.first).trim()
        if (prefix.isNotEmpty()) marked.add(0, MarkedTranslation(marker = null, text = prefix))
        val legal = marked.filter { it.marker != null && it.marker in 1..originals.size }

        // 如果全部标记都越界，仍返回每段译文；不能把它静默当成原文的第 1 段。
        if (legal.isEmpty()) {
            return marked.filter { it.text.isNotEmpty() }.map {
                BilingualParagraph(it.marker, it.text, original = null)
            }
        }

        val result = ArrayList<BilingualParagraph>(originals.size + marked.size)
        val bySourceIndex = legal.groupBy { it.marker!! - 1 }
        originals.forEachIndexed { sourceIndex, original ->
            val translations = bySourceIndex[sourceIndex].orEmpty()
            if (translations.isEmpty()) {
                // 缺失合法 marker：保留原文，交给 ReadingContent 渲染为未翻译段落。
                result += BilingualParagraph(
                    marker = sourceIndex + 1,
                    translatedText = "",
                    original = original
                )
            } else {
                translations.forEach { translation ->
                    result += BilingualParagraph(
                        marker = translation.marker,
                        translatedText = translation.text,
                        original = original
                    )
                }
            }
        }

        // 标记前的散落文本、越界标记和无法绑定的重复内容都保留，但不伪造原文范围。
        marked.filter { it.text.isNotEmpty() && (it.marker == null || it.marker !in 1..originals.size) }.forEach {
            result += BilingualParagraph(it.marker, it.text, original = null)
        }
        return result
    }

    /** 兼容旧 UI 的 Pair 结构，实际解析仍由本解析器完成。 */
    fun parseBilingualPairs(
        translatedContent: String,
        originalContent: String
    ): List<Pair<String, String>> = parseBilingualParagraphs(translatedContent, originalContent)
        .map { it.translatedText to it.chineseText }

    private fun pairUnmarked(
        translatedContent: String,
        originals: List<ReadingParagraph>
    ): List<BilingualParagraph> {
        val translations = parseParagraphs(translatedContent).map { it.text }
        val count = maxOf(originals.size, translations.size)
        return (0 until count).map { index ->
            BilingualParagraph(
                marker = null,
                translatedText = translations.getOrNull(index).orEmpty(),
                original = originals.getOrNull(index)
            )
        }
    }

    private data class MarkedTranslation(val marker: Int?, val text: String)

    private fun linesWithRanges(content: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var lineStart = 0
        var index = 0
        while (index < content.length) {
            val lineEnd = when (content[index]) {
                '\n', '\r' -> index
                else -> {
                    index++
                    continue
                }
            }
            result += lineStart to lineEnd
            if (content[index] == '\r' && index + 1 < content.length && content[index + 1] == '\n') {
                index++
            }
            index++
            lineStart = index
        }
        // 保留末尾空行，便于统一判断段落边界；后续 trimRange 会将其移除。
        result += lineStart to content.length
        return result
    }

    private fun groupedByBlankLines(
        content: String,
        lines: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var groupStart: Int? = null
        var groupEnd = 0
        lines.forEach { (start, end) ->
            if (isBlank(content, start, end)) {
                groupStart?.let { result += it to groupEnd }
                groupStart = null
            } else {
                if (groupStart == null) groupStart = start
                groupEnd = end
            }
        }
        groupStart?.let { result += it to groupEnd }
        return result
    }

    private fun isBlank(content: String, start: Int, end: Int): Boolean =
        start == end || content.substring(start, end).all(Char::isWhitespace)

    private fun trimRange(content: String, start: Int, end: Int): Pair<Int, Int>? {
        var trimmedStart = start
        var trimmedEnd = end
        while (trimmedStart < trimmedEnd && content[trimmedStart].isWhitespace()) trimmedStart++
        while (trimmedEnd > trimmedStart && content[trimmedEnd - 1].isWhitespace()) trimmedEnd--
        return if (trimmedStart < trimmedEnd) trimmedStart to trimmedEnd else null
    }
}
