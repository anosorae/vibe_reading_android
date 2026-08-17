package com.vibereading.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.remote.LlmApiService
import com.vibereading.app.data.remote.TranslationEvent
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ReadingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vibereading.app.domain.model.ReadingPosition

enum class TranslationPhase {
    IDLE,
    PREPARING,
    WAITING_FIRST_TOKEN,
    THINKING,
    STREAMING,
    FAILED,
    CANCELLED
}

data class ReaderUiState(
    val bookTitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val activeChapter: Chapter? = null,
    val activeChapterId: Long? = null,
    val position: ReadingPosition? = null,
    val restoreReady: Boolean = false,
    val streamingText: String = "",
    val thinkingText: String = "",
    val streamingCharCount: Int = 0,
    val isStreaming: Boolean = false,
    val translationPhase: TranslationPhase = TranslationPhase.IDLE,
    val mode: String = "zh",          // "zh" or "en"
    val readingSettings: ReadingSettings = ReadingSettings(),
    val llmSettings: LlmSettings = LlmSettings(),
    val catalogVisible: Boolean = false,
    val toolbarVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    val llmSettingsVisible: Boolean = false,
    val nightMode: Boolean = false,
    val errorMessage: String? = null,
    val llmTestResult: String? = null,
    val llmTestSuccess: Boolean? = null
)

class ReaderViewModel(
    private val bookId: Long,
    private val bookRepo: BookRepository,
    private val chapterRepo: ChapterRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val llmService = LlmApiService()
    private var translateJob: Job? = null
    private var translationGeneration = 0L
    private var translationChapterId: Long? = null
    private var llmEditDirty = false
    private val progressMutex = Mutex()
    private var pendingPosition: ReadingPosition? = null
    private var restoreCompleted = false

    init {
        viewModelScope.launch {
            val book = bookRepo.getBookByIdOnce(bookId) ?: return@launch
            val savedPosition = ReadingPosition(book.lastReadChapterId, book.lastReadOffset)
            chapterRepo.getChaptersByBook(bookId).collect { chapters ->
                _uiState.update { it.copy(bookTitle = book.title, chapters = chapters) }
                if (!restoreCompleted && chapters.isNotEmpty()) {
                    val chapter = chapters.firstOrNull { it.id == savedPosition.chapterId } ?: chapters.first()
                    val offset = if (chapter.id == savedPosition.chapterId) {
                        savedPosition.offset.coerceIn(0, chapter.content.length)
                    } else 0
                    val position = ReadingPosition(chapter.id, offset)
                    restoreCompleted = true
                    _uiState.update {
                        it.copy(
                            activeChapterId = chapter.id,
                            activeChapter = chapter,
                            position = position,
                            restoreReady = true,
                            streamingText = "",
                            thinkingText = "",
                            isStreaming = false,
                            translationPhase = TranslationPhase.IDLE,
                            errorMessage = null
                        )
                    }
                    if (_uiState.value.mode == "en") maybeTranslateChapter(chapter.id)
                } else if (restoreCompleted) {
                    val current = _uiState.value.activeChapterId
                    val updated = chapters.find { it.id == current }
                    if (updated != null) _uiState.update { it.copy(activeChapter = updated) }
                }
            }
        }

        // Load settings
        viewModelScope.launch {
            settingsRepo.readingMode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
                if (mode == "en") {
                    _uiState.value.activeChapterId?.let { maybeTranslateChapter(it) }
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.readingSettings.collect { rs ->
                _uiState.update { it.copy(readingSettings = rs) }
            }
        }
        viewModelScope.launch {
            settingsRepo.nightMode.collect { night ->
                _uiState.update { it.copy(nightMode = night) }
            }
        }
        viewModelScope.launch {
            settingsRepo.llmSettings.collect { ls ->
                _uiState.update { it.copy(llmSettings = ls) }
                if (!llmEditDirty) {
                    _editApiKey.value = ls.apiKey
                    _editApiBase.value = ls.apiBase
                    _editModel.value = ls.model
                }
            }
        }
    }

    /** 用户主动跳转；分页位置由当前排版器根据 offset 派生。 */
    fun navigateTo(chapterId: Long, offset: Int = 0, persist: Boolean = true) {
        viewModelScope.launch {
            val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
            val position = ReadingPosition(chapterId, offset.coerceIn(0, chapter.content.length))
            _uiState.update {
                it.copy(
                    activeChapterId = chapterId,
                    activeChapter = chapter,
                    position = position,
                    restoreReady = true,
                    streamingText = "",
                    thinkingText = "",
                    isStreaming = false,
                    translationPhase = TranslationPhase.IDLE,
                    errorMessage = null
                )
            }
            if (persist) enqueueProgress(position)
            if (_uiState.value.mode == "en") maybeTranslateChapter(chapterId)
        }
    }

    /** 统一记录当前内容位置；分页和滚动都调用同一个入口。 */
    fun updateProgress(chapterId: Long, offset: Int) {
        val chapter = _uiState.value.chapters.firstOrNull { it.id == chapterId } ?: return
        val position = ReadingPosition(chapterId, offset.coerceIn(0, chapter.content.length))
        if (position == _uiState.value.position) return
        _uiState.update { it.copy(position = position, activeChapterId = chapterId, activeChapter = chapter) }
        enqueueProgress(position)
    }

    private fun enqueueProgress(position: ReadingPosition) {
        pendingPosition = position
        viewModelScope.launch {
            progressMutex.withLock {
                val latest = pendingPosition ?: return@withLock
                pendingPosition = null
                if (latest.chapterId != null) {
                    bookRepo.updateLastReadProgress(bookId, latest.chapterId, latest.offset)
                }
            }
        }
    }

    suspend fun flushProgress() {
        progressMutex.withLock {
            val latest = pendingPosition ?: _uiState.value.position ?: return
            pendingPosition = null
            latest.chapterId?.let { bookRepo.updateLastReadProgress(bookId, it, latest.offset) }
        }
    }

    fun nextChapter() {
        val current = _uiState.value.activeChapter ?: return
        val chapters = _uiState.value.chapters
        val idx = chapters.indexOfFirst { it.id == current.id }
        if (idx >= 0 && idx < chapters.size - 1) {
            navigateTo(chapters[idx + 1].id, 0)
        }
    }

    fun prevChapter() {
        val current = _uiState.value.activeChapter ?: return
        val chapters = _uiState.value.chapters
        val idx = chapters.indexOfFirst { it.id == current.id }
        if (idx > 0) {
            val previous = chapters[idx - 1]
            navigateTo(previous.id, previous.content.length)
        }
    }

    fun switchMode(mode: String) {
        _uiState.update { it.copy(mode = mode) }
        viewModelScope.launch { settingsRepo.saveReadingMode(mode) }
        if (mode == "en") {
            _uiState.value.activeChapterId?.let { maybeTranslateChapter(it) }
        }
    }

    fun toggleToolbar() {
        _uiState.update { it.copy(toolbarVisible = !it.toolbarVisible) }
    }

    fun toggleCatalog() {
        _uiState.update { it.copy(catalogVisible = !it.catalogVisible) }
    }

    fun dismissCatalog() {
        _uiState.update { it.copy(catalogVisible = false) }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(settingsVisible = !it.settingsVisible) }
    }

    fun dismissSettings() {
        _uiState.update { it.copy(settingsVisible = false) }
    }

    fun toggleLlmSettings() {
        _uiState.update { it.copy(llmSettingsVisible = !it.llmSettingsVisible) }
    }

    fun dismissLlmSettings() {
        _uiState.update { it.copy(llmSettingsVisible = false) }
    }

    fun dismissAllOverlays() {
        _uiState.update { it.copy(toolbarVisible = false, catalogVisible = false, settingsVisible = false, llmSettingsVisible = false) }
    }

    // ── Reading style updates (persist immediately via DataStore) ──

    fun updateReadingSettings(transform: (ReadingSettings) -> ReadingSettings) {
        val new = transform(_uiState.value.readingSettings)
        _uiState.update { it.copy(readingSettings = new) }
        viewModelScope.launch { settingsRepo.saveReadingSettings(new) }
    }

    fun toggleNightMode() {
        val new = !_uiState.value.nightMode
        _uiState.update { it.copy(nightMode = new) }
        viewModelScope.launch { settingsRepo.saveNightMode(new) }
    }

    // ── LLM settings (翻译设置面板) ──

    private val _editApiKey = MutableStateFlow("")
    private val _editApiBase = MutableStateFlow("")
    private val _editModel = MutableStateFlow("")
    val editApiKey: StateFlow<String> = _editApiKey.asStateFlow()
    val editApiBase: StateFlow<String> = _editApiBase.asStateFlow()
    val editModel: StateFlow<String> = _editModel.asStateFlow()

    /** 打开面板时从最新持久化值填充；已有草稿则保持不变。 */
    fun initLlmEditFields() {
        if (llmEditDirty) return
        val ls = _uiState.value.llmSettings
        _editApiKey.value = ls.apiKey
        _editApiBase.value = ls.apiBase
        _editModel.value = ls.model
    }

    fun updateEditApiKey(key: String) {
        llmEditDirty = true
        _editApiKey.value = key
    }

    fun updateEditApiBase(base: String) {
        llmEditDirty = true
        _editApiBase.value = base
    }

    fun updateEditModel(model: String) {
        llmEditDirty = true
        _editModel.value = model
    }

    fun updateLlmChapterMaxChars(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(chapterMaxChars = value)) }
    }
    fun updateLlmContextBoost(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableContextBoost = enabled)) }
    }
    fun updateLlmContextChapters(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(contextChapters = value.coerceIn(1, 3))) }
    }
    fun updateLlmContextMaxChars(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(contextMaxChars = value)) }
    }
    fun updateLlmThinking(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableThinking = enabled)) }
    }

    private fun currentEditedLlmSettings(): LlmSettings =
        _uiState.value.llmSettings.copy(
            apiKey = _editApiKey.value.trim(),
            apiBase = _editApiBase.value.trim(),
            model = _editModel.value.trim()
        )

    fun saveLlmSettings() {
        viewModelScope.launch {
            val newSettings = currentEditedLlmSettings()
            try {
                _uiState.update { it.copy(llmSettings = newSettings, llmTestResult = null, llmTestSuccess = null) }
                settingsRepo.saveLlmSettings(newSettings)
                llmEditDirty = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(llmTestResult = e.message ?: "保存翻译设置失败", llmTestSuccess = false) }
            }
        }
    }

    fun testLlmConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(llmTestResult = null, llmTestSuccess = null) }
            val newSettings = currentEditedLlmSettings()
            try {
                _uiState.update { it.copy(llmSettings = newSettings) }
                settingsRepo.saveLlmSettings(newSettings)
                val result = llmService.testConnection(newSettings)
                _uiState.update {
                    it.copy(
                        llmTestResult = result.getOrNull() ?: result.exceptionOrNull()?.message,
                        llmTestSuccess = result.isSuccess
                    )
                }
                if (result.isSuccess) llmEditDirty = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(llmTestResult = e.message ?: "测试连接失败", llmTestSuccess = false)
                }
            }
        }
    }

    private fun maybeTranslateChapter(chapterId: Long) {
        val chapter = _uiState.value.chapters.find { it.id == chapterId } ?: return
        val settings = _uiState.value.llmSettings
        if (settings.apiKey.isBlank()) return
        when (chapter.status) {
            Chapter.STATUS_PENDING, Chapter.STATUS_FAILED -> translateChapter(chapterId)
        }
    }

    fun retryTranslation(chapterId: Long) {
        viewModelScope.launch {
            cancelTranslation()
            translateChapter(chapterId)
        }
    }

    private fun isCurrentTranslation(generation: Long, chapterId: Long): Boolean =
        generation == translationGeneration && _uiState.value.activeChapterId == chapterId

    private suspend fun cancelTranslation(restoreStatus: Boolean = true) {
        val oldChapterId = translationChapterId
        val oldJob = translateJob
        translationGeneration++
        translateJob = null
        translationChapterId = null
        oldJob?.cancelAndJoin()
        if (restoreStatus && oldChapterId != null) {
            chapterRepo.updateStatus(oldChapterId, Chapter.STATUS_PENDING)
        }
    }

    private fun cancelTranslationImmediately() {
        translationGeneration++
        translateJob?.cancel()
        translateJob = null
        translationChapterId = null
    }

    private fun translateChapter(chapterId: Long) {
        if (translationChapterId == chapterId && translateJob?.isActive == true) return
        val generation = ++translationGeneration
        translationChapterId = chapterId
        translateJob = viewModelScope.launch {
            var markedInProgress = false
            var terminalEvent = false
            try {
                if (isCurrentTranslation(generation, chapterId)) {
                    _uiState.update { it.copy(translationPhase = TranslationPhase.PREPARING, errorMessage = null) }
                }
                val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
                val settings = _uiState.value.llmSettings

                if (settings.apiKey.isBlank()) {
                    if (isCurrentTranslation(generation, chapterId)) {
                        _uiState.update { it.copy(translationPhase = TranslationPhase.FAILED, errorMessage = "请先配置 API Key") }
                    }
                    return@launch
                }

                if (chapter.content.length > settings.chapterMaxChars) {
                    val msg = "章节过长 (${chapter.content.length} 字符)"
                    chapterRepo.updateStatusWithError(chapterId, Chapter.STATUS_TOO_LONG, msg)
                    if (isCurrentTranslation(generation, chapterId)) {
                        _uiState.update { it.copy(translationPhase = TranslationPhase.FAILED, errorMessage = msg) }
                    }
                    return@launch
                }

                chapterRepo.updateStatus(chapterId, Chapter.STATUS_IN_PROGRESS)
                markedInProgress = true
                if (isCurrentTranslation(generation, chapterId)) {
                    _uiState.update {
                        it.copy(
                            isStreaming = true,
                            translationPhase = TranslationPhase.WAITING_FIRST_TOKEN,
                            streamingText = "",
                            thinkingText = "",
                            streamingCharCount = 0,
                            errorMessage = null
                        )
                    }
                }

                val prevEnglish = if (settings.enableContextBoost) loadContext(chapter, settings) else null
                llmService.translateStream(
                    settings = settings,
                    chapterTitle = chapter.title,
                    chapterContent = chapter.content,
                    prevChapterEnglish = prevEnglish
                ).collect { event ->
                    val isCurrent = isCurrentTranslation(generation, chapterId)
                    when (event) {
                        TranslationEvent.Started -> if (isCurrent) _uiState.update {
                            it.copy(translationPhase = TranslationPhase.WAITING_FIRST_TOKEN)
                        }
                        is TranslationEvent.Thinking -> if (isCurrent) _uiState.update {
                            it.copy(
                                translationPhase = TranslationPhase.THINKING,
                                thinkingText = it.thinkingText + event.text
                            )
                        }
                        is TranslationEvent.Chunk -> if (isCurrent) _uiState.update {
                            it.copy(
                                translationPhase = TranslationPhase.STREAMING,
                                streamingText = it.streamingText + event.text,
                                streamingCharCount = it.streamingCharCount + event.text.length
                            )
                        }
                        is TranslationEvent.Progress -> if (isCurrent) _uiState.update {
                            it.copy(streamingCharCount = maxOf(it.streamingCharCount, event.chars))
                        }
                        is TranslationEvent.Done -> {
                            terminalEvent = true
                            chapterRepo.updateTranslation(chapterId, event.text, Chapter.STATUS_DONE)
                            chapterRepo.updateStatusWithError(chapterId, Chapter.STATUS_DONE, null)
                            val doneCount = chapterRepo.getDoneCount(bookId)
                            bookRepo.updateTranslatedCount(bookId, doneCount)
                            if (isCurrent) _uiState.update {
                                it.copy(
                                    isStreaming = false,
                                    translationPhase = TranslationPhase.IDLE,
                                    thinkingText = "",
                                    streamingCharCount = 0
                                )
                            }
                        }
                        is TranslationEvent.Error -> {
                            terminalEvent = true
                            chapterRepo.updateStatusWithError(chapterId, Chapter.STATUS_FAILED, event.reason)
                            if (isCurrent) _uiState.update {
                                it.copy(
                                    isStreaming = false,
                                    translationPhase = TranslationPhase.FAILED,
                                    thinkingText = "",
                                    streamingCharCount = 0,
                                    errorMessage = event.reason
                                )
                            }
                        }
                    }
                }
                if (!terminalEvent) {
                    val reason = "翻译流未正常结束"
                    chapterRepo.updateStatusWithError(chapterId, Chapter.STATUS_FAILED, reason)
                    if (isCurrentTranslation(generation, chapterId)) {
                        _uiState.update {
                            it.copy(isStreaming = false, translationPhase = TranslationPhase.FAILED, errorMessage = reason)
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (generation == translationGeneration && markedInProgress) {
                    chapterRepo.updateStatus(chapterId, Chapter.STATUS_PENDING)
                    if (_uiState.value.activeChapterId == chapterId) {
                        _uiState.update {
                            it.copy(isStreaming = false, translationPhase = TranslationPhase.CANCELLED, thinkingText = "", streamingCharCount = 0)
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                if (markedInProgress) {
                    val reason = e.message ?: "翻译失败"
                    chapterRepo.updateStatusWithError(chapterId, Chapter.STATUS_FAILED, reason)
                    if (isCurrentTranslation(generation, chapterId)) {
                        _uiState.update {
                            it.copy(isStreaming = false, translationPhase = TranslationPhase.FAILED, streamingCharCount = 0, errorMessage = reason)
                        }
                    }
                }
            } finally {
                if (generation == translationGeneration) {
                    translationChapterId = null
                    if (!terminalEvent && _uiState.value.activeChapterId == chapterId) {
                        _uiState.update { it.copy(isStreaming = false) }
                    }
                }
            }
        }
    }

    private suspend fun loadContext(chapter: Chapter, settings: LlmSettings): String? {
        val budget = settings.contextMaxChars - chapter.content.length
        if (budget <= 0) return null
        val prevChapters = chapterRepo.getRecentDoneChapters(bookId, settings.contextChapters)
        if (prevChapters.isEmpty()) return null
        val combined = prevChapters.reversed().mapNotNull { it.translatedContent }.joinToString("\n\n")
        return if (combined.length > budget) llmService.truncateMiddle(combined, budget) else combined
    }

    override fun onCleared() {
        cancelTranslationImmediately()
        super.onCleared()
    }

    class Factory(
        private val bookId: Long,
        private val bookRepo: BookRepository,
        private val chapterRepo: ChapterRepository,
        private val settingsRepo: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(bookId, bookRepo, chapterRepo, settingsRepo) as T
        }
    }
}
