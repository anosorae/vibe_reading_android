package com.vibereading.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.remote.LlmApiService
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val llmSettings: LlmSettings = LlmSettings(),
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
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val llmService = LlmApiService()

    // Editable fields (separate from saved settings)
    private val _editApiKey = MutableStateFlow("")
    private val _editApiBase = MutableStateFlow("")
    private val _editModel = MutableStateFlow("")

    val editApiKey: StateFlow<String> = _editApiKey.asStateFlow()
    val editApiBase: StateFlow<String> = _editApiBase.asStateFlow()
    val editModel: StateFlow<String> = _editModel.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.llmSettings.collect { ls ->
                _uiState.update { it.copy(llmSettings = ls) }
                if (_editApiKey.value.isEmpty()) _editApiKey.value = ls.apiKey
                if (_editApiBase.value.isEmpty()) _editApiBase.value = ls.apiBase
                if (_editModel.value.isEmpty()) _editModel.value = ls.model
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

    fun updateApiKey(key: String) { _editApiKey.value = key }
    fun updateApiBase(base: String) { _editApiBase.value = base }
    fun updateModel(model: String) { _editModel.value = model }

    fun updateChapterMaxChars(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(chapterMaxChars = value)) }
    }

    fun updateContextBoost(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableContextBoost = enabled)) }
    }

    fun updateContextChapters(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(contextChapters = value.coerceIn(1, 3))) }
    }

    fun updateContextMaxChars(value: Int) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(contextMaxChars = value)) }
    }

    fun updateThinking(enabled: Boolean) {
        _uiState.update { it.copy(llmSettings = it.llmSettings.copy(enableThinking = enabled)) }
    }

    fun toggleShowApiKey() {
        _uiState.update { it.copy(showApiKey = !it.showApiKey) }
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

    fun saveLlmSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val current = _uiState.value.llmSettings
            val newSettings = current.copy(
                apiKey = _editApiKey.value,
                apiBase = _editApiBase.value,
                model = _editModel.value
            )
            settingsRepo.saveLlmSettings(newSettings)
            _uiState.update { it.copy(isSaving = false, saved = true, testResult = null) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            // Save first
            val current = _uiState.value.llmSettings
            val newSettings = current.copy(
                apiKey = _editApiKey.value,
                apiBase = _editApiBase.value,
                model = _editModel.value
            )
            settingsRepo.saveLlmSettings(newSettings)

            val result = llmService.testConnection(newSettings)
            _uiState.update {
                it.copy(
                    isTesting = false,
                    testResult = result.getOrNull() ?: result.exceptionOrNull()?.message,
                    testSuccess = result.isSuccess
                )
            }
        }
    }

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
        private val settingsRepo: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepo) as T
        }
    }
}
