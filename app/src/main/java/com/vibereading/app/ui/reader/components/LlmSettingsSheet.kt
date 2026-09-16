package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.ui.components.LlmProfileEditor
import com.vibereading.app.ui.components.LlmProfileList
import com.vibereading.app.ui.components.LlmSectionTitle
import com.vibereading.app.ui.components.LlmTranslationParams

/**
 * 阅读器内翻译设置面板：
 * - LLM 配置区：配置列表（切换/编辑）/ 编辑页（apiKey+base+model + 保存/测试）
 * - 翻译参数区：章节上限/上下文增强/思考模式，即时生效。
 * 编辑字段由外部 ViewModel 持有，面板只负责渲染与回调。
 */
data class LlmSettingsSheetUiState(
    val llmSettings: LlmSettings,
    val profiles: List<LlmProfile>,
    val activeProfileId: Long?,
    val editingProfileId: Long?,
    val editApiKey: String,
    val editApiBase: String,
    val editModel: String,
    val testResult: String?,
    val testSuccess: Boolean?
)

interface LlmSettingsSheetActions {
    fun updateApiKey(value: String)
    fun updateApiBase(value: String)
    fun updateModel(value: String)
    fun updateChapterMaxChars(value: Int)
    fun updateMaxOutputTokens(value: Int)
    fun toggleThinking(enabled: Boolean)
    fun toggleExplainThinking(enabled: Boolean)
    fun toggleAutoTranslateNext(enabled: Boolean)
    fun updateTemperature(value: Float)
    fun updateTopP(value: Float)
    fun switchProfile(id: Long)
    fun editProfile(id: Long)
    fun cancelEdit()
    fun save()
    fun test()
    fun dismiss()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsSheet(
    state: LlmSettingsSheetUiState,
    actions: LlmSettingsSheetActions,
    accentColor: Color
) {
    var showApiKey by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = actions::dismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "翻译设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // ── LLM 配置 ──
            LlmSectionTitle("LLM 配置", accentColor)

            Spacer(Modifier.height(10.dp))

            if (state.editingProfileId != null) {
                // ── 编辑配置二级页面 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { actions.cancelEdit() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "返回配置列表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(8.dp))

                LlmProfileEditor(
                    editApiKey = state.editApiKey,
                    editApiBase = state.editApiBase,
                    editModel = state.editModel,
                    showApiKey = showApiKey,
                    accentColor = accentColor,
                    testResult = state.testResult,
                    testSuccess = state.testSuccess,
                    onUpdateApiKey = actions::updateApiKey,
                    onUpdateApiBase = actions::updateApiBase,
                    onUpdateModel = actions::updateModel,
                    onToggleShowApiKey = { showApiKey = !showApiKey },
                    onSave = actions::save,
                    onTest = actions::test
                )
            } else {
                // ── 配置列表 ──
                LlmProfileList(
                    profiles = state.profiles,
                    activeProfileId = state.activeProfileId,
                    accentColor = accentColor,
                    onSelect = actions::switchProfile,
                    onEdit = actions::editProfile
                )
            }

            // ── 翻译参数 ──
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            LlmSectionTitle("翻译参数", accentColor)

            LlmTranslationParams(
                llmSettings = state.llmSettings,
                accentColor = accentColor,
                onUpdateChapterMaxChars = actions::updateChapterMaxChars,
                onUpdateMaxOutputTokens = actions::updateMaxOutputTokens,
                onToggleThinking = actions::toggleThinking,
                onToggleExplainThinking = actions::toggleExplainThinking,
                onUpdateTemperature = actions::updateTemperature,
                onUpdateTopP = actions::updateTopP,
                onToggleAutoTranslateNext = actions::toggleAutoTranslateNext
            )
        }
    }
}
