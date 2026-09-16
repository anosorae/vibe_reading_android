package com.vibereading.app.web

import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.local.entity.LlmProfileEntity
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.inMemoryPreferenceStore
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import com.vibereading.app.ui.reader.TranslationCoordinator
import com.vibereading.app.ui.reader.TranslationKeepAlive
import com.vibereading.app.ui.reader.TranslationTaskKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionApiTranslationTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db = newInMemoryDb()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `web can start different chapters concurrently and duplicate start is idempotent`() = runBlocking {
        val chapterIds = seedBookAndChapters(db, chapterCount = 2, chapterContent = { "正文-${it + 1}" })
        db.llmProfileDao().insert(LlmProfileEntity(name = "测试", apiKey = "key", isActive = true))
        val chapterRepo = ChapterRepository(db.chapterDao())
        val settingsRepo = SettingsRepository(ApplicationProvider.getApplicationContext(), inMemoryPreferenceStore())
        val service = ControlledService()
        val coordinator = TranslationCoordinator(
            chapterRepo,
            service,
            scope,
            ApplicationProvider.getApplicationContext(),
            TranslationKeepAlive(ApplicationProvider.getApplicationContext(), { false }, {}, {})
        )
        val api = CompanionApi(
            bookRepo = BookRepository(db.bookDao()),
            chapterRepo = chapterRepo,
            llmProfileRepo = LlmProfileRepository(db.llmProfileDao(), settingsRepo),
            coordinatorProvider = { coordinator }
        )

        val first = api.startTranslation(1L, chapterIds[0])
        val second = api.startTranslation(1L, chapterIds[1])
        val duplicate = api.startTranslation(1L, chapterIds[0])

        assertTrue(first.started)
        assertTrue(second.started)
        assertTrue(duplicate.alreadyRunning)
        assertTrue(coordinator.isRunning(TranslationTaskKey(1L, chapterIds[0])))
        assertTrue(coordinator.isRunning(TranslationTaskKey(1L, chapterIds[1])))
        assertFalse(service.wasCancelled("正文-1"))
        assertFalse(service.wasCancelled("正文-2"))
    }

    @Test
    fun `orphaned in progress chapter is retried instead of reported running`() = runBlocking {
        val chapterId = seedBookAndChapters(db, chapterCount = 1).first()
        db.llmProfileDao().insert(LlmProfileEntity(name = "测试", apiKey = "key", isActive = true))
        val chapterRepo = ChapterRepository(db.chapterDao())
        chapterRepo.startTranslation(1L, chapterId, 99L)
        val settingsRepo = SettingsRepository(ApplicationProvider.getApplicationContext(), inMemoryPreferenceStore())
        val service = ControlledService()
        val coordinator = TranslationCoordinator(
            chapterRepo,
            service,
            scope,
            ApplicationProvider.getApplicationContext(),
            TranslationKeepAlive(ApplicationProvider.getApplicationContext(), { false }, {}, {})
        )
        val api = CompanionApi(
            BookRepository(db.bookDao()),
            chapterRepo,
            LlmProfileRepository(db.llmProfileDao(), settingsRepo),
            { coordinator }
        )

        val result = api.startTranslation(1L, chapterId)

        assertTrue(result.started)
        assertFalse(result.alreadyRunning)
    }

    private class ControlledService : TranslationService {
        private val channels = ConcurrentHashMap<String, Channel<TranslationEvent>>()
        private val cancelled = ConcurrentHashMap.newKeySet<String>()

        override fun translateStream(
            settings: LlmSettings,
            chapterTitle: String,
            chapterContent: String,
            sourceLanguage: String
        ): Flow<TranslationEvent> = channels.computeIfAbsent(chapterContent) {
            Channel(Channel.UNLIMITED)
        }.receiveAsFlow().onCompletion { cause ->
            // 取消（而不是正常结束）才算被取消：断言才有意义
            if (cause is CancellationException) cancelled += chapterContent
        }

        override suspend fun testConnection(settings: LlmSettings): Result<String> = Result.success("ok")

        fun wasCancelled(content: String): Boolean = content in cancelled
    }
}
