package com.vibereading.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.remote.LlmApiService
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings
import com.vibereading.app.domain.model.toLlmSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val profiles: List<LlmProfile> = emptyList(),
    val activeProfileId: Long? = null,
    val editingProfile: LlmProfile? = null,     // null = 没有在编辑
    val isNewProfile: Boolean = false,           // true = 新建模式
    val llmSettings: LlmSettings = LlmSettings(), // 当前活跃配置映射
    val readingSettings: ReadingSettings = ReadingSettings(),
    val theme: ThemeSettings = ThemeSettings(),
    val bookshelfLayout: String = "list",
    val bookshelfSort: String = "recent",
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val testSuccess: Boolean? = null,
    val showApiKey: Boolean = false,
    val saved: Boolean = false
)

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val llmProfileRepo: LlmProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val llmService = LlmApiService()

    // 编辑字段（apiKey/apiBase/model 需要独立缓冲区，避免 DataStore Flow 覆盖输入）
    private val _editApiKey = MutableStateFlow("")
    private val _editApiBase = MutableStateFlow("")
    private val _editModel = MutableStateFlow("")
    private val _editName = MutableStateFlow("")
    private var editDirty = false

    val editApiKey: StateFlow<String> = _editApiKey.asStateFlow()
    val editApiBase: StateFlow<String> = _editApiBase.asStateFlow()
    val editModel: StateFlow<String> = _editModel.asStateFlow()
    val editName: StateFlow<String> = _editName.asStateFlow()

    init {
        viewModelScope.launch {
            llmProfileRepo.profiles.collect { list ->
                _uiState.update { it.copy(profiles = list) }
            }
        }
        viewModelScope.launch {
            llmProfileRepo.activeLlmSettings.collect { ls ->
                _uiState.update { it.copy(llmSettings = ls) }
                if (!editDirty) {
                    _editApiKey.value = ls.apiKey
                    _editApiBase.value = ls.apiBase
                    _editModel.value = ls.model
                }
            }
        }
        viewModelScope.launch {
            // 跟踪活跃 profile id
            llmProfileRepo.activeProfile.collect { profile ->
                _uiState.update { it.copy(activeProfileId = profile?.id) }
            }
        }
        viewModelScope.launch {
            settingsRepo.readingSettings.collect { rs ->
                _uiState.update { it.copy(readingSettings = rs) }
            }
        }
        viewModelScope.launch {
            settingsRepo.themeSettings.collect { t ->
                _uiState.update { it.copy(theme = t) }
            }
        }
        viewModelScope.launch {
            settingsRepo.bookshelfLayout.collect { l ->
                _uiState.update { it.copy(bookshelfLayout = l) }
            }
        }
        viewModelScope.launch {
            settingsRepo.bookshelfSort.collect { s ->
                _uiState.update { it.copy(bookshelfSort = s) }
            }
        }
    }

    // ── Profile 管理 ──

    /** 切换活跃配置 */
    fun selectProfile(id: Long) {
        viewModelScope.launch {
            llmProfileRepo.setActive(id)
        }
    }

    /** 开始新建配置 */
    fun addProfile() {
        editDirty = true
        val newProfile = LlmProfile(name = "")
        _uiState.update { it.copy(editingProfile = newProfile, isNewProfile = true) }
        _editName.value = ""
        _editApiKey.value = ""
        _editApiBase.value = "https://api.deepseek.com"
        _editModel.value = "deepseek-v4-flash"
    }

    /** 开始编辑某个配置 */
    fun editProfile(id: Long) {
        val profile = _uiState.value.profiles.find { it.id == id } ?: return
        editDirty = true
        _uiState.update { it.copy(editingProfile = profile, isNewProfile = false) }
        _editName.value = profile.name
        _editApiKey.value = profile.apiKey
        _editApiBase.value = profile.apiBase
        _editModel.value = profile.model
    }

    /** 取消编辑 */
    fun cancelEdit() {
        editDirty = false
        _uiState.update { it.copy(editingProfile = null, isNewProfile = false, testResult = null, testSuccess = null) }
        // 恢复编辑字段为当前活跃配置
        val ls = _uiState.value.llmSettings
        _editApiKey.value = ls.apiKey
        _editApiBase.value = ls.apiBase
        _editModel.value = ls.model
    }

    /** 删除配置（至少保留一个） */
    fun deleteProfile(id: Long) {
        if (_uiState.value.profiles.size <= 1) return
        viewModelScope.launch {
            llmProfileRepo.deleteProfile(id)
        }
    }

    /** 保存配置（新建或更新） */
    fun saveProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val profile = currentEditedProfile()
                if (_uiState.value.isNewProfile) {
                    val id = llmProfileRepo.addProfile(profile)
                    // 新建的第一个配置自动设为活跃
                    if (_uiState.value.profiles.isEmpty()) {
                        llmProfileRepo.setActive(id)
                    }
                } else {
                    val isActive = profile.id == _uiState.value.activeProfileId
                    llmProfileRepo.updateProfileWithActiveState(profile, isActive)
                    // 如果编辑的是活跃配置，更新 llmSettings 映射
                    if (isActive) {
                        _uiState.update { it.copy(llmSettings = profile.toLlmSettings()) }
                    }
                }
                editDirty = false
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saved = true,
                        editingProfile = null,
                        isNewProfile = false,
                        testResult = null,
                        testSuccess = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, saved = false, testResult = e.message ?: "保存配置失败", testSuccess = false)
                }
            }
        }
    }

    /** 测试连接 */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null, testSuccess = null) }
            try {
                val profile = currentEditedProfile()
                val settings = profile.toLlmSettings()
                // 先保存再测试
                if (_uiState.value.isNewProfile) {
                    val id = llmProfileRepo.addProfile(profile)
                    if (_uiState.value.profiles.isEmpty()) llmProfileRepo.setActive(id)
                    _uiState.update { it.copy(isNewProfile = false, editingProfile = profile.copy(id = id)) }
                } else {
                    val isActive = profile.id == _uiState.value.activeProfileId
                    llmProfileRepo.updateProfileWithActiveState(profile, isActive)
                    if (isActive) _uiState.update { it.copy(llmSettings = settings) }
                }
                editDirty = false
                val result = llmService.testConnection(settings)
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = result.getOrNull() ?: result.exceptionOrNull()?.message,
                        testSuccess = result.isSuccess
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTesting = false, testResult = e.message ?: "测试连接失败", testSuccess = false)
                }
            }
        }
    }

    // ── 编辑字段更新 ──

    fun updateEditApiKey(key: String) { editDirty = true; _editApiKey.value = key }
    fun updateEditApiBase(base: String) { editDirty = true; _editApiBase.value = base }
    fun updateEditModel(model: String) { editDirty = true; _editModel.value = model }
    fun updateEditName(name: String) { editDirty = true; _editName.value = name }

    fun updateChapterMaxChars(value: Int) {
        val ep = _uiState.value.editingProfile ?: return
        _uiState.update { it.copy(editingProfile = ep.copy(chapterMaxChars = value)) }
    }
    fun updateContextBoost(enabled: Boolean) {
        val ep = _uiState.value.editingProfile ?: return
        _uiState.update { it.copy(editingProfile = ep.copy(enableContextBoost = enabled)) }
    }
    fun updateContextChapters(value: Int) {
        val ep = _uiState.value.editingProfile ?: return
        _uiState.update { it.copy(editingProfile = ep.copy(contextChapters = value.coerceIn(1, 3))) }
    }
    fun updateContextMaxChars(value: Int) {
        val ep = _uiState.value.editingProfile ?: return
        _uiState.update { it.copy(editingProfile = ep.copy(contextMaxChars = value)) }
    }
    fun updateThinking(enabled: Boolean) {
        val ep = _uiState.value.editingProfile ?: return
        _uiState.update { it.copy(editingProfile = ep.copy(enableThinking = enabled)) }
    }

    fun toggleShowApiKey() {
        _uiState.update { it.copy(showApiKey = !it.showApiKey) }
    }

    /** 合并编辑字段为完整的 LlmProfile */
    private fun currentEditedProfile(): LlmProfile {
        val ep = _uiState.value.editingProfile ?: LlmProfile()
        return ep.copy(
            name = _editName.value.trim().ifEmpty { "未命名" },
            apiKey = _editApiKey.value.trim(),
            apiBase = _editApiBase.value.trim().trimEnd('/').ifEmpty { "https://api.deepseek.com" },
            model = _editModel.value.trim().ifEmpty { "deepseek-v4-flash" }
        )
    }

    // ── 主题设置 ──

    fun updateThemeMode(mode: ThemeMode) {
        val next = _uiState.value.theme.copy(themeMode = mode)
        _uiState.update { it.copy(theme = next) }
        viewModelScope.launch { settingsRepo.saveThemeSettings(next) }
    }

    fun updateAccent(accent: AppAccent) {
        val next = _uiState.value.theme.copy(accent = accent)
        _uiState.update { it.copy(theme = next) }
        viewModelScope.launch { settingsRepo.saveThemeSettings(next) }
    }

    // ── 阅读设置 ──

    fun saveReadingSettings() {
        viewModelScope.launch {
            settingsRepo.saveReadingSettings(_uiState.value.readingSettings)
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun resetReadingSettings() {
        _uiState.update { it.copy(readingSettings = ReadingSettings()) }
        saveReadingSettings()
    }

    class Factory(
        private val settingsRepo: SettingsRepository,
        private val llmProfileRepo: LlmProfileRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepo, llmProfileRepo) as T
        }
    }
}
