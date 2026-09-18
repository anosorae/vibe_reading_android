package com.vibereading.app.ui.reader

import com.vibereading.app.data.remote.TranslationService
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.toLlmProfile
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** LLM 配置编辑会话状态：editingProfileId 非空 = 翻译设置面板处于编辑某个配置的二级页面。 */
data class LlmEditState(
    val editingProfileId: Long? = null,
    val editApiKey: String = "",
    val editApiBase: String = "",
    val editModel: String = "",
    val testResult: String? = null,
    val testSuccess: Boolean? = null
)

/** 编辑控制器的宿主钩子：读取宿主（ReaderViewModel）UiState 的 LLM 上下文并接收运行时配置更新。 */
interface LlmEditHost {
    val currentProfiles: List<LlmProfile>
    val currentActiveProfileId: Long?
    val currentLlmSettings: LlmSettings
    fun onActiveLlmSettingsUpdated(newSettings: LlmSettings)
}

/**
 * LLM 配置编辑与连通测试控制器：从 ReaderViewModel 抽出的内聚职责单元。
 *
 * 持有「正在编辑的档案 + apiKey/base/model 草稿 + 连接测试结果」的独立状态流；
 * 未编辑时草稿跟随活跃配置回填（[onActiveSettingsChanged]），编辑期间（dirty）不被回流覆盖。
 * 保存/测试经 [LlmProfileRepository] 落库；翻译参数调档与翻译重评估仍归 ReaderViewModel。
 */
class LlmEditController(
    private val scope: CoroutineScope,
    private val llmProfileRepo: LlmProfileRepository,
    private val translationService: TranslationService,
    private val host: LlmEditHost
) {

    private val _state = MutableStateFlow(LlmEditState())
    val state: StateFlow<LlmEditState> = _state.asStateFlow()

    private var dirty = false

    /** 活跃配置流回填草稿；编辑期间不覆盖。 */
    fun onActiveSettingsChanged(ls: LlmSettings) {
        if (dirty) return
        _state.update { it.copy(editApiKey = ls.apiKey, editApiBase = ls.apiBase, editModel = ls.model) }
    }

    /** 切换活跃配置（即时生效，下次翻译用新配置）。 */
    fun switchProfile(id: Long) {
        scope.launch { llmProfileRepo.setActive(id) }
    }

    /** 进入编辑某个配置的 API 设置。 */
    fun editProfile(id: Long) {
        val profile = host.currentProfiles.find { it.id == id } ?: return
        dirty = true
        _state.update {
            it.copy(
                editingProfileId = id, testResult = null, testSuccess = null,
                editApiKey = profile.apiKey, editApiBase = profile.apiBase, editModel = profile.model
            )
        }
    }

    /** 退出编辑回到配置列表：草稿丢弃并回填活跃配置。 */
    fun cancelEdit() {
        dirty = false
        fillFrom(host.currentLlmSettings)
    }

    /** 打开面板时从最新持久化值填充；已有草稿则保持不变。 */
    fun initEditFields() {
        if (dirty) return
        fillFrom(host.currentLlmSettings)
    }

    fun updateApiKey(key: String) {
        dirty = true
        _state.update { it.copy(editApiKey = key) }
    }

    fun updateApiBase(base: String) {
        dirty = true
        _state.update { it.copy(editApiBase = base) }
    }

    fun updateModel(model: String) {
        dirty = true
        _state.update { it.copy(editModel = model) }
    }

    /** 关闭面板：清理编辑会话（草稿丢弃，回填由下次活跃配置流或打开面板触发）。 */
    fun onSheetDismissed() {
        dirty = false
        _state.update { it.copy(editingProfileId = null, testResult = null, testSuccess = null) }
    }

    private fun fillFrom(ls: LlmSettings) {
        _state.update {
            it.copy(
                editingProfileId = null, testResult = null, testSuccess = null,
                editApiKey = ls.apiKey, editApiBase = ls.apiBase, editModel = ls.model
            )
        }
    }

    private fun editedSettings(): LlmSettings = host.currentLlmSettings.copy(
        apiKey = _state.value.editApiKey.trim(),
        apiBase = _state.value.editApiBase.trim(),
        model = _state.value.editModel.trim()
    )

    /** 保存当前编辑的配置；无编辑会话时仅更新运行时配置（与原 ReaderViewModel 行为一致）。 */
    fun save() {
        scope.launch {
            val newSettings = editedSettings()
            try {
                host.onActiveLlmSettingsUpdated(newSettings)
                _state.update { it.copy(testResult = null, testSuccess = null) }
                val editId = _state.value.editingProfileId ?: return@launch
                val profile = host.currentProfiles.find { it.id == editId } ?: return@launch
                val updated = newSettings.toLlmProfile(name = profile.name, id = editId)
                val isActive = editId == host.currentActiveProfileId
                llmProfileRepo.updateProfileWithActiveState(updated, isActive = isActive)
                dirty = false
                _state.update { it.copy(editingProfileId = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("保存翻译设置失败", e)
                _state.update { it.copy(testResult = e.message ?: "保存翻译设置失败", testSuccess = false) }
            }
        }
    }

    /** 连接测试；编辑会话中先保存再测试。 */
    fun testConnection() {
        scope.launch {
            _state.update { it.copy(testResult = null, testSuccess = null) }
            val newSettings = editedSettings()
            try {
                val editId = _state.value.editingProfileId
                if (editId != null) {
                    val profile = host.currentProfiles.find { it.id == editId }
                    if (profile != null) {
                        val updated = newSettings.toLlmProfile(name = profile.name, id = editId)
                        val isActive = editId == host.currentActiveProfileId
                        llmProfileRepo.updateProfileWithActiveState(updated, isActive = isActive)
                        if (isActive) host.onActiveLlmSettingsUpdated(newSettings)
                    }
                }
                dirty = false
                val result = translationService.testConnection(newSettings)
                result.exceptionOrNull()?.let { AppLog.put("连接测试失败", it) }
                _state.update {
                    it.copy(
                        testResult = result.getOrNull() ?: result.exceptionOrNull()?.message,
                        testSuccess = result.isSuccess
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("测试连接失败", e)
                _state.update { it.copy(testResult = e.message ?: "测试连接失败", testSuccess = false) }
            }
        }
    }
}
