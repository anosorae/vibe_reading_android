package com.vibereading.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.BuildConfig
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
import com.vibereading.app.ui.theme.WereadColors

@Composable
internal fun ThemeSettingsSection(
    theme: ThemeSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AppAccent) -> Unit
) {
    SectionHeader("外观")
    SectionCard {
        Text("主题模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        val modes = listOf(
            ThemeMode.SYSTEM to "跟随系统",
            ThemeMode.LIGHT to "浅色",
            ThemeMode.DARK to "深色"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            modes.forEach { (mode, label) ->
                val selected = theme.themeMode == mode
                OutlinedButton(
                    onClick = { onThemeModeChange(mode) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
        Text("主题色", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            val palettes = listOf(
                Triple(AppAccent.INDIGO, "黛蓝", IndigoColors.Accent),
                Triple(AppAccent.MOSS, "苔绿", MossColors.Accent),
                Triple(AppAccent.VIBE, "原木", VibeColors.Sienna),
                Triple(AppAccent.LOTUS, "藕荷", LotusColors.Accent),
                Triple(AppAccent.WEREAD, "青简", WereadColors.Accent),
                Triple(AppAccent.INK, "墨白", InkColors.Accent)
            )
            palettes.forEach { (accent, label, color) ->
                AccentDot(
                    label = label,
                    color = color,
                    selected = theme.accent == accent,
                    onClick = { onAccentChange(accent) }
                )
            }
        }
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
    onTest: () -> Unit
) {
    SectionHeader("翻译与 AI")
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LLM 配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val rotation by animateFloatAsState(targetValue = if (expanded) 90f else 0f, label = "chevron")
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation).size(20.dp)
            )
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
                        contentDescription = "返回",
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
    SectionHeader("翻译参数")
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
internal fun WebCompanionSection(
    running: Boolean,
    url: String?,
    onToggle: (Boolean) -> Unit,
    onCopyUrl: (String) -> Unit
) {
    SectionHeader("阅读体验")
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("局域网网页阅读", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "同一 WiFi 下的电脑浏览器可阅读本书库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = running,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
            )
        }
        if (running && url != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "此地址已常驻通知栏，锁屏后也能查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun DebugSection(onOpenLogs: () -> Unit) {
    SectionHeader("其他")
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLogs).padding(vertical = 4.dp),
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
}

@Composable
internal fun AboutSection() {
    SectionHeader("关于")
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("版本", style = MaterialTheme.typography.bodyMedium)
            Text(
                BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "译读 —— 双语阅读器：导入 TXT 小说，逐章调用 LLM 翻译为英文，中英双模式阅读。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun AccentDot(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 38.dp else 32.dp)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                )
                .padding(if (selected) 4.dp else 0.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
