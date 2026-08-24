package com.vibereading.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.vibereading.app.data.dict.DictDatabase
import com.vibereading.app.data.remote.LlmApiService
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.DictEntry
import com.vibereading.app.domain.model.WordExplanation
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.domain.model.ReadingPosition
import com.vibereading.app.domain.model.toLlmProfile
import com.vibereading.app.domain.model.toLlmSettings
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val profiles: List<LlmProfile> = emptyList(),
    val activeProfileId: Long? = null,
    val catalogVisible: Boolean = false,
    val toolbarVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    val llmSettingsVisible: Boolean = false,
    val nightMode: Boolean = false,
    val errorMessage: String? = null,
    val editingProfileId: Long? = null,     // 非空 = 翻译设置面板中正在编辑某个配置
    val llmTestResult: String? = null,
    val llmTestSuccess: Boolean? = null,
    val dictQueryWord: String? = null, // 非空 = 词典弹窗可见
    val dictEntry: DictEntry? = null,
    val dictLoading: Boolean = false,
    val explainWord: String? = null,   // 非空 = 解释弹窗可见
    val explainResult: WordExplanation? = null,
    val explainLoading: Boolean = false,
    val explainError: String? = null
)

class ReaderViewModel(
    private val bookId: Long,
    private val bookRepo: BookRepository,
    private val chapterRepo: ChapterRepository,
    private val settingsRepo: SettingsRepository,
    private val llmProfileRepo: LlmProfileRepository,
    private val translationService: TranslationService,
    private val dictDatabase: DictDatabase? = null,
    private val llmApiService: LlmApiService? = null,
    appScope: CoroutineScope,
    appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** 当前阅读章节 ID 的独立流；combine 协调器状态时用它做 active 判定，
     *  避免 navigateTo 改 activeChapterId 但协调器 _state 未变时桥接不重评估。 */
    private val activeChapterIdFlow = MutableStateFlow<Long?>(null)

    // 翻译在 appScope 运行：按 Home 挂起或退出阅读器后仍可继续，直到完成/失败/被新任务替换
    private val translationCoordinator = TranslationCoordinator(
        bookId = bookId,
        chapterRepo = chapterRepo,
        translationService = translationService,
        scope = appScope,
        appContext = appContext
    )
    private var llmEditDirty = false
    private val progressMutex = Mutex()
    private var pendingPosition: ReadingPosition? = null
    private var restoreCompleted = false

    // ── LLM 编辑字段（必须在 init 之前声明，因为 llmSettings.collect 会写这些字段） ──
    private val _editApiKey = MutableStateFlow("")
    private val _editApiBase = MutableStateFlow("")
    private val _editModel = MutableStateFlow("")
    val editApiKey: StateFlow<String> = _editApiKey.asStateFlow()
    val editApiBase: StateFlow<String> = _editApiBase.asStateFlow()
    val editModel: StateFlow<String> = _editModel.asStateFlow()

    init {
        viewModelScope.launch {
            val book = bookRepo.getBookByIdOnce(bookId) ?: return@launch
            val savedPosition = ReadingPosition(book.lastReadChapterId, book.lastReadOffset)
            _uiState.update { it.copy(mode = book.languageMode) } // 阅读模式按书绑定，默认中文
            chapterRepo.getChaptersByBook(bookId).collect { chapters ->
                _uiState.update { it.copy(bookTitle = book.title, chapters = chapters) }
                if (!restoreCompleted && chapters.isNotEmpty()) {
                    val chapter = chapters.firstOrNull { it.id == savedPosition.chapterId } ?: chapters.first()
                    val position = if (chapter.id == savedPosition.chapterId) {
                        savedPosition.normalized(chapter.content.length).copy(chapterId = chapter.id)
                    } else {
                        ReadingPosition(chapter.id, 0)
                    }
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
                    activeChapterIdFlow.value = chapter.id
                    if (_uiState.value.mode == "en") maybeTranslateChapter(chapter.id)
                    prefetchNextChapterIfNeeded()
                } else if (restoreCompleted) {
                    val current = _uiState.value.activeChapterId
                    val updated = chapters.find { it.id == current }
                    if (updated != null) _uiState.update { it.copy(activeChapter = updated) }
                }
            }
        }

        // 翻译协调器状态 → UI 状态。
        // 用 combine 而非独立 collect：当 activeChapterId 变化（用户切回正在后台翻译的章节）
        // 但协调器 _state 未变时，桥接也会重新评估并重新应用该章节的流式状态，
        // 避免切回运行中章节时 UI 卡在 isStreaming=false 显示“翻译中…”。
        viewModelScope.launch {
            combine(activeChapterIdFlow, translationCoordinator.state) { activeId, ts ->
                activeId to ts
            }.collect { (activeId, ts) ->
                val isActive = ts.chapterId == activeId && activeId != null
                _uiState.update { ui ->
                    ui.copy(
                        streamingText = if (isActive) ts.streamingText else ui.streamingText,
                        thinkingText = if (isActive) ts.thinkingText else ui.thinkingText,
                        streamingCharCount = if (isActive) ts.streamingCharCount else ui.streamingCharCount,
                        // 协调器在译「非当前章」（如预译下一章）时，当前章未在放流：
                        // 置 false，避免当前章的进度弹窗因状态合并（conflation）卡住不关闭。
                        isStreaming = if (isActive) ts.isStreaming else false,
                        translationPhase = if (isActive) ts.phase else ui.translationPhase,
                        errorMessage = if (isActive) ts.errorMessage else ui.errorMessage
                    )
                }
            }
        }

        // 任一翻译任务结束后（完成/失败/取消），尝试预译下一章：
        // 若当前章翻译在忙被跳过，等它结束后在这里补上预译。
        viewModelScope.launch {
            translationCoordinator.state
                .drop(1) // 跳过初始 IDLE 态
                .collect { ts ->
                    if (!ts.isStreaming && ts.phase != TranslationPhase.PREPARING) {
                        prefetchNextChapterIfNeeded()
                    }
                }
        }

        // Load settings
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
        // LLM 设置从 llmProfileRepo 读取（替代原 settingsRepo.llmSettings）
        viewModelScope.launch {
            llmProfileRepo.activeLlmSettings.collect { ls ->
                _uiState.update { it.copy(llmSettings = ls) }
                if (!llmEditDirty) {
                    _editApiKey.value = ls.apiKey
                    _editApiBase.value = ls.apiBase
                    _editModel.value = ls.model
                }
                // 配置变更后重新评估当前章节是否需要翻译
                if (_uiState.value.mode == "en") {
                    _uiState.value.activeChapterId?.let { maybeTranslateChapter(it) }
                }
            }
        }
        viewModelScope.launch {
            llmProfileRepo.profiles.collect { list ->
                _uiState.update { it.copy(profiles = list) }
            }
        }
        viewModelScope.launch {
            llmProfileRepo.activeProfile.collect { profile ->
                _uiState.update { it.copy(activeProfileId = profile?.id) }
            }
        }
    }

    /** 用户主动跳转；分页位置由当前排版器根据 offset 派生。 */
    fun navigateTo(chapterId: Long, offset: Int = 0, persist: Boolean = true) {
        viewModelScope.launch {
            val chapter = chapterRepo.getChapterById(bookId, chapterId) ?: return@launch
            val position = ReadingPosition(chapterId, offset.coerceIn(0, chapter.content.length))
            // 若目标章节已有后台翻译在运行，不重置流式状态，让 combine 桥接重新应用协调器状态；
            // 否则清空，准备开始新翻译或展示已完成译文
            val running = translationCoordinator.currentRunningChapterId == chapterId &&
                translationCoordinator.state.value.isStreaming
            _uiState.update {
                if (running) {
                    it.copy(
                        activeChapterId = chapterId,
                        activeChapter = chapter,
                        position = position,
                        restoreReady = true
                    )
                } else {
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
            }
            activeChapterIdFlow.value = chapterId
            if (persist) enqueueProgress(position)
            if (_uiState.value.mode == "en") maybeTranslateChapter(chapterId)
            prefetchNextChapterIfNeeded()
        }
    }

    /** 统一记录当前内容位置；分页和滚动都调用同一个入口。 */
    fun updateProgress(chapterId: Long, offset: Int) {
        val chapter = _uiState.value.chapters.firstOrNull { it.id == chapterId } ?: return
        val position = ReadingPosition(chapterId, offset.coerceIn(0, chapter.content.length))
        if (position == _uiState.value.position) return
        _uiState.update { it.copy(position = position, activeChapterId = chapterId, activeChapter = chapter) }
        activeChapterIdFlow.value = chapterId
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
        viewModelScope.launch { bookRepo.updateLanguageMode(bookId, mode) }
        if (mode == "en") {
            _uiState.value.activeChapterId?.let { maybeTranslateChapter(it) }
            prefetchNextChapterIfNeeded()
        }
    }

    fun toggleToolbar() {
        _uiState.update { it.copy(toolbarVisible = !it.toolbarVisible) }
    }

    fun toggleCatalog() {
        _uiState.update {
            val opening = !it.catalogVisible
            it.copy(catalogVisible = true, toolbarVisible = if (opening) false else it.toolbarVisible)
        }
    }

    fun dismissCatalog() {
        _uiState.update { it.copy(catalogVisible = false, toolbarVisible = true) }
    }

    fun toggleSettings() {
        _uiState.update {
            val opening = !it.settingsVisible
            it.copy(settingsVisible = true, toolbarVisible = if (opening) false else it.toolbarVisible)
        }
    }

    fun dismissSettings() {
        _uiState.update { it.copy(settingsVisible = false, toolbarVisible = true) }
    }

    fun toggleLlmSettings() {
        _uiState.update {
            val opening = !it.llmSettingsVisible
            it.copy(llmSettingsVisible = true, toolbarVisible = if (opening) false else it.toolbarVisible)
        }
    }

    fun dismissLlmSettings() {
        llmEditDirty = false
        _uiState.update { it.copy(llmSettingsVisible = false, editingProfileId = null, llmTestResult = null, llmTestSuccess = null, toolbarVisible = true) }
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

    // ── Profile 切换 ──

    /** 切换活跃配置（即时生效，下次翻译用新配置） */
    fun switchProfile(id: Long) {
        viewModelScope.launch {
            llmProfileRepo.setActive(id)
        }
    }

    /** 进入编辑某个配置的 API 设置 */
    fun editProfileInSheet(id: Long) {
        val profile = _uiState.value.profiles.find { it.id == id } ?: return
        llmEditDirty = true
        _editApiKey.value = profile.apiKey
        _editApiBase.value = profile.apiBase
        _editModel.value = profile.model
        _uiState.update { it.copy(editingProfileId = id, llmTestResult = null, llmTestSuccess = null) }
    }

    /** 退出编辑，回到配置列表 */
    fun cancelProfileEditInSheet() {
        llmEditDirty = false
        _uiState.update { it.copy(editingProfileId = null, llmTestResult = null, llmTestSuccess = null) }
        val ls = _uiState.value.llmSettings
        _editApiKey.value = ls.apiKey
        _editApiBase.value = ls.apiBase
        _editModel.value = ls.model
    }

    // ── LLM settings (翻译设置面板 — 编辑当前活跃配置) ──

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

    // ── 翻译参数（解绑自 LLM 配置，即时持久化到活跃 profile） ──

    fun updateLlmChapterMaxChars(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(chapterMaxChars = value)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(chapterMaxChars = value), isActive = true)
        }
    }
    fun updateLlmContextBoost(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableContextBoost = enabled)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(enableContextBoost = enabled), isActive = true)
        }
    }
    fun updateLlmContextChapters(value: Int) {
        val clamped = value.coerceIn(1, 3)
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(contextChapters = clamped)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(contextChapters = clamped), isActive = true)
        }
    }
    fun updateLlmContextMaxChars(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(contextMaxChars = value)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(contextMaxChars = value), isActive = true)
        }
    }
    fun updateLlmThinking(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableThinking = enabled)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(enableThinking = enabled), isActive = true)
        }
    }
    fun updateLlmExplainThinking(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableExplainThinking = enabled)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(enableExplainThinking = enabled), isActive = true)
        }
    }
    fun updateLlmAutoTranslateNext(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(autoTranslateNext = enabled)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(autoTranslateNext = enabled), isActive = true)
        }
        // 打开开关时立即预译下一章（无需等到下次切章），并让目录圆点立刻反映
        if (enabled) prefetchNextChapterIfNeeded()
    }
    fun updateLlmTemperature(value: Float) {
        val clamped = value.coerceIn(0f, 2f)
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(temperature = clamped)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(temperature = clamped), isActive = true)
        }
    }
    fun updateLlmTopP(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(topP = clamped)) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(profile.copy(topP = clamped), isActive = true)
        }
    }

    private fun currentEditedLlmSettings(): LlmSettings =
        _uiState.value.llmSettings.copy(
            apiKey = _editApiKey.value.trim(),
            apiBase = _editApiBase.value.trim(),
            model = _editModel.value.trim()
        )

    /** 保存当前编辑的配置 */
    fun saveLlmSettings() {
        viewModelScope.launch {
            val newSettings = currentEditedLlmSettings()
            try {
                _uiState.update { it.copy(llmSettings = newSettings, llmTestResult = null, llmTestSuccess = null) }
                val editId = _uiState.value.editingProfileId ?: return@launch
                val profile = _uiState.value.profiles.find { it.id == editId }
                    ?: return@launch
                val updated = newSettings.toLlmProfile(name = profile.name, id = editId)
                val isActive = editId == _uiState.value.activeProfileId
                llmProfileRepo.updateProfileWithActiveState(updated, isActive = isActive)
                llmEditDirty = false
                _uiState.update { it.copy(editingProfileId = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("保存翻译设置失败", e)
                _uiState.update { it.copy(llmTestResult = e.message ?: "保存翻译设置失败", llmTestSuccess = false) }
            }
        }
    }

    fun testLlmConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(llmTestResult = null, llmTestSuccess = null) }
            val newSettings = currentEditedLlmSettings()
            try {
                // 先保存再测试
                val editId = _uiState.value.editingProfileId
                if (editId != null) {
                    val profile = _uiState.value.profiles.find { it.id == editId }
                    if (profile != null) {
                        val updated = newSettings.toLlmProfile(name = profile.name, id = editId)
                        val isActive = editId == _uiState.value.activeProfileId
                        llmProfileRepo.updateProfileWithActiveState(updated, isActive = isActive)
                        if (isActive) _uiState.update { it.copy(llmSettings = newSettings) }
                    }
                }
                llmEditDirty = false
                val result = translationService.testConnection(newSettings)
                result.exceptionOrNull()?.let { AppLog.put("连接测试失败", it) }
                _uiState.update {
                    it.copy(
                        llmTestResult = result.getOrNull() ?: result.exceptionOrNull()?.message,
                        llmTestSuccess = result.isSuccess
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("测试连接失败", e)
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
            Chapter.STATUS_PENDING, Chapter.STATUS_FAILED, Chapter.STATUS_TOO_LONG ->
                translationCoordinator.translate(chapter, settings)
        }
    }

    /**
     * 提前翻译下一章（英文阅读时，空闲则后台预译未译的下一章）。
     * 仅协调器空闲时才启动，避免打断当前阅读章的翻译；成功后用户翻到下一章即已就绪。
     */
    private fun prefetchNextChapterIfNeeded() {
        val s = _uiState.value
        if (!s.llmSettings.autoTranslateNext) return
        if (s.mode != "en") return
        if (translationCoordinator.currentRunningChapterId != null) return
        val current = s.activeChapter ?: return
        val idx = s.chapters.indexOfFirst { it.id == current.id }
        if (idx < 0) return
        val next = s.chapters.getOrNull(idx + 1) ?: return
        if (next.status == Chapter.STATUS_PENDING || next.status == Chapter.STATUS_FAILED) {
            maybeTranslateChapter(next.id)
        }
    }

    /** 用户重译：取消当前任务并恢复旧章节 PENDING 后重新开始。 */
    fun retryTranslation(chapterId: Long) {
        val chapter = _uiState.value.chapters.find { it.id == chapterId } ?: return
        viewModelScope.launch {
            translationCoordinator.cancelAndReset()
            translationCoordinator.translate(chapter, _uiState.value.llmSettings)
        }
    }

    // ── 离线词典查词（内嵌 ECDICT，读 IO 线程，毫秒级返回） ──

    fun lookupDictWord(word: String) {
        _uiState.update {
            it.copy(dictQueryWord = word, dictEntry = null, dictLoading = true)
        }
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) { dictDatabase?.lookup(word) }
            _uiState.update {
                it.copy(dictEntry = entry, dictLoading = false)
            }
        }
    }

    fun dismissDictPopup() {
        _uiState.update {
            it.copy(dictQueryWord = null, dictEntry = null, dictLoading = false)
        }
    }

    // ── LLM 词语解释（选词「解释」按钮） ──

    fun explainWord(word: String, paragraphText: String) {
        val service = llmApiService
        if (service == null) {
            _uiState.update {
                it.copy(explainWord = word, explainResult = null, explainLoading = false,
                    explainError = "LLM 服务不可用")
            }
            return
        }
        val settings = _uiState.value.llmSettings
        if (settings.apiKey.isBlank()) {
            _uiState.update {
                it.copy(explainWord = word, explainResult = null, explainLoading = false,
                    explainError = "请先配置 API Key")
            }
            return
        }
        _uiState.update {
            it.copy(explainWord = word, explainResult = null, explainLoading = true, explainError = null)
        }
        viewModelScope.launch {
            val result = service.explainWord(settings, word, paragraphText)
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(explainResult = result.getOrNull(), explainLoading = false, explainError = null)
                } else {
                    val ex = result.exceptionOrNull()
                    AppLog.put("单词解释失败：$word", ex)
                    it.copy(explainResult = null, explainLoading = false,
                        explainError = ex?.message ?: "解释失败")
                }
            }
        }
    }

    fun dismissExplainPopup() {
        _uiState.update {
            it.copy(explainWord = null, explainResult = null, explainLoading = false, explainError = null)
        }
    }

    override fun onCleared() {
        // 翻译运行在 appScope，退出阅读器后继续在后台完成，不在此取消；
        // 仅 flush 阅读进度。重译/换章取消走 cancelAndReset，由用户动作触发。
        super.onCleared()
    }

    class Factory(
        private val bookId: Long,
        private val bookRepo: BookRepository,
        private val chapterRepo: ChapterRepository,
        private val settingsRepo: SettingsRepository,
        private val llmProfileRepo: LlmProfileRepository,
        private val translationService: TranslationService,
        private val dictDatabase: DictDatabase? = null,
        private val llmApiService: LlmApiService? = null,
        private val appScope: CoroutineScope,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                bookId, bookRepo, chapterRepo, settingsRepo, llmProfileRepo, translationService, dictDatabase, llmApiService, appScope, appContext
            ) as T
        }
    }
}
