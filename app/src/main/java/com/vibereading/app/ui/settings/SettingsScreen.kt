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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibereading.app.ui.bookshelf.ShelfMetrics
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenLlmSettings: () -> Unit = {},
    onOpenTranslationParams: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val stableInsets = LocalStableSystemBarInsets.current

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

    Scaffold(
        modifier = modifier,
        contentWindowInsets = stableInsets,
        topBar = {
            if (showTopBar) {
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(if (showTopBar) 4.dp else 16.dp))
            ThemeSettingsSection(
                theme = state.theme,
                onThemeModeChange = vm::updateThemeMode,
                onAccentChange = vm::updateAccent
            )
            LlmOverviewSection(
                state = state,
                onOpenLlmSettings = onOpenLlmSettings,
                onOpenTranslationParams = onOpenTranslationParams,
                onToggleThinking = vm::updateThinking,
                onToggleExplainThinking = vm::updateExplainThinking
            )
            WebCompanionSection(
                running = state.webCompanionRunning,
                url = state.webCompanionUrl,
                onToggle = { enabled ->
                    val needNotifPermission = enabled &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needNotifPermission) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.toggleWebCompanion(enabled)
                    }
                },
                onCopyUrl = { clipboard.setText(AnnotatedString(it)) }
            )
            DebugSection(onOpenLogs = onOpenLogs)
            SettingsIdentityCard(onClick = onOpenAbout, showWaveDecoration = true)
            // AppShell 的悬浮底栏覆盖在内容上方；为最后一项预留完整的滚动安全区。
            Spacer(
                Modifier.height(
                    if (showTopBar) 28.dp
                    else ShelfMetrics.NavBarHeight + ShelfMetrics.NavBarBottomGap
                )
            )
        }
    }
}
