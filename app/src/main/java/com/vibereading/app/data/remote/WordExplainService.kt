package com.vibereading.app.data.remote

import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.WordExplanation

/**
 * 单词解释服务抽象（非流式），由 [LlmApiService] 实现。
 *
 * 与 [TranslationService] 分开是有意的：解释是选词交互的独立用例，
 * 不应迫使只做翻译的调用方（与测试替身）也实现它。
 * UI/ViewModel 依赖本接口而非 [LlmApiService] 具体类。
 */
interface WordExplainService {
    /**
     * 解释 [word] 在 [paragraphContext] 语境中的含义。
     * 失败时返回带原因文本的 [Result.failure]，不抛异常（取消除外）。
     */
    suspend fun explainWord(
        settings: LlmSettings,
        word: String,
        paragraphContext: String
    ): Result<WordExplanation>
}
