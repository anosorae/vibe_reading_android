package com.vibereading.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings
import com.vibereading.app.ui.components.LlmProfileEditor
import com.vibereading.app.ui.components.LlmProfileList
import com.vibereading.app.ui.components.LlmTranslationParams
import com.vibereading.app.ui.theme.IndigoColors
import com.vibereading.app.ui.theme.InkColors
import com.vibereading.app.ui.theme.LotusColors
import com.vibereading.app.ui.theme.MossColors
import com.vibereading.app.ui.theme.VibeColors

@Composable
internal fun ThemeSettingsSection(
    theme: ThemeSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AppAccent) -> Unit
) {
    SettingsSectionHeader(
        title = "外观",
        icon = Icons.Filled.Palette,
        iconTint = MaterialTheme.colorScheme.primary
    )
    SectionCard {
        SettingsRowLabel("主题模式")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色"
            ).forEach { (mode, label) ->
                val selected = theme.themeMode == mode
                OutlinedButton(
                    onClick = { onThemeModeChange(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
        SettingsRowLabel("主题色")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Triple(AppAccent.INDIGO, "黛蓝", IndigoColors.Accent),
                Triple(AppAccent.MOSS, "苔绿", MossColors.Accent),
                Triple(AppAccent.VIBE, "原木", VibeColors.Sienna),
                Triple(AppAccent.LOTUS, "藕荷", LotusColors.Accent),
                Triple(AppAccent.INK, "墨白", InkColors.Accent)
            ).forEach { (accent, label, color) ->
                AccentDot(
                    label = label,
                    color = color,
                    isSelected = theme.accent == accent,
                    onClick = { onAccentChange(accent) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun LlmOverviewSection(
    state: SettingsUiState,
    onOpenLlmSettings: () -> Unit,
    onOpenTranslationParams: () -> Unit,
    onToggleExplainThinking: (Boolean) -> Unit
) {
    SettingsSectionHeader(
        title = "翻译与 AI",
        icon = Icons.Filled.Translate,
        iconTint = MaterialTheme.colorScheme.secondary
    )
    SectionCard {
        SettingsNavigationRow(
            title = "LLM 配置",
            value = state.profiles.firstOrNull { it.id == state.activeProfileId }?.model
                ?: state.llmSettings.model,
            onClick = onOpenLlmSettings
        )
        SectionDivider()
        SettingsNavigationRow(
            title = "翻译参数",
            subtitle = "单章字符上限、最大输出 Token 等",
            onClick = onOpenTranslationParams
        )
        SectionDivider()
        SettingsSwitchRow(
            title = "解释时思考",
            subtitle = "选词解释时使用深度思考模式",
            checked = state.llmSettings.enableExplainThinking,
            onCheckedChange = onToggleExplainThinking
        )
        SectionDivider()
        SettingsNavigationRow(
            title = "采样温度",
            subtitle = "越高输出越随机，建议 0.2 ~ 0.5",
            value = formatParameter(state.llmSettings.temperature),
            onClick = onOpenTranslationParams
        )
        SectionDivider()
        SettingsNavigationRow(
            title = "Top P",
            subtitle = "仅考虑前 top_p 概率的 token",
            value = formatParameter(state.llmSettings.topP),
            onClick = onOpenTranslationParams
        )
    }
}

@Composable
internal fun WebCompanionSection(
    running: Boolean,
    url: String?,
    onToggle: (Boolean) -> Unit,
    onCopyUrl: (String) -> Unit
) {
    SettingsSectionHeader(
        title = "阅读体验",
        icon = Icons.Filled.AutoStories,
        iconTint = MaterialTheme.colorScheme.tertiary
    )
    SectionCard {
        SettingsSwitchRow(
            title = "局域网网页阅读",
            subtitle = "同一 Wi-Fi 下的电脑浏览器可阅读本书库",
            checked = running,
            onCheckedChange = onToggle
        )
        if (running && url != null) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onCopyUrl(url) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "在电脑浏览器打开（点击复制）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        url,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun DebugSection(onOpenLogs: () -> Unit) {
    SettingsSectionHeader(
        title = "其他",
        icon = Icons.Filled.Settings,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SectionCard {
        SettingsNavigationRow(title = "日志", onClick = onOpenLogs)
    }
}

@Composable
internal fun LlmProfilesSection(
    state: SettingsUiState,
    editName: String,
    editApiKey: String,
    editApiBase: String,
    editModel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit,
    onCancelEdit: () -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateApiBase: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onToggleShowApiKey: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    showHeader: Boolean = true
) {
    SectionCard {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LLM 配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val rotation by animateFloatAsState(targetValue = if (expanded) 90f else 0f, label = "chevron")
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation).size(22.dp)
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            if (state.editingProfile != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCancelEdit)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回配置列表",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "返回配置列表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(8.dp))
                LlmProfileEditor(
                    editName = editName,
                    editApiKey = editApiKey,
                    editApiBase = editApiBase,
                    editModel = editModel,
                    showApiKey = state.showApiKey,
                    testResult = state.testResult,
                    testSuccess = state.testSuccess,
                    onUpdateName = onUpdateName,
                    onUpdateApiKey = onUpdateApiKey,
                    onUpdateApiBase = onUpdateApiBase,
                    onUpdateModel = onUpdateModel,
                    onToggleShowApiKey = onToggleShowApiKey,
                    onSave = onSave,
                    onTest = onTest,
                    isSaving = state.isSaving,
                    isTesting = state.isTesting,
                    editorTitle = if (state.isNewProfile) "新建配置" else "编辑配置",
                    onCancel = onCancelEdit
                )
            } else {
                LlmProfileList(
                    profiles = state.profiles,
                    activeProfileId = state.activeProfileId,
                    onSelect = onSelect,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onAdd = onAdd
                )
            }
        }
    }
}

@Composable
internal fun TranslationParamsSection(
    llmSettings: LlmSettings,
    onUpdateChapterMaxChars: (Int) -> Unit,
    onUpdateMaxOutputTokens: (Int) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onToggleExplainThinking: (Boolean) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateTopP: (Float) -> Unit
) {
    SectionCard {
        LlmTranslationParams(
            llmSettings = llmSettings,
            onUpdateChapterMaxChars = onUpdateChapterMaxChars,
            onUpdateMaxOutputTokens = onUpdateMaxOutputTokens,
            onToggleThinking = onToggleThinking,
            onToggleExplainThinking = onToggleExplainThinking,
            onUpdateTemperature = onUpdateTemperature,
            onUpdateTopP = onUpdateTopP,
            dividerBetweenGroups = true
        )
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color
) {
    Row(
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp), content = content)
    }
}

@Composable
private fun SettingsRowLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    )
}

@Composable
private fun AccentDot(
    label: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics { contentDescription = "主题色：$label" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 42.dp else 34.dp)
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                )
                .padding(if (isSelected) 4.dp else 0.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun formatParameter(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)
