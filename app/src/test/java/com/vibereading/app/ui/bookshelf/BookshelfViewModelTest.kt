package com.vibereading.app.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.FakeTranslationService
import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.inMemoryPreferenceStore
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import com.vibereading.app.ui.reader.TranslationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * BookshelfViewModel 单测：书架聚合流（排序/搜索/主题色）、删书编排
 * （协调器释放 + 数据删除）、原文语言修正（清译文重置显示模式）与 TXT 导入链路。
 * Room 用真实内存库、DataStore 用纯内存实现，翻译服务用 Fake。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookshelfViewModelTest {

    private lateinit var db: com.vibereading.app.data.local.AppDatabase
    private lateinit var bookRepo: BookRepository
    private lateinit var chapterRepo: ChapterRepository
    private lateinit var settings: SettingsRepository
    private lateinit var coordinatorScope: CoroutineScope
    private var vm: BookshelfViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        db = newInMemoryDb()
        bookRepo = BookRepository(db.bookDao())
        chapterRepo = ChapterRepository(db.chapterDao())
        settings = SettingsRepository(app, inMemoryPreferenceStore())
        coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        coordinatorScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newVm(): BookshelfViewModel {
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val coordinator = TranslationCoordinator(
            chapterRepo, FakeTranslationService(), coordinatorScope, app
        )
        return BookshelfViewModel(bookRepo, chapterRepo, settings, coordinator).also { vm = it }
    }

    /** Room 用真实 IO 线程：等待走真实时钟，避免虚拟时间抢先超时。 */
    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5000) { block() } }

    @Test
    fun `shelf items follow sort preference and search query`() = runTest {
        bookRepo.insert(Book(title = "Beta书", lastReadAt = 200L, createdAt = 200L))
        bookRepo.insert(Book(title = "Alpha书", lastReadAt = 100L, createdAt = 300L))
        settings.bookshelf.saveSort(ShelfSort.TITLE)
        settings.bookshelf.saveSortOrder(SortOrder.ASC)
        val viewModel = newVm()

        val sorted = awaitReal { viewModel.uiState.first { it.items.isNotEmpty() } }
        assertEquals(listOf("Alpha书", "Beta书"), sorted.items.map { it.book.title })
        assertEquals(sorted.items, sorted.filteredItems)
        assertEquals(ShelfSort.TITLE, sorted.sort)
        assertEquals(SortOrder.ASC, sorted.sortOrder)

        viewModel.setSearchQuery("beta")
        val filtered = awaitReal { viewModel.uiState.first { it.filteredItems.size == 1 } }
        assertEquals(listOf("Beta书"), filtered.filteredItems.map { it.book.title })
        assertEquals(2, filtered.items.size) // 全量列表不受搜索影响
    }

    @Test
    fun `deleteBook removes book with its chapters`() = runTest {
        seedBookAndChapters(db, bookId = 1L, chapterCount = 2)
        val viewModel = newVm()
        awaitReal { viewModel.uiState.first { it.items.isNotEmpty() } }

        viewModel.deleteBook(1L)
        awaitReal { viewModel.uiState.first { it.items.isEmpty() } }

        assertTrue(bookRepo.getShelfItems().first().isEmpty())
        assertTrue(db.chapterDao().getChaptersByBookList(1L).isEmpty())
    }

    @Test
    fun `correctSourceLanguage resets translations and display mode`() = runTest {
        val ids = seedBookAndChapters(db, bookId = 1L, chapterCount = 2)
        chapterRepo.startTranslation(1L, ids[0], 7L)
        chapterRepo.completeTranslation(1L, ids[0], 7L, "旧方向译文")
        chapterRepo.startTranslation(1L, ids[1], 8L)
        chapterRepo.completeTranslation(1L, ids[1], 8L, "旧方向译文二")
        val viewModel = newVm()

        viewModel.correctSourceLanguage(1L, "en")
        // sourceLanguage 与 languageMode 是两次独立写库，两个字段都就绪后再断言
        val book = awaitReal {
            bookRepo.getBookById(1L).first { it?.sourceLanguage == "en" && it.languageMode == "en" }!!
        }
        assertEquals("en", book.languageMode) // 显示模式重置为新原文语言
        val chapters = awaitReal { chapterRepo.getChaptersByBookList(1L) }
        assertTrue(
            chapters.all {
                it.status == Chapter.STATUS_PENDING && it.translatedContent == null
            }
        )
    }

    @Test
    fun `uploadBook imports txt chapters and reports success`() = runTest {
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val txt = "第一章 起\n\n这是第一章的中文正文内容。\n\n第二章 承\n\n这是第二章的中文正文内容。".toByteArray()
        val uri = Uri.parse("content://test/我的书.txt")
        shadowOf(app.contentResolver).registerInputStream(uri, txt.inputStream())
        val viewModel = newVm()

        viewModel.uploadBook(app, uri)
        val state = awaitReal { viewModel.uiState.first { it.shelfMessage != null } }

        assertFalse(state.isLoading)
        assertTrue(state.shelfMessage!!.contains("上传成功"))
        assertTrue(state.shelfMessage!!.contains("2 章"))
        val book = awaitReal { bookRepo.getShelfItems().first { it.isNotEmpty() } }.single().book
        assertEquals("我的书", book.title)
        assertEquals("zh", book.sourceLanguage)
        assertEquals("zh", book.languageMode)
        assertEquals(2, book.totalChapters)
    }

    @Test
    fun `uploadBook reports failure for unreadable uri`() = runTest {
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val viewModel = newVm()

        viewModel.uploadBook(app, Uri.parse("content://test/不存在.txt"))
        val state = awaitReal { viewModel.uiState.first { it.shelfMessage != null } }

        assertFalse(state.isLoading)
        assertTrue(state.shelfMessage!!.startsWith("上传失败"))
        assertTrue(bookRepo.getShelfItems().first().isEmpty())
    }
}
