package com.vibereading.app.ui.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.ui.components.LlmProfileEditor
import com.vibereading.app.ui.components.LlmProfileList
import com.vibereading.app.ui.components.LlmSectionTitle
import com.vibereading.app.ui.components.LlmSwitchRow
import com.vibereading.app.ui.components.LlmTranslationParams

/**
 * 阅读器内翻译设置面板：
 * - LLM 配置区：配置列表（切换/编辑）/ 编辑页（apiKey+base+model + 保存/测试）
 * - 一级行为开关：思考模式 / 解释时思考 / 提前翻译下一章（与 App「翻译与 AI」分区同层级）
 * - 二级翻译参数：单章上限 / 最大 Token / 采样温度 / Top P，折叠收起，低频调整才展开
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
    var paramsExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (paramsExpanded) 90f else 0f,
        label = "paramsChevron"
    )

    ModalBottomSheet(
        onDismissRequest = actions::dismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        // material3 1.4.0 的 sheet 会按当前 offset 消耗 top inset 回灌给内容测量：
        // offset → 顶部 insets padding → sheet 测量高度 → 锚点位置 → snap 回 offset，
        // 内容接近满屏时构成测量反馈环，表现为面板持续抖动（相差恰为一个状态栏高度）。
        // 固定只保留底部 inset，切断 offset 对内容测量的耦合。
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) }
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
            LlmSectionTitle("LLM 配置")

            Spacer(Modifier.height(10.dp))

            if (state.editingProfileId != null) {
                // ── 编辑配置二级页面 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
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
                    onSelect = actions::switchProfile,
                    onEdit = actions::editProfile
                )
            }

            // ── 一级行为开关（与 App「翻译与 AI」分区同层级）──
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            LlmSectionTitle("翻译行为")

            Spacer(Modifier.height(10.dp))

            LlmSwitchRow(
                title = "思考模式",
                description = "允许模型输出思考过程",
                checked = state.llmSettings.enableThinking,
                onCheckedChange = actions::toggleThinking
            )
            LlmSwitchRow(
                title = "解释时思考",
                description = "选词解释时使用深度思考模式",
                checked = state.llmSettings.enableExplainThinking,
                onCheckedChange = actions::toggleExplainThinking
            )
            LlmSwitchRow(
                title = "提前翻译下一章",
                description = "英文阅读时自动预译未译的下一章",
                checked = state.llmSettings.autoTranslateNext,
                onCheckedChange = actions::toggleAutoTranslateNext
            )

            // ── 二级翻译参数（低频数值项，折叠收起）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { paramsExpanded = !paramsExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "翻译参数",
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (paramsExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation).size(20.dp)
                )
            }

            AnimatedVisibility(visible = paramsExpanded) {
                LlmTranslationParams(
                    llmSettings = state.llmSettings,
                    onUpdateChapterMaxChars = actions::updateChapterMaxChars,
                    onUpdateMaxOutputTokens = actions::updateMaxOutputTokens,
                    onUpdateTemperature = actions::updateTemperature,
                    onUpdateTopP = actions::updateTopP
                )
            }
        }
    }
}
