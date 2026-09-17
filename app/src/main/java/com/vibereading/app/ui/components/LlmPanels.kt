package com.vibereading.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

// 设计系统的控件圆角刻度（12-16dp 区间取 14）：输入框 / 按钮 / 结果条共用
private val ControlCorner = RoundedCornerShape(14.dp)

/** 连接测试结果条（成功/失败两态）。 */
@Composable
fun LlmTestResultBanner(testResult: String, testSuccess: Boolean?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            val isActive = profile.id == activeProfileId
            val itemShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(itemShape)
                    .clickable { onSelect(profile.id) }
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                        itemShape
                    )
                    // 与「我的」页行一致：未选中是扁平行，不带描边；选中才用主色描边表达状态
                    .then(
                        if (isActive) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, itemShape)
                        else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
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
                shape = ControlCorner,
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
                shape = ControlCorner,
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
                shape = ControlCorner,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                enabled = !isSaving
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = ControlCorner,
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

/** 参数行骨架：与「我的」页设置行同一套字号与行高（14sp 标题 / 12sp 说明）。 */
@Composable
private fun LlmParamRow(
    title: String,
    description: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** 标题 + 说明 + 右侧开关的一行参数（设置页「翻译与 AI」分区与阅读器弹窗一级共用）。 */
@Composable
fun LlmSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    LlmParamRow(title, description) {
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
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
    LlmParamRow(title, description) {
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
    LlmParamRow(title, description) {
        StepperValueInput(
            value = value,
            range = range,
            step = step,
            onValueChange = onValueChange
        )
    }
}

/**
 * 翻译参数区块：只承载**数值类高级参数**（单章上限 / 最大 Token / 采样温度 / Top P）。
 *
 * 层级约定：思考模式、解释时思考、提前翻译下一章这类**行为开关**由调用方放在一级
 * （设置页的「翻译与 AI」分区、阅读器弹窗的顶层），不要塞回本组件——
 * 两处的翻译参数入口都是二级，只放低频调整的数值项。
 */
@Composable
fun LlmTranslationParams(
    llmSettings: LlmSettings,
    onUpdateChapterMaxChars: (Int) -> Unit,
    onUpdateMaxOutputTokens: (Int) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateTopP: (Float) -> Unit
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
