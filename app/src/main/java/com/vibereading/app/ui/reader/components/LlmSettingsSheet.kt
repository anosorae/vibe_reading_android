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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsSheet(
    llmSettings: LlmSettings,
    profiles: List<LlmProfile>,
    activeProfileId: Long?,
    editingProfileId: Long?,
    editApiKey: String,
    editApiBase: String,
    editModel: String,
    accentColor: Color,
    testResult: String?,
    testSuccess: Boolean?,
    onUpdateApiKey: (String) -> Unit,
    onUpdateApiBase: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateChapterMaxChars: (Int) -> Unit,
    onUpdateMaxOutputTokens: (Int) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onToggleExplainThinking: (Boolean) -> Unit,
    onToggleAutoTranslateNext: (Boolean) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateTopP: (Float) -> Unit,
    onSwitchProfile: (Long) -> Unit,
    onEditProfile: (Long) -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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

            if (editingProfileId != null) {
                // ── 编辑配置二级页面 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCancelEdit() }
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
                    editApiKey = editApiKey,
                    editApiBase = editApiBase,
                    editModel = editModel,
                    showApiKey = showApiKey,
                    accentColor = accentColor,
                    testResult = testResult,
                    testSuccess = testSuccess,
                    onUpdateApiKey = onUpdateApiKey,
                    onUpdateApiBase = onUpdateApiBase,
                    onUpdateModel = onUpdateModel,
                    onToggleShowApiKey = { showApiKey = !showApiKey },
                    onSave = onSave,
                    onTest = onTest
                )
            } else {
                // ── 配置列表 ──
                LlmProfileList(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    accentColor = accentColor,
                    onSelect = onSwitchProfile,
                    onEdit = onEditProfile
                )
            }

            // ── 翻译参数 ──
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            LlmSectionTitle("翻译参数", accentColor)

            LlmTranslationParams(
                llmSettings = llmSettings,
                accentColor = accentColor,
                onUpdateChapterMaxChars = onUpdateChapterMaxChars,
                onUpdateMaxOutputTokens = onUpdateMaxOutputTokens,
                onToggleThinking = onToggleThinking,
                onToggleExplainThinking = onToggleExplainThinking,
                onUpdateTemperature = onUpdateTemperature,
                onUpdateTopP = onUpdateTopP,
                onToggleAutoTranslateNext = onToggleAutoTranslateNext
            )
        }
    }
}
