package com.vibereading.app.ui.reader

import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.TranslationForegroundService
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
 */
class TranslationCoordinator(
    private val bookId: Long,
    private val chapterRepo: ChapterRepository,
    private val translationService: TranslationService,
    private val scope: CoroutineScope,
    private val appContext: Context
) {
    private val _state = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()

    private var translateJob: Job? = null
    private var runId = 0L
    private var runningChapterId: Long? = null

    /** 启动翻译；同一章已有活动任务则忽略。 */
    fun translate(chapter: Chapter, settings: LlmSettings) {
        if (runningChapterId == chapter.id && translateJob?.isActive == true) return
        val run = ++runId
        val chapterId = chapter.id
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

                if (!chapterRepo.startTranslation(bookId, chapterId, run)) {
                    // 章节不存在：无处写状态，直接结束
                    return@launch
                }
                markedInProgress = true
                // 启动前台服务：保持进程前台状态，避免按 Home 挂起时系统销毁 socket
                TranslationForegroundService.start(appContext)
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

                val prevEnglish = if (settings.enableContextBoost) loadContext(chapter, settings) else null
                translationService.translateStream(
                    settings = settings,
                    chapterTitle = chapter.title,
                    chapterContent = chapter.content,
                    prevChapterEnglish = prevEnglish
                ).collect { event ->
                    when (event) {
                        TranslationEvent.Started -> _state.update {
                            it.copy(phase = TranslationPhase.WAITING_FIRST_TOKEN)
                        }
                        is TranslationEvent.Thinking -> _state.update {
                            it.copy(
                                phase = TranslationPhase.THINKING,
                                thinkingText = it.thinkingText + event.text
                            )
                        }
                        is TranslationEvent.Chunk -> _state.update {
                            it.copy(
                                phase = TranslationPhase.STREAMING,
                                streamingText = it.streamingText + event.text,
                                streamingCharCount = it.streamingCharCount + event.text.length
                            )
                        }
                        is TranslationEvent.Progress -> _state.update {
                            it.copy(streamingCharCount = maxOf(it.streamingCharCount, event.chars))
                        }
                        is TranslationEvent.Done -> {
                            terminalEvent = true
                            if (chapterRepo.completeTranslation(bookId, chapterId, run, event.text)) {
                                // 仅当本任务仍是当前任务时清空流式状态；取消/替换后由新状态接管
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
                    runningChapterId = null
                    if (!terminalEvent) _state.update { it.copy(isStreaming = false) }
                    // 本任务仍是当前任务且已终止（完成/失败/取消）：停止前台服务。
                    // 若已被新任务替换（run != runId），服务由新任务接管，此处不停止。
                    if (markedInProgress) TranslationForegroundService.stop(appContext)
                }
            }
        }
    }

    /** 取消当前任务并恢复旧章节 PENDING（重译前调用）；等待旧任务完全结束。 */
    suspend fun cancelAndReset() {
        val oldChapterId = runningChapterId
        val oldJob = translateJob
        val oldRun = runId
        runId++
        runningChapterId = null
        translateJob = null
        _state.value = TranslationUiState()
        oldJob?.cancelAndJoin()
        if (oldChapterId != null && oldRun > 0) {
            chapterRepo.cancelTranslation(bookId, oldChapterId, oldRun)
        }
        // 显式取消：停止前台服务。若紧接着重译，translate() 会重新启动。
        TranslationForegroundService.stop(appContext)
    }

    private suspend fun loadContext(chapter: Chapter, settings: LlmSettings): String? {
        val budget = settings.contextMaxChars - chapter.content.length
        if (budget <= 0) return null
        val prevChapters = chapterRepo.getRecentDoneChapters(bookId, settings.contextChapters)
        if (prevChapters.isEmpty()) return null
        val combined = prevChapters.reversed().mapNotNull { it.translatedContent }.joinToString("\n\n")
        return if (combined.length > budget) translationService.truncateMiddle(combined, budget) else combined
    }
}
