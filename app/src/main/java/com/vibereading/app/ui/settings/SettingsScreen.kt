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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.LlmProfile
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.WereadColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val editApiKey by vm.editApiKey.collectAsState()
    val editApiBase by vm.editApiBase.collectAsState()
    val editModel by vm.editModel.collectAsState()
    val editName by vm.editName.collectAsState()

    val accentColor = if (state.theme.accent == AppAccent.WEREAD) WereadColors.Accent else VibeColors.Sienna

    // 折叠区：翻译设置
    var llmExpanded by remember { mutableStateOf(true) }

    // 稳定系统栏 insets：沉浸式切换时不归零，防止布局跳动
    val stableInsets = LocalStableSystemBarInsets.current

    Scaffold(
        contentWindowInsets = stableInsets,
        topBar = {
            TopAppBar(
                windowInsets = stableInsets,
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
                    Spacer(Modifier.height(12.dp))

                    if (state.editingProfile != null) {
                        // ── 编辑配置二级页面 ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.cancelEdit() }
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

                        LlmProfileForm(
                            state = state,
                            editName = editName,
                            editApiKey = editApiKey,
                            editApiBase = editApiBase,
                            editModel = editModel,
                            accentColor = accentColor,
                            onUpdateName = vm::updateEditName,
                            onUpdateApiKey = vm::updateEditApiKey,
                            onUpdateApiBase = vm::updateEditApiBase,
                            onUpdateModel = vm::updateEditModel,
                            onToggleShowApiKey = vm::toggleShowApiKey,
                            onSave = vm::saveProfile,
                            onTest = vm::testConnection,
                            onCancel = vm::cancelEdit
                        )
                    } else {
                        // ── 配置列表 ──
                        ProfileList(
                            profiles = state.profiles,
                            activeProfileId = state.activeProfileId,
                            accentColor = accentColor,
                            onSelect = vm::selectProfile,
                            onEdit = vm::editProfile,
                            onDelete = vm::deleteProfile,
                            onAdd = vm::addProfile
                        )
                    }
                }
            }

            // ── 翻译参数 ──
            SectionHeader("翻译参数")
            SectionCard {
                val ls = state.llmSettings

                // 单章字符上限
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
                    onValueChange = { vm.updateChapterMaxChars(it.toInt()) },
                    valueRange = 1000f..200000f,
                    steps = 19,
                    colors = SliderDefaults.colors(activeTrackColor = accentColor)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                        checked = ls.enableContextBoost,
                        onCheckedChange = { vm.updateContextBoost(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                    )
                }

                // 上下文增强子设置
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
                                onValueChange = { vm.updateContextChapters(it.toInt()) },
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
                                onValueChange = { vm.updateContextMaxChars(it.toInt()) },
                                valueRange = 5000f..500000f,
                                colors = SliderDefaults.colors(activeTrackColor = accentColor)
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                        checked = ls.enableThinking,
                        onCheckedChange = { vm.updateThinking(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = accentColor)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                    Text(
                        String.format("%.1f", ls.temperature),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }
                Slider(
                    value = ls.temperature,
                    onValueChange = { vm.updateTemperature(it) },
                    valueRange = 0f..2f,
                    steps = 19,
                    colors = SliderDefaults.colors(activeTrackColor = accentColor)
                )

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
                    Text(
                        String.format("%.1f", ls.topP),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor
                    )
                }
                Slider(
                    value = ls.topP,
                    onValueChange = { vm.updateTopP(it) },
                    valueRange = 0f..1f,
                    steps = 9,
                    colors = SliderDefaults.colors(activeTrackColor = accentColor)
                )
            }

            // ── 调试 ──
            SectionHeader("调试")
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLogs() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("日志", style = MaterialTheme.typography.bodyMedium)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
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
                    "译读 —— 双语阅读器：导入 TXT 小说，逐章调用 LLM 翻译为英文，中英双模式阅读。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 配置档案列表：名称 + 模型，活跃配置高亮，可切换/编辑/删除 */
@Composable
private fun ProfileList(
    profiles: List<LlmProfile>,
    activeProfileId: Long?,
    accentColor: Color,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        profiles.forEach { profile ->
            val isActive = profile.id == activeProfileId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(profile.id) }
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
                // 活跃标记
                if (isActive) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前使用",
                        tint = accentColor,
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
                IconButton(
                    onClick = { onEdit(profile.id) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Edit, "编辑", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 删除（仅多配置时）
                if (profiles.size > 1) {
                    IconButton(
                        onClick = { onDelete(profile.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    }
                }
            }
        }

        // 添加按钮
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加配置")
        }
    }
}

/** 配置编辑表单：名称 + API Key / Base / Model + 保存/测试 */
@Composable
private fun LlmProfileForm(
    state: SettingsUiState,
    editName: String,
    editApiKey: String,
    editApiBase: String,
    editModel: String,
    accentColor: Color,
    onUpdateName: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateApiBase: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onToggleShowApiKey: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onCancel: () -> Unit
) {
    val isnew = state.isNewProfile

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 标题
        Text(
            if (isnew) "新建配置" else "编辑配置",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accentColor
        )

        // 配置名称
        OutlinedTextField(
            value = editName,
            onValueChange = onUpdateName,
            label = { Text("配置名称") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

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
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        // API Base
        OutlinedTextField(
            value = editApiBase,
            onValueChange = onUpdateApiBase,
            label = { Text("API Base URL") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        // Model
        OutlinedTextField(
            value = editModel,
            onValueChange = onUpdateModel,
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

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

        // Cancel button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("取消")
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
