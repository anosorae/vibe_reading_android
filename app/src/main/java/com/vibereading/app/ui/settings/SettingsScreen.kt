package com.vibereading.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var llmExpanded by remember { mutableStateOf(true) }

    // 伴读是前台服务，靠常驻通知展示含 Token 的地址、也靠它把服务锁在前台。
    // Android 13+ 必须先拿到通知权限，否则服务照跑但通知栏里什么都看不到。
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.toggleWebCompanion(true)
        if (!granted) {
            Toast.makeText(
                context,
                "未授予通知权限：伴读会照常运行，但通知栏不会显示常驻地址",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
            ThemeSettingsSection(
                theme = state.theme,
                onThemeModeChange = vm::updateThemeMode,
                onAccentChange = vm::updateAccent
            )
            LlmProfilesSection(
                state = state,
                editName = editName,
                editApiKey = editApiKey,
                editApiBase = editApiBase,
                editModel = editModel,
                expanded = llmExpanded,
                onExpandedChange = { llmExpanded = it },
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
            TranslationParamsSection(
                llmSettings = state.llmSettings,
                onUpdateChapterMaxChars = vm::updateChapterMaxChars,
                onUpdateMaxOutputTokens = vm::updateMaxOutputTokens,
                onToggleThinking = vm::updateThinking,
                onToggleExplainThinking = vm::updateExplainThinking,
                onUpdateTemperature = vm::updateTemperature,
                onUpdateTopP = vm::updateTopP
            )
            WebCompanionSection(
                running = state.webCompanionRunning,
                url = state.webCompanionUrl,
                onToggle = { on ->
                    val needNotifPermission = on &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    // 先要权限再启服务：授权回调里无论通过与否都会启动，避免开关
                    // 打开却没反应；拒绝时给出提示而不是静默失败。
                    if (needNotifPermission) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.toggleWebCompanion(on)
                    }
                },
                onCopyUrl = { clipboard.setText(AnnotatedString(it)) }
            )
            DebugSection(onOpenLogs = onOpenLogs)
            AboutSection()
            Spacer(Modifier.height(32.dp))
        }
    }
}
