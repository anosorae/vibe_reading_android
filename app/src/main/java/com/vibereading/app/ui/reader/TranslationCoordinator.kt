package com.vibereading.app.ui.reader

import com.vibereading.app.VibeReadingApp
import com.vibereading.app.data.remote.LlmApiService
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.domain.parser.ReadingContentParser
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.TranslationForegroundService
import com.vibereading.app.web.WebCompanionService
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TranslationPhase {
    IDLE,
    PREPARING,
    WAITING_FIRST_TOKEN,
    THINKING,
    STREAMING,
    FAILED,
    CANCELLED
}

/** 翻译任务的实时状态；chapterId 标记状态归属章节（供 UI 决定是否应用）。 */
data class TranslationUiState(
    val chapterId: Long? = null,
    val streamingText: String = "",
    val thinkingText: String = "",
    val streamingCharCount: Int = 0,
    val isStreaming: Boolean = false,
    val phase: TranslationPhase = TranslationPhase.IDLE,
    val errorMessage: String? = null
)

/**
 * 翻译状态机：单任务运行 + 数据库级 stale 防护。
 *
 * 每个任务持有自增 runId：开始翻译时写入 chapters.translationRunId，
 * 完成/失败/取消必须带同一 runId 才生效（见 ChapterDao 的 *TranslationRun）。
 * 即使内存代际判断被绕过，旧任务也无法把译文/错误写进新任务的章节状态。
 * 切换阅读章节不会取消正在运行的任务（后台任务合法完成），开启新任务才替换旧任务。
 *
 * 进程级单例（[TranslationCoordinatorProvider]）：ReaderViewModel 与 Web 伴读服务
 * 共用同一实例，App 内发起与 Web 发起天然互斥（ADR-005）。
 */
class TranslationCoordinator(
    private val chapterRepo: ChapterRepository,
    private val translationService: TranslationService,
    private val scope: CoroutineScope,
    private val appContext: Context
) {
    private val _state = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()

    private var translateJob: Job? = null
    private var runId = 0L
    private var runningBookId: Long? = null
    private var runningChapterId: Long? = null
    // 本任务是否启动了翻译前台服务（伴读服务活跃时不启动，也不代为停止）
    private var fgServiceStarted = false

    /** 当前正在翻译的章节 ID（可能在后台运行，UI 不在前台展示）。 */
    val currentRunningChapterId: Long? get() = runningChapterId

    /** 是否有翻译任务在运行（App 内与 Web 共用同一状态，ADR-005）。 */
    fun isBusy(): Boolean = translateJob?.isActive == true

    /** 启动翻译；同一章已有活动任务则忽略。[sourceLanguage] 为书籍原文语言（ADR-003），决定翻译方向。 */
    fun translate(
        bookId: Long,
        chapter: Chapter,
        settings: LlmSettings,
        sourceLanguage: String = SourceLanguageDetector.ZH
    ) {
        if (runningChapterId == chapter.id && translateJob?.isActive == true) return
        val run = ++runId
        val chapterId = chapter.id
        runningBookId = bookId
        runningChapterId = chapterId
        _state.value = TranslationUiState(chapterId = chapterId)
        translateJob = scope.launch {
            var markedInProgress = false
            var terminalEvent = false
            try {
                _state.update { it.copy(phase = TranslationPhase.PREPARING, errorMessage = null) }

                if (settings.apiKey.isBlank()) {
                    _state.update { it.copy(phase = TranslationPhase.FAILED, errorMessage = "请先配置 API Key") }
                    return@launch
                }
                if (chapter.content.length > settings.chapterMaxChars) {
                    val msg = "章节过长 (${chapter.content.length} 字符)"
                    chapterRepo.markTooLong(bookId, chapterId, msg)
                    _state.update { it.copy(phase = TranslationPhase.FAILED, errorMessage = msg) }
                    return@launch
                }

                // 纯插图/空章节（ADR-002）：没有可翻译的文本段（全部是空白或插图链接）
                // 时不调 API——空 prompt 会让模型输出「请提供文本」之类的无意义寒暄。
                // 直接走 start→complete 落 DONE，空译文读取侧回退原文（插图照常渲染）。
                val hasTranslatableText = ReadingContentParser.splitParagraphs(chapter.content)
                    .any { p ->
                        val trimmed = p.trim()
                        trimmed.isNotEmpty() && IllustrationLink.parse(trimmed) == null
                    }
                if (!hasTranslatableText) {
                    if (chapterRepo.startTranslation(bookId, chapterId, run)) {
                        chapterRepo.completeTranslation(bookId, chapterId, run, "")
                    }
                    _state.update { it.copy(isStreaming = false, phase = TranslationPhase.IDLE) }
                    return@launch
                }

                if (!chapterRepo.startTranslation(bookId, chapterId, run)) {
                    // 章节不存在：无处写状态，直接结束
                    return@launch
                }
                markedInProgress = true
                // 启动前台服务：保持进程前台状态，避免按 Home 挂起时系统销毁 socket。
                // Web 伴读服务活跃时其 WifiLock/WakeLock 已覆盖保活（ADR-005），跳过。
                if (WebCompanionService.isRunning) {
                    fgServiceStarted = false
                } else {
                    TranslationForegroundService.start(appContext)
                    fgServiceStarted = true
                }
                _state.update {
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
                ).collect { event ->
                    when (event) {
                        TranslationEvent.Started -> {
                            if (run == runId) _state.update { it.copy(phase = TranslationPhase.WAITING_FIRST_TOKEN) }
                        }
                        is TranslationEvent.Thinking -> {
                            if (run == runId) _state.update {
                                it.copy(
                                    phase = TranslationPhase.THINKING,
                                    thinkingText = it.thinkingText + event.text
                                )
                            }
                        }
                        is TranslationEvent.Chunk -> {
                            if (run == runId) _state.update {
                                it.copy(
                                    phase = TranslationPhase.STREAMING,
                                    streamingText = it.streamingText + event.text,
                                    streamingCharCount = it.streamingCharCount + event.text.length
                                )
                            }
                        }
                        is TranslationEvent.Progress -> {
                            if (run == runId) _state.update {
                                it.copy(streamingCharCount = maxOf(it.streamingCharCount, event.chars))
                            }
                        }
                        is TranslationEvent.Done -> {
                            terminalEvent = true
                            if (chapterRepo.completeTranslation(bookId, chapterId, run, event.text)) {
                                // 仅当本任务仍是当前任务时清空流式状态；取消/替换后由新状态接管。
                                // 旧任务仍完成写库（completeTranslation），但不写 UI 状态。
                                if (run == runId) {
                                    _state.update { it.copy(isStreaming = false, phase = TranslationPhase.IDLE, thinkingText = "", streamingCharCount = 0) }
                                }
                            }
                        }
                        is TranslationEvent.Error -> {
                            terminalEvent = true
                            AppLog.put("翻译流错误：书 $bookId 章 $chapterId run $run：${event.reason}")
                            if (chapterRepo.failTranslation(bookId, chapterId, run, event.reason)) {
                                if (run == runId) {
                                    _state.update {
                                        it.copy(
                                            isStreaming = false,
                                            phase = TranslationPhase.FAILED,
                                            thinkingText = "",
                                            streamingCharCount = 0,
                                            errorMessage = event.reason
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!terminalEvent) {
                    val reason = "翻译流未正常结束"
                    if (chapterRepo.failTranslation(bookId, chapterId, run, reason)) {
                        if (run == runId) {
                            _state.update {
                                it.copy(isStreaming = false, phase = TranslationPhase.FAILED, errorMessage = reason)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (run == runId && markedInProgress) {
                    chapterRepo.cancelTranslation(bookId, chapterId, run)
                    _state.update {
                        it.copy(isStreaming = false, phase = TranslationPhase.CANCELLED, thinkingText = "", streamingCharCount = 0)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (markedInProgress) {
                    val reason = e.message ?: "翻译失败"
                    AppLog.put("翻译失败：书 $bookId 章 $chapterId run $run", e)
                    if (chapterRepo.failTranslation(bookId, chapterId, run, reason)) {
                        if (run == runId) {
                            _state.update {
                                it.copy(isStreaming = false, phase = TranslationPhase.FAILED, streamingCharCount = 0, errorMessage = reason)
                            }
                        }
                    }
                }
            } finally {
                if (run == runId) {
                    runningBookId = null
                    runningChapterId = null
                    if (!terminalEvent) _state.update { it.copy(isStreaming = false) }
                    // 本任务仍是当前任务且已终止（完成/失败/取消）：停止前台服务。
                    // 若已被新任务替换（run != runId），服务由新任务接管，此处不停止。
                    if (markedInProgress && fgServiceStarted) TranslationForegroundService.stop(appContext)
                    fgServiceStarted = false
                }
            }
        }
    }

    /** 取消当前任务并恢复旧章节 PENDING（重译前调用）；等待旧任务完全结束。 */
    suspend fun cancelAndReset() {
        val oldBookId = runningBookId
        val oldChapterId = runningChapterId
        val oldJob = translateJob
        val oldRun = runId
        runId++
        runningBookId = null
        runningChapterId = null
        translateJob = null
        _state.value = TranslationUiState()
        oldJob?.cancelAndJoin()
        if (oldBookId != null && oldChapterId != null && oldRun > 0) {
            chapterRepo.cancelTranslation(oldBookId, oldChapterId, oldRun)
        }
        // 显式取消：停止前台服务。若紧接着重译，translate() 会重新启动。
        // 伴读活跃期间未持有翻译前台服务（fgServiceStarted=false），不误停伴读服务。
        if (fgServiceStarted) TranslationForegroundService.stop(appContext)
        fgServiceStarted = false
    }
}

/**
 * 翻译协调器的进程级单一实例（ADR-005）：ReaderViewModel 与 Web 伴读服务共用，
 * 单任务状态机全局生效。惰性构造，依赖 [VibeReadingApp] 的数据库与 appScope。
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

    private fun create(app: VibeReadingApp): TranslationCoordinator = TranslationCoordinator(
        chapterRepo = ChapterRepository(app.database.chapterDao()),
        translationService = LlmApiService(),
        scope = app.appScope,
        appContext = app
    )
}
