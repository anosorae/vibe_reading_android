package com.vibereading.app.ui.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 可配置事件序列的假翻译服务，记录最后一次调用的入参。 */
class FakeTranslationService(
    var events: List<TranslationEvent> = emptyList()
) : TranslationService {
    var lastTitle: String? = null
    var lastContent: String? = null
    var lastPrevEnglish: String? = null

    override fun translateStream(
        settings: LlmSettings,
        chapterTitle: String,
        chapterContent: String,
        prevChapterEnglish: String?
    ): Flow<TranslationEvent> = flow {
        lastTitle = chapterTitle
        lastContent = chapterContent
        lastPrevEnglish = prevChapterEnglish
        emit(TranslationEvent.Started)
        events.forEach { emit(it) }
    }

    override suspend fun testConnection(settings: LlmSettings): Result<String> = Result.success("ok")

    override fun truncateMiddle(text: String, maxLen: Int): String = text
}

/**
 * TranslationCoordinator 回归测试。
 * 使用真实 Dispatchers.Default 协程 + 轮询等待：Room 的 suspend DAO 走真实 IO 线程，
 * 若用测试调度器（advanceUntilIdle）会因真实线程恢复与虚拟时间不同步而竞争。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationCoordinatorTest {

    private lateinit var db: AppDatabase
    private lateinit var chapterRepo: ChapterRepository
    private lateinit var scope: CoroutineScope
    private val bookId = 1L
    private var chapterId = 0L
    private lateinit var chapter: Chapter
    private val settings = LlmSettings(apiKey = "test-key")

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        chapterRepo = ChapterRepository(db.chapterDao())
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        runBlocking {
            db.bookDao().insert(
                BookEntity(id = bookId, title = "测试书", totalChapters = 1, lastReadAt = 1L, createdAt = 1L)
            )
            val ids = db.chapterDao().insertAll(
                listOf(ChapterEntity(bookId = bookId, title = "第一章", chapterIndex = 0, content = "正文"))
            )
            chapterId = ids[0]
            chapter = chapterRepo.getChapterById(bookId, chapterId)!!
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun coordinator(service: TranslationService) =
        TranslationCoordinator(bookId, chapterRepo, service, scope, context)

    /** 轮询等待章节状态落库；返回最终实体（含 translationRunId）。 */
    private suspend fun awaitStatus(expected: Int): ChapterEntity {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val ch = db.chapterDao().getChapterById(bookId, chapterId)
            if (ch != null && ch.status == expected) return ch
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("等待章节状态 $expected 超时")
            }
            delay(20)
        }
    }

    @Test
    fun `successful translation persists text and marks done`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Chunk("EN "), TranslationEvent.Done("EN TEXT")))
        val coordinator = coordinator(service)

        coordinator.translate(chapter, settings)
        val done = awaitStatus(Chapter.STATUS_DONE)

        assertEquals("EN TEXT", done.translatedContent)
        assertEquals(0L, done.translationRunId)
        assertEquals("正文", service.lastContent)
        assertNull(service.lastPrevEnglish)
    }

    @Test
    fun `failed translation persists error reason`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Error("boom")))
        val coordinator = coordinator(service)

        coordinator.translate(chapter, settings)
        val failed = awaitStatus(Chapter.STATUS_FAILED)

        assertEquals("boom", failed.errorMessage)
    }

    @Test
    fun `empty stream marks failed with generic reason`() = runBlocking {
        val service = FakeTranslationService(emptyList())
        val coordinator = coordinator(service)

        coordinator.translate(chapter, settings)
        val failed = awaitStatus(Chapter.STATUS_FAILED)

        assertEquals("翻译流未正常结束", failed.errorMessage)
    }

    @Test
    fun `blank api key fails fast without writing status`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Done("EN")))
        val coordinator = coordinator(service)
        val blankSettings = LlmSettings(apiKey = "")

        coordinator.translate(chapter, blankSettings)
        kotlinx.coroutines.yield()

        val pending = db.chapterDao().getChapterById(bookId, chapterId)!!
        assertEquals(Chapter.STATUS_PENDING, pending.status)
        assertNull(pending.translatedContent)
        assertNull(pending.errorMessage)
    }

    @Test
    fun `too long chapter marks too long`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Done("EN")))
        val coordinator = coordinator(service)
        val smallLimit = LlmSettings(apiKey = "test-key", chapterMaxChars = 1)

        coordinator.translate(chapter, smallLimit)
        val tooLong = awaitStatus(Chapter.STATUS_TOO_LONG)

        assertNotNull(tooLong.errorMessage)
    }

    @Test
    fun `cancel and reset restores pending before retry`() = runBlocking {
        // 第一次翻译：流不结束（挂起），模拟进行中的任务
        val slowService = object : TranslationService {
            override fun translateStream(
                settings: LlmSettings,
                chapterTitle: String,
                chapterContent: String,
                prevChapterEnglish: String?
            ): Flow<TranslationEvent> = flow {
                emit(TranslationEvent.Started)
                emit(TranslationEvent.Chunk("partial"))
                kotlinx.coroutines.awaitCancellation()
            }

            override suspend fun testConnection(settings: LlmSettings): Result<String> = Result.success("ok")
            override fun truncateMiddle(text: String, maxLen: Int): String = text
        }
        val coordinator = coordinator(slowService)
        coordinator.translate(chapter, settings)
        awaitStatus(Chapter.STATUS_IN_PROGRESS)

        // 用户重译：取消旧任务，恢复 PENDING 并清 runId
        coordinator.cancelAndReset()
        val pending = db.chapterDao().getChapterById(bookId, chapterId)!!
        assertEquals(Chapter.STATUS_PENDING, pending.status)
        assertEquals(0L, pending.translationRunId)

        // 新任务（正常完成服务）成功写入
        val freshService = FakeTranslationService(listOf(TranslationEvent.Done("FINAL")))
        val newCoordinator = coordinator(freshService)
        newCoordinator.translate(chapter, settings)
        val done = awaitStatus(Chapter.STATUS_DONE)
        assertEquals("FINAL", done.translatedContent)
    }

    @Test
    fun `context boost passes previous chapter translation`() = runBlocking {
        // 前一章直接完成，提供 translatedContent 供上下文引用
        val prevId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, title = "前一章", chapterIndex = 0, content = "旧正文"))
        )[0]
        db.chapterDao().startTranslationRun(bookId, prevId, 1L, 1)
        db.chapterDao().completeTranslationRun(bookId, prevId, 1L, "OLD EN", 2)

        // 第二章：contextBoost 开启，应把前一章英译传给服务
        val secondId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, title = "当前章", chapterIndex = 1, content = "新正文"))
        )[0]
        val secondChapter = chapterRepo.getChapterById(bookId, secondId)!!
        val boostSettings = settings.copy(enableContextBoost = true, contextChapters = 1)

        val service = FakeTranslationService(listOf(TranslationEvent.Done("NEW EN")))
        val coordinator = coordinator(service)
        coordinator.translate(secondChapter, boostSettings)
        withTimeout(10_000) {
            while (service.lastPrevEnglish == null) delay(20)
        }

        assertEquals("OLD EN", service.lastPrevEnglish)
    }
}
