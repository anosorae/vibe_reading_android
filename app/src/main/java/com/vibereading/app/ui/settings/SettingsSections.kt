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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    SectionCard(
        title = "外观",
        subtitle = "自定义界面显示效果",
        icon = Icons.Filled.Palette,
        iconTint = MaterialTheme.colorScheme.primary
    ) {
        // 主题模式：标签左、分段胶囊右的单行（控件不独占整行，视觉基线见设计稿）
        ThemeRow(label = "主题模式") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色"
                ).forEach { (mode, label) ->
                    val selected = theme.themeMode == mode
                    Surface(
                        onClick = { onThemeModeChange(mode) },
                        shape = RoundedCornerShape(percent = 50),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        SectionDivider()

        // 主题色：标签左、色点右的单行
        ThemeRow(label = "主题色") {
            Row(
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
                        onClick = { onAccentChange(accent) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun LlmOverviewSection(
    state: SettingsUiState,
    onOpenLlmSettings: () -> Unit,
    onOpenTranslationParams: () -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onToggleExplainThinking: (Boolean) -> Unit
) {
    SectionCard(
        title = "翻译与 AI",
        subtitle = "配置 AI 模型与翻译行为",
        icon = Icons.Filled.Translate,
        iconTint = MaterialTheme.colorScheme.secondary
    ) {
        SettingsNavigationRow(
            title = "LLM 配置",
            value = state.profiles.firstOrNull { it.id == state.activeProfileId }?.model
                ?: state.llmSettings.model,
            onClick = onOpenLlmSettings
        )
        SectionDivider()
        SettingsNavigationRow(
            title = "翻译参数",
            subtitle = "单章字符上限、最大输出 Token、采样温度等",
            onClick = onOpenTranslationParams
        )
        SectionDivider()
        SettingsSwitchRow(
            title = "思考模式",
            subtitle = "允许模型输出思考过程",
            checked = state.llmSettings.enableThinking,
            onCheckedChange = onToggleThinking
        )
        SectionDivider()
        SettingsSwitchRow(
            title = "解释时思考",
            subtitle = "选词解释时使用深度思考模式",
            checked = state.llmSettings.enableExplainThinking,
            onCheckedChange = onToggleExplainThinking
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
    SectionCard(
        title = "阅读体验",
        subtitle = "优化你的阅读使用场景",
        icon = Icons.Filled.AutoStories,
        iconTint = MaterialTheme.colorScheme.tertiary
    ) {
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
    SectionCard(
        title = "其他",
        subtitle = "应用日志等工具",
        icon = Icons.Filled.Settings,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
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
private fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            if (title != null && icon != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(iconTint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            fontSize = 19.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

/**
 * 外观卡的行骨架：标签居左，行尾控件（分段胶囊/色点）由调用方给出。
 * 刻意没有箭头：这两行的操作件就在行内，箭头是"长得像入口却点不动"的装饰（ADR-006 先例）。
 */
@Composable
private fun ThemeRow(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        content()
    }
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
            Text(title, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
                Text(
                    value,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 14.sp,
                lineHeight = 18.sp,
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
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics { contentDescription = "主题色：$label" },
        contentAlignment = Alignment.Center
    ) {
        // 选中态 = 主色描边环 + 3dp 空隙 + 色点，空隙透出卡片底色（对齐设计稿的选中环）
        Box(
            modifier = Modifier
                .size(if (isSelected) 28.dp else 20.dp)
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = CircleShape
                )
                .padding(if (isSelected) 3.dp else 0.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}
