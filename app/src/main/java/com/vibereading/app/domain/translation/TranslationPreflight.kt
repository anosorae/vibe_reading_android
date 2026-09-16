package com.vibereading.app.domain.translation

import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.domain.parser.ReadingContentParser

/**
 * 一次翻译调用的前置判定（纯函数，不碰数据库与 UI，可独立单测）。
 *
 * 此前这段判定嵌在 `TranslationCoordinator.translate()` 的 try 块里，与状态机、
 * 持久化、前台服务生命周期混在一起，测不到也读不清。
 */
enum class TranslationGate {
    /** 校验通过，可以真正调 API。 */
    TRANSLATE,

    /** 未配置 API Key。 */
    MISSING_API_KEY,

    /** 章节字符数超过配置上限（不调 API，直接标记过长）。 */
    TOO_LONG,

    /**
     * 无可用翻译文本（纯插图/空章节，ADR-002）：不调 API——空 prompt 会让模型
     * 输出「请提供文本」之类的无意义寒暄，直接落 DONE，读取侧空译文回退原文。
     */
    NOTHING_TO_TRANSLATE
}

object TranslationPreflight {

    /** 判定本次调用该怎么走。顺序即优先级：Key → 长度 → 可译文本。 */
    fun gate(chapter: Chapter, settings: LlmSettings): TranslationGate = when {
        settings.apiKey.isBlank() -> TranslationGate.MISSING_API_KEY
        chapter.content.length > settings.chapterMaxChars -> TranslationGate.TOO_LONG
        !hasTranslatableText(chapter.content) -> TranslationGate.NOTHING_TO_TRANSLATE
        else -> TranslationGate.TRANSLATE
    }

    /** 「章节过长」的落库与展示文案（唯一来源，避免两处措辞漂移）。 */
    fun tooLongMessage(chapter: Chapter): String = "章节过长 (${chapter.content.length} 字符)"

    /** 是否存在可翻译段落：非空白且不是插图链接。 */
    fun hasTranslatableText(content: String): Boolean =
        ReadingContentParser.splitParagraphs(content).any { p ->
            val trimmed = p.trim()
            trimmed.isNotEmpty() && IllustrationLink.parse(trimmed) == null
        }
}
