package com.vibereading.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibereading.app.BuildConfig
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val stableInsets = LocalStableSystemBarInsets.current
    Scaffold(
        contentWindowInsets = stableInsets,
        topBar = {
            TopAppBar(
                windowInsets = stableInsets,
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        content = content
    )
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

    SettingsDetailScaffold(title = "LLM 配置", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
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
}

@Composable
internal fun TranslationParamsDetailScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by vm.uiState.collectAsState()

    SettingsDetailScaffold(title = "翻译参数", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "翻译参数",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
            )
            Text(
                "调整章节长度、输出上限和模型采样方式",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            TranslationParamsSection(
                llmSettings = state.llmSettings,
                onUpdateChapterMaxChars = vm::updateChapterMaxChars,
                onUpdateMaxOutputTokens = vm::updateMaxOutputTokens,
                onToggleThinking = vm::updateThinking,
                onToggleExplainThinking = vm::updateExplainThinking,
                onUpdateTemperature = vm::updateTemperature,
                onUpdateTopP = vm::updateTopP
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    SettingsDetailScaffold(title = "关于", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            SettingsIdentityCard()
            AboutInfoCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutInfoCard() {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("版本", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "译读是一款双语阅读器：导入 TXT 或 EPUB 书籍，逐章调用 LLM 生成译文，在中文与英文模式之间自由切换。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
