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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「挂起后台被系统冻结后杀进程」场景的反馈回路：
 * 模拟阅读若干页（每次翻页 updateProgress 即时入队落库）→ 进程死亡
 * （scope 取消、无 flush、无 onCleared 落库）→ 重建 ViewModel 冷启动恢复。
 *
 * 断言用户症状的反面：恢复位置应等于最后一次翻页持久化的 offset，
 * 而不是回退到章节开头。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderProcessDeathRestoreTest {

    @Test
    fun `process death mid-chapter restores last page-turn offset not chapter start`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<VibeReadingApp>()
        val db = newInMemoryDb()
        val store = inMemoryPreferenceStore()
        val settings = SettingsRepository(app, store)
        val chapterRepo = ChapterRepository(db.chapterDao())
        val books = BookRepository(db.bookDao())
        try {
            // 三章、每章 60 段长文本，保证 offset 深处恢复有区分度
            val ids = seedBookAndChapters(
                db, bookId = 1L, chapterCount = 3,
                bookTitle = "冻结测试", chapterTitle = { "章节$it" },
                chapterContent = { idx ->
                    (0 until 60).joinToString("\n\n") { p -> "第${idx}章第${p}段正文内容。" + "文字".repeat(30) }
                }
            )
            val target = ids[1]
            settings.reading.saveSettings(ReadingSettings())
            // 先有一轮正常阅读：定位到第 1 章中部
            books.updateLastReadProgress(1, target, 0)

            fun newVm(): ReaderViewModel = ReaderViewModel(
                1, books, chapterRepo, settings,
                LlmProfileRepository(db.llmProfileDao(), settings),
                FakeTranslationService(), appContext = app
            )

            // ── 会话 1：正常打开并逐页翻到章节深处 ──
            var vm = newVm()
            val restored = withContext(Dispatchers.Default) {
                withTimeout(5000) { vm.uiState.first { it.restoreReady } }
            }
            assertEquals(ReadingPosition(target, 0), restored.position)
            vm.onFirstContentReady()
            withContext(Dispatchers.Default) {
                withTimeout(5000) { vm.uiState.first { it.chaptersLoaded } }
            }
            // 模拟分页翻页：pager 会话按当前页起始 offset 上报（这里取几个递增段首 offset）
            val content = chapterRepo.getChapterById(1, target)!!.content
            val deepOffset = content.indexOf("第1章第40段")
            org.junit.Assert.assertTrue("测试语料应包含第 40 段", deepOffset > 0)
            listOf(
                content.indexOf("第1章第10段"),
                content.indexOf("第1章第25段"),
                deepOffset
            ).forEach { offset -> vm.updateProgress(target, offset) }
            // 翻页入队的落库完成（真实 IO 线程，真实时钟等待）
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    while (books.getBookByIdOnce(1)!!.lastReadOffset != deepOffset) kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(ReadingPosition(target, deepOffset), vm.uiState.value.position)

            // ── 进程死亡：scope 直接取消（无 flushProgress / 无 ON_STOP）──
            vm.viewModelScope.cancel()

            // ── 会话 2：进程重建，冷启动恢复 ──
            val vm2 = newVm()
            try {
                val reopened = withContext(Dispatchers.Default) {
                    withTimeout(5000) { vm2.uiState.first { it.restoreReady } }
                }
                assertEquals(target, reopened.activeChapterId)
                assertEquals(
                    "冻结杀进程后重开应恢复到最后持久化 offset，而不是章节开头",
                    ReadingPosition(target, deepOffset),
                    reopened.position
                )
            } finally {
                vm2.viewModelScope.cancel()
            }
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
