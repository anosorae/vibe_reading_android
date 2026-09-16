package com.vibereading.app.ui.reader

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.inMemoryPreferenceStore
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelTranslationConcurrencyTest {
    private lateinit var app: VibeReadingApp
    private lateinit var db: AppDatabase
    private lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        db = newInMemoryDb()
        appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `switching chapters projects each running task without cancelling either`() = runTest {
        val chapterIds = seedBookAndChapters(
            db,
            bookId = 1L,
            chapterCount = 2,
            chapterContent = { "正文-${it + 1}" }
        )
        val settingsRepo = SettingsRepository(app, inMemoryPreferenceStore())
        val profileRepo = LlmProfileRepository(db.llmProfileDao(), settingsRepo)
        profileRepo.addProfile(LlmProfile(name = "测试", apiKey = "key"))
        val profileId = profileRepo.profiles.firstValue { it.isNotEmpty() }.first().id
        profileRepo.setActive(profileId)
        val chapterRepo = ChapterRepository(db.chapterDao())
        val service = ControlledService()
        val coordinator = TranslationCoordinator(
            chapterRepo = chapterRepo,
            translationService = service,
            scope = appScope,
            appContext = app,
            keepAlive = TranslationKeepAlive(app, { false }, {}, {})
        )
        val vm = ReaderViewModel(
            bookId = 1L,
            bookRepo = BookRepository(db.bookDao()),
            chapterRepo = chapterRepo,
            settingsRepo = settingsRepo,
            llmProfileRepo = profileRepo,
            translationService = service,
            appContext = app,
            coordinator = coordinator
        )
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.uiState.firstValue { it.restoreReady } }
            }
            vm.onFirstContentReady()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.uiState.firstValue { it.chaptersLoaded } }
                withTimeout(5_000) { vm.uiState.firstValue { it.llmSettings.apiKey == "key" } }
            }

            vm.switchMode("en")
            service.awaitChannel("正文-1").send(TranslationEvent.Chunk("ABC"))
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.uiState.firstValue { it.streamingText == "ABC" } }
            }

            vm.navigateTo(chapterIds[1])
            service.awaitChannel("正文-2").send(TranslationEvent.Thinking("B-think"))
            val stateB = withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.uiState.firstValue { it.activeChapterId == chapterIds[1] && it.thinkingText == "B-think" } }
            }
            assertEquals("B 章不应看到 A 章的正文", "", stateB.streamingText)
            assertEquals("B 章不应看到 A 章的译文字数", 0, stateB.streamingCharCount)
            assertEquals(TranslationPhase.THINKING, stateB.translationPhase)
            assertTrue("B 章思考中仍属流式状态", stateB.isStreaming)

            vm.navigateTo(chapterIds[0])
            val stateA = withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.uiState.firstValue { it.activeChapterId == chapterIds[0] && it.streamingText == "ABC" } }
            }
            assertEquals("切回 A 章不应看到 B 章的思考文本", "", stateA.thinkingText)
            assertEquals("切回 A 章应恢复该章已收到的字数", 3, stateA.streamingCharCount)
            assertEquals(TranslationPhase.STREAMING, stateA.translationPhase)
            assertTrue("切回运行中的 A 章应保持流式状态", stateA.isStreaming)
            assertTrue(coordinator.isRunning(TranslationTaskKey(1L, chapterIds[0])))
            assertTrue(coordinator.isRunning(TranslationTaskKey(1L, chapterIds[1])))
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private class ControlledService : TranslationService {
        private val channels = ConcurrentHashMap<String, Channel<TranslationEvent>>()

        override fun translateStream(
            settings: LlmSettings,
            chapterTitle: String,
            chapterContent: String,
            sourceLanguage: String
        ): Flow<TranslationEvent> = channels.computeIfAbsent(chapterContent) {
            Channel(Channel.UNLIMITED)
        }.receiveAsFlow()

        override suspend fun testConnection(settings: LlmSettings): Result<String> = Result.success("ok")

        suspend fun awaitChannel(content: String): Channel<TranslationEvent> {
            val deadline = System.currentTimeMillis() + 5_000
            while (true) {
                channels[content]?.let { return it }
                if (System.currentTimeMillis() > deadline) throw AssertionError("等待通道超时：$content")
                delay(10)
            }
        }
    }
}

private suspend fun <T> Flow<T>.firstValue(predicate: (T) -> Boolean = { true }): T = first(predicate)
