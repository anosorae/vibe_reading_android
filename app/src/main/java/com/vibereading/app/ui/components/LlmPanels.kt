package com.vibereading.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.LlmSettings

/**
 * LLM 配置面板的共享组件（单一实现）。
 *
 * 阅读器内的翻译设置抽屉与设置页的 LLM 区块此前各写了一份逐字相同的列表、
 * 编辑表单、七行翻译参数与连接测试结果，共约 500 行重复；此文件是它们唯一的实现。
 *
 * 差异用参数表达而非复制组件：
 * - 设置页有「配置名称」字段、删除按钮、添加按钮与保存/测试的进行中态；阅读器内没有。
 * - 设置页的翻译参数不含「提前翻译下一章」（预译是阅读场景的开关）。
 */

/** 「LLM 配置」/「翻译参数」小标题。 */
@Composable
fun LlmSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

/** 连接测试结果条（成功/失败两态）。 */
@Composable
fun LlmTestResultBanner(testResult: String, testSuccess: Boolean?) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (testSuccess == true) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (testSuccess == true) "✓ 连接成功" else "✗ 连接失败",
                fontWeight = FontWeight.SemiBold,
                color = if (testSuccess == true) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                testResult,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 配置档案列表：活跃配置高亮，可切换/编辑。
 * [onDelete] 非空时显示删除按钮（仅多配置时），[onAdd] 非空时显示底部「添加配置」。
 */
@Composable
fun LlmProfileList(
    profiles: List<LlmProfile>,
    activeProfileId: Long?,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: ((Long) -> Unit)? = null,
    onAdd: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        profiles.forEach { profile ->
            val isActive = profile.id == activeProfileId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onSelect(profile.id) }
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                        MaterialTheme.shapes.small
                    )
                    .then(
                        if (isActive) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 活跃标记
                if (isActive) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前使用",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // 名称
                Text(
                    profile.name.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // 模型
                Text(
                    profile.model,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                // 编辑
                IconButton(onClick = { onEdit(profile.id) }) {
                    Icon(
                        Icons.Filled.Edit, "编辑",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 删除（仅多配置时）
                if (onDelete != null && profiles.size > 1) {
                    IconButton(onClick = { onDelete(profile.id) }) {
                        Icon(
                            Icons.Filled.Delete, "删除",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        if (onAdd != null) {
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加配置")
            }
        }
    }
}

/**
 * 配置编辑表单：API Key（可显隐）/ API Base / Model + 保存与测试连接。
 *
 * [editName] 非空时在顶部显示「配置名称」字段（设置页的新建/编辑配置）；
 * [isSaving]/[isTesting] 用于禁用按钮并显示进行中态。
 * 「返回配置列表」或「取消」这类返回入口由调用方在表单外提供（两处形态不同）。
 */
@Composable
fun LlmProfileEditor(
    editApiKey: String,
    editApiBase: String,
    editModel: String,
    showApiKey: Boolean,
    testResult: String?,
    testSuccess: Boolean?,
    onUpdateApiKey: (String) -> Unit,
    onUpdateApiBase: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onToggleShowApiKey: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    editName: String? = null,
    onUpdateName: ((String) -> Unit)? = null,
    isSaving: Boolean = false,
    isTesting: Boolean = false,
    editorTitle: String? = null,
    onCancel: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (editorTitle != null) {
            LlmSectionTitle(editorTitle)
        }

        if (editName != null && onUpdateName != null) {
            OutlinedTextField(
                value = editName,
                onValueChange = onUpdateName,
                label = { Text("配置名称") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                singleLine = true
            )
        }

        // API Key
        OutlinedTextField(
            value = editApiKey,
            onValueChange = onUpdateApiKey,
            label = { Text("API Key") },
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleShowApiKey) {
                    Icon(
                        if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showApiKey) "隐藏" else "显示"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            singleLine = true
        )

        // API Base
        OutlinedTextField(
            value = editApiBase,
            onValueChange = onUpdateApiBase,
            label = { Text("API Base URL") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            singleLine = true
        )

        // Model
        OutlinedTextField(
            value = editModel,
            onValueChange = onUpdateModel,
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            singleLine = true
        )

        // 保存/测试
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                enabled = !isSaving
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                enabled = !isTesting
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text("测试连接")
            }
        }

        if (onCancel != null) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) {
                Text("取消")
            }
        }

        if (testResult != null) {
            LlmTestResultBanner(testResult = testResult, testSuccess = testSuccess)
        }
    }
}

/** 标题 + 说明 + 右侧开关的一行参数。 */
@Composable
private fun LlmSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

/** 标题 + 说明 + 右侧步进器的一行参数。 */
@Composable
private fun LlmStepperRow(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepperValueInput(
            value = value,
            range = range,
            step = step,
            onValueChange = onValueChange
        )
    }
}

/** 标题 + 说明 + 右侧步进器的一行整数参数。 */
@Composable
private fun LlmIntStepperRow(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepperValueInput(
            value = value,
            range = range,
            step = step,
            onValueChange = onValueChange
        )
    }
}

/**
 * 翻译参数区块（阅读器抽屉与设置页共用同一份文案与控件，差异只在分组分隔线与预译开关）。
 *
 * [onToggleAutoTranslateNext] 为空时不显示「提前翻译下一章」（设置页不提供该开关）。
 * [dividerBetweenGroups] 为 true 时按「长度类 / 开关类 / 采样类」插入分隔线（设置页样式）。
 */
@Composable
fun LlmTranslationParams(
    llmSettings: LlmSettings,
    onUpdateChapterMaxChars: (Int) -> Unit,
    onUpdateMaxOutputTokens: (Int) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onToggleExplainThinking: (Boolean) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateTopP: (Float) -> Unit,
    onToggleAutoTranslateNext: ((Boolean) -> Unit)? = null,
    dividerBetweenGroups: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LlmIntStepperRow(
            title = "单章字符上限",
            description = "超过该字符数的章节跳过翻译",
            value = llmSettings.chapterMaxChars,
            range = CHAPTER_MAX_CHARS_RANGE,
            step = 1000,
            onValueChange = onUpdateChapterMaxChars
        )

        LlmIntStepperRow(
            title = "最大输出Token",
            description = "模型单次翻译输出的最大 token 数",
            value = llmSettings.maxOutputTokens,
            range = MAX_OUTPUT_TOKENS_RANGE,
            step = 1024,
            onValueChange = onUpdateMaxOutputTokens
        )

        if (dividerBetweenGroups) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        LlmSwitchRow(
            title = "思考模式",
            description = "允许模型输出思考过程",
            checked = llmSettings.enableThinking,
            onCheckedChange = onToggleThinking
        )

        LlmSwitchRow(
            title = "解释时思考",
            description = "选词解释时使用深度思考模式",
            checked = llmSettings.enableExplainThinking,
            onCheckedChange = onToggleExplainThinking
        )

        if (onToggleAutoTranslateNext != null) {
            LlmSwitchRow(
                title = "提前翻译下一章",
                description = "英文阅读时自动预译未译的下一章",
                checked = llmSettings.autoTranslateNext,
                    onCheckedChange = onToggleAutoTranslateNext
            )
        }

        if (dividerBetweenGroups) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        LlmStepperRow(
            title = "采样温度",
            description = "越高输出越随机，越低越确定；建议与 top_p 二选一调整",
            value = llmSettings.temperature,
            range = TEMPERATURE_RANGE,
            step = DECIMAL_PARAM_STEP,
            onValueChange = onUpdateTemperature
        )

        LlmStepperRow(
            title = "Top P",
            description = "仅考虑前 top_p 概率的 token；建议与采样温度二选一调整",
            value = llmSettings.topP,
            range = TOP_P_RANGE,
            step = DECIMAL_PARAM_STEP,
            onValueChange = onUpdateTopP
        )
    }
}
