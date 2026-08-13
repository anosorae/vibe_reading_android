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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ReaderUiState(
    val bookTitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val activeChapter: Chapter? = null,
    val activeChapterId: Long? = null,
    val lastReadPage: Int = 0,          // 分页模式：上次阅读的「章内页」索引（滚动模式恒 0）
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val mode: String = "zh",          // "zh" or "en"
    val readingSettings: ReadingSettings = ReadingSettings(),
    val llmSettings: LlmSettings = LlmSettings(),
    val catalogVisible: Boolean = false,
    val toolbarVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    val nightMode: Boolean = false,
    val errorMessage: String? = null
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

    init {
        // Load book info
        viewModelScope.launch {
            val book = bookRepo.getBookByIdOnce(bookId) ?: return@launch
            _uiState.update { it.copy(bookTitle = book.title, lastReadPage = book.lastReadPage) }
        }

        // Load chapters
        viewModelScope.launch {
            chapterRepo.getChaptersByBook(bookId).collect { chapters ->
                _uiState.update { it.copy(chapters = chapters) }
                // Restore last read chapter
                val current = _uiState.value.activeChapterId
                if (current == null) {
                    val book = bookRepo.getBookByIdOnce(bookId)
                    val lastRead = book?.lastReadChapterId
                    if (lastRead != null) {
                        navigateTo(lastRead)
                    } else if (chapters.isNotEmpty()) {
                        navigateTo(chapters.first().id)
                    }
                } else {
                    // Refresh active chapter data
                    val updated = chapters.find { it.id == current }
                    if (updated != null) {
                        _uiState.update { it.copy(activeChapter = updated) }
                    }
                }
            }
        }

        // Load settings
        viewModelScope.launch {
            settingsRepo.readingMode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
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
            }
        }
    }

    /** 跳转/恢复：分页模式可带「章内页」；目录跳章/翻译触发 page 恒 0（章首页）。 */
    fun navigateTo(chapterId: Long, page: Int = 0) {
        viewModelScope.launch {
            val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
            _uiState.update {
                it.copy(
                    activeChapterId = chapterId,
                    activeChapter = chapter,
                    lastReadPage = page,
                    streamingText = "",
                    isStreaming = false,
                    errorMessage = null
                )
            }
            // Save reading progress
            bookRepo.updateLastReadProgress(bookId, chapterId, page)
            // Auto-translate if in EN mode
            if (_uiState.value.mode == "en") {
                maybeTranslateChapter(chapterId)
            }
        }
    }

    /** 分页模式：翻页时保存「章 + 章内页」进度（滚动模式不用，page 恒 0）。 */
    fun updateProgress(page: Int) {
        val chapterId = _uiState.value.activeChapterId ?: return
        if (page == _uiState.value.lastReadPage) return
        _uiState.update { it.copy(lastReadPage = page) }
        viewModelScope.launch { bookRepo.updateLastReadProgress(bookId, chapterId, page) }
    }

    fun nextChapter() {
        val current = _uiState.value.activeChapter ?: return
        val chapters = _uiState.value.chapters
        val idx = chapters.indexOfFirst { it.id == current.id }
        if (idx >= 0 && idx < chapters.size - 1) {
            navigateTo(chapters[idx + 1].id)
        }
    }

    fun prevChapter() {
        val current = _uiState.value.activeChapter ?: return
        val chapters = _uiState.value.chapters
        val idx = chapters.indexOfFirst { it.id == current.id }
        if (idx > 0) {
            navigateTo(chapters[idx - 1].id)
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

    private fun maybeTranslateChapter(chapterId: Long) {
        val chapter = _uiState.value.chapters.find { it.id == chapterId } ?: return
        val settings = _uiState.value.llmSettings
        if (settings.apiKey.isBlank()) return
        when (chapter.status) {
            Chapter.STATUS_PENDING, Chapter.STATUS_FAILED -> {
                translateChapter(chapterId)
            }
        }
    }

    fun retryTranslation(chapterId: Long) {
        viewModelScope.launch {
            chapterRepo.resetChapter(chapterId)
            translateChapter(chapterId)
        }
    }

    private fun translateChapter(chapterId: Long) {
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            val chapter = chapterRepo.getChapterById(chapterId) ?: return@launch
            val settings = _uiState.value.llmSettings

            if (settings.apiKey.isBlank()) {
                _uiState.update { it.copy(errorMessage = "请先配置 API Key") }
                return@launch
            }

            if (chapter.content.length > settings.chapterMaxChars) {
                chapterRepo.updateStatus(chapterId, Chapter.STATUS_TOO_LONG)
                _uiState.update { it.copy(errorMessage = "章节过长 (${chapter.content.length} 字符)") }
                return@launch
            }

            // Mark as in progress
            chapterRepo.updateStatus(chapterId, Chapter.STATUS_IN_PROGRESS)
            _uiState.update { it.copy(isStreaming = true, streamingText = "", errorMessage = null) }

            // Load context
            val prevEnglish = if (settings.enableContextBoost) {
                loadContext(chapter, settings)
            } else null

            llmService.translateStream(
                settings = settings,
                chapterTitle = chapter.title,
                chapterContent = chapter.content,
                prevChapterEnglish = prevEnglish
            ).collect { event ->
                when (event) {
                    is TranslationEvent.Chunk -> {
                        _uiState.update { it.copy(streamingText = it.streamingText + event.text) }
                    }
                    is TranslationEvent.Done -> {
                        chapterRepo.updateTranslation(chapterId, event.text, Chapter.STATUS_DONE)
                        val doneCount = chapterRepo.getDoneCount(bookId)
                        bookRepo.updateTranslatedCount(bookId, doneCount)
                        _uiState.update { it.copy(isStreaming = false) }
                    }
                    is TranslationEvent.Error -> {
                        chapterRepo.updateStatus(chapterId, Chapter.STATUS_FAILED)
                        _uiState.update { it.copy(isStreaming = false, errorMessage = event.reason) }
                    }
                    is TranslationEvent.Progress -> {
                        // Could update a progress indicator
                    }
                    is TranslationEvent.Status -> {
                        // Status event — not used in streaming
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

        val combined = prevChapters.reversed()
            .mapNotNull { it.translatedContent }
            .joinToString("\n\n")

        return if (combined.length > budget) {
            llmService.truncateMiddle(combined, budget)
        } else {
            combined
        }
    }

    override fun onCleared() {
        super.onCleared()
        translateJob?.cancel()
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
