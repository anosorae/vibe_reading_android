package com.vibereading.app.ui.reader.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.ui.theme.VibeColors

/**
 * 阅读器内翻译设置面板：API Key / Base / Model / 章节上限 / 上下文增强 / 思考模式 / 保存 / 测试连接。
 * 编辑字段由外部 ViewModel 持有，面板只负责渲染与回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsSheet(
    llmSettings: LlmSettings,
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
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }

    // 折叠区：高级选项
    var advancedExpanded by remember { mutableStateOf(false) }

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

            // ── 高级选项折叠组 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("高级选项", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val rotation by animateFloatAsState(
                    targetValue = if (advancedExpanded) 90f else 0f,
                    label = "chevron"
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .rotate(rotation)
                        .size(20.dp)
                )
            }

            if (advancedExpanded) {
                Spacer(Modifier.height(8.dp))

                // Chapter max chars
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("单章字符上限", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${llmSettings.chapterMaxChars}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }
                Slider(
                    value = llmSettings.chapterMaxChars.toFloat(),
                    onValueChange = { onUpdateChapterMaxChars(it.toInt()) },
                    valueRange = 1000f..200000f,
                    steps = 19,
                    colors = SliderDefaults.colors(activeTrackColor = accentColor)
                )

                Spacer(Modifier.height(8.dp))

                // Context boost toggle
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

                // Context boost sub-settings
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
                                Text("${llmSettings.contextChapters}", fontWeight = FontWeight.SemiBold, color = accentColor)
                            }
                            Slider(
                                value = llmSettings.contextChapters.toFloat(),
                                onValueChange = { onUpdateContextChapters(it.toInt()) },
                                valueRange = 1f..3f,
                                steps = 1,
                                colors = SliderDefaults.colors(activeTrackColor = accentColor)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("总字符限制", style = MaterialTheme.typography.bodySmall)
                                Text("${llmSettings.contextMaxChars}", fontWeight = FontWeight.SemiBold, color = accentColor)
                            }
                            Slider(
                                value = llmSettings.contextMaxChars.toFloat(),
                                onValueChange = { onUpdateContextMaxChars(it.toInt()) },
                                valueRange = 5000f..500000f,
                                colors = SliderDefaults.colors(activeTrackColor = accentColor)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Thinking mode toggle
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
            }

            Spacer(Modifier.height(20.dp))

            // Action buttons
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

            // Test result
            if (testResult != null) {
                Spacer(Modifier.height(12.dp))
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
        }
    }
}
