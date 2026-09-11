package com.vibereading.app.ui.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.domain.model.ReadingPosition
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.ReadingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val settings = SettingsRepository(app, store)
        var vm: ReaderViewModel? = null
        try {
            db.bookDao().insert(BookEntity(id = 1, title = "测试", totalChapters = 3, lastReadAt = 1, createdAt = 1))
            val ids = db.chapterDao().insertAll((0..2).map {
                ChapterEntity(bookId = 1, title = "章节$it", chapterIndex = it, content = "正文".repeat(100))
            })
            val books = BookRepository(db.bookDao())
            books.updateLastReadProgress(1, ids[1], 42)
            settings.saveReadingSettings(ReadingSettings(fontSize = 23))
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
            vm?.viewModelScope?.cancel()
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cached settings can load inline during repeated reader construction`() = checkInitialization(cached = true)

    @Test
    fun `settings edits wait for cold load and preserve persisted values`() = checkInitialization(cached = false)

    private fun checkInitialization(cached: Boolean) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val ready = CompletableDeferred<Unit>().apply { if (cached) complete(Unit) }
        val values = MutableStateFlow(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = flow { ready.await(); emitAll(values) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(values.value).also { values.value = it }
        }
        val settings = SettingsRepository(app, store)
        try {
            settings.saveReadingSettings(ReadingSettings(fontSize = 21, paragraphSpacing = 25))
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
                    assertEquals(vm.uiState.value.readingSettings, settings.readingSettings.first())
                } finally {
                    vm.viewModelScope.cancel()
                }
            }
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
