package com.vibereading.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.vibereading.app.data.dict.DictDatabase
import com.vibereading.app.data.remote.WordExplainService
import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.ReadingSettingsSaver
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
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.log.AppLog
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
    // 章节列表流是否已首次到达（并行加载下书籍信息可能先到，不能用书名非空推断）
    val chaptersLoaded: Boolean = false,
    val streamingText: String = "",
    val thinkingText: String = "",
    val streamingCharCount: Int = 0,
    val isStreaming: Boolean = false,
    val translationPhase: TranslationPhase = TranslationPhase.IDLE,
    val mode: String = "zh",          // "zh" 或 "en"（显示模式，默认=原文语言，按书绑定）
    val sourceLanguage: String = "zh",  // 书籍原文语言（ADR-003）：决定翻译方向与渲染插槽
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
    private val wordExplainService: WordExplainService? = null,
    appContext: Context,
    coordinator: TranslationCoordinator? = null
) : ViewModel(), LlmEditHost {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** 当前阅读章节 ID 的独立流；combine 协调器状态时用它做 active 判定，
     *  避免 navigateTo 改 activeChapterId 但协调器 _state 未变时桥接不重评估。 */
    private val activeChapterIdFlow = MutableStateFlow<Long?>(null)

    // 生产环境与 Web 伴读共用进程级协调器；测试可注入独立实例。
    // 同章互斥、异章并行，阅读焦点只决定展示哪个任务状态。
    private val translationCoordinator = coordinator ?: TranslationCoordinatorProvider.get(appContext)
    private val progressMutex = Mutex()
    private var pendingPosition: ReadingPosition? = null
    private var restoreCompleted = false

    // Main.immediate 下，缓存设置的 first() 可能在构造期间直接返回。
    // init 中协程使用的状态必须提前初始化，不能依赖首次读取一定会挂起。
    private val readingSettingsLoaded = MutableStateFlow(false)
    private val firstContentReady = MutableStateFlow(false)
    private val settingsSaver = ReadingSettingsSaver(viewModelScope, settingsRepo.reading::saveSettings)

    // ── LLM 配置编辑/连通测试的独立状态单元（编辑草稿跟随活跃配置回填，dirty 期间不被覆盖） ──
    // 必须在 init 之前声明：init 中的 activeLlmSettings 收集会调用 onActiveSettingsChanged。
    private val llmEdit = LlmEditController(
        scope = viewModelScope,
        llmProfileRepo = llmProfileRepo,
        translationService = translationService,
        host = this
    )
    val llmEditState: StateFlow<LlmEditState> = llmEdit.state

    init {
        // 书籍/目标单章与设置并行准备，首屏不再等待整书正文读取。
        viewModelScope.launch {
            val book = bookRepo.getBookByIdOnce(bookId) ?: run {
                _uiState.update { it.copy(chaptersLoaded = true) }
                return@launch
            }
            val savedPosition = ReadingPosition(book.lastReadChapterId, book.lastReadOffset)
            val chapter = chapterRepo.getOpeningChapter(bookId, book.lastReadChapterId)
            readingSettingsLoaded.first { it }
            _uiState.update {
                it.copy(bookTitle = book.title, mode = book.languageMode, sourceLanguage = book.sourceLanguage,
                    chapters = listOfNotNull(chapter), chaptersLoaded = chapter == null)
            }
            tryRestore(listOfNotNull(chapter), savedPosition)
        }
        viewModelScope.launch {
            firstContentReady.first { it }
            chapterRepo.getChaptersByBook(bookId).collect { chapters ->
                _uiState.update { it.copy(chapters = chapters, chaptersLoaded = true) }
                if (restoreCompleted) {
                    val current = _uiState.value.activeChapterId
                    val updated = chapters.find { it.id == current }
                    if (updated != null) _uiState.update { it.copy(activeChapter = updated) }
                }
                prefetchNextChapterIfNeeded()
            }
        }

        // 当前阅读章节 + 按章节任务 Map → UI 实时状态。目标章没有任务时完整归零，
        // 不保留上一章的思考文本/字数；切回仍在运行的章节会立即恢复其独立快照。
        viewModelScope.launch {
            combine(activeChapterIdFlow, translationCoordinator.states) { activeId, taskStates ->
                activeId?.let { taskStates[TranslationTaskKey(bookId, it)] }
            }.collect { task ->
                _uiState.update { ui ->
                    ui.copy(
                        streamingText = task?.streamingText.orEmpty(),
                        thinkingText = task?.thinkingText.orEmpty(),
                        streamingCharCount = task?.streamingCharCount ?: 0,
                        isStreaming = task?.isStreaming == true,
                        translationPhase = task?.phase ?: TranslationPhase.IDLE,
                        errorMessage = task?.errorMessage
                    )
                }
            }
        }

        // 活动章节变化是自动翻译的唯一触发点，覆盖目录/按钮、分页跨章、滚动跨章和恢复。
        viewModelScope.launch {
            firstContentReady.first { it }
            activeChapterIdFlow.filterNotNull().distinctUntilChanged().collect { chapterId ->
                if (needsTranslation()) maybeTranslateChapter(chapterId)
                prefetchNextChapterIfNeeded()
            }
        }

        // Load settings：持久化值一次性载入，此后 UI 状态是唯一事实源。
        // 不能持续 collect settingsRepo.reading.settings：saveReadingSettings 每次写库
        // 都会经 store.data 回流，拖动滑杆时延迟到达的旧值回声会覆盖较新的 UI 状态，
        // 造成滑杆回跳/跳变（全 app 只有本 ViewModel 写阅读设置，无外部变更需要回流）。
        viewModelScope.launch {
            val rs = settingsRepo.reading.settings.first()
            _uiState.update { it.copy(readingSettings = rs) }
            readingSettingsLoaded.value = true
        }
        viewModelScope.launch {
            settingsRepo.reading.nightMode.collect { night ->
                _uiState.update { it.copy(nightMode = night) }
            }
        }
        // LLM 设置从 llmProfileRepo 读取（替代原 settingsRepo.llmSettings）
        viewModelScope.launch {
            firstContentReady.first { it }
            llmProfileRepo.activeLlmSettings.collect { ls ->
                _uiState.update { it.copy(llmSettings = ls) }
                llmEdit.onActiveSettingsChanged(ls)
                // 配置变更后重新评估当前章节是否需要翻译
                if (needsTranslation()) {
                    _uiState.value.activeChapterId?.let { maybeTranslateChapter(it) }
                }
            }
        }
        viewModelScope.launch {
            firstContentReady.first { it }
            llmProfileRepo.profiles.collect { list ->
                _uiState.update { it.copy(profiles = list) }
            }
        }
        viewModelScope.launch {
            firstContentReady.first { it }
            llmProfileRepo.activeProfile.collect { profile ->
                _uiState.update { it.copy(activeProfileId = profile?.id) }
            }
        }
    }

    /** 首屏可显示后才放行全书读取和非首屏工作；不参与开书动画的启动条件。 */
    fun onFirstContentReady() {
        if (!firstContentReady.value) {
            firstContentReady.value = true
        }
    }

    /** 一次性原子恢复（书籍信息 + 章节列表双就绪才执行）：先读 Book 位置快照，再恢复章节与偏移。 */
    private fun tryRestore(chapters: List<Chapter>, savedPosition: ReadingPosition?) {
        if (restoreCompleted || savedPosition == null || chapters.isEmpty()) return
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
        // 自动翻译由首屏就绪后的配置流启动，不阻塞本次原文位置恢复。
    }

    /** 用户主动跳转；分页位置由当前排版器根据 offset 派生。 */
    fun navigateTo(chapterId: Long, offset: Int = 0, persist: Boolean = true) {
        viewModelScope.launch {
            val chapter = chapterRepo.getChapterById(bookId, chapterId) ?: return@launch
            // 切到别的章：由 flow 收集器统一触发；留在同一章（目录点当前章）时再试一次，
            // 保留「重进失败章节会自动重试」的原行为（flow 的 distinctUntilChanged 不重发同值）。
            val reenteringSameChapter = _uiState.value.activeChapterId == chapterId
            val position = ReadingPosition(chapterId, offset.coerceIn(0, chapter.content.length))
            _uiState.update {
                it.copy(
                    activeChapterId = chapterId,
                    activeChapter = chapter,
                    position = position,
                    restoreReady = true
                )
            }
            // 切章与滚动跨章的自动翻译统一由 activeChapterIdFlow 收集器触发，避免多个入口各写一份。
            activeChapterIdFlow.value = chapterId
            if (persist) enqueueProgress(position)
            if (reenteringSameChapter && needsTranslation()) maybeTranslateChapter(chapterId)
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
        if (needsTranslation()) {
            _uiState.value.activeChapterId?.let { maybeTranslateChapter(it) }
            prefetchNextChapterIfNeeded()
        }
    }

    /**
     * 打开浮层：浮层与工具栏互斥，本次为首次打开时收起工具栏。
     * [isVisible] 与 [open] 都在 `update` 闭包内求值，保持状态变更的原子性。
     */
    private fun openOverlay(
        isVisible: ReaderUiState.() -> Boolean,
        open: ReaderUiState.() -> ReaderUiState
    ) {
        _uiState.update { state ->
            val opening = !state.isVisible()
            state.open().copy(toolbarVisible = if (opening) false else state.toolbarVisible)
        }
    }

    fun toggleToolbar() {
        _uiState.update { it.copy(toolbarVisible = !it.toolbarVisible) }
    }

    fun toggleCatalog() = openOverlay({ catalogVisible }) { copy(catalogVisible = true) }

    fun dismissCatalog() {
        _uiState.update { it.copy(catalogVisible = false, toolbarVisible = true) }
    }

    fun toggleSettings() = openOverlay({ settingsVisible }) { copy(settingsVisible = true) }

    fun dismissSettings() {
        _uiState.update { it.copy(settingsVisible = false, toolbarVisible = true) }
    }

    fun toggleLlmSettings() = openOverlay({ llmSettingsVisible }) { copy(llmSettingsVisible = true) }

    fun dismissLlmSettings() {
        llmEdit.onSheetDismissed()
        _uiState.update { it.copy(llmSettingsVisible = false, toolbarVisible = true) }
    }

    fun dismissAllOverlays() {
        _uiState.update { it.copy(toolbarVisible = false, catalogVisible = false, settingsVisible = false, llmSettingsVisible = false) }
    }

    // ── Reading style updates（UI 状态即时生效；持久化经合并写，见 ReadingSettingsSaver） ──

    /** 载入前的设置改动等待初始值就绪，避免以默认值覆盖持久值。 */
    fun updateReadingSettings(transform: (ReadingSettings) -> ReadingSettings) {
        viewModelScope.launch {
            readingSettingsLoaded.first { it }
            val new = transform(_uiState.value.readingSettings)
            _uiState.update { it.copy(readingSettings = new) }
            settingsSaver.submit(new)
        }
    }

    fun toggleNightMode() {
        val new = !_uiState.value.nightMode
        _uiState.update { it.copy(nightMode = new) }
        viewModelScope.launch { settingsRepo.reading.saveNightMode(new) }
    }

    // ── LlmEditHost：编辑控制器读写本 ViewModel 的 LLM 上下文 ──

    override val currentProfiles: List<LlmProfile> get() = _uiState.value.profiles
    override val currentActiveProfileId: Long? get() = _uiState.value.activeProfileId
    override val currentLlmSettings: LlmSettings get() = _uiState.value.llmSettings

    override fun onActiveLlmSettingsUpdated(newSettings: LlmSettings) {
        _uiState.update { it.copy(llmSettings = newSettings) }
    }

    // ── LLM 配置编辑与连通测试（实现在 LlmEditController，此处仅保留面板 Actions 的转发） ──

    fun switchProfile(id: Long) = llmEdit.switchProfile(id)

    fun editProfileInSheet(id: Long) = llmEdit.editProfile(id)

    fun cancelProfileEditInSheet() = llmEdit.cancelEdit()

    /** 打开面板时从最新持久化值填充；已有草稿则保持不变。 */
    fun initLlmEditFields() = llmEdit.initEditFields()

    fun updateEditApiKey(key: String) = llmEdit.updateApiKey(key)

    fun updateEditApiBase(base: String) = llmEdit.updateApiBase(base)

    fun updateEditModel(model: String) = llmEdit.updateModel(model)

    // ── 翻译参数（解绑自 LLM 配置，即时持久化到活跃 profile） ──

    /**
     * 更新活跃档案的一组翻译参数：UI 状态即时生效，同一组参数异步落库。
     * 无活跃档案时只更新运行时状态（不落库），与逐字段实现的原行为一致。
     */
    private fun updateActiveProfile(transform: (LlmSettings) -> LlmSettings) {
        val updated = transform(_uiState.value.llmSettings)
        _uiState.update { it.copy(llmSettings = updated) }
        viewModelScope.launch {
            val id = _uiState.value.activeProfileId ?: return@launch
            val profile = _uiState.value.profiles.find { it.id == id } ?: return@launch
            llmProfileRepo.updateProfileWithActiveState(
                updated.toLlmProfile(name = profile.name, id = profile.id),
                isActive = true
            )
        }
    }

    fun updateLlmChapterMaxChars(value: Int) = updateActiveProfile { it.copy(chapterMaxChars = value) }
    fun updateLlmMaxOutputTokens(value: Int) = updateActiveProfile { it.copy(maxOutputTokens = value) }
    fun updateLlmThinking(enabled: Boolean) = updateActiveProfile { it.copy(enableThinking = enabled) }
    fun updateLlmExplainThinking(enabled: Boolean) = updateActiveProfile { it.copy(enableExplainThinking = enabled) }

    fun updateLlmAutoTranslateNext(enabled: Boolean) {
        updateActiveProfile { it.copy(autoTranslateNext = enabled) }
        // 打开开关时立即预译下一章（无需等到下次切章），并让目录圆点立刻反映
        if (enabled) prefetchNextChapterIfNeeded()
    }

    fun updateLlmTemperature(value: Float) = updateActiveProfile { it.copy(temperature = value.coerceIn(0f, 2f)) }
    fun updateLlmTopP(value: Float) = updateActiveProfile { it.copy(topP = value.coerceIn(0f, 1f)) }

    /** 保存当前编辑的配置 */
    fun saveLlmSettings() = llmEdit.save()

    fun testLlmConnection() = llmEdit.testConnection()

    private fun maybeTranslateChapter(chapterId: Long) {
        val chapter = _uiState.value.chapters.find { it.id == chapterId } ?: return
        val settings = _uiState.value.llmSettings
        if (settings.apiKey.isBlank()) return
        if (chapter.status !in setOf(
                Chapter.STATUS_PENDING,
                Chapter.STATUS_IN_PROGRESS,
                Chapter.STATUS_FAILED,
                Chapter.STATUS_TOO_LONG
            )
        ) return
        viewModelScope.launch {
            translationCoordinator.start(bookId, chapterId, settings, _uiState.value.sourceLanguage)
        }
    }

    /** 当前阅读是否需要译文：英文显示模式，或英文原版书（两种模式都预译，ADR-003）。 */
    private fun needsTranslation(): Boolean {
        val s = _uiState.value
        return firstContentReady.value && (s.mode == "en" || s.sourceLanguage == SourceLanguageDetector.EN)
    }

    /**
     * 提前翻译紧邻下一章；与当前章任务可并行，同章启动由协调器幂等去重。
     *
     * 只自动启动 PENDING：FAILED/TOO_LONG 是真实失败，自动重试会形成
     * 「失败 → 章节表发射 → 再预译 → 再失败」的无上限 API 循环，必须由用户显式重试。
     */
    private fun prefetchNextChapterIfNeeded() {
        val state = _uiState.value
        if (!state.llmSettings.autoTranslateNext || !needsTranslation()) return
        val current = state.activeChapter ?: return
        val index = state.chapters.indexOfFirst { it.id == current.id }
        val next = state.chapters.getOrNull(index + 1) ?: return
        if (next.status == Chapter.STATUS_PENDING) {
            maybeTranslateChapter(next.id)
        }
    }

    /** 用户重译：只替换指定章节任务，其他章节继续运行。 */
    fun retryTranslation(chapterId: Long) {
        viewModelScope.launch {
            translationCoordinator.retry(
                bookId = bookId,
                chapterId = chapterId,
                settings = _uiState.value.llmSettings,
                sourceLanguage = _uiState.value.sourceLanguage
            )
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
        val service = wordExplainService
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
        // 重译只替换指定章节，切换阅读章节不影响任何后台任务。
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
        private val wordExplainService: WordExplainService? = null,
        private val appContext: Context,
        private val coordinator: TranslationCoordinator? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                bookId,
                bookRepo,
                chapterRepo,
                settingsRepo,
                llmProfileRepo,
                translationService,
                dictDatabase,
                wordExplainService,
                appContext,
                coordinator
            ) as T
        }
    }
}
