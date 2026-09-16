package com.vibereading.app

import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.domain.model.LlmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 可配置事件序列的假翻译服务，记录最后一次调用的入参。
 *
 * 独立成文件：此前它定义在 `TranslationCoordinatorTest` 内，却被
 * `ReaderViewModelInitializationTest` 直接复用——改一个测试文件会让另一个编译失败。
 */
class FakeTranslationService(
    var events: List<TranslationEvent> = emptyList()
) : TranslationService {

    var lastTitle: String? = null
    var lastContent: String? = null
    var lastSourceLanguage: String? = null
    var callCount = 0

    override fun translateStream(
        settings: LlmSettings,
        chapterTitle: String,
        chapterContent: String,
        sourceLanguage: String
    ): Flow<TranslationEvent> = flow {
        callCount++
        lastTitle = chapterTitle
        lastContent = chapterContent
        lastSourceLanguage = sourceLanguage
        emit(TranslationEvent.Started)
        events.forEach { emit(it) }
    }

    override suspend fun testConnection(settings: LlmSettings): Result<String> = Result.success("ok")
}
