package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.vibereading.app.ui.components.CHAPTER_MAX_CHARS_RANGE
import com.vibereading.app.ui.components.CONTEXT_CHAPTERS_RANGE
import com.vibereading.app.ui.components.CONTEXT_MAX_CHARS_RANGE
import com.vibereading.app.ui.components.DECIMAL_PARAM_STEP
import com.vibereading.app.ui.components.TEMPERATURE_RANGE
import com.vibereading.app.ui.components.StepperValueInput
import com.vibereading.app.ui.components.TOP_P_RANGE
import com.vibereading.app.ui.theme.VibeColors

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
    onToggleContextBoost: (Boolean) -> Unit,
    onUpdateContextChapters: (Int) -> Unit,
    onUpdateContextMaxChars: (Int) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onToggleExplainThinking: (Boolean) -> Unit,
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
            Text(
                "LLM 配置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )

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

                // API Key
                OutlinedTextField(
                    value = editApiKey,
                    onValueChange = onUpdateApiKey,
                    label = { Text("API Key") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showApiKey) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                // API Base
                OutlinedTextField(
                    value = editApiBase,
                    onValueChange = onUpdateApiBase,
                    label = { Text("API Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                // Model
                OutlinedTextField(
                    value = editModel,
                    onValueChange = onUpdateModel,
                    label = { Text("模型") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(16.dp))

                // 保存/测试
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("保存")
                    }
                    OutlinedButton(
                        onClick = onTest,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("测试连接")
                    }
                }

                // 测试结果
                if (testResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (testSuccess == true) VibeColors.SageLight else VibeColors.RedMuted.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (testSuccess == true) "✓ 连接成功" else "✗ 连接失败",
                                fontWeight = FontWeight.SemiBold,
                                color = if (testSuccess == true) VibeColors.Sage else VibeColors.RedMuted
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
            } else {
                // ── 配置列表 ──
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    profiles.forEach { profile ->
                        val isActive = profile.id == activeProfileId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSwitchProfile(profile.id) }
                                .background(
                                    if (isActive) accentColor.copy(alpha = 0.08f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .then(
                                    if (isActive) Modifier.border(1.5.dp, accentColor, RoundedCornerShape(8.dp))
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActive) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "当前使用",
                                    tint = accentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                profile.name.ifEmpty { "未命名" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                profile.model,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { onEditProfile(profile.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Edit, "编辑",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── 翻译参数 ──
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                "翻译参数",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )

            Spacer(Modifier.height(10.dp))

            // 单章字符上限：步进器微调 + 直接输入
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("单章字符上限", style = MaterialTheme.typography.bodyMedium)
                StepperValueInput(
                    value = llmSettings.chapterMaxChars,
                    range = CHAPTER_MAX_CHARS_RANGE,
                    step = 1000,
                    accentColor = accentColor,
                    onValueChange = onUpdateChapterMaxChars
                )
            }

            // 上下文增强翻译
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("上下文增强翻译", style = MaterialTheme.typography.bodyMedium)
                    Text("包含前几章英译作为上下文", fontSize = 12.sp, color = VibeColors.WarmGray)
                }
                Switch(
                    checked = llmSettings.enableContextBoost,
                    onCheckedChange = onToggleContextBoost,
                    colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                )
            }

            // 上下文增强子设置
            if (llmSettings.enableContextBoost) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("上下文章节数", style = MaterialTheme.typography.bodySmall)
                            StepperValueInput(
                                value = llmSettings.contextChapters,
                                range = CONTEXT_CHAPTERS_RANGE,
                                step = 1,
                                accentColor = accentColor,
                                fieldWidth = 44.dp,
                                onValueChange = onUpdateContextChapters
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("总字符限制", style = MaterialTheme.typography.bodySmall)
                            StepperValueInput(
                                value = llmSettings.contextMaxChars,
                                range = CONTEXT_MAX_CHARS_RANGE,
                                step = 5000,
                                accentColor = accentColor,
                                onValueChange = onUpdateContextMaxChars
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 思考模式
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("思考模式", style = MaterialTheme.typography.bodyMedium)
                    Text("允许模型输出思考过程", fontSize = 12.sp, color = VibeColors.WarmGray)
                }
                Switch(
                    checked = llmSettings.enableThinking,
                    onCheckedChange = onToggleThinking,
                    colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                )
            }

            // 解释时思考
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("解释时思考", style = MaterialTheme.typography.bodyMedium)
                    Text("选词解释时使用深度思考模式", fontSize = 12.sp, color = VibeColors.WarmGray)
                }
                Switch(
                    checked = llmSettings.enableExplainThinking,
                    onCheckedChange = onToggleExplainThinking,
                    colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                )
            }

            Spacer(Modifier.height(8.dp))

            // 采样温度
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("采样温度", style = MaterialTheme.typography.bodyMedium)
                    Text("越高输出越随机，越低越确定；建议与 top_p 二选一调整", fontSize = 12.sp, color = VibeColors.WarmGray)
                }
                StepperValueInput(
                    value = llmSettings.temperature,
                    range = TEMPERATURE_RANGE,
                    step = DECIMAL_PARAM_STEP,
                    accentColor = accentColor,
                    onValueChange = onUpdateTemperature
                )
            }

            // Top P
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Top P", style = MaterialTheme.typography.bodyMedium)
                    Text("仅考虑前 top_p 概率的 token；建议与采样温度二选一调整", fontSize = 12.sp, color = VibeColors.WarmGray)
                }
                StepperValueInput(
                    value = llmSettings.topP,
                    range = TOP_P_RANGE,
                    step = DECIMAL_PARAM_STEP,
                    accentColor = accentColor,
                    onValueChange = onUpdateTopP
                )
            }
        }
    }
}
