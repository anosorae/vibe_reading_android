package com.vibereading.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.WereadColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by vm.uiState.collectAsState()
    val editApiKey by vm.editApiKey.collectAsState()
    val editApiBase by vm.editApiBase.collectAsState()
    val editModel by vm.editModel.collectAsState()

    val accentColor = if (state.theme.accent == AppAccent.WEREAD) WereadColors.Accent else VibeColors.Sienna

    // 折叠区：翻译设置
    var llmExpanded by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── 主题设置 ──
            SectionHeader("主题设置")
            SectionCard {
                // 主题模式
                Text(
                    "主题模式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val modes = listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    modes.forEach { (mode, label) ->
                        val selected = state.theme.themeMode == mode
                        OutlinedButton(
                            onClick = { vm.updateThemeMode(mode) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) accentColor.copy(alpha = 0.1f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 配色预览：原木 / 青简
                Text(
                    "配色",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccentPreview(
                        label = "原木",
                        primary = VibeColors.Sienna,
                        bg = VibeColors.Cream,
                        selected = state.theme.accent == AppAccent.VIBE,
                        accentColor = accentColor,
                        onClick = { vm.updateAccent(AppAccent.VIBE) }
                    )
                    AccentPreview(
                        label = "青简",
                        primary = WereadColors.Accent,
                        bg = WereadColors.Cream,
                        selected = state.theme.accent == AppAccent.WEREAD,
                        accentColor = accentColor,
                        onClick = { vm.updateAccent(AppAccent.WEREAD) }
                    )
                }
            }

            // ── 阅读设置 ──
            SectionHeader("阅读设置")
            SectionCard {
                Text(
                    "字体、字号、行间距、段落间距、背景色与翻页类型以阅读器内的设置面板为唯一入口（阅读页 → 底部「设置」）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            // ── 翻译设置 ──
            SectionHeader("翻译设置")
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { llmExpanded = !llmExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LLM 配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val rotation by animateFloatAsState(
                        targetValue = if (llmExpanded) 90f else 0f,
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

                if (llmExpanded) {
                    Spacer(Modifier.height(8.dp))
                    LlmForm(
                        state = state,
                        editApiKey = editApiKey,
                        editApiBase = editApiBase,
                        editModel = editModel,
                        accentColor = accentColor,
                        onUpdateApiKey = vm::updateApiKey,
                        onUpdateApiBase = vm::updateApiBase,
                        onUpdateModel = vm::updateModel,
                        onUpdateChapterMaxChars = vm::updateChapterMaxChars,
                        onToggleContextBoost = vm::updateContextBoost,
                        onUpdateContextChapters = vm::updateContextChapters,
                        onUpdateContextMaxChars = vm::updateContextMaxChars,
                        onToggleThinking = vm::updateThinking,
                        onToggleShowApiKey = vm::toggleShowApiKey,
                        onSave = vm::saveLlmSettings,
                        onTest = vm::testConnection
                    )
                }
            }

            // ── 关于 ──
            SectionHeader("关于")
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("版本", style = MaterialTheme.typography.bodyMedium)
                    Text(com.vibereading.app.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "VibeReading —— 双语阅读器：导入 TXT 小说，逐章调用 LLM 翻译为英文，中英双模式阅读。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** 配色预览块：主色圆点 + 底色卡片 + 名称。 */
@Composable
private fun AccentPreview(
    label: String,
    primary: Color,
    bg: Color,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) accentColor.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .then(
                if (selected) Modifier.border(2.dp, accentColor, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            )
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(primary)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LlmForm(
    state: SettingsUiState,
    editApiKey: String,
    editApiBase: String,
    editModel: String,
    accentColor: Color,
    onUpdateApiKey: (String) -> Unit,
    onUpdateApiBase: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateChapterMaxChars: (Int) -> Unit,
    onToggleContextBoost: (Boolean) -> Unit,
    onUpdateContextChapters: (Int) -> Unit,
    onUpdateContextMaxChars: (Int) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onToggleShowApiKey: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit
) {
    val ls = state.llmSettings

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // API Key
        OutlinedTextField(
            value = editApiKey,
            onValueChange = onUpdateApiKey,
            label = { Text("API Key") },
            visualTransformation = if (state.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleShowApiKey) {
                    Icon(
                        if (state.showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (state.showApiKey) "隐藏" else "显示"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )

        // API Base
        OutlinedTextField(
            value = editApiBase,
            onValueChange = onUpdateApiBase,
            label = { Text("API Base URL") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )

        // Model
        OutlinedTextField(
            value = editModel,
            onValueChange = onUpdateModel,
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        )

        // Chapter max chars
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("单章字符上限", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${ls.chapterMaxChars}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )
        }
        Slider(
            value = ls.chapterMaxChars.toFloat(),
            onValueChange = { onUpdateChapterMaxChars(it.toInt()) },
            valueRange = 1000f..200000f,
            steps = 19,
            colors = SliderDefaults.colors(activeTrackColor = accentColor)
        )

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
                checked = ls.enableContextBoost,
                onCheckedChange = onToggleContextBoost,
                colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
            )
        }

        // Context boost sub-settings
        if (ls.enableContextBoost) {
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
                        Text("${ls.contextChapters}", fontWeight = FontWeight.SemiBold, color = accentColor)
                    }
                    Slider(
                        value = ls.contextChapters.toFloat(),
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
                        Text("${ls.contextMaxChars}", fontWeight = FontWeight.SemiBold, color = accentColor)
                    }
                    Slider(
                        value = ls.contextMaxChars.toFloat(),
                        onValueChange = { onUpdateContextMaxChars(it.toInt()) },
                        valueRange = 5000f..500000f,
                        colors = SliderDefaults.colors(activeTrackColor = accentColor)
                    )
                }
            }
        }

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
                checked = ls.enableThinking,
                onCheckedChange = onToggleThinking,
                colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
            )
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                enabled = !state.isSaving
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                enabled = !state.isTesting
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text("测试连接")
            }
        }

        // Test result
        if (state.testResult != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (state.testSuccess == true) VibeColors.SageLight else VibeColors.RedMuted.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (state.testSuccess == true) "✓ 连接成功" else "✗ 连接失败",
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.testSuccess == true) VibeColors.Sage else VibeColors.RedMuted
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.testResult ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
