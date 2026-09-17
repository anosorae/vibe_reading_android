package com.vibereading.app.ui.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.FakeTranslationService
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationCoordinatorTest {

    private lateinit var db: AppDatabase
    private lateinit var chapterRepo: ChapterRepository
    private lateinit var scope: CoroutineScope
    private val bookId = 1L
    private lateinit var chapters: List<Chapter>
    private val settings = LlmSettings(apiKey = "test-key")
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = newInMemoryDb()
        chapterRepo = ChapterRepository(db.chapterDao())
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        runBlocking {
            val ids = seedBookAndChapters(
                db,
                bookId = bookId,
                chapterCount = 2,
                chapterTitle = { "第${it + 1}章" },
                chapterContent = { "正文-${it + 1}" }
            )
            chapters = ids.map { chapterRepo.getChapterById(bookId, it)!! }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun coordinator(service: TranslationService): TranslationCoordinator = TranslationCoordinator(
        chapterRepo = chapterRepo,
        translationService = service,
        scope = scope,
        appContext = context,
        keepAlive = TranslationKeepAlive(
            appContext = context,
            companionRunning = { false },
            startService = {},
            stopService = {}
        )
    )

    private suspend fun awaitStatus(expected: Int, id: Long = chapters.first().id): ChapterEntity {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val chapter = db.chapterDao().getChapterById(bookId, id)
            if (chapter != null && chapter.status == expected) return chapter
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("等待章节状态 $expected 超时")
            }
            delay(20)
        }
    }

    private suspend fun awaitTask(
        coordinator: TranslationCoordinator,
        key: TranslationTaskKey,
        predicate: (TranslationUiState) -> Boolean
    ): TranslationUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            coordinator.stateOf(key)?.takeIf(predicate)?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("等待任务状态超时：$key")
            delay(20)
        }
    }

    @Test
    fun `successful translation persists text and marks done`() = runBlocking {
        val service = FakeTranslationService(
            listOf(TranslationEvent.Chunk("EN "), TranslationEvent.Done("EN TEXT"))
        )
        val coordinator = coordinator(service)

        coordinator.start(bookId, chapters[0].id, settings)
        val done = awaitStatus(Chapter.STATUS_DONE)

        assertEquals("EN TEXT", done.translatedContent)
        assertEquals(0L, done.translationRunId)
        assertEquals("正文-1", service.lastContent)
        assertEquals("zh", service.lastSourceLanguage)
    }

    @Test
    fun `english source book passes source language to service`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Done("中文译文")))
        val coordinator = coordinator(service)

        coordinator.start(bookId, chapters[0].id, settings, "en")

        val done = awaitStatus(Chapter.STATUS_DONE)
        assertEquals("中文译文", done.translatedContent)
        assertEquals("en", service.lastSourceLanguage)
    }

    @Test
    fun `failed translation persists error reason`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Error("boom")))
        val coordinator = coordinator(service)

        coordinator.start(bookId, chapters[0].id, settings)
        val failed = awaitStatus(Chapter.STATUS_FAILED)

        assertEquals("boom", failed.errorMessage)
    }

    @Test
    fun `empty stream marks failed with generic reason`() = runBlocking {
        val coordinator = coordinator(FakeTranslationService(emptyList()))

        coordinator.start(bookId, chapters[0].id, settings)
        val failed = awaitStatus(Chapter.STATUS_FAILED)

        assertEquals("翻译流未正常结束", failed.errorMessage)
    }

    @Test
    fun `blank api key fails fast without writing status`() = runBlocking {
        val service = FakeTranslationService(listOf(TranslationEvent.Done("EN")))
        val coordinator = coordinator(service)
        val key = TranslationTaskKey(bookId, chapters[0].id)

        coordinator.start(bookId, chapters[0].id, LlmSettings(apiKey = ""))
        awaitTask(coordinator, key) { it.phase == TranslationPhase.FAILED }

        val pending = db.chapterDao().getChapterById(bookId, chapters[0].id)!!
        assertEquals(Chapter.STATUS_PENDING, pending.status)
        assertNull(pending.translatedContent)
        assertNull(pending.errorMessage)
    }

    @Test
    fun `too long chapter marks too long`() = runBlocking {
        val coordinator = coordinator(FakeTranslationService(listOf(TranslationEvent.Done("EN"))))

        coordinator.start(bookId, chapters[0].id, settings.copy(chapterMaxChars = 1))
        val tooLong = awaitStatus(Chapter.STATUS_TOO_LONG)

        assertNotNull(tooLong.errorMessage)
    }

    @Test
    fun `different chapters run concurrently and retain independent progress`() = runBlocking {
        val service = ControlledTranslationService()
        val coordinator = coordinator(service)
        val keyA = TranslationTaskKey(bookId, chapters[0].id)
        val keyB = TranslationTaskKey(bookId, chapters[1].id)

        coordinator.start(bookId, chapters[0].id, settings)
        coordinator.start(bookId, chapters[1].id, settings)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[0].id)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[1].id)

        service.channel("正文-1").send(TranslationEvent.Chunk("A-part"))
        service.channel("正文-2").send(TranslationEvent.Thinking("B-thinking"))

        val stateA = awaitTask(coordinator, keyA) { it.streamingText == "A-part" }
        val stateB = awaitTask(coordinator, keyB) { it.thinkingText == "B-thinking" }
        assertEquals(TranslationPhase.STREAMING, stateA.phase)
        assertEquals(TranslationPhase.THINKING, stateB.phase)
        assertEquals("", stateA.thinkingText)
        assertEquals("", stateB.streamingText)
        assertEquals(setOf(keyA, keyB), coordinator.runningKeys())

        service.finish("正文-1", "A-final")
        service.finish("正文-2", "B-final")
        assertEquals("A-final", awaitStatus(Chapter.STATUS_DONE, chapters[0].id).translatedContent)
        assertEquals("B-final", awaitStatus(Chapter.STATUS_DONE, chapters[1].id).translatedContent)
    }

    @Test
    fun `same chapter start is idempotent`() = runBlocking {
        val service = ControlledTranslationService()
        val coordinator = coordinator(service)

        val first = coordinator.start(bookId, chapters[0].id, settings)
        val second = coordinator.start(bookId, chapters[0].id, settings)

        assertTrue(first is TranslationLaunchResult.Started)
        assertTrue(second is TranslationLaunchResult.AlreadyRunning)
        service.awaitCallCount("正文-1", 1)
        coordinator.cancel(TranslationTaskKey(bookId, chapters[0].id))
        Unit
    }

    @Test
    fun `cancelling one chapter does not cancel another`() = runBlocking {
        val service = ControlledTranslationService()
        val coordinator = coordinator(service)
        val keyA = TranslationTaskKey(bookId, chapters[0].id)
        val keyB = TranslationTaskKey(bookId, chapters[1].id)

        coordinator.start(bookId, chapters[0].id, settings)
        coordinator.start(bookId, chapters[1].id, settings)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[0].id)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[1].id)

        assertTrue(coordinator.cancel(keyA))
        assertEquals(Chapter.STATUS_PENDING, awaitStatus(Chapter.STATUS_PENDING, chapters[0].id).status)
        assertFalse(coordinator.isRunning(keyA))
        assertTrue(coordinator.isRunning(keyB))
        assertTrue(service.wasCancelled("正文-1"))
        assertFalse(service.wasCancelled("正文-2"))

        service.finish("正文-2", "B-final")
        assertEquals("B-final", awaitStatus(Chapter.STATUS_DONE, chapters[1].id).translatedContent)
    }

    @Test
    fun `retry replaces only requested chapter`() = runBlocking {
        val service = ControlledTranslationService()
        val coordinator = coordinator(service)
        val keyA = TranslationTaskKey(bookId, chapters[0].id)
        val keyB = TranslationTaskKey(bookId, chapters[1].id)

        coordinator.start(bookId, chapters[0].id, settings)
        coordinator.start(bookId, chapters[1].id, settings)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[0].id)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[1].id)

        val retried = coordinator.retry(bookId, chapters[0].id, settings)
        assertTrue(retried is TranslationLaunchResult.Started)
        assertTrue(coordinator.isRunning(keyA))
        assertTrue(coordinator.isRunning(keyB))
        service.awaitCallCount("正文-1", 2)
        service.awaitCallCount("正文-2", 1)

        service.finish("正文-1", "A-new", invocation = 1)
        service.finish("正文-2", "B-final")
        assertEquals("A-new", awaitStatus(Chapter.STATUS_DONE, chapters[0].id).translatedContent)
        assertEquals("B-final", awaitStatus(Chapter.STATUS_DONE, chapters[1].id).translatedContent)
    }

    @Test
    fun `completed and cancelled tasks release runtime state while failures keep the reason`() = runBlocking {
        val service = ControlledTranslationService()
        val coordinator = coordinator(service)
        val keyA = TranslationTaskKey(bookId, chapters[0].id)
        val keyB = TranslationTaskKey(bookId, chapters[1].id)

        coordinator.start(bookId, chapters[0].id, settings)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[0].id)
        service.finish("正文-1", "A-final")
        awaitStatus(Chapter.STATUS_DONE, chapters[0].id)
        awaitTaskCleared(coordinator, keyA)

        coordinator.start(bookId, chapters[1].id, settings)
        awaitStatus(Chapter.STATUS_IN_PROGRESS, chapters[1].id)
        assertTrue(coordinator.cancel(keyB))
        awaitStatus(Chapter.STATUS_PENDING, chapters[1].id)
        assertNull("取消后不应残留运行期相位", coordinator.stateOf(keyB))

        val failing = coordinator(FakeTranslationService(listOf(TranslationEvent.Error("boom"))))
        failing.retry(bookId, chapters[0].id, settings)
        awaitStatus(Chapter.STATUS_FAILED, chapters[0].id)
        // 失败条目保留：运行期原因（如「请先配置 API Key」）不写库，删掉 UI 就失去线索
        awaitTask(failing, keyA) { it.phase == TranslationPhase.FAILED }
        Unit
    }

    private suspend fun awaitTaskCleared(coordinator: TranslationCoordinator, key: TranslationTaskKey) {
        val deadline = System.currentTimeMillis() + 10_000
        while (coordinator.stateOf(key) != null) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("等待运行期状态清理超时：$key")
            }
            delay(20)
        }
    }

    @Test
    fun `illustration only chapter completes without calling api`() = runBlocking {
        val ids = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId,
                    title = "插图章",
                    chapterIndex = 2,
                    content = IllustrationLink.build("9/pic.jpg", 100, 50)
                ),
                ChapterEntity(bookId = bookId, title = "空白章", chapterIndex = 3, content = "\n\n   \n\n")
            )
        )
        val service = FakeTranslationService(listOf(TranslationEvent.Done("不应被使用")))
        val coordinator = coordinator(service)

        coordinator.start(bookId, ids[0], settings)
        coordinator.start(bookId, ids[1], settings)

        val imageDone = awaitStatus(Chapter.STATUS_DONE, ids[0])
        assertEquals("", imageDone.translatedContent)
        assertEquals(0L, imageDone.translationRunId)
        assertEquals("", awaitStatus(Chapter.STATUS_DONE, ids[1]).translatedContent)
        assertEquals(0, service.callCount)
    }

    private class ControlledTranslationService : TranslationService {
        private val channels = ConcurrentHashMap<String, CopyOnWriteArrayList<Channel<TranslationEvent>>>()
        private val cancelled = ConcurrentHashMap.newKeySet<String>()
        private val calls = ConcurrentHashMap<String, AtomicInteger>()

        override fun translateStream(
            settings: LlmSettings,
            chapterTitle: String,
            chapterContent: String,
            sourceLanguage: String
        ): Flow<TranslationEvent> {
            calls.computeIfAbsent(chapterContent) { AtomicInteger() }.incrementAndGet()
            val channel = Channel<TranslationEvent>(Channel.UNLIMITED)
            channels.computeIfAbsent(chapterContent) { CopyOnWriteArrayList() }.add(channel)
            return channel.receiveAsFlow().onCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) cancelled += chapterContent
            }
        }

        override suspend fun testConnection(settings: LlmSettings): Result<String> = Result.success("ok")

        suspend fun channel(content: String, invocation: Int = callCount(content) - 1): Channel<TranslationEvent> {
            val deadline = System.currentTimeMillis() + 10_000
            while (true) {
                // 协调器先写库 IN_PROGRESS 再调 translateStream 注册通道，测试线程可能抢跑；
                // 此时 callCount=0 会推算出 -1，须等注册完成后按真实调用次数重新定位
                val idx = if (invocation < 0) maxOf(0, callCount(content) - 1) else invocation
                channels[content]?.getOrNull(idx)?.let { return it }
                if (System.currentTimeMillis() > deadline) throw AssertionError("等待服务调用超时：$content#$idx")
                delay(10)
            }
        }

        suspend fun finish(content: String, result: String, invocation: Int = callCount(content) - 1) {
            val channel = channel(content, invocation)
            channel.send(TranslationEvent.Done(result))
            channel.close()
        }

        fun callCount(content: String): Int = calls[content]?.get() ?: 0

        suspend fun awaitCallCount(content: String, expected: Int) {
            val deadline = System.currentTimeMillis() + 10_000
            while (callCount(content) < expected) {
                if (System.currentTimeMillis() > deadline) {
                    throw AssertionError("等待服务调用次数超时：$content expected=$expected actual=${callCount(content)}")
                }
                delay(10)
            }
        }

        fun wasCancelled(content: String): Boolean = content in cancelled
    }
}
