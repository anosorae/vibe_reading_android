package com.vibereading.app.ui.reader

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.FakeTranslationService
import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.ReadingPosition
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.inMemoryPreferenceStore
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import com.vibereading.app.teardownRoomAndMain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 模拟已经预热的 DataStore：first() 不挂起，Main.immediate 在构造期间直接执行。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelInitializationTest {
    @Test
    fun `opening loads saved chapter before full book and preserves restored offset`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val db = newInMemoryDb()
        val store = inMemoryPreferenceStore()
        val settings = SettingsRepository(app, store)
        var vm: ReaderViewModel? = null
        try {
            val ids = seedBookAndChapters(
                db, bookId = 1L, chapterCount = 3,
                bookTitle = "测试", chapterTitle = { "章节$it" },
                chapterContent = { "正文".repeat(100) }
            )
            val books = BookRepository(db.bookDao())
            books.updateLastReadProgress(1, ids[1], 42)
            settings.reading.saveSettings(ReadingSettings(fontSize = 23))
            val reader = ReaderViewModel(1, books, ChapterRepository(db.chapterDao()), settings,
                LlmProfileRepository(db.llmProfileDao(), settings), FakeTranslationService(), appContext = app)
            vm = reader
            // Room 使用真实 IO 线程，等待也用真实时钟，避免虚拟超时抢先推进。
            val first = withContext(Dispatchers.Default) {
                withTimeout(5000) { reader.uiState.first { it.restoreReady } }
            }
            assertEquals(listOf(ids[1]), first.chapters.map { it.id })
            assertEquals(false, first.chaptersLoaded)
            assertEquals(23, first.readingSettings.fontSize)
            assertEquals(ReadingPosition(ids[1], 42), first.position)
            reader.onFirstContentReady()
            val expanded = withContext(Dispatchers.Default) {
                withTimeout(5000) { reader.uiState.first { it.chaptersLoaded } }
            }
            assertEquals(ids, expanded.chapters.map { it.id })
            assertEquals(first.position, expanded.position)
            assertEquals(42, books.getBookByIdOnce(1)!!.lastReadOffset)
            // 缺失/别书章节 ID 回退首章，查询仍局限于当前书籍。
            assertEquals(ids.first(), ChapterRepository(db.chapterDao()).getOpeningChapter(1, -99)!!.id)
        } finally {
            teardownRoomAndMain(db, vm?.viewModelScope)
        }
    }

    @Test
    fun `cached settings can load inline during repeated reader construction`() = checkInitialization(cached = true)

    @Test
    fun `settings edits wait for cold load and preserve persisted values`() = checkInitialization(cached = false)

    private fun checkInitialization(cached: Boolean) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val db = newInMemoryDb()
        val ready = CompletableDeferred<Unit>().apply { if (cached) complete(Unit) }
        // gate 非空：首次收集 data 先等待 ready，模拟未预热的 DataStore 冷启动
        val store = inMemoryPreferenceStore(gate = ready)
        val settings = SettingsRepository(app, store)
        try {
            settings.reading.saveSettings(ReadingSettings(fontSize = 21, paragraphSpacing = 25))
            repeat(3) { index ->
                val vm = ReaderViewModel(
                    bookId = -1,
                    bookRepo = BookRepository(db.bookDao()),
                    chapterRepo = ChapterRepository(db.chapterDao()),
                    settingsRepo = settings,
                    llmProfileRepo = LlmProfileRepository(db.llmProfileDao(), settings),
                    translationService = FakeTranslationService(),
                    appContext = app
                )
                try {
                    vm.updateReadingSettings { it.copy(fontSize = it.fontSize + 1) }
                    if (!ready.isCompleted) {
                        assertEquals(ReadingSettings(), vm.uiState.value.readingSettings)
                        ready.complete(Unit)
                    }
                    assertEquals(22 + index, vm.uiState.value.readingSettings.fontSize)
                    assertEquals(25, vm.uiState.value.readingSettings.paragraphSpacing)
                    assertEquals(vm.uiState.value.readingSettings, settings.reading.settings.first())
                } finally {
                    vm.viewModelScope.cancel()
                }
            }
        } finally {
            teardownRoomAndMain(db)
        }
    }
}
