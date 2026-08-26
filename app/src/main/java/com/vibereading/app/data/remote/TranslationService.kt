package com.vibereading.app.data.remote

import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.parser.SourceLanguageDetector
import kotlinx.coroutines.flow.Flow

/**
 * 翻译服务抽象：流式翻译 + 连接测试 + 上下文截断。
 * 由 [LlmApiService] 实现，注入 TranslationCoordinator，便于替换实现与单测。
 */
interface TranslationService {
    /**
     * 流式翻译整章；事件流见 [TranslationEvent]。
     * [prevChapterTranslation] 是上一章译文（目标语言版本，供术语/风格衔接）；
     * [sourceLanguage] 是书籍原文语言（"zh"/"en"，ADR-003），决定翻译方向。
     */
    fun translateStream(
        settings: LlmSettings,
        chapterTitle: String,
        chapterContent: String,
        prevChapterTranslation: String? = null,
        sourceLanguage: String = SourceLanguageDetector.ZH
    ): Flow<TranslationEvent>

    /** 非流式连接测试，返回成功消息或失败原因。 */
    suspend fun testConnection(settings: LlmSettings): Result<String>

    /** 上下文中段截断，保留首尾内容。 */
    fun truncateMiddle(text: String, maxLen: Int): String
}
