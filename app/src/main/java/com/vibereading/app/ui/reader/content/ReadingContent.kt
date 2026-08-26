package com.vibereading.app.ui.reader.content

import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.domain.parser.ReadingContentParser

/**
 * 章节原文中的一个稳定段落范围。
 *
 * [sourceStartOffset] inclusive，[sourceEndOffset] exclusive，均以章节原文
 * [Chapter.content] 的 UTF-16 字符偏移计。合法双语段落使用原文范围；无法由
 * marker 绑定原文的译文保留在内容列表中，并使用 -1 表示「没有原文范围」，
 * 绝不把它伪造成章节开头 offset=0。
 *
 * [illustration] 非空表示本段是插图段（ADR-002 D3）：整段就是一个插图链接，
 * 双语两侧共用同一张图、无气泡、不参与翻译/选词。
 */
data class ReadingParagraph(
    val index: Int,
    val sourceText: String,
    val translatedText: String? = null,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
    val illustration: IllustrationLink? = null
) {
    val hasSourceOffset: Boolean
        get() = sourceStartOffset >= 0 && sourceEndOffset >= sourceStartOffset
}

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
            val originals = ReadingContentParser.parseOriginalParagraphs(chapter.content)
            val translation = chapter.translatedContent?.takeIf { it.isNotBlank() }
            // 插图段判定只看原文；即使模型对空洞编号回了散落译文也不当作文本显示
            fun illustrationOf(text: String): IllustrationLink? =
                IllustrationLink.parse(text.trim())
            val paragraphs = if (translation == null) {
                originals.mapIndexed { index, paragraph ->
                    ReadingParagraph(
                        index = index,
                        sourceText = paragraph.text,
                        sourceStartOffset = paragraph.startOffset,
                        sourceEndOffset = paragraph.endOffset,
                        illustration = illustrationOf(paragraph.text)
                    )
                }
            } else {
                // 双语内容只消费 parser 的配对结果；不要再次按换行拆分任一侧。
                ReadingContentParser.parseBilingualParagraphs(translation, chapter.content)
                    .mapIndexed { index, pair ->
                        val original = pair.original
                        val illustration = original?.text?.let { illustrationOf(it) }
                        ReadingParagraph(
                            index = index,
                            sourceText = original?.text.orEmpty(),
                            translatedText = if (illustration != null) null else {
                                pair.translatedText.takeIf { it.isNotBlank() }
                            },
                            // -1 是无原文范围的兼容哨兵，供旧的 Int offset API 使用。
                            sourceStartOffset = original?.startOffset ?: NO_SOURCE_OFFSET,
                            sourceEndOffset = original?.endOffset ?: NO_SOURCE_OFFSET,
                            illustration = illustration
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

        /** 旧调用方仍使用 Int offset；负值明确表示没有可定位的原文范围。 */
        const val NO_SOURCE_OFFSET = -1
    }
}

/**
 * 旧 UI 辅助 API 的兼容视图。实际分段由 [ReadingContentParser] 完成，避免 UI
 * 再维护一套 CRLF/空白行规则。
 */
data class SourceParagraph(
    val text: String,
    val start: Int,
    val end: Int
)

fun sourceParagraphs(content: String): List<SourceParagraph> =
    ReadingContentParser.parseParagraphs(content).map {
        SourceParagraph(it.text, it.startOffset, it.endOffset)
    }
