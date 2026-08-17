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
import kotlinx.coroutines.CancellationException
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
    private var editDirty = false

    val editApiKey: StateFlow<String> = _editApiKey.asStateFlow()
    val editApiBase: StateFlow<String> = _editApiBase.asStateFlow()
    val editModel: StateFlow<String> = _editModel.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.llmSettings.collect { ls ->
                _uiState.update { it.copy(llmSettings = ls) }
                if (!editDirty) {
                    _editApiKey.value = ls.apiKey
                    _editApiBase.value = ls.apiBase
                    _editModel.value = ls.model
                }
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

    fun updateApiKey(key: String) { editDirty = true; _editApiKey.value = key }
    fun updateApiBase(base: String) { editDirty = true; _editApiBase.value = base }
    fun updateModel(model: String) { editDirty = true; _editModel.value = model }

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

    private fun currentEditedLlmSettings(): LlmSettings =
        _uiState.value.llmSettings.copy(
            apiKey = _editApiKey.value.trim(),
            apiBase = _editApiBase.value.trim(),
            model = _editModel.value.trim()
        )

    fun saveLlmSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val newSettings = currentEditedLlmSettings()
                _uiState.update { it.copy(llmSettings = newSettings) }
                settingsRepo.saveLlmSettings(newSettings)
                editDirty = false
                _uiState.update { it.copy(isSaving = false, saved = true, testResult = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, saved = false, testResult = e.message ?: "保存翻译设置失败", testSuccess = false)
                }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null, testSuccess = null) }
            try {
                val newSettings = currentEditedLlmSettings()
                _uiState.update { it.copy(llmSettings = newSettings) }
                settingsRepo.saveLlmSettings(newSettings)
                val result = llmService.testConnection(newSettings)
                if (result.isSuccess) editDirty = false
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
