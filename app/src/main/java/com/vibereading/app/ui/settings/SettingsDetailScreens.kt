package com.vibereading.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibereading.app.BuildConfig
import com.vibereading.app.ui.components.DetailPageHeader
import com.vibereading.app.ui.components.SoftCard
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

/**
 * 二级页脚手架：大标题头部（圆形返回钮 + 标题 + 副标题）+ 纵向滚动内容，
 * 与一级页「大标题 + 白卡」的设计语言一致。
 */
@Composable
private fun SettingsDetailScaffold(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val stableInsets = LocalStableSystemBarInsets.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = stableInsets
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            DetailPageHeader(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun LlmSettingsDetailScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by vm.uiState.collectAsState()
    val editApiKey by vm.editApiKey.collectAsState()
    val editApiBase by vm.editApiBase.collectAsState()
    val editModel by vm.editModel.collectAsState()
    val editName by vm.editName.collectAsState()

    SettingsDetailScaffold(title = "LLM 配置", subtitle = "管理与切换模型档案", onBack = onBack) {
        LlmProfilesSection(
            state = state,
            editName = editName,
            editApiKey = editApiKey,
            editApiBase = editApiBase,
            editModel = editModel,
            expanded = true,
            onExpandedChange = {},
            showHeader = false,
            onSelect = vm::selectProfile,
            onEdit = vm::editProfile,
            onDelete = vm::deleteProfile,
            onAdd = vm::addProfile,
            onCancelEdit = vm::cancelEdit,
            onUpdateName = vm::updateEditName,
            onUpdateApiKey = vm::updateEditApiKey,
            onUpdateApiBase = vm::updateEditApiBase,
            onUpdateModel = vm::updateEditModel,
            onToggleShowApiKey = vm::toggleShowApiKey,
            onSave = vm::saveProfile,
            onTest = vm::testConnection
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun TranslationParamsDetailScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by vm.uiState.collectAsState()

    SettingsDetailScaffold(
        title = "翻译参数",
        subtitle = "调整章节长度、输出上限和模型采样方式",
        onBack = onBack
    ) {
        TranslationParamsSection(
            llmSettings = state.llmSettings,
            onUpdateChapterMaxChars = vm::updateChapterMaxChars,
            onUpdateMaxOutputTokens = vm::updateMaxOutputTokens,
            onUpdateTemperature = vm::updateTemperature,
            onUpdateTopP = vm::updateTopP
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    SettingsDetailScaffold(title = "关于", subtitle = "版本信息与应用介绍", onBack = onBack) {
        Spacer(Modifier.height(4.dp))
        SettingsIdentityCard()
        AboutInfoCard()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutInfoCard() {
    SoftCard(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "译读是一款双语阅读器：导入 TXT 或 EPUB 书籍，逐章调用 LLM 生成译文，在中文与英文模式之间自由切换。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
