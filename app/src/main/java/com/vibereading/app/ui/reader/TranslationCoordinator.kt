package com.vibereading.app.ui.reader

import android.content.Context
import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.domain.translation.TranslationGate
import com.vibereading.app.domain.translation.TranslationPreflight
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.TranslationForegroundService
import com.vibereading.app.web.WebCompanionService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TranslationPhase {
    IDLE,
    PREPARING,
    WAITING_FIRST_TOKEN,
    THINKING,
    STREAMING,
    FAILED,
    CANCELLED
}

data class TranslationTaskKey(
    val bookId: Long,
    val chapterId: Long
)

/** 单个章节翻译任务的运行期状态；持久化终态仍以 Room 章节记录为准。 */
data class TranslationUiState(
    val key: TranslationTaskKey,
    val runId: Long,
    val streamingText: String = "",
    val thinkingText: String = "",
    val streamingCharCount: Int = 0,
    val isStreaming: Boolean = false,
    val phase: TranslationPhase = TranslationPhase.PREPARING,
    val errorMessage: String? = null
)

sealed interface TranslationLaunchResult {
    data class Started(val key: TranslationTaskKey, val runId: Long) : TranslationLaunchResult
    data class AlreadyRunning(val key: TranslationTaskKey, val runId: Long) : TranslationLaunchResult
    data class Rejected(val reason: String) : TranslationLaunchResult
    data object NotFound : TranslationLaunchResult
}

/**
 * 多章节翻译协调器：同章互斥、异章并行，数据库以章节级 runId 防止迟到写入。
 *
 * 阅读焦点只决定 UI 展示 [states] 中哪个任务，不决定任务生命周期。切换章节、退出阅读器
 * 或 App/Web 从不同入口启动其他章节时，已有任务继续运行；同章重复开始幂等，重译只替换该章。
 */
class TranslationCoordinator(
    private val chapterRepo: ChapterRepository,
    private val translationService: TranslationService,
    private val scope: CoroutineScope,
    appContext: Context,
    private val keepAlive: TranslationKeepAlive = TranslationKeepAlive(appContext)
) {
    private data class TaskHandle(
        val runId: Long,
        val job: Job
    )

    private val taskLock = Any()
    private val operationMutex = Mutex()
    private val nextRunId = AtomicLong(0L)
    private val handles = mutableMapOf<TranslationTaskKey, TaskHandle>()
    // 运行期状态同样是 Map：改一处只替换一个条目。只保留「有活任务」与「FAILED 提示」
    // 两类条目：IDLE/CANCELLED 的终态在 Room 里（DONE / PENDING），留着只会让状态表
    // 随阅读过的章节数无界增长。
    private val stateMap = mutableMapOf<TranslationTaskKey, TranslationUiState>()
    private val _states = MutableStateFlow<Map<TranslationTaskKey, TranslationUiState>>(emptyMap())
    val states: StateFlow<Map<TranslationTaskKey, TranslationUiState>> = _states.asStateFlow()

    fun stateOf(key: TranslationTaskKey): TranslationUiState? = states.value[key]

    fun isRunning(key: TranslationTaskKey): Boolean = synchronized(taskLock) {
        handles[key]?.job?.isActive == true
    }

    fun runningKeys(): Set<TranslationTaskKey> = synchronized(taskLock) {
        handles.filterValues { it.job.isActive }.keys.toSet()
    }

    /**
     * 开始指定章节任务。相同 key 已有活动任务时幂等返回；不同 key 不互相阻塞。
     * Chapter 在协调器内部重新读取，避免 App/Web 调用方传入过期状态。
     */
    suspend fun start(
        bookId: Long,
        chapterId: Long,
        settings: LlmSettings,
        sourceLanguage: String = SourceLanguageDetector.ZH
    ): TranslationLaunchResult = operationMutex.withLock {
        startLocked(bookId, chapterId, settings, sourceLanguage)
    }

    /** 取消指定章节任务并恢复 PENDING；其他章节不受影响。 */
    suspend fun cancel(key: TranslationTaskKey): Boolean = operationMutex.withLock {
        cancelLocked(key)
    }

    /** 重译指定章节：只替换该章任务，清除旧译文/错误后以新 run 启动。 */
    suspend fun retry(
        bookId: Long,
        chapterId: Long,
        settings: LlmSettings,
        sourceLanguage: String = SourceLanguageDetector.ZH
    ): TranslationLaunchResult = operationMutex.withLock {
        val key = TranslationTaskKey(bookId, chapterId)
        cancelLocked(key)
        if (chapterRepo.resetChapter(bookId, chapterId) <= 0) return@withLock TranslationLaunchResult.NotFound
        startLocked(bookId, chapterId, settings, sourceLanguage)
    }

    /** 删除书籍/修正原文语言前取消该书全部任务并等待资源释放，同时清掉该书残留的失败提示。 */
    suspend fun cancelBook(bookId: Long) = operationMutex.withLock {
        val keys = synchronized(taskLock) {
            (handles.keys + stateMap.keys).filter { it.bookId == bookId }
        }
        keys.forEach { cancelLocked(it) }
        synchronized(taskLock) {
            // 保留的 FAILED 条目属于「这本书 + 这一章」的旧上下文：书已删/已重置，
            // 提示不再有意义，留着只会让状态表随删书次数无界增长。
            val stale = stateMap.keys.filter { it.bookId == bookId }
            if (stale.isNotEmpty()) {
                stale.forEach { stateMap.remove(it) }
                publishStates()
            }
        }
    }

    private suspend fun startLocked(
        bookId: Long,
        chapterId: Long,
        settings: LlmSettings,
        sourceLanguage: String
    ): TranslationLaunchResult {
        val key = TranslationTaskKey(bookId, chapterId)
        synchronized(taskLock) {
            handles[key]?.takeIf { it.job.isActive }?.let {
                return TranslationLaunchResult.AlreadyRunning(key, it.runId)
            }
        }
        val chapter = chapterRepo.getChapterById(bookId, chapterId)
            ?: return TranslationLaunchResult.NotFound
        if (chapter.status == Chapter.STATUS_DONE) {
            return TranslationLaunchResult.Rejected("该章已翻译完成")
        }
        val run = nextRunId.incrementAndGet()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            runTask(key, run, chapter, settings, sourceLanguage)
        }
        synchronized(taskLock) {
            handles[key]?.takeIf { it.job.isActive }?.let { existing ->
                job.cancel()
                return TranslationLaunchResult.AlreadyRunning(key, existing.runId)
            }
            handles[key] = TaskHandle(run, job)
            putState(TranslationUiState(key = key, runId = run, phase = TranslationPhase.PREPARING))
        }
        job.start()
        return TranslationLaunchResult.Started(key, run)
    }

    private suspend fun cancelLocked(key: TranslationTaskKey): Boolean {
        val handle = synchronized(taskLock) {
            val h = handles.remove(key) ?: return false
            // 条目随取消立刻消失：章节在 Room 里已恢复 PENDING，运行期不必再留相位
            stateMap.remove(key)
            publishStates()
            h
        }
        handle.job.cancelAndJoin()
        chapterRepo.cancelTranslation(key.bookId, key.chapterId, handle.runId)
        return true
    }

    /** Web 伴读服务启停后重新计算翻译前台服务是否需要接管保活。 */
    fun onCompanionServiceStateChanged() {
        keepAlive.companionStateChanged()
    }

    private suspend fun runTask(
        key: TranslationTaskKey,
        run: Long,
        chapter: Chapter,
        settings: LlmSettings,
        sourceLanguage: String
    ) {
        var markedInProgress = false
        var networkActive = false
        var terminalEvent = false
        try {
            updateIfCurrent(key, run) {
                it.copy(phase = TranslationPhase.PREPARING, errorMessage = null)
            }

            when (TranslationPreflight.gate(chapter, settings)) {
                TranslationGate.MISSING_API_KEY -> {
                    finishIfCurrent(key, run, TranslationPhase.FAILED, "请先配置 API Key")
                    return
                }

                TranslationGate.TOO_LONG -> {
                    val msg = TranslationPreflight.tooLongMessage(chapter)
                    chapterRepo.markTooLong(key.bookId, key.chapterId, msg)
                    finishIfCurrent(key, run, TranslationPhase.FAILED, msg)
                    return
                }

                TranslationGate.NOTHING_TO_TRANSLATE -> {
                    if (chapterRepo.startTranslation(key.bookId, key.chapterId, run)) {
                        chapterRepo.completeTranslation(key.bookId, key.chapterId, run, "")
                    }
                    finishIfCurrent(key, run, TranslationPhase.IDLE)
                    return
                }

                TranslationGate.TRANSLATE -> Unit
            }

            if (!chapterRepo.startTranslation(key.bookId, key.chapterId, run)) {
                finishIfCurrent(key, run, TranslationPhase.IDLE)
                return
            }
            markedInProgress = true
            keepAlive.taskStarted(run)
            networkActive = true
            updateIfCurrent(key, run) {
                it.copy(
                    isStreaming = true,
                    phase = TranslationPhase.WAITING_FIRST_TOKEN,
                    streamingText = "",
                    thinkingText = "",
                    streamingCharCount = 0,
                    errorMessage = null
                )
            }

            translationService.translateStream(
                settings = settings,
                chapterTitle = chapter.title,
                chapterContent = chapter.content,
                sourceLanguage = sourceLanguage
            ).batchForDisplay().collect { event ->
                when (event) {
                    TranslationEvent.Started -> updateIfCurrent(key, run) {
                        it.copy(phase = TranslationPhase.WAITING_FIRST_TOKEN)
                    }
                    is TranslationEvent.Thinking -> updateIfCurrent(key, run) {
                        it.copy(
                            phase = TranslationPhase.THINKING,
                            thinkingText = it.thinkingText + event.text
                        )
                    }
                    is TranslationEvent.Chunk -> updateIfCurrent(key, run) {
                        it.copy(
                            phase = TranslationPhase.STREAMING,
                            streamingText = it.streamingText + event.text,
                            streamingCharCount = it.streamingCharCount + event.text.length
                        )
                    }
                    is TranslationEvent.Progress -> updateIfCurrent(key, run) {
                        it.copy(streamingCharCount = maxOf(it.streamingCharCount, event.chars))
                    }
                    is TranslationEvent.Done -> {
                        terminalEvent = true
                        if (chapterRepo.completeTranslation(key.bookId, key.chapterId, run, event.text)) {
                            finishIfCurrent(key, run, TranslationPhase.IDLE)
                        }
                    }
                    is TranslationEvent.Error -> {
                        terminalEvent = true
                        AppLog.put("翻译流错误：书 ${key.bookId} 章 ${key.chapterId} run $run：${event.reason}")
                        if (chapterRepo.failTranslation(key.bookId, key.chapterId, run, event.reason)) {
                            finishIfCurrent(key, run, TranslationPhase.FAILED, event.reason)
                        }
                    }
                }
            }
            if (!terminalEvent) {
                val reason = "翻译流未正常结束"
                if (chapterRepo.failTranslation(key.bookId, key.chapterId, run, reason)) {
                    finishIfCurrent(key, run, TranslationPhase.FAILED, reason)
                }
            }
        } catch (e: CancellationException) {
            if (markedInProgress && isCurrent(key, run)) {
                chapterRepo.cancelTranslation(key.bookId, key.chapterId, run)
                finishIfCurrent(key, run, TranslationPhase.CANCELLED)
            }
            throw e
        } catch (e: Exception) {
            if (markedInProgress) {
                val reason = e.message ?: "翻译失败"
                AppLog.put("翻译失败：书 ${key.bookId} 章 ${key.chapterId} run $run", e)
                if (chapterRepo.failTranslation(key.bookId, key.chapterId, run, reason)) {
                    finishIfCurrent(key, run, TranslationPhase.FAILED, reason)
                }
            } else {
                AppLog.put("翻译准备失败：书 ${key.bookId} 章 ${key.chapterId} run $run", e)
                finishIfCurrent(key, run, TranslationPhase.FAILED, e.message ?: "翻译失败")
            }
        } finally {
            if (networkActive) keepAlive.taskFinished(run)
            removeHandleIfCurrent(key, run)
        }
    }

    private fun isCurrent(key: TranslationTaskKey, run: Long): Boolean = synchronized(taskLock) {
        handles[key]?.runId == run
    }

    private inline fun updateIfCurrent(
        key: TranslationTaskKey,
        run: Long,
        update: (TranslationUiState) -> TranslationUiState
    ) {
        synchronized(taskLock) {
            if (handles[key]?.runId != run) return
            val current = stateMap[key] ?: TranslationUiState(key = key, runId = run)
            putState(update(current))
        }
    }

    /** 必须在 [taskLock] 内调用：只替换一个条目并发布不可变快照。 */
    private fun putState(state: TranslationUiState) {
        stateMap[state.key] = state
        publishStates()
    }

    private fun publishStates() {
        _states.value = stateMap.toMap()
    }

    private fun finishIfCurrent(
        key: TranslationTaskKey,
        run: Long,
        phase: TranslationPhase,
        errorMessage: String? = null
    ) {
        updateIfCurrent(key, run) {
            it.copy(
                streamingText = "",
                thinkingText = "",
                streamingCharCount = 0,
                isStreaming = false,
                phase = phase,
                errorMessage = errorMessage
            )
        }
    }

    /**
     * 任务收尾：摘除句柄，并清理运行期状态。
     *
     * FAILED 条目保留：`请先配置 API Key` 这类失败没有写库，删掉条目后 UI 就再也
     * 看不到原因。IDLE（已完成，译文在 Room）与取消（Room 已恢复 PENDING）不留条目，
     * 否则 Map 会随读过的章节数无界增长。
     */
    private fun removeHandleIfCurrent(key: TranslationTaskKey, run: Long) {
        synchronized(taskLock) {
            if (handles[key]?.runId != run) return
            handles.remove(key)
            if (stateMap[key]?.phase != TranslationPhase.FAILED) {
                stateMap.remove(key)
                publishStates()
            }
        }
    }
}

/** 所有并发翻译任务共用的前台保活所有权。 */
class TranslationKeepAlive(
    private val appContext: Context,
    private val companionRunning: () -> Boolean = { WebCompanionService.isRunning },
    private val startService: (Context) -> Unit = TranslationForegroundService::start,
    private val stopService: (Context) -> Unit = TranslationForegroundService::stop
) {
    private val lock = Any()
    private val activeRuns = mutableSetOf<Long>()
    private var serviceOwned = false

    fun taskStarted(runId: Long) = synchronized(lock) {
        activeRuns += runId
        reconcile()
    }

    fun taskFinished(runId: Long) = synchronized(lock) {
        activeRuns -= runId
        reconcile()
    }

    fun companionStateChanged() = synchronized(lock) {
        reconcile()
    }

    private fun reconcile() {
        val shouldOwn = activeRuns.isNotEmpty() && !companionRunning()
        when {
            shouldOwn && !serviceOwned -> {
                startService(appContext)
                serviceOwned = true
            }
            !shouldOwn && serviceOwned -> {
                stopService(appContext)
                serviceOwned = false
            }
        }
    }
}

/**
 * 进程级协调器：ReaderViewModel 与 Web 伴读共用任务集合，同章互斥、异章并行。
 */
object TranslationCoordinatorProvider {
    @Volatile
    private var instance: TranslationCoordinator? = null

    fun get(context: Context): TranslationCoordinator {
        context.applicationContext.let { app ->
            return instance ?: synchronized(this) {
                instance ?: create(app as VibeReadingApp).also { instance = it }
            }
        }
    }

    fun notifyCompanionServiceStateChanged() {
        instance?.onCompanionServiceStateChanged()
    }

    private fun create(app: VibeReadingApp): TranslationCoordinator = TranslationCoordinator(
        chapterRepo = ChapterRepository(app.database.chapterDao()),
        translationService = app.llmApiService,
        scope = app.appScope,
        appContext = app
    )
}
